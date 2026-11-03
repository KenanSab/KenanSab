package warfare.desktop;

import warfare.core.Terrain;
import warfare.core.WorldData;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.nio.file.Path;
import java.util.stream.IntStream;

/**
 * Procedural satellite-style underlay, split into three images so the renderer can light the map properly:
 * <ul>
 *   <li>{@link #image}: unlit terrain colours on land, depth-graded ocean with shelf contours and coastal foam;</li>
 *   <li>{@link #relief}: sun lighting (black shadow / white light with alpha) drawn <em>over</em> the political colours so mountains stay three-dimensional in every map mode;</li>
 *   <li>{@link #seaMask}: coverage of open water (reduced resolution) used to confine the animated waves;</li>
 *   <li>{@link #shoreA}, {@link #shoreB}: two bands of surf at different distances from the coast, pulsed in antiphase;</li>
 *   <li>{@link #snow}: where snow can lie (high latitudes, high ground); the season decides how much of it does.</li>
 * </ul>
 * The result is baked to PNG (assets/map/basemap.png etc.) by {@code --basemap}; at runtime it is only loaded.
 */
final class Basemap {
    static final double PX_PER_UNIT = 1.5;
    static final double MASK_PX_PER_UNIT = 0.75;
    final BufferedImage image, relief, seaMask, shoreA, shoreB, snow;

    private static final int[] TERRAIN = {
            0x7F8D4A,   // plains
            0x3B5B36,   // forest
            0x80735F,   // mountain
            0xCDB078,   // desert
            0x25552F,   // jungle
            0xE4EBF1,   // arctic
            0x000000};
    /** How rugged each terrain class is: scales the ridged height noise that feeds the hillshade. */
    private static final double[] RUGGED = {0.16, 0.30, 1.0, 0.34, 0.30, 0.42, 0};

    static boolean baked(Path dir) {
        if (dir == null) return false;
        for (String f : new String[]{"basemap", "relief", "seamask", "shore1", "shore2", "snow"}) if (!dir.resolve(f + ".png").toFile().exists()) return false;
        return true;
    }

    /** Loads the baked underlay when present, otherwise computes it (several seconds). */
    Basemap(MapShapes shapes, WorldData w, Path dir) {
        BufferedImage[] out = new BufferedImage[6];
        if (baked(dir) && !Boolean.getBoolean("warfare.rebake")) {
            try {
                out[0] = convert(ImageIO.read(dir.resolve("basemap.png").toFile()), BufferedImage.TYPE_INT_RGB);
                out[1] = convert(ImageIO.read(dir.resolve("relief.png").toFile()), BufferedImage.TYPE_INT_ARGB);
                out[2] = convert(ImageIO.read(dir.resolve("seamask.png").toFile()), BufferedImage.TYPE_INT_ARGB);
                out[3] = convert(ImageIO.read(dir.resolve("shore1.png").toFile()), BufferedImage.TYPE_INT_ARGB);
                out[4] = convert(ImageIO.read(dir.resolve("shore2.png").toFile()), BufferedImage.TYPE_INT_ARGB);
                out[5] = convert(ImageIO.read(dir.resolve("snow.png").toFile()), BufferedImage.TYPE_INT_ARGB);
            } catch (Exception e) { out[0] = null; }
        }
        if (out[0] == null) out = build(shapes, w);
        image = out[0]; relief = out[1]; seaMask = out[2]; shoreA = out[3]; shoreB = out[4]; snow = out[5];
    }

    void bake(Path dir) throws Exception {
        ImageIO.write(image, "png", dir.resolve("basemap.png").toFile());
        ImageIO.write(relief, "png", dir.resolve("relief.png").toFile());
        ImageIO.write(seaMask, "png", dir.resolve("seamask.png").toFile());
        ImageIO.write(shoreA, "png", dir.resolve("shore1.png").toFile());
        ImageIO.write(shoreB, "png", dir.resolve("shore2.png").toFile());
        ImageIO.write(snow, "png", dir.resolve("snow.png").toFile());
    }

