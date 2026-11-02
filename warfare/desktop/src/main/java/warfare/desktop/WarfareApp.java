package warfare.desktop;

import warfare.core.*;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.List;

/** Warfare - desktop client. Run with no arguments to play; "--shot out.png [TAG] [days]" renders a headless screenshot. */
public final class WarfareApp {
    static final int[] DAY_MS = {1000, 500, 250, 100, 40};
    static Path assets;

    public static void main(String[] args) throws Exception {
        assets = findAssets(args);
        Fonts.load(assets);
        Assets.init(assets);
        theme();
        if (args.length >= 2 && args[0].equals("--picker")) { picker(args[1]); return; }
        if (args.length >= 2 && args[0].equals("--shot")) { System.setProperty("java.awt.headless", "true"); shot(args); return; }
        WorldData world = WorldData.load(assets);
        SwingUtilities.invokeLater(() -> {
            JFrame f = new JFrame("Warfare");
            f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            f.setSize(1500, 900);
            f.setLocationRelativeTo(null);
            String forced = null;
            for (int i = 0; i < args.length - 1; i++) if (args[i].equals("--nation")) forced = args[i + 1];
            start(f, world, forced != null ? world.nationByTag.get(forced) : chooseNation(f, world));
            f.setVisible(true);
            for (String a : args) if (a.equals("--smoke")) smoke(f);
        });
    }

    /** Plays the real window for a few seconds at top speed, then screenshots it and exits. */
    static void smoke(JFrame f) {
        GameUi ui = (GameUi) f.getContentPane();
        ui.setSpeed(5);
        javax.swing.Timer t = new javax.swing.Timer(8000, e -> {
            try {
                BufferedImage img = new BufferedImage(f.getWidth(), f.getHeight(), BufferedImage.TYPE_INT_RGB);
                f.paint(img.getGraphics());
                ImageIO.write(img, "png", new File("smoke.png"));
                System.out.printf("renders %d avg %.1f ms%n", ui.map.renders, ui.map.renderNs / 1e6 / Math.max(1, ui.map.renders));
                System.out.printf("smoke ok, day %d | per tick ms: sim %.1f colors %.1f ui+paint %.1f (%d ticks)%n", ui.gs.day, GameUi.simNs / 1e6 / Math.max(1, GameUi.ticks), GameUi.colorNs / 1e6 / Math.max(1, GameUi.ticks), GameUi.uiNs / 1e6 / Math.max(1, GameUi.ticks), GameUi.ticks);
            } catch (Exception ex) { ex.printStackTrace(); }
            System.exit(0);
        });
        t.setRepeats(false); t.start();
    }

    static void theme() {
        com.formdev.flatlaf.FlatDarkLaf.setup();
        UIManager.put("defaultFont", Fonts.ui(13f));
        UIManager.put("Component.arc", 8);
        UIManager.put("Button.arc", 8);
        UIManager.put("TabbedPane.selectedBackground", new java.awt.Color(38, 50, 72));
        UIManager.put("Panel.background", new java.awt.Color(20, 26, 38));
        UIManager.put("Component.focusColor", new java.awt.Color(120, 170, 240));
    }

    static Path findAssets(String[] args) {
        for (int i = 0; i < args.length - 1; i++) if (args[i].equals("--assets")) return Path.of(args[i + 1]);
        for (String c : new String[]{"assets/map", "../assets/map", "../../assets/map", "warfare/assets/map"})
            if (Files.exists(Path.of(c, "provinces.png"))) return Path.of(c);
        throw new IllegalStateException("Cannot find assets/map. Pass --assets <dir>.");
    }

    static int chooseNation(JFrame f, WorldData w) { return NationPicker.pick(f, w); }

    static void start(JFrame f, WorldData world, int nation) {
        GameState gs = new GameState(world, nation, System.nanoTime());
        install(f, new GameUi(gs, f));
    }

    static void install(JFrame f, GameUi ui) {
        f.setContentPane(ui);
        f.setJMenuBar(ui.menuBar());
        f.revalidate();
        ui.focusStart();
    }

    // ------------------------------------------------------------------ headless screenshot
    static void shot(String[] args) throws Exception {
        String out = args[1];
        String tag = args.length > 2 ? args[2] : "RUS";
        int days = args.length > 3 ? Integer.parseInt(args[3]) : 30;
        WorldData world = WorldData.load(assets);
        GameState gs = new GameState(world, world.nationByTag.get(tag), 7);
        int me = gs.player;
        int foe = world.nationByTag.get(args.length > 4 ? args[4] : "UKR");
        gs.declareWar(me, foe);
        int focus = -1;
        for (int i = 0; i < days; i++) {
            if (i % 3 == 0) for (Army a : gs.armies.values()) if (a.owner == me && a.path.isEmpty() && a.battleId < 0) Ai.manageArmies(gs, me);
            gs.advanceDay();
            gs.pauseRequested = false; gs.pendingEvents.clear(); gs.awaitingTactic.clear();
        }
        for (Army a : gs.armies.values()) if (a.owner == me) { focus = a.province; break; }
        final int fp = focus;
        BufferedImage[] img = new BufferedImage[1];
        SwingUtilities.invokeAndWait(() -> {
            GameUi ui = new GameUi(gs, null);
            ui.setSize(1500, 900);
            layoutAll(ui);
            ui.map.centerOn(fp >= 0 ? fp : world.nations.get(me).capital, 3.2);
            if (args.length > 5) ui.map.setMode(MapPanel.Mode.valueOf(args[5]));
            ui.map.selProv = fp;
            if (args.length > 6) ui.tabs.setSelectedIndex(Integer.parseInt(args[6]));
            if (args.length > 7 && args[7].equals("army"))
                for (Army a : gs.armies.values()) if (a.owner == me) { ui.map.selArmy = a.id; ui.map.selProv = a.province; break; }
            ui.refresh();
            layoutAll(ui);
            img[0] = new BufferedImage(1500, 900, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img[0].createGraphics();
            ui.paint(g);
            g.dispose();
        });
        ImageIO.write(img[0], "png", new File(out));
        System.out.println("wrote " + out);
    }

    static void picker(String out) throws Exception {
        System.clearProperty("java.awt.headless");
        WorldData world = WorldData.load(assets);
        SwingUtilities.invokeAndWait(() -> {
            JFrame f = new JFrame("picker");
            NationPicker p = new NationPicker(world);
            f.setContentPane(p);
            f.setSize(900, 580);
            f.setVisible(true);
            javax.swing.Timer t = new javax.swing.Timer(1500, e -> {
                try {
                    BufferedImage img = new BufferedImage(900, 580, BufferedImage.TYPE_INT_RGB);
                    Graphics2D g = img.createGraphics();
                    f.getContentPane().paint(g);
                    g.dispose();
                    ImageIO.write(img, "png", new File(out));
                    System.out.println("wrote " + out);
                } catch (Exception ex) { ex.printStackTrace(); }
                System.exit(0);
            });
            t.setRepeats(false); t.start();
        });
    }

    static void layoutAll(Component c) {
        c.doLayout();
        if (c instanceof Container k) for (Component ch : k.getComponents()) layoutAll(ch);
    }
}
