package app.arquidraw.backend.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.arquidraw.backend.model.DiagramConnection;
import app.arquidraw.backend.model.DiagramData;
import app.arquidraw.backend.model.DiagramNode;
import app.arquidraw.backend.model.PortRef;
import app.arquidraw.backend.service.RevisionConflictException;
import app.arquidraw.backend.service.RoomManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** Fase 4: lotes atomicos e importacion parcial por id. */
class DiagramMcpToolsPhase4Test {

    private final ObjectMapper mapper = new ObjectMapper();
    private RoomManager rooms;
    private DiagramMcpTools tools;

    @BeforeEach
    void setUp() {
        rooms = new RoomManager(event -> {
        });
        tools = new DiagramMcpTools(rooms, mapper);
    }

    @Test
    void batchAplicaTodoEnOrdenYGeneraUnaSolaRevision() {
        Map<String, Object> addApi = operation("add_node", Map.of("id", "n_api", "label", "API",
                "x", 20, "y", 20));
        Map<String, Object> addDb = operation("add_node", Map.of("id", "n_db", "label", "DB",
                "x", 300, "y", 20));
        Map<String, Object> connect = operation("add_connection", Map.of("id", "c_api_db",
                "from", "n_api", "to", "n_db"));
        Map<String, Object> rename = operation("update_node", Map.of("id", "n_api", "label",
                "API v2"));

        Map<String, Object> result = tools.batch(List.of(addApi, addDb, connect, rename), false,
                null, null);

        assertThat(result).containsEntry("ok", true).containsEntry("revision", 1L);
        assertThat(result.get("ids")).isEqualTo(List.of("n_api", "n_db", "c_api_db"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
        assertThat(results).hasSize(4);
        assertThat(results.get(0)).containsEntry("tool", "add_node").containsEntry("id", "n_api");
        assertThat(results.get(2)).containsEntry("tool", "add_connection")
                .containsEntry("id", "c_api_db");

        DiagramData current = rooms.defaultRoom().current();
        assertThat(current.nodes()).extracting(DiagramNode::id)
                .containsExactly("n_api", "n_db");
        assertThat(current.node("n_api").label()).isEqualTo("API v2");
        assertThat(current.connections()).extracting(DiagramConnection::id)
                .containsExactly("c_api_db");
        assertThat(rooms.defaultRoom().revision()).isEqualTo(1);
    }

    @Test
    void unFalloEnMedioNoDejaCambiosParciales() {
        rooms.defaultRoom().replace(new DiagramData("Demo", "grid", 10, List.of(node("n_base",
                "Base")), List.of(), Map.of()), "test");
        long revision = rooms.defaultRoom().revision();

        Map<String, Object> add = operation("add_node", Map.of("id", "n_new", "label", "Nuevo"));
        Map<String, Object> badConnection = operation("add_connection", Map.of("from", "n_new",
                "to", "n_missing"));
        Map<String, Object> addAfter = operation("add_node", Map.of("id", "n_after",
                "label", "Despues"));

        assertThatThrownBy(() -> tools.batch(List.of(add, badConnection, addAfter), false, null,
                null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Operacion 1")
                .hasMessageContaining("n_missing");

        DiagramData current = rooms.defaultRoom().current();
        assertThat(current.nodes()).extracting(DiagramNode::id).containsExactly("n_base");
        assertThat(current.node("n_new")).isNull();
        assertThat(current.node("n_after")).isNull();
        assertThat(rooms.defaultRoom().revision()).isEqualTo(revision);
    }

    @Test
    void batchConRevisionViejaFallaCon409SinCambios() {
        rooms.defaultRoom().replace(new DiagramData("Demo", "grid", 10, List.of(node("n_base",
                "Base")), List.of(), Map.of()), "test");
        long revision = rooms.defaultRoom().revision();

        assertThatThrownBy(() -> tools.batch(List.of(operation("add_node", Map.of("label",
                "Nuevo"))), false, revision - 1, null))
                .isInstanceOf(RevisionConflictException.class)
                .hasMessageContaining("409");

        assertThat(rooms.defaultRoom().current().nodes()).extracting(DiagramNode::id)
                .containsExactly("n_base");
        assertThat(rooms.defaultRoom().revision()).isEqualTo(revision);
    }

    @Test
    void batchAdmiteArgsAnidadosYOperaEnNivelesInternos() {
        DiagramNode parent = new DiagramNode("n_parent", 20, 20, 240, 120, "Backend", "", "", "",
                "", "", "rounded", "#ffffff", "#8a93a6", "#1f2430", "",
                List.of("top", "right", "bottom", "left"), "lv_parent", null);
        DiagramData level = new DiagramData("Backend", "grid", 10, List.of(), List.of(), Map.of());
        rooms.defaultRoom().replace(new DiagramData("Demo", "grid", 10, List.of(parent), List.of(),
                Map.of("lv_parent", level)), "test");

        Map<String, Object> addIn = new LinkedHashMap<>();
        addIn.put("tool", "add_node");
        addIn.put("args", Map.of("id", "n_in", "label", "Entrada", "path", List.of("n_parent")));
        Map<String, Object> addOut = operation("add_node", Map.of("id", "n_out", "label",
                "Salida", "path", List.of("n_parent")));
        Map<String, Object> connect = operation("add_connection", Map.of("id", "c_in_out",
                "from", "n_in", "to", "n_out", "path", List.of("n_parent")));

        Map<String, Object> result = tools.batch(List.of(addIn, addOut, connect), false, null,
                null);

        assertThat(result).containsEntry("revision", 2L);
        DiagramData inner = rooms.defaultRoom().current().level("lv_parent");
        assertThat(inner.nodes()).extracting(DiagramNode::id).containsExactly("n_in", "n_out");
        assertThat(inner.connections()).extracting(DiagramConnection::id)
                .containsExactly("c_in_out");
        assertThat(rooms.defaultRoom().current().nodes()).hasSize(1);
    }

    @Test
    void batchRechazaCamposDesconocidosYOperacionesNoSoportadas() {
        assertThatThrownBy(() -> tools.batch(List.of(operation("add_node", Map.of("label", "X",
                "typo", "no"))), false, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("campos desconocidos");

        assertThatThrownBy(() -> tools.batch(List.of(operation("focus_nodes", Map.of("ids",
                List.of("n_a")))), false, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no soportada");

        assertThatThrownBy(() -> tools.batch(List.of(operation("rename_diagram", Map.of("name",
                "Otro", "expectedRevision", 1))), false, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expectedRevision va en el batch");

        assertThat(rooms.defaultRoom().revision()).isZero();
    }

    @Test
    void batchRechazaUnIdDeNodoYaUsadoEnOtroNivel() {
        DiagramNode parent = new DiagramNode("n_parent", 20, 20, 240, 120, "Backend", "", "", "",
                "", "", "rounded", "#ffffff", "#8a93a6", "#1f2430", "",
                List.of("top", "right", "bottom", "left"), "lv_parent", null);
        DiagramData level = new DiagramData("Backend", "grid", 10, List.of(node("n_in", "In")),
                List.of(), Map.of());
        rooms.defaultRoom().replace(new DiagramData("Demo", "grid", 10, List.of(parent), List.of(),
                Map.of("lv_parent", level)), "test");

        assertThatThrownBy(() -> tools.batch(List.of(operation("add_node", Map.of("id", "n_in",
                "label", "Duplicado", "path", List.of("n_parent")))), false, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Ya existe un nodo con id: n_in");

        assertThat(rooms.defaultRoom().current().level("lv_parent").nodes())
                .extracting(DiagramNode::id).containsExactly("n_in");
    }

    @Test
    void batchImportAceptaElJsonComoObjetoAdemasDeTexto() {
        rooms.defaultRoom().replace(new DiagramData("Original", "grid", 10, List.of(), List.of(),
                Map.of()), "test");
        Map<String, Object> incoming = new LinkedHashMap<>();
        incoming.put("name", "Importado");
        incoming.put("background", "dots");
        incoming.put("gridSize", 22);
        incoming.put("nodes", List.of(Map.of("id", "n_obj", "label", "Objeto", "x", 20, "y", 20)));

        Map<String, Object> result = tools.batch(List.of(operation("import_diagram", Map.of(
                "json", incoming, "merge", true))), false, null, null);

        assertThat(result).containsEntry("revision", 2L);
        DiagramData current = rooms.defaultRoom().current();
        assertThat(current.name()).isEqualTo("Original");
        assertThat(current.nodes()).extracting(DiagramNode::id).containsExactly("n_obj");
    }

    @Test
    void importConMergeHaceUpsertSinBorrarNiCambiarMetadatos() throws Exception {
        DiagramNode nodeA = new DiagramNode("n_a", 20, 20, 160, 64, "A", "", "Java", "", "", "",
                "rounded", "#ffffff", "#8a93a6", "#1f2430", "",
                List.of("top", "right", "bottom", "left"), "lv_a", null);
        DiagramNode nodeB = node("n_b", "B");
        DiagramNode inner = node("n_inner", "Inner");
        DiagramData innerLevel = new DiagramData("Nivel original", "grid", 10, List.of(inner),
                List.of(), Map.of());
        rooms.defaultRoom().replace(new DiagramData("Original", "grid", 10,
                List.of(nodeA, nodeB), List.of(connection("c_ab", "n_a", "n_b")),
                Map.of("lv_a", innerLevel)), "test");

        DiagramNode incomingA = new DiagramNode("n_a", 20, 20, 0, 0, "A2", "", "Kotlin", "",
                "", "", "rounded", "#ffffff", "#8a93a6", "#1f2430", "",
                List.of("top", "right", "bottom", "left"), "lv_a", null);
        DiagramNode incomingC = new DiagramNode("n_c", 300, 20, 0, 0, "C", "", "", "", "", "",
                "rounded", "#ffffff", "#8a93a6", "#1f2430", "",
                List.of("top", "right", "bottom", "left"), "", null);
        DiagramData incomingInner = new DiagramData("Nivel nuevo", "dots", 22,
                List.of(new DiagramNode("n_inner", 20, 20, 160, 64, "Inner2", "", "", "", "", "",
                        "rounded", "#ffffff", "#8a93a6", "#1f2430", "",
                        List.of("top", "right", "bottom", "left"), "", null),
                        node("n_extra", "Extra")),
                List.of(), Map.of());
        DiagramData incoming = new DiagramData("Otro", "dots", 22,
                List.of(incomingA, incomingC),
                List.of(connection("c_bc", "n_b", "n_c")), Map.of("lv_a", incomingInner));

        Map<String, Object> result = tools.importDiagram(mapper.writeValueAsString(incoming), true,
                false, null, null);

        assertThat(result).containsEntry("merge", true).containsEntry("revision", 2L);
        DiagramData current = rooms.defaultRoom().current();
        assertThat(current.name()).isEqualTo("Original");
        assertThat(current.background()).isEqualTo("grid");
        assertThat(current.gridSize()).isEqualTo(10);
        assertThat(current.nodes()).extracting(DiagramNode::id)
                .containsExactly("n_a", "n_b", "n_c");
        assertThat(current.node("n_a").label()).isEqualTo("A2");
        assertThat(current.node("n_a").technology()).isEqualTo("Kotlin");
        assertThat(current.node("n_a").childLevelId()).isEqualTo("lv_a");
        assertThat(current.node("n_a").width()).isEqualTo(160);
        assertThat(current.node("n_a").height()).isEqualTo(64);
        assertThat(current.node("n_c").width()).isEqualTo(160);
        assertThat(current.node("n_c").height()).isEqualTo(80);
        assertThat(current.connections()).extracting(DiagramConnection::id)
                .containsExactly("c_ab", "c_bc");
        DiagramData level = current.level("lv_a");
        assertThat(level.name()).isEqualTo("Nivel original");
        assertThat(level.nodes()).extracting(DiagramNode::id)
                .containsExactly("n_inner", "n_extra");
        assertThat(level.node("n_inner").label()).isEqualTo("Inner2");
    }

    @Test
    void importSinMergeReemplazaElDiagramaCompleto() throws Exception {
        rooms.defaultRoom().replace(new DiagramData("Original", "grid", 10, List.of(node("n_a",
                "A")), List.of(), Map.of()), "test");
        DiagramData incoming = new DiagramData("Nuevo", "dots", 22,
                List.of(node("n_c", "C")), List.of(), Map.of());

        Map<String, Object> result = tools.importDiagram(mapper.writeValueAsString(incoming), false,
                false, null, null);

        assertThat(result).doesNotContainKey("merge").containsEntry("revision", 2L);
        DiagramData current = rooms.defaultRoom().current();
        assertThat(current.name()).isEqualTo("Nuevo");
        assertThat(current.background()).isEqualTo("dots");
        assertThat(current.nodes()).extracting(DiagramNode::id).containsExactly("n_c");
    }

    @Test
    void importConMergeConIdsDuplicadosGanaElUltimo() throws Exception {
        rooms.defaultRoom().replace(new DiagramData("Demo", "grid", 10, List.of(node("n_a",
                "A")), List.of(), Map.of()), "test");
        long revision = rooms.defaultRoom().revision();
        DiagramData incoming = new DiagramData("Otro", "grid", 10,
                List.of(node("n_dup", "Uno"), node("n_dup", "Dos")), List.of(), Map.of());

        tools.importDiagram(mapper.writeValueAsString(incoming), true, false, null, null);

        assertThat(rooms.defaultRoom().current().nodes()).extracting(DiagramNode::id)
                .containsExactly("n_a", "n_dup");
        assertThat(rooms.defaultRoom().current().node("n_dup").label()).isEqualTo("Dos");
        assertThat(rooms.defaultRoom().revision()).isEqualTo(revision + 1);
    }

    private static Map<String, Object> operation(String tool, Map<String, Object> args) {
        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("tool", tool);
        operation.putAll(args);
        return operation;
    }

    private static DiagramNode node(String id, String label) {
        return new DiagramNode(id, 20, 20, 160, 64, label, "", "", "", "", "", "rounded",
                "#ffffff", "#8a93a6", "#1f2430", "",
                List.of("top", "right", "bottom", "left"), "", null);
    }

    private static DiagramConnection connection(String id, String from, String to) {
        return new DiagramConnection(id, new PortRef(from, "right"), new PortRef(to, "left"), "",
                "orthogonal", "solid", "none", "end", "#7c8aa3", 2, null);
    }
}
