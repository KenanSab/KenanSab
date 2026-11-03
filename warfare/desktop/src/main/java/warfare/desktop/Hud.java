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

/**
 * The heads-up display: one slim bar across the top of the screen with the country, the resources and the clock.
 * Graphite glass, a brass hairline underneath, no plates or ornaments.
 */
final class Hud {
    static final int BAR_H = 58;

    final TopBar bar = new TopBar();
    final Badge badge;
    final JPanel plates = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    final JPanel time = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
    private final GameState gs;
    private final SpeedControl speed;
    private final Chip money = new Chip("money", Ui.GOLD, "Money: treasury and income per day"),
            men = new Chip("manpower", new Color(176, 190, 206), "Manpower: thousands of recruits available"),
            ind = new Chip("industry", Ui.TEXT, "Production slots in use"),
            oil = new Chip("oil", new Color(206, 164, 120), "Oil stock (armor, mechanized units and aircraft burn it)"),
            stab = new Chip("stability", Ui.GREEN, "Stability. Low stability brings coups, unrest and weak morale"),
            appr = new Chip("general", Ui.GOLD, "Government approval (click for the government screen)"),
            nuke = new Chip("nuke", Ui.RED, "Nuclear warheads"),
            decide = new Chip("tab_reports", Ui.GOLD, "Decisions are waiting - click to answer them");

    Hud(GameState gs, Runnable togglePause, IntConsumer setSpeed, Runnable openMenu, Runnable openCountry, Runnable openDecisions) {
        this.gs = gs;
        badge = new Badge(gs, openCountry);
        plates.setOpaque(false);
        for (Chip c : new Chip[]{money, men, ind, oil, stab, appr, nuke, decide}) plates.add(c);
        decide.setVisible(false);
        decide.onClick(openDecisions);
        appr.onClick(openCountry);
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
        int waiting = gs.pendingEvents.size();
        decide.setVisible(waiting > 0);
        if (waiting > 0) decide.set(String.valueOf(waiting), "decide", Ui.AMBER);
        plates.revalidate();
    }

