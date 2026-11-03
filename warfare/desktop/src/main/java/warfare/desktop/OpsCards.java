package warfare.desktop;

import warfare.core.GameState;
import warfare.core.Ops;

import javax.swing.*;
import java.awt.*;

/** The covert-operations card for one target nation: what each operation costs, how likely it is to work and to be traced. */
final class OpsCards {
    private OpsCards() {}

    private static final String[] ICONS = {"radar", "tab_reports", "battle", "special", "research"};

    static void open(GameUi ui, int target) {
        GameState gs = ui.gs;
        int me = gs.player;
        if (target < 0 || target == me) { GameUi.notify(new GameState.Result(false, "Select a nation in the list first.")); return; }
        double net = gs.ns[me].agents.getOrDefault(target, 0.0);
        JPanel col = Ui.column();
        col.setBorder(BorderFactory.createEmptyBorder(6, 18, 18, 18));
        JPanel head = Ui.row(Ui.imageLabel(Assets.flag(gs.world.nations.get(target), 36)), Ui.gap(10, 0),
                Ui.label(gs.nationName(target), 18f, Ui.TEXT), Box.createHorizontalGlue(),
                Ui.label("Your network there: " + (int) net + " / 100", 13f, net >= 10 ? Ui.GREEN : Ui.AMBER));
        col.add(head);
        col.add(Ui.gap(0, 4));
        col.add(Ui.label("<html><div style='width:470px'>Operations can be traced to you, pass unnoticed, or throw suspicion on someone else. Failure costs half your network.</div></html>", 12.5f, Ui.MUTED));
        col.add(Ui.gap(0, 8));
        Wf.Button agent = Wf.button("Station an agent (25 money, +5 network)", "spy", () -> { GameUi.notify(gs.assignAgent(me, target)); reopen(ui, target); });
        agent.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        col.add(agent);
        col.add(Ui.gap(0, 10));
        int i = 0;
        for (Ops.Op op : Ops.Op.values()) {
            Ops.Estimate e = gs.opsEstimate(me, target, op);
            Ui.Card c = new Ui.Card(new BorderLayout(12, 0));
            c.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
            c.add(Ui.imageLabel(Assets.icon(ICONS[i++], 30, e.available() ? Skin.BRASS : Ui.MUTED)), BorderLayout.WEST);
            JPanel mid = Ui.column();
            mid.add(Ui.label(op.label, 15f, Ui.TEXT));
            mid.add(Ui.label("<html><div style='width:240px'>" + SidePanels.esc(op.hint) + "</div></html>", 12f, Ui.MUTED));
            c.add(mid, BorderLayout.CENTER);
            JPanel right = Ui.column();
            right.setPreferredSize(new Dimension(150, 62));
            if (e.available()) {
                String chance = net >= 25 ? Math.round(e.success() * 100) + "%" : Math.max(5, Math.round(e.success() * 100) - 15) + "-" + Math.min(95, Math.round(e.success() * 100) + 15) + "%";
                right.add(Ui.label("Success " + chance, 12.5f, e.success() >= 0.6 ? Ui.GREEN : e.success() >= 0.4 ? Ui.AMBER : Ui.RED));
                right.add(Ui.label("Traced: " + (e.trace() < 0.3 ? "unlikely" : e.trace() < 0.55 ? "possible" : "likely"), 12f, Ui.MUTED));
                right.add(Ui.gap(0, 4));
                Wf.Button run = Wf.primary("Run (" + op.cost + ")", null, () -> { GameUi.notify(gs.runOp(me, target, op)); reopen(ui, target); });
                run.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
                right.add(run);
            } else {
                right.add(Ui.label("<html><div style='width:140px'>" + SidePanels.esc(e.why()) + "</div></html>", 12f, Ui.MUTED));
            }
            c.add(right, BorderLayout.EAST);
            col.add(c);
            col.add(Ui.gap(0, 6));
        }
        ui.modals.push(Modals.card("Covert operations", "spy", col, 640, 0, () -> ui.modals.pop()));
    }

    private static void reopen(GameUi ui, int target) {
        ui.modals.pop();
        ui.map.refreshColors();
        ui.refresh();
        open(ui, target);
    }
}
