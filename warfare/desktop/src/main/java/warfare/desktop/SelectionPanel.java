package warfare.desktop;

import warfare.core.*;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/** Right-hand "Selection" tab: province, own army (with tactic cards) or a foreign sighting. */
final class SelectionPanel extends JPanel {
    private static final String[] UNIT_ICON = {"infantry", "mechanized", "armor", "artillery", "anti_air", "anti_tank", "special"};
    private static final String[] TACTIC_ICON = {"t_frontal", "t_flank", "t_ambush", "t_hold", "t_depth"};

    final GameState gs;
    final MapPanel map;
    final Runnable refreshAll;
    private final JPanel content = new JPanel();
    private final JScrollPane scroll;

    SelectionPanel(GameState gs, MapPanel map, Runnable refreshAll) {
        super(new BorderLayout());
        this.gs = gs; this.map = map; this.refreshAll = refreshAll;
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBackground(Ui.PANEL);
        content.setBorder(BorderFactory.createEmptyBorder(10, 12, 12, 12));
        scroll = new JScrollPane(content);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        add(scroll, BorderLayout.CENTER);
    }

    void update() {
        int pos = scroll.getVerticalScrollBar().getValue();
        content.removeAll();
        Intel in = gs.intelOf(gs.player);
        Army a = gs.armies.get(map.selArmy);
        if (a != null && (a.owner == gs.player || (gs.isAlly(gs.player, a.owner) && in.quality[a.province] >= 2))) army(a, a.owner == gs.player);
        else if (map.selArmy >= 0 && in.sightingOf(map.selArmy) != null) sighting(in.sightingOf(map.selArmy));
        else if (map.selProv >= 0) province(in);
        else {
            content.add(Ui.title("WARFARE", 30));
            content.add(Ui.label("<html>Select a province or an army on the map.<br>Left-click an army, then left-click a province to march.<br>Right-click to inspect a province.</html>", 13f, Ui.MUTED));
        }
        content.add(Box.createVerticalGlue());
        content.revalidate(); content.repaint();
        SwingUtilities.invokeLater(() -> scroll.getVerticalScrollBar().setValue(pos));
    }

    // ------------------------------------------------------------------ header
    private JPanel header(int nation, String title, String subtitle) {
        WorldData.NationInfo ni = nation >= 0 ? gs.world.nations.get(nation) : null;
        JPanel text = Ui.column();
        text.add(Ui.title(title.toUpperCase(), 26));
        text.add(Ui.label(subtitle, 13f, Ui.MUTED));
        JPanel row = Ui.row(Ui.imageLabel(Assets.flag(ni, 56)), Ui.gap(12, 0), text);
        row.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
        return row;
    }

    private JComponent stat(String icon, Color tint, String name, String value) {
        JPanel r = Ui.row(Ui.imageLabel(Assets.icon(icon, 18, tint)), Ui.gap(8, 0), Ui.label(name, 13f, Ui.MUTED), Box.createHorizontalGlue(), Ui.label(value, 13.5f, Ui.TEXT));
        r.setBorder(BorderFactory.createEmptyBorder(3, 0, 3, 0));
        return r;
    }

