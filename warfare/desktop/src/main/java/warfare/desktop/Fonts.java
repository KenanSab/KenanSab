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

    static Font label(float size) { return label.deriveFont(size); }
    static Font ui(float size) { return ui.deriveFont(size); }
    static Font title(float size) { return title.deriveFont(size); }
    static String uiFamily() { return ui.getFamily(); }
}
