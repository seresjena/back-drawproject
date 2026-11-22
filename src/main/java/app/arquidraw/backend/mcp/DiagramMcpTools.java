package app.arquidraw.backend.mcp;

import app.arquidraw.backend.model.DiagramConnection;
import app.arquidraw.backend.model.DiagramData;
import app.arquidraw.backend.model.DiagramNode;
import app.arquidraw.backend.model.NodePreset;
import app.arquidraw.backend.model.NodePresets;
import app.arquidraw.backend.model.Point;
import app.arquidraw.backend.model.Viewport;
import app.arquidraw.backend.service.DiagramService;
import app.arquidraw.backend.service.DiagramValidator;
import app.arquidraw.backend.service.RevisionConflictException;
import app.arquidraw.backend.service.RoomManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import jakarta.servlet.http.HttpServletResponse;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Tools MCP. Cada metodo es una operacion que cualquier agente puede invocar.
 * Las mutaciones llaman a {@link DiagramService}, que publica el cambio y el
 * WebSocket lo refleja en vivo en el editor.
 */
@Service
public class DiagramMcpTools {

    private static final String ORIGIN = DiagramService.ORIGIN_MCP;

    /** Campos que puede proyectar list_nodes. */
    private static final List<String> NODE_FIELDS = List.of("id", "x", "y", "width", "height",
            "label", "sublabel", "technology", "summary", "docUrl", "doc", "shape", "fill",
            "stroke", "textColor", "icon", "ports", "childLevelId", "portal");

    private final RoomManager rooms;
    private final ObjectMapper mapper;

    public DiagramMcpTools(RoomManager rooms, ObjectMapper mapper) {
        this.rooms = rooms;
        this.mapper = mapper;
    }

    /**
     * Servicio del diagrama de la sala que el agente indico al conectarse
     * ({@code ?sala=TOKEN} en la URL de MCP). Si no hay token, la sala por
     * defecto compartida con el editor monousuario.
     */
    private DiagramService svc() {
        return rooms.forRoom(McpSalaContext.current());
    }

    /** Ejecuta una mutacion atomica y marca 409 si la revision no coincide. */
    private <T> T guarded(Long expectedRevision, Supplier<T> action) {
        try {
            return svc().withExpectedRevision(expectedRevision, action);
        } catch (RevisionConflictException ex) {
            markConflict();
            throw ex;
        }
    }

    private void guardedRun(Long expectedRevision, Runnable action) {
        try {
            svc().withExpectedRevision(expectedRevision, action);
        } catch (RevisionConflictException ex) {
            markConflict();
            throw ex;
        }
    }

