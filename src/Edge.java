public class Edge {

    public Point p, q;
    final static double EPS = 1e-9;

    public Edge(){
    }
    public Edge(Point p, Point q){
        this.p = p;
        this.q = q;
    }
    public Edge(double x1, double y1, double x2, double y2){
        this.p = new Point(x1, y1);
        this.q = new Point(x2, y2);
    }
    public Edge(Edge e){
        this.p = e.p;
        this.q = e.q;
    }

    public String toString(){
        return "{" + p + "---" + q + "}";
    }

    public static boolean equals(double a, double b){
        return (Math.abs(a - b) < EPS);
    }
    public static boolean lessThan(double a, double b){
        return a < b - EPS;
    }
    public static boolean lessThanEqual(double a, double b){
        return a <= b + EPS;
    }
    public static boolean greaterThan(double a, double b){
        return a > b + EPS;
    }
    public static boolean greaterThanEqual(double a, double b){
        return a >= b - EPS;
    }

    // Returns true is both edges 'e1' and 'e2' are exactly same
    public static boolean isSame(Edge e1, Edge e2){
        return ( Point.isSame(e1.p, e2.p) && Point.isSame(e1.q, e2.q ) );
    }

    // Returns true if vertex/point V is on the Edge E(A, B)
    public static boolean isVertexOnEdge(Point V, Edge E){
        return isVertexOnEdge(V, E.p, E.q);
    }
    public static boolean isVertexOnEdge(Point V, Point A, Point B) {

        // 1. Check collinearity (area of triangle = 0)
        double cross = (B.x - A.x) * (V.y - A.y) - (B.y - A.y) * (V.x - A.x);
        if (Math.abs(cross) > EPS)
            return false;   // Not collinear → cannot lie on segment

        // 2. Check that V lies within bounding box of AB
        double minX = Math.min(A.x, B.x) - EPS;
        double maxX = Math.max(A.x, B.x) + EPS;
        double minY = Math.min(A.y, B.y) - EPS;
        double maxY = Math.max(A.y, B.y) + EPS;

        return (V.x >= minX && V.x <= maxX &&
                V.y >= minY && V.y <= maxY);
    }

    // Returns true if line segment A(p1, p2) intersect line segment B(q1, q2)
    public static boolean segmentProperIntersect(Edge A, Edge B){
        return segmentProperIntersect(A.p, A.q, B.p, B.q);
    }
    public static boolean segmentProperIntersect(Point p1, Point p2, Point q1, Point q2) {

        double x1 = p1.x, y1 = p1.y;
        double x2 = p2.x, y2 = p2.y;
        double x3 = q1.x, y3 = q1.y;
        double x4 = q2.x, y4 = q2.y;

        // Solve:
        // p1 + t(p2 - p1) = q1 + u(q2 - q1)
        double denom = (x1 - x2)*(y3 - y4) - (y1 - y2)*(x3 - x4);

        // Parallel or collinear -> cannot cross
        if (Math.abs(denom) < EPS)
            return false;

        double t = ((x1 - x3)*(y3 - y4) - (y1 - y3)*(x3 - x4)) / denom;
        double u = ((x1 - x3)*(y1 - y2) - (y1 - y3)*(x1 - x2)) / denom;

        // Proper intersection if t and u are strictly between 0 and 1
        // (exclude endpoints -> touching is allowed)
        if (t > EPS && t < 1 - EPS &&
            u > EPS && u < 1 - EPS)
            return true;

        return false;
    }


    // Returns true if edges e1 and e2 overlap i.e. say for two polygons they will be connected if their edges e1 and e2 overlap
    public static boolean edgeOverlap(Edge e1, Edge e2) {

        Point A = e1.p, B = e1.q;
        Point C = e2.p, D = e2.q;

        // ---- Step 1: Check collinearity ----
        double cross1 = (B.x - A.x) * (C.y - A.y) - (B.y - A.y) * (C.x - A.x);
        double cross2 = (B.x - A.x) * (D.y - A.y) - (B.y - A.y) * (D.x - A.x);

        if (Math.abs(cross1) > EPS || Math.abs(cross2) > EPS)
            return false;   // not collinear

        // ---- Step 2: Determine dominant axis for projection ----
        double dx = Math.abs(B.x - A.x);
        double dy = Math.abs(B.y - A.y);

        if (dx >= dy) {
            // Project onto X axis
            double a1 = Math.min(A.x, B.x);
            double a2 = Math.max(A.x, B.x);

            double b1 = Math.min(C.x, D.x);
            double b2 = Math.max(C.x, D.x);

            double overlap = Math.min(a2, b2) - Math.max(a1, b1);

            return overlap > EPS;   // strictly positive length
        }
        else {
            // Project onto Y axis
            double a1 = Math.min(A.y, B.y);
            double a2 = Math.max(A.y, B.y);

            double b1 = Math.min(C.y, D.y);
            double b2 = Math.max(C.y, D.y);

            double overlap = Math.min(a2, b2) - Math.max(a1, b1);

            return overlap > EPS;
        }
    }

}