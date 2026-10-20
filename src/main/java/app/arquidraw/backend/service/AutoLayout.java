package app.arquidraw.backend.service;

import app.arquidraw.backend.model.DiagramConnection;
import app.arquidraw.backend.model.DiagramNode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Auto-layout por capas (estilo Sugiyama simplificado). Espejo exacto de
 * {@code frontend/src/app/core/geometry/layout.ts}: ambos deben dar las mismas
 * posiciones para la misma entrada (lo comprueban los tests espejo).
 *
 * Rompe los ciclos (invirtiendo aristas de retorno solo para el calculo), asigna
 * cada nodo a un nivel segun el camino mas largo desde las fuentes, opcionalmente
 * agrupa por rol (clientes primero; datos y externos al final), ordena cada nivel
 * con barridos de baricentro y coloca los nodos en columnas/filas.
 */
public final class AutoLayout {

    private static final Set<String> CLIENT_ICONS = Set.of("client", "mobile", "browser", "actor");
    private static final Set<String> DATA_ICONS = Set.of("database", "cache");
    private static final Set<String> SIDE_ROLES = Set.of("client", "data", "external");

    private static final double MIN_LAYER_GAP = 80;
    private static final double MIN_NODE_GAP = 44;
    /** Barridos de ordenacion por baricentro (alternando sentido). */
    private static final int SWEEPS = 8;
    private static final double ORIGIN = 60;

    private AutoLayout() {
    }

    /** Posiciones nuevas (esquina superior izquierda, sin redondear) por id de nodo. */
    public static Map<String, double[]> compute(List<DiagramNode> nodes, List<DiagramConnection> connections,
            String direction, Double layerGap, Double nodeGap, Boolean byRole) {
        boolean tb = direction != null && direction.equalsIgnoreCase("TB");
        double lg = Math.max(layerGap != null ? layerGap : 90, MIN_LAYER_GAP);
        double ng = Math.max(nodeGap != null ? nodeGap : 46, MIN_NODE_GAP);
        Map<String, double[]> result = new LinkedHashMap<>();
        if (nodes.isEmpty()) {
            return result;
        }

        Map<String, DiagramNode> byId = new HashMap<>();
        List<String> ids = new ArrayList<>();
        for (DiagramNode n : nodes) {
            byId.put(n.id(), n);
            ids.add(n.id());
        }
        List<String[]> edges = new ArrayList<>();
        for (DiagramConnection c : connections) {
            String a = c.from().nodeId();
            String b = c.to().nodeId();
            if (a == null || b == null || a.equals(b) || !byId.containsKey(a) || !byId.containsKey(b)) {
                continue;
            }
            edges.add(new String[] { a, b });
        }
        Map<String, List<String>> preds = new HashMap<>();
        Map<String, List<String>> succs = new HashMap<>();
        acyclicGraph(ids, edges, preds, succs);

        // Niveles = camino mas largo desde las fuentes, en orden topologico (una pasada).
        Map<String, Integer> level = new HashMap<>();
        for (String id : topologicalOrder(ids, preds, succs)) {
            int l = 0;
            for (String p : preds.get(id)) {
                l = Math.max(l, level.get(p) + 1);
            }
            level.put(id, l);
        }

        // Por rol (opcional): clientes en la primera columna, datos y externos en las
        // ultimas; el resto (borde y servicios) conserva su nivel.
        if (Boolean.TRUE.equals(byRole)) {
            int last = 0;
            for (String id : ids) {
                if (!SIDE_ROLES.contains(roleOf(byId.get(id)))) {
                    last = Math.max(last, level.get(id));
                }
            }
            for (String id : ids) {
                switch (roleOf(byId.get(id))) {
                    case "client" -> level.put(id, -1);
                    case "data" -> level.put(id, last + 1);
                    case "external" -> level.put(id, last + 2);
                    default -> {
                    }
                }
            }
        }

        // Capas compactas: sin niveles vacios entre medias.
        List<Integer> used = new ArrayList<>(new TreeSet<>(level.values()));
        List<List<String>> layers = new ArrayList<>();
        for (int i = 0; i < used.size(); i++) {
            layers.add(new ArrayList<>());
        }
        for (String id : ids) {
            layers.get(used.indexOf(level.get(id))).add(id);
        }

        // Orden dentro de cada capa: barridos alternos por baricentro (hacia delante con
        // los predecesores, hacia atras con los sucesores); se queda con el orden que
        // menos cruces da entre capas vecinas.
        Map<String, Integer> indexOf = new HashMap<>();
        reindex(layers, indexOf);
        List<List<String>> best = copy(layers);
        int bestCrossings = layerCrossings(layers, succs, indexOf);
        for (int sweep = 0; sweep < SWEEPS; sweep++) {
            boolean forward = sweep % 2 == 0;
            List<List<String>> order = new ArrayList<>(layers);
            if (!forward) {
                java.util.Collections.reverse(order);
            }
            for (List<String> layer : order) {
                Map<String, Double> key = new HashMap<>();
                for (String id : layer) {
                    key.put(id, barycenter(id, forward ? preds.get(id) : succs.get(id), indexOf));
                }
                layer.sort((a, b) -> Double.compare(key.get(a), key.get(b)));
                for (int i = 0; i < layer.size(); i++) {
                    indexOf.put(layer.get(i), i);
                }
            }
            int crossings = layerCrossings(layers, succs, indexOf);
            if (crossings < bestCrossings) {
                bestCrossings = crossings;
                best = copy(layers);
            }
        }
        layers = best;
        reindex(layers, indexOf);

        int cols = layers.size();
        double[] colWidth = new double[cols];
        double[] colHeight = new double[cols];
        for (int i = 0; i < cols; i++) {
            List<String> layer = layers.get(i);
            for (int j = 0; j < layer.size(); j++) {
                double[] s = size(byId.get(layer.get(j)), tb);
                colWidth[i] = Math.max(colWidth[i], s[0]);
                colHeight[i] += s[1] + (j > 0 ? ng : 0);
            }
        }
        double totalHeight = 0;
        for (double ch : colHeight) {
            totalHeight = Math.max(totalHeight, ch);
        }
        double x = ORIGIN;
        for (int ci = 0; ci < cols; ci++) {
            double y = ORIGIN + (totalHeight - colHeight[ci]) / 2;
            for (String id : layers.get(ci)) {
                double[] s = size(byId.get(id), tb);
                double px = x + (colWidth[ci] - s[0]) / 2;
                result.put(id, tb ? new double[] { y, px } : new double[] { px, y });
                y += s[1] + ng;
            }
            x += colWidth[ci] + lg;
        }
        return result;
    }

