package app.arquidraw.backend.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.arquidraw.backend.model.DiagramData;
import app.arquidraw.backend.model.DiagramNode;
import app.arquidraw.backend.service.DiagramService;
import app.arquidraw.backend.service.RevisionConflictException;
import app.arquidraw.backend.service.RoomManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** Fase 5: snapshots en memoria, restauracion y undo con autor. */
class DiagramMcpToolsPhase5Test {

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
    void snapshotListaRestauraYElPropioRestoreSePuedeDeshacer() {
        rooms.defaultRoom().replace(diagram(node("n_a", "A")), "editor");
        Map<String, Object> snap = tools.snapshot("Antes de B", null, null);

        assertThat(snap).containsEntry("ok", true).containsEntry("label", "Antes de B");
        String snapshotId = (String) snap.get("snapshotId");
        assertThat(snapshotId).startsWith("s_");
        assertThat(snap.get("counts")).isEqualTo(Map.of("nodes", 1, "connections", 0, "levels", 0));

        rooms.defaultRoom().replace(diagram(node("n_a", "A"), node("n_b", "B")), "editor");

        List<Map<String, Object>> listed = tools.listSnapshots();
        assertThat(listed).hasSize(1);
        assertThat(listed.get(0)).containsEntry("snapshotId", snapshotId)
                .containsEntry("label", "Antes de B").containsEntry("revision", 1L)
                .containsEntry("auto", false);

        Map<String, Object> restored = tools.restore(snapshotId, false, null, null);
        assertThat(restored).containsEntry("ok", true).containsEntry("snapshotId", snapshotId);
        assertThat(rooms.defaultRoom().current().nodes()).extracting(DiagramNode::id)
                .containsExactly("n_a");
        assertThat(rooms.defaultRoom().revision()).isEqualTo(3);

        tools.undo(1, false, null, null);
        assertThat(rooms.defaultRoom().current().nodes()).extracting(DiagramNode::id)
                .containsExactly("n_a", "n_b");
    }

