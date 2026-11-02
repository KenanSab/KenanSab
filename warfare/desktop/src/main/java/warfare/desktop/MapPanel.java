package warfare.desktop;

import warfare.core.*;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.font.GlyphVector;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The world map. Provinces are smooth antialiased vector shapes over a procedural satellite-style underlay.
 * The heavy layer (underlay, fills, borders) is cached and only re-rendered when the view or the fog changes.
 */
final class MapPanel extends JPanel {
    enum Mode {
        POLITICAL("Political"), TERRAIN("Terrain"), INTEL("Intel / Vision"), DIPLOMATIC("Diplomatic");
        final String label;
        Mode(String l) { label = l; }
    }

    private record Hit(Rectangle r, int armyId, boolean own) {}
    private record Label(String text, double cx, double cy, double width) {}

    final GameState gs;
    final WorldData w;
    final MapShapes shapes;
    final Basemap basemap;
    Mode mode = Mode.POLITICAL;
    int selProv = -1, selArmy = -1, hoverProv = -1;
    Runnable onSelect = () -> {};
    Runnable onOrder = () -> {};
    Tutorial tutorial;
    int raidArmy = -1;
    int rightInset;
    java.util.function.IntConsumer onCapital = n -> {};
    private final List<Rectangle> capRects = new ArrayList<>(), modeRects = new ArrayList<>();
    private final List<Integer> capNations = new ArrayList<>();
    private Rectangle zoomInRect = new Rectangle(), zoomOutRect = new Rectangle(), homeRect = new Rectangle();
    private Point mouse;
    private int hoverOverlay = -1;
    String perf = "";
    boolean showPerf;

    private final int[] fillArgb;
    private final byte[] arcClass;          // 0 none, 1 coast, 2 national, 3 province, 4 sea zone
    private final List<Label> labels = new ArrayList<>();
    private BufferedImage baseImg, fillImg, borderImg;
    private double layerOx, layerOy, layerZoom;
    private boolean viewDirty = true, fillDirty = true, borderDirty = true;
    long renderNs, renders;
    private double zoom = 1, ox, oy;
    private boolean fitted;
    private Point dragFrom;
    private boolean dragged;
    private final List<Hit> hits = new ArrayList<>();
    private record Toast(String title, String text, String icon, long until, String header) {}
    private final List<Toast> toasts = new ArrayList<>();
    private javax.swing.Timer toastTimer;

    /** Short pop-up in the top-right corner (achievements, notices). */
    void toast(String title, String text, String icon) { toast(title, text, icon, "NOTICE"); }

    void toast(String title, String text, String icon, String header) {
        toasts.add(new Toast(title, text, icon, System.currentTimeMillis() + 5200, header));
        if (toastTimer == null) { toastTimer = new javax.swing.Timer(60, e -> { toasts.removeIf(t -> t.until < System.currentTimeMillis()); repaint(); if (toasts.isEmpty()) toastTimer.stop(); }); }
        if (!toastTimer.isRunning()) toastTimer.start();
        repaint();
    }

