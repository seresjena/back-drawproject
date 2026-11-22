package app.arquidraw.backend.service;

import app.arquidraw.backend.model.DiagramConnection;
import app.arquidraw.backend.model.DiagramData;
import app.arquidraw.backend.model.DiagramNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Valida la consistencia del arbol del diagrama y corrige los problemas que
 * se pueden resolver sin perder informacion (conexiones/portales huerfanos,
 * niveles no referenciados y childLevelId sin nivel).
 */
public final class DiagramValidator {

    private DiagramValidator() {
    }

    /** Problema detectado, con la ruta del nivel donde vive. */
    public record Issue(String code, String message, List<String> path, String nodeId,
            String connectionId, String levelId, List<String> nodeIds, boolean fixable) {
    }

    /** Resultado de una validacion (y de la correccion opcional). */
    public record Report(boolean valid, int fixedCount, List<Issue> issues) {
    }

    public static List<Issue> validate(DiagramData root) {
        Accumulator acc = new Accumulator();
        walk(root, List.of(), null, acc);
        acc.duplicates();
        return List.copyOf(acc.issues);
    }

    public static DiagramData fix(DiagramData root) {
        return fixLevel(root, null, true);
    }

    private static void walk(DiagramData level, List<String> path, DiagramData parent,
            Accumulator acc) {
        Set<String> nodeIds = new HashSet<>();
        for (DiagramNode node : level.nodes()) {
            nodeIds.add(node.id());
            acc.nodeIds.merge(node.id(), 1, Integer::sum);

            String childId = node.childLevelId();
            if (childId != null && !childId.isBlank()) {
                DiagramData child = level.level(childId);
                if (child == null) {
                    acc.add("child_level_missing",
                            "El nodo '" + node.label() + "' apunta al nivel '" + childId
                                    + "', que no existe.",
                            path, node.id(), null, childId, null, true);
                } else {
                    List<String> childPath = new ArrayList<>(path);
                    childPath.add(node.id());
                    walk(child, List.copyOf(childPath), level, acc);
                }
            }

            if (node.portal() != null) {
                String connectionId = node.portal().connectionId();
                if (parent == null) {
                    acc.add("portal_without_parent",
                            "El portal '" + node.label() + "' esta en la raiz y no tiene nivel padre.",
                            path, node.id(), null, null, null, true);
                } else if (connectionId == null || connectionId.isBlank()) {
                    acc.add("portal_missing_connection",
                            "El portal '" + node.label() + "' no indica su conexion de origen.",
                            path, node.id(), null, null, null, true);
                } else if (parent.connection(connectionId) == null) {
                    acc.add("portal_missing_connection",
                            "El portal '" + node.label() + "' apunta a la conexion '" + connectionId
                                    + "', que no existe en el nivel padre.",
                            path, node.id(), connectionId, null, null, true);
                }
            }
        }

        for (DiagramConnection connection : level.connections()) {
            acc.connectionIds.merge(connection.id(), 1, Integer::sum);
            if (connection.from() == null || !nodeIds.contains(connection.from().nodeId())) {
                acc.add("connection_missing_node",
                        "La conexion '" + connection.id() + "' sale de un nodo inexistente.",
                        path, connection.from() == null ? null : connection.from().nodeId(),
                        connection.id(), null, null, true);
            }
            if (connection.to() == null || !nodeIds.contains(connection.to().nodeId())) {
                acc.add("connection_missing_node",
                        "La conexion '" + connection.id() + "' llega a un nodo inexistente.",
                        path, connection.to() == null ? null : connection.to().nodeId(),
                        connection.id(), null, null, true);
            }
        }

        Set<String> referencedLevels = new HashSet<>();
        for (DiagramNode node : level.nodes()) {
            if (node.childLevelId() != null && !node.childLevelId().isBlank()) {
                referencedLevels.add(node.childLevelId());
            }
        }
        for (String levelId : level.levels().keySet()) {
            if (!referencedLevels.contains(levelId)) {
                acc.add("orphan_level",
                        "El nivel '" + levelId + "' no esta referenciado por ningun nodo.",
                        path, null, null, levelId, null, true);
            }
        }

        List<DiagramNode> nodes = level.nodes();
        for (int i = 0; i < nodes.size(); i++) {
            DiagramNode a = nodes.get(i);
            for (int j = i + 1; j < nodes.size(); j++) {
                DiagramNode b = nodes.get(j);
                if (overlapArea(a, b) > 0.5) {
                    acc.add("overlapping_nodes",
                            "Los nodos '" + a.label() + "' y '" + b.label() + "' se superponen.",
                            path, null, null, null, List.of(a.id(), b.id()), false);
                }
            }
        }
    }

