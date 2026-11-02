package warfare.desktop;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** The icon rail down the left edge: one button per window, in the manner of Age of History's side menu. */
final class Rail extends JPanel {
    private final Map<String, Item> items = new LinkedHashMap<>();
    private String active;

    Rail(Consumer<String> onPick) {
        setOpaque(false);
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        add("economy", "Economy", "money", "E", onPick);
        add("diplomacy", "Diplomacy", "tab_diplo", "T", onPick);
        add("production", "Build", "tab_prod", "P", onPick);
        add("reports", "Reports", "tab_reports", "R", onPick);
        add("country", "Country", "general", "C", onPick);
    }

    private void add(String key, String label, String icon, String hotkey, Consumer<String> onPick) {
        Item it = new Item(label, icon, hotkey, () -> onPick.accept(key));
        items.put(key, it);
        super.add(it);
        super.add(Box.createVerticalStrut(6));
    }

    void setActive(String key) { active = key; for (var e : items.entrySet()) e.getValue().repaint(); }

    @Override public Dimension getPreferredSize() { return new Dimension(70, items.size() * 72); }

    private final class Item extends JComponent {
        private final String label, icon;
        private boolean hover;

        Item(String label, String icon, String hotkey, Runnable r) {
            this.label = label; this.icon = icon;
            setPreferredSize(new Dimension(68, 66));
            setMaximumSize(new Dimension(68, 66));
            setAlignmentX(Component.LEFT_ALIGNMENT);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setToolTipText(label + "  [" + hotkey + "]");
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
            boolean on = items.get(active) == this;
            g.setColor(new Color(0, 0, 0, 90));
            g.fill(new RoundRectangle2D.Float(3, 5, w - 4, h - 6, 16, 16));
            Tex.plate(g, new RoundRectangle2D.Float(0, 0, w - 3, h - 4, 8, 8), on ? Tex.Kind.LEATHER : Tex.Kind.WOOD, on ? 0.6f : 1f);
            if (on) { g.setColor(new Color(236, 192, 92, 50)); g.fill(new RoundRectangle2D.Float(0, 0, w - 3, h - 4, 8, 8)); }
            Tex.frame(g, 0.5f, 0.5f, w - 4, h - 5, 8, false, on || hover);
            if (on) { g.setColor(Wf.GOLD); g.fillRoundRect(0, 14, 4, h - 32, 4, 4); }
            g.drawImage(Assets.icon(icon, 28, on ? Wf.GOLD : hover ? Color.WHITE : new Color(214, 222, 238)), (w - 3 - 28) / 2, 9, null);
            g.setFont(Fonts.label(12.5f));
            FontMetrics fm = g.getFontMetrics();
            String t = label.toUpperCase();
            g.setColor(on ? Wf.GOLD : Ui.TEXT);
            g.drawString(t, (w - 3 - fm.stringWidth(t)) / 2, h - 12);
            g.dispose();
        }
    }
}
