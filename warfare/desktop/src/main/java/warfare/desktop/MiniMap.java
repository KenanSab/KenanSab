package warfare.desktop;

import warfare.core.GameState;
import warfare.core.WorldData;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

/**
 * The world in the corner of the screen, drawn the way you see it (same colours, same fog), with a frame that shows what
 * the main view covers. Click or drag to move the view; scroll to zoom. At the close zoom the game opens on, it is what
 * keeps a continent-sized map navigable.
 */
final class MiniMap extends JComponent {
    static final int MAX_W = 276, PAD = 6;

    private final MapPanel map;
    private final GameState gs;
    private final WorldData w;
    private final Basemap base;
    private final boolean[] land;
    private final Cache frame = new Cache();

    private int mw, mh;                      // size of the picture inside the frame
    private int[] ids, terrainPx, px;        // per minimap pixel: province id, terrain colour, shown colour
    private BufferedImage shown;

    MiniMap(MapPanel map, GameState gs, Basemap base) {
        this.map = map; this.gs = gs; this.w = gs.world; this.base = base;
        land = new boolean[w.provinces.size()];
        for (WorldData.ProvinceInfo p : w.provinces) land[p.id] = !p.sea;
        setOpaque(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
        setToolTipText("World map: click or drag to move the view, scroll to zoom");
        MouseAdapter ma = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { goTo(e); }
            @Override public void mouseDragged(MouseEvent e) { goTo(e); }
        };
        addMouseListener(ma);
        addMouseMotionListener(ma);
        addMouseWheelListener(e -> map.zoomBy(e.getWheelRotation() < 0 ? 1.3 : 1 / 1.3));
    }

    /** Height of a minimap that is {@code width} wide, frame included. */
    static int heightFor(WorldData w, int width) { return (int) Math.round((width - 2 * PAD) * (double) w.height / w.width) + 2 * PAD; }

    private void goTo(MouseEvent e) {
        if (mw <= 0) return;
        double mx = (e.getX() - PAD) * w.width / (double) mw, my = (e.getY() - PAD) * w.height / (double) mh;
        map.centerOnMapPoint(Math.max(0, Math.min(w.width, mx)), Math.max(0, Math.min(w.height, my)));
    }

    // ------------------------------------------------------------------ picture
    private static final Map<String, BufferedImage> THUMBS = new HashMap<>();

    /** Terrain with relief, shrunk step by step so thin coasts do not turn into noise. */
    private BufferedImage terrain(int tw, int th) {
        String key = tw + "x" + th;
        synchronized (THUMBS) {
            BufferedImage hit = THUMBS.get(key);
            if (hit != null) return hit;
        }
        int hw = base.image.getWidth() / 2, hh = base.image.getHeight() / 2;
        BufferedImage cur = new BufferedImage(hw, hh, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = cur.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(base.image, 0, 0, hw, hh, null);
        g.drawImage(base.relief, 0, 0, hw, hh, null);
        g.dispose();
        while (cur.getWidth() / 2 >= tw) {
            int nw = cur.getWidth() / 2, nh = Math.max(th, cur.getHeight() / 2);
            BufferedImage n = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
            Graphics2D ng = n.createGraphics();
            ng.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            ng.drawImage(cur, 0, 0, nw, nh, null);
            ng.dispose();
            cur = n;
        }
        BufferedImage out = new BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB);
        Graphics2D og = out.createGraphics();
        og.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        og.drawImage(cur, 0, 0, tw, th, null);
        og.dispose();
        synchronized (THUMBS) {
            if (THUMBS.size() > 6) THUMBS.clear();
            THUMBS.put(key, out);
        }
        return out;
    }

    /** Rebuilds the lookup tables when the minimap changes size. */
    private void resize() {
        int tw = getWidth() - 2 * PAD, th = getHeight() - 2 * PAD;
        if (tw < 40 || th < 20 || (tw == mw && th == mh)) return;
        mw = tw; mh = th;
        BufferedImage t = terrain(mw, mh);
        terrainPx = t.getRGB(0, 0, mw, mh, null, 0, mw);
        px = new int[mw * mh];
        ids = new int[mw * mh];
        for (int y = 0; y < mh; y++) {
            int sy = Math.min(w.height - 1, (int) ((y + 0.5) * w.height / mh));
            for (int x = 0; x < mw; x++) ids[y * mw + x] = w.idMap[sy * w.width + Math.min(w.width - 1, (int) ((x + 0.5) * w.width / mw))];
        }
        shown = new BufferedImage(mw, mh, BufferedImage.TYPE_INT_RGB);
        recolour();
    }

