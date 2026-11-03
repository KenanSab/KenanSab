package warfare.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Peace negotiations. The side being asked weighs how the war is going for it (territory, armies, exhaustion, stability,
 * a fallen capital, how long it has dragged on) against its reluctance to quit, then the proposed terms shift the balance.
 * A refusal comes with reasons, and a close call produces a counter-offer.
 */
public final class Peace {
    private Peace() {}

    public enum Term {
        WHITE("White peace", "End the war on the current front lines."),
        REPARATIONS("Peace with reparations", "They pay you for the war. Harder to get."),
        TRIBUTE("Offer tribute", "You pay them to stop. Easier to get."),
        RETURN("Return captured provinces", "Give back what you took from them. Much easier to get."),
        DEMAND("Demand provinces", "Paint the provinces you want with the peace brush. Each one makes peace harder.");
        public final String label, hint;
        Term(String l, String h) { label = l; hint = h; }
    }

    /** The state of the war between two nations, as the proposer sees it. */
    public record Situation(double youHold, double theyHold, double armyRatio, double yourWeariness, double theirWeariness,
                            int warDays, int returnable, int reparations, int tribute) {}

    public static final class Talk {
        public boolean accepted;
        public double score;
        public final List<String> reasons = new ArrayList<>();
        public Term counter;           // a different set of terms they would accept, if any
        public int counterPay;
        public Set<Integer> counterDemand;   // for a refused demand: the part of it they would give up
        public String message = "";
    }

    static final int TRUCE_DAYS = 90, MIN_WAR_DAYS = 20;

    /** Share (weighted by population) of {@code victim}'s home provinces that {@code holder} owns right now. */
    static double share(GameState gs, int victim, int holder) {
        double total = 0, held = 0;
        for (int p : gs.world.nations.get(victim).provinces) {
            double w = 1 + gs.world.provinces.get(p).pop;
            total += w;
            if (gs.owner[p] == holder) held += w;
        }
        return total <= 0 ? 0 : held / total;
    }

    static int returnable(GameState gs, int a, int b) {
        int n = 0;
        for (int p : gs.world.nations.get(b).provinces) if (gs.owner[p] == a) n++;
        return n;
    }

    public static Situation situation(GameState gs, int a, int b) {
        double hpA = Math.max(1, gs.nationHp(a)), hpB = Math.max(1, gs.nationHp(b));
        int reps = (int) Math.round(Math.min(Math.max(0, gs.ns[b].money) * 0.4, 600));
        int trib = (int) Math.round(Math.min(Math.max(0, gs.ns[a].money), Math.max(40, gs.ns[a].money * 0.25)));
        return new Situation(share(gs, b, a), share(gs, a, b), hpA / hpB, gs.ns[a].warWeariness, gs.ns[b].warWeariness,
                gs.day - gs.warStart[a][b], returnable(gs, a, b), reps, trib);
    }

    private record Factor(String text, double value) {}

    /** How {@code b} answers a proposal from {@code a}. No side effects. */
    public static Talk assess(GameState gs, int a, int b, Term term) { return assess(gs, a, b, term, Set.of()); }

    /** What it costs {@code b}'s willingness to give up these provinces: dearer if we do not hold them, much dearer for the capital. */
    public static double demandCost(GameState gs, int a, int b, Set<Integer> demand) {
        double total = 0;
        for (int p = 0; p < gs.P; p++) if (gs.owner[p] == b) total += 1 + gs.world.provinces.get(p).pop;
        total = Math.max(1, total);
        double cost = 0;
        for (int p : demand) cost += provinceCost(gs, a, b, p, total);
        return cost;
    }

    static double provinceCost(GameState gs, int a, int b, int p, double total) {
        double v = (1 + gs.world.provinces.get(p).pop) / total;
        double c = (gs.controller[p] == a ? 0.9 : 2.0) * v * 1.2 + 0.03;
        if (p == gs.world.nations.get(b).capital) c += 0.5;
        return c;
    }

