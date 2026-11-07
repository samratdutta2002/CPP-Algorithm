import java.util.*;

/*
    // Most important methods:-

    generateWaypoints(Point current, PolygonVertex goal)        // Generates waypoints for inter-polygon traversal that are adjacent
    generateWaypoints(Point start, Point goal, boolean debug)   // Generates waypoints when we want to traverse the polygon on the boundary
    generateWaypoints(Point start, boolean debug)               // Generates waypoints to traverse inside a polygon piece
*/

public class WayPointGenerator {

    // Parameters
    public Point start, goal;
    public List<Point> lower, upper; // entry/exit locations minimum 2 maximum 4
    public List<Edge> leftChain = null;
    public List<Edge> rightChain = null;
    public Polygon polygon = null;
    public double overlap = 0.0, robotWidth = 0.0;
    public boolean verticalDirection, horizontalDirection;

    // This region's own y-extremes, computed fresh from polygon.polygonPoints in
    // determineDirections() -- see the comment there for why these replace the cached
    // polygon.ymin/polygon.ymax everywhere in this class.
    private double localYMin, localYMax;


    double actualLength = 0.0; // delete this later

    public WayPointGenerator(PolygonVertex poly, double robotWidth, double overlap){
        this.polygon = poly.polygon;
        this.upper = poly.upper;
        this.lower = poly.lower;
        this.robotWidth = robotWidth;
        this.overlap = overlap;
    }
    public WayPointGenerator(Polygon polygon, double robotWidth, double overlap){
        this.polygon = polygon;
        this.robotWidth = robotWidth;
        this.overlap = overlap;
    }

    
    // This method is used to compute the exit point when the entry point is known
    /** Corner to use for a predicted exit: falls back to the OTHER side's matching corner
     *  (same "end", left or right) if the intended list is empty -- a decomposed region can
     *  occasionally have an empty upper or lower corner list (e.g. a triangular/degenerate cell
     *  where one side collapses to nothing), and the original code assumed both were always
     *  non-empty. Falls back to `start` itself only if both lists are genuinely empty. */
    private Point safeCorner(List<Point> primary, int idx, List<Point> fallback, int fallbackIdx) {
        if (!primary.isEmpty()) return primary.get(Math.min(idx, primary.size() - 1));
        if (!fallback.isEmpty()) {
            System.out.println("[predictExitPoint] preferred corner list was empty for this region; "
                + "using the other side's matching corner instead");
            return fallback.get(Math.min(fallbackIdx, fallback.size() - 1));
        }
        System.out.println("[predictExitPoint] both corner lists were empty for this region; "
            + "falling back to the start point");
        return new Point(start);
    }

    public Point predictExitPoint(Point start){                                                                                                     // O(1)

        this.start = start;
        determineDirections();
        
        int sweepLineCount = (int)(Math.ceil(((localYMax - localYMin)/(robotWidth*(1-overlap)))) + 1);
        Point exit;

        if(sweepLineCount%2 == 1){

            if(horizontalDirection){ 
                if(verticalDirection) // we will end at right most point in upper
                    exit = safeCorner(upper, upper.size()-1, lower, lower.size()-1);
                else // we will end at right most point in lower
                    exit = safeCorner(lower, lower.size()-1, upper, upper.size()-1);
            }

            else{ 
                if(verticalDirection) // we will end at left most point in upper
                    exit = safeCorner(upper, 0, lower, 0);
                else // we will end at left most point in lower
                    exit = safeCorner(lower, 0, upper, 0);
            }
            
        }
        else{

            if(horizontalDirection){ 
                if(verticalDirection) // we will end at left most point in upper
                    exit = safeCorner(upper, 0, lower, 0);
                else // we will end at left most point in lower
                    exit = safeCorner(lower, 0, upper, 0);
            }

            else{ 
                if(verticalDirection) // we will end at right most point in upper
                    exit = safeCorner(upper, upper.size()-1, lower, lower.size()-1);
                else // we will end at right most point in lower
                    exit = safeCorner(lower, lower.size()-1, upper, upper.size()-1);
            }

        }

        return (new Point(exit));
    }