    @Test
    void undoDeshaceVariosCambios() {
        rooms.defaultRoom().replace(diagram(node("n_a", "A")), "editor");
        rooms.defaultRoom().replace(diagram(node("n_a", "A"), node("n_b", "B")), "editor");
        rooms.defaultRoom().replace(diagram(node("n_a", "A"), node("n_b", "B"), node("n_c", "C")),
                "editor");

        Map<String, Object> result = tools.undo(2, false, null, null);

        assertThat(result).containsEntry("ok", true).containsEntry("steps", 2)
                .containsEntry("revision", 4L);
        assertThat(rooms.defaultRoom().current().nodes()).extracting(DiagramNode::id)
                .containsExactly("n_a");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> undone = (List<Map<String, Object>>) result.get("undone");
        assertThat(undone).hasSize(2);
        assertThat(undone.get(0)).containsEntry("revision", 2L).containsEntry("origin", "editor");
        assertThat(undone.get(1)).containsEntry("revision", 3L).containsEntry("origin", "editor");

        assertThatThrownBy(() -> tools.undo(1, false, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No hay cambios");
    }

    @Test
    void undosSucesivosSiguenRetrocediendo() {
        rooms.defaultRoom().replace(diagram(node("n_a", "A")), "editor");
        rooms.defaultRoom().replace(diagram(node("n_a", "A"), node("n_b", "B")), "editor");
        rooms.defaultRoom().replace(diagram(node("n_a", "A"), node("n_b", "B"), node("n_c", "C")),
                "editor");

        tools.undo(1, false, null, null);
        assertThat(rooms.defaultRoom().current().nodes()).extracting(DiagramNode::id)
                .containsExactly("n_a", "n_b");

        tools.undo(1, false, null, null);
        assertThat(rooms.defaultRoom().current().nodes()).extracting(DiagramNode::id)
                .containsExactly("n_a");

        assertThatThrownBy(() -> tools.undo(1, false, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No hay cambios");
    }

    @Test
    void laCargaInicialNoSePuedeDeshacerAUnaSalaVacia() {
        rooms.defaultRoom().replace(diagram(node("n_a", "A")), "editor");

        assertThatThrownBy(() -> tools.undo(1, false, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No hay cambios");
    }

    @Test
    void undoRechazaPasosInvalidosOSuperioresAlHistorial() {
        assertThatThrownBy(() -> tools.undo(0, false, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("steps");

        rooms.defaultRoom().replace(diagram(node("n_a", "A")), "editor");
        rooms.defaultRoom().replace(diagram(node("n_a", "A"), node("n_b", "B")), "editor");
        assertThatThrownBy(() -> tools.undo(5, false, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Solo hay 1");
    }

    @Test
    void snapshotAutomaticoAntesDeClearYSePuedeRestaurar() {
        rooms.defaultRoom().replace(diagram(node("n_a", "A")), "editor");

        tools.clearDiagram(false, null, null);

        assertThat(rooms.defaultRoom().current().nodes()).isEmpty();
        List<Map<String, Object>> snaps = tools.listSnapshots();
        assertThat(snaps).hasSize(1);
        Map<String, Object> auto = snaps.get(0);
        assertThat(auto).containsEntry("auto", true);
        assertThat((String) auto.get("label")).contains("clear_diagram");

        tools.restore((String) auto.get("snapshotId"), false, null, null);
        assertThat(rooms.defaultRoom().current().nodes()).extracting(DiagramNode::id)
                .containsExactly("n_a");
    }

    @Test
    void snapshotAutomaticoAntesDeImport() throws Exception {
        rooms.defaultRoom().replace(diagram(node("n_a", "A")), "editor");
        String incoming = mapper.writeValueAsString(diagram(node("n_c", "C")));

        tools.importDiagram(incoming, false, false, null, null);

        assertThat(rooms.defaultRoom().current().nodes()).extracting(DiagramNode::id)
                .containsExactly("n_c");
        List<Map<String, Object>> snaps = tools.listSnapshots();
        assertThat(snaps).hasSize(1);
        assertThat((String) snaps.get(0).get("label")).contains("import_diagram");
    }

    @Test
    void snapshotAutomaticoAntesDeReplaceLevel() {
        DiagramNode parent = new DiagramNode("n_parent", 20, 20, 240, 120, "Backend", "", "", "",
                "", "", "rounded", "#ffffff", "#8a93a6", "#1f2430", "",
                List.of("top", "right", "bottom", "left"), "lv_parent", null);
        DiagramData level = new DiagramData("Backend", "grid", 10, List.of(node("n_in", "In")),
                List.of(), Map.of());
        rooms.defaultRoom().replace(new DiagramData("Demo", "grid", 10, List.of(parent), List.of(),
                Map.of("lv_parent", level)), "editor");

        Map<String, Object> replacement = new LinkedHashMap<>();
        replacement.put("name", "Backend 2");
        replacement.put("background", "dots");
        replacement.put("gridSize", 22);
        replacement.put("nodes", List.of(Map.of("id", "n_out", "label", "Out", "x", 20, "y", 20)));
        replacement.put("connections", List.of());
        replacement.put("levels", Map.of());

        tools.replaceLevel(List.of("n_parent"), replacement, false, null, null);

        assertThat(rooms.defaultRoom().current().level("lv_parent").nodes())
                .extracting(DiagramNode::id).containsExactly("n_out");
        List<Map<String, Object>> snaps = tools.listSnapshots();
        assertThat(snaps).hasSize(1);
        assertThat((String) snaps.get(0).get("label")).contains("replace_level");
    }

    @Test
    void snapshotAutomaticoAntesDeBatchGrandeYElBatchEsUnSoloUndo() {
        rooms.defaultRoom().replace(diagram(node("n_a", "A")), "editor");
        List<Map<String, Object>> operations = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            operations.add(operation("add_node", Map.of("id", "n_" + i, "label", "N" + i)));
        }

        tools.batch(operations, false, null, null);

        assertThat(rooms.defaultRoom().current().nodes()).hasSize(11);
        List<Map<String, Object>> snaps = tools.listSnapshots();
        assertThat(snaps).hasSize(1);
        assertThat((String) snaps.get(0).get("label")).contains("batch");

        tools.undo(1, false, null, null);
        assertThat(rooms.defaultRoom().current().nodes()).extracting(DiagramNode::id)
                .containsExactly("n_a");
    }

    @Test
    void snapshotNoSeAdmiteDentroDeUnBatch() {
        assertThatThrownBy(() -> tools.batch(List.of(operation("snapshot", Map.of("label", "x"))),
                false, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no soportada");
    }

    @Test
    void restoreConRevisionViejaFallaCon409SinCambios() {
        rooms.defaultRoom().replace(diagram(node("n_a", "A")), "editor");
        Map<String, Object> snap = tools.snapshot("Base", null, null);
        long revision = rooms.defaultRoom().revision();
        rooms.defaultRoom().replace(diagram(node("n_a", "A"), node("n_b", "B")), "editor");

        assertThatThrownBy(() -> tools.restore((String) snap.get("snapshotId"), false, revision,
                null))
                .isInstanceOf(RevisionConflictException.class)
                .hasMessageContaining("409");

        assertThat(rooms.defaultRoom().current().nodes()).extracting(DiagramNode::id)
                .containsExactly("n_a", "n_b");
    }

    @Test
    void snapshotsEstanAisladosPorSala() {
        rooms.defaultRoom().replace(diagram(node("n_a", "A")), "editor");
        rooms.forRoom("OTRA").replace(diagram(node("n_b", "B")), "editor");

        tools.snapshot("Default", null, null);
        rooms.forRoom("OTRA").snapshot("editor", "Otra");

        assertThat(tools.listSnapshots()).extracting(item -> item.get("label"))
                .containsExactly("Default");
        assertThat(rooms.forRoom("OTRA").snapshots()).extracting(DiagramService.SnapshotInfo::label)
                .containsExactly("Otra");
    }

    @Test
    void snapshotsAutomaticosSePodanYLosManualesTienenLimite() {
        rooms.defaultRoom().replace(diagram(node("n_a", "A")), "editor");
        for (int i = 0; i < 11; i++) {
            rooms.defaultRoom().snapshotAuto("editor", "auto-" + i);
        }
        assertThat(rooms.defaultRoom().snapshots()).hasSize(10);
        assertThat(rooms.defaultRoom().snapshots()).extracting(DiagramService.SnapshotInfo::label)
                .doesNotContain("auto-0").contains("auto-10");

        for (int i = 0; i < 20; i++) {
            rooms.defaultRoom().snapshot("editor", "manual-" + i);
        }
        assertThatThrownBy(() -> rooms.defaultRoom().snapshot("editor", "manual-20"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Limite de snapshots manuales");

        assertThat(rooms.defaultRoom().snapshotAuto("editor", "vacio")).isNotNull();
        rooms.defaultRoom().clear("editor");
        assertThat(rooms.defaultRoom().snapshotAuto("editor", "vacio")).isNull();
    }

    private static Map<String, Object> operation(String tool, Map<String, Object> args) {
        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("tool", tool);
        operation.putAll(args);
        return operation;
    }

    private static DiagramData diagram(DiagramNode... nodes) {
        return new DiagramData("Demo", "grid", 10, List.of(nodes), List.of(), Map.of());
    }

    private static DiagramNode node(String id, String label) {
        return new DiagramNode(id, 20, 20, 160, 64, label, "", "", "", "", "", "rounded",
                "#ffffff", "#8a93a6", "#1f2430", "",
                List.of("top", "right", "bottom", "left"), "", null);
    }
}
