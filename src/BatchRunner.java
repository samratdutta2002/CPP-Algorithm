import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * BatchRunner — pure-Java (no JavaFX) orchestration of the six benchmarking
 * situations. Call run(cfg, listener) from a background thread; the listener
 * callbacks are invoked synchronously on that same thread, so a GUI caller
 * should hop back to the UI thread itself (e.g. Platform.runLater) inside
 * its listener implementation.
 */
public class BatchRunner {

    public static final String[] HEADER = {
        "Idx", "Polygon Name", "Vertices (m)", "Reflex Vertices (n)", "Complexity (m+n)", "Area",
        "Robot Width", "Aspect Ratio", "Start Point", "Coverage (%)", "Redundancy (%)", "Path Length",
        "Coverage/Unit (Area/Length)", "Turns", "Rotation Angle (deg)", "Norm. Path Length (PL/sqrt(A))",
        "Norm. Turns (T/sqrt(A))", "Turn Density (T/PL)", "Time(sec)",
        "Situation", "Optimal Rotation", "Robot Area % of Polygon"
    };

    public interface BatchProgressListener {
        /** Called before a polygon (or a polygon+width combination) starts running. */
        void onStep(String fileName, double robotWidth, int stepIndex, int totalSteps);

        /** Called after a run succeeds; carries the full pipeline result for live preview. */
        void onStepSuccess(PipelineResult result, RunConfig usedConfig, int stepIndex, int totalSteps);

        /** Called after a run fails or a width is skipped for a polygon. */
        void onStepSkipped(String fileName, double robotWidth, String reason, int stepIndex, int totalSteps);

        /** Called when a polygon file itself is deleted (unrecoverable for this situation). */
        void onPolygonDeleted(String fileName, String reason);

        /** Polled periodically; return true to stop the batch early (partial results are still saved). */
        boolean isCancelled();

        void onBatchComplete(int rowsWritten, int polygonsSkippedOrDeleted, File excelFile, File heatmapsDir, File logFile);

        void onBatchFailed(String message);
    }

