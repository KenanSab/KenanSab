package warfare.desktop;

import warfare.core.Terrain;
import warfare.core.WorldData;

import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

/**
 * Procedural satellite-style underlay: smoothly blended terrain colours, noise-driven hillshading and a coastal
 * depth gradient in the ocean. Built once from the vector province shapes (no downloaded imagery needed).
 */
final class Basemap {
    static final double PX_PER_UNIT = 1.5;
    final BufferedImage image;

    private static final int[] TERRAIN = {
            0x9AA866,   // plains
            0x4A6B44,   // forest
            0x867A68,   // mountain
            0xCDB67C,   // desert
            0x2D5C38,   // jungle
            0xDDE3EA,   // arctic
            0x000000};

    Basemap(MapShapes shapes, WorldData w) {
        int W = (int) Math.round(w.width * PX_PER_UNIT), H = (int) Math.round(w.height * PX_PER_UNIT);
        float[] mask = new float[W * H];
        float[][] rgb = new float[3][W * H];
        float[] mtn = new float[W * H];

        paint(shapes, w, W, H, null, mask);
        for (int t = 0; t < 6; t++) {
            final int rgbv = TERRAIN[t];
            float[] layer = new float[W * H];
            paint(shapes, w, W, H, t, layer);
            for (int i = 0; i < layer.length; i++) {
                rgb[0][i] += layer[i] * ((rgbv >> 16) & 255);
                rgb[1][i] += layer[i] * ((rgbv >> 8) & 255);
                rgb[2][i] += layer[i] * (rgbv & 255);
                if (t == Terrain.MOUNTAIN.ordinal()) mtn[i] = layer[i];
            }
        }
        // normalised blur: colours bleed smoothly into each other across province lines but never out into the sea
        float[] bm = box(mask, W, H, 7, 3);
        float[][] soft = new float[3][];
        for (int c = 0; c < 3; c++) {
            float[] b = box(rgb[c], W, H, 7, 3);
            for (int i = 0; i < b.length; i++) b[i] = bm[i] > 0.001f ? b[i] / bm[i] : 0;
            soft[c] = b;
        }
        float[] mtnSoft = box(mtn, W, H, 9, 2);
        float[] coast = box(mask, W, H, 20, 3);
        float[] shelf = box(mask, W, H, 60, 2);

        float[] height = new float[W * H];
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++) {
                int i = y * W + x;
                double n = fbm(x / 58.0, y / 58.0, 5);
                double ridge = 1 - Math.abs(2 * fbm(x / 34.0 + 40, y / 34.0 + 40, 4) - 1);
                height[i] = (float) (0.45 * n + mtnSoft[i] * (0.55 + 0.9 * ridge));
            }

        image = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        int[] out = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++) {
                int i = y * W + x;
                float dx = height[y * W + Math.min(W - 1, x + 1)] - height[y * W + Math.max(0, x - 1)];
                float dy = height[Math.min(H - 1, y + 1) * W + x] - height[Math.max(0, y - 1) * W + x];
                double shade = Math.max(-0.4, Math.min(0.4, (-dx - dy) * 5.5));
                double fine = 0.93 + 0.14 * fbm(x / 7.0 + 90, y / 7.0 + 12, 2);
                double f = (1 + shade) * fine;
                double lr = soft[0][i] * f, lg = soft[1][i] * f, lb = soft[2][i] * f;
                // ocean: deeper away from land, with a bright shelf close to the coast
                double depth = Math.pow(Math.max(0, Math.min(1, coast[i] * 1.8 + shelf[i] * 0.9)), 0.8);
                double ripple = 0.95 + 0.1 * fbm(x / 20.0 + 200, y / 40.0 + 7, 3);
                double or = (10 + 36 * depth) * ripple, og = (26 + 78 * depth) * ripple, ob = (50 + 92 * depth) * ripple;
                double m = Math.min(1, Math.max(0, mask[i]));
                int r = clamp(or * (1 - m) + lr * m), g = clamp(og * (1 - m) + lg * m), b = clamp(ob * (1 - m) + lb * m);
                out[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
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

    static double fbm(double x, double y, int oct) {
        double v = 0, amp = 0.5, tot = 0;
        for (int o = 0; o < oct; o++) { v += amp * noise(x, y); tot += amp; x *= 2.03; y *= 2.03; amp *= 0.5; }
        return v / tot;
    }
}
