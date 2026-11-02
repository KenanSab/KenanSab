package warfare.desktop;

import warfare.core.GameState;
import warfare.core.NationState;
import warfare.core.Politics;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.util.function.IntConsumer;

/** The floating heads-up display: nation medallion, resource plates, and the time controls. */
final class Hud {
    final Badge badge;
    final JPanel plates = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
    final JPanel time = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
    private final GameState gs;
    private final SpeedControl speed;
    private final Chip money = new Chip("money", Ui.GOLD, "Money: treasury and income per day"),
            men = new Chip("manpower", Ui.ACCENT, "Manpower: thousands of recruits available"),
            ind = new Chip("industry", Ui.TEXT, "Production slots in use"),
            oil = new Chip("oil", new Color(200, 160, 120), "Oil stock (armor, mechanized units and aircraft burn it)"),
            stab = new Chip("stability", Ui.GREEN, "Stability. Low stability brings coups, unrest and weak morale"),
            appr = new Chip("general", Ui.GOLD, "Government approval (click for the government screen)"),
            nuke = new Chip("nuke", Ui.RED, "Nuclear warheads");

    Hud(GameState gs, Runnable togglePause, IntConsumer setSpeed, Runnable openMenu, Runnable openCountry) {
        this.gs = gs;
        badge = new Badge(gs, openCountry);
        plates.setOpaque(false);
        for (Chip c : new Chip[]{money, men, ind, oil, stab, appr, nuke}) plates.add(c);
        appr.addMouseListener(new MouseAdapter() { @Override public void mouseClicked(MouseEvent e) { openCountry.run(); } });
        speed = new SpeedControl(togglePause, setSpeed);
        time.setOpaque(false);
        time.add(speed);
        time.add(new MenuButton(openMenu));
    }

    void update(boolean paused, int level) {
        NationState s = gs.ns[gs.player];
        badge.update();
        speed.set(paused, level);
        money.set(String.format("%.0f", s.money), String.format("%+.1f", s.lastIncome), s.lastIncome < 0 ? Ui.RED : Ui.GREEN);
        men.set(String.format("%.0f", s.manpower), null, null);
        ind.set(String.valueOf(gs.productionCapacity(gs.player)), s.recruitQueue.size() + " busy", Ui.MUTED);
        oil.set(String.format("%.0f", s.oil), String.format("%+.2f", s.lastOilDelta), s.lastOilDelta < 0 ? Ui.RED : Ui.GREEN);
        stab.set((int) s.stability + "%", s.warWeariness > 1 ? "war " + (int) s.warWeariness + "%" : null, Ui.AMBER);
        double ov = Politics.overall(s);
        appr.set((int) ov + "%", null, null);
        nuke.setVisible(s.nukes > 0);
        nuke.set(String.valueOf(s.nukes), null, null);
        plates.revalidate();
    }

    // ------------------------------------------------------------------ medallion
    static final class Badge extends JComponent {
        private final GameState gs;
        private String dateText = "";
        private boolean hover;

