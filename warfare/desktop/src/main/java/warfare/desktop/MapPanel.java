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

    private final int[] fillArgb;
    private final byte[] arcClass;          // 0 none, 1 coast, 2 national, 3 province, 4 sea zone
    private final List<Label> labels = new ArrayList<>();
    private BufferedImage layer;
    private double layerOx, layerOy, layerZoom;
    private boolean dirty = true;
    private double zoom = 1, ox, oy;
    private boolean fitted;
    private Point dragFrom;
    private boolean dragged;
    private final List<Hit> hits = new ArrayList<>();

    MapPanel(GameState gs, Path mapDir) {
        this.gs = gs; this.w = gs.world;
        setBackground(new Color(10, 24, 46));
        try { shapes = MapShapes.load(mapDir.resolve("arcs.bin.gz"), w.provinces.size()); }
        catch (Exception e) { throw new IllegalStateException("cannot load map shapes", e); }
        basemap = new Basemap(shapes, w);
        fillArgb = new int[w.provinces.size()];
        arcClass = new byte[shapes.p1.length];
        buildLabels();
        MouseAdapter ma = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { dragFrom = e.getPoint(); dragged = false; }
            @Override public void mouseDragged(MouseEvent e) {
                if (dragFrom == null) return;
                int dx = e.getX() - dragFrom.x, dy = e.getY() - dragFrom.y;
                if (Math.abs(dx) + Math.abs(dy) > 3) dragged = true;
                if (dragged) { ox += dx; oy += dy; dragFrom = e.getPoint(); repaint(); }
            }
            @Override public void mouseReleased(MouseEvent e) {
                if (!dragged) click(e); else { dirty = true; repaint(); }
                dragFrom = null;
            }
            @Override public void mouseMoved(MouseEvent e) {
                int id = provinceAt(e.getX(), e.getY());
                if (id != hoverProv) { hoverProv = id; repaint(); }
            }
            @Override public void mouseWheelMoved(MouseWheelEvent e) {
                double f = e.getWheelRotation() < 0 ? 1.2 : 1 / 1.2;
                double nz = Math.max(0.4, Math.min(16, zoom * f));
                ox = e.getX() - (e.getX() - ox) * nz / zoom;
                oy = e.getY() - (e.getY() - oy) * nz / zoom;
                zoom = nz; dirty = true; repaint();
            }
        };
        addMouseListener(ma); addMouseMotionListener(ma); addMouseWheelListener(ma);
        refreshColors();
    }

    void centerOn(int province, double z) {
        WorldData.ProvinceInfo p = w.provinces.get(province);
        zoom = z; ox = getWidth() / 2.0 - p.cx * zoom; oy = getHeight() / 2.0 - p.cy * zoom; fitted = true; dirty = true; repaint();
    }

    void setMode(Mode m) { mode = m; refreshColors(); }

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
    void refreshColors() {
        Intel in = gs.intelOf(gs.player);
        int me = gs.player;
        for (int p = 0; p < fillArgb.length; p++) fillArgb[p] = colorOf(p, in, me);
        for (int a = 0; a < arcClass.length; a++) {
            int p1 = shapes.p1[a], p2 = shapes.p2[a];
            if (p1 < 0 || p2 < 0) { arcClass[a] = 0; continue; }
            boolean s1 = w.provinces.get(p1).sea, s2 = w.provinces.get(p2).sea;
            if (s1 && s2) arcClass[a] = 4;
            else if (s1 != s2) arcClass[a] = 1;
            else arcClass[a] = (byte) (in.knownOwner[p1] != in.knownOwner[p2] ? 2 : 3);
        }
        dirty = true;
        repaint();
    }

    private static int argb(int rgb, double alpha) { return ((int) Math.max(0, Math.min(255, alpha * 255)) << 24) | (rgb & 0xFFFFFF); }

    private int colorOf(int p, Intel in, int me) {
        WorldData.ProvinceInfo pi = w.provinces.get(p);
        int q = in.quality[p];
        double conf = in.provinceConfidence(p, gs.day);
        double stale = 1 - conf;
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
    private void renderLayer() {
        int W = Math.max(1, getWidth()), H = Math.max(1, getHeight());
        if (layer == null || layer.getWidth() != W || layer.getHeight() != H) layer = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = layer.createGraphics();
        g.setColor(getBackground());
        g.fillRect(0, 0, W, H);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        AffineTransform at = new AffineTransform();
        at.translate(ox, oy);
        at.scale(zoom / Basemap.PX_PER_UNIT, zoom / Basemap.PX_PER_UNIT);
        g.drawImage(basemap.image, at, null);

        g.translate(ox, oy);
        g.scale(zoom, zoom);
        Rectangle2D view = new Rectangle2D.Double(-ox / zoom - 2, -oy / zoom - 2, W / zoom + 4, H / zoom + 4);
        for (int p = 0; p < fillArgb.length; p++) {
            int c = fillArgb[p];
            if ((c >>> 24) == 0 || !view.intersects(shapes.bounds[p])) continue;
            g.setColor(new Color(c, true));
            g.fill(shapes.fill[p]);
        }
        // borders, quietest first
        if (zoom >= 0.9) strokeArcs(g, view, 4, new Color(255, 255, 255, 34), 0.8, false);
        if (zoom >= 1.15) strokeArcs(g, view, 3, new Color(14, 18, 26, 105), 0.9, false);
        strokeArcs(g, view, 1, new Color(150, 196, 236, 55), 5.0, true);
        strokeArcs(g, view, 2, new Color(8, 10, 16, 215), 1.7, false);
        strokeArcs(g, view, 1, new Color(6, 14, 28, 230), 1.35, true);
        g.dispose();
        layerOx = ox; layerOy = oy; layerZoom = zoom;
        dirty = false;
    }

    private void strokeArcs(Graphics2D g, Rectangle2D view, int cls, Color c, double px, boolean glow) {
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
            fitted = true; dirty = true;
        }
        if (dirty || layer == null || layer.getWidth() != getWidth() || layer.getHeight() != getHeight() || layerZoom != zoom) renderLayer();
        g.drawImage(layer, (int) Math.round(ox - layerOx), (int) Math.round(oy - layerOy), null);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        hits.clear();
        drawHighlights(g);
        drawLabels(g);
        drawPath(g);
        drawArmies(g);
        drawLegend(g);
    }

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

    private void drawTracked(Graphics2D g, String text, double cx, double cy, float size, double tracking, Color fill, Color halo) {
        Font f = Fonts.label(size);
        FontMetrics fm = g.getFontMetrics(f);
        double total = 0;
        for (char ch : text.toCharArray()) total += fm.charWidth(ch) + tracking;
        total -= tracking;
        double x = cx - total / 2, y = cy + fm.getAscent() * 0.35;
        for (char ch : text.toCharArray()) {
            GlyphVector gv = f.createGlyphVector(g.getFontRenderContext(), String.valueOf(ch));
            Shape s = gv.getOutline((float) x, (float) y);
            g.setColor(halo);
            g.setStroke(new BasicStroke(Math.max(2f, size / 5f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(s);
            g.setColor(fill);
            g.fill(s);
            x += fm.charWidth(ch) + tracking;
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
            drawMarker(g, a.province, slot, NationColors.of(gs.nationTag(a.owner)), String.valueOf((int) Math.round(a.totalUnits())),
                    a.id, own, 1f, a.battleId >= 0, false, !own);
        }
        for (Intel.Sighting s : in.activeSightings(gs.day)) {
            if (s.quality <= 1 && zoom < 3.0) continue;   // bare contacts only clutter the strategic view
            double conf = Math.max(0.3, Intel.confidence(gs.day - s.day) * 1.1);
            Color c = s.ownerKnown ? NationColors.of(gs.nationTag(s.owner)) : new Color(140, 144, 152);
            String text = s.quality >= 3 ? String.valueOf((int) Math.round(Math.max(1, sum(s.composition))))
                    : s.quality == 2 ? "~" + (int) Math.max(1, Math.round(s.strengthEstimate / 11.0)) : "?";
            drawMarker(g, s.province, slot, c, text, s.armyId, false, (float) Math.min(1.0, conf), false, gs.day != s.day, true);
        }
    }

    private static double sum(double[] a) { double s = 0; for (double v : a) s += v; return s; }

    private void drawMarker(Graphics2D g, int prov, Map<Integer, Integer> slot, Color c, String text,
                            int armyId, boolean own, float alpha, boolean inBattle, boolean stale, boolean foreign) {
        Point2D.Double s = screen(prov);
        if (s.x < -50 || s.y < -50 || s.x > getWidth() + 50 || s.y > getHeight() + 50) return;
        double sz = Math.max(0.85, Math.min(1.35, 0.72 + zoom * 0.1));
        int mw = (int) (32 * sz), mh = (int) (19 * sz);
        int k = slot.merge(prov, 1, Integer::sum) - 1;
        int x = (int) s.x - mw / 2 + k * (mw + 4), y = (int) s.y - mh / 2 - k * 2;
        Composite old = g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
        RoundRectangle2D.Double body = new RoundRectangle2D.Double(x, y, mw, mh, 7, 7);
        g.setColor(new Color(0, 0, 0, 90));
        g.fill(new RoundRectangle2D.Double(x + 1.5, y + 2.5, mw, mh, 7, 7));
        Color top = c.brighter(), bot = new Color(Math.max(0, c.getRed() - 50), Math.max(0, c.getGreen() - 50), Math.max(0, c.getBlue() - 50));
        g.setPaint(new GradientPaint(0, y, top, 0, y + mh, bot));
        g.fill(body);
        g.setPaint(null);
        boolean selected = armyId == selArmy;
        if (inBattle) { g.setColor(new Color(255, 70, 50, 120)); g.setStroke(new BasicStroke(5f)); g.draw(body); }
        if (selected) { g.setColor(new Color(255, 230, 90, 140)); g.setStroke(new BasicStroke(5f)); g.draw(body); }
        g.setStroke(stale && foreign ? new BasicStroke(1.4f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{3, 3}, 0) : new BasicStroke(own ? 2f : 1.4f));
        g.setColor(selected ? new Color(255, 230, 90) : own ? Color.WHITE : new Color(20, 24, 32, 220));
        g.draw(body);
        Font f = Fonts.label((float) (13.5 * sz)).deriveFont(Font.BOLD);
        g.setFont(f);
        FontMetrics fm = g.getFontMetrics();
        int tx = x + (mw - fm.stringWidth(text)) / 2, ty = y + (mh + fm.getAscent()) / 2 - 2;
        g.setColor(new Color(0, 0, 0, 160));
        g.drawString(text, tx + 1, ty + 1);
        g.setColor(Color.WHITE);
        g.drawString(text, tx, ty);
        g.setComposite(old);
        hits.add(new Hit(new Rectangle(x, y, mw, mh), armyId, own));
    }

    private void drawLegend(Graphics2D g) {
        String t = mode.label.toUpperCase() + " MAP" + (mode == Mode.INTEL ? "   |   green exact  -  lime estimate  -  amber contact  -  dark unseen (memory fades)" : "");
        g.setFont(Fonts.label(13f));
        FontMetrics fm = g.getFontMetrics();
        int wd = fm.stringWidth(t) + 24;
        g.setColor(new Color(8, 14, 26, 190));
        g.fillRoundRect(10, getHeight() - 34, wd, 24, 10, 10);
        g.setColor(new Color(255, 255, 255, 40));
        g.drawRoundRect(10, getHeight() - 34, wd, 24, 10, 10);
        g.setColor(new Color(225, 232, 244));
        g.drawString(t, 22, getHeight() - 17);
    }

    // ------------------------------------------------------------------ input
    private int provinceAt(int sx, int sy) {
        int px = (int) ((sx - ox) / zoom), py = (int) ((sy - oy) / zoom);
        if (px < 0 || py < 0 || px >= w.width || py >= w.height) return -1;
        return w.idMap[py * w.width + px];
    }

    private void click(MouseEvent e) {
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (h.r.contains(e.getPoint()) && SwingUtilities.isLeftMouseButton(e)) {
                selArmy = h.armyId; selProv = gs.armies.containsKey(h.armyId) ? gs.armies.get(h.armyId).province : selProv;
                Intel.Sighting sg = gs.intelOf(gs.player).sightingOf(h.armyId);
                if (sg != null) selProv = sg.province;
                onSelect.run(); repaint(); return;
            }
        }
        int id = provinceAt(e.getX(), e.getY());
        if (id < 0) return;
        Army sel = gs.armies.get(selArmy);
        if (SwingUtilities.isLeftMouseButton(e) && sel != null && sel.owner == gs.player) {
            // click army, then click destination
            GameState.Result r = gs.orderMove(sel, id);
            if (!r.ok) JOptionPane.showMessageDialog(this, r.message, "Order", JOptionPane.WARNING_MESSAGE);
            onOrder.run(); repaint();
            return;
        }
        selProv = id; selArmy = -1;
        onSelect.run(); repaint();
    }
}
