package app.arquidraw.backend.service;

import app.arquidraw.backend.model.DiagramConnection;
import app.arquidraw.backend.model.DiagramData;
import app.arquidraw.backend.model.DiagramNode;
import app.arquidraw.backend.model.DiagramPortal;
import app.arquidraw.backend.model.NodePreset;
import app.arquidraw.backend.model.NodePresets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fusiona un diagrama parcial en el actual para la importacion con
 * {@code merge: true}.
 *
 * Hace upsert por id: un nodo o conexion con el mismo id se reemplaza; si no
 * existia, se anade al final. Los niveles internos se fusionan recursivamente
 * por su clave y nunca se borra lo que no venga en el JSON. El nombre, fondo y
 * cuadricula del diagrama actual se conservan; los niveles nuevos usan los
 * metadatos que trae el JSON.
 */
public final class DiagramMerger {

    private DiagramMerger() {
    }

    /** Fusiona {@code incoming} sobre {@code current} sin eliminar nada existente. */
    public static DiagramData merge(DiagramData current, DiagramData incoming) {
        if (incoming == null) {
            return current == null ? DiagramData.empty() : current;
        }
        if (current == null) {
            return incoming;
        }
        List<DiagramNode> nodes = mergeNodes(current.nodes(), incoming.nodes());
        List<DiagramConnection> connections = mergeConnections(current.connections(),
                incoming.connections());
        Map<String, DiagramData> levels = new LinkedHashMap<>(current.levels());
        for (Map.Entry<String, DiagramData> entry : incoming.levels().entrySet()) {
            DiagramData base = current.levels().get(entry.getKey());
            levels.put(entry.getKey(), base == null ? entry.getValue() : merge(base, entry.getValue()));
        }
        return new DiagramData(current.name(), current.background(), current.gridSize(), nodes,
                connections, levels);
    }

    /** Ids de nodos y conexiones de todo el arbol, en orden de aparicion. */
    public static List<String> ids(DiagramData data) {
        List<String> out = new ArrayList<>();
        if (data != null) {
            collectIds(data, out);
        }
        return List.copyOf(out);
    }

    private static List<DiagramNode> mergeNodes(List<DiagramNode> current, List<DiagramNode> incoming) {
        Map<String, DiagramNode> replacements = new LinkedHashMap<>();
        for (DiagramNode node : incoming) {
            replacements.put(node.id(), node);
        }
        Set<String> currentIds = new HashSet<>();
        List<DiagramNode> result = new ArrayList<>(current.size() + incoming.size());
        for (DiagramNode node : current) {
            currentIds.add(node.id());
            DiagramNode replacement = replacements.get(node.id());
            result.add(replacement == null ? node : mergeNode(node, replacement));
        }
        Set<String> added = new HashSet<>();
        for (DiagramNode node : incoming) {
            if (!currentIds.contains(node.id()) && added.add(node.id())) {
                result.add(normalizeNewNode(replacements.get(node.id())));
            }
        }
        return List.copyOf(result);
    }

    /**
     * Un nodo que ya existia conserva su tamano, su nivel interno y su portal
     * cuando el fragmento no los trae (un 0 o un texto vacio no los borra).
     */
    private static DiagramNode mergeNode(DiagramNode base, DiagramNode incoming) {
        double width = incoming.width() > 0 ? incoming.width() : base.width();
        double height = incoming.height() > 0 ? incoming.height() : base.height();
        String childLevelId = incoming.childLevelId().isBlank()
                ? base.childLevelId() : incoming.childLevelId();
        DiagramPortal portal = incoming.portal() == null ? base.portal() : incoming.portal();
        if (width == incoming.width() && height == incoming.height()
                && childLevelId.equals(incoming.childLevelId()) && portal == incoming.portal()) {
            return incoming;
        }
        return new DiagramNode(incoming.id(), incoming.x(), incoming.y(), width, height,
                incoming.label(), incoming.sublabel(), incoming.technology(), incoming.summary(),
                incoming.docUrl(), incoming.doc(), incoming.shape(), incoming.fill(),
                incoming.stroke(), incoming.textColor(), incoming.icon(), incoming.ports(),
                childLevelId, portal);
    }

    /** Un nodo nuevo sin tamano usa el preset por defecto para no quedar en 0x0. */
    private static DiagramNode normalizeNewNode(DiagramNode node) {
        if (node.width() > 0 && node.height() > 0) {
            return node;
        }
        NodePreset preset = NodePresets.defaultPreset();
        return new DiagramNode(node.id(), node.x(), node.y(),
                node.width() > 0 ? node.width() : preset.width(),
                node.height() > 0 ? node.height() : preset.height(),
                node.label(), node.sublabel(), node.technology(), node.summary(), node.docUrl(),
                node.doc(), node.shape(), node.fill(), node.stroke(), node.textColor(),
                node.icon(), node.ports(), node.childLevelId(), node.portal());
    }

    private static List<DiagramConnection> mergeConnections(List<DiagramConnection> current,
            List<DiagramConnection> incoming) {
        Map<String, DiagramConnection> replacements = new LinkedHashMap<>();
        for (DiagramConnection connection : incoming) {
            replacements.put(connection.id(), connection);
        }
        Set<String> currentIds = new HashSet<>();
        List<DiagramConnection> result = new ArrayList<>(current.size() + incoming.size());
        for (DiagramConnection connection : current) {
            currentIds.add(connection.id());
            result.add(replacements.getOrDefault(connection.id(), connection));
        }
        Set<String> added = new HashSet<>();
        for (DiagramConnection connection : incoming) {
            if (!currentIds.contains(connection.id()) && added.add(connection.id())) {
                result.add(replacements.get(connection.id()));
            }
        }
        return List.copyOf(result);
    }

    private static void collectIds(DiagramData level, List<String> out) {
        for (DiagramNode node : level.nodes()) {
            out.add(node.id());
            String childId = node.childLevelId();
            if (childId != null && !childId.isBlank()) {
                DiagramData child = level.level(childId);
                if (child != null) {
                    collectIds(child, out);
                }
            }
        }
        for (DiagramConnection connection : level.connections()) {
            out.add(connection.id());
        }
    }
}
