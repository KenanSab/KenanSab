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
import java.util.Iterator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;

/**
 * The world map. Provinces are smooth antialiased vector shapes over a procedural satellite-style underlay.
 * The heavy layer (underlay, fills, borders) is cached and only re-rendered when the view or the fog changes.
 */
final class MapPanel extends JPanel {
    enum Mode {
        POLITICAL("Political"), TERRAIN("Terrain"), INTEL("Intel / Vision"), DIPLOMATIC("Diplomatic"),
        POPULATION("Population"), ECONOMY("Economy"), UNREST("Unrest"), RESOURCES("Resources");
        final String label;
        Mode(String l) { label = l; }
    }

    private record Hit(Rectangle r, int armyId, boolean own) {}
    private record Label(String text, double cx, double cy, double width) {}

    final GameState gs;
    final WorldData w;
    final MapShapes shapes;
    final Basemap basemap;
    final MapExtras extras;
    private final MapLayers layers;
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
    private double targetZoom = -1, velX, velY;
    private Point anchor = new Point(0, 0);
    private final javax.swing.Timer animTimer = new javax.swing.Timer(16, e -> animate());

    // ---- cached layers: rendered on a worker thread, patched in place when the fog changes
    private volatile MapLayers.Result cur;
    private boolean rendering, wantRender;
    private long epoch;
    private static final java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor(rn -> {
        Thread t = new Thread(rn, "map-layers"); t.setDaemon(true); t.setPriority(Thread.NORM_PRIORITY - 1); return t;
    });
    private final boolean[] markedFill, markedArc;
    private int[] lastOwner;
    private final List<long[]> flashes = new ArrayList<>();      // {province, startMillis}: a province that just changed hands lights up
    private final List<Integer> pendFill = new ArrayList<>(), pendArc = new ArrayList<>();
    long renderNs, renders, paintNs, paints;
    final long[] seg = new long[8];   // profiling: base blit, fx, highlights, province names, cities, armies/paths, controls

    @Override public void addNotify() { super.addNotify(); animTimer.start(); }
    @Override public void removeNotify() { animTimer.stop(); super.removeNotify(); }

    /** Eases wheel zoom toward its target, coasts after a drag, and keeps the sea moving. */
    private void animate() {
        if (!isShowing()) return;
        boolean moving = false;
        if (targetZoom > 0) {
            double nz = zoom + (targetZoom - zoom) * 0.30;
            if (Math.abs(nz - targetZoom) < targetZoom * 0.003) { nz = targetZoom; targetZoom = -1; }
            ox = anchor.x - (anchor.x - ox) * nz / zoom;
            oy = anchor.y - (anchor.y - oy) * nz / zoom;
            zoom = nz; moving = true;
        }
        if (dragFrom == null && (Math.abs(velX) > 0.4 || Math.abs(velY) > 0.4)) {
            ox += velX; oy += velY; velX *= 0.90; velY *= 0.90; moving = true;
        }
        if (moving) clampView();
        int delay = moving ? 16 : 40;
        if (animTimer.getDelay() != delay) animTimer.setDelay(delay);
        repaint();
    }

    /** Keeps the world on screen: zoom stays within its limits and at most a thin strip of empty space shows beyond the map edge. */
    private void clampView() {
        int W = getWidth(), H = getHeight();
        if (W < 2 || H < 2) return;
        double min = minZoom();
        if (zoom < min) {                                  // e.g. the window was enlarged: keep the centre where it was
            double cx = (W / 2.0 - ox) / zoom, cy = (H / 2.0 - oy) / zoom;
            zoom = min; ox = W / 2.0 - cx * zoom; oy = H / 2.0 - cy * zoom;
        }
        if (zoom > MAX_ZOOM) zoom = MAX_ZOOM;
        if (targetZoom > 0) targetZoom = Math.max(min, Math.min(MAX_ZOOM, targetZoom));
        double mw = w.width * zoom, mh = w.height * zoom;
        ox = Math.max(W - mw - 80, Math.min(80, ox));
        oy = Math.max(H - mh - 90, Math.min(90, oy));
    }

    /** The map opens 5x closer than the old 3.0 start view (about 20 degrees of longitude across a 1500 px window). */
    static final double START_ZOOM = 15.0, MAX_ZOOM = 32.0;
    private int pendingCenter = -1;
    private double pendingZoom;

    /** Zoomed out as far as it goes: the world still covers the whole window, so there is never dead space around it. */
    double minZoom() {
        int W = Math.max(2, getWidth()), H = Math.max(2, getHeight());
        return Math.max(Math.max(W / (double) w.width, H / (double) w.height), 0.3);
    }

