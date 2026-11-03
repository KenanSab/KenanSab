package warfare.desktop;

import warfare.core.*;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Warfare - desktop client. No arguments: play. Tools: --shot, --start, --smoke, --basemap, --export-audio.
 * Screenshot options are key=value pairs: window=economy|production|diplomacy|reports, modal=country:TAG:tab|menu|settings|achievements|controls,
 * army, hover, zoom=3.0, tutorial=N, scroll.
 */
public final class WarfareApp {
    static Path assets;

    public static void main(String[] args) throws Exception {
        assets = findAssets(args);
        Fonts.load(assets);
        Assets.init(assets);
        theme();
        Settings.load();
        Profile.load();
        Steam.init();
        if (args.length >= 2 && args[0].equals("--export-audio")) { exportAudio(args[1]); return; }
        if (args.length >= 2 && args[0].equals("--basemap")) {
            System.setProperty("java.awt.headless", "true");
            WorldData world = WorldData.load(assets);
            System.setProperty("warfare.rebake", "true");
            Basemap bm = basemap(world);
            bm.bake(assets);
            ImageIO.write(bm.image, "png", new File(args[1]));
            System.out.println("baked basemap, relief and sea mask into " + assets + " and wrote " + args[1]);
            return;
        }
        if (args.length >= 2 && args[0].equals("--start")) { System.setProperty("java.awt.headless", "true"); startShot(args); return; }
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
            if (forced == null && java.util.Arrays.asList(args).contains("--smoke")) forced = "FRA";   // unattended runs skip the menu
            warfare.core.Scenario scenario = warfare.core.Scenario.QUIET;
            for (int i = 0; i < args.length - 1; i++) if (args[i].equals("--scenario")) scenario = warfare.core.Scenario.valueOf(args[i + 1].toUpperCase());
            if (forced != null) start(f, world, new StartScreen.Choice(world.nationByTag.get(forced), false, scenario)); else showStart(f, world);
            f.setVisible(true);
            for (int i = 0; i < args.length; i++) if (args[i].equals("--smoke")) smoke(f, i + 1 < args.length && args[i + 1].matches("\\d+") ? Integer.parseInt(args[i + 1]) : 8);
        });
    }

    // ------------------------------------------------------------------ unattended run
    static <T extends Component> List<T> find(Container c, Class<T> type) {
        List<T> out = new ArrayList<>();
        for (Component ch : c.getComponents()) {
            if (type.isInstance(ch)) out.add(type.cast(ch));
            if (ch instanceof Container k) out.addAll(find(k, type));
        }
        return out;
    }

    /** Plays the real window for a few seconds at top speed, answering every prompt, then screenshots it and exits. */
    static void smoke(JFrame f, int seconds) {
        GameUi ui = (GameUi) f.getContentPane();
        String war = System.getProperty("smoke.war");
        if (war != null && ui.gs.world.nationByTag.containsKey(war)) ui.gs.declareWar(ui.gs.player, ui.gs.world.nationByTag.get(war));
        ui.setSpeed(5);
        javax.swing.Timer auto = new javax.swing.Timer(250, e -> {
            if (ui.modals.isOpen()) { List<Wf.Button> b = find(ui.modals.top(), Wf.Button.class); if (!b.isEmpty()) b.get(0).doClick(); }
            else if (ui.paused) ui.setPaused(false);
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
        UIManager.put("Component.arc", 6);
        UIManager.put("Button.arc", 6);
        UIManager.put("Panel.background", new java.awt.Color(23, 24, 27));
        UIManager.put("Component.focusColor", new java.awt.Color(217, 178, 94));
        UIManager.put("ScrollBar.thumbArc", 999);
        UIManager.put("ScrollBar.width", 11);
        UIManager.put("ScrollBar.thumb", new java.awt.Color(120, 119, 114));
        UIManager.put("ToolTip.background", new java.awt.Color(28, 29, 32));
        UIManager.put("ToolTip.foreground", new java.awt.Color(238, 236, 230));
        UIManager.put("ToolTip.border", javax.swing.BorderFactory.createLineBorder(new java.awt.Color(217, 178, 94, 150)));
        UIManager.put("ToolTip.font", Fonts.ui(12.5f));
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

    private static MapShapes sharedShapes;
    private static Basemap sharedBasemap;

    static synchronized MapShapes shapes(WorldData world) {
        if (sharedShapes == null) {
            try { sharedShapes = MapShapes.load(assets.resolve("arcs.bin.gz"), world.provinces.size()); }
            catch (Exception e) { throw new IllegalStateException("cannot load map shapes", e); }
        }
        return sharedShapes;
    }

    static synchronized Basemap basemap(WorldData world) {
        if (sharedBasemap == null) sharedBasemap = new Basemap(shapes(world), world, assets);
        return sharedBasemap;
    }

    private static MapExtras sharedExtras;
    private static MapLayers sharedLayers;

    static synchronized MapExtras extras(WorldData world) {
        if (sharedExtras == null) sharedExtras = MapExtras.load(assets, world);
        return sharedExtras;
    }

    static synchronized MapLayers layers(WorldData world) {
        if (sharedLayers == null) sharedLayers = new MapLayers(world, shapes(world), basemap(world), extras(world));
        return sharedLayers;
    }

    /** Shows the start screen in the frame; choosing a nation begins a new game. */
    static void showStart(JFrame f, WorldData world) {
        f.setJMenuBar(null);
        f.setContentPane(new StartScreen(world, choice -> start(f, world, choice)));
        f.revalidate(); f.repaint();
    }

    static void start(JFrame f, WorldData world, StartScreen.Choice choice) {
        GameState gs = new GameState(world, choice.nation(), System.nanoTime(), choice.scenario());
        gs.ironman = choice.ironman();
        install(f, new GameUi(gs, f));
    }

    static void install(JFrame f, GameUi ui) {
        f.setJMenuBar(null);
        f.setContentPane(ui);
        f.revalidate();
        ui.focusStart();
    }


    // ------------------------------------------------------------------ headless screenshots
    static Map<String, String> options(String[] args, int from) {
        Map<String, String> o = new HashMap<>();
        for (int i = from; i < args.length; i++) { String[] kv = args[i].split("=", 2); o.put(kv[0], kv.length > 1 ? kv[1] : "1"); }
        return o;
    }

    static void paintTo(Component c, int w, int h, File out) throws Exception {
        BufferedImage[] img = new BufferedImage[1];
        SwingUtilities.invokeAndWait(() -> {
            c.setSize(w, h);
            layoutAll(c);
            layoutAll(c);
            img[0] = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img[0].createGraphics();
            c.paint(g);
            g.dispose();
        });
        ImageIO.write(img[0], "png", out);
        System.out.println("wrote " + out);
    }

    static void startShot(String[] args) throws Exception {
        Wf.Window.instant = true;
        WorldData world = WorldData.load(assets);
        JPanel[] p = new JPanel[1];
        SwingUtilities.invokeAndWait(() -> p[0] = new StartScreen(world, c -> {}));
        paintTo(p[0], 1500, 900, new File(args[1]));
    }

    /** --shot out.png TAG DAYS FOE MODE [options] : a war between TAG and FOE, DAYS in, rendered headless. */
    static void shot(String[] args) throws Exception {
        Wf.Window.instant = true;
        String out = args[1], tag = args.length > 2 ? args[2] : "RUS";
        int days = args.length > 3 ? Integer.parseInt(args[3]) : 30;
        WorldData world = WorldData.load(assets);
        GameState gs = new GameState(world, world.nationByTag.get(tag), 7);
        int me = gs.player;
        String foeTag = args.length > 4 ? args[4] : "UKR";
        if (!foeTag.equals("none")) gs.declareWar(me, world.nationByTag.get(foeTag));
        MapPanel.Mode mode = MapPanel.Mode.valueOf(args.length > 5 ? args[5] : "POLITICAL");
        Map<String, String> o = options(args, 6);
        int focus = -1;
        for (int i = 0; i < days; i++) {
            if (i % 3 == 0) for (Army a : gs.armies.values()) if (a.owner == me && a.path.isEmpty() && a.battleId < 0) Ai.manageArmies(gs, me);
            gs.advanceDay();
            gs.pauseRequested = false; gs.pendingEvents.clear(); gs.awaitingTactic.clear();
        }
        for (Army a : gs.armies.values()) if (a.owner == me) { focus = a.province; break; }
        final int fp = focus;
        SwingUtilities.invokeAndWait(() -> {
            GameUi ui = new GameUi(gs, null);
            Settings.tutorial = false;
            ui.setSize(1500, 900);
            layoutAll(ui);
            ui.map.centerOn(fp >= 0 ? fp : world.nations.get(me).capital, Double.parseDouble(o.getOrDefault("zoom", String.valueOf(MapPanel.START_ZOOM))));
            ui.map.setMode(mode);
            if (o.containsKey("tutorial")) { ui.map.tutorial.restart(0); ui.map.tutorial.step = Integer.parseInt(o.get("tutorial")); }
            else ui.map.tutorial.active = false;
            if (o.containsKey("army")) for (Army a : gs.armies.values()) if (a.owner == me) { ui.map.selArmy = a.id; ui.map.selProv = a.province; break; }
            if (o.containsKey("province")) ui.map.selProv = fp >= 0 ? fp : world.nations.get(me).capital;
            if (o.containsKey("window")) ui.pick(o.get("window"));
            if (o.containsKey("toast")) ui.map.toast("Out of Nowhere", "Win a battle with Ambush while unseen.", "t_ambush", "ACHIEVEMENT UNLOCKED");
            ui.refresh();
            layoutAll(ui);
            if (o.containsKey("scroll") && ui.openWindow != null)
                for (JScrollPane sp : find(ui.windows.get(ui.openWindow), JScrollPane.class)) { layoutAll(sp); sp.getVerticalScrollBar().setValue(sp.getVerticalScrollBar().getMaximum()); }
            String modal = o.get("modal");
            if (modal != null) {
                if (modal.startsWith("peace")) {
                    gs.warStart[me][world.nationByTag.get(foeTag)] = gs.warStart[world.nationByTag.get(foeTag)][me] = gs.day - 90;
                    PeaceCards.open(ui, world.nationByTag.get(foeTag));
                    if (modal.equals("peace:refuse")) { layoutAll(ui); ui.modals.top(); PeaceCards.propose(ui, world.nationByTag.get(foeTag), warfare.core.Peace.Term.WHITE); }
                } else if (modal.startsWith("country:")) {
                    String[] parts = modal.split(":");
                    CountryMenu cm = new CountryMenu(gs, world.nationByTag.get(parts[1]), () -> {}, () -> {});
                    ui.modals.push(cm.card(() -> {}));
                    layoutAll(ui);
                    if (parts.length > 2) { cm.setTab(Integer.parseInt(parts[2])); layoutAll(ui); }
                } else {
                    switch (modal) {
                        case "menu" -> GameMenu.open(ui);
                        case "tactic" -> ui.pushTacticHand("Battle at Lorraine (FOREST).\n1st Army: 9 battalions, morale 82. Germany fields 11 battalions - composition known.\n\nPlay a tactic card, or let the General decide.", warfare.core.Tactic.FLANKING, i -> {}, () -> {});
                        case "ops" -> OpsCards.open(ui, world.nationByTag.get(foeTag));
                        case "settings" -> ui.modals.push(GameMenu.settings(ui));
                        case "achievements" -> ui.modals.push(GameMenu.achievements(ui));
                        case "controls" -> ui.modals.push(GameMenu.controls(ui));
                        case "slots" -> ui.modals.push(GameMenu.slots(ui, true));
                        default -> {}
                    }
                }
            }
            if (o.containsKey("brush")) {
                int foe = world.nationByTag.get(foeTag);
                gs.warStart[me][foe] = gs.warStart[foe][me] = gs.day - 90;
                ui.map.startBrush(foe, sel -> {}, () -> {});
                final int centre = fp >= 0 ? fp : world.nations.get(me).capital;
                List<Integer> mine = new ArrayList<>();
                for (int p = 0; p < world.provinces.size(); p++) if (!world.provinces.get(p).sea && gs.owner[p] == foe) mine.add(p);
                mine.sort(java.util.Comparator.comparingDouble(p -> gs.dist(centre, p)));
                for (int i = 0; i < Math.min(Integer.parseInt(o.get("brush")), mine.size()); i++) ui.map.debugBrushPaint(mine.get(i));
            }
            if (o.containsKey("nukefx")) {
                int target = world.nations.get(world.nationByTag.getOrDefault(foeTag, me)).capital;
                if (o.get("nukefx").equals("flight")) { gs.nukesInFlight.add(new int[]{me, target, gs.day + 1}); ui.map.debugMissileAge(850); }
                else ui.map.debugBlast(target, 650);
            }
            if (o.containsKey("hover")) {
                java.awt.geom.Point2D.Double sp = ui.map.screenOf(fp >= 0 ? fp : world.nations.get(me).capital);
                ui.map.dispatchEvent(new java.awt.event.MouseEvent(ui.map, java.awt.event.MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0, (int) sp.x + 34, (int) sp.y + 40, 0, false));
            }
            layoutAll(ui);
        });
        BufferedImage[] img = new BufferedImage[1];
        SwingUtilities.invokeAndWait(() -> {
            GameUi ui = GameUi.current;
            layoutAll(ui);
            ui.map.flushLayers();
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

    static void layoutAll(Component c) {
        c.doLayout();
        if (c instanceof Container k) for (Component ch : k.getComponents()) layoutAll(ch);
    }
}
