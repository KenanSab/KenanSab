package warfare.desktop;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.IntConsumer;

/** Full-window layer that shows cards (events, confirmations, menus) over a dimmed map. Replaces popup dialogs. */
final class Modals extends JPanel {
    private final Deque<JComponent> stack = new ArrayDeque<>();

    Modals() {
        setOpaque(false);
        setLayout(null);
        addMouseListener(new MouseAdapter() {});
        addMouseMotionListener(new MouseAdapter() {});
        addMouseWheelListener(e -> {});
        setVisible(false);
    }

    boolean isOpen() { return !stack.isEmpty(); }
    JComponent top() { return stack.peek(); }

    void push(JComponent card) {
        JComponent old = stack.peek();
        if (old != null) remove(old);
        stack.push(card);
        add(card);
        setVisible(true);
        revalidate(); repaint();
    }

    void pop() {
        JComponent c = stack.poll();
        if (c != null) remove(c);
        JComponent next = stack.peek();
        if (next != null) add(next);
        setVisible(isOpen());
        revalidate(); repaint();
    }

    void clear() { while (isOpen()) pop(); }

    @Override public void doLayout() {
        JComponent c = stack.peek();
        if (c == null) return;
        Dimension d = c.getPreferredSize();
        int w = Math.min(d.width, getWidth() - 40), h = Math.min(d.height, getHeight() - 40);
        c.setBounds((getWidth() - w) / 2, (getHeight() - h) / 2, w, h);
    }

    @Override protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setPaint(new RadialGradientPaint(getWidth() / 2f, getHeight() / 2f, Math.max(getWidth(), getHeight()) * 0.7f,
                new float[]{0f, 1f}, new Color[]{new Color(4, 8, 16, 150), new Color(2, 4, 10, 215)}));
        g.fillRect(0, 0, getWidth(), getHeight());
        g.dispose();
    }

    // ------------------------------------------------------------------ builders
    /** A modal card: title bar, body, fixed width. */
    static Wf.Window card(String title, String icon, JComponent body, int width, int height, Runnable onClose) {
        Wf.Window w = new Wf.Window(title, icon, onClose) {
            @Override public Dimension getPreferredSize() { return new Dimension(width, height > 0 ? height : super.getPreferredSize().height); }
        };
        w.body.add(body, BorderLayout.CENTER);
        return w;
    }

    static JComponent textBody(String text, JComponent buttons) {
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(BorderFactory.createEmptyBorder(14, 18, 18, 18));
        JLabel l = Ui.label("<html><div style='width:360px'>" + SidePanels.esc(text).replace("\n", "<br>") + "</div></html>", 14.5f, Ui.TEXT);
        p.add(l);
        p.add(Ui.gap(0, 16));
        p.add(buttons);
        return p;
    }

    static JComponent buttonColumn(List<String> labels, IntConsumer pick, int primary) {
        JPanel col = new JPanel();
        col.setOpaque(false);
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
        col.setAlignmentX(Component.LEFT_ALIGNMENT);
        for (int i = 0; i < labels.size(); i++) {
            final int idx = i;
            Wf.Button b = i == primary ? Wf.primary(labels.get(i), null, () -> pick.accept(idx)) : Wf.button(labels.get(i), null, () -> pick.accept(idx));
            b.setAlignmentX(Component.LEFT_ALIGNMENT);
            b.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
            col.add(b);
            col.add(Ui.gap(0, 6));
        }
        return col;
    }
}
