package warfare.desktop;

import warfare.core.*;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.geom.RoundRectangle2D;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

/**
 * The in-game screen: a full-window map with a floating HUD (medallion, resource plates, time controls), a left icon
 * rail that opens floating windows, a selection card on the right and a modal layer for menus and decisions.
 * The simulation runs on real elapsed time so speed does not depend on frame rate.
 */
final class GameUi extends JPanel {
    static GameUi current;

    final GameState gs;
    final JFrame frame;
    final MapPanel map;
    final SelectionPanel selection;
    final ProductionPanel production;
    final DiplomacyPanel diplomacy;
    final ReportsPanel log;
    final EconomyPanel economy;
    final Hud hud;
    final Rail rail;
    final Modals modals = new Modals();
    final JLayeredPane layers = new JLayeredPane();
    final Wf.Window selectionCard;
    final Map<String, Wf.Window> windows = new LinkedHashMap<>();
    final NewsPlate news = new NewsPlate();
    final Timer timer;
    String openWindow;
    CountryMenu country;
    boolean paused = true;
    int speed = 2;

    GameUi(GameState gs, JFrame frame) {
        super(new BorderLayout());
        this.gs = gs; this.frame = frame;
        current = this;
        map = new MapPanel(gs, WarfareApp.assets);
        Runnable refresh = this::refresh;
        selection = new SelectionPanel(gs, map, refresh);
        production = new ProductionPanel(gs, map, refresh);
        diplomacy = new DiplomacyPanel(gs, map, refresh);
        log = new ReportsPanel(gs);
        economy = new EconomyPanel(gs, this::refreshTop, refresh);
        map.tutorial = new Tutorial(Settings.tutorial && gs.day < 3, gs.day);
        map.onCapital = this::openCountry;
        diplomacy.onOpen = this::openCountry;
        map.onSelect = this::refresh;
        map.onOrder = this::refresh;

        hud = new Hud(gs, () -> setPaused(!paused), this::setSpeed, () -> GameMenu.open(this), () -> openCountry(gs.player), () -> showNextPrompt(true));
        rail = new Rail(this::pick);
        selectionCard = new Wf.Window("Selection", "tab_select", this::clearSelection);
        selectionCard.body.add(selection, BorderLayout.CENTER);
        windows.put("economy", window("Economy", "money", economy));
        windows.put("diplomacy", window("Diplomacy", "tab_diplo", diplomacy));
        windows.put("production", window("Build and recruit", "tab_prod", production));
        windows.put("reports", window("Reports", "tab_reports", log));

        layers.setLayout(new HudLayout());
        layers.add(map, JLayeredPane.DEFAULT_LAYER);
        for (JComponent c : new JComponent[]{hud.badge, hud.plates, hud.time, rail, selectionCard, news}) layers.add(c, JLayeredPane.PALETTE_LAYER);
        layers.add(modals, JLayeredPane.MODAL_LAYER);
        selectionCard.setVisible(false);
        add(layers, BorderLayout.CENTER);

        timer = new Timer(16, e -> frame());
        timer.setRepeats(true);
        bindKeys();
        refresh();
    }

    private Wf.Window window(String title, String icon, JComponent content) {
        Wf.Window w = new Wf.Window(title, icon, () -> pick(openWindow));
        w.body.add(content, BorderLayout.CENTER);
        return w;
    }

    // ------------------------------------------------------------------ static helpers used by panels
    static void notify(GameState.Result r) {
        if (current == null || r == null) return;
        Audio.get().play(r.ok ? Synth.Sfx.CONFIRM : Synth.Sfx.ERROR);
        current.map.toast(r.ok ? "Done" : "Cannot do that", r.message, r.ok ? "tab_prod" : "battle", r.ok ? "ORDER" : "NOTICE");
    }

    static void confirm(String title, String text, String yes, boolean danger, Runnable onYes) { if (current != null) current.confirmCard(title, text, yes, danger, onYes); }

    static void closeModals() { if (current != null) current.modals.clear(); }

