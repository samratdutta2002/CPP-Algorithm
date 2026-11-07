/*import javax.swing.*;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;

public class GraphGUI {

    public static void visualise(Graph<PolygonVertex> graph){

        SwingUtilities.invokeLater(() -> {

            JFrame frame = new JFrame("Polygon Graph");
            frame.setSize(1200,900);
            frame.setLocationRelativeTo(null);
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);

            GraphPanel panel = new GraphPanel(graph);

            frame.add(panel,BorderLayout.CENTER);
            frame.add(createToolbar(panel),BorderLayout.NORTH);

            frame.setVisible(true);
        });
    }

    private static JToolBar createToolbar(GraphPanel panel){

        JToolBar bar = new JToolBar();

        JCheckBox dotToggle = new JCheckBox("Show Entry/Exit Dots");
        JCheckBox textToggle = new JCheckBox("Show Entry/Exit Text");

        dotToggle.setSelected(false);
        textToggle.setSelected(false);

        dotToggle.addActionListener(e -> {
            panel.showDots = dotToggle.isSelected();
            panel.repaint();
        });

        textToggle.addActionListener(e -> {
            panel.showText = textToggle.isSelected();
            panel.repaint();
        });

        JButton save = new JButton("Save PNG");
        save.addActionListener(e -> panel.saveImage());

        JSlider sizeSlider = new JSlider(15,80,40);

        sizeSlider.setToolTipText("Node Size");

        sizeSlider.addChangeListener(e -> {
            panel.nodeSize = sizeSlider.getValue();
            panel.repaint();
        });

        bar.add(dotToggle);
        bar.add(textToggle);
        bar.addSeparator();
        bar.add(new JLabel("Node Size "));
        bar.add(sizeSlider);
        bar.addSeparator();
        bar.add(save);

        return bar;
    }

    static class GraphPanel extends JPanel{

        Graph<PolygonVertex> graph;

        Map<PolygonVertex,Point> pos = new HashMap<>();

        boolean showDots=false;
        boolean showText=false;

        int nodeSize = 40;

        double offsetX=0;
        double offsetY=0;

        PolygonVertex dragged=null;

        java.awt.Point lastMouse;

        private final Color LOWER_COLOR = new Color(70,120,255);
        private final Color UPPER_COLOR = new Color(255,90,90);

        GraphPanel(Graph<PolygonVertex> g){

            graph=g;

            setBackground(Color.WHITE);

            computeInitialPositions();

            enableZoom();
            enablePan();
            enableDrag();
        }

        private void computeInitialPositions(){

            double minX=Double.MAX_VALUE;
            double maxX=Double.MIN_VALUE;
            double minY=Double.MAX_VALUE;
            double maxY=Double.MIN_VALUE;

            Map<PolygonVertex,Point> centroids=new HashMap<>();

            for(PolygonVertex v:graph.getVertices()){

                Point c=Polygon.computeCentroid(v.polygon);

                centroids.put(v,c);

                minX=Math.min(minX,c.x);
                maxX=Math.max(maxX,c.x);
                minY=Math.min(minY,c.y);
                maxY=Math.max(maxY,c.y);
            }

            double width=maxX-minX;
            double height=maxY-minY;

            double availableW=900;
            double availableH=650;

            double scale=Math.min(availableW/width,availableH/height);

            for(PolygonVertex v:centroids.keySet()){

                Point c=centroids.get(v);

                double x=(c.x-minX)*scale;
                double y=(c.y-minY)*scale;

                pos.put(v,new Point(x,y));
            }

            offsetX=150;
            offsetY=650;
        }

        private void enableZoom(){

            addMouseWheelListener(e -> {

                double factor=1.1;

                if(e.getWheelRotation()<0)
                    nodeSize+=2;
                else
                    nodeSize=Math.max(10,nodeSize-2);

                repaint();
            });
        }

        private void enablePan(){

            addMouseListener(new MouseAdapter(){

                public void mousePressed(MouseEvent e){
                    lastMouse=e.getPoint();
                }
            });

            addMouseMotionListener(new MouseAdapter(){

                public void mouseDragged(MouseEvent e){

                    if(dragged!=null)
                        return;

                    offsetX+=e.getX()-lastMouse.x;
                    offsetY+=e.getY()-lastMouse.y;

                    lastMouse=e.getPoint();

                    repaint();
                }
            });
        }

        private void enableDrag(){

            addMouseListener(new MouseAdapter(){

                public void mousePressed(MouseEvent e){

                    for(PolygonVertex v:pos.keySet()){

                        Point p=pos.get(v);

                        double sx=p.x+offsetX;
                        double sy=-p.y+offsetY;

                        if(Math.hypot(e.getX()-sx,e.getY()-sy)<nodeSize){

                            dragged=v;
                            break;
                        }
                    }
                }

                public void mouseReleased(MouseEvent e){
                    dragged=null;
                }
            });

            addMouseMotionListener(new MouseAdapter(){

                public void mouseDragged(MouseEvent e){

                    if(dragged==null)
                        return;

                    double x=e.getX()-offsetX;
                    double y=-(e.getY()-offsetY);

                    pos.put(dragged,new Point(x,y));

                    repaint();
                }
            });
        }

        public void saveImage(){

            BufferedImage img=new BufferedImage(
                    getWidth(),
                    getHeight(),
                    BufferedImage.TYPE_INT_ARGB);

            Graphics2D g2=img.createGraphics();

            paint(g2);

            g2.dispose();

            try{

                JFileChooser ch=new JFileChooser();

                if(ch.showSaveDialog(this)==JFileChooser.APPROVE_OPTION){

                    File f=ch.getSelectedFile();

                    ImageIO.write(img,"png",
                            new File(f.getAbsolutePath()+".png"));
                }

            }catch(Exception ex){
                ex.printStackTrace();
            }
        }

        protected void paintComponent(Graphics g){

            super.paintComponent(g);

            Graphics2D g2=(Graphics2D)g;

            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);

            AffineTransform old=g2.getTransform();

            g2.translate(offsetX,offsetY);
            g2.scale(1,-1);

            drawEdges(g2);
            drawNodes(g2);

            g2.setTransform(old);
        }

        private void drawEdges(Graphics2D g2){

            g2.setStroke(new BasicStroke(2));
            g2.setColor(Color.GRAY);

            Set<String> drawn=new HashSet<>();

            for(EdgeGraph<PolygonVertex> e:graph.getEdges()){

                String id1=e.u.hashCode()+"-"+e.v.hashCode();
                String id2=e.v.hashCode()+"-"+e.u.hashCode();

                if(drawn.contains(id1)||drawn.contains(id2))
                    continue;

                drawn.add(id1);

                Point a=pos.get(e.u);
                Point b=pos.get(e.v);

                g2.drawLine(
                        (int)a.x,(int)a.y,
                        (int)b.x,(int)b.y
                );
            }
        }

        private void drawNodes(Graphics2D g2){

            int i=0;

            for(PolygonVertex v:graph.getVertices()){

                Point p=pos.get(v);

                int r=nodeSize;

                Color c=Color.getHSBColor((i*0.25f)%1,0.35f,0.95f);

                g2.setColor(c);
                g2.fillOval((int)p.x-r,(int)p.y-r,2*r,2*r);

                g2.setColor(Color.DARK_GRAY);
                g2.drawOval((int)p.x-r,(int)p.y-r,2*r,2*r);

                drawNodeContent(g2,v,p,r);

                i++;
            }
        }

        private void drawNodeContent(Graphics2D g2,PolygonVertex v,Point p,int r){

            g2.scale(1,-1);

            g2.setFont(new Font("SansSerif",Font.BOLD,14));
            FontMetrics fm=g2.getFontMetrics();

            int cx=(int)p.x;
            int cy=(int)(-p.y);

            String name=v.polygon.name;

            g2.setColor(Color.BLACK);
            g2.drawString(name,
                    cx-fm.stringWidth(name)/2,
                    cy+5);

            if(showDots){

                int dot=5;
                int spacing=14;

                int startX=cx-(v.lower.size()-1)*spacing/2;
                int yLower=cy+r+10;

                g2.setColor(LOWER_COLOR);

                for(int i=0;i<v.lower.size();i++){

                    int x=startX+i*spacing;

                    g2.fillOval(x-dot,yLower-dot,dot*2,dot*2);
                }

                startX=cx-(v.upper.size()-1)*spacing/2;
                int yUpper=cy-r-10;

                g2.setColor(UPPER_COLOR);

                for(int i=0;i<v.upper.size();i++){

                    int x=startX+i*spacing;

                    g2.fillOval(x-dot,yUpper-dot,dot*2,dot*2);
                }
            }

            if(showText){

                g2.setColor(Color.BLACK);

                String up="U:"+v.upper;
                String lo="L:"+v.lower;

                g2.drawString(up,
                        cx-fm.stringWidth(up)/2,
                        cy+r+30);

                g2.drawString(lo,
                        cx-fm.stringWidth(lo)/2,
                        cy+r+45);
            }

            g2.scale(1,-1);
        }
    }
}
*/

