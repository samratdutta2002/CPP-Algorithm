import java.util.*;

public class PolygonVertex {

    // ── Optional corrections (off by default so earlier results stay reproducible) ──
    // -Dfix.width=true   : build the predicted exit points with the real robot width/overlap
    //                      (PipelineRunner sets the two statics below before the graph is built)
    // -Dfix.entries=true : always map the right-hand corner when a region has two corners on its
    //                      lower/upper side (previously only mapped if the pair was out of order)
    public static double robotWidth = RunConfig.DEFAULT_ROBOT_WIDTH;
    public static double overlap    = RunConfig.DEFAULT_OVERLAP;
    static final boolean FIX_WIDTH   = Boolean.getBoolean("fix.width");
    static final boolean FIX_ENTRIES = Boolean.getBoolean("fix.entries");

    public Polygon polygon; // reference of the working polygon piece
    public List<Point> lower, upper; // entry/exit locations which can be minimum 2 and maximum 4

    public Map<Point, Point> entryExitMap; // to store every possible extry w.r.t to the exit point
    public Map<Point, Double> distMap; // to store the actual distance from every entry to respective exit points
    public double area; // stores the area of the working polygon piece

    public void printVertexDetails(){
        System.out.println(
            "Node: " + polygon.name + "\n" +
            "Upper Points: " + upper.get(0) + "\t" + ((upper.size()>1)? (""+upper.get(1)) : "") + "\n" +
            "Lower Points: " + lower.get(0) + "\t" + ((lower.size()>1)? (""+lower.get(1)) : "")
        );
        
    }

    public PolygonVertex(Polygon P){

        this.polygon = P;
        this.lower = new ArrayList<>();
        this.upper = new ArrayList<>();
        this.entryExitMap = new HashMap<>();
        this.distMap = new HashMap<>();

        // Use defaults from RunConfig; actual values are injected by PipelineRunner
        // via WayPointGenerator when real values are needed.
        double rw = FIX_WIDTH ? robotWidth : RunConfig.DEFAULT_ROBOT_WIDTH;
        double ov = FIX_WIDTH ? overlap    : RunConfig.DEFAULT_OVERLAP;
        WayPointGenerator wpg = new WayPointGenerator(this, rw, ov);

        // Compute ymin/ymax freshly from THIS region's own points, rather than trusting
        // polygon.ymin/polygon.ymax: for a decomposed sub-polygon piece, those cached bounds can
        // fail to exactly match any point here via Point.equals() (the same floating-point drift
        // between independently-computed geometry stages seen elsewhere), leaving lower/upper
        // empty. Recomputing locally guarantees at least one point matches its own true min/max.
        double localYMin = Double.POSITIVE_INFINITY, localYMax = Double.NEGATIVE_INFINITY;
        for (Point p : polygon.polygonPoints) {
            if (p.y < localYMin) localYMin = p.y;
            if (p.y > localYMax) localYMax = p.y;
        }

        for(Point p: polygon.polygonPoints){
            if(Point.equals(p.y, localYMin))
                lower.add(p);

            if(Point.equals(p.y, localYMax))
                upper.add(p);
        }

        if(lower.size() == 2 && Point.greaterThan(lower.get(0).x, lower.get(1).x)){
            Point temp = lower.get(0);
            lower.set(0, lower.get(1));
            lower.set(1, temp);

            this.entryExitMap.put(lower.get(1), wpg.predictExitPoint(lower.get(1))); // lower right to exit mapped
        }

        if(upper.size() == 2 && Point.greaterThan(upper.get(0).x, upper.get(1).x)){
            Point temp = upper.get(0);
            upper.set(0, upper.get(1));
            upper.set(1, temp);

            this.entryExitMap.put(upper.get(1), wpg.predictExitPoint(upper.get(1))); // upper right to exit mapped
        }
        if (FIX_ENTRIES) {
            if (lower.size() == 2) this.entryExitMap.put(lower.get(1), wpg.predictExitPoint(lower.get(1)));
            if (upper.size() == 2) this.entryExitMap.put(upper.get(1), wpg.predictExitPoint(upper.get(1)));
        }
        this.entryExitMap.put(lower.get(0), wpg.predictExitPoint(lower.get(0))); // lower left to exit mapped
        this.entryExitMap.put(upper.get(0), wpg.predictExitPoint(upper.get(0))); // upper left to exit mapped

        int i = 0;
        for(Point entry: entryExitMap.keySet())
            distMap.put( entry, wpg.wayPointDistance(entry, entryExitMap.get(entry)) );
        
        this.area = Math.abs(Polygon.computeArea(this.polygon));
    }



    public String toString(){
        return ("Polygon: " + polygon.name + "\nLower Points: " + lower + "\nUpper Points: " + upper);
    }

}