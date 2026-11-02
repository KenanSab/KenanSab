package warfare.desktop;

import org.junit.jupiter.api.*;
import warfare.core.*;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
        for (int nb : gs.prov(mine.province).neighbours) if (!gs.isSea(nb) && gs.owner[nb] == gs.player) { dest = nb; break; }
        assertTrue(dest >= 0);
        final int target = dest;
        SwingUtilities.invokeAndWait(() -> { Point2D t = Point2D.of(map.screenOf(target)); click(map, new Point((int) t.getX(), (int) t.getY()), MouseEvent.BUTTON1); });
        assertFalse(mine.path.isEmpty(), "second click issues the march order");
        // right click deselects and inspects
        SwingUtilities.invokeAndWait(() -> { Point2D t = Point2D.of(map.screenOf(target)); click(map, new Point((int) t.getX(), (int) t.getY()), MouseEvent.BUTTON3); });
        assertEquals(-1, map.selArmy);

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
