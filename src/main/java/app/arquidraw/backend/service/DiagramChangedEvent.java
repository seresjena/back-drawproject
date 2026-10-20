package app.arquidraw.backend.service;

import app.arquidraw.backend.model.DiagramData;
import java.util.List;

/**
 * Se publica cada vez que el diagrama cambia (por MCP o por el editor).
 *
 * @param data      estado nuevo completo
 * @param revision  numero de version incremental (util para descartar ecos)
 * @param origin    quien origino el cambio ("mcp", "server" o el id del cliente web)
 * @param highlight ids de nodos/conexiones que conviene resaltar un instante en el editor
 *                  (para que el usuario vea que fue lo que cambio el agente)
 * @param room      sala (tenant) a la que pertenece el diagrama; el WebSocket solo
 *                  lo reenvia a los clientes conectados a esa misma sala
 */
public record DiagramChangedEvent(DiagramData data, long revision, String origin, List<String> highlight,
        String room) {
}
