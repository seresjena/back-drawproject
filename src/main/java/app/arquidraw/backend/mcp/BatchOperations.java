package app.arquidraw.backend.mcp;

import app.arquidraw.backend.model.DiagramConnection;
import app.arquidraw.backend.model.DiagramData;
import app.arquidraw.backend.model.DiagramNode;
import app.arquidraw.backend.model.Point;
import app.arquidraw.backend.service.DiagramMerger;
import app.arquidraw.backend.service.DiagramService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import tools.jackson.databind.ObjectMapper;

/**
 * Aplica una operacion de {@code batch} sobre un {@link DiagramService} de
 * trabajo (copia aislada). Reutiliza la logica de negocio existente sin subir
 * revision ni emitir eventos por operacion: el lote entero se confirma al final
 * con un unico {@code replace}.
 */
final class BatchOperations {

    private static final String ORIGIN = DiagramService.ORIGIN_MCP;

    private static final Set<String> NODE_PATCH_FIELDS = Set.of("label", "sublabel", "technology",
            "summary", "x", "y", "width", "height", "shape", "icon", "fill", "stroke", "textColor",
            "ports", "docUrl", "doc", "path");
    private static final Set<String> CONNECTION_PATCH_FIELDS = Set.of("label", "routing", "stroke",
            "animation", "arrow", "color", "width", "path");

    private final DiagramService target;
    private final ObjectMapper mapper;

    BatchOperations(DiagramService target, ObjectMapper mapper) {
        this.target = target;
        this.mapper = mapper;
    }

    /** Resultado de una operacion: ids tocados y si creo una entidad nueva. */
    record Outcome(String tool, String id, List<String> ids, boolean created) {
    }

    Outcome apply(Map<String, Object> operation) {
        if (operation == null) {
            throw new IllegalArgumentException("La operacion no puede ser nula.");
        }
        Map<String, Object> copy = new LinkedHashMap<>(operation);
        Object toolValue = copy.remove("tool");
        if (toolValue == null) {
            toolValue = copy.remove("operation");
        }
        if (toolValue == null) {
            toolValue = copy.remove("op");
        }
        if (toolValue == null) {
            toolValue = copy.remove("name");
        }
        if (toolValue == null || String.valueOf(toolValue).isBlank()) {
            throw new IllegalArgumentException("Cada operacion debe indicar 'tool'.");
        }
        String tool = normalize(String.valueOf(toolValue));
        Object nested = copy.remove("args");
        if (nested == null) {
            nested = copy.remove("arguments");
        }
        Map<String, Object> args;
        if (nested != null) {
            if (!copy.isEmpty()) {
                throw new IllegalArgumentException("Operacion " + tool
                        + ": no mezcles 'args' con parametros en la misma operacion.");
            }
            if (!(nested instanceof Map<?, ?> raw)) {
                throw new IllegalArgumentException("Operacion " + tool + ": 'args' debe ser un objeto.");
            }
            args = stringKeyed(raw);
        } else {
            args = copy;
        }
        return switch (tool) {
            case "add_node" -> addNode(args);
            case "update_node" -> updateNode(args);
            case "update_nodes" -> updateNodes(args);
            case "move_node" -> moveNode(args);
            case "resize_node" -> resizeNode(args);
            case "duplicate_node" -> duplicateNode(args);
            case "z_order_node" -> zOrderNode(args);
            case "align_nodes" -> alignNodes(args);
            case "auto_layout" -> autoLayout(args);
            case "remove_node" -> removeNode(args);
            case "remove_nodes" -> removeNodes(args);
            case "add_connection" -> addConnection(args);
            case "update_connection" -> updateConnection(args);
            case "update_connections" -> updateConnections(args);
            case "reconnect_connection" -> reconnectConnection(args);
            case "reverse_connection" -> reverseConnection(args);
            case "remove_connection" -> removeConnection(args);
            case "remove_connections" -> removeConnections(args);
            case "rename_diagram" -> renameDiagram(args);
            case "set_background" -> setBackground(args);
            case "set_grid_size" -> setGridSize(args);
            case "clear_diagram" -> clearDiagram(args);
            case "set_node_doc" -> setNodeDoc(args);
            case "append_node_doc" -> appendNodeDoc(args);
            case "set_node_doc_url" -> setNodeDocUrl(args);
            case "remove_node_doc" -> removeNodeDoc(args);
            case "update_doc" -> updateDoc(args);
            case "patch_doc" -> patchDoc(args);
            case "create_level" -> createLevel(args);
            case "remove_level" -> removeLevel(args);
            case "replace_level" -> replaceLevel(args);
            case "import_diagram" -> importDiagram(args);
            default -> throw new IllegalArgumentException("Operacion no soportada en batch: " + tool);
        };
    }

