package app.arquidraw.backend.mcp;

/**
 * Token de sala del endpoint MCP en curso ({@code ?sala=TOKEN} o
 * {@code ?token=TOKEN} de la URL con la que el agente se conecta).
 *
 * El transporte MCP (Streamable HTTP) lo deja aqui al iniciar cada peticion,
 * de modo que los tools operen sobre la sala/sesion que el usuario le indico al
 * agente. Si la peticion no trae token queda a {@code null}, y entonces se usa
 * la sala por defecto.
 */
public final class McpSalaContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private McpSalaContext() {
    }

    public static void set(String room) {
        CURRENT.set(room);
    }

    public static String current() {
        return CURRENT.get();
    }
}
