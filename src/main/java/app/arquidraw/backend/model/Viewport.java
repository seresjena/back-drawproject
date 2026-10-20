package app.arquidraw.backend.model;

/**
 * Viewport del editor que el frontend reporta por WebSocket: tamano del lienzo
 * en pixeles de pantalla mas el pan/zoom actuales. Permite al agente saber que
 * region del mundo esta viendo la persona ({@link #visibleWorld()}).
 */
public record Viewport(double width, double height, double zoom, double panX, double panY) {

    public Viewport {
        zoom = zoom <= 0 ? 1 : zoom;
    }

    /** Region del mundo visible con el pan/zoom actuales. */
    public Bounds visibleWorld() {
        return new Bounds(-panX / zoom, -panY / zoom, width / zoom, height / zoom);
    }
}
