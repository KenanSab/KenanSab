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
        Settings.load();
        Profile.load();
        Steam.init();
        theme();
        if (args.length >= 2 && args[0].equals("--export-audio")) { exportAudio(args[1]); return; }
        if (args.length >= 2 && args[0].equals("--basemap")) {
            System.setProperty("java.awt.headless", "true");
            WorldData world = WorldData.load(assets);
            MapShapes shapes = MapShapes.load(assets.resolve("arcs.bin.gz"), world.provinces.size());
            Basemap b = new Basemap(shapes, world);
            ImageIO.write(b.image, "png", new File(args[1]));
            System.out.println("wrote " + args[1] + " " + b.image.getWidth() + "x" + b.image.getHeight());
            return;
        }
        if (args.length >= 2 && args[0].equals("--picker")) { picker(args[1]); return; }
        if (args.length >= 2 && args[0].equals("--shot")) { System.setProperty("java.awt.headless", "true"); shot(args); return; }
        WorldData world = WorldData.load(assets);
        Audio.get().start();
        SwingUtilities.invokeLater(() -> {
            JFrame f = new JFrame("Warfare");
            f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            f.setSize(1500, 900);
            f.setLocationRelativeTo(null);
            String forced = null;
            for (int i = 0; i < args.length - 1; i++) if (args[i].equals("--nation")) forced = args[i + 1];
            start(f, world, forced != null ? new NationPicker.Choice(world.nationByTag.get(forced), false) : chooseNation(f, world));
            f.setVisible(true);
            for (int i = 0; i < args.length; i++) if (args[i].equals("--smoke")) smoke(f, i + 1 < args.length && args[i + 1].matches("\\d+") ? Integer.parseInt(args[i + 1]) : 8);
        });
    }

    /** Plays the real window for a few seconds at top speed, then screenshots it and exits. */
    static JOptionPane findPane(Container c) {
        if (c instanceof JOptionPane p) return p;
        for (Component ch : c.getComponents()) if (ch instanceof Container k) { JOptionPane r = findPane(k); if (r != null) return r; }
        return null;
    }

    static void smoke(JFrame f, int seconds) {
        GameUi ui = (GameUi) f.getContentPane();
        String war = System.getProperty("smoke.war");
        if (war != null && ui.gs.world.nationByTag.containsKey(war)) ui.gs.declareWar(ui.gs.player, ui.gs.world.nationByTag.get(war));
        ui.setSpeed(5);
        // unattended: answer any modal dialog with its first option, and resume play after automatic pauses
        javax.swing.Timer auto = new javax.swing.Timer(250, e -> {
            for (Window win : Window.getWindows()) {
                if (!(win instanceof JDialog d) || !d.isVisible()) continue;
                JOptionPane pane = findPane(d.getContentPane());
                if (pane != null) { Object[] o = pane.getOptions(); pane.setValue(o != null && o.length > 0 ? o[0] : JOptionPane.OK_OPTION); } else d.dispose();
            }
            if (ui.paused) ui.setPaused(false);
        });
        auto.start();
        javax.swing.Timer t = new javax.swing.Timer(seconds * 1000, e -> {
            try {
                BufferedImage img = new BufferedImage(f.getWidth(), f.getHeight(), BufferedImage.TYPE_INT_RGB);
                f.paint(img.getGraphics());
                ImageIO.write(img, "png", new File("smoke.png"));
                Runtime rt = Runtime.getRuntime();
                System.out.printf("heap used %d MB, nations alive %d, armies %d, wars on player %d%n", (rt.totalMemory() - rt.freeMemory()) >> 20, java.util.stream.IntStream.range(0, ui.gs.N).filter(n -> ui.gs.ns[n].alive).count(), ui.gs.armies.size(), java.util.stream.IntStream.range(0, ui.gs.N).filter(n -> ui.gs.war[ui.gs.player][n]).count());
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
        // packaged builds: look next to the jars (installDist: lib/ -> ../assets, jpackage: lib/app/ -> ../assets)
        try {
            Path jar = Path.of(WarfareApp.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path dir = Files.isDirectory(jar) ? jar : jar.getParent();
            for (Path base : new Path[]{dir, dir.getParent(), dir.getParent() == null ? dir : dir.getParent().getParent()})
                if (base != null && Files.exists(base.resolve("assets/map/provinces.png"))) return base.resolve("assets/map");
        } catch (Exception ignored) {}
        String home = System.getProperty("warfare.home");
        if (home != null && Files.exists(Path.of(home, "assets/map/provinces.png"))) return Path.of(home, "assets/map");
        for (String c : new String[]{"assets/map", "../assets/map", "../../assets/map", "warfare/assets/map"})
            if (Files.exists(Path.of(c, "provinces.png"))) return Path.of(c);
        throw new IllegalStateException("Cannot find assets/map. Pass --assets <dir>.");
    }

    static NationPicker.Choice chooseNation(JFrame f, WorldData w) { return NationPicker.pick(f, w); }

    static void start(JFrame f, WorldData world, NationPicker.Choice choice) {
        GameState gs = new GameState(world, choice.nation(), System.nanoTime());
        gs.ironman = choice.ironman();
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
            ui.map.centerOn(fp >= 0 ? fp : world.nations.get(me).capital, args.length > 10 ? Double.parseDouble(args[10]) : 3.2);
            if (args.length > 5) ui.map.setMode(MapPanel.Mode.valueOf(args[5]));
            ui.map.selProv = fp;
            if (args.length > 6) ui.tabs.setSelectedIndex(Integer.parseInt(args[6]));
            if (args.length > 9 && Integer.parseInt(args[9]) >= 0) { ui.map.tutorial.restart(0); ui.map.tutorial.step = Integer.parseInt(args[9]); }
            else ui.map.tutorial.active = false;
            if (args.length > 8 && args[8].equals("toast")) ui.map.toast("Out of Nowhere", "Win a battle with Ambush while unseen.", "t_ambush");
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

    /** Writes the score (low and high tension mixes) and the effects as WAV files for inspection. */
    static void exportAudio(String dir) throws Exception {
        new File(dir).mkdirs();
        Synth.Stems st = Synth.render(16, 1962);
        double[][] mixes = {{0.62, 0.1, 0, 0}, {0.45, 0.7, 0.95, 0.8}};
        String[] names = {"score_calm", "score_war"};
        for (int m = 0; m < 2; m++) {
            short[] pcm = new short[st.length() * 2];
            for (int i = 0; i < st.length(); i++) {
                double l = (st.padL()[i] * mixes[m][0] + st.bass()[i] * mixes[m][1] + st.perc()[i] * mixes[m][2] + st.ostinato()[i] * mixes[m][3]) * 0.4;
                double r = (st.padR()[i] * mixes[m][0] + st.bass()[i] * mixes[m][1] + st.perc()[i] * mixes[m][2] + st.ostinato()[i] * mixes[m][3]) * 0.4;
                pcm[2 * i] = (short) (Math.tanh(l) * 32000); pcm[2 * i + 1] = (short) (Math.tanh(r) * 32000);
            }
            writeWav(new File(dir, names[m] + ".wav"), pcm, 2);
        }
        for (Synth.Sfx s : Synth.Sfx.values()) {
            float[] b = Synth.sfx(s);
            short[] pcm = new short[b.length];
            for (int i = 0; i < b.length; i++) pcm[i] = (short) (b[i] * 30000);
            writeWav(new File(dir, "sfx_" + s.name().toLowerCase() + ".wav"), pcm, 1);
        }
        System.out.println("wrote audio to " + dir);
    }

    static void writeWav(File f, short[] pcm, int channels) throws Exception {
        try (java.io.DataOutputStream o = new java.io.DataOutputStream(new java.io.BufferedOutputStream(new java.io.FileOutputStream(f)))) {
            int dataLen = pcm.length * 2;
            o.writeBytes("RIFF"); o.writeInt(Integer.reverseBytes(36 + dataLen)); o.writeBytes("WAVEfmt ");
            o.writeInt(Integer.reverseBytes(16)); o.writeShort(Short.reverseBytes((short) 1)); o.writeShort(Short.reverseBytes((short) channels));
            o.writeInt(Integer.reverseBytes(Synth.SR)); o.writeInt(Integer.reverseBytes(Synth.SR * channels * 2));
            o.writeShort(Short.reverseBytes((short) (channels * 2))); o.writeShort(Short.reverseBytes((short) 16));
            o.writeBytes("data"); o.writeInt(Integer.reverseBytes(dataLen));
            for (short v : pcm) o.writeShort(Short.reverseBytes(v));
        }
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
