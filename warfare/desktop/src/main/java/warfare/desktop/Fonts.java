package warfare.desktop;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.nio.file.Path;

/** Free fonts shipped in assets/fonts (SIL OFL): Barlow, Barlow Condensed, Bebas Neue. */
final class Fonts {
    private Fonts() {}

    private static Font label = new Font("SansSerif", Font.BOLD, 12), ui = new Font("SansSerif", Font.PLAIN, 12), title = label;

    static void load(Path mapDir) {
        Path dir = mapDir.resolve("..").resolve("fonts").normalize();
        label = read(dir.resolve("BarlowCondensed-SemiBold.ttf"), label);
        ui = read(dir.resolve("Barlow-Medium.ttf"), ui);
        title = read(dir.resolve("BebasNeue-Regular.ttf"), title);
    }

    private static Font read(Path p, Font fallback) {
        try {
            Font f = Font.createFont(Font.TRUETYPE_FONT, new File(p.toString()));
            GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(f);
            return f;
        } catch (Exception e) { return fallback; }
    }

    private static final java.util.Map<Integer, Font> labelCache = new java.util.HashMap<>(), boldCache = new java.util.HashMap<>();
    static Font label(float size) { return labelCache.computeIfAbsent(Math.round(size * 2), k -> label.deriveFont(k / 2f)); }
    static Font labelBold(float size) { return boldCache.computeIfAbsent(Math.round(size * 2), k -> label.deriveFont(Font.BOLD, k / 2f)); }
    static Font ui(float size) { return ui.deriveFont(size); }
    static Font title(float size) { return title.deriveFont(size); }
    static String uiFamily() { return ui.getFamily(); }
}
