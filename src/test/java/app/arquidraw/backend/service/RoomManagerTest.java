package app.arquidraw.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RoomManagerTest {

    private final RoomManager rooms = new RoomManager(event -> {
    });

    @Test
    void resuelveElTokenDeEspectadorHaciaLaSalaDelEditor() {
        DiagramService editor = rooms.forRoom("ABC123");

        rooms.registerViewer("VER999", "ABC123");

        assertThat(rooms.roomForViewer("VER999")).isEqualTo("ABC123");
        assertThat(rooms.forToken("VER999")).isSameAs(editor);
        assertThat(rooms.forToken("ABC123")).isSameAs(editor);
    }

    @Test
    void elMapeoDeEspectadorMandaAunqueYaExistaUnaSalaConEseToken() {
        rooms.forToken("VER999");
        DiagramService editor = rooms.forRoom("ABC123");

        rooms.registerViewer("VER999", "ABC123");

        assertThat(rooms.forToken("VER999")).isSameAs(editor);
    }

    @Test
    void noRegistraUnTokenDeVistaIgualAlDeEdicion() {
        rooms.registerViewer("ABC123", "ABC123");

        assertThat(rooms.roomForViewer("ABC123")).isNull();
    }
}
