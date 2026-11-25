package app.arquidraw.backend.service;

import app.arquidraw.backend.model.DiagramConnection;
import app.arquidraw.backend.model.DiagramData;
import app.arquidraw.backend.model.DiagramNode;
import app.arquidraw.backend.model.DiagramPortal;
import app.arquidraw.backend.model.NodePreset;
import app.arquidraw.backend.model.NodePresets;
import app.arquidraw.backend.model.Point;
import app.arquidraw.backend.model.PortRef;
import app.arquidraw.backend.model.ViewRequest;
import app.arquidraw.backend.model.Viewport;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Logica de negocio del diagrama activo de una sala (tenant).
 *
 * Se crea una instancia por sala desde {@link RoomManager}; la sala va en los
 * eventos para que el WebSocket solo avise a los clientes de esa misma sala.
 *
 * Todas las mutaciones pasan por {@link #replace}: guardan el estado nuevo,
 * suben la revision y publican un {@link DiagramChangedEvent} que el WebSocket
 * reenvia a los editores conectados. Asi, cualquier operacion hecha por un
 * agente via MCP se ve en vivo en la UI (y viceversa).
 *
 * Varias operaciones informan ademas que ids "tocaron" (highlight) para que el
 * editor los resalte un instante y el usuario vea al agente trabajando.
 */
public class DiagramService {

    public static final String ORIGIN_MCP = "mcp";
    public static final String ORIGIN_SERVER = "server";

    /** Direcciones validas para {@link #align}. */
    private static final Set<String> ALIGN_DIRECTIONS =
            Set.of("left", "right", "top", "bottom", "centerX", "centerY");

    /** Limite del Markdown interno de un nodo (100 KB). */
    public static final int MAX_DOC_LENGTH = 100_000;

    /** Limite de la URL externa de documentacion. */
    private static final int MAX_DOC_URL_LENGTH = 2048;

    /** Limites del historial y de los snapshots (viven en memoria por sala). */
    private static final int MAX_HISTORY = 50;
    private static final int MAX_MANUAL_SNAPSHOTS = 20;
    private static final int MAX_AUTO_SNAPSHOTS = 10;

    /** Metadatos de un snapshot, sin el diagrama completo. */
    public record SnapshotInfo(String id, String label, long revision, String createdAt, String origin,
            boolean auto, int nodes, int connections, int levels) {
    }

    /** Cambio deshecho, con la revision y el autor que lo produjo. */
    public record UndoEntry(long revision, String origin, String createdAt) {
    }

    /** Resultado de deshacer uno o varios cambios. */
    public record UndoResult(DiagramData data, long revision, int steps, List<UndoEntry> undone) {
    }

    /** Resultado de restaurar un snapshot. */
    public record RestoreResult(SnapshotInfo snapshot, DiagramData data) {
    }

    private record HistoryEntry(DiagramData data, long revision, String origin, String createdAt) {
    }

    private record SnapshotEntry(SnapshotInfo info, DiagramData data) {
    }

    private final DiagramRepository repository;
    private final ApplicationEventPublisher events;
    private final String room;
    private final AtomicLong revision = new AtomicLong();
    private final Deque<HistoryEntry> undoStack = new ArrayDeque<>();
    private final List<SnapshotEntry> snapshots = new ArrayList<>();

    /**
     * Ultimo viewport reportado por un editor (tamano, pan y zoom). No forma
     * parte del diagrama ni sube revision: solo lo consultan los agentes para
     * saber que region del lienzo esta viendo la persona.
     */
    private volatile Viewport viewport;

    public DiagramService(DiagramRepository repository, ApplicationEventPublisher events, String room) {
        this.repository = repository;
        this.events = events;
        this.room = room;
    }

    /** Sala (tenant) de este diagrama. */
    public String room() {
        return room;
    }

    public DiagramData current() {
        return repository.load();
    }

    public long revision() {
        return revision.get();
    }

    /**
     * Ejecuta una mutacion comprobando antes la revision esperada y manteniendo
     * el bloqueo hasta terminarla, para que la comprobacion y el cambio sean
     * atomicos frente a otro escritor.
     */
    public synchronized <T> T withExpectedRevision(Long expectedRevision, Supplier<T> action) {
        checkRevision(expectedRevision);
        return action.get();
    }

    public synchronized void withExpectedRevision(Long expectedRevision, Runnable action) {
        checkRevision(expectedRevision);
        action.run();
    }

    /** Ultimo viewport reportado por un editor, o null si ninguno se ha reportado. */
    public Viewport viewport() {
        return viewport;
    }

    /** Guarda el viewport reportado por el editor (no notifica a nadie). */
    public void setViewport(Viewport next) {
        this.viewport = next;
    }

    /** Reemplaza el diagrama completo y notifica a todos (sin resaltado). */
    public synchronized DiagramData replace(DiagramData data, String origin) {
        return replace(data, origin, List.of());
    }

    /** Reemplaza el diagrama completo y notifica a todos, resaltando ciertos ids. */
    public synchronized DiagramData replace(DiagramData data, String origin, List<String> highlight) {
        String author = origin == null ? ORIGIN_SERVER : origin;
        DiagramData current = repository.load();
        DiagramData next = data == null ? DiagramData.empty() : data;
        // La primera carga que llena un diagrama vacio se trata como estado
        // inicial: no se puede "deshacer" a una sala vacia por accidente.
        if (hasContent(current) || !hasContent(next)) {
            if (undoStack.size() >= MAX_HISTORY) {
                undoStack.removeFirst();
            }
            undoStack.addLast(new HistoryEntry(current, revision.get() + 1, author,
                    Instant.now().toString()));
        }
        return apply(next, author, highlight);
    }

    /** Guarda y publica un estado sin registrarlo en el historial (para undo). */
    private synchronized DiagramData apply(DiagramData data, String origin, List<String> highlight) {
        DiagramData next = data == null ? DiagramData.empty() : data;
        repository.save(next);
        long rev = revision.incrementAndGet();
        events.publishEvent(new DiagramChangedEvent(next, rev,
                origin == null ? ORIGIN_SERVER : origin, highlight == null ? List.of() : highlight, room));
        return next;
    }

    // ------------------------------------------------------------------ historial y snapshots

    /** Crea un snapshot manual con etiqueta. */
    public synchronized SnapshotInfo snapshot(String origin, String label) {
        return saveSnapshot(origin, label, false);
    }

    /**
     * Snapshot automatico antes de una operacion destructiva. Si el diagrama
     * esta vacio no se guarda nada (no aporta nada y ocupa memoria).
     */
    public synchronized SnapshotInfo snapshotAuto(String origin, String label) {
        if (!hasContent(repository.load())) {
            return null;
        }
        return saveSnapshot(origin, label, true);
    }

    /** Snapshots de la sala, del mas reciente al mas antiguo. */
    public synchronized List<SnapshotInfo> snapshots() {
        List<SnapshotInfo> out = new ArrayList<>(snapshots.size());
        for (int i = snapshots.size() - 1; i >= 0; i--) {
            out.add(snapshots.get(i).info());
        }
        return List.copyOf(out);
    }

    /** Restaura un snapshot; el cambio en si tambien se puede deshacer. */
    public synchronized RestoreResult restore(String origin, String snapshotId) {
        SnapshotEntry entry = findSnapshot(snapshotId);
        if (entry == null) {
            throw new NoSuchElementException("No existe el snapshot: " + snapshotId);
        }
        DiagramData data = replace(entry.data(), origin);
        return new RestoreResult(entry.info(), data);
    }

    /**
     * Deshace los ultimos cambios. Usa una pila de estados previos, no numeros
     * de revision, para que un segundo undo siga retrocediendo y no vuelva hacia
     * delante. Devuelve el autor y la revision de cada cambio deshecho.
     */
    public synchronized UndoResult undo(String origin, int steps) {
        if (steps < 1) {
            throw new IllegalArgumentException("steps debe ser >= 1.");
        }
        if (undoStack.isEmpty()) {
            throw new IllegalStateException("No hay cambios para deshacer.");
        }
        if (steps > undoStack.size()) {
            throw new IllegalArgumentException("Solo hay " + undoStack.size()
                    + " cambios para deshacer.");
        }
        List<UndoEntry> undone = new ArrayList<>(steps);
        HistoryEntry target = null;
        for (int i = 0; i < steps; i++) {
            HistoryEntry entry = undoStack.removeLast();
            undone.add(new UndoEntry(entry.revision(), entry.origin(), entry.createdAt()));
            target = entry;
        }
        Collections.reverse(undone);
        DiagramData data = apply(target.data(), origin, List.of());
        return new UndoResult(data, revision.get(), steps, List.copyOf(undone));
    }

    private SnapshotInfo saveSnapshot(String origin, String label, boolean auto) {
        if (auto) {
            while (countAutoSnapshots() >= MAX_AUTO_SNAPSHOTS && removeOldestAuto()) {
                // Poda el automatico mas antiguo para dejar sitio al nuevo.
            }
        } else if (countManualSnapshots() >= MAX_MANUAL_SNAPSHOTS) {
            throw new IllegalArgumentException("Limite de snapshots manuales alcanzado ("
                    + MAX_MANUAL_SNAPSHOTS + "). Reinicia la sesion para limpiar el historial.");
        }
        DiagramData data = repository.load();
        int[] counts = countTree(data);
        String cleanLabel = label == null || label.isBlank()
                ? (auto ? "Automatico" : "Manual") : label.trim();
        SnapshotInfo info = new SnapshotInfo(newSnapshotId(), cleanLabel, revision.get(),
                Instant.now().toString(), origin == null ? ORIGIN_SERVER : origin, auto, counts[0],
                counts[1], counts[2]);
        snapshots.add(new SnapshotEntry(info, data));
        return info;
    }

    private SnapshotEntry findSnapshot(String id) {
        if (id == null) {
            return null;
        }
        for (SnapshotEntry entry : snapshots) {
            if (entry.info().id().equals(id)) {
                return entry;
            }
        }
        return null;
    }

    private String newSnapshotId() {
        String id;
        do {
            id = "s_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        } while (findSnapshot(id) != null);
        return id;
    }

    private int countAutoSnapshots() {
        int count = 0;
        for (SnapshotEntry entry : snapshots) {
            if (entry.info().auto()) {
                count++;
            }
        }
        return count;
    }

    private int countManualSnapshots() {
        int count = 0;
        for (SnapshotEntry entry : snapshots) {
            if (!entry.info().auto()) {
                count++;
            }
        }
        return count;
    }

    private boolean removeOldestAuto() {
        for (int i = 0; i < snapshots.size(); i++) {
            if (snapshots.get(i).info().auto()) {
                snapshots.remove(i);
                return true;
            }
        }
        return false;
    }

    private static boolean hasContent(DiagramData data) {
        return !data.nodes().isEmpty() || !data.connections().isEmpty() || !data.levels().isEmpty();
    }

    private static int[] countTree(DiagramData data) {
        int[] counts = new int[3];
        collectCounts(data, counts);
        return counts;
    }

    private static void collectCounts(DiagramData data, int[] counts) {
        counts[0] += data.nodes().size();
        counts[1] += data.connections().size();
        counts[2] += data.levels().size();
        for (DiagramData child : data.levels().values()) {
            collectCounts(child, counts);
        }
    }

    /** Resalta ids sin cambiar el diagrama (para que el usuario vea que "mira" el agente). */
    /**
     * Pide al editor conectado cambiar su vista (zoom/centro) sin tocar el diagrama.
     * Sin zoom, el editor encuadra todo el contenido.
     */
    public void setView(String origin, Double zoom, Double centerX, Double centerY) {
        if (zoom != null && (zoom < 0.15 || zoom > 4)) {
            throw new IllegalArgumentException("zoom fuera de rango (0.15 a 4): " + zoom);
        }
        events.publishEvent(new DiagramViewEvent(new ViewRequest(zoom, centerX, centerY),
                origin == null ? ORIGIN_SERVER : origin, room));
    }

    public synchronized void focus(String origin, List<String> ids) {
        List<String> clean = ids == null ? List.of() : ids;
        events.publishEvent(new DiagramFocusEvent(clean, origin == null ? ORIGIN_SERVER : origin, room));
    }

    /** Resalta ids de un nivel concreto (la ruta se valida antes de publicar). */
    public synchronized void focus(String origin, List<String> path, List<String> ids) {
        if (path != null) {
            resolveLevel(current(), path);
        }
        focus(origin, ids);
    }

    private synchronized DiagramData mutate(String origin, Function<DiagramData, DiagramData> fn) {
        return mutate(origin, fn, List.of());
    }

    private synchronized DiagramData mutate(String origin, Function<DiagramData, DiagramData> fn,
            List<String> highlight) {
        return replace(fn.apply(repository.load()), origin, highlight);
    }

    // ------------------------------------------------------------------ diagrama

    public DiagramData rename(String origin, String name) {
        return mutate(origin, d -> copy(d).name(name).build());
    }

    public DiagramData setBackground(String origin, String background) {
        return mutate(origin, d -> copy(d).background(background).build());
    }

    public DiagramData setGridSize(String origin, Double gridSize) {
        return mutate(origin, d -> copy(d).gridSize(gridSize == null ? d.gridSize() : gridSize).build());
    }

    public DiagramData clear(String origin) {
        return mutate(origin, d -> copy(d).nodes(List.of()).connections(List.of()).levels(Map.of()).build());
    }

    /**
     * Importa un diagrama completo. Con {@code merge} hace upsert por id (nodos,
     * conexiones y niveles) sin borrar lo que no venga en el JSON; sin merge
     * reemplaza todo el diagrama, como la importacion clasica.
     */
    public synchronized DiagramData importDiagram(String origin, DiagramData incoming, boolean merge) {
        DiagramData next = merge
                ? DiagramMerger.merge(repository.load(), incoming)
                : (incoming == null ? DiagramData.empty() : incoming);
        List<String> highlight = merge && incoming != null ? DiagramMerger.ids(incoming) : List.of();
        return replace(next, origin, highlight);
    }

    /**
     * Valida el arbol completo y, con {@code fix}, corrige los problemas que
     * se pueden resolver sin perder datos. Solo sube la revision si algo cambia.
     */
    public synchronized DiagramValidator.Report validate(String origin, boolean fix,
            Long expectedRevision) {
        checkRevision(expectedRevision);
        DiagramData current = repository.load();
        List<DiagramValidator.Issue> before = DiagramValidator.validate(current);
        if (fix && before.stream().anyMatch(DiagramValidator.Issue::fixable)) {
            DiagramData fixed = DiagramValidator.fix(current);
            if (!fixed.equals(current)) {
                replace(fixed, origin);
            }
        }
        List<DiagramValidator.Issue> remaining = DiagramValidator.validate(repository.load());
        int fixedCount = Math.max(0, before.size() - remaining.size());
        return new DiagramValidator.Report(remaining.isEmpty(), fixedCount, remaining);
    }

    // ------------------------------------------------------------------ nodos

    public DiagramNode addNode(String origin, List<String> path, String label, String sublabel,
            String technology, String summary, String presetKey, Double x, Double y, Double width,
            Double height, String shape, String icon, String fill, String stroke, String textColor,
            List<String> ports, String docUrl, String doc) {
        return addNode(origin, path, null, label, sublabel, technology, summary, presetKey, x, y,
                width, height, shape, icon, fill, stroke, textColor, ports, docUrl, doc);
    }

    /**
     * Igual que {@link #addNode(String, List, String, String, String, String, String, Double,
     * Double, Double, Double, String, String, String, String, String, List, String, String)} pero
     * permite fijar el id (util para lotes atomicos que luego conectan los nodos creados).
     */
    public DiagramNode addNode(String origin, List<String> path, String id, String label,
            String sublabel, String technology, String summary, String presetKey, Double x, Double y,
            Double width, Double height, String shape, String icon, String fill, String stroke,
            String textColor, List<String> ports, String docUrl, String doc) {

        DiagramData d = repository.load();
        DiagramData target = resolveLevel(d, path);
        NodePreset preset = NodePresets.find(presetKey);
        if (preset == null) {
            preset = NodePresets.defaultPreset();
        }

        String nodeId = id == null || id.isBlank() ? newId("n") : id.trim();
        if (findNode(d, nodeId) != null) {
            throw new IllegalArgumentException("Ya existe un nodo con id: " + nodeId);
        }
        double[] pos = autoPosition(target, x, y);
        double w = width != null ? width : preset.width();
        double h = height != null ? height : preset.height();
        String sh = shape != null ? shape : preset.shape();
        String ic = icon != null ? icon : preset.icon();
        String fi = fill != null ? fill : preset.fill();
        String st = stroke != null ? stroke : preset.stroke();
        String tc = textColor != null ? textColor : preset.textColor();
        List<String> p = ports != null ? ports : List.of("top", "right", "bottom", "left");

        DiagramNode node = new DiagramNode(nodeId, pos[0], pos[1], w, h,
                (label == null || label.isBlank()) ? preset.label() : label,
                sublabel == null ? "" : sublabel,
                technology == null ? "" : technology,
                summary == null ? "" : summary,
                normalizeDocUrl(docUrl),
                normalizeDoc(doc),
                sh, fi, st, tc, ic, p, null, null);

        mutate(origin, root -> updateLevelAt(root, path,
                level -> copy(level).nodes(append(level.nodes(), node)).build()), List.of(nodeId));
        return node;
    }

    public DiagramNode updateNode(String origin, List<String> path, String id, String label,
            String sublabel, String technology, String summary, Double x, Double y, Double width,
            Double height, String shape, String icon, String fill, String stroke, String textColor,
            List<String> ports, String docUrl, String doc) {

        return patchNode(origin, path, id, label, sublabel, technology, summary, x, y, width,
                height, shape, icon, fill, stroke, textColor, ports, docUrl, doc);
    }

    /** Aplica el mismo patch a varios nodos a la vez (estilo/serie). */
    public List<DiagramNode> updateNodes(String origin, List<String> path, List<String> ids, String label,
            String sublabel, String technology, String summary, Double x, Double y, Double width,
            Double height, String shape, String icon, String fill, String stroke, String textColor,
            List<String> ports, String docUrl, String doc) {

        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("Indica al menos un id.");
        }
        List<DiagramNode> result = new ArrayList<>();
        for (String id : ids) {
            result.add(patchNode(origin, path, id, label, sublabel, technology, summary, x, y, width,
                    height, shape, icon, fill, stroke, textColor, ports, docUrl, doc));
        }
        return result;
    }

    /** Busca un nodo en la raiz o en cualquier nivel interno. */
    public DiagramNode findNode(String id) {
        return findNode(repository.load(), id);
    }

    private static DiagramNode findNode(DiagramData level, String id) {
        for (DiagramNode node : level.nodes()) {
            if (node.id().equals(id)) {
                return node;
            }
        }
        for (DiagramData child : level.levels().values()) {
            DiagramNode found = findNode(child, id);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static DiagramConnection findConnection(DiagramData level, String id) {
        DiagramConnection connection = level.connection(id);
        if (connection != null) {
            return connection;
        }
        for (DiagramData child : level.levels().values()) {
            DiagramConnection found = findConnection(child, id);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** Reemplaza el Markdown interno de un nodo (en cualquier nivel). */
    public DiagramNode setDoc(String origin, String id, String doc) {
        return patchDocumentation(origin, id, null, doc == null ? "" : doc, false);
    }

    /** Anade Markdown al final de la documentacion interna de un nodo. */
    public DiagramNode appendDoc(String origin, String id, String doc) {
        return patchDocumentation(origin, id, null, doc == null ? "" : doc, true);
    }

    /** Cambia la URL externa de documentacion (vacio la elimina). */
    public DiagramNode setDocUrl(String origin, String id, String docUrl) {
        return patchDocumentation(origin, id, docUrl == null ? "" : docUrl, null, false);
    }

    /** Elimina la URL externa y el Markdown interno de un nodo. */
    public DiagramNode clearDoc(String origin, String id) {
        return patchDocumentation(origin, id, "", "", false);
    }

    /** Busca un nodo en el nivel indicado por la ruta (vacia/null = raiz). */
    public DiagramNode nodeAt(List<String> path, String id) {
        DiagramData level = resolveLevel(repository.load(), path);
        DiagramNode node = level.node(id);
        if (node == null) {
            throw new NoSuchElementException("No existe el nodo: " + id);
        }
        return node;
    }

    /** Reemplaza la ficha de un nodo de un nivel concreto. */
    public DiagramNode updateDoc(String origin, List<String> path, String id, String markdown,
            Long expectedRevision) {
        return withExpectedRevision(expectedRevision, () -> patchDocumentationAt(origin, path, id, null,
                markdown == null ? "" : markdown, false));
    }

    /**
     * Reemplaza (o anade, con mode=append) el contenido de la seccion
     * "## <section>" de la ficha. Las demas secciones no se tocan.
     */
    public DiagramNode patchDoc(String origin, List<String> path, String id, String section,
            String markdown, String mode, Long expectedRevision) {
        return withExpectedRevision(expectedRevision, () -> {
            DiagramNode existing = nodeAt(path, id);
            String next = patchSection(existing.doc(), section, markdown, mode);
            return patchDocumentationAt(origin, path, id, null, next, false);
        });
    }

    private DiagramNode patchDocumentationAt(String origin, List<String> path, String id,
            String docUrl, String doc, boolean append) {
        DiagramData level = resolveLevel(repository.load(), path);
        DiagramNode existing = level.node(id);
        if (existing == null) {
            throw new NoSuchElementException("No existe el nodo: " + id);
        }
        String nextUrl = docUrl != null ? normalizeDocUrl(docUrl) : existing.docUrl();
        String nextDoc;
        if (doc == null) {
            nextDoc = existing.doc();
        } else if (append) {
            nextDoc = existing.doc().isBlank() ? doc : existing.doc() + "\n\n" + doc;
        } else {
            nextDoc = doc;
        }
        nextDoc = normalizeDoc(nextDoc);
        DiagramNode updated = existing.withDocumentation(nextUrl, nextDoc);
        mutate(origin, root -> updateLevelAt(root, path,
                lvl -> copy(lvl).nodes(replaceNodeList(lvl.nodes(), updated)).build()), List.of(id));
        return updated;
    }

    private void checkRevision(Long expectedRevision) {
        if (expectedRevision != null && expectedRevision.longValue() != revision.get()) {
            throw new RevisionConflictException(expectedRevision, revision.get());
        }
    }

    /**
     * Sustituye el contenido de una seccion nivel 2. Conserva el titulo y el
     * texto de las demas secciones; si no existe, la crea al final.
     */
    private static String patchSection(String doc, String section, String markdown, String mode) {
        String title = normalizeSectionTitle(section);
        if (title.isEmpty()) {
            throw new IllegalArgumentException("Indica la seccion que quieres modificar.");
        }
        String content = markdown == null ? "" : markdown;
        boolean append = "append".equalsIgnoreCase(mode == null ? "" : mode.trim());
        if (mode != null && !mode.isBlank() && !append && !"replace".equalsIgnoreCase(mode.trim())) {
            throw new IllegalArgumentException("Modo invalido: " + mode + ". Usa replace o append.");
        }

        String source = doc == null ? "" : doc;
        Matcher headings = Pattern.compile("(?m)^##[ \t]+([^\r\n]+?)[ \t]*$").matcher(source);
        int contentStart = -1;
        int contentEnd = -1;
        while (headings.find()) {
            if (!normalizeHeadingTitle(headings.group(1)).equalsIgnoreCase(title)) {
                continue;
            }
            contentStart = headings.end();
            if (contentStart < source.length() && source.charAt(contentStart) == '\r') {
                contentStart++;
            }
            if (contentStart < source.length() && source.charAt(contentStart) == '\n') {
                contentStart++;
            }
            Matcher next = Pattern.compile("(?m)^#{1,2}[ \t]+").matcher(source);
            contentEnd = next.find(contentStart) ? next.start() : source.length();
            break;
        }

        if (contentStart < 0) {
            String base = source;
            if (!base.isEmpty() && !base.endsWith("\n")) {
                base += "\n";
            }
            if (!base.isEmpty() && !base.endsWith("\n\n")) {
                base += "\n";
            }
            return base + "## " + title + "\n" + content
                    + (content.endsWith("\n") ? "" : "\n");
        }

        String prefix = source.substring(0, contentStart);
        String suffix = source.substring(contentEnd);
        String existingContent = source.substring(contentStart, contentEnd);
        String trailing = trailingLineBreaks(existingContent);
        String nextContent;
        if (append) {
            nextContent = existingContent;
            if (!nextContent.isEmpty() && !nextContent.endsWith("\n")) {
                nextContent += "\n";
            }
            if (!nextContent.isEmpty() && !nextContent.endsWith("\n\n")) {
                nextContent += "\n";
            }
            nextContent += content;
        } else if (!trailing.isEmpty()) {
            nextContent = content.replaceFirst("[\\r\\n]+$", "") + trailing;
        } else {
            nextContent = content;
        }
        if (!nextContent.isEmpty() && !nextContent.endsWith("\n")) {
            nextContent += "\n";
        }
        return prefix + nextContent + suffix;
    }

    private static String trailingLineBreaks(String value) {
        int i = value.length();
        while (i > 0 && (value.charAt(i - 1) == '\n' || value.charAt(i - 1) == '\r')) {
            i--;
        }
        return value.substring(i);
    }

    private static String normalizeSectionTitle(String raw) {
        String title = raw == null ? "" : raw.trim();
        title = title.replaceFirst("^#{1,6}\\s*", "");
        title = title.replaceFirst("\\s*#+\\s*$", "");
        return title.trim();
    }

    private static String normalizeHeadingTitle(String raw) {
        String title = raw == null ? "" : raw.trim();
        title = title.replaceFirst("\\s*#+\\s*$", "");
        return title.trim();
    }

    private static String normalizeDoc(String doc) {
        String value = doc == null ? "" : doc;
        if (value.length() > MAX_DOC_LENGTH) {
            throw new IllegalArgumentException(
                    "La documentacion supera el limite de " + MAX_DOC_LENGTH + " caracteres.");
        }
        return value;
    }

    private DiagramNode patchDocumentation(String origin, String id, String docUrl, String doc,
            boolean append) {
        DiagramNode existing = findNode(id);
        if (existing == null) {
            throw new NoSuchElementException("No existe el nodo: " + id);
        }
        String nextUrl = docUrl != null ? normalizeDocUrl(docUrl) : existing.docUrl();
        String nextDoc;
        if (doc == null) {
            nextDoc = existing.doc();
        } else if (append) {
            nextDoc = existing.doc().isBlank() ? doc : existing.doc() + "\n\n" + doc;
        } else {
            nextDoc = doc;
        }
        if (nextDoc.length() > MAX_DOC_LENGTH) {
            throw new IllegalArgumentException(
                    "La documentacion supera el limite de " + MAX_DOC_LENGTH + " caracteres.");
        }
        DiagramNode updated = existing.withDocumentation(nextUrl, nextDoc);
        mutate(origin, diag -> replaceNodeDeep(diag, updated), List.of(id));
        return updated;
    }

    private static String normalizeDocUrl(String raw) {
        String url = raw == null ? "" : raw.trim();
        if (url.isEmpty()) {
            return "";
        }
        if (url.length() > MAX_DOC_URL_LENGTH) {
            throw new IllegalArgumentException("La URL de documentacion es demasiado larga.");
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("URL de documentacion invalida: " + url);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("La URL de documentacion debe usar http o https.");
        }
        return url;
    }

    /** Reemplaza un nodo en cualquier nivel interno (mismo id). */
    private static DiagramData replaceNodeDeep(DiagramData level, DiagramNode updated) {
        List<DiagramNode> nodes = new ArrayList<>(level.nodes().size());
        for (DiagramNode node : level.nodes()) {
            nodes.add(node.id().equals(updated.id()) ? updated : node);
        }
        Map<String, DiagramData> levels = new HashMap<>(level.levels());
        for (Map.Entry<String, DiagramData> entry : level.levels().entrySet()) {
            levels.put(entry.getKey(), replaceNodeDeep(entry.getValue(), updated));
        }
        return copy(level).nodes(nodes).levels(levels).build();
    }

    private DiagramNode patchNode(String origin, List<String> path, String id, String label,
            String sublabel, String technology, String summary, Double x, Double y, Double width,
            Double height, String shape, String icon, String fill, String stroke, String textColor,
            List<String> ports, String docUrl, String doc) {

        DiagramData d = repository.load();
        DiagramData level = resolveLevel(d, path);
        DiagramNode existing = level.node(id);
        if (existing == null) {
            throw new NoSuchElementException("No existe el nodo: " + id);
        }
        DiagramNode updated = new DiagramNode(
                existing.id(),
                x != null ? x : existing.x(),
                y != null ? y : existing.y(),
                width != null ? width : existing.width(),
                height != null ? height : existing.height(),
                label != null ? label : existing.label(),
                sublabel != null ? sublabel : existing.sublabel(),
                technology != null ? technology : existing.technology(),
                summary != null ? summary : existing.summary(),
                docUrl != null ? normalizeDocUrl(docUrl) : existing.docUrl(),
                doc != null ? normalizeDoc(doc) : existing.doc(),
                shape != null ? shape : existing.shape(),
                fill != null ? fill : existing.fill(),
                stroke != null ? stroke : existing.stroke(),
                textColor != null ? textColor : existing.textColor(),
                icon != null ? icon : existing.icon(),
                ports != null ? ports : existing.ports(),
                existing.childLevelId(),
                existing.portal());

        mutate(origin, root -> updateLevelAt(root, path,
                lvl -> copy(lvl).nodes(replaceNodeList(lvl.nodes(), updated)).build()), List.of(id));
        return updated;
    }

    public DiagramNode moveNode(String origin, String id, double x, double y) {
        return updateNode(origin, List.of(), id, null, null, null, null, x, y, null, null, null,
                null, null, null, null, null, null, null);
    }

    public DiagramNode resizeNode(String origin, String id, double width, double height) {
        return updateNode(origin, List.of(), id, null, null, null, null, null, null, width, height,
                null, null, null, null, null, null, null, null);
    }

    /** Duplica un nodo (nuevo id) desplazado un offset. */
    public DiagramNode duplicateNode(String origin, String id, Double offsetX, Double offsetY) {
        DiagramData d = repository.load();
        DiagramNode existing = d.node(id);
        if (existing == null) {
            throw new NoSuchElementException("No existe el nodo: " + id);
        }
        double dx = offsetX != null ? offsetX : 30;
        double dy = offsetY != null ? offsetY : 30;
        DiagramNode clone = new DiagramNode(newId("n"), existing.x() + dx, existing.y() + dy,
                existing.width(), existing.height(), existing.label(), existing.sublabel(),
                existing.technology(), existing.summary(), existing.docUrl(), existing.doc(),
                existing.shape(), existing.fill(), existing.stroke(), existing.textColor(),
                existing.icon(), existing.ports(), null, existing.portal());
        Map<String, DiagramData> levels = new HashMap<>(d.levels());
        if (existing.childLevelId() != null && !existing.childLevelId().isBlank()) {
            DiagramData child = d.level(existing.childLevelId());
            if (child != null) {
                String childId = newId("lv");
                clone = clone.withChildLevel(childId);
                levels.put(childId, cloneLevel(child));
            }
        }
        DiagramNode result = clone;
        mutate(origin, diag -> copy(diag).nodes(append(diag.nodes(), result))
                .connections(diag.connections()).levels(levels).build(), List.of(result.id()));
        return result;
    }

    /** Clona un nivel completo con ids nuevos (para duplicar contenedores). */
    private static DiagramData cloneLevel(DiagramData level) {
        Map<String, String> ids = new HashMap<>();
        Map<String, DiagramData> levels = new HashMap<>();
        List<DiagramNode> nodes = new ArrayList<>();
        for (DiagramNode n : level.nodes()) {
            String id = newId("n");
            ids.put(n.id(), id);
            DiagramNode copy = new DiagramNode(id, n.x(), n.y(), n.width(), n.height(), n.label(),
                    n.sublabel(), n.technology(), n.summary(), n.docUrl(), n.doc(), n.shape(), n.fill(),
                    n.stroke(), n.textColor(), n.icon(), n.ports(), null, n.portal());
            if (n.childLevelId() != null && !n.childLevelId().isBlank()) {
                DiagramData child = level.level(n.childLevelId());
                if (child != null) {
                    String childId = newId("lv");
                    copy = copy.withChildLevel(childId);
                    levels.put(childId, cloneLevel(child));
                }
            }
            nodes.add(copy);
        }
        List<DiagramConnection> connections = new ArrayList<>();
        for (DiagramConnection c : level.connections()) {
            connections.add(new DiagramConnection(newId("c"),
                    new PortRef(ids.getOrDefault(c.from().nodeId(), c.from().nodeId()),
                            c.from().side()),
                    new PortRef(ids.getOrDefault(c.to().nodeId(), c.to().nodeId()), c.to().side()),
                    c.label(), c.routing(), c.stroke(), c.animation(), c.arrow(), c.color(),
                    c.width(), c.waypoints()));
        }
        return copy(level).nodes(nodes).connections(connections).levels(levels).build();
    }

    public void removeNode(String origin, List<String> path, String id) {
        removeNodes(origin, path, List.of(id));
    }

    public void removeNodes(String origin, List<String> path, List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("Indica al menos un id.");
        }
        Set<String> set = new LinkedHashSet<>(ids);
        mutate(origin, root -> updateLevelAt(root, path, d -> {
            List<DiagramNode> removed = d.nodes().stream().filter(n -> set.contains(n.id())).toList();
            List<DiagramNode> nodes = d.nodes().stream().filter(n -> !set.contains(n.id())).toList();
            List<DiagramConnection> conns = d.connections().stream()
                    .filter(c -> !set.contains(c.from().nodeId()) && !set.contains(c.to().nodeId()))
                    .toList();
            Map<String, DiagramData> levels = new HashMap<>(d.levels());
            for (DiagramNode node : removed) {
                if (node.childLevelId() != null && !node.childLevelId().isBlank()) {
                    levels.remove(node.childLevelId());
                }
            }
            return copy(d).nodes(nodes).connections(conns).levels(levels).build();
        }));
    }

    /** Cambia el orden Z de un nodo: front | back | forward | backward. */
    public DiagramNode zOrder(String origin, String id, String action) {
        DiagramData d = repository.load();
        DiagramNode node = d.node(id);
        if (node == null) {
            throw new NoSuchElementException("No existe el nodo: " + id);
        }
        List<DiagramNode> list = new ArrayList<>(d.nodes());
        int i = list.indexOf(node);
        list.remove(i);
        switch (action == null ? "front" : action.toLowerCase()) {
            case "back" -> list.add(0, node);
            case "forward" -> list.add(Math.min(i + 1, list.size()), node);
            case "backward" -> list.add(Math.max(i - 1, 0), node);
            default -> list.add(node); // front
        }
        mutate(origin, diag -> copy(diag).nodes(List.copyOf(list)).connections(diag.connections()).build(), List.of(id));
        return node;
    }

    /** Alinea varios nodos entre si: left/right/top/bottom/centerX/centerY. */
    public List<DiagramNode> align(String origin, List<String> ids, String direction) {
        if (ids == null || ids.size() < 2) {
            throw new IllegalArgumentException("Se necesitan al menos dos nodos para alinear.");
        }
        if (direction == null || !ALIGN_DIRECTIONS.contains(direction)) {
            throw new IllegalArgumentException("Direccion invalida. Usa: " + ALIGN_DIRECTIONS);
        }
        DiagramData d = repository.load();
        Set<String> set = new LinkedHashSet<>(ids);
        List<DiagramNode> selected = d.nodes().stream().filter(n -> set.contains(n.id())).toList();
        if (selected.size() < 2) {
            throw new IllegalArgumentException("Se necesitan al menos dos nodos validos para alinear.");
        }
        double minX = selected.stream().mapToDouble(DiagramNode::x).min().orElse(0);
        double minY = selected.stream().mapToDouble(DiagramNode::y).min().orElse(0);
        double maxX = selected.stream().mapToDouble(n -> n.x() + n.width()).max().orElse(0);
        double maxY = selected.stream().mapToDouble(n -> n.y() + n.height()).max().orElse(0);
        double cx = (minX + maxX) / 2;
        double cy = (minY + maxY) / 2;

        List<DiagramNode> updated = new ArrayList<>();
        for (DiagramNode n : selected) {
            DiagramNode next = switch (direction) {
                case "left" -> n.movedTo(minX, n.y());
                case "right" -> n.movedTo(maxX - n.width(), n.y());
                case "top" -> n.movedTo(n.x(), minY);
                case "bottom" -> n.movedTo(n.x(), maxY - n.height());
                case "centerX" -> n.movedTo(cx - n.width() / 2, n.y());
                default -> n.movedTo(n.x(), cy - n.height() / 2);
            };
            updated.add(next);
        }
        List<DiagramNode> nextNodes = replaceNodeList(d.nodes(), updated);
        mutate(origin, diag -> copy(diag).nodes(nextNodes).connections(diag.connections()).build(), new ArrayList<>(set));
        return updated;
    }

    /**
     * Recoloca todos los nodos en capas (ver {@link AutoLayout}: rompe ciclos y agrupa
     * por rol). Equivale al boton "Organizar" del editor. Devuelve los nodos con la
     * posicion nueva.
     */
    public List<DiagramNode> autoLayout(String origin, String direction, Double layerGap, Double nodeGap,
            Boolean byRole) {
        DiagramData d = repository.load();
        List<DiagramNode> nodes = d.nodes();
        if (nodes.isEmpty()) {
            return List.of();
        }
        Map<String, double[]> result = AutoLayout.compute(nodes, d.connections(), direction, layerGap, nodeGap,
                byRole);
        List<String> ids = new ArrayList<>(result.keySet());

        List<DiagramNode> updated = new ArrayList<>();
        for (DiagramNode n : nodes) {
            double[] p = result.get(n.id());
            if (p == null) {
                updated.add(n);
            } else {
                updated.add(n.movedTo(Math.round(p[0]), Math.round(p[1])));
            }
        }
        mutate(origin, diag -> copy(diag).nodes(updated).connections(diag.connections()).build(), ids);
        return updated;
    }

    // ------------------------------------------------------------------ conexiones
    public DiagramConnection addConnection(String origin, List<String> path, String fromId,
            String toId, String fromSide, String toSide, String label, String routing,
            String stroke, String animation, String arrow, String color, Double width,
            List<Point> waypoints) {
        return addConnection(origin, path, null, fromId, toId, fromSide, toSide, label, routing,
                stroke, animation, arrow, color, width, waypoints);
    }

    /**
     * Igual que la conexion normal pero permite fijar el id (para lotes atomicos
     * que referencian la conexion recien creada).
     */
    public DiagramConnection addConnection(String origin, List<String> path, String id,
            String fromId, String toId, String fromSide, String toSide, String label, String routing,
            String stroke, String animation, String arrow, String color, Double width,
            List<Point> waypoints) {

        DiagramData d = repository.load();
        DiagramData level = resolveLevel(d, path);
        if (level.node(fromId) == null) {
            throw new NoSuchElementException("No existe el nodo origen: " + fromId);
        }
        if (level.node(toId) == null) {
            throw new NoSuchElementException("No existe el nodo destino: " + toId);
        }
        String connectionId = id == null || id.isBlank() ? newId("c") : id.trim();
        if (findConnection(d, connectionId) != null) {
            throw new IllegalArgumentException("Ya existe una conexion con id: " + connectionId);
        }
        DiagramConnection c = new DiagramConnection(connectionId, new PortRef(fromId, fromSide),
                new PortRef(toId, toSide), label, routing, stroke, animation, arrow, color,
                width == null ? 0 : width, waypoints);

        mutate(origin, root -> updateLevelAt(root, path,
                lvl -> copy(lvl).connections(append(lvl.connections(), c)).build()),
                List.of(c.id()));
        return c;
    }

    public DiagramConnection updateConnection(String origin, List<String> path, String id,
            String label, String routing, String stroke, String animation, String arrow,
            String color, Double width, List<Point> waypoints) {

        return patchConnection(origin, path, id, label, routing, stroke, animation, arrow, color,
                width, waypoints);
    }

    /** Aplica el mismo patch a varias conexiones a la vez. */
    public List<DiagramConnection> updateConnections(String origin, List<String> path,
            List<String> ids, String label, String routing, String stroke, String animation,
            String arrow, String color, Double width) {

        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("Indica al menos un id.");
        }
        List<DiagramConnection> result = new ArrayList<>();
        for (String id : ids) {
            result.add(patchConnection(origin, path, id, label, routing, stroke, animation, arrow,
                    color, width, null));
        }
        return result;
    }

    private DiagramConnection patchConnection(String origin, List<String> path, String id,
            String label, String routing, String stroke, String animation, String arrow,
            String color, Double width, List<Point> waypoints) {

        DiagramData d = repository.load();
        DiagramData level = resolveLevel(d, path);
        DiagramConnection existing = level.connection(id);
        if (existing == null) {
            throw new NoSuchElementException("No existe la conexion: " + id);
        }
        DiagramConnection updated = new DiagramConnection(existing.id(), existing.from(), existing.to(),
                label != null ? label : existing.label(),
                routing != null ? routing : existing.routing(),
                stroke != null ? stroke : existing.stroke(),
                animation != null ? animation : existing.animation(),
                arrow != null ? arrow : existing.arrow(),
                color != null ? color : existing.color(),
                width != null ? width : existing.width(),
                waypoints != null ? waypoints : existing.waypoints());

        mutate(origin, root -> updateLevelAt(root, path,
                lvl -> copy(lvl).connections(replaceConnList(lvl.connections(), updated)).build()),
                List.of(id));
        return updated;
    }

    /** Reapunta una conexion a otros nodos/lados (reconectar). */
    public DiagramConnection reconnect(String origin, String id, String fromNodeId, String fromSide,
            String toNodeId, String toSide) {
        DiagramData d = repository.load();
        DiagramConnection existing = d.connection(id);
        if (existing == null) {
            throw new NoSuchElementException("No existe la conexion: " + id);
        }
        String fn = fromNodeId != null ? fromNodeId : existing.from().nodeId();
        String tn = toNodeId != null ? toNodeId : existing.to().nodeId();
        if (d.node(fn) == null) {
            throw new NoSuchElementException("No existe el nodo origen: " + fn);
        }
        if (d.node(tn) == null) {
            throw new NoSuchElementException("No existe el nodo destino: " + tn);
        }
        DiagramConnection updated = new DiagramConnection(existing.id(),
                new PortRef(fn, fromSide != null ? fromSide : existing.from().side()),
                new PortRef(tn, toSide != null ? toSide : existing.to().side()),
                existing.label(), existing.routing(), existing.stroke(), existing.animation(),
                existing.arrow(), existing.color(), existing.width(), existing.waypoints());

        mutate(origin, diag -> copy(diag).nodes(diag.nodes()).connections(replaceConnList(diag.connections(), updated)).build(), List.of(id));
        return updated;
    }

    /** Invierte el sentido de una conexion (intercambia origen y destino). */
    public DiagramConnection reverseConnection(String origin, String id) {
        DiagramData d = repository.load();
        DiagramConnection existing = d.connection(id);
        if (existing == null) {
            throw new NoSuchElementException("No existe la conexion: " + id);
        }
        DiagramConnection updated = new DiagramConnection(existing.id(), existing.to(), existing.from(),
                existing.label(), existing.routing(), existing.stroke(), existing.animation(),
                existing.arrow(), existing.color(), existing.width(), existing.waypoints());
        mutate(origin, diag -> copy(diag).nodes(diag.nodes()).connections(replaceConnList(diag.connections(), updated)).build(), List.of(id));
        return updated;
    }

    public void removeConnection(String origin, List<String> path, String id) {
        removeConnections(origin, path, List.of(id));
    }

    public void removeConnections(String origin, List<String> path, List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("Indica al menos un id.");
        }
        Set<String> set = new LinkedHashSet<>(ids);
        mutate(origin, root -> updateLevelAt(root, path, lvl -> copy(lvl).connections(
                lvl.connections().stream().filter(c -> !set.contains(c.id())).toList()).build()));
    }

    // ------------------------------------------------------------------ niveles internos

    /** Devuelve el nivel interno indicado por la ruta de ids de nodos (vacia = raiz). */
    public DiagramData level(List<String> path) {
        return resolveLevel(current(), path);
    }

    /** Crea (si falta) el nivel interno de un nodo en el nivel indicado por 'path'. */
    public DiagramData createLevel(String origin, List<String> path, String nodeId, String name) {
        if (path != null && path.size() >= 2) {
            throw new IllegalArgumentException("Solo se permiten 3 niveles.");
        }
        return mutate(origin, root -> updateLevelAt(root, path, level -> {
            DiagramNode node = level.node(nodeId);
            if (node == null) {
                throw new NoSuchElementException("No existe el nodo: " + nodeId);
            }
            if (node.childLevelId() != null && !node.childLevelId().isBlank()
                    && level.level(node.childLevelId()) != null) {
                return level;
            }
            String childId = newId("lv");
            DiagramData child = buildInterior(node, level,
                    name == null || name.isBlank() ? node.label() : name);
            DiagramNode updated = node.withChildLevel(childId);
            Map<String, DiagramData> levels = new HashMap<>(level.levels());
            levels.put(childId, child);
            return copy(level).nodes(replaceNodeList(level.nodes(), updated)).levels(levels).build();
        }));
    }

    /** Elimina el nivel interno de un nodo (y su contenido). */
    public DiagramData removeLevel(String origin, List<String> path, String nodeId) {
        return mutate(origin, root -> updateLevelAt(root, path, level -> {
            DiagramNode node = level.node(nodeId);
            if (node == null) {
                throw new NoSuchElementException("No existe el nodo: " + nodeId);
            }
            Map<String, DiagramData> levels = new HashMap<>(level.levels());
            if (node.childLevelId() != null && !node.childLevelId().isBlank()) {
                levels.remove(node.childLevelId());
            }
            DiagramNode updated = node.withChildLevel("");
            return copy(level).nodes(replaceNodeList(level.nodes(), updated)).levels(levels).build();
        }));
    }

    /** Reemplaza el contenido de un nivel interno (la ruta no puede estar vacia). */
    public DiagramData replaceLevel(String origin, List<String> path, DiagramData data) {
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("Indica la ruta de nodos hasta el nivel interno.");
        }
        DiagramData next = data == null ? DiagramData.empty() : data;
        return mutate(origin, root -> updateLevelAt(root, path, ignored -> next));
    }

    private DiagramData resolveLevel(DiagramData root, List<String> path) {
        DiagramData level = root;
        if (path == null) {
            return level;
        }
        for (String nodeId : path) {
            DiagramNode node = level.node(nodeId);
            if (node == null || node.childLevelId() == null || node.childLevelId().isBlank()) {
                throw new NoSuchElementException("El nodo no tiene nivel interno: " + nodeId);
            }
            DiagramData child = level.level(node.childLevelId());
            if (child == null) {
                throw new NoSuchElementException("No existe el nivel interno de: " + nodeId);
            }
            level = child;
        }
        return level;
    }

    private DiagramData updateLevelAt(DiagramData root, List<String> path,
            Function<DiagramData, DiagramData> fn) {
        if (path == null || path.isEmpty()) {
            return fn.apply(root);
        }
        String nodeId = path.get(0);
        DiagramNode node = root.node(nodeId);
        if (node == null || node.childLevelId() == null || node.childLevelId().isBlank()) {
            throw new NoSuchElementException("El nodo no tiene nivel interno: " + nodeId);
        }
        String childId = node.childLevelId();
        DiagramData child = root.level(childId);
        if (child == null) {
            throw new NoSuchElementException("No existe el nivel interno de: " + nodeId);
        }
        DiagramData updated = updateLevelAt(child, path.subList(1, path.size()), fn);
        Map<String, DiagramData> levels = new HashMap<>(root.levels());
        levels.put(childId, updated);
        return copy(root).levels(levels).build();
    }

    /** Nivel inicial con portales automaticos segun las conexiones del nodo padre. */
    private DiagramData buildInterior(DiagramNode parent, DiagramData level, String name) {
        List<DiagramConnection> incoming = level.connections().stream()
                .filter(c -> c.to().nodeId().equals(parent.id())).toList();
        List<DiagramConnection> outgoing = level.connections().stream()
                .filter(c -> c.from().nodeId().equals(parent.id())).toList();
        List<DiagramNode> nodes = new ArrayList<>();
        for (int i = 0; i < incoming.size(); i++) {
            nodes.add(portalNode("in", incoming.get(i), i, incoming.size(), level.gridSize()));
        }
        for (int i = 0; i < outgoing.size(); i++) {
            nodes.add(portalNode("out", outgoing.get(i), i, outgoing.size(), level.gridSize()));
        }
        return new DiagramData(name, level.background(), level.gridSize(), nodes, List.of(),
                Map.of());
    }

    private DiagramNode portalNode(String direction, DiagramConnection connection, int index,
            int total, double grid) {
        double width = 160;
        double height = 56;
        double gap = 40;
        double rowWidth = total * width + Math.max(0, total - 1) * gap;
        double x = Math.round((360 - rowWidth / 2 + index * (width + gap)) / grid) * grid;
        double y = direction.equals("in") ? 80 : 260;
        boolean in = direction.equals("in");
        return new DiagramNode(newId("n"), x, y, width, height,
                connection.label() == null || connection.label().isBlank()
                        ? (in ? "Entrada" : "Salida")
                        : connection.label(),
                in ? "Desde el nivel superior" : "Hacia el nivel superior",
                "", "", "pill", in ? "#e6efff" : "#e2f7ee", in ? "#2f6bd8" : "#1f9d6b",
                "#1f2430", in ? "portalIn" : "portalOut",
                List.of("top", "right", "bottom", "left"), null,
                new DiagramPortal(direction, connection.id(),
                        in ? connection.to().side() : connection.from().side()));
    }

    // ------------------------------------------------------------------ helpers

    private double[] autoPosition(DiagramData d, Double x, Double y) {
        if (x != null && y != null) {
            return new double[] { x, y };
        }
        if (x != null) {
            return new double[] { x, y != null ? y : 60 };
        }
        if (y != null) {
            return new double[] { 60, y };
        }
        if (d.nodes().isEmpty()) {
            return new double[] { 60, 60 };
        }
        double maxBottom = d.nodes().stream().mapToDouble(n -> n.y() + n.height()).max().orElse(60);
        return new double[] { 60, maxBottom + 40 };
    }

    private static <T> List<T> append(List<T> list, T item) {
        List<T> next = new ArrayList<>(list);
        next.add(item);
        return List.copyOf(next);
    }

    private static List<DiagramNode> replaceNodeList(List<DiagramNode> list, DiagramNode updated) {
        return list.stream().map(n -> n.id().equals(updated.id()) ? updated : n).toList();
    }

    private static List<DiagramNode> replaceNodeList(List<DiagramNode> list, List<DiagramNode> updates) {
        java.util.Map<String, DiagramNode> byId = new java.util.HashMap<>();
        for (DiagramNode n : updates) {
            byId.put(n.id(), n);
        }
        return list.stream().map(n -> byId.getOrDefault(n.id(), n)).toList();
    }

    private static List<DiagramConnection> replaceConnList(List<DiagramConnection> list,
            DiagramConnection updated) {
        return list.stream().map(c -> c.id().equals(updated.id()) ? updated : c).toList();
    }

    private static String newId(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    static Builder copy(DiagramData d) {
        return new Builder(d);
    }

    /** Pequena ayuda mutable para construir DiagramData copiando campos. */
    static final class Builder {
        private String name;
        private String background;
        private double gridSize;
        private List<DiagramNode> nodes;
        private List<DiagramConnection> connections;
        private Map<String, DiagramData> levels;

        Builder(DiagramData d) {
            this.name = d.name();
            this.background = d.background();
            this.gridSize = d.gridSize();
            this.nodes = d.nodes();
            this.connections = d.connections();
            this.levels = d.levels();
        }

        Builder name(String v) {
            this.name = v;
            return this;
        }

        Builder background(String v) {
            this.background = v;
            return this;
        }

        Builder gridSize(double v) {
            this.gridSize = v;
            return this;
        }

        Builder nodes(List<DiagramNode> v) {
            this.nodes = v;
            return this;
        }

        Builder connections(List<DiagramConnection> v) {
            this.connections = v;
            return this;
        }

        Builder levels(Map<String, DiagramData> v) {
            this.levels = v;
            return this;
        }

        DiagramData build() {
            return new DiagramData(name, background, gridSize, nodes, connections, levels);
        }
    }
}
