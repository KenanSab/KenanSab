package warfare.desktop;

import warfare.core.*;
import warfare.core.NationState.AirWing;
import warfare.core.NationState.Mission;
import warfare.core.NationState.Track;

import javax.swing.*;
import java.awt.*;

/** Production tab: recruit forces, research tracks, air wings, queues and the nuclear arsenal. */
final class ProductionPanel extends JPanel {
    private static final String[] UNIT_ICON = {"infantry", "mechanized", "armor", "artillery", "anti_air", "anti_tank", "special"};
    private static final String[] TRACK_ICON = {"general", "air", "recon"};

    final GameState gs;
    final MapPanel map;
    final Runnable refreshAll;
    private final int[] counts = new int[UnitType.N];
    private final JLabel[] countLabels = new JLabel[UnitType.N];
    private final JLabel total = Ui.label(" ", 13.5f, Ui.GOLD);
    private final JPanel dynamic = Ui.column();
    private final JComboBox<Mission> mission = new JComboBox<>(Mission.values());

    ProductionPanel(GameState gs, MapPanel map, Runnable refreshAll) {
        super(new BorderLayout());
        this.gs = gs; this.map = map; this.refreshAll = refreshAll;
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBackground(Ui.PANEL);
        content.setBorder(BorderFactory.createEmptyBorder(10, 12, 12, 12));

        content.add(Ui.section("Recruit forces"));
        Ui.Card rc = new Ui.Card(new BorderLayout());
        JPanel col = Ui.column();
        for (UnitType t : UnitType.values()) {
            int i = t.ordinal();
            countLabels[i] = Ui.label("0", 15f, Ui.TEXT);
            countLabels[i].setHorizontalAlignment(SwingConstants.CENTER);
            countLabels[i].setPreferredSize(new Dimension(28, 20));
            JPanel info = Ui.column();
            info.add(Ui.label(t.label, 13.5f, Ui.TEXT));
            info.add(Ui.label((int) t.cost + " money  -  " + (int) t.manpower + " manpower  -  " + t.buildDays + " days", 11.5f, Ui.MUTED));
            JPanel r = Ui.row(Ui.imageLabel(Assets.icon(UNIT_ICON[i], 24, Ui.TEXT)), Ui.gap(8, 0), info, Box.createHorizontalGlue(),
                    step("-", () -> bump(i, -1)), countLabels[i], step("+", () -> bump(i, 1)));
            r.setBorder(BorderFactory.createEmptyBorder(3, 0, 3, 0));
            col.add(r);
        }
        col.add(Ui.gap(0, 4));
        col.add(total);
        rc.add(col, BorderLayout.CENTER);
        content.add(rc);
        content.add(Ui.gap(0, 8));
        JButton go = Ui.button("Recruit at selected province", "battle", () -> {
            if (map.selProv < 0) { SidePanels.message(this, new GameState.Result(false, "Select one of your provinces first.")); return; }
            double[] comp = new double[UnitType.N];
            for (int i = 0; i < comp.length; i++) comp[i] = counts[i];
            SidePanels.message(this, gs.recruit(gs.player, map.selProv, comp));
            refreshAll.run();
        });
        content.add(Ui.row(go));
        content.add(Ui.gap(0, 6));
        JButton decoy = Ui.button("Deploy decoy force (8 bns, 16 money)", "hidden", () -> {
            if (map.selProv < 0) { SidePanels.message(this, new GameState.Result(false, "Select one of your provinces first.")); return; }
            SidePanels.message(this, gs.deployDecoy(gs.player, map.selProv, 8));
            refreshAll.run();
        });
        decoy.setToolTipText("Enemies who see it from afar read it as a real army. Close-up vision exposes it.");
        content.add(Ui.row(decoy));

        content.add(Ui.section("Research"));
        for (Track t : Track.values()) content.add(researchCard(t));

        content.add(Ui.section("Air power"));
        JButton wing = Ui.button("New wing at selected airfield", "air", () -> {
            if (map.selProv >= 0) SidePanels.message(this, gs.addAirWing(gs.player, map.selProv));
            refreshAll.run();
        });
        JButton assign = Ui.button("Assign wing to selected province", "recon", () -> {
            if (map.selProv < 0) return;
            AirWing pick = null;
            for (AirWing w : gs.ns[gs.player].airWings) if (w.target < 0) { pick = w; break; }
            if (pick == null && !gs.ns[gs.player].airWings.isEmpty()) pick = gs.ns[gs.player].airWings.get(0);
            if (pick == null) { SidePanels.message(this, new GameState.Result(false, "No air wings.")); return; }
            SidePanels.message(this, gs.setAirMission(gs.player, pick, (Mission) mission.getSelectedItem(), map.selProv));
            refreshAll.run();
        });
        mission.setFont(Fonts.ui(13f));
        JPanel airBox = Ui.column();
        airBox.add(Ui.row(wing)); airBox.add(Ui.gap(0, 5));
        airBox.add(Ui.row(mission, Ui.gap(6, 0), assign));
        content.add(airBox);

        content.add(Ui.section("Queues"));
        content.add(dynamic);
        JScrollPane sp = new JScrollPane(content);
        sp.setBorder(null);
        sp.getVerticalScrollBar().setUnitIncrement(18);
        add(sp, BorderLayout.CENTER);
        sum();
    }

