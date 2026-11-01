package warfare.desktop;

import warfare.core.*;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.ArrayList;
import java.util.List;

/** The world map: province colouring per map mode, fog of war shading, army markers, pan/zoom, selection. */
final class MapPanel extends JPanel {
    enum Mode {
        POLITICAL("Political"), TERRAIN("Terrain"), INTEL("Intel / Vision"), DIPLOMATIC("Diplomatic");
        final String label;
        Mode(String l) { label = l; }
    }

    private static final int[] TERRAIN_RGB = {
            0xB0C878, 0x588C54, 0x8C8076, 0xDEC482, 0x367846, 0xE4EAF0, 0x34548C};

    private record Hit(Rectangle r, int armyId, boolean own) {}

    final GameState gs;
    final WorldData w;
    Mode mode = Mode.POLITICAL;
    int selProv = -1, selArmy = -1;
    Runnable onSelect = () -> {};
    Runnable onOrder = () -> {};

    private final BufferedImage img;
    private final int[] pixels;
    private final int[] provColor;
    private final int[] bIdx, bOther;
    private double zoom = 1, ox, oy;
    private boolean fitted;
    private Point dragFrom;
    private boolean dragged;
    private final List<Hit> hits = new ArrayList<>();

    MapPanel(GameState gs) {
        this.gs = gs; this.w = gs.world;
        setBackground(new Color(18, 24, 36));
        img = new BufferedImage(w.width, w.height, BufferedImage.TYPE_INT_RGB);
        pixels = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
        provColor = new int[w.provinces.size()];
        List<int[]> b = new ArrayList<>();
        for (int y = 0; y < w.height; y++)
            for (int x = 0; x < w.width; x++) {
                int i = y * w.width + x, id = w.idMap[i];
                if (x < w.width - 1 && w.idMap[i + 1] != id) { b.add(new int[]{i, w.idMap[i + 1]}); b.add(new int[]{i + 1, id}); }
                if (y < w.height - 1 && w.idMap[i + w.width] != id) { b.add(new int[]{i, w.idMap[i + w.width]}); b.add(new int[]{i + w.width, id}); }
            }
        bIdx = new int[b.size()]; bOther = new int[b.size()];
        for (int k = 0; k < b.size(); k++) { bIdx[k] = b.get(k)[0]; bOther[k] = b.get(k)[1]; }
        MouseAdapter ma = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { dragFrom = e.getPoint(); dragged = false; }
            @Override public void mouseDragged(MouseEvent e) {
                if (dragFrom == null) return;
                int dx = e.getX() - dragFrom.x, dy = e.getY() - dragFrom.y;
                if (Math.abs(dx) + Math.abs(dy) > 3) dragged = true;
                if (dragged) { ox += dx; oy += dy; dragFrom = e.getPoint(); repaint(); }
            }
            @Override public void mouseReleased(MouseEvent e) { if (!dragged) click(e); dragFrom = null; }
            @Override public void mouseWheelMoved(MouseWheelEvent e) {
                double f = e.getWheelRotation() < 0 ? 1.18 : 1 / 1.18;
                double nz = Math.max(0.35, Math.min(14, zoom * f));
                ox = e.getX() - (e.getX() - ox) * nz / zoom;
                oy = e.getY() - (e.getY() - oy) * nz / zoom;
                zoom = nz; repaint();
            }
        };
        addMouseListener(ma); addMouseMotionListener(ma); addMouseWheelListener(ma);
        refreshColors();
    }

    void centerOn(int province, double z) {
        WorldData.ProvinceInfo p = w.provinces.get(province);
        zoom = z; ox = getWidth() / 2.0 - p.cx * zoom; oy = getHeight() / 2.0 - p.cy * zoom; fitted = true; repaint();
    }

    void setMode(Mode m) { mode = m; refreshColors(); }

    // ------------------------------------------------------------------ colours
    void refreshColors() {
        Intel in = gs.intelOf(gs.player);
        int me = gs.player;
        for (int p = 0; p < provColor.length; p++) provColor[p] = colorOf(p, in, me);
        int[] ids = w.idMap;
        for (int i = 0; i < pixels.length; i++) pixels[i] = ids[i] < 0 ? 0x101820 : provColor[ids[i]];
        for (int k = 0; k < bIdx.length; k++) {
            int i = bIdx[k], a = ids[i], o = bOther[k];
            if (a < 0) continue;
            int ka = knownOwner(in, a), ko = o < 0 ? -9 : knownOwner(in, o);
            double f = ka != ko ? 0.38 : 0.78;
            pixels[i] = NationColors.scale(provColor[a], f);
        }
        repaint();
    }

    private int knownOwner(Intel in, int p) { return w.provinces.get(p).sea ? -2 : in.knownOwner[p]; }

    private int colorOf(int p, Intel in, int me) {
        WorldData.ProvinceInfo pi = w.provinces.get(p);
        int q = in.quality[p];
        double conf = in.provinceConfidence(p, gs.day);
        int base;
        switch (mode) {
            case TERRAIN -> {
                base = TERRAIN_RGB[pi.terrain.ordinal()];
                return q > 0 ? base : NationColors.mix(NationColors.scale(base, 0.8), 0x707880, 0.25 * (1 - conf));
            }
            case INTEL -> {
                if (q == 3) return 0x50C878;
                if (q == 2) return 0xAAD25A;
                if (q == 1) return 0xE6B446;
                int g = (int) (50 + 60 * conf);
                return pi.sea ? NationColors.mix(0x1c2c44, 0x3a5070, conf) : (g << 16) | (g << 8) | (g + 10);
            }
            case DIPLOMATIC -> {
                if (pi.sea) return q > 0 ? 0x34548C : 0x2a3c5c;
                int ko = in.knownOwner[p];
                if (ko < 0) return 0x777777;
                int c;
                if (ko == me) c = 0x3CA0E6;
                else if (gs.isAlly(me, ko)) c = 0x50C878;
                else if (gs.atWar(me, ko)) c = 0xD2463C;
                else c = switch (w.nations.get(ko).bloc) {
                    case "NATO" -> 0x466EC8; case "CSTO" -> 0xC87846; case "EASTERN" -> 0xAA46A0; case "PACIFIC" -> 0x3CAAAA; default -> 0x969696;
                };
                return q > 0 ? c : NationColors.mix(c, 0x606068, 0.45 * (1 - conf) + 0.15);
            }
            default -> {
                if (pi.sea) return q > 0 ? 0x34548C : NationColors.mix(0x2a3c5c, 0x34548C, conf * 0.5);
                int ko = in.knownOwner[p];
                if (ko < 0) return 0x777777;
                base = NationColors.rgb(NationColors.of(w.nations.get(ko).tag));
                if (q > 0 || ko == me) return base;
                return NationColors.mix(NationColors.scale(base, 0.9), 0x6c7078, 0.6 * (1 - conf));
            }
        }
    }

    // ------------------------------------------------------------------ painting
    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0;
        if (!fitted && getWidth() > 0) {
            zoom = Math.max(0.5, getWidth() / (double) w.width);
            ox = 0; oy = (getHeight() - w.height * zoom) / 2;
            fitted = true;
        }
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, zoom >= 2 ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR : RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        AffineTransform at = new AffineTransform();
        at.translate(ox, oy); at.scale(zoom, zoom);
        g.drawImage(img, at, null);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        hits.clear();
        if (selProv >= 0 && selProv < w.provinces.size()) drawOutline(g, selProv);
        drawPath(g);
        drawArmies(g);
        drawLegend(g);
    }

    private Point2D screen(int p) {
        WorldData.ProvinceInfo pi = w.provinces.get(p);
        return new Point2D(pi.cx * zoom + ox, pi.cy * zoom + oy);
    }

    private record Point2D(double x, double y) {}

    private void drawOutline(Graphics2D g, int p) {
        Point2D s = screen(p);
        double r = Math.max(8, Math.sqrt(w.provinces.get(p).pixels) * zoom * 0.6);
        g.setColor(new Color(255, 255, 255, 200));
        g.setStroke(new BasicStroke(2f));
        g.drawOval((int) (s.x - r), (int) (s.y - r), (int) (2 * r), (int) (2 * r));
    }

    private void drawPath(Graphics2D g) {
        Army a = gs.armies.get(selArmy);
        if (a == null || a.owner != gs.player || a.path.isEmpty()) return;
        g.setColor(new Color(255, 230, 90, 220));
        g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[]{6, 4}, 0));
        Point2D prev = screen(a.province);
        for (int p : a.path) {
            Point2D s = screen(p);
            g.drawLine((int) prev.x, (int) prev.y, (int) s.x, (int) s.y);
            prev = s;
        }
        g.fillOval((int) prev.x - 4, (int) prev.y - 4, 8, 8);
    }

    private void drawArmies(Graphics2D g) {
        Intel in = gs.intelOf(gs.player);
        int me = gs.player;
        java.util.Map<Integer, Integer> slot = new java.util.HashMap<>();
        g.setFont(g.getFont().deriveFont(Font.BOLD, 10.5f));
        for (Army a : gs.armies.values()) {
            boolean own = a.owner == me;
            boolean allied = !own && a.owner >= 0 && gs.isAlly(me, a.owner) && in.quality[a.province] >= 2;
            if (!own && !allied) continue;
            drawMarker(g, a.province, slot, NationColors.of(gs.nationTag(a.owner)), String.valueOf((int) Math.round(a.totalUnits())),
                    a.id, own, 1f, a.battleId >= 0, false);
        }
        for (Intel.Sighting s : in.activeSightings(gs.day)) {
            double conf = Math.max(0.25, Intel.confidence(gs.day - s.day) * 1.1);
            float alpha = (float) Math.min(1.0, conf);
            Color c = s.ownerKnown ? NationColors.of(gs.nationTag(s.owner)) : new Color(150, 150, 150);
            String text = s.quality >= 3 ? String.valueOf((int) Math.round(Math.max(1, sum(s.composition))))
                    : s.quality == 2 ? "~" + (int) Math.max(1, Math.round(s.strengthEstimate / 11.0)) : "?";
            drawMarker(g, s.province, slot, c, text, s.armyId, false, alpha, false, gs.day != s.day);
        }
    }

    private static double sum(double[] a) { double s = 0; for (double v : a) s += v; return s; }

    private void drawMarker(Graphics2D g, int prov, java.util.Map<Integer, Integer> slot, Color c, String text,
                            int armyId, boolean own, float alpha, boolean inBattle, boolean stale) {
        Point2D s = screen(prov);
        if (s.x < -40 || s.y < -40 || s.x > getWidth() + 40 || s.y > getHeight() + 40) return;
        double sz = Math.max(0.8, Math.min(1.4, 0.7 + zoom * 0.12));
        int mw = (int) (28 * sz), mh = (int) (16 * sz);
        int k = slot.merge(prov, 1, Integer::sum) - 1;
        int x = (int) s.x - mw / 2 + k * (mw + 3) - 0, y = (int) s.y - mh / 2 - k * 2;
        Composite old = g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
        g.setColor(c);
        g.fillRect(x, y, mw, mh);
        g.setColor(inBattle ? new Color(255, 40, 30) : armyId == selArmy && own ? Color.YELLOW : own ? Color.BLACK : stale ? new Color(40, 40, 40, 160) : Color.DARK_GRAY);
        g.setStroke(new BasicStroke(armyId == selArmy || inBattle ? 2.5f : 1.3f));
        if (stale && !own) g.setStroke(new BasicStroke(1.3f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{3, 3}, 0));
        g.drawRect(x, y, mw, mh);
        g.setColor(brightness(c) > 140 ? Color.BLACK : Color.WHITE);
        FontMetrics fm = g.getFontMetrics();
        g.drawString(text, x + (mw - fm.stringWidth(text)) / 2, y + (mh + fm.getAscent()) / 2 - 2);
        g.setComposite(old);
        hits.add(new Hit(new Rectangle(x, y, mw, mh), armyId, own));
    }

    private static int brightness(Color c) { return (c.getRed() * 299 + c.getGreen() * 587 + c.getBlue() * 114) / 1000; }

    private void drawLegend(Graphics2D g) {
        String t = "Map mode: " + mode.label + (mode == Mode.INTEL ? "   green = exact, yellow-green = estimate, amber = contact, grey = unseen (fading memory)" : "");
        g.setFont(g.getFont().deriveFont(Font.PLAIN, 12f));
        g.setColor(new Color(0, 0, 0, 150));
        g.fillRoundRect(8, getHeight() - 28, g.getFontMetrics().stringWidth(t) + 16, 20, 8, 8);
        g.setColor(Color.WHITE);
        g.drawString(t, 16, getHeight() - 13);
    }

    // ------------------------------------------------------------------ input
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
        int px = (int) ((e.getX() - ox) / zoom), py = (int) ((e.getY() - oy) / zoom);
        if (px < 0 || py < 0 || px >= w.width || py >= w.height) return;
        int id = w.idMap[py * w.width + px];
        if (id < 0) return;
        Army sel = gs.armies.get(selArmy);
        if (SwingUtilities.isLeftMouseButton(e) && sel != null && sel.owner == gs.player) {
            // click army, then click destination
            GameState.Result r = gs.orderMove(sel, id);
            if (!r.ok) JOptionPane.showMessageDialog(this, r.message, "Order", JOptionPane.WARNING_MESSAGE);
            onOrder.run(); repaint();
            return;
        }
        // right click (or left click with no own army selected): inspect the province
        selProv = id; selArmy = -1;
        onSelect.run(); repaint();
    }
}