    private void drawToasts(Graphics2D g) {
        long now = System.currentTimeMillis();
        int y = 78;
        for (Toast t : toasts) {
            double life = (t.until - now) / 5200.0;
            float a = (float) Math.max(0, Math.min(1, Math.min(life * 6, (1 - life) * 8)));
            int w = 330, h = 62, x = getWidth() - w - 14;
            Composite old = g.getComposite();
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, a));
            g.setColor(new Color(10, 16, 28, 235));
            g.fillRoundRect(x, y, w, h, 16, 16);
            g.setColor(new Color(236, 192, 92, 200));
            g.setStroke(new BasicStroke(1.6f));
            g.drawRoundRect(x, y, w, h, 16, 16);
            g.drawImage(Assets.icon(t.icon, 36, new Color(236, 192, 92)), x + 12, y + 13, null);
            g.setFont(Fonts.label(12.5f));
            g.setColor(new Color(236, 192, 92));
            g.drawString(t.header, x + 58, y + 20);
            g.setFont(Fonts.title(21f));
            g.setColor(Color.WHITE);
            g.drawString(t.title.toUpperCase(), x + 58, y + 40);
            g.setFont(Fonts.ui(12f));
            g.setColor(new Color(170, 182, 206));
            g.drawString(t.text, x + 58, y + 55);
            g.setComposite(old);
            y += h + 8;
        }
    }

    MapPanel(GameState gs, Path mapDir) {
        this.gs = gs; this.w = gs.world;
        setBackground(new Color(10, 24, 46));
        shapes = WarfareApp.shapes(w);
        basemap = WarfareApp.basemap(w);
        fillArgb = new int[w.provinces.size()];
        arcClass = new byte[shapes.p1.length];
        buildLabels();
        MouseAdapter ma = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { dragFrom = e.getPoint(); dragged = false; }
            @Override public void mouseDragged(MouseEvent e) {
                if (dragFrom == null || SwingUtilities.isRightMouseButton(e)) return;
                mouse = e.getPoint();
                int dx = e.getX() - dragFrom.x, dy = e.getY() - dragFrom.y;
                if (Math.abs(dx) + Math.abs(dy) > 3) dragged = true;
                if (dragged) { ox += dx; oy += dy; dragFrom = e.getPoint(); repaint(); }
            }
            @Override public void mouseReleased(MouseEvent e) {
                if (!dragged) click(e); else { viewDirty = true; repaint(); }
                dragFrom = null;
            }
            @Override public void mouseMoved(MouseEvent e) {
                mouse = e.getPoint();
                int id = provinceAt(e.getX(), e.getY());
                int ov = overlayAt(e.getPoint());
                hoverProv = ov >= 0 ? -1 : id;
                hoverOverlay = ov;
                setCursor(ov >= 0 || capitalAt(e.getPoint()) >= 0 ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
                repaint();
            }
            @Override public void mouseExited(MouseEvent e) { mouse = null; hoverProv = -1; hoverOverlay = -1; repaint(); }
            @Override public void mouseWheelMoved(MouseWheelEvent e) {
                double f = e.getWheelRotation() < 0 ? 1.2 : 1 / 1.2;
                double nz = Math.max(0.4, Math.min(16, zoom * f));
                ox = e.getX() - (e.getX() - ox) * nz / zoom;
                oy = e.getY() - (e.getY() - oy) * nz / zoom;
                zoom = nz; viewDirty = true; repaint();
            }
        };
        addMouseListener(ma); addMouseMotionListener(ma); addMouseWheelListener(ma);
        refreshColors();
    }

    void centerOn(int province, double z) {
        WorldData.ProvinceInfo p = w.provinces.get(province);
        zoom = z; ox = getWidth() / 2.0 - p.cx * zoom; oy = getHeight() / 2.0 - p.cy * zoom; fitted = true; viewDirty = true; repaint();
    }

    void viewChanged() { viewDirty = true; repaint(); }

    void pan(double dx, double dy) { ox += dx; oy += dy; viewDirty = true; repaint(); }

    void zoomBy(double f) {
        double nz = Math.max(0.4, Math.min(16, zoom * f)), cx = getWidth() / 2.0, cy = getHeight() / 2.0;
        ox = cx - (cx - ox) * nz / zoom; oy = cy - (cy - oy) * nz / zoom;
        zoom = nz; viewDirty = true; repaint();
    }

    void setMode(Mode m) { mode = m; Arrays.fill(fillArgb, 1); fillFull = true; fillDirty = true; refreshColors(); repaint(); }

    // ------------------------------------------------------------------ labels
    /** One label per nation, placed over its largest contiguous landmass. */
    private void buildLabels() {
        for (WorldData.NationInfo n : w.nations) {
            java.util.Set<Integer> mine = new java.util.HashSet<>(n.provinces);
            java.util.Set<Integer> seen = new java.util.HashSet<>();
            double bestPx = -1; List<Integer> best = null;
            for (int start : n.provinces) {
                if (seen.contains(start)) continue;
                List<Integer> comp = new ArrayList<>();
                java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<>();
                q.add(start); seen.add(start);
                double px = 0;
                while (!q.isEmpty()) {
                    int p = q.poll(); comp.add(p); px += w.provinces.get(p).pixels;
                    for (int nb : w.provinces.get(p).neighbours) if (mine.contains(nb) && seen.add(nb)) q.add(nb);
                }
                if (px > bestPx) { bestPx = px; best = comp; }
            }
            if (best == null) continue;
            double sx = 0, sy = 0, tot = 0, x0 = 1e9, x1 = -1e9;
            for (int p : best) {
                WorldData.ProvinceInfo pi = w.provinces.get(p);
                sx += pi.cx * pi.pixels; sy += pi.cy * pi.pixels; tot += pi.pixels;
                x0 = Math.min(x0, shapes.bounds[p].x); x1 = Math.max(x1, shapes.bounds[p].x + shapes.bounds[p].width);
            }
            labels.add(new Label(n.name.toUpperCase(), sx / tot, sy / tot, x1 - x0));
        }
    }

    // ------------------------------------------------------------------ colours (fog of war lives here)
    private final List<Integer> chFill = new ArrayList<>(), chArc = new ArrayList<>();
    private boolean fillFull = true, borderFull = true;

    void refreshColors() {
        Intel in = gs.intelOf(gs.player);
        int me = gs.player;
        for (int p = 0; p < fillArgb.length; p++) {
            int c = colorOf(p, in, me);
            if (c != fillArgb[p]) { fillArgb[p] = c; if (!fillFull) { if (chFill.size() > 120) fillFull = true; else chFill.add(p); } fillDirty = true; }
        }
        for (int a = 0; a < arcClass.length; a++) {
            int p1 = shapes.p1[a], p2 = shapes.p2[a];
            byte cls;
            if (p1 < 0 || p2 < 0) cls = 0;
            else {
                boolean s1 = w.provinces.get(p1).sea, s2 = w.provinces.get(p2).sea;
                if (s1 && s2) cls = 4;
                else if (s1 != s2) cls = 1;
                else cls = (byte) (in.knownOwner[p1] != in.knownOwner[p2] ? 2 : 3);
            }
            if (cls != arcClass[a]) { arcClass[a] = cls; if (!borderFull) { if (chArc.size() > 60) borderFull = true; else chArc.add(a); } borderDirty = true; }
        }
        if (fillDirty || borderDirty) repaint();
    }

    private static int argb(int rgb, double alpha) { return ((int) Math.max(0, Math.min(255, alpha * 255)) << 24) | (rgb & 0xFFFFFF); }

    private int colorOf(int p, Intel in, int me) {
        WorldData.ProvinceInfo pi = w.provinces.get(p);
        int q = in.quality[p];
        double conf = in.provinceConfidence(p, gs.day);
        double stale = Math.round((1 - conf) * 12) / 12.0;   // quantised so fog changes only every few days
        switch (mode) {
            case TERRAIN -> {
                if (q > 0) return 0;
                return argb(0x0A1426, 0.10 + 0.42 * stale);
            }
            case INTEL -> {
                if (q == 3) return argb(0x3DBE78, 0.62);
                if (q == 2) return argb(0xA6CF52, 0.62);
                if (q == 1) return argb(0xE6B040, 0.60);
                return argb(pi.sea ? 0x0A1830 : 0x2A2E38, 0.45 + 0.35 * stale);
            }
            case DIPLOMATIC -> {
                if (pi.sea) return q > 0 ? 0 : argb(0x0A1426, 0.12 + 0.32 * stale);
                int ko = in.knownOwner[p];
                if (ko < 0) return argb(0x555555, 0.55);
                int c;
                if (ko == me) c = 0x2E9BE8;
                else if (gs.isAlly(me, ko)) c = 0x3FC47A;
                else if (gs.atWar(me, ko)) c = 0xD6453A;
                else c = switch (w.nations.get(ko).bloc) {
                    case "NATO" -> 0x4A6FC4; case "CSTO" -> 0xC8803F; case "EASTERN" -> 0xA64A9C; case "PACIFIC" -> 0x3FAAA8; default -> 0x8C8C92;
                };
                return q > 0 ? argb(c, 0.72) : argb(NationColors.mix(c, 0x50545C, 0.35 + 0.35 * stale), 0.62);
            }
            default -> {
                if (pi.sea) return q > 0 ? 0 : argb(0x0A1426, 0.12 + 0.34 * stale);
                int ko = in.knownOwner[p];
                if (ko < 0) return argb(0x555A62, 0.6);
                int base = NationColors.rgb(NationColors.of(w.nations.get(ko).tag));
                if (q > 0 || ko == me) return argb(base, 0.66);
                return argb(NationColors.mix(NationColors.scale(base, 0.85), 0x5A5E68, 0.55 * stale), 0.60 - 0.14 * stale);
            }
        }
    }

    // ------------------------------------------------------------------ painting
    private BufferedImage fresh(BufferedImage old, int type) {
        int W = Math.max(1, getWidth()), H = Math.max(1, getHeight());
        return old != null && old.getWidth() == W && old.getHeight() == H ? old : new BufferedImage(W, H, type);
    }

    private Graphics2D hq(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        return g;
    }

    private Rectangle screenRect(Rectangle2D.Float b, int pad) {
        int x0 = (int) Math.floor(b.x * zoom + ox) - pad, y0 = (int) Math.floor(b.y * zoom + oy) - pad;
        int x1 = (int) Math.ceil((b.x + b.width) * zoom + ox) + pad, y1 = (int) Math.ceil((b.y + b.height) * zoom + oy) + pad;
        return new Rectangle(x0, y0, x1 - x0, y1 - y0);
    }

    private Rectangle2D worldRect(Rectangle r) {
        return new Rectangle2D.Double((r.x - ox) / zoom, (r.y - oy) / zoom, r.width / zoom, r.height / zoom);
    }

    private void fillProvince(Graphics2D g, int p) {
        g.setColor(new Color(fillArgb[p], true));
        g.fill(shapes.fill[p]);
    }

    private void drawArcPasses(Graphics2D g, Rectangle2D worldView) {
        if (zoom >= 0.9) strokeArcs(g, worldView, 4, new Color(255, 255, 255, 34), 0.8);
        if (zoom >= 1.15) strokeArcs(g, worldView, 3, new Color(14, 18, 26, 105), 0.9);
        strokeArcs(g, worldView, 1, new Color(150, 196, 236, 55), 5.0);
        strokeArcs(g, worldView, 2, new Color(8, 10, 16, 215), 1.7);
        strokeArcs(g, worldView, 1, new Color(6, 14, 28, 230), 1.35);
    }

    private void renderLayers() {
        long t0 = System.nanoTime();
        int W = Math.max(1, getWidth()), H = Math.max(1, getHeight());
        boolean resized = baseImg == null || baseImg.getWidth() != W || baseImg.getHeight() != H;
        boolean view = viewDirty || resized || layerZoom != zoom;
        Rectangle screen = new Rectangle(0, 0, W, H);
        Rectangle2D worldView = new Rectangle2D.Double(-ox / zoom - 2, -oy / zoom - 2, W / zoom + 4, H / zoom + 4);
        if (view) {
            baseImg = fresh(baseImg, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = hq(baseImg);
            g.setColor(getBackground());
            g.fillRect(0, 0, W, H);
            AffineTransform at = new AffineTransform();
            at.translate(ox, oy);
            at.scale(zoom / Basemap.PX_PER_UNIT, zoom / Basemap.PX_PER_UNIT);
            g.drawImage(basemap.image, at, null);
            g.dispose();
        }
        if (view || fillFull) {
            fillImg = fresh(fillImg, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = hq(fillImg);
            g.setComposite(AlphaComposite.Clear);
            g.fillRect(0, 0, W, H);
            g.setComposite(AlphaComposite.SrcOver);
            g.translate(ox, oy);
            g.scale(zoom, zoom);
            for (int p = 0; p < fillArgb.length; p++) {
                if ((fillArgb[p] >>> 24) == 0 || !worldView.intersects(shapes.bounds[p])) continue;
                fillProvince(g, p);
            }
            g.dispose();
            chFill.clear(); fillFull = false;
        } else if (fillDirty && !chFill.isEmpty()) {
            Graphics2D g = hq(fillImg);
            for (int p : chFill) {
                Rectangle r = screenRect(shapes.bounds[p], 3).intersection(screen);
                if (r.isEmpty()) continue;
                g.setTransform(new AffineTransform());
                g.setClip(r);
                g.setComposite(AlphaComposite.Clear);
                g.fillRect(r.x, r.y, r.width, r.height);
                g.setComposite(AlphaComposite.SrcOver);
                g.translate(ox, oy);
                g.scale(zoom, zoom);
                Rectangle2D wr = worldRect(r);
                for (int q = 0; q < fillArgb.length; q++)
                    if ((fillArgb[q] >>> 24) != 0 && wr.intersects(shapes.bounds[q])) fillProvince(g, q);
            }
            g.dispose();
            chFill.clear();
        }
        if (view || borderFull) {
            borderImg = fresh(borderImg, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = hq(borderImg);
            g.setComposite(AlphaComposite.Clear);
            g.fillRect(0, 0, W, H);
            g.setComposite(AlphaComposite.SrcOver);
            g.translate(ox, oy);
            g.scale(zoom, zoom);
            drawArcPasses(g, worldView);
            g.dispose();
            chArc.clear(); borderFull = false;
        } else if (borderDirty && !chArc.isEmpty()) {
            Graphics2D g = hq(borderImg);
            for (int a : chArc) {
                Rectangle r = screenRect(shapes.arcBounds[a], 6).intersection(screen);
                if (r.isEmpty()) continue;
                g.setTransform(new AffineTransform());
                g.setClip(r);
                g.setComposite(AlphaComposite.Clear);
                g.fillRect(r.x, r.y, r.width, r.height);
                g.setComposite(AlphaComposite.SrcOver);
                g.translate(ox, oy);
                g.scale(zoom, zoom);
                drawArcPasses(g, worldRect(r));
            }
            g.dispose();
            chArc.clear();
        }
        if (view) { layerOx = ox; layerOy = oy; layerZoom = zoom; fillFull = false; borderFull = false; }
        viewDirty = fillDirty = borderDirty = false;
        renderNs += System.nanoTime() - t0; renders++;
    }

    private void strokeArcs(Graphics2D g, Rectangle2D view, int cls, Color c, double px) {
        g.setColor(c);
        g.setStroke(new BasicStroke((float) (px / zoom), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int a = 0; a < arcClass.length; a++)
            if (arcClass[a] == cls && view.intersects(shapes.arcBounds[a])) g.draw(shapes.arcPath[a]);
    }

    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0;
        if (!fitted && getWidth() > 0) {
            zoom = Math.max(0.5, getWidth() / (double) w.width);
            ox = 0; oy = (getHeight() - w.height * zoom) / 2;
            fitted = true; viewDirty = true;
        }
        if (viewDirty || fillDirty || borderDirty || baseImg == null || baseImg.getWidth() != getWidth() || baseImg.getHeight() != getHeight() || layerZoom != zoom) renderLayers();
        int dx = (int) Math.round(ox - layerOx), dy = (int) Math.round(oy - layerOy);
        g.drawImage(baseImg, dx, dy, null);
        g.drawImage(fillImg, dx, dy, null);
        g.drawImage(borderImg, dx, dy, null);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        hits.clear();
        drawHighlights(g);
        drawLabels(g);
        drawCapitals(g);
        drawPath(g);
        drawArmies(g);
        if (mode == Mode.INTEL) drawLegend(g);
        drawControls(g);
        drawTooltip(g);
        drawToasts(g);
        if (showPerf) { g.setFont(Fonts.ui(12.5f)); g.setColor(new Color(0, 0, 0, 170)); g.fillRoundRect(10, 10, 560, 24, 8, 8); g.setColor(new Color(150, 255, 170)); g.drawString(perf, 18, 27); }
        if (tutorial != null) tutorial.paint(g, getWidth(), getHeight());
    }

    /** Screen position of a point given in map units (used by tests to click exact pixels). */
    Point2D.Double screenOfMapPoint(double mx, double my) { return new Point2D.Double(mx * zoom + ox, my * zoom + oy); }

    /** Screen position of a province centre (also used by tests to aim synthetic clicks). */
    Point2D.Double screenOf(int p) { return screen(p); }

    private Point2D.Double screen(int p) {
        WorldData.ProvinceInfo pi = w.provinces.get(p);
        return new Point2D.Double(pi.cx * zoom + ox, pi.cy * zoom + oy);
    }

    private void drawHighlights(Graphics2D g) {
        AffineTransform old = g.getTransform();
        g.translate(ox, oy); g.scale(zoom, zoom);
        if (hoverProv >= 0 && hoverProv != selProv) { g.setColor(new Color(255, 255, 255, 48)); g.fill(shapes.fill[hoverProv]); }
        if (selProv >= 0) {
            g.setColor(new Color(255, 255, 255, 40)); g.fill(shapes.fill[selProv]);
            g.setStroke(new BasicStroke((float) (5.0 / zoom), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(255, 236, 140, 70)); g.draw(shapes.fill[selProv]);
            g.setStroke(new BasicStroke((float) (1.8 / zoom), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(255, 244, 190, 255)); g.draw(shapes.fill[selProv]);
        }
        g.setTransform(old);
    }

    private void drawLabels(Graphics2D g) {
        double fade = zoom > 7 ? Math.max(0, 1 - (zoom - 7) / 4) : 1;
        if (fade > 0.02) {
            for (Label l : labels) {
                double px = l.width * zoom;
                int n = l.text.length();
                double size = Math.min(34, px / (n * 0.56 + 1));
                if (size < 9) continue;
                double sx = l.cx * zoom + ox, sy = l.cy * zoom + oy;
                if (sx < -200 || sy < -50 || sx > getWidth() + 200 || sy > getHeight() + 50) continue;
                drawTracked(g, l.text, sx, sy, (float) size, size * 0.14, new Color(255, 255, 255, (int) (225 * fade)), new Color(6, 12, 24, (int) (180 * fade)));
            }
        }
        if (zoom >= 4.5) {
            for (WorldData.ProvinceInfo p : w.provinces) {
                if (p.sea || shapes.bounds[p.id].width * zoom < 56) continue;
                double sx = p.cx * zoom + ox, sy = p.cy * zoom + oy;
                if (sx < -100 || sy < -30 || sx > getWidth() + 100 || sy > getHeight() + 30) continue;
                String name = p.name;
                int ko = gs.intelOf(gs.player).knownOwner[p.id];
                if (ko < 0) continue;
                drawTracked(g, name.toUpperCase(), sx, sy + 12, 11.5f, 0.8, new Color(255, 255, 255, 190), new Color(6, 12, 24, 170));
            }
        }
    }

    private final Map<String, Shape> textCache = new HashMap<>();

    /** Outline of tracked text centred on (0, 0); cached because glyph layout is the slowest thing in a frame. */
    private Shape trackedShape(Graphics2D g, String text, int sizePx, int trackingTenths) {
        String key = text + "|" + sizePx + "|" + trackingTenths;
        Shape cached = textCache.get(key);
        if (cached != null) return cached;
        Font f = Fonts.label(sizePx);
        FontMetrics fm = g.getFontMetrics(f);
        double tracking = trackingTenths / 10.0, total = 0;
        for (char ch : text.toCharArray()) total += fm.charWidth(ch) + tracking;
        total -= tracking;
        double x = -total / 2, y = fm.getAscent() * 0.35;
        Path2D.Double path = new Path2D.Double();
        for (char ch : text.toCharArray()) {
            GlyphVector gv = f.createGlyphVector(g.getFontRenderContext(), String.valueOf(ch));
            path.append(gv.getOutline((float) x, (float) y), false);
            x += fm.charWidth(ch) + tracking;
        }
        if (textCache.size() > 4000) textCache.clear();
        textCache.put(key, path);
        return path;
    }

    private void drawTracked(Graphics2D g, String text, double cx, double cy, float size, double tracking, Color fill, Color halo) {
        Shape s = trackedShape(g, text, Math.round(size), (int) Math.round(tracking * 10));
        AffineTransform old = g.getTransform();
        g.translate(cx, cy);
        g.setColor(halo);
        g.setStroke(new BasicStroke(Math.max(2f, size / 5f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(s);
        g.setColor(fill);
        g.fill(s);
        g.setTransform(old);
    }

    private static Path2D.Double star(double cx, double cy, double r) {
        Path2D.Double s = new Path2D.Double();
        for (int i = 0; i < 10; i++) {
            double ang = -Math.PI / 2 + i * Math.PI / 5, rad = i % 2 == 0 ? r : r * 0.45;
            double x = cx + Math.cos(ang) * rad, y = cy + Math.sin(ang) * rad;
            if (i == 0) s.moveTo(x, y); else s.lineTo(x, y);
        }
        s.closePath();
        return s;
    }

    /** Capitals are drawn as stars above the province centre: gold and larger for yours, a red cross-star if lost. */
    private void drawCapitals(Graphics2D g) {
        capRects.clear(); capNations.clear();
        if (zoom < 1.25) return;
        Intel in = gs.intelOf(gs.player);
        for (WorldData.NationInfo n : w.nations) {
            int p = n.capital;
            if (p < 0) continue;
            Point2D.Double s = screen(p);
            if (s.x < -30 || s.y < -30 || s.x > getWidth() + 30 || s.y > getHeight() + 30) continue;
            boolean mine = n.index == gs.player, selected = p == selProv;
            if (!mine && !selected && zoom < 1.8) continue;
            int knownOwner = in.knownOwner[p];
            boolean lost = knownOwner >= 0 && knownOwner != n.index;
            double r = mine ? 11 : 8;
            double cy = s.y - (zoom > 2.5 ? 30 : 20);
            Path2D.Double star = star(s.x, cy, r);
            g.setColor(new Color(0, 0, 0, 120));
            g.fill(star(s.x + 1.5, cy + 2, r));
            g.setColor(lost ? new Color(200, 60, 52) : mine ? new Color(255, 214, 90) : new Color(245, 245, 250));
            g.fill(star);
            g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(10, 14, 24, 235));
            g.draw(star);
            capRects.add(new Rectangle((int) (s.x - r - 5), (int) (cy - r - 5), (int) (2 * r + 10), (int) (2 * r + 10)));
            capNations.add(n.index);
            if (mine) { g.setColor(new Color(255, 214, 90, 150)); g.setStroke(new BasicStroke(1.5f)); g.draw(new Ellipse2D.Double(s.x - r - 4, cy - r - 4, 2 * r + 8, 2 * r + 8)); }
        }
    }

    private void drawPath(Graphics2D g) {
        Army a = gs.armies.get(selArmy);
        if (a == null || a.owner != gs.player || a.path.isEmpty()) return;
        Point2D.Double prev = screen(a.province);
        g.setStroke(new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(0, 0, 0, 110));
        Path2D.Double route = new Path2D.Double();
        route.moveTo(prev.x, prev.y);
        for (int p : a.path) { Point2D.Double s = screen(p); route.lineTo(s.x, s.y); prev = s; }
        g.draw(route);
        g.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[]{7, 5}, 0));
        g.setColor(new Color(255, 232, 110, 235));
        g.draw(route);
        g.setColor(new Color(255, 232, 110));
        g.fill(new Ellipse2D.Double(prev.x - 4.5, prev.y - 4.5, 9, 9));
    }

    private void drawArmies(Graphics2D g) {
        Intel in = gs.intelOf(gs.player);
        int me = gs.player;
        Map<Integer, Integer> slot = new HashMap<>();
        for (Army a : gs.armies.values()) {
            boolean own = a.owner == me;
            boolean allied = !own && a.owner >= 0 && gs.isAlly(me, a.owner) && in.quality[a.province] >= 2;
            if (!own && !allied) continue;
            drawMarker(g, a.province, slot, w.nations.get(a.owner), a.decoy ? a.fake + "*" : String.valueOf((int) Math.round(a.totalUnits())),
                    a.id, own, 1f, a.battleId >= 0, false);
        }
        for (Intel.Sighting s : in.activeSightings(gs.day)) {
            if (s.quality <= 1 && zoom < 4.5) continue;   // bare contacts only clutter the strategic view
            double conf = Math.max(0.3, Intel.confidence(gs.day - s.day) * 1.1);
            WorldData.NationInfo ni = s.ownerKnown && s.owner >= 0 ? w.nations.get(s.owner) : null;
            String text = s.decoy ? "decoy" : s.quality >= 3 ? String.valueOf((int) Math.round(Math.max(1, sum(s.composition))))
                    : s.quality == 2 ? "~" + (int) Math.max(1, Math.round(s.strengthEstimate / 11.0)) : "?";
            drawMarker(g, s.province, slot, ni, text, s.armyId, false, (float) Math.min(1.0, conf), false, gs.day != s.day);
        }
    }

    private static double sum(double[] a) { double s = 0; for (double v : a) s += v; return s; }

    /** Army marker: the nation's flag with a number pill underneath. Unknown owners get a plain grey flag. */
    private void drawMarker(Graphics2D g, int prov, Map<Integer, Integer> slot, WorldData.NationInfo nation, String text,
                            int armyId, boolean own, float alpha, boolean inBattle, boolean stale) {
        Point2D.Double s = screen(prov);
        if (s.x < -50 || s.y < -50 || s.x > getWidth() + 50 || s.y > getHeight() + 50) return;
        double sz = Math.max(0.85, Math.min(1.35, 0.74 + zoom * 0.1));
        int fw = (int) (27 * sz), fh = Math.round(fw * 0.75f), ph = (int) (14 * sz);
        int k = slot.merge(prov, 1, Integer::sum) - 1;
        int x = (int) s.x - fw / 2 + k * (fw + 5), y = (int) s.y - fh / 2 - k * 2;
        Composite old = g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
        boolean selected = armyId == selArmy;
        g.setColor(new Color(0, 0, 0, 100));
        g.fill(new RoundRectangle2D.Double(x + 1.5, y + 3, fw, fh + ph / 2.0, 6, 6));
        if (inBattle) { g.setColor(new Color(255, 70, 50, 150)); g.setStroke(new BasicStroke(6f)); g.draw(new RoundRectangle2D.Double(x, y, fw, fh, 6, 6)); }
        if (selected) { g.setColor(new Color(255, 230, 90, 170)); g.setStroke(new BasicStroke(6f)); g.draw(new RoundRectangle2D.Double(x, y, fw, fh, 6, 6)); }
        g.drawImage(Assets.flag(nation, fw), x, y, null);
        g.setStroke(stale && !own ? new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{3, 3}, 0) : new BasicStroke(own ? 2f : 1.2f));
        g.setColor(selected ? new Color(255, 230, 90) : own ? Color.WHITE : new Color(14, 18, 26, 230));
        g.draw(new RoundRectangle2D.Double(x, y, fw, fh, 6, 6));
        Font f = Fonts.labelBold((float) (12.5 * sz));
        g.setFont(f);
        FontMetrics fm = g.getFontMetrics();
        int pw = Math.max(fw - 8, fm.stringWidth(text) + 12), px = x + (fw - pw) / 2, py = y + fh - 2;
        g.setColor(new Color(10, 14, 22, 235));
        g.fill(new RoundRectangle2D.Double(px, py, pw, ph, ph, ph));
        g.setColor(own ? new Color(255, 255, 255, 200) : new Color(255, 255, 255, 90));
        g.setStroke(new BasicStroke(1f));
        g.draw(new RoundRectangle2D.Double(px, py, pw, ph, ph, ph));
        g.setColor(Color.WHITE);
        g.drawString(text, px + (pw - fm.stringWidth(text)) / 2, py + (ph + fm.getAscent()) / 2 - 2);
        g.setComposite(old);
        hits.add(new Hit(new Rectangle(x, y, fw, fh + ph), armyId, own));
    }

    private void drawLegend(Graphics2D g) {
        String t = "green exact  -  lime estimate  -  amber contact  -  dark unseen (memory fades)";
        g.setFont(Fonts.label(13f));
        FontMetrics fm = g.getFontMetrics();
        int wd = fm.stringWidth(t) + 24;
        g.setColor(new Color(8, 14, 26, 190));
        g.fillRoundRect((getWidth() - wd) / 2, getHeight() - 132, wd, 24, 10, 10);
        g.setColor(new Color(255, 255, 255, 40));
        g.drawRoundRect((getWidth() - wd) / 2, getHeight() - 132, wd, 24, 10, 10);
        g.setColor(new Color(225, 232, 244));
        g.drawString(t, (getWidth() - wd) / 2 + 12, getHeight() - 115);
    }

    // ------------------------------------------------------------------ input
    private int provinceAt(int sx, int sy) {
        int px = (int) ((sx - ox) / zoom), py = (int) ((sy - oy) / zoom);
        if (px < 0 || py < 0 || px >= w.width || py >= w.height) return -1;
        return w.idMap[py * w.width + px];
    }

    private void click(MouseEvent e) {
        if (tutorial != null && tutorial.click(e.getPoint())) { repaint(); return; }
        boolean left = SwingUtilities.isLeftMouseButton(e), right = SwingUtilities.isRightMouseButton(e);
        if (left) {
            int ov = overlayAt(e.getPoint());
            if (ov >= 0) { overlayAction(ov); return; }
            int cap = capitalAt(e.getPoint());
            if (cap >= 0) { Audio.get().play(Synth.Sfx.CLICK); onCapital.accept(cap); return; }
            for (int i = hits.size() - 1; i >= 0; i--) {
                Hit h = hits.get(i);
                if (h.r.contains(e.getPoint())) {
                    selArmy = h.armyId; selProv = gs.armies.containsKey(h.armyId) ? gs.armies.get(h.armyId).province : selProv;
                    Intel.Sighting sg = gs.intelOf(gs.player).sightingOf(h.armyId);
                    if (sg != null) selProv = sg.province;
                    Audio.get().play(Synth.Sfx.CLICK);
                    onSelect.run(); repaint(); return;
                }
            }
        }
        int id = provinceAt(e.getX(), e.getY());
        if (id < 0) return;
        if (left && raidArmy >= 0) {
            Army raider = gs.armies.get(raidArmy);
            raidArmy = -1;
            if (raider != null) {
                GameState.Result r = gs.raid(raider, id);
                Audio.get().play(r.ok ? Synth.Sfx.CONFIRM : Synth.Sfx.ERROR);
                toast(r.ok ? "Raid successful" : "Raid", r.message, "special");
                onOrder.run();
            }
            repaint();
            return;
        }
        Army sel = gs.armies.get(selArmy);
        if (right) {
            // right-click orders the selected army to march
            if (sel != null && sel.owner == gs.player) {
                GameState.Result r = gs.orderMove(sel, id);
                Audio.get().play(r.ok ? Synth.Sfx.CONFIRM : Synth.Sfx.ERROR);
                if (!r.ok) toast("Cannot march", r.message, "battle");
                onOrder.run(); repaint();
            }
            return;
        }
        selProv = id; selArmy = -1;                      // left-click on ground: inspect the province, drop the army selection
        Audio.get().play(Synth.Sfx.CLICK);
        onSelect.run(); repaint();
    }

    // ------------------------------------------------------------------ floating controls
    private int overlayAt(Point p) {
        for (int i = 0; i < modeRects.size(); i++) if (modeRects.get(i).contains(p)) return i;
        if (zoomInRect.contains(p)) return 10;
        if (zoomOutRect.contains(p)) return 11;
        if (homeRect.contains(p)) return 12;
        return -1;
    }

    private void overlayAction(int i) {
        Audio.get().play(Synth.Sfx.CLICK);
        if (i < Mode.values().length) setMode(Mode.values()[i]);
        else if (i == 10) zoomBy(1.4);
        else if (i == 11) zoomBy(1 / 1.4);
        else if (i == 12) centerOn(w.nations.get(gs.player).capital, Math.max(zoom, 2.6));
    }

    private int capitalAt(Point p) {
        for (int i = 0; i < capRects.size(); i++) if (capRects.get(i).contains(p)) return capNations.get(i);
        return -1;
    }

    private void drawControls(Graphics2D g) {
        modeRects.clear();
        Mode[] modes = Mode.values();
        int bw = 104, bh = 32, gap = 4, total = modes.length * bw + (modes.length - 1) * gap + 16;
        int x0 = (getWidth() - total) / 2, y0 = getHeight() - bh - 22;
        g.setColor(new Color(0, 0, 0, 90));
        g.fillRoundRect(x0 + 2, y0 + 3, total, bh + 12, 22, 22);
        g.setColor(new Color(10, 16, 28, 228));
        g.fillRoundRect(x0, y0 - 2, total, bh + 12, 22, 22);
        g.setColor(new Color(255, 255, 255, 36));
        g.setStroke(new BasicStroke(1f));
        g.drawRoundRect(x0, y0 - 2, total, bh + 12, 22, 22);
        g.setFont(Fonts.label(14.5f));
        for (int i = 0; i < modes.length; i++) {
            Rectangle r = new Rectangle(x0 + 8 + i * (bw + gap), y0 + 3, bw, bh);
            modeRects.add(r);
            boolean on = modes[i] == mode, hov = hoverOverlay == i;
            g.setColor(on ? Ui.ACCENT : hov ? new Color(255, 255, 255, 30) : new Color(255, 255, 255, 12));
            g.fillRoundRect(r.x, r.y, r.width, r.height, 16, 16);
            g.setColor(on ? Ui.BG : Ui.TEXT);
            String label = modes[i].label.replace(" / Vision", "").toUpperCase();
            g.drawString(label, r.x + (r.width - g.getFontMetrics().stringWidth(label)) / 2, r.y + 21);
        }
        // zoom and home buttons, bottom right
        int bx = getWidth() - 52 - rightInset, by = getHeight() - 168;
        zoomInRect = new Rectangle(bx, by, 38, 38);
        zoomOutRect = new Rectangle(bx, by + 44, 38, 38);
        homeRect = new Rectangle(bx, by + 88, 38, 38);
        String[] glyph = {"+", "-", null};
        Rectangle[] rs = {zoomInRect, zoomOutRect, homeRect};
        for (int i = 0; i < 3; i++) {
            Rectangle r = rs[i];
            g.setColor(new Color(10, 16, 28, 228));
            g.fillRoundRect(r.x, r.y, r.width, r.height, 14, 14);
            g.setColor(hoverOverlay == 10 + i ? new Color(255, 255, 255, 50) : new Color(255, 255, 255, 24));
            g.drawRoundRect(r.x, r.y, r.width, r.height, 14, 14);
            if (glyph[i] != null) {
                g.setFont(Fonts.ui(24f));
                g.setColor(Ui.TEXT);
                g.drawString(glyph[i], r.x + (r.width - g.getFontMetrics().stringWidth(glyph[i])) / 2, r.y + 27);
            } else g.drawImage(Assets.icon("castle", 24, Ui.GOLD), r.x + 7, r.y + 7, null);
        }
    }

    /** Small info card that follows the cursor: who owns the province under it and how much we actually know. */
    private void drawTooltip(Graphics2D g) {
        if (mouse == null || hoverProv < 0 || hoverOverlay >= 0 || dragged) return;
        WorldData.ProvinceInfo pi = w.provinces.get(hoverProv);
        Intel in = gs.intelOf(gs.player);
        int ko = pi.sea ? -1 : in.knownOwner[hoverProv];
        int age = gs.day - in.lastSeen[hoverProv];
        String l1 = pi.name;
        String l2 = pi.sea ? "Sea zone" : (ko >= 0 ? gs.nationName(ko) : "Unknown owner") + "  -  " + pi.terrain.name().charAt(0) + pi.terrain.name().substring(1).toLowerCase();
        String l3 = in.quality[hoverProv] > 0 ? "Visible now" : age >= 40 ? "Stale atlas data" : "Last seen " + age + " days ago";
        String l4 = null;
        for (WorldData.NationInfo n : w.nations) if (n.capital == hoverProv) l4 = "Capital of " + n.name + (n.capitalName.isEmpty() ? "" : " (" + n.capitalName + ")");
        g.setFont(Fonts.ui(13.5f));
        FontMetrics fm = g.getFontMetrics();
        int wd = Math.max(Math.max(fm.stringWidth(l1) + 40, fm.stringWidth(l2)), Math.max(fm.stringWidth(l3), l4 == null ? 0 : fm.stringWidth(l4))) + 28;
        int h = 22 + 18 * (l4 == null ? 3 : 4) + 8;
        int x = Math.min(mouse.x + 18, getWidth() - wd - 8), y = Math.min(mouse.y + 20, getHeight() - h - 8);
        g.setColor(new Color(0, 0, 0, 100));
        g.fillRoundRect(x + 2, y + 3, wd, h, 14, 14);
        g.setColor(new Color(10, 16, 28, 238));
        g.fillRoundRect(x, y, wd, h, 14, 14);
        g.setColor(new Color(255, 255, 255, 40));
        g.drawRoundRect(x, y, wd, h, 14, 14);
        if (ko >= 0) g.drawImage(Assets.flag(w.nations.get(ko), 22), x + 10, y + 9, null);
        g.setFont(Fonts.labelBold(16f));
        g.setColor(Color.WHITE);
        g.drawString(l1, x + (ko >= 0 ? 40 : 12), y + 24);
        g.setFont(Fonts.ui(13f));
        g.setColor(new Color(214, 222, 238));
        int ty = y + 44;
        g.drawString(l2, x + 12, ty); ty += 18;
        g.setColor(in.quality[hoverProv] > 0 ? Ui.GREEN : Ui.AMBER);
        g.drawString(l3, x + 12, ty); ty += 18;
        if (l4 != null) { g.setColor(Ui.GOLD); g.drawString(l4, x + 12, ty); }
    }
}
