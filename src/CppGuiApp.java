import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.*;
import javafx.concurrent.Task;
import javafx.geometry.*;
import javafx.scene.*;
import javafx.scene.canvas.*;
import javafx.scene.control.*;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.*;
import javafx.scene.paint.*;
import javafx.scene.text.*;
import javafx.stage.*;
import javafx.animation.*;
import javafx.scene.image.*;
import javafx.embed.swing.SwingFXUtils;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.*;

public class CppGuiApp extends Application {

    // ── Shared state ──────────────────────────────────────────────────────
    private Stage      primaryStage;
    private RunConfig  config = new RunConfig();

    // ── Setup scene ───────────────────────────────────────────────────────
    private Polygon      previewPolygon = null;
    private Canvas       previewCanvas  = null;
    private boolean      robotPlaced    = false;
    // preview viewport (supports zoom/drag)
    private double       pvScale = 1, pvOffX = 0, pvOffY = 0;
    private boolean      pvFitted = false;
    private double       pvDragX, pvDragY;

    private Spinner<Double> spRobotWidth, spTurnPenalty, spOverlap;
    private ToggleButton    tbOptRotation, tbBoundarySweep;
    private Label           lblRobotCoords, lblFileStatus;
    private Button          btnGenerate;
    private TextField       tfRobotX, tfRobotY;
    private Button          btnApplyRobotXY;
    // guard to avoid feedback loop when we programmatically update the text
    // fields from a mouse click (setText() would otherwise re-trigger listeners)
    private boolean         suppressRobotFieldSync = false;

    // ── Simulator scene ───────────────────────────────────────────────────
    private PipelineResult result;
    private Canvas         simCanvas;
    private GraphicsContext simGC;

    // sim viewport
    private double  simScale = 1, simOffX = 0, simOffY = 0;
    private boolean simFitted = false;
    // Per-view viewport memory: mode -> {scale, offX, offY}
    private final Map<String,double[]> viewportMemory = new HashMap<>();
    private double  simDragX, simDragY;

    // animation
    private AnimationTimer animTimer;
    private int    wpIndex = 0;
    private double wpT     = 0;
    private double animSpeed = 0.01;
    private boolean animRunning = false, animPaused = false;
    private double[] robotPos = null;

    // coverage stamps
    private List<double[]> coverageStrokes = new ArrayList<>();
    private double[]       lastStampPos    = null;
    private boolean        showCoverage    = true;
    private boolean        showRobot       = true;
    private double         robotDiameter;

    // timing
    private long   startMs = 0, endMs = 0, pausedMs = 0, pauseStartMs = 0;
    private boolean timingStarted = false;

    // display mode: "sim", "original", "shrunk", "rotated", "hvsdcel", "graph", "heatmap"
    private String  displayMode = "sim";
    // pre-rendered heatmap image (computed once on demand)
    private WritableImage heatmapImage = null;
    private boolean heatmapDirty = true;

    // dark/light mode
    private boolean darkMode      = true;
    private boolean showGraphPaper = false;  // default off

    // right panel reference (needed for rebuild on dark/light toggle)
    private VBox rightPanelBox;
    private ScrollPane rightScroll;
    private SplitPane  simSplitPane;
    private HBox       topBarRef;
    private BorderPane simRoot;

    // ── View-specific display settings ────────────────────────────────────
    private boolean showCoords      = false; // off by default; ON only in Original Polygon view
    private boolean showRegionNames = true;
    private double  coordFontSize   = 10.0;
    private double  regionFontSize  = 11.0;
    private double  vertexDotSize   = 6.0;

    // ── Draggable coordinate labels ───────────────────────────────────────
    // Key = "x,y" world-coord string. Value = manual screen offset {dx, dy}
    // from the auto-layout position. Null entry = use auto-layout.
    private final Map<String,double[]> labelOffsets = new HashMap<>();
    private String  dragLabelKey = null;   // key of label being dragged
    private double  dragLabelStartX, dragLabelStartY;   // mouse at drag start
    private double  dragLabelOrigX,  dragLabelOrigY;    // offset at drag start

    /** Return screen position for a coord label, respecting manual drag offset. */
    private double[] labelPos(double wx, double wy, double autoSX, double autoSY) {
        String key = wx + "," + wy;
        double[] off = labelOffsets.get(key);
        if (off == null) return new double[]{autoSX, autoSY};
        return new double[]{autoSX + off[0], autoSY + off[1]};
    }

    /** Hit-test: which label (if any) is at screen pos (mx,my)? */
    private String hitTestLabel(double mx, double my) {
        if (result == null) return null;
        // Collect all polygons currently visible (decomposed view has multiple)
        List<Polygon> polys = new ArrayList<>();
        if (displayMode.equals("decomposed") && result.decomposedRegions != null)
            polys.addAll(result.decomposedRegions);
        else { Polygon ap = activePoly(); if (ap != null) polys.add(ap); }
        if (polys.isEmpty()) return null;

        for (Polygon poly : polys) {
            for (Point p : poly.polygonPoints) {
                double[] s  = s2c(p.x, p.y);
                double dotR = vertexDotSize / 2.0;
                // MUST match drawVertexDots autoX/autoY exactly
                double autoX = s[0] + dotR + 10;
                double autoY = s[1] - 8;
                double[] lp  = labelPos(p.x, p.y, autoX, autoY);

                // Compute actual pill bounds (same formula as drawVertexDots)
                String txt = String.format("(%.2f, %.2f)", p.x, p.y);
                double tw   = txt.length() * coordFontSize * 0.56;
                double th   = coordFontSize + 6;
                double pillX = lp[0] - 2;
                double pillY = lp[1] - th + 3;
                double pillW = tw + 4;
                double pillH = th;

                // Generous hit area: pill bounds expanded by 6px on each side
                if (mx >= pillX - 6 && mx <= pillX + pillW + 6
                        && my >= pillY - 6 && my <= pillY + pillH + 6)
                    return p.x + "," + p.y;
            }
        }
        return null;
    }

    /** Return the polygon currently being displayed (for drag hit-test). */
    private Polygon activePoly() {
        if (result == null) return null;
        return switch (displayMode) {
            case "original"   -> result.originalPolygon;
            case "rotated"    -> result.rotatedPolygon;
            case "shrunk"     -> result.shrunkPolygon;
            case "hvsdcel"    -> result.hvsDcelPolygon;
            case "decomposed" -> result.shrunkPolygon;
            default           -> result.originalPolygon;
        };
    }

    // =====================================================================
    @Override
    public void start(Stage stage) {
        this.primaryStage = stage;
        stage.setOnCloseRequest(e -> Platform.exit());
        showSetupScene();
        stage.show();
    }

    // =====================================================================
    //  SCENE 1 — Setup
    // =====================================================================
    private void showSetupScene() {
        primaryStage.setTitle("CPP Algorithm — Setup");

        Label lblTitle = new Label("Coverage Path Planning");
        lblTitle.setStyle("-fx-font-size:22px;-fx-font-weight:bold;-fx-text-fill:#1e3a5f;");
        Label lblSub = new Label("Configure parameters and select robot start location");
        lblSub.setStyle("-fx-font-size:13px;-fx-text-fill:#555;");
        VBox titleBox = new VBox(4, lblTitle, lblSub);
        titleBox.setPadding(new Insets(0,0,12,0));

        lblFileStatus = new Label("No polygon file loaded");
        lblFileStatus.setStyle("-fx-font-size:12px;-fx-text-fill:#888;");
        Button btnOpen = new Button("📂  Open Polygon File (.txt)");
        btnOpen.setStyle("-fx-font-size:13px;-fx-padding:8 18;-fx-background-color:#1e3a5f;"
            +"-fx-text-fill:white;-fx-background-radius:6;");
        btnOpen.setOnAction(e -> handleOpenFile());

        Button btnBatch = new Button("🗂  Batch Mode (Multi-Polygon Benchmarking)");
        btnBatch.setStyle("-fx-font-size:12px;-fx-padding:7 14;-fx-background-color:#6d28d9;"
            +"-fx-text-fill:white;-fx-background-radius:6;");
        btnBatch.setOnAction(e -> new BatchModeWindow(primaryStage).show());

        // ── Preview canvas: fixed size, no bindings, no listeners ──────────
        // Binding canvas to a growing pane + adding resize listeners causes a
        // feedback loop: draw → layout pass → pane grows → listener → draw …
        // Solution: use a plain fixed Canvas. It redraws only on explicit calls.
        // The canvas is placed in a BorderPane center so it gets all remaining
        // space naturally without any programmatic size tracking.
        previewCanvas = new Canvas(500, 380);
        previewCanvas.setStyle("-fx-effect: null;");

        Pane canvasPaneHolder = new Pane(previewCanvas);
        canvasPaneHolder.setStyle("-fx-background-color:#f1f5f9;-fx-border-color:#cbd5e1;-fx-border-width:1;");
        canvasPaneHolder.setMinHeight(280);
        // Resize canvas when pane is resized by layout — one-way, no listener loop
        canvasPaneHolder.layoutBoundsProperty().addListener((obs, ov, nv) -> {
            double nw = nv.getWidth(), nh = nv.getHeight();
            if (nw > 10 && nh > 10
                    && (Math.abs(nw - previewCanvas.getWidth())  > 2.0
                     || Math.abs(nh - previewCanvas.getHeight()) > 2.0)) {
                previewCanvas.setWidth(nw);
                previewCanvas.setHeight(nh);
                pvFitted = false;
                // Redraw after layout is fully settled — not inside the listener
                Platform.runLater(() -> {
                    if (previewPolygon != null) refreshPreview(config.robotLocation);
                    else drawPreviewPlaceholder();
                });
            }
        });

        canvasPaneHolder.setOnMousePressed(e -> { pvDragX = e.getX(); pvDragY = e.getY(); });
        canvasPaneHolder.setOnMouseDragged(e -> {
            pvOffX += e.getX() - pvDragX; pvOffY += e.getY() - pvDragY;
            pvDragX = e.getX(); pvDragY = e.getY();
            refreshPreview(config.robotLocation);
        });
        canvasPaneHolder.setOnScroll((ScrollEvent e) -> {
            if (previewPolygon == null) return;
            double z = e.getDeltaY() > 0 ? 1.12 : 0.89;
            pvOffX = e.getX() - z*(e.getX()-pvOffX);
            pvOffY = e.getY() - z*(e.getY()-pvOffY);
            pvScale *= z;
            refreshPreview(config.robotLocation);
        });
        canvasPaneHolder.setOnMouseClicked(this::handlePreviewClick);

        Label lblHint = new Label("💡 Click inside the polygon to place the robot, or type coordinates below. Scroll to zoom, drag to pan.");
        lblHint.setStyle("-fx-font-size:11px;-fx-text-fill:#888;-fx-wrap-text:true;");
        lblHint.setMaxWidth(Double.MAX_VALUE);
        lblRobotCoords = new Label("Robot location: not set");
        lblRobotCoords.setStyle("-fx-font-size:12px;-fx-text-fill:#555;-fx-font-style:italic;");

        // ── Manual X / Y entry (kept in sync with mouse clicks) ────────────
        Label lblX = new Label("X:");
        lblX.setStyle("-fx-font-size:12px;-fx-text-fill:#555;");
        tfRobotX = new TextField();
        tfRobotX.setPromptText("x");
        tfRobotX.setPrefWidth(80);
        Label lblY = new Label("Y:");
        lblY.setStyle("-fx-font-size:12px;-fx-text-fill:#555;");
        tfRobotY = new TextField();
        tfRobotY.setPromptText("y");
        tfRobotY.setPrefWidth(80);
        btnApplyRobotXY = new Button("Set");
        btnApplyRobotXY.setStyle("-fx-font-size:12px;-fx-padding:4 12;"
            +"-fx-background-color:#1e3a5f;-fx-text-fill:white;-fx-background-radius:5;");
        btnApplyRobotXY.setOnAction(e -> handleManualRobotEntry());
        tfRobotX.setOnAction(e -> handleManualRobotEntry());
        tfRobotY.setOnAction(e -> handleManualRobotEntry());

        HBox manualEntryRow = new HBox(6, lblX, tfRobotX, lblY, tfRobotY, btnApplyRobotXY);
        manualEntryRow.setAlignment(Pos.CENTER_LEFT);

        drawPreviewPlaceholder();

        // Use BorderPane so canvasPaneHolder gets all remaining vertical space
        // without VBox growth triggering a layout→listener→layout loop
        VBox topItems = new VBox(10, titleBox, new Separator(), btnOpen, btnBatch, lblFileStatus);
        topItems.setPadding(new Insets(0));
        VBox bottomItems = new VBox(6, lblHint, manualEntryRow, lblRobotCoords);
        bottomItems.setPadding(new Insets(0));

        BorderPane leftPanel = new BorderPane();
        leftPanel.setTop(topItems);
        leftPanel.setCenter(canvasPaneHolder);
        leftPanel.setBottom(bottomItems);
        BorderPane.setMargin(topItems, new Insets(20, 20, 8, 20));
        BorderPane.setMargin(canvasPaneHolder, new Insets(0, 20, 8, 20));
        BorderPane.setMargin(bottomItems, new Insets(0, 20, 14, 20));
        leftPanel.setStyle("-fx-background-color:#f9fafc;");

        // ── Right: parameter form ─────────────────────────────────────────
        Label lblParams = new Label("Parameters");
        lblParams.setStyle("-fx-font-size:16px;-fx-font-weight:bold;-fx-text-fill:#1e3a5f;");

        GridPane grid = new GridPane();
        grid.setHgap(12); grid.setVgap(14);
        grid.setPadding(new Insets(10,0,10,0));

        spRobotWidth  = makeSpinner(0.1, 20.0, RunConfig.DEFAULT_ROBOT_WIDTH, 0.1);
        spTurnPenalty = makeSpinner(0.0, 20.0, RunConfig.DEFAULT_TURN_PENALTY, 0.5);
        spOverlap     = makeSpinner(0.0, 0.5,  RunConfig.DEFAULT_OVERLAP,      0.01);
        tbOptRotation  = makeToggle("ON","OFF",RunConfig.DEFAULT_OPT_ROTATION);
        tbBoundarySweep= makeToggle("ON","OFF",RunConfig.DEFAULT_BOUNDARY_SWEEP);

        addParamRow(grid,0,"Robot Width","physical width of the robot (units)",spRobotWidth);
        addParamRow(grid,1,"Turn Penalty","cost multiplier per U-turn",spTurnPenalty);
        addParamRow(grid,2,"Overlap Ratio","fraction of width to overlap (0–0.5)",spOverlap);
        addParamRow(grid,3,"Optimal Rotation","rotate to minimise sweep-line count",tbOptRotation);
        addParamRow(grid,4,"Boundary Sweep","append boundary-tracing pass at end",tbBoundarySweep);

        btnGenerate = new Button("▶  Generate Coverage Path");
        btnGenerate.setDisable(true);
        btnGenerate.setStyle("-fx-font-size:14px;-fx-padding:10 24;"
            +"-fx-background-color:#1d7a3c;-fx-text-fill:white;"
            +"-fx-background-radius:7;-fx-font-weight:bold;");
        btnGenerate.setMaxWidth(Double.MAX_VALUE);

        ProgressIndicator pi = new ProgressIndicator();
        pi.setPrefSize(30,30); pi.setVisible(false);
        Label lblRun = new Label("Running algorithm…");
        lblRun.setStyle("-fx-font-size:12px;-fx-text-fill:#555;"); lblRun.setVisible(false);
        HBox progRow = new HBox(8, pi, lblRun);
        progRow.setAlignment(Pos.CENTER_LEFT);

        btnGenerate.setUserData(new Object[]{pi, lblRun});
        btnGenerate.setOnAction(e -> handleGenerate());

        VBox rightPanel = new VBox(16, lblParams, new Separator(),
            grid, new Separator(), btnGenerate, progRow);
        rightPanel.setPadding(new Insets(20));
        rightPanel.setPrefWidth(340);
        rightPanel.setStyle("-fx-background-color:#ffffff;");

        HBox root = new HBox(leftPanel, new Separator(Orientation.VERTICAL), rightPanel);
        HBox.setHgrow(leftPanel, Priority.ALWAYS);

        Scene scene = new Scene(root);
        primaryStage.setScene(scene);
        primaryStage.setWidth(940);
        primaryStage.setHeight(640);
        primaryStage.centerOnScreen();
    }

