package warfare.desktop;

import javax.swing.*;
import javax.swing.plaf.basic.BasicScrollBarUI;
import javax.swing.plaf.basic.BasicSliderUI;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * The game's own widget kit. Nothing here uses the stock look-and-feel painting: buttons, sliders, switches,
 * segmented selectors, scrollbars and window frames are all drawn by hand in the game's palette.
 */
final class Wf {
    private Wf() {}

    static final Color GOLD = new Color(226, 187, 102), GOLD_DARK = new Color(176, 134, 54), INK = new Color(24, 20, 12);

    static void quality(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    }

    // ------------------------------------------------------------------ buttons
    enum Kind { NORMAL, PRIMARY, DANGER, GHOST }

    static final class Button extends JButton {
        Kind kind = Kind.NORMAL;
        private boolean hover, down;
        private float hoverT;                       // 0..1, eased toward the hover state so highlights fade instead of snapping
        private javax.swing.Timer easing;
        private final String iconName;

        private void ease() {
            if (easing == null) easing = new javax.swing.Timer(16, e -> {
                float target = hover ? 1f : 0f;
                hoverT += (target - hoverT) * 0.32f;
                if (Math.abs(target - hoverT) < 0.02f) { hoverT = target; easing.stop(); }
                repaint();
            });
            if (!easing.isRunning()) easing.start();
        }

