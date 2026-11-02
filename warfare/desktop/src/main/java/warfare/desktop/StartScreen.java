package warfare.desktop;

import warfare.core.WorldData;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/** Main menu / new-game screen: the world map dimmed behind a title, a nation list and a preview card. */
final class StartScreen extends JPanel {
    record Choice(int nation, boolean ironman) {}

    private final WorldData world;
    private final Consumer<Choice> onStart;
    private final List<WorldData.NationInfo> all = new ArrayList<>();
    private final JPanel list = new Ui.ScrollColumn();
    private final List<Row> rows = new ArrayList<>();
    private final JTextField search = Wf.field("Search nations...");
    private final JPanel preview = new JPanel();
    private final Wf.Toggle ironman = Wf.toggle("Ironman: one life, autosave only", false, null);
    private WorldData.NationInfo selected;
    private Image backdrop;

    StartScreen(WorldData world, Consumer<Choice> onStart) {
        super(new BorderLayout());
        this.world = world; this.onStart = onStart;
        backdrop = WarfareApp.basemap(world).image;
        all.addAll(world.nations);
        all.sort(Comparator.comparing(n -> n.name));

        JPanel left = new JPanel();
        left.setOpaque(false);
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
        left.setBorder(BorderFactory.createEmptyBorder(40, 48, 36, 20));
        JLabel title = new JLabel("WARFARE");
        title.setFont(Fonts.title(124f));
        title.setForeground(Color.WHITE);
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        left.add(title);
        left.add(Ui.label("MODERN GRAND STRATEGY  -  NOVEMBER 2026", 15f, Wf.GOLD));
        left.add(Ui.gap(0, 22));
        left.add(Ui.label("CHOOSE YOUR NATION", 14f, Ui.MUTED));
        left.add(Ui.gap(0, 8));
        search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { filter(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { filter(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { filter(); }
        });
        left.add(search);
        left.add(Ui.gap(0, 8));
        for (WorldData.NationInfo n : all) { Row r = new Row(n); rows.add(r); list.add(r); }
        JScrollPane sp = Wf.scroll(list);
        sp.setAlignmentX(Component.LEFT_ALIGNMENT);
        sp.setPreferredSize(new Dimension(420, 300));
        left.add(sp);
        left.setPreferredSize(new Dimension(560, 600));
        add(left, BorderLayout.WEST);

        preview.setOpaque(false);
        preview.setLayout(new BoxLayout(preview, BoxLayout.Y_AXIS));
        preview.setBorder(BorderFactory.createEmptyBorder(60, 30, 40, 60));
        add(preview, BorderLayout.CENTER);
        select(all.stream().filter(n -> n.tag.equals("FRA")).findFirst().orElse(all.get(0)));
        SwingUtilities.invokeLater(() -> { for (Row r : rows) if (r.n == selected) r.scrollRectToVisible(new Rectangle(0, 0, 10, 10)); });
    }

    private void filter() {
        String q = search.getText().trim().toLowerCase();
        for (Row r : rows) r.setVisible(q.isEmpty() || r.n.name.toLowerCase().contains(q));
        list.revalidate(); list.repaint();
    }

    WorldData.NationInfo selected() { return selected; }

    void select(WorldData.NationInfo n) {
        selected = n;
        for (Row r : rows) r.repaint();
        preview.removeAll();
        JPanel card = new JPanel() {
            @Override protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0.create();
                Wf.quality(g);
                g.setPaint(new GradientPaint(0, 0, new Color(44, 35, 27, 232), 0, getHeight(), new Color(25, 19, 14, 240)));
                g.fill(new RoundRectangle2D.Float(0, 0, getWidth() - 1, getHeight() - 1, 24, 24));
                g.setColor(new Color(255, 255, 255, 40));
                g.draw(new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 2, getHeight() - 2, 24, 24));
                g.dispose();
            }
        };
        card.setOpaque(false);
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(BorderFactory.createEmptyBorder(26, 30, 26, 30));
        card.setMaximumSize(new Dimension(560, 640));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel flag = new JLabel(new ImageIcon(Assets.flag(n, 250)));
        flag.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(flag);
        card.add(Ui.gap(0, 14));
        JLabel name = new JLabel(n.name.toUpperCase());
        name.setFont(Fonts.title(58f));
        name.setForeground(Color.WHITE);
        name.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(name);
        warfare.core.Government g = warfare.core.Government.of(n.tag);
        JPanel pills = Ui.row(Ui.pill(g.label.toUpperCase(), Ui.ACCENT), Ui.gap(6, 0), Ui.pill(n.continent.toUpperCase(), Ui.MUTED));
        if (!n.bloc.equals("NONE")) { pills.add(Ui.gap(6, 0)); pills.add(Ui.pill(n.bloc, Ui.AMBER)); }
        card.add(pills);
        card.add(Ui.gap(0, 14));
        JPanel stats = Ui.column();
        stats.add(stat("manpower", Ui.ACCENT, "Population", String.format("%,d", n.population)));
        stats.add(stat("money", Ui.GOLD, "GDP", String.format("$%,.0f M / yr", n.gdp)));
        stats.add(stat("battle", Ui.TEXT, "Provinces", String.valueOf(n.provinces.size())));
        stats.add(stat("nuke", n.nukes > 0 ? Ui.RED : Ui.MUTED, "Nuclear warheads", n.nukes > 0 ? String.valueOf(n.nukes) : "none"));
        stats.add(stat("oil", new Color(200, 160, 120), "Oil production", n.oilProduction > 0 ? String.format("%.1f", n.oilProduction) : "none"));
        card.add(stats);
        card.add(Ui.gap(0, 18));
        card.add(ironman);
        card.add(Ui.gap(0, 14));
        Wf.Button go = Wf.primary("Start campaign", "battle", () -> onStart.accept(new Choice(selected.index, ironman.isOn())));
        go.setFont(Fonts.label(20f));
        go.setPreferredSize(new Dimension(340, 52));
        go.setMaximumSize(new Dimension(340, 52));
        card.add(go);
        preview.add(Box.createVerticalGlue());
        preview.add(card);
        preview.add(Box.createVerticalGlue());
        preview.revalidate(); preview.repaint();
    }

