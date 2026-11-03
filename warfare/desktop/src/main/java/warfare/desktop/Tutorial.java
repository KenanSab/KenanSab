package warfare.desktop;

import warfare.core.Army;

import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.util.List;

/** Guided first steps. The card advances by itself when the player does what it asks, or with Next. */
final class Tutorial {
    record Step(String title, String text, String icon, boolean needsAction) {}

    static final List<Step> STEPS = List.of(
            new Step("Welcome, Commander", "This is the strategic map. Drag to pan and use the mouse wheel to zoom. Every province is a real administrative region.", "general", false),
            new Step("Start the clock", "Press SPACE (or the round button) to run time. Keys 1-5 change the speed. The game is real time, and you can pause and give orders any moment.", "time", true),
            new Step("Select an army", "Left-click one of your army flags on the map. The right panel shows its units, morale and battle orders.", "battle", true),
            new Step("March", "With an army selected, RIGHT-click any province to march there. The dashed line shows the route. Armies fight when they meet an enemy.", "t_flank", true),
            new Step("Fog of war", "You only see what your units, provinces, radar, aircraft, agents and allies reveal. Press M until the Intel map shows: green is exact, lime an estimate, amber a contact, dark unseen. Old sightings fade.", "recon", true),
            new Step("Tactic cards", "In a battle, each army uses a tactic card. Every card counters two others, and unit mix, terrain and surprise matter. Pick cards yourself, or leave the army on General (auto), which only uses what you can see.", "t_ambush", false),
            new Step("Build and recruit", "The Production tab recruits forces, researches upgrades and forms air wings. Provinces you own can build factories, forts, radar and more.", "industry", false),
            new Step("The economy panel", "Press E or the Economy button for the budget: tax rate, development, infrastructure, education, welfare, security and military funding, plus mobilization, fuel rationing and money printing. Stars on the map mark capitals.", "money", false),
            new Step("Diplomacy and spies", "The Diplomacy tab handles war, negotiated peace, alliances, sanctions and agents. Agents inside a country reveal secret pacts and its forces. Click any capital star for that country's screen: government, approval, relations and military.", "spy", false),
            new Step("You are ready", "This is a sandbox with no fixed end. Watch stability, oil and morale, and keep your intelligence fresh. Good luck.", "trophy", false));

    int step;
    boolean active;
    private Rectangle next = new Rectangle(), skip = new Rectangle(), card = new Rectangle();
    private boolean wasPaused = true;
    private int startDay;

    Tutorial(boolean active, int day) { this.active = active; this.startDay = day; }

    void restart(int day) { step = 0; active = true; startDay = day; }

    Step current() { return STEPS.get(step); }

    /** Called every tick and click; moves on when the requested action has happened. */
    void update(GameUi ui) {
        if (!active) return;
        boolean done = switch (step) {
            case 1 -> !ui.paused && ui.gs.day > startDay;
            case 2 -> ui.map.selArmy >= 0 && ui.gs.armies.containsKey(ui.map.selArmy) && ui.gs.armies.get(ui.map.selArmy).owner == ui.gs.player;
            case 3 -> { Army a = ui.gs.armies.get(ui.map.selArmy); yield a != null && a.owner == ui.gs.player && !a.path.isEmpty(); }
            case 4 -> ui.map.mode == MapPanel.Mode.INTEL;
            default -> false;
        };
        if (done) advance();
    }

    void advance() {
        if (step + 1 >= STEPS.size()) { active = false; return; }
        step++;
        Audio.get().play(Synth.Sfx.CONFIRM);
    }

    boolean click(Point p) {
        if (!active) return false;
        if (next.contains(p)) { advance(); return true; }
        if (skip.contains(p)) { active = false; return true; }
        return card.contains(p);          // clicks on the card itself never fall through to the map
    }

    void paint(Graphics2D g, int width, int height) {
        if (!active) return;
        Step s = current();
        int w = 420, x = (width - w) / 2;
        g.setFont(Fonts.ui(13.5f));
        FontMetrics fm = g.getFontMetrics();
        java.util.List<String> lines = wrap(s.text(), fm, w - 36);
        int h = 74 + lines.size() * (fm.getHeight() + 1) + 46;
        int y = height - h - 112;
        card = new Rectangle(x, y, w, h);
        Skin.shadow(g, x, y, w, h, 8, 6, 130);
        RoundRectangle2D body = new RoundRectangle2D.Float(x, y, w, h, 8, 8);
        Skin.plate(g, body, Skin.Kind.PANEL, 1f);
        Skin.frame(g, x + 0.5f, y + 0.5f, w - 1, h - 1, 8, true, true);
        g.drawImage(Assets.icon(s.icon(), 34, Ui.ACCENT), x + 20, y + 18, null);
        g.setFont(Fonts.label(12.5f));
        g.setColor(Ui.MUTED);
        g.drawString("TUTORIAL  " + (step + 1) + " / " + STEPS.size(), x + 66, y + 28);
        g.setFont(Fonts.title(25f));
        g.setColor(Color.WHITE);
        g.drawString(s.title().toUpperCase(), x + 66, y + 52);
        Skin.rule(g, x + 20, y + 62, w * 0.55f);
        g.setFont(Fonts.ui(13.5f));
        g.setColor(new Color(222, 220, 214));
        int ty = y + 86;
        for (String l : lines) { g.drawString(l, x + 20, ty); ty += fm.getHeight() + 1; }
        int by = y + h - 42;
        skip = new Rectangle(x + 20, by, 110, 28);
        RoundRectangle2D sk = new RoundRectangle2D.Float(skip.x, skip.y, skip.width, skip.height, 6, 6);
        Skin.plate(g, sk, Skin.Kind.RAISED, 1f);
        g.setColor(Skin.HAIR_STRONG); g.setStroke(new BasicStroke(1f)); g.draw(sk);
        g.setFont(Fonts.label(14f));
        g.setColor(Ui.MUTED);
        g.drawString("Skip tutorial", skip.x + 14, skip.y + 19);
        next = new Rectangle(x + w - 130, by, 110, 28);
        boolean last = step == STEPS.size() - 1;
        RoundRectangle2D nx = new RoundRectangle2D.Float(next.x, next.y, next.width, next.height, 6, 6);
        Skin.plate(g, nx, s.needsAction() ? Skin.Kind.RAISED : Skin.Kind.GOLD, 1f);
        g.setColor(s.needsAction() ? Skin.HAIR_STRONG : new Color(122, 90, 34, 210)); g.draw(nx);
        g.setColor(s.needsAction() ? Ui.MUTED : Wf.INK);
        String label = last ? "Finish" : s.needsAction() ? "Skip step" : "Next";
        g.drawString(label, next.x + (next.width - g.getFontMetrics().stringWidth(label)) / 2, next.y + 19);
    }

    private static java.util.List<String> wrap(String text, FontMetrics fm, int width) {
        java.util.List<String> out = new java.util.ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (fm.stringWidth(line + " " + word) > width && line.length() > 0) { out.add(line.toString()); line = new StringBuilder(); }
            if (line.length() > 0) line.append(' ');
            line.append(word);
        }
        if (line.length() > 0) out.add(line.toString());
        return out;
    }
}
