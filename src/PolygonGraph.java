import java.util.*;

public class PolygonGraph {

    public Graph<PolygonVertex> G;

    public PolygonGraph(List<Polygon> decomposedPolygons, Polygon polygon, boolean debug){

        G = new Graph<PolygonVertex>();

        // 1. Convert each polygon into a vertex of the Graph
        for(Polygon P: decomposedPolygons)
            G.addVertexIfAbsent(new PolygonVertex(P));
        
        if(debug){
            System.out.println("\nThe new Edges from HSV are:-");
            for(Edge E: polygon.newEdges)
                System.out.println(E);
        }

        // 2. Link polygonVertex using edges
        /*
        For each new edge E inserted into original polygon do:
            Set neighList = null;
            for each polygon P in decomposedPolygons do:
                if E is a part of P then
                    neighList.add(P);
            
            for all polygons P' in neighList, cast edges amongst them
        */
        for(Edge E: polygon.newEdges){

            List<PolygonVertex> neighList = new ArrayList<>(); // stores adjacent polygons associated with edge E

            for(PolygonVertex P: G.getVertices()){
                
                // Check is polygon P is associatd with edge E
                for(Edge e: P.polygon.polygonEdges){
                    if(Edge.edgeOverlap(E, e)){
                        neighList.add(P);
                        break;
                    }
                }

            }

            // Add edges amongst all polygons in neighList
            if(neighList.size() == 2){  
                G.addEdge(neighList.get(0), neighList.get(1), true);
            }
            else{
                System.out.println("\n!!!!!!!!!!! Edge " + E + " is associated with more than 2 polygons !!!!!!!!!!!\n");
                System.out.println("HVS Egde " + E + " has neighbour polygons: " + neighList);
            }

        }

        if(debug){
            System.out.println();
            printGraph();
            GraphGUI.visualise(G);
        }


        
    }

    public void printGraph() {
        System.out.println("Adjacency Graph");
        for (PolygonVertex u : G.getVertices()) {
            System.out.print(u.polygon.name + " → ");
            for (PolygonVertex v : G.getAdjVertices(u))
                System.out.print(v.polygon.name + ", ");
            System.out.println();
        }
    }

}