    private static BufferedImage convert(BufferedImage src, int type) {
        if (src.getType() == type) return src;
        BufferedImage dst = new BufferedImage(src.getWidth(), src.getHeight(), type);
        Graphics2D g = dst.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return dst;
    }

    private static BufferedImage[] build(MapShapes shapes, WorldData w) {
        final int W = (int) Math.round(w.width * PX_PER_UNIT), H = (int) Math.round(w.height * PX_PER_UNIT);
        final float[] mask = new float[W * H];
        float[][] rgb = new float[3][W * H];
        float[] rugged = new float[W * H], mtn = new float[W * H];

        paint(shapes, w, W, H, null, mask);
        for (int t = 0; t < 6; t++) {
            final int rgbv = TERRAIN[t];
            float[] layer = new float[W * H];
            paint(shapes, w, W, H, t, layer);
            for (int i = 0; i < layer.length; i++) {
                float l = layer[i];
                if (l == 0) continue;
                rgb[0][i] += l * ((rgbv >> 16) & 255);
                rgb[1][i] += l * ((rgbv >> 8) & 255);
                rgb[2][i] += l * (rgbv & 255);
                rugged[i] += (float) (l * RUGGED[t]);
                if (t == Terrain.MOUNTAIN.ordinal()) mtn[i] = l;
            }
        }
        // normalised blur: colours bleed smoothly into each other across province lines but never out into the sea
        float[] bm = box(mask, W, H, 7, 3);
        final float[][] soft = new float[3][];
        for (int c = 0; c < 3; c++) {
            float[] b = box(rgb[c], W, H, 7, 3);
            for (int i = 0; i < b.length; i++) b[i] = bm[i] > 0.001f ? b[i] / bm[i] : 0;
            soft[c] = b;
        }
        rgb = null;
        final float[] ruggedSoft = box(rugged, W, H, 9, 2);
        final float[] mtnSoft = box(mtn, W, H, 9, 2);
        final float[] coast = box(mask, W, H, 20, 3);
        final float[] shelf = box(mask, W, H, 60, 2);

        // ---- height field: rolling continents + ridge systems scaled by how rugged the terrain class is
        final float[] height = new float[W * H];
        IntStream.range(0, H).parallel().forEach(y -> {
            for (int x = 0; x < W; x++) {
                int i = y * W + x;
                double rolling = fbm(x / 80.0, y / 80.0, 5);
                double ridgeA = 1 - Math.abs(2 * fbm(x / 36.0 + 40, y / 36.0 + 40, 4) - 1);
                double ridgeB = 1 - Math.abs(2 * fbm(x / 13.0 + 7, y / 13.0 + 91, 3) - 1);
                double r = ruggedSoft[i];
                height[i] = (float) (0.55 * rolling + r * (0.62 * ridgeA * ridgeA + 0.30 * ridgeB * ridgeB * (0.4 + 0.6 * ridgeA)) + 0.22 * mtnSoft[i]);
            }
        });

        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        BufferedImage rel = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        final int[] out = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
        final int[] ro = ((DataBufferInt) rel.getRaster().getDataBuffer()).getData();
        IntStream.range(0, H).parallel().forEach(y -> {
            for (int x = 0; x < W; x++) {
                int i = y * W + x;
                double m = Math.min(1, Math.max(0, mask[i]));
                // ---------------- land colour (unlit)
                double lr = 0, lg = 0, lb = 0, shade = 0;
                if (m > 0.002) {
                    float hxm = height[y * W + Math.max(0, x - 1)], hxp = height[y * W + Math.min(W - 1, x + 1)];
                    float hym = height[Math.max(0, y - 1) * W + x], hyp = height[Math.min(H - 1, y + 1) * W + x];
                    double dx = hxp - hxm, dy = hyp - hym;
                    shade = Math.max(-1, Math.min(1, (-dx * 0.78 - dy * 0.62) * 11.0));
                    double patch = fbm(x / 17.0 + 7, y / 17.0 + 3, 3), region = fbm(x / 61.0 + 91, y / 61.0 + 17, 3);
                    double v = 0.86 + 0.28 * patch;
                    lr = soft[0][i] * v * (0.95 + 0.10 * region);
                    lg = soft[1][i] * v;
                    lb = soft[2][i] * v * (1.05 - 0.10 * region);
                    // snow on the highest ridges
                    double snow = smooth(0.80, 1.05, height[i] - 0.10 * (1 - mtnSoft[i])) * mtnSoft[i];
                    lr += (236 - lr) * snow; lg += (240 - lg) * snow; lb += (246 - lb) * snow;
                }
                // ---------------- ocean: open water varies slowly, shelves turn turquoise toward the coast
                double near = Math.pow(Math.max(0, Math.min(1, coast[i] * 1.8 + shelf[i] * 0.9)), 0.8);   // 1 at the coast, 0 in open ocean
                double macro = fbm(x / 240.0 + 13, y / 240.0 + 71, 4) - 0.5, streak = fbm(x / 110.0 + 5, y / 38.0 + 9, 3) - 0.5;
                double ripple = 0.96 + 0.08 * fbm(x / 20.0 + 200, y / 40.0 + 7, 3);
                double contour = 0.5 + 0.5 * Math.sin(near * 26 + 4 * fbm(x / 90.0 + 11, y / 90.0 + 5, 3));
                double caustic = Math.pow(fbm(x / 11.0 + 300, y / 11.0 + 80, 3), 3) * 1.6;
                double or = 8 + 7 * macro + 5 * streak, og = 34 + 26 * macro + 12 * streak, ob = 64 + 30 * macro + 14 * streak;
                double tq = Math.pow(near, 1.35) * 0.78;
                or += (46 - or) * tq; og += (136 - og) * tq; ob += (156 - ob) * tq;
                or = or * ripple + 3 * contour * near + 12 * caustic * near;
                og = og * ripple + 4 * contour * near + 16 * caustic * near;
                ob = ob * ripple + 5 * contour * near + 18 * caustic * near;
                double cm = coast[i];
                double foam = Math.exp(-Math.pow((cm - 0.42) / 0.07, 2)) * 0.20;
                foam *= 0.6 + 0.8 * fbm(x / 9.0 + 500, y / 9.0, 2);
                or += (225 - or) * foam; og += (240 - og) * foam; ob += (244 - ob) * foam;
                out[i] = 0xFF000000 | (clamp(or * (1 - m) + lr * m) << 16) | (clamp(og * (1 - m) + lg * m) << 8) | clamp(ob * (1 - m) + lb * m);
                // ---------------- relief overlay
                if (m > 0.002 && Math.abs(shade) > 0.01) {
                    int a = (int) (Math.min(1, Math.abs(shade)) * (shade > 0 ? 0.40 : 0.52) * m * 255);
                    ro[i] = shade > 0 ? (a << 24) | 0xFFF6E4 : (a << 24) | 0x120C06;
                }
            }
        });

        // sea mask at reduced resolution
        int mw = (int) Math.round(w.width * MASK_PX_PER_UNIT), mh = (int) Math.round(w.height * MASK_PX_PER_UNIT);
        BufferedImage sm = new BufferedImage(mw, mh, BufferedImage.TYPE_INT_ARGB);
        int[] so = ((DataBufferInt) sm.getRaster().getDataBuffer()).getData();
        for (int y = 0; y < mh; y++)
            for (int x = 0; x < mw; x++) {
                int sx = Math.min(W - 1, x * 2), sy = Math.min(H - 1, y * 2);
                int sx1 = Math.min(W - 1, sx + 1), sy1 = Math.min(H - 1, sy + 1);
                double cov = (mask[sy * W + sx] + mask[sy * W + sx1] + mask[sy1 * W + sx] + mask[sy1 * W + sx1]) / 4.0;
                so[y * mw + x] = ((int) (255 * Math.max(0, Math.min(1, 1 - cov * 1.15))) << 24) | 0xFFFFFF;
            }
        BufferedImage sa = new BufferedImage(mw, mh, BufferedImage.TYPE_INT_ARGB), sb = new BufferedImage(mw, mh, BufferedImage.TYPE_INT_ARGB);
        int[] ao = ((DataBufferInt) sa.getRaster().getDataBuffer()).getData(), bo = ((DataBufferInt) sb.getRaster().getDataBuffer()).getData();
        for (int y = 0; y < mh; y++)
            for (int x = 0; x < mw; x++) {
                int sx = Math.min(W - 1, x * 2), sy = Math.min(H - 1, y * 2);
                double c = coast[sy * W + sx], land = Math.min(1, mask[sy * W + sx] * 1.3);
                double n = 0.55 + 0.9 * fbm(x / 6.0 + 40, y / 6.0 + 9, 2);
                double va = Math.exp(-Math.pow((c - 0.455) / 0.03, 2)) * (1 - land) * n, vb = Math.exp(-Math.pow((c - 0.395) / 0.04, 2)) * (1 - land) * n;
                ao[y * mw + x] = ((int) Math.max(0, Math.min(255, va * 235)) << 24) | 0xFFFFFF;
                bo[y * mw + x] = ((int) Math.max(0, Math.min(255, vb * 200)) << 24) | 0xFFFFFF;
            }
        // snow potential: cold latitudes, high ground, broken up by noise (0.75 px per unit like the other masks)
        BufferedImage sn = new BufferedImage(mw, mh, BufferedImage.TYPE_INT_ARGB);
        int[] sno = ((DataBufferInt) sn.getRaster().getDataBuffer()).getData();
        IntStream.range(0, mh).parallel().forEach(y -> {
            for (int x = 0; x < mw; x++) {
                int sx = Math.min(W - 1, x * 2), sy = Math.min(H - 1, y * 2), i = sy * W + sx;
                double land = Math.min(1, mask[i] * 1.2);
                if (land < 0.02) continue;
                double lat = Math.abs(84 - sy * 0.1);
                double noise = fbm(sx / 34.0 + 300, sy / 34.0 + 120, 4);
                double cold = smooth(36, 66, lat) * (0.5 + 0.9 * noise);
                double high = mtnSoft[i] * smooth(0, 38, lat) * (0.35 + 0.9 * smooth(0.55, 1.1, height[i]));
                double pot = Math.max(0, Math.min(1, cold + high));
                sno[y * mw + x] = ((int) (pot * land * 255) << 24) | 0xEEF4FC;
            }
        });
        return new BufferedImage[]{img, rel, sm, sa, sb, sn};
    }

