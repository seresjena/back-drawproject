package app.arquidraw.backend.model;

/**
 * Vista que un agente pide aplicar en el editor (tool MCP {@code set_view}).
 *
 * @param zoom    zoom deseado (0.15 a 4); null = encuadrar todo el contenido
 * @param centerX x del mundo que queda en el centro de la pantalla; null = centro del contenido
 * @param centerY y del mundo que queda en el centro de la pantalla; null = centro del contenido
 */
public record ViewRequest(Double zoom, Double centerX, Double centerY) {
}
