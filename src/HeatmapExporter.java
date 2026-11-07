import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * HeatmapExporter — headless (java.awt / BufferedImage only, no JavaFX
 * toolkit required) port of CppGuiApp.buildHeatmapImage(), producing a PNG
 * with a coverage-density heatmap, a green start pointer and a red end
 * pointer. No coordinate axes and no waypoint lines are drawn, per spec.
 */
public class HeatmapExporter {

    private static final int PPU = 20; // pixels per world unit — matches interactive default
    private static final int PAD = 2;

    public static void export(File pngFile, Polygon poly, List<Point> waypoints,
                               double robotWidth) throws IOException {

        if (waypoints == null || waypoints.size() < 2) {
            BufferedImage blank = new BufferedImage(200, 120, BufferedImage.TYPE_INT_ARGB);
            ImageIO.write(blank, "png", pngFile);
            return;
        }

        double radius = robotWidth / 2.0;
        double xMin = poly.xmin, xMax = poly.xmax, yMin = poly.ymin, yMax = poly.ymax;

        int W = Math.max(1, (int) Math.ceil((xMax - xMin) * PPU) + 2 * PAD);
        int H = Math.max(1, (int) Math.ceil((yMax - yMin) * PPU) + 2 * PAD);

        // ── Distance-gated stamp list (matches spacing = radius) ─────────
        double spacing = Math.max(radius, 1e-6);
        List<double[]> stamps = new ArrayList<>();
        stamps.add(new double[]{waypoints.get(0).x, waypoints.get(0).y});
        double acc = 0.0;
        Point prev = waypoints.get(0);
        for (int idx = 1; idx < waypoints.size(); idx++) {
            Point cur = waypoints.get(idx);
            double dx = cur.x - prev.x, dy = cur.y - prev.y;
            double seg = Math.hypot(dx, dy);
            if (seg < 1e-9) { prev = cur; continue; }
            double da = spacing - acc;
            while (da <= seg) {
                double ff = da / seg;
                stamps.add(new double[]{prev.x + dx * ff, prev.y + dy * ff});
                da += spacing;
            }
            acc = seg - (da - spacing);
            prev = cur;
        }
        stamps.add(new double[]{waypoints.get(waypoints.size() - 1).x, waypoints.get(waypoints.size() - 1).y});

        // ── Rasterise circular discs (depth = overlap count) ──────────────
        float[] depth = new float[W * H];
        double pr = radius * PPU, pr2 = pr * pr;
        for (double[] s : stamps) {
            double pcx = (s[0] - xMin) * PPU + PAD;
            double pcy = H - (s[1] - yMin) * PPU - PAD;
            int x0 = Math.max(0, (int) Math.floor(pcx - pr));
            int x1 = Math.min(W - 1, (int) Math.ceil(pcx + pr));
            int y0 = Math.max(0, (int) Math.floor(pcy - pr));
            int y1 = Math.min(H - 1, (int) Math.ceil(pcy + pr));
            for (int py = y0; py <= y1; py++) {
                for (int px = x0; px <= x1; px++) {
                    double ddx = (px + 0.5) - pcx, ddy = (py + 0.5) - pcy;
                    if (ddx * ddx + ddy * ddy <= pr2) depth[py * W + px] += 1f;
                }
            }
        }

        // ── Polygon mask ────────────────────────────────────────────────
        boolean[] mask = new boolean[W * H];
        for (int py = 0; py < H; py++) {
            for (int px = 0; px < W; px++) {
                double wx2 = (px + 0.5) / PPU + xMin - (double) PAD / PPU;
                double wy2 = (H - py - 0.5) / PPU + yMin - (double) PAD / PPU;
                mask[py * W + px] = pointInPoly(wx2, wy2, poly);
            }
        }

        float vmax = 1f;
        for (int i = 0; i < W * H; i++) if (mask[i] && depth[i] > vmax) vmax = depth[i];

        float sigma = Math.max(0.5f, (float) (robotWidth * PPU / 6.0));
        float[] smooth = gaussianBlur(depth, W, H, sigma);
        for (int i = 0; i < W * H; i++) if (depth[i] == 0f) smooth[i] = 0f;

        // ── Rasterise into a BufferedImage ────────────────────────────────
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        for (int py = 0; py < H; py++) {
            for (int px = 0; px < W; px++) {
                int idx = py * W + px;
                int argb;
                if (!mask[idx] || smooth[idx] <= 0f) {
                    argb = 0x00000000;
                } else {
                    float t = Math.min(1f, smooth[idx] / vmax);
                    argb = heatColor(t);
                }
                img.setRGB(px, py, argb);
            }
        }

        // ── Start / end pointers only (no axes, no waypoint lines) ───────
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        Point startWorld = waypoints.get(0);
        Point endWorld = waypoints.get(waypoints.size() - 1);
        double[] startPx = worldToPixel(startWorld, xMin, yMin, H);
        double[] endPx = worldToPixel(endWorld, xMin, yMin, H);

        drawPointer(g, startPx[0], startPx[1], new Color(0x1D, 0x7A, 0x3C), "S");
        drawPointer(g, endPx[0], endPx[1], new Color(0xC0, 0x39, 0x2B), "E");

        g.dispose();

        File parent = pngFile.getParentFile();
        if (parent != null) parent.mkdirs();
        ImageIO.write(img, "png", pngFile);
    }