    private static double smooth(double a, double b, double v) {
        double t = Math.max(0, Math.min(1, (v - a) / (b - a)));
        return t * t * (3 - 2 * t);
    }

    private static int clamp(double v) { return (int) Math.max(0, Math.min(255, v)); }

    /** Draws land provinces (optionally only one terrain class) as white coverage into a float array. */
    private static void paint(MapShapes s, WorldData w, int W, int H, Integer terrain, float[] dst) {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setTransform(AffineTransform.getScaleInstance(PX_PER_UNIT, PX_PER_UNIT));
        g.setColor(Color.WHITE);
        for (WorldData.ProvinceInfo p : w.provinces) {
            if (p.sea) continue;
            if (terrain != null && p.terrain.ordinal() != terrain) continue;
            g.fill(s.fill[p.id]);
        }
        g.dispose();
        byte[] data = ((java.awt.image.DataBufferByte) img.getRaster().getDataBuffer()).getData();
        for (int i = 0; i < dst.length; i++) dst[i] = (data[i] & 255) / 255f;
    }

    /** Separable box blur, repeated {@code passes} times. */
    static float[] box(float[] src, int W, int H, int r, int passes) {
        float[] a = src.clone(), b = new float[src.length];
        for (int p = 0; p < passes; p++) {
            for (int y = 0; y < H; y++) {
                double sum = 0;
                int row = y * W;
                for (int x = -r; x <= r; x++) sum += a[row + Math.max(0, Math.min(W - 1, x))];
                for (int x = 0; x < W; x++) {
                    b[row + x] = (float) Math.max(0, sum / (2 * r + 1));
                    sum += a[row + Math.min(W - 1, x + r + 1)] - a[row + Math.max(0, x - r)];
                }
            }
            for (int x = 0; x < W; x++) {
                double sum = 0;
                for (int y = -r; y <= r; y++) sum += b[Math.max(0, Math.min(H - 1, y)) * W + x];
                for (int y = 0; y < H; y++) {
                    a[y * W + x] = (float) Math.max(0, sum / (2 * r + 1));
                    sum += b[Math.min(H - 1, y + r + 1) * W + x] - b[Math.max(0, y - r) * W + x];
                }
            }
        }
        return a;
    }