    // ------------------------------------------------------------------ layout
    private final class HudLayout implements LayoutManager {
        @Override public void addLayoutComponent(String n, Component c) {}
        @Override public void removeLayoutComponent(Component c) {}
        @Override public Dimension preferredLayoutSize(Container p) { return new Dimension(1400, 860); }
        @Override public Dimension minimumLayoutSize(Container p) { return new Dimension(900, 600); }

        @Override public void layoutContainer(Container c) {
            int W = c.getWidth(), H = c.getHeight();
            map.setBounds(0, 0, W, H);
            hud.badge.setBounds(10, 6, 340, 74);
            int timeW = 46 + 246 + 28;
            hud.time.setBounds(W - timeW - 8, 14, timeW, 50);
            int plateX = 360, plateW = Math.max(200, W - timeW - 8 - plateX - 8);
            hud.plates.setBounds(plateX, 18, plateW, 50);
            rail.setBounds(10, 92, 70, rail.getPreferredSize().height);
            boolean sel = selectionCard.isVisible();
            int selW = 410;
            map.rightInset = sel ? selW + 16 : 0;
            if (sel) selectionCard.setBounds(W - selW - 6, 72, selW, Math.max(260, H - 72 - 70));
            Wf.Window w = openWindow == null ? null : windows.get(openWindow);
            if (w != null) {
                int ww = 420;
                int avail = W - 92 - (sel ? selW + 16 : 0) - 20;
                w.setBounds(92, 88, Math.min(ww, Math.max(320, avail)), Math.max(300, H - 88 - 74));
            }
            int nw = Math.min(600, Math.max(260, W - 900));
            news.setBounds((W - nw) / 2, H - 92, nw, 30);
            modals.setBounds(0, 0, W, H);
        }
    }

    /** A rounded plate showing the latest headline. */
    static final class NewsPlate extends JComponent {
        private String text = "";

        void set(String t) { if (!t.equals(text)) { text = t; setVisible(!t.isBlank()); repaint(); } }

        @Override protected void paintComponent(Graphics g0) {
            if (text.isBlank()) return;
            Graphics2D g = (Graphics2D) g0.create();
            Wf.quality(g);
            int w = getWidth(), h = getHeight();
            g.setColor(new Color(0, 0, 0, 90));
            g.fill(new RoundRectangle2D.Float(2, 4, w - 3, h - 3, 8, 8));
            Tex.plate(g, new RoundRectangle2D.Float(0, 0, w - 1, h - 1, 8, 8), Tex.Kind.DARK_LEATHER, 1f);
            Tex.frame(g, 0.5f, 0.5f, w - 2, h - 2, 8, false, false);
            g.drawImage(Assets.icon("tab_reports", 16, Ui.MUTED), 10, 7, null);
            g.setFont(Fonts.ui(13f));
            g.setColor(Ui.TEXT);
            FontMetrics fm = g.getFontMetrics();
            String t = text;
            while (fm.stringWidth(t) > w - 40 && t.length() > 8) t = t.substring(0, t.length() - 2);
            g.drawString(t.equals(text) ? t : t + "...", 34, h / 2 + fm.getAscent() / 2 - 2);
            g.dispose();
        }
    }

    // ------------------------------------------------------------------ windows and selection
    boolean isOpen(String key) { return key.equals(openWindow); }

    /** Rail click or hotkey: open a window (closing any other), close it if it is already open, or open the country screen. */
    void pick(String key) {
        if (key == null) return;
        if (key.equals("country")) { openCountry(gs.player); return; }
        Wf.Window old = openWindow == null ? null : windows.get(openWindow);
        if (old != null) layers.remove(old);
        if (key.equals(openWindow)) openWindow = null;
        else {
            openWindow = key;
            Wf.Window w = windows.get(key);
            layers.add(w, JLayeredPane.PALETTE_LAYER);
            if (key.equals("economy")) economy.update();
        }
        rail.setActive(openWindow);
        layers.revalidate(); layers.repaint();
        updateVisibleTab();
    }

