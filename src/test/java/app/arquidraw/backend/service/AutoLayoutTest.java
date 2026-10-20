package app.arquidraw.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import app.arquidraw.backend.model.DiagramConnection;
import app.arquidraw.backend.model.DiagramNode;
import app.arquidraw.backend.model.PortRef;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests espejo del auto-layout. Las fixtures de {@code layout-fixtures/} son las
 * MISMAS que usa el frontend ({@code frontend/src/app/core/geometry/layout-fixtures/},
 * layout.spec.ts): si la tool MCP {@code auto_layout} y el boton "Organizar" del
 * editor divergen, falla uno de los dos. Al cambiar el algoritmo, cambiarlo en
 * ambos lados y regenerar {@code expected} en las dos copias.
 */
class AutoLayoutTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @ParameterizedTest
    @ValueSource(strings = { "ciclo-simple", "flujo-tb", "iot-esp32", "iot-esp32-por-rol" })
    void coincideConElFrontend(String name) throws Exception {
        JsonNode f;
        try (InputStream in = getClass().getResourceAsStream("/layout-fixtures/" + name + ".json")) {
            f = JSON.readTree(in);
        }
        List<DiagramNode> nodes = new ArrayList<>();
        for (JsonNode n : f.get("diagram").get("nodes")) {
            nodes.add(new DiagramNode(n.get("id").asString(), 0, 0, n.get("width").asDouble(),
                    n.get("height").asDouble(), "", "", "", "", n.get("shape").asString(), null, null, null,
                    n.get("icon").asString(), List.of()));
        }
        List<DiagramConnection> connections = new ArrayList<>();
        for (JsonNode c : f.get("diagram").get("connections")) {
            connections.add(new DiagramConnection(c.get("id").asString(),
                    new PortRef(c.get("from").get("nodeId").asString(), "auto"),
                    new PortRef(c.get("to").get("nodeId").asString(), "auto"), "", null, null, null, null, null, 0,
                    List.of()));
        }
        JsonNode o = f.get("options");
        Map<String, double[]> pos = AutoLayout.compute(nodes, connections,
                o.has("direction") ? o.get("direction").asString() : null,
                o.has("layerGap") ? o.get("layerGap").asDouble() : null,
                o.has("nodeGap") ? o.get("nodeGap").asDouble() : null,
                o.has("byRole") ? o.get("byRole").asBoolean() : null);

        JsonNode expected = f.get("expected");
        assertThat(pos).hasSize(expected.size());
        for (String id : expected.propertyNames()) {
            assertThat(pos.get(id)[0]).as(id + ".x").isCloseTo(expected.get(id).get(0).asDouble(), within(1e-6));
            assertThat(pos.get(id)[1]).as(id + ".y").isCloseTo(expected.get(id).get(1).asDouble(), within(1e-6));
        }
    }
}
