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
        if (args.length >= 2 && args[0].equals("--shot")) { System.setProperty("java.awt.headless", "true"); shot(args); return; }
        WorldData world = WorldData.load(assets);
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) {}
            JFrame f = new JFrame("Warfare");
            f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            f.setSize(1500, 900);
            f.setLocationRelativeTo(null);
            start(f, world, chooseNation(f, world));
            f.setVisible(true);
        });
    }

    static Path findAssets(String[] args) {
        for (int i = 0; i < args.length - 1; i++) if (args[i].equals("--assets")) return Path.of(args[i + 1]);
        for (String c : new String[]{"assets/map", "../assets/map", "../../assets/map", "warfare/assets/map"})
            if (Files.exists(Path.of(c, "provinces.png"))) return Path.of(c);
        throw new IllegalStateException("Cannot find assets/map. Pass --assets <dir>.");
    }

    static int chooseNation(JFrame f, WorldData w) {
        List<WorldData.NationInfo> list = new ArrayList<>(w.nations);
        list.sort(Comparator.comparing(n -> n.name));
        JComboBox<String> box = new JComboBox<>();
        for (WorldData.NationInfo n : list) box.addItem(n.name);
        box.setSelectedItem("France");
        JPanel p = new JPanel(new BorderLayout(6, 6));
        p.add(new JLabel("<html><b>WARFARE</b> - modern day, 1 November 2026<br>Choose your nation. Every country is playable.</html>"), BorderLayout.NORTH);
        p.add(box, BorderLayout.CENTER);
        JOptionPane.showMessageDialog(f, p, "New game", JOptionPane.PLAIN_MESSAGE);
        return list.get(box.getSelectedIndex()).index;
    }

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

    static void layoutAll(Component c) {
        c.doLayout();
        if (c instanceof Container k) for (Component ch : k.getComponents()) layoutAll(ch);
    }
}
