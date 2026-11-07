import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.File;
import java.io.IOException;
import java.util.*;

import java.util.List;

public class SinglePolygonVisualizer extends JFrame {

    private final DrawPanel drawPanel;

    public static void show(Polygon polygon, String title) {
        SwingUtilities.invokeLater(() ->
            new SinglePolygonVisualizer(polygon, title).setVisible(true));
    }

    public SinglePolygonVisualizer(Polygon polygon, String title) {
        super(title);
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setSize(1000, 700);
        setLocationRelativeTo(null);
        drawPanel = new DrawPanel(polygon);
        add(drawPanel, BorderLayout.CENTER);
        add(createToolbar(), BorderLayout.NORTH);
        add(createStatusBar(), BorderLayout.SOUTH);
    }

    private JToolBar createToolbar() {
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);

        JButton   resetBtn  = new JButton("Reset View");
        JButton   saveBtn   = new JButton("Save PNG");
        JButton   resetLbls = new JButton("Reset Labels");
        JCheckBox showPts   = new JCheckBox("Show Vertices", true);
        JCheckBox showGrid  = new JCheckBox("Show Grid",     true);

        resetBtn .addActionListener(e -> drawPanel.resetView());
        saveBtn  .addActionListener(e -> drawPanel.saveImage());
        resetLbls.addActionListener(e -> drawPanel.resetLabels());
        resetLbls.setToolTipText("Return all dragged labels to auto-layout");
        showPts  .addActionListener(e -> drawPanel.setShowPoints(showPts.isSelected()));
        showGrid .addActionListener(e -> drawPanel.setShowGrid(showGrid.isSelected()));

        JLabel  sliderLabel = new JLabel("Label Size:");
        JSlider labelSlider = new JSlider(JSlider.HORIZONTAL, 8, 32, 13);
        labelSlider.setMajorTickSpacing(8);
        labelSlider.setMinorTickSpacing(2);
        labelSlider.setPaintTicks(true);
        labelSlider.setPaintLabels(true);
        labelSlider.setMaximumSize(new Dimension(220, 50));
        labelSlider.setToolTipText("Adjust vertex label font size");

        JLabel currentSize = new JLabel("13px");
        currentSize.setPreferredSize(new Dimension(40, 20));

        labelSlider.addChangeListener(e -> {
            int v = labelSlider.getValue();
            currentSize.setText(v + "px");
            drawPanel.setLabelFontSize(v);
            drawPanel.resetLabels();
        });

        bar.add(resetBtn);
        bar.add(saveBtn);
        bar.add(resetLbls);
        bar.addSeparator();
        bar.add(showPts);
        bar.add(showGrid);
        bar.addSeparator();
        bar.add(sliderLabel);
        bar.add(Box.createHorizontalStrut(4));
        bar.add(labelSlider);
        bar.add(Box.createHorizontalStrut(4));
        bar.add(currentSize);

