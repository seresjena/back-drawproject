package app.arquidraw.backend.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.arquidraw.backend.model.DiagramData;
import app.arquidraw.backend.model.DiagramNode;
import app.arquidraw.backend.service.RoomManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Fase 2: respuestas compactas, list_nodes filtrado y exportacion por nivel. */
class DiagramMcpToolsPhase2Test {

    private static final List<String> BACKEND = List.of("n_backend");
    private static final String DOC = "## Resumen\nTexto\n\n## Estado\nPendiente\n";

    private final ObjectMapper mapper = new ObjectMapper();
    private RoomManager rooms;
    private DiagramMcpTools tools;

    @BeforeEach
    void setUp() {
        rooms = new RoomManager(event -> {
        });
        tools = new DiagramMcpTools(rooms, mapper);
        rooms.defaultRoom().replace(seed(), "test");
    }

    @Test
    void listNodesOmiteFichasYRespetaLaProyeccion() {
        List<Map<String, Object>> all = tools.listNodes(null, null, false, null, null);

        assertThat(all).hasSize(2);
        assertThat(all).allSatisfy(node -> assertThat(node).doesNotContainKey("doc"));
        assertThat(all.get(0)).containsEntry("id", "n_backend").containsEntry("label", "Backend");

        List<Map<String, Object>> projected = tools.listNodes(null, List.of("id", "label", "stroke"),
                true, null, null);
        assertThat(projected.get(0)).containsOnlyKeys("id", "label", "stroke");

        List<Map<String, Object>> withDocs = tools.listNodes(BACKEND, null, true, null, null);
        assertThat(withDocs.get(0)).containsEntry("doc", DOC);
    }

    @Test
    void listNodesFiltraPorEtiquetaYBorde() {
        List<Map<String, Object>> byLabel = tools.listNodes(null, List.of("id"), false,
                Map.of("labelContains", "back"), null);
        assertThat(byLabel).extracting(m -> m.get("id")).containsExactly("n_backend");

        List<Map<String, Object>> byStroke = tools.listNodes(BACKEND, List.of("id"), false,
                Map.of("stroke", "#888888"), null);
        assertThat(byStroke).extracting(m -> m.get("id")).containsExactly("n_auth");

        assertThatThrownBy(() -> tools.listNodes(null, null, false, Map.of("status", "test"), null))
                .hasMessageContaining("fase 6");
        assertThatThrownBy(() -> tools.listNodes(null, List.of("inventado"), false, null, null))
                .hasMessageContaining("Campos desconocidos");
    }

    @Test
    void exportaSinFichasYPermiteExportarUnNivel() {
        String root = tools.exportDiagram("adraw", false, null, null);
        JsonNode rootJson = mapper.readTree(root);
        assertThat(rootJson.at("/diagram/nodes/0/doc").asText()).isEmpty();
        assertThat(rootJson.at("/diagram/nodes/0/docUrl").asText())
                .isEqualTo("https://example.com/backend");

        String level = tools.exportDiagram("json", false, BACKEND, null);
        JsonNode levelJson = mapper.readTree(level);
        assertThat(levelJson.at("/diagram/nodes")).hasSize(1);
        assertThat(levelJson.at("/diagram/nodes/0/label").asText()).isEqualTo("Authz");

        String withDocs = tools.exportDiagram("adraw", true, BACKEND, null);
        assertThat(mapper.readTree(withDocs).at("/diagram/nodes/0/doc").asText()).isEqualTo(DOC);
    }

    @Test
    void unaMutacionDevuelveResumenCompactoYVerboseIncluyeElDiagrama() {
        Map<String, Object> compact = tools.patchDoc("n_auth", "Estado", "Listo", BACKEND, null,
                false, null, null);

        assertThat(compact).containsEntry("ok", true).containsEntry("id", "n_auth")
                .containsEntry("revision", 2L).containsEntry("warnings", List.of());
        assertThat(compact).doesNotContainKey("diagram");
        @SuppressWarnings("unchecked")
        Map<String, Object> counts = (Map<String, Object>) compact.get("counts");
        assertThat(counts).containsEntry("nodes", 3).containsEntry("connections", 0)
                .containsEntry("levels", 1);
        assertThat(mapper.writeValueAsString(compact).length()).isLessThan(2_000);

        Map<String, Object> verbose = tools.patchDoc("n_auth", "Estado", "Hecho", BACKEND, null,
                true, null, null);
        assertThat(verbose).containsKey("diagram");
    }

    @Test
    void listNodesSinFichasQuedaPorDebajoDeOchoKbConCatorceNodos() {
        List<DiagramNode> nodes = new ArrayList<>();
        String doc = "x".repeat(3_000);
        for (int i = 0; i < 14; i++) {
            nodes.add(new DiagramNode("n_" + i, i * 10, i * 10, 160, 64, "Servicio " + i,
                    "Sub", "Java", "Resumen del servicio", "", doc, "rounded", "#ffffff",
                    "#8a93a6", "#1f2430", "", List.of("top", "right", "bottom", "left"), "", null));
        }
        rooms.defaultRoom().replace(new DiagramData("Dedalo", "grid", 10, nodes, List.of(), Map.of()),
                "test");

        List<Map<String, Object>> result = tools.listNodes(null, null, false, null, null);
        String json = mapper.writeValueAsString(result);

        assertThat(result).hasSize(14);
        assertThat(json).doesNotContain("\"doc\":");
        assertThat(json.length()).isLessThan(8_000);
    }

    private static DiagramData seed() {
        DiagramNode auth = new DiagramNode("n_auth", 20, 40, 160, 64, "Authz", "Identidad",
                "Java", "Valida", "", DOC, "rounded", "#ffffff", "#888888", "#111111", "",
                List.of("top", "right", "bottom", "left"), "", null);
        DiagramNode web = new DiagramNode("n_web", 20, 200, 160, 64, "Web", "Cliente", "Angular",
                "UI", "", "", "rounded", "#ffffff", "#8a93a6", "#111111", "",
                List.of("top", "right", "bottom", "left"), "", null);
        DiagramNode backend = new DiagramNode("n_backend", 260, 40, 240, 120, "Backend",
                "Servicios", "Spring", "API", "https://example.com/backend", "", "rounded",
                "#ffffff", "#8a93a6", "#111111", "",
                List.of("top", "right", "bottom", "left"), "lv_backend", null);
        DiagramData inner = new DiagramData("Backend", "grid", 10, List.of(auth), List.of(), Map.of());
        return new DiagramData("Dedalo", "grid", 10, List.of(backend, web), List.of(),
                Map.of("lv_backend", inner));
    }
}
