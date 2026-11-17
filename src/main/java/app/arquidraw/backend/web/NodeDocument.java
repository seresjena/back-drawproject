package app.arquidraw.backend.web;

/**
 * Documentacion de un componente para el visor independiente de ArquiDraw.
 * Incluye metadatos utiles para la cabecera de la pagina.
 */
public record NodeDocument(
        String room,
        String nodeId,
        String label,
        String sublabel,
        String technology,
        String summary,
        String docUrl,
        String doc,
        long revision) {
}