    // ------------------------------------------------------------------ the bar itself
    static final class TopBar extends JComponent {
        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Wf.quality(g);
            int w = getWidth(), h = BAR_H;
            g.setPaint(new GradientPaint(0, 0, new Color(24, 25, 28, 246), 0, h, new Color(15, 16, 18, 238)));
            g.fillRect(0, 0, w, h);
            g.setColor(new Color(255, 255, 255, 16));
            g.fillRect(0, 0, w, 1);
            // brass hairline with a soft glow beneath it
            g.setPaint(new GradientPaint(0, 0, new Color(217, 178, 94, 0), w * 0.5f, 0, new Color(217, 178, 94, 150), true));
            g.fillRect(0, h - 1, w, 1);
            g.setPaint(new GradientPaint(0, h, new Color(0, 0, 0, 120), 0, h + 10, new Color(0, 0, 0, 0)));
            g.fillRect(0, h, w, 10);
            g.dispose();
        }
        @Override public boolean contains(int x, int y) { return y < BAR_H; }   // the glow strip below does not swallow map clicks
    }

    // ------------------------------------------------------------------ country
    static final class Badge extends JComponent {
        private final GameState gs;
        private String dateText = "";
        private boolean hover;

        Badge(GameState gs, Runnable open) {
            this.gs = gs;
            setPreferredSize(new Dimension(330, BAR_H));
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
            int h = BAR_H, w = getWidth();
            if (hover) { g.setColor(new Color(255, 255, 255, 12)); g.fillRect(0, 0, w, h - 1); }
            // flag with a thin brass edge
            int fw = 52, fh = 39, fx = 14, fy = (h - fh) / 2;
            g.setColor(new Color(0, 0, 0, 120));
            g.fill(new RoundRectangle2D.Float(fx, fy + 2, fw, fh, 4, 4));
            g.drawImage(Assets.flag(gs.world.nations.get(gs.player), fw), fx, fy, null);
            g.setPaint(new GradientPaint(0, fy, new Color(255, 255, 255, 50), 0, fy + fh / 2f, new Color(255, 255, 255, 0)));
            g.fill(new RoundRectangle2D.Float(fx, fy, fw, fh / 2f, 4, 4));
            g.setColor(new Color(Skin.BRASS.getRed(), Skin.BRASS.getGreen(), Skin.BRASS.getBlue(), hover ? 230 : 150));
            g.setStroke(new BasicStroke(1.2f));
            g.draw(new RoundRectangle2D.Float(fx - 0.5f, fy - 0.5f, fw + 1, fh + 1, 4, 4));
            // name and date
            String nm = gs.nationName(gs.player).toUpperCase();
            float fs = 27f;
            Font nf = Fonts.title(fs);
            int room = w - 82 - 14;
            while (fs > 16f && g.getFontMetrics(nf).stringWidth(nm) > room) { fs -= 1f; nf = Fonts.title(fs); }
            g.setFont(nf);
            g.setColor(Color.WHITE);
            g.drawString(nm, 82, 31);
            g.setFont(Fonts.ui(12.5f));
            g.setColor(Ui.MUTED);
            g.drawString(dateText, 82, 49);
            g.setColor(new Color(255, 255, 255, 22));
            g.fillRect(w - 1, 12, 1, h - 24);
            g.dispose();
        }
    }

    // ------------------------------------------------------------------ resource readout
    static final class Chip extends JComponent {
        private final String icon; private final Color tint;
        private String value = "-", delta; private Color deltaColor = Ui.MUTED;
        private boolean hover, clickable;

        Chip(String icon, Color tint, String tip) { this.icon = icon; this.tint = tint; setToolTipText(tip); setPreferredSize(new Dimension(92, BAR_H)); }

        void onClick(Runnable r) {
            clickable = true;
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) { Audio.get().play(Synth.Sfx.CLICK); r.run(); }
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); }
            });
        }

        void set(String v, String d, Color dc) {
            boolean same = v.equals(value) && java.util.Objects.equals(d, delta);
            value = v; delta = d; deltaColor = dc == null ? Ui.MUTED : dc;
            if (same) return;
            FontMetrics fm = getFontMetrics(Fonts.ui(16f).deriveFont(Font.BOLD));
            int w = 52 + fm.stringWidth(v) + (d == null ? 0 : getFontMetrics(Fonts.ui(12f)).stringWidth(d) + 8) + 12;
            setPreferredSize(new Dimension(w, BAR_H));
            revalidate(); repaint();
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Wf.quality(g);
            int w = getWidth(), h = BAR_H;
            if (hover && clickable) { g.setColor(new Color(255, 255, 255, 14)); g.fillRect(0, 0, w, h - 1); }
            g.setColor(new Color(255, 255, 255, 18));
            g.fillRect(0, 14, 1, h - 28);
            g.drawImage(Assets.icon(icon, 24, tint), 16, (h - 24) / 2, null);
            g.setFont(Fonts.ui(16f).deriveFont(Font.BOLD));
            g.setColor(Ui.TEXT);
            FontMetrics fm = g.getFontMetrics();
            int x = 48, y = h / 2 + fm.getAscent() / 2 - 2;
            g.drawString(value, x, y);
            if (delta != null) {
                g.setFont(Fonts.ui(12f));
                g.setColor(deltaColor);
                g.drawString(delta, x + fm.stringWidth(value) + 6, y);
            }
            g.dispose();
        }
    }

    // ------------------------------------------------------------------ clock
    static final class SpeedControl extends JComponent {
        private boolean paused = true;
        private int level = 2;
        private final Runnable toggle;
        private final IntConsumer set;

        SpeedControl(Runnable toggle, IntConsumer set) {
            this.toggle = toggle; this.set = set;
            setPreferredSize(new Dimension(206, BAR_H));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) {
                    if (e.getX() < 56) SpeedControl.this.toggle.run();
                    else { int i = (e.getX() - 60) / 28 + 1; if (i >= 1 && i <= 5) SpeedControl.this.set.accept(i); }
                }
            });
            setToolTipText("Space: pause/play   1-5: speed");
        }

        void set(boolean p, int l) { if (paused != p || level != l) { paused = p; level = l; repaint(); } }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Wf.quality(g);
            int h = BAR_H, cy = h / 2;
            // play / pause disc
            Ellipse2D disc = new Ellipse2D.Float(10, cy - 19, 38, 38);
            g.setPaint(new GradientPaint(0, cy - 19, paused ? new Color(243, 212, 132) : new Color(58, 59, 64), 0, cy + 19, paused ? new Color(201, 158, 66) : new Color(36, 37, 41)));
            g.fill(disc);
            g.setColor(paused ? new Color(122, 90, 34, 220) : new Color(Skin.BRASS.getRed(), Skin.BRASS.getGreen(), Skin.BRASS.getBlue(), 170));
            g.setStroke(new BasicStroke(1.2f));
            g.draw(new Ellipse2D.Float(10.5f, cy - 18.5f, 37, 37));
            g.setColor(paused ? Wf.INK : Skin.BRASS_HI);
            if (paused) { Path2D tri = new Path2D.Float(); tri.moveTo(25, cy - 9); tri.lineTo(25, cy + 9); tri.lineTo(38, cy); tri.closePath(); g.fill(tri); }
            else { g.fill(new RoundRectangle2D.Float(22, cy - 9, 5, 18, 2, 2)); g.fill(new RoundRectangle2D.Float(31, cy - 9, 5, 18, 2, 2)); }
            // five speed notches
            for (int i = 1; i <= 5; i++) {
                int x = 62 + (i - 1) * 28, nh = 8 + i * 3;
                boolean on = !paused && i <= level, cur = !paused && i == level;
                g.setColor(on ? (cur ? Skin.BRASS_HI : new Color(Skin.BRASS.getRed(), Skin.BRASS.getGreen(), Skin.BRASS.getBlue(), 150)) : new Color(255, 255, 255, 34));
                g.fill(new RoundRectangle2D.Float(x, cy + 11 - nh, 22, nh, 4, 4));
            }
            g.dispose();
        }
    }

    static final class MenuButton extends JComponent {
        private boolean hover;

        MenuButton(Runnable r) {
            setPreferredSize(new Dimension(48, BAR_H));
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
            int w = getWidth(), h = BAR_H;
            if (hover) { g.setColor(new Color(255, 255, 255, 14)); g.fillRect(0, 0, w, h - 1); }
            g.setColor(hover ? Skin.BRASS_HI : Ui.TEXT);
            g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            for (int i = 0; i < 3; i++) g.drawLine(15, h / 2 - 8 + i * 8, w - 15, h / 2 - 8 + i * 8);
            g.dispose();
        }
    }
}
