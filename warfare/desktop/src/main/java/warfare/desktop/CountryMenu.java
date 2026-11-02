package warfare.desktop;

import warfare.core.*;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * The country screen opened by clicking a capital (inspired by Age of History 3): overview, government and approval
 * ("democracy menu"), relations with diplomatic actions, and military. Foreign details are limited by your intelligence.
 */
final class CountryMenu {
    private static final String[] TABS = {"Overview", "Government", "Relations", "Military"};

    final GameState gs;
    final int nation;
    final JPanel root = new JPanel(new BorderLayout());
    final JDialog dialog;
    private final Runnable refreshAll, openEconomy;
    private int tab;
    private final JPanel header = new JPanel(new BorderLayout()), tabBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
    private final JPanel body = new Ui.ScrollColumn();
    private final JScrollPane scroll = new JScrollPane(body);
    private javax.swing.Timer timer;

    CountryMenu(Window owner, GameState gs, int nation, Runnable refreshAll, Runnable openEconomy) {
        this.gs = gs; this.nation = nation; this.refreshAll = refreshAll; this.openEconomy = openEconomy;
        root.setBackground(Ui.PANEL);
        header.setBackground(new Color(22, 31, 50));
        header.setBorder(BorderFactory.createEmptyBorder(14, 18, 12, 18));
        tabBar.setBackground(Ui.PANEL);
        body.setBorder(BorderFactory.createEmptyBorder(6, 16, 16, 16));
        scroll.setBorder(null);
        scroll.getViewport().setBackground(Ui.PANEL);
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        JPanel top = new JPanel(new BorderLayout());
        top.add(header, BorderLayout.NORTH);
        top.add(tabBar, BorderLayout.SOUTH);
        root.add(top, BorderLayout.NORTH);
        root.add(scroll, BorderLayout.CENTER);
        if (owner != null) {
            dialog = new JDialog(owner, gs.nationName(nation), Dialog.ModalityType.MODELESS);
            dialog.setContentPane(root);
            dialog.setSize(560, 700);
            dialog.setLocationRelativeTo(owner);
            timer = new javax.swing.Timer(1000, e -> { if (dialog.isVisible()) refreshBody(); });
            dialog.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override public void windowClosed(java.awt.event.WindowEvent e) { timer.stop(); }
            });
        } else dialog = null;
        rebuild();
    }

    void show() { if (dialog != null) { dialog.setVisible(true); timer.start(); } }

    void setTab(int t) { tab = t; rebuild(); }

    // ------------------------------------------------------------------ structure
    private boolean mine() { return nation == gs.player; }
    private double agents() { return mine() ? 100 : gs.agentLevel(gs.player, nation); }
    private WorldData.NationInfo info() { return gs.world.nations.get(nation); }
    private NationState st() { return gs.ns[nation]; }

    void rebuild() {
        header.removeAll();
        WorldData.NationInfo ni = info();
        JPanel text = Ui.column();
        text.add(Ui.title(ni.name.toUpperCase(), 38));
        text.add(Ui.label(st().gov.label + "  -  capital " + (ni.capitalName.isEmpty() ? "?" : ni.capitalName) + "  -  " + ni.continent, 13.5f, Ui.MUTED));
        JPanel pills = Ui.row();
        String rel = mine() ? "YOUR NATION" : gs.war[gs.player][nation] ? "AT WAR WITH YOU" : gs.isAlly(gs.player, nation) ? "ALLY" : "NEUTRAL";
        Color rc = mine() ? Ui.ACCENT : gs.war[gs.player][nation] ? Ui.RED : gs.isAlly(gs.player, nation) ? Ui.GREEN : Ui.MUTED;
        pills.add(Ui.pill(rel, rc));
        if (!ni.bloc.equals("NONE")) { pills.add(Ui.gap(6, 0)); pills.add(Ui.pill(ni.bloc, Ui.AMBER)); }
        if (!st().alive) { pills.add(Ui.gap(6, 0)); pills.add(Ui.pill("ELIMINATED", Ui.RED)); }
        text.add(Ui.gap(0, 6));
        text.add(pills);
        header.add(Ui.row(Ui.imageLabel(Assets.flag(ni, 110)), Ui.gap(16, 0), text), BorderLayout.CENTER);
        header.revalidate(); header.repaint();

        tabBar.removeAll();
        for (int i = 0; i < TABS.length; i++) {
            final int idx = i;
            JToggleButton b = new JToggleButton(TABS[i].toUpperCase(), i == tab);
            b.setFont(Fonts.label(15f));
            b.setFocusable(false);
            b.addActionListener(e -> { Audio.get().play(Synth.Sfx.CLICK); tab = idx; rebuild(); });
            tabBar.add(b);
        }
        tabBar.revalidate(); tabBar.repaint();
        refreshBody();
    }

    void refreshBody() {
        int pos = scroll.getVerticalScrollBar().getValue();
        body.removeAll();
        switch (tab) {
            case 0 -> overview();
            case 1 -> government();
            case 2 -> relations();
            default -> military();
        }
        body.add(Box.createVerticalGlue());
        body.revalidate(); body.repaint();
        SwingUtilities.invokeLater(() -> scroll.getVerticalScrollBar().setValue(pos));
    }

    // ------------------------------------------------------------------ pieces
    private JComponent row(String icon, Color tint, String name, String value) {
        JPanel r = Ui.row(Ui.imageLabel(Assets.icon(icon, 20, tint)), Ui.gap(10, 0), Ui.label(name, 14f, Ui.MUTED), Box.createHorizontalGlue(), Ui.label(value, 14.5f, Ui.TEXT));
        r.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
        return r;
    }

    private JComponent card(Component... parts) {
        Ui.Card c = new Ui.Card(new BorderLayout());
        JPanel col = Ui.column();
        for (Component p : parts) col.add(p);
        c.add(col, BorderLayout.CENTER);
        JPanel wrap = Ui.column();
        wrap.add(c); wrap.add(Ui.gap(0, 8));
        return wrap;
    }

    private String hidden(double need) { return "unknown  (agents " + (int) need + "%+)"; }

    private JComponent flags(List<Integer> list) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        p.setOpaque(false);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        if (list.isEmpty()) p.add(Ui.label("none", 13.5f, Ui.MUTED));
        for (int n : list) {
            JLabel l = new JLabel(gs.world.nations.get(n).tag, new ImageIcon(Assets.flag(gs.world.nations.get(n), 26)), SwingConstants.LEFT);
            l.setFont(Fonts.label(13.5f)); l.setForeground(Ui.TEXT); l.setIconTextGap(5);
            l.setToolTipText(gs.nationName(n));
            p.add(l);
        }
        return p;
    }

    // ------------------------------------------------------------------ tabs
    private void overview() {
        WorldData.NationInfo ni = info();
        double ag = agents();
        body.add(Ui.section("At a glance"));
        body.add(card(
                row("manpower", Ui.ACCENT, "Population", String.format("%,d", ni.population)),
                row("money", Ui.GOLD, "GDP", String.format("$%,.0f M / yr", ni.gdp)),
                row("battle", Ui.TEXT, "Provinces held", gs.ownedProvinces(nation) + " of " + ni.provinces.size()),
                row("stability", Ui.GREEN, "Stability", ag >= 30 ? (int) st().stability + "%" : hidden(30)),
                row("money", Ui.GOLD, "Treasury", ag >= 50 ? String.format("%,.0f  (%+.1f/day)", st().money, st().lastIncome) : hidden(50)),
                row("oil", new Color(200, 160, 120), "Oil production", ni.oilProduction > 0 ? String.format("%.1f", ni.oilProduction) : "none"),
                row("nuke", ni.nukes > 0 ? Ui.RED : Ui.MUTED, "Nuclear weapons", ni.nukes > 0 ? (ag >= 50 ? st().nukes + " warheads" : "nuclear power") : "none")));
        List<Integer> allies = new ArrayList<>(), wars = new ArrayList<>();
        for (int n = 0; n < gs.N; n++) {
            if (n == nation || !gs.ns[n].alive) continue;
            if (gs.war[nation][n]) wars.add(n);
            if (gs.ally[nation][n] && gs.allianceKnownTo(gs.player, nation, n) || (gs.isAlly(nation, n) && !gs.ally[nation][n])) allies.add(n);
        }
        body.add(Ui.section("Allies and blocs"));
        body.add(card(flags(allies)));
        body.add(Ui.section("At war with"));
        body.add(card(flags(wars)));
    }

    private void government() {
        NationState s = st();
        Government g = s.gov;
        double ag = agents();
        body.add(Ui.section("Government"));
        List<Component> eff = new ArrayList<>();
        eff.add(Ui.label(g.label, 20f, Ui.TEXT));
        eff.add(Ui.label(String.format("War weariness x%.1f   -   tax anger x%.1f   -   coup risk x%.1f   -   stability %+d", g.warWeariness, g.taxAnger, g.coupRisk, g.stabilityOffset), 12.5f, Ui.MUTED));
        eff.add(Ui.label(g.elections ? "Holds elections every four years: losing approval means losing power." : "No elections: the regime answers to the army, party or clergy.", 12.5f, Ui.MUTED));
        if (g.elections) {
            String when = ag >= 25 || mine() ? "in " + Math.max(0, (s.nextElection - gs.day)) + " days  (" + gs.date().plusDays(Math.max(0, s.nextElection - gs.day)) + ")" : hidden(25);
            eff.add(Ui.gap(0, 4));
            eff.add(row("time", Ui.AMBER, "Next election", when));
        }
        body.add(card(eff.toArray(new Component[0])));

        body.add(Ui.section("Who supports the government"));
        if (ag < 40 && !mine()) {
            body.add(card(Ui.label("<html>Approval by interest group is secret. Station agents in " + SidePanels.esc(info().name) + " (40%+) to read the mood of its army, business, workers and public.</html>", 13f, Ui.MUTED)));
        } else {
            double[] w = Politics.weights(g);
            double[] target = Politics.target(s, gs.atWarAny(nation));
            String[] icons = {"armor", "money", "industry", "tab_diplo"};
            List<Component> rows = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                Color c = s.approval[i] < 35 ? Ui.RED : s.approval[i] < 55 ? Ui.AMBER : Ui.GREEN;
                rows.add(Ui.row(Ui.imageLabel(Assets.icon(icons[i], 20, c)), Ui.gap(10, 0), Ui.label(Politics.GROUPS[i], 14f, Ui.TEXT), Box.createHorizontalGlue(),
                        Ui.label(String.format("%.0f%%   (weight %.0f%%, heading to %.0f)", s.approval[i], w[i] * 100, target[i]), 12.5f, Ui.MUTED)));
                rows.add(new Ui.Bar(s.approval[i] / 100.0, c));
                rows.add(Ui.gap(0, 5));
            }
            double ov = Politics.overall(s);
            rows.add(Ui.row(Ui.label("Overall approval", 15f, Ui.TEXT), Box.createHorizontalGlue(), Ui.label(String.format("%.0f%%", ov), 18f, ov >= 50 ? Ui.GREEN : Ui.RED)));
            body.add(card(rows.toArray(new Component[0])));
            if (mine()) body.add(Ui.label("<html><div style='width:470px'>Approval follows your budget: the army likes military funding, business wants low tax and development, workers want welfare, and the public follows stability.</div></html>", 12.5f, Ui.MUTED));
        }
        if (mine()) {
            body.add(Ui.section("Powers of the state"));
            JPanel acts = Ui.column();
            acts.add(Ui.row(Ui.button("Open economy panel", "money", () -> { if (dialog != null) dialog.setVisible(false); openEconomy.run(); })));
            acts.add(Ui.gap(0, 5));
            if (g.elections) {
                acts.add(Ui.row(Ui.button("Call early elections", "general", () -> {
                    if (JOptionPane.showConfirmDialog(root, "Call elections tomorrow? If approval is below 50% you lose power.", "Early elections", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION)
                        { SidePanels.message(root, gs.callElection(nation)); refreshAll.run(); refreshBody(); }
                })));
                acts.add(Ui.gap(0, 5));
            }
            if (g == Government.DEMOCRACY || g == Government.REPUBLIC || g == Government.MONARCHY) {
                JButton seize = Ui.button("Seize emergency powers", "wall", () -> {
                    if (JOptionPane.showConfirmDialog(root, "Suspend elections and rule by decree? Stability -15 and the world will condemn it.", "Emergency powers", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION)
                        { SidePanels.message(root, gs.seizePower(nation)); refreshAll.run(); rebuild(); }
                });
                seize.setBackground(new Color(110, 40, 40));
                acts.add(Ui.row(seize));
            }
            body.add(card(acts));
        }
    }

    private interface Act { GameState.Result run(); }

    private JButton act(String label, String icon, Act a) {
        return Ui.button(label, icon, () -> { SidePanels.message(root, a.run()); refreshAll.run(); refreshBody(); });
    }

    private void relations() {
        int me = gs.player;
        if (mine()) {
            body.add(Ui.section("Your diplomacy"));
            List<Integer> al = new ArrayList<>(), wr = new ArrayList<>();
            for (int n = 0; n < gs.N; n++) { if (n != me && gs.ns[n].alive && gs.isAlly(me, n)) al.add(n); if (gs.war[me][n]) wr.add(n); }
            body.add(card(Ui.label("Allies", 14f, Ui.TEXT), flags(al), Ui.label("At war with", 14f, Ui.TEXT), flags(wr)));
            return;
        }
        double op = gs.opinion[nation][me];
        body.add(Ui.section("What they think of you"));
        Relations.Verdict v = Relations.allianceVerdict(gs, me, nation);
        Color oc = op < -20 ? Ui.RED : op < 30 ? Ui.AMBER : Ui.GREEN;
        body.add(card(
                Ui.row(Ui.label("Opinion", 15f, Ui.TEXT), Box.createHorizontalGlue(), Ui.label(String.format("%+.0f", op), 20f, oc)),
                new Ui.Bar((op + 100) / 200.0, oc),
                Ui.gap(0, 6),
                Ui.label("<html>Alliance: " + (v.accept() ? "<b style='color:#60cc86'>they would accept</b>" : "<b style='color:#e85a4e'>they would refuse</b>")
                        + String.format(" (score %.0f, needs %.0f)<br><span style='color:#929eb6'>%s</span></html>", v.score(), v.needed(), SidePanels.esc(v.reason())), 13f, Ui.TEXT)));
        body.add(Ui.section("Actions"));
        JPanel grid = new JPanel(new GridLayout(0, 2, 6, 6));
        grid.setOpaque(false);
        grid.setAlignmentX(Component.LEFT_ALIGNMENT);
        grid.add(act("Declare war", "battle", () -> gs.declareWar(me, nation)));
        grid.add(act("Offer peace", "general", () -> gs.offerPeace(me, nation)));
        grid.add(act("Propose alliance", "tab_diplo", () -> gs.formAlliance(me, nation)));
        grid.add(act("Dissolve alliance", "split", () -> gs.breakAlliance(me, nation)));
        grid.add(act("Toggle sanctions", "wall", () -> gs.toggleSanction(me, nation)));
        grid.add(act("Buy oil", "oil", () -> gs.oilDeal(nation, me)));
        grid.add(act("Send 30 aid", "money", () -> gs.sendAid(me, nation, 30)));
        grid.add(act("Send arms (40)", "armor", () -> gs.armsDeal(me, nation)));
        grid.add(act("Station agent (25)", "spy", () -> gs.assignAgent(me, nation)));
        body.add(grid);
        body.add(Ui.section("Their friends and enemies"));
        List<Integer> al = new ArrayList<>(), wr = new ArrayList<>();
        for (int n = 0; n < gs.N; n++) {
            if (n == nation || n == me || !gs.ns[n].alive) continue;
            if (gs.allianceKnownTo(me, nation, n) && gs.ally[nation][n]) al.add(n);
            if (gs.war[nation][n]) wr.add(n);
        }
        body.add(card(Ui.label("Allies", 14f, Ui.TEXT), flags(al), Ui.label("At war with", 14f, Ui.TEXT), flags(wr)));
    }

    private void military() {
        double ag = agents();
        String[] icons = {"infantry", "mechanized", "armor", "artillery", "anti_air", "anti_tank", "special"};
        if (mine()) {
            double[] tot = new double[UnitType.N];
            int armies = 0;
            for (Army a : gs.armies.values()) if (a.owner == nation && !a.decoy) { armies++; for (int i = 0; i < tot.length; i++) tot[i] += a.units[i]; }
            body.add(Ui.section("Your forces"));
            List<Component> rows = new ArrayList<>();
            rows.add(row("battle", Ui.TEXT, "Armies", String.valueOf(armies)));
            for (UnitType t : UnitType.values()) if (tot[t.ordinal()] >= 0.5) rows.add(row(icons[t.ordinal()], Ui.TEXT, t.label, String.valueOf((int) Math.round(tot[t.ordinal()]))));
            rows.add(row("air", Ui.ACCENT, "Air wings", String.valueOf(st().airWings.size())));
            rows.add(row("nuke", Ui.RED, "Nuclear warheads", String.valueOf(st().nukes)));
            body.add(card(rows.toArray(new Component[0])));
            return;
        }
        body.add(Ui.section("What we know"));
        double est = Vision.estimateStrength(gs, gs.player, nation) / 11.0;
        List<Component> rows = new ArrayList<>();
        rows.add(row("battle", Ui.TEXT, "Estimated strength", String.format("about %d battalions  (%s)", (int) est, ag > 30 ? "agent report" : "rough")));
        rows.add(row("general", Ui.TEXT, "Morale and readiness", ag >= 50 ? "good" : hidden(50)));
        int seen = 0;
        for (Intel.Sighting s : gs.intelOf(gs.player).activeSightings(gs.day)) if (s.ownerKnown && s.owner == nation) seen++;
        rows.add(row("recon", Ui.GREEN, "Their forces currently tracked", seen + " sightings"));
        body.add(card(rows.toArray(new Component[0])));
        body.add(Ui.label("<html><div style='width:470px'>Recon, aircraft, radar, satellites and agents improve these numbers. Allies share what they see.</div></html>", 12.5f, Ui.MUTED));
    }
}