        Button(String text, String icon, Kind kind, Runnable action) {
            super(text == null ? "" : text);
            this.iconName = icon;
            this.kind = kind;
            setOpaque(false); setContentAreaFilled(false); setBorderPainted(false); setFocusPainted(false); setFocusable(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setFont(Fonts.label(15f));
            setForeground(Ui.TEXT);
            setBorder(BorderFactory.createEmptyBorder(7, 14, 7, 14));
            setAlignmentX(Component.LEFT_ALIGNMENT);
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hover = true; ease(); }
                @Override public void mouseExited(MouseEvent e) { hover = false; down = false; ease(); }
                @Override public void mousePressed(MouseEvent e) { down = true; repaint(); }
                @Override public void mouseReleased(MouseEvent e) { down = false; repaint(); }
            });
            if (action != null) addActionListener(e -> { Audio.get().play(Synth.Sfx.CLICK); action.run(); });
        }

        @Override public Dimension getPreferredSize() {
            FontMetrics fm = getFontMetrics(getFont());
            int w = fm.stringWidth(getText().toUpperCase()) + 28 + (iconName != null ? 24 : 0);
            return new Dimension(Math.max(w, 36), 34);
        }

        @Override public Dimension getMaximumSize() { return new Dimension(super.getMaximumSize().width, 34); }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            quality(g);
            int w = getWidth(), h = getHeight();
            boolean on = isEnabled();
            RoundRectangle2D shape = new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h - 1, 6, 6);
            Color edge, text;
            if (kind == Kind.GHOST) {
                g.setColor(new Color(255, 255, 255, (int) (8 + 22 * (on ? hoverT : 0))));
                g.fill(shape);
                edge = new Color(255, 255, 255, 30 + (int) (60 * (on ? hoverT : 0)));
                text = Ui.TEXT;
            } else {
                Skin.Kind sk = kind == Kind.PRIMARY ? Skin.Kind.GOLD : kind == Kind.DANGER ? Skin.Kind.DANGER : Skin.Kind.RAISED;
                Skin.plate(g, shape, sk, down ? 0.2f : 1f);
                if (hoverT > 0.01f && on) { g.setColor(new Color(255, 255, 255, (int) ((kind == Kind.PRIMARY ? 36 : 22) * hoverT))); g.fill(shape); }
                if (down && on) { g.setColor(new Color(0, 0, 0, 55)); g.fill(shape); }
                switch (kind) {
                    case PRIMARY -> { edge = new Color(122, 90, 34, 210); text = INK; }
                    case DANGER -> { edge = new Color(255, 130, 110, 100); text = Color.WHITE; }
                    default -> { edge = new Color(Skin.BRASS.getRed(), Skin.BRASS.getGreen(), Skin.BRASS.getBlue(), (int) (28 + 170 * (on ? hoverT : 0))); text = Ui.TEXT; }
                }
            }
            g.setColor(edge);
            g.setStroke(new BasicStroke(1f));
            g.draw(shape);
            String label = getText().toUpperCase();
            FontMetrics fm = g.getFontMetrics(getFont());
            int iw = iconName != null ? 22 : 0, tw = fm.stringWidth(label), total = iw + tw;
            int x = (w - total) / 2, cy = h / 2;
            Font f = getFont();
            int room = w - 16 - iw;
            while (fm.stringWidth(label) > room && f.getSize2D() > 10.5f) { f = f.deriveFont(f.getSize2D() - 0.5f); fm = g.getFontMetrics(f); }
            if (fm.stringWidth(label) > room) { while (label.length() > 3 && fm.stringWidth(label + "...") > room) label = label.substring(0, label.length() - 1); label += "..."; }
            tw = fm.stringWidth(label);
            x = (w - (iw + tw)) / 2 + iw;
            if (iconName != null) g.drawImage(Assets.icon(iconName, 18, kind == Kind.PRIMARY ? INK : on ? Ui.TEXT : Ui.MUTED), x - iw, cy - 9, null);
            g.setFont(f);
            g.setColor(on ? text : Ui.MUTED);
            g.drawString(label, x, cy + fm.getAscent() / 2 - 1);
            g.dispose();
        }
    }

    private static Color brighter(Color c, double f) {
        return new Color(Math.min(255, (int) (c.getRed() * f)), Math.min(255, (int) (c.getGreen() * f)), Math.min(255, (int) (c.getBlue() * f)), c.getAlpha());
    }

    static Button button(String text, String icon, Runnable r) { return new Button(text, icon, Kind.NORMAL, r); }
    static Button primary(String text, String icon, Runnable r) { return new Button(text, icon, Kind.PRIMARY, r); }
    static Button danger(String text, String icon, Runnable r) { return new Button(text, icon, Kind.DANGER, r); }
    static Button ghost(String text, String icon, Runnable r) { return new Button(text, icon, Kind.GHOST, r); }

    // ------------------------------------------------------------------ slider
    static JSlider slider(int min, int max, int value) {
        JSlider s = new JSlider(min, max, value);
        s.setOpaque(false);
        s.setFocusable(false);
        s.setUI(new SliderUI(s));
        s.setAlignmentX(Component.LEFT_ALIGNMENT);
        s.setPreferredSize(new Dimension(200, 22));
        s.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
        s.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return s;
    }

    private static final class SliderUI extends BasicSliderUI {
        SliderUI(JSlider s) { super(s); }
        @Override public void paintTrack(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            quality(g);
            Rectangle t = trackRect;
            int cy = t.y + t.height / 2, h = 6;
            g.setColor(new Color(0, 0, 0, 120));
            g.fillRoundRect(t.x, cy - h / 2, t.width, h, h, h);
            g.setColor(new Color(255, 255, 255, 18));
            g.drawRoundRect(t.x, cy - h / 2, t.width - 1, h - 1, h, h);
            int fillTo = thumbRect.x + thumbRect.width / 2;
            if (slider.getMinimum() < 0) {                       // centred sliders (e.g. civilian <-> military) fill from the middle
                int mid = t.x + t.width / 2;
                g.setColor(GOLD);
                g.fillRoundRect(Math.min(mid, fillTo), cy - h / 2, Math.abs(fillTo - mid), h, h, h);
                g.setColor(new Color(255, 255, 255, 80));
                g.fillRect(mid - 1, cy - 6, 2, 12);
            } else {
                g.setPaint(new GradientPaint(t.x, 0, GOLD_DARK, fillTo, 0, GOLD));
                g.fillRoundRect(t.x, cy - h / 2, Math.max(h, fillTo - t.x), h, h, h);
            }
            g.dispose();
        }
        @Override public void paintThumb(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            quality(g);
            Rectangle r = thumbRect;
            int d = 16, x = r.x + (r.width - d) / 2, y = r.y + (r.height - d) / 2;
            g.setColor(new Color(0, 0, 0, 120));
            g.fill(new Ellipse2D.Float(x, y + 2, d, d));
            g.setPaint(new GradientPaint(0, y, new Color(246, 222, 150), 0, y + d, new Color(196, 152, 64)));
            g.fill(new Ellipse2D.Float(x, y, d, d));
            g.setColor(new Color(90, 66, 24, 230));
            g.setStroke(new BasicStroke(1f));
            g.draw(new Ellipse2D.Float(x + 0.5f, y + 0.5f, d - 1, d - 1));
            g.setColor(new Color(255, 255, 255, 90));
            g.fill(new Ellipse2D.Float(x + 4, y + 3, d - 8, 3));
            g.dispose();
        }
        @Override public void paintFocus(Graphics g) {}
        @Override public void paintLabels(Graphics g) {}
        @Override public void paintTicks(Graphics g) {}
        @Override protected Dimension getThumbSize() { return new Dimension(20, 20); }
    }

    // ------------------------------------------------------------------ switch
    static final class Toggle extends JPanel {
        private boolean on, hover;
        private final JLabel label;
        private final Consumer<Boolean> onChange;

        Toggle(String text, boolean initial, Consumer<Boolean> onChange) {
            super(new FlowLayout(FlowLayout.LEFT, 10, 0));
            this.on = initial; this.onChange = onChange;
            setOpaque(false);
            setAlignmentX(Component.LEFT_ALIGNMENT);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            JComponent sw = new JComponent() {
                { setPreferredSize(new Dimension(42, 24)); }
                @Override protected void paintComponent(Graphics g0) {
                    Graphics2D g = (Graphics2D) g0.create();
                    quality(g);
                    g.setPaint(Toggle.this.on ? new GradientPaint(0, 2, new Color(224, 184, 96), 0, 22, new Color(176, 134, 54)) : new GradientPaint(0, 2, new Color(26, 27, 30), 0, 22, new Color(44, 45, 49)));
                    g.fillRoundRect(0, 2, 42, 20, 20, 20);
                    g.setColor(hover ? new Color(255, 255, 255, 90) : new Color(255, 255, 255, 34));
                    g.drawRoundRect(0, 2, 41, 19, 20, 20);
                    int kx = Toggle.this.on ? 22 : 2;
                    g.setColor(new Color(0, 0, 0, 110));
                    g.fillOval(kx + 1, 5, 18, 18);
                    g.setColor(Toggle.this.on ? new Color(255, 250, 235) : new Color(176, 176, 172));
                    g.fillOval(kx, 3, 18, 18);
                    g.dispose();
                }
            };
            label = new JLabel(text);
            label.setFont(Fonts.ui(13f));
            label.setForeground(Ui.TEXT);
            add(sw); add(label);
            MouseAdapter ma = new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) { flip(); }
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); }
            };
            addMouseListener(ma); sw.addMouseListener(ma); label.addMouseListener(ma);
        }

        void flip() { on = !on; Audio.get().play(Synth.Sfx.CLICK); repaint(); if (onChange != null) onChange.accept(on); }
        boolean isOn() { return on; }
        void set(boolean v) { if (on != v) { on = v; repaint(); } }
        String text() { return label.getText(); }
        @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, 30); }
    }

    static Toggle toggle(String text, boolean initial, Consumer<Boolean> onChange) { return new Toggle(text, initial, onChange); }

    // ------------------------------------------------------------------ segmented selector
    static final class Segmented extends JPanel {
        private int selected;
        private final String[] labels;
        private final IntConsumer onChange;
        private int hover = -1;

        Segmented(String[] labels, int selected, IntConsumer onChange) {
            this.labels = labels; this.selected = selected; this.onChange = onChange;
            setOpaque(false);
            setAlignmentX(Component.LEFT_ALIGNMENT);
            setPreferredSize(new Dimension(300, 32));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) {
                    int i = indexAt(e.getX());
                    if (i >= 0 && i != Segmented.this.selected) { Segmented.this.selected = i; Audio.get().play(Synth.Sfx.CLICK); repaint(); Segmented.this.onChange.accept(i); }
                }
                @Override public void mouseExited(MouseEvent e) { hover = -1; repaint(); }
            });
            addMouseMotionListener(new MouseAdapter() { @Override public void mouseMoved(MouseEvent e) { hover = indexAt(e.getX()); repaint(); } });
        }

        private int indexAt(int x) { int w = getWidth() / labels.length; return w <= 0 ? -1 : Math.min(labels.length - 1, x / w); }
        int selected() { return selected; }
        void select(int i) { if (selected != i) { selected = i; repaint(); } }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            quality(g);
            int w = getWidth() / labels.length, h = getHeight();
            Skin.plate(g, new RoundRectangle2D.Float(0, 0, getWidth(), h, 8, 8), Skin.Kind.INSET, 1f);
            g.setFont(Fonts.label(13.5f));
            FontMetrics fm = g.getFontMetrics();
            for (int i = 0; i < labels.length; i++) {
                boolean on = i == selected;
                if (on) {
                    Skin.plate(g, new RoundRectangle2D.Float(i * w + 2, 2, w - 4, h - 4, 6, 6), Skin.Kind.GOLD, 1f);
                } else if (i == hover) { g.setColor(new Color(255, 255, 255, 20)); g.fillRoundRect(i * w + 2, 2, w - 4, h - 4, 6, 6); }
                g.setColor(on ? INK : Ui.TEXT);
                String s = labels[i].toUpperCase();
                g.drawString(s, i * w + (w - fm.stringWidth(s)) / 2, h / 2 + fm.getAscent() / 2 - 1);
            }
            g.setColor(new Color(255, 255, 255, 30));
            g.drawRoundRect(0, 0, getWidth() - 1, h - 1, 8, 8);
            g.dispose();
        }
    }

    // ------------------------------------------------------------------ scrolling
    static JScrollPane scroll(JComponent view) {
        JScrollPane sp = new JScrollPane(view);
        sp.setBorder(null);
        sp.setOpaque(false);
        sp.getViewport().setOpaque(false);
        sp.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sp.getVerticalScrollBar().setUI(new ScrollUI());
        sp.getVerticalScrollBar().setPreferredSize(new Dimension(10, 10));
        sp.getVerticalScrollBar().setUnitIncrement(20);
        sp.getVerticalScrollBar().setOpaque(false);
        return sp;
    }

    private static final class ScrollUI extends BasicScrollBarUI {
        @Override protected JButton createDecreaseButton(int o) { return zero(); }
        @Override protected JButton createIncreaseButton(int o) { return zero(); }
        private JButton zero() { JButton b = new JButton(); b.setPreferredSize(new Dimension(0, 0)); b.setOpaque(false); return b; }
        @Override protected void paintTrack(Graphics g, JComponent c, Rectangle r) {}
        @Override protected void paintThumb(Graphics g0, JComponent c, Rectangle r) {
            if (r.isEmpty()) return;
            Graphics2D g = (Graphics2D) g0.create();
            quality(g);
            g.setColor(isThumbRollover() || isDragging ? new Color(226, 187, 102, 200) : new Color(210, 208, 200, 70));
            g.fillRoundRect(r.x + 2, r.y + 2, r.width - 4, r.height - 4, 8, 8);
            g.dispose();
        }
        @Override protected void configureScrollBarColors() { thumbColor = new Color(0, 0, 0, 0); }
    }

    // ------------------------------------------------------------------ window frame
    /** Floating window: translucent dark body, title with gold rule, close button. */
    static class Window extends JPanel {
        final JPanel body = new JPanel(new BorderLayout());
        private final Runnable onClose;

        static boolean instant;
        private float fade = instant ? 1f : 0f;
        private javax.swing.Timer fadeTimer;

        @Override public void addNotify() {
            super.addNotify();
            if (fade >= 1f) return;
            fadeTimer = new javax.swing.Timer(16, e -> { fade = Math.min(1f, fade + 0.14f); repaint(); if (fade >= 1f) fadeTimer.stop(); });
            fadeTimer.start();
        }

        @Override public void paint(Graphics g0) {
            if (fade >= 1f) { super.paint(g0); return; }
            Graphics2D g = (Graphics2D) g0.create();
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.max(0.02f, fade)));
            g.translate(0, (int) ((1 - fade) * 10));
            super.paint(g);
            g.dispose();
        }

        Window(String title, String icon, Runnable onClose) {
            super(new BorderLayout());
            this.onClose = onClose;
            setOpaque(false);
            JPanel head = new JPanel(new BorderLayout());
            head.setOpaque(false);
            head.setBorder(BorderFactory.createEmptyBorder(12, 16, 10, 12));
            JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
            left.setOpaque(false);
            if (icon != null) left.add(new JLabel(new ImageIcon(Assets.icon(icon, 26, GOLD))));
            JLabel t = new JLabel(title.toUpperCase());
            t.setFont(Fonts.title(30f));
            t.setForeground(Color.WHITE);
            left.add(t);
            head.add(left, BorderLayout.WEST);
            if (onClose != null) head.add(closeButton(onClose), BorderLayout.EAST);
            add(head, BorderLayout.NORTH);
            body.setOpaque(false);
            add(body, BorderLayout.CENTER);
            addMouseListener(new MouseAdapter() {});          // the window swallows clicks so they never reach the map
            addMouseMotionListener(new MouseAdapter() {});
            addMouseWheelListener(e -> {});
        }

        private final Cache face = new Cache();

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            int w = getWidth(), h = getHeight();
            face.paint(g, 0, 0, w, h, null, b -> {
                Skin.shadow(b, 2, 2, w - 8, h - 8, 8, 6, 120);
                RoundRectangle2D body = new RoundRectangle2D.Float(0, 0, w - 4, h - 4, 8, 8);
                Skin.plate(b, body, Skin.Kind.PANEL, 1f);
                // header band
                Shape old = b.getClip();
                b.clip(body);
                b.setPaint(new GradientPaint(0, 0, new Color(255, 255, 255, 14), 0, 56, new Color(255, 255, 255, 0)));
                b.fillRect(0, 0, w, 56);
                b.setClip(old);
                Skin.rule(b, 16, 55, Math.max(60, (w - 4) * 0.62f));
                Skin.frame(b, 0.5f, 0.5f, w - 5, h - 5, 8, true, false);
            });
            g.dispose();
        }

        @Override public Insets getInsets() { return new Insets(0, 0, 4, 4); }
    }

    static JComponent closeButton(Runnable r) {
        JComponent b = new JComponent() {
            boolean hover;
            { setPreferredSize(new Dimension(30, 30)); setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
              addMouseListener(new MouseAdapter() {
                  @Override public void mouseClicked(MouseEvent e) { Audio.get().play(Synth.Sfx.CLICK); r.run(); }
                  @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                  @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); }
              }); }
            @Override protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0.create();
                quality(g);
                g.setColor(hover ? new Color(224, 96, 84, 220) : new Color(255, 255, 255, 16));
                g.fill(new RoundRectangle2D.Float(2, 2, 26, 26, 8, 8));
                g.setColor(new Color(255, 255, 255, hover ? 70 : 28));
                g.draw(new RoundRectangle2D.Float(2.5f, 2.5f, 25, 25, 8, 8));
                g.setColor(hover ? Color.WHITE : new Color(220, 218, 212));
                g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.drawLine(10, 10, 20, 20); g.drawLine(20, 10, 10, 20);
                g.dispose();
            }
        };
        JPanel wrap = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        wrap.setOpaque(false);
        wrap.add(b);
        return wrap;
    }

    /** Text field in the game's style. */
    static JTextField field(String placeholder) {
        JTextField f = new JTextField();
        f.putClientProperty("JTextField.placeholderText", placeholder);
        f.putClientProperty("JComponent.roundRect", true);
        f.setFont(Fonts.ui(14f));
        f.setBackground(new Color(0, 0, 0, 70));
        f.setForeground(Ui.TEXT);
        f.setCaretColor(GOLD);
        f.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(255, 255, 255, 34), 1, true), BorderFactory.createEmptyBorder(6, 10, 6, 10)));
        f.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        return f;
    }
}
