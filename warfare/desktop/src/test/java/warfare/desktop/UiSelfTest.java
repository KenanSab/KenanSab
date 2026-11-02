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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Drives the real window (needs a display, e.g. xvfb-run): synthetic mouse clicks on the map, the floating windows,
 * custom controls, modal cards for tactics and events, menus, save/load. Skipped automatically when headless.
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

    static GameUi open(GameState gs, JFrame[] frame) throws Exception {
        GameUi[] ui = new GameUi[1];
        SwingUtilities.invokeAndWait(() -> {
            frame[0] = new JFrame("selftest");
            frame[0].setSize(1500, 900);
            ui[0] = new GameUi(gs, frame[0]);
            WarfareApp.install(frame[0], ui[0]);
            frame[0].setVisible(true);
        });
        Thread.sleep(600);
        return ui[0];
    }

    static void click(MapPanel map, Point p, int button) {
        long now = System.currentTimeMillis();
        int mod = button == MouseEvent.BUTTON1 ? MouseEvent.BUTTON1_DOWN_MASK : MouseEvent.BUTTON3_DOWN_MASK;
        map.dispatchEvent(new MouseEvent(map, MouseEvent.MOUSE_PRESSED, now, mod, p.x, p.y, 1, false, button));
        map.dispatchEvent(new MouseEvent(map, MouseEvent.MOUSE_RELEASED, now + 5, 0, p.x, p.y, 1, false, button));
    }

    /** A screen point inside the province, on screen and at least 70 px from the given point (e.g. an army flag). */
    static Point insidePoint(MapPanel map, int province, Point avoid) {
        WorldData.ProvinceInfo p = world.provinces.get(province);
        double bestD = Double.MAX_VALUE; Point best = null;
        for (int y = Math.max(0, (int) p.cy - 200); y < Math.min(world.height, (int) p.cy + 200); y++)
            for (int x = Math.max(0, (int) p.cx - 200); x < Math.min(world.width, (int) p.cx + 200); x++) {
                if (world.idMap[y * world.width + x] != province) continue;
                java.awt.geom.Point2D.Double sp = map.screenOfMapPoint(x + 0.5, y + 0.5);
                if (sp.x < 100 || sp.y < 100 || sp.x > map.getWidth() - 450 || sp.y > map.getHeight() - 120) continue;   // clear of the HUD, windows and cards
                if (avoid != null && Math.hypot(sp.x - avoid.x, sp.y - avoid.y) < 70) continue;
                double d = (x - p.cx) * (x - p.cx) + (y - p.cy) * (y - p.cy);
                if (d < bestD) { bestD = d; best = new Point((int) Math.round(sp.x), (int) Math.round(sp.y)); }
            }
        assertNotNull(best, "found a clickable pixel in province " + province);
        return best;
    }

    static <T extends Component> List<T> all(Container c, Class<T> type) { return WarfareApp.find(c, type); }

    static void clickSegment(Wf.Segmented s, int index) {
        int x = (int) ((index + 0.5) * s.getWidth() / 4.0);
        s.dispatchEvent(new MouseEvent(s, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, x, 10, 1, false));
    }

    /** Answers the open modal card by pressing its n-th button. */
    static void answer(GameUi ui, int n) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            List<Wf.Button> b = all(ui.modals.top(), Wf.Button.class);
            assertTrue(b.size() > n, "modal has button " + n);
            b.get(n).doClick();
        });
    }

    @Test
    void playsThroughTheRealInterface() throws Exception {
        GameState gs = new GameState(world, world.nationByTag.get("RUS"), 11);
        JFrame[] frame = new JFrame[1];
        GameUi ui = open(gs, frame);
        MapPanel map = ui.map;

        // the rail opens, switches and closes every window
        for (String key : new String[]{"economy", "diplomacy", "production", "reports"}) {
            SwingUtilities.invokeAndWait(() -> ui.pick(key));
            assertTrue(ui.isOpen(key), key + " opens");
            SwingUtilities.invokeAndWait(ui::refresh);
        }
        SwingUtilities.invokeAndWait(() -> ui.pick("reports"));
        assertNull(ui.openWindow, "same key closes the window");

        Army mine = gs.armies.values().stream().filter(a -> a.owner == gs.player).findFirst().orElseThrow();
        SwingUtilities.invokeAndWait(() -> { map.centerOn(mine.province, 4); map.paintImmediately(map.getBounds()); });
        // left-click selects the army and shows the selection card
        SwingUtilities.invokeAndWait(() -> { java.awt.geom.Point2D.Double f = map.screenOf(mine.province); click(map, new Point((int) f.x, (int) f.y + 4), MouseEvent.BUTTON1); });
        assertEquals(mine.id, map.selArmy, "left-clicking the flag selects the army");
        Thread.sleep(200);
        assertTrue(ui.selectionCard.isVisible(), "selection card appears");

        int dest = -1;
        double bestDist = Double.MAX_VALUE;
        for (int nb : gs.prov(mine.province).neighbours) {
            if (gs.isSea(nb) || gs.owner[nb] != gs.player || !gs.armiesIn(nb).isEmpty()) continue;
            java.awt.geom.Point2D.Double sp = map.screenOf(nb);
            boolean onScreen = sp.x > 140 && sp.y > 140 && sp.x < map.getWidth() - 460 && sp.y < map.getHeight() - 140;
            double d = gs.dist(mine.province, nb);
            if (onScreen && d < bestDist) { bestDist = d; dest = nb; }
        }
        assertTrue(dest >= 0, "an own neighbouring province is visible on screen");
        final int target = dest;
        Point flagPt = new Point((int) map.screenOf(mine.province).x, (int) map.screenOf(mine.province).y);
        SwingUtilities.invokeAndWait(() -> click(map, insidePoint(map, target, flagPt), MouseEvent.BUTTON3));
        assertFalse(mine.path.isEmpty(), "right-click issues the march order");
        SwingUtilities.invokeAndWait(() -> click(map, insidePoint(map, target, null), MouseEvent.BUTTON1));
        assertEquals(-1, map.selArmy, "left-click on ground drops the army selection");
        assertEquals(target, map.selProv);
        mine.path.clear();
        SwingUtilities.invokeAndWait(() -> click(map, insidePoint(map, target, null), MouseEvent.BUTTON3));
        assertTrue(mine.path.isEmpty(), "right-click with no army selected does nothing");
        SwingUtilities.invokeAndWait(ui::clearSelection);
        assertFalse(ui.selectionCard.isVisible());

        // left-click on a capital star opens that country's screen as an in-game card
        int ukr = world.nationByTag.get("UKR");
        int capProv = world.nations.get(ukr).capital;
        SwingUtilities.invokeAndWait(() -> map.centerOn(capProv, 3.2));
        SwingUtilities.invokeAndWait(() -> map.paintImmediately(map.getBounds()));
        SwingUtilities.invokeAndWait(() -> { java.awt.geom.Point2D.Double sp = map.screenOf(capProv); click(map, new Point((int) sp.x, (int) sp.y - 30), MouseEvent.BUTTON1); });
        Thread.sleep(300);
        assertTrue(ui.modals.isOpen() && ui.country != null && ui.country.nation == ukr, "capital star opened the country card");
        SwingUtilities.invokeAndWait(() -> { for (int t = 0; t < 4; t++) ui.country.setTab(t); });
        SwingUtilities.invokeAndWait(() -> ui.modals.pop());
        assertFalse(ui.modals.isOpen());

        // a manual army with no tactic: the game pauses and asks through a modal card
        int foe = ukr;
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
        for (int i = 0; i < 40 && !asked; i++) {
            SwingUtilities.invokeAndWait(ui::tick);
            Thread.sleep(120);
            asked = ui.modals.isOpen();
        }
        assertTrue(asked, "tactic prompt appeared as a modal card");
        answer(ui, 1);                                            // pick the second tactic card
        Thread.sleep(200);
        assertFalse(ui.modals.isOpen(), "answering closes the card");
        assertEquals(Army.Mode.MANUAL, a.mode);
        assertEquals(Tactic.values()[1], a.tactic, "the chosen card was applied");

        // events are answered through cards too
        gs.pendingEvents.add(new GameEvent("border", gs.player, "Border incident", "Test event.", List.of("Protest", "Mobilise", "Ignore")));
        gs.pauseRequested = true;
        SwingUtilities.invokeAndWait(ui::handlePauses);
        Thread.sleep(300);
        assertTrue(ui.modals.isOpen(), "event card shown");
        answer(ui, 0);
        Thread.sleep(200);
        assertTrue(gs.pendingEvents.isEmpty(), "event answered");
        assertFalse(ui.modals.isOpen());

        // menu, settings, achievements, controls, save slots all open and close as cards
        SwingUtilities.invokeAndWait(() -> GameMenu.open(ui));
        assertTrue(ui.modals.isOpen());
        SwingUtilities.invokeAndWait(() -> { ui.modals.push(GameMenu.settings(ui)); ui.modals.push(GameMenu.achievements(ui)); ui.modals.push(GameMenu.controls(ui)); ui.modals.push(GameMenu.slots(ui, false)); });
        SwingUtilities.invokeAndWait(() -> ui.modals.clear());
        assertFalse(ui.modals.isOpen());

        // save and load through the same path as the menu
        Path f = Files.createTempFile("selftest", ".sav");
        gs.save(f);
        GameState back = GameState.load(f, world);
        SwingUtilities.invokeAndWait(() -> { GameUi u2 = new GameUi(back, frame[0]); WarfareApp.install(frame[0], u2); u2.tick(); u2.refresh(); });

        // a stretch of real-time play with a window open
        GameUi u = (GameUi) frame[0].getContentPane();
        SwingUtilities.invokeAndWait(() -> u.pick("diplomacy"));
        for (int i = 0; i < 60; i++) SwingUtilities.invokeAndWait(() -> { GameUi cur = (GameUi) frame[0].getContentPane(); if (cur.modals.isOpen()) cur.modals.clear(); cur.tick(); });
        SwingUtilities.invokeAndWait(() -> frame[0].dispose());
        assertTrue(errors.isEmpty(), "uncaught UI exceptions: " + errors);
    }

    @Test
    void economyWindowControlsDriveTheSimulation() throws Exception {
        GameState gs = new GameState(world, world.nationByTag.get("FRA"), 5);
        JFrame[] frame = new JFrame[1];
        GameUi ui = open(gs, frame);
        SwingUtilities.invokeAndWait(() -> { assertNull(ui.openWindow); ui.pick("economy"); });
        assertTrue(ui.isOpen("economy"), "E opens the economy window");
        Thread.sleep(300);
        Budget b = gs.ns[gs.player].budget();
        SwingUtilities.invokeAndWait(() -> {
            List<JSlider> sliders = all(ui.economy, JSlider.class);
            assertTrue(sliders.size() >= 7, "tax + 6 budget sliders + focus: " + sliders.size());
            sliders.get(0).setValue(52);
            sliders.get(1).setValue(25);
        });
        assertEquals(0.52, b.tax, 1e-9);
        assertEquals(0.25, b.dev, 1e-9);
        SwingUtilities.invokeAndWait(() -> {
            Wf.Segmented mob = all(ui.economy, Wf.Segmented.class).get(0);
            clickSegment(mob, 3);
            for (Wf.Toggle t : all(ui.economy, Wf.Toggle.class)) if (t.text().startsWith("Print")) t.flip();
        });
        assertEquals(3, b.mobilization);
        assertTrue(b.printMoney);
        SwingUtilities.invokeAndWait(() -> { for (Wf.Button bt : all(ui.economy, Wf.Button.class)) if (bt.getText().equals("War economy")) bt.doClick(); });
        assertEquals(2, b.mobilization, "war economy preset");
        assertTrue(b.military >= 0.15 && b.tax >= 0.4);
        SwingUtilities.invokeAndWait(() -> { for (Wf.Button bt : all(ui.economy, Wf.Button.class)) if (bt.getText().equals("Balanced")) bt.doClick(); });
        assertEquals(0.30, b.tax, 1e-9);
        gs.ns[gs.player].money = 2000;
        SwingUtilities.invokeAndWait(() -> { for (Wf.Button bt : all(ui.economy, Wf.Button.class)) if (bt.getText().equals("Build")) bt.doClick(); });
        Thread.sleep(300);
        assertFalse(gs.ns[gs.player].constructions.isEmpty(), "Build economy queued construction");
        for (int i = 0; i < 40; i++) SwingUtilities.invokeAndWait(ui::tick);
        SwingUtilities.invokeAndWait(() -> ui.pick("economy"));
        assertNull(ui.openWindow);
        SwingUtilities.invokeAndWait(() -> frame[0].dispose());
        assertTrue(errors.isEmpty(), "uncaught UI exceptions: " + errors);
    }

    @Test
    void diplomacyWindowListsFiltersAndActsOnNations() throws Exception {
        GameState gs = new GameState(world, world.nationByTag.get("FRA"), 5);
        JFrame[] frame = new JFrame[1];
        GameUi ui = open(gs, frame);
        SwingUtilities.invokeAndWait(() -> ui.pick("diplomacy"));
        Thread.sleep(300);
        SwingUtilities.invokeAndWait(() -> {
            ui.diplomacy.selectNation(world.nationByTag.get("PNG"));
            for (Wf.Button bt : all(ui.diplomacy, Wf.Button.class)) if (bt.getText().equals("Alliance")) bt.doClick();
        });
        assertFalse(gs.isAlly(gs.player, world.nationByTag.get("PNG")), "a stranger does not simply accept");
        SwingUtilities.invokeAndWait(() -> { for (Wf.Button bt : all(ui.diplomacy, Wf.Button.class)) if (bt.getText().equals("War")) bt.doClick(); });
        assertTrue(gs.war[gs.player][world.nationByTag.get("PNG")], "war declared through the list");
        SwingUtilities.invokeAndWait(() -> frame[0].dispose());
        assertTrue(errors.isEmpty(), errors.toString());
    }

    @Test
    void startScreenBuildsAndStartsAGame() throws Exception {
        StartScreen.Choice[] chosen = new StartScreen.Choice[1];
        StartScreen[] s = new StartScreen[1];
        SwingUtilities.invokeAndWait(() -> {
            s[0] = new StartScreen(world, c -> chosen[0] = c);
            s[0].setSize(1500, 900);
            s[0].doLayout();
            for (Wf.Button b : all(s[0], Wf.Button.class)) if (b.getText().equals("Start campaign")) b.doClick();
        });
        assertNotNull(chosen[0]);
        assertEquals("FRA", world.nations.get(chosen[0].nation()).tag, "France is preselected");
        assertTrue(errors.isEmpty(), errors.toString());
    }
}