    public static Talk assess(GameState gs, int a, int b, Term term, Set<Integer> demand) {
        Talk t = new Talk();
        if (!gs.war[a][b]) { t.message = "You are not at war."; return t; }
        Situation s = situation(gs, a, b);
        if (s.warDays() < MIN_WAR_DAYS) {
            t.message = gs.nationName(b) + " will not even talk: the war has barely begun.";
            t.reasons.add("Wait at least " + (MIN_WAR_DAYS - s.warDays()) + " more days.");
            return t;
        }
        if (gs.day < gs.peaceCooldown[a][b]) {
            t.message = gs.nationName(b) + " refuses to talk for another " + (gs.peaceCooldown[a][b] - gs.day) + " days.";
            return t;
        }
        if (term == Term.RETURN && s.returnable() == 0) { t.message = "You hold none of their provinces."; return t; }
        if (term == Term.TRIBUTE && s.tribute() < 20) { t.message = "You cannot afford a tribute."; return t; }
        if (term == Term.DEMAND) {
            demand = new LinkedHashSet<>(demand);
            demand.removeIf(p -> gs.owner[p] != b);
            if (demand.isEmpty()) { t.message = "Paint at least one of their provinces first."; return t; }
        }

        List<Factor> f = new ArrayList<>();
        double occ = s.youHold() - s.theyHold();
        f.add(new Factor(occ >= 0 ? "You hold " + pct(s.youHold()) + " of their land" : "They hold " + pct(s.theyHold()) + " of your land", 1.0 * occ));
        double mil = Math.max(-1, Math.min(1, Math.log(s.armyRatio()) / Math.log(3)));
        f.add(new Factor(mil >= 0 ? "Your armies outmatch theirs" : "Their armies are stronger than yours", 0.5 * mil));
        double wearB = gs.ns[b].warWeariness / 100.0, wearA = gs.ns[a].warWeariness / 100.0;
        f.add(new Factor("War weariness at home (" + (int) gs.ns[b].warWeariness + "%)", 0.55 * wearB + 0.25 * (wearB - wearA)));
        double stab = gs.ns[b].stability < 20 ? 0.6 : gs.ns[b].stability < 35 ? 0.3 : 0;
        if (stab > 0) f.add(new Factor("Their government is shaking", stab));
        if (gs.owner[gs.world.nations.get(b).capital] == a) f.add(new Factor("Their capital has fallen", 0.5));
        f.add(new Factor("The war has dragged on for " + s.warDays() + " days", Math.min(0.6, s.warDays() / 450.0)));
        double op = Math.max(-0.25, Math.min(0.25, gs.opinion[b][a] / 400.0));
        f.add(new Factor(op >= 0 ? "They think well of you" : "They despise you", op));
        Government g = gs.ns[b].gov;
        double gov = g.elections ? 0.08 : (g == Government.AUTOCRACY || g == Government.JUNTA ? -0.10 : 0);
        if (gov != 0) f.add(new Factor(gov > 0 ? "A democracy tires of war" : "A regime that cannot afford to look weak", gov));
        f.add(new Factor("Reluctance to quit", -0.42));
        switch (term) {
            case REPARATIONS -> f.add(new Factor("You demand reparations", -0.32));
            case TRIBUTE -> f.add(new Factor("You offer them " + s.tribute() + " in tribute", 0.22 + Math.min(0.25, s.tribute() / 800.0)));
            case RETURN -> f.add(new Factor("You return " + s.returnable() + " captured provinces", 0.30 + 0.06 * Math.min(6, s.returnable())));
            case DEMAND -> f.add(new Factor("You demand " + demand.size() + " province" + (demand.size() == 1 ? "" : "s")
                    + (demand.contains(gs.world.nations.get(b).capital) ? ", including their capital" : ""), -demandCost(gs, a, b, demand)));
            default -> { }
        }
        double score = 0;
        for (Factor x : f) score += x.value();
        score += (new Random(gs.day * 31L + a * 131L + b * 7L).nextDouble() - 0.5) * 0.12;   // same answer if you ask twice on the same day
        t.score = score;
        t.accepted = score >= 0;
        f.sort(Comparator.comparingDouble((Factor x) -> -Math.abs(x.value())));
        for (int i = 0; i < f.size() && t.reasons.size() < 4; i++) {
            Factor x = f.get(i);
            if (Math.abs(x.value()) < 0.06) continue;
            t.reasons.add((x.value() > 0 ? "+ " : "- ") + x.text());
        }
        if (t.accepted) t.message = gs.nationName(b) + " accepts.";
        else {
            t.message = gs.nationName(b) + " refuses" + (term == Term.WHITE ? " a white peace." : ".");
            if (term == Term.DEMAND) {
                double total = 1;
                for (int p = 0; p < gs.P; p++) if (gs.owner[p] == b) total += 1 + gs.world.provinces.get(p).pop;
                double room = score + demandCost(gs, a, b, demand);          // how much they could give up at most
                List<Integer> byCost = new ArrayList<>(demand);
                final double tot = total;
                byCost.sort(Comparator.comparingDouble(p -> provinceCost(gs, a, b, p, tot)));
                Set<Integer> part = new LinkedHashSet<>();
                double used = 0;
                for (int p : byCost) { double c = provinceCost(gs, a, b, p, total); if (used + c <= room) { part.add(p); used += c; } }
                if (!part.isEmpty() && part.size() < demand.size()) t.counterDemand = part;
            } else
            if (score > -0.45 && term != Term.DEMAND) {
                for (Term alt : new Term[]{Term.TRIBUTE, Term.RETURN}) {
                    if (alt == term || (alt == Term.RETURN && s.returnable() == 0) || (alt == Term.TRIBUTE && s.tribute() < 20)) continue;
                    double gain = alt == Term.TRIBUTE ? 0.22 + Math.min(0.25, s.tribute() / 800.0) : 0.30 + 0.06 * Math.min(6, s.returnable());
                    double base = score - (term == Term.TRIBUTE ? 0.22 + Math.min(0.25, s.tribute() / 800.0) : term == Term.RETURN ? 0.30 + 0.06 * Math.min(6, s.returnable()) : term == Term.REPARATIONS ? -0.32 : 0);
                    if (base + gain >= 0) { t.counter = alt; t.counterPay = alt == Term.TRIBUTE ? s.tribute() : s.returnable(); break; }
                }
            }
        }
        return t;
    }

