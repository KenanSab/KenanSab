package warfare.desktop;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import warfare.core.WorldData;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Loads the free SVG icons (game-icons.net, CC BY 3.0) and flags (flag-icons, MIT) and caches rendered bitmaps. */
final class Assets {
    private Assets() {}

    static Path root = Path.of("assets");
    private static final Map<String, BufferedImage> cache = new HashMap<>();

    static void init(Path mapDir) { root = mapDir.resolve("..").normalize(); }

    static BufferedImage icon(String name, int size, Color tint) {
        String key = name + "|" + size + "|" + tint.getRGB();
        return cache.computeIfAbsent(key, k -> {
            BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            File f = root.resolve("icons").resolve(name + ".svg").toFile();
            if (!f.exists()) return img;
            try {
                FlatSVGIcon icon = new FlatSVGIcon(f).derive(size, size);
                icon.setColorFilter(new FlatSVGIcon.ColorFilter(c -> tint));
                Graphics2D g = img.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                icon.paintIcon(null, g, 0, 0);
                g.dispose();
            } catch (Exception ignored) {}
            return img;
        });
    }

    static Icon swingIcon(String name, int size, Color tint) { return new ImageIcon(icon(name, size, tint)); }

    /** Flag with softly rounded corners and a thin edge. Width is given, height follows the 4:3 ratio. */
    static BufferedImage flag(String iso2, int w) {
        int h = Math.round(w * 0.75f);
        String key = "flag|" + iso2 + "|" + w;
        return cache.computeIfAbsent(key, k -> {
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            float arc = Math.max(3f, w / 9f);
            RoundRectangle2D clip = new RoundRectangle2D.Float(0, 0, w, h, arc, arc);
            g.setClip(clip);
            File f = root.resolve("flags").resolve(iso2 + ".svg").toFile();
            boolean drawn = false;
            if (!iso2.isEmpty() && f.exists()) {
                try { new FlatSVGIcon(f).derive(w, h).paintIcon(null, g, 0, 0); drawn = true; } catch (Exception ignored) {}
            }
            if (!drawn) {
                g.setPaint(new GradientPaint(0, 0, new Color(96, 104, 120), 0, h, new Color(60, 66, 80)));
                g.fillRect(0, 0, w, h);
            }
            g.setClip(null);
            g.setColor(new Color(0, 0, 0, 110));
            g.setStroke(new BasicStroke(1f));
            g.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h - 1, arc, arc));
            g.dispose();
            return img;
        });
    }

    static BufferedImage flag(WorldData.NationInfo n, int w) { return flag(n == null ? "" : n.iso2, w); }
}
