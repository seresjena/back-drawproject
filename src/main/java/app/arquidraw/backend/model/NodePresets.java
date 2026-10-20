package app.arquidraw.backend.model;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Catalogo de presets disponibles para crear componentes.
 * Mantener sincronizado con NODE_PRESETS del front (palette.ts).
 */
public final class NodePresets {

    private static final String INK = "#1f2430";

    private static final String NF = "#e6efff";
    private static final String NS = "#3f6fd8";
    private static final String PF = "#efe3ff";
    private static final String PS = "#9b6ef3";
    private static final String GF = "#e2f7ee";
    private static final String GS = "#2fa87a";
    private static final String OF = "#fff2e0";
    private static final String OS = "#e08a2b";
    private static final String RF = "#ffe6ea";
    private static final String RS = "#e05670";
    private static final String TF = "#e0f4f8";
    private static final String TS = "#2c9fb8";

    private static final List<NodePreset> PRESETS = List.of(
            // --- Arquitectura ---
            new NodePreset("user", "Usuario", "rounded", NF, NS, INK, "user", 150, 70),
            new NodePreset("client", "Cliente web", "rounded", NF, NS, INK, "client", 170, 80),
            new NodePreset("mobile", "App movil", "rounded", NF, NS, INK, "mobile", 150, 80),
            new NodePreset("browser", "Navegador", "rounded", TF, TS, INK, "browser", 170, 80),
            new NodePreset("gateway", "API Gateway", "hexagon", GF, GS, INK, "gateway", 170, 84),
            new NodePreset("router", "Router (1 a muchos)", "rounded", GF, GS, INK, "router", 180, 80),
            new NodePreset("lb", "Balanceador", "rounded", GF, GS, INK, "loadbalancer", 170, 80),
            new NodePreset("server", "Servidor", "rectangle", NF, NS, INK, "server", 170, 84),
            new NodePreset("function", "Funcion / Lambda", "rounded", OF, OS, INK, "function", 180, 78),
            new NodePreset("database", "Base de datos", "cylinder", PF, PS, INK, "database", 160, 100),
            new NodePreset("cache", "Cache", "rounded", RF, RS, INK, "cache", 150, 76),
            new NodePreset("queue", "Cola de mensajes", "rounded", GF, GS, INK, "queue", 180, 80),
            new NodePreset("stream", "Stream de eventos", "rounded", OF, OS, INK, "stream", 190, 80),
            new NodePreset("storage", "Almacenamiento", "rounded", PF, PS, INK, "storage", 170, 80),
            new NodePreset("cloud", "Servicio externo", "rounded", TF, TS, INK, "cloud", 180, 84),
            new NodePreset("external", "Sistema externo", "rectangle", "#ffffff", "#8a93a6", INK, "external", 180, 80),
            // --- Flujo ---
            new NodePreset("start", "Inicio", "pill", GF, GS, INK, "start", 140, 56),
            new NodePreset("process", "Proceso", "rounded", NF, NS, INK, "process", 160, 70),
            new NodePreset("decision", "Decision", "diamond", OF, OS, INK, "decision", 150, 110),
            new NodePreset("document", "Documento", "rectangle", PF, PS, INK, "document", 160, 80),
            new NodePreset("actor", "Actor", "rounded", TF, TS, INK, "actor", 150, 76),
            new NodePreset("end", "Fin", "pill", RF, RS, INK, "start", 140, 56),
            // --- Basicos ---
            new NodePreset("rect", "Rectangulo", "rectangle", "#ffffff", "#8a93a6", INK, "", 160, 80),
            new NodePreset("round", "Redondeado", "rounded", "#ffffff", "#8a93a6", INK, "", 160, 80),
            new NodePreset("circle", "Circulo", "circle", "#ffffff", "#8a93a6", INK, "", 120, 120),
            new NodePreset("hex", "Hexagono", "hexagon", "#ffffff", "#8a93a6", INK, "", 160, 84),
            new NodePreset("diamond", "Rombo", "diamond", "#ffffff", "#8a93a6", INK, "", 150, 110),
            new NodePreset("cyl", "Cilindro", "cylinder", "#ffffff", "#8a93a6", INK, "", 150, 100));

    public static final Map<String, NodePreset> BY_KEY = PRESETS.stream()
            .collect(Collectors.toUnmodifiableMap(NodePreset::key, p -> p));

    public static final String DEFAULT_KEY = "round";

    public static List<NodePreset> all() {
        return PRESETS;
    }

    public static NodePreset find(String key) {
        return key == null ? null : BY_KEY.get(key);
    }

    public static NodePreset defaultPreset() {
        return BY_KEY.get(DEFAULT_KEY);
    }

    private NodePresets() {
    }
}
