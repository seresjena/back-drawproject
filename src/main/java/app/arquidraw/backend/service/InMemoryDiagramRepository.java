package app.arquidraw.backend.service;

import app.arquidraw.backend.model.DiagramData;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Repository;

/**
 * Implementacion en memoria (single-user, sin persistencia en disco).
 * El estado vive mientras el backend esta arrancado.
 */
@Repository
public class InMemoryDiagramRepository implements DiagramRepository {

    private final AtomicReference<DiagramData> current = new AtomicReference<>(DiagramData.empty());

    @Override
    public DiagramData load() {
        return current.get();
    }

    @Override
    public void save(DiagramData data) {
        current.set(data);
    }
}