    // These methods generate waypoints when we want to traverse the polygon on the boundary
    public double wayPointDistance(Point start, Point goal){ // this returns the distance/cost 
        List<Point> wp = generateWaypoints(start, goal, false);
        double dist = 0;
        for(int i = 0; i < wp.size()-1; i++)
            dist += Point.distance(wp.get(i), wp.get(i+1));
        return dist;
    }
    public List<Point> generateWaypoints(Point start, Point goal, boolean debug){ // this returns the waypoints                                 // O(n)

        if(Point.isSame(start, goal))
            return new ArrayList<>();

        int n = polygon.polygonPoints.size();

        // Nearest match (not exact Point.isSame()) for both start and goal -- see
        // determineDirections() above for why. Once matched to an INDEX, the traversal loop
        // below compares by index, not by point value, so it can never drift out of sync.
        int startIdx = -1, goalIdx = -1;
        double bestStart = Double.MAX_VALUE, bestGoal = Double.MAX_VALUE;
        for (int k = 0; k < n; k++) {
            Point p = polygon.polygonPoints.get(k);
            double ds = Point.distance(start, p);
            if (ds < bestStart) { bestStart = ds; startIdx = k; }
            double dg = Point.distance(goal, p);
            if (dg < bestGoal) { bestGoal = dg; goalIdx = k; }
        }

        if(startIdx == -1 || goalIdx == -1){
            if(debug){
                if(startIdx == -1)
                    System.out.println("\nStart point " + start + " is not from boundary of Shrinked Polygon");
                if(goalIdx == -1)
                    System.out.println("\nGoal point " + goal + " is not from boundary of Shrinked Polygon");
            }
            return (new ArrayList<>());
        }
        if (bestStart > 1e-6)
            System.out.println("[generateWaypoints/boundary] start " + start + " snapped to nearest polygon "
                + "point " + polygon.polygonPoints.get(startIdx) + " (distance " + bestStart + ")");
        if (bestGoal > 1e-6)
            System.out.println("[generateWaypoints/boundary] goal " + goal + " snapped to nearest polygon "
                + "point " + polygon.polygonPoints.get(goalIdx) + " (distance " + bestGoal + ")");

        if(startIdx == goalIdx){
            if(debug)
                System.out.println("\nStart " + start + " and Goal " + goal
                    + " map to the same corner so no intermediate points!");
            return (new ArrayList<>());
        }

        List<Point> wayPointLeft = new ArrayList<>();
        List<Point> wayPointRight = new ArrayList<>();

        int leftPointer = startIdx, rightPointer = startIdx;
        boolean s = false, g = false;
        double leftDist = 0.0, rightDist = 0.0;
        int i;

        while(!s || !g){

            if(!s){
                wayPointLeft.add(polygon.polygonPoints.get(leftPointer));
                if(leftPointer == goalIdx)
                    s = true; // we reached goal
                i = wayPointLeft.size();
                if(i > 1)
                    leftDist += Point.distance(wayPointLeft.get(i-1), wayPointLeft.get(i-2));
                leftPointer = (leftPointer-1 + n) % n;
            }
            if(!g){
                wayPointRight.add(polygon.polygonPoints.get(rightPointer));
                if(rightPointer == goalIdx)
                    g = true; // we reached goal
                i = wayPointRight.size();
                if(i > 1)
                    rightDist += Point.distance(wayPointRight.get(i-1), wayPointRight.get(i-2));
                rightPointer = (rightPointer+1) % n;
            }
        }

        return ((Point.lessThanEqual(leftDist, rightDist)) ? wayPointLeft : wayPointRight);
    }
    



