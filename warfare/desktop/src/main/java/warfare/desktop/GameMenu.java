package warfare.desktop;

import warfare.core.Achievements;
import warfare.core.GameState;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** The in-game menu and its sub-cards. Everything is drawn by the game; there is no menu bar. */
final class GameMenu {
    private GameMenu() {}

    static void open(GameUi ui) {
        JPanel col = Ui.column();
        col.setBorder(BorderFactory.createEmptyBorder(10, 22, 20, 22));
        col.add(entry("Resume", "time", true, () -> ui.modals.pop()));
        col.add(entry("Save game", "tab_reports", false, () -> ui.modals.push(slots(ui, true))));
        col.add(entry("Load game", "tab_prod", false, () -> ui.modals.push(slots(ui, false))));
        col.add(entry("Settings", "stability", false, () -> ui.modals.push(settings(ui))));
        col.add(entry("Achievements", "trophy", false, () -> ui.modals.push(achievements(ui))));
        col.add(entry("Controls", "general", false, () -> ui.modals.push(controls(ui))));
        col.add(entry("New game", "castle", false, () -> ui.confirm("New game", "Leave this game and choose a new nation? Unsaved progress is lost.", "Start over", true, () -> { ui.modals.clear(); ui.newGame(); })));
        col.add(entry("Quit", "nuke", false, () -> ui.confirm("Quit", "Quit to the desktop? Unsaved progress is lost.", "Quit", true, () -> System.exit(0))));
        ui.modals.push(Modals.card("Menu", "general", col, 380, 0, () -> ui.modals.pop()));
    }

    private static JComponent entry(String text, String icon, boolean primary, Runnable r) {
        Wf.Button b = primary ? Wf.primary(text, icon, r) : Wf.button(text, icon, r);
        b.setAlignmentX(Component.LEFT_ALIGNMENT);
        b.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
        b.setPreferredSize(new Dimension(300, 44));
        JPanel wrap = Ui.column();
        wrap.add(b); wrap.add(Ui.gap(0, 8));
        return wrap;
    }

