package app.arquidraw.backend.mcp;

import app.arquidraw.backend.model.DiagramData;
import app.arquidraw.backend.service.RoomManager;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Recursos MCP de solo lectura.
 *
 * - arquidraw://schema   -> el JSON Schema del contrato (.adraw / .json)
 * - arquidraw://diagram  -> el diagrama activo actual
 */
@Service
public class ArquiDrawResources {

    private final RoomManager rooms;
    private final ObjectMapper mapper;

    public ArquiDrawResources(RoomManager rooms, ObjectMapper mapper) {
        this.rooms = rooms;
        this.mapper = mapper;
    }

    @McpResource(
            uri = "arquidraw://schema",
            name = "ArquiDraw JSON Schema",
            title = "Contrato del proyecto ArquiDraw",
            description = "JSON Schema del formato de proyecto .adraw/.json (estructura de nodos, conexiones, presets, etc.).",
            mimeType = "application/json")
    public String schema() {
        try (InputStream in = getClass().getResourceAsStream("/arquidraw.schema.json")) {
            if (in == null) {
                return "{}";
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return "{}";
        }
    }

    @McpResource(
            uri = "arquidraw://diagram",
            name = "Diagrama activo",
            title = "Diagrama activo",
            description = "Estado actual del diagrama (nodos y conexiones).",
            mimeType = "application/json")
    public String diagram() {
        DiagramData data = rooms.forRoom(McpSalaContext.current()).current();
        return mapper.writeValueAsString(data);
    }
}