    // This method generates the waypoints inside a polygon piece
    public double wayPointDistance(Point start){ // this returns the distance/cost 
        List<Point> wp = generateWaypoints(start, false);
        double dist = 0;
        for(int i = 0; i < wp.size()-1; i++)
            dist += Point.distance(wp.get(i), wp.get(i+1));
        return dist;
    }
    public List<Point> generateWaypoints(Point start, boolean debug){                                                                           // O(n + k)

        this.start = start;
        determineDirections();
        buildEdgeChain(debug);

        Point exit = predictExitPoint(start);


        List<Point> wayPoints = new ArrayList<>(); // initialise empty way points list
        double effectiveRobotWidth, currentHeight, channelLength, oldCurrentHeight;
        int leftPointer = 0, rightPointer = 0;

        // 1. Update robot's width based on overlap percentage
        effectiveRobotWidth = this.robotWidth * (1.0 - overlap); // this is the value at which we increment upwards/downwards based on direction
        currentHeight = start.y;

        int counter = 0;
        if(debug){
            int sweepLineCount = (int)(Math.ceil(((localYMax - localYMin)/effectiveRobotWidth)) + 1);
            System.out.println("Start Point: " + this.start);
            System.out.println("Vertical Direction: " + ((verticalDirection) ? "Low To Up" : "Up To Low"));
            System.out.println("Horizontal Direction: " + ((horizontalDirection) ? "Left To Right" : "Right To Left"));
            System.out.println("Robot Width: " + this.robotWidth);
            System.out.println("Effective Robot Width: " + effectiveRobotWidth);
            System.out.println("Predicted Sweep line count: " + sweepLineCount);
            System.out.println("Predicted Exit Point: " + exit);
            System.out.println("\nLeft Chain:");
            for(Edge e: leftChain)
                System.out.println(e);
            System.out.println("Right Chain:");
            for(Edge e: rightChain)
                System.out.println(e);
        }


        // If start belongs to lower/upper list and the list has only one point then we need to start from the next sweep line
        if(verticalDirection && lower.size() == 1){ // start belongs to lower and lower has only one point
            wayPoints.add(new Point(start));
            currentHeight = start.y + effectiveRobotWidth;
            horizontalDirection = !horizontalDirection;
            counter++;
            //System.out.println("The Lower start point " + start + " is single, so sweep starts from after " + effectiveRobotWidth + " units at " + currentHeight);
        }
        else if(!verticalDirection && upper.size() == 1){ // start belongs to upper and upper has only 1 point
            wayPoints.add(new Point(start));
            currentHeight = start.y - effectiveRobotWidth;
            horizontalDirection = !horizontalDirection;
            counter++;
            //System.out.println("The Upper start point " + start + " is single, so sweep starts from after " + effectiveRobotWidth + " units at " + currentHeight);
        }
    

        while( ((verticalDirection)? (Point.lessThanEqual(currentHeight, localYMax)) : (Point.greaterThanEqual(currentHeight, localYMin))) ){

            counter++;
            
            // Find left and right intersection
            Edge eLeft = leftChain.get(leftPointer);
            Edge eRight = rightChain.get(rightPointer);

            // Update left and right intersection if needed
            
            if(verticalDirection){ // Lower To Upper

                if(! (Point.greaterThanEqual(currentHeight, eLeft.p.y) && Point.lessThanEqual(currentHeight, eLeft.q.y) && !Point.equals(eLeft.p.y, eLeft.q.y)) ){

                    while(! (Point.greaterThanEqual(currentHeight, eLeft.p.y) && Point.lessThanEqual(currentHeight, eLeft.q.y) && !Point.equals(eLeft.p.y, eLeft.q.y)) ){
                        if (leftPointer + 1 >= leftChain.size()) { // defence-in-depth: localYMax/localYMin should
                            System.out.println("[generateWaypoints] left chain exhausted while sweeping upward "
                                + "at height " + currentHeight + " -- stopping this sweep line early instead of "
                                + "throwing an IndexOutOfBoundsException");
                            break;
                        }
                        eLeft = leftChain.get(++leftPointer); // if not "eLeft.p.y <= currentHeight <= eLeft.q.y" (or eLeft is horizontal --
                                                               // a sweep line can't "intersect" a same-height edge at one point, skip it)

                        if(horizontalDirection) // left to right
                            wayPoints.add( new Point(eLeft.p) );
                    }
                }

                if(! (Point.greaterThanEqual(currentHeight, eRight.p.y) && Point.lessThanEqual(currentHeight, eRight.q.y) && !Point.equals(eRight.p.y, eRight.q.y)) ){

                    while(! (Point.greaterThanEqual(currentHeight, eRight.p.y) && Point.lessThanEqual(currentHeight, eRight.q.y) && !Point.equals(eRight.p.y, eRight.q.y)) ){
                        if (rightPointer + 1 >= rightChain.size()) {
                            System.out.println("[generateWaypoints] right chain exhausted while sweeping upward "
                                + "at height " + currentHeight + " -- stopping this sweep line early instead of "
                                + "throwing an IndexOutOfBoundsException");
                            break;
                        }
                        eRight = rightChain.get(++rightPointer); // if not "eRight.p.y <= currentHeight <= eRight.q.y" (or horizontal, see above)

                        if(!horizontalDirection)
                            wayPoints.add( new Point(eRight.p) );
                    }
                }

            }
            else{ // Upper To Lower

                if(! (Point.lessThanEqual(currentHeight, eLeft.p.y) && Point.greaterThanEqual(currentHeight, eLeft.q.y) && !Point.equals(eLeft.p.y, eLeft.q.y)) ){
                    while(! (Point.lessThanEqual(currentHeight, eLeft.p.y) && Point.greaterThanEqual(currentHeight, eLeft.q.y) && !Point.equals(eLeft.p.y, eLeft.q.y)) ){
                        if (leftPointer + 1 >= leftChain.size()) {
                            System.out.println("[generateWaypoints] left chain exhausted while sweeping downward "
                                + "at height " + currentHeight + " -- stopping this sweep line early instead of "
                                + "throwing an IndexOutOfBoundsException");
                            break;
                        }
                        eLeft = leftChain.get(++leftPointer); // if not "eLeft.p >= currentHeight >= eLeft.q" (or horizontal, see above)
                        if(horizontalDirection)
                            wayPoints.add( new Point(eLeft.p) );
                    }
                }

                if(! (Point.lessThanEqual(currentHeight, eRight.p.y) && Point.greaterThanEqual(currentHeight, eRight.q.y) && !Point.equals(eRight.p.y, eRight.q.y)) ){
                    while(! (Point.lessThanEqual(currentHeight, eRight.p.y) && Point.greaterThanEqual(currentHeight, eRight.q.y) && !Point.equals(eRight.p.y, eRight.q.y)) ){
                        if (rightPointer + 1 >= rightChain.size()) {
                            System.out.println("[generateWaypoints] right chain exhausted while sweeping downward "
                                + "at height " + currentHeight + " -- stopping this sweep line early instead of "
                                + "throwing an IndexOutOfBoundsException");
                            break;
                        }
                        eRight = rightChain.get(++rightPointer); // if not "eRight.p >= currentHeight >= eRight.q" (or horizontal, see above)
                        if(!horizontalDirection)
                            wayPoints.add( new Point(eRight.p) );
                    }
                }

            }

            //System.out.println("\nFinding intersection point from y = " + currentHeight + " to\nLeft Edge: " + eLeft + "\nRight Edge: " + eRight);
            Point left = Point.intersectionPoint(eLeft, currentHeight);
            Point right = Point.intersectionPoint(eRight, currentHeight);

            channelLength = right.x - left.x;
            if(Point.greaterThan(effectiveRobotWidth, channelLength)){ // we should skip this sweep line
                wayPoints.add( new Point((left.x + right.x)/2.0, (left.y + right.y)/2.0) ); // we add the sweep line's mid point
            }
            else{
                // Add the left & right points in the correct order inside list waypoints
                if(horizontalDirection){ // Left To Right
                    wayPoints.add(left);
                    wayPoints.add(right);
                }
                else{ // Right To Left
                    wayPoints.add(right);
                    wayPoints.add(left);
                }

                horizontalDirection = !horizontalDirection;
            }            

            // Update current height
            if(verticalDirection){ // Low To Up

                if(Point.equals(currentHeight, localYMax))
                    break;

                oldCurrentHeight = currentHeight;
                currentHeight += effectiveRobotWidth;
                // Check if clipping is needed
                if( Point.greaterThan(currentHeight, localYMax) ){
                    currentHeight = localYMax; // clipped to allowed maximum
                    System.out.println("Upper clipping done");
                }

                // donot create sweep line if lower has only single point
                if(Point.equals(currentHeight, localYMax) && upper.size() == 1){
                    currentHeight = oldCurrentHeight;
                    if(!horizontalDirection){ // add all p points from left edge to wayPoints
                        while(leftPointer < leftChain.size()-1){
                            eLeft = leftChain.get(++leftPointer); // if not "eLeft.p >= currentHeight >= eLeft.q"
                            wayPoints.add( new Point(eLeft.p) );
                        }
                        eLeft = leftChain.get(leftPointer); // if not "eLeft.p >= currentHeight >= eLeft.q"
                        wayPoints.add( new Point(eLeft.q) );
                    }
                    else{
                        while(rightPointer < rightChain.size()-1){
                            eRight = rightChain.get(++rightPointer);
                            wayPoints.add( new Point(eRight.p) );
                        }
                        eRight = rightChain.get(rightPointer);
                        wayPoints.add( new Point(eRight.q) );
                    }
                    break;
                }

            }
            else{ // Up To Low

                if(Point.equals(currentHeight, localYMin))
                    break;

                oldCurrentHeight = currentHeight;
                currentHeight -= effectiveRobotWidth;
                // Check if clipping is needed
                if( Point.lessThan(currentHeight, localYMin) ){
                    currentHeight = localYMin; // clipped to allowed minimum
                    System.out.println("Lower clipping done");
                }

                // donot create sweep line if lower has only single point
                if(Point.equals(currentHeight, localYMin) && lower.size() == 1){
                    currentHeight = oldCurrentHeight;
                    if(horizontalDirection){ // add all p points from left edge to wayPoints
                        while(leftPointer < leftChain.size()-1){
                            eLeft = leftChain.get(++leftPointer); // if not "eLeft.p >= currentHeight >= eLeft.q"
                            wayPoints.add( new Point(eLeft.p) );
                        }
                        eLeft = leftChain.get(leftPointer);
                        wayPoints.add( new Point(eLeft.q) );
                    }
                    else{
                        while(rightPointer < rightChain.size()-1){
                            eRight = rightChain.get(++rightPointer); // if not "eRight.p >= currentHeight >= eRight.q"
                            wayPoints.add( new Point(eRight.p) );
                        }
                        eRight = rightChain.get(rightPointer); // if not "eRight.p >= currentHeight >= eRight.q"
                        wayPoints.add( new Point(eRight.q) );
                    }
                    break;
                }

            }

        }

        double len = 0.0;
        int i;
        for(i = 0; i < wayPoints.size()-1; i++)
            len += Point.distance(wayPoints.get(i), wayPoints.get(i+1));
        actualLength = len;
        if(debug){
            System.out.println("\nActual Sweep Line Count: " + counter);
            System.out.println("Actual Exit Point: " + wayPoints.get(wayPoints.size()-1));
            System.out.println("Acutal Distance: " + len);
            System.out.println("Total number of waypoints: " + wayPoints.size() + "\n");
        }
        return wayPoints;
    }



