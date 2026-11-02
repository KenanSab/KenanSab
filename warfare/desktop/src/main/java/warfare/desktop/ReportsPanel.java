package warfare.desktop;

import warfare.core.Combat;
import warfare.core.GameState;

import javax.swing.*;
import java.awt.*;

/** Battle reports as cards (flags, result, casualties, tactic cards used) followed by the news feed. */
final class ReportsPanel extends JPanel {
    private final GameState gs;
    private final JPanel content = new Ui.ScrollColumn();
    private final JScrollPane scroll;
    private int shownNews = -1, shownReports = -1;

    ReportsPanel(GameState gs) {
        super(new BorderLayout());
        this.gs = gs;
        content.setBorder(BorderFactory.createEmptyBorder(10, 12, 12, 12));
        setOpaque(false);
        scroll = Wf.scroll(content);
        add(scroll, BorderLayout.CENTER);
    }

    void update() {
        if (shownNews == gs.news.size() && shownReports == gs.reports.size()) return;
        shownNews = gs.news.size(); shownReports = gs.reports.size();
        content.removeAll();
        content.add(Ui.section("Battle reports"));
        if (gs.reports.isEmpty()) content.add(Ui.label("No battles fought yet.", 13f, Ui.MUTED));
        for (int i = gs.reports.size() - 1; i >= Math.max(0, gs.reports.size() - 12); i--) content.add(card(gs.reports.get(i)));
        content.add(Ui.section("News"));
        if (gs.news.isEmpty()) content.add(Ui.label("Quiet so far.", 13f, Ui.MUTED));
        for (int i = gs.news.size() - 1; i >= Math.max(0, gs.news.size() - 40); i--) {
            JLabel l = Ui.label("<html><div style='width:330px'>" + SidePanels.esc(gs.news.get(i)) + "</div></html>", 12.5f, gs.news.get(i).contains("WAR") || gs.news.get(i).contains("NUCLEAR") ? Ui.RED : Ui.TEXT);
            l.setBorder(BorderFactory.createEmptyBorder(3, 0, 3, 0));
            content.add(l);
        }
        content.revalidate(); content.repaint();
        SwingUtilities.invokeLater(() -> scroll.getVerticalScrollBar().setValue(0));
    }

    private JComponent card(Combat.Report r) {
        boolean won = r.winnerNation == gs.player;
        Ui.Card c = new Ui.Card(new BorderLayout());
        Color edge = won ? Ui.GREEN : Ui.RED;
        c.edge = new Color(edge.getRed(), edge.getGreen(), edge.getBlue(), 150);
        JPanel col = Ui.column();
        JPanel top = Ui.row(Ui.label(r.provinceName, 15f, Ui.TEXT), Box.createHorizontalGlue(), Ui.pill(won ? "VICTORY" : "DEFEAT", won ? Ui.GREEN : Ui.RED));
        col.add(top);
        col.add(Ui.label(GameState.START.plusDays(r.day) + "  -  " + r.days + " days", 12f, Ui.MUTED));
        col.add(Ui.gap(0, 5));
        col.add(side(r.attackerNation, "Attacker", r.attLost, r.attackerTactic));
        col.add(side(r.defenderNation, "Defender", r.defLost, r.defenderTactic));
        c.add(col, BorderLayout.CENTER);
        JPanel wrap = Ui.column();
        wrap.add(c); wrap.add(Ui.gap(0, 6));
        return wrap;
    }

    private JComponent side(int nation, String role, double lost, String tactic) {
        warfare.core.WorldData.NationInfo ni = nation >= 0 ? gs.world.nations.get(nation) : null;
        JPanel text = Ui.column();
        text.add(Ui.label(role + ": " + gs.nationName(nation), 13f, Ui.TEXT));
        text.add(Ui.label((tactic.isEmpty() ? "" : tactic + "  -  ") + "lost " + (int) lost + " HP", 12f, Ui.MUTED));
        JPanel r = Ui.row(Ui.imageLabel(Assets.flag(ni, 28)), Ui.gap(8, 0), text);
        r.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
        return r;
    }
}
