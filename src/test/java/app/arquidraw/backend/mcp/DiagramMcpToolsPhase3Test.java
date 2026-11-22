package app.arquidraw.backend.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.arquidraw.backend.model.DiagramConnection;
import app.arquidraw.backend.model.DiagramData;
import app.arquidraw.backend.model.DiagramNode;
import app.arquidraw.backend.model.DiagramPortal;
import app.arquidraw.backend.model.PortRef;
import app.arquidraw.backend.service.DiagramService;
import app.arquidraw.backend.service.DiagramValidator;
import app.arquidraw.backend.service.RevisionConflictException;
import app.arquidraw.backend.service.RoomManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** Fase 3: niveles vacios, revision esperada y validacion/correccion. */
class DiagramMcpToolsPhase3Test {

    private static final List<String> PARENT = List.of("n_parent");
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
    void unNivelVacioSeListaYSePuedeReemplazar() {
        rooms.defaultRoom().replace(withLevels(new DiagramData("Demo", "grid", 10,
                List.of(parent()), List.of(), Map.of("lv_empty", emptyLevel()))), "test");

        List<Map<String, Object>> levels = tools.listLevels();
        assertThat(levels).hasSize(1);
        assertThat(levels.get(0)).containsEntry("path", PARENT).containsEntry("nodeCount", 0)
                .containsEntry("connectionCount", 0);

        Map<String, Object> empty = new LinkedHashMap<>();
        empty.put("name", "Vacio");
        empty.put("background", "grid");
        empty.put("gridSize", 10);
        empty.put("nodes", List.of());
        empty.put("connections", List.of());
        empty.put("levels", Map.of());

        Map<String, Object> result = tools.replaceLevel(PARENT, empty, false, null, null);

        assertThat(result).containsEntry("ok", true).containsEntry("revision", 2L);
        assertThat(tools.getLevel(PARENT).nodes()).isEmpty();
        assertThat(tools.listLevels().get(0)).containsEntry("nodeCount", 0);
    }

    @Test
    void unaRevisionViejaFallaCon409YSinCambiarElDiagrama() {
        rooms.defaultRoom().replace(withLevels(new DiagramData("Demo", "grid", 10,
                List.of(parent()), List.of(), Map.of("lv_empty", emptyLevel()))), "test");
        long revision = rooms.defaultRoom().revision();

        assertThatThrownBy(() -> tools.renameDiagram("Otro", false, revision - 1, null))
                .isInstanceOf(RevisionConflictException.class)
                .hasMessageContaining("409")
                .hasMessageContaining("actual " + revision);

        assertThat(rooms.defaultRoom().current().name()).isEqualTo("Demo");
        assertThat(rooms.defaultRoom().revision()).isEqualTo(revision);

        Map<String, Object> result = tools.renameDiagram("Otro", false, revision, null);
        assertThat(result).containsEntry("revision", revision + 1);
        assertThat(rooms.defaultRoom().current().name()).isEqualTo("Otro");
    }

    @Test
    void validateDetectaYCorrigeProblemasSeguros() {
        DiagramNode portal = new DiagramNode("n_portal", 20, 20, 160, 56, "Entrada", "", "", "",
                "", "", "pill", "#ffffff", "#2f6bd8", "#1f2430", "portalIn",
                List.of("top", "right", "bottom", "left"), "",
                new DiagramPortal("in", "c_bad", "left"));
        DiagramNode orphanNode = new DiagramNode("n_orphan", 40, 40, 160, 64, "Huerfano",
                "", "", "", "", "", "rounded", "#ffffff", "#888888", "#111111", "",
                List.of("top", "right", "bottom", "left"), "", null);

        DiagramData orphan = new DiagramData("Huerfano", "grid", 10, List.of(orphanNode),
                List.of(), Map.of());
        DiagramData child = new DiagramData("Hijo", "grid", 10, List.of(portal), List.of(),
                Map.of());
        DiagramData root = new DiagramData("Demo", "grid", 10,
                List.of(parent(), missingChild(), overlapA(), overlapB(), duplicateA()),
                List.of(badConnection()), Map.of("lv_empty", child, "lv_orphan", orphan));
        rooms.defaultRoom().replace(root, "test");

        List<DiagramValidator.Issue> issues = DiagramValidator.validate(root);
        assertThat(issues).extracting(DiagramValidator.Issue::code)
                .contains("connection_missing_node", "orphan_level", "child_level_missing",
                        "overlapping_nodes", "duplicate_node_id");

        Map<String, Object> result = tools.validate(true, false,
                rooms.defaultRoom().revision(), null);

        assertThat(result).containsEntry("ok", true).containsEntry("valid", false);
        assertThat((Integer) result.get("fixedCount")).isGreaterThan(0);
        assertThat(rooms.defaultRoom().current().levels()).doesNotContainKey("lv_orphan");
        assertThat(rooms.defaultRoom().current().connection("c_bad")).isNull();
        assertThat(rooms.defaultRoom().current().node("n_missing").childLevelId()).isEmpty();
        assertThat(rooms.defaultRoom().current().level("lv_empty").node("n_portal")).isNull();
        assertThat(rooms.defaultRoom().revision()).isEqualTo(2);
    }