    private static String pct(double v) { return Math.round(v * 100) + "%"; }

    /** Proposes the terms; applies them if accepted, otherwise starts a cooldown. */
    static GameState.Result negotiate(GameState gs, int a, int b, Term term) { return negotiate(gs, a, b, term, Set.of()); }

    static GameState.Result negotiate(GameState gs, int a, int b, Term term, Set<Integer> demand) {
        Talk t = assess(gs, a, b, term, demand);
        if (!gs.war[a][b]) return new GameState.Result(false, t.message);
        if (!t.accepted) {
            if (gs.day >= gs.peaceCooldown[a][b] && t.message.contains("refuses")) {
                gs.peaceCooldown[a][b] = gs.peaceCooldown[b][a] = gs.day + (a == gs.player ? 25 : 40);
                Relations.change(gs, b, a, -1);
            }
            return new GameState.Result(false, t.message);
        }
        Situation s = situation(gs, a, b);
        switch (term) {
            case REPARATIONS -> { gs.ns[b].money -= s.reparations(); gs.ns[a].money += s.reparations(); }
            case TRIBUTE -> { gs.ns[a].money -= s.tribute(); gs.ns[b].money += s.tribute(); }
            case RETURN -> {
                for (int p : gs.world.nations.get(b).provinces)
                    if (gs.owner[p] == a) { gs.owner[p] = gs.controller[p] = b; gs.unrest[p] = Math.min(gs.unrest[p], 20); gs.occupy[p] = 0; }
            }
            case DEMAND -> {
                for (int p : demand) {
                    if (gs.owner[p] != b) continue;
                    gs.owner[p] = gs.controller[p] = a;
                    gs.occupy[p] = 0;
                    gs.unrest[p] = Math.max(gs.unrest[p], 50);               // a province handed over by treaty is restless
                    if (a == gs.player) gs.stCaptures++;
                }
            }
            default -> { }
        }
        gs.makePeace(a, b);
        Relations.both(gs, a, b, term == Term.DEMAND ? -6 : 8);
        return new GameState.Result(true, term == Term.DEMAND ? "Peace with " + gs.nationName(b) + ": " + demand.size() + " province" + (demand.size() == 1 ? "" : "s") + " ceded."
                : "Peace with " + gs.nationName(b) + " (" + term.label.toLowerCase() + ").");
    }
}
