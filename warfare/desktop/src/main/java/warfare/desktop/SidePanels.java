package warfare.desktop;

import warfare.core.*;
import warfare.core.NationState.AirWing;
import warfare.core.NationState.Mission;
import warfare.core.NationState.Track;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.util.*;
import java.util.List;

/** The right-hand tabs. Each panel only ever shows what the player's Intel allows. */
final class SidePanels {
    private SidePanels() {}

    static JEditorPane html() {
        JEditorPane p = new JEditorPane("text/html", "");
        p.setEditable(false);
        p.setBackground(new Color(30, 36, 48));
        return p;
    }

    static String esc(String s) { return s.replace("&", "&amp;").replace("<", "&lt;"); }
    static String doc(String body) { return "<html><body style='font-family:\""+Fonts.uiFamily()+"\",sans-serif;font-size:12px;color:#e6ebf2'>" + body + "</body></html>"; }

    static void message(Component c, GameState.Result r) {
        if (r == null) return;
        JOptionPane.showMessageDialog(c, r.message, r.ok ? "Done" : "Cannot do that", r.ok ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE);
    }

    // ================================================================== selection
    static final class Selection extends JPanel {
        final GameState gs; final MapPanel map; final Runnable refreshAll;
        final JEditorPane info = html();
        final JPanel controls = new JPanel();

