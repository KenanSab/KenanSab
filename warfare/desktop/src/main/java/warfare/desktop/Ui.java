package warfare.desktop;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;

/** Shared look: palette plus small custom components (cards, bars, chips, pills). */
final class Ui {
    private Ui() {}

    static final Color BG = new Color(29, 23, 17), PANEL = new Color(40, 31, 24), CARD = new Color(55, 44, 34), CARD_HI = new Color(73, 59, 46),
            ACCENT = new Color(226, 186, 110), GOLD = new Color(236, 192, 92), GREEN = new Color(96, 204, 134), RED = new Color(232, 90, 78),
            AMBER = new Color(240, 170, 70), TEXT = new Color(242, 236, 222), MUTED = new Color(176, 166, 150), LINE = new Color(255, 255, 255, 28);

    static JLabel label(String text, float size, Color c) {
        JLabel l = new JLabel(text);
        l.setFont(Fonts.ui(size));
        l.setForeground(c);
        return l;
    }

    static JLabel title(String text, float size) {
        JLabel l = new JLabel(text);
        l.setFont(Fonts.title(size));
        l.setForeground(TEXT);
        return l;
    }

    static JLabel section(String text) {
        JLabel l = new JLabel(text.toUpperCase());
        l.setFont(Fonts.label(13f));
        l.setForeground(MUTED);
        l.setBorder(BorderFactory.createEmptyBorder(10, 2, 4, 0));
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    /** Rounded card; optional click action and highlight. */
    static class Card extends JPanel {
        Color fill = CARD, edge = LINE;
        boolean hover, clickable;
        Card(LayoutManager lm) {
            super(lm);
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
            setAlignmentX(Component.LEFT_ALIGNMENT);
        }
        Card onClick(Runnable r) {
            clickable = true;
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); }
                @Override public void mouseClicked(MouseEvent e) { Audio.get().play(Synth.Sfx.CLICK); r.run(); }
            });
            return this;
        }
        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(hover ? CARD_HI : fill);
            g.fill(new RoundRectangle2D.Float(0, 0, getWidth() - 1, getHeight() - 1, 12, 12));
            g.setColor(edge);
            g.setStroke(new BasicStroke(edge.getAlpha() > 100 ? 2f : 1f));
            g.draw(new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 2, getHeight() - 2, 12, 12));
            g.dispose();
            super.paintComponent(g0);
        }
        @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
    }

    /** Vertical column that always matches the scroll viewport's width (so nothing is clipped on the right). */
    static class ScrollColumn extends JPanel implements Scrollable {
        ScrollColumn() { setLayout(new BoxLayout(this, BoxLayout.Y_AXIS)); setOpaque(false); }
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 18; }
        @Override public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return 120; }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }

    /** Thin progress bar. */
    static class Bar extends JComponent {
        double value; Color color;
        Bar(double v, Color c) { value = Math.max(0, Math.min(1, v)); color = c; setPreferredSize(new Dimension(80, 8)); }
        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int h = 7, y = (getHeight() - h) / 2;
            g.setColor(new Color(255, 255, 255, 22));
            g.fillRoundRect(0, y, getWidth(), h, h, h);
            g.setPaint(new GradientPaint(0, 0, color.brighter(), getWidth(), 0, color));
            g.fillRoundRect(0, y, Math.max(h, (int) (getWidth() * value)), h, h, h);
            g.dispose();
        }
    }

    /** Small coloured pill, e.g. "VISIBLE NOW". */
    static JComponent pill(String text, Color c) {
        JLabel l = new JLabel(text) {
            @Override protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 48));
                g.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
                g.dispose();
                super.paintComponent(g0);
            }
        };
        l.setFont(Fonts.label(12.5f));
        l.setForeground(c);
        l.setBorder(BorderFactory.createEmptyBorder(2, 9, 2, 9));
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    static JPanel row(Component... parts) {
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setLayout(new BoxLayout(p, BoxLayout.X_AXIS));
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        for (Component c : parts) p.add(c);
        return p;
    }

    static JPanel column() {
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        return p;
    }

    static JLabel imageLabel(Image img) { JLabel l = new JLabel(new ImageIcon(img)); l.setAlignmentY(Component.CENTER_ALIGNMENT); return l; }

    static Component gap(int w, int h) { return Box.createRigidArea(new Dimension(w, h)); }

    static JButton button(String text, String icon, Runnable r) { return Wf.button(text, icon, r); }
}
