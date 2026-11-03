package warfare.desktop;

import warfare.core.Tactic;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * A hand of tactic cards: five tactics plus "let the General decide". Cards lift when hovered, show what they beat and
 * what beats them, and the advisor's pick (made from what the army can actually see) wears a ribbon.
 */
final class TacticHand extends JComponent {
    private static final String[] ICON = {"t_frontal", "t_flank", "t_ambush", "t_hold", "t_depth"};
    private final Tactic suggested;
    private final IntConsumer pick;
    private int hover = -1;
    private final float[] lift = new float[6];
    private final javax.swing.Timer anim;

    TacticHand(Tactic suggested, IntConsumer pick) {
        this.suggested = suggested; this.pick = pick;
        setPreferredSize(new Dimension(980, 330));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        anim = new javax.swing.Timer(16, e -> {
            boolean moving = false;
            for (int i = 0; i < lift.length; i++) {
                float target = i == hover ? 1f : 0f;
                float next = lift[i] + (target - lift[i]) * 0.3f;
                if (Math.abs(next - target) < 0.01f) next = target;
                if (next != lift[i]) moving = true;
                lift[i] = next;
            }
            repaint();
            if (!moving) ((javax.swing.Timer) e.getSource()).stop();
        });
        addMouseMotionListener(new MouseAdapter() {
            @Override public void mouseMoved(MouseEvent e) { int i = cardAt(e.getPoint()); if (i != hover) { hover = i; anim.start(); } }
        });
        addMouseListener(new MouseAdapter() {
            @Override public void mouseExited(MouseEvent e) { hover = -1; anim.start(); }
            @Override public void mouseClicked(MouseEvent e) { int i = cardAt(e.getPoint()); if (i >= 0) { Audio.get().play(Synth.Sfx.CONFIRM); pick.accept(i); } }
        });
    }

    /** Plays card {@code i} (0-4 tactics, 5 the General) exactly as a click would. */
    void play(int i) { pick.accept(i); }

    private int cardW() { return Math.max(118, Math.min(150, (getWidth() - 5 * 12) / 6)); }

    private Rectangle cardRect(int i) {
        int cw = cardW(), total = cw * 6 + 12 * 5, x0 = (getWidth() - total) / 2;
        return new Rectangle(x0 + i * (cw + 12), 18, cw, getHeight() - 30);
    }

    private int cardAt(Point p) {
        for (int i = 0; i < 6; i++) if (cardRect(i).contains(p.x, p.y + (int) (lift[i] * 10))) return i;
        return -1;
    }

    private static String shortName(Tactic t) { return t.label.split(" /")[0]; }

    private static List<String> wrap(String text, FontMetrics fm, int width) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (fm.stringWidth(line + " " + word) > width && line.length() > 0) { out.add(line.toString()); line = new StringBuilder(); }
            if (line.length() > 0) line.append(' ');
            line.append(word);
        }
        if (line.length() > 0) out.add(line.toString());
        return out;
    }

    @Override protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        Wf.quality(g);
        for (int i = 0; i < 6; i++) {
            Rectangle r = cardRect(i);
            double up = lift[i] * 10;
            g.translate(0, -up);
            boolean general = i == 5;
            Tactic t = general ? null : Tactic.values()[i];
            boolean hot = lift[i] > 0.5f;
            Skin.shadow(g, r.x, r.y, r.width, r.height, 8, 5, (int) (90 + 70 * lift[i]));
            RoundRectangle2D body = new RoundRectangle2D.Float(r.x, r.y, r.width, r.height, 8, 8);
            Skin.plate(g, body, general ? Skin.Kind.INSET : Skin.Kind.RAISED, 1f);
            Skin.frame(g, r.x + 0.5f, r.y + 0.5f, r.width - 1, r.height - 1, 8, hot || t == suggested, hot || t == suggested);
            // icon medallion
            int cx = r.x + r.width / 2;
            g.setPaint(new RadialGradientPaint(cx, r.y + 52, 38, new float[]{0f, 1f}, new Color[]{new Color(226, 187, 102, hot ? 90 : 50), new Color(226, 187, 102, 0)}));
            g.fillRect(r.x, r.y + 10, r.width, 84);
            g.drawImage(Assets.icon(general ? "general" : ICON[i], 52, hot ? Skin.BRASS_HI : Skin.BRASS), cx - 26, r.y + 26, null);
            // title
            g.setFont(Fonts.title(21f));
            FontMetrics fm = g.getFontMetrics();
            String title = (general ? "General decides" : shortName(t)).toUpperCase();
            int ty = r.y + 112;
            for (String line : wrap(title, fm, r.width - 16)) { g.setColor(Color.WHITE); g.drawString(line, cx - fm.stringWidth(line) / 2, ty); ty += 22; }
            Skin.rule(g, r.x + 14, ty - 12, r.width - 28);
            // description
            g.setFont(Fonts.ui(11.5f));
            fm = g.getFontMetrics();
            g.setColor(new Color(204, 203, 197));
            String desc = general ? "Your general picks a card each day from what the army can see." : t.description;
            int dy = ty + 6;
            int maxLines = 5;
            List<String> lines = wrap(desc, fm, r.width - 22);
            for (int k = 0; k < Math.min(maxLines, lines.size()); k++) { g.drawString(lines.get(k), r.x + 11, dy + 4); dy += 14; }
            // matchups
            if (!general) {
                g.setFont(Fonts.label(11f));
                int my = r.y + r.height - 78;
                g.setColor(Ui.GREEN);
                g.drawString("BEATS", r.x + 11, my);
                g.setFont(Fonts.ui(11.5f));
                g.setColor(new Color(214, 232, 220));
                StringBuilder beats = new StringBuilder(), weak = new StringBuilder();
                for (Tactic o : Tactic.values()) { if (t.beats(o)) beats.append(beats.length() > 0 ? ", " : "").append(shortName(o)); else if (o != t) weak.append(weak.length() > 0 ? ", " : "").append(shortName(o)); }
                int yy = my + 14;
                for (String l : wrap(beats.toString(), g.getFontMetrics(), r.width - 20)) { g.drawString(l, r.x + 11, yy); yy += 13; }
                g.setFont(Fonts.label(11f));
                g.setColor(Ui.RED);
                g.drawString("LOSES TO", r.x + 11, yy + 3);
                g.setFont(Fonts.ui(11.5f));
                g.setColor(new Color(236, 214, 210));
                yy += 17;
                for (String l : wrap(weak.toString(), g.getFontMetrics(), r.width - 20)) { g.drawString(l, r.x + 11, yy); yy += 13; }
            }
            // advisor's ribbon
            if (t != null && t == suggested) {
                g.setPaint(new GradientPaint(0, r.y, new Color(243, 212, 132), 0, r.y + 18, new Color(201, 158, 66)));
                g.fill(new RoundRectangle2D.Float(r.x + r.width - 74, r.y - 8, 66, 20, 6, 6));
                g.setFont(Fonts.label(11.5f));
                g.setColor(Wf.INK);
                g.drawString("ADVISOR", r.x + r.width - 74 + (66 - g.getFontMetrics().stringWidth("ADVISOR")) / 2, r.y + 6);
            }
            g.translate(0, up);
        }
        g.dispose();
    }
}
