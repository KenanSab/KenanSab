package warfare.desktop;

import warfare.core.WorldData;

import java.awt.*;
import java.awt.font.GlyphVector;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Renders the cached map layers for one view (zoom and offset) off the UI thread:
 * unlit terrain, political fills, hillshade relief, lakes, rivers, borders and curved country names,
 * merged into one opaque composite. {@link MapPanel} just blits the composite (scaled while a newer view is still rendering)
 * and patches small regions in place when the fog of war changes.
 */
final class MapLayers {
    /** Extra pixels rendered around the viewport so panning never shows an edge while the next render is in flight. */
    static final int MARGIN = 160;
    /** The animated sea effects are rendered at 1/FX_DIV of the layer resolution. */
    static final int FX_DIV = 4;
    static final Color BACKGROUND = new Color(16, 36, 56);

    record Params(double zoom, double ox, double oy, int w, int h, int[] fill, byte[] arc) {}

    /** A placed country name: outline already in image pixels. */
    record NameDraw(Shape outline, Rectangle bounds, float size, float alpha) {}

    static final class Result {
        final double zoom, ox, oy;
        final int vw, vh, iw, ih;
        BufferedImage fill, line, composite, waveMask, shoreA, shoreB;
        int[] fillSnap; byte[] arcSnap;
        List<NameDraw> names = new ArrayList<>();
        Result(double zoom, double ox, double oy, int vw, int vh) {
            this.zoom = zoom; this.ox = ox; this.oy = oy; this.vw = vw; this.vh = vh;
            this.iw = vw + 2 * MARGIN; this.ih = vh + 2 * MARGIN;
        }
        Rectangle imageRect() { return new Rectangle(0, 0, iw, ih); }
    }

    /** Where a country's name goes: a gently curved spine through the largest landmass. */
    private static final class Spine {
        String text;
        double mx, my;            // anchor on the map (map units)
        double ax, ay;            // reading direction
        double tc;                // offset of the label centre along the axis
        double half;              // half length available (map units)
        double thick;             // typical thickness across the axis (map units)
        double a, b, c;           // perpendicular offset f(t/half) = a + b t + c t^2, t in [-1,1]
    }

    private final WorldData w;
    private final MapShapes shapes;
    private final Basemap base;
    private final MapExtras extras;
    private final List<Spine> spines = new ArrayList<>();

    MapLayers(WorldData w, MapShapes shapes, Basemap base, MapExtras extras) {
        this.w = w; this.shapes = shapes; this.base = base; this.extras = extras;
        buildSpines();
    }

    // ------------------------------------------------------------------ geometry helpers
    static Graphics2D hq(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        return g;
    }

    static AffineTransform worldToImage(Result r) {
        AffineTransform at = new AffineTransform();
        at.translate(MARGIN + r.ox, MARGIN + r.oy);
        at.scale(r.zoom, r.zoom);
        return at;
    }

    static Rectangle imageRect(Result r, Rectangle2D b, int pad) {
        int x0 = (int) Math.floor(b.getX() * r.zoom + r.ox + MARGIN) - pad, y0 = (int) Math.floor(b.getY() * r.zoom + r.oy + MARGIN) - pad;
        int x1 = (int) Math.ceil((b.getX() + b.getWidth()) * r.zoom + r.ox + MARGIN) + pad, y1 = (int) Math.ceil((b.getY() + b.getHeight()) * r.zoom + r.oy + MARGIN) + pad;
        return new Rectangle(x0, y0, x1 - x0, y1 - y0).intersection(r.imageRect());
    }

    static Rectangle2D worldRect(Result r, Rectangle ir) {
        return new Rectangle2D.Double((ir.x - MARGIN - r.ox) / r.zoom, (ir.y - MARGIN - r.oy) / r.zoom, ir.width / r.zoom, ir.height / r.zoom);
    }

