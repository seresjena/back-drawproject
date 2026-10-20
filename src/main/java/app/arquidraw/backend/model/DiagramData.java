package app.arquidraw.backend.model;

import java.util.List;

/**
 * Diagrama completo. Espejo de DiagramData en arquidraw.schema.json
 * (el mismo objeto que viaja dentro de los archivos .adraw / .json).
 */
public record DiagramData(
        String name,
        String background,
        double gridSize,
        List<DiagramNode> nodes,
        List<DiagramConnection> connections) {

    public static final String DEFAULT_NAME = "Sin titulo";
    public static final String DEFAULT_BACKGROUND = "grid";
    public static final double DEFAULT_GRID = 10;

    public DiagramData {
        name = (name == null || name.isBlank()) ? DEFAULT_NAME : name;
        background = (background == null || background.isBlank()) ? DEFAULT_BACKGROUND : background;
        gridSize = gridSize <= 0 ? DEFAULT_GRID : gridSize;
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        connections = connections == null ? List.of() : List.copyOf(connections);
    }

    public static DiagramData empty() {
        return new DiagramData(DEFAULT_NAME, DEFAULT_BACKGROUND, DEFAULT_GRID, List.of(), List.of());
    }

    public DiagramNode node(String id) {
        return nodes.stream().filter(n -> n.id().equals(id)).findFirst().orElse(null);
    }

    public DiagramConnection connection(String id) {
        return connections.stream().filter(c -> c.id().equals(id)).findFirst().orElse(null);
    }

    /** Rectangulo que ocupan todos los nodos, o null si el diagrama esta vacio. */
    public Bounds bounds() {
        if (nodes.isEmpty()) {
            return null;
        }
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (DiagramNode n : nodes) {
            minX = Math.min(minX, n.x());
            minY = Math.min(minY, n.y());
            maxX = Math.max(maxX, n.x() + n.width());
            maxY = Math.max(maxY, n.y() + n.height());
        }
        return new Bounds(minX, minY, maxX - minX, maxY - minY);
    }
}