    private static JComponent stat(String icon, Color tint, String name, String value) {
        JPanel r = Ui.row(Ui.imageLabel(Assets.icon(icon, 20, tint)), Ui.gap(10, 0), Ui.label(name, 14.5f, Ui.MUTED), Box.createHorizontalGlue(), Ui.label(value, 15f, Ui.TEXT));
        r.setBorder(BorderFactory.createEmptyBorder(5, 0, 5, 0));
        return r;
    }

    @Override protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        int w = getWidth(), h = getHeight();
        double scale = Math.max(w / (double) backdrop.getWidth(null), h / (double) backdrop.getHeight(null));
        int iw = (int) (backdrop.getWidth(null) * scale), ih = (int) (backdrop.getHeight(null) * scale);
        g.drawImage(backdrop, (w - iw) / 2, (h - ih) / 2, iw, ih, null);
        g.setPaint(new GradientPaint(0, 0, new Color(17, 13, 9, 230), w * 0.62f, 0, new Color(17, 13, 9, 120)));
        g.fillRect(0, 0, w, h);
        g.setPaint(new GradientPaint(0, h * 0.7f, new Color(0, 0, 0, 0), 0, h, new Color(0, 0, 0, 160)));
        g.fillRect(0, 0, w, h);
        g.dispose();
    }

    private final class Row extends JPanel {
        final WorldData.NationInfo n;
        private boolean hover;

        Row(WorldData.NationInfo n) {
            this.n = n;
            setOpaque(false);
            setLayout(new BorderLayout(12, 0));
            setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
            setAlignmentX(Component.LEFT_ALIGNMENT);
            setPreferredSize(new Dimension(380, 44));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            add(new JLabel(new ImageIcon(Assets.flag(n, 32))), BorderLayout.WEST);
            add(Ui.label(n.name, 16f, Ui.TEXT), BorderLayout.CENTER);
            if (n.nukes > 0) add(new JLabel(new ImageIcon(Assets.icon("nuke", 18, Ui.RED))), BorderLayout.EAST);
            addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) { Audio.get().play(Synth.Sfx.CLICK); select(n); if (e.getClickCount() == 2) onStart.accept(new Choice(n.index, ironman.isOn())); }
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); }
            });
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Wf.quality(g);
            boolean sel = selected == n;
            if (sel || hover) {
                g.setColor(sel ? new Color(236, 192, 92, 40) : new Color(255, 255, 255, 20));
                g.fill(new RoundRectangle2D.Float(0, 1, getWidth() - 1, getHeight() - 3, 12, 12));
                if (sel) { g.setColor(new Color(236, 192, 92, 210)); g.setStroke(new BasicStroke(1.4f)); g.draw(new RoundRectangle2D.Float(0.5f, 1.5f, getWidth() - 2, getHeight() - 4, 12, 12)); }
            }
            g.dispose();
        }
    }
}