    private static double[] worldToPixel(Point p, double xMin, double yMin, int H) {
        double px = (p.x - xMin) * PPU + PAD;
        double py = H - (p.y - yMin) * PPU - PAD;
        return new double[]{px, py};
    }

    private static void drawPointer(Graphics2D g, double cx, double cy, Color color, String label) {
        double r = 7.0;
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(2.5f));
        g.draw(new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r));
        g.setColor(color);
        g.fill(new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r));

        // small downward-pointing tag under the marker for extra visibility
        Path2D tag = new Path2D.Double();
        tag.moveTo(cx - 4, cy + r - 1);
        tag.lineTo(cx + 4, cy + r - 1);
        tag.lineTo(cx, cy + r + 7);
        tag.closePath();
        g.fill(tag);

        g.setColor(Color.WHITE);
        g.setFont(g.getFont().deriveFont(java.awt.Font.BOLD, 9f));
        java.awt.FontMetrics fm = g.getFontMetrics();
        int tw = fm.stringWidth(label);
        g.drawString(label, (float) (cx - tw / 2.0), (float) (cy + fm.getAscent() / 2.0 - 1));
    }

    private static boolean pointInPoly(double px, double py, Polygon poly) {
        List<Point> pts = poly.polygonPoints;
        int n = pts.size();
        boolean in = false;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double xi = pts.get(i).x, yi = pts.get(i).y, xj = pts.get(j).x, yj = pts.get(j).y;
            if (((yi > py) != (yj > py)) && (px < (xj - xi) * (py - yi) / (yj - yi) + xi)) in = !in;
        }
        return in;
    }

    /** 9-stop colormap identical to CppGuiApp.heatColor(), packed as ARGB int. */
    private static int heatColor(float t) {
        if (t <= 0f) return 0x00000000;
        float[] stops = {0.001f, 0.120f, 0.280f, 0.430f, 0.580f, 0.720f, 0.850f, 1.000f};
        int[][] rgb = {
            {0, 46, 166}, {0, 115, 230}, {0, 199, 184}, {26, 204, 51},
            {166, 224, 0}, {255, 191, 0}, {255, 77, 0}, {204, 0, 0},
        };
        if (t >= 1f) return argb(255, 204, 0, 0);
        int i = 0;
        while (i < stops.length - 1 && t > stops[i + 1]) i++;
        float lo = (i == 0) ? 0.001f : stops[i];
        float hi = stops[Math.min(i + 1, stops.length - 1)];
        float frac = (hi > lo) ? (t - lo) / (hi - lo) : 0f;
        frac = Math.max(0f, Math.min(1f, frac));
        int[] c0 = rgb[i];
        int[] c1 = rgb[Math.min(i + 1, rgb.length - 1)];
        int r = clamp255((int) (c0[0] + (c1[0] - c0[0]) * frac));
        int g = clamp255((int) (c0[1] + (c1[1] - c0[1]) * frac));
        int b = clamp255((int) (c0[2] + (c1[2] - c0[2]) * frac));
        return argb(255, r, g, b);
    }

    private static int clamp255(int v) { return Math.max(0, Math.min(255, v)); }
    private static int argb(int a, int r, int g, int b) { return (a << 24) | (r << 16) | (g << 8) | b; }

    /** Gaussian blur approximated by 3 sequential box blurs (Berry 2004). */
    private static float[] gaussianBlur(float[] src, int W, int H, float sigma) {
        int r = Math.max(1, (int) Math.round((Math.sqrt(12.0 * sigma * sigma / 3.0 + 1.0) - 1.0) / 2.0));
        float[] a = src.clone();
        for (int pass = 0; pass < 3; pass++) a = boxBlurPass(a, W, H, r);
        return a;
    }

    private static float[] boxBlurPass(float[] src, int W, int H, int r) {
        float[] tmp = new float[W * H], dst = new float[W * H];
        for (int y = 0; y < H; y++) {
            float s = 0; int cnt = 0;
            for (int k = 0; k <= r && k < W; k++) { s += src[y * W + k]; cnt++; }
            for (int x = 0; x < W; x++) {
                tmp[y * W + x] = s / cnt;
                int xr = x + r + 1, xl = x - r;
                if (xr < W) { s += src[y * W + xr]; cnt++; }
                if (xl >= 0) { s -= src[y * W + xl]; cnt--; }
            }
        }
        for (int x = 0; x < W; x++) {
            float s = 0; int cnt = 0;
            for (int k = 0; k <= r && k < H; k++) { s += tmp[k * W + x]; cnt++; }
            for (int y = 0; y < H; y++) {
                dst[y * W + x] = s / cnt;
                int yr = y + r + 1, yl = y - r;
                if (yr < H) { s += tmp[yr * W + x]; cnt++; }
                if (yl >= 0) { s -= tmp[yl * W + x]; cnt--; }
            }
        }
        return dst;
    }
}
