import java.util.*;
import java.io.*;

/**
 * PipelineRunner — pure algorithm, zero GUI.
 *
 * Replaces the processPolygonBasic / processPolygonAdvanced methods in
 * PolygonMain.  Takes a RunConfig, runs the full CPP pipeline, and returns
 * a PipelineResult that the GUI can consume.
 *
 * All intermediate objects are preserved in the result so that the
 * visualisation dropdown can render any of the 5 views.
 */
public class PipelineRunner {

    // ── Analysis hooks (leave null for normal operation) ──────────────────
    public static Double forcedAngle = null;                 // degrees CW; overrides the rotation search
    public static java.util.List<String> forcedOrder = null; // polygon names; overrides the sequencer

    /**
     * Run the full pipeline on the given config.
     * This method is deliberately synchronous — call it from a background
     * thread in the GUI layer (JavaFX Task).
     */
    public static PipelineResult run(RunConfig cfg) {

        PipelineResult res = new PipelineResult();
        res.config = cfg;

        try {

            // ── Step 1: Parse polygon file ────────────────────────────────
            Polygon originalPolygon = new Polygon(cfg.polygonFilePath);
            res.originalPolygon = originalPolygon;

            // ── Step 2: Decide rotation ───────────────────────────────────
            Point centroid = Polygon.computeCentroid(originalPolygon);
            res.centroid   = centroid;

            double angle = 0.0;
            Point  translationOffset = new Point(0.0, 0.0);

            Polygon workingPolygon; // the polygon we operate on (possibly rotated)

            if (cfg.optimalRotation || forcedAngle != null) {
                angle = (forcedAngle != null) ? forcedAngle
                        : originalPolygon.computeRotateAngle(centroid, false);
                res.rotationAngle = angle;

                if (Point.greaterThan(angle, 0.0)) {
                    workingPolygon = originalPolygon.rotatePolygon(
                        angle, centroid, translationOffset);
                    res.rotatedPolygon = new Polygon(workingPolygon);
                } else {
                    workingPolygon = new Polygon(originalPolygon);
                    res.rotatedPolygon = null;
                }
            } else {
                workingPolygon   = new Polygon(originalPolygon);
                res.rotationAngle = 0.0;
                res.rotatedPolygon = null;
            }
            res.translationOffset = translationOffset;

            // ── Step 3: Shrink inward (robust JTS-based erosion; splits into pieces if the
            //            polygon has a "neck" narrower than the robot width -- keeps the
            //            largest piece only, per project policy) ───────────────────────────
            Polygon.ShrinkResult shrinkResult =
                Polygon.shrinkPolygonInwardRobust(workingPolygon, cfg.robotWidth / 2.0);
            if (shrinkResult.polygon == null) {
                res.success = false;
                res.errorMessage = "Inward shrink failed for width " + (cfg.robotWidth / 2.0)
                    + " offset: " + shrinkResult.reason;
                return res;
            }
            Polygon shrunk = shrinkResult.polygon;
            shrunk.name    = workingPolygon.name + "_Shrunk";
            res.shrunkPolygon = new Polygon(shrunk);   // snapshot before HVS mutates it
            res.polygonWasSplit = shrinkResult.pieceCount > 1;
            res.splitPieceCount = shrinkResult.pieceCount;
            res.largestPieceAreaFraction = shrinkResult.totalErodedArea > 0
                ? shrinkResult.largestPieceArea / shrinkResult.totalErodedArea : 1.0;

            // ── Step 4: HVS ───────────────────────────────────────────────
            shrunk.HVS(false);
            res.hvsDcelPolygon = new Polygon(shrunk);  // snapshot after HVS, before DCEL
            // Copy newEdges explicitly — Polygon copy constructor does not copy them
            res.hvsDcelPolygon.newEdges = new java.util.ArrayList<>(shrunk.newEdges);

            // ── Step 5: DCEL decomposition ────────────────────────────────
            List<Polygon> decomposed = shrunk.decomposeUsingDCEL();
            res.decomposedRegions   = decomposed;

            // ── Step 6: Build connectivity graph ─────────────────────────
            PolygonVertex.robotWidth = cfg.robotWidth;   // used only when -Dfix.width=true
            PolygonVertex.overlap    = cfg.overlap;
            PolygonGraph polyGraph  = new PolygonGraph(decomposed, shrunk, false);
            res.polygonGraph        = polyGraph;

            // ── Step 7: Locate the robot's starting region ───────────────
            // Transform robot location to working (rotated) coordinate space
            Point robotLocWorld = cfg.robotLocation;
            Point robotLocWork  = (angle > 1e-9)
                ? rotatePoint(robotLocWorld, angle, centroid, translationOffset)
                : new Point(robotLocWorld);

            // Try ray-casting to find which region contains the robot click
            PolygonVertex root = null;
            for (PolygonVertex v : polyGraph.G.getVertices()) {
                if (v.polygon.isPointInside(robotLocWork, false)) {
                    root = v;
                    break;
                }
            }
            // Fallback: nearest centroid (handles boundary clicks & floating-point misses)
            if (root == null) {
                root = nearestRegion(polyGraph.G.getVertices(), robotLocWork);
            }
            // Final safety: first vertex
            if (root == null) {
                root = polyGraph.G.getVertices().iterator().next();
            }
            res.startRegion = root;

            // ── Find nearest valid entry corner of start region ──────────
            // entryExitMap.keySet() contains exactly the valid entry corners
            // (lower-left always, lower-right if 2 lower pts, upper-left always,
            //  upper-right if 2 upper pts — i.e. 2 to 4 points).
            // All points are in working (rotated) coordinate space, same as robotLocWork.
            if (root.entryExitMap == null || root.entryExitMap.isEmpty()) {
                // Fallback: use all upper + lower points
                root.entryExitMap = new java.util.HashMap<>();
                for (Point p : root.upper) root.entryExitMap.put(p, p);
                for (Point p : root.lower) root.entryExitMap.put(p, p);
            }
            Point robotStartPoint = root.entryExitMap.keySet().iterator().next();
            double bestDist = Double.MAX_VALUE;
            for (Point corner : root.entryExitMap.keySet()) {
                double d = Math.hypot(corner.x - robotLocWork.x,
                                      corner.y - robotLocWork.y);
                if (d < bestDist) { bestDist = d; robotStartPoint = corner; }
            }

            // Yellow marker = user's click location (in original world space)
            // The robot starts here and travels to robotStartPoint first.
            res.robotStartPoint = cfg.robotLocation; // original-space click

            // ── Step 8: Compute visit order ───────────────────────────────
            PolygonSequencer sequencer = new PolygonSequencer(
                polyGraph.G, cfg.robotWidth, cfg.overlap, cfg.turnPenalty);
            if (Boolean.getBoolean("fix.transit"))      // -Dfix.transit=true
                sequencer.setBoundaryRouting(new WayPointGenerator(shrunk, cfg.robotWidth, cfg.overlap));
            List<PolygonVertex> order = sequencer.computeVisitOrder(root, robotStartPoint);
            if (forcedOrder != null) {
                List<PolygonVertex> forced = new ArrayList<>();
                for (String nm : forcedOrder)
                    for (PolygonVertex v : polyGraph.G.getVertices())
                        if (v.polygon.name.equals(nm)) { forced.add(v); break; }
                if (forced.size() == order.size()) order = forced;
            }
            res.visitOrder = order;

            // ── Step 9: Generate waypoints ────────────────────────────────
            WayPointGenerator globalWPG = new WayPointGenerator(shrunk, cfg.robotWidth, cfg.overlap);
            List<Point> wayPoints = new ArrayList<>();

            // waypoint[0] = user click (in working space)
            wayPoints.add(robotLocWork);

            // Route from click to nearest corner.
            // If the straight line stays inside the shrunk polygon use it directly.
            // Otherwise walk the shrunk polygon boundary (shorter arc) to reach the corner.
            List<Point> preamble = routeToCorner(
                robotLocWork, robotStartPoint, root.polygon, shrunk);
            for (Point p : preamble) wayPoints.add(p);

            // Second last point before main path = the corner itself
            Point entry = robotStartPoint;

            for (PolygonVertex v : order) {
                List<Point> wp = globalWPG.generateWaypoints(entry, v);
                if (!wp.isEmpty()) {
                    // wp's first element is presumed to duplicate the current position and is
                    // dropped from the visible path -- but the CORRECTED entry point for the
                    // upcoming regional sweep must always be wp's last element, regardless of
                    // wp's length. The old code took entry from wayPoints' own last element
                    // *after* removing wp's only entry when wp.size() == 1 (an entry and goal
                    // exactly one step apart), which left entry stuck at its stale pre-traversal
                    // value -- silently feeding the regional sweep a start point that could be
                    // far from any real corner of v, corrupting its left/right chains downstream.
                    entry = wp.get(wp.size() - 1);
                    if (wp.size() > 1) {
                        wayPoints.addAll(wp.subList(1, wp.size()));
                    }
                }
                WayPointGenerator regionalWPG = new WayPointGenerator(v, cfg.robotWidth, cfg.overlap);
                wp = regionalWPG.generateWaypoints(entry, false);
                if (!wp.isEmpty()) {
                    wp.remove(0);
                    wayPoints.addAll(wp);
                }
                entry = wayPoints.get(wayPoints.size() - 1);
            }
            wayPoints = removeConsecutiveDuplicates(wayPoints);

            // ── Step 10: Back-transform to original coordinate space ──────
            List<Point> finalWP = (angle > 1e-9)
                ? WayPointGenerator.transformWaypoints(wayPoints, angle, centroid, translationOffset)
                : wayPoints;
            res.waypointsNoBSweep = new ArrayList<>(finalWP);

            // ── Step 11: Boundary sweep (optional) ───────────────────────
            if (cfg.boundarySweep) {
                List<Point> bwp = addBoundarySweep(wayPoints, shrunk);
                List<Point> finalBWP = (angle > 1e-9)
                    ? WayPointGenerator.transformWaypoints(bwp, angle, centroid, translationOffset)
                    : bwp;
                res.waypoints = finalBWP;
            } else {
                res.waypoints = res.waypointsNoBSweep;
            }

            res.success = true;

        } catch (Exception e) {
            res.success      = false;
            res.errorMessage = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            e.printStackTrace();
        }

        return res;
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static List<Point> removeConsecutiveDuplicates(List<Point> wp) {
        if (wp == null || wp.isEmpty()) return new ArrayList<>();
        List<Point> clean = new ArrayList<>();
        clean.add(wp.get(0));
        for (int i = 1; i < wp.size(); i++) {
            if (!Point.isSame(clean.get(clean.size() - 1), wp.get(i)))
                clean.add(wp.get(i));
        }
        return clean;
    }

    private static List<Point> addBoundarySweep(List<Point> wayPoints, Polygon shrunk) {
        List<Point> bwp = new ArrayList<>(wayPoints);
        int i;
        for (i = 0; i < shrunk.polygonPoints.size(); i++)
            if (Point.isSame(shrunk.polygonPoints.get(i), wayPoints.get(wayPoints.size() - 1)))
                break;
        if (i < shrunk.polygonPoints.size()) {
            int index = (i + 1) % shrunk.polygonPoints.size();
            while (!Point.isSame(shrunk.polygonPoints.get(i), shrunk.polygonPoints.get(index))) {
                bwp.add(shrunk.polygonPoints.get(index));
                index = (index + 1) % shrunk.polygonPoints.size();
            }
            bwp.add(shrunk.polygonPoints.get(index));
        }
        return bwp;
    }

    /**
     * Route from 'from' to 'to', constrained to the start region polygon.
     *
     * 1. If the straight segment from→to does NOT cross any edge of the
     *    start region → return [to] directly.
     * 2. If it DOES cross → find the FIRST intersection point, snap to the
     *    nearest boundary vertex of the start region from that point, then
     *    walk the shorter arc of the region boundary to reach 'to'.
     */
    private static List<Point> routeToCorner(
            Point from, Point to, Polygon startRegion, Polygon shrunk) {

        // Use start region boundary; fall back to full shrunk polygon
        Polygon boundary = (startRegion != null
                            && startRegion.polygonPoints.size() >= 3)
                           ? startRegion : shrunk;

        List<Point> pts = boundary.polygonPoints;
        int n = pts.size();

        double fx = from.x, fy = from.y;
        double dx = to.x - fx, dy = to.y - fy;

        // ── Find first boundary edge crossed by from→to ───────────────────
        Point firstHit  = null;
        int   hitVertex = -1;
        double bestT    = Double.MAX_VALUE;

        for (int i = 0; i < n; i++) {
            Point a = pts.get(i), b = pts.get((i + 1) % n);
            double ex = b.x - a.x, ey = b.y - a.y;
            double denom = dx * ey - dy * ex;
            if (Math.abs(denom) < 1e-9) continue;

            double t = ((a.x - fx) * ey - (a.y - fy) * ex) / denom;
            double u = ((a.x - fx) * dy  - (a.y - fy) * dx)  / denom;

            if (t > 1e-6 && t < 1.0 - 1e-6
                    && u > 1e-6 && u < 1.0 - 1e-6
                    && t < bestT) {
                bestT    = t;
                firstHit = new Point(fx + t * dx, fy + t * dy);
                hitVertex = i;
            }
        }

        // ── No crossing → direct straight line ────────────────────────────
        if (firstHit == null) {
            return java.util.List.of(to);
        }

        // ── Crossing found ────────────────────────────────────────────────
        // Step onto boundary at the nearest vertex to the intersection point
        int iBoundary = nearestPolyIndex(firstHit, pts);
        int iTo       = nearestPolyIndex(to, pts);

        // Build both arcs from iBoundary → iTo
        List<Point> arc1 = new ArrayList<>(), arc2 = new ArrayList<>();
        int idx = (iBoundary + 1) % n;
        while (idx != iTo) { arc1.add(pts.get(idx)); idx = (idx + 1) % n; }
        arc1.add(to);
        idx = (iBoundary - 1 + n) % n;
        while (idx != iTo) { arc2.add(pts.get(idx)); idx = (idx - 1 + n) % n; }
        arc2.add(to);

        Point entry = pts.get(iBoundary);
        List<Point> best = arcLength(entry, arc1) <= arcLength(entry, arc2)
                           ? arc1 : arc2;

        List<Point> path = new ArrayList<>();
        path.add(entry);        // step onto boundary
        path.addAll(best);      // walk boundary to corner
        return path;
    }

    private static int nearestPolyIndex(Point p, List<Point> pts) {
        int best = 0; double bestD = Double.MAX_VALUE;
        for (int i = 0; i < pts.size(); i++) {
            double d = Math.hypot(pts.get(i).x - p.x, pts.get(i).y - p.y);
            if (d < bestD) { bestD = d; best = i; }
        }
        return best;
    }

    private static double arcLength(Point from, List<Point> arc) {
        double len = 0; Point prev = from;
        for (Point p : arc) { len += Math.hypot(p.x-prev.x, p.y-prev.y); prev = p; }
        return len;
    }

    private static Point rotatePoint(Point p, double degree, Point centroid, Point translationOffset) {
        // Forward-transform: same rotation as rotatePolygon (theta = -degree CW),
        // then add the translation shift that rotatePolygon wrote into translationOffset.
        double theta = Math.toRadians(-degree);
        double cos   = Math.cos(theta);
        double sin   = Math.sin(theta);
        double dx    = p.x - centroid.x;
        double dy    = p.y - centroid.y;
        return new Point(
            centroid.x + dx * cos - dy * sin + translationOffset.x,
            centroid.y + dx * sin + dy * cos + translationOffset.y);
    }

    /**
     * Nearest-centroid fallback: if ray-casting fails to place the click inside
     * any decomposed region (can happen near region boundaries or due to floating-
     * point edge cases), return the region whose centroid is closest to the click.
     */
    private static PolygonVertex nearestRegion(
            java.util.Collection<PolygonVertex> vertices, Point loc) {
        PolygonVertex best = null;
        double bestDist = Double.MAX_VALUE;
        for (PolygonVertex v : vertices) {
            double cx = v.polygon.polygonPoints.stream().mapToDouble(pp -> pp.x).average().orElse(0);
            double cy = v.polygon.polygonPoints.stream().mapToDouble(pp -> pp.y).average().orElse(0);
            double d  = Math.hypot(cx - loc.x, cy - loc.y);
            if (d < bestDist) { bestDist = d; best = v; }
        }
        return best;
    }
}
