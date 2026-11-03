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
        setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 9));
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
        super.add(Box.createVerticalStrut(2));
    }

    void setActive(String key) { active = key; for (var e : items.entrySet()) e.getValue().repaint(); }

    @Override public Dimension getPreferredSize() { return new Dimension(78, items.size() * 62 + 12); }

    private final class Item extends JComponent {
        private final String label, icon;
        private boolean hover;

        Item(String label, String icon, String hotkey, Runnable r) {
            this.label = label; this.icon = icon;
            setPreferredSize(new Dimension(62, 60));
            setMaximumSize(new Dimension(62, 60));
            setAlignmentX(Component.LEFT_ALIGNMENT);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setToolTipText(label + "  [" + hotkey + "]");
            addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) { Audio.get().play(Synth.Sfx.CLICK); r.run(); }
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); }
            });
        }

        private final Cache face = new Cache();

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            int w = getWidth(), h = getHeight();
            boolean on = items.get(active) == this;
            face.paint(g, 0, 0, w, h, (on ? 2 : 0) | (hover ? 1 : 0), b -> {
                RoundRectangle2D cell = new RoundRectangle2D.Float(2, 1, w - 4, h - 2, 6, 6);
                if (on) {
                    b.setPaint(new GradientPaint(0, 0, new Color(226, 187, 102, 52), 0, h, new Color(226, 187, 102, 14)));
                    b.fill(cell);
                    b.setColor(Skin.BRASS_HI);
                    b.fill(new RoundRectangle2D.Float(2, 10, 3, h - 20, 3, 3));
                } else if (hover) { b.setColor(new Color(255, 255, 255, 18)); b.fill(cell); }
                b.drawImage(Assets.icon(icon, 26, on ? Skin.BRASS_HI : hover ? Color.WHITE : new Color(206, 204, 198)), (w - 26) / 2, 9, null);
                b.setFont(Fonts.label(11.5f));
                FontMetrics fm = b.getFontMetrics();
                String t = label.toUpperCase();
                b.setColor(on ? Skin.BRASS_HI : hover ? Color.WHITE : new Color(176, 175, 170));
                b.drawString(t, (w - fm.stringWidth(t)) / 2, h - 11);
            });
            g.dispose();
        }
    }

    private final Cache dock = new Cache();

    @Override protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        int w = getWidth(), h = getHeight();
        dock.paint(g, 0, 0, w, h, null, b -> {
            Skin.shadow(b, 0, 0, w - 4, h - 4, 8, 5, 90);
            RoundRectangle2D plate = new RoundRectangle2D.Float(0, 0, w - 4, h - 4, 8, 8);
            Skin.plate(b, plate, Skin.Kind.BAR, 1f);
            Skin.frame(b, 0.5f, 0.5f, w - 5, h - 5, 8, false, false);
        });
        g.dispose();
    }
}
