package app.arquidraw.backend.model;

import java.util.List;

/**
 * Una conexion (linea) entre dos componentes.
 * Espejo de DiagramConnection en arquidraw.schema.json.
 */
public record DiagramConnection(
        String id,
        PortRef from,
        PortRef to,
        String label,
        String routing,
        String stroke,
        String animation,
        String arrow,
        String color,
        double width,
        List<Point> waypoints) {

    public static final String DEFAULT_ROUTING = "orthogonal";
    public static final String DEFAULT_STROKE = "solid";
    public static final String DEFAULT_ANIMATION = "none";
    public static final String DEFAULT_ARROW = "end";
    public static final String DEFAULT_COLOR = "#7c8aa3";
    public static final double DEFAULT_WIDTH = 2;

    /** Constructor sin puntos de paso (recorrido automatico). */
    public DiagramConnection(String id, PortRef from, PortRef to, String label, String routing,
            String stroke, String animation, String arrow, String color, double width) {
        this(id, from, to, label, routing, stroke, animation, arrow, color, width, null);
    }

    public DiagramConnection {
        label = label == null ? "" : label;
        routing = (routing == null || routing.isBlank()) ? DEFAULT_ROUTING : routing;
        stroke = (stroke == null || stroke.isBlank()) ? DEFAULT_STROKE : stroke;
        animation = (animation == null || animation.isBlank()) ? DEFAULT_ANIMATION : animation;
        arrow = (arrow == null || arrow.isBlank()) ? DEFAULT_ARROW : arrow;
        color = (color == null || color.isBlank()) ? DEFAULT_COLOR : color;
        width = width <= 0 ? DEFAULT_WIDTH : width;
        waypoints = waypoints == null ? List.of() : List.copyOf(waypoints);
    }
}

