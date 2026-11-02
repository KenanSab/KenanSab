package warfare.desktop;

import warfare.core.Achievements;

import javax.swing.*;
import java.awt.*;

/** Lists every achievement with locked/unlocked state. */
final class AchievementsDialog {
    private AchievementsDialog() {}

    static void show(JFrame owner) {
        JDialog d = new JDialog(owner, "Achievements", true);
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(Ui.PANEL);
        p.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
        p.add(Ui.title("ACHIEVEMENTS   " + Profile.unlocked.size() + " / " + Achievements.ALL.size(), 28));
        p.add(Ui.gap(0, 8));
        for (Achievements.Def a : Achievements.ALL) {
            boolean got = Profile.unlocked.contains(a.id());
            Ui.Card c = new Ui.Card(new BorderLayout());
            if (got) c.edge = new Color(236, 192, 92, 170);
            JPanel text = Ui.column();
            text.add(Ui.label(a.title(), 14.5f, got ? Ui.TEXT : Ui.MUTED));
            text.add(Ui.label(a.description(), 12.5f, Ui.MUTED));
            c.add(Ui.row(Ui.imageLabel(Assets.icon(a.icon(), 30, got ? Ui.GOLD : new Color(90, 100, 120))), Ui.gap(12, 0), text), BorderLayout.CENTER);
            p.add(c);
            p.add(Ui.gap(0, 5));
        }
        JScrollPane sp = new JScrollPane(p);
        sp.setBorder(null);
        sp.getVerticalScrollBar().setUnitIncrement(18);
        d.setContentPane(sp);
        d.setSize(520, 640);
        d.setLocationRelativeTo(owner);
        d.setVisible(true);
    }
}
