import java.util.Objects;

public class EdgeGraph<T> {

    public final T u;
    public final T v;

    public EdgeGraph(T u, T v) {
        this.u = u;
        this.v = v;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EdgeGraph<?>)) return false;
        EdgeGraph<?> e = (EdgeGraph<?>) o;
        return Objects.equals(u, e.u) &&
               Objects.equals(v, e.v);
    }

    @Override
    public int hashCode() {
        return Objects.hash(u, v);
    }

    @Override
    public String toString() {
        return u + " -> " + v;
    }
}