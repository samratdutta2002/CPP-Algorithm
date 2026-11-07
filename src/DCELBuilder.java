import java.util.*;

public class DCELBuilder {

    // DCEL STRUCTURES
    static class Vertex {
        Point p;
        HalfEdge incident;

        Vertex(Point p) { this.p = p; }
    }
    static class HalfEdge {
        Vertex origin;
        HalfEdge twin, next, prev;
        Face face;
    }
    static class Face {
        HalfEdge outer;
    }



    public static List<Polygon> buildAndExtract(Polygon input) {

        Map<Point, Vertex> map = new HashMap<>();
        List<HalfEdge> edges = new ArrayList<>();

        // Create vertices
        for (Point p : input.polygonPoints)
            map.put(p, new Vertex(p));

        // REMOVE DUPLICATE UNDIRECTED EDGES  (CRITICAL FIX)
        Set<String> seen = new HashSet<>();
        List<Edge> uniqueEdges = new ArrayList<>();

        for (Edge e : input.polygonEdges) {
            String key = edgeKey(e.p, e.q);
            if (!seen.contains(key)) {
                seen.add(key);
                uniqueEdges.add(e);
            }
        }

        // Create half edges
        for (Edge e : uniqueEdges) {

            Vertex v1 = map.get(e.p);
            Vertex v2 = map.get(e.q);

            HalfEdge h1 = new HalfEdge();
            HalfEdge h2 = new HalfEdge();

            h1.origin = v1;
            h2.origin = v2;

            h1.twin = h2;
            h2.twin = h1;

            v1.incident = h1;
            v2.incident = h2;

            edges.add(h1);
            edges.add(h2);
        }

        // Group outgoing
        Map<Vertex, List<HalfEdge>> outgoing = new HashMap<>();

        for (HalfEdge h : edges)
            outgoing.computeIfAbsent(h.origin, k -> new ArrayList<>()).add(h);

        // Sort CCW
        for (Vertex v : outgoing.keySet()) {

            List<HalfEdge> list = outgoing.get(v);

            list.sort((a, b) -> {
                double A = angle(a.origin.p, a.twin.origin.p);
                double B = angle(b.origin.p, b.twin.origin.p);
                return Double.compare(A, B);
            });
        }

        // CORRECT DCEL NEXT / PREV LINKING
        for (HalfEdge e : edges) {

            HalfEdge twin = e.twin;
            Vertex v = twin.origin;

            List<HalfEdge> list = outgoing.get(v);

            int idx = list.indexOf(twin);

            HalfEdge prevAtVertex =
                    list.get((idx - 1 + list.size()) % list.size());

            e.next = prevAtVertex;
            prevAtVertex.prev = e;
        }

        // EXTRACT FACES
        List<Face> faces = new ArrayList<>();

        for (HalfEdge h : edges) {

            if (h.face != null) continue;

            Face f = new Face();
            HalfEdge start = h;
            HalfEdge cur = h;

            do {
                cur.face = f;
                cur = cur.next;
            } while (cur != start);

            f.outer = start;
            faces.add(f);
        }

        return convertFacesToPolygons(faces);
    }

    // FACE -> POLYGON
    private static List<Polygon> convertFacesToPolygons(List<Face> faces) {

        List<Polygon> result = new ArrayList<>();
        int count = 1;

        for (Face f : faces) {

            HalfEdge start = f.outer;
            HalfEdge cur = start;

            Polygon poly = new Polygon();
            Set<Point> visited = new HashSet<>();

            do {
                Point p = cur.origin.p;

                if (!visited.contains(p)) {
                    poly.addPoint(p);
                    visited.add(p);
                }

                cur = cur.next;

            } while (cur != start);

            if (poly.polygonPoints.size() < 3)
                continue;

            // FILTER ONLY TRUE FACES USING SIGNED AREA
            double signed = signedArea(poly);

            if (signed <= Point.EPS)
                continue;   // discard pseudo cycles & outer face

            // Close polygon
            poly.polygonEdges.add(
                    new Edge(
                            poly.polygonPoints.get(poly.polygonPoints.size() - 1),
                            poly.polygonPoints.get(0)
                    )
            );

            //poly.resetBoundingBox();
            poly.removeInitialCollinearity(); // if first and last points have collinearity\
            poly.reshapePolygon();
            poly.criticalVertexCount = poly.allCriticalVertices().size();
            poly.height = poly.ymax - poly.ymin;

            poly.name = "P" + count;
            count++;
            result.add(poly);
        }

        return result;
    }


    private static double angle(Point a, Point b) {
        double ang = Math.atan2(b.y - a.y, b.x - a.x);
        if (ang < 0) ang += 2 * Math.PI;
        return ang;
    }
    private static double signedArea(Polygon poly) {
        double sum = 0;
        List<Point> pts = poly.polygonPoints;
        int n = pts.size();

        for (int i = 0; i < n; i++) {
            Point p1 = pts.get(i);
            Point p2 = pts.get((i + 1) % n);
            sum += p1.x * p2.y - p2.x * p1.y;
        }
        return sum / 2.0;
    }


    // EPS-SAFE EDGE KEY FOR DUPLICATE REMOVAL
    private static String edgeKey(Point a, Point b) {

        long ax = Math.round(a.x / Point.EPS);
        long ay = Math.round(a.y / Point.EPS);
        long bx = Math.round(b.x / Point.EPS);
        long by = Math.round(b.y / Point.EPS);

        if (ax < bx || (ax == bx && ay < by))
            return ax + "," + ay + "|" + bx + "," + by;
        else
            return bx + "," + by + "|" + ax + "," + ay;
    }
}
