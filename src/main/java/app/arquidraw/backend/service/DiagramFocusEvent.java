package app.arquidraw.backend.service;

import java.util.List;

/**
 * Se publica cuando se quiere resaltar ciertos ids SIN cambiar el diagrama.
 * Sirve para que un agente "senale" componentes y el usuario lo vea en vivo.
 *
 * @param ids    ids de nodos/conexiones a resaltar
 * @param origin quien lo origino
 * @param room   sala (tenant) a la que pertenece el diagrama
 */
public record DiagramFocusEvent(List<String> ids, String origin, String room) {
}
