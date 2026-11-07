import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

// JTS: used only by shrinkPolygonInwardRobust() for a correct, self-intersection-safe inward
// offset (erosion). org.locationtech.jts.geom.Polygon / .Geometry are NOT imported by simple
// name because they would collide with this file's own Polygon class -- they are referenced
// fully-qualified wherever used.
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.operation.buffer.BufferParameters;
import org.locationtech.jts.operation.buffer.BufferOp;
import org.locationtech.jts.algorithm.Orientation;
import java.util.Comparator;
import java.util.Map;
import java.util.HashMap;
import java.util.Iterator;


public class Polygon {

    public String name;

    public List<Point> polygonPoints = new ArrayList<>();
    public List<Edge> polygonEdges = new ArrayList<>();
    public double ymin, ymax, xmin, xmax;
    final static double EPS = 1e-9;

    public int criticalVertexCount;
    public int reflexiveVertexCount;
    public double height;
    public double area;

    public List<Edge> newEdges = new ArrayList<>();

    public void resetBoundingBox(){
        ymin = Double.MAX_VALUE;
        ymax = Double.MIN_VALUE;
        xmin = Double.MAX_VALUE;
        xmax = Double.MIN_VALUE;
    }

    public Polygon(){
        name = "Polygon";
        resetBoundingBox();
    }

    public Polygon(Polygon P){
        name = P.name;
        polygonPoints = new ArrayList<>(P.polygonPoints);
        polygonEdges = new ArrayList<>(P.polygonEdges);
        ymin = P.ymin;
        ymax = P.ymax;
        xmin = P.xmin;
        xmax = P.xmax;
        height = P.ymax - P.ymin;
        criticalVertexCount = P.criticalVertexCount;
        reflexiveVertexCount = P.reflexiveVertexCount;
    }

    public Polygon(String filename){

        name = filename.substring(
                    filename.lastIndexOf('/')+1,
                    filename.lastIndexOf('.')
                );
        System.out.println("\nProcessing polygon: \"" + name + "\"");
    
        resetBoundingBox();

        readFile(filename);

        reshapePolygon();   // If the polygon is CW, then we make it CCW

        criticalVertexCount = allCriticalVertices().size();

        System.out.println("\n==========Processing Complete==========");
        System.out.println(
            "\nTotal Points: " + polygonPoints.size() + 
            "\nTotal Edges: " + polygonEdges.size() + 
            "\nBounding Box: " + (new Point(xmin, ymin)) + "\t" + (new Point(xmax, ymax)) +
            "\nHeight: " + (ymax-ymin) +
            "\nReflexive Vertex Count: " + reflexiveVertexCount +
            "\nCritical Vertex Count: " + criticalVertexCount + "\n"
        );

        height = ymax - ymin;
    }

