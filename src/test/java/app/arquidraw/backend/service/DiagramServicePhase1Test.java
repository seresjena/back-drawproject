package app.arquidraw.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.arquidraw.backend.model.DiagramConnection;
import app.arquidraw.backend.model.DiagramData;
import app.arquidraw.backend.model.DiagramNode;
import app.arquidraw.backend.model.PortRef;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Fase 1: fichas y direccionamiento de nodos en niveles internos. */
class DiagramServicePhase1Test {

    private static final String ORIGIN = "test";
    private static final List<String> BACKEND = List.of("n_backend");
    private static final String DOC = """
            ## Resumen
            Texto A

            ## Estado
            Pendiente

            ## Notas
            - uno
            - dos
            """;

    private DiagramService service;

    @BeforeEach
    void setUp() {
        service = new DiagramService(new InMemoryDiagramRepository(), event -> {
        }, "TEST");
        service.replace(seed(), ORIGIN);
    }

    @Test
    void agregaUnNodoConFichaEnUnNivelInterno() {
        DiagramNode added = service.addNode(ORIGIN, BACKEND, "Identity", "Auth", "Java", "Permisos",
                null, null, null, null, null, null, null, null, null, null, null,
                "https://example.com/identity", "# Identity\n");

        assertThat(added.doc()).isEqualTo("# Identity\n");
        assertThat(added.docUrl()).isEqualTo("https://example.com/identity");
        assertThat(service.level(BACKEND).nodes()).extracting(DiagramNode::id)
                .contains("n_auth", "n_user", added.id());
        assertThat(service.current().nodes()).hasSize(2);
    }

    @Test
    void actualizaLaFichaDeUnNodoInternoSinTocarElRestoDelDiagrama() {
        DiagramData before = service.current();

        DiagramNode updated = service.updateNode(ORIGIN, BACKEND, "n_auth",
                null, null, null, null,
                null, null, null, null,
                null, null, null, null,
                null,
                null,
                "https://example.com/auth", "## Nueva\nContenido\n");

        DiagramData after = service.current();
        assertThat(updated.doc()).isEqualTo("## Nueva\nContenido\n");
        assertThat(updated.docUrl()).isEqualTo("https://example.com/auth");
        assertThat(after.nodes()).isEqualTo(before.nodes());
        assertThat(after.connections()).isEqualTo(before.connections());
        assertThat(after.level("lv_backend").connections())
                .isEqualTo(before.level("lv_backend").connections());
        assertThat(after.level("lv_backend").node("n_user"))
                .isEqualTo(before.level("lv_backend").node("n_user"));
    }

    @Test
    void patchDocCambiaSoloLaSeccionIndicada() {
        DiagramNode updated = service.patchDoc(ORIGIN, BACKEND, "n_auth", "Estado", "Listo",
                null, null);

        assertThat(updated.doc()).isEqualTo("""
                ## Resumen
                Texto A

                ## Estado
                Listo

                ## Notas
                - uno
                - dos
                """);
    }

    @Test
    void patchDocAppendYSeccionNuevaMantienenElResto() {
        DiagramNode appended = service.patchDoc(ORIGIN, BACKEND, "n_auth", "Notas", "- tres",
                "append", null);

        assertThat(appended.doc()).startsWith("""
                ## Resumen
                Texto A

                ## Estado
                Pendiente

                ## Notas
                - uno
                - dos

                - tres
                """);

        DiagramNode created = service.patchDoc(ORIGIN, BACKEND, "n_auth", "Riesgos", "- X",
                null, null);
        assertThat(created.doc()).endsWith("\n\n## Riesgos\n- X\n");
        assertThat(created.doc()).contains("## Estado\nPendiente");
    }

