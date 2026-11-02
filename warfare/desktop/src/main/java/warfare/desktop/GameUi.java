package warfare.desktop;

import warfare.core.*;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** The in-game window: top bar, map, dockable tabs and news bar, plus the real-time-with-pause loop. */
final class GameUi extends JPanel {
    final GameState gs;
    final JFrame frame;
    final MapPanel map;
    final SelectionPanel selection;
    final ProductionPanel production;
    final SidePanels.Diplomacy diplomacy;
    final ReportsPanel log;
    final JTabbedPane tabs = new JTabbedPane();
    final JLabel newsBar = new JLabel(" ");
    final TopBar topBar;
    final Timer timer;
    boolean paused = true;
    int speed = 2;
    boolean dockRight = true, showSide = true, showNews = true;

    GameUi(GameState gs, JFrame frame) {
        super(new BorderLayout());
        this.gs = gs; this.frame = frame;
        map = new MapPanel(gs, WarfareApp.assets);
        Runnable refresh = this::refresh;
        selection = new SelectionPanel(gs, map, refresh);
        production = new ProductionPanel(gs, map, refresh);
        diplomacy = new SidePanels.Diplomacy(gs, map, refresh);
        log = new ReportsPanel(gs);
        map.tutorial = new Tutorial(Settings.tutorial && gs.day < 3, gs.day);
        map.onSelect = this::refresh;
        map.onOrder = this::refresh;
        tabs.addTab("Selection", Assets.swingIcon("tab_select", 18, Ui.TEXT), selection);
        tabs.addTab("Production", Assets.swingIcon("tab_prod", 18, Ui.TEXT), production);
        tabs.addTab("Diplomacy", Assets.swingIcon("tab_diplo", 18, Ui.TEXT), diplomacy);
        tabs.addTab("Reports", Assets.swingIcon("tab_reports", 18, Ui.TEXT), log);
        tabs.setPreferredSize(new Dimension(410, 100));
        tabs.addChangeListener(e -> updateVisibleTab());
        tabs.setFont(Fonts.label(15f));
        topBar = new TopBar(gs, () -> setPaused(!paused), this::setSpeed);
        add(topBar, BorderLayout.NORTH);
        add(map, BorderLayout.CENTER);
        add(tabs, BorderLayout.EAST);
        JPanel south = new JPanel(new BorderLayout());
        south.setBackground(Ui.BG);
        newsBar.setFont(Fonts.ui(13f));
        newsBar.setForeground(Ui.TEXT);
        newsBar.setIcon(Assets.swingIcon("tab_reports", 16, Ui.MUTED));
        newsBar.setIconTextGap(8);
        south.add(newsBar, BorderLayout.CENTER);
        south.setBorder(BorderFactory.createEmptyBorder(5, 12, 5, 12));
        add(south, BorderLayout.SOUTH);
        timer = new Timer(DAY_MS(), e -> tick());
        timer.setRepeats(true);
        bindKeys();
        refresh();
    }

    int DAY_MS() { return WarfareApp.DAY_MS[speed - 1]; }

    void focusStart() {
        SwingUtilities.invokeLater(() -> { map.centerOn(gs.world.nations.get(gs.player).capital, 3.5); map.selProv = gs.world.nations.get(gs.player).capital; refresh(); });
    }