        Badge(GameState gs, Runnable open) {
            this.gs = gs;
            setPreferredSize(new Dimension(340, 74));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setToolTipText("Your country (C)");
            addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) { Audio.get().play(Synth.Sfx.CLICK); open.run(); }
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); }
            });
        }

        void update() {
            String d = gs.date().getDayOfMonth() + " " + gs.date().getMonth().getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH) + " " + gs.date().getYear();
            if (!d.equals(dateText)) { dateText = d; repaint(); }
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Wf.quality(g);
            int h = getHeight();
            // plate behind the text
            g.setColor(new Color(0, 0, 0, 90));
            g.fill(new RoundRectangle2D.Float(34, 14, getWidth() - 36, h - 16, 32, 32));
            g.setPaint(new GradientPaint(0, 8, new Color(51, 41, 32, 238), 0, h, new Color(27, 21, 16, 242)));
            g.fill(new RoundRectangle2D.Float(30, 10, getWidth() - 36, h - 18, 32, 32));
            g.setColor(hover ? new Color(236, 192, 92, 200) : new Color(255, 255, 255, 44));
            g.setStroke(new BasicStroke(1.3f));
            g.draw(new RoundRectangle2D.Float(30.5f, 10.5f, getWidth() - 37, h - 19, 32, 32));
            // medallion
            int d = 62, x = 4, y = (h - d) / 2;
            g.setColor(new Color(0, 0, 0, 120));
            g.fill(new Ellipse2D.Float(x + 2, y + 3, d, d));
            Shape old = g.getClip();
            g.setClip(new Ellipse2D.Float(x, y, d, d));
            Image flag = Assets.flag(gs.world.nations.get(gs.player), 96);
            g.drawImage(flag, x - 14, y - 2, 96, 72, null);
            g.setClip(old);
            g.setColor(Wf.GOLD);
            g.setStroke(new BasicStroke(3f));
            g.draw(new Ellipse2D.Float(x + 1.5f, y + 1.5f, d - 3, d - 3));
            g.setColor(new Color(22, 17, 12, 200));
            g.setStroke(new BasicStroke(1.2f));
            g.draw(new Ellipse2D.Float(x - 1, y - 1, d + 2, d + 2));
            // text
            g.setFont(Fonts.title(32f));
            g.setColor(Color.WHITE);
            g.drawString(gs.nationName(gs.player).toUpperCase(), 78, 44);
            g.setFont(Fonts.ui(13f));
            g.setColor(Ui.MUTED);
            g.drawString(dateText, 80, 62);
            g.dispose();
        }
    }

    // ------------------------------------------------------------------ resource plate
    static final class Chip extends JComponent {
        private final String icon; private final Color tint;
        private String value = "-", delta; private Color deltaColor = Ui.MUTED;

        Chip(String icon, Color tint, String tip) { this.icon = icon; this.tint = tint; setToolTipText(tip); setPreferredSize(new Dimension(84, 44)); }

        void set(String v, String d, Color dc) {
            boolean same = v.equals(value) && java.util.Objects.equals(d, delta);
            value = v; delta = d; deltaColor = dc == null ? Ui.MUTED : dc;
            if (same) return;
            FontMetrics fm = getFontMetrics(Fonts.ui(16f).deriveFont(Font.BOLD));
            int w = 48 + fm.stringWidth(v) + (d == null ? 0 : getFontMetrics(Fonts.ui(12f)).stringWidth(d) + 8);
            setPreferredSize(new Dimension(w + 8, 44));
            revalidate(); repaint();
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Wf.quality(g);
            int w = getWidth(), h = getHeight();
            g.setColor(new Color(0, 0, 0, 90));
            g.fill(new RoundRectangle2D.Float(2, 4, w - 3, h - 4, 16, 16));
            g.setPaint(new GradientPaint(0, 0, new Color(51, 41, 32, 238), 0, h, new Color(27, 21, 16, 242)));
            g.fill(new RoundRectangle2D.Float(0, 0, w - 2, h - 4, 16, 16));
            g.setColor(new Color(255, 255, 255, 40));
            g.setStroke(new BasicStroke(1.2f));
            g.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 3, h - 5, 16, 16));
            g.drawImage(Assets.icon(icon, 24, tint), 10, (h - 4 - 24) / 2, null);
            g.setFont(Fonts.ui(16f).deriveFont(Font.BOLD));
            g.setColor(Ui.TEXT);
            FontMetrics fm = g.getFontMetrics();
            int x = 42, y = (h - 4 + fm.getAscent()) / 2 - 2;
            g.drawString(value, x, y);
            if (delta != null) {
                g.setFont(Fonts.ui(12f));
                g.setColor(deltaColor);
                g.drawString(delta, x + fm.stringWidth(value) + 6, y);
            }
            g.dispose();
        }
    }

    // ------------------------------------------------------------------ time
    static final class SpeedControl extends JComponent {
        private boolean paused = true; private int level = 2;
        private final Runnable toggle; private final IntConsumer set;

        SpeedControl(Runnable toggle, IntConsumer set) {
            this.toggle = toggle; this.set = set;
            setPreferredSize(new Dimension(246, 46));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) {
                    if (e.getX() < 54) SpeedControl.this.toggle.run();
                    else { int i = (e.getX() - 62) / 36 + 1; if (i >= 1 && i <= 5) SpeedControl.this.set.accept(i); }
                }
            });
            setToolTipText("Space: pause/play   1-5: speed");
        }

        void set(boolean p, int l) { if (paused != p || level != l) { paused = p; level = l; repaint(); } }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Wf.quality(g);
            int h = getHeight(), w = getWidth(), cy = h / 2;
            g.setColor(new Color(0, 0, 0, 90));
            g.fill(new RoundRectangle2D.Float(2, 4, w - 3, h - 4, 24, 24));
            g.setPaint(new GradientPaint(0, 0, new Color(51, 41, 32, 238), 0, h, new Color(27, 21, 16, 242)));
            g.fill(new RoundRectangle2D.Float(0, 0, w - 2, h - 4, 24, 24));
            g.setColor(new Color(255, 255, 255, 40));
            g.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 3, h - 5, 24, 24));
            int bcy = (h - 4) / 2;
            g.setPaint(new GradientPaint(0, 4, paused ? new Color(244, 208, 120) : new Color(120, 180, 255), 0, h, paused ? new Color(196, 150, 54) : new Color(60, 120, 220)));
            g.fill(new Ellipse2D.Float(6, bcy - 17, 34, 34));
            g.setColor(Wf.INK);
            if (paused) { Path2D tri = new Path2D.Float(); tri.moveTo(18, bcy - 9); tri.lineTo(18, bcy + 9); tri.lineTo(32, bcy); tri.closePath(); g.fill(tri); }
            else { g.fill(new RoundRectangle2D.Float(16, bcy - 9, 5, 18, 2, 2)); g.fill(new RoundRectangle2D.Float(25, bcy - 9, 5, 18, 2, 2)); }
            for (int i = 1; i <= 5; i++) {
                int x = 62 + (i - 1) * 36;
                boolean on = !paused && i <= level, cur = i == level;
                g.setColor(on ? (cur ? Wf.GOLD : new Color(236, 192, 92, 130)) : new Color(255, 255, 255, 30));
                g.fill(new RoundRectangle2D.Float(x, bcy + 9 - (6 + i * 3), 30, 6 + i * 3, 6, 6));
            }
            g.dispose();
        }
    }

    static final class MenuButton extends JComponent {
        private boolean hover;

        MenuButton(Runnable r) {
            setPreferredSize(new Dimension(46, 46));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setToolTipText("Menu (Esc)");
            addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) { Audio.get().play(Synth.Sfx.CLICK); r.run(); }
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); }
            });
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Wf.quality(g);
            int w = getWidth(), h = getHeight();
            g.setPaint(new GradientPaint(0, 0, new Color(51, 41, 32, 238), 0, h, new Color(27, 21, 16, 242)));
            g.fill(new RoundRectangle2D.Float(0, 0, w - 2, h - 4, 16, 16));
            g.setColor(hover ? new Color(236, 192, 92, 200) : new Color(255, 255, 255, 40));
            g.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 3, h - 5, 16, 16));
            g.setColor(Ui.TEXT);
            g.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            for (int i = 0; i < 3; i++) g.drawLine(13, 12 + i * 8, w - 15, 12 + i * 8);
            g.dispose();
        }
    }
}
