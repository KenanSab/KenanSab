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
    final SidePanels.Selection selection;
    final SidePanels.Production production;
    final SidePanels.Diplomacy diplomacy;
    final SidePanels.Log log;
    final JTabbedPane tabs = new JTabbedPane();
    final JLabel dateLabel = new JLabel(), resources = new JLabel(), newsBar = new JLabel(" ");
    final JButton[] speedButtons = new JButton[5];
    final JButton pauseButton = new JButton("Pause");
    final Timer timer;
    boolean paused = true;
    int speed = 2;
    boolean dockRight = true, showSide = true, showNews = true;

    GameUi(GameState gs, JFrame frame) {
        super(new BorderLayout());
        this.gs = gs; this.frame = frame;
        map = new MapPanel(gs, WarfareApp.assets);
        Runnable refresh = this::refresh;
        selection = new SidePanels.Selection(gs, map, refresh);
        production = new SidePanels.Production(gs, map, refresh);
        diplomacy = new SidePanels.Diplomacy(gs, map, refresh);
        log = new SidePanels.Log(gs);
        map.onSelect = this::refresh;
        map.onOrder = this::refresh;
        tabs.addTab("Selection", selection);
        tabs.addTab("Production", production);
        tabs.addTab("Diplomacy", diplomacy);
        tabs.addTab("Reports", log);
        tabs.setPreferredSize(new Dimension(380, 100));
        add(topBar(), BorderLayout.NORTH);
        add(map, BorderLayout.CENTER);
        add(tabs, BorderLayout.EAST);
        JPanel south = new JPanel(new BorderLayout());
        south.add(newsBar, BorderLayout.CENTER);
        south.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
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

    JComponent topBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        bar.setBackground(new Color(24, 30, 42));
        dateLabel.setForeground(Color.WHITE); dateLabel.setFont(dateLabel.getFont().deriveFont(Font.BOLD, 14f));
        resources.setForeground(new Color(220, 228, 240));
        bar.add(dateLabel);
        pauseButton.addActionListener(e -> setPaused(!paused));
        bar.add(pauseButton);
        for (int i = 0; i < 5; i++) {
            final int s = i + 1;
            speedButtons[i] = new JButton(String.valueOf(s));
            speedButtons[i].setMargin(new Insets(2, 8, 2, 8));
            speedButtons[i].addActionListener(e -> setSpeed(s));
            bar.add(speedButtons[i]);
        }
        bar.add(resources);
        return bar;
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

    void tick() {
        gs.advanceDay();
        map.refreshColors();
        refresh();
        handlePauses();
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
        NationState s = gs.ns[gs.player];
        dateLabel.setText(gs.date() + "  |  " + gs.nationName(gs.player));
        pauseButton.setText(paused ? "Play" : "Pause");
        for (int i = 0; i < 5; i++) speedButtons[i].setFont(speedButtons[i].getFont().deriveFont(i + 1 == speed && !paused ? Font.BOLD : Font.PLAIN));
        resources.setText(String.format("<html>Money <b>%.0f</b> (%+.1f)  |  Manpower <b>%.0f</b>  |  Industry <b>%d</b>  |  Oil <b>%.0f</b> (%+.2f)  |  Stability <b>%.0f%%</b>  |  War weariness %.0f%%%s</html>",
                s.money, s.lastIncome, s.manpower, gs.productionCapacity(gs.player), s.oil, s.lastOilDelta, s.stability, s.warWeariness,
                s.nukes > 0 ? "  |  Nukes <b>" + s.nukes + "</b>" : ""));
        resources.setToolTipText("Money, manpower (thousands), industry slots, oil, stability");
        int n = gs.news.size();
        newsBar.setText(n == 0 ? " " : gs.news.get(n - 1));
        selection.update();
        production.update();
        diplomacy.update();
        log.update();
        map.repaint();
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
            game.add(load);
        }
        game.addSeparator();
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
        JMenuItem keys = new JMenuItem("Controls");
        keys.addActionListener(e -> JOptionPane.showMessageDialog(this,
                "Space: pause / play      1-5: game speed      M: cycle map mode      Esc: deselect army\n"
                        + "Left-click an army marker, then left-click a province to march there.\n"
                        + "Right-click: inspect a province.   Mouse wheel: zoom.   Drag: pan.\n\n"
                        + "Fog of war: you only see what your units, provinces, radar, aircraft, agents,\n"
                        + "satellites and allies reveal. Old sightings fade and expire after 45 days.\n\n"
                        + "Battles: choose a tactic card per army, or leave the army on General (auto)."));
        help.add(keys);
        mb.add(help);
        return mb;
    }

    static Path savePath(int slot) { return Path.of(System.getProperty("user.home"), ".warfare", "slot" + slot + ".sav"); }
}
