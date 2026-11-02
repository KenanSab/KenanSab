package warfare.desktop;

import warfare.core.Achievements;
import warfare.core.GameState;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Persistent player profile: unlocked achievements (also mirrored to Steam when it is available). */
final class Profile {
    private Profile() {}

    static final Set<String> unlocked = new LinkedHashSet<>();
    static Path file = Path.of(System.getProperty("user.home"), ".warfare", "profile.properties");

    static void load() {
        unlocked.clear();
        try (InputStream in = Files.newInputStream(file)) {
            Properties p = new Properties();
            p.load(in);
            for (String s : p.getProperty("achievements", "").split(",")) if (!s.isBlank()) unlocked.add(s.trim());
        } catch (Exception ignored) {}
    }

    static void save() {
        try {
            Files.createDirectories(file.getParent());
            Properties p = new Properties();
            p.setProperty("achievements", String.join(",", unlocked));
            try (OutputStream out = Files.newOutputStream(file)) { p.store(out, "Warfare profile"); }
        } catch (Exception ignored) {}
    }

    /** Evaluates the game state and returns newly unlocked achievements (already saved and sent to Steam). */
    static List<Achievements.Def> check(GameState gs) {
        List<Achievements.Def> fresh = Achievements.newlyUnlocked(gs, unlocked);
        if (fresh.isEmpty()) return fresh;
        for (Achievements.Def d : fresh) { unlocked.add(d.id()); Steam.unlock(d.id()); }
        save();
        return fresh;
    }
}
