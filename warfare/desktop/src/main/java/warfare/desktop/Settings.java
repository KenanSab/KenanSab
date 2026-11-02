package warfare.desktop;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** User preferences, stored in ~/.warfare/settings.properties. */
final class Settings {
    private Settings() {}

    static double master = 0.8, music = 0.7, sfx = 0.8;
    static boolean autosave = true, tutorial = true;

    static Path file() { return Path.of(System.getProperty("user.home"), ".warfare", "settings.properties"); }

    static void load() {
        try (InputStream in = Files.newInputStream(file())) {
            Properties p = new Properties();
            p.load(in);
            master = Double.parseDouble(p.getProperty("master", "0.8"));
            music = Double.parseDouble(p.getProperty("music", "0.7"));
            sfx = Double.parseDouble(p.getProperty("sfx", "0.8"));
            autosave = Boolean.parseBoolean(p.getProperty("autosave", "true"));
            tutorial = Boolean.parseBoolean(p.getProperty("tutorial", "true"));
        } catch (Exception ignored) {}
    }

    static void save() {
        try {
            Files.createDirectories(file().getParent());
            Properties p = new Properties();
            p.setProperty("master", String.valueOf(master));
            p.setProperty("music", String.valueOf(music));
            p.setProperty("sfx", String.valueOf(sfx));
            p.setProperty("autosave", String.valueOf(autosave));
            p.setProperty("tutorial", String.valueOf(tutorial));
            try (OutputStream out = Files.newOutputStream(file())) { p.store(out, "Warfare settings"); }
        } catch (Exception ignored) {}
    }
}
