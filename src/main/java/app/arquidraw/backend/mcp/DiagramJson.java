package app.arquidraw.backend.mcp;

import app.arquidraw.backend.model.DiagramData;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

/**
 * Lectura tolerante del JSON del proyecto. A diferencia de la conversion
 * estricta, un campo numerico primitivo ausente o nulo no rompe la importacion:
 * se usa el valor por defecto (0) y el constructor del record lo normaliza.
 * Asi un fragmento parcial puede importarse con {@code merge: true}.
 */
final class DiagramJson {

    private DiagramJson() {
    }

    static DiagramData parse(ObjectMapper mapper, String json) {
        JsonNode root = mapper.readTree(json);
        JsonNode diagram = root.has("diagram") ? root.get("diagram") : root;
        return convert(mapper, diagram, DiagramData.class);
    }

    /** Acepta un String JSON o el objeto ya deserializado (util en {@code batch}). */
    static DiagramData parse(ObjectMapper mapper, Object value) {
        if (value instanceof String json) {
            return parse(mapper, json);
        }
        JsonNode root = mapper.valueToTree(value);
        JsonNode diagram = root.has("diagram") ? root.get("diagram") : root;
        return convert(mapper, diagram, DiagramData.class);
    }

    static <T> T convert(ObjectMapper mapper, Object value, Class<T> type) {
        ObjectReader reader = mapper.reader()
                .without(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .forType(type);
        JsonNode node = mapper.valueToTree(value);
        return reader.readValue(node);
    }
}
