package warfare.desktop;

import org.junit.jupiter.api.*;
import warfare.core.*;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import warfare.core.Budget;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Drives the real window (needs a display, e.g. xvfb-run): tabs, selection, synthetic mouse clicks, the modal tactic
 * and event dialogs, settings and achievements dialogs, save/load. Skipped automatically when headless.
 */
class UiSelfTest {
    static WorldData world;
    static final List<Throwable> errors = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void setup() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display (run under xvfb-run)");
        WarfareApp.assets = Path.of("../assets/map").toAbsolutePath().normalize();
        Fonts.load(WarfareApp.assets);
        Assets.init(WarfareApp.assets);
        WarfareApp.theme();
        world = WorldData.load(WarfareApp.assets);
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> errors.add(e));
        Settings.tutorial = false;
    }

    /** Closes any modal dialog that pops up: answers JOptionPanes with their first/default option. */
    static Thread dialogCloser(AtomicBoolean stop, List<String> seen) {
        Thread t = new Thread(() -> {
            while (!stop.get()) {
                try { Thread.sleep(80); } catch (InterruptedException e) { return; }
                for (Window w : Window.getWindows()) {
                    if (!(w instanceof JDialog d) || !d.isVisible()) continue;
                    SwingUtilities.invokeLater(() -> {
                        JOptionPane pane = find(d.getContentPane());
                        seen.add(d.getTitle());
                        if (pane != null) {
                            Object[] opts = pane.getOptions();
                            pane.setValue(opts != null && opts.length > 0 ? opts[0] : JOptionPane.OK_OPTION);
                        } else d.dispose();
                    });
                }
            }
        }, "dialog-closer");
        t.setDaemon(true);
        t.start();
        return t;
    }

    static JOptionPane find(Container c) {
        if (c instanceof JOptionPane p) return p;
        for (Component ch : c.getComponents()) if (ch instanceof Container k) { JOptionPane r = find(k); if (r != null) return r; }
        return null;
    }

    static void click(MapPanel map, Point p, int button) {
        long now = System.currentTimeMillis();
        int mod = button == MouseEvent.BUTTON1 ? MouseEvent.BUTTON1_DOWN_MASK : MouseEvent.BUTTON3_DOWN_MASK;
        map.dispatchEvent(new MouseEvent(map, MouseEvent.MOUSE_PRESSED, now, mod, p.x, p.y, 1, false, button));
        map.dispatchEvent(new MouseEvent(map, MouseEvent.MOUSE_RELEASED, now + 5, 0, p.x, p.y, 1, false, button));
    }

    @Test
    void playsThroughTheRealInterface() throws Exception {
        GameState gs = new GameState(world, world.nationByTag.get("RUS"), 11);
        JFrame[] frame = new JFrame[1];
        GameUi[] ui = new GameUi[1];
        SwingUtilities.invokeAndWait(() -> {
            frame[0] = new JFrame("selftest");
            frame[0].setSize(1500, 900);
            ui[0] = new GameUi(gs, frame[0]);
            WarfareApp.install(frame[0], ui[0]);
            frame[0].setVisible(true);
        });
        Thread.sleep(600);
        AtomicBoolean stop = new AtomicBoolean();
        List<String> dialogs = new CopyOnWriteArrayList<>();
        dialogCloser(stop, dialogs);
        stop.set(false);
        MapPanel map = ui[0].map;

        // every tab renders with a province selected, then an army, then nothing
        SwingUtilities.invokeAndWait(() -> {
            map.centerOn(world.nations.get(gs.player).capital, 3.2);
            map.selProv = world.nations.get(gs.player).capital;
            for (int i = 0; i < ui[0].tabs.getTabCount(); i++) { ui[0].tabs.setSelectedIndex(i); ui[0].refresh(); }
            ui[0].tabs.setSelectedIndex(0);
        });
        Army mine = gs.armies.values().stream().filter(a -> a.owner == gs.player).findFirst().orElseThrow();

        // real mouse: click the army's flag, then click a neighbouring province to march
        SwingUtilities.invokeAndWait(() -> { map.centerOn(mine.province, 4); map.paintImmediately(map.getBounds()); });
        Point at = SwingUtilities.convertPoint(map, 0, 0, map);
        SwingUtilities.invokeAndWait(() -> {
            Point2D flag = Point2D.of(map.screenOf(mine.province));
            click(map, new Point((int) flag.getX(), (int) flag.getY() + 4), MouseEvent.BUTTON1);
        });
        assertEquals(mine.id, map.selArmy, "clicking the flag selects the army");
        int dest = -1;
        double bestDist = Double.MAX_VALUE;
        for (int nb : gs.prov(mine.province).neighbours) {
            if (gs.isSea(nb) || gs.owner[nb] != gs.player || !gs.armiesIn(nb).isEmpty()) continue;   // empty ground, so no army flag intercepts the click
            java.awt.geom.Point2D.Double sp = map.screenOf(nb);
            boolean onScreen = sp.x > 40 && sp.y > 40 && sp.x < map.getWidth() - 40 && sp.y < map.getHeight() - 40;
            double d = gs.dist(mine.province, nb);
            if (onScreen && d < bestDist) { bestDist = d; dest = nb; }
        }
        assertTrue(dest >= 0, "an own neighbouring province is visible on screen");
        final int target = dest;
        SwingUtilities.invokeAndWait(() -> click(map, insidePoint(map, target, new Point((int) map.screenOf(mine.province).x, (int) map.screenOf(mine.province).y)), MouseEvent.BUTTON3));
        assertFalse(mine.path.isEmpty(), "right-click issues the march order");
        // left-clicking ground inspects the province and drops the army selection
        SwingUtilities.invokeAndWait(() -> click(map, insidePoint(map, target, null), MouseEvent.BUTTON1));
        assertEquals(-1, map.selArmy);
        assertEquals(target, map.selProv);
        // right-click without a selected army does nothing
        mine.path.clear();
        SwingUtilities.invokeAndWait(() -> click(map, insidePoint(map, target, null), MouseEvent.BUTTON3));
        assertTrue(mine.path.isEmpty());
        // left-click on a capital star opens that country's screen
        int usa = world.nationByTag.get("UKR");
        int capProv = world.nations.get(usa).capital;
        SwingUtilities.invokeAndWait(() -> map.centerOn(capProv, 3.2));
        SwingUtilities.invokeAndWait(() -> map.paintImmediately(map.getBounds()));
        SwingUtilities.invokeAndWait(() -> {
            java.awt.geom.Point2D.Double sp = map.screenOf(capProv);
            click(map, new Point((int) sp.x, (int) sp.y - 30), MouseEvent.BUTTON1);
        });
        Thread.sleep(300);
        assertNotNull(ui[0].country, "capital star opened the country screen");
        assertEquals(usa, ui[0].country.nation);

        // war with a manual army that has no tactic: the game must pause and ask
        int foe = world.nationByTag.get("UKR");
        gs.declareWar(gs.player, foe);
        gs.armies.clear();
        Army a = new Army(gs.nextArmyId++, gs.player, world.nations.get(foe).capital, "Test Army");
        a.units[0] = 8; a.units[2] = 3; a.morale = 80; a.mode = Army.Mode.MANUAL; a.tactic = null;
        gs.armies.put(a.id, a);
        Army e = new Army(gs.nextArmyId++, foe, a.province, "Defenders");
        e.units[0] = 6; e.morale = 80;
        gs.armies.put(e.id, e);
        gs.controller[a.province] = foe; gs.owner[a.province] = foe;
        boolean asked = false;
        for (int i = 0; i < 30 && !asked; i++) {
            SwingUtilities.invokeAndWait(ui[0]::tick);
            Thread.sleep(150);
            asked = dialogs.stream().anyMatch(t -> t.equals("Choose tactic"));
        }
        assertTrue(asked, "tactic prompt appeared, dialogs: " + dialogs);
        assertTrue(gs.awaitingTactic.isEmpty() || gs.armies.get(a.id) == null || gs.armies.get(a.id).tactic != null || gs.armies.get(a.id).mode == Army.Mode.GENERAL, "prompt answered");

        // events dialog
        gs.pendingEvents.add(new GameEvent("border", gs.player, "Border incident", "Test event.", List.of("Protest", "Mobilise", "Ignore")));
        gs.pauseRequested = true;
        SwingUtilities.invokeAndWait(ui[0]::handlePauses);
        Thread.sleep(500);
        assertTrue(gs.pendingEvents.isEmpty(), "event answered through the dialog");

        // settings, achievements, new-game picker and the menu bar
        SwingUtilities.invokeLater(() -> SettingsDialog.show(frame[0], ui[0]));
        Thread.sleep(700);
        SwingUtilities.invokeLater(() -> AchievementsDialog.show(frame[0]));
        Thread.sleep(700);
        assertTrue(dialogs.contains("Settings") && dialogs.contains("Achievements"), dialogs.toString());
        SwingUtilities.invokeAndWait(() -> assertNotNull(ui[0].menuBar()));

        // save and load a game through the same path as the menu
        Path f = Files.createTempFile("selftest", ".sav");
        gs.save(f);
        GameState back = GameState.load(f, world);
        SwingUtilities.invokeAndWait(() -> { GameUi u2 = new GameUi(back, frame[0]); WarfareApp.install(frame[0], u2); u2.tick(); u2.refresh(); });

        // a stretch of real-time play, all tabs, no exceptions
        for (int i = 0; i < 60; i++) SwingUtilities.invokeAndWait(() -> { GameUi u = (GameUi) frame[0].getContentPane(); u.tick(); });
        stop.set(true);
        SwingUtilities.invokeAndWait(() -> frame[0].dispose());
        assertTrue(errors.isEmpty(), "uncaught UI exceptions: " + errors);
    }

    /** A screen point inside the province, on screen and at least 70 px from the given point (e.g. an army flag). */
    static Point insidePoint(MapPanel map, int province, Point avoid) {
        WorldData.ProvinceInfo p = world.provinces.get(province);
        double bestD = Double.MAX_VALUE; Point best = null;
        for (int y = Math.max(0, (int) p.cy - 200); y < Math.min(world.height, (int) p.cy + 200); y++)
            for (int x = Math.max(0, (int) p.cx - 200); x < Math.min(world.width, (int) p.cx + 200); x++) {
                if (world.idMap[y * world.width + x] != province) continue;
                java.awt.geom.Point2D.Double sp = map.screenOfMapPoint(x + 0.5, y + 0.5);
                if (sp.x < 30 || sp.y < 30 || sp.x > map.getWidth() - 30 || sp.y > map.getHeight() - 30) continue;
                if (avoid != null && Math.hypot(sp.x - avoid.x, sp.y - avoid.y) < 70) continue;
                double d = (x - p.cx) * (x - p.cx) + (y - p.cy) * (y - p.cy);
                if (d < bestD) { bestD = d; best = new Point((int) Math.round(sp.x), (int) Math.round(sp.y)); }
            }
        assertNotNull(best, "found a clickable pixel in province " + province);
        return best;
    }

    static <T extends Component> List<T> all(Container c, Class<T> type) {
        List<T> out = new java.util.ArrayList<>();
        for (Component ch : c.getComponents()) {
            if (type.isInstance(ch)) out.add(type.cast(ch));
            if (ch instanceof Container k) out.addAll(all(k, type));
        }
        return out;
    }

    @Test
    void economyPanelControlsDriveTheSimulation() throws Exception {
        GameState gs = new GameState(world, world.nationByTag.get("FRA"), 5);
        AtomicBoolean stopCloser = new AtomicBoolean();
        dialogCloser(stopCloser, new CopyOnWriteArrayList<>());
        JFrame[] frame = new JFrame[1];
        GameUi[] ui = new GameUi[1];
        SwingUtilities.invokeAndWait(() -> {
            frame[0] = new JFrame("econ");
            frame[0].setSize(1500, 900);
            ui[0] = new GameUi(gs, frame[0]);
            WarfareApp.install(frame[0], ui[0]);
            frame[0].setVisible(true);
            assertFalse(ui[0].economy.isVisible());
            ui[0].toggleEconomy();
            assertTrue(ui[0].economy.isVisible(), "E toggles the dock open");
        });
        Budget b = gs.ns[gs.player].budget();
        SwingUtilities.invokeAndWait(() -> {
            List<JSlider> sliders = all(ui[0].economy, JSlider.class);
            assertTrue(sliders.size() >= 7, "tax + 6 budget sliders + focus: " + sliders.size());
            sliders.get(0).setValue(52);                       // tax rate
            sliders.get(1).setValue(25);                       // development
        });
        assertEquals(0.52, b.tax, 1e-9);
        assertEquals(0.25, b.dev, 1e-9);
        SwingUtilities.invokeAndWait(() -> {
            JComboBox<?> mob = all(ui[0].economy, JComboBox.class).get(0);
            mob.setSelectedIndex(3);
            for (JCheckBox c : all(ui[0].economy, JCheckBox.class)) if (c.getText().startsWith("Print")) c.doClick();
        });
        assertEquals(3, b.mobilization);
        assertTrue(b.printMoney);
        SwingUtilities.invokeAndWait(() -> {
            for (JButton bt : all(ui[0].economy, JButton.class)) if (bt.getText().equals("War economy")) bt.doClick();
        });
        assertEquals(2, b.mobilization, "war economy preset");
        assertTrue(b.military >= 0.15 && b.tax >= 0.4);
        SwingUtilities.invokeAndWait(() -> {
            for (JButton bt : all(ui[0].economy, JButton.class)) if (bt.getText().equals("Balanced")) bt.doClick();
        });
        assertEquals(0.30, b.tax, 1e-9);
        gs.ns[gs.player].money = 2000;
        SwingUtilities.invokeAndWait(() -> {
            for (JButton bt : all(ui[0].economy, JButton.class)) if (bt.getText().equals("Build")) bt.doClick();
        });
        Thread.sleep(300);
        assertFalse(gs.ns[gs.player].constructions.isEmpty(), "Build economy queued construction");
        for (int i = 0; i < 40; i++) SwingUtilities.invokeAndWait(() -> ui[0].tick());
        SwingUtilities.invokeAndWait(() -> ui[0].toggleEconomy());
        stopCloser.set(true);
        SwingUtilities.invokeAndWait(() -> frame[0].dispose());
        assertTrue(errors.isEmpty(), "uncaught UI exceptions: " + errors);
    }

    @Test
    void nationPickerBuildsForEveryNation() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            NationPicker p = new NationPicker(world);
            p.setSize(900, 560);
            p.doLayout();
        });
        assertTrue(errors.isEmpty(), errors.toString());
    }

    record Point2D(double x, double y) {
        static Point2D of(java.awt.geom.Point2D p) { return new Point2D(p.getX(), p.getY()); }
        double getX() { return x; }
        double getY() { return y; }
    }
}
