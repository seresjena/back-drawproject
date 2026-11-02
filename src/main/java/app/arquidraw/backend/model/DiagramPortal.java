package app.arquidraw.backend.model;

/**
 * Marca un nodo como entrada/salida del nivel interno hacia el nodo padre.
 * Espejo de DiagramPortal en arquidraw.schema.json.
 */
public record DiagramPortal(String direction, String connectionId, String side) {

    public static final String IN = "in";
    public static final String OUT = "out";

    public DiagramPortal {
        direction = (direction == null || direction.isBlank()) ? IN : direction;
        connectionId = connectionId == null ? "" : connectionId;
        side = (side == null || side.isBlank()) ? "auto" : side;
    }
}
