package app.arquidraw.backend.web;

import app.arquidraw.backend.model.DiagramData;
import java.util.List;

/**
 * Mensaje que envia el backend al editor web.
 *
 * type: "diagram"   -> estado completo del diagrama (data); puede traer ids a resaltar (highlight)
 *       "highlight" -> solo ids a resaltar (sin cambios en el diagrama)
 *       "error"     -> algo fallo al procesar un mensaje del cliente
 */
public record WsServerMessage(String type, long revision, String origin, DiagramData data,
        List<String> highlight, String message) {

    public static WsServerMessage diagram(DiagramData data, long revision, String origin) {
        return diagram(data, revision, origin, List.of());
    }

    public static WsServerMessage diagram(DiagramData data, long revision, String origin,
            List<String> highlight) {
        return new WsServerMessage("diagram", revision, origin, data, highlight, null);
    }

    public static WsServerMessage highlight(List<String> ids, String origin) {
        return new WsServerMessage("highlight", 0, origin, null, ids, null);
    }

    public static WsServerMessage error(String message) {
        return new WsServerMessage("error", 0, "server", null, null, message);
    }
}
