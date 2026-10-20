package app.arquidraw.backend.config;

import app.arquidraw.backend.mcp.McpSalaContext;
import app.arquidraw.backend.service.RoomManager;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import java.net.URI;
import java.util.Map;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.ServerRequest;
import tools.jackson.databind.json.JsonMapper;

/**
 * Endpoint MCP por sesion/sala.
 *
 * El agente no se conecta a un unico MCP global: recibe del usuario una direccion
 * con el token de su sesion, p. ej. {@code https://arquidraw.com/mcp?sala=ABC123}
 * (o {@code /mcp/ABC123}). Aqui se extrae ese token de la peticion y se deja en
 * {@link McpSalaContext} para que los tools operen sobre el diagrama de ESA sala,
 * el mismo que ve el usuario en su editor.
 *
 * Sin token, todo cae en la sala por defecto ({@link RoomManager#DEFAULT_ROOM}),
 * que mantiene el comportamiento monousuario y la configuracion MCP clasica.
 */
@Configuration
public class McpConfig {

    @Bean
    public WebMvcStreamableServerTransportProvider webMvcStreamableServerTransportProvider(
            JsonMapper jsonMapper, McpServerStreamableHttpProperties props) {
        return WebMvcStreamableServerTransportProvider.builder()
                .jsonMapper(new JacksonMcpJsonMapper(jsonMapper))
                .mcpEndpoint(props.getMcpEndpoint())
                .keepAliveInterval(props.getKeepAliveInterval())
                .disallowDelete(props.isDisallowDelete())
                .contextExtractor(McpConfig::extractRoom)
                .build();
    }

    private static McpTransportContext extractRoom(ServerRequest request) {
        String room = roomOf(request);
        McpSalaContext.set(room);
        return McpTransportContext.create(Map.of("sala", room));
    }

    private static String roomOf(ServerRequest request) {
        URI uri = request.uri();
        String fromQuery = queryParam(uri, "sala");
        if (fromQuery == null) {
            fromQuery = queryParam(uri, "token");
        }
        if (fromQuery != null) {
            return RoomManager.normalize(fromQuery);
        }
        // Tambien se admite el token como ultimo tramo de la ruta: /mcp/ABC123
        String path = uri.getPath();
        int slash = path.lastIndexOf('/');
        if (slash >= 0 && slash < path.length() - 1) {
            return RoomManager.normalize(path.substring(slash + 1));
        }
        return RoomManager.DEFAULT_ROOM;
    }

    private static String queryParam(URI uri, String key) {
        String query = uri.getQuery();
        if (query == null) {
            return null;
        }
        for (String part : query.split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0 && key.equalsIgnoreCase(part.substring(0, eq))) {
                String value = part.substring(eq + 1);
                return value.isEmpty() ? null : value;
            }
        }
        return null;
    }
}