    /** Rol de un componente para el layout, deducido de su icono y su forma. */
    public static String roleOf(DiagramNode n) {
        String icon = n.icon() == null ? "" : n.icon();
        if (icon.equals("external")) {
            return "external";
        }
        if ("cylinder".equals(n.shape()) || DATA_ICONS.contains(icon)) {
            return "data";
        }
        if (CLIENT_ICONS.contains(icon)) {
            return "client";
        }
        return "service";
    }

    /**
     * Rompe los ciclos: un DFS (primero desde las fuentes, en el orden de entrada)
     * marca como "de retorno" las aristas que vuelven a un nodo de la pila y las
     * invierte. Rellena {@code preds}/{@code succs} con el grafo ya sin ciclos.
     */
    private static void acyclicGraph(List<String> ids, List<String[]> edges, Map<String, List<String>> preds,
            Map<String, List<String>> succs) {
        Map<String, List<Integer>> out = new HashMap<>();
        Set<String> hasPred = new HashSet<>();
        for (String id : ids) {
            out.put(id, new ArrayList<>());
        }
        for (int i = 0; i < edges.size(); i++) {
            out.get(edges.get(i)[0]).add(i);
            hasPred.add(edges.get(i)[1]);
        }
        Map<String, Boolean> onStack = new HashMap<>(); // true = en la pila, false = terminado
        Set<Integer> reversed = new HashSet<>();
        List<String> roots = new ArrayList<>();
        for (String id : ids) {
            if (!hasPred.contains(id)) {
                roots.add(id);
            }
        }
        roots.addAll(ids);
        for (String id : roots) {
            if (!onStack.containsKey(id)) {
                visit(id, edges, out, onStack, reversed);
            }
        }
        for (String id : ids) {
            preds.put(id, new ArrayList<>());
            succs.put(id, new ArrayList<>());
        }
        for (int i = 0; i < edges.size(); i++) {
            String from = edges.get(i)[reversed.contains(i) ? 1 : 0];
            String to = edges.get(i)[reversed.contains(i) ? 0 : 1];
            succs.get(from).add(to);
            preds.get(to).add(from);
        }
    }

