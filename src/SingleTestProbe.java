import java.io.File;
import java.io.FileWriter;
import java.util.List;
import java.util.Locale;

/**
 * SingleTestProbe — headless, no F2C, no CSV. Runs YOUR pipeline on one polygon file
 * across every (width, rotation) combination requested and prints one line per combo
 * so a Python wrapper can parse pass/fail without touching the JVM's stack trace format.
 *
 * Usage:
 *   java -cp <classes> SingleTestProbe <polygon.txt> <widths comma list> <overlap> [waypointsOutDir]
 *
 * For each width, runs twice: rotation=false (mirrors the "Fixed0" benchmark objective)
 * and rotation=true (mirrors "NSwath"/"SwathLength" — same Java behaviour for both).
 *
 * Output, one line per combo, to stdout:
 *   RESULT width=2.0 rotation=false ok=true  seconds=0.341 waypoints=118 split=false pieces=1
 *   RESULT width=2.0 rotation=true  ok=false seconds=0.012 error=<message, no newlines>
 * On failure the full stack trace goes to stderr immediately before the RESULT line,
 * bracketed by ---STACKTRACE BEGIN width=W rotation=R--- / ---STACKTRACE END---
 * so the Python wrapper can capture exactly the trace for that one combo.
 */
public class SingleTestProbe {

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: java -cp <classes> SingleTestProbe <polygon.txt> <widths> <overlap> [waypointsOutDir]");
            System.exit(1);
        }
        String polygonPath = args[0];
        String[] widthStrs = args[1].split(",");
        double overlap = Double.parseDouble(args[2]);
        File wpOutDir = args.length > 3 ? new File(args[3]) : null;
        if (wpOutDir != null) wpOutDir.mkdirs();

        String baseName = new File(polygonPath).getName().replaceAll("\\.txt$", "");

        Polygon polygon;
        try {
            polygon = new Polygon(polygonPath);
        } catch (Exception e) {
            // Can't even parse the file -- report once and stop, nothing else can run.
            System.err.println("---STACKTRACE BEGIN parse---");
            e.printStackTrace();
            System.err.println("---STACKTRACE END---");
            System.out.printf(Locale.US, "RESULT width=ALL rotation=ALL ok=false seconds=0.000 error=%s%n",
                escape(safe(e)));
            return;
        }

        Point centroid = Polygon.computeCentroid(polygon);

        for (String ws : widthStrs) {
            double width = Double.parseDouble(ws.trim());
            for (boolean rotation : new boolean[]{false, true}) {
                runOne(polygonPath, width, overlap, rotation, centroid, baseName, wpOutDir);
            }
        }
    }

    private static void runOne(String polygonPath, double width, double overlap, boolean rotation,
                                Point centroid, String baseName, File wpOutDir) {
        long t0 = System.nanoTime();
        try {
            RunConfig cfg = new RunConfig();
            cfg.polygonFilePath = polygonPath;
            cfg.robotWidth = width;
            cfg.overlap = overlap;
            cfg.optimalRotation = rotation;
            cfg.boundarySweep = true;
            cfg.robotLocation = centroid;

            PipelineResult res = PipelineRunner.run(cfg);
            double seconds = (System.nanoTime() - t0) / 1e9;

            if (!res.success || res.waypoints == null || res.waypoints.size() < 2) {
                String err = res.errorMessage == null ? "pipeline failed (no waypoints)" : res.errorMessage;
                System.out.printf(Locale.US, "RESULT width=%s rotation=%s ok=false seconds=%.3f error=%s%n",
                    fmt(width), rotation, seconds, escape(err));
                return;
            }

            if (wpOutDir != null) {
                File out = new File(wpOutDir, String.format(Locale.US, "%s_w%s_rot%s.txt",
                    baseName, fmt(width), rotation ? "1" : "0"));
                try (FileWriter fw = new FileWriter(out)) {
                    for (Point p : res.waypoints) fw.write(String.format(Locale.US, "(%.6f, %.6f)%n", p.x, p.y));
                }
            }

            System.out.printf(Locale.US,
                "RESULT width=%s rotation=%s ok=true  seconds=%.3f waypoints=%d split=%s pieces=%d%n",
                fmt(width), rotation, seconds, res.waypoints.size(), res.polygonWasSplit, res.splitPieceCount);

        } catch (Throwable e) {
            double seconds = (System.nanoTime() - t0) / 1e9;
            System.err.printf(Locale.US, "---STACKTRACE BEGIN width=%s rotation=%s---%n", fmt(width), rotation);
            e.printStackTrace();
            System.err.println("---STACKTRACE END---");
            System.out.printf(Locale.US, "RESULT width=%s rotation=%s ok=false seconds=%.3f error=%s%n",
                fmt(width), rotation, seconds, escape(safe(e)));
        }
    }

    private static String fmt(double v) { return String.format(Locale.US, "%.6f", v); }

    private static String safe(Throwable e) {
        String msg = e.getMessage();
        return (msg != null ? msg : e.getClass().getSimpleName());
    }

    /** Keep the error on one line so the RESULT line stays parseable. */
    private static String escape(String s) {
        return s.replace("\n", " | ").replace("\r", "");
    }
}