    private Spinner<Double> makeSpinner(double min, double max, double def, double step) {
        Spinner<Double> s = new Spinner<>(min, max, def, step);
        s.setEditable(true); s.setPrefWidth(110); return s;
    }

    private void addParamRow(GridPane g, int row, String name, String hint, Control ctrl) {
        Label ln = new Label(name);
        ln.setStyle("-fx-font-size:13px;-fx-font-weight:bold;");
        Label lh = new Label(hint);
        lh.setStyle("-fx-font-size:11px;-fx-text-fill:#888;");
        g.add(new VBox(2,ln,lh),0,row);
        g.add(ctrl,1,row);
    }

    // ── File open ─────────────────────────────────────────────────────────
    private void handleOpenFile() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Open Polygon File");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Text files","*.txt"));
        // Priority: last-used dir, then polygon-files next to jar, then user home
        File dir = null;
        if (config.polygonFilePath != null) {
            File last = new File(config.polygonFilePath).getParentFile();
            if (last != null && last.exists()) dir = last;
        }
        if (dir == null) { File d = new File("polygon-files"); if (d.exists()) dir = d; }
        if (dir == null) dir = new File(System.getProperty("user.home"));
        if (dir != null && dir.exists()) fc.setInitialDirectory(dir);
        File f = fc.showOpenDialog(primaryStage);
        if (f == null) return;
        config.polygonFilePath = f.getAbsolutePath();
        lblFileStatus.setText("Loaded: " + f.getName());
        lblFileStatus.setStyle("-fx-font-size:12px;-fx-text-fill:#1d7a3c;");
        try {
            previewPolygon = new Polygon(f.getAbsolutePath());
            robotPlaced = false;
            config.robotLocation = null;
            lblRobotCoords.setText("Robot location: click inside polygon or type X/Y to set");
            suppressRobotFieldSync = true;
            tfRobotX.clear(); tfRobotY.clear();
            suppressRobotFieldSync = false;
            pvFitted = false;
            refreshPreview(null);
            checkGenerateReady();
        } catch (Exception ex) {
            showError("Failed to parse polygon file:\n" + ex.getMessage());
        }
    }

    // ── Preview click: place robot ────────────────────────────────────────
    private void handlePreviewClick(MouseEvent e) {
        if (previewPolygon == null) return;
        // Convert screen → world using current viewport
        double wx = (e.getX() - pvOffX) / pvScale;
        double wy = (pvOffY - e.getY()) / pvScale;
        Point clicked = new Point(wx, wy);
        if (!previewPolygon.isPointInside(clicked, false)) {
            lblRobotCoords.setText("⚠  Outside polygon — try again");
            lblRobotCoords.setStyle("-fx-font-size:12px;-fx-text-fill:#c0392b;");
            return;
        }
        config.robotLocation = clicked;
        robotPlaced = true;
        lblRobotCoords.setText(String.format("Robot location: (%.3f, %.3f)  ✓", wx, wy));
        lblRobotCoords.setStyle("-fx-font-size:12px;-fx-text-fill:#1d7a3c;");
        syncRobotFields(wx, wy);
        refreshPreview(clicked);
        checkGenerateReady();
    }

    /** Push the current robot coordinates into the X/Y text fields (e.g. after a mouse click)
     *  without re-triggering the manual-entry handler. */
    private void syncRobotFields(double x, double y) {
        if (tfRobotX == null || tfRobotY == null) return;
        suppressRobotFieldSync = true;
        tfRobotX.setText(String.format("%.3f", x));
        tfRobotY.setText(String.format("%.3f", y));
        suppressRobotFieldSync = false;
    }

    // ── Manual entry: place/fine-tune robot via typed X / Y ────────────────
    private void handleManualRobotEntry() {
        if (suppressRobotFieldSync) return;
        if (previewPolygon == null) {
            lblRobotCoords.setText("⚠  Open a polygon file first");
            lblRobotCoords.setStyle("-fx-font-size:12px;-fx-text-fill:#c0392b;");
            return;
        }
        double x, y;
        try {
            x = Double.parseDouble(tfRobotX.getText().trim());
            y = Double.parseDouble(tfRobotY.getText().trim());
        } catch (NumberFormatException | NullPointerException ex) {
            lblRobotCoords.setText("⚠  Enter valid numeric X and Y values");
            lblRobotCoords.setStyle("-fx-font-size:12px;-fx-text-fill:#c0392b;");
            return;
        }
        Point typed = new Point(x, y);
        if (!previewPolygon.isPointInside(typed, false)) {
            lblRobotCoords.setText("⚠  Outside polygon — try again");
            lblRobotCoords.setStyle("-fx-font-size:12px;-fx-text-fill:#c0392b;");
            return;
        }
        config.robotLocation = typed;
        robotPlaced = true;
        lblRobotCoords.setText(String.format("Robot location: (%.3f, %.3f)  ✓", x, y));
        lblRobotCoords.setStyle("-fx-font-size:12px;-fx-text-fill:#1d7a3c;");
        refreshPreview(typed);
        checkGenerateReady();
    }

    private void checkGenerateReady() {
        btnGenerate.setDisable(previewPolygon == null || !robotPlaced);
    }

    // ── Generate ──────────────────────────────────────────────────────────
    private void handleGenerate() {
        config.robotWidth      = spRobotWidth.getValue();
        config.turnPenalty     = spTurnPenalty.getValue();
        config.overlap         = spOverlap.getValue();
        config.optimalRotation = tbOptRotation.isSelected();
        config.boundarySweep   = tbBoundarySweep.isSelected();

        Object[] ud = (Object[]) btnGenerate.getUserData();
        ProgressIndicator pi   = (ProgressIndicator) ud[0];
        Label             lblR = (Label) ud[1];
        pi.setVisible(true); lblR.setVisible(true);
        btnGenerate.setDisable(true);

        Task<PipelineResult> task = new Task<>() {
            @Override protected PipelineResult call() { return PipelineRunner.run(config); }
        };
        task.setOnSucceeded(ev -> {
            pi.setVisible(false); lblR.setVisible(false);
            result = task.getValue();
            if (!result.success) {
                showError("Pipeline failed:\n" + result.errorMessage);
                btnGenerate.setDisable(false);
                return;
            }
            showSimulatorScene();
        });
        task.setOnFailed(ev -> {
            pi.setVisible(false); lblR.setVisible(false);
            btnGenerate.setDisable(false);
            Throwable ex = task.getException();
            showError("Unexpected error:\n" + (ex != null ? ex.getMessage() : "unknown"));
        });
        new Thread(task,"pipeline-thread").start();
    }

    // =====================================================================
    //  SCENE 2 — Simulator
    // =====================================================================
    private void showSimulatorScene() {
        primaryStage.setTitle("CPP — " + new File(config.polygonFilePath).getName());
        robotDiameter = config.robotWidth;
        heatmapImage  = null;
        heatmapDirty  = true;
        displayMode   = "sim";
        viewportMemory.clear(); // fresh viewports for each new polygon run

        // Initialise robot at waypoint[0] so it is visible before animation starts
        if (result != null && result.waypoints != null && !result.waypoints.isEmpty()) {
            Point p0 = result.waypoints.get(0);
            robotPos = new double[]{p0.x, p0.y};
        } else {
            robotPos = null;
        }
        wpIndex = 0; wpT = 0; animRunning = false; animPaused = false;
        coverageStrokes.clear(); lastStampPos = null;
        timingStarted = false;

        // ── Canvas ────────────────────────────────────────────────────────
        simCanvas = new Canvas();
        simGC     = simCanvas.getGraphicsContext2D();

        StackPane canvasHolder = new StackPane(simCanvas);
        simCanvas.widthProperty().bind(canvasHolder.widthProperty());
        simCanvas.heightProperty().bind(canvasHolder.heightProperty());
        simCanvas.widthProperty() .addListener(o -> { simFitted = false; redrawSim(); });
        simCanvas.heightProperty().addListener(o -> { simFitted = false; redrawSim(); });

        canvasHolder.setOnMousePressed(e -> {
            simDragX = e.getX(); simDragY = e.getY();
            // Check if pressing on a coord label (any view with coords on)
            if (showCoords && !displayMode.equals("heatmap")) {
                dragLabelKey = hitTestLabel(e.getX(), e.getY());
                if (dragLabelKey != null) {
                    double[] existing = labelOffsets.getOrDefault(dragLabelKey, new double[]{0,0});
                    dragLabelOrigX = existing[0]; dragLabelOrigY = existing[1];
                    dragLabelStartX = e.getX(); dragLabelStartY = e.getY();
                }
            }
        });
        canvasHolder.setOnMouseDragged(e -> {
            if (dragLabelKey != null) {
                // Dragging a label
                double dx = e.getX() - dragLabelStartX;
                double dy = e.getY() - dragLabelStartY;
                labelOffsets.put(dragLabelKey, new double[]{dragLabelOrigX+dx, dragLabelOrigY+dy});
                redrawSim();
            } else {
                // Panning the view
                simOffX += e.getX()-simDragX; simOffY += e.getY()-simDragY;
                simDragX = e.getX(); simDragY = e.getY(); simFitted=true;
                viewportMemory.put(displayMode, new double[]{simScale,simOffX,simOffY});
                redrawSim();
            }
        });
        canvasHolder.setOnMouseReleased(e -> dragLabelKey = null);
        canvasHolder.setOnScroll(e -> {
            double z = e.getDeltaY() > 0 ? 1.1 : 0.9;
            simOffX = e.getX() - z*(e.getX()-simOffX);
            simOffY = e.getY() - z*(e.getY()-simOffY);
            simScale *= z; simFitted=true;
            viewportMemory.put(displayMode, new double[]{simScale,simOffX,simOffY});
            redrawSim();
        });

        // ── Right panel ───────────────────────────────────────────────────
        rightPanelBox = buildRightPanel();
        rightScroll = new ScrollPane(rightPanelBox);
        rightScroll.setFitToWidth(true);
        rightScroll.setPrefWidth(290);
        rightScroll.setMinWidth(240);
        rightScroll.setMaxWidth(340);
        applyPanelTheme();

        simSplitPane = new SplitPane(canvasHolder, rightScroll);
        simSplitPane.setDividerPositions(0.78);
        // Keep right panel at fixed width, only canvas resizes
        SplitPane.setResizableWithParent(rightScroll, false);
        // Enforce minimum divider so panel never disappears
        simSplitPane.getDividers().get(0).positionProperty().addListener((obs, ov, nv) -> {
            double minPos = 1.0 - (rightScroll.getPrefWidth() + 20) /
                Math.max(1, simSplitPane.getWidth());
            if (nv.doubleValue() > minPos)
                simSplitPane.setDividerPosition(0, minPos);
        });

        // ── Top bar ───────────────────────────────────────────────────────
        Button btnBack = new Button("← Back");
        style(btnBack, "#374151", "#e2e8f0");
        btnBack.setOnAction(e -> {
            stopAnimation();
            // Clear all result state so the canvas is blank on return
            result = null;
            robotPos = null;
            coverageStrokes.clear();
            heatmapImage = null;
            wpIndex = 0; wpT = 0;
            timingStarted = false;
            displayMode = "sim";
            simFitted = false;
            // Also clear the setup scene robot placement
            config.robotLocation = null;
            robotPlaced = false;
            pvFitted = false;
            showSetupScene();
        });

        Label lblInfo = new Label(String.format("%s  •  %d waypoints  •  rw=%.2f  overlap=%.0f%%",
            new File(config.polygonFilePath).getName(),
            result.waypoints.size(), config.robotWidth, config.overlap*100));
        // Visible in both dark (#cbd5e1) and light (#1e293b) modes
        lblInfo.setStyle("-fx-font-size:12px;-fx-font-weight:bold;-fx-text-fill:" + (darkMode ? "#cbd5e1" : "#1e293b") + ";");

        // Dark/light toggle
        ToggleButton tbTheme = new ToggleButton("☀ Light");
        tbTheme.setSelected(false);
        tbTheme.setStyle("-fx-font-size:11px;-fx-background-color:#374151;"
            +"-fx-text-fill:#e2e8f0;-fx-background-radius:5;-fx-padding:4 10;");
        tbTheme.setOnAction(e -> {
            darkMode = !tbTheme.isSelected();
            tbTheme.setText(darkMode ? "☀ Light" : "🌙 Dark");
            lblInfo.setStyle("-fx-font-size:12px;-fx-font-weight:bold;-fx-text-fill:" + (darkMode ? "#cbd5e1" : "#1e293b") + ";");
            applyCanvasTheme(canvasHolder);
            applyPanelTheme();
            applyTopBarTheme();
            rebuildRightPanel(); // update secLabel colours immediately
            redrawSim();
        });

        ToggleButton tbGrid = new ToggleButton("▦ Graph Paper");
        tbGrid.setSelected(showGraphPaper);
        tbGrid.setStyle("-fx-font-size:11px;-fx-background-color:" +
            (showGraphPaper?"#1d7a3c":"#374151") +
            ";-fx-text-fill:#e2e8f0;-fx-background-radius:5;-fx-padding:4 10;");
        tbGrid.setOnAction(e -> {
            showGraphPaper = tbGrid.isSelected();
            tbGrid.setStyle("-fx-font-size:11px;-fx-background-color:" +
                (showGraphPaper?"#1d7a3c":"#374151") +
                ";-fx-text-fill:#e2e8f0;-fx-background-radius:5;-fx-padding:4 10;");
            redrawSim();
        });

        Button btnZoomIn  = new Button("＋ Zoom In");
        Button btnZoomOut = new Button("－ Zoom Out");
        Button btnZoomFit = new Button("⊞ Fit");
        for (Button b : new Button[]{btnZoomIn, btnZoomOut, btnZoomFit}) {
            b.setStyle("-fx-font-size:11px;-fx-background-color:#374151;"
                + "-fx-text-fill:#e2e8f0;-fx-background-radius:5;-fx-padding:4 10;");
        }
        btnZoomIn.setOnAction(e -> {
            double cx = simCanvas.getWidth()/2, cy = simCanvas.getHeight()/2;
            double z = 1.25;
            simOffX = cx - z*(cx-simOffX); simOffY = cy - z*(cy-simOffY);
            simScale *= z; simFitted = true;
            viewportMemory.put(displayMode, new double[]{simScale,simOffX,simOffY});
            redrawSim();
        });
        btnZoomOut.setOnAction(e -> {
            double cx = simCanvas.getWidth()/2, cy = simCanvas.getHeight()/2;
            double z = 1.0/1.25;
            simOffX = cx - z*(cx-simOffX); simOffY = cy - z*(cy-simOffY);
            simScale *= z; simFitted = true;
            viewportMemory.put(displayMode, new double[]{simScale,simOffX,simOffY});
            redrawSim();
        });
        btnZoomFit.setOnAction(e -> {
            simFitted = false;
            viewportMemory.remove(displayMode);
            redrawSim();
        });
        HBox zoomBox = new HBox(4, btnZoomOut, btnZoomFit, btnZoomIn);
        zoomBox.setAlignment(Pos.CENTER);

        topBarRef = new HBox(10, btnBack, lblInfo, new Region(), zoomBox, new Region(), tbGrid, tbTheme);
        HBox.setHgrow(topBarRef.getChildren().get(2), Priority.ALWAYS);
        HBox.setHgrow(topBarRef.getChildren().get(4), Priority.ALWAYS);
        topBarRef.setAlignment(Pos.CENTER_LEFT);
        topBarRef.setPadding(new Insets(6,12,6,12));

        simRoot = new BorderPane();
        simRoot.setTop(topBarRef);
        simRoot.setCenter(simSplitPane);
        applyTopBarTheme();
        applyCanvasTheme(canvasHolder);

        Scene scene = new Scene(simRoot);
        primaryStage.setScene(scene);

        Screen screen = Screen.getPrimary();
        javafx.geometry.Rectangle2D bounds = screen.getVisualBounds();
        primaryStage.setWidth(bounds.getWidth() * 0.92);
        primaryStage.setHeight(bounds.getHeight() * 0.92);
        primaryStage.centerOnScreen();

        Platform.runLater(() -> { simFitted = false; redrawSim(); });
    }

    private void applyCanvasTheme(Pane canvasHolder) {
        canvasHolder.setStyle("-fx-background-color:" + bg() + ";");
    }
    private void applyPanelTheme() {
        String bg = darkMode ? "#1e293b" : "#f1f5f9";
        rightScroll.setStyle("-fx-background-color:" + bg + ";-fx-border-color:" + bg + ";");
        if (rightPanelBox != null)
            rightPanelBox.setStyle("-fx-background-color:" + bg + ";");
    }
    private void applyTopBarTheme() {
        topBarRef.setStyle("-fx-background-color:" + (darkMode ? "#1e293b" : "#e2e8f0") + ";");
        simRoot.setStyle("-fx-background-color:" + bg() + ";");
    }
    private String bg()   { return darkMode ? "#111827" : "#f8fafc"; }
    private String fg()   { return darkMode ? "#e2e8f0" : "#1e293b"; }
    private String gridC(){ return darkMode ? "#1f2937" : "#e2e8f0"; }

    // ── Right panel — rebuilt whenever view changes ───────────────────────
    private ComboBox<String> cbVizRef; // kept so rebuildRightPanel can sync selection

    private VBox buildRightPanel() {
        VBox panel = new VBox(10);
        panel.setPadding(new Insets(12));
        String bg = darkMode ? "#1e293b" : "#f1f5f9";
        panel.setStyle("-fx-background-color:" + bg + ";");

        // ── View selector (always shown) ──────────────────────────────────
        Label lblViz = secLabel("🔍  View");
        cbVizRef = new ComboBox<>();
        cbVizRef.getItems().addAll(
            "Simulation View",
            "Original Polygon",
            "Rotated Polygon",
            "Shrunk Polygon",
            "After HVS",
            "Decomposed Polygon",
            "Connectivity Graph",
            "Heatmap View"
        );
        cbVizRef.setValue(modeToLabel(displayMode));
        cbVizRef.setMaxWidth(Double.MAX_VALUE);
        cbVizRef.setOnAction(e -> {
            String sel = cbVizRef.getValue();
            // Save current viewport before switching
            viewportMemory.put(displayMode, new double[]{simScale, simOffX, simOffY});
            switch (sel) {
                case "Original Polygon"   -> { displayMode="original";   heatmapDirty=false; showCoords=true;  }
                case "Rotated Polygon"    -> { displayMode="rotated";    heatmapDirty=false; showCoords=false; }
                case "Shrunk Polygon"     -> { displayMode="shrunk";     heatmapDirty=false; showCoords=false; }
                case "After HVS"          -> { displayMode="hvsdcel";    heatmapDirty=false; showCoords=false; }
                case "Decomposed Polygon" -> { displayMode="decomposed"; heatmapDirty=false; showCoords=false; }
                case "Connectivity Graph" -> { displayMode="graph";      heatmapDirty=false; showCoords=false; }
                case "Heatmap View"       -> { displayMode="heatmap";    heatmapDirty=true;  showCoords=false; }
                default                   -> { displayMode="sim";                            showCoords=false; }
            }
            // Restore saved viewport or auto-fit on first visit
            double[] saved = viewportMemory.get(displayMode);
            if (saved != null) { simScale=saved[0]; simOffX=saved[1]; simOffY=saved[2]; simFitted=true; }
            else { simFitted = false; }
            rebuildRightPanel();
            redrawSim();
        });

        panel.getChildren().addAll(lblViz, cbVizRef, new Separator());

        // ── View-specific controls ────────────────────────────────────────
        switch (displayMode) {
            case "sim"     -> buildSimControls(panel);
            case "heatmap" -> buildHeatmapControls(panel);
            default        -> buildPolygonViewControls(panel);
        }

        // ── Always-present footer ─────────────────────────────────────────
        panel.getChildren().add(new Separator());
        Label lblExp = secLabel("💾  Export");
        Button btnExp = ctrlBtn("Export Waypoints", "#7c3aed");
        btnExp.setMaxWidth(Double.MAX_VALUE);
        btnExp.setOnAction(e -> handleExportWaypoints());

        Button btnPng = ctrlBtn("📷  Save PNG…", "#0f766e");
        btnPng.setMaxWidth(Double.MAX_VALUE);
        btnPng.setOnAction(e -> handleSavePng());

        panel.getChildren().addAll(lblExp, btnExp, btnPng);

        return panel;
    }

    private String modeToLabel(String mode) {
        return switch (mode) {
            case "original"   -> "Original Polygon";
            case "rotated"    -> "Rotated Polygon";
            case "shrunk"     -> "Shrunk Polygon";
            case "hvsdcel"    -> "After HVS";
            case "decomposed" -> "Decomposed Polygon";
            case "graph"      -> "Connectivity Graph";
            case "heatmap"    -> "Heatmap View";
            default           -> "Simulation View";
        };
    }

    private void rebuildRightPanel() {
        rightPanelBox = buildRightPanel();
        rightScroll.setContent(rightPanelBox);
        applyPanelTheme();
    }

    // ── Simulation-specific controls ──────────────────────────────────────
    private void buildSimControls(VBox panel) {
        // Playback
        Label lblPlay = secLabel("▶  Playback");
        Button btnStart = ctrlBtn("▶ Start",  "#1d7a3c");
        Button btnPause = ctrlBtn("⏸ Pause",  "#b45309");
        Button btnStep  = ctrlBtn("⏭ Step",   "#1e3a5f");
        Button btnReset = ctrlBtn("↺ Reset",  "#6b21a8");
        btnStart.setOnAction(e -> startAnimation());
        btnPause.setOnAction(e -> togglePause(btnPause));
        btnStep .setOnAction(e -> stepAnimation());
        btnReset.setOnAction(e -> resetAnimation());
        GridPane pg = new GridPane(); pg.setHgap(6); pg.setVgap(6);
        pg.add(btnStart,0,0); pg.add(btnPause,1,0);
        pg.add(btnStep, 0,1); pg.add(btnReset,1,1);
        for (var n : pg.getChildren()) GridPane.setHgrow((Node)n, Priority.ALWAYS);
        for (var b : List.of(btnStart,btnPause,btnStep,btnReset)) b.setMaxWidth(Double.MAX_VALUE);

        Label lblSpd = secLabel("⚡  Animation Speed");
        Slider sldSpd = styledSlider(1,100,30);
        sldSpd.valueProperty().addListener((o,ov,nv) -> {
            animSpeed = 0.002 + (0.05-0.002)*(nv.doubleValue()/100.0);
        });

        Label lblOv = secLabel("🗂  Overlays");
        ToggleButton tbCov = makeToggle("Coverage ON","Coverage OFF",showCoverage);
        tbCov.setMaxWidth(Double.MAX_VALUE);
        tbCov.setOnAction(e -> { showCoverage=tbCov.isSelected();
            tbCov.setText(showCoverage?"Coverage ON":"Coverage OFF");
            tbCov.setStyle(toggleStyle(showCoverage)); redrawSim(); });
        ToggleButton tbRob = makeToggle("Robot ON","Robot OFF",showRobot);
        tbRob.setMaxWidth(Double.MAX_VALUE);
        tbRob.setOnAction(e -> { showRobot=tbRob.isSelected();
            tbRob.setText(showRobot?"Robot ON":"Robot OFF");
            tbRob.setStyle(toggleStyle(showRobot)); redrawSim(); });

        // Coordinates toggle (same field shared with polygon views)
        Label lblCoords = secLabel("📍  Coordinates");
        ToggleButton tbCoordsSim = makeToggle("Coords ON","Coords OFF",showCoords);
        tbCoordsSim.setMaxWidth(Double.MAX_VALUE);
        tbCoordsSim.setOnAction(e -> { showCoords=tbCoordsSim.isSelected();
            tbCoordsSim.setText(showCoords?"Coords ON":"Coords OFF");
            tbCoordsSim.setStyle(toggleStyle(showCoords)); redrawSim(); });
        Label lblCoordSzSim = secLabel("🔤  Coord Label Size");
        Slider sldCoordSzSim = styledSlider(7, 20, coordFontSize);
        sldCoordSzSim.valueProperty().addListener((o,ov,nv) -> {
            coordFontSize = nv.doubleValue(); redrawSim(); });
        Button btnResetLblsSim = ctrlBtn("↺ Reset Labels","#374151");
        btnResetLblsSim.setMaxWidth(Double.MAX_VALUE);
        btnResetLblsSim.setOnAction(e -> { labelOffsets.clear(); redrawSim(); });

        Label lblMet = secLabel("📊  Metrics");
        Button btnMet = ctrlBtn("Compute Metrics","#0f4c8a");
        btnMet.setMaxWidth(Double.MAX_VALUE);
        btnMet.setOnAction(e -> showMetricsDialog());

        panel.getChildren().addAll(
            lblPlay, pg, new Separator(),
            lblSpd, sldSpd, new Separator(),
            lblOv, tbCov, tbRob, new Separator(),
            lblCoords, tbCoordsSim, lblCoordSzSim, sldCoordSzSim, btnResetLblsSim, new Separator(),
            lblMet, btnMet
        );
    }

    // ── Polygon view controls (original/rotated/shrunk/hvsdcel/decomposed/graph) ──
    private void buildPolygonViewControls(VBox panel) {
        Label lblDisp = secLabel("🖥  Display");

        // Coordinates toggle
        ToggleButton tbCoords = makeToggle("Coords ON","Coords OFF",showCoords);
        tbCoords.setMaxWidth(Double.MAX_VALUE);
        tbCoords.setOnAction(e -> { showCoords=tbCoords.isSelected();
            tbCoords.setText(showCoords?"Coords ON":"Coords OFF");
            tbCoords.setStyle(toggleStyle(showCoords)); redrawSim(); });

        // Region names toggle (only relevant for decomposed/hvsdcel/graph)
        ToggleButton tbNames = makeToggle("Region Names ON","Region Names OFF",showRegionNames);
        tbNames.setMaxWidth(Double.MAX_VALUE);
        tbNames.setOnAction(e -> { showRegionNames=tbNames.isSelected();
            tbNames.setText(showRegionNames?"Region Names ON":"Region Names OFF");
            tbNames.setStyle(toggleStyle(showRegionNames)); redrawSim(); });

        // Coordinate font size
        Label lblCoordSz = secLabel("🔤  Coord Label Size");
        Slider sldCoordSz = styledSlider(7, 20, coordFontSize);
        sldCoordSz.valueProperty().addListener((o,ov,nv) -> {
            coordFontSize = nv.doubleValue(); redrawSim(); });

        // Region name font size
        Label lblRegSz = secLabel("🔤  Region Name Size");
        Slider sldRegSz = styledSlider(7, 24, regionFontSize);
        sldRegSz.valueProperty().addListener((o,ov,nv) -> {
            regionFontSize = nv.doubleValue(); redrawSim(); });

        // Vertex dot size
        Label lblDotSz = secLabel("⬤  Vertex Dot Size");
        Slider sldDotSz = styledSlider(2, 14, vertexDotSize);
        sldDotSz.valueProperty().addListener((o,ov,nv) -> {
            vertexDotSize = nv.doubleValue(); redrawSim(); });

        // Fit to view / reset labels buttons
        Button btnFit = ctrlBtn("⊞ Fit to View","#1e3a5f");
        btnFit.setMaxWidth(Double.MAX_VALUE);
        btnFit.setOnAction(e -> { simFitted=false; redrawSim(); });

        Button btnResetLabels = ctrlBtn("↺ Reset Labels","#374151");
        btnResetLabels.setMaxWidth(Double.MAX_VALUE);
        btnResetLabels.setOnAction(e -> { labelOffsets.clear(); redrawSim(); });

        Label lblDragHint = new Label("💡 Drag any coord label to reposition it");
        lblDragHint.setStyle("-fx-font-size:10px;-fx-text-fill:#888;-fx-wrap-text:true;");
        lblDragHint.setMaxWidth(Double.MAX_VALUE);

        panel.getChildren().addAll(
            lblDisp, tbCoords, tbNames, new Separator(),
            lblCoordSz, sldCoordSz,
            lblRegSz, sldRegSz,
            lblDotSz, sldDotSz,
            new Separator(), btnFit, btnResetLabels, lblDragHint
        );
    }

    // ── Heatmap-specific controls ─────────────────────────────────────────
    private void buildHeatmapControls(VBox panel) {
        Label lblOv = secLabel("🗂  Overlays");
        ToggleButton tbPath = makeToggle("Path ON","Path OFF",heatmapShowPath);
        tbPath.setMaxWidth(Double.MAX_VALUE);
        tbPath.setOnAction(e -> { heatmapShowPath=tbPath.isSelected();
            tbPath.setText(heatmapShowPath?"Path ON":"Path OFF");
            tbPath.setStyle(toggleStyle(heatmapShowPath)); redrawSim(); });

        Button btnRebuild = ctrlBtn("↺ Rebuild Heatmap","#0f4c8a");
        btnRebuild.setMaxWidth(Double.MAX_VALUE);
        btnRebuild.setOnAction(e -> { heatmapDirty=true; redrawSim(); });

        Label lblMet = secLabel("📊  Metrics");
        Button btnMet = ctrlBtn("Compute Metrics","#0f4c8a");
        btnMet.setMaxWidth(Double.MAX_VALUE);
        btnMet.setOnAction(e -> showMetricsDialog());

        panel.getChildren().addAll(lblOv, tbPath, btnRebuild, new Separator(), lblMet, btnMet);
    }

    private Label secLabel(String t) {
        Label l = new Label(t);
        l.setStyle("-fx-font-size:13px;-fx-font-weight:bold;-fx-text-fill:"+fg()+";");
        return l;
    }
    private Button ctrlBtn(String t, String col) {
        Button b = new Button(t);
        b.setStyle("-fx-font-size:12px;-fx-background-color:"+col+";-fx-text-fill:white;"
            +"-fx-background-radius:5;-fx-padding:6 10;");
        return b;
    }
    private void style(Button b, String bg, String fg) {
        b.setStyle("-fx-font-size:12px;-fx-background-color:"+bg+";-fx-text-fill:"+fg+";"
            +"-fx-background-radius:5;-fx-padding:5 12;");
    }
    private Slider styledSlider(double mn, double mx, double v) {
        Slider s = new Slider(mn,mx,v);
        s.setShowTickLabels(true); s.setShowTickMarks(true);
        s.setMajorTickUnit((mx-mn)/4.0);
        return s;
    }
    private ToggleButton makeToggle(String on, String off, boolean init) {
        ToggleButton tb = new ToggleButton(init?on:off);
        tb.setSelected(init);
        tb.setStyle(toggleStyle(init));
        tb.setOnAction(e -> {
            boolean sel = tb.isSelected();
            tb.setText(sel?on:off); tb.setStyle(toggleStyle(sel));
        });
        tb.setPrefWidth(70);
        return tb;
    }
    private String toggleStyle(boolean on) {
        return on ? "-fx-background-color:#1d7a3c;-fx-text-fill:white;-fx-font-weight:bold;-fx-background-radius:5;"
                  : "-fx-background-color:#ccc;-fx-text-fill:#333;-fx-font-weight:bold;-fx-background-radius:5;";
    }

    // =====================================================================
    //  ANIMATION
    // =====================================================================
    private void startAnimation() {
        if (result==null||result.waypoints==null||result.waypoints.isEmpty()) return;
        if (wpIndex>=result.waypoints.size()-1) resetAnimation();
        if (!timingStarted) { startMs=System.currentTimeMillis(); pausedMs=0; timingStarted=true; }
        else if (animPaused) { pausedMs+=System.currentTimeMillis()-pauseStartMs; animPaused=false; }
        animRunning = true;
        if (animTimer!=null) animTimer.stop();
        animTimer = new AnimationTimer() {
            @Override public void handle(long now) { if (animRunning) tickAnimation(); }
        };
        animTimer.start();
    }

    private void tickAnimation() {
        List<Point> wp = result.waypoints;
        if (wpIndex>=wp.size()-1) {
            animRunning=false; animTimer.stop();
            endMs=System.currentTimeMillis(); heatmapDirty=true;
            redrawSim(); return;
        }
        wpT+=animSpeed;
        if (wpT>=1.0) { wpIndex++; wpT=0; }
        int i=wpIndex, j=Math.min(i+1,wp.size()-1);
        Point a=wp.get(i), b=wp.get(j);
        robotPos=new double[]{a.x+(b.x-a.x)*wpT, a.y+(b.y-a.y)*wpT};
        recordCoverage(robotPos[0],robotPos[1]);
        redrawSim();
    }

    private void togglePause(Button btnPause) {
        if (!animRunning&&!animPaused) return;
        if (!animPaused) {
            animPaused=true; animRunning=false;
            pauseStartMs=System.currentTimeMillis();
            if (animTimer!=null) animTimer.stop();
            btnPause.setText("▶ Resume");
        } else {
            btnPause.setText("⏸ Pause");
            startAnimation();
        }
    }

    private void stepAnimation() {
        if (result==null||result.waypoints==null) return;
        List<Point> wp=result.waypoints;
        if (wpIndex<wp.size()-1) {
            wpIndex++; Point p=wp.get(wpIndex);
            robotPos=new double[]{p.x,p.y};
            recordCoverage(p.x,p.y); heatmapDirty=true; redrawSim();
        }
    }

    private void resetAnimation() {
        stopAnimation();
        wpIndex=0; wpT=0; animRunning=false; animPaused=false;
        if (result!=null&&result.waypoints!=null&&!result.waypoints.isEmpty()) {
            Point p=result.waypoints.get(0); robotPos=new double[]{p.x,p.y};
        } else robotPos=null;
        coverageStrokes.clear(); lastStampPos=null;
        timingStarted=false; startMs=0; endMs=0; pausedMs=0;
        heatmapImage=null; heatmapDirty=true;
        redrawSim();
    }

    private void stopAnimation() {
        animRunning=false;
        if (animTimer!=null) { animTimer.stop(); animTimer=null; }
    }

    // =====================================================================
    //  COVERAGE STAMPING
    // =====================================================================
    private void recordCoverage(double wx, double wy) {
        double r=robotDiameter/2.0, spacing=r;
        if (lastStampPos==null) {
            coverageStrokes.add(new double[]{wx,wy,r});
            lastStampPos=new double[]{wx,wy}; return;
        }
        double dx=wx-lastStampPos[0], dy=wy-lastStampPos[1];
        double dist=Math.hypot(dx,dy);
        if (dist<spacing) return;
        int steps=(int)Math.floor(dist/spacing);
        for (int k=1;k<=steps;k++) {
            double f=k*spacing/dist;
            coverageStrokes.add(new double[]{lastStampPos[0]+dx*f, lastStampPos[1]+dy*f, r});
        }
        double f=steps*spacing/dist;
        lastStampPos=new double[]{lastStampPos[0]+dx*f, lastStampPos[1]+dy*f};
    }

    // =====================================================================
    //  HEATMAP  — faithful Java port of CoverageHeatMap.py
    //  9-stop custom colormap + proper Gaussian blur (3-pass box approximation)
    // =====================================================================

    // Bug 2: toggle for waypoint line visibility in heatmap view
    private boolean heatmapShowPath = false;  // off by default

    // heatmap coordinate metadata (set by buildHeatmapImage)
    private int    hmW, hmH, hmPPU, hmPad;
    private double hmX0, hmY0;

    /**
     * 9-stop colormap matching Python's HEATMAP_CMAP:
     *   0.000 → transparent          (no coverage)
     *   0.001 → (0, 46, 166)         deep blue
     *   0.120 → (0, 115, 230)        blue
     *   0.280 → (0, 199, 184)        cyan-teal
     *   0.430 → (26, 204, 51)        green
     *   0.580 → (166, 224, 0)        yellow-green
     *   0.720 → (255, 191, 0)        amber
     *   0.850 → (255, 77, 0)         orange-red
     *   1.000 → (204, 0, 0)          deep red
     */
    private Color heatColor(float t) {
        if (t <= 0f) return Color.TRANSPARENT;
        // Stop positions and RGBA values
        float[] stops = {0.001f, 0.120f, 0.280f, 0.430f, 0.580f, 0.720f, 0.850f, 1.000f};
        int[][] rgb = {
            {  0,  46, 166}, // deep blue
            {  0, 115, 230}, // blue
            {  0, 199, 184}, // cyan-teal
            { 26, 204,  51}, // green
            {166, 224,   0}, // yellow-green
            {255, 191,   0}, // amber
            {255,  77,   0}, // orange-red
            {204,   0,   0}, // deep red
        };
        // Find bracket
        if (t >= 1f) return Color.rgb(204, 0, 0, 1.0);
        int i = 0;
        while (i < stops.length - 1 && t > stops[i+1]) i++;
        float lo = (i==0) ? 0.001f : stops[i];
        float hi = stops[i+1 < stops.length ? i+1 : stops.length-1];
        float frac = (hi > lo) ? (t - lo) / (hi - lo) : 0f;
        frac = Math.max(0f, Math.min(1f, frac));
        int[] c0 = rgb[i];
        int[] c1 = rgb[Math.min(i+1, rgb.length-1)];
        int r = (int)(c0[0] + (c1[0]-c0[0]) * frac);
        int g = (int)(c0[1] + (c1[1]-c0[1]) * frac);
        int b = (int)(c0[2] + (c1[2]-c0[2]) * frac);
        return Color.rgb(
            Math.max(0,Math.min(255,r)),
            Math.max(0,Math.min(255,g)),
            Math.max(0,Math.min(255,b)), 1.0);
    }

    /**
     * Gaussian blur approximated by 3 sequential box blurs (Berry 2004).
     * sigma controls spread; matches Python scipy.ndimage.gaussian_filter.
     */
    private float[] gaussianBlur(float[] src, int W, int H, float sigma) {
        // Convert sigma to box radius using 3-pass formula: r = round(sqrt((12*s²/3)+1) - 1) / 2
        int r = Math.max(1, (int) Math.round((Math.sqrt(12.0*sigma*sigma/3.0 + 1.0) - 1.0) / 2.0));
        float[] a = src.clone();
        for (int pass = 0; pass < 3; pass++) a = boxBlurPass(a, W, H, r);
        return a;
    }

    private float[] boxBlurPass(float[] src, int W, int H, int r) {
        float[] tmp = new float[W*H], dst = new float[W*H];
        for (int y=0;y<H;y++) {
            float s=0; int cnt=0;
            for (int k=0;k<=r&&k<W;k++){s+=src[y*W+k];cnt++;}
            for (int x=0;x<W;x++) {
                tmp[y*W+x]=s/cnt;
                int xr=x+r+1, xl=x-r;
                if (xr<W){s+=src[y*W+xr];cnt++;}
                if (xl>=0){s-=src[y*W+xl];cnt--;}
            }
        }
        for (int x=0;x<W;x++) {
            float s=0; int cnt=0;
            for (int k=0;k<=r&&k<H;k++){s+=tmp[k*W+x];cnt++;}
            for (int y=0;y<H;y++) {
                dst[y*W+x]=s/cnt;
                int yr=y+r+1, yl=y-r;
                if (yr<H){s+=tmp[yr*W+x];cnt++;}
                if (yl>=0){s-=tmp[yl*W+x];cnt--;}
            }
        }
        return dst;
    }

    private WritableImage buildHeatmapImage() {
        if (result==null||result.waypoints==null||result.waypoints.size()<2) return null;
        Polygon poly = result.originalPolygon;
        List<Point> wp = result.waypoints;
        double radius  = config.robotWidth / 2.0;
        double overlap = config.overlap;
        double spacing = Math.max(radius * (1.0 - overlap), 1e-6);

        double xMin=poly.xmin, xMax=poly.xmax, yMin=poly.ymin, yMax=poly.ymax;
        final int PPU = 20; // pixels per world unit — matches Python default
        final int pad = 2;
        int W = Math.max(1, (int)Math.ceil((xMax-xMin)*PPU) + 2*pad);
        int H = Math.max(1, (int)Math.ceil((yMax-yMin)*PPU) + 2*pad);

        // ── Build stamp list (distance-gated, identical to Python) ────────
        List<double[]> stamps = new ArrayList<>();
        stamps.add(new double[]{wp.get(0).x, wp.get(0).y});
        double acc = 0.0;
        Point prev = wp.get(0);
        for (int idx=1; idx<wp.size(); idx++) {
            Point cur = wp.get(idx);
            double dx = cur.x-prev.x, dy = cur.y-prev.y;
            double seg = Math.hypot(dx, dy);
            if (seg < 1e-9) { prev=cur; continue; }
            double da = spacing - acc;
            while (da <= seg) {
                double ff = da/seg;
                stamps.add(new double[]{prev.x+dx*ff, prev.y+dy*ff});
                da += spacing;
            }
            acc = seg - (da - spacing);
            prev = cur;
        }
        stamps.add(new double[]{wp.get(wp.size()-1).x, wp.get(wp.size()-1).y});

        // ── Rasterise circular discs ──────────────────────────────────────
        float[] depth = new float[W*H];
        double pr = radius*PPU, pr2 = pr*pr;
        for (double[] s : stamps) {
            double pcx = (s[0]-xMin)*PPU + pad;
            double pcy = H - (s[1]-yMin)*PPU - pad;
            int x0=Math.max(0,(int)Math.floor(pcx-pr));
            int x1=Math.min(W-1,(int)Math.ceil(pcx+pr));
            int y0=Math.max(0,(int)Math.floor(pcy-pr));
            int y1=Math.min(H-1,(int)Math.ceil(pcy+pr));
            for (int py=y0;py<=y1;py++)
                for (int px=x0;px<=x1;px++) {
                    double ddx=(px+0.5)-pcx, ddy=(py+0.5)-pcy;
                    if (ddx*ddx+ddy*ddy<=pr2) depth[py*W+px]+=1f;
                }
        }

        // ── Polygon mask ──────────────────────────────────────────────────
        boolean[] mask = new boolean[W*H];
        for (int py=0;py<H;py++)
            for (int px=0;px<W;px++) {
                double wx2 = (px+0.5)/PPU + xMin - (double)pad/PPU;
                double wy2 = (H-py-0.5)/PPU + yMin - (double)pad/PPU;
                mask[py*W+px] = pointInPoly(wx2, wy2, poly);
            }

        // ── Find vmax before blur ─────────────────────────────────────────
        float vmax = 1f;
        for (int i=0;i<W*H;i++) if (mask[i] && depth[i]>vmax) vmax=depth[i];

        // ── Gaussian blur — sigma matches Python: robotWidth*PPU/6 ────────
        float sigma = Math.max(0.5f, (float)(config.robotWidth * PPU / 6.0));
        float[] smooth = gaussianBlur(depth, W, H, sigma);
        // Zero-out where original depth was zero (mask transparent stays transparent)
        for (int i=0;i<W*H;i++) if (depth[i]==0f) smooth[i]=0f;

        // ── Build RGBA image ──────────────────────────────────────────────
        WritableImage img = new WritableImage(W, H);
        PixelWriter pw = img.getPixelWriter();
        for (int py=0;py<H;py++)
            for (int px=0;px<W;px++) {
                int idx = py*W+px;
                if (!mask[idx] || smooth[idx] <= 0f) {
                    pw.setColor(px, py, Color.TRANSPARENT);
                } else {
                    float t = Math.min(1f, smooth[idx] / vmax);
                    pw.setColor(px, py, heatColor(t));
                }
            }

        hmW=W; hmH=H; hmPPU=PPU; hmPad=pad; hmX0=xMin; hmY0=yMin;
        return img;
    }

    // =====================================================================
    //  RENDERING — main dispatcher
    // =====================================================================
    private void redrawSim() {
        if (simCanvas==null) return;
        double cw=simCanvas.getWidth(), ch=simCanvas.getHeight();
        if (cw<=0||ch<=0) return;

        GraphicsContext gc=simGC;
        gc.clearRect(0,0,cw,ch);
        gc.setFill(Color.web(bg()));
        gc.fillRect(0,0,cw,ch);

        if (result==null) return;

        dispatchDraw(gc, cw, ch);
    }

    /** Shared draw dispatcher — used both for the live on-screen canvas and
     *  for off-screen high-resolution PNG export, so exported figures are
     *  pixel-for-pixel the same drawing, just rendered at a larger scale. */
    private void dispatchDraw(GraphicsContext gc, double cw, double ch) {
        switch (displayMode) {
            case "sim"        -> drawSimView(gc,cw,ch);
            case "original"   -> drawSinglePolygonView(gc,cw,ch,result.originalPolygon,"Original Polygon");
            case "shrunk"     -> drawShrunkView(gc,cw,ch);
            case "rotated"    -> drawRotatedView(gc,cw,ch);
            case "hvsdcel"    -> drawHvsDcelView(gc,cw,ch);
            case "decomposed" -> drawDecomposedView(gc,cw,ch);
            case "graph"      -> drawGraphView(gc,cw,ch);
            case "heatmap"    -> drawHeatmapView(gc,cw,ch);
        }
    }

    // ── Fit helpers ───────────────────────────────────────────────────────
    private void autoFit(Polygon poly, double cw, double ch) {
        if (poly==null) return;
        double w=poly.xmax-poly.xmin, h=poly.ymax-poly.ymin;
        if (w==0||h==0) return;
        simScale  = Math.min(cw/(w+60), ch/(h+60))*0.9;
        simOffX   = cw/2.0-(poly.xmin+w/2.0)*simScale;
        simOffY   = ch/2.0+(poly.ymin+h/2.0)*simScale;
        simFitted = true;
    }

    private double[] s2c(double wx, double wy) {
        return new double[]{wx*simScale+simOffX, simOffY-wy*simScale};
    }

    // ── Simulation view ───────────────────────────────────────────────────
    private void drawSimView(GraphicsContext gc, double cw, double ch) {
        if (!simFitted) autoFit(result.originalPolygon,cw,ch);
        drawGrid(gc,cw,ch);
        drawPolygonFx(gc, result.originalPolygon,
            Color.web(darkMode?"#1e3a5f":"#dbeafe",0.4),
            Color.web(darkMode?"#3b82f6":"#1e40af"), 2.0);
        drawWaypointPath(gc);
        if (showCoverage) drawCoverageHeatmapLive(gc);
        if (showRobot&&robotPos!=null) drawRobotFx(gc);
        drawStartMarker(gc);
        // Show vertex coords on simulation canvas if enabled
        if (showCoords) drawVertexDots(gc, result.originalPolygon,
            Color.web(darkMode?"#3b82f6":"#1e40af",0.7));
        drawModeLabel(gc,"Simulation View",cw,ch);
    }

    // ── Single polygon view ───────────────────────────────────────────────
    private void drawSinglePolygonView(GraphicsContext gc, double cw, double ch,
                                        Polygon poly, String label) {
        if (poly==null) { drawNALabel(gc,cw,ch,"Not available"); return; }
        if (!simFitted) autoFit(poly,cw,ch);
        drawGrid(gc,cw,ch);
        Color fill  = Color.web(darkMode?"#1e3a5f":"#dbeafe",0.4);
        Color stroke= Color.web(darkMode?"#3b82f6":"#1e40af");
        drawPolygonFx(gc,poly,fill,stroke,2.0);
        drawVertexDots(gc,poly,stroke);
        drawModeLabel(gc,label,cw,ch);
    }

    // ── Shrunk view: show rotated original vs rotated+shrunk ──────────────
    private void drawShrunkView(GraphicsContext gc, double cw, double ch) {
        // BUG FIX #1: show rotated (or original if no rotation) vs shrunk
        Polygon base = (result.rotatedPolygon != null)
            ? result.rotatedPolygon : result.originalPolygon;
        Polygon shrunk = result.shrunkPolygon;
        if (!simFitted) autoFit(base,cw,ch);
        drawGrid(gc,cw,ch);
        // Outer (rotated/original) in light blue
        drawPolygonFx(gc,base,
            Color.web(darkMode?"#1e3a5f":"#dbeafe",0.25),
            Color.web(darkMode?"#3b82f6":"#1e40af"), 2.0);
        // Shrunk in orange
        drawPolygonFx(gc,shrunk,
            Color.web("#f97316",0.2),
            Color.web("#f97316"), 2.0);
        drawVertexDots(gc,shrunk,Color.web("#f97316"));
        drawModeLabel(gc,"Shrunk Polygon (orange = shrunk, blue = base)",cw,ch);
    }

    // ── Rotated view ──────────────────────────────────────────────────────
    private void drawRotatedView(GraphicsContext gc, double cw, double ch) {
        if (result.rotatedPolygon==null) {
            drawNALabel(gc,cw,ch,"No rotation was applied\n(optimal angle ≤ 0°)");
            return;
        }
        if (!simFitted) autoFit(result.rotatedPolygon,cw,ch);
        drawGrid(gc,cw,ch);
        drawPolygonFx(gc,result.rotatedPolygon,
            Color.web(darkMode?"#14532d":"#dcfce7",0.4),
            Color.web(darkMode?"#22c55e":"#15803d"),2.0);
        drawVertexDots(gc,result.rotatedPolygon,Color.web(darkMode?"#22c55e":"#15803d"));
        drawModeLabel(gc,String.format("Rotated Polygon (%.2f° CW)",result.rotationAngle),cw,ch);
    }

    // ── HVS+DCEL view ─────────────────────────────────────────────────────
    private static final Color[] REGION_COLORS = {
        Color.web("#ef4444",0.35), Color.web("#3b82f6",0.35), Color.web("#22c55e",0.35),
        Color.web("#f59e0b",0.35), Color.web("#a855f7",0.35), Color.web("#06b6d4",0.35),
        Color.web("#ec4899",0.35), Color.web("#84cc16",0.35), Color.web("#f97316",0.35)
    };
    private void drawHvsDcelView(GraphicsContext gc, double cw, double ch) {
        // hvsDcelPolygon = shrunk polygon AFTER HVS (has newEdges) but BEFORE DCEL split.
        // This shows the HVS chord lines drawn across the shrunk polygon.
        Polygon hvsPoly = result.hvsDcelPolygon != null
            ? result.hvsDcelPolygon : result.shrunkPolygon;
        if (!simFitted) autoFit(hvsPoly, cw, ch);
        drawGrid(gc, cw, ch);

        // Draw the polygon with a light fill
        drawPolygonFx(gc, hvsPoly,
            Color.web(darkMode?"#1e3a5f":"#dbeafe", 0.35),
            Color.web(darkMode?"#3b82f6":"#1e40af"), 2.0);

        // Draw vertex dots + coords on the outer boundary
        drawVertexDots(gc, hvsPoly, Color.web(darkMode?"#3b82f6":"#1e40af"));

        // Draw HVS chord edges in yellow/amber
        if (hvsPoly.newEdges != null && !hvsPoly.newEdges.isEmpty()) {
            gc.setStroke(darkMode ? Color.web("#facc15") : Color.web("#b45309"));
            gc.setLineWidth(2.5);
            for (Edge e : hvsPoly.newEdges) {
                double[] a = s2c(e.p.x, e.p.y), b = s2c(e.q.x, e.q.y);
                gc.strokeLine(a[0], a[1], b[0], b[1]);
                // Small dot at each chord endpoint
                gc.setFill(darkMode ? Color.web("#fbbf24") : Color.web("#92400e"));
                gc.fillOval(a[0]-4, a[1]-4, 8, 8);
                gc.fillOval(b[0]-4, b[1]-4, 8, 8);
            }
            // Legend
            gc.setFill(darkMode ? Color.web("#facc15") : Color.web("#b45309"));
            gc.setFont(Font.font("System", 11));
            gc.setTextAlign(TextAlignment.LEFT);
            gc.fillText("— HVS chord edges (" + hvsPoly.newEdges.size() + ")", 10, 24);
        } else {
            drawNALabel(gc, cw, ch, "No HVS edges found - polygon may not require chord insertion");
        }
        drawModeLabel(gc, "After HVS  (yellow = chord edges added by Horizontal Visibility Sweep)", cw, ch);
    }

    // ── Decomposed polygon view ───────────────────────────────────────────
    private void drawDecomposedView(GraphicsContext gc, double cw, double ch) {
        if (result.decomposedRegions==null||result.decomposedRegions.isEmpty()) {
            drawNALabel(gc,cw,ch,"No decomposed regions available"); return;
        }
        Polygon outer = result.shrunkPolygon;
        if (!simFitted) autoFit(outer,cw,ch);
        drawGrid(gc,cw,ch);
        drawPolygonFx(gc,outer,Color.TRANSPARENT,Color.web(darkMode?"#475569":"#94a3b8"),1.0);
        int ci=0;
        for (Polygon r: result.decomposedRegions) {
            Color fill = REGION_COLORS[ci%REGION_COLORS.length];
            Color stroke = fill.deriveColor(0,1,0.65,2.0);
            drawPolygonFx(gc,r,fill,stroke,1.8);
            // Region name label at centroid
            double cx2=r.polygonPoints.stream().mapToDouble(p->p.x).average().orElse(0);
            double cy2=r.polygonPoints.stream().mapToDouble(p->p.y).average().orElse(0);
            double[] sc2=s2c(cx2,cy2);
            if (showRegionNames) {
                gc.setFill(darkMode?Color.WHITE:Color.web("#1e293b"));
                gc.setFont(Font.font("System",FontWeight.BOLD,regionFontSize));
                gc.setTextAlign(TextAlignment.CENTER);
                gc.fillText("R"+(ci+1), sc2[0], sc2[1]+4);
            }
            // Vertex coordinate labels for this region
            if (showCoords) drawVertexDots(gc, r, stroke.deriveColor(0,1,1,3.0));
            ci++;
        }
        drawModeLabel(gc,"Decomposed Polygon  ("+result.decomposedRegions.size()+" regions)",cw,ch);
    }

    // ── Graph view ────────────────────────────────────────────────────────
    private void drawGraphView(GraphicsContext gc, double cw, double ch) {
        if (result.polygonGraph==null) { drawNALabel(gc,cw,ch,"Graph unavailable"); return; }
        // Compute centroid of each region as node position
        Map<PolygonVertex,double[]> pos=new LinkedHashMap<>();
        for (PolygonVertex v: result.polygonGraph.G.getVertices()) {
            double cx2=v.polygon.polygonPoints.stream().mapToDouble(p->p.x).average().orElse(0);
            double cy2=v.polygon.polygonPoints.stream().mapToDouble(p->p.y).average().orElse(0);
            pos.put(v,new double[]{cx2,cy2});
        }
        // Fit to polygon bounds for consistent coords
        if (!simFitted) autoFit(result.shrunkPolygon,cw,ch);
        drawGrid(gc,cw,ch);
        // Draw polygon backdrop lightly
        drawPolygonFx(gc,result.shrunkPolygon,Color.TRANSPARENT,
            Color.web(darkMode?"#334155":"#cbd5e1"),0.8);

        // Edges
        gc.setStroke(Color.web(darkMode?"#64748b":"#94a3b8"));
        gc.setLineWidth(1.5);
        for (var e: result.polygonGraph.G.getEdges()) {
            double[] a=s2c(pos.get(e.u)[0],pos.get(e.u)[1]);
            double[] b=s2c(pos.get(e.v)[0],pos.get(e.v)[1]);
            gc.strokeLine(a[0],a[1],b[0],b[1]);
        }
        // Nodes
        int ci=0;
        for (PolygonVertex v: result.polygonGraph.G.getVertices()) {
            double[] sc2=s2c(pos.get(v)[0],pos.get(v)[1]);
            Color nc=REGION_COLORS[ci%REGION_COLORS.length]; ci++;
            gc.setFill(nc.deriveColor(0,1,1,2.0));
            gc.fillOval(sc2[0]-14,sc2[1]-14,28,28);
            gc.setStroke(nc.deriveColor(0,1,0.7,2));
            gc.setLineWidth(2);
            gc.strokeOval(sc2[0]-14,sc2[1]-14,28,28);
            if (showRegionNames) {
                gc.setFill(darkMode?Color.WHITE:Color.web("#1e293b"));
                gc.setFont(Font.font("System",FontWeight.BOLD,regionFontSize));
                gc.setTextAlign(TextAlignment.CENTER);
                gc.fillText(v.polygon.name.replaceAll(".*_","R"),sc2[0],sc2[1]+3);
            }
        }
        // Visit order annotation
        /*
        if (!result.visitOrder.isEmpty()) {
            gc.setStroke(Color.web("#f59e0b"));
            gc.setLineWidth(2);
            for (int i=0;i<result.visitOrder.size()-1;i++) {
                PolygonVertex va=result.visitOrder.get(i), vb=result.visitOrder.get(i+1);
                if (pos.containsKey(va)&&pos.containsKey(vb)) {
                    double[] a=s2c(pos.get(va)[0],pos.get(va)[1]);
                    double[] b=s2c(pos.get(vb)[0],pos.get(vb)[1]);
                    drawArrowFx(gc,a,b,Color.web("#f59e0b"));
                }
            }
        }
        drawModeLabel(gc,"Connectivity Graph (yellow = visit order)",cw,ch);
        */
    }

    // ── Heatmap view ──────────────────────────────────────────────────────
    private void drawHeatmapView(GraphicsContext gc, double cw, double ch) {
        if (!simFitted) autoFit(result.originalPolygon, cw, ch);
        drawGrid(gc, cw, ch);

        // Polygon outline with a subtle fill
        drawPolygonFx(gc, result.originalPolygon,
            Color.web(darkMode?"#1e3a5f":"#dbeafe", 0.15),
            Color.web(darkMode?"#0077aa":"#004466"), 2.2);

        if (heatmapDirty || heatmapImage == null) {
            heatmapImage = buildHeatmapImage();
            heatmapDirty = false;
        }

        if (heatmapImage != null) {
            double worldX0 = hmX0 - (double)hmPad/hmPPU;
            double worldY0 = hmY0 - (double)hmPad/hmPPU;
            double worldX1 = worldX0 + (double)hmW/hmPPU;
            double worldY1 = worldY0 + (double)hmH/hmPPU;
            double[] tl = s2c(worldX0, worldY1);
            double[] br = s2c(worldX1, worldY0);
            gc.drawImage(heatmapImage, tl[0], tl[1], br[0]-tl[0], br[1]-tl[1]);

            // ── Colour scale bar (right edge) ──────────────────────────
            double barX = cw - 44, barY = ch*0.08, barH = ch*0.78, barW = 18;
            // gradient from bottom (deep blue) to top (deep red)
            for (int yi = 0; yi < (int)barH; yi++) {
                float t = 1f - (float)yi / (float)barH;
                Color c = heatColor(t);
                gc.setFill(c);
                gc.fillRect(barX, barY+yi, barW, 1.5);
            }
            gc.setStroke(Color.web(darkMode?"#64748b":"#94a3b8"));
            gc.setLineWidth(1); gc.strokeRect(barX, barY, barW, barH);
            // Labels
            gc.setFill(Color.web(fg()));
            gc.setFont(Font.font("System",10));
            gc.setTextAlign(TextAlignment.LEFT);
            gc.fillText("High", barX+barW+3, barY+8);
            gc.fillText("Low",  barX+barW+3, barY+barH);
            gc.fillText("Cov.", barX+barW+3, barY+barH/2+4);
        } else {
            drawNALabel(gc, cw, ch, "Run simulation first to generate heatmap");
        }

        // Waypoint path (toggled by heatmapShowPath) — path line only
        if (heatmapShowPath) drawWaypointPath(gc);

        // Start / end markers — ALWAYS shown regardless of path toggle
        if (result.waypoints != null && !result.waypoints.isEmpty()) {
            Point sp = result.waypoints.get(0);
            Point ep = result.waypoints.get(result.waypoints.size()-1);
            double[] sc1 = s2c(sp.x, sp.y);
            double[] sc2 = s2c(ep.x, ep.y);
            double r = 9;
            // Start: green circle with "S"
            gc.setFill(Color.web("#16a34a")); gc.setStroke(Color.web("#052e16"));
            gc.setLineWidth(2);
            gc.fillOval(sc1[0]-r, sc1[1]-r, r*2, r*2);
            gc.strokeOval(sc1[0]-r, sc1[1]-r, r*2, r*2);
            gc.setFill(Color.WHITE);
            gc.setFont(Font.font("System", FontWeight.BOLD, 9));
            gc.setTextAlign(TextAlignment.CENTER);
            gc.fillText("S", sc1[0], sc1[1]+3);
            // End: red square with "E"
            gc.setFill(Color.web("#dc2626")); gc.setStroke(Color.web("#450a0a"));
            gc.setLineWidth(2);
            gc.fillRect(sc2[0]-r, sc2[1]-r, r*2, r*2);
            gc.strokeRect(sc2[0]-r, sc2[1]-r, r*2, r*2);
            gc.setFill(Color.WHITE);
            gc.fillText("E", sc2[0], sc2[1]+3);
        }

        // Mode label
        String pathStatus = heatmapShowPath ? "" : "  |  path hidden";
        drawModeLabel(gc, "Heatmap View — deep blue = single pass, red = max overlap" + pathStatus, cw, ch);
    }

    // ── Drawing utilities ─────────────────────────────────────────────────
    private void drawGrid(GraphicsContext gc, double cw, double ch) {
        if (!showGraphPaper) return;
        // ── Graph-paper style: minor grid (1 unit) + major grid (5 units) ──
        double minorPx = simScale;      // 1 world unit
        double majorPx = 5 * simScale;  // 5 world units
        if (minorPx < 3) minorPx = 3;
        if (majorPx < 15) majorPx = 15;

        // Minor lines
        gc.setStroke(Color.web(darkMode ? "#1e2d40" : "#dbeafe", 0.7));
        gc.setLineWidth(0.4);
        double sx = simOffX % minorPx, sy = simOffY % minorPx;
        for (double x = sx; x < cw; x += minorPx) gc.strokeLine(x, 0, x, ch);
        for (double y = sy; y < ch; y += minorPx) gc.strokeLine(0, y, cw, y);

        // Major lines
        gc.setStroke(Color.web(darkMode ? "#2d4a6b" : "#93c5fd", 0.85));
        gc.setLineWidth(0.8);
        double mx = simOffX % majorPx, my = simOffY % majorPx;
        for (double x = mx; x < cw; x += majorPx) gc.strokeLine(x, 0, x, ch);
        for (double y = my; y < ch; y += majorPx) gc.strokeLine(0, y, cw, y);
    }

    private void drawPolygonFx(GraphicsContext gc, Polygon poly,
                                Color fill, Color stroke, double lw) {
        if (poly==null||poly.polygonPoints.isEmpty()) return;
        gc.beginPath();
        double[] s0=s2c(poly.polygonPoints.get(0).x,poly.polygonPoints.get(0).y);
        gc.moveTo(s0[0],s0[1]);
        for (Point p:poly.polygonPoints) { double[] s=s2c(p.x,p.y); gc.lineTo(s[0],s[1]); }
        gc.closePath();
        gc.setFill(fill); gc.fill();
        gc.setStroke(stroke); gc.setLineWidth(lw); gc.stroke();
    }

    private void drawVertexDots(GraphicsContext gc, Polygon poly, Color col) {
        double r = vertexDotSize / 2.0;
        for (Point p : poly.polygonPoints) {
            double[] s = s2c(p.x, p.y);
            // Vertex dot
            gc.setFill(col);
            gc.fillOval(s[0]-r, s[1]-r, r*2, r*2);
            if (showCoords) {
                String key = p.x + "," + p.y;
                double autoX = s[0] + r + 10, autoY = s[1] - 8;
                double[] lp = labelPos(p.x, p.y, autoX, autoY);
                boolean dragging = key.equals(dragLabelKey);
                String txt = String.format("(%.2f, %.2f)", p.x, p.y);
                gc.setFont(Font.font("System", coordFontSize));
                double tw = txt.length() * coordFontSize * 0.56;
                double th = coordFontSize + 6;
                double pillX = lp[0] - 2;
                double pillY = lp[1] - th + 3;
                double pillW = tw + 4;
                double pillH = th;
                // ── Leader line from vertex dot centre to nearest pill edge ──
                // Anchor = centre of pill
                double pillCX = pillX + pillW / 2.0;
                double pillCY = pillY + pillH / 2.0;
                // Direction vector
                double ldx = pillCX - s[0], ldy = pillCY - s[1];
                double ldist = Math.hypot(ldx, ldy);
                // Start of line: edge of dot
                double lx0 = s[0] + (ldist > 0 ? r * ldx/ldist : 0);
                double ly0 = s[1] + (ldist > 0 ? r * ldy/ldist : 0);
                // End of line: nearest point on pill border (approximate as centre)
                double lx1 = pillCX - (ldist > 0 ? (pillW/2.0+2)*ldx/ldist : 0);
                double ly1 = pillCY - (ldist > 0 ? (pillH/2.0+2)*ldy/ldist : 0);
                gc.setStroke(dragging
                    ? Color.web("#f59e0b", 0.9)
                    : Color.web(fg(), 0.45));
                gc.setLineWidth(0.8);
                gc.strokeLine(lx0, ly0, lx1, ly1);
                // Small tick at vertex end
                gc.setFill(dragging ? Color.web("#f59e0b") : col);
                gc.fillOval(lx0-2, ly0-2, 4, 4);
                // ── Pill background ───────────────────────────────────────
                gc.setFill(dragging
                    ? Color.web("#f59e0b", 0.92)
                    : Color.web(darkMode ? "#0f172a" : "#ffffff", 0.88));
                gc.fillRoundRect(pillX, pillY, pillW, pillH, 5, 5);
                gc.setStroke(dragging
                    ? Color.web("#b45309")
                    : Color.web(fg(), 0.3));
                gc.setLineWidth(0.6);
                gc.strokeRoundRect(pillX, pillY, pillW, pillH, 5, 5);
                // ── Text ──────────────────────────────────────────────────
                gc.setFill(Color.web(dragging ? "#1e293b" : fg(), 0.97));
                gc.setFont(Font.font("System", coordFontSize));
                gc.setTextAlign(TextAlignment.LEFT);
                gc.fillText(txt, lp[0], lp[1]);
            }
        }
    }

    private void drawWaypointPath(GraphicsContext gc) {
        if (result==null||result.waypoints==null) return;
        List<Point> wp=result.waypoints;
        gc.setStroke(Color.web("#22c55e",0.8)); gc.setLineWidth(2.5);
        for (int i=0;i<wp.size()-1;i++) {
            double[] a=s2c(wp.get(i).x,wp.get(i).y);
            double[] b=s2c(wp.get(i+1).x,wp.get(i+1).y);
            gc.strokeLine(a[0],a[1],b[0],b[1]);
            if (i%5==0) drawArrowFx(gc,a,b,Color.web("#22c55e",0.8));
        }
        for (int i=0;i<wp.size();i++) {
            double[] s=s2c(wp.get(i).x,wp.get(i).y);
            gc.setFill(i==wpIndex?Color.web("#fbbf24"):Color.web("#16a34a",0.7));
            gc.fillOval(s[0]-3,s[1]-3,6,6);
        }
    }

    private void drawArrowFx(GraphicsContext gc, double[] a, double[] b, Color col) {
        double ang=Math.atan2(b[1]-a[1],b[0]-a[0]); int sz=12;
        gc.setFill(col);
        gc.beginPath();
        gc.moveTo(b[0],b[1]);
        gc.lineTo(b[0]-sz*Math.cos(ang-Math.PI/6), b[1]-sz*Math.sin(ang-Math.PI/6));
        gc.lineTo(b[0]-sz*Math.cos(ang+Math.PI/6), b[1]-sz*Math.sin(ang+Math.PI/6));
        gc.closePath(); gc.fill();
    }

    private void drawCoverageHeatmapLive(GraphicsContext gc) {
        gc.save();
        for (double[] s:coverageStrokes) {
            double[] sc2=s2c(s[0],s[1]); double sr=s[2]*simScale;
            if (sr<0.5) continue;
            gc.setFill(Color.rgb(239,68,68,0.18));
            gc.fillOval(sc2[0]-sr,sc2[1]-sr,sr*2,sr*2);
        }
        gc.restore();
    }

    private void drawRobotFx(GraphicsContext gc) {
        double[] sc2=s2c(robotPos[0],robotPos[1]);
        double sz=robotDiameter*simScale;
        gc.setFill(Color.rgb(59,130,246,0.25));
        gc.fillOval(sc2[0]-sz/2-2,sc2[1]-sz/2-2,sz+4,sz+4);
        gc.setFill(Color.web("#3b82f6"));
        gc.fillOval(sc2[0]-sz/2,sc2[1]-sz/2,sz,sz);
        gc.setFill(Color.rgb(255,255,255,0.4));
        gc.fillOval(sc2[0]-sz/4,sc2[1]-sz/2+2,sz/3,sz/4);
    }

    private void drawStartMarker(GraphicsContext gc) {
        if (result==null||result.robotStartPoint==null) return;
        double[] s=s2c(result.robotStartPoint.x,result.robotStartPoint.y);
        gc.setFill(Color.web("#f59e0b",0.5)); gc.fillOval(s[0]-10,s[1]-10,20,20);
        gc.setFill(Color.web("#f59e0b"));
        gc.fillPolygon(new double[]{s[0],s[0]-8,s[0]+8},new double[]{s[1]-16,s[1],s[1]},3);
    }

    private void drawModeLabel(GraphicsContext gc, String label, double cw, double ch) {
        gc.setFill(Color.web(fg(),0.6));
        gc.setFont(Font.font("System",11));
        gc.setTextAlign(TextAlignment.LEFT);
        gc.fillText(label, 10, ch-10);
    }

    private void drawNALabel(GraphicsContext gc, double cw, double ch, String msg) {
        gc.setFill(Color.web(fg(),0.5));
        gc.setFont(Font.font("System",16));
        gc.setTextAlign(TextAlignment.CENTER);
        for (String line:msg.split("\n")) {
            gc.fillText(line, cw/2, ch/2);
        }
    }

    // =====================================================================
    //  METRICS DIALOG (removed maxOverlapDepth & meanOverlapDepth per #4)
    // =====================================================================
    private void showMetricsDialog() {
        SimMetrics m=computeSimMetrics();
        Dialog<Void> dlg=new Dialog<>();
        dlg.setTitle("Coverage Metrics");
        dlg.setHeaderText("Coverage Analysis Results");
        dlg.getDialogPane().getButtonTypes().add(ButtonType.OK);

        TableView<String[]> tbl=new TableView<>();
        TableColumn<String[],String> c1=new TableColumn<>("Metric");
        c1.setCellValueFactory(d->new SimpleStringProperty(d.getValue()[0]));
        c1.setPrefWidth(260);
        TableColumn<String[],String> c2=new TableColumn<>("Value");
        c2.setCellValueFactory(d->new SimpleStringProperty(d.getValue()[1]));
        c2.setPrefWidth(150);
        tbl.getColumns().addAll(c1,c2);
        tbl.setEditable(false);

        String[][] rows={
            {"Polygon area",                  String.format("%.4f u²",   m.polygonArea)},
            {"Unique covered area",            String.format("%.4f u²",   m.uniqueCoveredArea)},
            {"Coverage efficiency",            String.format("%.2f %%",   m.coveragePct)},
            {"Raw swept area (with overlap)",  String.format("%.4f u²",   m.rawSweptArea)},
            {"Redundant (overlap) area",       String.format("%.4f u²",   m.redundantArea)},
            {"Redundancy ratio",               String.format("%.2f %%",   m.redundancyPct)},
            {"Missed area",                    String.format("%.4f u²",   m.missedArea)},
            {"Missed area fraction",           String.format("%.2f %%",   m.missedPct)},
            {"Total path length",              String.format("%.4f u",    m.pathLength)},
            {"Coverage per unit path",         String.format("%.4f u²/u", m.coveragePerUnit)},
            {"Number of turns (≥ 30°)",        String.valueOf(m.turnCount)},
            {"Total simulation time",          String.format("%.3f s",    m.totalTimeSeconds)},
            {"Total waypoints",                String.valueOf(result.waypoints.size())},
            {"Decomposed regions",             String.valueOf(result.decomposedRegions.size())},
            {"Rotation angle",                 String.format("%.2f°",     result.rotationAngle)},
        };
        for (String[] r:rows) tbl.getItems().add(r);
        tbl.setPrefHeight(400);
        tbl.setRowFactory(tv->new TableRow<>(){
            @Override protected void updateItem(String[] item,boolean empty){
                super.updateItem(item,empty);
                if (empty||item==null){setStyle("");return;}
                if (item[0].contains("Coverage efficiency")) setStyle("-fx-background-color:#d1fae5;");
                else if (item[0].contains("Missed area fraction")) setStyle("-fx-background-color:#fee2e2;");
                else setStyle("");
            }
        });
        dlg.getDialogPane().setContent(tbl);
        dlg.getDialogPane().setPrefWidth(460);
        dlg.showAndWait();

        MetaData md=new MetaData("logFile_GUI");
        md.println("\n=== Coverage Metrics ===");
        for (String[] r:rows) md.println(r[0]+" : "+r[1]);
    }

    // ── Metrics computation ───────────────────────────────────────────────
    private static final double PPU=10.0;

    private SimMetrics computeSimMetrics() {
        SimMetrics m=new SimMetrics();
        Polygon poly=result.originalPolygon;
        List<Point> wp=result.waypoints;
        List<Point> pts=poly.polygonPoints;
        int n=pts.size(); double area=0;
        for (int i=0;i<n;i++){Point a=pts.get(i),b=pts.get((i+1)%n); area+=a.x*b.y-b.x*a.y;}
        m.polygonArea=Math.abs(area)/2.0;
        m.pathLength=0;
        for (int i=0;i<wp.size()-1;i++) m.pathLength+=Point.distance(wp.get(i),wp.get(i+1));
        m.turnCount=0;
        for (int i=1;i<wp.size()-1;i++){
            Point a=wp.get(i-1),b=wp.get(i),c=wp.get(i+1);
            double dx1=b.x-a.x,dy1=b.y-a.y,dx2=c.x-b.x,dy2=c.y-b.y;
            double l1=Math.hypot(dx1,dy1),l2=Math.hypot(dx2,dy2);
            if (l1<1e-9||l2<1e-9) continue;
            double cos=Math.max(-1,Math.min(1,(dx1*dx2+dy1*dy2)/(l1*l2)));
            if (Math.toDegrees(Math.acos(cos))>=30.0) m.turnCount++;
        }
        if (coverageStrokes.isEmpty()) return m;
        double minX=poly.xmin,maxX=poly.xmax,minY=poly.ymin,maxY=poly.ymax;
        int imgW=(int)Math.ceil((maxX-minX)*PPU)+2, imgH=(int)Math.ceil((maxY-minY)*PPU)+2;
        if (imgW<=0||imgH<=0) return m;
        double pa=1.0/(PPU*PPU);
        short[] dm=new short[imgW*imgH];
        for (double[] s:coverageStrokes){
            double pcx=(s[0]-minX)*PPU,pcy=imgH-(s[1]-minY)*PPU,pr=s[2]*PPU,pr2=pr*pr;
            int x0=Math.max(0,(int)Math.floor(pcx-pr)),x1=Math.min(imgW-1,(int)Math.ceil(pcx+pr));
            int y0=Math.max(0,(int)Math.floor(pcy-pr)),y1=Math.min(imgH-1,(int)Math.ceil(pcy+pr));
            for (int py=y0;py<=y1;py++) for (int px=x0;px<=x1;px++){
                double ddx=(px+0.5)-pcx,ddy=(py+0.5)-pcy;
                if (ddx*ddx+ddy*ddy<=pr2){int idx=py*imgW+px;if(dm[idx]<Short.MAX_VALUE)dm[idx]++;}
            }
        }
        boolean[] mask=new boolean[imgW*imgH];
        for (int py=0;py<imgH;py++) for (int px=0;px<imgW;px++){
            double wx2=(px+0.5)/PPU+minX,wy2=(imgH-py-0.5)/PPU+minY;
            mask[py*imgW+px]=pointInPoly(wx2,wy2,poly);
        }
        long cov=0,raw=0,ov=0;
        for (int idx=0;idx<imgW*imgH;idx++){
            short d=dm[idx]; if (d==0) continue;
            raw+=d;
            if (mask[idx]){cov++;if(d>1)ov++;}
        }
        m.uniqueCoveredArea=cov*pa; m.rawSweptArea=raw*pa; m.redundantArea=ov*pa;
        m.coveragePct   =m.polygonArea>0?100.0*m.uniqueCoveredArea/m.polygonArea:0;
        m.redundancyPct =m.rawSweptArea>0?100.0*m.redundantArea/m.rawSweptArea:0;
        m.missedArea    =Math.max(0,m.polygonArea-m.uniqueCoveredArea);
        m.missedPct     =m.polygonArea>0?100.0*m.missedArea/m.polygonArea:0;
        m.coveragePerUnit=m.pathLength>0?m.uniqueCoveredArea/m.pathLength:0;
        if (timingStarted&&endMs>startMs) m.totalTimeSeconds=(endMs-startMs-pausedMs)/1000.0;
        return m;
    }

    private static boolean pointInPoly(double px,double py,Polygon poly){
        List<Point> pts=poly.polygonPoints; int n=pts.size(); boolean in=false;
        for (int i=0,j=n-1;i<n;j=i++){
            double xi=pts.get(i).x,yi=pts.get(i).y,xj=pts.get(j).x,yj=pts.get(j).y;
            if (((yi>py)!=(yj>py))&&(px<(xj-xi)*(py-yi)/(yj-yi)+xi)) in=!in;
        }
        return in;
    }

    static class SimMetrics {
        double polygonArea,uniqueCoveredArea,coveragePct,rawSweptArea;
        double redundantArea,redundancyPct,missedArea,missedPct;
        double pathLength,coveragePerUnit,totalTimeSeconds;
        int turnCount;
    }

    // =====================================================================
    //  PREVIEW CANVAS (Setup scene) — zoom/drag aware
    // =====================================================================
    private void drawPreviewPlaceholder() {
        GraphicsContext gc=previewCanvas.getGraphicsContext2D();
        double w=previewCanvas.getWidth(),h=previewCanvas.getHeight();
        if (w < 10 || h < 10) return; // not laid out yet
        gc.setFill(Color.web("#f1f5f9")); gc.fillRect(0,0,w,h);
        gc.setStroke(Color.web("#cbd5e1")); gc.setLineWidth(1.5);
        gc.strokeRect(2,2,w-4,h-4);
        gc.setFill(Color.web("#94a3b8")); gc.setFont(Font.font("System",14));
        gc.setTextAlign(TextAlignment.CENTER);
        gc.fillText("Open a polygon file to preview",w/2,h/2);
    }

    private void refreshPreview(Point robotLoc) {
        if (previewPolygon==null) return;
        GraphicsContext gc=previewCanvas.getGraphicsContext2D();
        double cw=previewCanvas.getWidth(),ch=previewCanvas.getHeight();
        // Guard: canvas not laid out yet — skip silently (will be called again once ready)
        if (cw < 10 || ch < 10) return;

        // Auto-fit on first load
        if (!pvFitted) {
            double pw=previewPolygon.xmax-previewPolygon.xmin;
            double ph=previewPolygon.ymax-previewPolygon.ymin;
            if (pw>0&&ph>0){
                pvScale=Math.min((cw-40)/pw,(ch-40)/ph)*0.88;
                pvOffX=cw/2.0-(previewPolygon.xmin+pw/2.0)*pvScale;
                pvOffY=ch/2.0+(previewPolygon.ymin+ph/2.0)*pvScale;
                pvFitted=true;
            }
        }

        gc.clearRect(0,0,cw,ch);
        gc.setFill(Color.web("#f8fafc")); gc.fillRect(0,0,cw,ch);
        // Grid
        gc.setStroke(Color.web("#e2e8f0")); gc.setLineWidth(0.7);
        double gPx=10*pvScale; if (gPx>5){
            double sx=pvOffX%gPx,sy=pvOffY%gPx;
            for (double x=sx;x<cw;x+=gPx) gc.strokeLine(x,0,x,ch);
            for (double y=sy;y<ch;y+=gPx) gc.strokeLine(0,y,cw,y);
        }
        // Polygon
        List<Point> pts=previewPolygon.polygonPoints;
        if (!pts.isEmpty()){
            gc.beginPath();
            Point p0=pts.get(0); gc.moveTo(p0.x*pvScale+pvOffX,pvOffY-p0.y*pvScale);
            for (Point p:pts) gc.lineTo(p.x*pvScale+pvOffX,pvOffY-p.y*pvScale);
            gc.closePath();
            gc.setFill(Color.web("#dbeafe",0.6)); gc.fill();
            gc.setStroke(Color.web("#1e3a5f")); gc.setLineWidth(2); gc.stroke();
            gc.setFill(Color.web("#1e3a5f"));
            for (Point p:pts) gc.fillOval(p.x*pvScale+pvOffX-3,pvOffY-p.y*pvScale-3,6,6);
        }
        // Robot pin
        if (robotLoc!=null){
            double rx=robotLoc.x*pvScale+pvOffX, ry=pvOffY-robotLoc.y*pvScale;
            gc.setFill(Color.web("#fbbf24",0.4)); gc.fillOval(rx-12,ry-12,24,24);
            gc.setFill(Color.web("#d97706"));
            gc.beginPath(); gc.moveTo(rx,ry); gc.lineTo(rx-8,ry-18); gc.lineTo(rx+8,ry-18);
            gc.closePath(); gc.fill();
            gc.setFill(Color.web("#fbbf24")); gc.fillOval(rx-8,ry-26,16,16);
            gc.setFill(Color.web("#1e3a5f")); gc.setFont(Font.font("System",11));
            gc.setTextAlign(TextAlignment.LEFT);
            gc.fillText(String.format("(%.1f,%.1f)",robotLoc.x,robotLoc.y),rx+10,ry-14);
        }
    }

    // =====================================================================
    //  UTILS
    // =====================================================================

    private void handleExportWaypoints() {
        if (result == null || result.waypoints == null || result.waypoints.isEmpty()) {
            showError("No waypoints to export. Run the algorithm first.");
            return;
        }
        DirectoryChooser dc = new DirectoryChooser();
        dc.setTitle("Choose Export Location (a folder will be created here)");
        if (config.polygonFilePath != null) {
            File polyDir = new File(config.polygonFilePath).getParentFile();
            if (polyDir != null && polyDir.exists()) dc.setInitialDirectory(polyDir);
        }
        File baseDir = dc.showDialog(primaryStage);
        if (baseDir == null) return;

        String polyBaseName = new File(config.polygonFilePath).getName().replaceAll("\\.txt$","");
        File exportDir = new File(baseDir, polyBaseName + "_CPP_Export");
        if (!exportDir.exists() && !exportDir.mkdirs()) {
            showError("Could not create export folder:\n" + exportDir.getAbsolutePath()); return;
        }
        try {
            // 1. Copy original polygon file
            File polySrc  = new File(config.polygonFilePath);
            File polyDest = new File(exportDir, polySrc.getName());
            java.nio.file.Files.copy(polySrc.toPath(), polyDest.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            // 2. Waypoints .txt
            File wpTxt = new File(exportDir, polyBaseName + "_waypoints.txt");
            try (java.io.PrintWriter pw = new java.io.PrintWriter(
                    new java.io.BufferedWriter(new java.io.FileWriter(wpTxt)))) {
                pw.println("# Coverage Path Waypoints");
                pw.println("# Polygon       : " + polySrc.getName());
                pw.println("# Robot width   : " + config.robotWidth);
                pw.println("# Overlap ratio : " + config.overlap);
                pw.println("# Turn penalty  : " + config.turnPenalty);
                pw.println("# Opt. rotation : " + config.optimalRotation);
                pw.println("# Boundary sweep: " + config.boundarySweep);
                pw.println("# Total waypoints: " + result.waypoints.size());
                pw.println("# Format: (x, y)");
                pw.println("#");
                for (Point p : result.waypoints)
                    pw.printf("(%s, %s)%n", formatCoord(p.x), formatCoord(p.y));
            }

            // 3. Waypoints .csv
            File wpCsv = new File(exportDir, polyBaseName + "_waypoints.csv");
            try (java.io.PrintWriter pw = new java.io.PrintWriter(
                    new java.io.BufferedWriter(new java.io.FileWriter(wpCsv)))) {
                pw.println("index,x,y");
                List<Point> wp = result.waypoints;
                for (int i = 0; i < wp.size(); i++)
                    pw.printf("%d,%.6f,%.6f%n", i, wp.get(i).x, wp.get(i).y);
            }

            String msg = String.format(
                "Exported to folder:%n  %s%n%n" +
                "Files:%n  %s%n  %s%n  %s",
                exportDir.getAbsolutePath(),
                polyDest.getName(), wpTxt.getName(), wpCsv.getName());
            Platform.runLater(() -> {
                Alert a = new Alert(Alert.AlertType.INFORMATION);
                a.setTitle("Export Complete"); a.setHeaderText("Export successful");
                a.setContentText(msg); a.showAndWait();
            });
        } catch (java.io.IOException ex) {
            showError("Export failed:\n" + ex.getMessage());
        }
    }

    /** Format a coordinate: no trailing zeros, at most 6 decimal places. */
    private String formatCoord(double v) {
        // Remove trailing zeros like Python's default float formatting
        String s = String.format("%.6f", v);
        s = s.replaceAll("0+$", "").replaceAll("\\.$", ".0");
        return s;
    }

    private void showError(String msg) {
        Platform.runLater(()->{
            Alert a=new Alert(Alert.AlertType.ERROR);
            a.setTitle("Error"); a.setHeaderText("An error occurred");
            a.setContentText(msg); a.showAndWait();
        });
    }

    private void showInfo(String header, String msg) {
        Alert a = new Alert(Alert.AlertType.INFORMATION);
        a.setTitle("Saved"); a.setHeaderText(header);
        a.setContentText(msg); a.showAndWait();
    }

    // =====================================================================
    //  PNG EXPORT — high-resolution "Save PNG" button (all 8 view panels)
    // =====================================================================

    // Conservative cap on the exported image's pixel dimensions. JavaFX
    // Canvas rendering can fail or silently blank out on some GPUs/drivers
    // once a single dimension goes much beyond this, so the requested DPI
    // is scaled back (and the user is told) rather than risking a corrupt
    // export.
    private static final int MAX_EXPORT_DIM = 6000;

    private File lastExportDir = null;

    private void handleSavePng() {
        if (result == null) {
            showError("Nothing to export yet. Run the algorithm first.");
            return;
        }
        if (simCanvas == null || simCanvas.getWidth() <= 0 || simCanvas.getHeight() <= 0) {
            showError("The view isn't ready yet — please try again in a moment.");
            return;
        }

        Integer dpi = askForDpi();
        if (dpi == null) return; // user cancelled

        File file = askForPngSaveFile();
        if (file == null) return; // user cancelled

        try {
            int achievedDpi = exportCurrentViewToPng(file, dpi);
            String note = (achievedDpi != dpi)
                ? String.format("%n%n(Resolution capped to stay within safe image-size limits — "
                    + "saved at %d DPI instead of the requested %d DPI.)", achievedDpi, dpi)
                : "";
            showInfo("PNG saved", "Saved \"" + modeToLabel(displayMode) + "\" to:\n"
                + file.getAbsolutePath() + " (" + achievedDpi + " DPI)" + note);
        } catch (Exception ex) {
            showError("Could not save PNG:\n" + ex.getMessage());
        }
    }

    /** Small modal dialog asking for the export DPI (default 200, capped 50–1000). */
    private Integer askForDpi() {
        Dialog<Integer> dialog = new Dialog<>();
        dialog.setTitle("Save PNG");
        dialog.setHeaderText("Export \"" + modeToLabel(displayMode) + "\" as a PNG image");

        ButtonType saveType = new ButtonType("Next…", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveType, ButtonType.CANCEL);
        dialog.getDialogPane().setStyle("-fx-background-color:" + (darkMode ? "#1e293b" : "#f1f5f9") + ";");

        Spinner<Integer> spDpi = new Spinner<>(50, 1000, 200, 25);
        spDpi.setEditable(true);
        spDpi.setPrefWidth(110);
        spDpi.focusedProperty().addListener((obs, was, isNow) -> {
            if (!isNow) commitSpinnerText(spDpi);
        });

        Label lblDpi = new Label("DPI (50 – 1000):");
        lblDpi.setStyle("-fx-text-fill:" + fg() + ";");
        Label lblHint = new Label("Higher DPI = larger, sharper image for print/paper figures.");
        lblHint.setStyle("-fx-text-fill:" + fg() + ";-fx-font-size:11px;-fx-opacity:0.75;");

        HBox row = new HBox(10, lblDpi, spDpi);
        row.setAlignment(Pos.CENTER_LEFT);
        VBox content = new VBox(8, row, lblHint);
        content.setPadding(new Insets(12));
        dialog.getDialogPane().setContent(content);

        dialog.setResultConverter(bt -> {
            if (bt == saveType) { commitSpinnerText(spDpi); return spDpi.getValue(); }
            return null;
        });
        return dialog.showAndWait().orElse(null);
    }

    /** Forces the spinner's editable text field to be parsed/clamped into its value. */
    private void commitSpinnerText(Spinner<Integer> sp) {
        try {
            int v = Integer.parseInt(sp.getEditor().getText().replaceAll("[^0-9]", ""));
            v = Math.max(50, Math.min(1000, v));
            sp.getValueFactory().setValue(v);
        } catch (Exception ex) {
            sp.getValueFactory().setValue(200);
        }
    }

    private File askForPngSaveFile() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Save PNG — " + modeToLabel(displayMode));
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("PNG image (*.png)", "*.png"));
        String base = modeToLabel(displayMode).toLowerCase()
            .replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        fc.setInitialFileName((base.isEmpty() ? "view" : base) + ".png");
        if (lastExportDir != null && lastExportDir.isDirectory()) {
            fc.setInitialDirectory(lastExportDir);
        } else if (config.polygonFilePath != null) {
            File polyDir = new File(config.polygonFilePath).getParentFile();
            if (polyDir != null && polyDir.exists()) fc.setInitialDirectory(polyDir);
        }
        File file = fc.showSaveDialog(primaryStage);
        if (file == null) return null;
        if (!file.getName().toLowerCase().endsWith(".png")) {
            file = new File(file.getParentFile(), file.getName() + ".png");
        }
        lastExportDir = file.getParentFile();
        return file;
    }

    /**
     * Re-renders the current view (whichever of the 8 panels is active) onto an
     * off-screen canvas scaled up for the requested DPI, then writes it out as a
     * PNG with correct DPI metadata embedded. Returns the DPI actually achieved
     * (may be lower than requested if capped for safety — see MAX_EXPORT_DIM).
     */
    private int exportCurrentViewToPng(File file, int requestedDpi) throws IOException {
        double cw = simCanvas.getWidth(), ch = simCanvas.getHeight();

        double scale = requestedDpi / 96.0; // 96 DPI ≈ standard on-screen density
        double outW  = cw * scale, outH = ch * scale;
        double maxDim = Math.max(outW, outH);
        int achievedDpi = requestedDpi;
        if (maxDim > MAX_EXPORT_DIM) {
            double factor = MAX_EXPORT_DIM / maxDim;
            scale  *= factor;
            outW   *= factor;
            outH   *= factor;
            achievedDpi = (int) Math.round(scale * 96.0);
        }
        int W = Math.max(1, (int) Math.round(outW));
        int H = Math.max(1, (int) Math.round(outH));

        Canvas offCanvas = new Canvas(W, H);
        GraphicsContext g = offCanvas.getGraphicsContext2D();
        g.setFill(Color.web(bg()));
        g.fillRect(0, 0, W, H);
        g.save();
        g.scale(scale, scale);
        dispatchDraw(g, cw, ch);
        g.restore();

        SnapshotParameters sp = new SnapshotParameters();
        sp.setFill(Color.web(bg()));
        WritableImage snap = offCanvas.snapshot(sp, new WritableImage(W, H));

        BufferedImage buffered = SwingFXUtils.fromFXImage(snap, null);
        writePngWithDpi(buffered, file, achievedDpi);
        return achievedDpi;
    }

    /** Writes a BufferedImage to PNG with a pHYs chunk so viewers/Word/LaTeX
     *  know its physical resolution (dots per inch). */
    private void writePngWithDpi(BufferedImage image, File file, int dpi) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("png");
        if (!writers.hasNext()) { ImageIO.write(image, "png", file); return; }

        ImageWriter writer = writers.next();
        try {
            ImageWriteParam writeParam = writer.getDefaultWriteParam();
            ImageTypeSpecifier typeSpecifier =
                ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_INT_ARGB);
            IIOMetadata metadata = writer.getDefaultImageMetadata(typeSpecifier, writeParam);
            if (metadata != null && !metadata.isReadOnly()
                    && metadata.isStandardMetadataFormatSupported()) {
                double pixelSizeMm = 25.4 / dpi; // mm per pixel
                IIOMetadataNode horiz = new IIOMetadataNode("HorizontalPixelSize");
                horiz.setAttribute("value", Double.toString(pixelSizeMm));
                IIOMetadataNode vert = new IIOMetadataNode("VerticalPixelSize");
                vert.setAttribute("value", Double.toString(pixelSizeMm));
                IIOMetadataNode dimension = new IIOMetadataNode("Dimension");
                dimension.appendChild(horiz);
                dimension.appendChild(vert);
                IIOMetadataNode root = new IIOMetadataNode("javax_imageio_1.0");
                root.appendChild(dimension);
                metadata.mergeTree("javax_imageio_1.0", root);
            }
            if (file.getParentFile() != null) file.getParentFile().mkdirs();
            try (ImageOutputStream ios = ImageIO.createImageOutputStream(file)) {
                writer.setOutput(ios);
                writer.write(metadata, new IIOImage(image, null, metadata), writeParam);
            }
        } finally {
            writer.dispose();
        }
    }

    public static void main(String[] args) { launch(args); }
}
