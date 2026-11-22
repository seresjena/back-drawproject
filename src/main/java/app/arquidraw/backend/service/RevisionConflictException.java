package app.arquidraw.backend.service;

/**
 * Conflicto de revision: otro escritor cambio el diagrama desde que el agente
 * leyo la revision indicada. El transporte MCP lo devuelve como error y el
 * endpoint HTTP responde 409.
 */
public class RevisionConflictException extends IllegalStateException {

    private final long expectedRevision;
    private final long currentRevision;

    public RevisionConflictException(long expectedRevision, long currentRevision) {
        // Spring AI concatena el mensaje de la excepcion y el de su causa raiz.
        // Sin causa, el mensaje del 409 se duplicaria en la respuesta MCP.
        super("Conflicto de revision (409): esperada " + expectedRevision
                + ", actual " + currentRevision + ".",
                new IllegalStateException("No se aplico ningun cambio; reintenta con get_meta."));
        this.expectedRevision = expectedRevision;
        this.currentRevision = currentRevision;
    }

    public long expectedRevision() {
        return expectedRevision;
    }

    public long currentRevision() {
        return currentRevision;
    }
}
