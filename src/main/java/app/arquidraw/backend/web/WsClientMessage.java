package app.arquidraw.backend.web;

import app.arquidraw.backend.model.DiagramData;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Mensaje que envia el editor web al backend.
 *
 * type: "push"     -> el editor envia su estado actual (data)
 *       "request"  -> pide el estado actual
 *       "viewport" -> el editor reporta el tamano del lienzo y su pan/zoom
 *                     (width, height, zoom, panX, panY) para que los agentes
 *                     sepan que region se esta viendo.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WsClientMessage(String type, String origin, DiagramData data,
        Double width, Double height, Double zoom, Double panX, Double panY) {
}
