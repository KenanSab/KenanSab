package warfare.desktop;

import java.awt.*;
import java.awt.geom.*;

/**
 * The look of the interface: graphite surfaces, hairline borders, a single brass accent, soft shadows.
 * No textures: every surface is a smooth gradient with a one-pixel top light and a dark outer edge,
 * so panels stay crisp at any size and the map shows through the translucent ones.
 */
final class Skin {
    private Skin() {}

    enum Kind { PANEL, RAISED, BAR, GOLD, INSET, DANGER }

    // ---- palette
    static final Color BRASS = new Color(217, 178, 94), BRASS_HI = new Color(240, 206, 124), BRASS_LO = new Color(150, 116, 52);
    static final Color HAIR = new Color(255, 255, 255, 22), HAIR_STRONG = new Color(255, 255, 255, 44), EDGE = new Color(0, 0, 0, 170);

    private static Color[] colours(Kind k) {
        return switch (k) {
            case PANEL -> new Color[]{new Color(36, 37, 41, 253), new Color(23, 24, 27, 254)};
            case RAISED -> new Color[]{new Color(48, 49, 54, 255), new Color(37, 38, 42, 255)};
            case BAR -> new Color[]{new Color(30, 31, 35, 238), new Color(19, 20, 23, 242)};
            case INSET -> new Color[]{new Color(14, 15, 17, 200), new Color(20, 21, 24, 200)};
            case GOLD -> new Color[]{new Color(243, 212, 132), new Color(201, 158, 66)};
            case DANGER -> new Color[]{new Color(176, 66, 56), new Color(128, 42, 38)};
        };
    }

    /** Fills a shape with the surface of the given kind. {@code shade} below 1 flattens the highlight (pressed or inactive). */
    static void plate(Graphics2D g, Shape s, Kind k, float shade) {
        Rectangle2D b = s.getBounds2D();
        Color[] c = colours(k);
        g.setPaint(new GradientPaint(0, (float) b.getY(), c[0], 0, (float) (b.getY() + b.getHeight()), c[1]));
        g.fill(s);
        if (k != Kind.INSET) {
            // one-pixel top light, clipped to the shape
            Shape old = g.getClip();
            g.clip(s);
            g.setColor(new Color(255, 255, 255, (int) ((k == Kind.GOLD ? 90 : 20) * Math.max(0, Math.min(1, shade)))));
            g.fill(new Rectangle2D.Double(b.getX(), b.getY(), b.getWidth(), 1.2));
            g.setClip(old);
        }
    }

    /**
     * Border: a dark outer edge and a faint inner light. {@code bright} turns the edge brass (focus, selection, hover).
     * {@code corners} adds small brass brackets on the four corners, the signature of windows and cards.
     */
    static void frame(Graphics2D g, float x, float y, float w, float h, float arc, boolean corners, boolean bright) {
        Stroke old = g.getStroke();
        g.setStroke(new BasicStroke(1f));
        g.setColor(bright ? new Color(BRASS.getRed(), BRASS.getGreen(), BRASS.getBlue(), 190) : EDGE);
        g.draw(new RoundRectangle2D.Float(x, y, w, h, arc, arc));
        g.setColor(bright ? new Color(255, 232, 170, 46) : HAIR);
        g.draw(new RoundRectangle2D.Float(x + 1, y + 1, w - 2, h - 2, Math.max(0, arc - 1), Math.max(0, arc - 1)));
        if (corners) {
            float len = Math.min(14, Math.min(w, h) / 4), in = 3.5f;
            g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER));
            g.setColor(bright ? BRASS_HI : BRASS);
            float[][] c = {{x + in, y + in, 1, 1}, {x + w - in, y + in, -1, 1}, {x + in, y + h - in, 1, -1}, {x + w - in, y + h - in, -1, -1}};
            for (float[] p : c) {
                g.draw(new Line2D.Float(p[0], p[1], p[0] + p[2] * len, p[1]));
                g.draw(new Line2D.Float(p[0], p[1], p[0], p[1] + p[3] * len));
            }
        }
        g.setStroke(old);
    }

    /** Soft drop shadow made of a few translucent layers: cheap, and unlike an offset rectangle it has no hard edge. */
    static void shadow(Graphics2D g, float x, float y, float w, float h, float arc, int spread, int alpha) {
        for (int i = spread; i >= 1; i--) {
            float grow = i * 1.6f;
            g.setColor(new Color(0, 0, 0, Math.max(1, alpha * (spread - i + 1) / (spread * spread + 1))));
            g.fill(new RoundRectangle2D.Float(x - grow, y - grow + spread * 0.9f, w + 2 * grow, h + 2 * grow, arc + grow, arc + grow));
        }
    }

    /** A thin brass rule that fades out to the right: under headings. */
    static void rule(Graphics2D g, float x, float y, float w) {
        g.setPaint(new GradientPaint(x, y, new Color(BRASS.getRed(), BRASS.getGreen(), BRASS.getBlue(), 230), x + w, y, new Color(BRASS.getRed(), BRASS.getGreen(), BRASS.getBlue(), 0)));
        g.fill(new Rectangle2D.Float(x, y, w, 1.5f));
    }
}
