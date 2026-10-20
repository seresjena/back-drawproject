package app.arquidraw.backend.model;

import java.util.List;

/**
 * Un componente del diagrama. Espejo de DiagramNode en arquidraw.schema.json.
 */
public record DiagramNode(
        String id,
        double x,
        double y,
        double width,
        double height,
        String label,
        String sublabel,
        String technology,
        String summary,
        String shape,
        String fill,
        String stroke,
        String textColor,
        String icon,
        List<String> ports) {

    public static final String DEFAULT_SHAPE = "rounded";
    public static final String DEFAULT_FILL = "#ffffff";
    public static final String DEFAULT_STROKE = "#8a93a6";
    public static final String DEFAULT_TEXT = "#1f2430";
    public static final String DEFAULT_PORTS = "left";

    public DiagramNode {
        label = label == null ? "" : label;
        sublabel = sublabel == null ? "" : sublabel;
        technology = technology == null ? "" : technology;
        summary = summary == null ? "" : summary;
        shape = (shape == null || shape.isBlank()) ? DEFAULT_SHAPE : shape;
        fill = (fill == null || fill.isBlank()) ? DEFAULT_FILL : fill;
        stroke = (stroke == null || stroke.isBlank()) ? DEFAULT_STROKE : stroke;
        textColor = (textColor == null || textColor.isBlank()) ? DEFAULT_TEXT : textColor;
        icon = icon == null ? "" : icon;
        ports = ports == null ? List.of() : List.copyOf(ports);
    }

    /**
     * Constructor sin tecnologia (compatibilidad con diagramas y llamadas antiguas).
     */
    public DiagramNode(String id, double x, double y, double width, double height, String label,
            String sublabel, String summary, String shape, String fill, String stroke, String textColor,
            String icon, List<String> ports) {
        this(id, x, y, width, height, label, sublabel, "", summary, shape, fill, stroke, textColor, icon,
                ports);
    }

    /**
     * Constructor sin tecnologia ni resumen (compatibilidad con diagramas y llamadas antiguas).
     */
    public DiagramNode(String id, double x, double y, double width, double height, String label,
            String sublabel, String shape, String fill, String stroke, String textColor, String icon,
            List<String> ports) {
        this(id, x, y, width, height, label, sublabel, "", "", shape, fill, stroke, textColor, icon,
                ports);
    }

    public DiagramNode movedTo(double nx, double ny) {
        return new DiagramNode(id, nx, ny, width, height, label, sublabel, technology, summary, shape,
                fill, stroke, textColor, icon, ports);
    }

    public DiagramNode resizedTo(double nw, double nh) {
        return new DiagramNode(id, x, y, nw, nh, label, sublabel, technology, summary, shape, fill,
                stroke, textColor, icon, ports);
    }

    public DiagramNode relabeled(String newLabel, String newSublabel) {
        return new DiagramNode(id, x, y, width, height, newLabel, newSublabel, technology, summary,
                shape, fill, stroke, textColor, icon, ports);
    }

    public DiagramNode withSummary(String newSummary) {
        return new DiagramNode(id, x, y, width, height, label, sublabel, technology, newSummary, shape,
                fill, stroke, textColor, icon, ports);
    }

    public DiagramNode withTechnology(String newTechnology) {
        return new DiagramNode(id, x, y, width, height, label, sublabel, newTechnology, summary, shape,
                fill, stroke, textColor, icon, ports);
    }
}
