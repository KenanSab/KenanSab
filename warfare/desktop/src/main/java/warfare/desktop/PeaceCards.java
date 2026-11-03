package warfare.desktop;

import warfare.core.GameState;
import warfare.core.Peace;

import java.util.ArrayList;
import java.util.List;

/** The peace-talks cards: pick terms, hear the other side's answer with its reasons, and take or leave a counter-offer. */
final class PeaceCards {
    private PeaceCards() {}

    private static String pct(double v) { return Math.round(v * 100) + "%"; }

    static void open(GameUi ui, int target) {
        GameState gs = ui.gs;
        int me = gs.player;
        if (target < 0 || target == me) { GameUi.notify(new GameState.Result(false, "Select a nation in the list first.")); return; }
        if (!gs.war[me][target]) { GameUi.notify(new GameState.Result(false, "You are not at war with " + gs.nationName(target) + ".")); return; }
        Peace.Situation s = gs.peaceSituation(me, target);
        StringBuilder sb = new StringBuilder("Peace talks with " + gs.nationName(target) + ".\n\n");
        sb.append("You hold ").append(pct(s.youHold())).append(" of their land; they hold ").append(pct(s.theyHold())).append(" of yours.\n");
        sb.append(s.armyRatio() >= 1.15 ? String.format("Your armies are %.1fx stronger.\n", s.armyRatio())
                : s.armyRatio() <= 0.87 ? String.format("Their armies are %.1fx stronger.\n", 1 / s.armyRatio()) : "The armies are evenly matched.\n");
        sb.append("War weariness: you ").append((int) s.yourWeariness()).append("%, them ").append((int) s.theirWeariness())
                .append("%. The war is ").append(s.warDays()).append(" days old.\n\nChoose your terms. They may refuse.");
        List<Peace.Term> terms = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        terms.add(Peace.Term.WHITE); labels.add("White peace (current borders)");
        terms.add(Peace.Term.REPARATIONS); labels.add("Demand reparations (they pay about " + s.reparations() + ")");
        if (s.tribute() >= 20) { terms.add(Peace.Term.TRIBUTE); labels.add("Offer tribute (you pay " + s.tribute() + ")"); }
        if (s.returnable() > 0) { terms.add(Peace.Term.RETURN); labels.add("Return " + s.returnable() + " captured provinces"); }
        terms.add(Peace.Term.DEMAND); labels.add("Demand provinces (peace brush)");
        labels.add("Cancel");
        JComponentHolder.push(ui, "Peace talks", sb.toString(), labels, i -> {
            if (i >= terms.size()) return;
            if (terms.get(i) == Peace.Term.DEMAND) startBrush(ui, target); else propose(ui, target, terms.get(i), java.util.Set.of());
        });
    }

    /** Hands the map over to the brush; proposing from the brush bar comes back here with the painted provinces. */
    static void startBrush(GameUi ui, int target) {
        ui.map.startBrush(target, sel -> propose(ui, target, Peace.Term.DEMAND, sel), () -> {});
        ui.map.toast("Peace brush", "Paint the provinces you want, then press PROPOSE.", "castle", "PEACE TALKS");
    }

    static void propose(GameUi ui, int target, Peace.Term term) { propose(ui, target, term, java.util.Set.of()); }

    static void propose(GameUi ui, int target, Peace.Term term, java.util.Set<Integer> demand) {
        GameState gs = ui.gs;
        int me = gs.player;
        Peace.Talk talk = gs.assessPeace(me, target, term, demand);   // before negotiating: a refusal starts a cooldown that would hide the reasons
        GameState.Result r = gs.negotiatePeace(me, target, term, demand);
        ui.map.refreshColors();
        ui.refresh();
        Audio.get().play(r.ok ? Synth.Sfx.CONFIRM : Synth.Sfx.ERROR);
        StringBuilder sb = new StringBuilder(r.ok ? r.message : talk.message);
        if (!talk.reasons.isEmpty()) sb.append("\n\nTheir reasoning:\n").append(String.join("\n", talk.reasons));
        List<String> labels = new ArrayList<>();
        Peace.Term counter = r.ok ? null : talk.counter;
        if (counter != null) {
            sb.append("\n\nCounter-offer: they would agree to peace if you ")
              .append(counter == Peace.Term.TRIBUTE ? "pay " + talk.counterPay + " in tribute." : "return the " + talk.counterPay + " provinces you took from them.");
            labels.add(counter == Peace.Term.TRIBUTE ? "Accept: pay " + talk.counterPay + " tribute" : "Accept: return " + talk.counterPay + " provinces");
        }
        java.util.Set<Integer> partial = r.ok ? null : talk.counterDemand;
        if (partial != null) {
            sb.append("\n\nCounter-offer: they would cede ").append(partial.size()).append(partial.size() == 1 ? " province" : " provinces").append(" of the ").append(demand.size()).append(" you asked for.");
            labels.add("Accept: take those " + partial.size());
        }
        labels.add(r.ok ? "Good" : "Close");
        final Peace.Term accept = counter;
        JComponentHolder.push(ui, r.ok ? "Peace signed" : "Peace refused", sb.toString(), labels, i -> {
            if (accept != null && i == 0) propose(ui, target, accept);
            else if (partial != null && i == 0) propose(ui, target, Peace.Term.DEMAND, partial);
        });
    }

    /** Tiny helper so both cards are built the same way. */
    private static final class JComponentHolder {
        static void push(GameUi ui, String title, String text, List<String> labels, java.util.function.IntConsumer pick) {
            ui.choose(title, "general", text, labels, pick, () -> {});
        }
    }
}