import javax.swing.*;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;

import java.util.List;

public class GraphGUI {

    public static void visualise(Graph<PolygonVertex> graph) {
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("Polygon Graph");
            frame.setSize(1200, 900);
            frame.setLocationRelativeTo(null);
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);

            GraphPanel panel = new GraphPanel(graph);

            frame.add(panel, BorderLayout.CENTER);
            frame.add(createToolbar(panel), BorderLayout.NORTH);
            frame.setVisible(true);
        });
    }

    private static JToolBar createToolbar(GraphPanel panel) {
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);

        // ── Existing toggles ─────────────────────────────────────────
        JCheckBox dotToggle  = new JCheckBox("Show Entry/Exit Dots");
        JCheckBox textToggle = new JCheckBox("Show Entry/Exit Text");
        dotToggle.setSelected(false);
        textToggle.setSelected(false);

        dotToggle.addActionListener(e -> {
            panel.showDots = dotToggle.isSelected();
            panel.repaint();
        });
        textToggle.addActionListener(e -> {
            panel.showText = textToggle.isSelected();
            panel.repaint();
        });

        // ── Node size slider ─────────────────────────────────────────
        JLabel nodeSizeLbl = new JLabel("Node Size:");
        JSlider nodeSizeSlider = new JSlider(15, 80, 40);
        nodeSizeSlider.setToolTipText("Node Size");
        nodeSizeSlider.setMaximumSize(new Dimension(160, 40));
        nodeSizeSlider.addChangeListener(e -> {
            panel.nodeSize = nodeSizeSlider.getValue();
            panel.repaint();
        });

        // ── NEW: Polygon name font size slider ───────────────────────
        JLabel nameFontLbl = new JLabel("Name Size:");
        JSlider nameFontSlider = new JSlider(8, 28, 14);
        nameFontSlider.setMajorTickSpacing(10);
        nameFontSlider.setMinorTickSpacing(2);
        nameFontSlider.setPaintTicks(true);
        nameFontSlider.setPaintLabels(true);
        nameFontSlider.setMaximumSize(new Dimension(180, 50));
        nameFontSlider.setToolTipText("Polygon name font size");

        JLabel nameSizeVal = new JLabel("14px");
        nameSizeVal.setPreferredSize(new Dimension(38, 20));
        nameFontSlider.addChangeListener(e -> {
            panel.nameFontSize = nameFontSlider.getValue();
            nameSizeVal.setText(panel.nameFontSize + "px");
            panel.repaint();
        });

        // ── NEW: Entry/exit label font size slider ───────────────────
        JLabel entryFontLbl = new JLabel("Entry/Exit Size:");
        JSlider entryFontSlider = new JSlider(7, 22, 11);
        entryFontSlider.setMajorTickSpacing(5);
        entryFontSlider.setMinorTickSpacing(1);
        entryFontSlider.setPaintTicks(true);
        entryFontSlider.setPaintLabels(true);
        entryFontSlider.setMaximumSize(new Dimension(180, 50));
        entryFontSlider.setToolTipText("Entry/exit label font size");

        JLabel entryFontVal = new JLabel("11px");
        entryFontVal.setPreferredSize(new Dimension(38, 20));
        entryFontSlider.addChangeListener(e -> {
            panel.entryFontSize = entryFontSlider.getValue();
            entryFontVal.setText(panel.entryFontSize + "px");
            panel.repaint();
        });

        // ── Save ─────────────────────────────────────────────────────
        JButton save = new JButton("Save PNG");
        save.addActionListener(e -> panel.saveImage());

        // ── Assemble ─────────────────────────────────────────────────
        bar.add(dotToggle);
        bar.add(textToggle);
        bar.addSeparator();
        bar.add(nodeSizeLbl);
        bar.add(nodeSizeSlider);
        bar.addSeparator();
        bar.add(nameFontLbl);
        bar.add(nameFontSlider);
        bar.add(nameSizeVal);
        bar.addSeparator();
        bar.add(entryFontLbl);
        bar.add(entryFontSlider);
        bar.add(entryFontVal);
        bar.addSeparator();
        bar.add(save);

        return bar;
    }

    /* ================================================================
     *  GRAPH PANEL
     * ================================================================ */
    static class GraphPanel extends JPanel {

        Graph<PolygonVertex> graph;
        Map<PolygonVertex, Point> pos = new HashMap<>();

        boolean showDots = false;
        boolean showText = false;

        int nodeSize    = 40;
        int nameFontSize  = 14;   // ← polygon name inside circle
        int entryFontSize = 11;   // ← upper/lower entry-exit labels

        double offsetX = 0;
        double offsetY = 0;

        PolygonVertex dragged = null;
        java.awt.Point lastMouse;

        private final Color LOWER_COLOR = new Color(70, 120, 255);
        private final Color UPPER_COLOR = new Color(255, 90, 90);

        // Padding between label background pill and text
        private static final int PILL_PAD = 3;

        GraphPanel(Graph<PolygonVertex> g) {
            graph = g;
            setBackground(Color.WHITE);
            computeInitialPositions();
            enableZoom();
            enablePan();
            enableDrag();
        }

        /* ── Layout ─────────────────────────────────────────────────── */

        private void computeInitialPositions() {
            double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
            double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;

            Map<PolygonVertex, Point> centroids = new HashMap<>();
            for (PolygonVertex v : graph.getVertices()) {
                Point c = Polygon.computeCentroid(v.polygon);
                centroids.put(v, c);
                minX = Math.min(minX, c.x); maxX = Math.max(maxX, c.x);
                minY = Math.min(minY, c.y); maxY = Math.max(maxY, c.y);
            }

            double width  = maxX - minX;
            double height = maxY - minY;
            double scale  = Math.min(900.0 / width, 650.0 / height);

            for (PolygonVertex v : centroids.keySet()) {
                Point c = centroids.get(v);
                pos.put(v, new Point((c.x - minX) * scale, (c.y - minY) * scale));
            }
            offsetX = 150;
            offsetY = 650;
        }

        /* ── Interactions ───────────────────────────────────────────── */

        private void enableZoom() {
            addMouseWheelListener(e -> {
                nodeSize = (e.getWheelRotation() < 0)
                        ? nodeSize + 2
                        : Math.max(10, nodeSize - 2);
                repaint();
            });
        }

        private void enablePan() {
            addMouseListener(new MouseAdapter() {
                public void mousePressed(MouseEvent e) { lastMouse = e.getPoint(); }
            });
            addMouseMotionListener(new MouseAdapter() {
                public void mouseDragged(MouseEvent e) {
                    if (dragged != null) return;
                    offsetX += e.getX() - lastMouse.x;
                    offsetY += e.getY() - lastMouse.y;
                    lastMouse = e.getPoint();
                    repaint();
                }
            });
        }

        private void enableDrag() {
            addMouseListener(new MouseAdapter() {
                public void mousePressed(MouseEvent e) {
                    for (PolygonVertex v : pos.keySet()) {
                        Point p  = pos.get(v);
                        double sx = p.x + offsetX;
                        double sy = -p.y + offsetY;
                        if (Math.hypot(e.getX() - sx, e.getY() - sy) < nodeSize) {
                            dragged = v;
                            break;
                        }
                    }
                }
                public void mouseReleased(MouseEvent e) { dragged = null; }
            });
            addMouseMotionListener(new MouseAdapter() {
                public void mouseDragged(MouseEvent e) {
                    if (dragged == null) return;
                    pos.put(dragged, new Point(e.getX() - offsetX, -(e.getY() - offsetY)));
                    repaint();
                }
            });
        }

        /* ── Save ───────────────────────────────────────────────────── */

        public void saveImage() {
            BufferedImage img = new BufferedImage(getWidth(), getHeight(),
                                                  BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2 = img.createGraphics();
            paint(g2);
            g2.dispose();
            try {
                JFileChooser ch = new JFileChooser();
                if (ch.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
                    File f = ch.getSelectedFile();
                    ImageIO.write(img, "png", new File(f.getAbsolutePath() + ".png"));
                }
            } catch (Exception ex) { ex.printStackTrace(); }
        }

        /* ── Paint ──────────────────────────────────────────────────── */

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);

            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                                RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            AffineTransform savedTransform = g2.getTransform();

            // Apply pan + Y-flip once for edges and node circles
            g2.translate(offsetX, offsetY);
            g2.scale(1, -1);

            drawEdges(g2);
            drawNodes(g2);      // draws circles only (no text yet)

            g2.setTransform(savedTransform);

            // Draw all text labels in screen space (no Y-flip)
            drawAllNodeLabels(g2);
        }

        /* ── Edges ──────────────────────────────────────────────────── */

        private void drawEdges(Graphics2D g2) {
            g2.setStroke(new BasicStroke(2));
            g2.setColor(Color.GRAY);
            Set<String> drawn = new HashSet<>();

            for (EdgeGraph<PolygonVertex> e : graph.getEdges()) {
                String id1 = e.u.hashCode() + "-" + e.v.hashCode();
                String id2 = e.v.hashCode() + "-" + e.u.hashCode();
                if (drawn.contains(id1) || drawn.contains(id2)) continue;
                drawn.add(id1);

                Point a = pos.get(e.u), b = pos.get(e.v);
                g2.drawLine((int) a.x, (int) a.y, (int) b.x, (int) b.y);
            }
        }

        /* ── Node circles (drawn in flipped space) ──────────────────── */

        private void drawNodes(Graphics2D g2) {
            int i = 0;
            for (PolygonVertex v : graph.getVertices()) {
                Point p = pos.get(v);
                int   r = nodeSize;
                Color c = Color.getHSBColor((i * 0.25f) % 1, 0.35f, 0.95f);
                g2.setColor(c);
                g2.fillOval((int) p.x - r, (int) p.y - r, 2 * r, 2 * r);
                g2.setColor(Color.DARK_GRAY);
                g2.drawOval((int) p.x - r, (int) p.y - r, 2 * r, 2 * r);
                i++;
            }
        }

        /* ================================================================
         *  All node text — drawn in normal screen space after the Y-flip
         *  transform has been reset, so text is never upside-down.
         * ================================================================ */
        private void drawAllNodeLabels(Graphics2D g2) {
            for (PolygonVertex v : graph.getVertices()) {
                Point p = pos.get(v);

                // Screen-space centre of this node
                int cx = (int) (p.x + offsetX);
                int cy = (int) (-p.y + offsetY);

                drawPolygonName(g2, v, cx, cy);

                if (showDots)  drawEntryExitDots(g2, v, cx, cy);
                if (showText)  drawEntryExitLabels(g2, v, cx, cy);
            }
        }

        /* ── Polygon name centred inside the circle ─────────────────── */

        private void drawPolygonName(Graphics2D g2, PolygonVertex v,
                                     int cx, int cy) {
            g2.setFont(new Font("SansSerif", Font.BOLD, nameFontSize));
            FontMetrics fm = g2.getFontMetrics();
            String name = v.polygon.name;
            g2.setColor(Color.BLACK);
            g2.drawString(name,
                          cx - fm.stringWidth(name) / 2,
                          cy + fm.getAscent() / 2 - 1);
        }

        /* ── Entry/exit dots ────────────────────────────────────────── */

        private void drawEntryExitDots(Graphics2D g2, PolygonVertex v,
                                       int cx, int cy) {
            int r       = nodeSize;
            int dot     = 5;
            int spacing = 14;

            // Lower dots — just below the circle
            int yLower  = cy + r + 12;
            int startX  = cx - (v.lower.size() - 1) * spacing / 2;
            g2.setColor(LOWER_COLOR);
            for (int i = 0; i < v.lower.size(); i++)
                g2.fillOval(startX + i * spacing - dot, yLower - dot, dot * 2, dot * 2);

            // Upper dots — just above the circle
            int yUpper = cy - r - 12;
            startX     = cx - (v.upper.size() - 1) * spacing / 2;
            g2.setColor(UPPER_COLOR);
            for (int i = 0; i < v.upper.size(); i++)
                g2.fillOval(startX + i * spacing - dot, yUpper - dot, dot * 2, dot * 2);
        }

        /* ── Entry/exit coordinate labels ───────────────────────────── *
         *  Each point is formatted to 2 d.p. and rendered as a white-   *
         *  pill label. They are stacked dynamically based on node radius  *
         *  and the current entryFontSize, so they never overlap the       *
         *  circle regardless of node size.                               *
         * ─────────────────────────────────────────────────────────────── */
        private void drawEntryExitLabels(Graphics2D g2, PolygonVertex v,
                                         int cx, int cy) {

            Font       font = new Font("SansSerif", Font.PLAIN, entryFontSize);
            g2.setFont(font);
            FontMetrics fm  = g2.getFontMetrics();

            int r        = nodeSize;
            int lineH    = fm.getAscent() + fm.getDescent() + 2;
            int pad      = PILL_PAD;
            int hGap     = 10;   // gap between circle edge and first label row

            // ── UPPER group (rendered above the circle, stacked upward) ──
            // We draw from the circle edge upward, so we collect rows first
            // then paint them top-to-bottom once we know total height.

            List<String> upperLabels = formatPoints(v.upper);
            List<String> lowerLabels = formatPoints(v.lower);

            // Upper: anchor y just above circle, grow upward
            int yTopEdge = cy - r - hGap;
            drawLabelColumn(g2, fm, upperLabels, cx, yTopEdge,
                            lineH, pad, UPPER_COLOR, true /* grow upward */);

            // Lower: anchor y just below circle, grow downward
            int yBotEdge = cy + r + hGap;
            drawLabelColumn(g2, fm, lowerLabels, cx, yBotEdge,
                            lineH, pad, LOWER_COLOR, false /* grow downward */);
        }

        /** Format a list of Points to "(x.xx, y.xx)" strings. */
        private List<String> formatPoints(List<Point> pts) {
            List<String> out = new ArrayList<>();
            for (Point p : pts)
                out.add(String.format("(%.2f, %.2f)", p.x, p.y));
            return out;
        }

        /**
         * Draw a column of pill-labelled strings centred at cx.
         *
         * @param growUpward  true  → first label is closest to anchor (rows go up)
         *                    false → first label is closest to anchor (rows go down)
         */
        private void drawLabelColumn(Graphics2D g2, FontMetrics fm,
                                     List<String> labels, int cx, int anchorY,
                                     int lineH, int pad, Color accentColor,
                                     boolean growUpward) {
            if (labels.isEmpty()) return;

            int rowStep = lineH + pad * 2 + 3;   // full height of one pill row
            int n       = labels.size();

            for (int i = 0; i < n; i++) {
                // Which row index from anchor?  0 = closest to the circle.
                int row = i;
                int rowY;
                if (growUpward) {
                    // stack going up: row 0 is just above anchor
                    rowY = anchorY - row * rowStep - (lineH + pad * 2);
                } else {
                    // stack going down: row 0 is just below anchor
                    rowY = anchorY + row * rowStep;
                }

                String lbl = labels.get(i);
                int tw  = fm.stringWidth(lbl);
                int th  = fm.getAscent();

                int pillX = cx - tw / 2 - pad;
                int pillW = tw + pad * 2;
                int pillH = th + pad * 2;
                int pillY = rowY;

                // White pill background
                g2.setColor(new Color(255, 255, 255, 230));
                g2.fillRoundRect(pillX, pillY, pillW, pillH, 6, 6);

                // Coloured border (blue for lower, red for upper)
                g2.setColor(new Color(accentColor.getRed(),
                                      accentColor.getGreen(),
                                      accentColor.getBlue(), 160));
                g2.setStroke(new BasicStroke(1f));
                g2.drawRoundRect(pillX, pillY, pillW, pillH, 6, 6);

                // Text
                g2.setColor(accentColor.darker());
                g2.drawString(lbl, cx - tw / 2, pillY + th + pad - 1);
            }
        }
    }
}