package warfare.desktop;

import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Remembers how a rarely changing piece of the interface looks, so that repainting the map behind it costs one blit
 * instead of a dozen anti-aliased gradient fills and soft shadows. The image is made at the display's own resolution,
 * so it stays sharp on Windows displays that run at 125% or 150%.
 */
final class Cache {
    private BufferedImage img;
    private int w, h;
    private double sx, sy;
    private Object key;

    /**
     * Draws the region at (x, y) of {@code g}. {@code painter} runs only when the size, the display scale or {@code key}
     * changed since the last call; inside it, (0, 0) is the top-left corner of the region.
     */
    void paint(Graphics2D g, int x, int y, int w, int h, Object key, Consumer<Graphics2D> painter) {
        if (w <= 0 || h <= 0) return;
        AffineTransform t = g.getTransform();
        double sx = Math.hypot(t.getScaleX(), t.getShearY()), sy = Math.hypot(t.getScaleY(), t.getShearX());
        if (img == null || w != this.w || h != this.h || sx != this.sx || sy != this.sy || !Objects.equals(key, this.key)) {
            int pw = Math.max(1, (int) Math.ceil(w * sx)), ph = Math.max(1, (int) Math.ceil(h * sy));
            if (img == null || img.getWidth() != pw || img.getHeight() != ph) img = new BufferedImage(pw, ph, BufferedImage.TYPE_INT_ARGB_PRE);
            Graphics2D b = img.createGraphics();
            b.setComposite(AlphaComposite.Clear);
            b.fillRect(0, 0, pw, ph);
            b.setComposite(AlphaComposite.SrcOver);
            b.scale(sx, sy);
            Wf.quality(b);
            painter.accept(b);
            b.dispose();
            this.w = w; this.h = h; this.sx = sx; this.sy = sy; this.key = key;
        }
        g.drawImage(img, x, y, w, h, null);
    }
}
