import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * MetricsCalculator (version 2): headless coverage metrics for a waypoint polyline.
 *
 * Differences from the original stamp-based version (see the audit notes):
 *  - The swept footprint of every path segment is rasterised as an exact capsule (all points within
 *    robotWidth/2 of the segment), so there are no skipped corners and no scalloped edges.
 *  - A pixel counts as visited again only if the robot returns to it after travelling more than one
 *    robot diameter along the path. Overlap between the consecutive pieces of ONE pass (stamps, joints
 *    of neighbouring segments) is therefore never counted as redundancy.
 *  - Consecutive duplicate waypoints are removed before turns are counted.
 *  - The raster covers the polygon AND the whole path, so travel outside the bounding box is counted.
 *
 * Public fields keep the old names so existing callers (PipelineRunner users, BatchRunner,
 * CompareRunner) compile unchanged.
 */
public class MetricsCalculator {

    private static final double TURN_THRESHOLD_DEG = 30.0;
    private static final double MIN_SEGMENT = 1e-9;
    private static final long MAX_PIXELS = 60_000_000L;

    public static class Metrics {
        public double polygonArea;
        public double uniqueCoveredArea;   // area of the polygon swept at least once
        public double coveragePct;         // uniqueCoveredArea / polygonArea
        public double rawSweptArea;        // swept area counting every visit (also outside the polygon)
        public double redundantArea;       // polygon area swept in two or more separate visits
        public double redundancyPct;       // redundantArea / rawSweptArea (same formula as before)
        public double pathLength;
        public double coveragePerUnit;     // uniqueCoveredArea / pathLength
        public int    turnCount;           // waypoints where the heading changes by >= 30 degrees
        // --- new in version 2 ---
        public double totalTurnAngleDeg;   // sum of all heading changes along the path
        public double excessSweptPct;      // (raw swept - unique swept) / raw swept, whole raster
        public double outsideSweptPct;     // share of raw swept area lying outside the polygon
        public double pixelsPerUnit;       // raster resolution actually used
    }