    // These methods are used for inter-polygon traversal that are adjacent
    public Point adjacentPolygonEntryPoint(Point current, PolygonVertex goal){ // this method returns the entry point for adjacent 'goal' polygon
        List<Point> wp = generateWaypoints(current, goal);
        return (wp.get(wp.size()-1));
    }
    public double wayPointDistance(Point p, PolygonVertex goal){ // this returns the distance/cost 
        List<Point> wp = generateWaypoints(p, goal);
        double dist = 0;
        for(int i = 0; i < wp.size()-1; i++)
            dist += Point.distance(wp.get(i), wp.get(i+1));
        return dist;
    }
    private boolean haveReached(Point p, PolygonVertex goal){ // 'goal' polygon has min 2 and max 4 points which sould be the entry point
        // Nearest match within a tight tolerance, not exact Point.isSame() -- same reasoning as
        // determineDirections() above: p and goal's corners can be computed by different
        // geometric stages and differ by a tiny floating-point amount for the same corner.
        double best = Double.MAX_VALUE;
        for (Point c : goal.lower) best = Math.min(best, Point.distance(p, c));
        for (Point c : goal.upper) best = Math.min(best, Point.distance(p, c));
        return best < 1e-6;
    }
    public List<Point> generateWaypoints(Point current, PolygonVertex goal){                                                                        // O(n)
          
        int i, leftPointer, rightPointer, n = polygon.polygonPoints.size();
        boolean s, g;

        // "Are we already at the goal?" -- nearest match against goal's (up to 4) boundary
        // corners within a tight tolerance, not an exact Point.isSame(). Same reasoning as
        // determineDirections() above: `current` can come from a different geometric stage
        // (the JTS-based shrink, or another region's own exit point) and differ from the
        // "same" corner by a tiny floating-point amount.
        double bestGoalDist = Double.MAX_VALUE;
        for (Point c : goal.lower) bestGoalDist = Math.min(bestGoalDist, Point.distance(current, c));
        for (Point c : goal.upper) bestGoalDist = Math.min(bestGoalDist, Point.distance(current, c));
        if (bestGoalDist < 1e-6)
            return (new ArrayList<>());

        // Finding the index of current point -- nearest match, not exact (same reasoning).
        double best = Double.MAX_VALUE;
        i = -1;
        for (int k = 0; k < n; k++) {
            double d = Point.distance(current, polygon.polygonPoints.get(k));
            if (d < best) { best = d; i = k; }
        }
        if (i == -1) {
            System.out.println("\nPoint " + current + " is not from boundary of Shrinked Polygon");
            return (new ArrayList<>());
        }
        if (best > 1e-6)
            System.out.println("[generateWaypoints/traversal] " + current + " snapped to nearest polygon "
                + "point " + polygon.polygonPoints.get(i) + " (distance " + best + ")");

        leftPointer = i;
        rightPointer = i;
        List<Point> wayPointLeft = new ArrayList<>();
        List<Point> wayPointRight = new ArrayList<>();

        s = false;
        g = false;
        double leftDist = 0.0, rightDist = 0.0;

        while(!s || !g){

            if(!s){

                Point left = polygon.polygonPoints.get(leftPointer);
                wayPointLeft.add(new Point(left));

                if(haveReached(left, goal)) // we reached goal
                    s = true; 

                i = wayPointLeft.size();
                if(i > 1)
                    leftDist += Point.distance(wayPointLeft.get(i-1), wayPointLeft.get(i-2));
                leftPointer = (leftPointer-1 + n) % n;
            }
            if(!g){
                Point right = polygon.polygonPoints.get(rightPointer);
                wayPointRight.add(new Point(right));

                if(haveReached(right, goal)) // we reached goal
                    g = true; 

                i = wayPointRight.size();
                if(i > 1)
                    rightDist += Point.distance(wayPointRight.get(i-1), wayPointRight.get(i-2));
                rightPointer = (rightPointer+1) % n;
            }
        }

        return ((Point.lessThanEqual(leftDist, rightDist)) ? wayPointLeft : wayPointRight);
    }



    

