package warfare.desktop;

import java.awt.*;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;

/** Small cached bitmaps for text with a halo and soft glows, so per-frame map decoration is a handful of blits. */
final class Sprites {
    private Sprites() {}

    /** A rendered label: {@code (padX, baseline)} is where the text origin (left edge, baseline) sits inside the image. */
    record Sprite(BufferedImage img, int padX, int baseline, int textW) {}

    private static final int MAX = 1200;
    private static final Map<String, Sprite> CACHE = new LinkedHashMap<>(512, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Sprite> e) { return size() > MAX; }
    };

    static synchronized Sprite text(String text, Font font, Color fill, Color halo, float haloWidth, double tracking) {
        String key = text + "|" + font.getSize2D() + "|" + fill.getRGB() + "|" + halo.getRGB() + "|" + haloWidth + "|" + tracking;
        Sprite s = CACHE.get(key);
        if (s != null) return s;
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D pg = probe.createGraphics();
        pg.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        FontMetrics fm = pg.getFontMetrics(font);
        java.awt.font.FontRenderContext frc = pg.getFontRenderContext();
        Path2D.Double path = new Path2D.Double();
        double x = 0;
        for (int i = 0; i < text.length(); i++) {
            String ch = String.valueOf(text.charAt(i));
            GlyphVector gv = font.createGlyphVector(frc, ch);
            path.append(gv.getOutline((float) x, 0f), false);
            x += fm.stringWidth(ch) + tracking;
        }
        pg.dispose();
        int textW = (int) Math.ceil(x - tracking);
        int pad = (int) Math.ceil(haloWidth) + 2;
        Rectangle2D b = path.getBounds2D();
        int w = Math.max(1, (int) Math.ceil(Math.max(textW, b.getWidth())) + 2 * pad), h = Math.max(1, fm.getAscent() + fm.getDescent() + 2 * pad);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.translate(pad, pad + fm.getAscent());
        if (haloWidth > 0) {
            g.setColor(halo);
            g.setStroke(new BasicStroke(haloWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(path);
        }
        g.setColor(fill);
        g.fill(path);
        g.dispose();
        s = new Sprite(img, pad, pad + fm.getAscent(), textW);
        CACHE.put(key, s);
        return s;
    }

    private static final Map<Integer, BufferedImage> GLOWS = new java.util.HashMap<>();

    /** Soft round glow of the given colour (alpha at the centre) and radius in pixels. */
    static synchronized BufferedImage glow(int radius, int rgb, int centerAlpha) {
        int key = radius * 1_000_003 + rgb * 31 + centerAlpha;
        BufferedImage img = GLOWS.get(key);
        if (img != null) return img;
        int d = radius * 2;
        img = new BufferedImage(d, d, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setPaint(new RadialGradientPaint(radius, radius, radius, new float[]{0f, 1f}, new Color[]{new Color((centerAlpha << 24) | (rgb & 0xFFFFFF), true), new Color(rgb & 0xFFFFFF, true)}));
        g.fillRect(0, 0, d, d);
        g.dispose();
        GLOWS.put(key, img);
        return img;
    }
}
