package warfare.desktop;

import warfare.core.*;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleConsumer;

/**
 * Left-hand economy dock (Age of History style): tax rate, budget allocation sliders with live effect text,
 * standing policies, the daily ledger and a build-economy tool. Every number comes from {@link GameState#previewLedger}.
 */
final class EconomyPanel extends JPanel {
    final GameState gs;
    final Runnable onChanged;          // lightweight refresh (top bar) while a slider moves
    final Runnable refreshAll;
    private final List<Runnable> refreshers = new ArrayList<>();
    private boolean updating;
    private Budget.Ledger led;             // computed once per refresh

    EconomyPanel(GameState gs, Runnable onChanged, Runnable refreshAll) {
        super(new BorderLayout());
        this.gs = gs; this.onChanged = onChanged; this.refreshAll = refreshAll;
        setPreferredSize(new Dimension(372, 100));
        setBackground(Ui.PANEL);
        JPanel head = new JPanel(new BorderLayout());
        head.setBackground(new Color(24, 33, 52));
        head.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        head.add(Ui.row(Ui.imageLabel(Assets.icon("money", 26, Ui.GOLD)), Ui.gap(8, 0), Ui.title("ECONOMY", 26)), BorderLayout.WEST);
        head.add(Ui.label("[E] to hide", 12.5f, Ui.MUTED), BorderLayout.EAST);
        add(head, BorderLayout.NORTH);

        JPanel c = new Ui.ScrollColumn();
        c.setBorder(BorderFactory.createEmptyBorder(6, 12, 14, 12));
        build(c);
        JScrollPane sp = new JScrollPane(c);
        sp.setBorder(null);
        sp.getViewport().setBackground(Ui.PANEL);
        sp.getVerticalScrollBar().setUnitIncrement(18);
        add(sp, BorderLayout.CENTER);
        update();
    }

    private Budget b() { return gs.ns[gs.player].budget(); }
    private NationState s() { return gs.ns[gs.player]; }

    private void changed() {
        if (updating) return;
        b().clamp();
        update();
        onChanged.run();
    }

    /** Re-reads every value from the simulation (called daily while visible and after each slider move). */
    void update() {
        updating = true;
        led = gs.previewLedger(gs.player);
        for (Runnable r : refreshers) r.run();
        updating = false;
    }