    void bindKeys() {
        InputMap im = getInputMap(WHEN_IN_FOCUSED_WINDOW);
        ActionMap am = getActionMap();
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "pause");
        am.put("pause", new AbstractAction() { public void actionPerformed(java.awt.event.ActionEvent e) { setPaused(!paused); } });
        for (int s = 1; s <= 5; s++) {
            final int sp = s;
            im.put(KeyStroke.getKeyStroke(KeyEvent.VK_0 + s, 0), "speed" + s);
            am.put("speed" + s, new AbstractAction() { public void actionPerformed(java.awt.event.ActionEvent e) { setSpeed(sp); } });
        }
        String[] names = {"left", "right", "up", "down"};
        int[][] keys = {{KeyEvent.VK_LEFT, KeyEvent.VK_A}, {KeyEvent.VK_RIGHT, KeyEvent.VK_D}, {KeyEvent.VK_UP, KeyEvent.VK_W}, {KeyEvent.VK_DOWN, KeyEvent.VK_S}};
        double[][] dirs = {{90, 0}, {-90, 0}, {0, 90}, {0, -90}};
        for (int i = 0; i < 4; i++) {
            final double[] d = dirs[i];
            for (int k : keys[i]) im.put(KeyStroke.getKeyStroke(k, 0), "pan" + names[i]);
            am.put("pan" + names[i], new AbstractAction() { public void actionPerformed(java.awt.event.ActionEvent e) { map.pan(d[0], d[1]); } });
        }
        for (int k : new int[]{KeyEvent.VK_EQUALS, KeyEvent.VK_PLUS, KeyEvent.VK_ADD}) im.put(KeyStroke.getKeyStroke(k, 0), "zoomin");
        for (int k : new int[]{KeyEvent.VK_MINUS, KeyEvent.VK_SUBTRACT}) im.put(KeyStroke.getKeyStroke(k, 0), "zoomout");
        am.put("zoomin", new AbstractAction() { public void actionPerformed(java.awt.event.ActionEvent e) { map.zoomBy(1.25); } });
        am.put("zoomout", new AbstractAction() { public void actionPerformed(java.awt.event.ActionEvent e) { map.zoomBy(0.8); } });
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_M, 0), "mode");
        am.put("mode", new AbstractAction() { public void actionPerformed(java.awt.event.ActionEvent e) {
            MapPanel.Mode[] m = MapPanel.Mode.values(); map.setMode(m[(map.mode.ordinal() + 1) % m.length]); } });
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "esc");
        am.put("esc", new AbstractAction() { public void actionPerformed(java.awt.event.ActionEvent e) { map.selArmy = -1; refresh(); } });
    }

    void setPaused(boolean p) {
        paused = p;
        if (p) timer.stop(); else timer.start();
        refresh();
    }

    void setSpeed(int s) { speed = s; timer.setDelay(DAY_MS()); if (paused) setPaused(false); else refresh(); }

    static long simNs, colorNs, uiNs, ticks;
    private long lastRefresh;
    private int seenNews;

    void tick() {
        long t0 = System.nanoTime();
        gs.advanceDay();
        long t1 = System.nanoTime();
        map.refreshColors();
        long t2 = System.nanoTime();
        long now = System.currentTimeMillis();
        if (now - lastRefresh >= 70 || paused) { refresh(); lastRefresh = now; }
        else topBar.update(paused, speed);
        long t3 = System.nanoTime();
        audioCues();
        metaChecks();
        simNs += t1 - t0; colorNs += t2 - t1; uiNs += t3 - t2; ticks++;
        handlePauses();
    }

    private boolean gameOverShown;

    /** Autosave, achievements and the game-over screen. */
    void metaChecks() {
        if (gs.day > 0 && gs.day % 30 == 0 && (Settings.autosave || gs.ironman)) {
            try { gs.save(savePath(gs.ironman ? "ironman" : "autosave")); } catch (Exception ignored) {}
        }
        if (gs.day % 5 == 0) {
            for (warfare.core.Achievements.Def d : Profile.check(gs)) {
                map.toast(d.title(), d.description(), d.icon());
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
        int c = JOptionPane.showOptionDialog(this, stats, "Your nation has been eliminated", JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE, null,
                new Object[]{"New game", "Keep watching", "Quit"}, "New game");
        if (c == 0) { timer.stop(); WarfareApp.start(frame, gs.world, WarfareApp.chooseNation(frame, gs.world)); }
        else if (c == 2) System.exit(0);
    }

    /** Maps game news and state to music tension and one-shot effects. */
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
                if (s.contains("on " + me3 + "!")) { map.toast("War declared on you", s.substring(s.indexOf("WAR:") + 5), "battle"); gs.pauseRequested = true; }
            }
            else if (s.contains("captures") && s.contains(me3)) Audio.get().play(Synth.Sfx.CAPTURE);
            else if (s.contains("Battle begins")) Audio.get().play(Synth.Sfx.BATTLE);
            else if (s.contains("ALERT")) Audio.get().play(Synth.Sfx.ALERT);
        }
        seenNews = gs.news.size();
        if (!gs.pendingEvents.isEmpty()) Audio.get().play(Synth.Sfx.CHIME);
    }

    void handlePauses() {
        if (!gs.pauseRequested && gs.pendingEvents.isEmpty() && gs.awaitingTactic.isEmpty()) return;
        gs.pauseRequested = false;
        setPaused(true);
        SwingUtilities.invokeLater(() -> {
            for (Integer id : new ArrayList<>(gs.awaitingTactic)) {
                Army a = gs.armies.get(id);
                if (a == null || a.battleId < 0) { gs.awaitingTactic.remove(id); continue; }
                askTactic(a);
            }
            for (GameEvent ev : new ArrayList<>(gs.pendingEvents)) {
                Object[] opts = ev.options.toArray();
                int c = JOptionPane.showOptionDialog(this, "<html><body style='width:320px'>" + SidePanels.esc(ev.text) + "</body></html>", ev.title,
                        JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, opts, opts[0]);
                gs.answerEvent(ev, c < 0 ? ev.options.size() - 1 : c);
            }
            map.refreshColors();
            refresh();
        });
    }

    void askTactic(Army a) {
        Combat.Battle b = gs.battles.get(a.battleId);
        Army enemy = null;
        if (b != null) enemy = gs.armies.get(b.attacker == a.id ? b.defender : b.attacker);
        StringBuilder sb = new StringBuilder("<html><body style='width:340px'><b>Battle at " + SidePanels.esc(gs.prov(a.province).name) + "</b> (" + gs.prov(a.province).terrain + ")<br>");
        sb.append(SidePanels.esc(a.name)).append(" (").append((int) a.totalUnits()).append(" battalions, morale ").append((int) a.morale).append(") ");
        if (enemy != null) {
            Intel.Sighting s = gs.intelOf(gs.player).sightingOf(enemy.id);
            sb.append("faces ");
            if (s == null) sb.append("an unseen enemy force.");
            else if (s.composition != null) sb.append(SidePanels.esc(gs.nationName(s.owner))).append(" with ").append((int) Math.round(sum(s.composition))).append(" battalions - exact composition known.");
            else sb.append(s.ownerKnown ? SidePanels.esc(gs.nationName(s.owner)) : "an unidentified force").append(", roughly ").append((int) Math.max(1, Math.round(s.strengthEstimate / 11.0))).append(" battalions.");
        }
        sb.append("<br><br>Choose a tactic card, or let the General decide.</body></html>");
        List<String> opts = new ArrayList<>();
        for (Tactic t : Tactic.values()) opts.add(t.label);
        opts.add("General decides (auto)");
        int c = JOptionPane.showOptionDialog(this, sb.toString(), "Choose tactic", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, opts.toArray(), opts.get(opts.size() - 1));
        if (c >= 0 && c < Tactic.values().length) gs.setTactic(a, Tactic.values()[c]);
        else gs.setGeneralMode(a);
    }

    static double sum(double[] a) { double s = 0; for (double v : a) s += v; return s; }

    void refresh() {
        if (map.tutorial != null) map.tutorial.update(this);
        topBar.update(paused, speed);
        int n = gs.news.size();
        newsBar.setText(n == 0 ? " " : gs.news.get(n - 1));
        updateVisibleTab();
        map.repaint();
    }

    void updateVisibleTab() {
        Component c = tabs.getSelectedComponent();
        if (c == selection) selection.update();
        else if (c == production) production.update();
        else if (c == diplomacy) diplomacy.update();
        else if (c == log) log.update();
    }

    JMenuBar menuBar() {
        JMenuBar mb = new JMenuBar();
        JMenu game = new JMenu("Game");
        for (int slot = 1; slot <= 3; slot++) {
            final int sl = slot;
            JMenuItem save = new JMenuItem("Save to slot " + slot);
            save.addActionListener(e -> {
                try { gs.save(savePath(sl)); JOptionPane.showMessageDialog(this, "Saved to slot " + sl + "."); }
                catch (Exception ex) { JOptionPane.showMessageDialog(this, "Save failed: " + ex.getMessage()); }
            });
            save.setEnabled(!gs.ironman);
            game.add(save);
        }
        for (int slot = 1; slot <= 3; slot++) {
            final int sl = slot;
            JMenuItem load = new JMenuItem("Load slot " + slot);
            load.addActionListener(e -> {
                try {
                    GameState g2 = GameState.load(savePath(sl), gs.world);
                    WarfareApp.install(frame, new GameUi(g2, frame));
                } catch (Exception ex) { JOptionPane.showMessageDialog(this, "No save in slot " + sl + "."); }
            });
            load.setEnabled(!gs.ironman);
            game.add(load);
        }
        JMenuItem loadAuto = new JMenuItem(gs.ironman ? "Resume ironman save" : "Load autosave");
        loadAuto.addActionListener(e -> {
            try { WarfareApp.install(frame, new GameUi(GameState.load(savePath(gs.ironman ? "ironman" : "autosave"), gs.world), frame)); }
            catch (Exception ex) { JOptionPane.showMessageDialog(this, "No autosave yet."); }
        });
        game.add(loadAuto);
        JMenuItem ach = new JMenuItem("Achievements...");
        ach.addActionListener(e -> AchievementsDialog.show(frame));
        game.add(ach);
        game.addSeparator();
        JMenuItem settings = new JMenuItem("Settings...");
        settings.addActionListener(e -> SettingsDialog.show(frame, this));
        game.add(settings);
        JMenuItem nw = new JMenuItem("New game...");
        nw.addActionListener(e -> { timer.stop(); WarfareApp.start(frame, gs.world, WarfareApp.chooseNation(frame, gs.world)); });
        game.add(nw);
        JMenuItem quit = new JMenuItem("Quit");
        quit.addActionListener(e -> System.exit(0));
        game.add(quit);
        mb.add(game);

        JMenu view = new JMenu("View");
        ButtonGroup bg = new ButtonGroup();
        for (MapPanel.Mode m : MapPanel.Mode.values()) {
            JRadioButtonMenuItem it = new JRadioButtonMenuItem(m.label + " map (M)", m == map.mode);
            it.addActionListener(e -> map.setMode(m));
            bg.add(it); view.add(it);
        }
        view.addSeparator();
        JCheckBoxMenuItem side = new JCheckBoxMenuItem("Show side panel", true);
        side.addActionListener(e -> { showSide = side.isSelected(); tabs.setVisible(showSide); revalidate(); });
        JCheckBoxMenuItem newsItem = new JCheckBoxMenuItem("Show news bar", true);
        newsItem.addActionListener(e -> { showNews = newsItem.isSelected(); newsBar.setVisible(showNews); revalidate(); });
        JCheckBoxMenuItem left = new JCheckBoxMenuItem("Dock side panel on the left", false);
        left.addActionListener(e -> { remove(tabs); add(tabs, left.isSelected() ? BorderLayout.WEST : BorderLayout.EAST); revalidate(); repaint(); });
        view.add(side); view.add(newsItem); view.add(left);
        mb.add(view);

        JMenu help = new JMenu("Help");
        JMenuItem tut = new JMenuItem("Restart tutorial");
        tut.addActionListener(e -> { map.tutorial.restart(gs.day); map.repaint(); });
        help.add(tut);
        JMenuItem keys = new JMenuItem("Controls");
        keys.addActionListener(e -> JOptionPane.showMessageDialog(this,
                "Space: pause / play      1-5: game speed      M: cycle map mode      Esc: deselect army\n"
                        + "Arrow keys / WASD: pan      + / -: zoom\n"
                        + "Left-click an army marker, then left-click a province to march there.\n"
                        + "Right-click: inspect a province.   Mouse wheel: zoom.   Drag: pan.\n\n"
                        + "Fog of war: you only see what your units, provinces, radar, aircraft, agents,\n"
                        + "satellites and allies reveal. Old sightings fade and expire after 45 days.\n\n"
                        + "Battles: choose a tactic card per army, or leave the army on General (auto)."));
        help.add(keys);
        mb.add(help);
        return mb;
    }

    static Path savePath(int slot) { return savePath("slot" + slot); }
    static Path savePath(String name) { return Path.of(System.getProperty("user.home"), ".warfare", name + ".sav"); }
}
