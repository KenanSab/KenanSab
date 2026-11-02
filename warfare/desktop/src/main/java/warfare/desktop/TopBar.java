package warfare.desktop;

import warfare.core.GameState;
import warfare.core.NationState;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.util.function.IntConsumer;

/** Top strip: nation flag + date, play/pause + 5 speed pips, and the resource chips. */
final class TopBar extends JPanel {
    private final GameState gs;
    private final JLabel nation = Ui.title("", 26), date = Ui.label("", 14f, Ui.MUTED);
    private final JLabel flag = new JLabel();
    private final SpeedControl speed;
    private final Chip money = new Chip("money", Ui.GOLD, "Money (treasury, income per day)"),
            men = new Chip("manpower", Ui.ACCENT, "Manpower (thousands of recruits available)"),
            ind = new Chip("industry", Ui.TEXT, "Production slots"),
            oil = new Chip("oil", new Color(200, 160, 120), "Oil stock (armor, mechanized and aircraft burn it)"),
            stab = new Chip("stability", Ui.GREEN, "Stability. Low stability causes coups, unrest and weak morale"),
            nuke = new Chip("nuke", Ui.RED, "Nuclear warheads");

    TopBar(GameState gs, Runnable togglePause, IntConsumer setSpeed) {
        super(new BorderLayout());
        this.gs = gs;
        setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));
        flag.setIcon(new ImageIcon(Assets.flag(gs.world.nations.get(gs.player), 44)));
        JPanel text = Ui.column();
        text.add(nation); text.add(date);
        JPanel left = Ui.row(flag, Ui.gap(10, 0), text);
        speed = new SpeedControl(togglePause, setSpeed);
        JPanel centre = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 6));
        centre.setOpaque(false);
        centre.add(speed);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
        right.setOpaque(false);
        for (Chip c : new Chip[]{money, men, ind, oil, stab, nuke}) right.add(c);
        add(left, BorderLayout.WEST); add(centre, BorderLayout.CENTER); add(right, BorderLayout.EAST);
        nation.setText(gs.nationName(gs.player).toUpperCase());
    }

    void update(boolean paused, int speedLevel) {
        NationState s = gs.ns[gs.player];
        date.setText(gs.date().getDayOfMonth() + " " + gs.date().getMonth().getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH) + " " + gs.date().getYear());
        speed.set(paused, speedLevel);
        money.set(String.format("%.0f", s.money), String.format("%+.1f", s.lastIncome), s.lastIncome < 0 ? Ui.RED : Ui.GREEN);
        men.set(String.format("%.0f", s.manpower), null, null);
        ind.set(String.valueOf(gs.productionCapacity(gs.player)), s.recruitQueue.size() + " busy", Ui.MUTED);
        oil.set(String.format("%.0f", s.oil), String.format("%+.2f", s.lastOilDelta), s.lastOilDelta < 0 ? Ui.RED : Ui.GREEN);
        stab.set((int) s.stability + "%", s.warWeariness > 1 ? "war " + (int) s.warWeariness + "%" : null, Ui.AMBER);
        nuke.setVisible(s.nukes > 0);
        nuke.set(String.valueOf(s.nukes), null, null);
    }

    @Override protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0;
        g.setPaint(new GradientPaint(0, 0, new Color(24, 33, 52), 0, getHeight(), new Color(14, 19, 30)));
        g.fillRect(0, 0, getWidth(), getHeight());
        g.setColor(new Color(255, 255, 255, 26));
        g.drawLine(0, getHeight() - 1, getWidth(), getHeight() - 1);
    }

    /** One resource: icon, value, small delta. */
    static final class Chip extends JComponent {
        private final String icon; private final Color tint;
        private String value = "-", delta; private Color deltaColor = Ui.MUTED;
        Chip(String icon, Color tint, String tip) { this.icon = icon; this.tint = tint; setToolTipText(tip); setPreferredSize(new Dimension(80, 38)); }
        void set(String v, String d, Color dc) {
            value = v; delta = d; deltaColor = dc == null ? Ui.MUTED : dc;
            FontMetrics fm = getFontMetrics(Fonts.ui(15f).deriveFont(Font.BOLD));
            int w = 44 + fm.stringWidth(v) + (d == null ? 0 : getFontMetrics(Fonts.ui(12f)).stringWidth(d) + 8);
            setPreferredSize(new Dimension(w + 8, 38));
            revalidate(); repaint();
        }
        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(new Color(255, 255, 255, 16));
            g.fill(new RoundRectangle2D.Float(0, 0, getWidth() - 1, getHeight() - 1, 14, 14));
            g.setColor(new Color(255, 255, 255, 24));
            g.draw(new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 2, getHeight() - 2, 14, 14));
            g.drawImage(Assets.icon(icon, 22, tint), 9, (getHeight() - 22) / 2, null);
            g.setFont(Fonts.ui(15f).deriveFont(Font.BOLD));
            g.setColor(Ui.TEXT);
            FontMetrics fm = g.getFontMetrics();
            int x = 38, y = (getHeight() + fm.getAscent()) / 2 - 3;
            g.drawString(value, x, y);
            if (delta != null) {
                g.setFont(Fonts.ui(12f));
                g.setColor(deltaColor);
                g.drawString(delta, x + fm.stringWidth(value) + 6, y);
            }
            g.dispose();
        }
    }

    /** Play/pause button and five clickable speed pips. */
    static final class SpeedControl extends JComponent {
        private boolean paused = true; private int level = 2;
        private final Runnable toggle; private final IntConsumer set;
        SpeedControl(Runnable toggle, IntConsumer set) {
            this.toggle = toggle; this.set = set;
            setPreferredSize(new Dimension(230, 38));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) {
                    if (e.getX() < 44) SpeedControl.this.toggle.run();
                    else { int i = (e.getX() - 56) / 34 + 1; if (i >= 1 && i <= 5) SpeedControl.this.set.accept(i); }
                }
            });
            setToolTipText("Space: pause/play   1-5: speed");
        }
        void set(boolean p, int l) { paused = p; level = l; repaint(); }
        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int cy = getHeight() / 2;
            g.setColor(paused ? Ui.AMBER : Ui.ACCENT);
            g.fill(new Ellipse2D.Float(2, cy - 17, 34, 34));
            g.setColor(Ui.BG);
            if (paused) {
                Path2D tri = new Path2D.Float();
                tri.moveTo(14, cy - 8); tri.lineTo(14, cy + 8); tri.lineTo(28, cy); tri.closePath();
                g.fill(tri);
            } else { g.fill(new RoundRectangle2D.Float(12, cy - 8, 5, 16, 2, 2)); g.fill(new RoundRectangle2D.Float(21, cy - 8, 5, 16, 2, 2)); }
            for (int i = 1; i <= 5; i++) {
                int x = 56 + (i - 1) * 34;
                boolean on = !paused && i <= level, cur = i == level;
                g.setColor(on ? (cur ? Ui.ACCENT : new Color(92, 162, 255, 150)) : new Color(255, 255, 255, 30));
                g.fill(new RoundRectangle2D.Float(x, cy - 5 - (i - 1) * 1.5f, 28, 10 + (i - 1) * 3, 6, 6));
                g.setColor(on ? Ui.BG : Ui.MUTED);
                g.setFont(Fonts.label(12f));
                g.drawString(String.valueOf(i), x + 11, cy + 4);
            }
            g.dispose();
        }
    }
}