    private static void markConflict() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                && attributes.getResponse() != null) {
            attributes.getResponse().setStatus(HttpServletResponse.SC_CONFLICT);
        }
    }

    // ------------------------------------------------------------------ lectura

    @McpTool(name = "get_diagram", annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false), description = "Devuelve el diagrama activo completo: nombre, fondo, cuadricula, nodos y conexiones.")
    public DiagramData getDiagram() {
        return svc().current();
    }

    @McpTool(name = "get_meta", annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false), description = "Resumen del diagrama: nombre, fondo, tamano de cuadricula, cuantos nodos/conexiones hay, el rectangulo que ocupa el contenido (contentBounds) y el viewport del editor (viewport/visibleWorld) para saber que region se esta viendo.")
    public Map<String, Object> getMeta() {
        DiagramData d = svc().current();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("name", d.name());
        meta.put("background", d.background());
        meta.put("gridSize", d.gridSize());
        meta.put("nodeCount", d.nodes().size());
        meta.put("connectionCount", d.connections().size());
        meta.put("revision", svc().revision());
        meta.put("contentBounds", d.bounds());
        Viewport viewport = svc().viewport();
        meta.put("viewport", viewport);
        meta.put("visibleWorld", viewport == null ? null : viewport.visibleWorld());
        return meta;
    }

    @McpTool(name = "list_nodes", annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false), description = "Lista los componentes de un nivel (raiz por defecto). Por defecto omite las fichas Markdown; usa includeDocs=true o fields=[\"doc\"] para incluirlas. fields proyecta solo esos campos y filter acepta labelContains y stroke (status/priority/tag llegan en la fase 6).")
    public List<Map<String, Object>> listNodes(
            @McpToolParam(description = "Ruta de ids hasta el nivel (vacia u omitida = raiz)", required = false) List<String> path,
            @McpToolParam(description = "Campos exactos a devolver, p. ej. [\"id\",\"label\",\"stroke\"]", required = false) List<String> fields,
            @McpToolParam(description = "Incluir el Markdown interno (por defecto false)", required = false) Boolean includeDocs,
            @McpToolParam(description = "Filtro: { labelContains?, stroke? }", required = false) Map<String, Object> filter,
            McpSchema.CallToolRequest request) {
        rejectUnknownFields(request, Set.of("path", "fields", "includeDocs", "filter"));
        validateNodeFields(fields);
        validateFilter(filter);
        DiagramData level = svc().level(path);
        List<DiagramNode> nodes = level.nodes();
        if (filter != null && !filter.isEmpty()) {
            nodes = nodes.stream().filter(n -> matchesFilter(n, filter)).toList();
        }
        boolean withDocs = Boolean.TRUE.equals(includeDocs);
        List<Map<String, Object>> out = new ArrayList<>();
        for (DiagramNode node : nodes) {
            out.add(projectNode(node, fields, withDocs));
        }
        return out;
    }

    @McpTool(name = "get_node", annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false), description = "Devuelve un componente por su id. Si esta en un nivel interno, indica la ruta de ids hasta ese nivel.")
    public DiagramNode getNode(
            @McpToolParam(description = "Id del nodo", required = true) String id,
            @McpToolParam(description = "Ruta de ids desde la raiz hasta el nivel que contiene el nodo (vacia u omitida = raiz)", required = false) List<String> path,
            McpSchema.CallToolRequest request) {
        rejectUnknownFields(request, Set.of("id", "path"));
        return svc().nodeAt(path, id);
    }

    @McpTool(name = "get_node_doc", annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false), description = "Devuelve la documentacion completa de un componente: docUrl (enlace externo) y doc (Markdown interno).")
    public DiagramNode getNodeDoc(
            @McpToolParam(description = "Id del nodo", required = true) String id) {
        DiagramNode node = svc().findNode(id);
        if (node == null) {
            throw new IllegalArgumentException("No existe el nodo: " + id);
        }
        return node;
    }

    @McpTool(name = "set_node_doc", annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false), description = "Reemplaza el Markdown interno de documentacion de un componente. Admite titulos, parrafos, tablas, listas, bloques de codigo y diagramas Mermaid. Maximo 100 KB. Usa get_node_doc para leerlo.")
    public Map<String, Object> setNodeDoc(
            @McpToolParam(description = "Id del nodo", required = true) String id,
            @McpToolParam(description = "Documentacion en Markdown", required = true) String doc,
            @McpToolParam(description = "Devolver el diagrama completo ademas del resumen", required = false) Boolean verbose,
            @McpToolParam(description = "Revision que el agente espera; si no coincide, la operacion falla con 409", required = false) Long expectedRevision,
            McpSchema.CallToolRequest request) {
        rejectUnknownFields(request, Set.of("id", "doc", "verbose"));
        DiagramNode node = guarded(expectedRevision, () -> svc().setDoc(ORIGIN, id, doc));
        return mutationResult(node.id(), null, docExtra(node, null, null), verbose);
    }

    @McpTool(name = "append_node_doc", annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false), description = "Anade Markdown al final de la documentacion interna de un componente, sin borrar lo existente.")
    public Map<String, Object> appendNodeDoc(
            @McpToolParam(description = "Id del nodo", required = true) String id,
            @McpToolParam(description = "Markdown que se anade al final", required = true) String doc,
            @McpToolParam(description = "Devolver el diagrama completo ademas del resumen", required = false) Boolean verbose,
            @McpToolParam(description = "Revision que el agente espera; si no coincide, la operacion falla con 409", required = false) Long expectedRevision,
            McpSchema.CallToolRequest request) {
        rejectUnknownFields(request, Set.of("id", "doc", "verbose"));
        DiagramNode node = guarded(expectedRevision, () -> svc().appendDoc(ORIGIN, id, doc));
        return mutationResult(node.id(), null, docExtra(node, null, null), verbose);
    }

    @McpTool(name = "set_node_doc_url", annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false), description = "Asigna o cambia la URL externa de documentacion de un componente (http o https). Vacio la elimina. Si existe, el editor abre esa pagina en una pestana nueva.")
    public Map<String, Object> setNodeDocUrl(
            @McpToolParam(description = "Id del nodo", required = true) String id,
            @McpToolParam(description = "URL http(s) de la documentacion; vacio para quitarla", required = true) String docUrl,
            @McpToolParam(description = "Devolver el diagrama completo ademas del resumen", required = false) Boolean verbose,
            @McpToolParam(description = "Revision que el agente espera; si no coincide, la operacion falla con 409", required = false) Long expectedRevision,
            McpSchema.CallToolRequest request) {
        rejectUnknownFields(request, Set.of("id", "docUrl", "verbose"));
        DiagramNode node = guarded(expectedRevision, () -> svc().setDocUrl(ORIGIN, id, docUrl));
        return mutationResult(node.id(), null, docExtra(node, null, null), verbose);
    }

    @McpTool(name = "remove_node_doc", annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false), description = "Elimina la URL externa y el Markdown interno de documentacion de un componente.")
    public Map<String, Object> removeNodeDoc(
            @McpToolParam(description = "Id del nodo", required = true) String id,
            @McpToolParam(description = "Devolver el diagrama completo ademas del resumen", required = false) Boolean verbose,
            @McpToolParam(description = "Revision que el agente espera; si no coincide, la operacion falla con 409", required = false) Long expectedRevision,
            McpSchema.CallToolRequest request) {
        rejectUnknownFields(request, Set.of("id", "verbose"));
        DiagramNode node = guarded(expectedRevision, () -> svc().clearDoc(ORIGIN, id));
        return mutationResult(node.id(), null, docExtra(node, null, null), verbose);
    }

    @McpTool(name = "get_doc", annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false), description = "Devuelve la ficha de un componente de un nivel concreto: id, doc (Markdown), docUrl y revision. Para un nivel interno, pasa 'path'.")
    public Map<String, Object> getDoc(
            @McpToolParam(description = "Id del nodo", required = true) String id,
            @McpToolParam(description = "Ruta de ids hasta el nivel que contiene el nodo (vacia u omitida = raiz)", required = false) List<String> path,
            McpSchema.CallToolRequest request) {
        rejectUnknownFields(request, Set.of("id", "path"));
        DiagramNode node = svc().nodeAt(path, id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", node.id());
        out.put("doc", node.doc());
        out.put("docUrl", node.docUrl());
        out.put("revision", svc().revision());
        return out;
    }

    @McpTool(name = "update_doc", annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false), description = "Reemplaza la ficha completa de un componente de un nivel concreto. Devuelve solo el resumen (sin el Markdown) para ahorrar tokens.")
    public Map<String, Object> updateDoc(
            @McpToolParam(description = "Id del nodo", required = true) String id,
            @McpToolParam(description = "Nueva ficha Markdown completa", required = true) String markdown,
            @McpToolParam(description = "Ruta de ids hasta el nivel que contiene el nodo (vacia u omitida = raiz)", required = false) List<String> path,
            @McpToolParam(description = "Devolver el diagrama completo ademas del resumen", required = false) Boolean verbose,
            @McpToolParam(description = "Revision que el agente espera; si no coincide, la operacion falla con 409", required = false) Long expectedRevision,
            McpSchema.CallToolRequest request) {
        rejectUnknownFields(request, Set.of("id", "markdown", "path", "expectedRevision", "verbose"));
        DiagramNode node = guarded(expectedRevision,
                () -> svc().updateDoc(ORIGIN, path, id, markdown, expectedRevision));
        return mutationResult(node.id(), null, docExtra(node, null, null), verbose);
    }

    @McpTool(name = "patch_doc", annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false), description = "Reemplaza o anade (mode=append) el contenido de una seccion '## <section>' de la ficha. Si la seccion no existe, la crea al final. Las demas secciones no se tocan.")
    public Map<String, Object> patchDoc(
            @McpToolParam(description = "Id del nodo", required = true) String id,
            @McpToolParam(description = "Titulo de la seccion nivel 2, sin el prefijo ##", required = true) String section,
            @McpToolParam(description = "Markdown del contenido de la seccion", required = true) String markdown,
            @McpToolParam(description = "Ruta de ids hasta el nivel que contiene el nodo (vacia u omitida = raiz)", required = false) List<String> path,
            @McpToolParam(description = "replace (por defecto) o append", required = false) String mode,
            @McpToolParam(description = "Devolver el diagrama completo ademas del resumen", required = false) Boolean verbose,
            @McpToolParam(description = "Revision que el agente espera; si no coincide, la operacion falla con 409", required = false) Long expectedRevision,
            McpSchema.CallToolRequest request) {
        rejectUnknownFields(request, Set.of("id", "section", "markdown", "path", "mode",
                "expectedRevision", "verbose"));
        DiagramNode node = guarded(expectedRevision,
                () -> svc().patchDoc(ORIGIN, path, id, section, markdown, mode, expectedRevision));
        return mutationResult(node.id(), null,
                docExtra(node, section, mode == null || mode.isBlank() ? "replace" : mode), verbose);
    }

    private static Map<String, Object> docExtra(DiagramNode node, String section, String mode) {
        Map<String, Object> extra = new LinkedHashMap<>();
        if (section != null) {
            extra.put("section", section);
            extra.put("mode", mode);
        }
        extra.put("docLength", node.doc().length());
        extra.put("docUrl", node.docUrl());
        return extra;
    }

    @McpTool(name = "list_connections", annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false), description = "Lista todas las conexiones del diagrama.")
    public List<DiagramConnection> listConnections() {
        return svc().current().connections();
    }

    @McpTool(name = "list_presets", annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false), description = "Lista los presets de componentes disponibles (key, forma, colores, icono y tamano por defecto).")
    public List<NodePreset> listPresets() {
        return NodePresets.all();
    }

    @McpTool(name = "focus_nodes", annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false), description = "Resalta (un instante) nodos y/o conexiones en el editor para que el usuario vea en que esta trabajando el agente. No modifica el diagrama.")
    public String focusNodes(
            @McpToolParam(description = "Ids de nodos/conexiones a resaltar", required = true) List<String> ids,
            @McpToolParam(description = "Ruta de ids hasta el nivel donde estan esos ids (vacia u omitida = raiz)", required = false) List<String> path,
            McpSchema.CallToolRequest request) {
        rejectUnknownFields(request, Set.of("ids", "path"));
        svc().focus(ORIGIN, path, ids);
        return "Resaltados: " + ids.size();
    }

    // ------------------------------------------------------------------ diagrama

    @McpTool(name = "set_view", annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false), description = "Cambia la vista del editor del usuario (zoom y centro) sin modificar el diagrama. Sin zoom encuadra todo el contenido (como 'Ajustar'). El editor se reencuadra solo tras cada cambio del diagrama, asi que llamalo al terminar de editar. Para ver el diagrama al 100% usa zoom=1 (cabe entero si el contenido mide como mucho viewport - 72 px; ver get_meta).")
    public String setView(
            @McpToolParam(description = "Zoom (0.15 a 4; 1 = 100%). Omitir para encuadrar todo", required = false) Double zoom,
            @McpToolParam(description = "X del mundo a centrar en pantalla (por defecto, centro del contenido)", required = false) Double centerX,
            @McpToolParam(description = "Y del mundo a centrar en pantalla (por defecto, centro del contenido)", required = false) Double centerY) {
        svc().setView(ORIGIN, zoom, centerX, centerY);
        return zoom == null ? "Vista: encuadrar todo el contenido" : "Vista: zoom " + zoom;
    }

    @McpTool(name = "rename_diagram", annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false), description = "Cambia el nombre del diagrama. Devuelve un resumen compacto; usa verbose=true para el diagrama completo.")
    public Map<String, Object> renameDiagram(
            @McpToolParam(description = "Nuevo nombre", required = true) String name,
            @McpToolParam(description = "Devolver el diagrama completo ademas del resumen", required = false) Boolean verbose,
            @McpToolParam(description = "Revision que el agente espera; si no coincide, la operacion falla con 409", required = false) Long expectedRevision,
            McpSchema.CallToolRequest request) {
        rejectUnknownFields(request, Set.of("name", "verbose"));
        guardedRun(expectedRevision, () -> svc().rename(ORIGIN, name));
        return mutationResult(null, null, null, verbose);
    }

    @McpTool(name = "set_background", annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false), description = "Cambia el fondo del lienzo. Valores: grid, dots, none.")
    public Map<String, Object> setBackground(
            @McpToolParam(description = "grid | dots | none", required = true) String background,
            @McpToolParam(description = "Devolver el diagrama completo ademas del resumen", required = false) Boolean verbose,
            @McpToolParam(description = "Revision que el agente espera; si no coincide, la operacion falla con 409", required = false) Long expectedRevision,
            McpSchema.CallToolRequest request) {
        rejectUnknownFields(request, Set.of("background", "verbose"));
        guardedRun(expectedRevision, () -> svc().setBackground(ORIGIN, background));
        return mutationResult(null, null, null, verbose);
    }

    @McpTool(name = "set_grid_size", annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false), description = "Ajusta el tamano de la cuadricula (snap). Entre 6 y 200.")
    public Map<String, Object> setGridSize(
            @McpToolParam(description = "Tamano en unidades del mundo", required = true) Double gridSize,
            @McpToolParam(description = "Devolver el diagrama completo ademas del resumen", required = false) Boolean verbose,
            @McpToolParam(description = "Revision que el agente espera; si no coincide, la operacion falla con 409", required = false) Long expectedRevision,
            McpSchema.CallToolRequest request) {
        rejectUnknownFields(request, Set.of("gridSize", "verbose"));
        guardedRun(expectedRevision, () -> svc().setGridSize(ORIGIN, gridSize));
        return mutationResult(null, null, null, verbose);
    }

    @McpTool(name = "clear_diagram", annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false), description = "Elimina todos los nodos y conexiones (mantiene nombre y fondo).")
    public Map<String, Object> clearDiagram(
            @McpToolParam(description = "Devolver el diagrama completo ademas del resumen", required = false) Boolean verbose,
            @McpToolParam(description = "Revision que el agente espera; si no coincide, la operacion falla con 409", required = false) Long expectedRevision,
            McpSchema.CallToolRequest request) {
        rejectUnknownFields(request, Set.of("verbose"));
        guardedRun(expectedRevision, () -> svc().clear(ORIGIN));
        return mutationResult(null, null, null, verbose);
    }

    // ------------------------------------------------------------------ nodos

    @McpTool(name = "validate", annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false), description = "Revisa la consistencia del diagrama: conexiones/portales huerfanos, niveles sin referencia, childLevelId sin nivel, nodos superpuestos e ids duplicados. Con fix=true corrige los problemas seguros (huerfanos, niveles sin referencia y childLevelId roto). No modifica nada si fix es false.")
    public Map<String, Object> validate(
            @McpToolParam(description = "Corregir los problemas que se pueden resolver sin perder datos", required = false) Boolean fix,
            @McpToolParam(description = "Devolver el diagrama completo ademas del resumen", required = false) Boolean verbose,
            @McpToolParam(description = "Revision que el agente espera; si no coincide, la operacion falla con 409", required = false) Long expectedRevision,
            McpSchema.CallToolRequest request) {
        rejectUnknownFields(request, Set.of("fix", "verbose"));
        DiagramValidator.Report report = guarded(expectedRevision,
                () -> svc().validate(ORIGIN, Boolean.TRUE.equals(fix), expectedRevision));
        DiagramData data = svc().current();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("valid", report.valid());
        out.put("fixedCount", report.fixedCount());
        out.put("issues", report.issues());
        out.put("revision", svc().revision());
        out.put("counts", counts(data));
        if (Boolean.TRUE.equals(verbose)) {
            out.put("diagram", data);
        }
        return out;
    }

    @McpTool(name = "add_node", annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false), description = "Agrega un componente. Si se indica 'preset' se heredan forma, colores, icono y tamano (ver list_presets). Indica 'technology' (tecnologia principal) y 'summary' (que hace). Puede incluir 'docUrl' y 'doc'. Para un nivel interno, pasa 'path'. Si no pasas x/y, al terminar llama a auto_layout para ordenar el diagrama.")
    public Map<String, Object> addNode(
            @McpToolParam(description = "Texto principal del componente", required = true) String label,
            @McpToolParam(description = "Texto secundario / descripcion", required = false) String sublabel,
            @McpToolParam(description = "Tecnologia principal (p. ej. Node.js, PostgreSQL, Kafka)", required = false) String technology,
            @McpToolParam(description = "Resumen de que hace el componente (descripcion del servicio)", required = false) String summary,
            @McpToolParam(description = "Key del preset (p. ej. client, server, database, queue)", required = false) String preset,
            @McpToolParam(description = "Coordenada X (esquina superior izquierda)", required = false) Double x,
            @McpToolParam(description = "Coordenada Y (esquina superior izquierda)", required = false) Double y,
            @McpToolParam(description = "Ancho", required = false) Double width,
            @McpToolParam(description = "Alto", required = false) Double height,
            @McpToolParam(description = "Forma: rectangle, rounded, pill, circle, diamond, hexagon, cylinder", required = false) String shape,
            @McpToolParam(description = "Key del icono (ver list_presets)", required = false) String icon,
            @McpToolParam(description = "Color de relleno (hex)", required = false) String fill,
            @McpToolParam(description = "Color del borde (hex)", required = false) String stroke,
            @McpToolParam(description = "Color del texto (hex)", required = false) String textColor,
            @McpToolParam(description = "Lados conectables: top, right, bottom, left", required = false) List<String> ports,
            @McpToolParam(description = "URL externa de documentacion (http/https); vacio para quitarla", required = false) String docUrl,
            @McpToolParam(description = "Markdown interno de documentacion (maximo 100 KB)", required = false) String doc,
            @McpToolParam(description = "Ruta de ids hasta el nivel donde se agrega (vacia u omitida = raiz)", required = false) List<String> path,
            @McpToolParam(description = "Devolver el diagrama completo ademas del resumen", required = false) Boolean verbose,
            @McpToolParam(description = "Revision que el agente espera; si no coincide, la operacion falla con 409", required = false) Long expectedRevision,
            McpSchema.CallToolRequest request) {
        rejectUnknownFields(request, Set.of("label", "sublabel", "technology", "summary", "preset",
                "x", "y", "width", "height", "shape", "icon", "fill", "stroke", "textColor",
                "ports", "docUrl", "doc", "path", "verbose"));
        DiagramNode node = guarded(expectedRevision, () -> svc().addNode(ORIGIN, path, label, sublabel,
                technology, summary, preset, x, y, width, height, shape, icon, fill, stroke, textColor,
                ports, docUrl, doc));
        return mutationResult(node.id(), null, null, verbose);
    }

    @McpTool(name = "update_node", annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false), description = "Modifica un componente existente. Solo cambia los campos indicados; acepta 'doc' y 'docUrl'. Para un nodo de un nivel interno, pasa 'path'.")
    public Map<String, Object> updateNode(
            @McpToolParam(description = "Id del nodo", required = true) String id,
            @McpToolParam(description = "Nuevo texto principal", required = false) String label,
            @McpToolParam(description = "Nuevo texto secundario", required = false) String sublabel,
            @McpToolParam(description = "Nueva tecnologia principal", required = false) String technology,
            @McpToolParam(description = "Nuevo resumen", required = false) String summary,
            @McpToolParam(description = "Nueva X", required = false) Double x,
            @McpToolParam(description = "Nueva Y", required = false) Double y,
            @McpToolParam(description = "Nuevo ancho", required = false) Double width,
            @McpToolParam(description = "Nuevo alto", required = false) Double height,
            @McpToolParam(description = "Nueva forma", required = false) String shape,
            @McpToolParam(description = "Nuevo icono", required = false) String icon,
            @McpToolParam(description = "Nuevo relleno", required = false) String fill,
            @McpToolParam(description = "Nuevo borde", required = false) String stroke,
            @McpToolParam(description = "Nuevo color de texto", required = false) String textColor,
            @McpToolParam(description = "Nuevos lados conectables", required = false) List<String> ports,
            @McpToolParam(description = "Nueva URL externa (http/https); vacio para quitarla", required = false) String docUrl,
            @McpToolParam(description = "Nuevo Markdown interno; vacio para borrarlo", required = false) String doc,
            @McpToolParam(description = "Ruta de ids hasta el nivel que contiene el nodo (vacia u omitida = raiz)", required = false) List<String> path,
            @McpToolParam(description = "Devolver el diagrama completo ademas del resumen", required = false) Boolean verbose,
            @McpToolParam(description = "Revision que el agente espera; si no coincide, la operacion falla con 409", required = false) Long expectedRevision,
            McpSchema.CallToolRequest request) {
        rejectUnknownFields(request, Set.of("id", "label", "sublabel", "technology", "summary", "x",
                "y", "width", "height", "shape", "icon", "fill", "stroke", "textColor", "ports",
                "docUrl", "doc", "path", "verbose"));
        DiagramNode node = guarded(expectedRevision, () -> svc().updateNode(ORIGIN, path, id, label,
                sublabel, technology, summary, x, y, width, height, shape, icon, fill, stroke,
                textColor, ports, docUrl, doc));
        return mutationResult(node.id(), null, null, verbose);
    }

    @McpTool(name = "move_node", annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false), description = "Mueve un componente a una posicion (esquina superior izquierda).")
    public Map<String, Object> moveNode(
            @McpToolParam(description = "Id del nodo", required = true) String id,
            @McpToolParam(description = "Nueva X", required = true) double x,
            @McpToolParam(description = "Nueva Y", required = true) double y,
            @McpToolParam(description = "Devolver el diagrama completo ademas del resumen", required = false) Boolean verbose,
            @McpToolParam(description = "Revision que el agente espera; si no coincide, la operacion falla con 409", required = false) Long expectedRevision,
            McpSchema.CallToolRequest request) {
        rejectUnknownFields(request, Set.of("id", "x", "y", "verbose"));
        DiagramNode node = guarded(expectedRevision, () -> svc().moveNode(ORIGIN, id, x, y));
        return mutationResult(node.id(), null, null, verbose);
    }

    @McpTool(name = "resize_node", annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false), description = "Redimensiona un componente.")
    public Map<String, Object> resizeNode(
            @McpToolParam(description = "Id del nodo", required = true) String id,
            @McpToolParam(description = "Nuevo ancho", required = true) double width,
            @McpToolParam(description = "Nuevo alto", required = true) double height,
            @McpToolParam(description = "Devolver el diagrama completo ademas del resumen", required = false) Boolean verbose,
            @McpToolParam(description = "Revision que el agente espera; si no coincide, la operacion falla con 409", required = false) Long expectedRevision,
            McpSchema.CallToolRequest request) {
        rejectUnknownFields(request, Set.of("id", "width", "height", "verbose"));
        DiagramNode node = guarded(expectedRevision, () -> svc().resizeNode(ORIGIN, id, width, height));
        return mutationResult(node.id(), null, null, verbose);
    }

    @McpTool(name = "remove_node", annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false), description = "Elimina un componente y todas sus conexiones. Para un nivel interno, pasa 'path'.")
    public Map<String, Object> removeNode(
            @McpToolParam(description = "Id del nodo", required = true) String id,
            @McpToolParam(description = "Ruta de ids hasta el nivel que contiene el nodo (vacia u omitida = raiz)", required = false) List<String> path,
            @McpToolParam(description = "Devolver el diagrama completo ademas del resumen", required = false) Boolean verbose,
            @McpToolParam(description = "Revision que el agente espera; si no coincide, la operacion falla con 409", required = false) Long expectedRevision,
            McpSchema.CallToolRequest request) {
        rejectUnknownFields(request, Set.of("id", "path", "verbose"));
        guardedRun(expectedRevision, () -> svc().removeNode(ORIGIN, path, id));
        return mutationResult(id, null, null, verbose);
    }

    @McpTool(name = "remove_nodes", annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false), description = "Elimina varios componentes (y sus conexiones) a la vez. Para un nivel interno, pasa 'path'.")
    public Map<String, Object> removeNodes(
            @McpToolParam(description = "Ids de los nodos", required = true) List<String> ids,
            @McpToolParam(description = "Ruta de ids hasta el nivel que contiene los nodos (vacia u omitida = raiz)", required = false) List<String> path,
            @McpToolParam(description = "Devolver el diagrama completo ademas del resumen", required = false) Boolean verbose,
            @McpToolParam(description = "Revision que el agente espera; si no coincide, la operacion falla con 409", required = false) Long expectedRevision,
            McpSchema.CallToolRequest request) {
        rejectUnknownFields(request, Set.of("ids", "path", "verbose"));
        guardedRun(expectedRevision, () -> svc().removeNodes(ORIGIN, path, ids));
        return mutationResult(null, ids, null, verbose);
    }

    @McpTool(name = "update_nodes", annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false), description = "Aplica el mismo cambio a varios componentes a la vez (p. ej. el mismo color o tamano). Acepta 'doc' y 'docUrl'. Para nodos de un nivel interno, pasa 'path'.")
    public Map<String, Object> updateNodes(
            @McpToolParam(description = "Ids de los nodos", required = true) List<String> ids,
            @McpToolParam(description = "Nuevo texto principal", required = false) String label,
            @McpToolParam(description = "Nuevo texto secundario", required = false) String sublabel,
            @McpToolParam(description = "Nueva tecnologia principal", required = false) String technology,
            @McpToolParam(description = "Nuevo resumen", required = false) String summary,
            @McpToolParam(description = "Nueva X", required = false) Double x,
            @McpToolParam(description = "Nueva Y", required = false) Double y,
            @McpToolParam(description = "Nuevo ancho", required = false) Double width,
            @McpToolParam(description = "Nuevo alto", required = false) Double height,
            @McpToolParam(description = "Nueva forma", required = false) String shape,
            @McpToolParam(description = "Nuevo icono", required = false) String icon,
            @McpToolParam(description = "Nuevo relleno", required = false) String fill,
            @McpToolParam(description = "Nuevo borde", required = false) String stroke,
            @McpToolParam(description = "Nuevo color de texto", required = false) String textColor,
            @McpToolParam(description = "Nuevos lados conectables", required = false) List<String> ports,
            @McpToolParam(description = "Nueva URL externa (http/https); vacio para quitarla", required = false) String docUrl,
            @McpToolParam(description = "Nuevo Markdown interno; vacio para borrarlo", required = false) String doc,
            @McpToolParam(description = "Ruta de ids hasta el nivel que contiene los nodos (vacia u omitida = raiz)", required = false) List<String> path,
            @McpToolParam(description = "Devolver el diagrama completo ademas del resumen", required = false) Boolean verbose,
            @McpToolParam(description = "Revision que el agente espera; si no coincide, la operacion falla con 409", required = false) Long expectedRevision,
            McpSchema.CallToolRequest request) {
        rejectUnknownFields(request, Set.of("ids", "label", "sublabel", "technology", "summary", "x",
                "y", "width", "height", "shape", "icon", "fill", "stroke", "textColor", "ports",
                "docUrl", "doc", "path", "verbose"));
        guardedRun(expectedRevision, () -> svc().updateNodes(ORIGIN, path, ids, label, sublabel,
                technology, summary, x, y, width, height, shape, icon, fill, stroke, textColor,
                ports, docUrl, doc));
        return mutationResult(null, ids, null, verbose);
    }

    @McpTool(name = "duplicate_node", annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false), description = "Duplica un componente (nuevo id) desplazado un offset. Por defecto 30,30.")
    public Map<String, Object> duplicateNode(
            @McpToolParam(description = "Id del nodo a duplicar", required = true) String id,
            @McpToolParam(description = "Desplazamiento en X", required = false) Double offsetX,
            @McpToolParam(description = "Desplazamiento en Y", required = false) Double offsetY,
            @McpToolParam(description = "Devolver el diagrama completo ademas del resumen", required = false) Boolean verbose,
            @McpToolParam(description = "Revision que el agente espera; si no coincide, la operacion falla con 409", required = false) Long expectedRevision,
            McpSchema.CallToolRequest request) {
        rejectUnknownFields(request, Set.of("id", "offsetX", "offsetY", "verbose"));
        DiagramNode node = guarded(expectedRevision,
                () -> svc().duplicateNode(ORIGIN, id, offsetX, offsetY));
        return mutationResult(node.id(), null, null, verbose);
    }

    @McpTool(name = "z_order_node", annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false), description = "Cambia el orden de apilado (Z) de un componente. Acciones: front, back, forward, backward.")
    public Map<String, Object> zOrderNode(
            @McpToolParam(description = "Id del nodo", required = true) String id,
            @McpToolParam(description = "front | back | forward | backward", required = true) String action,
            @McpToolParam(description = "Devolver el diagrama completo ademas del resumen", required = false) Boolean verbose,
            @McpToolParam(description = "Revision que el agente espera; si no coincide, la operacion falla con 409", required = false) Long expectedRevision,
            McpSchema.CallToolRequest request) {
        rejectUnknownFields(request, Set.of("id", "action", "verbose"));
        DiagramNode node = guarded(expectedRevision, () -> svc().zOrder(ORIGIN, id, action));
        return mutationResult(node.id(), null, null, verbose);
    }

    @McpTool(name = "align_nodes", annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false), description = "Alinea varios componentes entre si. Direcciones: left, right, top, bottom, centerX, centerY.")
    public Map<String, Object> alignNodes(
            @McpToolParam(description = "Ids de al menos dos nodos", required = true) List<String> ids,
            @McpToolParam(description = "left | right | top | bottom | centerX | centerY", required = true) String direction,
            @McpToolParam(description = "Devolver el diagrama completo ademas del resumen", required = false) Boolean verbose,
            @McpToolParam(description = "Revision que el agente espera; si no coincide, la operacion falla con 409", required = false) Long expectedRevision,
            McpSchema.CallToolRequest request) {
        rejectUnknownFields(request, Set.of("ids", "direction", "verbose"));
        guardedRun(expectedRevision, () -> svc().align(ORIGIN, ids, direction));
        return mutationResult(null, ids, null, verbose);
    }

    @McpTool(name = "auto_layout", annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false), description = "Ordena todo el diagrama en capas por niveles (de las fuentes a los sumideros), reduciendo los cruces de lineas. Tolera ciclos. Equivale al boton 'Organizar' del editor.")
    public Map<String, Object> autoLayout(
            @McpToolParam(description = "Direccion del flujo: LR (izquierda->derecha, por defecto) o TB (arriba->abajo)", required = false) String direction,
            @McpToolParam(description = "Separacion entre capas (>=80, por defecto 90)", required = false) Double layerGap,
            @McpToolParam(description = "Separacion entre nodos dentro de una capa (>=44, por defecto 46)", required = false) Double nodeGap,
            @McpToolParam(description = "Agrupar por rol (por defecto false): clientes (icono client/mobile/browser/actor) en la primera columna; datos (database/cache o forma cylinder) y externos (external) en las ultimas. Agrupa mejor pero suele alargar lineas", required = false) Boolean byRole,
            @McpToolParam(description = "Devolver el diagrama completo ademas del resumen", required = false) Boolean verbose,
            @McpToolParam(description = "Revision que el agente espera; si no coincide, la operacion falla con 409", required = false) Long expectedRevision,
            McpSchema.CallToolRequest request) {
        rejectUnknownFields(request, Set.of("direction", "layerGap", "nodeGap", "byRole", "verbose"));
        List<DiagramNode> nodes = guarded(expectedRevision,
                () -> svc().autoLayout(ORIGIN, direction, layerGap, nodeGap, byRole));
        return mutationResult(null, nodes.stream().map(DiagramNode::id).toList(), null, verbose);
    }

    // ------------------------------------------------------------------ conexiones
    @McpTool(name = "add_connection", annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false), description = "Conecta dos componentes. Los lados admiten top/right/bottom/left o 'auto'. Para un nivel interno, pasa 'path'.")
    public Map<String, Object> addConnection(
            @McpToolParam(description = "Id del nodo origen", required = true) String from,
            @McpToolParam(description = "Id del nodo destino", required = true) String to,
            @McpToolParam(description = "Lado de salida (top/right/bottom/left/auto)", required = false) String fromSide,
            @McpToolParam(description = "Lado de entrada (top/right/bottom/left/auto)", required = false) String toSide,
            @McpToolParam(description = "Etiqueta sobre la linea", required = false) String label,
            @McpToolParam(description = "Recorrido: straight, curve, orthogonal", required = false) String routing,
            @McpToolParam(description = "Trazo: solid, dashed, dotted", required = false) String stroke,
            @McpToolParam(description = "Animacion de flujo: none, dash, dots, pulse", required = false) String animation,
            @McpToolParam(description = "Flechas: end, both, none", required = false) String arrow,
            @McpToolParam(description = "Color (hex)", required = false) String color,
            @McpToolParam(description = "Grosor en px", required = false) Double width,
            @McpToolParam(description = "Puntos de paso manuales [{x,y},...] para forzar el recorrido", required = false) List<Point> waypoints,
            @McpToolParam(description = "Ruta de ids hasta el nivel donde se conecta (vacia u omitida = raiz)", required = false) List<String> path,
            @McpToolParam(description = "Devolver el diagrama completo ademas del resumen", required = false) Boolean verbose,
            @McpToolParam(description = "Revision que el agente espera; si no coincide, la operacion falla con 409", required = false) Long expectedRevision,
            McpSchema.CallToolRequest request) {
        rejectUnknownFields(request, Set.of("from", "to", "fromSide", "toSide", "label", "routing",
                "stroke", "animation", "arrow", "color", "width", "waypoints", "path", "verbose"));
        DiagramConnection connection = guarded(expectedRevision,
                () -> svc().addConnection(ORIGIN, path, from, to, fromSide, toSide, label, routing,
                        stroke, animation, arrow, color, width, waypoints));
        return mutationResult(connection.id(), null, null, verbose);
    }

    @McpTool(name = "update_connection", annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false), description = "Modifica una conexion existente. Solo cambia los campos indicados. Para un nivel interno, pasa 'path'.")
    public Map<String, Object> updateConnection(
            @McpToolParam(description = "Id de la conexion", required = true) String id,
            @McpToolParam(description = "Nueva etiqueta", required = false) String label,
            @McpToolParam(description = "Nuevo recorrido", required = false) String routing,
            @McpToolParam(description = "Nuevo trazo", required = false) String stroke,
            @McpToolParam(description = "Nueva animacion", required = false) String animation,
            @McpToolParam(description = "Nuevas flechas", required = false) String arrow,
            @McpToolParam(description = "Nuevo color", required = false) String color,
            @McpToolParam(description = "Nuevo grosor", required = false) Double width,
            @McpToolParam(description = "Puntos de paso manuales [{x,y},...] (vacio = recorrido automatico)", required = false) List<Point> waypoints,
            @McpToolParam(description = "Ruta de ids hasta el nivel que contiene la conexion (vacia u omitida = raiz)", required = false) List<String> path,
            @McpToolParam(description = "Devolver el diagrama completo ademas del resumen", required = false) Boolean verbose,
            @McpToolParam(description = "Revision que el agente espera; si no coincide, la operacion falla con 409", required = false) Long expectedRevision,
            McpSchema.CallToolRequest request) {
        rejectUnknownFields(request, Set.of("id", "label", "routing", "stroke", "animation", "arrow",
                "color", "width", "waypoints", "path", "verbose"));
        DiagramConnection connection = guarded(expectedRevision,
                () -> svc().updateConnection(ORIGIN, path, id, label, routing, stroke, animation,
                        arrow, color, width, waypoints));
        return mutationResult(connection.id(), null, null, verbose);
    }

    @McpTool(name = "remove_connection", annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false), description = "Elimina una conexion. Para un nivel interno, pasa 'path'.")
    public Map<String, Object> removeConnection(
            @McpToolParam(description = "Id de la conexion", required = true) String id,
            @McpToolParam(description = "Ruta de ids hasta el nivel que contiene la conexion (vacia u omitida = raiz)", required = false) List<String> path,
            @McpToolParam(description = "Devolver el diagrama completo ademas del resumen", required = false) Boolean verbose,
            @McpToolParam(description = "Revision que el agente espera; si no coincide, la operacion falla con 409", required = false) Long expectedRevision,
            McpSchema.CallToolRequest request) {
        rejectUnknownFields(request, Set.of("id", "path", "verbose"));
        guardedRun(expectedRevision, () -> svc().removeConnection(ORIGIN, path, id));
        return mutationResult(id, null, null, verbose);
    }

    @McpTool(name = "remove_connections", annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false), description = "Elimina varias conexiones a la vez. Para un nivel interno, pasa 'path'.")
    public Map<String, Object> removeConnections(
            @McpToolParam(description = "Ids de las conexiones", required = true) List<String> ids,
            @McpToolParam(description = "Ruta de ids hasta el nivel que contiene las conexiones (vacia u omitida = raiz)", required = false) List<String> path,
            @McpToolParam(description = "Devolver el diagrama completo ademas del resumen", required = false) Boolean verbose,
            @McpToolParam(description = "Revision que el agente espera; si no coincide, la operacion falla con 409", required = false) Long expectedRevision,
            McpSchema.CallToolRequest request) {
        rejectUnknownFields(request, Set.of("ids", "path", "verbose"));
        guardedRun(expectedRevision, () -> svc().removeConnections(ORIGIN, path, ids));
        return mutationResult(null, ids, null, verbose);
    }

    @McpTool(name = "update_connections", annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false), description = "Aplica el mismo cambio a varias conexiones a la vez (p. ej. la misma animacion). Para un nivel interno, pasa 'path'.")
    public Map<String, Object> updateConnections(
            @McpToolParam(description = "Ids de las conexiones", required = true) List<String> ids,
            @McpToolParam(description = "Nueva etiqueta", required = false) String label,
            @McpToolParam(description = "Nuevo recorrido", required = false) String routing,
            @McpToolParam(description = "Nuevo trazo", required = false) String stroke,
            @McpToolParam(description = "Nueva animacion", required = false) String animation,
            @McpToolParam(description = "Nuevas flechas", required = false) String arrow,
            @McpToolParam(description = "Nuevo color", required = false) String color,
            @McpToolParam(description = "Nuevo grosor", required = false) Double width,
            @McpToolParam(description = "Ruta de ids hasta el nivel que contiene las conexiones (vacia u omitida = raiz)", required = false) List<String> path,
            @McpToolParam(description = "Devolver el diagrama completo ademas del resumen", required = false) Boolean verbose,
            @McpToolParam(description = "Revision que el agente espera; si no coincide, la operacion falla con 409", required = false) Long expectedRevision,
            McpSchema.CallToolRequest request) {
        rejectUnknownFields(request, Set.of("ids", "label", "routing", "stroke", "animation", "arrow",
                "color", "width", "path", "verbose"));
        guardedRun(expectedRevision, () -> svc().updateConnections(ORIGIN, path, ids, label, routing,
                stroke, animation, arrow, color, width));
        return mutationResult(null, ids, null, verbose);
    }

    @McpTool(name = "reconnect_connection", annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false), description = "Reapunta una conexion a otros nodos/lados. Los lados admiten top/right/bottom/left o 'auto'.")
    public Map<String, Object> reconnectConnection(
            @McpToolParam(description = "Id de la conexion", required = true) String id,
            @McpToolParam(description = "Nuevo nodo origen", required = false) String fromNode,
            @McpToolParam(description = "Nuevo lado de salida (top/right/bottom/left/auto)", required = false) String fromSide,
            @McpToolParam(description = "Nuevo nodo destino", required = false) String toNode,
            @McpToolParam(description = "Nuevo lado de entrada (top/right/bottom/left/auto)", required = false) String toSide,
            @McpToolParam(description = "Devolver el diagrama completo ademas del resumen", required = false) Boolean verbose,
            @McpToolParam(description = "Revision que el agente espera; si no coincide, la operacion falla con 409", required = false) Long expectedRevision,
            McpSchema.CallToolRequest request) {
        rejectUnknownFields(request, Set.of("id", "fromNode", "fromSide", "toNode", "toSide",
                "verbose"));
        DiagramConnection connection = guarded(expectedRevision,
                () -> svc().reconnect(ORIGIN, id, fromNode, fromSide, toNode, toSide));
        return mutationResult(connection.id(), null, null, verbose);
    }

    @McpTool(name = "reverse_connection", annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false), description = "Invierte el sentido de una conexion (intercambia origen y destino).")
    public Map<String, Object> reverseConnection(
            @McpToolParam(description = "Id de la conexion", required = true) String id,
            @McpToolParam(description = "Devolver el diagrama completo ademas del resumen", required = false) Boolean verbose,
            @McpToolParam(description = "Revision que el agente espera; si no coincide, la operacion falla con 409", required = false) Long expectedRevision,
            McpSchema.CallToolRequest request) {
        rejectUnknownFields(request, Set.of("id", "verbose"));
        DiagramConnection connection = guarded(expectedRevision,
                () -> svc().reverseConnection(ORIGIN, id));
        return mutationResult(connection.id(), null, null, verbose);
    }

    // ------------------------------------------------------------------ niveles internos

    @McpTool(name = "list_levels", annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false), description = "Lista los niveles internos del diagrama (hasta 3 niveles), con la ruta de ids que hay que pasar a get_level/replace_level.")
    public List<Map<String, Object>> listLevels() {
        List<Map<String, Object>> out = new ArrayList<>();
        collectLevels(svc().current(), List.of(), 1, out);
        return out;
    }

    @McpTool(name = "get_level", annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false), description = "Devuelve el contenido de un nivel interno. 'path' es la lista de ids de nodos desde la raiz (ver list_levels).")
    public DiagramData getLevel(
            @McpToolParam(description = "Ids de nodos desde la raiz hasta el nivel interno", required = true) List<String> path) {
        return svc().level(path);
    }

    @McpTool(name = "create_level", annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false), description = "Crea el nivel interno de un componente (maximo 3 niveles). Genera portales de entrada/salida segun sus conexiones externas.")
    public Map<String, Object> createLevel(
            @McpToolParam(description = "Id del nodo contenedor", required = true) String nodeId,
            @McpToolParam(description = "Nombre del nivel (por defecto, el del nodo)", required = false) String name,
            @McpToolParam(description = "Ruta de ids hasta el nivel que contiene el nodo (vacia = raiz)", required = false) List<String> path,
            @McpToolParam(description = "Devolver el diagrama completo ademas del resumen", required = false) Boolean verbose,
            @McpToolParam(description = "Revision que el agente espera; si no coincide, la operacion falla con 409", required = false) Long expectedRevision,
            McpSchema.CallToolRequest request) {
        rejectUnknownFields(request, Set.of("nodeId", "name", "path", "verbose"));
        List<String> levelPath = path == null ? List.of() : path;
        guarded(expectedRevision, () -> svc().createLevel(ORIGIN, levelPath, nodeId, name));
        svc().focus(ORIGIN, List.of(nodeId));
        List<String> childPath = new ArrayList<>(levelPath);
        childPath.add(nodeId);
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("path", childPath);
        return mutationResult(nodeId, null, extra, verbose);
    }

    @McpTool(name = "remove_level", annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false), description = "Elimina el nivel interno de un componente y todo su contenido.")
    public Map<String, Object> removeLevel(
            @McpToolParam(description = "Id del nodo contenedor", required = true) String nodeId,
            @McpToolParam(description = "Ruta de ids hasta el nivel que contiene el nodo (vacia = raiz)", required = false) List<String> path,
            @McpToolParam(description = "Devolver el diagrama completo ademas del resumen", required = false) Boolean verbose,
            @McpToolParam(description = "Revision que el agente espera; si no coincide, la operacion falla con 409", required = false) Long expectedRevision,
            McpSchema.CallToolRequest request) {
        rejectUnknownFields(request, Set.of("nodeId", "path", "verbose"));
        guardedRun(expectedRevision,
                () -> svc().removeLevel(ORIGIN, path == null ? List.of() : path, nodeId));
        return mutationResult(nodeId, null, null, verbose);
    }

    @McpTool(name = "replace_level", annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false), description = "Reemplaza el contenido de un nivel interno con un DiagramData completo (nodos, conexiones y subniveles).")
    public Map<String, Object> replaceLevel(
            @McpToolParam(description = "Ids de nodos desde la raiz hasta el nivel interno", required = true) List<String> path,
            @McpToolParam(description = "DiagramData del nivel", required = true) Map<String, Object> data,
            @McpToolParam(description = "Devolver el diagrama completo ademas del resumen", required = false) Boolean verbose,
            @McpToolParam(description = "Revision que el agente espera; si no coincide, la operacion falla con 409", required = false) Long expectedRevision,
            McpSchema.CallToolRequest request) {
        rejectUnknownFields(request, Set.of("path", "data", "verbose"));
        DiagramData level = mapper.convertValue(data, DiagramData.class);
        guardedRun(expectedRevision, () -> svc().replaceLevel(ORIGIN, path, level));
        String id = path == null || path.isEmpty() ? null : path.get(path.size() - 1);
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("path", path);
        return mutationResult(id, null, extra, verbose);
    }

    private void collectLevels(DiagramData level, List<String> path, int depth,
            List<Map<String, Object>> out) {
        for (DiagramNode node : level.nodes()) {
            String childId = node.childLevelId();
            DiagramData child = childId == null || childId.isBlank() ? null : level.level(childId);
            if (child == null) {
                continue;
            }
            List<String> childPath = new ArrayList<>(path);
            childPath.add(node.id());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("path", childPath);
            item.put("nodeId", node.id());
            item.put("label", node.label());
            item.put("depth", depth + 1);
            item.put("nodeCount", child.nodes().size());
            item.put("connectionCount", child.connections().size());
            out.add(item);
            collectLevels(child, childPath, depth + 1, out);
        }
    }

    // ------------------------------------------------------------------ import/export

    @McpTool(name = "import_diagram", annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false), description = "Reemplaza el diagrama completo. Acepta el contenido de un archivo .adraw/.json (con o sin envoltorio {format,version,diagram}). Devuelve un resumen compacto; usa verbose=true para el diagrama completo.")
    public Map<String, Object> importDiagram(
            @McpToolParam(description = "JSON del proyecto", required = true) String json,
            @McpToolParam(description = "Devolver el diagrama completo ademas del resumen", required = false) Boolean verbose,
            @McpToolParam(description = "Revision que el agente espera; si no coincide, la operacion falla con 409", required = false) Long expectedRevision,
            McpSchema.CallToolRequest request) {
        rejectUnknownFields(request, Set.of("json", "verbose"));
        guardedRun(expectedRevision, () -> svc().replace(parseDiagram(json), ORIGIN));
        return mutationResult(null, null, null, verbose);
    }

    @McpTool(name = "export_diagram", annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false), description = "Devuelve el diagrama serializado como archivo de proyecto. Formato: adraw | json. Con level exporta solo ese nivel; includeDocs=false omite las fichas Markdown (docUrl se conserva).")
    public String exportDiagram(
            @McpToolParam(description = "adraw | json (por defecto adraw)", required = false) String format,
            @McpToolParam(description = "Incluir fichas Markdown (por defecto true)", required = false) Boolean includeDocs,
            @McpToolParam(description = "Ruta de ids hasta el nivel que se exporta (vacia u omitida = raiz)", required = false) List<String> level,
            McpSchema.CallToolRequest request) {
        rejectUnknownFields(request, Set.of("format", "includeDocs", "level"));
        String fmt = format == null || format.isBlank() ? "adraw" : format.trim().toLowerCase();
        if (!fmt.equals("adraw") && !fmt.equals("json")) {
            throw new IllegalArgumentException("Formato invalido: " + format + ". Usa adraw o json.");
        }
        DiagramData data = level == null || level.isEmpty() ? svc().current() : svc().level(level);
        if (Boolean.FALSE.equals(includeDocs)) {
            data = withoutDocs(data);
        }
        return mapper.writeValueAsString(arquiDrawFile(data));
    }

    /** Copia el diagrama (y sus niveles) sin el Markdown de las fichas. */
    private static DiagramData withoutDocs(DiagramData data) {
        List<DiagramNode> nodes = data.nodes().stream()
                .map(n -> n.withDocumentation(n.docUrl(), ""))
                .toList();
        Map<String, DiagramData> levels = new LinkedHashMap<>();
        data.levels().forEach((key, child) -> levels.put(key, withoutDocs(child)));
        return new DiagramData(data.name(), data.background(), data.gridSize(), nodes,
                data.connections(), levels);
    }

    // ------------------------------------------------------------------ helpers

    /** Respuesta compacta comun a las mutaciones: ok, ids, revision, counts y warnings. */
    private Map<String, Object> mutationResult(String id, List<String> ids, Map<String, Object> extra,
            Boolean verbose) {
        DiagramData data = svc().current();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        if (id != null) {
            out.put("id", id);
        }
        if (ids != null) {
            out.put("ids", ids);
        }
        if (extra != null) {
            out.putAll(extra);
        }
        out.put("revision", svc().revision());
        out.put("counts", counts(data));
        out.put("warnings", List.of());
        if (Boolean.TRUE.equals(verbose)) {
            out.put("diagram", data);
        }
        return out;
    }

    /** Cuenta nodos, conexiones y niveles de todo el arbol. */
    private static Map<String, Object> counts(DiagramData data) {
        int[] values = new int[3];
        countTree(data, values);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("nodes", values[0]);
        out.put("connections", values[1]);
        out.put("levels", values[2]);
        return out;
    }

    private static void countTree(DiagramData data, int[] values) {
        values[0] += data.nodes().size();
        values[1] += data.connections().size();
        values[2] += data.levels().size();
        for (DiagramData child : data.levels().values()) {
            countTree(child, values);
        }
    }

    /** Valida la proyeccion de list_nodes antes de recorrer nada. */
    private static void validateNodeFields(List<String> fields) {
        if (fields == null || fields.isEmpty()) {
            return;
        }
        List<String> unknown = fields.stream()
                .filter(field -> field == null || !NODE_FIELDS.contains(field))
                .distinct()
                .toList();
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("Campos desconocidos en fields: "
                    + String.join(", ", unknown) + ". Campos aceptados: "
                    + String.join(", ", NODE_FIELDS));
        }
    }

    /** Proyecta un nodo a un mapa, sin fichas salvo que se pidan. */
    private static Map<String, Object> projectNode(DiagramNode node, List<String> fields,
            boolean includeDocs) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (fields == null || fields.isEmpty()) {
            for (String field : NODE_FIELDS) {
                if (!"doc".equals(field) || includeDocs) {
                    putNodeField(out, node, field);
                }
            }
            return out;
        }
        Set<String> wanted = new java.util.LinkedHashSet<>(fields);
        for (String field : wanted) {
            putNodeField(out, node, field);
        }
        return out;
    }

    private static void putNodeField(Map<String, Object> out, DiagramNode node, String field) {
        switch (field) {
            case "id" -> out.put("id", node.id());
            case "x" -> out.put("x", node.x());
            case "y" -> out.put("y", node.y());
            case "width" -> out.put("width", node.width());
            case "height" -> out.put("height", node.height());
            case "label" -> out.put("label", node.label());
            case "sublabel" -> out.put("sublabel", node.sublabel());
            case "technology" -> out.put("technology", node.technology());
            case "summary" -> out.put("summary", node.summary());
            case "docUrl" -> out.put("docUrl", node.docUrl());
            case "doc" -> out.put("doc", node.doc());
            case "shape" -> out.put("shape", node.shape());
            case "fill" -> out.put("fill", node.fill());
            case "stroke" -> out.put("stroke", node.stroke());
            case "textColor" -> out.put("textColor", node.textColor());
            case "icon" -> out.put("icon", node.icon());
            case "ports" -> out.put("ports", node.ports());
            case "childLevelId" -> out.put("childLevelId", node.childLevelId());
            case "portal" -> out.put("portal", node.portal());
            default -> throw new IllegalArgumentException("Campo de nodo desconocido: " + field);
        }
    }

    /** Valida el filtro antes de recorrer nodos (aunque el nivel este vacio). */
    private static void validateFilter(Map<String, Object> filter) {
        if (filter == null) {
            return;
        }
        for (String key : filter.keySet()) {
            switch (key) {
                case "labelContains", "stroke" -> {
                    // Filtros disponibles en la fase 2.
                }
                case "status", "priority", "tag" -> throw new IllegalArgumentException(
                        "El filtro '" + key + "' estara disponible en la fase 6.");
                default -> throw new IllegalArgumentException("Filtro desconocido: "
                        + key + ". Usa labelContains o stroke.");
            }
        }
    }

    /** Filtro de list_nodes. status/priority/tag se incorporan en la fase 6. */
    private static boolean matchesFilter(DiagramNode node, Map<String, Object> filter) {
        for (Map.Entry<String, Object> entry : filter.entrySet()) {
            String value = entry.getValue() == null ? "" : String.valueOf(entry.getValue());
            switch (entry.getKey()) {
                case "labelContains" -> {
                    if (!node.label().toLowerCase().contains(value.toLowerCase())) {
                        return false;
                    }
                }
                case "stroke" -> {
                    if (!node.stroke().equalsIgnoreCase(value)) {
                        return false;
                    }
                }
                case "status", "priority", "tag" -> throw new IllegalArgumentException(
                        "El filtro '" + entry.getKey() + "' estara disponible en la fase 6.");
                default -> throw new IllegalArgumentException("Filtro desconocido: "
                        + entry.getKey() + ". Usa labelContains o stroke.");
            }
        }
        return true;
    }

    /** Falla si el cliente envio un argumento que la tool no conoce. */
    private static void rejectUnknownFields(McpSchema.CallToolRequest request, Set<String> allowed) {
        if (request == null || request.arguments() == null) {
            return;
        }
        List<String> unknown = request.arguments().keySet().stream()
                .filter(key -> !allowed.contains(key) && !"expectedRevision".equals(key))
                .sorted()
                .toList();
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("Campos desconocidos: " + String.join(", ", unknown)
                    + ". Campos aceptados: " + String.join(", ", allowed.stream().sorted().toList()));
        }
    }

    /** Envoltorio de proyecto (.adraw / .json). */
    private Map<String, Object> arquiDrawFile(DiagramData data) {
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("format", "arquidraw");
        file.put("version", 2);
        file.put("type", "diagram");
        file.put("diagram", data);
        return file;
    }

    private DiagramData parseDiagram(String json) {
        JsonNode root = mapper.readTree(json);
        JsonNode diagram = root.has("diagram") ? root.get("diagram") : root;
        return mapper.treeToValue(diagram, DiagramData.class);
    }
}
