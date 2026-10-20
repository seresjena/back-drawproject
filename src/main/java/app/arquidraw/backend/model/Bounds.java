package app.arquidraw.backend.model;

/**
 * Rectangulo en coordenadas del mundo (x,y esquina superior izquierda).
 * Espejo de Rect en el frontend (diagram.model.ts).
 */
public record Bounds(double x, double y, double width, double height) {
}