    /** Back-compat for the old name used by the tutorial and tests. */
    void toggleEconomy() { pick("economy"); }

    void clearSelection() { map.selArmy = -1; map.selProv = -1; map.raidArmy = -1; refresh(); }

    private void syncSelectionCard() {
        boolean want = map.selArmy >= 0 || map.selProv >= 0;
        if (want != selectionCard.isVisible()) { selectionCard.setVisible(want); layers.revalidate(); layers.repaint(); }
    }

    void focusStart() {
        SwingUtilities.invokeLater(() -> { map.centerOn(gs.world.nations.get(gs.player).capital, MapPanel.START_ZOOM); refresh(); });
    }

    // ------------------------------------------------------------------ input
    void bindKeys() {
        InputMap im = getInputMap(WHEN_IN_FOCUSED_WINDOW);
        ActionMap am = getActionMap();
        key(im, am, KeyEvent.VK_SPACE, "pause", () -> setPaused(!paused));
        for (int s = 1; s <= 5; s++) { final int sp = s; key(im, am, KeyEvent.VK_0 + s, "speed" + s, () -> setSpeed(sp)); }
        int[][] keys = {{KeyEvent.VK_LEFT, KeyEvent.VK_A}, {KeyEvent.VK_RIGHT, KeyEvent.VK_D}, {KeyEvent.VK_UP, KeyEvent.VK_W}, {KeyEvent.VK_DOWN, KeyEvent.VK_S}};
        double[][] dirs = {{90, 0}, {-90, 0}, {0, 90}, {0, -90}};
        for (int i = 0; i < 4; i++) { final double[] d = dirs[i]; for (int k : keys[i]) key(im, am, k, "pan" + i + "_" + k, () -> map.pan(d[0], d[1])); }
        for (int k : new int[]{KeyEvent.VK_EQUALS, KeyEvent.VK_PLUS, KeyEvent.VK_ADD}) key(im, am, k, "zin" + k, () -> map.zoomBy(1.25));
        for (int k : new int[]{KeyEvent.VK_MINUS, KeyEvent.VK_SUBTRACT}) key(im, am, k, "zout" + k, () -> map.zoomBy(0.8));
        key(im, am, KeyEvent.VK_F3, "perf", () -> { map.showPerf = !map.showPerf; map.repaint(); });
        key(im, am, KeyEvent.VK_E, "economy", () -> pick("economy"));
        key(im, am, KeyEvent.VK_T, "diplomacy", () -> pick("diplomacy"));
        key(im, am, KeyEvent.VK_P, "production", () -> pick("production"));
        key(im, am, KeyEvent.VK_R, "reports", () -> pick("reports"));
        key(im, am, KeyEvent.VK_C, "country", () -> openCountry(gs.player));
        key(im, am, KeyEvent.VK_M, "mode", () -> { MapPanel.Mode[] m = MapPanel.Mode.values(); map.setMode(m[(map.mode.ordinal() + 1) % m.length]); });
        key(im, am, KeyEvent.VK_ESCAPE, "esc", this::escape);
    }

    private void key(InputMap im, ActionMap am, int code, String name, Runnable r) {
        im.put(KeyStroke.getKeyStroke(code, 0), name);
        am.put(name, new AbstractAction() { public void actionPerformed(java.awt.event.ActionEvent e) { if (!modals.isOpen() || name.equals("esc")) r.run(); } });
    }

    private void escape() {
        if (modals.isOpen()) { modals.pop(); return; }
        if (selectionCard.isVisible()) { clearSelection(); return; }
        if (openWindow != null) { pick(openWindow); return; }
        GameMenu.open(this);
    }

    // ------------------------------------------------------------------ time
    /** Game days per real second at each speed setting. */
    static final double[] DAYS_PER_SEC = {1, 2.5, 5, 10, 22};
    private double acc;
    private long lastNanos;
    long frames, frameNsTotal;
    double daysPerSecNow;
    private long perfWindowStart = System.nanoTime();
    private int perfDays;
    static long simNs, colorNs, uiNs, ticks;
    private int seenNews;
    private long lastPanel;