    /** Nombre de la tool de una operacion (para mensajes de error). */
    static String toolName(Map<String, Object> operation) {
        if (operation == null) {
            return "?";
        }
        for (String key : List.of("tool", "operation", "op", "name")) {
            Object value = operation.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value);
            }
        }
        return "?";
    }

    /** Nombre normalizado (minusculas y guiones como guiones bajos). */
    static String normalizedTool(Map<String, Object> operation) {
        return normalize(toolName(operation));
    }

    // ------------------------------------------------------------------ nodos

    private Outcome addNode(Map<String, Object> args) {
        allow("add_node", args, Set.of("id", "label", "sublabel", "technology", "summary", "preset",
                "x", "y", "width", "height", "shape", "icon", "fill", "stroke", "textColor", "ports",
                "docUrl", "doc", "path"));
        DiagramNode node = target.addNode(ORIGIN, path(args), str(args, "id"), str(args, "label"),
                str(args, "sublabel"), str(args, "technology"), str(args, "summary"),
                str(args, "preset"), num(args, "x"), num(args, "y"), num(args, "width"),
                num(args, "height"), str(args, "shape"), str(args, "icon"), str(args, "fill"),
                str(args, "stroke"), str(args, "textColor"), strList(args, "ports"),
                str(args, "docUrl"), str(args, "doc"));
        return new Outcome("add_node", node.id(), List.of(node.id()), true);
    }

    private Outcome updateNode(Map<String, Object> args) {
        Set<String> allowed = new TreeSet<>(NODE_PATCH_FIELDS);
        allowed.add("id");
        allow("update_node", args, allowed);
        String id = requireStr(args, "id");
        DiagramNode node = target.updateNode(ORIGIN, path(args), id, str(args, "label"),
                str(args, "sublabel"), str(args, "technology"), str(args, "summary"),
                num(args, "x"), num(args, "y"), num(args, "width"), num(args, "height"),
                str(args, "shape"), str(args, "icon"), str(args, "fill"), str(args, "stroke"),
                str(args, "textColor"), strList(args, "ports"), str(args, "docUrl"),
                str(args, "doc"));
        return new Outcome("update_node", node.id(), List.of(node.id()), false);
    }

    private Outcome updateNodes(Map<String, Object> args) {
        Set<String> allowed = new TreeSet<>(NODE_PATCH_FIELDS);
        allowed.add("ids");
        allow("update_nodes", args, allowed);
        List<String> ids = requireList(args, "ids");
        List<DiagramNode> nodes = target.updateNodes(ORIGIN, path(args), ids, str(args, "label"),
                str(args, "sublabel"), str(args, "technology"), str(args, "summary"),
                num(args, "x"), num(args, "y"), num(args, "width"), num(args, "height"),
                str(args, "shape"), str(args, "icon"), str(args, "fill"), str(args, "stroke"),
                str(args, "textColor"), strList(args, "ports"), str(args, "docUrl"),
                str(args, "doc"));
        List<String> touched = nodes.stream().map(DiagramNode::id).toList();
        return new Outcome("update_nodes", null, touched, false);
    }

    private Outcome moveNode(Map<String, Object> args) {
        allow("move_node", args, Set.of("id", "x", "y"));
        String id = requireStr(args, "id");
        DiagramNode node = target.moveNode(ORIGIN, id, requireDouble(args, "x"),
                requireDouble(args, "y"));
        return new Outcome("move_node", node.id(), List.of(node.id()), false);
    }

    private Outcome resizeNode(Map<String, Object> args) {
        allow("resize_node", args, Set.of("id", "width", "height"));
        String id = requireStr(args, "id");
        DiagramNode node = target.resizeNode(ORIGIN, id, requireDouble(args, "width"),
                requireDouble(args, "height"));
        return new Outcome("resize_node", node.id(), List.of(node.id()), false);
    }

    private Outcome duplicateNode(Map<String, Object> args) {
        allow("duplicate_node", args, Set.of("id", "offsetX", "offsetY"));
        String id = requireStr(args, "id");
        DiagramNode node = target.duplicateNode(ORIGIN, id, num(args, "offsetX"),
                num(args, "offsetY"));
        return new Outcome("duplicate_node", node.id(), List.of(node.id()), true);
    }

    private Outcome zOrderNode(Map<String, Object> args) {
        allow("z_order_node", args, Set.of("id", "action"));
        String id = requireStr(args, "id");
        DiagramNode node = target.zOrder(ORIGIN, id, requireStr(args, "action"));
        return new Outcome("z_order_node", node.id(), List.of(node.id()), false);
    }

    private Outcome alignNodes(Map<String, Object> args) {
        allow("align_nodes", args, Set.of("ids", "direction"));
        List<String> ids = requireList(args, "ids");
        List<DiagramNode> nodes = target.align(ORIGIN, ids, requireStr(args, "direction"));
        List<String> touched = nodes.stream().map(DiagramNode::id).toList();
        return new Outcome("align_nodes", null, touched, false);
    }

    private Outcome autoLayout(Map<String, Object> args) {
        allow("auto_layout", args, Set.of("direction", "layerGap", "nodeGap", "byRole"));
        List<DiagramNode> nodes = target.autoLayout(ORIGIN, str(args, "direction"),
                num(args, "layerGap"), num(args, "nodeGap"), bool(args, "byRole"));
        List<String> touched = nodes.stream().map(DiagramNode::id).toList();
        return new Outcome("auto_layout", null, touched, false);
    }

    private Outcome removeNode(Map<String, Object> args) {
        allow("remove_node", args, Set.of("id", "path"));
        String id = requireStr(args, "id");
        target.removeNode(ORIGIN, path(args), id);
        return new Outcome("remove_node", id, List.of(id), false);
    }

    private Outcome removeNodes(Map<String, Object> args) {
        allow("remove_nodes", args, Set.of("ids", "path"));
        List<String> ids = requireList(args, "ids");
        target.removeNodes(ORIGIN, path(args), ids);
        return new Outcome("remove_nodes", null, ids, false);
    }

    // ------------------------------------------------------------------ conexiones

    private Outcome addConnection(Map<String, Object> args) {
        allow("add_connection", args, Set.of("id", "from", "to", "fromSide", "toSide", "label",
                "routing", "stroke", "animation", "arrow", "color", "width", "waypoints", "path"));
        DiagramConnection connection = target.addConnection(ORIGIN, path(args), str(args, "id"),
                requireStr(args, "from"), requireStr(args, "to"), str(args, "fromSide"),
                str(args, "toSide"), str(args, "label"), str(args, "routing"), str(args, "stroke"),
                str(args, "animation"), str(args, "arrow"), str(args, "color"), num(args, "width"),
                points(args));
        return new Outcome("add_connection", connection.id(), List.of(connection.id()), true);
    }

    private Outcome updateConnection(Map<String, Object> args) {
        Set<String> allowed = new TreeSet<>(CONNECTION_PATCH_FIELDS);
        allowed.add("id");
        allowed.add("waypoints");
        allow("update_connection", args, allowed);
        String id = requireStr(args, "id");
        DiagramConnection connection = target.updateConnection(ORIGIN, path(args), id,
                str(args, "label"), str(args, "routing"), str(args, "stroke"),
                str(args, "animation"), str(args, "arrow"), str(args, "color"), num(args, "width"),
                points(args));
        return new Outcome("update_connection", connection.id(), List.of(connection.id()), false);
    }

    private Outcome updateConnections(Map<String, Object> args) {
        Set<String> allowed = new TreeSet<>(CONNECTION_PATCH_FIELDS);
        allowed.add("ids");
        allow("update_connections", args, allowed);
        List<String> ids = requireList(args, "ids");
        List<DiagramConnection> connections = target.updateConnections(ORIGIN, path(args), ids,
                str(args, "label"), str(args, "routing"), str(args, "stroke"),
                str(args, "animation"), str(args, "arrow"), str(args, "color"), num(args, "width"));
        List<String> touched = connections.stream().map(DiagramConnection::id).toList();
        return new Outcome("update_connections", null, touched, false);
    }

    private Outcome reconnectConnection(Map<String, Object> args) {
        allow("reconnect_connection", args, Set.of("id", "fromNode", "fromSide", "toNode", "toSide"));
        String id = requireStr(args, "id");
        DiagramConnection connection = target.reconnect(ORIGIN, id, str(args, "fromNode"),
                str(args, "fromSide"), str(args, "toNode"), str(args, "toSide"));
        return new Outcome("reconnect_connection", connection.id(), List.of(connection.id()), false);
    }

    private Outcome reverseConnection(Map<String, Object> args) {
        allow("reverse_connection", args, Set.of("id"));
        String id = requireStr(args, "id");
        DiagramConnection connection = target.reverseConnection(ORIGIN, id);
        return new Outcome("reverse_connection", connection.id(), List.of(connection.id()), false);
    }

    private Outcome removeConnection(Map<String, Object> args) {
        allow("remove_connection", args, Set.of("id", "path"));
        String id = requireStr(args, "id");
        target.removeConnection(ORIGIN, path(args), id);
        return new Outcome("remove_connection", id, List.of(id), false);
    }

    private Outcome removeConnections(Map<String, Object> args) {
        allow("remove_connections", args, Set.of("ids", "path"));
        List<String> ids = requireList(args, "ids");
        target.removeConnections(ORIGIN, path(args), ids);
        return new Outcome("remove_connections", null, ids, false);
    }

    // ------------------------------------------------------------------ diagrama

    private Outcome renameDiagram(Map<String, Object> args) {
        allow("rename_diagram", args, Set.of("name"));
        target.rename(ORIGIN, requireStr(args, "name"));
        return new Outcome("rename_diagram", null, List.of(), false);
    }

    private Outcome setBackground(Map<String, Object> args) {
        allow("set_background", args, Set.of("background"));
        target.setBackground(ORIGIN, requireStr(args, "background"));
        return new Outcome("set_background", null, List.of(), false);
    }

    private Outcome setGridSize(Map<String, Object> args) {
        allow("set_grid_size", args, Set.of("gridSize"));
        target.setGridSize(ORIGIN, requireDouble(args, "gridSize"));
        return new Outcome("set_grid_size", null, List.of(), false);
    }

    private Outcome clearDiagram(Map<String, Object> args) {
        allow("clear_diagram", args, Set.of());
        target.clear(ORIGIN);
        return new Outcome("clear_diagram", null, List.of(), false);
    }

    // ------------------------------------------------------------------ documentacion

    private Outcome setNodeDoc(Map<String, Object> args) {
        allow("set_node_doc", args, Set.of("id", "doc"));
        String id = requireStr(args, "id");
        DiagramNode node = target.setDoc(ORIGIN, id, requireValue(args, "doc"));
        return new Outcome("set_node_doc", node.id(), List.of(node.id()), false);
    }

    private Outcome appendNodeDoc(Map<String, Object> args) {
        allow("append_node_doc", args, Set.of("id", "doc"));
        String id = requireStr(args, "id");
        DiagramNode node = target.appendDoc(ORIGIN, id, requireValue(args, "doc"));
        return new Outcome("append_node_doc", node.id(), List.of(node.id()), false);
    }

    private Outcome setNodeDocUrl(Map<String, Object> args) {
        allow("set_node_doc_url", args, Set.of("id", "docUrl"));
        String id = requireStr(args, "id");
        DiagramNode node = target.setDocUrl(ORIGIN, id, requireValue(args, "docUrl"));
        return new Outcome("set_node_doc_url", node.id(), List.of(node.id()), false);
    }

    private Outcome removeNodeDoc(Map<String, Object> args) {
        allow("remove_node_doc", args, Set.of("id"));
        String id = requireStr(args, "id");
        DiagramNode node = target.clearDoc(ORIGIN, id);
        return new Outcome("remove_node_doc", node.id(), List.of(node.id()), false);
    }

    private Outcome updateDoc(Map<String, Object> args) {
        allow("update_doc", args, Set.of("id", "markdown", "path"));
        String id = requireStr(args, "id");
        DiagramNode node = target.updateDoc(ORIGIN, path(args), id,
                requireValue(args, "markdown"), null);
        return new Outcome("update_doc", node.id(), List.of(node.id()), false);
    }

    private Outcome patchDoc(Map<String, Object> args) {
        allow("patch_doc", args, Set.of("id", "section", "markdown", "mode", "path"));
        String id = requireStr(args, "id");
        DiagramNode node = target.patchDoc(ORIGIN, path(args), id, requireStr(args, "section"),
                requireValue(args, "markdown"), str(args, "mode"), null);
        return new Outcome("patch_doc", node.id(), List.of(node.id()), false);
    }

    // ------------------------------------------------------------------ niveles internos

    private Outcome createLevel(Map<String, Object> args) {
        allow("create_level", args, Set.of("nodeId", "name", "path"));
        String nodeId = requireStr(args, "nodeId");
        target.createLevel(ORIGIN, path(args), nodeId, str(args, "name"));
        return new Outcome("create_level", nodeId, List.of(nodeId), false);
    }

    private Outcome removeLevel(Map<String, Object> args) {
        allow("remove_level", args, Set.of("nodeId", "path"));
        String nodeId = requireStr(args, "nodeId");
        target.removeLevel(ORIGIN, path(args), nodeId);
        return new Outcome("remove_level", nodeId, List.of(nodeId), false);
    }

    private Outcome replaceLevel(Map<String, Object> args) {
        allow("replace_level", args, Set.of("path", "data"));
        List<String> path = requireList(args, "path");
        Object data = args.get("data");
        if (data == null) {
            throw new IllegalArgumentException("Falta 'data'.");
        }
        target.replaceLevel(ORIGIN, path, DiagramJson.convert(mapper, data, DiagramData.class));
        String id = path.get(path.size() - 1);
        return new Outcome("replace_level", id, path, false);
    }

    private Outcome importDiagram(Map<String, Object> args) {
        allow("import_diagram", args, Set.of("json", "merge"));
        if (!args.containsKey("json") || args.get("json") == null) {
            throw new IllegalArgumentException("Falta 'json'.");
        }
        DiagramData data = DiagramJson.parse(mapper, args.get("json"));
        boolean merge = Boolean.TRUE.equals(bool(args, "merge"));
        target.importDiagram(ORIGIN, data, merge);
        return new Outcome("import_diagram", null, DiagramMerger.ids(data), false);
    }

    // ------------------------------------------------------------------ helpers

    private static void allow(String tool, Map<String, Object> args, Set<String> allowed) {
        if (args.containsKey("expectedRevision")) {
            throw new IllegalArgumentException("Operacion " + tool
                    + ": expectedRevision va en el batch, no en cada operacion.");
        }
        List<String> unknown = args.keySet().stream()
                .filter(key -> key == null || !allowed.contains(key))
                .sorted()
                .toList();
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("Operacion " + tool + ": campos desconocidos: "
                    + String.join(", ", unknown) + ". Aceptados: "
                    + String.join(", ", new TreeSet<>(allowed)));
        }
    }

    private static Map<String, Object> stringKeyed(Map<?, ?> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            out.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return out;
    }

    private static String normalize(String tool) {
        return tool == null ? "" : tool.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }

    private static List<String> path(Map<String, Object> args) {
        return strList(args, "path");
    }

    private static String str(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static String requireStr(Map<String, Object> args, String key) {
        String value = str(args, key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Falta '" + key + "'.");
        }
        return value;
    }

    /** Valor obligatorio que puede ser vacio (para borrar una ficha, por ejemplo). */
    private static String requireValue(Map<String, Object> args, String key) {
        if (!args.containsKey(key) || args.get(key) == null) {
            throw new IllegalArgumentException("Falta '" + key + "'.");
        }
        return String.valueOf(args.get(key));
    }

    private static Double num(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text) {
            if (text.isBlank()) {
                return null;
            }
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("'" + key + "' debe ser numerico.");
            }
        }
        throw new IllegalArgumentException("'" + key + "' debe ser numerico.");
    }

    private static double requireDouble(Map<String, Object> args, String key) {
        Double value = num(args, key);
        if (value == null) {
            throw new IllegalArgumentException("Falta '" + key + "'.");
        }
        return value;
    }

    private static Boolean bool(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof String text) {
            if (text.equalsIgnoreCase("true")) {
                return true;
            }
            if (text.equalsIgnoreCase("false")) {
                return false;
            }
        }
        throw new IllegalArgumentException("'" + key + "' debe ser true o false.");
    }

    private static List<String> strList(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof List<?> list) {
            List<String> out = new ArrayList<>(list.size());
            for (Object item : list) {
                if (item == null) {
                    throw new IllegalArgumentException("'" + key + "' no puede contener valores nulos.");
                }
                out.add(String.valueOf(item));
            }
            return List.copyOf(out);
        }
        if (value instanceof String text) {
            return List.of(text);
        }
        throw new IllegalArgumentException("'" + key + "' debe ser una lista de textos.");
    }

    private static List<String> requireList(Map<String, Object> args, String key) {
        List<String> value = strList(args, key);
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("Falta '" + key + "'.");
        }
        return value;
    }

    private List<Point> points(Map<String, Object> args) {
        Object value = args.get("waypoints");
        if (value == null) {
            return null;
        }
        if (value instanceof List<?> list && list.isEmpty()) {
            return List.of();
        }
        Point[] array = DiagramJson.convert(mapper, value, Point[].class);
        return List.of(array);
    }
}
