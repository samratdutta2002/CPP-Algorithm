import java.io.File;

/**
 * BatchConfig — all user-supplied parameters for a batch benchmarking run.
 *
 * Situations:
 *  1. Fixed robot width, different polygons, optimal rotation OFF
 *  2. Fixed robot width, different polygons, optimal rotation ON
 *  3. Robot width = derived from X% of polygon area, different polygons, rotation OFF
 *  4. Robot width = derived from X% of polygon area, different polygons, rotation ON
 *  5. Robot width swept from min to max (step), every polygon in folder, rotation OFF
 *  6. Robot width swept from min to max (step), every polygon in folder, rotation ON
 *
 * Robot-width-from-area formula (situations 3/4):
 *   The robot footprint is modelled as a circle whose AREA equals X% of the
 *   polygon's area. The robot "width" fed into the CPP algorithm is that
 *   circle's diameter:
 *       robotArea  = (areaPercent / 100) * polygonArea
 *       robotWidth = 2 * sqrt(robotArea / PI)
 */
public class BatchConfig {

    public int situation; // 1..6

    public File polygonFolder;
    public File outputFolder; // the already-created, user-named batch folder

    // Situations 1 / 2
    public double fixedRobotWidth = RunConfig.DEFAULT_ROBOT_WIDTH;

    // Situations 3 / 4
    public double areaPercent = 2.0; // X, e.g. 2.0 == 2%

    // Situations 5 / 6
    public double widthMin = 0.5;
    public double widthMax = 2.0;
    public double widthStep = 0.5;

    // Common algorithm parameters
    public double turnPenalty = RunConfig.DEFAULT_TURN_PENALTY;
    public double overlap = RunConfig.DEFAULT_OVERLAP;
    public boolean boundarySweep = RunConfig.DEFAULT_BOUNDARY_SWEEP;

    // Live preview during batch run (GUI only)
    public boolean showLiveAnimation = true;

    public boolean isOptimalRotation() {
        return situation == 2 || situation == 4 || situation == 6;
    }

    public boolean isFixedWidth() {
        return situation == 1 || situation == 2;
    }

    public boolean isPercentWidth() {
        return situation == 3 || situation == 4;
    }

    public boolean isSweepWidth() {
        return situation == 5 || situation == 6;
    }

    /** Robot width (diameter) derived from an area percentage of a given polygon area. */
    public static double widthFromAreaPercent(double polygonArea, double areaPercentX) {
        double robotArea = (areaPercentX / 100.0) * polygonArea;
        if (robotArea <= 0.0) return 0.0;
        return 2.0 * Math.sqrt(robotArea / Math.PI);
    }

    public static String situationLabel(int s) {
        switch (s) {
            case 1: return "1: Fixed width vs. polygons, Rotation OFF";
            case 2: return "2: Fixed width vs. polygons, Rotation ON";
            case 3: return "3: Width = X% of polygon area, Rotation OFF";
            case 4: return "4: Width = X% of polygon area, Rotation ON";
            case 5: return "5: Width sweep (min\u2192max) per polygon, Rotation OFF";
            case 6: return "6: Width sweep (min\u2192max) per polygon, Rotation ON";
            default: return "Unknown";
        }
    }
}
