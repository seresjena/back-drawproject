package app.arquidraw.backend.model;

/**
 * Preset de componente (forma, colores, icono y tamano por defecto).
 * Espejo reducido de NODE_PRESETS en el front (palette.ts).
 */
public record NodePreset(
        String key,
        String label,
        String shape,
        String fill,
        String stroke,
        String textColor,
        String icon,
        double width,
        double height) {

    public DiagramNode toNode(String id, double x, double y, String label, String sublabel) {
        return new DiagramNode(id, x, y, width, height,
                label == null || label.isBlank() ? this.label : label,
                sublabel == null ? "" : sublabel,
                "", shape, fill, stroke, textColor, icon, java.util.List.of("left", "right"));
    }
}
