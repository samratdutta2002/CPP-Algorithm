import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;

public class PolygonVisualizer extends JPanel {

    private Polygon originalPolygon;
    private List<Polygon> decomposedPolygons;

    private double zoom    = 1.0;
    private double offsetX = 0, offsetY = 0;

    private double         initialScale  = -1;
    private Point2D.Double initialOffset = null;

    private int    hoveredIndex     = -1;
    private double hoveredArea      = 0.0;
    private double hoveredPerimeter = 0.0;

    private boolean showOriginal = true;
    private boolean showGrid     = true;
    private boolean showVertices = true;

    private final Map<Integer, Boolean>        perPolyVertexVisible = new HashMap<>();
    private final Map<String, Point2D.Double>  manualLabelPos       = new HashMap<>();

    private String  draggedLabelKey = null;
    private double  dragOffsetX     = 0, dragOffsetY = 0;

    private int labelFontSize = 13;
    private int nameFontSize  = 14;

    private static final int    DOT_SIZE                = 7;
    private static final int    FINE_GRID_SPACING_PX    = 25;
    private static final double MAJOR_GRID_SPACING_WORLD = 10.0;
    private static final int    LABEL_MIN_OFFSET        = 18;
    private static final int    COLLISION_NUDGE         = 6;
    private static final int    MAX_ITER                = 40;
    private static final int    PAD                     = 4;

    private static final Color[] COLORS = {
            new Color(255,   0,   0, 100), new Color(  0, 255,   0, 100),
            new Color(  0,   0, 255, 100), new Color(255, 165,   0, 100),
            new Color(128,   0, 128, 100), new Color(  0, 255, 255, 100),
            new Color(255,   0, 255, 100), new Color(128, 128,   0, 100),
            new Color(255, 105, 180, 100)
    };

    /* ================================================================
     *  CONSTRUCTOR
     * ================================================================ */
    public PolygonVisualizer(Polygon originalPolygon, List<Polygon> decomposedPolygons) {
        this.originalPolygon    = originalPolygon;
        this.decomposedPolygons = decomposedPolygons;
        setBackground(Color.WHITE);
        enableInteractions();
    }

