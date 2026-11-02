package warfare.desktop;

import javax.swing.*;
import java.awt.*;

/** Volume and gameplay preferences. */
final class SettingsDialog {
    private SettingsDialog() {}

    static void show(JFrame owner, Component parent) {
        JDialog d = new JDialog(owner, "Settings", true);
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(Ui.PANEL);
        p.setBorder(BorderFactory.createEmptyBorder(16, 18, 12, 18));
        p.add(Ui.title("SETTINGS", 28));
        p.add(Ui.section("Audio"));
        p.add(slider("Master volume", Settings.master, v -> Settings.master = v));
        p.add(slider("Music", Settings.music, v -> Settings.music = v));
        p.add(slider("Sound effects", Settings.sfx, v -> Settings.sfx = v));
        p.add(Ui.label(Audio.get().isAvailable() ? "Audio device ready." : "No audio device detected (the game stays silent).", 12.5f, Ui.MUTED));
        p.add(Ui.section("Gameplay"));
        JCheckBox auto = new JCheckBox("Autosave every 30 days", Settings.autosave);
        auto.addActionListener(e -> Settings.autosave = auto.isSelected());
        JCheckBox tut = new JCheckBox("Show tutorial hints on new games", Settings.tutorial);
        tut.addActionListener(e -> Settings.tutorial = tut.isSelected());
        for (JCheckBox c : new JCheckBox[]{auto, tut}) { c.setOpaque(false); c.setFont(Fonts.ui(14f)); c.setAlignmentX(Component.LEFT_ALIGNMENT); p.add(c); }
        p.add(Ui.gap(0, 12));
        JButton close = Ui.button("Close", null, d::dispose);
        close.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(close);
        d.setContentPane(p);
        d.pack();
        d.setSize(Math.max(d.getWidth(), 420), d.getHeight());
        d.setLocationRelativeTo(owner);
        d.addWindowListener(new java.awt.event.WindowAdapter() { @Override public void windowClosed(java.awt.event.WindowEvent e) { Settings.save(); } });
        d.setVisible(true);
    }

    private static JComponent slider(String name, double value, java.util.function.DoubleConsumer set) {
        JSlider s = new JSlider(0, 100, (int) Math.round(value * 100));
        s.addChangeListener(e -> set.accept(s.getValue() / 100.0));
        s.setOpaque(false);
        JPanel row = Ui.row(Ui.label(name, 14f, Ui.TEXT), Box.createHorizontalGlue(), s);
        s.setMaximumSize(new Dimension(220, 26));
        return row;
    }
}