    public static Metrics compute(Polygon poly, List<Point> waypoints, double robotWidth) {
        Metrics m = new Metrics();
        m.polygonArea = poly.area > 0 ? poly.area : Math.abs(Polygon.computeArea(poly));

        // ---- clean the path: drop consecutive duplicates ----
        List<Point> wp = new ArrayList<>();
        for (Point p : waypoints) {
            if (wp.isEmpty() || Point.distance(wp.get(wp.size() - 1), p) > MIN_SEGMENT) wp.add(p);
        }

        // ---- path length, turns ----
        for (int i = 0; i < wp.size() - 1; i++) m.pathLength += Point.distance(wp.get(i), wp.get(i + 1));
        for (int i = 1; i < wp.size() - 1; i++) {
            Point a = wp.get(i - 1), b = wp.get(i), c = wp.get(i + 1);
            double dx1 = b.x - a.x, dy1 = b.y - a.y, dx2 = c.x - b.x, dy2 = c.y - b.y;
            double cos = (dx1 * dx2 + dy1 * dy2) / (Math.hypot(dx1, dy1) * Math.hypot(dx2, dy2));
            double ang = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cos))));
            m.totalTurnAngleDeg += ang;
            if (ang >= TURN_THRESHOLD_DEG) m.turnCount++;
        }
        if (wp.size() < 2 || robotWidth <= 0) return m;

        // ---- raster grid covering polygon and path ----
        double r = robotWidth / 2.0, diameter = robotWidth;
        double minX = poly.xmin, maxX = poly.xmax, minY = poly.ymin, maxY = poly.ymax;
        for (Point p : wp) {
            minX = Math.min(minX, p.x); maxX = Math.max(maxX, p.x);
            minY = Math.min(minY, p.y); maxY = Math.max(maxY, p.y);
        }
        double ppu = Math.max(10.0, Math.min(40.0, 20.0 / robotWidth));
        while (((maxX - minX + 2 * r) * ppu + 4) * ((maxY - minY + 2 * r) * ppu + 4) > MAX_PIXELS && ppu > 1.0) {
            ppu *= 0.8;
        }
        double pad = 2.0 / ppu;
        double gx0 = minX - r - pad, gy0 = minY - r - pad;
        int imgW = (int) Math.ceil((maxX + r + pad - gx0) * ppu) + 1;
        int imgH = (int) Math.ceil((maxY + r + pad - gy0) * ppu) + 1;
        m.pixelsPerUnit = ppu;
        double pa = 1.0 / (ppu * ppu), r2 = r * r;

        // ---- exact capsule footprint per segment, with visit counting by arc length ----
        short[] visits = new short[imgW * imgH];
        float[] lastS = new float[imgW * imgH];
        double s0 = 0.0;
        for (int i = 0; i < wp.size() - 1; i++) {
            Point a = wp.get(i), b = wp.get(i + 1);
            double dx = b.x - a.x, dy = b.y - a.y;
            double len2 = dx * dx + dy * dy, len = Math.sqrt(len2);
            int x0 = Math.max(0, (int) Math.floor((Math.min(a.x, b.x) - r - gx0) * ppu));
            int x1 = Math.min(imgW - 1, (int) Math.ceil((Math.max(a.x, b.x) + r - gx0) * ppu));
            int y0 = Math.max(0, (int) Math.floor((Math.min(a.y, b.y) - r - gy0) * ppu));
            int y1 = Math.min(imgH - 1, (int) Math.ceil((Math.max(a.y, b.y) + r - gy0) * ppu));
            for (int py = y0; py <= y1; py++) {
                double cy = gy0 + (py + 0.5) / ppu;
                for (int px = x0; px <= x1; px++) {
                    double cx = gx0 + (px + 0.5) / ppu;
                    double t = ((cx - a.x) * dx + (cy - a.y) * dy) / len2;
                    t = t < 0 ? 0 : (t > 1 ? 1 : t);
                    double qx = a.x + t * dx - cx, qy = a.y + t * dy - cy;
                    if (qx * qx + qy * qy > r2) continue;
                    int idx = py * imgW + px;
                    double s = s0 + t * len;
                    if (visits[idx] == 0) {
                        visits[idx] = 1;
                        lastS[idx] = (float) s;
                    } else if (s - lastS[idx] > diameter) {   // robot came back after > 1 diameter of travel
                        if (visits[idx] < Short.MAX_VALUE) visits[idx]++;
                        lastS[idx] = (float) s;
                    } else if (s > lastS[idx]) {
                        lastS[idx] = (float) s;               // still the same pass
                    }
                }
            }
            s0 += len;
        }

        // ---- polygon mask by even-odd scanline fill ----
        boolean[] mask = new boolean[imgW * imgH];
        List<Point> pts = poly.polygonPoints;
        int n = pts.size();
        double[] xs = new double[n];
        for (int py = 0; py < imgH; py++) {
            double y = gy0 + (py + 0.5) / ppu;
            int k = 0;
            for (int i = 0, j = n - 1; i < n; j = i++) {
                double yi = pts.get(i).y, yj = pts.get(j).y;
                if ((yi > y) != (yj > y)) {
                    double xi = pts.get(i).x, xj = pts.get(j).x;
                    xs[k++] = xi + (y - yi) * (xj - xi) / (yj - yi);
                }
            }
            Arrays.sort(xs, 0, k);
            for (int q = 0; q + 1 < k; q += 2) {
                int from = Math.max(0, (int) Math.ceil((xs[q] - gx0) * ppu - 0.5));
                int to = Math.min(imgW - 1, (int) Math.ceil((xs[q + 1] - gx0) * ppu - 0.5) - 1);
                for (int px = from; px <= to; px++) mask[py * imgW + px] = true;
            }
        }

        // ---- tally ----
        long cov = 0, redundantPx = 0, rawAll = 0, uniqueAll = 0, rawOutside = 0;
        for (int idx = 0; idx < visits.length; idx++) {
            int d = visits[idx];
            if (d == 0) continue;
            rawAll += d;
            uniqueAll++;
            if (mask[idx]) {
                cov++;
                if (d > 1) redundantPx++;
            } else {
                rawOutside += d;
            }
        }
        m.uniqueCoveredArea = cov * pa;
        m.rawSweptArea = rawAll * pa;
        m.redundantArea = redundantPx * pa;
        m.coveragePct = m.polygonArea > 0 ? 100.0 * m.uniqueCoveredArea / m.polygonArea : 0;
        m.redundancyPct = rawAll > 0 ? 100.0 * m.redundantArea / m.rawSweptArea : 0;
        m.coveragePerUnit = m.pathLength > 0 ? m.uniqueCoveredArea / m.pathLength : 0;
        m.excessSweptPct = rawAll > 0 ? 100.0 * (rawAll - uniqueAll) / rawAll : 0;
        m.outsideSweptPct = rawAll > 0 ? 100.0 * rawOutside / rawAll : 0;
        return m;
    }

    /** Bounding-box aspect ratio (width / height), always >= 0. */
    public static double aspectRatio(Polygon poly) {
        double w = poly.xmax - poly.xmin, h = poly.ymax - poly.ymin;
        if (h <= 1e-12) return 0.0;
        return w / h;
    }
}