    private static void visit(String id, List<String[]> edges, Map<String, List<Integer>> out,
            Map<String, Boolean> onStack, Set<Integer> reversed) {
        onStack.put(id, true);
        for (int i : out.get(id)) {
            String next = edges.get(i)[1];
            Boolean s = onStack.get(next);
            if (Boolean.TRUE.equals(s)) {
                reversed.add(i);
            } else if (s == null) {
                visit(next, edges, out, onStack, reversed);
            }
        }
        onStack.put(id, false);
    }

    /** Orden topologico estable (Kahn, respetando el orden de entrada). */
    private static List<String> topologicalOrder(List<String> ids, Map<String, List<String>> preds,
            Map<String, List<String>> succs) {
        Map<String, Integer> pending = new HashMap<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        for (String id : ids) {
            pending.put(id, preds.get(id).size());
            if (preds.get(id).isEmpty()) {
                queue.add(id);
            }
        }
        List<String> order = new ArrayList<>();
        while (!queue.isEmpty()) {
            String id = queue.poll();
            order.add(id);
            for (String next : succs.get(id)) {
                int left = pending.get(next) - 1;
                pending.put(next, left);
                if (left == 0) {
                    queue.add(next);
                }
            }
        }
        return order;
    }

    private static double[] size(DiagramNode n, boolean tb) {
        return tb ? new double[] { n.height(), n.width() } : new double[] { n.width(), n.height() };
    }

    private static double barycenter(String id, List<String> list, Map<String, Integer> indexOf) {
        if (list.isEmpty()) {
            return indexOf.get(id);
        }
        double sum = 0;
        for (String n : list) {
            sum += indexOf.get(n);
        }
        return sum / list.size();
    }

    /**
     * Cruces entre aristas que unen las mismas dos capas (aproximacion de lineas
     * rectas): dos aristas se cruzan si sus extremos estan en orden inverso.
     */
    private static int layerCrossings(List<List<String>> layers, Map<String, List<String>> succs,
            Map<String, Integer> indexOf) {
        Map<String, Integer> layerOf = new HashMap<>();
        for (int i = 0; i < layers.size(); i++) {
            for (String id : layers.get(i)) {
                layerOf.put(id, i);
            }
        }
        Map<String, List<int[]>> groups = new HashMap<>();
        for (List<String> layer : layers) {
            for (String id : layer) {
                for (String next : succs.get(id)) {
                    groups.computeIfAbsent(layerOf.get(id) + ">" + layerOf.get(next), k -> new ArrayList<>())
                            .add(new int[] { indexOf.get(id), indexOf.get(next) });
                }
            }
        }
        int total = 0;
        for (List<int[]> list : groups.values()) {
            for (int i = 0; i < list.size(); i++) {
                for (int j = i + 1; j < list.size(); j++) {
                    if ((list.get(i)[0] - list.get(j)[0]) * (list.get(i)[1] - list.get(j)[1]) < 0) {
                        total++;
                    }
                }
            }
        }
        return total;
    }

    private static void reindex(List<List<String>> layers, Map<String, Integer> indexOf) {
        for (List<String> layer : layers) {
            for (int i = 0; i < layer.size(); i++) {
                indexOf.put(layer.get(i), i);
            }
        }
    }

    private static List<List<String>> copy(List<List<String>> layers) {
        List<List<String>> out = new ArrayList<>();
        for (List<String> layer : layers) {
            out.add(new ArrayList<>(layer));
        }
        return out;
    }
}
