import java.io.File;
import java.io.PrintStream;
import java.util.*;

/**
 * AnalysisProbe -- headless analyses for the revision. Prints CSV to stdout.
 *
 * Usage:
 *   java -cp <classes> AnalysisProbe <mode> <polygonFolder> <widths,comma> <overlap> [arg]
 *
 *   structure            one row per (polygon, width, rotation on/off): regions k, offset-polygon
 *                        vertices, graph degree/edges/connectivity, regions visited, chosen angle
 *   grid <stepDeg>       forces every rotation angle 0, step, 2*step, ... < 180 and records regions,
 *                        path length etc.; plus one row ("opt") for the angle chosen by the search
 *   alpha <a1,a2,...>    reruns with each turn penalty; records path metrics and the visit order
 *   orders <maxOrders>   enumerates every depth-first visit order of the region tree (skipped if
 *                        more than maxOrders) and compares the greedy order with the best/mean/worst
 *
 * Requires the two hooks (forcedAngle, forcedOrder) added to PipelineRunner.
 */
public class AnalysisProbe {

    static PrintStream csv;   // CSV goes here; the pipeline's own console output is redirected to stderr

    public static void main(String[] a) throws Exception {
        if (a.length < 4) { System.err.println("usage: AnalysisProbe <mode> <folder> <widths> <overlap> [arg]"); return; }
        csv = System.out;
        System.setOut(System.err);
        String mode = a[0];
        File[] files = new File(a[1]).listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".txt"));
        if (files == null) { System.err.println("no polygon files"); return; }
        Arrays.sort(files, Comparator.comparing(File::getName));
        double[] widths = Arrays.stream(a[2].split(",")).mapToDouble(s -> Double.parseDouble(s.trim())).toArray();
        double overlap = Double.parseDouble(a[3]);
        String arg = a.length > 4 ? a[4] : "";

        switch (mode) {
            case "structure": csv.println("polygon,width,rotation,ok,k,offset_vertices,max_degree,graph_edges,connected,visited,angle_deg,split,path_bs,path_nobs,cov_bs,red_bs,exc_bs,out_bs,turns_bs,turndeg_bs,cov_nobs,red_nobs,exc_nobs,out_nobs,turns_nobs,turndeg_nobs"); break;
            case "grid":      csv.println("polygon,width,tag,angle_deg,ok,k,path_bs,path_nobs,turns,turn_deg,coverage,redundancy"); break;
            case "alpha":     csv.println("polygon,width,alpha,ok,path_bs,path_nobs,turns,turn_deg,order"); break;
            case "orders":    csv.println("polygon,width,k,n_orders,greedy_path,best_path,mean_path,worst_path,greedy_rank,greedy_turns,best_turns"); break;
            default: System.err.println("unknown mode " + mode); return;
        }

        for (File f : files) {
            String name = f.getName().replaceAll("\\.txt$", "");
            Point loc;
            try { loc = Polygon.computeCentroid(new Polygon(f.getAbsolutePath())); }
            catch (Throwable t) { System.err.println("parse failed: " + name); continue; }
            for (double w : widths) {
                try {
                    switch (mode) {
                        case "structure": structure(f, name, w, overlap, loc); break;
                        case "grid":      grid(f, name, w, overlap, loc, Double.parseDouble(arg.isEmpty() ? "1" : arg)); break;
                        case "alpha":     alpha(f, name, w, overlap, loc, arg); break;
                        case "orders":    orders(f, name, w, overlap, loc, Integer.parseInt(arg.isEmpty() ? "2000" : arg)); break;
                    }
                } catch (Throwable t) {
                    System.err.println("failed " + name + " w=" + w + ": " + t);
                } finally {
                    PipelineRunner.forcedAngle = null; PipelineRunner.forcedOrder = null;
                }
            }
        }
    }

    // ---------------------------------------------------------------- helpers
    static PipelineResult run(File f, double w, double ov, boolean rot, double alpha, Point loc) {
        RunConfig c = new RunConfig();
        c.polygonFilePath = f.getAbsolutePath();
        c.robotWidth = w; c.overlap = ov; c.optimalRotation = rot; c.turnPenalty = alpha;
        c.boundarySweep = true; c.robotLocation = loc;
        return PipelineRunner.run(c);
    }
    static boolean good(PipelineResult r) { return r != null && r.success && r.waypoints != null && r.waypoints.size() >= 2; }
    static MetricsCalculator.Metrics met(PipelineResult r, List<Point> wp, double w) { return MetricsCalculator.compute(r.originalPolygon, wp, w); }
    static String f(double v) { return String.format(Locale.US, "%.6f", v); }
    static List<PolygonVertex> verts(PipelineResult r) { List<PolygonVertex> l = new ArrayList<>(); for (PolygonVertex v : r.polygonGraph.G.getVertices()) l.add(v); return l; }
    static Map<String, List<String>> adjacency(PipelineResult r) {
        Map<String, List<String>> m = new LinkedHashMap<>();
        for (PolygonVertex v : verts(r)) {
            List<String> nb = new ArrayList<>();
            for (PolygonVertex u : r.polygonGraph.G.getAdjVertices(v)) nb.add(u.polygon.name);
            m.put(v.polygon.name, nb);
        }
        return m;
    }
    static List<String> names(List<PolygonVertex> l) { List<String> s = new ArrayList<>(); for (PolygonVertex v : l) s.add(v.polygon.name); return s; }

    // ---------------------------------------------------------------- structure
    static void structure(File f, String name, double w, double ov, Point loc) {
        for (boolean rot : new boolean[]{false, true}) {
            PipelineResult r = run(f, w, ov, rot, RunConfig.DEFAULT_TURN_PENALTY, loc);
            if (!good(r)) { csv.println(name + "," + f(w) + "," + rot + ",false,,,,,,,,,,,,,,,,,,,,,,"); continue; }
            Map<String, List<String>> adj = adjacency(r);
            int k = adj.size(), maxDeg = 0, deg2 = 0;
            for (List<String> nb : adj.values()) { maxDeg = Math.max(maxDeg, nb.size()); deg2 += nb.size(); }
            // connectivity from the start region
            Set<String> seen = new HashSet<>(); Deque<String> q = new ArrayDeque<>();
            String root = r.startRegion.polygon.name; q.add(root); seen.add(root);
            while (!q.isEmpty()) for (String u : adj.get(q.poll())) if (seen.add(u)) q.add(u);
            MetricsCalculator.Metrics m1 = met(r, r.waypoints, w), m0 = met(r, r.waypointsNoBSweep, w);
            csv.println(name + "," + f(w) + "," + rot + ",true," + k + "," + r.shrunkPolygon.polygonPoints.size() + "," + maxDeg + ","
                + (deg2 / 2) + "," + (seen.size() == k) + "," + r.visitOrder.size() + "," + f(r.rotationAngle) + "," + r.polygonWasSplit + ","
                + f(m1.pathLength) + "," + f(m0.pathLength) + ","
                + f(m1.coveragePct) + "," + f(m1.redundancyPct) + "," + f(m1.excessSweptPct) + "," + f(m1.outsideSweptPct) + "," + m1.turnCount + "," + f(m1.totalTurnAngleDeg) + ","
                + f(m0.coveragePct) + "," + f(m0.redundancyPct) + "," + f(m0.excessSweptPct) + "," + f(m0.outsideSweptPct) + "," + m0.turnCount + "," + f(m0.totalTurnAngleDeg));
        }
    }

    // ---------------------------------------------------------------- grid
    static void grid(File f, String name, double w, double ov, Point loc, double step) {
        for (double ang = 0; ang < 180.0 - 1e-9; ang += step) {
            PipelineRunner.forcedAngle = ang;
            PipelineResult r = run(f, w, ov, true, RunConfig.DEFAULT_TURN_PENALTY, loc);
            gridRow(name, w, "grid", r, ang);
        }
        PipelineRunner.forcedAngle = null;
        PipelineResult r = run(f, w, ov, true, RunConfig.DEFAULT_TURN_PENALTY, loc);
        gridRow(name, w, "opt", r, good(r) ? r.rotationAngle : Double.NaN);
    }
    static void gridRow(String name, double w, String tag, PipelineResult r, double ang) {
        if (!good(r)) { csv.println(name + "," + f(w) + "," + tag + "," + f(ang) + ",false,,,,,,,"); return; }
        MetricsCalculator.Metrics m1 = met(r, r.waypoints, w), m0 = met(r, r.waypointsNoBSweep, w);
        csv.println(name + "," + f(w) + "," + tag + "," + f(ang) + ",true," + r.decomposedRegions.size() + "," + f(m1.pathLength) + "," + f(m0.pathLength) + ","
            + m1.turnCount + "," + f(m1.totalTurnAngleDeg) + "," + f(m1.coveragePct) + "," + f(m1.redundancyPct));
    }

    // ---------------------------------------------------------------- alpha
    static void alpha(File f, String name, double w, double ov, Point loc, String list) {
        for (String s : list.split(",")) {
            double al = Double.parseDouble(s.trim());
            PipelineResult r = run(f, w, ov, true, al, loc);
            if (!good(r)) { csv.println(name + "," + f(w) + "," + f(al) + ",false,,,,,"); continue; }
            MetricsCalculator.Metrics m1 = met(r, r.waypoints, w), m0 = met(r, r.waypointsNoBSweep, w);
            csv.println(name + "," + f(w) + "," + f(al) + ",true," + f(m1.pathLength) + "," + f(m0.pathLength) + "," + m1.turnCount + ","
                + f(m1.totalTurnAngleDeg) + "," + String.join("|", names(r.visitOrder)));
        }
    }

    // ---------------------------------------------------------------- orders
    static long countOrders(String node, String parent, Map<String, List<String>> adj) {
        long prod = 1; int ch = 0;
        for (String c : adj.get(node)) if (!c.equals(parent)) { ch++; prod *= countOrders(c, node, adj); if (prod > 1_000_000) return prod; }
        for (int i = 2; i <= ch; i++) prod *= i;
        return prod;
    }
    static List<List<String>> allOrders(String node, String parent, Map<String, List<String>> adj) {
        List<String> ch = new ArrayList<>();
        for (String c : adj.get(node)) if (!c.equals(parent)) ch.add(c);
        List<List<String>> out = new ArrayList<>();
        if (ch.isEmpty()) { out.add(new ArrayList<>(List.of(node))); return out; }
        Map<String, List<List<String>>> sub = new HashMap<>();
        for (String c : ch) sub.put(c, allOrders(c, node, adj));
        for (List<String> perm : permutations(ch)) {
            List<List<String>> acc = new ArrayList<>(); acc.add(new ArrayList<>(List.of(node)));
            for (String c : perm) {
                List<List<String>> next = new ArrayList<>();
                for (List<String> pre : acc) for (List<String> s : sub.get(c)) { List<String> x = new ArrayList<>(pre); x.addAll(s); next.add(x); }
                acc = next;
            }
            out.addAll(acc);
        }
        return out;
    }
    static List<List<String>> permutations(List<String> l) {
        if (l.size() <= 1) { List<List<String>> r = new ArrayList<>(); r.add(new ArrayList<>(l)); return r; }
        List<List<String>> r = new ArrayList<>();
        for (int i = 0; i < l.size(); i++) {
            List<String> rest = new ArrayList<>(l); String h = rest.remove(i);
            for (List<String> p : permutations(rest)) { List<String> x = new ArrayList<>(); x.add(h); x.addAll(p); r.add(x); }
        }
        return r;
    }
    static void orders(File f, String name, double w, double ov, Point loc, int cap) {
        PipelineResult g = run(f, w, ov, true, RunConfig.DEFAULT_TURN_PENALTY, loc);
        if (!good(g)) return;
        Map<String, List<String>> adj = adjacency(g);
        String root = g.startRegion.polygon.name;
        int k = adj.size();
        if (g.visitOrder.size() != k || k < 2) return;                 // disconnected graph or single region
        long cnt = countOrders(root, null, adj);
        if (cnt > cap) return;
        double greedy = met(g, g.waypoints, w).pathLength; int greedyTurns = met(g, g.waypoints, w).turnCount;
        double best = Double.POSITIVE_INFINITY, worst = 0, sum = 0; int bestTurns = 0, better = 0, n = 0;
        for (List<String> ord : allOrders(root, null, adj)) {
            PipelineRunner.forcedOrder = ord;
            PipelineResult r = run(f, w, ov, true, RunConfig.DEFAULT_TURN_PENALTY, loc);
            if (!good(r)) continue;
            MetricsCalculator.Metrics m = met(r, r.waypoints, w);
            n++; sum += m.pathLength; worst = Math.max(worst, m.pathLength);
            if (m.pathLength < best) { best = m.pathLength; bestTurns = m.turnCount; }
            if (m.pathLength < greedy * (1 - 1e-9)) better++;
        }
        PipelineRunner.forcedOrder = null;
        if (n == 0) return;
        csv.println(name + "," + f(w) + "," + k + "," + n + "," + f(greedy) + "," + f(best) + "," + f(sum / n) + "," + f(worst) + "," + (better + 1) + "," + greedyTurns + "," + bestTurns);
    }
}
