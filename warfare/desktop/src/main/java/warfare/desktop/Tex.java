package warfare.desktop;

import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.util.EnumMap;
import java.util.Random;

/** Procedurally generated UI textures (leather, wood, gold, parchment) plus a studded frame, so panels are not flat gradients. */
final class Tex {
    private Tex() {}

    enum Kind { LEATHER, DARK_LEATHER, WOOD, GOLD, PARCHMENT }

    private static final EnumMap<Kind, TexturePaint> CACHE = new EnumMap<>(Kind.class);
    private static final int S = 192;

    static synchronized TexturePaint paint(Kind k) {
        return CACHE.computeIfAbsent(k, Tex::make);
    }

    private static TexturePaint make(Kind k) {
        BufferedImage im = new BufferedImage(S, S, BufferedImage.TYPE_INT_ARGB);
        Random r = new Random(k.ordinal() * 7919L + 3);
        float[] n1 = noise(r, 6), n2 = noise(r, 24), n3 = noise(r, 96);
        for (int y = 0; y < S; y++)
            for (int x = 0; x < S; x++) {
                double a = n1[y * S + x], b = n2[y * S + x], c = n3[y * S + x], grain = r.nextDouble();
                int rgb;
                switch (k) {
                    case LEATHER -> { double v = 0.78 + 0.14 * a + 0.10 * b + 0.12 * (c > 0.62 ? 1 : 0) * 0.5 + 0.06 * grain; rgb = rgb(78 * v, 58 * v, 42 * v); }
                    case DARK_LEATHER -> { double v = 0.72 + 0.14 * a + 0.10 * b + 0.10 * (c > 0.6 ? 1 : 0) * 0.5 + 0.06 * grain; rgb = rgb(50 * v, 38 * v, 28 * v); }
                    case WOOD -> {
                        double plank = Math.sin((y + a * 18) * 0.42) * 0.5 + 0.5;       // horizontal grain
                        double v = 0.74 + 0.16 * plank + 0.10 * b + 0.04 * grain;
                        if (y % 64 < 2) v *= 0.55;                                     // plank seams
                        rgb = rgb(104 * v, 70 * v, 40 * v);
                    }
                    case GOLD -> { double v = 0.86 + 0.10 * Math.sin((x * 0.9 + a * 12)) + 0.05 * b + 0.03 * grain; rgb = rgb(232 * v, 184 * v, 84 * v); }
                    default -> { double v = 0.88 + 0.08 * a + 0.06 * b + 0.04 * grain + (c > 0.8 ? -0.05 : 0); rgb = rgb(236 * v, 222 * v, 184 * v); }
                }
                im.setRGB(x, y, 0xFF000000 | rgb);
            }
        return new TexturePaint(im, new Rectangle2D.Double(0, 0, S, S));
    }

    private static int rgb(double r, double g, double b) {
        return (cl(r) << 16) | (cl(g) << 8) | cl(b);
    }
    private static int cl(double v) { return (int) Math.max(0, Math.min(255, v)); }

    /** Tileable value noise with the given lattice size. */
    private static float[] noise(Random r, int cells) {
        float[][] lat = new float[cells][cells];
        for (float[] row : lat) for (int i = 0; i < cells; i++) row[i] = r.nextFloat();
        float[] out = new float[S * S];
        for (int y = 0; y < S; y++)
            for (int x = 0; x < S; x++) {
                double fx = x * cells / (double) S, fy = y * cells / (double) S;
                int x0 = (int) fx, y0 = (int) fy, x1 = (x0 + 1) % cells, y1 = (y0 + 1) % cells;
                double tx = fx - x0, ty = fy - y0;
                tx = tx * tx * (3 - 2 * tx); ty = ty * ty * (3 - 2 * ty);
                double top = lat[y0][x0] * (1 - tx) + lat[y0][x1] * tx, bot = lat[y1][x0] * (1 - tx) + lat[y1][x1] * tx;
                out[y * S + x] = (float) (top * (1 - ty) + bot * ty);
            }
        return out;
    }

    /** Fills a shape with the texture and a soft top-light / bottom-shade so it reads as a raised plate. */
    static void plate(Graphics2D g, Shape s, Kind k, float shade) {
        Rectangle2D b = s.getBounds2D();
        g.setPaint(paint(k));
        g.fill(s);
        g.setPaint(new GradientPaint(0, (float) b.getY(), new Color(255, 240, 210, (int) (40 * shade)), 0, (float) (b.getY() + b.getHeight()), new Color(0, 0, 0, (int) (110 * shade))));
        g.fill(s);
    }

    /** Gold double border with corner studs, used on windows, cards and HUD plates. */
    static void frame(Graphics2D g, float x, float y, float w, float h, float arc, boolean studs, boolean bright) {
        Color gold = bright ? new Color(240, 200, 104) : new Color(176, 138, 70);
        g.setStroke(new BasicStroke(2.2f));
        g.setColor(new Color(20, 12, 6, 220));
        g.draw(new RoundRectangle2D.Float(x, y, w, h, arc, arc));
        g.setStroke(new BasicStroke(1.4f));
        g.setColor(gold);
        g.draw(new RoundRectangle2D.Float(x + 2.5f, y + 2.5f, w - 5, h - 5, arc, arc));
        g.setColor(new Color(255, 236, 170, bright ? 90 : 45));
        g.draw(new RoundRectangle2D.Float(x + 4.5f, y + 4.5f, w - 9, h - 9, Math.max(2, arc - 2), Math.max(2, arc - 2)));
        if (studs) for (float[] c : new float[][]{{x + 9, y + 9}, {x + w - 9, y + 9}, {x + 9, y + h - 9}, {x + w - 9, y + h - 9}}) stud(g, c[0], c[1]);
    }

    static void stud(Graphics2D g, float cx, float cy) {
        g.setPaint(new RadialGradientPaint(cx - 1, cy - 1.5f, 4.2f, new float[]{0f, 0.6f, 1f}, new Color[]{new Color(255, 240, 180), new Color(200, 152, 66), new Color(90, 60, 24)}));
        g.fill(new Ellipse2D.Float(cx - 3.5f, cy - 3.5f, 7, 7));
    }
}