    public void determineDirections(){                                                                                                              // O(1)

        // Compute this region's own y-extremes fresh from its own points, rather than trusting
        // the cached polygon.ymin/polygon.ymax fields: for a decomposed sub-polygon piece those
        // cached bounds can fail to exactly match any point here via Point.equals() (the same
        // floating-point drift between independently-computed geometry stages fixed in
        // PolygonVertex's constructor). Every use of polygon.ymin/polygon.ymax in this class was
        // replaced with these -- buildEdgeChain()'s do/while loops could otherwise run forever
        // (OutOfMemoryError, since fixed), and generateWaypoints()'s sweep loop could otherwise
        // run one step past what leftChain/rightChain actually cover (IndexOutOfBoundsException).
        // determineDirections() is called at the top of both generateWaypoints() and
        // predictExitPoint(), and buildEdgeChain() is only ever called right after it, so these
        // are always current before anything below reads them.
        localYMin = Double.POSITIVE_INFINITY;
        localYMax = Double.NEGATIVE_INFINITY;
        for (Point p : polygon.polygonPoints) {
            if (p.y < localYMin) localYMin = p.y;
            if (p.y > localYMax) localYMax = p.y;
        }

        // Match `start` to the NEAREST of the (up to 4) reference corners, rather than requiring
        // an exact Point.isSame() match. `start` can be computed by a different part of the
        // pipeline (the JTS-based inward shrink, or an adjacent region's exit point) using its own
        // floating-point arithmetic, so it can differ from these corners by a tiny amount even
        // though it represents, geometrically, the same corner. The snap only affects which
        // branch is taken below -- the literal `start` coordinate is still what gets emitted as a
        // waypoint, so this never moves the reported path.
        Point matched = null;
        boolean isLower = false;
        double best = Double.MAX_VALUE;
        for (Point c : lower) {
            double d = Point.distance(start, c);
            if (d < best) { best = d; matched = c; isLower = true; }
        }
        for (Point c : upper) {
            double d = Point.distance(start, c);
            if (d < best) { best = d; matched = c; isLower = false; }
        }
        if (matched == null) {
            System.out.println("\nStart Point " + start + " does not belong to any corner points!\n");
            return;
        }
        if (best > 1e-6)
            System.out.println("[determineDirections] start " + start + " snapped to nearest corner "
                + matched + " (distance " + best + ")");

        if (isLower) {
            verticalDirection = true; // lower to upper

            if (lower.size() == 2) {
                if (start.x < lower.get(0).x || start.x < lower.get(1).x)
                    horizontalDirection = true; // Left To Right
                else
                    horizontalDirection = false; // Right To Left
            }
            else{ // start point is single so direction can be both true and false
                horizontalDirection = true; // Left to Right        Biased decision taken
            }
        }
        else{
            verticalDirection = false; // upper to lower

            if (upper.size() == 2) {
                if (start.x < upper.get(0).x || start.x < upper.get(1).x)
                    horizontalDirection = true; // Left To Right
                else
                    horizontalDirection = false; // Right To Left
            }
            else{ // start point is single so direction can be both true and false
                horizontalDirection = true; // Left to Right        Biased decision taken
            }
        }

    }
    
