import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.DirectoryChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * BatchModeWindow — standalone window for running the six benchmarking
 * situations against a folder of polygon files. Delegates all actual work
 * to BatchRunner (no JavaFX dependency) and only handles presentation here.
 */
public class BatchModeWindow {

    private final Stage stage;
    private final BatchConfig bc = new BatchConfig();

    // form controls
    private ComboBox<Integer> cbSituation;
    private Label lblPolygonFolder, lblOutputBaseFolder;
    private File polygonFolder, outputBaseFolder;
    private TextField tfFolderName;

    private VBox dynamicParamsBox;
    private Spinner<Double> spFixedWidth;
    private Spinner<Double> spAreaPercent;
    private Spinner<Double> spWidthMin, spWidthMax, spWidthStep;

    private Spinner<Double> spTurnPenalty, spOverlap;
    private ToggleButton tbBoundarySweep;
    private CheckBox cbLiveAnim;

    private Button btnStart, btnCancel, btnOpenOutput;
    private ProgressBar progressBar;
    private Label lblProgress, lblCurrentFile, lblSkipped;
    private TextArea logArea;

    // live preview
    private Canvas previewCanvas;
    private GraphicsContext pGC;
    private Timeline currentAnim;
    private double pvScale = 1, pvOffX = 0, pvOffY = 0;

    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
    private final AtomicBoolean running = new AtomicBoolean(false);

    public BatchModeWindow(Stage owner) {
        stage = new Stage();
        if (owner != null) {
            stage.initModality(Modality.NONE);
        }
        stage.setTitle("Batch Benchmarking — CPP Algorithm");
        build();
    }

    public void show() {
        stage.show();
    }

