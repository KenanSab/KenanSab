package warfare.desktop;

import warfare.core.WorldData;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** New-game screen: searchable list of every nation with a flag preview and key stats. */
final class NationPicker extends JPanel {
    private final List<WorldData.NationInfo> all = new ArrayList<>();
    private final DefaultListModel<WorldData.NationInfo> model = new DefaultListModel<>();
    private final JList<WorldData.NationInfo> list = new JList<>(model);
    private final JPanel preview = new JPanel();
    private final JTextField search = new JTextField();

    NationPicker(WorldData w) {
        super(new BorderLayout(14, 0));
        setBackground(Ui.PANEL);
        setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        all.addAll(w.nations);
        all.sort(Comparator.comparing(n -> n.name));
        filter("");
        list.setCellRenderer((l, n, i, sel, foc) -> {
            JPanel p = new JPanel(new BorderLayout(10, 0));
            p.setOpaque(true);
            p.setBackground(sel ? Ui.CARD_HI : Ui.PANEL);
            p.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
            p.add(new JLabel(new ImageIcon(Assets.flag(n, 30))), BorderLayout.WEST);
            JLabel name = Ui.label(n.name, 15f, sel ? Ui.TEXT : new Color(200, 208, 224));
            p.add(name, BorderLayout.CENTER);
            if (n.nukes > 0) p.add(new JLabel(new ImageIcon(Assets.icon("nuke", 16, Ui.RED))), BorderLayout.EAST);
            return p;
        });
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setBackground(Ui.PANEL);
        list.addListSelectionListener(e -> showPreview(list.getSelectedValue()));
        search.putClientProperty("JTextField.placeholderText", "Search nations...");
        search.setFont(Fonts.ui(14f));
        search.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { filter(search.getText()); }
            public void removeUpdate(DocumentEvent e) { filter(search.getText()); }
            public void changedUpdate(DocumentEvent e) { filter(search.getText()); }
        });
        JPanel left = new JPanel(new BorderLayout(0, 8));
        left.setOpaque(false);
        left.add(search, BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(list);
        sp.setBorder(BorderFactory.createLineBorder(Ui.LINE));
        left.add(sp, BorderLayout.CENTER);
        left.setPreferredSize(new Dimension(300, 480));
        preview.setLayout(new BoxLayout(preview, BoxLayout.Y_AXIS));
        preview.setOpaque(false);
        add(left, BorderLayout.WEST);
        add(preview, BorderLayout.CENTER);
        for (int i = 0; i < model.size(); i++) if (model.get(i).tag.equals("FRA")) { list.setSelectedIndex(i); list.ensureIndexIsVisible(i); }
        if (list.getSelectedIndex() < 0 && model.size() > 0) list.setSelectedIndex(0);
    }

    private void filter(String q) {
        String s = q.trim().toLowerCase();
        WorldData.NationInfo keep = list.getSelectedValue();
        model.clear();
        for (WorldData.NationInfo n : all) if (s.isEmpty() || n.name.toLowerCase().contains(s)) model.addElement(n);
        if (keep != null && model.contains(keep)) list.setSelectedValue(keep, true);
        else if (model.size() > 0) list.setSelectedIndex(0);
    }

    private void showPreview(WorldData.NationInfo n) {
        preview.removeAll();
        if (n == null) { preview.revalidate(); preview.repaint(); return; }
        JLabel flag = new JLabel(new ImageIcon(Assets.flag(n, 220)));
        flag.setAlignmentX(Component.LEFT_ALIGNMENT);
        preview.add(Ui.label("WARFARE  -  NOVEMBER 2026", 13f, Ui.MUTED));
        preview.add(Ui.gap(0, 10));
        preview.add(flag);
        preview.add(Ui.gap(0, 12));
        JLabel t = Ui.title(n.name.toUpperCase(), 44);
        t.setAlignmentX(Component.LEFT_ALIGNMENT);
        preview.add(t);
        String bloc = n.bloc.equals("NONE") ? "Non-aligned" : n.bloc;
        preview.add(Ui.label(n.continent + "  -  " + bloc, 14f, Ui.MUTED));
        preview.add(Ui.gap(0, 14));
        Ui.Card c = new Ui.Card(new BorderLayout());
        JPanel col = Ui.column();
        col.add(row("manpower", Ui.ACCENT, "Population", String.format("%,d", n.population)));
        col.add(row("money", Ui.GOLD, "GDP", String.format("$%,.0f M / yr", n.gdp)));
        col.add(row("battle", Ui.TEXT, "Provinces", String.valueOf(n.provinces.size())));
        col.add(row("nuke", n.nukes > 0 ? Ui.RED : Ui.MUTED, "Nuclear warheads", n.nukes > 0 ? String.valueOf(n.nukes) : "none"));
        col.add(row("oil", new Color(200, 160, 120), "Oil production", n.oilProduction > 0 ? String.format("%.1f", n.oilProduction) : "none"));
        c.add(col, BorderLayout.CENTER);
        c.setMaximumSize(new Dimension(420, 200));
        preview.add(c);
        preview.add(Box.createVerticalGlue());
        preview.revalidate(); preview.repaint();
    }

    private static JComponent row(String icon, Color tint, String name, String value) {
        JPanel r = Ui.row(Ui.imageLabel(Assets.icon(icon, 20, tint)), Ui.gap(8, 0), Ui.label(name, 14f, Ui.MUTED), Box.createHorizontalGlue(), Ui.label(value, 14.5f, Ui.TEXT));
        r.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
        return r;
    }

    WorldData.NationInfo selected() { return list.getSelectedValue(); }

    static int pick(JFrame owner, WorldData w) {
        NationPicker p = new NationPicker(w);
        JDialog d = new JDialog(owner, "Warfare - choose your nation", true);
        JButton go = Ui.button("START CAMPAIGN", "battle", d::dispose);
        go.setFont(Fonts.label(18f));
        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        south.setBackground(Ui.PANEL);
        south.add(go);
        d.setLayout(new BorderLayout());
        d.add(p, BorderLayout.CENTER);
        d.add(south, BorderLayout.SOUTH);
        d.setSize(900, 620);
        d.setLocationRelativeTo(owner);
        d.getRootPane().setDefaultButton(go);
        d.setVisible(true);
        WorldData.NationInfo n = p.selected();
        return n == null ? w.nationByTag.get("FRA") : n.index;
    }
}
