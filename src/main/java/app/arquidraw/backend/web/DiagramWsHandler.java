package app.arquidraw.backend.web;

import app.arquidraw.backend.model.Viewport;
import app.arquidraw.backend.service.DiagramChangedEvent;
import app.arquidraw.backend.service.DiagramFocusEvent;
import app.arquidraw.backend.service.DiagramService;
import app.arquidraw.backend.service.RoomManager;
import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.ObjectMapper;

/**
 * Puente en vivo hacia el editor, por salas (tenants).
 *
 * Cada conexion se asocia a una sala (parametro {@code ?sala=TOKEN} de la URL
 * WebSocket). Los mensajes entrantes operan sobre el diagrama de esa sala y los
 * eventos se reenvian solo a las sesiones de la misma sala. Sin token se usa la
 * sala por defecto (compatibilidad con MCP y el modo monousuario).
 *
 * - Cuando el editor envia un "push", reemplaza el estado de su sala (origin =
 *   id del cliente), lo que dispara un broadcast a los demas editores de la sala.
 * - Cuando el editor envia "request", le devuelve solo a el el estado de su sala.
 * - Escucha {@link DiagramChangedEvent} (cambios hechos por MCP o por otros
 *   editores) y reenvia el estado nuevo a la sala correspondiente.
 * - Escucha {@link DiagramFocusEvent} para reenviar solo ids a resaltar.
 */
@Component
public class DiagramWsHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(DiagramWsHandler.class);
    private static final int SEND_BUFFER_LIMIT = 1024 * 1024;
    private static final int SEND_TIME_LIMIT_MS = 10_000;

    private record Conn(String room, WebSocketSession session) {
    }

    private final Map<String, Conn> conns = new ConcurrentHashMap<>();
    private final RoomManager rooms;
    private final ObjectMapper mapper;

    public DiagramWsHandler(RoomManager rooms, ObjectMapper mapper) {
        this.rooms = rooms;
        this.mapper = mapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String room = roomOf(session);
        WebSocketSession safe = new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, SEND_BUFFER_LIMIT);
        conns.put(session.getId(), new Conn(room, safe));
        log.info("Editor conectado a la sala {} ({} sesiones).", room, conns.size());
        // No se envia estado automaticamente: el editor decide al abrir si
        // empuja su diagrama ("push") o pide el del servidor ("request").
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        conns.remove(session.getId());
        log.info("Editor desconectado ({} sesiones).", conns.size());
    }

    @Override
    protected void handleTextMessage(WebSocketSession rawSession, TextMessage message) {
        Conn conn = conns.get(rawSession.getId());
        String room = conn == null ? RoomManager.DEFAULT_ROOM : conn.room();
        WebSocketSession session = conn == null ? rawSession : conn.session();
        DiagramService service = rooms.forRoom(room);
        try {
            WsClientMessage incoming = mapper.readValue(message.getPayload(), WsClientMessage.class);
            if (incoming == null || incoming.type() == null) {
                return;
            }
            switch (incoming.type()) {
                case "push" -> service.replace(incoming.data(),
                        incoming.origin() == null ? "editor" : incoming.origin());
                case "request" -> send(session,
                        WsServerMessage.diagram(service.current(), service.revision(), DiagramService.ORIGIN_SERVER));
                case "viewport" -> service.setViewport(new Viewport(
                        orDefault(incoming.width(), 0),
                        orDefault(incoming.height(), 0),
                        orDefault(incoming.zoom(), 1),
                        orDefault(incoming.panX(), 0),
                        orDefault(incoming.panY(), 0)));
                default -> log.debug("Mensaje WS ignorado: {}", incoming.type());
            }
        } catch (Exception ex) {
            log.warn("Mensaje WS invalido: {}", ex.getMessage());
            if (session != null) {
                send(session, WsServerMessage.error("Mensaje invalido: " + ex.getMessage()));
            }
        }
    }

    @EventListener
    public void onDiagramChanged(DiagramChangedEvent event) {
        broadcast(WsServerMessage.diagram(event.data(), event.revision(), event.origin(),
                event.highlight()), event.room());
    }

    @EventListener
    public void onDiagramFocus(DiagramFocusEvent event) {
        if (event.ids() == null || event.ids().isEmpty()) {
            return;
        }
        broadcast(WsServerMessage.highlight(event.ids(), event.origin()), event.room());
    }

    /** Reenvia a las sesiones de una misma sala. */
    private void broadcast(WsServerMessage out, String room) {
        String json;
        try {
            json = mapper.writeValueAsString(out);
        } catch (Exception ex) {
            log.error("No se pudo serializar el mensaje: {}", ex.getMessage());
            return;
        }
        TextMessage text = new TextMessage(json);
        for (Conn conn : conns.values()) {
            if (!conn.room().equals(room) || !conn.session().isOpen()) {
                continue;
            }
            try {
                conn.session().sendMessage(text);
            } catch (IOException ex) {
                log.debug("Fallo al enviar a {} : {}", conn.session().getId(), ex.getMessage());
            }
        }
    }

    /** Lee el token de sala ({@code ?sala=TOKEN}) de la URL de la conexion. */
    private static String roomOf(WebSocketSession session) {
        URI uri = session.getUri();
        if (uri == null || uri.getQuery() == null) {
            return RoomManager.DEFAULT_ROOM;
        }
        for (String part : uri.getQuery().split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0 && "sala".equalsIgnoreCase(part.substring(0, eq))) {
                return RoomManager.normalize(part.substring(eq + 1));
            }
        }
        return RoomManager.DEFAULT_ROOM;
    }

    private static double orDefault(Double value, double fallback) {
        return value == null ? fallback : value;
    }

    private void send(WebSocketSession session, WsServerMessage out) {
        if (session == null || !session.isOpen()) {
            return;
        }
        try {
            session.sendMessage(new TextMessage(mapper.writeValueAsString(out)));
        } catch (Exception ex) {
            log.debug("No se pudo enviar mensaje: {}", ex.getMessage());
        }
    }
}