    public static void run(BatchConfig bc, BatchProgressListener listener) {
        List<Object[]> rows = new ArrayList<>();
        List<String> logLines = new ArrayList<>();
        int polygonsSkippedOrDeleted = 0;

        try {
            if (bc.polygonFolder == null || !bc.polygonFolder.isDirectory()) {
                listener.onBatchFailed("Polygon folder is not valid: " + bc.polygonFolder);
                return;
            }
            if (bc.outputFolder == null) {
                listener.onBatchFailed("Output folder is not set.");
                return;
            }
            bc.outputFolder.mkdirs();
            File heatmapsDir = new File(bc.outputFolder, "HeatMaps");
            heatmapsDir.mkdirs();

            File[] files = bc.polygonFolder.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".txt"));
            if (files == null) files = new File[0];
            Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));

            double[] widths = bc.isSweepWidth() ? buildWidthSweep(bc) : null;
            int totalSteps = bc.isSweepWidth() ? files.length * (widths == null ? 0 : widths.length) : files.length;

            int stepIndex = 0;
            int idx = 1;

            for (File file : files) {
                if (listener.isCancelled()) break;

                String fileName = file.getName();
                Polygon probe;
                try {
                    probe = new Polygon(file.getAbsolutePath());
                } catch (Exception ex) {
                    stepIndex++;
                    logLines.add("[DELETED] " + fileName + " — could not be parsed: " + safeMsg(ex));
                    deleteQuietly(file);
                    polygonsSkippedOrDeleted++;
                    listener.onPolygonDeleted(fileName, "Could not be parsed: " + safeMsg(ex));
                    continue;
                }

                if (bc.isFixedWidth() || bc.isPercentWidth()) {
                    stepIndex++;
                    double width = bc.isFixedWidth()
                        ? bc.fixedRobotWidth
                        : BatchConfig.widthFromAreaPercent(probe.area, bc.areaPercent);

                    listener.onStep(fileName, width, stepIndex, totalSteps);

                    StepOutcome outcome = runOneStep(bc, file, probe, width);
                    if (outcome.success) {
                        rows.add(buildRow(idx++, probe, outcome, bc, width));
                        exportHeatmap(heatmapsDir, probe.name, outcome, width, logLines);
                        listener.onStepSuccess(outcome.result, outcome.usedConfig, stepIndex, totalSteps);
                    } else {
                        logLines.add("[DELETED] " + fileName + " — " + outcome.errorMessage);
                        deleteQuietly(file);
                        polygonsSkippedOrDeleted++;
                        listener.onStepSkipped(fileName, width, outcome.errorMessage, stepIndex, totalSteps);
                        listener.onPolygonDeleted(fileName, outcome.errorMessage);
                    }

                } else { // sweep width — situations 5 / 6
                    boolean anySucceeded = false;
                    List<String> failuresForThisFile = new ArrayList<>();

                    for (double width : widths) {
                        if (listener.isCancelled()) break;
                        stepIndex++;
                        listener.onStep(fileName, width, stepIndex, totalSteps);

                        StepOutcome outcome = runOneStep(bc, file, probe, width);
                        if (outcome.success) {
                            anySucceeded = true;
                            rows.add(buildRow(idx++, probe, outcome, bc, width));
                            exportHeatmap(heatmapsDir, probe.name, outcome, width, logLines);
                            listener.onStepSuccess(outcome.result, outcome.usedConfig, stepIndex, totalSteps);
                        } else {
                            failuresForThisFile.add(String.format(Locale.US, "width=%.4f -> %s", width, outcome.errorMessage));
                            listener.onStepSkipped(fileName, width, outcome.errorMessage, stepIndex, totalSteps);
                        }
                    }

                    if (!anySucceeded) {
                        // Every width in the sweep failed for this polygon — treat the file as broken.
                        logLines.add("[DELETED] " + fileName + " — every width in the sweep failed:");
                        for (String f : failuresForThisFile) logLines.add("    " + f);
                        deleteQuietly(file);
                        polygonsSkippedOrDeleted++;
                        listener.onPolygonDeleted(fileName, "Every width in the sweep failed.");
                    } else if (!failuresForThisFile.isEmpty()) {
                        logLines.add("[PARTIAL] " + fileName + " — some widths skipped:");
                        for (String f : failuresForThisFile) logLines.add("    " + f);
                    }
                }
            }

            File excelFile = new File(bc.outputFolder, "BenchmarkData_Situation" + bc.situation + ".xlsx");
            XlsxWriter.write(excelFile, "Benchmark", HEADER, rows);

            File logFile = new File(bc.outputFolder, "BatchLog.txt");
            try (PrintWriter pw = new PrintWriter(new FileWriter(logFile))) {
                pw.println("Batch run — Situation " + bc.situation + " (" + BatchConfig.situationLabel(bc.situation) + ")");
                pw.println("Polygon folder: " + bc.polygonFolder.getAbsolutePath());
                pw.println("Rows written  : " + rows.size());
                pw.println("Polygons skipped/deleted: " + polygonsSkippedOrDeleted);
                pw.println();
                for (String l : logLines) pw.println(l);
            }

            listener.onBatchComplete(rows.size(), polygonsSkippedOrDeleted, excelFile, heatmapsDir, logFile);

        } catch (Exception e) {
            listener.onBatchFailed(safeMsg(e));
        }
    }

    // ── Single (polygon, width) run ─────────────────────────────────────────

    private static class StepOutcome {
        boolean success;
        String errorMessage = "";
        PipelineResult result;
        RunConfig usedConfig;
        double elapsedSeconds;
    }

    private static StepOutcome runOneStep(BatchConfig bc, File file, Polygon probe, double width) {
        StepOutcome out = new StepOutcome();

        if (width <= 0.0 || Double.isNaN(width) || Double.isInfinite(width)) {
            out.success = false;
            out.errorMessage = "Computed robot width is invalid (<= 0).";
            return out;
        }

        RunConfig cfg = new RunConfig();
        cfg.polygonFilePath = file.getAbsolutePath();
        cfg.robotWidth = width;
        cfg.turnPenalty = bc.turnPenalty;
        cfg.overlap = bc.overlap;
        cfg.optimalRotation = bc.isOptimalRotation();
        cfg.boundarySweep = bc.boundarySweep;
        // "Nearest valid point to the polygon's own centroid" — PipelineRunner already
        // falls back from a click point to the nearest valid region/corner, so handing
        // it the raw centroid produces exactly that behaviour.
        cfg.robotLocation = Polygon.computeCentroid(probe);

        long t0 = System.nanoTime();
        PipelineResult res;
        try {
            res = PipelineRunner.run(cfg);
        } catch (Exception ex) {
            out.success = false;
            out.errorMessage = "Pipeline threw an exception: " + safeMsg(ex);
            return out;
        }
        double elapsed = (System.nanoTime() - t0) / 1_000_000_000.0;

        if (!res.success || res.waypoints == null || res.waypoints.size() < 2) {
            out.success = false;
            out.errorMessage = (res.errorMessage == null || res.errorMessage.isBlank())
                ? "Pipeline failed (possibly robot width too large for this polygon)."
                : res.errorMessage;
            return out;
        }

        out.success = true;
        out.result = res;
        out.usedConfig = cfg;
        out.elapsedSeconds = elapsed;
        return out;
    }

    private static double[] buildWidthSweep(BatchConfig bc) {
        double min = Math.min(bc.widthMin, bc.widthMax);
        double max = Math.max(bc.widthMin, bc.widthMax);
        double step = Math.abs(bc.widthStep);
        if (step < 1e-9) step = max - min > 0 ? (max - min) : 1.0;
        int n = (int) Math.floor((max - min) / step + 1e-9) + 1;
        n = Math.max(1, n);
        double[] widths = new double[n];
        for (int i = 0; i < n; i++) widths[i] = min + i * step;
        widths[n - 1] = Math.min(widths[n - 1], max);
        return widths;
    }

    private static Object[] buildRow(int idx, Polygon probe, StepOutcome outcome, BatchConfig bc, double width) {
        MetricsCalculator.Metrics m = MetricsCalculator.compute(outcome.result.originalPolygon, outcome.result.waypoints, width);

        int vertices = probe.polygonPoints.size();
        int reflex = probe.reflexiveVertexCount;
        double area = m.polygonArea;
        double aspectRatio = MetricsCalculator.aspectRatio(probe);
        Point start = outcome.result.robotStartPoint;
        String startStr = start == null ? "" : String.format(Locale.US, "(%.4f, %.4f)", start.x, start.y);

        double normPathLength = area > 0 ? m.pathLength / Math.sqrt(area) : 0.0;
        double normTurns = area > 0 ? m.turnCount / Math.sqrt(area) : 0.0;
        double turnDensity = m.pathLength > 0 ? m.turnCount / m.pathLength : 0.0;

        double robotArea = Math.PI * (width / 2.0) * (width / 2.0);
        double robotAreaPct = area > 0 ? 100.0 * robotArea / area : 0.0;

        return new Object[]{
            idx, probe.name, vertices, reflex, (vertices + reflex), round(area, 6),
            round(width, 6), round(aspectRatio, 6), startStr, round(m.coveragePct, 4), round(m.redundancyPct, 4),
            round(m.pathLength, 6), round(m.coveragePerUnit, 6), m.turnCount, round(outcome.result.rotationAngle, 4),
            round(normPathLength, 6), round(normTurns, 6), round(turnDensity, 6), round(outcome.elapsedSeconds, 4),
            bc.situation, (bc.isOptimalRotation() ? "ON" : "OFF"), round(robotAreaPct, 4)
        };
    }

    private static void exportHeatmap(File heatmapsDir, String polyBaseName, StepOutcome outcome,
                                       double width, List<String> logLines) {
        String suffix = "";
        // Multiple widths per polygon (situations 5/6) need unique heatmap filenames.
        String fname = polyBaseName + suffix + ".png";
        File target = new File(heatmapsDir, fname);
        if (target.exists()) {
            fname = polyBaseName + "_w" + String.format(Locale.US, "%.4f", width).replace('.', 'p') + ".png";
            target = new File(heatmapsDir, fname);
        }
        try {
            HeatmapExporter.export(target, outcome.result.originalPolygon, outcome.result.waypoints, width);
        } catch (IOException e) {
            logLines.add("[WARN] Heatmap export failed for " + polyBaseName + " (w=" + width + "): " + safeMsg(e));
        }
    }

    private static double round(double v, int decimals) {
        double f = Math.pow(10, decimals);
        return Math.round(v * f) / f;
    }

    private static void deleteQuietly(File f) {
        try { f.delete(); } catch (Exception ignored) { }
    }

    private static String safeMsg(Exception e) {
        String m = e.getMessage();
        return (m == null || m.isBlank()) ? e.getClass().getSimpleName() : m;
    }
}