    private double clampZoom(double z) { return Math.max(minZoom(), Math.min(MAX_ZOOM, z)); }

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
        int y = Hud.BAR_H + 14 + (brushActive() ? 92 : 0);
        for (Toast t : toasts) {
            double life = (t.until - now) / 5200.0;
            float a = (float) Math.max(0, Math.min(1, Math.min(life * 6, (1 - life) * 8)));
            int w = 380, x = getWidth() - w - 14 - rightInset;
            g.setFont(Fonts.ui(12f));
            FontMetrics fm = g.getFontMetrics();
            List<String> lines = new ArrayList<>();
            StringBuilder cur = new StringBuilder();
            for (String word : t.text.split(" ")) {
                if (fm.stringWidth(cur + " " + word) > w - 76 && cur.length() > 0) { lines.add(cur.toString()); cur = new StringBuilder(); }
                if (cur.length() > 0) cur.append(' ');
                cur.append(word);
            }
            if (cur.length() > 0) lines.add(cur.toString());
            if (lines.size() > 3) { lines = new ArrayList<>(lines.subList(0, 3)); lines.set(2, lines.get(2) + "..."); }
            int h = 52 + Math.max(1, lines.size()) * 15;
            Composite old = g.getComposite();
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, a));
            Skin.shadow(g, x, y, w, h, 8, 5, 110);
            Skin.plate(g, new RoundRectangle2D.Float(x, y, w, h, 8, 8), Skin.Kind.PANEL, 1f);
            Skin.frame(g, x + 0.5f, y + 0.5f, w - 1, h - 1, 8, true, true);
            g.drawImage(Assets.icon(t.icon, 36, Skin.BRASS), x + 16, y + 14, null);
            g.setFont(Fonts.label(12.5f));
            g.setColor(Skin.BRASS);
            g.drawString(t.header, x + 62, y + 22);
            g.setFont(Fonts.title(21f));
            g.setColor(Color.WHITE);
            g.drawString(t.title.toUpperCase(), x + 62, y + 42);
            g.setFont(Fonts.ui(12f));
            g.setColor(new Color(190, 189, 183));
            int ty = y + 58;
            for (String line : lines) { g.drawString(line, x + 62, ty); ty += 15; }
            g.setComposite(old);
            y += h + 8;
        }
    }

    MapPanel(GameState gs, Path mapDir) {
        this.gs = gs; this.w = gs.world;
        setBackground(MapLayers.BACKGROUND);
        NationColors.init(gs.world);
        shapes = WarfareApp.shapes(w);
        basemap = WarfareApp.basemap(w);
        extras = WarfareApp.extras(w);
        layers = WarfareApp.layers(w);
        fillArgb = new int[w.provinces.size()];
        arcClass = new byte[shapes.p1.length];
        markedFill = new boolean[fillArgb.length];
        markedArc = new boolean[arcClass.length];
        MouseAdapter ma = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                dragFrom = e.getPoint(); dragged = false; velX = velY = 0;
                if (brushActive() && brushBarAt(e.getPoint()) < 0) { paintBrush(e.getPoint(), SwingUtilities.isRightMouseButton(e)); dragged = true; }
            }
            @Override public void mouseDragged(MouseEvent e) {
                if (brushActive()) { mouse = e.getPoint(); if (brushBarAt(e.getPoint()) < 0) paintBrush(e.getPoint(), SwingUtilities.isRightMouseButton(e)); dragged = true; repaint(); return; }
                if (dragFrom == null || SwingUtilities.isRightMouseButton(e)) return;
                mouse = e.getPoint();
                int dx = e.getX() - dragFrom.x, dy = e.getY() - dragFrom.y;
                if (Math.abs(dx) + Math.abs(dy) > 3) dragged = true;
                if (dragged) { ox += dx; oy += dy; clampView(); velX = dx * 0.6 + velX * 0.4; velY = dy * 0.6 + velY * 0.4; dragFrom = e.getPoint(); repaint(); }
            }
            @Override public void mouseReleased(MouseEvent e) {
                if (!dragged) click(e); else repaint();
                if (dragged && Math.abs(velX) + Math.abs(velY) < 2) velX = velY = 0;
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
                double f = e.getWheelRotation() < 0 ? 1.3 : 1 / 1.3;
                targetZoom = clampZoom((targetZoom > 0 ? targetZoom : zoom) * f);
                anchor = e.getPoint(); velX = velY = 0;
            }
        };
        addMouseListener(ma); addMouseMotionListener(ma); addMouseWheelListener(ma);
        refreshColors();
    }

    void centerOn(int province, double z) {
        if (getWidth() < 2 || getHeight() < 2) { pendingCenter = province; pendingZoom = z; fitted = true; return; }   // not laid out yet: apply on first paint
        WorldData.ProvinceInfo p = w.provinces.get(province);
        targetZoom = -1; velX = velY = 0;
        zoom = clampZoom(z); ox = getWidth() / 2.0 - p.cx * zoom; oy = getHeight() / 2.0 - p.cy * zoom; fitted = true; clampView(); repaint();
    }

    void viewChanged() { repaint(); }

    void pan(double dx, double dy) { ox += dx; oy += dy; clampView(); repaint(); }

    void zoomBy(double f) {
        anchor = new Point(getWidth() / 2, getHeight() / 2);
        targetZoom = clampZoom((targetZoom > 0 ? targetZoom : zoom) * f);
        velX = velY = 0;
    }

    void setMode(Mode m) { mode = m; Arrays.fill(fillArgb, 1); refreshColors(); repaint(); }

    // ------------------------------------------------------------------ colours (fog of war lives here)
    private void markFill(int p) { if (!markedFill[p]) { markedFill[p] = true; pendFill.add(p); } }
    private void markArc(int a) { if (!markedArc[a]) { markedArc[a] = true; pendArc.add(a); } }
    private void clearPending() {
        for (int p : pendFill) markedFill[p] = false;
        for (int a : pendArc) markedArc[a] = false;
        pendFill.clear(); pendArc.clear();
    }

    void refreshColors() {
        Intel in = gs.intelOf(gs.player);
        int me = gs.player;
        if (lastOwner == null) { lastOwner = new int[fillArgb.length]; Arrays.fill(lastOwner, -2); }
        long nowMs = System.currentTimeMillis();
        for (int p = 0; p < lastOwner.length; p++) {
            int ko = in.knownOwner[p];
            if (lastOwner[p] >= 0 && ko >= 0 && ko != lastOwner[p] && in.quality[p] > 0 && flashes.size() < 40) flashes.add(new long[]{p, nowMs});
            lastOwner[p] = ko;
        }
        for (int p = 0; p < fillArgb.length; p++) {
            int c = colorOf(p, in, me);
            if (c != fillArgb[p]) { fillArgb[p] = c; markFill(p); }
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
            if (cls != arcClass[a]) { arcClass[a] = cls; markArc(a); }
        }
        applyPending();
    }

    /** Patches the cached layers in place for small changes; hands big ones (mode switch, big conquests) to the worker. */
    private void applyPending() {
        if (pendFill.isEmpty() && pendArc.isEmpty()) return;
        MapLayers.Result r = cur;
        if (r == null || rendering) return;                 // the next adopted render diffs against its snapshot
        if (pendFill.size() > 140 || pendArc.size() > 90) { clearPending(); requestRender(); return; }
        Rectangle dirty = null;
        if (!pendFill.isEmpty()) dirty = union(dirty, layers.updateFill(r, fillArgb, pendFill));
        if (!pendArc.isEmpty()) dirty = union(dirty, layers.updateLines(r, arcClass, pendArc));
        clearPending();
        if (dirty != null) layers.composite(r, dirty);
        repaint();
    }

    private static Rectangle union(Rectangle a, Rectangle b) { return a == null ? b : b == null ? a : a.union(b); }

    /** How much of the possible snow lies in a hemisphere on the game date: peaks in midwinter, steps of 0.08 so the map is re-lit rarely. */
    double seasonSnow(boolean south) {
        int doy = gs.date().getDayOfYear();
        double phase = (doy - 20 - (south ? 182 : 0)) / 365.0 * 2 * Math.PI;
        double winter = Math.max(0, Math.min(1, 0.5 + 0.62 * Math.cos(phase)));
        return Math.round(winter / 0.08) * 0.08;
    }

    private MapLayers.Params snapshot() {
        return new MapLayers.Params(zoom, ox, oy, getWidth(), getHeight(), fillArgb.clone(), arcClass.clone(), seasonSnow(false), seasonSnow(true), mode == Mode.TERRAIN ? 0.9 : 0.5);
    }

    /** Starts a background render for the current view (at most one in flight). */
    private void requestRender() {
        if (getWidth() < 2 || getHeight() < 2) return;
        if (rendering) { wantRender = true; return; }
        rendering = true; wantRender = false;
        final long ep = epoch;
        final MapLayers.Params p = snapshot();
        worker.submit(() -> {
            MapLayers.Result res = null; Throwable err = null;
            long t0 = System.nanoTime();
            try { res = layers.render(p); } catch (Throwable t) { err = t; }
            final MapLayers.Result done = res; final Throwable failed = err; final long took = System.nanoTime() - t0;
            SwingUtilities.invokeLater(() -> {
                rendering = false;
                if (failed != null) throw new RuntimeException("map layer render failed", failed);
                renderNs += took; renders++;
                if (ep == epoch) adopt(done);
                if (wantRender || layersStale()) requestRender();
            });
        });
    }

    /** Synchronous render on the calling thread: first frame, screenshots and tests. */
    void renderNow() {
        if (getWidth() < 2 || getHeight() < 2) return;
        epoch++;
        long t0 = System.nanoTime();
        adopt(layers.render(snapshot()));
        renderNs += System.nanoTime() - t0; renders++;
    }

    private void adopt(MapLayers.Result r) {
        cur = r;
        clearPending();
        for (int p = 0; p < fillArgb.length; p++) if (r.fillSnap[p] != fillArgb[p]) markFill(p);
        for (int a = 0; a < arcClass.length; a++) if (r.arcSnap[a] != arcClass[a]) markArc(a);
        applyPending();
        repaint();
    }

    /** True when the cached layers no longer cover the view well enough: another zoom level, resized, or panned near the margin. */
    private boolean layersStale() {
        MapLayers.Result r = cur;
        int W = getWidth(), H = getHeight();
        if (r == null) return W > 1 && H > 1;
        if (r.vw != W || r.vh != H) return true;
        double s = zoom / r.zoom;
        if (Math.abs(s - 1) > 0.025) return true;
        if (r.snowN != seasonSnow(false) || r.snowS != seasonSnow(true)) return true;
        double tx = ox - s * (MapLayers.MARGIN + r.ox), ty = oy - s * (MapLayers.MARGIN + r.oy);
        double slack = Math.min(Math.min(-tx / s, r.iw - (W - tx) / s), Math.min(-ty / s, r.ih - (H - ty) / s));
        return slack < MapLayers.MARGIN * 0.4;
    }

    /** Blocks until the cached layers match the current view exactly (screenshots, tests). */
    void flushLayers() { renderNow(); }

    private double popScale = -1, gdpScale, oilScale;

    /** 95th-percentile values so a few giant provinces do not wash out the whole heat map. */
    private void heatScales() {
        if (popScale >= 0) return;
        List<Double> p = new ArrayList<>(), g2 = new ArrayList<>(), o = new ArrayList<>();
        for (WorldData.ProvinceInfo pi : w.provinces) if (!pi.sea) { p.add(pi.pop); g2.add(pi.gdp); if (pi.oil > 0) o.add(pi.oil); }
        java.util.Collections.sort(p); java.util.Collections.sort(g2); java.util.Collections.sort(o);
        popScale = Math.max(1, p.get((int) (p.size() * 0.95)));
        gdpScale = Math.max(1, g2.get((int) (g2.size() * 0.95)));
        oilScale = o.isEmpty() ? 1 : Math.max(1e-6, o.get((int) (o.size() * 0.9)));
    }

    private static final int[][] HEAT = {{30, 32, 42}, {74, 54, 112}, {178, 66, 98}, {238, 142, 62}, {255, 228, 126}};

    private static int heat(double t) {
        t = Math.max(0, Math.min(1, t)) * (HEAT.length - 1);
        int i = Math.min(HEAT.length - 2, (int) t);
        double f = t - i;
        int r = (int) (HEAT[i][0] + (HEAT[i + 1][0] - HEAT[i][0]) * f), g = (int) (HEAT[i][1] + (HEAT[i + 1][1] - HEAT[i][1]) * f), b = (int) (HEAT[i][2] + (HEAT[i + 1][2] - HEAT[i][2]) * f);
        return (r << 16) | (g << 8) | b;
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
            case POPULATION, ECONOMY, RESOURCES -> {
                heatScales();
                if (pi.sea) return q > 0 ? 0 : argb(0x3A4A5C, 0.10 + 0.3 * stale);
                int ko = in.knownOwner[p];
                if (ko < 0) return argb(0x6C6E70, 0.8);
                double v = mode == Mode.POPULATION ? Math.sqrt(pi.pop / popScale) : mode == Mode.ECONOMY ? Math.sqrt(pi.gdp * gs.devastation[p] / gdpScale) : pi.oil <= 0 ? 0 : 0.25 + 0.75 * Math.min(1, pi.oil / oilScale);
                return argb(NationColors.mix(heat(v), 0x6C6E70, q > 0 || ko == me ? 0 : 0.45 * stale + 0.2), q > 0 || ko == me ? 0.92 : 0.8);
            }
            case UNREST -> {
                if (pi.sea) return q > 0 ? 0 : argb(0x3A4A5C, 0.10 + 0.3 * stale);
                int ko = in.knownOwner[p];
                if (ko < 0) return argb(0x6C6E70, 0.8);
                if (ko != me && q < 2) return argb(0x6C6E70, 0.7);        // unrest is only visible where we can see clearly
                double u = Math.max(0, Math.min(1, gs.unrest[p] / 100.0));
                return argb(NationColors.mix(0x3C8C5A, 0xE04632, Math.sqrt(u)), 0.9);
            }
            default -> {
                if (pi.sea) return q > 0 ? 0 : argb(0x3A4A5C, 0.10 + 0.34 * stale);
                int ko = in.knownOwner[p];
                if (ko < 0) return argb(0x8C8E88, 0.85);
                int base = NationColors.fill(NationColors.of(w.nations.get(ko).tag));
                if (q > 0 || ko == me) return argb(base, 0.93);
                return argb(NationColors.mix(NationColors.scale(base, 0.92), 0x8A8A84, 0.6 * stale), 0.90 - 0.1 * stale);
            }
        }
    }

    // ------------------------------------------------------------------ painting
    private static final BufferedImage[] swell = new BufferedImage[2];
    private static BufferedImage cloudTile;

    /**
     * Tileable ocean swell: several crossing wave trains, lightly domain-warped, lit from the north-west. Sunlit faces
     * are white, shaded faces deep navy, so the sea shows relief in motion even over very dark water.
     */
    private static synchronized BufferedImage swellTile(int variant) {
        if (swell[variant] != null) return swell[variant];
        final int T = 256;
        double[][] k = variant == 0 ? new double[][]{{3, 1}, {-2, 4}, {4, -3}, {1, 7}, {-6, -3}, {7, 4}}
                                    : new double[][]{{1, 3}, {4, -1}, {-3, -4}, {6, 2}, {-2, 6}, {5, -6}};
        double[] amp = {1.0, 0.85, 0.7, 0.45, 0.3, 0.22}, ph = variant == 0 ? new double[]{0.3, 1.9, 4.1, 2.2, 5.0, 0.8} : new double[]{2.1, 0.4, 3.3, 5.5, 1.2, 4.4};
        double[] h = new double[T * T];
        for (int y = 0; y < T; y++)
            for (int x = 0; x < T; x++) {
                double u = 2 * Math.PI * x / T, v = 2 * Math.PI * y / T;
                double wu = 0.30 * Math.sin(u + 2 * v + 1), wv = 0.28 * Math.sin(2 * u - v);
                double sum = 0;
                for (int i = 0; i < k.length; i++) {
                    double sn = Math.sin(k[i][0] * (u + wu) + k[i][1] * (v + wv) + ph[i]);
                    sum += amp[i] * (1 - Math.abs(sn));                    // peaked crests, rounded troughs
                }
                h[y * T + x] = sum;
            }
        BufferedImage t = new BufferedImage(T, T, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < T; y++)
            for (int x = 0; x < T; x++) {
                double dx = h[y * T + (x + 1) % T] - h[y * T + (x + T - 1) % T], dy = h[((y + 1) % T) * T + x] - h[((y + T - 1) % T) * T + x];
                double lit = (-dx * 0.75 - dy * 0.55) * 1.5;                 // light from the north-west
                int argb;
                if (lit > 0) {
                    double a = Math.min(1, lit);
                    int al = (int) (Math.pow(a, 1.4) * 74 + Math.pow(a, 5) * 80);
                    argb = (Math.min(255, al) << 24) | 0xDDF0FF;
                } else {
                    double a = Math.min(1, -lit);
                    argb = ((int) (Math.pow(a, 1.3) * 70) << 24) | 0x031020;
                }
                t.setRGB(x, y, argb);
            }
        return swell[variant] = t;
    }

    /** Large soft blobs, tileable: drifting cloud shadows. */
    static BufferedImage cloudShadow() { return cloudTile(); }

    private static synchronized BufferedImage cloudTile() {
        if (cloudTile != null) return cloudTile;
        int T = 384;
        BufferedImage t = new BufferedImage(T, T, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < T; y++)
            for (int x = 0; x < T; x++) {
                double u = 2 * Math.PI * x / T, v = 2 * Math.PI * y / T;
                double n = 0.5 + 0.5 * (0.55 * Math.sin(u + 1.3 * Math.sin(v * 2 + 1)) * Math.cos(v + 0.8 * Math.sin(u * 2))
                        + 0.30 * Math.sin(2 * u + 3 * v + 2) * Math.cos(3 * u - v) + 0.15 * Math.sin(5 * u + 2 * v));
                double c = Math.max(0, Math.min(1, (n - 0.52) / 0.30));
                c = c * c * (3 - 2 * c);
                t.setRGB(x, y, ((int) (c * 70) << 24) | 0x08121E);
            }
        return cloudTile = t;
    }

    /** Blits a seamless tile over the visible rectangle, shifted by a fractional offset. */
    private static void tile(Graphics2D b, BufferedImage tex, double offX, double offY, Rectangle vis, float alpha) {
        int tw = tex.getWidth(), th = tex.getHeight();
        double fx = offX - Math.floor(offX / tw) * tw, fy = offY - Math.floor(offY / th) * th;
        b.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
        for (double y = fy - th; y < vis.y + vis.height; y += th) {
            if (y + th < vis.y) continue;
            for (double x = fx - tw; x < vis.x + vis.width; x += tw) {
                if (x + tw < vis.x) continue;
                b.drawImage(tex, AffineTransform.getTranslateInstance(x, y), null);
            }
        }
    }

    // ---- glitter: sparkles scattered over open water, each with its own twinkle
    private float[] glitterX, glitterY, glitterRate, glitterPhase;

    private void buildGlitter() {
        java.util.Random rnd = new java.util.Random(4242);
        BufferedImage mask = basemap.seaMask;
        int n = 0, want = 90000;
        float[] xs = new float[want], ys = new float[want], rt = new float[want], ph = new float[want];
        for (int tries = 0; tries < 220000 && n < want; tries++) {
            double x = rnd.nextDouble() * w.width, y = rnd.nextDouble() * w.height;
            int mx = (int) (x * Basemap.MASK_PX_PER_UNIT), my = (int) (y * Basemap.MASK_PX_PER_UNIT);
            if (mx >= mask.getWidth() || my >= mask.getHeight() || (mask.getRGB(mx, my) >>> 24) < 250) continue;
            xs[n] = (float) x; ys[n] = (float) y; rt[n] = (float) (1.2 + rnd.nextDouble() * 2.6); ph[n] = (float) (rnd.nextDouble() * 6.28); n++;
        }
        glitterX = Arrays.copyOf(xs, n); glitterY = Arrays.copyOf(ys, n); glitterRate = Arrays.copyOf(rt, n); glitterPhase = Arrays.copyOf(ph, n);
    }

    private void drawGlitter(Graphics2D g) {
        if (zoom > 24 || zoom < 0.9) return;
        if (glitterX == null) buildGlitter();
        double t = System.currentTimeMillis() / 1000.0;
        float fade = (float) Math.min(1, (24 - zoom) / 6.0);
        int stride = (int) Math.max(1, Math.min(80, Math.round(302 / (zoom * zoom))));
        for (int i = 0; i < glitterX.length; i += stride) {
            double sx = glitterX[i] * zoom + ox, sy = glitterY[i] * zoom + oy;
            if (sx < -4 || sy < -4 || sx > getWidth() + 4 || sy > getHeight() + 4) continue;
            double tw = Math.sin(t * glitterRate[i] + glitterPhase[i]);
            if (tw < 0.55) continue;
            float a = (float) ((tw - 0.55) / 0.45) * fade;
            g.setColor(new Color(1f, 1f, 1f, 0.55f * a));
            g.fillRect((int) sx, (int) sy, 2, 2);
            g.setColor(new Color(1f, 1f, 1f, 0.22f * a));
            g.fillRect((int) sx - 1, (int) sy, 4, 2);
            g.fillRect((int) sx, (int) sy - 1, 2, 4);
        }
    }

    private BufferedImage fxBuf;
    private MapLayers.Result fxFor;
    private long fxStamp;
    private static final int FX = MapLayers.FX_DIV;

    /** Animated swell, surf and cloud shadows, drawn at quarter resolution and scaled up (they are soft by nature). */
    private void drawFx(Graphics2D g, MapLayers.Result r, double s, double tx, double ty) {
        if (zoom > 28) return;
        BufferedImage mask = r.waveMask;
        if (fxBuf == null || fxBuf.getWidth() != mask.getWidth() || fxBuf.getHeight() != mask.getHeight()) { fxBuf = new BufferedImage(mask.getWidth(), mask.getHeight(), BufferedImage.TYPE_INT_ARGB); fxFor = null; }
        long now = System.currentTimeMillis();
        if (fxFor != r || now - fxStamp >= 45) { fxFor = r; fxStamp = now; renderFx(r, now, s, tx, ty); }
        float a = (float) Math.max(0, Math.min(1, (28 - zoom) / 8.0));
        Composite old = g.getComposite();
        if (a < 1) g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, a));
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(fxBuf, new AffineTransform(FX * s, 0, 0, FX * s, tx, ty), null);
        g.setComposite(old);
        drawGlitter(g);
    }

    private void renderFx(MapLayers.Result r, long now, double s, double tx, double ty) {
        int W = fxBuf.getWidth(), H = fxBuf.getHeight();
        // only the part of the buffer that is actually on screen needs painting
        int vx0 = (int) Math.max(0, Math.floor((-tx / s) / FX) - 2), vy0 = (int) Math.max(0, Math.floor((-ty / s) / FX) - 2);
        int vx1 = (int) Math.min(W, Math.ceil(((getWidth() - tx) / s) / FX) + 2), vy1 = (int) Math.min(H, Math.ceil(((getHeight() - ty) / s) / FX) + 2);
        Rectangle vis = new Rectangle(vx0, vy0, Math.max(1, vx1 - vx0), Math.max(1, vy1 - vy0));
        Graphics2D b = fxBuf.createGraphics();
        b.setComposite(AlphaComposite.Clear); b.fillRect(0, 0, W, H); b.setComposite(AlphaComposite.SrcOver);
        b.setClip(vis);
        b.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        double t = now / 1000.0;
        // two crossing swells with different headings
        tile(b, swellTile(0), t * 1.9, t * 0.8, vis, 1f);
        tile(b, swellTile(1), -t * 1.3 + 40, t * 1.7 + 11, vis, 0.7f);
        b.setComposite(AlphaComposite.DstIn);
        b.drawImage(r.waveMask, 0, 0, null);
        // surf: two bands at different distances from the coast breathe in antiphase
        double pa = 0.5 + 0.5 * Math.sin(t * 1.5), pb = 0.5 + 0.5 * Math.sin(t * 1.5 + Math.PI);
        double surfK = Math.max(0.12, Math.min(1, (13 - r.zoom) / 8.0));        // the coarse surf mask turns to fog when magnified
        b.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) ((0.25 + 0.55 * pa) * surfK)));
        b.drawImage(r.shoreA, 0, 0, null);
        b.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) ((0.15 + 0.5 * pb) * surfK)));
        b.drawImage(r.shoreB, 0, 0, null);
        // cloud shadows fall on land and sea alike
        tile(b, cloudTile(), t * 0.9, t * 0.35, vis, 0.85f);
        b.dispose();
    }

    @Override
    protected void paintComponent(Graphics g0) {
        long t0 = System.nanoTime(), tm = t0;
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0;
        if (pendingCenter >= 0 && getWidth() > 1 && getHeight() > 1) { int pc = pendingCenter; pendingCenter = -1; centerOn(pc, pendingZoom); }
        if (!fitted && getWidth() > 1) {
            zoom = minZoom();
            ox = (getWidth() - w.width * zoom) / 2; oy = (getHeight() - w.height * zoom) / 2;
            fitted = true; clampView();
        }
        if (cur == null) renderNow();
        else if (layersStale()) requestRender();
        MapLayers.Result r = cur;
        if (r != null) {
            double s = zoom / r.zoom;
            double tx = ox - s * (MapLayers.MARGIN + r.ox), ty = oy - s * (MapLayers.MARGIN + r.oy);
            if (Math.abs(s - 1) < 1e-6) g.drawImage(r.composite, (int) Math.round(tx), (int) Math.round(ty), null);
            else {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(r.composite, new AffineTransform(s, 0, 0, s, tx, ty), null);
            }
            seg[0] += System.nanoTime() - tm; tm = System.nanoTime();
            drawFx(g, r, s, tx, ty);
            seg[1] += System.nanoTime() - tm; tm = System.nanoTime();
        }
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        drawVignette(g);
        hits.clear();
        drawHighlights(g); drawFlashes(g); drawBrush(g); seg[2] += System.nanoTime() - tm; tm = System.nanoTime();
        drawLabels(g); seg[3] += System.nanoTime() - tm; tm = System.nanoTime();
        drawCities(g); seg[4] += System.nanoTime() - tm; tm = System.nanoTime();
        drawCapitals(g);
        drawPath(g);
        drawArmies(g); drawNukes(g); seg[5] += System.nanoTime() - tm; tm = System.nanoTime();
        if (mode == Mode.INTEL) drawLegend(g);
        drawControls(g);
        drawBrushBar(g);
        drawTooltip(g);
        drawToasts(g); seg[6] += System.nanoTime() - tm; tm = System.nanoTime();
        if (showPerf) { g.setFont(Fonts.ui(12.5f)); g.setColor(new Color(0, 0, 0, 170)); g.fillRoundRect(10, 10, 640, 24, 8, 8); g.setColor(new Color(150, 255, 170)); g.drawString(perf + String.format("   paint %.1f ms", paintNs / 1e6 / Math.max(1, paints)), 18, 27); }
        if (tutorial != null) tutorial.paint(g, getWidth(), getHeight());
        paintNs += System.nanoTime() - t0; paints++;
        if (paints > 240) { paintNs /= 2; paints /= 2; }
    }

    /** Screen position of a point given in map units (used by tests to click exact pixels). */
    Point2D.Double screenOfMapPoint(double mx, double my) { return new Point2D.Double(mx * zoom + ox, my * zoom + oy); }

    /** Screen position of a province centre (also used by tests to aim synthetic clicks). */
    Point2D.Double screenOf(int p) { return screen(p); }

    private Point2D.Double screen(int p) {
        WorldData.ProvinceInfo pi = w.provinces.get(p);
        return new Point2D.Double(pi.cx * zoom + ox, pi.cy * zoom + oy);
    }

    /** Darkens the screen edges (and the strip under the HUD) so the interface sits on the map instead of floating over it. */
    private void drawVignette(Graphics2D g) {
        int W = getWidth(), H = getHeight();
        g.setPaint(new GradientPaint(0, 0, new Color(4, 8, 14, 150), 0, 120, new Color(4, 8, 14, 0)));
        g.fillRect(0, 0, W, 120);
        g.setPaint(new GradientPaint(0, H - 150, new Color(4, 8, 14, 0), 0, H, new Color(4, 8, 14, 140)));
        g.fillRect(0, H - 150, W, 150);
        g.setPaint(new GradientPaint(0, 0, new Color(4, 8, 14, 70), 100, 0, new Color(4, 8, 14, 0)));
        g.fillRect(0, 0, 100, H);
        g.setPaint(new GradientPaint(W - 100, 0, new Color(4, 8, 14, 0), W, 0, new Color(4, 8, 14, 70)));
        g.fillRect(W - 100, 0, 100, H);
    }

    /** A province that changes hands flares white and settles into its new colour. */
    private void drawFlashes(Graphics2D g) {
        if (flashes.isEmpty()) return;
        long now = System.currentTimeMillis();
        AffineTransform old = g.getTransform();
        g.translate(ox, oy); g.scale(zoom, zoom);
        for (Iterator<long[]> it = flashes.iterator(); it.hasNext(); ) {
            long[] f = it.next();
            double age = (now - f[1]) / 1400.0;
            if (age >= 1) { it.remove(); continue; }
            int p = (int) f[0];
            double k = Math.pow(1 - age, 1.6);
            g.setColor(new Color(255, 250, 225, (int) (170 * k)));
            g.fill(shapes.fill[p]);
            g.setStroke(new BasicStroke((float) ((2 + 5 * k) / zoom), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(255, 214, 110, (int) (230 * k)));
            g.draw(shapes.fill[p]);
        }
        g.setTransform(old);
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

    /** Province names once you are zoomed in close enough to care. Country names live in the cached layers. */
    private void drawLabels(Graphics2D g) {
        if (zoom < 4.5) return;
        Intel in = gs.intelOf(gs.player);
        Font f = Fonts.label(11.5f);
        Color fill = new Color(255, 255, 255, 150), halo = new Color(17, 16, 14, 150);
        for (WorldData.ProvinceInfo p : w.provinces) {
            if (p.sea || shapes.bounds[p.id].width * zoom < 56) continue;
            double sx = p.cx * zoom + ox, sy = p.cy * zoom + oy;
            if (sx < -100 || sy < -30 || sx > getWidth() + 100 || sy > getHeight() + 30) continue;
            if (in.knownOwner[p.id] < 0) continue;
            Sprites.Sprite sp = Sprites.text(p.name.toUpperCase(), f, fill, halo, 2.4f, 0.8);
            g.drawImage(sp.img(), (int) Math.round(sx - sp.textW() / 2.0) - sp.padX(), (int) Math.round(sy + 16) - sp.baseline(), null);
        }
    }

    /** Major cities as glowing dots with names, thinning out as you zoom out. Only cities in provinces we know the owner of. */
    private void drawCities(Graphics2D g) {
        if (zoom < 1.5 || extras.cities.isEmpty()) return;
        Intel in = gs.intelOf(gs.player);
        double minPop = zoom < 2.0 ? 6_000_000 : zoom < 2.8 ? 2_500_000 : zoom < 3.8 ? 1_000_000 : zoom < 5 ? 500_000 : 0;
        List<Rectangle> placed = new ArrayList<>();
        Font font = Fonts.label(zoom < 3 ? 11.5f : 12.5f);
        int shown = 0;
        for (MapExtras.City c : extras.cities) {
            if (shown > 120) break;
            if (c.population() < minPop && !(c.capital() && zoom >= 2.4)) continue;
            double sx = c.x() * zoom + ox, sy = c.y() * zoom + oy;
            if (sx < -20 || sy < -20 || sx > getWidth() + 20 || sy > getHeight() + 20) continue;
            if (in.knownOwner[c.province()] < 0) continue;
            double r = c.population() > 8_000_000 ? 4.2 : c.population() > 2_000_000 ? 3.4 : 2.6;
            Sprites.Sprite sp = Sprites.text(c.name(), font, c.capital() ? new Color(255, 226, 140) : new Color(246, 240, 226), new Color(13, 12, 10, 200), 2.8f, 0);
            Rectangle label = new Rectangle((int) sx + 6, (int) sy - 9, sp.textW() + 4, 15);
            boolean room = true;
            for (Rectangle p : placed) if (p.intersects(label)) { room = false; break; }
            if (zoom >= 7) {
                int rad = (int) Math.max(6, Math.min(46, (5 + Math.sqrt(c.population() / 1e6) * 7) * Math.min(1.7, zoom / 11.0 + 0.3)));
                BufferedImage cs = citySprite(c, rad / 3 * 3);
                g.drawImage(cs, (int) Math.round(sx) - cs.getWidth() / 2, (int) Math.round(sy) - cs.getHeight() / 2, null);
            }
            drawCityDot(g, sx, sy, r, c.capital());
            if (!room && !c.capital()) continue;
            placed.add(label);
            g.drawImage(sp.img(), (int) Math.round(sx + 7) - sp.padX(), (int) Math.round(sy + 4) - sp.baseline(), null);
            shown++;
        }
    }

    private final Map<String, BufferedImage> citySprites = new HashMap<>();

    /** Top-down roofs scattered around the city centre, thinning toward the edge. Cached per city and size. */
    private BufferedImage citySprite(MapExtras.City c, int radius) {
        String key = c.name() + "|" + radius;
        BufferedImage hit = citySprites.get(key);
        if (hit != null) return hit;
        int d = radius * 2 + 8;
        BufferedImage img = new BufferedImage(d, d, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        java.util.Random rnd = new java.util.Random(c.name().hashCode());
        int n = (int) (radius * radius * 0.30) + 8;
        for (int i = 0; i < n; i++) {
            double ang = rnd.nextDouble() * Math.PI * 2, dist = Math.pow(rnd.nextDouble(), 0.8) * radius;
            double bx = d / 2.0 + Math.cos(ang) * dist, by = d / 2.0 + Math.sin(ang) * dist * 0.85;
            double bw = 2 + rnd.nextInt(4) + (1 - dist / radius) * 2, bh = 2 + rnd.nextInt(4) + (1 - dist / radius) * 2;
            int tone = 150 + rnd.nextInt(70);
            AffineTransform at = g.getTransform();
            g.translate(bx, by); g.rotate(Math.round(rnd.nextDouble() * 4) * Math.PI / 8 + 0.1);
            g.setColor(new Color(0, 0, 0, 90)); g.fill(new Rectangle2D.Double(-bw / 2 + 1, -bh / 2 + 1.4, bw, bh));
            g.setColor(new Color(tone, tone - 14, tone - 34, 235)); g.fill(new Rectangle2D.Double(-bw / 2, -bh / 2, bw, bh));
            g.setColor(new Color(46, 45, 43, 150)); g.setStroke(new BasicStroke(0.6f)); g.draw(new Rectangle2D.Double(-bw / 2, -bh / 2, bw, bh));
            g.setTransform(at);
        }
        g.dispose();
        if (citySprites.size() > 300) citySprites.clear();
        citySprites.put(key, img);
        return img;
    }

    private void drawCityDot(Graphics2D g, double x, double y, double r, boolean capital) {
        int gr = (int) Math.round(r * 3.2);
        g.drawImage(Sprites.glow(gr, 0xFFD678, 110), (int) Math.round(x) - gr, (int) Math.round(y) - gr, null);
        g.setColor(new Color(16, 15, 13, 230));
        g.fill(new Ellipse2D.Double(x - r - 1.2, y - r - 1.2, 2 * r + 2.4, 2 * r + 2.4));
        g.setColor(capital ? new Color(255, 214, 90) : new Color(255, 244, 214));
        g.fill(new Ellipse2D.Double(x - r, y - r, 2 * r, 2 * r));
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
            g.setColor(new Color(19, 18, 16, 235));
            g.draw(star);
            capRects.add(new Rectangle((int) (s.x - r - 5), (int) (cy - r - 5), (int) (2 * r + 10), (int) (2 * r + 10)));
            capNations.add(n.index);
            if (mine) { g.setColor(new Color(255, 214, 90, 150)); g.setStroke(new BasicStroke(1.5f)); g.draw(new Ellipse2D.Double(s.x - r - 4, cy - r - 4, 2 * r + 8, 2 * r + 8)); }
        }
    }

    /** Every march order of ours is shown; the selected army's route is the bright one. */
    private void drawPath(Graphics2D g) {
        Army sel = gs.armies.get(selArmy);
        for (Army a : gs.armies.values()) if (a.owner == gs.player && !a.path.isEmpty() && a != sel) drawRoute(g, a, false);
        if (sel != null && sel.owner == gs.player && !sel.path.isEmpty()) drawRoute(g, sel, true);
    }

    /** Movement order: a glowing route with flowing chevrons and an arrow head, like a strategic-map advance arrow. */
    private void drawRoute(Graphics2D g, Army a, boolean selected) {
        Point2D.Double start = screen(a.province);
        Path2D.Double route = new Path2D.Double();
        route.moveTo(start.x, start.y);
        Point2D.Double prev = start, last = start;
        List<Point2D.Double> nodes = new ArrayList<>();
        for (int p : a.path) { Point2D.Double s = screen(p); route.lineTo(s.x, s.y); nodes.add(s); prev = last; last = s; }
        Rectangle bounds = route.getBounds();
        bounds.grow(30, 30);
        if (!bounds.intersects(0, 0, getWidth(), getHeight())) return;
        float k = selected ? 1f : 0.62f;
        int alpha = selected ? 235 : 175;
        Stroke old = g.getStroke();
        g.setStroke(new BasicStroke(9f * k, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(0, 0, 0, selected ? 90 : 70));
        g.draw(route);
        g.setStroke(new BasicStroke(5.2f * k, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(selected ? new Color(214, 150, 40, alpha) : new Color(196, 160, 90, alpha));
        g.draw(route);
        g.setStroke(new BasicStroke(2.2f * k, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f, new float[]{9, 15}, (float) (24 - (System.currentTimeMillis() % 1200) / 1200.0 * 24)));
        g.setColor(new Color(255, 244, 190, selected ? 240 : 190));
        g.draw(route);
        if (selected) for (Point2D.Double n : nodes) { g.setColor(new Color(255, 232, 150)); g.fill(new Ellipse2D.Double(n.x - 3, n.y - 3, 6, 6)); }
        // arrow head on the last leg
        double ang = Math.atan2(last.y - prev.y, last.x - prev.x);
        AffineTransform at = g.getTransform();
        g.translate(last.x, last.y); g.rotate(ang); g.scale(k * 0.85 + 0.15, k * 0.85 + 0.15);
        Path2D.Double head = new Path2D.Double();
        head.moveTo(15, 0); head.lineTo(-6, -11); head.lineTo(-1, 0); head.lineTo(-6, 11); head.closePath();
        g.setColor(new Color(0, 0, 0, 110)); g.translate(1.5, 2); g.fill(head); g.translate(-1.5, -2);
        g.setColor(selected ? new Color(255, 218, 96) : new Color(236, 214, 150)); g.fill(head);
        g.setStroke(new BasicStroke(1.4f)); g.setColor(new Color(120, 70, 10)); g.draw(head);
        g.setTransform(at);
        g.setStroke(old);
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

    private final Map<String, BufferedImage> markerCache = new HashMap<>();

    /** The static part of an army marker (shadow, flag, frame, number pill), cached per look. */
    private BufferedImage markerSprite(WorldData.NationInfo nation, String text, double sz, boolean own, boolean stale) {
        String key = (nation == null ? "?" : nation.tag) + "|" + text + "|" + Math.round(sz * 20) + "|" + own + "|" + stale;
        BufferedImage hit = markerCache.get(key);
        if (hit != null) return hit;
        int fw = (int) (28 * sz), fh = Math.round(fw * 0.75f), ph = (int) (15 * sz);
        Font f = Fonts.labelBold((float) (13 * sz));
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        FontMetrics fm = probe.createGraphics().getFontMetrics(f);
        int pw = Math.max(fw - 6, fm.stringWidth(text) + 14);
        int W = Math.max(fw, pw) + 16, H = fh + ph + 16;
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        int x = (W - fw) / 2, y = 5;
        // soft drop shadow
        g.setColor(new Color(0, 0, 0, 70));
        g.fill(new RoundRectangle2D.Double(x + 1, y + 4, fw + 2, fh + ph / 2.0 + 2, 8, 8));
        g.setColor(new Color(0, 0, 0, 60));
        g.fill(new RoundRectangle2D.Double(x - 1, y + 2, fw + 4, fh + ph / 2.0 + 4, 9, 9));
        // frame: gold for our own forces, dark steel for everyone else
        Color frame = own ? new Color(238, 196, 96) : new Color(30, 29, 27, 240);
        g.setColor(frame);
        g.fill(new RoundRectangle2D.Double(x - 2, y - 2, fw + 4, fh + 4, 8, 8));
        g.drawImage(Assets.flag(nation, fw), x, y, null);
        g.setPaint(new GradientPaint(0, y, new Color(255, 255, 255, 60), 0, y + fh / 2f, new Color(255, 255, 255, 0)));
        g.fill(new RoundRectangle2D.Double(x, y, fw, fh / 2.0, 6, 6));
        if (stale && !own) {
            g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{3, 3}, 0));
            g.setColor(new Color(255, 255, 255, 140));
            g.draw(new RoundRectangle2D.Double(x - 2, y - 2, fw + 4, fh + 4, 8, 8));
        }
        // number plate
        int px = (W - pw) / 2, py = y + fh - 1;
        g.setPaint(new GradientPaint(0, py, new Color(44, 43, 41, 245), 0, py + ph, new Color(17, 16, 14, 248)));
        g.fill(new RoundRectangle2D.Double(px, py, pw, ph, ph, ph));
        g.setColor(own ? new Color(238, 196, 96, 230) : new Color(255, 255, 255, 90));
        g.setStroke(new BasicStroke(1.2f));
        g.draw(new RoundRectangle2D.Double(px, py, pw, ph, ph, ph));
        g.setFont(f);
        g.setColor(Color.WHITE);
        g.drawString(text, px + (pw - fm.stringWidth(text)) / 2, py + (ph + fm.getAscent()) / 2 - 2);
        g.dispose();
        if (markerCache.size() > 400) markerCache.clear();
        markerCache.put(key, img);
        return img;
    }

    /** Army marker: the nation's flag with a number plate underneath. Unknown owners get a plain grey flag. */
    private void drawMarker(Graphics2D g, int prov, Map<Integer, Integer> slot, WorldData.NationInfo nation, String text,
                            int armyId, boolean own, float alpha, boolean inBattle, boolean stale) {
        Point2D.Double s = screen(prov);
        if (s.x < -60 || s.y < -60 || s.x > getWidth() + 60 || s.y > getHeight() + 60) return;
        double sz = Math.max(0.85, Math.min(1.35, 0.74 + zoom * 0.1));
        BufferedImage spr = markerSprite(nation, text, Math.round(sz * 10) / 10.0, own, stale);
        int k = slot.merge(prov, 1, Integer::sum) - 1;
        int fw = (int) (28 * sz), fh = Math.round(fw * 0.75f), ph = (int) (15 * sz);
        int x = (int) s.x - spr.getWidth() / 2 + k * (fw + 6), y = (int) s.y - fh / 2 - 5 - k * 2;
        Composite old = g.getComposite();
        if (alpha < 1) g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
        boolean selected = armyId == selArmy;
        if (inBattle) {
            double pulse = 0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 140.0);
            g.drawImage(Sprites.glow(34, 0xFF4632, (int) (110 + 90 * pulse)), x + spr.getWidth() / 2 - 34, y + 10 - 34 + fh / 2, null);
            drawBattleSparks(g, x + spr.getWidth() / 2.0, y + 6, armyId);
        }
        if (selected) {
            g.setColor(new Color(255, 230, 90, 190));
            g.setStroke(new BasicStroke(3.2f));
            g.draw(new RoundRectangle2D.Double(x + (spr.getWidth() - fw) / 2.0 - 4, y + 1, fw + 8, fh + 8, 10, 10));
        }
        g.drawImage(spr, x, y, null);
        g.setComposite(old);
        hits.add(new Hit(new Rectangle(x + (spr.getWidth() - fw) / 2, y + 5, fw, fh + ph), armyId, own));
    }

    // ------------------------------------------------------------------ battle and nuclear effects
    private record Blast(int province, long start) {}
    private final java.util.IdentityHashMap<int[], Long> missileSeen = new java.util.IdentityHashMap<>();
    private final List<int[]> lastMissiles = new ArrayList<>();
    private final List<Blast> blasts = new ArrayList<>();

    /** Flickering sparks and smoke above a fighting stack. */
    private void drawBattleSparks(Graphics2D g, double cx, double cy, int seed) {
        long now = System.currentTimeMillis();
        for (int i = 0; i < 7; i++) {
            double life = ((now + i * 211 + seed * 97) % 1300) / 1300.0;
            double ph = i * 2.399 + seed;
            double x = cx + Math.sin(ph * 3.1) * 14 + Math.sin(life * 6 + ph) * 4, y = cy - life * 34;
            float a = (float) Math.max(0, 1 - life);
            if (i % 3 == 0) {
                g.setColor(new Color(36, 35, 33, (int) (110 * a)));
                double r = 4 + life * 9;
                g.fill(new Ellipse2D.Double(x - r, y - r - 6, 2 * r, 2 * r));
            } else {
                g.setColor(new Color(255, (int) (150 + 90 * (1 - life)), 40, (int) (230 * a)));
                g.fill(new Ellipse2D.Double(x - 1.5, y - 1.5, 3, 3));
            }
        }
    }

    /** Screenshot hooks: pretend a missile has been flying for ageMs, or that a warhead exploded ageMs ago. */
    private long debugNow;   // when non-zero the nuclear effects run on this frozen clock
    void debugMissileAge(long ageMs) { debugNow = 1_000_000L; for (int[] mi : gs.nukesInFlight) missileSeen.put(mi, debugNow - ageMs); }
    void debugBlast(int province, long ageMs) { debugNow = 1_000_000L; blasts.add(new Blast(province, debugNow - ageMs)); }

    /** Missiles in flight arc across the map; when one disappears from the sky it detonates at its target. */
    private void drawNukes(Graphics2D g) {
        long now = debugNow > 0 ? debugNow : System.currentTimeMillis();
        List<int[]> current = new ArrayList<>(gs.nukesInFlight);
        for (int[] m : current) missileSeen.putIfAbsent(m, now);
        for (int[] old : lastMissiles) {
            boolean still = false;
            for (int[] m : current) if (m == old) { still = true; break; }
            if (!still) { blasts.add(new Blast(old[1], now)); missileSeen.remove(old); Audio.get().play(Synth.Sfx.CONFIRM); }
        }
        lastMissiles.clear(); lastMissiles.addAll(current);
        for (int[] m : current) {
            Point2D.Double from = screen(w.nations.get(m[0]).capital), to = screen(m[1]);
            double t = Math.min(1, (now - missileSeen.get(m)) / 1500.0);
            double dist = Math.hypot(to.x - from.x, to.y - from.y);
            int steps = 26;
            Path2D.Double trail = new Path2D.Double();
            Point2D.Double head = from;
            for (int i = 0; i <= steps * t; i++) {
                double u = i / (double) steps;
                double x = from.x + (to.x - from.x) * u, y = from.y + (to.y - from.y) * u - Math.sin(Math.PI * u) * dist * 0.22;
                if (i == 0) trail.moveTo(x, y); else trail.lineTo(x, y);
                head = new Point2D.Double(x, y);
            }
            Stroke old = g.getStroke();
            g.setStroke(new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(255, 120, 40, 70)); g.draw(trail);
            g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(255, 236, 190, 230)); g.draw(trail);
            g.setStroke(old);
            g.drawImage(Sprites.glow(18, 0xFFB050, 220), (int) head.x - 18, (int) head.y - 18, null);
            // warning ring on the target
            double pulse = 0.5 + 0.5 * Math.sin(now / 160.0);
            g.setColor(new Color(255, 60, 40, (int) (120 + 110 * pulse)));
            g.setStroke(new BasicStroke(2.2f));
            double rr = 16 + 6 * pulse;
            g.draw(new Ellipse2D.Double(to.x - rr, to.y - rr, 2 * rr, 2 * rr));
            g.draw(new Line2D.Double(to.x - rr - 6, to.y, to.x + rr + 6, to.y));
            g.draw(new Line2D.Double(to.x, to.y - rr - 6, to.x, to.y + rr + 6));
            g.setStroke(old);
        }
        for (Iterator<Blast> it = blasts.iterator(); it.hasNext(); ) {
            Blast b = it.next();
            double t = (now - b.start()) / 3200.0;
            if (t > 1) { it.remove(); continue; }
            Point2D.Double c = screen(b.province());
            double flash = Math.max(0, 1 - t * 5);
            if (flash > 0) { g.setColor(new Color(255, 250, 235, (int) (150 * flash))); g.fillRect(0, 0, getWidth(), getHeight()); }
            double ease = 1 - Math.pow(1 - t, 2.4);
            double ring = 30 + 230 * ease;
            Stroke old = g.getStroke();
            g.setStroke(new BasicStroke((float) (10 * (1 - t) + 1.5)));
            g.setColor(new Color(255, 220, 160, (int) (200 * (1 - t))));
            g.draw(new Ellipse2D.Double(c.x - ring, c.y - ring, 2 * ring, 2 * ring));
            g.setStroke(old);
            double fr = (34 + 80 * Math.min(1, t * 2.0)) * (0.8 + zoom * 0.14);
            g.setPaint(new RadialGradientPaint((float) c.x, (float) c.y, (float) fr, new float[]{0f, 0.45f, 1f},
                    new Color[]{new Color(255, 248, 210, (int) (255 * Math.max(0, 1 - t * 0.95))), new Color(255, 140, 34, (int) (235 * Math.max(0, 1 - t * 0.9))), new Color(150, 36, 10, 0)}));
            g.fill(new Ellipse2D.Double(c.x - fr, c.y - fr, 2 * fr, 2 * fr));
            double sr = 18 + 70 * ease, sy = c.y - 20 - 70 * ease;
            g.setColor(new Color(33, 32, 30, (int) (170 * Math.min(1, t * 3) * (1 - t))));
            g.fill(new Ellipse2D.Double(c.x - sr, sy - sr * 0.8, 2 * sr, 1.6 * sr));
        }
    }

    private void drawLegend(Graphics2D g) {
        String t = "green exact  -  lime estimate  -  amber contact  -  dark unseen (memory fades)";
        g.setFont(Fonts.label(13f));
        FontMetrics fm = g.getFontMetrics();
        int wd = fm.stringWidth(t) + 24;
        Skin.plate(g, new RoundRectangle2D.Float((getWidth() - wd) / 2f, getHeight() - 132, wd, 24, 8, 8), Skin.Kind.PANEL, 1f);
        Skin.frame(g, (getWidth() - wd) / 2f + 0.5f, getHeight() - 131.5f, wd - 1, 23, 8, false, false);
        g.setColor(new Color(225, 232, 244));
        g.drawString(t, (getWidth() - wd) / 2 + 12, getHeight() - 115);
    }

    // ------------------------------------------------------------------ peace brush: paint the provinces you want
    private int brushTarget = -1, brushRadius = 46;
    private final Set<Integer> brushSel = new LinkedHashSet<>();
    private final List<Integer> brushCandidates = new ArrayList<>();
    private final List<Rectangle> brushRects = new ArrayList<>();
    private java.util.function.Consumer<Set<Integer>> brushPropose;
    private Runnable brushCancel;
    private String brushVerdict = "Paint the provinces you want to take.";
    private Color brushVerdictColor = Ui.MUTED;

    boolean brushActive() { return brushTarget >= 0; }

    /** Enters brush mode: left-drag paints the target's provinces into the demand, right-drag rubs them out. */
    void startBrush(int target, java.util.function.Consumer<Set<Integer>> propose, Runnable cancel) {
        brushTarget = target; brushPropose = propose; brushCancel = cancel;
        brushSel.clear(); brushCandidates.clear();
        for (int p = 0; p < w.provinces.size(); p++) if (!w.provinces.get(p).sea && gs.owner[p] == target) brushCandidates.add(p);
        selProv = -1; selArmy = -1; onSelect.run();
        updateBrushVerdict();
        repaint();
    }

    /** Screenshot hook: paint a province into the demand as if the brush had passed over it. */
    void debugBrushPaint(int id) { if (brushCandidates.contains(id)) { brushSel.add(id); updateBrushVerdict(); } }

    void endBrush() { brushTarget = -1; brushSel.clear(); brushCandidates.clear(); brushRects.clear(); repaint(); }

    void cancelBrush() { Runnable c = brushCancel; endBrush(); if (c != null) c.run(); }

    Set<Integer> brushSelection() { return new LinkedHashSet<>(brushSel); }

    private void paintBrush(Point p, boolean erase) {
        int under = provinceAt(p.x, p.y);
        boolean changed = false;
        for (int id : brushCandidates) {
            Point2D.Double s = screen(id);
            boolean hit = id == under || Math.hypot(s.x - p.x, s.y - p.y) <= brushRadius;
            if (!hit) continue;
            changed |= erase ? brushSel.remove(id) : brushSel.add(id);
        }
        if (changed) { Audio.get().play(Synth.Sfx.CLICK); updateBrushVerdict(); repaint(); }
    }

    private void updateBrushVerdict() {
        if (brushSel.isEmpty()) { brushVerdict = "Paint the provinces you want to take."; brushVerdictColor = Ui.MUTED; return; }
        warfare.core.Peace.Talk t = gs.assessPeace(gs.player, brushTarget, warfare.core.Peace.Term.DEMAND, brushSel);
        boolean capital = brushSel.contains(w.nations.get(brushTarget).capital);
        String n = brushSel.size() + (brushSel.size() == 1 ? " province" : " provinces") + (capital ? " incl. capital" : "");
        if (t.accepted) { brushVerdict = n + " - they would accept"; brushVerdictColor = Ui.GREEN; }
        else if (t.counterDemand != null) { brushVerdict = n + " - too much, but they would give " + t.counterDemand.size(); brushVerdictColor = Ui.AMBER; }
        else { brushVerdict = n + " - they would refuse"; brushVerdictColor = Ui.RED; }
    }

    private int brushBarAt(Point p) { for (int i = 0; i < brushRects.size(); i++) if (brushRects.get(i).contains(p)) return i; return -1; }

    private void brushAction(int i) {
        Audio.get().play(Synth.Sfx.CLICK);
        switch (i) {
            case 0 -> brushRadius = 22;
            case 1 -> brushRadius = 46;
            case 2 -> brushRadius = 90;
            case 3 -> { for (int id : brushCandidates) if (gs.controller[id] == gs.player) brushSel.add(id); updateBrushVerdict(); }
            case 4 -> { brushSel.clear(); updateBrushVerdict(); }
            case 5 -> { if (brushSel.isEmpty()) { toast("Nothing painted", "Paint at least one of their provinces first.", "castle"); break; } Set<Integer> sel = brushSelection(); var cb = brushPropose; endBrush(); if (cb != null) cb.accept(sel); }
            default -> cancelBrush();
        }
        repaint();
    }

    private void drawBrush(Graphics2D g) {
        if (!brushActive()) return;
        AffineTransform old = g.getTransform();
        g.translate(ox, oy); g.scale(zoom, zoom);
        for (int id : brushCandidates) {
            boolean sel = brushSel.contains(id);
            g.setColor(sel ? new Color(255, 206, 80, 120) : new Color(255, 255, 255, 26));
            g.fill(shapes.fill[id]);
            g.setStroke(new BasicStroke((float) ((sel ? 2.2 : 1.0) / zoom)));
            g.setColor(sel ? new Color(255, 226, 120, 235) : new Color(255, 255, 255, 70));
            g.draw(shapes.fill[id]);
        }
        g.setTransform(old);
        if (mouse != null && brushBarAt(mouse) < 0) {
            g.setColor(new Color(255, 255, 255, 36));
            g.fill(new Ellipse2D.Double(mouse.x - brushRadius, mouse.y - brushRadius, brushRadius * 2.0, brushRadius * 2.0));
            g.setColor(new Color(255, 236, 160, 230));
            g.setStroke(new BasicStroke(2f));
            g.draw(new Ellipse2D.Double(mouse.x - brushRadius, mouse.y - brushRadius, brushRadius * 2.0, brushRadius * 2.0));
        }
    }

    /** The brush's control bar, drawn above armies and labels. */
    private void drawBrushBar(Graphics2D g) {
        if (!brushActive()) return;
        brushRects.clear();
        String title = "PEACE BRUSH  -  take provinces from " + gs.nationName(brushTarget).toUpperCase();
        int bw = 820, bh = 78, bx = (getWidth() - bw) / 2, by = 86;
        Skin.shadow(g, bx, by, bw, bh, 10, 5, 110);
        Skin.plate(g, new RoundRectangle2D.Float(bx, by, bw, bh, 10, 10), Skin.Kind.PANEL, 1f);
        Skin.frame(g, bx + 0.5f, by + 0.5f, bw - 1, bh - 1, 10, true, true);
        g.setFont(Fonts.label(15f));
        g.setColor(Ui.GOLD);
        g.drawString(title, bx + 20, by + 24);
        g.setFont(Fonts.ui(13f));
        g.setColor(brushVerdictColor);
        g.drawString(brushVerdict, bx + 20, by + 66);
        g.setColor(new Color(220, 210, 190));
        g.drawString("drag = paint   right-drag = erase   WASD / wheel = move", bx + 20, by + 45);
        String[] labels = {"S", "M", "L", "OCCUPIED", "CLEAR", "PROPOSE", "CANCEL"};
        int[] widths = {34, 34, 34, 92, 64, 96, 80};
        int x = bx + bw - 12;
        int[] xs = new int[labels.length];
        for (int i = labels.length - 1; i >= 0; i--) { x -= widths[i]; xs[i] = x; x -= 6; }
        for (int i = 0; i < labels.length; i++) brushRects.add(new Rectangle(xs[i], by + 38, widths[i], 30));
        for (int i = 0; i < labels.length; i++) {
            Rectangle r = brushRects.get(i);
            boolean on = i < 3 && brushRadius == new int[]{22, 46, 90}[i];
            boolean hov = mouse != null && r.contains(mouse);
            RoundRectangle2D pill = new RoundRectangle2D.Float(r.x, r.y, r.width, r.height, 8, 8);
            if (on || i == 5) Skin.plate(g, pill, Skin.Kind.GOLD, hov ? 0.5f : 0.8f);
            else { Skin.plate(g, pill, Skin.Kind.RAISED, 0.9f); if (hov) { g.setColor(new Color(255, 236, 190, 50)); g.fill(pill); } }
            g.setColor(new Color(255, 236, 190, hov ? 180 : 90)); g.setStroke(new BasicStroke(1f)); g.draw(pill);
            g.setFont(Fonts.label(13.5f));
            g.setColor(on || i == 5 ? new Color(26, 25, 23) : Color.WHITE);
            g.drawString(labels[i], r.x + (r.width - g.getFontMetrics().stringWidth(labels[i])) / 2, r.y + 20);
        }
    }

    // ------------------------------------------------------------------ input
    /** The province under a screen point. The pick map is coarse (one pixel per map unit), so close up the vector shapes decide. */
    private int provinceAt(int sx, int sy) {
        double wx = (sx - ox) / zoom, wy = (sy - oy) / zoom;
        int px = (int) wx, py = (int) wy;
        if (wx < 0 || wy < 0 || px >= w.width || py >= w.height) return -1;
        int id = w.idMap[py * w.width + px];
        if (zoom < 3.5 || id < 0) return id;
        if (shapes.fill[id].contains(wx, wy)) return id;
        for (int nb : w.provinces.get(id).neighbours) if (shapes.fill[nb].contains(wx, wy)) return nb;
        return id;
    }

    private void click(MouseEvent e) {
        if (brushActive()) { int b = brushBarAt(e.getPoint()); if (b >= 0) brushAction(b); return; }
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
        else if (i == 12) centerOn(w.nations.get(gs.player).capital, Math.max(zoom, START_ZOOM));
    }

    private int capitalAt(Point p) {
        for (int i = 0; i < capRects.size(); i++) if (capRects.get(i).contains(p)) return capNations.get(i);
        return -1;
    }

    private void drawControls(Graphics2D g) {
        modeRects.clear();
        Mode[] modes = Mode.values();
        int bw = modes.length > 5 ? 92 : 104, bh = 32, gap = 4, total = modes.length * bw + (modes.length - 1) * gap + 20;
        int x0 = (getWidth() - total) / 2, y0 = getHeight() - bh - 22;
        Skin.shadow(g, x0, y0, total, bh + 12, 12, 5, 110);
        Skin.plate(g, new RoundRectangle2D.Float(x0, y0 - 2, total, bh + 12, 12, 12), Skin.Kind.BAR, 1f);
        Skin.frame(g, x0 + 0.5f, y0 - 1.5f, total - 1, bh + 11, 12, false, false);
        g.setFont(Fonts.label(14.5f));
        for (int i = 0; i < modes.length; i++) {
            Rectangle r = new Rectangle(x0 + 10 + i * (bw + gap), y0 + 3, bw, bh);
            modeRects.add(r);
            boolean on = modes[i] == mode, hov = hoverOverlay == i;
            RoundRectangle2D pill = new RoundRectangle2D.Float(r.x, r.y, r.width, r.height, 8, 8);
            if (on) {
                Skin.plate(g, pill, Skin.Kind.GOLD, 0.8f);
                g.setColor(new Color(255, 236, 170, 200)); g.setStroke(new BasicStroke(1.2f)); g.draw(pill);
            } else {
                g.setColor(hov ? new Color(255, 236, 190, 46) : new Color(0, 0, 0, 70));
                g.fill(pill);
                g.setColor(new Color(255, 236, 190, hov ? 110 : 40)); g.setStroke(new BasicStroke(1f)); g.draw(pill);
            }
            g.setColor(on ? new Color(26, 25, 23) : hov ? Color.WHITE : new Color(232, 222, 202));
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
            boolean hov = hoverOverlay == 10 + i;
            Skin.shadow(g, r.x, r.y, r.width, r.height, 8, 5, 110);
            Skin.plate(g, new RoundRectangle2D.Float(r.x, r.y, r.width, r.height, 8, 8), hov ? Skin.Kind.RAISED : Skin.Kind.BAR, 1f);
            Skin.frame(g, r.x + 0.5f, r.y + 0.5f, r.width - 1, r.height - 1, 8, false, hov);
            if (glyph[i] != null) {
                g.setFont(Fonts.ui(24f));
                g.setColor(hov ? Color.WHITE : new Color(240, 228, 204));
                g.drawString(glyph[i], r.x + (r.width - g.getFontMetrics().stringWidth(glyph[i])) / 2, r.y + 27);
            } else g.drawImage(Assets.icon("castle", 24, Ui.GOLD), r.x + 7, r.y + 7, null);
        }
    }

    /** Small info card that follows the cursor: who owns the province under it and how much we actually know. */
    private void drawTooltip(Graphics2D g) {
        if (mouse == null || hoverProv < 0 || hoverOverlay >= 0 || dragged || brushActive()) return;
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
        Skin.shadow(g, x, y, wd, h, 8, 5, 110);
        Skin.plate(g, new RoundRectangle2D.Float(x, y, wd, h, 8, 8), Skin.Kind.PANEL, 1f);
        Skin.frame(g, x + 0.5f, y + 0.5f, wd - 1, h - 1, 8, false, true);
        if (ko >= 0) g.drawImage(Assets.flag(w.nations.get(ko), 22), x + 10, y + 9, null);
        g.setFont(Fonts.labelBold(16f));
        g.setColor(Color.WHITE);
        g.drawString(l1, x + (ko >= 0 ? 40 : 12), y + 24);
        g.setFont(Fonts.ui(13f));
        g.setColor(new Color(224, 222, 216));
        int ty = y + 44;
        g.drawString(l2, x + 12, ty); ty += 18;
        g.setColor(in.quality[hoverProv] > 0 ? Ui.GREEN : Ui.AMBER);
        g.drawString(l3, x + 12, ty); ty += 18;
        if (l4 != null) { g.setColor(Ui.GOLD); g.drawString(l4, x + 12, ty); }
    }
}
