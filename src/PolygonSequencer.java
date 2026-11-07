/*import java.util.*;

public class PolygonSequencer {

    private Graph<PolygonVertex> graph;

    public PolygonSequencer(Graph<PolygonVertex> graph) {
        this.graph = graph;
    }

    public List<PolygonVertex> computeVisitOrder(PolygonVertex root,
                                                Point startInside) {

        List<PolygonVertex> order = new ArrayList<>();
        Set<PolygonVertex> visited = new HashSet<>();

        // 🔹 Step 1: snap start → valid entry point of root
        Point startEntry = nearestEntry(root, startInside);

        // 🔹 Step 2: DFS with state (current exit = startEntry)
        dfs(root, null, startEntry, visited, order);

        return order;
    }

    private Point dfs(PolygonVertex curr,
                  PolygonVertex parent,
                  Point currentPos,
                  Set<PolygonVertex> visited,
                  List<PolygonVertex> order) {

        visited.add(curr);
        order.add(curr);

        // Compute exit from current entry
        Point exit = curr.entryExitMap.get(currentPos);

        // Collect children
        List<PolygonVertex> children = new ArrayList<>();
        for (PolygonVertex neigh : graph.getAdjVertices(curr)) {
            if (neigh != parent)
                children.add(neigh);
        }

        while (!children.isEmpty()) {

            PolygonVertex bestChild = null;
            Point bestEntry = null;
            double bestScore = Double.POSITIVE_INFINITY;

            for (PolygonVertex child : children) {

                for (Point entry : child.entryExitMap.keySet()) {

                    double transition = Point.distance(exit, entry);
                    double internal = child.distMap.get(entry);

                    double immediateCost = transition + internal;

                    // 🔥 LOOKAHEAD COST
                    double futureCost = estimateFutureCost(child, curr, entry);

                    double totalScore = immediateCost + futureCost;

                    if (totalScore < bestScore) {
                        bestScore = totalScore;
                        bestChild = child;
                        bestEntry = entry;
                    }
                }
            }

            children.remove(bestChild);

            exit = dfs(bestChild, curr, bestEntry, visited, order);
        }

        return exit;
    }


    private double estimateFutureCost(PolygonVertex node,
                                 PolygonVertex parent,
                                 Point entry) {

        Point exit = node.entryExitMap.get(entry);

        double best = Double.POSITIVE_INFINITY;

        boolean hasChild = false;

        for (PolygonVertex neigh : graph.getAdjVertices(node)) {

            if (neigh == parent)
                continue;

            hasChild = true;

            for (Point nextEntry : neigh.entryExitMap.keySet()) {

                double transition = Point.distance(exit, nextEntry);
                double internal = neigh.distMap.get(nextEntry);

                double cost = transition + internal;

                if (cost < best)
                    best = cost;
            }
        }

        // Leaf node → no future cost
        if (!hasChild)
            return 0.0;

        return best;
    }



    private Point nearestEntry(PolygonVertex poly, Point p) {

        double best = Double.POSITIVE_INFINITY;
        Point bestEntry = null;

        for (Point entry : poly.entryExitMap.keySet()) {

            double d = Point.distance(p, entry);

            if (d < best) {
                best = d;
                bestEntry = entry;
            }
        }

        return bestEntry;
    }
}
*/


import java.util.*;

public class PolygonSequencer {

    private Graph<PolygonVertex> graph;

    private double robotWidth;
    private double overlap;
    private double turnPenalty;

    // Optional (-Dfix.transit=true, wired in PipelineRunner): charge the boundary-following distance
    // that the waypoint generator really travels between regions, instead of the straight-line distance.
    private WayPointGenerator boundaryRouting = null;
    public void setBoundaryRouting(WayPointGenerator g) { this.boundaryRouting = g; }
    private double transitionCost(Point exit, PolygonVertex target, Point entry) {
        if (boundaryRouting != null) return boundaryRouting.wayPointDistance(exit, target);
        return Point.distance(exit, entry);
    }