    void setPaused(boolean p) {
        paused = p;
        if (p) timer.stop(); else { lastNanos = System.nanoTime(); acc = 0; timer.start(); }
        refresh();
    }

    void setSpeed(int s) { speed = s; if (paused) setPaused(false); else refresh(); }

    /** Timer callback: advance the simulation by elapsed real time, then repaint once. */
    void frame() {
        long now = System.nanoTime();
        double dt = Math.min(0.12, (now - lastNanos) / 1e9);
        lastNanos = now;
        if (modals.isOpen()) return;
        acc += dt * DAYS_PER_SEC[speed - 1];
        int steps = 0;
        while (acc >= 1 && steps < 6 && !gs.pauseRequested) { stepOnce(); acc -= 1; steps++; }
        if (acc > 2) acc = 2;
        if (steps > 0) finishFrame(steps);
        frames++; frameNsTotal += System.nanoTime() - now;
    }

    private void stepOnce() {
        long t0 = System.nanoTime();
        gs.advanceDay();
        simNs += System.nanoTime() - t0;
        ticks++; perfDays++;
        audioCues();
        metaChecks();
    }

    private void finishFrame(int steps) {
        long t1 = System.nanoTime();
        map.refreshColors();
        colorNs += System.nanoTime() - t1;
        long t2 = System.nanoTime();
        refreshLight();
        uiNs += System.nanoTime() - t2;
        long w = System.nanoTime();
        if (w - perfWindowStart > 1_000_000_000L) { daysPerSecNow = perfDays * 1e9 / (w - perfWindowStart); perfDays = 0; perfWindowStart = w; }
        map.perf = String.format("%.0f days/s   sim %.1f ms/day   frame %.1f ms   map layers %.1f ms", daysPerSecNow, simNs / 1e6 / Math.max(1, ticks), frameNsTotal / 1e6 / Math.max(1, frames), map.renderNs / 1e6 / Math.max(1, map.renders));
        handlePauses();
    }

    /** Cheap refresh used while time runs: HUD every frame, panels a few times per second. */
    private void refreshLight() {
        hud.update(paused, speed);
        long now = System.currentTimeMillis();
        if (now - lastPanel > 400) { lastPanel = now; updateVisibleTab(); int n = gs.news.size(); news.set(n == 0 ? "" : gs.news.get(n - 1)); }
    }

    /** One synchronous game day, used by tests and the headless harness. */
    void tick() { stepOnce(); finishFrame(1); refresh(); }

    // ------------------------------------------------------------------ cues, meta
    private boolean gameOverShown;

    void metaChecks() {
        if (gs.day > 0 && gs.day % 30 == 0 && (Settings.autosave || gs.ironman)) {
            try { gs.save(savePath(gs.ironman ? "ironman" : "autosave")); } catch (Exception ignored) {}
        }
        if (gs.day % 5 == 0) {
            for (warfare.core.Achievements.Def d : Profile.check(gs)) {
                map.toast(d.title(), d.description(), d.icon(), "ACHIEVEMENT UNLOCKED");
                Audio.get().play(Synth.Sfx.CAPTURE);
            }
        }
        if (!gs.ns[gs.player].alive && !gameOverShown) {
            gameOverShown = true;
            setPaused(true);
            SwingUtilities.invokeLater(this::gameOver);
        }
    }

    void gameOver() {
        String stats = String.format("%s fell on %s.%n%nProvinces captured: %d   Battles won: %d   Capitals taken: %d",
                gs.nationName(gs.player), gs.date(), gs.stCaptures, gs.stBattlesWon, gs.stCapitals);
        choose("Your nation has been eliminated", "nuke", stats, List.of("New game", "Keep watching", "Quit"), i -> { if (i == 0) newGame(); else if (i == 2) System.exit(0); });
    }

    void newGame() { timer.stop(); WarfareApp.showStart(frame, gs.world); }