    // ------------------------------------------------------------------ province
    private void province(Intel in) {
        int p = map.selProv;
        WorldData.ProvinceInfo pi = gs.prov(p);
        int q = in.quality[p], ko = in.knownOwner[p], age = gs.day - in.lastSeen[p];
        boolean own = gs.owner[p] == gs.player;
        if (pi.sea) {
            content.add(header(-1, pi.name, "Sea zone"));
            content.add(Ui.pill(q > 0 ? "VISIBLE NOW" : "NOT IN VISION", q > 0 ? Ui.GREEN : Ui.AMBER));
            content.add(Ui.gap(0, 8));
            content.add(Ui.label("<html>Open water. Armies need transports to cross it, and blockades are decided here.</html>", 13f, Ui.MUTED));
            return;
        }
        content.add(header(ko, pi.name, (ko >= 0 ? gs.nationName(ko) : "Unknown owner") + "  -  " + pi.terrain.name().charAt(0) + pi.terrain.name().substring(1).toLowerCase()));
        if (q > 0) content.add(Ui.pill("VISIBLE NOW  -  QUALITY " + q + "/3", Ui.GREEN));
        else if (own) content.add(Ui.pill("OWN TERRITORY", Ui.ACCENT));
        else content.add(Ui.pill(age >= 40 ? "STALE ATLAS DATA" : "LAST SEEN " + age + " DAYS AGO  -  " + (int) (100 * Intel.confidence(age)) + "% RELIABLE", Ui.AMBER));
        content.add(Ui.gap(0, 8));
        if (own) {
            Ui.Card c = new Ui.Card(new BorderLayout());
            JPanel col = Ui.column();
            col.add(stat("manpower", Ui.ACCENT, "Population", String.format("%,.0f", pi.pop * gs.devastation[p])));
            col.add(stat("money", Ui.GOLD, "GDP", String.format("%,.0f M$/yr", pi.gdp * gs.devastation[p])));
            col.add(stat("stability", gs.unrest[p] > 60 ? Ui.RED : Ui.GREEN, "Unrest", (int) gs.unrest[p] + "%"));
            col.add(new Ui.Bar(gs.unrest[p] / 100.0, gs.unrest[p] > 60 ? Ui.RED : Ui.AMBER));
            if (gs.devastation[p] < 0.9f) col.add(Ui.label("Devastated: " + (int) (gs.devastation[p] * 100) + "% output", 13f, Ui.RED));
            c.add(col, BorderLayout.CENTER);
            content.add(c);
            content.add(Ui.section("Build"));
            for (Building b : Building.values()) content.add(buildCard(p, b));
        } else if (q >= 2) {
            Ui.Card c = new Ui.Card(new BorderLayout());
            c.add(stat("wall", Ui.TEXT, "Fortification (observed)", String.valueOf(gs.fort[p])), BorderLayout.CENTER);
            content.add(c);
        } else {
            Ui.Card c = new Ui.Card(new BorderLayout());
            c.add(Ui.label("<html>Buildings, garrison and population here are <b>unknown</b>.<br>Move units, aircraft or an agent network close to find out.</html>", 13f, Ui.MUTED), BorderLayout.CENTER);
            content.add(c);
        }
        List<Army> mine = new ArrayList<>();
        for (Army a : gs.armies.values()) if (a.province == p && (a.owner == gs.player || (gs.isAlly(gs.player, a.owner) && q >= 2))) mine.add(a);
        if (!mine.isEmpty()) {
            content.add(Ui.section("Forces here"));
            for (Army a : mine) {
                Ui.Card c = new Ui.Card(new BorderLayout());
                c.add(Ui.row(Ui.imageLabel(Assets.flag(gs.world.nations.get(a.owner), 30)), Ui.gap(8, 0), Ui.label(a.name, 14f, Ui.TEXT),
                        Box.createHorizontalGlue(), Ui.label((int) a.totalUnits() + " bns", 13.5f, Ui.MUTED)), BorderLayout.CENTER);
                c.onClick(() -> { map.selArmy = a.id; refreshAll.run(); });
                content.add(c); content.add(Ui.gap(0, 4));
            }
        }
    }

    private JComponent buildCard(int p, Building b) {
        int lvl = gs.level(b, p);
        String icon = switch (b) { case FACTORY -> "industry"; case INFRASTRUCTURE -> "time"; case FORTIFICATION -> "wall"; case AIRFIELD -> "air"; case RADAR -> "radar"; case SILO -> "nuke"; };
        double cost = b.cost * (1 + lvl);
        boolean maxed = lvl >= b.maxLevel;
        Ui.Card c = new Ui.Card(new BorderLayout());
        c.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
        String pips = "Lv " + lvl + "/" + b.maxLevel;
        JPanel text = Ui.column();
        text.add(Ui.label(b.label, 14f, Ui.TEXT));
        text.add(Ui.label(b.effect, 12f, Ui.MUTED));
        c.add(Ui.row(Ui.imageLabel(Assets.icon(icon, 24, maxed ? Ui.GREEN : Ui.ACCENT)), Ui.gap(10, 0), text, Box.createHorizontalGlue(),
                Ui.label(maxed ? "MAX  " + pips : (int) cost + "  " + pips, 13.5f, maxed ? Ui.GREEN : Ui.GOLD)), BorderLayout.CENTER);
        if (!maxed) c.onClick(() -> { SidePanels.message(this, gs.build(gs.player, p, b)); refreshAll.run(); });
        JPanel wrap = Ui.column();
        wrap.add(c); wrap.add(Ui.gap(0, 4));
        return wrap;
    }

