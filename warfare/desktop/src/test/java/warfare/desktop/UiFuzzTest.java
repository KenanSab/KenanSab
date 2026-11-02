package warfare.desktop;

import org.junit.jupiter.api.*;
import warfare.core.*;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Hammers the real window with random clicks, drags, wheel zooms, window toggles, speed changes, resizes and modal
 * answers while the simulation runs, painting everything after every step. Any exception anywhere fails the test.
 */
class UiFuzzTest {
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
        Wf.Window.instant = true;
    }

    private static void mouse(MapPanel map, int id, int x, int y, int button) {
        int mod = button == MouseEvent.BUTTON1 ? MouseEvent.BUTTON1_DOWN_MASK : button == MouseEvent.BUTTON3 ? MouseEvent.BUTTON3_DOWN_MASK : 0;
        map.dispatchEvent(new MouseEvent(map, id, System.currentTimeMillis(), id == MouseEvent.MOUSE_RELEASED ? 0 : mod, x, y, 1, false, button));
    }

    private static void paintAll(JFrame f) {
        ((JComponent) f.getContentPane()).paintImmediately(0, 0, f.getContentPane().getWidth(), f.getContentPane().getHeight());
    }

    private void fuzz(String tag, long seed, int steps) throws Exception {
        Random r = new Random(seed);
        int me = world.nationByTag.get(tag);
        GameState gs = new GameState(world, me, seed);
        JFrame[] frame = new JFrame[1];
        GameUi[] ui = new GameUi[1];
        SwingUtilities.invokeAndWait(() -> {
            frame[0] = new JFrame("fuzz");
            frame[0].setSize(1400, 860);
            ui[0] = new GameUi(gs, frame[0]);
            WarfareApp.install(frame[0], ui[0]);
            frame[0].setVisible(true);
        });
        Thread.sleep(400);
        MapPanel map = ui[0].map;
        String[] windows = {"economy", "diplomacy", "production", "reports"};
        long worst = 0, total = 0;
        for (int i = 0; i < steps; i++) {
            final int step = i;
            int act = r.nextInt(16);
            long t0 = System.nanoTime();
            SwingUtilities.invokeAndWait(() -> {
                int W = Math.max(50, map.getWidth()), H = Math.max(50, map.getHeight());
                int x = r.nextInt(W), y = r.nextInt(H);
                switch (act) {
                    case 0, 1 -> { mouse(map, MouseEvent.MOUSE_PRESSED, x, y, MouseEvent.BUTTON1); mouse(map, MouseEvent.MOUSE_RELEASED, x, y, MouseEvent.BUTTON1); }
                    case 2 -> { mouse(map, MouseEvent.MOUSE_PRESSED, x, y, MouseEvent.BUTTON3); mouse(map, MouseEvent.MOUSE_RELEASED, x, y, MouseEvent.BUTTON3); }
                    case 3 -> map.dispatchEvent(new MouseWheelEvent(map, MouseEvent.MOUSE_WHEEL, System.currentTimeMillis(), 0, x, y, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, r.nextBoolean() ? -1 : 1));
                    case 4 -> {
                        mouse(map, MouseEvent.MOUSE_PRESSED, x, y, MouseEvent.BUTTON1);
                        for (int k = 1; k <= 4; k++) mouse(map, MouseEvent.MOUSE_DRAGGED, x + k * 30 - 60, y + k * 15 - 30, MouseEvent.BUTTON1);
                        mouse(map, MouseEvent.MOUSE_RELEASED, x + 120, y + 60, MouseEvent.BUTTON1);
                    }
                    case 5 -> ui[0].pick(windows[r.nextInt(windows.length)]);
                    case 6 -> ui[0].setSpeed(1 + r.nextInt(5));
                    case 7 -> ui[0].setPaused(r.nextBoolean());
                    case 8 -> map.setMode(MapPanel.Mode.values()[r.nextInt(MapPanel.Mode.values().length)]);
                    case 9 -> {
                        if (ui[0].modals.isOpen()) {
                            List<Wf.Button> b = WarfareApp.find(ui[0].modals.top(), Wf.Button.class);
                            if (!b.isEmpty()) b.get(r.nextInt(b.size())).doClick();
                        } else GameMenu.open(ui[0]);
                    }
                    case 10 -> { int n = r.nextInt(world.nations.size()); if (gs.ns[n].alive) ui[0].openCountry(n); }
                    case 11 -> ui[0].clearSelection();
                    case 12 -> { for (int k = 0; k < 1 + r.nextInt(4); k++) { if (ui[0].modals.isOpen()) break; ui[0].tick(); } }
                    case 13 -> { int p = r.nextInt(world.provinces.size()); map.centerOn(p, 0.6 + r.nextDouble() * 9); }
                    case 14 -> { if (step % 3 == 0) frame[0].setSize(900 + r.nextInt(900), 600 + r.nextInt(400)); frame[0].validate(); }
                    default -> mouse(map, MouseEvent.MOUSE_MOVED, x, y, 0);
                }
                paintAll(frame[0]);
            });
            long dt = System.nanoTime() - t0;
            total += dt; worst = Math.max(worst, dt);
            if (i % 40 == 0) Thread.sleep(30);   // let the animation timers run
        }
        SwingUtilities.invokeAndWait(() -> frame[0].dispose());
        System.out.printf("fuzz %s: %d steps, avg %.1f ms, worst %.0f ms, day %d%n", tag, steps, total / 1e6 / steps, worst / 1e6, gs.day);
        assertTrue(errors.isEmpty(), "uncaught exceptions: " + errors);
    }

    @Test void fuzzFrance() throws Exception { fuzz("FRA", 1, 350); }
    @Test void fuzzUsa() throws Exception { fuzz("USA", 2, 350); }
    @Test void fuzzTiny() throws Exception { fuzz("MLT", 3, 250); }
}
