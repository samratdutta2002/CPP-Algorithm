import java.util.List;
import java.util.ArrayList;

/**
 * PipelineResult — everything the GUI needs after the algorithm finishes.
 *
 * The pipeline (PipelineRunner) populates this and hands it back to the
 * SimulatorController, which uses it to drive rendering and metrics.
 */
public class PipelineResult {

    // ── Input polygon (original, for rendering) ───────────────────────────
    public Polygon originalPolygon;

    // ── Intermediate representations (for the 5-view dropdown) ───────────
    public Polygon shrunkPolygon;
    public Polygon rotatedPolygon;       // null if no rotation was applied
    public Polygon hvsDcelPolygon;       // shrunkPolygon after HVS (with newEdges)
    public List<Polygon> decomposedRegions = new ArrayList<>();

    // ── Graph (for the Graph view) ────────────────────────────────────────
    public PolygonGraph polygonGraph;

    // ── Final waypoints (in original polygon coordinate space) ───────────
    public List<Point> waypoints;        // with boundary sweep if enabled
    public List<Point> waypointsNoBSweep; // always present for comparison

    // ── Metadata ──────────────────────────────────────────────────────────
    public RunConfig config;
    public double    rotationAngle;      // degrees CW; 0 if no rotation
    public Point     centroid;
    public Point     translationOffset;
    public Point     robotStartPoint;
    public PolygonVertex startRegion;
    public List<PolygonVertex> visitOrder = new ArrayList<>();

    // ── Error reporting ───────────────────────────────────────────────────
    public boolean success = true;
    public String  errorMessage = "";

    // ── Robustness diagnostics (inward-shrink step) ───────────────────────
    /** True if the inward shrink eroded the polygon into more than one disjoint piece
     *  (a "neck" narrower than the robot width) -- only the largest piece was kept. */
    public boolean polygonWasSplit = false;
    /** How many disjoint pieces the erosion produced (1 if no split happened). */
    public int     splitPieceCount = 1;
    /** The kept (largest) piece's share of the total eroded area (1.0 if no split). */
    public double  largestPieceAreaFraction = 1.0;
}