    void audioCues() {
        int me = gs.player;
        int wars = 0;
        for (int n = 0; n < gs.N; n++) if (gs.war[me][n] && gs.ns[n].alive) wars++;
        boolean fighting = false;
        for (Army a : gs.armies.values()) if (a.owner == me && a.battleId >= 0) { fighting = true; break; }
        double t = 0.12 + 0.16 * Math.min(3, wars) + (fighting ? 0.34 : 0) + (gs.nukesInFlight.isEmpty() ? 0 : 0.4) + (gs.ns[me].stability < 30 ? 0.1 : 0);
        Audio.get().setTension(t);
        if (seenNews > gs.news.size()) seenNews = 0;
        for (int i = seenNews; i < gs.news.size(); i++) {
            String s = gs.news.get(i);
            String me3 = gs.nationName(me);
            if (s.contains("NUCLEAR STRIKE") || s.contains("launches a nuclear")) Audio.get().play(Synth.Sfx.NUKE);
            else if (s.contains("WAR:") && s.contains(me3)) {
                Audio.get().play(Synth.Sfx.WAR_HORN);
                if (s.contains("on " + me3 + "!")) map.toast("War declared on you", s.substring(s.indexOf("WAR:") + 5), "battle", "ALERT");
            }
            else if (s.contains("captures") && s.contains(me3)) Audio.get().play(Synth.Sfx.CAPTURE);
            else if (s.contains("Battle begins")) Audio.get().play(Synth.Sfx.BATTLE);
            else if (s.contains("ALERT")) Audio.get().play(Synth.Sfx.ALERT);
        }
        seenNews = gs.news.size();
    }

    // ------------------------------------------------------------------ decisions as in-game cards
    void choose(String title, String icon, String text, List<String> options, IntConsumer pick) { choose(title, icon, text, options, pick, null); }

    /** A decision card. With {@code onDismiss} it has a close button: the choice is postponed and the card will not pop up again by itself. */
    void choose(String title, String icon, String text, List<String> options, IntConsumer pick, Runnable onDismiss) {
        JComponent buttons = Modals.buttonColumn(options, i -> { modals.pop(); pick.accept(i); }, 0);
        modals.push(Modals.card(title, icon, Modals.textBody(text, buttons), 500, 0, onDismiss == null ? null : () -> { modals.pop(); onDismiss.run(); }));
    }

