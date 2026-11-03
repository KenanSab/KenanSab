package warfare.desktop;

import warfare.core.*;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.function.IntConsumer;

/** Nation list with live opinion and relation, search and filters, and the diplomatic actions for the selected nation. */
final class DiplomacyPanel extends JPanel {
    private static final String[] FILTERS = {"All", "Allies", "At war", "Nuclear"};

    final GameState gs;
    final Runnable refreshAll;
    IntConsumer onOpen = n -> {};
    private final List<Row> rows = new ArrayList<>();
    private final JPanel list = new Ui.ScrollColumn();
    private final JTextField search = Wf.field("Search nations...");
    private final Wf.Segmented filter;
    private final JLabel selectedLabel = Ui.label("Select a nation", 14f, Ui.MUTED);
    private final List<Wf.Button> actionButtons = new ArrayList<>();
    private int selected = -1;

    DiplomacyPanel(GameState gs, MapPanel map, Runnable refreshAll) {
        super(new BorderLayout());
        this.gs = gs; this.refreshAll = refreshAll;
        setOpaque(false);
        filter = new Wf.Segmented(FILTERS, 0, i -> applyFilter());
        JPanel top = new JPanel();
        top.setOpaque(false);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.setBorder(BorderFactory.createEmptyBorder(2, 14, 8, 14));
        search.setAlignmentX(Component.LEFT_ALIGNMENT);
        search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { applyFilter(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { applyFilter(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { applyFilter(); }
        });
        top.add(search);
        top.add(Ui.gap(0, 8));
        top.add(filter);
        add(top, BorderLayout.NORTH);

        list.setBorder(BorderFactory.createEmptyBorder(0, 10, 6, 6));
        List<Integer> ids = new ArrayList<>();
        for (int n = 0; n < gs.N; n++) if (n != gs.player) ids.add(n);
        ids.sort(Comparator.comparing(gs::nationName));
        for (int n : ids) { Row r = new Row(n); rows.add(r); list.add(r); }
        add(Wf.scroll(list), BorderLayout.CENTER);

        JPanel bottom = new JPanel();
        bottom.setOpaque(false);
        bottom.setLayout(new BoxLayout(bottom, BoxLayout.Y_AXIS));
        bottom.setBorder(BorderFactory.createEmptyBorder(8, 14, 14, 14));
        selectedLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        bottom.add(selectedLabel);
        bottom.add(Ui.gap(0, 8));
        JPanel grid = new JPanel(new GridLayout(0, 2, 6, 6));
        grid.setOpaque(false);
        grid.setAlignmentX(Component.LEFT_ALIGNMENT);
        int me = gs.player;
        action(grid, "War", "battle", n -> gs.declareWar(me, n), Wf.Kind.DANGER);
        action(grid, "Peace", "general", n -> { PeaceCards.open(GameUi.current, n); return null; }, Wf.Kind.NORMAL);
        action(grid, "Alliance", "tab_diplo", n -> gs.formAlliance(me, n), Wf.Kind.PRIMARY);
        action(grid, "Break pact", "split", n -> gs.breakAlliance(me, n), Wf.Kind.NORMAL);
        action(grid, "Sanction", "wall", n -> gs.toggleSanction(me, n), Wf.Kind.NORMAL);
        action(grid, "Buy oil", "oil", n -> gs.oilDeal(n, me), Wf.Kind.NORMAL);
        action(grid, "Send aid", "money", n -> gs.sendAid(me, n, 30), Wf.Kind.NORMAL);
        action(grid, "Send arms", "armor", n -> gs.armsDeal(me, n), Wf.Kind.NORMAL);
        action(grid, "Agent", "spy", n -> gs.assignAgent(me, n), Wf.Kind.NORMAL);
        Wf.Button open = Wf.ghost("Country screen", "castle", () -> { if (selected >= 0) onOpen.accept(selected); });
        grid.add(open);
        actionButtons.add(open);
        bottom.add(grid);
        bottom.add(Ui.gap(0, 8));
        Wf.Button nuke = Wf.danger("Launch nuke at selected province", "nuke", () -> {
            if (map.selProv < 0) { GameUi.notify(new GameState.Result(false, "Select a target province on the map first.")); return; }
            GameUi.confirm("Nuclear launch", "Launch a nuclear missile at " + gs.prov(map.selProv).name + "? This cannot be undone and the world will condemn it.", "Launch", true, () -> { GameUi.notify(gs.launchNuke(me, map.selProv)); refreshAll.run(); });
        });
        nuke.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        bottom.add(nuke);
        add(bottom, BorderLayout.SOUTH);
        applyFilter();
    }

    private void action(JPanel grid, String label, String icon, Function<Integer, GameState.Result> fn, Wf.Kind kind) {
        Wf.Button b = new Wf.Button(label, icon, kind, () -> {
            if (selected < 0) { GameUi.notify(new GameState.Result(false, "Select a nation in the list first.")); return; }
            GameUi.notify(fn.apply(selected));
            refreshAll.run();
            update();
        });
        grid.add(b);
        actionButtons.add(b);
    }

    private void select(int n) {
        selected = n;
        selectedLabel.setText(n < 0 ? "Select a nation" : gs.nationName(n) + "   -   opinion " + String.format("%+.0f", gs.opinion[n][gs.player]));
        selectedLabel.setForeground(n < 0 ? Ui.MUTED : Ui.TEXT);
        for (Row r : rows) r.repaint();
    }

    private boolean passes(Row r) {
        int n = r.nation;
        if (!gs.ns[n].alive) return false;
        String q = search.getText().trim().toLowerCase();
        if (!q.isEmpty() && !gs.nationName(n).toLowerCase().contains(q)) return false;
        return switch (filter.selected()) {
            case 1 -> gs.isAlly(gs.player, n);
            case 2 -> gs.war[gs.player][n];
            case 3 -> gs.world.nations.get(n).nukes > 0;
            default -> true;
        };
    }

    private void applyFilter() {
        for (Row r : rows) { r.setVisible(passes(r)); r.refresh(); }
        list.revalidate(); list.repaint();
    }

    void update() {
        for (Row r : rows) if (r.isVisible() || !gs.ns[r.nation].alive) r.refresh();
        if (selected >= 0) selectedLabel.setText(gs.nationName(selected) + "   -   opinion " + String.format("%+.0f", gs.opinion[selected][gs.player]));
    }

    int selectedNation() { return selected; }
    void selectNation(int n) { select(n); }

    // ------------------------------------------------------------------ one nation row
    private final class Row extends JPanel {
        final int nation;
        private final JLabel name = new JLabel(), gov = new JLabel(), opinion = new JLabel(), relation = new JLabel();
        private boolean hover;

        Row(int nation) {
            this.nation = nation;
            setOpaque(false);
            setLayout(new BorderLayout(10, 0));
            setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
            setAlignmentX(Component.LEFT_ALIGNMENT);
            setPreferredSize(new Dimension(300, 46));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 46));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            JLabel flag = new JLabel(new ImageIcon(Assets.flag(gs.world.nations.get(nation), 32)));
            name.setFont(Fonts.ui(14.5f)); name.setForeground(Ui.TEXT);
            gov.setFont(Fonts.ui(12f)); gov.setForeground(Ui.MUTED);
            JPanel mid = new JPanel(new GridLayout(2, 1));
            mid.setOpaque(false);
            mid.add(name); mid.add(gov);
            opinion.setFont(Fonts.ui(15f).deriveFont(Font.BOLD));
            relation.setFont(Fonts.label(12.5f));
            JPanel right = new JPanel(new GridLayout(2, 1));
            right.setOpaque(false);
            opinion.setHorizontalAlignment(SwingConstants.RIGHT); relation.setHorizontalAlignment(SwingConstants.RIGHT);
            right.add(opinion); right.add(relation);
            add(flag, BorderLayout.WEST); add(mid, BorderLayout.CENTER); add(right, BorderLayout.EAST);
            addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) {
                    Audio.get().play(Synth.Sfx.CLICK);
                    select(nation);
                    if (e.getClickCount() == 2) onOpen.accept(nation);
                }
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); }
            });
            refresh();
        }

        void refresh() {
            name.setText(gs.nationName(nation));
            gov.setText(gs.ns[nation].gov.label + "  -  " + gs.world.nations.get(nation).continent);
            int o = Math.round(gs.opinion[nation][gs.player]);
            opinion.setText(String.format("%+d", o));
            opinion.setForeground(o < -20 ? Ui.RED : o < 30 ? Ui.AMBER : Ui.GREEN);
            int me = gs.player;
            String rel = gs.war[me][nation] ? "AT WAR" : gs.ally[me][nation] && gs.allianceKnownTo(me, me, nation) ? (gs.secret[me][nation] ? "SECRET PACT" : "ALLY") : gs.isAlly(me, nation) ? "BLOC ALLY" : gs.sanction[me][nation] ? "SANCTIONED" : "";
            relation.setText(rel);
            relation.setForeground(rel.equals("AT WAR") ? Ui.RED : rel.isEmpty() ? Ui.MUTED : rel.equals("SANCTIONED") ? Ui.AMBER : Ui.GREEN);
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Wf.quality(g);
            boolean sel = selected == nation;
            if (sel || hover) {
                g.setColor(sel ? new Color(236, 192, 92, 36) : new Color(255, 255, 255, 18));
                g.fill(new RoundRectangle2D.Float(0, 1, getWidth() - 1, getHeight() - 3, 12, 12));
                if (sel) { g.setColor(new Color(236, 192, 92, 200)); g.setStroke(new BasicStroke(1.4f)); g.draw(new RoundRectangle2D.Float(0.5f, 1.5f, getWidth() - 2, getHeight() - 4, 12, 12)); }
            }
            g.dispose();
        }
    }
}