    private JButton step(String text, Runnable r) {
        JButton b = new JButton(text);
        b.setFont(Fonts.ui(15f).deriveFont(Font.BOLD));
        b.setMargin(new Insets(0, 0, 0, 0));
        b.setPreferredSize(new Dimension(26, 24));
        b.setFocusable(false);
        b.addActionListener(e -> r.run());
        return b;
    }

    private void bump(int i, int d) {
        counts[i] = Math.max(0, Math.min(12, counts[i] + d));
        countLabels[i].setText(String.valueOf(counts[i]));
        sum();
    }

    private void sum() {
        double cost = 0, men = 0;
        for (UnitType t : UnitType.values()) { cost += counts[t.ordinal()] * t.cost; men += counts[t.ordinal()] * t.manpower; }
        total.setText(String.format("Total  %.0f money  -  %.0f manpower", cost, men));
    }

    private JComponent researchCard(Track t) {
        NationState s = gs.ns[gs.player];
        int lvl = s.tech[t.ordinal()];
        boolean busy = s.researching == t;
        Ui.Card c = new Ui.Card(new BorderLayout());
        c.setBorder(BorderFactory.createEmptyBorder(7, 10, 7, 10));
        String pips = "Lv " + lvl + "/5";
        JPanel text = Ui.column();
        text.add(Ui.label(t.label, 14f, Ui.TEXT));
        text.add(Ui.label(lvl >= 5 ? "Maximum level" : busy ? "Researching... " + (int) s.researchLeft + " days left" : "Next level costs " + 60 * (lvl + 1) + " money", 12f, busy ? Ui.AMBER : Ui.MUTED));
        c.add(Ui.row(Ui.imageLabel(Assets.icon(TRACK_ICON[t.ordinal()], 26, busy ? Ui.AMBER : Ui.ACCENT)), Ui.gap(10, 0), text, Box.createHorizontalGlue(), Ui.label(pips, 13.5f, Ui.GOLD)), BorderLayout.CENTER);
        if (lvl < 5 && !busy) c.onClick(() -> { SidePanels.message(this, gs.research(gs.player, t)); refreshAll.run(); });
        JPanel wrap = Ui.column();
        wrap.add(c); wrap.add(Ui.gap(0, 4));
        return wrap;
    }

    void update() {
        NationState s = gs.ns[gs.player];
        dynamic.removeAll();
        int cap = gs.productionCapacity(gs.player);
        dynamic.add(Ui.label("Production slots   " + s.recruitQueue.size() + " / " + cap, 13.5f, Ui.TEXT));
        dynamic.add(new Ui.Bar(s.recruitQueue.size() / (double) Math.max(1, cap), Ui.ACCENT));
        dynamic.add(Ui.gap(0, 6));
        for (NationState.Recruit r : s.recruitQueue) dynamic.add(Ui.label("Troops at " + gs.prov(r.province).name + "  -  " + r.daysLeft + " days", 12.5f, Ui.MUTED));
        for (NationState.Construction c : s.constructions) dynamic.add(Ui.label(c.type.label + " in " + gs.prov(c.province).name + "  -  " + c.daysLeft + " days", 12.5f, Ui.MUTED));
        dynamic.add(Ui.gap(0, 6));
        dynamic.add(Ui.label("Air wings: " + s.airWings.size(), 13.5f, Ui.TEXT));
        for (AirWing w : s.airWings)
            dynamic.add(Ui.label(gs.prov(w.base).name + "  -  " + w.mission + (w.target >= 0 ? " over " + gs.prov(w.target).name : " (idle)"), 12.5f, Ui.MUTED));
        if (s.airWings.isEmpty()) dynamic.add(Ui.label("None. Build an airfield, then form a wing.", 12.5f, Ui.MUTED));
        if (s.nukes > 0) {
            dynamic.add(Ui.gap(0, 8));
            Ui.Card n = new Ui.Card(new BorderLayout());
            n.edge = new Color(Ui.RED.getRed(), Ui.RED.getGreen(), Ui.RED.getBlue(), 160);
            n.add(Ui.row(Ui.imageLabel(Assets.icon("nuke", 28, Ui.RED)), Ui.gap(10, 0), Ui.label("Nuclear arsenal", 14f, Ui.TEXT), Box.createHorizontalGlue(),
                    Ui.label(s.nukes + " warheads", 14f, Ui.RED)), BorderLayout.CENTER);
            dynamic.add(n);
        }
        dynamic.revalidate(); dynamic.repaint();
    }
}
