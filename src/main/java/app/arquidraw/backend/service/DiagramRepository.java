package app.arquidraw.backend.service;

import app.arquidraw.backend.model.DiagramData;

/**
 * Persistencia del diagrama activo.
 *
 * Hoy hay una unica implementacion en memoria ({@link InMemoryDiagramRepository}).
 * Cuando se quiera exportar a la nube basta con crear otra implementacion
 * (base de datos, objeto remoto, etc.) sin tocar la logica de negocio.
 */
public interface DiagramRepository {

    DiagramData load();

    void save(DiagramData data);
}
