import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CompareRunner: headless (no JavaFX) comparison driver.
 *
 * Reads the summary.csv written by f2c_runner.py. For every row with status "ok" it
 *   1. runs YOUR pipeline on the same polygon and robot width, starting at the row's
 *      java_start_x / java_start_y (centroid, or F2C's first waypoint),
 *      with optimal rotation OFF for the "Fixed0" objective and ON otherwise;
 *   2. reads the Fields2Cover waypoint file;
 *   3. scores BOTH waypoint lists with the SAME MetricsCalculator.compute(...).
 * One combined row per Fields2Cover configuration goes to the output CSV.
 *
 * Compile (from the folder containing your sources):
 *   javac -d out -sourcepath . CompareRunner.java
 * Run:
 *   java -cp out CompareRunner <f2c summary.csv> <polygon folder> <output csv>
 */
public class CompareRunner {

    private static final Pattern XY = Pattern.compile("\\(\\s*(-?[\\d.eE+-]+)\\s*,\\s*(-?[\\d.eE+-]+)\\s*\\)");

    /** Result of one run of your algorithm, cached per (polygon, width, rotation, start). */
    private static class JavaRun {
        boolean ok;
        String error = "";
        double seconds;
        List<Point> waypoints;
        MetricsCalculator.Metrics metrics;
        boolean polygonWasSplit;
        int splitPieceCount = 1;
        double largestPieceAreaFraction = 1.0;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: java -cp out CompareRunner <f2c summary.csv> <polygon folder> <output csv>");
            System.exit(1);
        }
        File summary = new File(args[0]);
        File polygonDir = new File(args[1]);
        File outFile = new File(args[2]);

        List<List<String>> table = readCsv(summary);
        if (table.isEmpty()) {
            System.err.println("Empty summary file: " + summary);
            return;
        }
        Map<String, Integer> col = new HashMap<>();
        for (int i = 0; i < table.get(0).size(); i++) col.put(table.get(0).get(i), i);

        Map<String, JavaRun> cache = new HashMap<>();
        Map<String, Polygon> polygons = new HashMap<>();

        String[] head = {
            "polygon", "robot_width", "config_id", "layout", "decomposition", "objective", "route_planner",
            "start_mode", "start_x", "start_y", "java_rotation",
            "java_ok", "java_error", "java_time_s", "java_waypoints", "java_path_length", "java_turns",
            "java_coverage_pct", "java_redundancy_pct", "java_coverage_per_unit",
            "java_excess_swept_pct", "java_outside_swept_pct", "java_total_turn_deg",
            "java_polygon_was_split", "java_split_piece_count", "java_largest_piece_area_fraction",
            "f2c_ok", "f2c_error", "f2c_runtime_s", "f2c_waypoints", "f2c_path_length", "f2c_turns",
            "f2c_coverage_pct", "f2c_redundancy_pct", "f2c_coverage_per_unit",
            "f2c_excess_swept_pct", "f2c_outside_swept_pct", "f2c_total_turn_deg",
            "f2c_headland_piece_count", "f2c_polygon_was_split"};

        try (PrintWriter out = new PrintWriter(outFile, StandardCharsets.UTF_8)) {
            out.println(String.join(",", head));

            for (int r = 1; r < table.size(); r++) {
                List<String> row = table.get(r);
                if (row.size() < col.size()) continue;
                if (!get(row, col, "status").equals("ok")) continue;

                String name = get(row, col, "polygon");
                double width = Double.parseDouble(get(row, col, "robot_width"));
                double overlap = Double.parseDouble(get(row, col, "overlap"));
                double sx = Double.parseDouble(get(row, col, "java_start_x"));
                double sy = Double.parseDouble(get(row, col, "java_start_y"));
                boolean rotation = !get(row, col, "objective").equals("Fixed0");
                File polyFile = new File(polygonDir, name + ".txt");

                // ---- your algorithm (cached: many F2C rows share the same start) ----
                String key = String.format(Locale.US, "%s|%.6f|%b|%.6f|%.6f", name, width, rotation, sx, sy);
                JavaRun jr = cache.get(key);
                if (jr == null) {
                    jr = runJava(polyFile, width, overlap, rotation, sx, sy);
                    cache.put(key, jr);
                }

                // ---- Fields2Cover waypoints, scored by the same calculator ----
                boolean f2cOk = false;
                String f2cError = "";
                List<Point> f2cWp = null;
                MetricsCalculator.Metrics f2cM = null;
                try {
                    Polygon poly = polygons.computeIfAbsent(name, n -> new Polygon(polyFile.getAbsolutePath()));
                    f2cWp = readWaypoints(new File(get(row, col, "waypoint_file")));
                    f2cM = MetricsCalculator.compute(poly, f2cWp, width);
                    f2cOk = true;
                } catch (Exception ex) {
                    f2cError = safe(ex);
                }

                List<String> o = new ArrayList<>();
                o.add(name);
                o.add(get(row, col, "robot_width"));
                o.add(get(row, col, "config_id"));
                o.add(get(row, col, "layout"));
                o.add(get(row, col, "decomposition"));
                o.add(get(row, col, "objective"));
                o.add(get(row, col, "route_planner"));
                o.add(get(row, col, "start_mode"));
                o.add(fmt(sx));
                o.add(fmt(sy));
                o.add(String.valueOf(rotation));
                o.add(String.valueOf(jr.ok));
                o.add(quote(jr.error));
                o.add(jr.ok ? fmt(jr.seconds) : "");
                addMetrics(o, jr.ok, jr.waypoints, jr.metrics);
                o.add(String.valueOf(jr.polygonWasSplit));
                o.add(String.valueOf(jr.splitPieceCount));
                o.add(fmt(jr.largestPieceAreaFraction));
                o.add(String.valueOf(f2cOk));
                o.add(quote(f2cError));
                o.add(get(row, col, "runtime_s"));
                addMetrics(o, f2cOk, f2cWp, f2cM);
                int headlandPieces = Integer.parseInt(get(row, col, "headland_piece_count"));
                o.add(String.valueOf(headlandPieces));
                o.add(String.valueOf(headlandPieces > 1));
                out.println(String.join(",", o));
                out.flush();
                System.out.printf(Locale.US, "%s w=%s %s  java=%s f2c=%s%n", name, get(row, col, "robot_width"),
                        get(row, col, "config_id"), jr.ok, f2cOk);
            }
        }
        System.out.println("Wrote " + outFile.getAbsolutePath());
    }

    private static JavaRun runJava(File polyFile, double width, double overlap, boolean rotation,
                                   double sx, double sy) {
        JavaRun jr = new JavaRun();
        try {
            RunConfig cfg = new RunConfig();
            cfg.polygonFilePath = polyFile.getAbsolutePath();
            cfg.robotWidth = width;
            cfg.overlap = overlap;
            cfg.optimalRotation = rotation;
            cfg.boundarySweep = true;
            cfg.robotLocation = new Point(sx, sy);

            long t0 = System.nanoTime();
            PipelineResult res = PipelineRunner.run(cfg);
            jr.seconds = (System.nanoTime() - t0) / 1e9;
            jr.polygonWasSplit = res.polygonWasSplit;
            jr.splitPieceCount = res.splitPieceCount;
            jr.largestPieceAreaFraction = res.largestPieceAreaFraction;

            if (!res.success || res.waypoints == null || res.waypoints.size() < 2) {
                jr.error = res.errorMessage == null ? "pipeline failed" : res.errorMessage;
                return jr;
            }
            jr.waypoints = res.waypoints;
            jr.metrics = MetricsCalculator.compute(res.originalPolygon, res.waypoints, width);
            jr.ok = true;
        } catch (Exception ex) {
            jr.error = safe(ex);
        }
        return jr;
    }

    private static void addMetrics(List<String> o, boolean ok, List<Point> wp, MetricsCalculator.Metrics m) {
        if (!ok || m == null) {
            for (int i = 0; i < 9; i++) o.add("");
            return;
        }
        o.add(String.valueOf(wp.size()));
        o.add(fmt(m.pathLength));
        o.add(String.valueOf(m.turnCount));
        o.add(fmt(m.coveragePct));
        o.add(fmt(m.redundancyPct));
        o.add(fmt(m.coveragePerUnit));
        o.add(fmt(m.excessSweptPct));
        o.add(fmt(m.outsideSweptPct));
        o.add(fmt(m.totalTurnAngleDeg));
    }

    // ------------------------------------------------------------------ helpers
    private static List<Point> readWaypoints(File f) throws IOException {
        List<Point> pts = new ArrayList<>();
        for (String line : Files.readAllLines(f.toPath())) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            Matcher m = XY.matcher(line);
            if (m.find()) pts.add(new Point(Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2))));
        }
        if (pts.size() < 2) throw new IOException("fewer than 2 waypoints in " + f);
        return pts;
    }

    /** Minimal RFC-4180 reader: handles quoted fields with commas, quotes and newlines. */
    static List<List<String>> readCsv(File f) throws IOException {
        String s = Files.readString(f.toPath(), StandardCharsets.UTF_8);
        List<List<String>> rows = new ArrayList<>();
        List<String> cur = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inQ = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inQ) {
                if (c == '"') {
                    if (i + 1 < s.length() && s.charAt(i + 1) == '"') { sb.append('"'); i++; }
                    else inQ = false;
                } else sb.append(c);
            } else if (c == '"') inQ = true;
            else if (c == ',') { cur.add(sb.toString()); sb.setLength(0); }
            else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < s.length() && s.charAt(i + 1) == '\n') i++;
                cur.add(sb.toString()); sb.setLength(0);
                if (!(cur.size() == 1 && cur.get(0).isEmpty())) rows.add(cur);
                cur = new ArrayList<>();
            } else sb.append(c);
        }
        if (sb.length() > 0 || !cur.isEmpty()) { cur.add(sb.toString()); rows.add(cur); }
        return rows;
    }

    private static String get(List<String> row, Map<String, Integer> col, String name) {
        Integer i = col.get(name);
        if (i == null) throw new IllegalArgumentException("Column missing in summary.csv: " + name);
        return row.get(i);
    }

    private static String fmt(double v) { return String.format(Locale.US, "%.6f", v); }

    private static String quote(String s) {
        return "\"" + (s == null ? "" : s.replace("\"", "\"\"").replace("\n", " ")) + "\"";
    }

    private static String safe(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }
}
