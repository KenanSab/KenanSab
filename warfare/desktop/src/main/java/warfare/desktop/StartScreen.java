package warfare.desktop;

import warfare.core.WorldData;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/** Main menu / new-game screen: the world map dimmed behind a title, a nation list and a preview card. */
final class StartScreen extends JPanel {
    record Choice(int nation, boolean ironman, warfare.core.Scenario scenario) {
        Choice(int nation, boolean ironman) { this(nation, ironman, warfare.core.Scenario.QUIET); }
    }

    private final WorldData world;
    private final Consumer<Choice> onStart;
    private final List<WorldData.NationInfo> all = new ArrayList<>();
    private final JPanel list = new Ui.ScrollColumn();
    private final List<Row> rows = new ArrayList<>();
    private final JTextField search = Wf.field("Search nations...");
    private final JPanel preview = new JPanel();
    private final Wf.Toggle ironman = Wf.toggle("Ironman: one life, autosave only", false, null);
    private final JLabel scenarioBlurb = Ui.label(warfare.core.Scenario.QUIET.blurb, 12.5f, Ui.MUTED);
    private final Wf.Segmented scenarioPick = new Wf.Segmented(new String[]{"Quiet world", "Flashpoints", "World on fire"}, 0, i -> scenarioBlurb.setText("<html><div style='width:470px'>" + warfare.core.Scenario.values()[i].blurb + "</div></html>"));
    private WorldData.NationInfo selected;
    private BufferedImage backdrop;      // pre-lit world strip that drifts slowly behind the menu
    private BufferedImage cachedStrip;
    private int stripH;
    private final javax.swing.Timer drift = new javax.swing.Timer(33, e -> repaint());
    private final long born = System.currentTimeMillis();
    private final java.util.Map<Integer, BufferedImage> minimaps = new java.util.HashMap<>();

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
                g.setColor(new Color(0, 0, 0, 110));
                g.fill(new RoundRectangle2D.Float(4, 8, getWidth() - 6, getHeight() - 6, 12, 12));
                Skin.plate(g, new RoundRectangle2D.Float(0, 0, getWidth() - 3, getHeight() - 3, 12, 12), Skin.Kind.PANEL, 1f);
                Skin.frame(g, 0.5f, 0.5f, getWidth() - 4, getHeight() - 4, 12, true, true);
                g.dispose();
            }
        };
        card.setOpaque(false);
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(BorderFactory.createEmptyBorder(26, 30, 26, 30));
        card.setMaximumSize(new Dimension(580, 760));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel mini = new JLabel(new ImageIcon(minimap(n, 500, 210)));
        mini.setAlignmentX(Component.LEFT_ALIGNMENT);
        mini.setBorder(BorderFactory.createLineBorder(new Color(0, 0, 0, 160), 2));
        card.add(mini);
        card.add(Ui.gap(0, 16));
        JPanel head = Ui.row(new JLabel(new ImageIcon(Assets.flag(n, 112))), Ui.gap(16, 0));
        head.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel name = new JLabel(n.name.toUpperCase());
        name.setFont(Fonts.title(n.name.length() > 16 ? 40f : 54f));
        name.setForeground(Color.WHITE);
        head.add(name);
        head.add(Box.createHorizontalGlue());
        card.add(head);
        card.add(Ui.gap(0, 8));
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
        card.add(Ui.gap(0, 14));
        card.add(Ui.label("SCENARIO", 12.5f, Ui.MUTED));
        card.add(Ui.gap(0, 4));
        card.add(scenarioPick);
        card.add(Ui.gap(0, 4));
        card.add(scenarioBlurb);
        card.add(Ui.gap(0, 12));
        card.add(ironman);
        card.add(Ui.gap(0, 14));
        Wf.Button go = Wf.primary("Start campaign", "battle", () -> onStart.accept(new Choice(selected.index, ironman.isOn(), warfare.core.Scenario.values()[scenarioPick.selected()])));
        go.setFont(Fonts.label(20f));
        go.setPreferredSize(new Dimension(340, 52));
        go.setMaximumSize(new Dimension(Integer.MAX_VALUE, 52));
        go.setAlignmentX(Component.LEFT_ALIGNMENT);
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

    @Override public void addNotify() { super.addNotify(); drift.start(); }
    @Override public void removeNotify() { drift.stop(); super.removeNotify(); }

    /** The whole world, lit by the relief layer, pre-rendered once at a height a bit taller than the screen. */
    private BufferedImage strip(int h) {
        int sh = (int) (h * 1.22);
        if (cachedStrip != null && stripH == sh) return cachedStrip;
        Basemap bm = WarfareApp.basemap(world);
        double sc = sh / (double) world.height;
        BufferedImage img = new BufferedImage((int) (world.width * sc), sh, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = MapLayers.hq(img);
        AffineTransform at = AffineTransform.getScaleInstance(sc / Basemap.PX_PER_UNIT, sc / Basemap.PX_PER_UNIT);
        g.drawImage(bm.image, at, null);
        g.drawImage(bm.relief, at, null);
        g.dispose();
        stripH = sh;
        return cachedStrip = img;
    }

    @Override protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        int w = getWidth(), h = getHeight();
        BufferedImage st = strip(h);
        double t = (System.currentTimeMillis() - born) / 1000.0;
        double span = Math.max(0, st.getWidth() - w), k = 0.5 + 0.5 * Math.sin(t * 0.045 - 0.9);
        int dx = (int) -Math.round(span * (0.18 + 0.64 * k)), dy = (int) -Math.round((st.getHeight() - h) * (0.5 + 0.5 * Math.sin(t * 0.03)));
        g.drawImage(st, dx, dy, null);
        // cloud shadows drift over the world
        BufferedImage clouds = MapPanel.cloudShadow();
        Composite old = g.getComposite();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.9f));
        for (double yy = -((t * 6) % 768) - 768; yy < h; yy += 768)
            for (double xx = -((t * 14) % 768) - 768; xx < w; xx += 768)
                g.drawImage(clouds, new AffineTransform(2, 0, 0, 2, xx, yy), null);
        g.setComposite(old);
        // grading: dark on the menu side, vignette all round, warm light from the right
        g.setPaint(new GradientPaint(0, 0, new Color(12, 11, 9, 238), w * 0.60f, 0, new Color(12, 11, 9, 90)));
        g.fillRect(0, 0, w, h);
        g.setPaint(new RadialGradientPaint(w * 0.78f, h * 0.38f, Math.max(w, h) * 0.55f, new float[]{0f, 1f}, new Color[]{new Color(255, 214, 140, 46), new Color(255, 214, 140, 0)}));
        g.fillRect(0, 0, w, h);
        g.setPaint(new RadialGradientPaint(w / 2f, h / 2f, Math.max(w, h) * 0.72f, new float[]{0.55f, 1f}, new Color[]{new Color(0, 0, 0, 0), new Color(0, 0, 0, 175)}));
        g.fillRect(0, 0, w, h);
        // title glow and rule
        g.setFont(Fonts.ui(12.5f));
        g.setColor(new Color(255, 255, 255, 70));
        String ver = "EARLY BUILD  -  MAPS: NATURAL EARTH  -  FLAGS: FLAG-ICONS  -  ICONS: GAME-ICONS.NET";
        g.drawString(ver, w - g.getFontMetrics().stringWidth(ver) - 24, h - 18);
        g.dispose();
    }

    /** The biggest connected landmass of the nation (overseas territories would drag the framing into the ocean). */
    private List<Integer> mainland(WorldData.NationInfo n) {
        java.util.Set<Integer> mine = new java.util.HashSet<>(), seen = new java.util.HashSet<>();
        for (int p : n.provinces) mine.add(p);
        List<Integer> best = new ArrayList<>(n.provinces);
        double bestPx = -1;
        for (int start : n.provinces) {
            if (seen.contains(start)) continue;
            List<Integer> comp = new ArrayList<>();
            java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<>();
            q.add(start); seen.add(start);
            double px = 0;
            while (!q.isEmpty()) {
                int p = q.poll(); comp.add(p); px += world.provinces.get(p).pixels;
                for (int nb : world.provinces.get(p).neighbours) if (mine.contains(nb) && seen.add(nb)) q.add(nb);
            }
            if (px > bestPx) { bestPx = px; best = comp; }
        }
        return best;
    }

    /** A regional map of the nation: its provinces in colour over the lit terrain, neighbours dimmed. */
    private BufferedImage minimap(WorldData.NationInfo n, int w, int h) {
        BufferedImage hit = minimaps.get(n.index);
        if (hit != null) return hit;
        MapShapes sh = WarfareApp.shapes(world);
        Basemap bm = WarfareApp.basemap(world);
        double x0 = 1e9, y0 = 1e9, x1 = -1e9, y1 = -1e9;
        for (int p : mainland(n)) {
            Rectangle2D b = sh.bounds[p];
            x0 = Math.min(x0, b.getX()); y0 = Math.min(y0, b.getY()); x1 = Math.max(x1, b.getMaxX()); y1 = Math.max(y1, b.getMaxY());
        }
        double bw = Math.max(8, x1 - x0), bh = Math.max(6, y1 - y0);
        double sc = Math.max(1.2, Math.min(14, Math.min(w / (bw * 1.9), h / (bh * 1.9))));
        double cx = (x0 + x1) / 2, cy = (y0 + y1) / 2;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = MapLayers.hq(img);
        AffineTransform at = new AffineTransform();
        at.translate(w / 2.0 - cx * sc, h / 2.0 - cy * sc);
        at.scale(sc / Basemap.PX_PER_UNIT, sc / Basemap.PX_PER_UNIT);
        g.drawImage(bm.image, at, null);
        g.transform(AffineTransform.getTranslateInstance(w / 2.0 - cx * sc, h / 2.0 - cy * sc));
        g.scale(sc, sc);
        Rectangle2D view = new Rectangle2D.Double(cx - w / 2.0 / sc, cy - h / 2.0 / sc, w / sc, h / sc);
        NationColors.init(world);
        for (WorldData.ProvinceInfo p : world.provinces) {
            if (p.sea || !view.intersects(sh.bounds[p.id])) continue;
            boolean mine = p.nation == n.index;
            Color c = p.nation < 0 ? Color.GRAY : new Color(NationColors.fill(NationColors.of(world.nations.get(p.nation).tag)));
            g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), mine ? 215 : 90));
            g.fill(sh.fill[p.id]);
        }
        g.setTransform(new AffineTransform());
        g.drawImage(bm.relief, at, null);
        g.transform(AffineTransform.getTranslateInstance(w / 2.0 - cx * sc, h / 2.0 - cy * sc));
        g.scale(sc, sc);
        g.setStroke(new BasicStroke((float) (1.4 / sc), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int a = 0; a < sh.p1.length; a++) {
            if (!view.intersects(sh.arcBounds[a])) continue;
            int p1 = sh.p1[a], p2 = sh.p2[a];
            if (p1 < 0 || p2 < 0) continue;
            WorldData.ProvinceInfo a1 = world.provinces.get(p1), a2 = world.provinces.get(p2);
            boolean mineEdge = (a1.nation == n.index) != (a2.nation == n.index) && !(a1.sea && a2.sea);
            boolean national = a1.nation != a2.nation && !(a1.sea && a2.sea) && !a1.sea && !a2.sea;
            if (mineEdge && !a1.sea && !a2.sea) { g.setColor(new Color(255, 232, 150, 235)); g.setStroke(new BasicStroke((float) (2.0 / sc), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)); g.draw(sh.arcPath[a]); }
            else if (national) { g.setColor(new Color(22, 21, 19, 190)); g.setStroke(new BasicStroke((float) (1.2 / sc), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)); g.draw(sh.arcPath[a]); }
            else if (!a1.sea && !a2.sea && a1.nation == n.index) { g.setColor(new Color(255, 255, 255, 80)); g.setStroke(new BasicStroke((float) (0.8 / sc))); g.draw(sh.arcPath[a]); }
        }
        g.setTransform(new AffineTransform());
        g.setPaint(new RadialGradientPaint(w / 2f, h / 2f, Math.max(w, h) * 0.62f, new float[]{0.55f, 1f}, new Color[]{new Color(0, 0, 0, 0), new Color(0, 0, 0, 150)}));
        g.fillRect(0, 0, w, h);
        g.dispose();
        minimaps.put(n.index, img);
        return img;
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
                @Override public void mouseClicked(MouseEvent e) { Audio.get().play(Synth.Sfx.CLICK); select(n); if (e.getClickCount() == 2) onStart.accept(new Choice(n.index, ironman.isOn(), warfare.core.Scenario.values()[scenarioPick.selected()])); }
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
