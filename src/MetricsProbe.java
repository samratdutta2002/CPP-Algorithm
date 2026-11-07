import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prints MetricsCalculator results for one polygon + waypoint file, as key=value pairs (used by verify_metrics.py).
 * Usage: java -cp out MetricsProbe <polygon.txt> <waypoints.txt> <robotWidth>
 */
public class MetricsProbe {
    public static void main(String[] a) throws Exception {
        Polygon p = new Polygon(a[0]);
        Pattern xy = Pattern.compile("\\(\\s*(-?[\\d.eE+-]+)\\s*,\\s*(-?[\\d.eE+-]+)\\s*\\)");
        List<Point> wp = new ArrayList<>();
        for (String l : Files.readAllLines(Paths.get(a[1]))) {
            l = l.trim();
            if (l.isEmpty() || l.startsWith("#")) continue;
            Matcher m = xy.matcher(l);
            if (m.find()) wp.add(new Point(Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2))));
        }
        MetricsCalculator.Metrics m = MetricsCalculator.compute(p, wp, Double.parseDouble(a[2]));
        System.out.printf(Locale.US,
            "METRICS coverage_pct=%.6f redundancy_pct=%.6f excess_swept_pct=%.6f outside_swept_pct=%.6f "
            + "path_length=%.6f turns=%d total_turn_deg=%.6f%n",
            m.coveragePct, m.redundancyPct, m.excessSweptPct, m.outsideSweptPct, m.pathLength, m.turnCount,
            m.totalTurnAngleDeg);
    }
}