    public static boolean isEdgeBetween(Edge e, double ymin, double ymax){                                                                          // O(1)
        // If edge is on line y = ymin OR y = ymax, then return false
        if(
            (Point.equals(e.p.y, ymin) && Point.equals(e.q.y, ymin)) ||
            (Point.equals(e.p.y, ymax) && Point.equals(e.q.y, ymax))
        )
            return false;

        // Condition 1: ymin <= e.p.y <= ymax
        // Condition 2: ymin <= e.q.y <= ymax
        return( 
            Point.greaterThanEqual(e.p.y, ymin) && Point.lessThanEqual(e.p.y, ymax) &&
            Point.greaterThanEqual(e.q.y, ymin) && Point.lessThanEqual(e.q.y, ymax)
        );
    }

    public void buildEdgeChain(boolean debug){                                                                                                      // O(n)

        leftChain = new ArrayList<>();
        rightChain = new ArrayList<>();

        // Find the index of the polygon corner NEAREST to start (not an exact match -- see the
        // comment in determineDirections() above for why). Only the index matters here; the
        // literal `start` value used elsewhere is left untouched.
        int i = -1;
        double best = Double.MAX_VALUE;
        for (int k = 0; k < polygon.polygonPoints.size(); k++) {
            double d = Point.distance(this.start, polygon.polygonPoints.get(k));
            if (d < best) { best = d; i = k; }
        }

        if (i == -1) {
            if(debug)
                System.out.println("\nStart Point " + this.start + " does not belong to any corner points!\n");
            return;
        }
        if (best > 1e-6)
            System.out.println("[buildEdgeChain] start " + this.start + " snapped to nearest polygon point "
                + polygon.polygonPoints.get(i) + " (distance " + best + ")");

        // localYMin/localYMax were already computed fresh for this region by determineDirections()
        // (always called immediately before this method) -- see the comment there.

        // We have the start point and we also know if its in upper part or lower
        int leftPointer = i, rightPointer = i;
        int leftPrev, rightPrev;
        int maxSteps = polygon.polygonPoints.size(); // a full lap can never need more edges than this

        if(verticalDirection){ // start belongs to lower

            int steps = 0;
            do{
                leftPrev = leftPointer;
                leftPointer = (leftPointer-1 + polygon.polygonPoints.size()) % polygon.polygonPoints.size();
                // Left_Edge: Edge(leftPrev, leftPointer)
                Edge eLeft = new Edge((new Point(polygon.polygonPoints.get(leftPrev))), (new Point(polygon.polygonPoints.get(leftPointer))));
                if(isEdgeBetween(eLeft, localYMin, localYMax))
                    leftChain.add(eLeft);
                if (++steps > maxSteps) {
                    System.out.println("[buildEdgeChain] left chain (lower) did not reach ymax after a full lap"
                        + " -- stopping early to avoid an unbounded loop");
                    break;
                }
            }
            while( !Point.equals( polygon.polygonPoints.get(leftPointer).y, localYMax ) );
    
            steps = 0;
            do{
                rightPrev = rightPointer;
                rightPointer = (rightPointer+1) % polygon.polygonPoints.size();
                // Right_Edge: Edge(rightPrev, rightPointer)
                Edge eRight = new Edge((new Point(polygon.polygonPoints.get(rightPrev))), (new Point(polygon.polygonPoints.get(rightPointer))));
                if(isEdgeBetween(eRight, localYMin, localYMax))
                    rightChain.add(eRight);
                if (++steps > maxSteps) {
                    System.out.println("[buildEdgeChain] right chain (lower) did not reach ymax after a full lap"
                        + " -- stopping early to avoid an unbounded loop");
                    break;
                }
            }
            while( !Point.equals( polygon.polygonPoints.get(rightPointer).y, localYMax ) );
            
        }

        else{ // start belongs to upper

            int steps = 0;
            do{
                leftPrev = leftPointer;
                leftPointer = (leftPointer+1) % polygon.polygonPoints.size();
                // Left_Edge: Edge(leftPrev, leftPointer)
                Edge eLeft = new Edge((new Point(polygon.polygonPoints.get(leftPrev))), (new Point(polygon.polygonPoints.get(leftPointer))));
                if(isEdgeBetween(eLeft, localYMin, localYMax))
                    leftChain.add(eLeft);
                if (++steps > maxSteps) {
                    System.out.println("[buildEdgeChain] left chain (upper) did not reach ymin after a full lap"
                        + " -- stopping early to avoid an unbounded loop");
                    break;
                }
            }
            while( !Point.equals( polygon.polygonPoints.get(leftPointer).y, localYMin ) );

            steps = 0;
            do{
                rightPrev = rightPointer;
                rightPointer = (rightPointer-1 + polygon.polygonPoints.size()) % polygon.polygonPoints.size();
                // Left_Edge: Edge(leftPrev, leftPointer)
                Edge eRight = new Edge((new Point(polygon.polygonPoints.get(rightPrev))), (new Point(polygon.polygonPoints.get(rightPointer))));
                if(isEdgeBetween(eRight, localYMin, localYMax))
                    rightChain.add(eRight);
                if (++steps > maxSteps) {
                    System.out.println("[buildEdgeChain] right chain (upper) did not reach ymin after a full lap"
                        + " -- stopping early to avoid an unbounded loop");
                    break;
                }
            }
            while( !Point.equals( polygon.polygonPoints.get(rightPointer).y, localYMin ) );
            
        }
    }










    public static List<Point> transformWaypoints(
            List<Point> waypoints,
            double degree,
            Point centroid,
            Point translationOffset
    ) {

        List<Point> original = new ArrayList<>();

        // Inverse rotation → anticlockwise
        double theta = Math.toRadians(degree);
        double cos = Math.cos(theta);
        double sin = Math.sin(theta);

        double cx = centroid.x;
        double cy = centroid.y;

        double shiftX = translationOffset.x;
        double shiftY = translationOffset.y;

        for (Point p : waypoints) {

            // 🔥 Step 1: undo translation
            double x1 = p.x - shiftX;
            double y1 = p.y - shiftY;

            // 🔥 Step 2: undo rotation
            double dx = x1 - cx;
            double dy = y1 - cy;

            double origX = cx + dx * cos - dy * sin;
            double origY = cy + dx * sin + dy * cos;

            original.add(new Point(origX, origY));
        }

        return original;
    }

}