    // =========================================================================
    //  UI construction
    // =========================================================================
    private void build() {
        Label title = new Label("Batch Benchmarking");
        title.setFont(Font.font("System", FontWeight.BOLD, 20));
        title.setStyle("-fx-text-fill:#1e3a5f;");
        Label sub = new Label("Run one of the six benchmarking situations across many polygons automatically.");
        sub.setStyle("-fx-font-size:12px;-fx-text-fill:#555;");

        // ── Situation selector ──────────────────────────────────────────
        cbSituation = new ComboBox<>();
        for (int i = 1; i <= 6; i++) cbSituation.getItems().add(i);
        cbSituation.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(Integer i) { return i == null ? "" : BatchConfig.situationLabel(i); }
            @Override public Integer fromString(String s) { return null; }
        });
        cbSituation.getSelectionModel().select(0);
        cbSituation.setMaxWidth(Double.MAX_VALUE);
        cbSituation.valueProperty().addListener((o, ov, nv) -> rebuildDynamicParams());

        // ── Folders ──────────────────────────────────────────────────────
        Button btnPickPolyFolder = new Button("📂 Choose Polygon Folder…");
        lblPolygonFolder = new Label("No folder selected");
        lblPolygonFolder.setStyle("-fx-font-size:11px;-fx-text-fill:#888;");
        btnPickPolyFolder.setOnAction(e -> {
            DirectoryChooser dc = new DirectoryChooser();
            dc.setTitle("Choose folder containing polygon .txt files");
            File f = dc.showDialog(stage);
            if (f != null) {
                polygonFolder = f;
                lblPolygonFolder.setText(f.getAbsolutePath());
                lblPolygonFolder.setStyle("-fx-font-size:11px;-fx-text-fill:#1d7a3c;");
            }
        });

        Button btnPickOutFolder = new Button("💾 Choose Where To Save Results…");
        lblOutputBaseFolder = new Label("No folder selected");
        lblOutputBaseFolder.setStyle("-fx-font-size:11px;-fx-text-fill:#888;");
        btnPickOutFolder.setOnAction(e -> {
            DirectoryChooser dc = new DirectoryChooser();
            dc.setTitle("Choose a location to store the batch results folder");
            File f = dc.showDialog(stage);
            if (f != null) {
                outputBaseFolder = f;
                lblOutputBaseFolder.setText(f.getAbsolutePath());
                lblOutputBaseFolder.setStyle("-fx-font-size:11px;-fx-text-fill:#1d7a3c;");
            }
        });

        tfFolderName = new TextField(defaultFolderName(1));
        cbSituation.valueProperty().addListener((o, ov, nv) -> {
            if (nv != null) tfFolderName.setText(defaultFolderName(nv));
        });
        Label lblFolderNameHint = new Label("Results folder name (editable):");
        lblFolderNameHint.setStyle("-fx-font-size:11px;-fx-text-fill:#555;");

        VBox foldersBox = new VBox(6,
            btnPickPolyFolder, lblPolygonFolder,
            new Separator(),
            btnPickOutFolder, lblOutputBaseFolder,
            lblFolderNameHint, tfFolderName);

        // ── Dynamic per-situation params ─────────────────────────────────
        dynamicParamsBox = new VBox(10);
        rebuildDynamicParams();

        // ── Common params ────────────────────────────────────────────────
        spTurnPenalty = makeSpinner(0.0, 20.0, RunConfig.DEFAULT_TURN_PENALTY, 0.5);
        spOverlap = makeSpinner(0.0, 0.5, RunConfig.DEFAULT_OVERLAP, 0.01);
        tbBoundarySweep = new ToggleButton(RunConfig.DEFAULT_BOUNDARY_SWEEP ? "Boundary Sweep: ON" : "Boundary Sweep: OFF");
        tbBoundarySweep.setSelected(RunConfig.DEFAULT_BOUNDARY_SWEEP);
        tbBoundarySweep.setMaxWidth(Double.MAX_VALUE);
        tbBoundarySweep.setOnAction(e -> tbBoundarySweep.setText(
            tbBoundarySweep.isSelected() ? "Boundary Sweep: ON" : "Boundary Sweep: OFF"));
        cbLiveAnim = new CheckBox("Show live animation while processing (turn off for faster large batches)");
        cbLiveAnim.setSelected(true);

        GridPane commonGrid = new GridPane();
        commonGrid.setHgap(10); commonGrid.setVgap(8);
        commonGrid.addRow(0, new Label("Turn Penalty:"), spTurnPenalty);
        commonGrid.addRow(1, new Label("Overlap Ratio:"), spOverlap);
        VBox commonBox = new VBox(8, commonGrid, tbBoundarySweep, cbLiveAnim);

        // ── Start / cancel ───────────────────────────────────────────────
        btnStart = new Button("▶  Start Batch");
        btnStart.setStyle("-fx-font-size:14px;-fx-padding:10 24;-fx-background-color:#1d7a3c;"
            + "-fx-text-fill:white;-fx-background-radius:7;-fx-font-weight:bold;");
        btnStart.setMaxWidth(Double.MAX_VALUE);
        btnStart.setOnAction(e -> onStartClicked());

        btnCancel = new Button("■  Cancel");
        btnCancel.setDisable(true);
        btnCancel.setMaxWidth(Double.MAX_VALUE);
        btnCancel.setOnAction(e -> cancelRequested.set(true));

        btnOpenOutput = new Button("📁 Open Results Folder");
        btnOpenOutput.setDisable(true);
        btnOpenOutput.setMaxWidth(Double.MAX_VALUE);
        btnOpenOutput.setOnAction(e -> openOutputFolder());

        VBox leftPanel = new VBox(14,
            title, sub, new Separator(),
            new Label("Situation:"), cbSituation,
            new Separator(),
            foldersBox,
            new Separator(),
            dynamicParamsBox,
            new Separator(),
            commonBox,
            new Separator(),
            btnStart, btnCancel, btnOpenOutput);
        leftPanel.setPadding(new Insets(20));
        leftPanel.setPrefWidth(400);
        ScrollPane leftScroll = new ScrollPane(leftPanel);
        leftScroll.setFitToWidth(true);
        leftScroll.setPrefWidth(420);

        // ── Right: progress + live preview ─────────────────────────────
        lblCurrentFile = new Label("Idle.");
        lblCurrentFile.setStyle("-fx-font-size:13px;-fx-font-weight:bold;-fx-text-fill:#1e3a5f;");
        progressBar = new ProgressBar(0);
        progressBar.setMaxWidth(Double.MAX_VALUE);
        lblProgress = new Label("0 / 0 processed");
        lblProgress.setStyle("-fx-font-size:12px;-fx-text-fill:#555;");
        lblSkipped = new Label("Skipped/deleted: 0");
        lblSkipped.setStyle("-fx-font-size:12px;-fx-text-fill:#c0392b;");

        previewCanvas = new Canvas(560, 380);
        pGC = previewCanvas.getGraphicsContext2D();
        drawPlaceholder();
        Pane previewHolder = new Pane(previewCanvas);
        previewHolder.setStyle("-fx-background-color:#f1f5f9;-fx-border-color:#cbd5e1;-fx-border-width:1;");

        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setPrefRowCount(10);
        logArea.setStyle("-fx-font-family:monospace;-fx-font-size:11px;");

        VBox rightPanel = new VBox(10,
            lblCurrentFile, progressBar, lblProgress, lblSkipped,
            new Label("Live Preview:"), previewHolder,
            new Label("Log:"), logArea);
        rightPanel.setPadding(new Insets(20));
        VBox.setVgrow(logArea, Priority.ALWAYS);

        BorderPane root = new BorderPane();
        root.setLeft(leftScroll);
        root.setCenter(rightPanel);

        Scene scene = new Scene(root, 1180, 760);
        stage.setScene(scene);
    }

    private String defaultFolderName(int situation) {
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        return "Batch_Situation" + situation + "_" + ts;
    }

    private void rebuildDynamicParams() {
        int s = cbSituation.getValue() == null ? 1 : cbSituation.getValue();
        dynamicParamsBox.getChildren().clear();

        if (s == 1 || s == 2) {
            spFixedWidth = makeSpinner(0.1, 50.0, RunConfig.DEFAULT_ROBOT_WIDTH, 0.1);
            GridPane g = new GridPane();
            g.setHgap(10); g.setVgap(8);
            g.addRow(0, new Label("Robot Width (fixed):"), spFixedWidth);
            dynamicParamsBox.getChildren().add(g);

        } else if (s == 3 || s == 4) {
            spAreaPercent = makeSpinner(0.01, 50.0, 2.0, 0.1);
            GridPane g = new GridPane();
            g.setHgap(10); g.setVgap(8);
            g.addRow(0, new Label("Robot area as % of polygon area:"), spAreaPercent);
            Label note = new Label("Robot is modelled as a circle of that area; its diameter is used as robot width.");
            note.setWrapText(true);
            note.setStyle("-fx-font-size:11px;-fx-text-fill:#888;");
            dynamicParamsBox.getChildren().addAll(g, note);

        } else { // 5 or 6
            spWidthMin = makeSpinner(0.05, 50.0, 0.5, 0.1);
            spWidthMax = makeSpinner(0.05, 50.0, 2.0, 0.1);
            spWidthStep = makeSpinner(0.01, 20.0, 0.5, 0.05);
            GridPane g = new GridPane();
            g.setHgap(10); g.setVgap(8);
            g.addRow(0, new Label("Width min:"), spWidthMin);
            g.addRow(1, new Label("Width max:"), spWidthMax);
            g.addRow(2, new Label("Width step:"), spWidthStep);
            Label note = new Label("Runs EVERY polygon in the folder swept from min to max width.");
            note.setWrapText(true);
            note.setStyle("-fx-font-size:11px;-fx-text-fill:#888;");
            dynamicParamsBox.getChildren().addAll(g, note);
        }
    }

    private Spinner<Double> makeSpinner(double min, double max, double def, double step) {
        Spinner<Double> s = new Spinner<>(min, max, def, step);
        s.setEditable(true);
        s.setMaxWidth(140);
        return s;
    }

    // =========================================================================
    //  Start / run
    // =========================================================================
    private void onStartClicked() {
        if (polygonFolder == null || !polygonFolder.isDirectory()) {
            showAlert("Please choose a valid polygon folder first."); return;
        }
        if (outputBaseFolder == null || !outputBaseFolder.isDirectory()) {
            showAlert("Please choose a valid location to store results first."); return;
        }
        String folderName = tfFolderName.getText() == null ? "" : tfFolderName.getText().trim();
        if (folderName.isEmpty()) { showAlert("Please give the results folder a name."); return; }

        File outDir = new File(outputBaseFolder, folderName);
        if (!outDir.exists() && !outDir.mkdirs()) {
            showAlert("Could not create folder:\n" + outDir.getAbsolutePath()); return;
        }

        bc.situation = cbSituation.getValue();
        bc.polygonFolder = polygonFolder;
        bc.outputFolder = outDir;
        bc.turnPenalty = spTurnPenalty.getValue();
        bc.overlap = spOverlap.getValue();
        bc.boundarySweep = tbBoundarySweep.isSelected();
        bc.showLiveAnimation = cbLiveAnim.isSelected();

        if (bc.isFixedWidth()) {
            bc.fixedRobotWidth = spFixedWidth.getValue();
        } else if (bc.isPercentWidth()) {
            bc.areaPercent = spAreaPercent.getValue();
        } else {
            bc.widthMin = spWidthMin.getValue();
            bc.widthMax = spWidthMax.getValue();
            bc.widthStep = spWidthStep.getValue();
        }

        cancelRequested.set(false);
        running.set(true);
        btnStart.setDisable(true);
        btnCancel.setDisable(false);
        btnOpenOutput.setDisable(true);
        logArea.clear();
        progressBar.setProgress(0);
        lblProgress.setText("0 / 0 processed");
        lblSkipped.setText("Skipped/deleted: 0");
        appendLog("Starting batch — " + BatchConfig.situationLabel(bc.situation));
        appendLog("Polygon folder: " + bc.polygonFolder.getAbsolutePath());
        appendLog("Results folder: " + bc.outputFolder.getAbsolutePath());

        Thread worker = new Thread(() -> BatchRunner.run(bc, new GuiListener()));
        worker.setDaemon(true);
        worker.start();
    }

    // =========================================================================
    //  BatchRunner.BatchProgressListener — hops to the JavaFX thread for all UI work
    // =========================================================================
    private class GuiListener implements BatchRunner.BatchProgressListener {

        @Override
        public void onStep(String fileName, double robotWidth, int stepIndex, int totalSteps) {
            Platform.runLater(() -> {
                lblCurrentFile.setText("Processing: " + fileName + String.format(Locale.US, "  (width=%.4f)", robotWidth));
                if (totalSteps > 0) progressBar.setProgress((double) (stepIndex - 1) / totalSteps);
                lblProgress.setText(stepIndex + " / " + totalSteps + " processed");
            });
        }

        @Override
        public void onStepSuccess(PipelineResult result, RunConfig usedConfig, int stepIndex, int totalSteps) {
            Platform.runLater(() -> {
                appendLog(String.format(Locale.US, "  OK  %s  width=%.4f  waypoints=%d",
                    result.originalPolygon.name, usedConfig.robotWidth, result.waypoints.size()));
                animatePreview(result);
            });
        }

        @Override
        public void onStepSkipped(String fileName, double robotWidth, String reason, int stepIndex, int totalSteps) {
            Platform.runLater(() -> appendLog(String.format(Locale.US,
                "  SKIP %s  width=%.4f  -> %s", fileName, robotWidth, reason)));
        }

        @Override
        public void onPolygonDeleted(String fileName, String reason) {
            Platform.runLater(() -> {
                appendLog("  DELETED " + fileName + " -> " + reason);
                lblSkipped.setText("Skipped/deleted: " + (skippedCount += 1));
            });
        }

        private int skippedCount = 0;

        @Override
        public boolean isCancelled() {
            return cancelRequested.get();
        }

        @Override
        public void onBatchComplete(int rowsWritten, int polygonsSkippedOrDeleted, File excelFile, File heatmapsDir, File logFile) {
            Platform.runLater(() -> {
                running.set(false);
                btnStart.setDisable(false);
                btnCancel.setDisable(true);
                btnOpenOutput.setDisable(false);
                progressBar.setProgress(1.0);
                lblCurrentFile.setText(cancelRequested.get() ? "Cancelled — partial results saved." : "Done.");
                appendLog("");
                appendLog("=== Batch complete ===");
                appendLog("Rows written: " + rowsWritten);
                appendLog("Polygons skipped/deleted: " + polygonsSkippedOrDeleted);
                appendLog("Excel file: " + excelFile.getAbsolutePath());
                appendLog("Heatmaps folder: " + heatmapsDir.getAbsolutePath());
                appendLog("Log file: " + logFile.getAbsolutePath());
            });
        }

        @Override
        public void onBatchFailed(String message) {
            Platform.runLater(() -> {
                running.set(false);
                btnStart.setDisable(false);
                btnCancel.setDisable(true);
                appendLog("BATCH FAILED: " + message);
                showAlert("Batch failed:\n" + message);
            });
        }
    }

    private void appendLog(String line) {
        logArea.appendText(line + "\n");
    }

    private void openOutputFolder() {
        try {
            java.awt.Desktop.getDesktop().open(bc.outputFolder);
        } catch (Exception e) {
            showAlert("Could not open folder automatically. It is located at:\n" + bc.outputFolder.getAbsolutePath());
        }
    }

    private void showAlert(String msg) {
        Alert a = new Alert(Alert.AlertType.WARNING, msg, ButtonType.OK);
        a.setHeaderText(null);
        a.showAndWait();
    }

    // =========================================================================
    //  Live preview drawing
    // =========================================================================
    private void drawPlaceholder() {
        double w = previewCanvas.getWidth(), h = previewCanvas.getHeight();
        pGC.setFill(Color.web("#f1f5f9"));
        pGC.fillRect(0, 0, w, h);
        pGC.setFill(Color.web("#94a3b8"));
        pGC.setTextAlign(javafx.scene.text.TextAlignment.CENTER);
        pGC.fillText("Live preview will appear here once the batch starts", w / 2, h / 2);
    }

    private void animatePreview(PipelineResult result) {
        if (currentAnim != null) currentAnim.stop();
        Polygon poly = result.originalPolygon;
        List<Point> wp = result.waypoints;
        if (poly == null || wp == null || wp.isEmpty()) return;

        fitToCanvas(poly);

        if (!bc.showLiveAnimation || wp.size() < 3) {
            drawFrame(poly, wp, wp.size() - 1);
            return;
        }

        int n = wp.size();
        int frames = Math.min(80, Math.max(10, n));
        double totalMs = 900;
        currentAnim = new Timeline();
        List<KeyFrame> kfs = new ArrayList<>();
        for (int f = 0; f <= frames; f++) {
            int wpIdx = (int) Math.round((double) f / frames * (n - 1));
            KeyFrame kf = new KeyFrame(Duration.millis(totalMs * f / frames),
                e -> drawFrame(poly, wp, wpIdx));
            kfs.add(kf);
        }
        currentAnim.getKeyFrames().setAll(kfs);
        currentAnim.play();
    }

    private void fitToCanvas(Polygon poly) {
        double cw = previewCanvas.getWidth(), ch = previewCanvas.getHeight();
        double w = poly.xmax - poly.xmin, h = poly.ymax - poly.ymin;
        if (w <= 0 || h <= 0) { pvScale = 1; pvOffX = cw / 2; pvOffY = ch / 2; return; }
        pvScale = Math.min(cw / (w + 40), ch / (h + 40)) * 0.9;
        pvOffX = cw / 2.0 - (poly.xmin + w / 2.0) * pvScale;
        pvOffY = ch / 2.0 + (poly.ymin + h / 2.0) * pvScale;
    }

    private double[] s2c(double wx, double wy) {
        return new double[]{wx * pvScale + pvOffX, pvOffY - wy * pvScale};
    }

    private void drawFrame(Polygon poly, List<Point> wp, int uptoIndex) {
        double cw = previewCanvas.getWidth(), ch = previewCanvas.getHeight();
        pGC.setFill(Color.web("#f1f5f9"));
        pGC.fillRect(0, 0, cw, ch);

        // polygon outline
        pGC.setStroke(Color.web("#1e40af"));
        pGC.setLineWidth(2.0);
        pGC.setFill(Color.web("#dbeafe", 0.5));
        double[] xs = new double[poly.polygonPoints.size()];
        double[] ys = new double[poly.polygonPoints.size()];
        for (int i = 0; i < poly.polygonPoints.size(); i++) {
            double[] c = s2c(poly.polygonPoints.get(i).x, poly.polygonPoints.get(i).y);
            xs[i] = c[0]; ys[i] = c[1];
        }
        pGC.fillPolygon(xs, ys, xs.length);
        pGC.strokePolygon(xs, ys, xs.length);

        // path up to current index
        if (wp != null && wp.size() > 1) {
            pGC.setStroke(Color.web("#f97316"));
            pGC.setLineWidth(1.6);
            int limit = Math.min(uptoIndex, wp.size() - 1);
            for (int i = 0; i < limit; i++) {
                double[] a = s2c(wp.get(i).x, wp.get(i).y);
                double[] b = s2c(wp.get(i + 1).x, wp.get(i + 1).y);
                pGC.strokeLine(a[0], a[1], b[0], b[1]);
            }
            // robot marker
            Point cur = wp.get(Math.max(0, Math.min(uptoIndex, wp.size() - 1)));
            double[] rp = s2c(cur.x, cur.y);
            pGC.setFill(Color.web("#1d7a3c"));
            pGC.fillOval(rp[0] - 5, rp[1] - 5, 10, 10);
        }

        pGC.setFill(Color.web("#1e3a5f"));
        pGC.setTextAlign(javafx.scene.text.TextAlignment.LEFT);
        pGC.fillText(poly.name, 8, 16);
    }
}