    // ------------------------------------------------------------------ value noise
    static double hash(int x, int y) {
        int h = x * 374761393 + y * 668265263;
        h = (h ^ (h >>> 13)) * 1274126177;
        return ((h ^ (h >>> 16)) & 0xFFFFFF) / (double) 0xFFFFFF;
    }

    static double noise(double x, double y) {
        int xi = (int) Math.floor(x), yi = (int) Math.floor(y);
        double fx = x - xi, fy = y - yi;
        fx = fx * fx * (3 - 2 * fx); fy = fy * fy * (3 - 2 * fy);
        double a = hash(xi, yi), b = hash(xi + 1, yi), c = hash(xi, yi + 1), d = hash(xi + 1, yi + 1);
        return a + (b - a) * fx + (c - a) * fy + (a - b - c + d) * fx * fy;
    }

    private static final float[] LATTICE = new float[256 * 256];
    static { for (int y = 0; y < 256; y++) for (int x = 0; x < 256; x++) LATTICE[y * 256 + x] = (float) hash(x * 7 + 3, y * 13 + 5); }

    /** fbm and its gradient in one pass (analytic derivative of the smooth value noise): out = {value, d/dx, d/dy}. */
    static void fbmGrad(double x, double y, int oct, double[] out) {
        double v = 0, gx = 0, gy = 0, amp = 0.5, tot = 0, freq = 1;
        for (int o = 0; o < oct; o++) {
            double px = x * freq, py = y * freq;
            int xi = (int) Math.floor(px), yi = (int) Math.floor(py);
            double fx = px - xi, fy = py - yi;
            double ux = fx * fx * (3 - 2 * fx), uy = fy * fy * (3 - 2 * fy);
            double dux = 6 * fx * (1 - fx), duy = 6 * fy * (1 - fy);
            int x0 = xi & 255, x1 = (xi + 1) & 255, y0 = (yi & 255) << 8, y1 = ((yi + 1) & 255) << 8;
            double a = LATTICE[y0 | x0], bb = LATTICE[y0 | x1], c = LATTICE[y1 | x0], d = LATTICE[y1 | x1];
            double k = a - bb - c + d;
            v += amp * (a + (bb - a) * ux + (c - a) * uy + k * ux * uy);
            gx += amp * freq * ((bb - a) + k * uy) * dux;
            gy += amp * freq * ((c - a) + k * ux) * duy;
            tot += amp; freq *= 2.03; amp *= 0.5;
        }
        out[0] = v / tot; out[1] = gx / tot; out[2] = gy / tot;
    }

    static double fbm(double x, double y, int oct) {
        double v = 0, amp = 0.5, tot = 0;
        for (int o = 0; o < oct; o++) { v += amp * noise(x, y); tot += amp; x *= 2.03; y *= 2.03; amp *= 0.5; }
        return v / tot;
    }
}
