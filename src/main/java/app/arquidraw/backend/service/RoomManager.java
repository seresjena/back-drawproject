package app.arquidraw.backend.service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * Registro de salas (tenants). Cada sala tiene su propio {@link DiagramService}
 * con estado, revision y viewport independientes, de modo que dos dispositivos
 * que no comparten token no se ven entre si.
 *
 * La sala se identifica por un "token" corto que viaja en la URL del editor
 * ({@code ?sala=TOKEN}) y en la conexion WebSocket. Si no se indica ninguno se
 * usa {@link #DEFAULT_ROOM} (compatibilidad con el modo monousuario y con MCP).
 */
@Service
public class RoomManager {

    public static final String DEFAULT_ROOM = "DEFAULT";
    private static final int MAX_ROOM_LENGTH = 32;

    private final ApplicationEventPublisher events;
    private final ConcurrentHashMap<String, DiagramService> rooms = new ConcurrentHashMap<>();
    /** Tokens de solo lectura -> token de la sala que pueden ver. */
    private final ConcurrentHashMap<String, String> viewers = new ConcurrentHashMap<>();

    public RoomManager(ApplicationEventPublisher events) {
        this.events = events;
    }

    /** Devuelve (creando si hace falta) el servicio de la sala indicada. */
    public DiagramService forRoom(String room) {
        String key = normalize(room);
        return rooms.computeIfAbsent(key, r -> new DiagramService(new InMemoryDiagramRepository(), events, r));
    }

    /**
     * Registra un token de espectador para una sala. Lo hace el editor al
     * conectar; es lo que permite compartir un codigo de solo lectura sin
     * revelar el token de edicion.
     */
    public void registerViewer(String viewToken, String room) {
        String view = normalize(viewToken);
        String key = normalize(room);
        if (view.equals(DEFAULT_ROOM) || view.equals(key)) {
            return;
        }
        viewers.put(view, key);
    }

    /** Sala asociada a un token de espectador, o null si no esta registrado. */
    public String roomForViewer(String viewToken) {
        return viewers.get(normalize(viewToken));
    }

    /**
     * Resuelve un token para lecturas (documentacion): si es un token de
     * espectador devuelve su sala; si no, la sala indicada.
     */
    public DiagramService forToken(String token) {
        String key = normalize(token);
        // El mapeo de espectador manda: si antes se creo una sala vacia con
        // ese token (p. ej. una peticion de docs tras reiniciar), no debe
        // ocultar la sala real cuando el editor vuelve a registrarlo.
        String room = viewers.get(key);
        return forRoom(room == null ? key : room);
    }

    /** Sala por defecto (MCP y editores sin token). */
    public DiagramService defaultRoom() {
        return forRoom(DEFAULT_ROOM);
    }

    /** Salas existentes (las que tienen al menos una operacion o conexion). */
    public Set<String> rooms() {
        return Set.copyOf(rooms.keySet());
    }

    /**
     * Normaliza un token de sala: mayusculas, solo letras/numeros/guion y como
     * mucho {@value #MAX_ROOM_LENGTH} caracteres. Vacio -> sala por defecto.
     */
    public static String normalize(String room) {
        if (room == null) {
            return DEFAULT_ROOM;
        }
        String clean = room.trim().toUpperCase().replaceAll("[^A-Z0-9-]", "");
        if (clean.isEmpty()) {
            return DEFAULT_ROOM;
        }
        return clean.length() > MAX_ROOM_LENGTH ? clean.substring(0, MAX_ROOM_LENGTH) : clean;
    }
}
