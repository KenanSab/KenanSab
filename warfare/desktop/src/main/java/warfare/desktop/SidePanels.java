package warfare.desktop;

import warfare.core.*;
import warfare.core.NationState.AirWing;
import warfare.core.NationState.Mission;
import warfare.core.NationState.Track;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.util.*;
import java.util.List;

/** The right-hand tabs. Each panel only ever shows what the player's Intel allows. */
final class SidePanels {
    private SidePanels() {}

    static JEditorPane html() {
        JEditorPane p = new JEditorPane("text/html", "");
        p.setEditable(false);
        p.setBackground(new Color(41, 40, 38));
        return p;
    }

    static String esc(String s) { return s.replace("&", "&amp;").replace("<", "&lt;"); }
    static String doc(String body) { return "<html><body style='font-family:\""+Fonts.uiFamily()+"\",sans-serif;font-size:12px;color:#e6ebf2'>" + body + "</body></html>"; }

    static void message(Component c, GameState.Result r) {
        if (r == null) return;
        GameUi.notify(r);
    }
}