        return bar;
    }

    private JLabel createStatusBar() {
        JLabel label = new JLabel(
            "Wheel: Zoom  |  Drag canvas: Pan  |  Drag label: Reposition  |  Save exports PNG");
        label.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
        return label;
    }

    /* ================================================================
     *  DRAW PANEL
     * ================================================================ */
    static class DrawPanel extends JPanel {

        private final Polygon polygon;

        private double scale    = 1.0;
        private double offsetX  = 0, offsetY = 0;

        private boolean showPoints  = true;
        private boolean showGrid    = true;
        private int     labelFontSz = 13;

        private static final double GRID_MINOR = 1.0;
        private static final double GRID_MAJOR = 5.0;
        private static final int    DOT_SIZE   = 7;

        /* ── Label drag state ──────────────────────────────────────────
         * Key   = vertex index as String
         * Value = screen-space pill CENTRE (set when drag starts or
         *         when user repositions the label).
         *
         * FIX: on mousePressed we immediately seed manualLabelPos with
         * the current auto-computed centre so the pill never jumps.
         */
        private final Map<String, Point2D.Double> manualLabelPos = new HashMap<>();
        private String  draggedKey = null;
        private double  dragOffX   = 0, dragOffY = 0;

        private static final int LABEL_MIN_OFFSET = 18;
        private static final int COLLISION_NUDGE  = 6;
        private static final int MAX_ITER         = 40;
        private static final int PAD              = 4;

        DrawPanel(Polygon polygon) {
            this.polygon = polygon;
            setBackground(Color.WHITE);
            enableInteractions();
        }

        void setShowPoints(boolean v) { showPoints  = v;  repaint(); }
        void setShowGrid(boolean v)   { showGrid    = v;  repaint(); }
        void setLabelFontSize(int sz) { labelFontSz = sz; repaint(); }
        void resetLabels()            { manualLabelPos.clear(); repaint(); }

        void resetView() {
            double w = polygon.xmax - polygon.xmin;
            double h = polygon.ymax - polygon.ymin;
            scale   = Math.min(getWidth() / w, getHeight() / h) * 0.8;
            offsetX = getWidth()  / 2.0 - scale * (polygon.xmin + polygon.xmax) / 2.0;
            offsetY = getHeight() / 2.0 + scale * (polygon.ymin + polygon.ymax) / 2.0;
            repaint();
        }

        /* ================================================================
         *  INTERACTIONS
         * ================================================================ */
        private void enableInteractions() {

            addMouseWheelListener(e -> {
                java.awt.Point mp = e.getPoint();
                double factor = (e.getWheelRotation() < 0) ? 1.1 : 0.9;
                double wx = screenToWorldX(mp.x);
                double wy = screenToWorldY(mp.y);
                scale   *= factor;
                offsetX  = mp.x - wx * scale;
                offsetY  = mp.y + wy * scale;
                repaint();
            });

            final int[]     lastXY  = new int[2];
            final boolean[] panning = {false};

            MouseAdapter ma = new MouseAdapter() {

                @Override
                public void mousePressed(MouseEvent e) {
                    // Build layout so we know where every pill actually is
                    LabelLayout layout = buildLayout();

                    String hit = hitTestLabel(e.getX(), e.getY(), layout);
                    if (hit != null) {
                        // ── FIX: seed manualLabelPos with the CURRENT pill
                        //    centre before we start moving, so the pill
                        //    doesn't jump on the first drag event.
                        if (!manualLabelPos.containsKey(hit)) {
                            int idx = Integer.parseInt(hit);
                            Point2D.Double autoC = autoPillCentre(
                                layout.vx[idx], layout.vy[idx],
                                layout.rayDX[idx], layout.rayDY[idx],
                                layout.dist[idx]);
                            manualLabelPos.put(hit, autoC);
                        }
                        draggedKey = hit;
                        Point2D.Double c = manualLabelPos.get(hit);
                        // Record offset of cursor INSIDE the pill
                        dragOffX = e.getX() - c.x;
                        dragOffY = e.getY() - c.y;
                        panning[0] = false;
                    } else {
                        draggedKey = null;
                        panning[0] = true;
                        lastXY[0]  = e.getX();
                        lastXY[1]  = e.getY();
                    }
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    draggedKey = null;
                    panning[0] = false;
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    if (draggedKey != null) {
                        // Move pill centre = cursor - original grab offset
                        manualLabelPos.put(draggedKey,
                            new Point2D.Double(e.getX() - dragOffX,
                                               e.getY() - dragOffY));
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
                    LabelLayout layout = buildLayout();
                    String hit = hitTestLabel(e.getX(), e.getY(), layout);
                    setCursor(hit != null
                        ? Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
                        : Cursor.getDefaultCursor());
                }
            };

            addMouseListener(ma);
            addMouseMotionListener(ma);

            addComponentListener(new ComponentAdapter() {
                public void componentResized(ComponentEvent e) { resetView(); }
            });
        }

        /* ================================================================
         *  HIT-TEST
         * ================================================================ */
        private String hitTestLabel(int mx, int my, LabelLayout layout) {
            if (!showPoints) return null;
            List<Point> pts = polygon.polygonPoints;
            for (int i = 0; i < pts.size(); i++) {
                String    key  = String.valueOf(i);
                Rectangle pill = pillRectFor(key,
                    layout.vx[i], layout.vy[i],
                    layout.rayDX[i], layout.rayDY[i],
                    layout.dist[i], layout.tw[i], layout.th[i]);
                if (pill.contains(mx, my)) return key;
            }
            return null;
        }

        /* ================================================================
         *  PAINT
         * ================================================================ */
        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                                RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            if (showGrid) drawGrid(g2);
            //drawAxes(g2);
            drawPolygonFill(g2);
            drawEdgesAndVertices(g2);
        }

        /* ================================================================
         *  GRID
         * ================================================================ */
        private void drawGrid(Graphics2D g2) {
            double xmin = screenToWorldX(0),           xmax = screenToWorldX(getWidth());
            double ymin = screenToWorldY(getHeight()),  ymax = screenToWorldY(0);

            g2.setStroke(new BasicStroke(1f));
            g2.setColor(new Color(220, 220, 220));
            for (double x = Math.floor(xmin); x <= xmax; x += GRID_MINOR)
                g2.draw(new Line2D.Double(toScreenX(x), 0, toScreenX(x), getHeight()));
            for (double y = Math.floor(ymin); y <= ymax; y += GRID_MINOR)
                g2.draw(new Line2D.Double(0, toScreenY(y), getWidth(), toScreenY(y)));

            g2.setStroke(new BasicStroke(1.5f));
            g2.setColor(new Color(180, 180, 180));
            for (double x = Math.floor(xmin / GRID_MAJOR) * GRID_MAJOR; x <= xmax; x += GRID_MAJOR)
                g2.draw(new Line2D.Double(toScreenX(x), 0, toScreenX(x), getHeight()));
            for (double y = Math.floor(ymin / GRID_MAJOR) * GRID_MAJOR; y <= ymax; y += GRID_MAJOR)
                g2.draw(new Line2D.Double(0, toScreenY(y), getWidth(), toScreenY(y)));
        }

        /* ================================================================
         *  AXES
         * ================================================================ */
        private void drawAxes(Graphics2D g2) {
            g2.setStroke(new BasicStroke(2f));
            g2.setColor(Color.BLACK);
            double x0 = toScreenX(0);
            g2.draw(new Line2D.Double(x0, 0, x0, getHeight()));
            double y0 = toScreenY(0);
            g2.draw(new Line2D.Double(0, y0, getWidth(), y0));
            g2.fill(new Ellipse2D.Double(x0 - 4, y0 - 4, 8, 8));
            g2.setFont(new Font("SansSerif", Font.BOLD, 12));
            g2.drawString("X", getWidth() - 20, (float) y0 - 5);
            g2.drawString("Y", (float) x0 + 5, 15);
        }

        /* ================================================================
         *  POLYGON FILL
         * ================================================================ */
        private void drawPolygonFill(Graphics2D g2) {
            Path2D   path  = new Path2D.Double();
            boolean  first = true;
            for (Point p : polygon.polygonPoints) {
                double x = toScreenX(p.x), y = toScreenY(p.y);
                if (first) { path.moveTo(x, y); first = false; }
                else         path.lineTo(x, y);
            }
            path.closePath();
            g2.setColor(new Color(100, 160, 255, 80));
            g2.fill(path);
        }

        /* ================================================================
         *  EDGES + VERTEX LAYER
         * ================================================================ */
        private void drawEdgesAndVertices(Graphics2D g2) {
            if (polygon.polygonEdges == null) return;

            g2.setStroke(new BasicStroke(2.5f));
            g2.setColor(Color.BLUE.darker());
            for (Edge e : polygon.polygonEdges)
                g2.draw(new Line2D.Double(
                    toScreenX(e.p.x), toScreenY(e.p.y),
                    toScreenX(e.q.x), toScreenY(e.q.y)));

            if (showPoints) drawVertexLayer(g2);
        }

        /* ================================================================
         *  LABEL LAYOUT
         * ================================================================ */
        private static class LabelLayout {
            int[]    vx, vy;
            double[] rayDX, rayDY, dist;
            int[]    tw, th;
        }

        private LabelLayout buildLayout() {
            List<Point> pts = polygon.polygonPoints;
            int n = pts.size();

            LabelLayout L = new LabelLayout();
            L.vx    = new int[n];    L.vy    = new int[n];
            L.rayDX = new double[n]; L.rayDY = new double[n];
            L.dist  = new double[n];
            L.tw    = new int[n];    L.th    = new int[n];

            Font        font = new Font("SansSerif", Font.BOLD, labelFontSz);
            FontMetrics fm   = getFontMetrics(font);

            for (int i = 0; i < n; i++) {
                L.vx[i] = (int) toScreenX(pts.get(i).x);
                L.vy[i] = (int) toScreenY(pts.get(i).y);
            }

            double cx = 0, cy = 0;
            for (int i = 0; i < n; i++) { cx += L.vx[i]; cy += L.vy[i]; }
            cx /= n; cy /= n;

            for (int i = 0; i < n; i++) {
                String lbl = String.format("(%.2f, %.2f)", pts.get(i).x, pts.get(i).y);
                L.tw[i] = fm.stringWidth(lbl);
                L.th[i] = fm.getAscent();

                double dx = L.vx[i] - cx, dy = L.vy[i] - cy;
                double len = Math.hypot(dx, dy);
                if (len < 1e-6) { dx = 1; dy = 0; len = 1; }
                L.rayDX[i] = dx / len;
                L.rayDY[i] = dy / len;
                L.dist[i]  = LABEL_MIN_OFFSET;
            }

            // Resolve collisions — skip labels that are already manually placed
            for (int iter = 0; iter < MAX_ITER; iter++) {
                boolean any = false;
                for (int i = 0; i < n; i++) {
                    if (manualLabelPos.containsKey(String.valueOf(i))) continue;
                    Rectangle ri = autoRect(L.vx[i], L.vy[i],
                                            L.rayDX[i], L.rayDY[i],
                                            L.dist[i], L.tw[i], L.th[i]);
                    for (int j = i + 1; j < n; j++) {
                        if (manualLabelPos.containsKey(String.valueOf(j))) continue;
                        Rectangle rj = autoRect(L.vx[j], L.vy[j],
                                                L.rayDX[j], L.rayDY[j],
                                                L.dist[j], L.tw[j], L.th[j]);
                        if (ri.intersects(rj)) {
                            any = true;
                            L.dist[i] += COLLISION_NUDGE;
                            L.dist[j] += COLLISION_NUDGE;
                            ri = autoRect(L.vx[i], L.vy[i],
                                          L.rayDX[i], L.rayDY[i],
                                          L.dist[i], L.tw[i], L.th[i]);
                        }
                    }
                }
                if (!any) break;
            }
            return L;
        }

        /* ================================================================
         *  VERTEX LAYER DRAW
         * ================================================================ */
        private void drawVertexLayer(Graphics2D g2) {
            List<Point> pts = polygon.polygonPoints;
            int n = pts.size();

            LabelLayout L    = buildLayout();
            Font        font = new Font("SansSerif", Font.BOLD, labelFontSz);
            g2.setFont(font);

            Stroke dashedThin = new BasicStroke(1f, BasicStroke.CAP_BUTT,
                BasicStroke.JOIN_MITER, 10f, new float[]{4f, 3f}, 0f);
            Stroke dashedPill = new BasicStroke(1.2f, BasicStroke.CAP_BUTT,
                BasicStroke.JOIN_MITER, 10f, new float[]{3f, 2f}, 0f);

            // Vertex dots
            g2.setColor(Color.RED);
            for (int i = 0; i < n; i++)
                g2.fill(new Ellipse2D.Double(
                    L.vx[i] - DOT_SIZE / 2.0, L.vy[i] - DOT_SIZE / 2.0,
                    DOT_SIZE, DOT_SIZE));

            // Pills + leader lines
            for (int i = 0; i < n; i++) {
                String  key      = String.valueOf(i);
                boolean dragging = key.equals(draggedKey);
                boolean manual   = manualLabelPos.containsKey(key);

                String lbl = String.format("(%.2f, %.2f)",
                                           pts.get(i).x, pts.get(i).y);
                int tw = L.tw[i], th = L.th[i];

                Rectangle pill = pillRectFor(key,
                    L.vx[i], L.vy[i],
                    L.rayDX[i], L.rayDY[i],
                    L.dist[i], tw, th);

                int pillW = tw + PAD * 2;
                int pillH = th + PAD * 2;

                // Leader direction: always vertex → current pill centre
                double leRayDX, leRayDY;
                if (manual || dragging) {
                    Point2D.Double c = manualLabelPos.get(key);
                    double ldx = c.x - L.vx[i], ldy = c.y - L.vy[i];
                    double llen = Math.hypot(ldx, ldy);
                    if (llen > 1e-6) { leRayDX = ldx / llen; leRayDY = ldy / llen; }
                    else             { leRayDX = L.rayDX[i];  leRayDY = L.rayDY[i]; }
                } else {
                    leRayDX = L.rayDX[i];
                    leRayDY = L.rayDY[i];
                }

                double dotEdgeX = L.vx[i] + (DOT_SIZE / 2.0 + 1) * leRayDX;
                double dotEdgeY = L.vy[i] + (DOT_SIZE / 2.0 + 1) * leRayDY;
                double lineEndX = clamp(L.vx[i], pill.x, pill.x + pillW);
                double lineEndY = clamp(L.vy[i], pill.y, pill.y + pillH);

                if (Math.hypot(lineEndX - dotEdgeX, lineEndY - dotEdgeY) > 2) {
                    g2.setStroke(dashedThin);
                    g2.setColor(dragging
                        ? new Color(60, 120, 220, 220)
                        : manual
                            ? new Color(80, 80, 80, 200)
                            : new Color(120, 120, 120, 180));
                    g2.drawLine((int) dotEdgeX, (int) dotEdgeY,
                                (int) lineEndX, (int) lineEndY);
                }

                // Pill fill
                g2.setColor(dragging ? new Color(210, 230, 255, 240)
                           : manual  ? new Color(245, 250, 255, 235)
                                     : new Color(255, 255, 255, 225));
                g2.fillRoundRect(pill.x, pill.y, pillW, pillH, 6, 6);

                // Pill border
                if (dragging) {
                    g2.setStroke(dashedPill);
                    g2.setColor(new Color(60, 120, 220, 200));
                } else if (manual) {
                    g2.setStroke(new BasicStroke(0.9f));
                    g2.setColor(new Color(100, 140, 200, 180));
                } else {
                    g2.setStroke(new BasicStroke(0.8f));
                    g2.setColor(new Color(150, 150, 150, 200));
                }
                g2.drawRoundRect(pill.x, pill.y, pillW, pillH, 6, 6);

                // Text
                g2.setFont(font);
                g2.setColor(dragging ? new Color(30, 70, 160) : new Color(20, 20, 20));
                g2.drawString(lbl, pill.x + PAD, pill.y + PAD + th - 1);
            }
        }

        /* ================================================================
         *  PILL GEOMETRY HELPERS
         * ================================================================ */

        /** Pill top-left from pure auto-layout (no manual override). */
        private Rectangle autoRect(int vxi, int vyi,
                                   double rDX, double rDY,
                                   double d, int twi, int thi) {
            double ax = vxi + d * rDX;
            double ay = vyi + d * rDY;
            return new Rectangle((int)(ax - twi / 2.0) - PAD,
                                 (int)(ay - thi / 2.0) - PAD,
                                 twi + PAD * 2, thi + PAD * 2);
        }

        /** Auto-layout pill CENTRE (used to seed manualLabelPos on first click). */
        private Point2D.Double autoPillCentre(int vxi, int vyi,
                                              double rDX, double rDY, double d) {
            return new Point2D.Double(vxi + d * rDX, vyi + d * rDY);
        }

        /** Pill rect — manual position wins when present. */
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
         *  SAVE
         * ================================================================ */
        void saveImage() {
            BufferedImage img = new BufferedImage(
                getWidth(), getHeight(), BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2 = img.createGraphics();
            paint(g2);
            g2.dispose();
            JFileChooser chooser = new JFileChooser();
            chooser.setSelectedFile(new File("polygon.png"));
            if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
                try {
                    ImageIO.write(img, "png", chooser.getSelectedFile());
                } catch (IOException ex) {
                    JOptionPane.showMessageDialog(this, "Failed to save image");
                }
            }
        }

        /* ================================================================
         *  TRANSFORMS
         * ================================================================ */
        private double toScreenX(double x)      { return  x * scale + offsetX; }
        private double toScreenY(double y)      { return -y * scale + offsetY; }
        private double screenToWorldX(double x) { return  (x - offsetX) / scale; }
        private double screenToWorldY(double y) { return -(y - offsetY) / scale; }
    }
}