    private static DiagramData fixLevel(DiagramData level, DiagramData parent, boolean root) {
        List<DiagramNode> nodes = new ArrayList<>();
        for (DiagramNode node : level.nodes()) {
            DiagramNode next = node;
            String childId = next.childLevelId();
            if (childId != null && !childId.isBlank() && level.level(childId) == null) {
                next = next.withChildLevel("");
            }
            if (next.portal() != null && shouldDropPortal(next, parent, root)) {
                continue;
            }
            nodes.add(next);
        }

        Set<String> nodeIds = new LinkedHashSet<>();
        for (DiagramNode node : nodes) {
            nodeIds.add(node.id());
        }
        List<DiagramConnection> connections = level.connections().stream()
                .filter(c -> c.from() != null && c.to() != null
                        && nodeIds.contains(c.from().nodeId()) && nodeIds.contains(c.to().nodeId()))
                .toList();

        Set<String> referencedLevels = new HashSet<>();
        for (DiagramNode node : nodes) {
            if (node.childLevelId() != null && !node.childLevelId().isBlank()) {
                referencedLevels.add(node.childLevelId());
            }
        }

        DiagramData parentFixed = new DiagramData(level.name(), level.background(), level.gridSize(),
                nodes, connections, Map.of());
        Map<String, DiagramData> levels = new HashMap<>();
        for (Map.Entry<String, DiagramData> entry : level.levels().entrySet()) {
            if (referencedLevels.contains(entry.getKey())) {
                levels.put(entry.getKey(), fixLevel(entry.getValue(), parentFixed, false));
            }
        }
        return new DiagramData(level.name(), level.background(), level.gridSize(), nodes, connections,
                levels);
    }

    private static boolean shouldDropPortal(DiagramNode node, DiagramData parent, boolean root) {
        if (root || parent == null) {
            return true;
        }
        String connectionId = node.portal().connectionId();
        return connectionId == null || connectionId.isBlank()
                || parent.connection(connectionId) == null;
    }

    private static double overlapArea(DiagramNode a, DiagramNode b) {
        double left = Math.max(a.x(), b.x());
        double top = Math.max(a.y(), b.y());
        double right = Math.min(a.x() + a.width(), b.x() + b.width());
        double bottom = Math.min(a.y() + a.height(), b.y() + b.height());
        double width = right - left;
        double height = bottom - top;
        return width > 0 && height > 0 ? width * height : 0;
    }

    private static final class Accumulator {

        private final List<Issue> issues = new ArrayList<>();
        private final Map<String, Integer> nodeIds = new HashMap<>();
        private final Map<String, Integer> connectionIds = new HashMap<>();

        private void add(String code, String message, List<String> path, String nodeId,
                String connectionId, String levelId, List<String> duplicates, boolean fixable) {
            issues.add(new Issue(code, message, List.copyOf(path), nodeId, connectionId, levelId,
                    duplicates == null ? List.of() : List.copyOf(duplicates), fixable));
        }

        private void duplicates() {
            nodeIds.forEach((id, count) -> {
                if (count > 1) {
                    issues.add(new Issue("duplicate_node_id",
                            "El id de nodo '" + id + "' aparece " + count + " veces.",
                            List.of(), id, null, null, List.of(), false));
                }
            });
            connectionIds.forEach((id, count) -> {
                if (count > 1) {
                    issues.add(new Issue("duplicate_connection_id",
                            "El id de conexion '" + id + "' aparece " + count + " veces.",
                            List.of(), null, id, null, List.of(), false));
                }
            });
        }
    }
}
