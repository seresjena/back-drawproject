package app.arquidraw.backend.web;

import app.arquidraw.backend.model.DiagramNode;
import app.arquidraw.backend.service.DiagramService;
import app.arquidraw.backend.service.RoomManager;
import java.nio.charset.StandardCharsets;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * API de solo lectura para el visor de documentacion por componente.
 *
 * La pagina se abre en una pestana independiente ({@code /doc/{sala}/{nodeId}})
 * y pide aqui el Markdown de la sala. El agente lo edita por MCP; el editor lo
 * ve en vivo por WebSocket.
 */
@RestController
@RequestMapping("/api/docs")
public class DocController {

    private final RoomManager rooms;

    public DocController(RoomManager rooms) {
        this.rooms = rooms;
    }

    @GetMapping("/{sala}/{nodeId}")
    public NodeDocument document(@PathVariable String sala, @PathVariable String nodeId) {
        DiagramService service = rooms.forToken(sala);
        DiagramNode node = service.findNode(nodeId);
        if (node == null) {
            throw new NodeDocumentNotFound(nodeId);
        }
        return new NodeDocument(service.room(), node.id(), node.label(), node.sublabel(),
                node.technology(), node.summary(), node.docUrl(), node.doc(), service.revision());
    }

    @GetMapping(value = "/{sala}/{nodeId}/raw.md", produces = "text/markdown;charset=UTF-8")
    public ResponseEntity<byte[]> rawMarkdown(@PathVariable String sala, @PathVariable String nodeId) {
        NodeDocument doc = document(sala, nodeId);
        String markdown = doc.doc() == null || doc.doc().isBlank()
                ? "# " + doc.label() + "\n\nSin documentacion interna.\n"
                : doc.doc();
        ContentDisposition disposition = ContentDisposition.inline()
                .filename(slug(doc.label()) + ".md", StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(new MediaType("text", "markdown", StandardCharsets.UTF_8))
                .body(markdown.getBytes(StandardCharsets.UTF_8));
    }

    private static String slug(String label) {
        String base = label == null ? "" : label.trim()
                .replaceFirst("^\\s*\\d+\\s*(?:\\.\\s*-?|[-)])\\s*", "");
        String clean = java.text.Normalizer.normalize(base, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        return clean.isEmpty() ? "componente" : clean;
    }

    /** Nodo inexistente: 404 sin exponer detalles internos. */
    @org.springframework.web.bind.annotation.ResponseStatus(
            org.springframework.http.HttpStatus.NOT_FOUND)
    static class NodeDocumentNotFound extends RuntimeException {
        NodeDocumentNotFound(String nodeId) {
            super("No existe el nodo: " + nodeId);
        }
    }
}
