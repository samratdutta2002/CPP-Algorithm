import java.util.*;

public class Graph<T> {

    private final Map<T, Set<T>> adjList;
    private final Set<EdgeGraph<T>> edges;

    public Graph() {
        adjList = new HashMap<>();
        edges = new HashSet<>();
    }

    public Graph(Graph<T> other) {

        this.adjList = new HashMap<>();
        this.edges = new HashSet<>();

        for (Map.Entry<T, Set<T>> entry : other.adjList.entrySet()) {
            this.adjList.put(entry.getKey(), new HashSet<>(entry.getValue()));
        }

        for (EdgeGraph<T> e : other.edges) {
            this.edges.add(new EdgeGraph<>(e.u, e.v));
        }
    }

    public void addVertexIfAbsent(T v) {
        if (v == null)
            throw new IllegalArgumentException("Vertex cannot be null");
        adjList.putIfAbsent(v, new HashSet<>());
    }

    public boolean containsVertex(T v) {
        return adjList.containsKey(v);
    }

    /**
     * Add edge
     */
    public void addEdge(T u, T v, boolean undirected) {

        if (u == null || v == null)
            throw new IllegalArgumentException("Vertices cannot be null");

        addVertexIfAbsent(u);
        addVertexIfAbsent(v);

        adjList.get(u).add(v);
        edges.add(new EdgeGraph<>(u, v));

        if (undirected) {
            adjList.get(v).add(u);
            edges.add(new EdgeGraph<>(v, u));
        }
    }

    public boolean containsEdge(T u, T v) {
        return adjList.containsKey(u) && adjList.get(u).contains(v);
    }

    public Set<EdgeGraph<T>> getEdges() {
        return Collections.unmodifiableSet(edges);
    }

    public Set<T> getVertices() {
        return Collections.unmodifiableSet(adjList.keySet());
    }

    public Set<T> getAdjVertices(T v) {
        return adjList.getOrDefault(v, Collections.emptySet());
    }

    public void buildFromEdges(Collection<EdgeGraph<T>> edgeList, boolean undirected) {
        for (EdgeGraph<T> e : edgeList) {
            addEdge(e.u, e.v, undirected);
        }
    }

    public int vertexCount() {
        return adjList.size();
    }

    public int edgeCount() {
        return edges.size();
    }

    public void printGraph() {
        for (T u : adjList.keySet()) {
            System.out.print(u + " → ");
            for (T v : adjList.get(u)) {
                System.out.print(v + ", ");
            }
            System.out.println();
        }
    }
}