    @Test
    void direccionaYModificaConexionesDeUnNivelInterno() {
        DiagramNode extra = service.addNode(ORIGIN, BACKEND, "Session", "", "", "",
                null, null, null, null, null, null, null, null, null, null, null, null, null);
        DiagramConnection added = service.addConnection(ORIGIN, BACKEND, "n_auth", extra.id(),
                "right", "left", "token", "orthogonal", "solid", "dash", "end", "#333", 2.0,
                null);

        assertThat(service.level(BACKEND).connections()).extracting(DiagramConnection::id)
                .contains("c_child", added.id());
        assertThat(service.current().connections()).hasSize(1);

        DiagramConnection updated = service.updateConnection(ORIGIN, BACKEND, added.id(), "sesion",
                null, null, null, null, null, null, null);
        assertThat(updated.label()).isEqualTo("sesion");
        assertThat(service.level(BACKEND).connection(added.id()).label()).isEqualTo("sesion");

        service.removeConnection(ORIGIN, BACKEND, added.id());
        assertThat(service.level(BACKEND).connection(added.id())).isNull();
        assertThat(service.current().connections()).hasSize(1);
    }

    @Test
    void eliminaUnNodoInternoYSuConexionSinTocarLaRaiz() {
        service.removeNode(ORIGIN, BACKEND, "n_user");

        assertThat(service.level(BACKEND).node("n_user")).isNull();
        assertThat(service.level(BACKEND).connections()).isEmpty();
        assertThat(service.current().nodes()).hasSize(2);
        assertThat(service.current().connections()).hasSize(1);
    }

    @Test
    void getNodeConPathNoEncuentraNodosDeOtroNivel() {
        assertThat(service.nodeAt(BACKEND, "n_auth").label()).isEqualTo("Authz");
        assertThatThrownBy(() -> service.nodeAt(List.of(), "n_auth"))
                .isInstanceOf(java.util.NoSuchElementException.class);
    }

    @Test
    void expectedRevisionViejaFallaSinModificarLaFicha() {
        long revision = service.revision();
        String before = service.nodeAt(BACKEND, "n_auth").doc();

        assertThatThrownBy(() -> service.updateDoc(ORIGIN, BACKEND, "n_auth", "# Cambio",
                revision - 1)).isInstanceOf(IllegalStateException.class);
        assertThat(service.nodeAt(BACKEND, "n_auth").doc()).isEqualTo(before);
    }

    private static DiagramData seed() {
        DiagramNode auth = new DiagramNode("n_auth", 20, 40, 160, 64, "Authz", "Identidad y permisos",
                "Java", "Valida tokens", "", DOC, "rounded", "#ffffff", "#8a93a6", "#1f2430", "",
                List.of("top", "right", "bottom", "left"), null, null);
        DiagramNode user = new DiagramNode("n_user", 260, 40, 160, 64, "Users", "Cuentas",
                "Java", "Gestiona usuarios", "", "", "rounded", "#ffffff", "#8a93a6", "#1f2430", "",
                List.of("top", "right", "bottom", "left"), null, null);
        DiagramConnection childConn = new DiagramConnection("c_child", new PortRef("n_auth", "right"),
                new PortRef("n_user", "left"), "valida", "orthogonal", "solid", "none", "end",
                "#7c8aa3", 2, null);
        DiagramData backend = new DiagramData("Backend", "grid", 10, List.of(auth, user),
                List.of(childConn), Map.of());

        DiagramNode backendNode = new DiagramNode("n_backend", 200, 40, 240, 120, "Backend",
                "Servicios", "Spring", "API", "", "", "rounded", "#ffffff", "#8a93a6", "#1f2430", "",
                List.of("top", "right", "bottom", "left"), "lv_backend", null);
        DiagramNode web = new DiagramNode("n_web", 20, 40, 160, 64, "Web", "Cliente",
                "Angular", "UI", "", "", "rounded", "#ffffff", "#8a93a6", "#1f2430", "",
                List.of("top", "right", "bottom", "left"), null, null);
        DiagramConnection rootConn = new DiagramConnection("c_root", new PortRef("n_web", "right"),
                new PortRef("n_backend", "left"), "REST", "orthogonal", "solid", "none", "end",
                "#7c8aa3", 2, null);
        return new DiagramData("Dedalo", "grid", 10, List.of(backendNode, web), List.of(rootConn),
                Map.of("lv_backend", backend));
    }
}
