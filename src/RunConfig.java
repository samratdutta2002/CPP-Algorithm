/**
 * RunConfig — single source of truth for all user-supplied parameters.
 *
 * Replaces the public static fields in PolygonMain and is passed explicitly
 * through the pipeline so nothing is globally mutable.
 */
public class RunConfig {

    // ── Defaults ──────────────────────────────────────────────────────────
    public static final double DEFAULT_ROBOT_WIDTH   = 1.0;
    public static final double DEFAULT_TURN_PENALTY  = 2.0;
    public static final double DEFAULT_OVERLAP       = 0.05;   // 5 %
    public static final boolean DEFAULT_OPT_ROTATION = true;
    public static final boolean DEFAULT_BOUNDARY_SWEEP = true;

    // ── Fields ────────────────────────────────────────────────────────────
    public double  robotWidth;
    public double  turnPenalty;
    public double  overlap;
    public boolean optimalRotation;
    public boolean boundarySweep;

    /** Absolute path to the polygon .txt file chosen by the user. */
    public String  polygonFilePath;

    /** Robot location clicked by the user on the preview canvas (world coords). */
    public Point   robotLocation;

    // ── Constructor with defaults ─────────────────────────────────────────
    public RunConfig() {
        robotWidth      = DEFAULT_ROBOT_WIDTH;
        turnPenalty     = DEFAULT_TURN_PENALTY;
        overlap         = DEFAULT_OVERLAP;
        optimalRotation = DEFAULT_OPT_ROTATION;
        boundarySweep   = DEFAULT_BOUNDARY_SWEEP;
    }

    @Override
    public String toString() {
        return String.format(
            "RunConfig{robotWidth=%.2f, turnPenalty=%.2f, overlap=%.2f, " +
            "optRot=%b, bSweep=%b, file='%s', robotLoc=%s}",
            robotWidth, turnPenalty, overlap,
            optimalRotation, boundarySweep,
            polygonFilePath, robotLocation);
    }
}