    // ------------------------------------------------------------------ layout
    private void build(JPanel c) {
        // ---- summary
        Ui.Card sum = new Ui.Card(new BorderLayout());
        JPanel col = Ui.column();
        JLabel gdp = valueLabel(), growth = valueLabel(), net = valueLabel(), rev = valueLabel(), eff = valueLabel(), infl = valueLabel(), stab = valueLabel();
        col.add(statRow("industry", Ui.TEXT, "GDP", gdp));
        col.add(statRow("research", Ui.GREEN, "Growth per year", growth));
        col.add(statRow("money", Ui.GOLD, "Net income per day", net));
        col.add(statRow("money", Ui.MUTED, "Revenue per day", rev));
        col.add(statRow("wall", Ui.ACCENT, "Tax efficiency", eff));
        col.add(statRow("nuke", Ui.AMBER, "Inflation", infl));
        col.add(statRow("stability", Ui.GREEN, "Stability target", stab));
        sum.add(col, BorderLayout.CENTER);
        c.add(sum);
        refreshers.add(() -> {
            Budget.Ledger l = led;
            gdp.setText(String.format("%,.0f M$", l.gdp()));
            growth.setText(String.format("%+.1f%%", l.growth() * 100));
            growth.setForeground(l.growth() >= 0 ? Ui.GREEN : Ui.RED);
            net.setText(String.format("%+.2f", l.net()));
            net.setForeground(l.net() >= 0 ? Ui.GREEN : Ui.RED);
            rev.setText(String.format("%.2f", l.revenue()));
            eff.setText(String.format("%.0f%%", l.taxEfficiency() * 100));
            infl.setText(String.format("%.1f%%", s().inflation * 100));
            infl.setForeground(s().inflation > 0.15 ? Ui.RED : Ui.TEXT);
            stab.setText(String.format("%.0f   (now %.0f)", l.stabilityTarget(), s().stability));
        });

        // ---- presets
        c.add(Ui.section("Quick presets"));
        c.add(Ui.row(preset("Balanced", () -> apply(new Budget())), Ui.gap(5, 0), preset("Growth", () -> {
            Budget x = new Budget(); x.tax = 0.25; x.dev = 0.25; x.infra = 0.10; x.edu = 0.10; x.security = 0.04; x.military = 0.03; apply(x);
        })));
        c.add(Ui.gap(0, 5));
        c.add(Ui.row(preset("War economy", () -> {
            Budget x = Budget.forAi(true, false); x.mobilization = 2; x.industryFocus = 0.6; apply(x);
        }), Ui.gap(5, 0), preset("Austerity", () -> {
            Budget x = new Budget(); x.tax = 0.45; x.dev = 0.03; x.infra = 0.02; x.edu = 0.02; x.welfare = 0.05; x.security = 0.04; x.military = 0.03; apply(x);
        })));

        // ---- taxation
        c.add(Ui.section("Taxation"));
        c.add(slider("Tax rate", "money", Ui.GOLD, 10, 70, () -> b().tax * 100, v -> b().tax = v / 100.0,
                l -> String.format("Efficiency %.0f%%  -  revenue %.2f/day  -  growth %+.1f%% from tax", l.taxEfficiency() * 100, l.revenue(), -10 * (b().tax - Budget.TAX_DEFAULT)),
                l -> l.revenue(), "%.0f%%"));

        // ---- budget
        c.add(Ui.section("Budget  -  share of revenue"));
        JLabel alloc = Ui.label(" ", 13f, Ui.TEXT);
        JPanel allocBox = Ui.column();
        allocBox.add(alloc);
        c.add(allocBox);
        refreshers.add(() -> {
            double a = b().allocated();
            alloc.setText(String.format("Allocated %.0f%%   -   Reserve to treasury %.0f%%", a * 100, Math.max(0, 1 - a) * 100));
            alloc.setForeground(a > 1 ? Ui.RED : Ui.TEXT);
        });
        c.add(slider("Economic development", "industry", Ui.ACCENT, 0, 40, () -> b().dev * 100, v -> b().dev = v / 100.0,
                l -> String.format("Growth +%.1f%% per year from this programme", 12 * b().dev), l -> l.dev(), "%.0f%%"));
        c.add(slider("Infrastructure", "time", Ui.ACCENT, 0, 30, () -> b().infra * 100, v -> b().infra = v / 100.0,
                l -> String.format("Index %.0f%%  -  armies +%.0f%% faster, GDP +%.0f%%  (%s)", s().infraIndex * 100, 15 * s().infraIndex, 10 * s().infraIndex,
                        b().infra > 0.05 ? "rising" : b().infra < 0.05 ? "decaying" : "stable"), l -> l.infra(), "%.0f%%"));
        c.add(slider("Education & research", "research", Ui.GREEN, 0, 30, () -> b().edu * 100, v -> b().edu = v / 100.0,
                l -> String.format("Research speed x%.2f  -  growth +%.1f%%", Math.max(0.4, 1 + 6 * (b().edu - 0.05)), 5 * b().edu), l -> l.edu(), "%.0f%%"));
        c.add(slider("Welfare & services", "stability", Ui.GREEN, 0, 30, () -> b().welfare * 100, v -> b().welfare = v / 100.0,
                l -> String.format("Stability target %.0f  -  unrest %+.2f/day", l.stabilityTarget(), -(b().welfare - 0.08) * 1.5), l -> l.welfare(), "%.0f%%"));
        c.add(slider("Security & administration", "spy", Ui.AMBER, 0, 30, () -> b().security * 100, v -> b().security = v / 100.0,
                l -> String.format("Tax efficiency %.0f%%  -  unrest %+.2f/day", l.taxEfficiency() * 100, -(b().security - 0.05) * 3), l -> l.security(), "%.0f%%"));
        c.add(slider("Military funding", "armor", Ui.RED, 0, 40, () -> b().military * 100, v -> b().military = v / 100.0,
                l -> String.format("Morale cap %+.0f  -  recruitment %.0f%% faster", (b().military - 0.05) * 60, Math.max(0, (b().military - 0.05) * 3) * 100), l -> l.military(), "%.0f%%"));

        // ---- policies
        c.add(Ui.section("Policies"));
        Ui.Card pol = new Ui.Card(new BorderLayout());
        JPanel pc = Ui.column();
        pc.add(Ui.label("Mobilization", 13.5f, Ui.TEXT));
        JComboBox<String> mob = new JComboBox<>(Budget.MOBILIZATION);
        mob.setFont(Fonts.ui(13f));
        mob.setAlignmentX(Component.LEFT_ALIGNMENT);
        mob.addActionListener(e -> { if (!updating) { b().mobilization = mob.getSelectedIndex(); changed(); } });
        pc.add(mob);
        JLabel mobInfo = Ui.label(" ", 12f, Ui.MUTED);
        pc.add(mobInfo);
        pc.add(Ui.gap(0, 8));
        JLabel focus = Ui.label(" ", 13.5f, Ui.TEXT);
        pc.add(focus);
        JSlider fs = new JSlider(-100, 100, 0);
        fs.setOpaque(false);
        fs.setAlignmentX(Component.LEFT_ALIGNMENT);
        fs.addChangeListener(e -> { if (!updating) { b().industryFocus = fs.getValue() / 100.0; changed(); } });
        pc.add(fs);
        JLabel focusInfo = Ui.label(" ", 12f, Ui.MUTED);
        pc.add(focusInfo);
        pc.add(Ui.gap(0, 6));
        JCheckBox ration = check("Fuel rationing (oil -30%, speed -7%)", () -> b().fuelRationing = !b().fuelRationing);
        JCheckBox print = check("Print money (+30% revenue, inflation)", () -> b().printMoney = !b().printMoney);
        pc.add(ration); pc.add(print);
        pol.add(pc, BorderLayout.CENTER);
        c.add(pol);
        refreshers.add(() -> {
            mob.setSelectedIndex(b().mobilization);
            int m = b().mobilization;
            mobInfo.setText(String.format("Manpower x%.2f  -  GDP %+.0f%%  -  stability %+d", Budget.MOB_MANPOWER[m], (Budget.MOB_GDP[m] - 1) * 100, -(int) Budget.MOB_STABILITY[m]));
            fs.setValue((int) Math.round(b().industryFocus * 100));
            double f = b().industryFocus;
            focus.setText("Industry focus:  " + (f < -0.05 ? "civilian" : f > 0.05 ? "military" : "balanced"));
            focusInfo.setText(String.format("Production slots %d  -  GDP %+.1f%%", gs.productionCapacity(gs.player),
                    (-0.05 * Math.max(0, f) + 0.03 * Math.max(0, -f)) * 100));
            ration.setSelected(b().fuelRationing);
            print.setSelected(b().printMoney);
        });

        // ---- ledger
        c.add(Ui.section("Ledger  -  per day"));
        Ui.Card ledgerCard = new Ui.Card(new BorderLayout());
        JPanel lc = Ui.column();
        String[] names = {"Revenue", "Army upkeep", "Development", "Infrastructure", "Education", "Welfare", "Security", "Military funding", "Trade deals", "Net"};
        JLabel[] vals = new JLabel[names.length];
        for (int i = 0; i < names.length; i++) {
            vals[i] = valueLabel();
            JPanel r = Ui.row(Ui.label(names[i], i == names.length - 1 ? 14f : 13f, i == names.length - 1 ? Ui.TEXT : Ui.MUTED), Box.createHorizontalGlue(), vals[i]);
            r.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
            lc.add(r);
        }
        ledgerCard.add(lc, BorderLayout.CENTER);
        c.add(ledgerCard);
        refreshers.add(() -> {
            Budget.Ledger l = led;
            double[] v = {l.revenue(), -l.upkeep(), -l.dev(), -l.infra(), -l.edu(), -l.welfare(), -l.security(), -l.military(), l.deals(), l.net()};
            for (int i = 0; i < v.length; i++) {
                vals[i].setText(String.format("%+.2f", v[i]));
                vals[i].setForeground(i == 0 || v[i] > 0 ? Ui.GREEN : v[i] < 0 ? Ui.RED : Ui.MUTED);
            }
        });

        // ---- build economy
        c.add(Ui.section("Build economy"));
        Ui.Card bc = new Ui.Card(new BorderLayout());
        JPanel bcol = Ui.column();
        bcol.add(Ui.label("Start construction in your richest provinces:", 12.5f, Ui.MUTED));
        JComboBox<Building> type = new JComboBox<>(Building.values());
        type.setFont(Fonts.ui(13f));
        type.setAlignmentX(Component.LEFT_ALIGNMENT);
        type.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean sel, boolean f) {
                Building x = (Building) v;
                return super.getListCellRendererComponent(l, x.label + "  -  from " + (int) x.cost + " money  (" + x.effect + ")", i, sel, f);
            }
        });
        JSpinner count = new JSpinner(new SpinnerNumberModel(3, 1, 30, 1));
        JLabel info = Ui.label(" ", 12f, Ui.MUTED);
        JButton go = Ui.button("Build", "industry", () -> {
            SidePanels.message(this, gs.autoBuild(gs.player, (Building) type.getSelectedItem(), (Integer) count.getValue()));
            refreshAll.run();
            update();
        });
        bcol.add(type);
        bcol.add(Ui.gap(0, 5));
        bcol.add(Ui.row(Ui.label("How many  ", 13f, Ui.TEXT), count, Ui.gap(10, 0), go));
        bcol.add(Ui.gap(0, 5));
        bcol.add(info);
        bc.add(bcol, BorderLayout.CENTER);
        c.add(bc);
        refreshers.add(() -> {
            int[] tot = new int[Building.values().length];
            for (int p = 0; p < gs.P; p++) if (gs.owner[p] == gs.player) for (Building x : Building.values()) tot[x.ordinal()] += gs.level(x, p);
            StringBuilder sb = new StringBuilder("<html>Levels owned:  ");
            for (Building x : Building.values()) sb.append(x.label.split(" ")[0]).append(' ').append(tot[x.ordinal()]).append("   ");
            sb.append("<br>Under construction: ").append(s().constructions.size()).append("   -   Treasury ").append((int) s().money).append("</html>");
            info.setText(sb.toString());
        });
    }

    // ------------------------------------------------------------------ pieces
    private void apply(Budget x) {
        Budget cur = b();
        cur.tax = x.tax; cur.dev = x.dev; cur.infra = x.infra; cur.edu = x.edu; cur.welfare = x.welfare; cur.security = x.security; cur.military = x.military;
        cur.mobilization = x.mobilization; cur.industryFocus = x.industryFocus; cur.fuelRationing = x.fuelRationing; cur.printMoney = x.printMoney;
        changed();
    }

    private JButton preset(String name, Runnable r) {
        JButton bt = Ui.button(name, null, r);
        bt.setAlignmentX(Component.LEFT_ALIGNMENT);
        return bt;
    }

    private JCheckBox check(String text, Runnable toggle) {
        JCheckBox c = new JCheckBox(text);
        c.setOpaque(false);
        c.setFont(Fonts.ui(12.5f));
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        c.addActionListener(e -> { if (!updating) { toggle.run(); changed(); } });
        return c;
    }

    private JLabel valueLabel() { JLabel l = Ui.label(" ", 13.5f, Ui.TEXT); return l; }

    private JComponent statRow(String icon, Color tint, String name, JLabel value) {
        JPanel r = Ui.row(Ui.imageLabel(Assets.icon(icon, 18, tint)), Ui.gap(8, 0), Ui.label(name, 13f, Ui.MUTED), Box.createHorizontalGlue(), value);
        r.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
        return r;
    }

    interface Effect { String text(Budget.Ledger l); }
    interface Cost { double of(Budget.Ledger l); }
    interface Getter { double get(); }

    /** A labelled slider with live effect text and the money/day it costs. */
    private JComponent slider(String name, String icon, Color tint, int min, int max, Getter get, DoubleConsumer set, Effect effect, Cost cost, String fmt) {
        Ui.Card card = new Ui.Card(new BorderLayout());
        card.setBorder(BorderFactory.createEmptyBorder(7, 10, 7, 10));
        JPanel col = Ui.column();
        JLabel value = Ui.label(" ", 14f, Ui.TEXT), money = Ui.label(" ", 12f, Ui.MUTED), eff = Ui.label(" ", 12f, Ui.MUTED);
        col.add(Ui.row(Ui.imageLabel(Assets.icon(icon, 20, tint)), Ui.gap(8, 0), Ui.label(name, 14f, Ui.TEXT), Box.createHorizontalGlue(), value));
        JSlider sl = new JSlider(min, max, (int) Math.round(get.get()));
        sl.setOpaque(false);
        sl.setAlignmentX(Component.LEFT_ALIGNMENT);
        sl.addChangeListener(e -> { if (!updating) { set.accept(sl.getValue()); changed(); } });
        col.add(sl);
        col.add(Ui.row(eff, Box.createHorizontalGlue(), money));
        card.add(col, BorderLayout.CENTER);
        refreshers.add(() -> {
            int v = (int) Math.round(get.get());
            if (sl.getValue() != v) sl.setValue(v);
            value.setText(String.format(fmt, get.get()));
            Budget.Ledger l = led;
            eff.setText(effect.text(l));
            money.setText(name.startsWith("Tax") ? "" : String.format("-%.2f/day", cost.of(l)));
        });
        JPanel wrap = Ui.column();
        wrap.add(card); wrap.add(Ui.gap(0, 5));
        return wrap;
    }
}