    // ------------------------------------------------------------------ full render
    Result render(Params p) {
        Result r = new Result(p.zoom, p.ox, p.oy, p.w, p.h);
        r.fillSnap = p.fill; r.arcSnap = p.arc;
        r.fill = new BufferedImage(r.iw, r.ih, BufferedImage.TYPE_INT_ARGB);
        r.line = new BufferedImage(r.iw, r.ih, BufferedImage.TYPE_INT_ARGB);
        r.composite = new BufferedImage(r.iw, r.ih, BufferedImage.TYPE_INT_RGB);
        Rectangle all = r.imageRect();
        drawFills(r, all);
        placeNames(r);
        drawLines(r, all);
        composite(r, all);
        r.waveMask = halfRes(r, base.seaMask);
        r.shoreA = halfRes(r, base.shoreA);
        r.shoreB = halfRes(r, base.shoreB);
        return r;
    }

    /** A mask image (0.75 px per map unit) resampled to half the layer resolution for the animated sea effects. */
    private BufferedImage halfRes(Result r, BufferedImage src) {
        BufferedImage m = new BufferedImage(r.iw / FX_DIV + 1, r.ih / FX_DIV + 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = hq(m);
        AffineTransform at = new AffineTransform();
        at.translate((MARGIN + r.ox) / FX_DIV, (MARGIN + r.oy) / FX_DIV);
        double s = r.zoom / (FX_DIV * Basemap.MASK_PX_PER_UNIT);
        at.scale(s, s);
        g.drawImage(src, at, null);
        g.dispose();
        return m;
    }

    void composite(Result r, Rectangle region) {
        Rectangle clip = region.intersection(r.imageRect());
        if (clip.isEmpty()) return;
        Graphics2D g = hq(r.composite);
        g.setClip(clip);
        g.setColor(BACKGROUND);
        g.fillRect(clip.x, clip.y, clip.width, clip.height);
        AffineTransform at = new AffineTransform();
        at.translate(MARGIN + r.ox, MARGIN + r.oy);
        at.scale(r.zoom / Basemap.PX_PER_UNIT, r.zoom / Basemap.PX_PER_UNIT);
        g.drawImage(base.image, at, null);
        g.drawImage(r.fill, 0, 0, null);
        g.drawImage(base.relief, at, null);
        g.drawImage(r.line, 0, 0, null);
        g.dispose();
    }

    // ------------------------------------------------------------------ fills
    private void drawFills(Result r, Rectangle region) {
        Graphics2D g = hq(r.fill);
        g.setClip(region);
        g.setComposite(AlphaComposite.Clear);
        g.fillRect(region.x, region.y, region.width, region.height);
        g.setComposite(AlphaComposite.SrcOver);
        g.transform(worldToImage(r));
        Rectangle2D view = worldRect(r, region);
        int[] fill = r.fillSnap;
        for (int p = 0; p < fill.length; p++) {
            if ((fill[p] >>> 24) == 0 || !view.intersects(shapes.bounds[p])) continue;
            g.setColor(new Color(fill[p], true));
            g.fill(shapes.fill[p]);
        }
        g.dispose();
    }

    /** Incremental update: repaint only the neighbourhood of the provinces whose colour changed. Returns the dirty region. */
    Rectangle updateFill(Result r, int[] now, List<Integer> changed) {
        Rectangle dirty = null;
        for (int p : changed) {
            r.fillSnap[p] = now[p];
            Rectangle ir = imageRect(r, shapes.bounds[p], 3);
            if (ir.isEmpty()) continue;
            drawFills(r, ir);
            dirty = dirty == null ? ir : dirty.union(ir);
        }
        return dirty;
    }

    Rectangle updateLines(Result r, byte[] now, List<Integer> changed) {
        Rectangle dirty = null;
        for (int a : changed) {
            r.arcSnap[a] = now[a];
            Rectangle ir = imageRect(r, shapes.arcBounds[a], 7);
            if (ir.isEmpty()) continue;
            drawLines(r, ir);
            dirty = dirty == null ? ir : dirty.union(ir);
        }
        return dirty;
    }

    // ------------------------------------------------------------------ lines: lakes, rivers, borders, names
    private void drawLines(Result r, Rectangle region) {
        Graphics2D g = hq(r.line);
        g.setClip(region);
        g.setComposite(AlphaComposite.Clear);
        g.fillRect(region.x, region.y, region.width, region.height);
        g.setComposite(AlphaComposite.SrcOver);
        Graphics2D gw = (Graphics2D) g.create();
        gw.transform(worldToImage(r));
        Rectangle2D view = worldRect(r, region);
        double z = r.zoom;

        // lakes: small ones only appear once you zoom in
        int maxRank = z < 1.2 ? 3 : z < 2.2 ? 5 : z < 4 ? 7 : 9;
        for (MapExtras.Lake l : extras.lakes) {
            if (l.rank() > maxRank || !view.intersects(l.bounds())) continue;
            gw.setColor(new Color(58, 104, 140, 238));
            gw.fill(l.path());
            gw.setColor(new Color(176, 210, 230, 150));
            gw.setStroke(new BasicStroke((float) (1.0 / z)));
            gw.draw(l.path());
        }
        // rivers: major ones everywhere, tributaries as you zoom in
        int riverRank = z < 1.0 ? 0 : z < 1.8 ? 1 : z < 3.2 ? 2 : 3;
        double[] width = {2.0, 1.4, 1.0, 0.8};
        double wscale = 0.7 + 0.18 * Math.min(z, 6);
        for (MapExtras.River rv : extras.rivers) {
            if (rv.rank() > riverRank || !view.intersects(rv.bounds())) continue;
            float px = (float) (width[rv.rank()] * wscale / z);
            gw.setStroke(new BasicStroke(px * 1.9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            gw.setColor(new Color(12, 24, 40, 70));
            gw.draw(rv.path());
            gw.setStroke(new BasicStroke(px, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            gw.setColor(new Color(112, 176, 214, 230));
            gw.draw(rv.path());
        }
        gw.dispose();

        // borders
        g.transform(worldToImage(r));
        strokeArcs(g, r, view, 4, new Color(255, 255, 255, r.zoom < 1.6 ? 22 : 34), 0.7);
        strokeArcs(g, r, view, 3, new Color(255, 255, 255, 105), 0.8);
        strokeArcs(g, r, view, 1, new Color(70, 52, 36, 140), 1.2);
        strokeArcs(g, r, view, 2, new Color(34, 22, 14, 238), 1.6);
        g.setTransform(new AffineTransform());

        // names
        for (NameDraw n : r.names) {
            if (!n.bounds().intersects(region)) continue;
            g.setColor(new Color(0, 0, 0, (int) (80 * n.alpha())));
            g.translate(n.size() * 0.05, n.size() * 0.07);
            g.setStroke(new BasicStroke(n.size() * 0.17f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(n.outline());
            g.translate(-n.size() * 0.05, -n.size() * 0.07);
            g.setColor(new Color(26, 18, 10, (int) (205 * n.alpha())));
            g.setStroke(new BasicStroke(Math.max(1.6f, n.size() * 0.13f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(n.outline());
            g.setColor(new Color(252, 244, 224, (int) (255 * n.alpha())));
            g.fill(n.outline());
        }
        g.dispose();
    }

    private void strokeArcs(Graphics2D g, Result r, Rectangle2D view, int cls, Color c, double px) {
        g.setColor(c);
        g.setStroke(new BasicStroke((float) (px / r.zoom), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        byte[] arc = r.arcSnap;
        for (int a = 0; a < arc.length; a++)
            if (arc[a] == cls && view.intersects(shapes.arcBounds[a])) g.draw(shapes.arcPath[a]);
    }

    // ------------------------------------------------------------------ country names along a curved spine
    private void buildSpines() {
        for (WorldData.NationInfo n : w.nations) {
            Set<Integer> mine = new HashSet<>(n.provinces), seen = new HashSet<>();
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
            Spine s = fit(n.name.toUpperCase(), best);
            if (s != null) spines.add(s);
        }
    }

    private Spine fit(String text, List<Integer> provs) {
        double tw = 0, mx = 0, my = 0;
        for (int p : provs) { WorldData.ProvinceInfo pi = w.provinces.get(p); tw += pi.pixels; mx += pi.cx * pi.pixels; my += pi.cy * pi.pixels; }
        if (tw <= 0) return null;
        mx /= tw; my /= tw;
        double sxx = 0, sxy = 0, syy = 0;
        for (int p : provs) {
            WorldData.ProvinceInfo pi = w.provinces.get(p);
            double dx = pi.cx - mx, dy = pi.cy - my;
            sxx += dx * dx * pi.pixels; sxy += dx * dy * pi.pixels; syy += dy * dy * pi.pixels;
        }
        sxx /= tw; sxy /= tw; syy /= tw;
        double elong = (Math.hypot((sxx - syy) / 2, sxy)) / Math.max(1e-9, (sxx + syy) / 2);
        double theta = elong < 0.22 ? 0 : 0.5 * Math.atan2(2 * sxy, sxx - syy);
        double ax = Math.cos(theta), ay = Math.sin(theta);
        if (ax < -1e-9 || (Math.abs(ax) < 1e-9 && ay > 0)) { ax = -ax; ay = -ay; }
        double px = -ay, py = ax;
        // extent along the axis, thickness across it
        double tmin = 1e18, tmax = -1e18, avgR = 0;
        for (int p : provs) {
            WorldData.ProvinceInfo pi = w.provinces.get(p);
            double r = Math.sqrt(pi.pixels / Math.PI);
            avgR += r * pi.pixels;
            double t = (pi.cx - mx) * ax + (pi.cy - my) * ay;
            tmin = Math.min(tmin, t - r); tmax = Math.max(tmax, t + r);
        }
        avgR /= tw;
        double len = tmax - tmin;
        double tc = (tmin + tmax) / 2, half = len / 2;
        double uvar = 0;
        for (int p : provs) {
            WorldData.ProvinceInfo pi = w.provinces.get(p);
            double u = (pi.cx - mx) * px + (pi.cy - my) * py;
            uvar += u * u * pi.pixels;
        }
        double thick = Math.max(avgR * 1.7, 2.3 * Math.sqrt(uvar / tw));
        // weighted quadratic fit of the centre line u(t)
        double[][] A = new double[3][3]; double[] B = new double[3];
        for (int p : provs) {
            WorldData.ProvinceInfo pi = w.provinces.get(p);
            double t = ((pi.cx - mx) * ax + (pi.cy - my) * ay - tc) / Math.max(1e-6, half);
            double u = (pi.cx - mx) * px + (pi.cy - my) * py;
            double wt = pi.pixels;
            double[] f = {1, t, t * t};
            for (int i = 0; i < 3; i++) { B[i] += wt * f[i] * u; for (int j = 0; j < 3; j++) A[i][j] += wt * f[i] * f[j]; }
        }
        double[] coef = solve3(A, B);
        Spine s = new Spine();
        s.text = text; s.mx = mx; s.my = my; s.ax = ax; s.ay = ay; s.tc = tc; s.half = half; s.thick = thick;
        double lim = thick * 0.30;
        s.a = clampAbs(coef[0], thick * 0.5); s.b = clampAbs(coef[1], thick * 0.5); s.c = clampAbs(coef[2], lim);
        return s;
    }

    private static double clampAbs(double v, double m) { return Math.max(-m, Math.min(m, v)); }

    private static double[] solve3(double[][] a, double[] b) {
        double[][] m = {a[0].clone(), a[1].clone(), a[2].clone()};
        double[] v = b.clone();
        for (int i = 0; i < 3; i++) m[i][i] += 1e-6 * Math.max(1, m[i][i]);
        for (int c = 0; c < 3; c++) {
            int piv = c;
            for (int r = c + 1; r < 3; r++) if (Math.abs(m[r][c]) > Math.abs(m[piv][c])) piv = r;
            double[] tr = m[c]; m[c] = m[piv]; m[piv] = tr; double tv = v[c]; v[c] = v[piv]; v[piv] = tv;
            if (Math.abs(m[c][c]) < 1e-12) return new double[]{0, 0, 0};
            for (int r = c + 1; r < 3; r++) {
                double f = m[r][c] / m[c][c];
                for (int k = c; k < 3; k++) m[r][k] -= f * m[c][k];
                v[r] -= f * v[c];
            }
        }
        double[] x = new double[3];
        for (int i = 2; i >= 0; i--) {
            double s = v[i];
            for (int j = i + 1; j < 3; j++) s -= m[i][j] * x[j];
            x[i] = s / m[i][i];
        }
        return x;
    }

    private void placeNames(Result r) {
        r.names = new ArrayList<>();
        double z = r.zoom;
        double fade = z > 7 ? Math.max(0, 1 - (z - 7) / 4) : 1;
        if (fade <= 0.02) return;
        Rectangle2D view = new Rectangle2D.Double(-r.ox / z - 40, -r.oy / z - 40, r.vw / z + 80, r.vh / z + 80);
        java.awt.font.FontRenderContext frc = new java.awt.font.FontRenderContext(null, true, true);
        Font base = Fonts.labelBold(100f);
        for (Spine s : spines) {
            double lenPx = s.half * 2 * z * 0.84, thickPx = s.thick * z;
            double cx = s.mx + s.ax * s.tc, cy = s.my + s.ay * s.tc;
            if (!view.intersects(cx - s.half, cy - s.half, s.half * 2, s.half * 2)) continue;
            int n = s.text.length();
            // natural advances at 100 px, then scaled
            double[] adv = new double[n];
            double natural100 = 0;
            for (int i = 0; i < n; i++) { adv[i] = base.createGlyphVector(frc, String.valueOf(s.text.charAt(i))).getGlyphMetrics(0).getAdvanceX(); natural100 += adv[i]; }
            double size = Math.min(Math.min(thickPx * 0.40, 58), lenPx * 0.95 / (natural100 / 100.0));
            if (size < 9.5) continue;
            double natural = natural100 * size / 100.0;
            double tracking = n > 1 ? (lenPx * 0.86 - natural) / (n - 1) : 0;
            tracking = Math.max(size * 0.10, Math.min(size * 0.62, tracking));
            double total = natural + tracking * (n - 1);
            if (total > lenPx * 1.02) { size *= lenPx * 1.02 / total; natural = natural100 * size / 100.0; tracking = size * 0.10; total = natural + tracking * (n - 1); }
            if (size < 9.5) continue;
            Font f = base.deriveFont((float) size);
            Path2D.Double outline = new Path2D.Double();
            double u = -total / 2;
            double px = -s.ay, py = s.ax;
            for (int i = 0; i < n; i++) {
                double a = adv[i] * size / 100.0;
                double t = (u + a / 2) / z;                       // map units along the axis from the centre
                double tn = Math.max(-1, Math.min(1, t / Math.max(1e-6, s.half)));
                double off = s.a + s.b * tn + s.c * tn * tn;
                double slope = (s.b + 2 * s.c * tn) / Math.max(1e-6, s.half);
                double wx = s.mx + s.ax * (s.tc + t) + px * off, wy = s.my + s.ay * (s.tc + t) + py * off;
                double ix = wx * z + r.ox + MARGIN, iy = wy * z + r.oy + MARGIN;
                double ang = Math.atan2(s.ay + py * slope, s.ax + px * slope);
                char ch = s.text.charAt(i);
                if (ch != ' ') {
                    GlyphVector gv = f.createGlyphVector(frc, String.valueOf(ch));
                    Shape glyph = gv.getOutline((float) (-a / 2), (float) (size * 0.36));
                    AffineTransform at = new AffineTransform();
                    at.translate(ix, iy); at.rotate(ang);
                    outline.append(at.createTransformedShape(glyph), false);
                }
                u += a + tracking;
            }
            Rectangle bounds = outline.getBounds();
            bounds.grow((int) (size * 0.3) + 2, (int) (size * 0.3) + 2);
            r.names.add(new NameDraw(outline, bounds, (float) size, (float) fade));
        }
    }
}