    public PolygonSequencer(Graph<PolygonVertex> graph,
                            double robotWidth,
                            double overlap,
                            double turnPenalty) {
        this.graph = graph;
        this.robotWidth = robotWidth;
        this.overlap = overlap;
        this.turnPenalty = turnPenalty;
    }

    public List<PolygonVertex> computeVisitOrder(PolygonVertex root,
                                                Point startInside) {

        List<PolygonVertex> order = new ArrayList<>();
        Set<PolygonVertex> visited = new HashSet<>();

        // Step 1: snap start → valid entry point of root
        Point startEntry = nearestEntry(root, startInside);

        // Step 2: DFS with state (current exit = startEntry)
        dfs(root, null, startEntry, visited, order);

        return order;
    }

    private Point dfs(PolygonVertex curr,
                      PolygonVertex parent,
                      Point currentPos,
                      Set<PolygonVertex> visited,
                      List<PolygonVertex> order) {

        visited.add(curr);
        order.add(curr);

        // Compute exit from current entry
        Point exit = curr.entryExitMap.get(currentPos);

        // Collect children
        List<PolygonVertex> children = new ArrayList<>();
        for (PolygonVertex neigh : graph.getAdjVertices(curr)) {
            if (neigh != parent)
                children.add(neigh);
        }

        while (!children.isEmpty()) {

            PolygonVertex bestChild = null;
            Point bestEntry = null;
            double bestScore = Double.POSITIVE_INFINITY;

            for (PolygonVertex child : children) {

                for (Point entry : child.entryExitMap.keySet()) {

                    // Transition cost
                    double transition = transitionCost(exit, child, entry);

                    // Analytical internal cost
                    double internal = computeInternalCost(child);

                    double immediateCost = transition + internal;

                    // LOOKAHEAD COST
                    double futureCost = estimateFutureCost(child, curr, entry);

                    double totalScore = immediateCost + futureCost;

                    if (totalScore < bestScore) {
                        bestScore = totalScore;
                        bestChild = child;
                        bestEntry = entry;
                    }
                }
            }

            children.remove(bestChild);

            // recurse
            exit = dfs(bestChild, curr, bestEntry, visited, order);
        }

        return exit;
    }

    private double computeInternalCost(PolygonVertex poly) {

        double effectiveWidth = robotWidth * (1.0 - overlap);

        double area = poly.area;

        double height = poly.polygon.ymax - poly.polygon.ymin;

        // Sweep distance approximation
        double sweepCost = area / effectiveWidth;

        // Turn penalty approximation
        double turnCost = 2.0 * turnPenalty * (height / effectiveWidth);

        return sweepCost + turnCost;
    }

    private double estimateFutureCost(PolygonVertex node,
                                     PolygonVertex parent,
                                     Point entry) {

        Point exit = node.entryExitMap.get(entry);

        double best = Double.POSITIVE_INFINITY;

        boolean hasChild = false;

        for (PolygonVertex neigh : graph.getAdjVertices(node)) {

            if (neigh == parent)
                continue;

            hasChild = true;

            for (Point nextEntry : neigh.entryExitMap.keySet()) {

                double transition = transitionCost(exit, neigh, nextEntry);

                double internal = computeInternalCost(neigh);

                double cost = transition + internal;

                if (cost < best)
                    best = cost;
            }
        }

        // Leaf node → no future cost
        return hasChild ? best : 0.0;
    }

    private Point nearestEntry(PolygonVertex poly, Point p) {

        double best = Double.POSITIVE_INFINITY;
        Point bestEntry = null;

        for (Point entry : poly.entryExitMap.keySet()) {

            double d = Point.distance(p, entry);

            if (d < best) {
                best = d;
                bestEntry = entry;
            }
        }

        return bestEntry;
    }
}