    // ------------------------------------------------------------------ saves
    static JComponent slots(GameUi ui, boolean save) {
        JPanel col = Ui.column();
        col.setBorder(BorderFactory.createEmptyBorder(8, 18, 18, 18));
        List<String> names = save ? List.of("slot1", "slot2", "slot3") : List.of("slot1", "slot2", "slot3", ui.gs.ironman ? "ironman" : "autosave");
        for (String name : names) {
            Path file = GameUi.savePath(name);
            String when = "empty";
            try { if (Files.exists(file)) when = DateTimeFormatter.ofPattern("d MMM yyyy  HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(Files.getLastModifiedTime(file).toMillis())); } catch (Exception ignored) {}
            boolean has = !when.equals("empty");
            Ui.Card c = new Ui.Card(new BorderLayout());
            String pretty = name.startsWith("slot") ? "Slot " + name.substring(4) : name.equals("ironman") ? "Ironman save" : "Autosave";
            JPanel text = Ui.column();
            text.add(Ui.label(pretty, 15f, Ui.TEXT));
            text.add(Ui.label(when, 12.5f, has ? Ui.MUTED : new Color(122, 122, 118)));
            Wf.Button go = save ? Wf.primary("Save", null, null) : Wf.button("Load", null, null);
            go.setEnabled(save || has);
            go.addActionListener(e -> {
                if (save) {
                    try { gs(ui).save(file); ui.map.toast("Game saved", pretty, "tab_reports"); ui.modals.clear(); }
                    catch (Exception ex) { ui.map.toast("Save failed", ex.getMessage(), "nuke"); }
                } else {
                    try { GameState g2 = GameState.load(file, ui.gs.world); ui.modals.clear(); WarfareApp.install(ui.frame, new GameUi(g2, ui.frame)); }
                    catch (Exception ex) { ui.map.toast("Load failed", String.valueOf(ex.getMessage()), "nuke"); }
                }
            });
            if (!save && ui.gs.ironman && name.startsWith("slot")) go.setEnabled(false);
            c.add(Ui.row(text, Box.createHorizontalGlue(), go), BorderLayout.CENTER);
            col.add(c); col.add(Ui.gap(0, 8));
        }
        if (ui.gs.ironman && !save) col.add(Ui.label("Ironman: only the automatic save can be resumed.", 12.5f, Ui.MUTED));
        return Modals.card(save ? "Save game" : "Load game", "tab_reports", col, 460, 0, () -> ui.modals.pop());
    }

    private static GameState gs(GameUi ui) { return ui.gs; }

    // ------------------------------------------------------------------ settings
    static JComponent settings(GameUi ui) {
        JPanel col = Ui.column();
        col.setBorder(BorderFactory.createEmptyBorder(8, 22, 20, 22));
        col.add(Ui.section("Audio"));
        col.add(slider("Master volume", Settings.master, v -> Settings.master = v));
        col.add(slider("Music", Settings.music, v -> Settings.music = v));
        col.add(slider("Sound effects", Settings.sfx, v -> Settings.sfx = v));
        col.add(Ui.label(Audio.get().isAvailable() ? "Audio device ready." : "No audio device detected (the game stays silent).", 12.5f, Ui.MUTED));
        col.add(Ui.section("Gameplay"));
        col.add(Wf.toggle("Autosave every 30 days", Settings.autosave, on -> Settings.autosave = on));
        col.add(Ui.gap(0, 6));
        col.add(Wf.toggle("Show tutorial hints on new games", Settings.tutorial, on -> Settings.tutorial = on));
        col.add(Ui.gap(0, 6));
        col.add(Wf.toggle("Pause when a decision arrives", Settings.pauseOnEvents, on -> Settings.pauseOnEvents = on));
        col.add(Ui.gap(0, 6));
        col.add(Wf.toggle("Pause to pick tactics in manual battles", Settings.pauseOnTactics, on -> Settings.pauseOnTactics = on));
        col.add(Ui.gap(0, 16));
        col.add(Wf.primary("Done", null, () -> { Settings.save(); ui.modals.pop(); }));
        return Modals.card("Settings", "stability", col, 480, 0, () -> { Settings.save(); ui.modals.pop(); });
    }

    private static JComponent slider(String name, double value, java.util.function.DoubleConsumer set) {
        JSlider s = Wf.slider(0, 100, (int) Math.round(value * 100));
        s.addChangeListener(e -> set.accept(s.getValue() / 100.0));
        JPanel col = Ui.column();
        JLabel val = Ui.label((int) Math.round(value * 100) + "%", 13f, Ui.MUTED);
        s.addChangeListener(e -> val.setText(s.getValue() + "%"));
        col.add(Ui.row(Ui.label(name, 14f, Ui.TEXT), Box.createHorizontalGlue(), val));
        col.add(s);
        col.add(Ui.gap(0, 8));
        return col;
    }

    // ------------------------------------------------------------------ achievements
    static JComponent achievements(GameUi ui) {
        JPanel col = new Ui.ScrollColumn();
        col.setBorder(BorderFactory.createEmptyBorder(6, 16, 14, 16));
        col.add(Ui.label(Profile.unlocked.size() + " of " + Achievements.ALL.size() + " unlocked", 14f, Ui.MUTED));
        col.add(Ui.gap(0, 8));
        for (Achievements.Def a : Achievements.ALL) {
            boolean got = Profile.unlocked.contains(a.id());
            Ui.Card c = new Ui.Card(new BorderLayout());
            if (got) c.edge = new Color(236, 192, 92, 170);
            JPanel text = Ui.column();
            text.add(Ui.label(a.title(), 14.5f, got ? Ui.TEXT : Ui.MUTED));
            text.add(Ui.label(a.description(), 12.5f, Ui.MUTED));
            c.add(Ui.row(Ui.imageLabel(Assets.icon(a.icon(), 30, got ? Ui.GOLD : new Color(107, 106, 104))), Ui.gap(12, 0), text), BorderLayout.CENTER);
            col.add(c); col.add(Ui.gap(0, 5));
        }
        return Modals.card("Achievements", "trophy", Wf.scroll(col), 540, 640, () -> ui.modals.pop());
    }

    // ------------------------------------------------------------------ controls
    static JComponent controls(GameUi ui) {
        JPanel col = Ui.column();
        col.setBorder(BorderFactory.createEmptyBorder(8, 22, 20, 22));
        String[][] rows = {{"Left-click army flag", "select it"}, {"Right-click province", "march the selected army"}, {"Left-click province", "inspect it"},
                {"Left-click capital star", "country screen"}, {"Space", "pause / play"}, {"1 - 5", "game speed"}, {"M", "cycle map mode"},
                {"E  T  P  R  C", "economy, diplomacy, build, reports, your country"}, {"Wheel / + -", "zoom"}, {"Drag / arrows / WASD", "pan"}, {"Esc", "close / menu"}, {"F3", "performance overlay"}};
        for (String[] r : rows) {
            JPanel row = Ui.row(Ui.label(r[0], 14f, Wf.GOLD), Box.createHorizontalGlue(), Ui.label(r[1], 13.5f, Ui.TEXT));
            row.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
            col.add(row);
        }
        col.add(Ui.gap(0, 10));
        col.add(Ui.label("<html><div style='width:420px'>Fog of war: you only see what your units, provinces, radar, aircraft, agents, satellites and allies reveal. Old sightings fade after 45 days.</div></html>", 12.5f, Ui.MUTED));
        col.add(Ui.gap(0, 14));
        col.add(Wf.primary("Close", null, () -> ui.modals.pop()));
        return Modals.card("Controls", "general", col, 560, 0, () -> ui.modals.pop());
    }
}
