import java.util.Objects;


public class Point {

    public double x, y;
    final static double EPS = 1e-9;

    public Point(){
    }
    public Point(double x, double y){
        this.x = x;
        this.y = y;
    }
    public Point(Point P){
        this.x = P.x;
        this.y = P.y;
    }

    public String toString(){
        return "(" + this.x + ", " + this.y + ")";
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
    public static boolean isSame(Point p, Point q){
        return (equals(p.x, q.x) && equals(p.y, q.y));
    }

    public static double distance(Point p, Point q){
        return Math.sqrt( Math.pow((p.x - q.x), 2) + Math.pow((p.y - q.y), 2) );
    }

    public static boolean isCollinear(Point a, Point b, Point c) {
        double cross = ( ((b.x - a.x) * (c.y - a.y)) - ((b.y - a.y) * (c.x - a.x)) );
        return (Math.abs(cross) < EPS);
    }

    // Returns true if Point 'B' is a Reflexive Point/Vertex i.e. internal angle > 180
    // cross = (B.x−A.x)(C.y−B.y)−(B.y−A.y)(C.x−B.x)
    public static boolean isReflexive(Point A, Point B, Point C) {
        return ( ((B.x - A.x) * (C.y - B.y) - (B.y - A.y) * (C.x - B.x)) < 0 );
    }

    // This method returns the intersection point for points 'A' and 'B' forming edge A->B with intersect line 'y'
    public static Point intersectionPoint(Edge e, double y) {
        return intersectionPoint(e.p, e.q, y);
    }
    public static Point intersectionPoint(Point A, Point B, double y) {

        // No intersection if A->B is a straight line parallel to x-axis
        if (equals(A.y, B.y))
            return null;

        // Compute t where A.y + t(B.y - A.y) = y
        double t = (y - A.y) / (B.y - A.y);
        if (lessThan(t, 0) || greaterThan(t, 1)) // if 't' is < 0 or > 1 then we will compute the intersection point outside edge A->B
            return null;

        return new Point((A.x + t * (B.x - A.x)), y); // point X = A.x + t * (B.x - A.x)
    }



    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Point)) return false;
        Point p = (Point) o;
        return Math.abs(x - p.x) < EPS &&
            Math.abs(y - p.y) < EPS;
    }

    @Override
    public int hashCode() {
        long rx = Math.round(x / EPS);
        long ry = Math.round(y / EPS);
        return Objects.hash(rx, ry);
    }



}