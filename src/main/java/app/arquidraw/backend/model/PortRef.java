package app.arquidraw.backend.model;

/**
 * Extremo de una conexion: a que nodo y a que lado se conecta.
 * "side" puede ser top/right/bottom/left o "auto".
 */
public record PortRef(String nodeId, String side) {

    public PortRef {
        side = (side == null || side.isBlank()) ? "auto" : side;
    }
}