    void confirmCard(String title, String text, String yes, boolean danger, Runnable onYes) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        Wf.Button ok = danger ? Wf.danger(yes, null, () -> { modals.pop(); onYes.run(); }) : Wf.primary(yes, null, () -> { modals.pop(); onYes.run(); });
        row.add(ok);
        row.add(Wf.button("Cancel", null, () -> modals.pop()));
        modals.push(Modals.card(title, danger ? "nuke" : "general", Modals.textBody(text, row), 480, 0, () -> modals.pop()));
    }

    private final java.util.Set<Object> announced = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private final java.util.Set<Object> dismissed = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    /**
     * Called after every simulated day. New decisions are announced with a toast and the bell in the top bar;
     * time only stops for them when the player has asked for that. Tactic cards in manual battles still ask by default.
     * A card on screen halts time by itself, and closing it resumes at the same speed.
     */
    void handlePauses() {
        gs.pauseRequested = false;                          // the simulation never stays stalled on a flag
        announced.retainAll(gs.pendingEvents);
        dismissed.retainAll(gs.pendingEvents);
        for (GameEvent ev : gs.pendingEvents) if (announced.add(ev)) {
            map.toast(ev.title, "A decision is waiting - click the bell in the top bar.", "tab_reports", "DECISION");
            Audio.get().play(Synth.Sfx.CHIME);
        }
        boolean tactics = Settings.pauseOnTactics && !gs.awaitingTactic.isEmpty();
        boolean events = Settings.pauseOnEvents && !gs.pendingEvents.isEmpty();
        if (tactics || events) SwingUtilities.invokeLater(() -> showNextPrompt(false));
    }

    /** Shows the next pending tactic choice or decision, one at a time. {@code manual}: the player asked, so dismissed ones show again. */
    void showNextPrompt(boolean manual) {
        if (modals.isOpen()) return;
        if (Settings.pauseOnTactics || manual) {
            for (Integer id : new ArrayList<>(gs.awaitingTactic)) {
                Army a = gs.armies.get(id);
                if (a == null || a.battleId < 0) { gs.awaitingTactic.remove(id); continue; }
                if (!manual && dismissed.contains(a)) continue;
                askTactic(a, manual);
                return;
            }
        }
        if (Settings.pauseOnEvents || manual) {
            for (GameEvent ev : gs.pendingEvents) {
                if (!manual && dismissed.contains(ev)) continue;
                choose(ev.title, "general", ev.text, ev.options, i -> { gs.answerEvent(ev, i); map.refreshColors(); refresh(); showNextPrompt(manual); }, () -> dismissed.add(ev));
                return;
            }
        }
    }

    void showNextPrompt() { showNextPrompt(false); }

    void askTactic(Army a, boolean manual) {
        Combat.Battle b = gs.battles.get(a.battleId);
        Army enemy = b == null ? null : gs.armies.get(b.attacker == a.id ? b.defender : b.attacker);
        StringBuilder sb = new StringBuilder("Battle at " + gs.prov(a.province).name + " (" + gs.prov(a.province).terrain + ").\n");
        sb.append(a.name).append(": ").append((int) a.totalUnits()).append(" battalions, morale ").append((int) a.morale).append(". ");
        if (enemy != null) {
            Intel.Sighting s = gs.intelOf(gs.player).sightingOf(enemy.id);
            if (s == null) sb.append("The enemy force is unseen.");
            else if (s.composition != null) sb.append(gs.nationName(s.owner)).append(" fields ").append((int) Math.round(sum(s.composition))).append(" battalions, composition known.");
            else sb.append(s.ownerKnown ? gs.nationName(s.owner) : "An unidentified force").append(" - roughly ").append((int) Math.max(1, Math.round(s.strengthEstimate / 11.0))).append(" battalions.");
        }
        sb.append("\n\nChoose a tactic card, or let the General decide.");
        List<String> opts = new ArrayList<>();
        for (Tactic t : Tactic.values()) opts.add(t.label);
        opts.add("General decides (auto)");
        choose("Choose tactic", "battle", sb.toString(), opts, i -> {
            if (i < Tactic.values().length) gs.setTactic(a, Tactic.values()[i]); else gs.setGeneralMode(a);
            refresh(); showNextPrompt(manual);
        }, () -> dismissed.add(a));
    }

    static double sum(double[] a) { double s = 0; for (double v : a) s += v; return s; }

    // ------------------------------------------------------------------ country screen
    void openCountry(int nation) {
        modals.clear();
        country = new CountryMenu(gs, nation, this::refresh, () -> pick("economy"));
        modals.push(country.card(() -> modals.pop()));
    }

    // ------------------------------------------------------------------ refresh
    void refreshTop() { hud.update(paused, speed); }

    void updateVisibleTab() {
        if (openWindow != null) {
            switch (openWindow) {
                case "economy" -> economy.update();
                case "production" -> production.update();
                case "diplomacy" -> diplomacy.update();
                case "reports" -> log.update();
                default -> {}
            }
        }
        if (map.tutorial != null) map.tutorial.update(this);
        syncSelectionCard();
        if (selectionCard.isVisible()) selection.update();
        if (modals.isOpen() && country != null && modals.top() != null) country.refreshBody();
    }

    void refresh() {
        hud.update(paused, speed);
        int n = gs.news.size();
        news.set(n == 0 ? "" : gs.news.get(n - 1));
        updateVisibleTab();
        map.repaint();
    }

    static Path savePath(int slot) { return savePath("slot" + slot); }
    static Path savePath(String name) { return Path.of(System.getProperty("user.home"), ".warfare", name + ".sav"); }
}