    public String toString(){
        return
            "Polygon: \"" + name + "\"" +
            "\nTotal Points: " + polygonPoints.size() + 
            "\nTotal Edges: " + polygonEdges.size() + 
            "\nBounding Box: " + (new Point(xmin, ymin)) + "\t" + (new Point(xmax, ymax)) +
            "\nHeight: " + (ymax-ymin) +
            "\nReflexive Vertex Count: " + reflexiveVertexCount +
            "\nCritical Vertex Count: " + criticalVertexCount + "\n";
    }
    public void getDetails(){
        System.out.println("Polygon: " + name);
        System.out.println("Points: " + polygonPoints + "\n\nEdges: " + polygonEdges);
        System.out.print("\nCritical Vertices: ");
        for(Point p : allCriticalVertices(this))
            System.out.print(p + ", ");
        System.out.println("\n");
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


    public void addPoint(double x, double y){                                                                                           // TC: O(1)
        addPoint(new Point(x, y));
    }
    public void addPoint(Point c){
        Point a = null, b = null;

        if(polygonPoints.size() > 0){

            b = polygonPoints.get(polygonPoints.size()-1);

            // Discard if two points (prev & curr) are consecutive
            if(Point.isSame(b, c))
                return;
        
            if(polygonPoints.size() > 1){
                a = polygonPoints.get(polygonPoints.size()-2);

                // Remove 'prev' if points (prev of prev, prev , curr) are collinear
                if(Point.isCollinear(a, b, c)){
                    polygonPoints.remove(polygonPoints.size()-1);
                    polygonEdges.remove(polygonEdges.size()-1);
                    b = a;
                }
            }
        }

        polygonPoints.add(c);

        if(c.y < ymin) ymin = c.y;      // ymin
        if(c.y > ymax) ymax = c.y;      // ymax
        
        if(c.x < xmin) xmin = c.x;      // xmin
        if(c.x > xmax) xmax = c.x;      // xmax

        if(b != null) // new edge detected
            this.polygonEdges.add( new Edge(b, c) );
    }

    public boolean removeInitialCollinearity(){ // check first and last point of the polygon for collinearity
        boolean validPolygon = false;
        // Removing first and last points if they are collinear
        if(this.polygonPoints.size() > 2){
            Point a, b, c;
            a = this.polygonPoints.get(0);
            b = this.polygonPoints.get(this.polygonPoints.size()-1); // last entry
            c = this.polygonPoints.get(this.polygonPoints.size()-2); 

            // check if 'b' is collinear
            if(Point.isCollinear(a, b, c)){
                this.polygonPoints.remove(this.polygonPoints.size()-1);
                this.polygonEdges.remove(this.polygonEdges.size()-1);
            }

            if(this.polygonPoints.size() > 2){

                a = this.polygonPoints.get(this.polygonPoints.size()-1);
                b = this.polygonPoints.get(0);
                c = this.polygonPoints.get(1);

                // check if 'b' is collinear
                if(Point.isCollinear(a, b, c)){
                    this.polygonPoints.remove(0);
                    this.polygonEdges.remove(0);
                }

                if(this.polygonPoints.size() > 2){
                    // We need to add the edge {P.lastPoint --- P.firstPoint} to close the Polygon
                    this.polygonEdges.add( new Edge((this.polygonPoints.get(this.polygonPoints.size()-1)), (this.polygonPoints.get(0))) );
                    validPolygon = true;
                }
            }
        }

        return validPolygon;
    }


    // This methods read the Point.txt file
    public void readFile(String fileName) {                                                                                                 // TC: O(P)

        try {
            BufferedReader br = new BufferedReader(new FileReader(fileName));
            // Regex for (double, double)
            Pattern pattern = Pattern.compile("\\((-?\\d+(?:\\.\\d+)?),\\s*(-?\\d+(?:\\.\\d+)?)\\)");
            String line;
            this.polygonEdges.clear();
            this.polygonPoints.clear();

            int counter = 0;
            while ( (line = br.readLine()) != null) {
                // Skip comment lines before scanning for coordinates -- the Fields2Benchmark
                // header convention ("# Local bbox min before re-basing to (0, 0), in metres:
                // (X, Y)") embeds two parenthesized number-pairs inside a comment for
                // documentation purposes, which this regex would otherwise silently pick up as
                // two extra, bogus polygon vertices ahead of the real ones on every single file
                // that uses this header format.
                if (line.trim().startsWith("#"))
                    continue;
                Matcher matcher = pattern.matcher(line);
                while (matcher.find()){
                    addPoint( Double.parseDouble(matcher.group(1)), Double.parseDouble(matcher.group(2)) );
                    counter++;
                }
            }

            //MetaData.println("Total Points: " + counter);

            boolean validPolygon = removeInitialCollinearity();

            System.out.println("Total " + counter + " points read");

            if(!validPolygon){
                polygonPoints.clear();
                polygonEdges.clear();
                System.out.println("\nInvalid Polygon\n");
            }

        } catch (IOException e) {
            System.out.println("Error reading file: " + e.getMessage());
        }
    }
    public void writeToFile() {
        writeToFile(this.name + ".txt");
    }
    public void writeToFile(String filename) {
        try {
            BufferedWriter bw = new BufferedWriter(new FileWriter(filename));
            for (Point p : polygonPoints) {
                bw.write("(" + p.x + ", " + p.y + ")");
                bw.newLine();
            }
            bw.close();
        } catch (IOException e) {
            System.out.println("Error writing to file: " + e.getMessage());
        }
    }

    public static double computeArea(Polygon polygon){
        double area = 0.0;
        int n = polygon.polygonPoints.size();
        // Compute signed area using the shoelace formula
        for (int i = 0; i < n; i++) {
            Point p1 = polygon.polygonPoints.get(i);
            Point p2 = polygon.polygonPoints.get((i + 1) % n); // wrap to first point
            area += (p1.x * p2.y - p1.y * p2.x);
        }
        return area/2.0;
    }


    // This method ensures the polygon vertices are ordered CCW
    public void reshapePolygon() {                                                                                                          // TC: O(P)

        if (polygonPoints == null || polygonPoints.size() < 3)
            return;

        double area = computeArea(this);

        this.area = (area > 0.0) ? area : (-1.0 * area);

        //double area = 0.0;
        int n = polygonPoints.size();

        // Compute signed area using the shoelace formula
        //for (int i = 0; i < n; i++) {
        //    Point p1 = polygonPoints.get(i);
        //    Point p2 = polygonPoints.get((i + 1) % n); // wrap to first point
        //    area += (p1.x * p2.y - p1.y * p2.x);
        //}

        // If area > 0, polygon is already counter-clockwise
        if (area > 0)
            return;

        // Reverse the list to make it counter-clockwise
        int left = 0, right = n - 1;
        while (left < right) {
            Point temp = polygonPoints.get(left);
            polygonPoints.set(left, polygonPoints.get(right));
            polygonPoints.set(right, temp);
            left++;
            right--;
        }
        System.out.println("Polygon was reshaped to CCW");
    }




    public static double orient(Point a, Point b, Point c){
        return (b.x - a.x)*(c.y - a.y) - (b.y - a.y)*(c.x - a.x);
    }

    public boolean isPointInside(Point p, boolean debug) {
        return isPointInside(p.x, p.y, debug);
    }

    public boolean isPointInside(double x, double y, boolean debug) {

        if (polygonPoints == null || polygonEdges == null
                || polygonPoints.size() < 3) {
            if (debug) System.out.println("Degenerate polygon.");
            return false;
        }

        /* ── Fast bounding-box rejection ──────────────────────────────────
        * O(1) check. If the point is outside the axis-aligned bounding box
        * it cannot be inside or on the polygon.
        */
        if (Point.lessThan(x, this.xmin) ||
            Point.greaterThan(x, this.xmax) ||
            Point.lessThan(y, this.ymin) ||
            Point.greaterThan(y, this.ymax)) {

            if (debug)
                System.out.printf(
                    "Outside bounding box [%.4f,%.4f]x[%.4f,%.4f]%n",
                    xmin, xmax, ymin, ymax);
            return false;
        }

        Point   test   = new Point(x, y);
        boolean inside = false;

        for (Edge e : polygonEdges) {

            Point a = e.p;
            Point b = e.q;

            if (debug) System.out.println("Edge: " + e);

            /* ── Guard: skip zero-length edges ────────────────────────────
            * A degenerate edge (a == b) would cause division by zero in the
            * intersection formula and contributes nothing geometrically.
            */
            if (Point.isSame(a, b)) continue;

            /* ── 1. Boundary check ─────────────────────────────────────────
            *
            * A point V lies on segment AB if and only if:
            *   (i)  orient(A, B, V) == 0  →  A, B, V are collinear
            *   (ii) V.x in [min(A.x,B.x), max(A.x,B.x)]
            *   (iii)V.y in [min(A.y,B.y), max(A.y,B.y)]
            *
            * If all three hold, V is on the edge — return true immediately.
            * This fires before the crossing test, so boundary points are
            * never misclassified by parity.
            */
            double cross = orient(a, b, test);   // = (B-A) × (V-A)

            if (Math.abs(cross) < EPS
                    && x >= Math.min(a.x, b.x) - EPS
                    && x <= Math.max(a.x, b.x) + EPS
                    && y >= Math.min(a.y, b.y) - EPS
                    && y <= Math.max(a.y, b.y) + EPS) {

                if (debug)
                    System.out.println("  ON boundary at edge " + e);
                return true;
            }

            /* ── 2. Ray-casting crossing test ──────────────────────────────
            *
            * Cast a horizontal ray from (x, y) in the +X direction.
            * Count how many polygon edges the ray crosses.
            * Odd count → inside.   Even count → outside.
            *
            * Vertex-on-ray rule (half-open interval):
            * ─────────────────────────────────────────
            * An edge AB contributes a crossing only if exactly one endpoint
            * is strictly above the ray and the other is on-or-below it.
            * Using the half-open interval [yLow, yHigh) achieves this:
            *
            *   count edge if:  min(a.y,b.y) <= y  AND  y < max(a.y,b.y)
            *
            * Written with the directed form used here:
            *   (a.y <= y < b.y)  OR  (b.y <= y < a.y)
            *
            * Why this is correct at a shared vertex:
            * ─────────────────────────────────────────
            * Suppose the ray passes through vertex B, shared by edges AB
            * and BC.  With the half-open interval:
            *   • Edge AB: B is the upper endpoint → excluded → AB counts
            *              only if a.y <= y (i.e. A is below/on the ray).
            *   • Edge BC: B is the lower endpoint → included → BC counts
            *              only if c.y > y (i.e. C is strictly above).
            * Together they count the vertex exactly once regardless of
            * whether the two edges form a local min/max or a crossing,
            * which is exactly the correct behaviour.
            *
            * Horizontal edges (a.y == b.y):
            * ─────────────────────────────────
            * Neither condition can be satisfied when a.y == b.y, so
            * horizontal edges are silently and correctly skipped.
            * (They are caught by the boundary check if they lie on the ray.)
            */
            boolean aboveLower = Point.lessThanEqual(a.y, y) && Point.lessThan(y, b.y);
            boolean aboveUpper = Point.lessThanEqual(b.y, y) && Point.lessThan(y, a.y);

            if (aboveLower || aboveUpper) {

                /* x-coordinate where edge AB crosses the ray's y-level.
                * Derivation: parametrise AB as A + t(B-A).
                *   At y: t = (y - A.y) / (B.y - A.y)
                *   xIntersect = A.x + t * (B.x - A.x)
                * Division is safe because B.y != A.y (horizontal edges
                * were excluded above).
                */
                double t          = (y - a.y) / (b.y - a.y);
                double xIntersect = a.x + t * (b.x - a.x);

                if (Point.lessThan(x, xIntersect)) {
                    inside = !inside;
                    if (debug)
                        System.out.printf(
                            "  Ray crosses %s at x=%.6f → inside=%b%n",
                            e, xIntersect, inside);
                }
            }
        }

        if (debug)
            System.out.printf("Result for (%.6f,%.6f): %b%n", x, y, inside);

        return inside;
    }



    /*
    // This method tells if a point is inside the polygon or on the boundary
    public boolean isPointInside(Point p, boolean debug){                                                                                   // TC: O(P)
        return isPointInside(p.x, p.y, debug);
    }
    public boolean isPointInside(double x, double y, boolean debug){

        if (polygonPoints == null || polygonEdges == null || polygonPoints.size() < 3)
            return false;

        if(
            Point.lessThan(x, this.xmin) ||
            Point.greaterThan(x, this.xmax) || 
            Point.lessThan(y, this.ymin) || 
            Point.greaterThan(y, this.ymax)
        )
            return false; // as the point is outside the bounding box

        boolean inside = false;
        Point test = new Point(x, y);

        for (Edge e : polygonEdges){

            if (debug) 
                System.out.println("Edge: " + e);

            Point a = e.p;
            Point b = e.q;

            // 1. Boundary check (robust)
            if(
                Math.abs(orient(a, b, test)) < EPS &&
                x >= Math.min(a.x, b.x) - EPS &&
                x <= Math.max(a.x, b.x) + EPS &&
                y >= Math.min(a.y, b.y) - EPS &&
                y <= Math.max(a.y, b.y) + EPS
            ){
                if (debug) 
                    System.out.println("Point " + test + " is ON edge " + e);
                return true;
            }
                

            // 2. Crossing test
            
            
            boolean cond1 = (Point.lessThanEqual(a.y, y) && Point.lessThan(y, b.y)); // (a.y <= y && y < b.y);
            boolean cond2 = (Point.lessThanEqual(b.y, y) && Point.lessThan(y, a.y)); // (b.y <= y && y < a.y);

            if(cond1 || cond2){
                double xIntersect = a.x + (y - a.y)*(b.x - a.x)/(b.y - a.y);
                if(Point.lessThan(x, xIntersect)){
                //if(x < xIntersect){
                    inside = !inside;
                    if (debug) 
                        System.out.println("Ray crosses edge " + e);
                }
            }
        }

        return inside;
    }*/


    /*
        A reflexive vertex V is CRITICAL for a horizontal sweep if:

        1. Both adjacent vertices lie strictly above or strictly below the line y = V.y
            OR
        2. Exactly one adjacent vertex lies on y = V.y, and other one is either above or below
    */

    // True if left and right Points w.r.t. 'V' are either above or below the line y = V.y
    public static boolean isSweepCritical(Point left, Point curr, Point right) {

        if(Point.isSame(left, curr) || Point.isSame(curr, right)) // only condition applicable for special triangle
            return false;

        boolean aboveLeft = left.y > curr.y + EPS;
        boolean aboveRight = right.y > curr.y + EPS;
        boolean belowLeft = left.y < curr.y - EPS;
        boolean belowRight = right.y < curr.y - EPS;

        return (
            (aboveLeft && aboveRight) ||
            (belowLeft && belowRight) ||
            (equals(left.y, curr.y) && (aboveRight || belowRight)) ||
            (equals(curr.y, right.y) && (aboveLeft || belowLeft))
        );
        
    }

    // This method returns all critical vertices in the current polygon
    public List<Point> allCriticalVertices(){                                                                                               // TC: O(P)
        return allCriticalVertices(this);
    }
    public static List<Point> allCriticalVertices(Polygon polygon){
        List<Point> criticalVertex = new ArrayList<>();
        int l, c, r, n = polygon.polygonPoints.size();
        Point left, curr, right;

        polygon.reflexiveVertexCount = 0;
        
        for(c = 0; c < n; c++){

            l = (c - 1 + n) % n;
            r = (c + 1) % n;

            left = polygon.polygonPoints.get(l);
            curr = polygon.polygonPoints.get(c);
            right = polygon.polygonPoints.get(r);

            if(! Point.isReflexive(left, curr, right))
                continue;

            polygon.reflexiveVertexCount++;

            if(isSweepCritical(left, curr, right)){
                criticalVertex.add(curr);
                
            }
        }
        return criticalVertex;
    }
    public static List<Integer> allCriticalVerticesIndex(Polygon polygon){
        List<Integer> criticalVertex = new ArrayList<>();
        int l, c, r, n = polygon.polygonPoints.size();
        Point left, curr, right;

        polygon.reflexiveVertexCount = 0;
        for(c = 0; c < n; c++){

            l = (c - 1 + n) % n;
            r = (c + 1) % n;

            left = polygon.polygonPoints.get(l);
            curr = polygon.polygonPoints.get(c);
            right = polygon.polygonPoints.get(r);

            if(! Point.isReflexive(left, curr, right))
                continue;

            polygon.reflexiveVertexCount++;

            if(isSweepCritical(left, curr, right))
                criticalVertex.add(c);
        }

        return criticalVertex;
    }



    /*
        Algorithm to calculate total pieces after HVS:-

        Sort all CV on non-decreasing y-value and for same y-values, descending x-values

        Compute all unique y-values in CV list sorted wise

        Create a list of y-value versus edge's intersection x-value list where
            each unique y-value has a linked list associated with edges that intersect the line y at point x

        Set total totalValidRay = 0
        for each CV do:
            if curr is first CV then
                If both neighbours above/below then totalValidRay += 2
                Else totalValidRay += 1 (one neighbour above/below other horizontal)
                continue

            if (prev CV).y != currCV.y
                If Both neighbour above/below then totalValidRay += 2
                Else totalValidRay += 1 (one neighbour above/below other horizontal)
                continue

            if (prev CV).y == currCV.y
                Find If there is an edge e in the polygon such that
                    (e is not neighbour edge of (prevCV) & currCV) AND (e intersects open segment between prevCV and currCV)

                If there exist such an edge e then (currCV is not affected by prevCV)
                    If Both neighbour above/below then totalValidRay += 2
                    Else totalValidRay += 1 (one eighbour above/below other horizontal)
                    continue
                Else
                    If prevCV casted a ray towards me then
                        If Both neighbour above/below then totalValidRay += 1
                    Else totalValidRay += 1

        return totalValidRay+1
    */

    public static int calculatePolygonPieces(Polygon P, boolean debug){      // TC: O(P)

        int i, rayCount = 0;
        Point lastCV = null, currCV = null, firstCV = null, prev, curr, next;

        // Iterate the polygon Points
        for(i = 0; i < P.polygonPoints.size(); i++){
            
            prev = P.polygonPoints.get((i-1 + P.polygonPoints.size())%P.polygonPoints.size());
            curr = P.polygonPoints.get(i);
            next = P.polygonPoints.get((i+1) % P.polygonPoints.size());

            
            if(isSweepCritical(prev, curr, next)){

                if(lastCV == null)
                    firstCV = currCV;

                lastCV = currCV;
                currCV = curr;

                if(!Point.equals(prev.y, curr.y) && !Point.equals(curr.y, next.y)){ // Possible 2 rays
                    if(lastCV == null || !Point.equals(lastCV.y, curr.y))
                        rayCount += 2;
                    else
                        rayCount += 1;
                }   
                else{ // 1 rays
                    rayCount += 1;
                }
            }
        }

        if(!Point.isSame(currCV, firstCV) && Point.equals(currCV.y, firstCV.y))
            rayCount -= 1;

        return (rayCount + 1);
    }


    /*public static int calculatePolygonPieces(Polygon P, boolean debug){           // TC: O(P^2)

        int i, j;
        // We first sort the CV list (ascending y-value and descending x-value, if y ties)
        List<Integer> CV = allCriticalVerticesIndex(P);

        // Sort CV indices based on their corresponding Point coordinates
        Collections.sort(CV, (i1, i2) -> {
            Point p1 = P.polygonPoints.get(i1);
            Point p2 = P.polygonPoints.get(i2);
            // 1. Compare by Y ascending
            if (!Point.equals(p1.y, p2.y))
                return Point.lessThan(p1.y, p2.y) ? -1 : 1;
            // 2. If Y same → compare by X descending
            if (!Point.equals(p1.x, p2.x))
                return Point.greaterThan(p1.x, p2.x) ? -1 : 1;
            return 0;
        });


        Point prev, currCV, next, prevCV;
        int totalValidRay = 0, count;
        for(i = 0; i < CV.size(); i++){
            
            count = 0;
            currCV = P.polygonPoints.get(CV.get(i)); // Critical Vertex
            prev = P.polygonPoints.get((CV.get(i) - 1 + P.polygonPoints.size()) % P.polygonPoints.size()); // previous
            next = P.polygonPoints.get((CV.get(i) + 1) % P.polygonPoints.size()); // next

            boolean bothAboveBelow = (
                    (greaterThan(prev.y, currCV.y) && greaterThan(next.y, currCV.y)) ||
                    (lessThan(prev.y, currCV.y) && lessThan(next.y, currCV.y))
            );

            if(i == 0){
                if(bothAboveBelow)
                    count = 2;
                else
                    count = 1;

                if (debug) System.out.println("CV: " + P.polygonPoints.get(CV.get(i)) + " contributed: " + count + " rays");
                totalValidRay += count;
                continue;
            }

            prevCV = P.polygonPoints.get(CV.get(i-1)); // previous critical vertex

            if(!equals(prevCV.y, currCV.y)){ // previous CV donot affect current CV
                if(bothAboveBelow)
                    count = 2;
                else
                    count = 1;
                
                if (debug) System.out.println("CV: " + P.polygonPoints.get(CV.get(i)) + " contributed: " + count + " rays");
                totalValidRay += count;
                continue;
            }

            

            else{ // previous CV may affect current CV

                boolean eExist = false; // whether there exist an edge that obstructs the ray
                for(Edge e: P.polygonEdges){

                    boolean incidentEdge = (
                        (Point.isSame(currCV, e.p) || Point.isSame(currCV, e.q)) ||
                        (Point.isSame(prevCV, e.p) || Point.isSame(prevCV, e.q))
                    );

                    if(incidentEdge) // if e is a neighour edge of point currCV or prevCV then ignore
                        continue;

                    Point X = Point.intersectionPoint(e, currCV.y);
                    if(X == null) // No intersection point found so ignore edge e
                        continue;
                    
                    //if(debug)
                    //    System.out.println("Egde " + e + " intersects line y = " + currCV.y + " at x = " + X.x);
                    double x = X.x;
                    if(greaterThan(x, currCV.x) && lessThan(x, prevCV.x)){
                        //if (debug)
                        //    System.out.println("Edge " + e + " is between currCV: " + currCV + " and prevCV: " + prevCV);
                        eExist = true;
                        break;
                    }

                }

                if(eExist){ // currCV is not affected by prevCV
                    if(bothAboveBelow)
                        count = 2;
                    else
                        count = 1;
                    
                    if (debug) System.out.println("CV: " + P.polygonPoints.get(CV.get(i)) + " contributed: " + count + " rays");
                    totalValidRay += count;
                    continue;
                }

                else{ // currCV is affected by prevCV

                    // Previous CV's neighbours as left and right
                    Point left = P.polygonPoints.get((CV.get(i-1)-1 + P.polygonPoints.size()) % P.polygonPoints.size());
                    Point right = P.polygonPoints.get((CV.get(i-1)+1) % P.polygonPoints.size());
                    
                    boolean prevBothAboveBelow = (
                        (greaterThan(left.y, prevCV.y) && greaterThan(right.y, prevCV.y)) ||
                        (lessThan(left.y, prevCV.y) && lessThan(right.y, prevCV.y))
                    );

                    boolean rayCastedTowardsMe = false;

                    if(prevBothAboveBelow){ // Case 1: prevCV casted 2 rays then definetely one towards me, so currCV can cast only 1 ray
                        rayCastedTowardsMe = true;
                        count = 1;
                    }
                    else{  // Case 2: prevCV casted 1 ray maybe towards me
                        Point prevCVHorizontal;
                        if(equals(prevCV.y, left.y)) prevCVHorizontal = left;
                        else  prevCVHorizontal = right;

                        if(greaterThan(prevCVHorizontal.x, prevCV.x))
                            rayCastedTowardsMe = true;
                    }


                    if(rayCastedTowardsMe){
                        if(bothAboveBelow){
                            count = 1;
                        }
                    }
                    else{
                        count = 1;   
                    }     


                    if (debug) System.out.println("CV: " + P.polygonPoints.get(CV.get(i)) + " contributed: " + count + " rays");
                    totalValidRay += count;
                    continue;
                }

            }

        }

        return (totalValidRay + 1);
    }*/

    

    



    /*  Computes the rotation angle (clockwise) that minimises the number of critical vertices in the polygon.
        Uses edge-based event-angle analysis.
        RETURN: angle in degrees (clockwise)
    */

    // Normalize angle to [0, 180)
    private static double normalizeAngle180(double deg) {
        deg %= 180.0;
        if (deg < 0) deg += 180.0;
        return deg;
    }

    /*
        For each edge e, do
            1. Compute angle a at which edge e becomes horizontal, and add them to a list angles
            2. Add 0 and 180 degrees, then De-duplicate the angles list
            3. For each angle pair (ai, ai+1) from the list compute the mid-angle
                3.1 Compute mid of the angle pair and rotate the polygon and compute the following parameters
                        3.1.1 Total number of critical vertex
                        3.1.2 Height of rotated polygon
                        3.1.3 Chosen Angle-pair interval width
                3.2 if the computed parameters outperform the old one then update best angle with current mid-angle
    */
    public double computeRotateAngle(Point centroid, boolean debug) {                                                                       // TC: O(P^3)

        int i, n = polygonPoints.size();
        if (n < 3)
            return 0.0;

        List<Double> angles = new ArrayList<>();

        // 1. Generate event angles (CW) where each edge becomes horizontal
        for (i = 0; i < n; i++) {

            Point A = polygonPoints.get(i);
            Point B = polygonPoints.get((i + 1) % n);

            double dx = B.x - A.x;
            double dy = B.y - A.y;

            // Ignore degenerate edges
            if (Math.abs(dx) < EPS && Math.abs(dy) < EPS)
                continue;

            // Angle (CW) required to make edge horizontal
            double angleRad = Math.atan2(dy, dx);
            double deg = normalizeAngle180(Math.toDegrees(angleRad));

            angles.add(deg);
        }
        System.out.println();

        if (debug)
            System.out.println("\nEvent angles before sorting: " + angles);

        angles.add(0.0);
        angles.add(180.0);

        // 3. Sort
        Collections.sort(angles);

        // 4. Deduplicate (EPS-safe)
        List<Double> uniqueAngles = new ArrayList<>();
        for (double a : angles) 
            if (uniqueAngles.isEmpty() || Math.abs(a - uniqueAngles.get(uniqueAngles.size() - 1)) > EPS) 
                uniqueAngles.add(a);

        if (debug)
            System.out.println("Event angles after deduplication: " + uniqueAngles + "\n");

        // 5. Find optimal mid-angle
        double bestAngle = 0.0;
        int bestPeiceCount = calculatePolygonPieces(this, debug);     // priority 1
        double bestHeight = height;                                 // priority 2
        double bestIntervalWidth = 0;                               // priority 3 (only for non 0 degree rotation)

        if (debug) {
            System.out.printf(
                "=> Angle: %.4f\tPiece Count: %d\tHeight: %.4f\tInterval Width: NIL\n",
                bestAngle, bestPeiceCount, bestHeight
            );
        }

        for (i = 0; i < uniqueAngles.size() - 1; i++) {

            double a1 = uniqueAngles.get(i);
            double a2 = uniqueAngles.get(i + 1);

            // Skip zero-width intervals
            if (Math.abs(a2 - a1) < EPS)
                continue;

            double midAngle = (a1 + a2) / 2.0;
            double intervalWidth = a2 - a1;

            // Rotate polygon clockwise by mid-angle
            Polygon rotated = this.rotatePolygon(midAngle, centroid, (new Point(0.0, 0.0)));

            int pieceCount = calculatePolygonPieces(rotated, false);
            double height = rotated.ymax - rotated.ymin;

            if (debug) {
                System.out.printf(
                    "=> Angle: %.4f\tPiece Count: %d\tHeight: %.4f\tInterval Width: %.4f%n",
                    midAngle, pieceCount, height, intervalWidth
                );
            }

            // Priority-based selection
            if (
                (pieceCount < bestPeiceCount) ||
                ((pieceCount == bestPeiceCount) && (height < bestHeight)) ||
                ((pieceCount == bestPeiceCount) && (height == bestHeight) && (!equals(bestAngle, 0.0) && intervalWidth > bestIntervalWidth) )
            ){
                bestPeiceCount = pieceCount;
                bestAngle = midAngle;
                bestHeight = height;
                bestIntervalWidth = intervalWidth;
            }

        }

        if (debug) {
            System.out.println("\nBEST ROTATION FOUND:");
            System.out.println("Angle (clockwise) = " + bestAngle);
            System.out.println("Total pieces after HVS: " + bestPeiceCount);
            System.out.println("Polygon height = " + bestHeight);
            System.out.println("Stability interval width = " + bestIntervalWidth);
        }

        System.out.println("\n\n\tRotation Angle = " + bestAngle + "\n\n");
        return bestAngle;
    }


    public static Point computeCentroid(Polygon poly){                                                                                      // TC: O(P)
        Point centroid = new Point();

        // 2. Compute centroid
        for (Point p : poly.polygonPoints) {
            centroid.x += p.x;
            centroid.y += p.y;
        }
        centroid.x /= poly.polygonPoints.size();
        centroid.y /= poly.polygonPoints.size();

        return centroid;
    }

    public Polygon rotatePolygon(double degree, Point centroid, Point translationOffset){                                                   // TC: O(P)
        // 1. Convert degree to radians (clockwise rotation)
        double theta = Math.toRadians(-degree);
        double cos = Math.cos(theta);
        double sin = Math.sin(theta);

        // 2. Compute centroid (fixed pivot ensures reversibility)
        double cx = centroid.x, cy = centroid.y;

        Polygon rotated = new Polygon();
        rotated.name = this.name + "_rotated";

        double xmin = Double.POSITIVE_INFINITY;
        double ymin = Double.POSITIVE_INFINITY;

        // 3. First pass: rotate points (NO shifting yet)
        for (Point p : this.polygonPoints) {

            double dx = p.x - cx;
            double dy = p.y - cy;

            double newX = cx + dx * cos - dy * sin;
            double newY = cy + dx * sin + dy * cos;

            rotated.polygonPoints.add(new Point(newX, newY));

            if (newX < xmin) xmin = newX;
            if (newY < ymin) ymin = newY;
        }

        // 4. Compute translation needed to remove negatives
        double shiftX = (xmin < 0) ? -xmin : 0;
        double shiftY = (ymin < 0) ? -ymin : 0;
        translationOffset.x = shiftX;
        translationOffset.y = shiftY;

        // 5. Apply translation (pure shift → reversible)
        for (Point p : rotated.polygonPoints) {
            p.x += shiftX;
            p.y += shiftY;

            if (p.x < rotated.xmin) rotated.xmin = p.x;
            if (p.x > rotated.xmax) rotated.xmax = p.x;

            if (p.y < rotated.ymin) rotated.ymin = p.y;
            if (p.y > rotated.ymax) rotated.ymax = p.y;
        }

        // 6. Rebuild edges
        int n = rotated.polygonPoints.size();
        for (int i = 0; i < n; i++) {
            Point a = rotated.polygonPoints.get(i);
            Point b = rotated.polygonPoints.get((i + 1) % n);
            rotated.polygonEdges.add(new Edge(a, b));
        }

        rotated.criticalVertexCount = rotated.allCriticalVertices().size();
        rotated.height = rotated.ymax - rotated.ymin;

        return rotated;
    }

    public Polygon undoRotationTransform( double degree, Point centroid, Point translationOffset){                                          // TC: O(P)
        // Inverse rotation angle
        double theta = Math.toRadians(-degree);
        double cos = Math.cos(theta);
        double sin = Math.sin(theta);

        double cx = centroid.x;
        double cy = centroid.y;

        double shiftX = translationOffset.x;
        double shiftY = translationOffset.y;

        Polygon restored = new Polygon();
        restored.name = this.name + "_restored";

        restored.xmin = Double.POSITIVE_INFINITY;
        restored.ymin = Double.POSITIVE_INFINITY;
        restored.xmax = Double.NEGATIVE_INFINITY;
        restored.ymax = Double.NEGATIVE_INFINITY;

        // For each point in THIS decomposed polygon
        for (Point p : this.polygonPoints) {

            // STEP 1: Undo translation FIRST
            double x1 = p.x - shiftX;
            double y1 = p.y - shiftY;

            // STEP 2: Undo rotation about original centroid
            double dx = x1 - cx;
            double dy = y1 - cy;

            double originalX = cx + dx * cos - dy * sin;
            double originalY = cy + dx * sin + dy * cos;

            Point restoredPoint = new Point(originalX, originalY);
            restored.polygonPoints.add(restoredPoint);

            // Update bounding box
            if (originalX < restored.xmin) restored.xmin = originalX;
            if (originalX > restored.xmax) restored.xmax = originalX;
            if (originalY < restored.ymin) restored.ymin = originalY;
            if (originalY > restored.ymax) restored.ymax = originalY;
        }

        // Rebuild edges
        int n = restored.polygonPoints.size();
        for (int i = 0; i < n; i++) {
            Point a = restored.polygonPoints.get(i);
            Point b = restored.polygonPoints.get((i + 1) % n);
            restored.polygonEdges.add(new Edge(a, b));
        }

        restored.height = restored.ymax - restored.ymin;

        return restored;
    }

    /*
    public boolean isRayValid(Point CV, Point V, boolean debug){                                                                        // TC: O(P)

        // 1. Allow boundary points (must not be strictly outside)
        if(!isPointInside(CV, false) || !isPointInside(V, false)){
            if(debug) System.out.println("Endpoints outside polygon");
            return false;
        }


        // 1. Check intersection with polygon edges
        for(Edge e : polygonEdges){

            Point A = e.p;
            Point B = e.q;

            // Skip edges incident to R or V
            if(Point.isSame(A, V) || Point.isSame(B, V))
                continue;

            //if(Point.isSame(CV,A) || Point.isSame(CV,B) || Point.isSame(V,A) || Point.isSame(V,B))
            //    continue;

            if(Edge.segmentProperIntersect(CV, V, A, B)){
                if(debug) System.out.println("Intersects edge " + e);
                return false;
            }
        }

        return true;
    }
    */

    /*
        ALGORITHM Horizontal Visibility Subdivision (HVS):-

        For each critical vertex CV do:

            // Initialise
            Left  = CV.leftVertex
            Right = CV.rightVertex

            if either Left OR Right is horizontal with CV // We will cast at most 1 ray
                if the horizontal edge's x-coordinate is greater than CV.x then
                    castRay(CV, Leftwards)
                else 
                    castRay(CV, Rightwards)

            else // We will cast at most 2 rays
                castRay(CV, Leftwards)
                Update index of CV (as list changes dynamically)
                castRay(CV, Rightwards)
    */
    public void HVS(boolean debug){                                                                                                     // TC: O(P^2)

        List<Point> allCV = allCriticalVertices(this);
        newEdges.clear(); // this list stores all new edges that were not present in original polygon

        int i;
        for(i = 0; i < allCV.size(); i++){

            // 1. Find index of ith Critical Vertex in latest list of polygon points
            int cv = 0;
            for(Point p: polygonPoints){
                if(Point.isSame( allCV.get(i), p ))
                    break;
                cv++;
            }
            
            int left, right;
            left = (cv - 1 + polygonPoints.size()) % polygonPoints.size();
            right = (cv + 1) % polygonPoints.size();

            if(equals(polygonPoints.get(cv).y, polygonPoints.get(left).y)){ // Edge(left, CV) is horizontal
                if(debug) System.out.println("\n---Casting only edge from CV: " + polygonPoints.get(cv) + "---");
                if( greaterThan(polygonPoints.get(left).x, polygonPoints.get(cv).x) ) // skip edges right of CV.x wrt to x-axis
                    castRay(cv, false, debug);
                else
                    castRay(cv, true, debug);
            }

            else if(equals(polygonPoints.get(cv).y, polygonPoints.get(right).y)){ // Edge(CV, right) is horizontal
                if(debug) System.out.println("\n---Casting only edge from CV: " + polygonPoints.get(cv) + "---");
                if( greaterThan(polygonPoints.get(right).x, polygonPoints.get(cv).x) )
                    castRay(cv, false, debug);
                else
                    castRay(cv, true, debug);
            }

            else{

                if(debug) System.out.println("\n---Casting first edge from CV: " + polygonPoints.get(cv) + "---");
                castRay(cv, false, debug);

                // Recompute CV index
                cv = 0;
                for(Point p: polygonPoints){
                    if(Point.isSame( allCV.get(i), p ))
                        break;
                    cv++;
                }

                if(debug) System.out.println("\n---Casting second edge from CV: " + polygonPoints.get(cv) + "---");
                castRay(cv, true, debug);
            }
        }
    }

    /*
        PROCEDURE: castRayRight(CV, boolean direction):-

        Initialise distanceMap (Edge vs Distance)

        for each Edge E do:
            
            Skip E if

                // E is incident to CV
                (E.p = CV OR E.q = CV)
                
                // Edges that are not in the direction 
                (direction = Rightwards AND (E.p.x < CV.x AND E.q.x < CV.x) OR
                 direction = Leftwards AND (E.p.x > CV.x AND E.q.x > CV.x))

                // Edges that donot intersect the line y = CV.y
                ((E.p.y > CV.y AND E.q.y > CV.y) OR
                 (E.p.y < CV.Y AND E.q.y > CV.y))


            Compute intersection Point X of the line y = CV.y with edge E

            Skip edge E if intersection Point is not in the direction
            ((direction = Rightwards AND X.x < CV.x) OR
             direction = Leftwards AND X.x > CV.x))


            // Record distance of CV to intersection point X and store into distance map
            distanceMap.add(E, dist(CV,X))


        from each edge E in distanceMap, select the nearest edge minEdge

        If either of the endpoints of minEdge = CV then
            if Edge(CV, minEdge) already exists
                return
            else
                add new edge Edge(CV, minEdge)
        
        Else
            Compute intersection point X for the line y = CV.y to edge minEdge
            Replace edge minEdge with 
                Edge(CV, X)
                Edge(minEdge.p, X)
                Edge(X, minEdge.q)
            Insert new point X between Points minEdge.p and minEdge.q
    */
    public void castRay(int cv, boolean direction, boolean debug){                                                                      // TC: O(P)

        Point X, CV = polygonPoints.get(cv);
        Map<Edge, Double> distMap = new HashMap<>();

        double dist;
        for(Edge E: polygonEdges){ // direction(true): Consider CV.x <= edges.x    direction(false): edges.x <= CV.x

            // skip edges that are not in the direction
            if(direction){ // skip edge if edge.p.x < CV.x && edge.q.x < CV.x
                if(lessThan(E.p.x, CV.x) && lessThan(E.q.x, CV.x))
                    continue;
            }   
            else{ // skip edge if edge.p.x > CV.x && edge.q.x > CV.x
                if(greaterThan(E.p.x, CV.x) && greaterThan(E.q.x, CV.x))
                    continue;
            }

            // Skip edges that donot intersect line y = CV.y
            if(
                (greaterThan(E.p.y, CV.y) && greaterThan(E.q.y, CV.y)) ||
                (lessThan(E.p.y, CV.y) && lessThan(E.q.y, CV.y)) 
            )
            continue;

            // Skip E is neighbour edge of vertex 'CV'
            if(Point.isSame(E.p, CV) || Point.isSame(E.q, CV))
                continue;

            // Compute intersection Point X
            
            // Case 1: Edge E intersects the line y = CV.y at E.p 
            if(equals(E.p.y, CV.y)){
                X = E.p;
            }
            // Case 2: Edge E intersects the line y = CV.y at E.q
            else if(equals(E.q.y, CV.y)){
                X = E.q;
            }
            // Case 3: Edge E intersects the line y = CV.y at some new Point say X
            else{
                X = Point.intersectionPoint(E.p, E.q, CV.y);
            }

            if(direction){
                if(! greaterThan(X.x, CV.x))
                    continue;
            }
            else{
                if(! lessThan(X.x, CV.x))
                    continue;
            }

            dist = distance(CV, X);
            if(debug)
                System.out.println("CV " + CV + " intersected Edge " + E + " at Point " + X + " with distance " + dist);

            distMap.put(E, dist); // store Edge vs Distance for later selection of nearest edge
        }

        // Select the edge nearest to CV and modify polygonEdges and polygonPoints.
        Edge minEdge = null;
        double minDist = Double.POSITIVE_INFINITY;
        for (Map.Entry<Edge, Double> e : distMap.entrySet()) {
            double d = e.getValue();
            if (d < minDist - EPS) {
                minDist = d;
                minEdge = e.getKey();
            }
        }

        // Case 1: CV.y == minEdge.p.y
        if(equals(CV.y, minEdge.p.y)){

            X = minEdge.p;

            Edge e = new Edge(CV, minEdge.p);
            Edge e_ = new Edge(minEdge.p, CV);

            for(Edge E: polygonEdges){
                if(Edge.isSame(e, E) || Edge.isSame(e_, E)){
                    if(debug)
                        System.out.println("Edge " + E + " already exists");
                    return;
                }
            }
            polygonEdges.add(e);
            newEdges.add(e);
        }

        // Case 2: CV.y == minEdge.q.y
        else if(equals(CV.y, minEdge.q.y)){

            X = minEdge.q;

            Edge e = new Edge(CV, minEdge.q);
            Edge e_ = new Edge(minEdge.q, CV);

            for(Edge E: polygonEdges){
                if(Edge.isSame(e, E) || Edge.isSame(e_, E)){
                    if(debug)
                        System.out.println("Edge " + E + " already exists");
                    return;
                }
            }
            polygonEdges.add(e);
            newEdges.add(e);
        }

        // Case 3: CV.y intersect edge minEdge so compute the point
        else{

            X = Point.intersectionPoint(minEdge.p, minEdge.q, CV.y);

            Edge e1 = new Edge(CV, X); // new edge
            Edge e2 = new Edge(minEdge.p, X);
            Edge e3 = new Edge(X, minEdge.q);

            // Delete edge P->Q from polygonEdges
            int i, index = -1;
            for(i = 0; i < polygonEdges.size(); i++){
                if(Edge.isSame(polygonEdges.get(i), (new Edge(minEdge.p, minEdge.q))) || Edge.isSame(polygonEdges.get(i), (new Edge(minEdge.q, minEdge.p)))){
                    index = i;
                    break;
                }
            }
            if(index == -1){
                System.out.println("Edge \"" + (new Edge(minEdge.p, minEdge.q)) + "\" not found Exception");
                return;
            }
            else 
                polygonEdges.remove(index);
            
            polygonEdges.add(e1);
            polygonEdges.add(e2);
            polygonEdges.add(e3);
            newEdges.add(e1);

            // Insert X between Edge: minEdge.p then minEdge.q
            // Find index of Point minEdge.p
            for(i = 0; i < polygonPoints.size(); i++)
                if(Point.isSame(polygonPoints.get(i), minEdge.p))
                    break;
            
            // Case: P then Q i.e. P---Q, so insert at 
            if( Point.isSame( (polygonPoints.get( ((i+1)%polygonPoints.size()) )), minEdge.q ) ){
                polygonPoints.add((i+1), X);
            }
            // Case: Q before P i.e. Q---P
            else if( Point.isSame( (polygonPoints.get( ((i-1+polygonPoints.size())%polygonPoints.size()) )), minEdge.q ) ){
                polygonPoints.add(i, X);
            }            
        }
        if(debug)
            System.out.println("===Casting edge from " + CV + " to Edge " + minEdge + " at Point " + X + "====\n");
    }
    public static double distance(Point a, Point b) {
        double dist = Math.hypot(a.x - b.x, a.y - b.y);
        return (dist < EPS) ? 0.0 : dist;
    }


    public List<Polygon> decomposeUsingDCEL() {                                                                                         // TC: O(Plog P)
        return DCELBuilder.buildAndExtract(this);
    }


    public static List<Polygon> undoRotation(List<Polygon> allPolygons, double degree, Point centroid, Point translationOffset) {           // TC: O(P)
        List<Polygon> result = new ArrayList<>();
        for (Polygon P : allPolygons) 
            result.add(P.undoRotationTransform(degree, centroid, translationOffset));
        return result;
    }







    public static Polygon shrinkPolygonInward(Polygon polygon, double offset) {

        int n = polygon.polygonPoints.size();
        if (n < 3) return null;

        List<Edge> offsetEdges = new ArrayList<>();

        // STEP 1: compute inward offset edges
        for (int i = 0; i < n; i++) {

            Point a = polygon.polygonPoints.get(i);
            Point b = polygon.polygonPoints.get((i + 1) % n);

            double dx = b.x - a.x;
            double dy = b.y - a.y;

            double len = Math.hypot(dx, dy);
            if (len < Point.EPS) continue;

            // inward normal (polygon is CCW)
            double nx = -dy / len;
            double ny = dx / len;

            Point a2 = new Point(a.x + nx * offset, a.y + ny * offset);
            Point b2 = new Point(b.x + nx * offset, b.y + ny * offset);

            offsetEdges.add(new Edge(a2, b2));
        }

        // STEP 2: compute intersections of adjacent offset edges
        List<Point> newVertices = new ArrayList<>();

        for (int i = 0; i < offsetEdges.size(); i++) {

            Edge e1 = offsetEdges.get(i);
            Edge e2 = offsetEdges.get((i + 1) % offsetEdges.size());

            Point inter = lineIntersection(e1, e2);

            if (inter != null)
                newVertices.add(inter);
        }

        // STEP 3: construct new polygon
        Polygon shrunk = new Polygon();
        shrunk.name = polygon.name + "_shrunk";

        for (Point p : newVertices)
            shrunk.addPoint(p);

        shrunk.removeInitialCollinearity();

        if(shrunk.polygonPoints.size() < 3)
            return null;

        return shrunk;
    }

    /** Every genuine (non-sliver) Polygon piece inside a Geometry, however it's nested
     *  (Polygon, MultiPolygon, or a GeometryCollection mixing polygons with slivers). */
    private static List<org.locationtech.jts.geom.Polygon> collectPolygons(org.locationtech.jts.geom.Geometry g) {
        List<org.locationtech.jts.geom.Polygon> out = new ArrayList<>();
        for (int i = 0; i < g.getNumGeometries(); i++) {
            org.locationtech.jts.geom.Geometry gi = g.getGeometryN(i);
            if (gi instanceof org.locationtech.jts.geom.Polygon && gi.getArea() > Point.EPS)
                out.add((org.locationtech.jts.geom.Polygon) gi);
        }
        return out;
    }

    /** The largest-area Polygon piece inside a Geometry, or null if it contains none. */
    private static org.locationtech.jts.geom.Polygon largestPolygonIn(org.locationtech.jts.geom.Geometry g) {
        List<org.locationtech.jts.geom.Polygon> pieces = collectPolygons(g);
        if (pieces.isEmpty()) return null;
        org.locationtech.jts.geom.Polygon largest = pieces.get(0);
        for (org.locationtech.jts.geom.Polygon piece : pieces)
            if (piece.getArea() > largest.getArea()) largest = piece;
        return largest;
    }

    /**
     * Robust inward offset (erosion) of a simple polygon by `offset`, using JTS's buffer
     * operation. Unlike shrinkPolygonInward() above (naive per-edge offset + unbounded line
     * intersection), this correctly detects when the eroded polygon would self-intersect --
     * i.e. when a "neck" of the field is narrower than 2 * offset (robot width) -- and, in
     * that case, returns the actual disjoint pieces the erosion produces, rather than a single
     * broken/self-intersecting shape that later stages (HVS, DCEL) cannot handle.
     *
     * Project policy (see PipelineRunner): when the polygon splits, only the LARGEST piece by
     * area is kept and covered -- the robot cannot physically cross between disconnected
     * pieces without leaving the field boundary. The caller can inspect ShrinkResult to see
     * whether a split happened, and by how much, for reporting.
     *
     * Returns a ShrinkResult with polygon == null if the erosion removes the polygon entirely
     * (every part of the field is narrower than the robot width).
     */
    public static ShrinkResult shrinkPolygonInwardRobust(Polygon polygon, double offset) {

        int n = polygon.polygonPoints.size();
        if (n < 3 || offset <= 0)
            return new ShrinkResult(null, 0, 0.0, 0.0).withReason(
                n < 3 ? "polygon has fewer than 3 points" : "offset must be positive");

        GeometryFactory gf = new GeometryFactory();
        Coordinate[] coords = new Coordinate[n + 1];
        for (int i = 0; i < n; i++) {
            Point p = polygon.polygonPoints.get(i);
            coords[i] = new Coordinate(p.x, p.y);
        }
        coords[n] = coords[0]; // JTS rings must be explicitly closed

        // JTS expects a CCW exterior ring by OGC convention; this codebase's polygons are CCW
        // too (see shrinkPolygonInward's comment above), but normalise defensively.
        if (!Orientation.isCCW(coords)) {
            List<Coordinate> rev = new ArrayList<>(java.util.Arrays.asList(coords));
            Collections.reverse(rev);
            coords = rev.toArray(new Coordinate[0]);
        }

        org.locationtech.jts.geom.Polygon jtsPoly;
        try {
            LinearRing shell = gf.createLinearRing(coords);
            jtsPoly = gf.createPolygon(shell, null);
            if (!jtsPoly.isValid()) {
                // buffer(0) is JTS's standard trick to repair minor self-intersections /
                // degenerate rings in the INPUT before eroding it. The repair can itself split
                // the input into several pieces (e.g. a near self-touching digitised boundary) --
                // that is a property of the INPUT, not of this offset's width, so it must be
                // handled the same way as an erosion split: keep the largest piece and carry on,
                // not bail out unconditionally.
                org.locationtech.jts.geom.Geometry repaired = jtsPoly.buffer(0);
                org.locationtech.jts.geom.Polygon largestRepaired = largestPolygonIn(repaired);
                if (largestRepaired == null)
                    return new ShrinkResult(null, 0, 0.0, 0.0).withReason(
                        "input polygon is invalid and repairing it (buffer(0)) left no usable area "
                        + "-- repaired geometry: " + repaired.getGeometryType());
                if (repaired.getNumGeometries() > 1) {
                    System.err.println("[shrinkPolygonInwardRobust] " + polygon.name +
                        ": input polygon was invalid (self-intersecting/degenerate) and repairing "
                        + "it split it into " + repaired.getNumGeometries()
                        + " piece(s); kept the largest (" +
                        String.format("%.1f%%", 100.0 * largestRepaired.getArea() / repaired.getArea())
                        + " of the repaired area) before eroding it");
                }
                jtsPoly = largestRepaired;
            }
        } catch (Exception ex) {
            return new ShrinkResult(null, 0, 0.0, 0.0).withReason(
                "exception building/repairing the input ring: " + ex.getClass().getSimpleName()
                + ": " + ex.getMessage());
        }

        BufferParameters params = new BufferParameters();
        params.setJoinStyle(BufferParameters.JOIN_MITRE); // sharp corners, closest to the old method's intent
        params.setMitreLimit(5.0);

        org.locationtech.jts.geom.Geometry eroded;
        try {
            eroded = BufferOp.bufferOp(jtsPoly, -offset, params);
        } catch (Exception ex) {
            return new ShrinkResult(null, 0, 0.0, 0.0).withReason(
                "exception during erosion: " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
        if (eroded == null || eroded.isEmpty())
            return new ShrinkResult(null, 0, 0.0, 0.0).withReason(
                "eroding by " + offset + " left nothing -- every part of the polygon is narrower "
                + "than the robot width");

        // Erosion can yield one polygon, several disjoint polygons (a split), or occasionally
        // slivers with near-zero area; collect every genuine polygonal piece.
        List<org.locationtech.jts.geom.Polygon> pieces = collectPolygons(eroded);
        if (pieces.isEmpty())
            return new ShrinkResult(null, 0, 0.0, 0.0).withReason(
                "erosion result had no polygon piece above the sliver-area threshold ("
                + eroded.getGeometryType() + ", area " + eroded.getArea() + ")");

        double totalArea = 0;
        org.locationtech.jts.geom.Polygon largest = pieces.get(0);
        for (org.locationtech.jts.geom.Polygon piece : pieces) {
            totalArea += piece.getArea();
            if (piece.getArea() > largest.getArea()) largest = piece;
        }

        Polygon shrunk = new Polygon();
        shrunk.name = polygon.name + "_shrunk";
        Coordinate[] shellCoords = largest.getExteriorRing().getCoordinates();
        boolean ccw = Orientation.isCCW(shellCoords);
        int start = ccw ? 0 : shellCoords.length - 2;
        int step  = ccw ? 1 : -1;
        for (int k = 0, i = start; k < shellCoords.length - 1; k++, i += step) {
            shrunk.addPoint(new Point(shellCoords[i].x, shellCoords[i].y));
        }
        shrunk.removeInitialCollinearity();
        if (shrunk.polygonPoints.size() < 3)
            return new ShrinkResult(null, 0, 0.0, 0.0).withReason(
                "the largest eroded piece collapsed to under 3 points after removing collinear vertices");

        if (largest.getNumInteriorRing() > 0) {
            // Rare for a simple field-like input; this codebase has no concept of holes, so the
            // outer boundary is kept and the hole is dropped -- flagged for visibility.
            System.err.println("[shrinkPolygonInwardRobust] " + polygon.name +
                ": eroded region has a hole; hole ignored, outer boundary kept");
        }

        return new ShrinkResult(shrunk, pieces.size(), largest.getArea(), totalArea);
    }

    /** Result of shrinkPolygonInwardRobust(): the kept (largest) piece plus split diagnostics. */
    public static class ShrinkResult {
        public final Polygon polygon;          // largest piece, or null if nothing survived
        public final int pieceCount;           // how many disjoint pieces the erosion produced
        public final double largestPieceArea;
        public final double totalErodedArea;   // sum of ALL pieces' area (incl. discarded ones)
        public String reason = "";             // WHY polygon is null, when it is -- for diagnostics

        public ShrinkResult(Polygon polygon, int pieceCount, double largestPieceArea, double totalErodedArea) {
            this.polygon = polygon;
            this.pieceCount = pieceCount;
            this.largestPieceArea = largestPieceArea;
            this.totalErodedArea = totalErodedArea;
        }
        public ShrinkResult withReason(String r) { this.reason = r; return this; }
    }

    public static Point lineIntersection(Edge e1, Edge e2) {

        double x1 = e1.p.x, y1 = e1.p.y;
        double x2 = e1.q.x, y2 = e1.q.y;

        double x3 = e2.p.x, y3 = e2.p.y;
        double x4 = e2.q.x, y4 = e2.q.y;

        double denom = (x1-x2)*(y3-y4) - (y1-y2)*(x3-x4);

        if (Math.abs(denom) < Point.EPS)
            return null;

        double px =
            ((x1*y2 - y1*x2)*(x3-x4) - (x1-x2)*(x3*y4 - y3*x4)) / denom;

        double py =
            ((x1*y2 - y1*x2)*(y3-y4) - (y1-y2)*(x3*y4 - y3*x4)) / denom;

        return new Point(px, py);
    }

}