    /* ================================================================
     *  INTERACTIONS
     * ================================================================ */
    private void enableInteractions() {

        addMouseWheelListener(e -> {
            initializeIfNeeded();
            double oldZoom = zoom;
            zoom = (e.getPreciseWheelRotation() < 0)
                    ? zoom + 0.1 : Math.max(0.2, zoom - 0.1);
            double oldScale = initialScale * oldZoom;
            double newScale = initialScale * zoom;
            double mx = e.getX(), my = e.getY();
            if (initialOffset != null && oldScale > 0) {
                double wx = (mx - initialOffset.x - offsetX) / oldScale;
                double wy = originalPolygon.ymax - ((my - initialOffset.y - offsetY) / oldScale);
                offsetX = mx - wx * newScale - initialOffset.x;
                offsetY = my - (originalPolygon.ymax - wy) * newScale - initialOffset.y;
            }
            repaint();
        });

        final int[]     lastXY  = new int[2];
        final boolean[] panning = {false};

        MouseAdapter ma = new MouseAdapter() {

            @Override
            public void mousePressed(MouseEvent e) {
                initializeIfNeeded();

                // Build layout so hit-test uses current pill positions
                FullLayout full = buildFullLayout();
                String hit = hitTestLabel(e.getX(), e.getY(), full);

                if (hit != null) {
                    // ── FIX: seed manualLabelPos with the CURRENT auto centre
                    //    if this label has not been manually placed yet.
                    if (!manualLabelPos.containsKey(hit)) {
                        Point2D.Double autoC = autoCentreForKey(hit, full);
                        if (autoC != null) manualLabelPos.put(hit, autoC);
                    }
                    draggedLabelKey = hit;
                    Point2D.Double c = manualLabelPos.get(hit);
                    dragOffsetX = e.getX() - c.x;
                    dragOffsetY = e.getY() - c.y;
                    panning[0]  = false;
                } else {
                    draggedLabelKey = null;
                    panning[0]  = true;
                    lastXY[0]   = e.getX();
                    lastXY[1]   = e.getY();
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                draggedLabelKey = null;
                panning[0]      = false;
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (draggedLabelKey != null) {
                    manualLabelPos.put(draggedLabelKey,
                        new Point2D.Double(e.getX() - dragOffsetX,
                                           e.getY() - dragOffsetY));
                    repaint();
                } else if (panning[0]) {
                    offsetX += e.getX() - lastXY[0];
                    offsetY += e.getY() - lastXY[1];
                    lastXY[0] = e.getX();
                    lastXY[1] = e.getY();
                    repaint();
                }
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                FullLayout full = buildFullLayout();
                String hit = hitTestLabel(e.getX(), e.getY(), full);
                setCursor(hit != null
                        ? Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
                        : Cursor.getDefaultCursor());

                hoveredIndex = detectHoverPolygon(e.getX(), e.getY());
                if (hoveredIndex >= 0) {
                    Polygon p = decomposedPolygons.get(hoveredIndex);
                    hoveredArea      = computeArea(p);
                    hoveredPerimeter = computePerimeter(p);
                } else {
                    hoveredArea = hoveredPerimeter = 0.0;
                }
                repaint();
            }
        };

        addMouseListener(ma);
        addMouseMotionListener(ma);

        addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) {
                initialScale = -1; initialOffset = null; repaint();
            }
        });
    }

    /* ================================================================
     *  FULL LAYOUT  — layout for every polygon in one pass
     * ================================================================ */
    private static class PolyLayout {
        int      polyIndex;
        int[]    vx, vy;
        double[] rayDX, rayDY, dist;
        int[]    tw, th;
        int      n;
    }

    private static class FullLayout {
        List<PolyLayout> polys = new ArrayList<>();
    }

    private FullLayout buildFullLayout() {
        FullLayout full = new FullLayout();
        if (initialOffset == null || initialScale <= 0) return full;
        double scale = initialScale * zoom;

        for (int pi = 0; pi < decomposedPolygons.size(); pi++) {
            if (!verticesVisibleFor(pi)) continue;
            Polygon     poly = decomposedPolygons.get(pi);
            List<Point> pts  = poly.polygonPoints;
            int n = pts.size();

            PolyLayout PL = new PolyLayout();
            PL.polyIndex = pi;
            PL.n         = n;
            PL.vx    = new int[n];    PL.vy    = new int[n];
            PL.rayDX = new double[n]; PL.rayDY = new double[n];
            PL.dist  = new double[n];
            PL.tw    = new int[n];    PL.th    = new int[n];

            Font        font = new Font("SansSerif", Font.BOLD, labelFontSize);
            FontMetrics fm   = getFontMetrics(font);

            for (int i = 0; i < n; i++) {
                PL.vx[i] = screenX(pts.get(i), scale, initialOffset, offsetX);
                PL.vy[i] = screenY(pts.get(i), scale, initialOffset, offsetY);
            }

            double cx = 0, cy = 0;
            for (int i = 0; i < n; i++) { cx += PL.vx[i]; cy += PL.vy[i]; }
            cx /= n; cy /= n;

            for (int i = 0; i < n; i++) {
                String lbl = String.format("(%.2f, %.2f)", pts.get(i).x, pts.get(i).y);
                PL.tw[i] = fm.stringWidth(lbl);
                PL.th[i] = fm.getAscent();
                double dx = PL.vx[i] - cx, dy = PL.vy[i] - cy;
                double len = Math.hypot(dx, dy);
                if (len < 1e-6) { dx = 1; dy = 0; len = 1; }
                PL.rayDX[i] = dx / len;
                PL.rayDY[i] = dy / len;
                PL.dist[i]  = LABEL_MIN_OFFSET;
            }

            // Collision resolution (skip manually placed)
            for (int iter = 0; iter < MAX_ITER; iter++) {
                boolean any = false;
                for (int i = 0; i < n; i++) {
                    String ki = pi + "_" + i;
                    if (manualLabelPos.containsKey(ki)) continue;
                    Rectangle ri = autoRect(PL.vx[i], PL.vy[i],
                                            PL.rayDX[i], PL.rayDY[i],
                                            PL.dist[i], PL.tw[i], PL.th[i]);
                    for (int j = i + 1; j < n; j++) {
                        String kj = pi + "_" + j;
                        if (manualLabelPos.containsKey(kj)) continue;
                        Rectangle rj = autoRect(PL.vx[j], PL.vy[j],
                                                PL.rayDX[j], PL.rayDY[j],
                                                PL.dist[j], PL.tw[j], PL.th[j]);
                        if (ri.intersects(rj)) {
                            any = true;
                            PL.dist[i] += COLLISION_NUDGE;
                            PL.dist[j] += COLLISION_NUDGE;
                            ri = autoRect(PL.vx[i], PL.vy[i],
                                          PL.rayDX[i], PL.rayDY[i],
                                          PL.dist[i], PL.tw[i], PL.th[i]);
                        }
                    }
                }
                if (!any) break;
            }

            full.polys.add(PL);
        }
        return full;
    }

    /* ================================================================
     *  HIT-TEST  against a pre-built FullLayout
     * ================================================================ */
    private String hitTestLabel(int mx, int my, FullLayout full) {
        for (PolyLayout PL : full.polys) {
            for (int i = 0; i < PL.n; i++) {
                String    key  = PL.polyIndex + "_" + i;
                Rectangle pill = pillRectFor(key,
                    PL.vx[i], PL.vy[i],
                    PL.rayDX[i], PL.rayDY[i],
                    PL.dist[i], PL.tw[i], PL.th[i]);
                if (pill.contains(mx, my)) return key;
            }
        }
        return null;
    }

    /**
     * Returns the auto-layout pill centre for a given key by looking it up
     * in the pre-built FullLayout.  Returns null if the key is not found.
     */
    private Point2D.Double autoCentreForKey(String key, FullLayout full) {
        String[] parts = key.split("_");
        if (parts.length != 2) return null;
        int pi = Integer.parseInt(parts[0]);
        int vi = Integer.parseInt(parts[1]);
        for (PolyLayout PL : full.polys) {
            if (PL.polyIndex == pi && vi < PL.n) {
                return new Point2D.Double(
                    PL.vx[vi] + PL.dist[vi] * PL.rayDX[vi],
                    PL.vy[vi] + PL.dist[vi] * PL.rayDY[vi]);
            }
        }
        return null;
    }

    /* ================================================================
     *  PUBLIC SETTERS
     * ================================================================ */
    public void setShowOriginal(boolean v) { showOriginal  = v; repaint(); }
    public void setShowGrid(boolean v)     { showGrid      = v; repaint(); }
    public void setShowVertices(boolean v) { showVertices  = v; repaint(); }
    public void setLabelFontSize(int sz)   { labelFontSize = sz; repaint(); }
    public void setNameFontSize(int sz)    { nameFontSize  = sz; repaint(); }

    public void setPolyVertexVisible(int idx, boolean v) {
        perPolyVertexVisible.put(idx, v); repaint();
    }
    public void clearPolyVertexOverride(int idx) {
        perPolyVertexVisible.remove(idx); repaint();
    }
    private boolean verticesVisibleFor(int idx) {
        return perPolyVertexVisible.getOrDefault(idx, showVertices);
    }

    /* ================================================================
     *  PAINT
     * ================================================================ */
    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        initializeIfNeeded();

        Graphics2D g2d = (Graphics2D) g;
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                             RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                             RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        double scale = initialScale * zoom;
        if (showGrid) drawGrid(g2d, scale);

        if (showOriginal && originalPolygon != null)
            drawPolygon(g2d, originalPolygon, scale, initialOffset,
                        offsetX, offsetY, new Color(0,0,0,0), Color.BLACK, 3, -1, "Original");

        for (int i = 0; i < decomposedPolygons.size(); i++) {
            Color fill   = COLORS[i % COLORS.length];
            Color border = fill.darker();
            int   sw     = 2;
            if (i == hoveredIndex) {
                fill   = new Color(fill.getRed(), fill.getGreen(), fill.getBlue(), 180);
                border = Color.BLACK; sw = 4;
            }
            drawPolygon(g2d, decomposedPolygons.get(i), scale, initialOffset,
                        offsetX, offsetY, fill, border, sw, i, "P" + (i + 1));
        }

        drawInfoOverlay(g2d);
    }

    /* ================================================================
     *  INIT / FIT
     * ================================================================ */
    private void initializeIfNeeded() {
        if (initialScale > 0 && initialOffset != null) return;
        if (getWidth() <= 0 || getHeight() <= 0) return;
        initialScale  = computeFitScale();
        initialOffset = computeFitOffset(initialScale);
    }
    private double computeFitScale() {
        double w = originalPolygon.xmax - originalPolygon.xmin;
        double h = originalPolygon.ymax - originalPolygon.ymin;
        if (w <= 0 || h <= 0) return 1;
        return Math.min(getWidth() * 0.8 / w, getHeight() * 0.8 / h);
    }
    private Point2D.Double computeFitOffset(double s) {
        double xo = (getWidth()  - (originalPolygon.xmax - originalPolygon.xmin) * s) / 2.0;
        double yo = (getHeight() - (originalPolygon.ymax - originalPolygon.ymin) * s) / 2.0;
        return new Point2D.Double(xo, yo);
    }

    /* ================================================================
     *  COORDINATE HELPERS
     * ================================================================ */
    private int screenX(Point p, double scale, Point2D.Double fit, double panX) {
        return (int)(p.x * scale + fit.x + panX);
    }
    private int screenY(Point p, double scale, Point2D.Double fit, double panY) {
        return (int)((originalPolygon.ymax - p.y) * scale + fit.y + panY);
    }
    private double worldXFromScreen(double sx, double scale) {
        return (sx - initialOffset.x - offsetX) / scale;
    }
    private double worldYFromScreen(double sy, double scale) {
        return originalPolygon.ymax - ((sy - initialOffset.y - offsetY) / scale);
    }

    /* ================================================================
     *  GRID
     * ================================================================ */
    private void drawGrid(Graphics2D g2d, double scale) {
        int w = getWidth(), h = getHeight();
        g2d.setStroke(new BasicStroke(1f));
        g2d.setColor(new Color(230, 230, 230));
        for (int x = 0; x < w; x += FINE_GRID_SPACING_PX) g2d.drawLine(x, 0, x, h);
        for (int y = 0; y < h; y += FINE_GRID_SPACING_PX) g2d.drawLine(0, y, w, y);
        g2d.setColor(new Color(200, 200, 200));
        g2d.setStroke(new BasicStroke(1.5f));
        double wxMin  = worldXFromScreen(0, scale), wxMax = worldXFromScreen(w, scale);
        double wyMin  = worldYFromScreen(h, scale),  wyMax = worldYFromScreen(0, scale);
        double startX = Math.floor(wxMin / MAJOR_GRID_SPACING_WORLD) * MAJOR_GRID_SPACING_WORLD;
        double startY = Math.floor(wyMin / MAJOR_GRID_SPACING_WORLD) * MAJOR_GRID_SPACING_WORLD;
        for (double xw = startX; xw <= wxMax; xw += MAJOR_GRID_SPACING_WORLD) {
            int sx = (int)(xw * scale + initialOffset.x + offsetX);
            g2d.drawLine(sx, 0, sx, h);
        }
        for (double yw = startY; yw <= wyMax; yw += MAJOR_GRID_SPACING_WORLD) {
            int sy = (int)((originalPolygon.ymax - yw) * scale + initialOffset.y + offsetY);
            g2d.drawLine(0, sy, w, sy);
        }
    }

    /* ================================================================
     *  DRAW POLYGON
     * ================================================================ */
    private void drawPolygon(Graphics2D g2d, Polygon polygon,
                             double scale, Point2D.Double fitOffset,
                             double panX, double panY,
                             Color fillColor, Color borderColor,
                             int strokeWidth, int index, String label) {

        List<Point> pts = polygon.polygonPoints;
        int n = pts.size();
        if (n < 2) return;

        int[] sx = new int[n], sy = new int[n];
        for (int i = 0; i < n; i++) {
            sx[i] = screenX(pts.get(i), scale, fitOffset, panX);
            sy[i] = screenY(pts.get(i), scale, fitOffset, panY);
        }

        if (fillColor.getAlpha() > 0) { g2d.setColor(fillColor); g2d.fillPolygon(sx, sy, n); }
        if (strokeWidth > 3) {
            g2d.setStroke(new BasicStroke(strokeWidth + 4,
                    BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2d.setColor(new Color(borderColor.getRed(), borderColor.getGreen(),
                                   borderColor.getBlue(), 80));
            g2d.drawPolygon(sx, sy, n);
        }
        g2d.setStroke(new BasicStroke(strokeWidth));
        g2d.setColor(borderColor);
        g2d.drawPolygon(sx, sy, n);

        if (index >= 0) drawCentredPolygonName(g2d, label, sx, sy, n);

        boolean showV = (index < 0) ? showVertices : verticesVisibleFor(index);
        if (showV) drawVertexLayer(g2d, index, polygon.polygonPoints, sx, sy, n);
    }

    /* ================================================================
     *  CENTRED POLYGON NAME
     * ================================================================ */
    private void drawCentredPolygonName(Graphics2D g2d, String label,
                                        int[] x, int[] y, int n) {
        double cx = 0, cy = 0;
        for (int i = 0; i < n; i++) { cx += x[i]; cy += y[i]; }
        cx /= n; cy /= n;

        g2d.setFont(new Font("SansSerif", Font.BOLD, nameFontSize));
        FontMetrics fm = g2d.getFontMetrics();
        int tw = fm.stringWidth(label), th = fm.getAscent(), pad = 5;
        int tx = (int)(cx - tw / 2.0), ty = (int)(cy + th / 2.0 - 1);

        g2d.setColor(new Color(255, 255, 255, 210));
        g2d.fillRoundRect(tx - pad, ty - th - pad + 2, tw + pad * 2, th + pad * 2, 8, 8);
        g2d.setColor(new Color(200, 200, 200, 160));
        g2d.setStroke(new BasicStroke(0.8f));
        g2d.drawRoundRect(tx - pad, ty - th - pad + 2, tw + pad * 2, th + pad * 2, 8, 8);
        g2d.setColor(Color.BLACK);
        g2d.drawString(label, tx, ty);
    }

    /* ================================================================
     *  VERTEX LAYER
     * ================================================================ */
    private void drawVertexLayer(Graphics2D g2d, int polyIndex,
                                 List<Point> pts, int[] vx, int[] vy, int n) {
        Font        font = new Font("SansSerif", Font.BOLD, labelFontSize);
        g2d.setFont(font);
        FontMetrics fm   = g2d.getFontMetrics();

        double cx = 0, cy = 0;
        for (int i = 0; i < n; i++) { cx += vx[i]; cy += vy[i]; }
        cx /= n; cy /= n;

        String[] labels = new String[n];
        double[] rayDX  = new double[n], rayDY = new double[n];
        double[] dist   = new double[n];
        int[]    tw     = new int[n],    th    = new int[n];

        for (int i = 0; i < n; i++) {
            labels[i] = String.format("(%.2f, %.2f)", pts.get(i).x, pts.get(i).y);
            tw[i] = fm.stringWidth(labels[i]);
            th[i] = fm.getAscent();
            double dx = vx[i] - cx, dy = vy[i] - cy;
            double len = Math.hypot(dx, dy);
            if (len < 1e-6) { dx = 1; dy = 0; len = 1; }
            rayDX[i] = dx / len; rayDY[i] = dy / len;
            dist[i]  = LABEL_MIN_OFFSET;
        }

        // Collision resolution
        for (int iter = 0; iter < MAX_ITER; iter++) {
            boolean any = false;
            for (int i = 0; i < n; i++) {
                if (manualLabelPos.containsKey(polyIndex + "_" + i)) continue;
                Rectangle ri = autoRect(vx[i], vy[i], rayDX[i], rayDY[i],
                                        dist[i], tw[i], th[i]);
                for (int j = i + 1; j < n; j++) {
                    if (manualLabelPos.containsKey(polyIndex + "_" + j)) continue;
                    Rectangle rj = autoRect(vx[j], vy[j], rayDX[j], rayDY[j],
                                            dist[j], tw[j], th[j]);
                    if (ri.intersects(rj)) {
                        any = true;
                        dist[i] += COLLISION_NUDGE; dist[j] += COLLISION_NUDGE;
                        ri = autoRect(vx[i], vy[i], rayDX[i], rayDY[i],
                                      dist[i], tw[i], th[i]);
                    }
                }
            }
            if (!any) break;
        }

        // Dots
        g2d.setColor(Color.BLACK);
        for (int i = 0; i < n; i++)
            g2d.fillOval(vx[i] - DOT_SIZE / 2, vy[i] - DOT_SIZE / 2,
                         DOT_SIZE, DOT_SIZE);

        Stroke dashedThin = new BasicStroke(1f, BasicStroke.CAP_BUTT,
                BasicStroke.JOIN_MITER, 10f, new float[]{4f, 3f}, 0f);
        Stroke dashedPill = new BasicStroke(1.2f, BasicStroke.CAP_BUTT,
                BasicStroke.JOIN_MITER, 10f, new float[]{3f, 2f}, 0f);

        for (int i = 0; i < n; i++) {
            String  key      = polyIndex + "_" + i;
            boolean dragging = key.equals(draggedLabelKey);
            boolean manual   = manualLabelPos.containsKey(key);

            Rectangle pill = pillRectFor(key,
                vx[i], vy[i], rayDX[i], rayDY[i], dist[i], tw[i], th[i]);
            int pillW = tw[i] + PAD * 2;
            int pillH = th[i] + PAD * 2;

            // Leader direction
            double leRayDX, leRayDY;
            if (manual || dragging) {
                Point2D.Double c = manualLabelPos.get(key);
                double ldx = c.x - vx[i], ldy = c.y - vy[i];
                double llen = Math.hypot(ldx, ldy);
                leRayDX = llen > 1e-6 ? ldx / llen : rayDX[i];
                leRayDY = llen > 1e-6 ? ldy / llen : rayDY[i];
            } else {
                leRayDX = rayDX[i]; leRayDY = rayDY[i];
            }

            double dotEdgeX = vx[i] + (DOT_SIZE / 2.0 + 1) * leRayDX;
            double dotEdgeY = vy[i] + (DOT_SIZE / 2.0 + 1) * leRayDY;
            double lineEndX = clamp(vx[i], pill.x, pill.x + pillW);
            double lineEndY = clamp(vy[i], pill.y, pill.y + pillH);

            if (Math.hypot(lineEndX - dotEdgeX, lineEndY - dotEdgeY) > 2) {
                g2d.setStroke(dashedThin);
                g2d.setColor(dragging ? new Color(60, 120, 220, 220)
                           : manual  ? new Color(80, 80, 80, 200)
                                     : new Color(120, 120, 120, 180));
                g2d.drawLine((int) dotEdgeX, (int) dotEdgeY,
                             (int) lineEndX, (int) lineEndY);
            }

            g2d.setColor(dragging ? new Color(210, 230, 255, 240)
                       : manual  ? new Color(245, 250, 255, 235)
                                 : new Color(255, 255, 255, 225));
            g2d.fillRoundRect(pill.x, pill.y, pillW, pillH, 6, 6);

            if (dragging) {
                g2d.setStroke(dashedPill);
                g2d.setColor(new Color(60, 120, 220, 200));
            } else if (manual) {
                g2d.setStroke(new BasicStroke(0.9f));
                g2d.setColor(new Color(100, 140, 200, 180));
            } else {
                g2d.setStroke(new BasicStroke(0.8f));
                g2d.setColor(new Color(150, 150, 150, 200));
            }
            g2d.drawRoundRect(pill.x, pill.y, pillW, pillH, 6, 6);

            g2d.setFont(font);
            g2d.setColor(dragging ? new Color(30, 70, 160) : new Color(20, 20, 20));
            g2d.drawString(labels[i], pill.x + PAD, pill.y + PAD + th[i] - 1);
        }
    }

    /* ================================================================
     *  PILL GEOMETRY HELPERS
     * ================================================================ */
    private Rectangle autoRect(int vxi, int vyi,
                               double rDX, double rDY,
                               double d, int twi, int thi) {
        double ax = vxi + d * rDX;
        double ay = vyi + d * rDY;
        return new Rectangle((int)(ax - twi / 2.0) - PAD,
                             (int)(ay - thi / 2.0) - PAD,
                             twi + PAD * 2, thi + PAD * 2);
    }

    private Rectangle pillRectFor(String key,
                                  int vxi, int vyi,
                                  double rDX, double rDY,
                                  double d, int twi, int thi) {
        if (manualLabelPos.containsKey(key)) {
            Point2D.Double c = manualLabelPos.get(key);
            return new Rectangle((int)(c.x - twi / 2.0) - PAD,
                                 (int)(c.y - thi / 2.0) - PAD,
                                 twi + PAD * 2, thi + PAD * 2);
        }
        return autoRect(vxi, vyi, rDX, rDY, d, twi, thi);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /* ================================================================
     *  HOVER DETECTION
     * ================================================================ */
    private int detectHoverPolygon(int mx, int my) {
        if (initialOffset == null || initialScale <= 0) return -1;
        double scale = initialScale * zoom;
        for (int i = 0; i < decomposedPolygons.size(); i++)
            if (isPointInsidePolygon(decomposedPolygons.get(i),
                                     mx, my, scale, initialOffset, offsetX, offsetY))
                return i;
        return -1;
    }

    private boolean isPointInsidePolygon(Polygon poly, int mx, int my,
                                         double scale, Point2D.Double fitOffset,
                                         double panX, double panY) {
        java.awt.Polygon awtPoly = new java.awt.Polygon();
        for (Point p : poly.polygonPoints)
            awtPoly.addPoint(
                (int)(p.x * scale + fitOffset.x + panX),
                (int)((originalPolygon.ymax - p.y) * scale + fitOffset.y + panY));
        return awtPoly.contains(mx, my);
    }

    /* ================================================================
     *  GEOMETRY HELPERS
     * ================================================================ */
    private double computeArea(Polygon poly) {
        List<Point> pts = poly.polygonPoints;
        int n = pts.size();
        if (n < 3) return 0.0;
        double sum = 0.0;
        for (int i = 0; i < n; i++) {
            Point a = pts.get(i), b = pts.get((i + 1) % n);
            sum += (a.x * b.y - b.x * a.y);
        }
        return Math.abs(sum) / 2.0;
    }

    private double computePerimeter(Polygon poly) {
        List<Point> pts = poly.polygonPoints;
        int n = pts.size();
        if (n < 2) return 0.0;
        double sum = 0.0;
        for (int i = 0; i < n; i++) {
            Point a = pts.get(i), b = pts.get((i + 1) % n);
            double dx = a.x - b.x, dy = a.y - b.y;
            sum += Math.sqrt(dx * dx + dy * dy);
        }
        return sum;
    }

    /* ================================================================
     *  INFO OVERLAY
     * ================================================================ */
    private void drawInfoOverlay(Graphics2D g2d) {
        g2d.setFont(g2d.getFont().deriveFont(Font.PLAIN, 14f));
        int x = 10, y = 20;
        g2d.setColor(new Color(255, 255, 255, 200));
        g2d.fillRoundRect(x - 5, y - 15, 560, 45, 10, 10);
        g2d.setColor(Color.BLACK);
        g2d.drawString(hoveredIndex >= 0
                ? "Hover: P" + (hoveredIndex + 1)
                  + "  |  Area = "      + String.format("%.2f", hoveredArea)
                  + "  |  Perimeter = " + String.format("%.2f", hoveredPerimeter)
                : "Hover over a polygon to see area & perimeter.", x, y);
        g2d.drawString("Zoom: wheel  |  Pan: drag canvas  |  Labels: drag to reposition",
                x, y + 18);
    }

    /* ================================================================
     *  EXPORT
     * ================================================================ */
    public void saveAsPng(File file) throws IOException {
        BufferedImage img = new BufferedImage(getWidth(), getHeight(),
                                             BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        this.printAll(g2);
        g2.dispose();
        ImageIO.write(img, "png", file);
    }

    /* ================================================================
     *  STATIC ENTRY POINT
     * ================================================================ */
    public static void visualize(Polygon original, List<Polygon> decomposed) {
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("Multi-Polygon Visualiser");
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);

            PolygonVisualizer panel = new PolygonVisualizer(original, decomposed);
            JPanel root = new JPanel(new BorderLayout());

            JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));

            JCheckBox showOriginalCb = new JCheckBox("Show Original", true);
            JCheckBox showGridCb     = new JCheckBox("Show Grid",     true);
            JCheckBox showVerticesCb = new JCheckBox("Show Vertices", true);

            showOriginalCb.addActionListener(e -> panel.setShowOriginal(showOriginalCb.isSelected()));
            showGridCb    .addActionListener(e -> panel.setShowGrid(showGridCb.isSelected()));
            showVerticesCb.addActionListener(e -> {
                panel.setShowVertices(showVerticesCb.isSelected());
                for (int i = 0; i < decomposed.size(); i++)
                    panel.clearPolyVertexOverride(i);
            });

            JButton resetLabelsBtn = new JButton("Reset Labels");
            resetLabelsBtn.setToolTipText("Return all manually repositioned labels to auto-layout");
            resetLabelsBtn.addActionListener(e -> {
                panel.manualLabelPos.clear();
                panel.repaint();
            });

            JLabel  lblSizeLbl = new JLabel("Vertex Label:");
            JSlider lblSlider  = new JSlider(8, 32, 13);
            lblSlider.setMajorTickSpacing(8); lblSlider.setMinorTickSpacing(2);
            lblSlider.setPaintTicks(true);    lblSlider.setPaintLabels(true);
            lblSlider.setMaximumSize(new Dimension(200, 50));
            JLabel lblSizeVal  = new JLabel("13px");
            lblSizeVal.setPreferredSize(new Dimension(36, 20));
            lblSlider.addChangeListener(e -> {
                lblSizeVal.setText(lblSlider.getValue() + "px");
                panel.setLabelFontSize(lblSlider.getValue());
                panel.manualLabelPos.clear();
            });

            JLabel  nameSizeLbl = new JLabel("Poly Name:");
            JSlider nameSlider  = new JSlider(8, 36, 14);
            nameSlider.setMajorTickSpacing(8); nameSlider.setMinorTickSpacing(2);
            nameSlider.setPaintTicks(true);    nameSlider.setPaintLabels(true);
            nameSlider.setMaximumSize(new Dimension(200, 50));
            JLabel nameSizeVal  = new JLabel("14px");
            nameSizeVal.setPreferredSize(new Dimension(36, 20));
            nameSlider.addChangeListener(e -> {
                nameSizeVal.setText(nameSlider.getValue() + "px");
                panel.setNameFontSize(nameSlider.getValue());
            });

            JButton savePngBtn = new JButton("Save PNG");
            savePngBtn.addActionListener(e -> {
                JFileChooser chooser = new JFileChooser();
                chooser.setDialogTitle("Save View as PNG");
                chooser.setSelectedFile(new File(original.name));
                if (chooser.showSaveDialog(frame) == JFileChooser.APPROVE_OPTION) {
                    File file = chooser.getSelectedFile();
                    if (!file.getName().toLowerCase().endsWith(".png"))
                        file = new File(file.getAbsolutePath() + ".png");
                    try {
                        panel.saveAsPng(file);
                        JOptionPane.showMessageDialog(frame,
                                "Saved to: " + file.getAbsolutePath(),
                                "Export Successful", JOptionPane.INFORMATION_MESSAGE);
                    } catch (IOException ex) {
                        JOptionPane.showMessageDialog(frame, "Failed: " + ex.getMessage(),
                                "Error", JOptionPane.ERROR_MESSAGE);
                    }
                }
            });

            controls.add(showOriginalCb);
            controls.add(showGridCb);
            controls.add(showVerticesCb);
            controls.add(resetLabelsBtn);
            controls.add(new JSeparator(SwingConstants.VERTICAL));
            controls.add(lblSizeLbl);  controls.add(lblSlider);  controls.add(lblSizeVal);
            controls.add(new JSeparator(SwingConstants.VERTICAL));
            controls.add(nameSizeLbl); controls.add(nameSlider); controls.add(nameSizeVal);
            controls.add(new JSeparator(SwingConstants.VERTICAL));
            controls.add(savePngBtn);

            JPanel     perPolyPanel = buildPerPolygonPanel(panel, decomposed);
            JScrollPane scroll      = new JScrollPane(perPolyPanel,
                    JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                    JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
            scroll.setPreferredSize(new Dimension(195, 0));
            scroll.setBorder(BorderFactory.createTitledBorder("Per-Polygon Vertices"));

            root.add(controls, BorderLayout.NORTH);
            root.add(panel,    BorderLayout.CENTER);
            root.add(scroll,   BorderLayout.EAST);

            frame.setContentPane(root);
            frame.setSize(1400, 900);
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        });
    }

    /* ================================================================
     *  PER-POLYGON SIDEBAR
     * ================================================================ */
    private static JPanel buildPerPolygonPanel(PolygonVisualizer panel,
                                               List<Polygon> decomposed) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        for (int i = 0; i < decomposed.size(); i++) {
            final int idx = i;
            Color base   = COLORS[i % COLORS.length];
            Color swatch = new Color(base.getRed(), base.getGreen(), base.getBlue(), 220);

            JLabel swatchLbl = new JLabel() {
                @Override protected void paintComponent(Graphics g) {
                    g.setColor(swatch);
                    g.fillRoundRect(0, 0, getWidth(), getHeight(), 5, 5);
                    g.setColor(swatch.darker());
                    g.drawRoundRect(0, 0, getWidth()-1, getHeight()-1, 5, 5);
                }
            };
            swatchLbl.setPreferredSize(new Dimension(16, 16));
            swatchLbl.setMinimumSize(new Dimension(16, 16));
            swatchLbl.setMaximumSize(new Dimension(16, 16));

            String name = decomposed.get(i).name != null ? decomposed.get(i).name : "P" + (i+1);
            if (name.length() > 10) name = name.substring(0, 9) + "…";
            JLabel nameLbl = new JLabel("P" + (i+1) + " " + name);
            nameLbl.setFont(nameLbl.getFont().deriveFont(Font.PLAIN, 12f));
            nameLbl.setPreferredSize(new Dimension(100, 22));

            JCheckBox cb = new JCheckBox("", true);
            cb.setToolTipText("Show vertices for P" + (i+1));
            cb.addActionListener(e -> panel.setPolyVertexVisible(idx, cb.isSelected()));

            JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
            row.add(swatchLbl); row.add(nameLbl); row.add(cb);
            p.add(row);
        }
        p.add(Box.createVerticalGlue());
        return p;
    }
}