    /** Lays the current province colours over the terrain and darkens the borders between countries. */
    void recolour() {
        if (ids == null) return;
        int[] fill = map.fillColours();
        int[] owner = gs.intelOf(gs.player).knownOwner;
        for (int i = 0; i < ids.length; i++) {
            int id = ids[i], t = terrainPx[i];
            px[i] = id < 0 ? t : tint(t, fill[id]);
        }
        for (int y = 0; y < mh - 1; y++)
            for (int x = 0; x < mw - 1; x++) {
                int i = y * mw + x, id = ids[i];
                if (id < 0 || !land[id]) continue;
                int o = owner[id], r = ids[i + 1], d = ids[i + mw];
                if ((r >= 0 && land[r] && owner[r] != o) || (d >= 0 && land[d] && owner[d] != o)) px[i] = scale(px[i], 0.58);
            }
        shown.setRGB(0, 0, mw, mh, px, 0, mw);
        repaint();
    }

    private static int tint(int t, int f) {
        double a = (f >>> 24) / 255.0 * 0.9;
        if (a <= 0.004) return t;
        int r = (int) (((t >> 16) & 255) * (1 - a) + ((f >> 16) & 255) * a);
        int g = (int) (((t >> 8) & 255) * (1 - a) + ((f >> 8) & 255) * a);
        int b = (int) ((t & 255) * (1 - a) + (f & 255) * a);
        return (r << 16) | (g << 8) | b;
    }

    private static int scale(int c, double k) { return ((int) (((c >> 16) & 255) * k) << 16) | ((int) (((c >> 8) & 255) * k) << 8) | (int) ((c & 255) * k); }

    // ------------------------------------------------------------------ painting
    @Override public void setBounds(int x, int y, int wd, int ht) {
        boolean size = wd != getWidth() || ht != getHeight();
        super.setBounds(x, y, wd, ht);
        if (size) resize();
    }

    @Override protected void paintComponent(Graphics g0) {
        if (shown == null) resize();
        if (shown == null) return;
        Graphics2D g = (Graphics2D) g0.create();
        int cw = getWidth(), ch = getHeight();
        frame.paint(g, 0, 0, cw, ch, null, b -> {
            Skin.shadow(b, 2, 2, cw - 8, ch - 8, 6, 5, 120);
            Skin.plate(b, new RoundRectangle2D.Float(0, 0, cw - 4, ch - 4, 6, 6), Skin.Kind.PANEL, 1f);
        });
        g.drawImage(shown, PAD - 2, PAD - 2, null);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.translate(PAD - 2, PAD - 2);

        // where the player's capital is
        WorldData.ProvinceInfo cap = w.provinces.get(w.nations.get(gs.player).capital);
        double sx = mw / (double) w.width, sy = mh / (double) w.height;
        g.setColor(new Color(0, 0, 0, 160)); g.fill(new Ellipse2D.Double(cap.cx * sx - 3.2, cap.cy * sy - 3.2, 6.4, 6.4));
        g.setColor(Skin.BRASS_HI); g.fill(new Ellipse2D.Double(cap.cx * sx - 2.2, cap.cy * sy - 2.2, 4.4, 4.4));

        // what the main view shows
        Rectangle2D.Double v = map.viewMapRect();
        double x0 = Math.max(0, v.x * sx), y0 = Math.max(0, v.y * sy), x1 = Math.min(mw, (v.x + v.width) * sx), y1 = Math.min(mh, (v.y + v.height) * sy);
        if (x1 - x0 < mw * 0.97 || y1 - y0 < mh * 0.97) {
            Rectangle2D.Double r = new Rectangle2D.Double(x0, y0, Math.max(3, x1 - x0), Math.max(3, y1 - y0));
            g.setColor(new Color(255, 255, 255, 26)); g.fill(r);
            g.setStroke(new BasicStroke(3.2f)); g.setColor(new Color(0, 0, 0, 150)); g.draw(r);
            g.setStroke(new BasicStroke(1.3f)); g.setColor(new Color(255, 244, 200)); g.draw(r);
        }
        g.translate(-(PAD - 2), -(PAD - 2));
        Skin.frame(g, 0.5f, 0.5f, cw - 5, ch - 5, 6, true, false);
        g.dispose();
    }
}
