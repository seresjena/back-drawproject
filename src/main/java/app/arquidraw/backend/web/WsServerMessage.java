package app.arquidraw.backend.web;

import app.arquidraw.backend.model.DiagramData;
import app.arquidraw.backend.model.ViewRequest;
import java.util.List;

/**
 * Mensaje que envia el backend al editor web.
 *
 * type: "diagram"   -> estado completo del diagrama (data); puede traer ids a resaltar (highlight)
 *       "highlight" -> solo ids a resaltar (sin cambios en el diagrama)
 *       "view"      -> el agente pide cambiar el zoom/centro de la vista (view)
 *       "error"     -> algo fallo al procesar un mensaje del cliente
 */
public record WsServerMessage(String type, long revision, String origin, DiagramData data,
        List<String> highlight, String message, ViewRequest view) {

    public static WsServerMessage diagram(DiagramData data, long revision, String origin) {
        return diagram(data, revision, origin, List.of());
    }

    public static WsServerMessage diagram(DiagramData data, long revision, String origin,
            List<String> highlight) {
        return new WsServerMessage("diagram", revision, origin, data, highlight, null, null);
    }

    public static WsServerMessage highlight(List<String> ids, String origin) {
        return new WsServerMessage("highlight", 0, origin, null, ids, null, null);
    }

    public static WsServerMessage view(ViewRequest view, String origin) {
        return new WsServerMessage("view", 0, origin, null, null, null, view);
    }

    public static WsServerMessage error(String message) {
        return new WsServerMessage("error", 0, "server", null, null, message, null);
    }
}
