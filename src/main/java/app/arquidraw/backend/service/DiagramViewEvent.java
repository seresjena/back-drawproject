package app.arquidraw.backend.service;

import app.arquidraw.backend.model.ViewRequest;

/**
 * Se publica cuando un agente pide cambiar la vista (zoom/centro) del editor,
 * sin cambiar el diagrama.
 *
 * @param view   vista pedida
 * @param origin quien lo origino
 * @param room   sala (tenant) a la que pertenece el diagrama
 */
public record DiagramViewEvent(ViewRequest view, String origin, String room) {
}
