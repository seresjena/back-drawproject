package app.arquidraw.backend.service;

import app.arquidraw.backend.model.DiagramConnection;
import app.arquidraw.backend.model.DiagramData;
import app.arquidraw.backend.model.DiagramNode;
import app.arquidraw.backend.model.NodePreset;
import app.arquidraw.backend.model.NodePresets;
import app.arquidraw.backend.model.Point;
import app.arquidraw.backend.model.PortRef;
import app.arquidraw.backend.model.ViewRequest;
import app.arquidraw.backend.model.Viewport;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
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

    private final DiagramRepository repository;
    private final ApplicationEventPublisher events;
    private final String room;
    private final AtomicLong revision = new AtomicLong();

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
        DiagramData next = data == null ? DiagramData.empty() : data;
        repository.save(next);
        long rev = revision.incrementAndGet();
        events.publishEvent(new DiagramChangedEvent(next, rev,
                origin == null ? ORIGIN_SERVER : origin, highlight == null ? List.of() : highlight, room));
        return next;
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
        return mutate(origin, d -> new DiagramData(d.name(), d.background(), d.gridSize(), List.of(), List.of()));
    }

    // ------------------------------------------------------------------ nodos

    public DiagramNode addNode(String origin, String label, String sublabel, String technology,
            String summary, String presetKey, Double x, Double y, Double width, Double height, String shape,
            String icon, String fill, String stroke, String textColor, List<String> ports) {

        DiagramData d = repository.load();
        NodePreset preset = NodePresets.find(presetKey);
        if (preset == null) {
            preset = NodePresets.defaultPreset();
        }

        String id = newId("n");
        double[] pos = autoPosition(d, x, y);
        double w = width != null ? width : preset.width();
        double h = height != null ? height : preset.height();
        String sh = shape != null ? shape : preset.shape();
        String ic = icon != null ? icon : preset.icon();
        String fi = fill != null ? fill : preset.fill();
        String st = stroke != null ? stroke : preset.stroke();
        String tc = textColor != null ? textColor : preset.textColor();
        List<String> p = ports != null ? ports : List.of("top", "right", "bottom", "left");

        DiagramNode node = new DiagramNode(id, pos[0], pos[1], w, h,
                (label == null || label.isBlank()) ? preset.label() : label,
                sublabel == null ? "" : sublabel,
                technology == null ? "" : technology,
                summary == null ? "" : summary,
                sh, fi, st, tc, ic, p);

        mutate(origin, diag -> new DiagramData(diag.name(), diag.background(), diag.gridSize(),
                append(diag.nodes(), node), diag.connections()), List.of(id));
        return node;
    }

    public DiagramNode updateNode(String origin, String id, String label, String sublabel,
            String technology, String summary, Double x, Double y, Double width, Double height,
            String shape, String icon, String fill, String stroke, String textColor, List<String> ports) {

        DiagramNode updated = patchNode(origin, id, label, sublabel, technology, summary, x, y, width,
                height, shape, icon, fill, stroke, textColor, ports);
        return updated;
    }

    /** Aplica el mismo patch a varios nodos a la vez (estilo/serie). */
    public List<DiagramNode> updateNodes(String origin, List<String> ids, String label, String sublabel,
            String technology, String summary, Double x, Double y, Double width, Double height,
            String shape, String icon, String fill, String stroke, String textColor, List<String> ports) {

        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("Indica al menos un id.");
        }
        List<DiagramNode> result = new ArrayList<>();
        for (String id : ids) {
            result.add(patchNode(origin, id, label, sublabel, technology, summary, x, y, width, height,
                    shape, icon, fill, stroke, textColor, ports));
        }
        return result;
    }

    private DiagramNode patchNode(String origin, String id, String label, String sublabel,
            String technology, String summary, Double x, Double y, Double width, Double height,
            String shape, String icon, String fill, String stroke, String textColor, List<String> ports) {

        DiagramData d = repository.load();
        DiagramNode existing = d.node(id);
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
                shape != null ? shape : existing.shape(),
                fill != null ? fill : existing.fill(),
                stroke != null ? stroke : existing.stroke(),
                textColor != null ? textColor : existing.textColor(),
                icon != null ? icon : existing.icon(),
                ports != null ? ports : existing.ports());

        mutate(origin, diag -> new DiagramData(diag.name(), diag.background(), diag.gridSize(),
                replaceNodeList(diag.nodes(), updated), diag.connections()), List.of(id));
        return updated;
    }

    public DiagramNode moveNode(String origin, String id, double x, double y) {
        return updateNode(origin, id, null, null, null, null, x, y, null, null, null, null, null, null,
                null, null);
    }

    public DiagramNode resizeNode(String origin, String id, double width, double height) {
        return updateNode(origin, id, null, null, null, null, null, null, width, height, null, null, null,
                null, null, null);
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
                existing.technology(), existing.summary(), existing.shape(), existing.fill(),
                existing.stroke(), existing.textColor(), existing.icon(), existing.ports());

        mutate(origin, diag -> new DiagramData(diag.name(), diag.background(), diag.gridSize(),
                append(diag.nodes(), clone), diag.connections()), List.of(clone.id()));
        return clone;
    }

    public void removeNode(String origin, String id) {
        removeNodes(origin, List.of(id));
    }

    public void removeNodes(String origin, List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("Indica al menos un id.");
        }
        Set<String> set = new LinkedHashSet<>(ids);
        mutate(origin, d -> {
            List<DiagramNode> nodes = d.nodes().stream().filter(n -> !set.contains(n.id())).toList();
            List<DiagramConnection> conns = d.connections().stream()
                    .filter(c -> !set.contains(c.from().nodeId()) && !set.contains(c.to().nodeId()))
                    .toList();
            return new DiagramData(d.name(), d.background(), d.gridSize(), nodes, conns);
        });
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
        mutate(origin, diag -> new DiagramData(diag.name(), diag.background(), diag.gridSize(),
                List.copyOf(list), diag.connections()), List.of(id));
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
        mutate(origin, diag -> new DiagramData(diag.name(), diag.background(), diag.gridSize(),
                nextNodes, diag.connections()), new ArrayList<>(set));
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
        mutate(origin, diag -> new DiagramData(diag.name(), diag.background(), diag.gridSize(),
                updated, diag.connections()), ids);
        return updated;
    }

    // ------------------------------------------------------------------ conexiones
    public DiagramConnection addConnection(String origin, String fromId, String toId,
            String fromSide, String toSide, String label, String routing, String stroke,
            String animation, String arrow, String color, Double width, List<Point> waypoints) {

        DiagramData d = repository.load();
        if (d.node(fromId) == null) {
            throw new NoSuchElementException("No existe el nodo origen: " + fromId);
        }
        if (d.node(toId) == null) {
            throw new NoSuchElementException("No existe el nodo destino: " + toId);
        }
        DiagramConnection c = new DiagramConnection(newId("c"), new PortRef(fromId, fromSide),
                new PortRef(toId, toSide), label, routing, stroke, animation, arrow, color,
                width == null ? 0 : width, waypoints);

        mutate(origin, diag -> new DiagramData(diag.name(), diag.background(), diag.gridSize(),
                diag.nodes(), append(diag.connections(), c)), List.of(c.id()));
        return c;
    }

    public DiagramConnection updateConnection(String origin, String id, String label, String routing,
            String stroke, String animation, String arrow, String color, Double width,
            List<Point> waypoints) {

        return patchConnection(origin, id, label, routing, stroke, animation, arrow, color, width,
                waypoints);
    }

    /** Aplica el mismo patch a varias conexiones a la vez. */
    public List<DiagramConnection> updateConnections(String origin, List<String> ids, String label,
            String routing, String stroke, String animation, String arrow, String color,
            Double width) {

        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("Indica al menos un id.");
        }
        List<DiagramConnection> result = new ArrayList<>();
        for (String id : ids) {
            result.add(patchConnection(origin, id, label, routing, stroke, animation, arrow, color,
                    width, null));
        }
        return result;
    }

    private DiagramConnection patchConnection(String origin, String id, String label, String routing,
            String stroke, String animation, String arrow, String color, Double width,
            List<Point> waypoints) {

        DiagramData d = repository.load();
        DiagramConnection existing = d.connection(id);
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

        mutate(origin, diag -> new DiagramData(diag.name(), diag.background(), diag.gridSize(),
                diag.nodes(), replaceConnList(diag.connections(), updated)), List.of(id));
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

        mutate(origin, diag -> new DiagramData(diag.name(), diag.background(), diag.gridSize(),
                diag.nodes(), replaceConnList(diag.connections(), updated)), List.of(id));
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
        mutate(origin, diag -> new DiagramData(diag.name(), diag.background(), diag.gridSize(),
                diag.nodes(), replaceConnList(diag.connections(), updated)), List.of(id));
        return updated;
    }

    public void removeConnection(String origin, String id) {
        removeConnections(origin, List.of(id));
    }

    public void removeConnections(String origin, List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("Indica al menos un id.");
        }
        Set<String> set = new LinkedHashSet<>(ids);
        mutate(origin, d -> new DiagramData(d.name(), d.background(), d.gridSize(), d.nodes(),
                d.connections().stream().filter(c -> !set.contains(c.id())).toList()));
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

        Builder(DiagramData d) {
            this.name = d.name();
            this.background = d.background();
            this.gridSize = d.gridSize();
            this.nodes = d.nodes();
            this.connections = d.connections();
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

        DiagramData build() {
            return new DiagramData(name, background, gridSize, nodes, connections);
        }
    }
}