        Selection(GameState gs, MapPanel map, Runnable refreshAll) {
            super(new BorderLayout());
            this.gs = gs; this.map = map; this.refreshAll = refreshAll;
            add(new JScrollPane(info), BorderLayout.CENTER);
            controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));
            add(controls, BorderLayout.SOUTH);
        }

        void update() {
            controls.removeAll();
            Army a = gs.armies.get(map.selArmy);
            Intel in = gs.intelOf(gs.player);
            StringBuilder sb = new StringBuilder();
            if (a != null && (a.owner == gs.player || (gs.isAlly(gs.player, a.owner) && in.quality[a.province] >= 2))) {
                boolean own = a.owner == gs.player;
                sb.append("<h3>").append(esc(a.name)).append(" <small>(").append(esc(gs.nationName(a.owner))).append(")</small></h3>");
                sb.append("Location: ").append(esc(gs.prov(a.province).name)).append(" - ").append(gs.prov(a.province).terrain).append("<br>");
                for (UnitType t : UnitType.values()) if (a.units[t.ordinal()] >= 0.5) sb.append(esc(t.label)).append(": ").append((int) Math.round(a.units[t.ordinal()])).append("<br>");
                sb.append("Morale: ").append((int) a.morale).append("  |  Speed: ").append((int) a.speed()).append(" km/day<br>");
                if (a.battleId >= 0) sb.append("<b style='color:#ff6b5e'>IN BATTLE</b><br>");
                if (!a.path.isEmpty()) sb.append("En route: ").append(a.path.size()).append(" provinces<br>");
                if (own) { sb.append("<br><b>Orders:</b> left-click a province to march there."); armyControls(a); }
            } else if (a != null) {
                Intel.Sighting s = in.sightingOf(a.id);
                sb.append("<h3>Foreign force</h3>");
                if (s == null) sb.append("No current intelligence on this force.");
                else {
                    int age = gs.day - s.day;
                    sb.append("Owner: ").append(s.ownerKnown ? esc(gs.nationName(s.owner)) : "<i>unidentified</i>").append("<br>");
                    sb.append("Last seen in ").append(esc(gs.prov(s.province).name)).append(age == 0 ? " (visible now)" : " (" + age + " days ago)").append("<br>");
                    sb.append("Reliability: ").append((int) (100 * Intel.confidence(age))).append("%<br>");
                    if (s.composition != null) for (UnitType t : UnitType.values()) { if (s.composition[t.ordinal()] >= 0.5) sb.append(esc(t.label)).append(": ").append((int) Math.round(s.composition[t.ordinal()])).append("<br>"); }
                    else if (s.quality == 2) sb.append("Estimated strength: about ").append((int) Math.max(1, Math.round(s.strengthEstimate / 11.0))).append(" battalions (composition unknown)<br>");
                    else sb.append("A contact only - size and nationality unknown.<br>");
                    sb.append("<i>Place recon, aircraft, radar or an agent network to learn more.</i>");
                }
            } else if (map.selProv >= 0) provinceInfo(sb, in);
            else sb.append("Select an army or province. Right-click deselects.");
            info.setText(doc(sb.toString()));
            info.setCaretPosition(0);
            controls.revalidate(); controls.repaint();
        }

        void provinceInfo(StringBuilder sb, Intel in) {
            int p = map.selProv;
            WorldData.ProvinceInfo pi = gs.prov(p);
            sb.append("<h3>").append(esc(pi.name)).append("</h3>");
            sb.append("Terrain: ").append(pi.terrain).append("<br>");
            if (pi.sea) { sb.append("Sea zone. Armies need transports to cross.<br>"); return; }
            int q = in.quality[p];
            int ko = in.knownOwner[p];
            int age = gs.day - in.lastSeen[p];
            boolean own = gs.owner[p] == gs.player;
            sb.append("Owner: ").append(ko >= 0 ? esc(gs.nationName(ko)) : "unknown");
            sb.append(q > 0 ? " <span style='color:#7fe0a0'>(visible now, quality " + q + ")</span>"
                    : own ? "" : " <span style='color:#e0b070'>(last seen " + (age >= 40 ? "long ago - stale atlas data" : age + " days ago") + ")</span>").append("<br>");
            if (own) {
                sb.append("Controller: ").append(esc(gs.nationName(gs.controller[p]))).append("<br>");
                sb.append(String.format("Population: %,.0f<br>GDP: %,.0f M$/yr<br>", pi.pop * gs.devastation[p], pi.gdp * gs.devastation[p]));
                sb.append("Unrest: ").append((int) gs.unrest[p]).append("%<br>");
                sb.append("Buildings - Factory ").append(gs.factory[p]).append(", Roads ").append(gs.infra[p]).append(", Fort ").append(gs.fort[p])
                        .append(", Airfield ").append(gs.airfield[p]).append(", Radar ").append(gs.radar[p]).append(", Silo ").append(gs.silo[p]).append("<br>");
                if (gs.devastation[p] < 0.9f) sb.append("<b style='color:#ff9a5e'>Devastated: ").append((int) (gs.devastation[p] * 100)).append("% of normal output</b><br>");
                buildControls(p);
            } else if (q >= 2) sb.append("Fortification (observed): ").append(gs.fort[p]).append("<br>");
            else if (ko >= 0) sb.append("Buildings and garrison: <i>unknown</i><br>");
            List<Army> mine = new ArrayList<>();
            for (Army a : gs.armies.values()) if (a.province == p && (a.owner == gs.player || (gs.isAlly(gs.player, a.owner) && q >= 2))) mine.add(a);
            for (Army a : mine) sb.append("Army: ").append(esc(a.name)).append(" (").append((int) a.totalUnits()).append(")<br>");
        }

        void buildControls(int p) {
            JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
            JComboBox<Building> box = new JComboBox<>(Building.values());
            box.setRenderer(new DefaultListCellRenderer() {
                @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean s, boolean f) {
                    Building b = (Building) v;
                    return super.getListCellRendererComponent(l, b.label + " (" + (int) (b.cost * (1 + gs.level(b, p))) + ")", i, s, f);
                }
            });
            JButton go = new JButton("Build");
            go.addActionListener(e -> { message(this, gs.build(gs.player, p, (Building) box.getSelectedItem())); refreshAll.run(); });
            row.add(box); row.add(go);
            controls.add(row);
        }

        void armyControls(Army a) {
            JPanel modeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
            modeRow.add(new JLabel("Battle orders:"));
            JButton general = new JButton("General (auto)");
            general.setToolTipText("The general picks tactic cards for you, using only what your intel shows.");
            general.setEnabled(a.mode != Army.Mode.GENERAL);
            general.addActionListener(e -> { gs.setGeneralMode(a); refreshAll.run(); });
            modeRow.add(general);
            JButton split = new JButton("Split 50%");
            split.addActionListener(e -> { message(this, gs.splitArmy(a, 0.5)); refreshAll.run(); });
            modeRow.add(split);
            controls.add(modeRow);
            JLabel hint = new JLabel(a.mode == Army.Mode.GENERAL ? "  Mode: GENERAL - tactics chosen automatically" : "  Mode: MANUAL - " + (a.tactic == null ? "choose a tactic card" : a.tactic.label));
            controls.add(hint);
            for (Tactic t : Tactic.values()) {
                JButton b = new JButton(t.label);
                b.setToolTipText("<html>" + esc(t.description) + "<br>Beats: " + beats(t) + "</html>");
                b.setAlignmentX(Component.LEFT_ALIGNMENT);
                if (a.mode == Army.Mode.MANUAL && a.tactic == t) b.setFont(b.getFont().deriveFont(Font.BOLD));
                b.addActionListener(e -> { gs.setTactic(a, t); refreshAll.run(); });
                controls.add(b);
            }
        }

        static String beats(Tactic t) {
            StringBuilder sb = new StringBuilder();
            for (Tactic o : Tactic.values()) if (t.beats(o)) sb.append(sb.length() > 0 ? ", " : "").append(o.label);
            return sb.toString();
        }
    }

    // ================================================================== production
    static final class Production extends JPanel {
        final GameState gs; final MapPanel map; final Runnable refreshAll;
        final JSpinner[] spin = new JSpinner[UnitType.N];
        final JLabel summary = new JLabel(" ");
        final JEditorPane queue = html();

        Production(GameState gs, MapPanel map, Runnable refreshAll) {
            super(new BorderLayout());
            this.gs = gs; this.map = map; this.refreshAll = refreshAll;
            JPanel top = new JPanel();
            top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
            top.add(new JLabel("<html><b>Recruit forces</b> at the selected province you own</html>"));
            for (UnitType t : UnitType.values()) {
                JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
                spin[t.ordinal()] = new JSpinner(new SpinnerNumberModel(0, 0, 12, 1));
                spin[t.ordinal()].addChangeListener(e -> sum());
                row.add(spin[t.ordinal()]);
                row.add(new JLabel(t.label + "  (" + (int) t.cost + " money, " + (int) t.manpower + " manpower)"));
                top.add(row);
            }
            top.add(summary);
            JButton go = new JButton("Recruit at selected province");
            go.addActionListener(e -> {
                if (map.selProv < 0) { message(this, new GameState.Result(false, "Select one of your provinces first.")); return; }
                double[] comp = new double[UnitType.N];
                for (int i = 0; i < comp.length; i++) comp[i] = ((Number) spin[i].getValue()).doubleValue();
                message(this, gs.recruit(gs.player, map.selProv, comp));
                refreshAll.run();
            });
            top.add(go);
            top.add(Box.createVerticalStrut(8));
            top.add(new JLabel("<html><b>Research</b> (simple upgrade tracks)</html>"));
            for (Track t : Track.values()) {
                JButton b = new JButton();
                b.setName(t.name());
                b.addActionListener(e -> { message(this, gs.research(gs.player, t)); refreshAll.run(); });
                top.add(b);
            }
            top.add(Box.createVerticalStrut(8));
            top.add(new JLabel("<html><b>Air power</b></html>"));
            JPanel air = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            JButton addWing = new JButton("New air wing at selected airfield");
            addWing.addActionListener(e -> { if (map.selProv >= 0) message(this, gs.addAirWing(gs.player, map.selProv)); refreshAll.run(); });
            air.add(addWing);
            top.add(air);
            JPanel mission = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            JComboBox<Mission> mb = new JComboBox<>(Mission.values());
            JButton assign = new JButton("Assign a free wing to selected province");
            assign.addActionListener(e -> {
                if (map.selProv < 0) return;
                AirWing pick = null;
                for (AirWing w : gs.ns[gs.player].airWings) if (w.target < 0 || w.mission != mb.getSelectedItem()) { pick = w; break; }
                if (pick == null) pick = gs.ns[gs.player].airWings.isEmpty() ? null : gs.ns[gs.player].airWings.get(0);
                if (pick == null) { message(this, new GameState.Result(false, "No air wings.")); return; }
                message(this, gs.setAirMission(gs.player, pick, (Mission) mb.getSelectedItem(), map.selProv));
                refreshAll.run();
            });
            mission.add(mb); mission.add(assign);
            top.add(mission);
            add(top, BorderLayout.NORTH);
            add(new JScrollPane(queue), BorderLayout.CENTER);
            sum();
        }

        void sum() {
            double cost = 0, men = 0;
            for (UnitType t : UnitType.values()) { double c = ((Number) spin[t.ordinal()].getValue()).doubleValue(); cost += c * t.cost; men += c * t.manpower; }
            summary.setText(String.format("  Total: %.0f money, %.0f manpower", cost, men));
        }

        void update() {
            NationState s = gs.ns[gs.player];
            for (Component c : ((JPanel) getComponent(0)).getComponents())
                if (c instanceof JButton b && b.getName() != null) {
                    Track t = Track.valueOf(b.getName());
                    int lvl = s.tech[t.ordinal()];
                    b.setText(t.label + " - level " + lvl + (lvl >= 5 ? " (max)" : "  ->  " + (lvl + 1) + "  (" + 60 * (lvl + 1) + " money)") + (s.researching == t ? "  [researching, " + (int) s.researchLeft + "d]" : ""));
                }
            StringBuilder sb = new StringBuilder();
            sb.append("<b>Production slots:</b> ").append(s.recruitQueue.size()).append("/").append(gs.productionCapacity(gs.player)).append("<br>");
            for (NationState.Recruit r : s.recruitQueue) sb.append("&bull; ").append(esc(gs.prov(r.province).name)).append(" - ").append(r.daysLeft).append(" days<br>");
            sb.append("<br><b>Under construction:</b><br>");
            for (NationState.Construction c : s.constructions) sb.append("&bull; ").append(c.type.label).append(" in ").append(esc(gs.prov(c.province).name)).append(" - ").append(c.daysLeft).append(" days<br>");
            sb.append("<br><b>Air wings:</b><br>");
            for (AirWing w : s.airWings) sb.append("&bull; base ").append(esc(gs.prov(w.base).name)).append(": ").append(w.mission).append(w.target >= 0 ? " over " + esc(gs.prov(w.target).name) : " (idle)").append("<br>");
            if (s.airWings.isEmpty()) sb.append("None. Build an airfield, then form a wing.<br>");
            sb.append("<br><b>Nuclear arsenal:</b> ").append(s.nukes).append(" warheads");
            queue.setText(doc(sb.toString()));
            queue.setCaretPosition(0);
        }
    }

    // ================================================================== diplomacy
    static final class Diplomacy extends JPanel {
        final GameState gs; final MapPanel map; final Runnable refreshAll;
        final List<Integer> rows = new ArrayList<>();
        final AbstractTableModel model;
        final JTable table;

        Diplomacy(GameState gs, MapPanel map, Runnable refreshAll) {
            super(new BorderLayout());
            this.gs = gs; this.map = map; this.refreshAll = refreshAll;
            model = new AbstractTableModel() {
                public int getRowCount() { return rows.size(); }
                public int getColumnCount() { return 4; }
                public String getColumnName(int c) { return new String[]{"Nation", "Bloc", "Relation", "Known pacts / strength"}[c]; }
                public Object getValueAt(int r, int c) { return cell(rows.get(r), c); }
            };
            table = new JTable(model);
            table.setAutoCreateRowSorter(true);
            add(new JScrollPane(table), BorderLayout.CENTER);
            JPanel btn = new JPanel(new GridLayout(0, 2, 3, 3));
            btn.add(action("Declare war", n -> gs.declareWar(gs.player, n)));
            btn.add(action("Offer peace", n -> gs.offerPeace(gs.player, n)));
            btn.add(action("Propose alliance", n -> gs.formAlliance(gs.player, n)));
            btn.add(action("Dissolve alliance", n -> gs.breakAlliance(gs.player, n)));
            btn.add(action("Toggle sanctions", n -> gs.toggleSanction(gs.player, n)));
            btn.add(action("Buy oil (trade deal)", n -> gs.oilDeal(n, gs.player)));
            btn.add(action("Send 30 aid", n -> gs.sendAid(gs.player, n, 30)));
            btn.add(action("Station agent (25)", n -> gs.assignAgent(gs.player, n)));
            JButton nuke = new JButton("LAUNCH NUKE at selected province");
            nuke.setForeground(new Color(200, 30, 30));
            nuke.addActionListener(e -> {
                if (map.selProv < 0) { message(this, new GameState.Result(false, "Select a target province on the map.")); return; }
                if (JOptionPane.showConfirmDialog(this, "Launch a nuclear missile at " + gs.prov(map.selProv).name + "? This cannot be undone.", "Nuclear launch", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION)
                    message(this, gs.launchNuke(gs.player, map.selProv));
                refreshAll.run();
            });
            btn.add(nuke);
            add(btn, BorderLayout.SOUTH);
            rebuild();
        }

        interface Act { GameState.Result run(int nation); }

        JButton action(String label, Act a) {
            JButton b = new JButton(label);
            b.addActionListener(e -> {
                int r = table.getSelectedRow();
                if (r < 0) { message(this, new GameState.Result(false, "Select a nation in the list.")); return; }
                int n = rows.get(table.convertRowIndexToModel(r));
                message(this, a.run(n));
                refreshAll.run();
            });
            return b;
        }

        Object cell(int n, int c) {
            int me = gs.player;
            switch (c) {
                case 0: return gs.nationName(n);
                case 1: return gs.world.nations.get(n).bloc;
                case 2: {
                    if (gs.war[me][n]) return "AT WAR";
                    if (gs.allianceKnownTo(me, me, n) && gs.ally[me][n]) return gs.secret[me][n] ? "Ally (secret pact)" : "Ally";
                    if (gs.isAlly(me, n)) return "Bloc ally";
                    if (gs.sanction[me][n]) return "Sanctioned";
                    return "-";
                }
                default: {
                    StringBuilder sb = new StringBuilder();
                    for (int o = 0; o < gs.N; o++) if (o != me && o != n && gs.ns[o].alive && gs.ally[n][o] && gs.allianceKnownTo(me, n, o)) sb.append(gs.nationTag(o)).append(' ');
                    double est = Vision.estimateStrength(gs, me, n);
                    boolean agents = gs.agentLevel(me, n) > 30;
                    return (sb.length() > 0 ? "pacts: " + sb + " | " : "") + "strength ~" + (int) (est / 11) + (agents ? "" : " (rough)") + (gs.agentLevel(me, n) > 0 ? " | agents " + (int) gs.agentLevel(me, n) + "%" : "");
                }
            }
        }

        void rebuild() {
            rows.clear();
            for (int n = 0; n < gs.N; n++) if (n != gs.player && gs.ns[n].alive) rows.add(n);
            rows.sort(Comparator.comparing(gs::nationName));
            model.fireTableDataChanged();
        }

        void update() {
            int sel = table.getSelectedRow();
            if (rows.size() != (int) java.util.stream.IntStream.range(0, gs.N).filter(n -> n != gs.player && gs.ns[n].alive).count()) rebuild();
            else model.fireTableRowsUpdated(0, Math.max(0, rows.size() - 1));
            if (sel >= 0 && sel < rows.size()) table.setRowSelectionInterval(sel, sel);
        }
    }

    // ================================================================== log
    static final class Log extends JPanel {
        final GameState gs;
        final JEditorPane view = html();
        int shownNews = -1, shownReports = -1;

        Log(GameState gs) {
            super(new BorderLayout());
            this.gs = gs;
            add(new JScrollPane(view), BorderLayout.CENTER);
        }

        void update() {
            if (shownNews == gs.news.size() && shownReports == gs.reports.size()) return;
            shownNews = gs.news.size(); shownReports = gs.reports.size();
            StringBuilder sb = new StringBuilder("<h3>Battle reports</h3>");
            for (int i = gs.reports.size() - 1; i >= Math.max(0, gs.reports.size() - 12); i--) {
                Combat.Report r = gs.reports.get(i);
                sb.append("<p><b>").append(GameState.START.plusDays(r.day)).append("</b> ").append(esc(r.text)).append("</p>");
            }
            sb.append("<h3>News</h3>");
            for (int i = gs.news.size() - 1; i >= Math.max(0, gs.news.size() - 60); i--) sb.append(esc(gs.news.get(i))).append("<br>");
            view.setText(doc(sb.toString()));
            view.setCaretPosition(0);
        }
    }
}