    // ------------------------------------------------------------------ army
    private void army(Army a, boolean own) {
        content.add(header(a.owner, a.name, gs.nationName(a.owner) + "  -  " + gs.prov(a.province).name));
        if (a.battleId >= 0) content.add(Ui.pill("IN BATTLE", Ui.RED));
        else if (!a.path.isEmpty()) content.add(Ui.pill("MARCHING  -  " + a.path.size() + " PROVINCES", Ui.ACCENT));
        else content.add(Ui.pill(a.embarked ? "AT SEA" : "HOLDING POSITION", Ui.MUTED));
        content.add(Ui.gap(0, 8));
        Ui.Card c = new Ui.Card(new BorderLayout());
        JPanel col = Ui.column();
        col.add(stat("battle", Ui.TEXT, "Battalions", String.valueOf((int) Math.round(a.totalUnits()))));
        col.add(stat("general", a.morale < 35 ? Ui.RED : Ui.GREEN, "Morale", (int) a.morale + "%"));
        col.add(new Ui.Bar(a.morale / 100.0, a.morale < 35 ? Ui.RED : Ui.GREEN));
        col.add(stat("time", Ui.MUTED, "March speed", (int) a.speed() + " km/day"));
        c.add(col, BorderLayout.CENTER);
        content.add(c);
        content.add(Ui.section("Composition"));
        double total = Math.max(1, a.totalUnits());
        Ui.Card uc = new Ui.Card(new BorderLayout());
        JPanel ucol = Ui.column();
        for (UnitType t : UnitType.values()) {
            double n = a.units[t.ordinal()];
            if (n < 0.5) continue;
            ucol.add(Ui.row(Ui.imageLabel(Assets.icon(UNIT_ICON[t.ordinal()], 22, Ui.TEXT)), Ui.gap(8, 0), Ui.label(t.label, 13.5f, Ui.TEXT),
                    Box.createHorizontalGlue(), Ui.label(String.valueOf((int) Math.round(n)), 14f, Ui.GOLD)));
            ucol.add(new Ui.Bar(n / total, Ui.ACCENT));
            ucol.add(Ui.gap(0, 3));
        }
        uc.add(ucol, BorderLayout.CENTER);
        content.add(uc);
        if (!own) return;
        content.add(Ui.section("Battle orders"));
        content.add(tacticCard("general", "General (auto)", "The general picks a tactic each day, using only what your intelligence shows.", null, a.mode == Army.Mode.GENERAL, () -> gs.setGeneralMode(a)));
        for (Tactic t : Tactic.values()) {
            StringBuilder beats = new StringBuilder();
            for (Tactic o : Tactic.values()) if (t.beats(o)) beats.append(beats.length() > 0 ? ", " : "").append(o.label.split(" /")[0]);
            content.add(tacticCard(TACTIC_ICON[t.ordinal()], t.label, t.description, "Counters: " + beats, a.mode == Army.Mode.MANUAL && a.tactic == t, () -> gs.setTactic(a, t)));
        }
        content.add(Ui.gap(0, 6));
        JPanel actions = Ui.row(Ui.button("Split army in half", "split", () -> { SidePanels.message(this, gs.splitArmy(a, 0.5)); refreshAll.run(); }));
        content.add(actions);
        content.add(Ui.gap(0, 6));
        content.add(Ui.label("<html>Left-click any province to march there.</html>", 12.5f, Ui.MUTED));
    }

    private JComponent tacticCard(String icon, String name, String desc, String beats, boolean active, Runnable r) {
        Ui.Card c = new Ui.Card(new BorderLayout());
        c.setBorder(BorderFactory.createEmptyBorder(7, 10, 7, 10));
        if (active) { c.edge = new Color(Ui.ACCENT.getRed(), Ui.ACCENT.getGreen(), Ui.ACCENT.getBlue(), 230); c.fill = new Color(36, 58, 94); }
        JPanel text = Ui.column();
        text.add(Ui.label(name + (active ? "   - ACTIVE" : ""), 14.5f, active ? Ui.ACCENT : Ui.TEXT));
        text.add(Ui.label("<html><div style='width:215px'>" + SidePanels.esc(desc) + "</div></html>", 12f, Ui.MUTED));
        if (beats != null) text.add(Ui.label(beats, 12f, Ui.GREEN));
        c.add(Ui.row(Ui.imageLabel(Assets.icon(icon, 30, active ? Ui.ACCENT : Ui.TEXT)), Ui.gap(10, 0), text), BorderLayout.CENTER);
        c.onClick(() -> { r.run(); refreshAll.run(); });
        JPanel wrap = Ui.column();
        wrap.add(c); wrap.add(Ui.gap(0, 5));
        return wrap;
    }

    // ------------------------------------------------------------------ foreign force
    private void sighting(Intel.Sighting s) {
        int age = gs.day - s.day;
        content.add(header(s.ownerKnown ? s.owner : -1, s.ownerKnown ? gs.nationName(s.owner) : "Unidentified force", "Last seen in " + gs.prov(s.province).name));
        content.add(Ui.pill(age == 0 ? "VISIBLE NOW" : age + " DAYS OLD  -  " + (int) (100 * Intel.confidence(age)) + "% RELIABLE", age == 0 ? Ui.GREEN : Ui.AMBER));
        content.add(Ui.gap(0, 8));
        Ui.Card c = new Ui.Card(new BorderLayout());
        JPanel col = Ui.column();
        if (s.composition != null) {
            for (UnitType t : UnitType.values()) {
                double n = s.composition[t.ordinal()];
                if (n < 0.5) continue;
                col.add(Ui.row(Ui.imageLabel(Assets.icon(UNIT_ICON[t.ordinal()], 22, Ui.TEXT)), Ui.gap(8, 0), Ui.label(t.label, 13.5f, Ui.TEXT),
                        Box.createHorizontalGlue(), Ui.label(String.valueOf((int) Math.round(n)), 14f, Ui.GOLD)));
                col.add(Ui.gap(0, 4));
            }
        } else if (s.quality == 2) col.add(Ui.label("<html>Estimated strength: about <b>" + (int) Math.max(1, Math.round(s.strengthEstimate / 11.0)) + "</b> battalions.<br>Composition unknown.</html>", 13.5f, Ui.TEXT));
        else col.add(Ui.label("A contact only. Size and nationality unknown.", 13.5f, Ui.TEXT));
        c.add(col, BorderLayout.CENTER);
        content.add(c);
        content.add(Ui.gap(0, 8));
        content.add(Ui.label("<html>Recon units, aircraft, radar, satellites or an agent network reveal more.</html>", 12.5f, Ui.MUTED));
    }
}