    @Test
    void dosEscritoresConLaMismaRevisionSoloUnoGana() throws Exception {
        rooms.defaultRoom().replace(new DiagramData("Demo", "grid", 10, List.of(), List.of(),
                Map.of()), "test");
        DiagramService service = rooms.defaultRoom();
        long revision = service.revision();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<String> writer = () -> {
            start.await();
            try {
                service.withExpectedRevision(revision, () -> service.rename("test", "Cambiado"));
                return "ok";
            } catch (RevisionConflictException ex) {
                return "409";
            }
        };
        try {
            Future<String> first = pool.submit(writer);
            Future<String> second = pool.submit(writer);
            start.countDown();
            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder("ok", "409");
            assertThat(service.revision()).isEqualTo(revision + 1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void validateSinFixNoModificaElDiagrama() {
        rooms.defaultRoom().replace(new DiagramData("Demo", "grid", 10,
                List.of(parent()), List.of(), Map.of("lv_orphan", emptyLevel())), "test");

        Map<String, Object> result = tools.validate(false, false, null, null);

        assertThat(result).containsEntry("valid", false);
        assertThat(rooms.defaultRoom().revision()).isEqualTo(1);
        assertThat(rooms.defaultRoom().current().levels()).containsKey("lv_orphan");
    }

    private static DiagramNode parent() {
        return new DiagramNode("n_parent", 20, 40, 240, 120, "Backend", "", "Java", "",
                "", "", "rounded", "#ffffff", "#8a93a6", "#1f2430", "",
                List.of("top", "right", "bottom", "left"), "lv_empty", null);
    }

    private static DiagramNode missingChild() {
        return new DiagramNode("n_missing", 300, 40, 160, 64, "Roto", "", "", "",
                "", "", "rounded", "#ffffff", "#8a93a6", "#1f2430", "",
                List.of("top", "right", "bottom", "left"), "lv_nope", null);
    }

    private static DiagramNode overlapA() {
        return new DiagramNode("n_a", 500, 40, 160, 64, "A", "", "", "",
                "", "", "rounded", "#ffffff", "#8a93a6", "#1f2430", "",
                List.of("top", "right", "bottom", "left"), "", null);
    }

    private static DiagramNode overlapB() {
        return new DiagramNode("n_b", 520, 50, 160, 64, "B", "", "", "",
                "", "", "rounded", "#ffffff", "#8a93a6", "#1f2430", "",
                List.of("top", "right", "bottom", "left"), "", null);
    }

    private static DiagramNode duplicateA() {
        return new DiagramNode("n_a", 700, 40, 160, 64, "A duplicado", "", "", "",
                "", "", "rounded", "#ffffff", "#8a93a6", "#1f2430", "",
                List.of("top", "right", "bottom", "left"), "", null);
    }

    private static DiagramConnection badConnection() {
        return new DiagramConnection("c_bad", new PortRef("n_parent", "right"),
                new PortRef("n_inexistente", "left"), "", "orthogonal", "solid", "none", "end",
                "#7c8aa3", 2, null);
    }

    private static DiagramData emptyLevel() {
        return new DiagramData("Vacio", "grid", 10, List.of(), List.of(), Map.of());
    }

    private static DiagramData withLevels(DiagramData data) {
        return new DiagramData(data.name(), data.background(), data.gridSize(), data.nodes(),
                data.connections(), data.levels());
    }
}
