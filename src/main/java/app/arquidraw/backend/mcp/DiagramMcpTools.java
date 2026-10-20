package app.arquidraw.backend.mcp;

import app.arquidraw.backend.model.DiagramConnection;
import app.arquidraw.backend.model.DiagramData;
import app.arquidraw.backend.model.DiagramNode;
import app.arquidraw.backend.model.NodePreset;
import app.arquidraw.backend.model.NodePresets;
import app.arquidraw.backend.model.Point;
import app.arquidraw.backend.model.Viewport;
import app.arquidraw.backend.service.DiagramService;
import app.arquidraw.backend.service.RoomManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;
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

    // ------------------------------------------------------------------ lectura

    @McpTool(name = "get_diagram", description = "Devuelve el diagrama activo completo: nombre, fondo, cuadricula, nodos y conexiones.")
    public DiagramData getDiagram() {
        return svc().current();
    }

    @McpTool(name = "get_meta", description = "Resumen del diagrama: nombre, fondo, tamano de cuadricula, cuantos nodos/conexiones hay, el rectangulo que ocupa el contenido (contentBounds) y el viewport del editor (viewport/visibleWorld) para saber que region se esta viendo.")
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

    @McpTool(name = "list_nodes", description = "Lista todos los componentes del diagrama.")
    public List<DiagramNode> listNodes() {
        return svc().current().nodes();
    }

    @McpTool(name = "get_node", description = "Devuelve un componente por su id.")
    public DiagramNode getNode(
            @McpToolParam(description = "Id del nodo", required = true) String id) {
        DiagramNode node = svc().current().node(id);
        if (node == null) {
            throw new IllegalArgumentException("No existe el nodo: " + id);
        }
        return node;
    }

    @McpTool(name = "list_connections", description = "Lista todas las conexiones del diagrama.")
    public List<DiagramConnection> listConnections() {
        return svc().current().connections();
    }

    @McpTool(name = "list_presets", description = "Lista los presets de componentes disponibles (key, forma, colores, icono y tamano por defecto).")
    public List<NodePreset> listPresets() {
        return NodePresets.all();
    }

    @McpTool(name = "focus_nodes", description = "Resalta (un instante) nodos y/o conexiones en el editor para que el usuario vea en que esta trabajando el agente. No modifica el diagrama.")
    public String focusNodes(
            @McpToolParam(description = "Ids de nodos/conexiones a resaltar", required = true) List<String> ids) {
        svc().focus(ORIGIN, ids);
        return "Resaltados: " + ids.size();
    }

    // ------------------------------------------------------------------ diagrama

    @McpTool(name = "rename_diagram", description = "Cambia el nombre del diagrama.")
    public DiagramData renameDiagram(
            @McpToolParam(description = "Nuevo nombre", required = true) String name) {
        return svc().rename(ORIGIN, name);
    }

    @McpTool(name = "set_background", description = "Cambia el fondo del lienzo. Valores: grid, dots, none.")
    public DiagramData setBackground(
            @McpToolParam(description = "grid | dots | none", required = true) String background) {
        return svc().setBackground(ORIGIN, background);
    }

    @McpTool(name = "set_grid_size", description = "Ajusta el tamano de la cuadricula (snap). Entre 6 y 200.")
    public DiagramData setGridSize(
            @McpToolParam(description = "Tamano en unidades del mundo", required = true) Double gridSize) {
        return svc().setGridSize(ORIGIN, gridSize);
    }

    @McpTool(name = "clear_diagram", description = "Elimina todos los nodos y conexiones (mantiene nombre y fondo).")
    public DiagramData clearDiagram() {
        return svc().clear(ORIGIN);
    }

    // ------------------------------------------------------------------ nodos

    @McpTool(name = "add_node", description = "Agrega un componente. Si se indica 'preset' se heredan forma, colores, icono y tamano (ver list_presets).")
    public DiagramNode addNode(
            @McpToolParam(description = "Texto principal del componente", required = true) String label,
            @McpToolParam(description = "Texto secundario / descripcion", required = false) String sublabel,
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
            @McpToolParam(description = "Lados conectables: top, right, bottom, left", required = false) List<String> ports) {
        return svc().addNode(ORIGIN, label, sublabel, summary, preset, x, y, width, height, shape,
                icon, fill, stroke, textColor, ports);
    }

    @McpTool(name = "update_node", description = "Modifica un componente existente. Solo cambia los campos indicados.")
    public DiagramNode updateNode(
            @McpToolParam(description = "Id del nodo", required = true) String id,
            @McpToolParam(description = "Nuevo texto principal", required = false) String label,
            @McpToolParam(description = "Nuevo texto secundario", required = false) String sublabel,
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
            @McpToolParam(description = "Nuevos lados conectables", required = false) List<String> ports) {
        return svc().updateNode(ORIGIN, id, label, sublabel, summary, x, y, width, height, shape,
                icon, fill, stroke, textColor, ports);
    }

    @McpTool(name = "move_node", description = "Mueve un componente a una posicion (esquina superior izquierda).")
    public DiagramNode moveNode(
            @McpToolParam(description = "Id del nodo", required = true) String id,
            @McpToolParam(description = "Nueva X", required = true) double x,
            @McpToolParam(description = "Nueva Y", required = true) double y) {
        return svc().moveNode(ORIGIN, id, x, y);
    }

    @McpTool(name = "resize_node", description = "Redimensiona un componente.")
    public DiagramNode resizeNode(
            @McpToolParam(description = "Id del nodo", required = true) String id,
            @McpToolParam(description = "Nuevo ancho", required = true) double width,
            @McpToolParam(description = "Nuevo alto", required = true) double height) {
        return svc().resizeNode(ORIGIN, id, width, height);
    }

    @McpTool(name = "remove_node", description = "Elimina un componente y todas sus conexiones.")
    public String removeNode(
            @McpToolParam(description = "Id del nodo", required = true) String id) {
        svc().removeNode(ORIGIN, id);
        return "Nodo eliminado: " + id;
    }

    @McpTool(name = "remove_nodes", description = "Elimina varios componentes (y sus conexiones) a la vez.")
    public String removeNodes(
            @McpToolParam(description = "Ids de los nodos", required = true) List<String> ids) {
        svc().removeNodes(ORIGIN, ids);
        return "Nodos eliminados: " + ids.size();
    }

    @McpTool(name = "update_nodes", description = "Aplica el mismo cambio a varios componentes a la vez (p. ej. el mismo color o tamano).")
    public List<DiagramNode> updateNodes(
            @McpToolParam(description = "Ids de los nodos", required = true) List<String> ids,
            @McpToolParam(description = "Nuevo texto principal", required = false) String label,
            @McpToolParam(description = "Nuevo texto secundario", required = false) String sublabel,
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
            @McpToolParam(description = "Nuevos lados conectables", required = false) List<String> ports) {
        return svc().updateNodes(ORIGIN, ids, label, sublabel, summary, x, y, width, height, shape,
                icon, fill, stroke, textColor, ports);
    }

    @McpTool(name = "duplicate_node", description = "Duplica un componente (nuevo id) desplazado un offset. Por defecto 30,30.")
    public DiagramNode duplicateNode(
            @McpToolParam(description = "Id del nodo a duplicar", required = true) String id,
            @McpToolParam(description = "Desplazamiento en X", required = false) Double offsetX,
            @McpToolParam(description = "Desplazamiento en Y", required = false) Double offsetY) {
        return svc().duplicateNode(ORIGIN, id, offsetX, offsetY);
    }

    @McpTool(name = "z_order_node", description = "Cambia el orden de apilado (Z) de un componente. Acciones: front, back, forward, backward.")
    public DiagramNode zOrderNode(
            @McpToolParam(description = "Id del nodo", required = true) String id,
            @McpToolParam(description = "front | back | forward | backward", required = true) String action) {
        return svc().zOrder(ORIGIN, id, action);
    }

    @McpTool(name = "align_nodes", description = "Alinea varios componentes entre si. Direcciones: left, right, top, bottom, centerX, centerY.")
    public List<DiagramNode> alignNodes(
            @McpToolParam(description = "Ids de al menos dos nodos", required = true) List<String> ids,
            @McpToolParam(description = "left | right | top | bottom | centerX | centerY", required = true) String direction) {
        return svc().align(ORIGIN, ids, direction);
    }

    @McpTool(name = "auto_layout", description = "Ordena todo el diagrama en capas por niveles (clientes -> servicios -> datos), reduciendo los cruces de lineas. Equivale al boton 'Organizar' del editor.")
    public List<DiagramNode> autoLayout(
            @McpToolParam(description = "Direccion del flujo: LR (izquierda->derecha, por defecto) o TB (arriba->abajo)", required = false) String direction,
            @McpToolParam(description = "Separacion entre capas (>=80, por defecto 90)", required = false) Double layerGap,
            @McpToolParam(description = "Separacion entre nodos dentro de una capa (>=44, por defecto 46)", required = false) Double nodeGap) {
        return svc().autoLayout(ORIGIN, direction, layerGap, nodeGap);
    }

    // ------------------------------------------------------------------ conexiones
    @McpTool(name = "add_connection", description = "Conecta dos componentes. Los lados admiten top/right/bottom/left o 'auto'.")
    public DiagramConnection addConnection(
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
            @McpToolParam(description = "Puntos de paso manuales [{x,y},...] para forzar el recorrido", required = false) List<Point> waypoints) {
        return svc().addConnection(ORIGIN, from, to, fromSide, toSide, label, routing, stroke,
                animation, arrow, color, width, waypoints);
    }

    @McpTool(name = "update_connection", description = "Modifica una conexion existente. Solo cambia los campos indicados.")
    public DiagramConnection updateConnection(
            @McpToolParam(description = "Id de la conexion", required = true) String id,
            @McpToolParam(description = "Nueva etiqueta", required = false) String label,
            @McpToolParam(description = "Nuevo recorrido", required = false) String routing,
            @McpToolParam(description = "Nuevo trazo", required = false) String stroke,
            @McpToolParam(description = "Nueva animacion", required = false) String animation,
            @McpToolParam(description = "Nuevas flechas", required = false) String arrow,
            @McpToolParam(description = "Nuevo color", required = false) String color,
            @McpToolParam(description = "Nuevo grosor", required = false) Double width,
            @McpToolParam(description = "Puntos de paso manuales [{x,y},...] (vacio = recorrido automatico)", required = false) List<Point> waypoints) {
        return svc().updateConnection(ORIGIN, id, label, routing, stroke, animation, arrow, color,
                width, waypoints);
    }

    @McpTool(name = "remove_connection", description = "Elimina una conexion.")
    public String removeConnection(
            @McpToolParam(description = "Id de la conexion", required = true) String id) {
        svc().removeConnection(ORIGIN, id);
        return "Conexion eliminada: " + id;
    }

    @McpTool(name = "remove_connections", description = "Elimina varias conexiones a la vez.")
    public String removeConnections(
            @McpToolParam(description = "Ids de las conexiones", required = true) List<String> ids) {
        svc().removeConnections(ORIGIN, ids);
        return "Conexiones eliminadas: " + ids.size();
    }

    @McpTool(name = "update_connections", description = "Aplica el mismo cambio a varias conexiones a la vez (p. ej. la misma animacion).")
    public List<DiagramConnection> updateConnections(
            @McpToolParam(description = "Ids de las conexiones", required = true) List<String> ids,
            @McpToolParam(description = "Nueva etiqueta", required = false) String label,
            @McpToolParam(description = "Nuevo recorrido", required = false) String routing,
            @McpToolParam(description = "Nuevo trazo", required = false) String stroke,
            @McpToolParam(description = "Nueva animacion", required = false) String animation,
            @McpToolParam(description = "Nuevas flechas", required = false) String arrow,
            @McpToolParam(description = "Nuevo color", required = false) String color,
            @McpToolParam(description = "Nuevo grosor", required = false) Double width) {
        return svc().updateConnections(ORIGIN, ids, label, routing, stroke, animation, arrow, color, width);
    }

    @McpTool(name = "reconnect_connection", description = "Reapunta una conexion a otros nodos/lados. Los lados admiten top/right/bottom/left o 'auto'.")
    public DiagramConnection reconnectConnection(
            @McpToolParam(description = "Id de la conexion", required = true) String id,
            @McpToolParam(description = "Nuevo nodo origen", required = false) String fromNode,
            @McpToolParam(description = "Nuevo lado de salida (top/right/bottom/left/auto)", required = false) String fromSide,
            @McpToolParam(description = "Nuevo nodo destino", required = false) String toNode,
            @McpToolParam(description = "Nuevo lado de entrada (top/right/bottom/left/auto)", required = false) String toSide) {
        return svc().reconnect(ORIGIN, id, fromNode, fromSide, toNode, toSide);
    }

    @McpTool(name = "reverse_connection", description = "Invierte el sentido de una conexion (intercambia origen y destino).")
    public DiagramConnection reverseConnection(
            @McpToolParam(description = "Id de la conexion", required = true) String id) {
        return svc().reverseConnection(ORIGIN, id);
    }

    // ------------------------------------------------------------------ import/export

    @McpTool(name = "import_diagram", description = "Reemplaza el diagrama completo. Acepta el contenido de un archivo .adraw/.json (con o sin envoltorio {format,version,diagram}).")
    public DiagramData importDiagram(
            @McpToolParam(description = "JSON del proyecto", required = true) String json) {
        return svc().replace(parseDiagram(json), ORIGIN);
    }

    @McpTool(name = "export_diagram", description = "Devuelve el diagrama serializado como archivo de proyecto. Formato: adraw | json.")
    public String exportDiagram(
            @McpToolParam(description = "adraw | json", required = false) String format) {
        return mapper.writeValueAsString(arquiDrawFile(svc().current()));
    }

    // ------------------------------------------------------------------ helpers

    /** Envoltorio de proyecto (.adraw / .json). */
    private Map<String, Object> arquiDrawFile(DiagramData data) {
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("format", "arquidraw");
        file.put("version", 1);
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
