package warfare.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import warfare.core.NationState.Track;

/**
 * Covert operations: cyber attacks, disinformation, insurgents, sabotage and technology theft.
 * Success depends on the agent network you have inside the target and on both sides' intelligence level.
 * Attribution is its own piece of fog: a successful operation may be traced to you, or go unnoticed, or pin the blame on somebody else.
 */
public final class Ops {
    private Ops() {}

    public enum Op {
        CYBER("Cyberattack", 60, "Blinds their radar, satellites and air recon for 25 days and wrecks part of their treasury. Needs Intelligence research."),
        DISINFO("Disinformation", 40, "Phantom contacts appear on their map, their approval and stability fall."),
        INSURGENTS("Fund insurgents", 50, "Unrest flares in their provinces nearest to your territory; rebels may rise."),
        SABOTAGE("Sabotage", 45, "Destroys buildings in their richest provinces and halts construction."),
        STEAL("Steal technology", 90, "You gain a technology level they have and you lack. Hard, and long to recover from.");
        public final String label, hint;
        public final int cost;
        Op(String l, int c, String h) { label = l; cost = c; hint = h; }
        int cooldown() { return this == STEAL ? 120 : 30; }
    }

    public record Estimate(boolean available, String why, double success, double trace, int cooldownLeft) {}

    private static String key(int target, Op op) { return target + ":" + op.ordinal(); }

    public static Estimate estimate(GameState gs, int a, int b, Op op) {
        NationState s = gs.ns[a];
        if (a == b || !gs.ns[b].alive) return new Estimate(false, "Invalid target.", 0, 0, 0);
        if (gs.isAlly(a, b)) return new Estimate(false, "No spying on allies.", 0, 0, 0);
        double net = s.agents.getOrDefault(b, 0.0);
        if (op == Op.CYBER ? s.tech[2] < 1 : net < 10) {
            return new Estimate(false, op == Op.CYBER ? "Needs Intelligence research." : "Needs an agent there (10+).", 0, 0, 0);
        }
        if (op == Op.STEAL && bestStealTrack(gs, a, b) == null) return new Estimate(false, "They have nothing you lack.", 0, 0, 0);
        int cd = Math.max(0, s.opCooldown.getOrDefault(key(b, op), 0) - gs.day);
        if (cd > 0) return new Estimate(false, "Recovering for " + cd + " more days.", 0, 0, cd);
        if (s.money < op.cost) return new Estimate(false, "Need " + op.cost + " money.", 0, 0, 0);
        double success = 0.38 + 0.004 * net + 0.07 * s.tech[2] - 0.05 * gs.ns[b].tech[2];
        success *= switch (op) { case STEAL -> 0.6; case SABOTAGE -> 0.85; case INSURGENTS -> 0.9; default -> 1.0; };
        double trace = 0.20 + 0.08 * gs.ns[b].tech[2] + 0.002 * gs.ns[b].agents.getOrDefault(a, 0.0) - 0.003 * net;
        return new Estimate(true, "", Math.max(0.12, Math.min(0.9, success)), Math.max(0.1, Math.min(0.85, trace)), 0);
    }

    static Track bestStealTrack(GameState gs, int a, int b) {
        Track best = null; int gap = 0;
        for (Track t : Track.values()) {
            int d = gs.ns[b].tech[t.ordinal()] - gs.ns[a].tech[t.ordinal()];
            if (d > gap) { gap = d; best = t; }
        }
        return best;
    }

    public static GameState.Result run(GameState gs, int a, int b, Op op) {
        Estimate e = estimate(gs, a, b, op);
        if (!e.available()) return new GameState.Result(false, e.why());
        NationState s = gs.ns[a];
        s.money -= op.cost;
        s.opCooldown.put(key(b, op), gs.day + op.cooldown());
        Random rnd = new Random(gs.day * 7919L + a * 104729L + b * 31L + op.ordinal());
        String them = gs.nationName(b), us = gs.nationName(a);
        if (rnd.nextDouble() >= e.success()) {
            s.agents.merge(b, 0.0, (x, y) -> x * 0.5);
            Relations.change(gs, b, a, -20);
            String msg = "COVERT: " + op.label.toLowerCase() + " by " + us + " against " + them + " was caught.";
            if (a == gs.player || b == gs.player) gs.news(msg);
            return new GameState.Result(false, op.label + " failed: your agents were caught and half your network there is lost.");
        }
        String detail = apply(gs, a, b, op, rnd);
        // attribution
        boolean traced = rnd.nextDouble() < e.trace();
        String attribution;
        if (traced) {
            Relations.change(gs, b, a, -25);
            attribution = us + " is named as the source.";
        } else {
            List<Integer> suspects = new ArrayList<>();
            for (int c = 0; c < gs.N; c++) if (c != a && c != b && gs.ns[c].alive && gs.opinion[b][c] < 10) suspects.add(c);
            if (!suspects.isEmpty() && rnd.nextDouble() < 0.45) {
                int sus = suspects.get(rnd.nextInt(suspects.size()));
                Relations.change(gs, b, sus, -12);
                attribution = "The source is unknown; suspicion falls on " + gs.nationName(sus) + ".";
            } else attribution = "The source is unknown.";
        }
        String report = "COVERT: " + them + " suffers " + op.label.toLowerCase() + ". " + attribution;
        if (b == gs.player) gs.news(report + " (" + detail + ")");
        else if (a == gs.player) gs.news("COVERT: " + op.label + " against " + them + " succeeded. " + (traced ? "They know it was us." : "Undetected."));
        return new GameState.Result(true, op.label + " succeeded: " + detail + (traced ? " They traced it to you." : " Undetected."));
    }

    private static String apply(GameState gs, int a, int b, Op op, Random rnd) {
        NationState t = gs.ns[b];
        switch (op) {
            case CYBER -> {
                t.blackoutUntil = Math.max(t.blackoutUntil, gs.day + 25);
                double loss = Math.min(Math.max(0, t.money) * 0.10, 150);
                t.money -= loss;
                return "their radar, satellites and air recon are dark for 25 days (" + (int) loss + " money lost)";
            }
            case DISINFO -> {
                for (int i = 0; i < t.approval.length; i++) t.approval[i] = Math.max(0, t.approval[i] - 6);
                t.stability = Math.max(0, t.stability - 3);
                int ghosts = phantoms(gs, a, b, rnd);
                return "approval and stability fall" + (ghosts > 0 ? ", " + ghosts + " phantom contacts planted" : "");
            }
            case INSURGENTS -> {
                List<Integer> provs = new ArrayList<>();
                for (int p = 0; p < gs.P; p++) if (gs.owner[p] == b) provs.add(p);
                if (provs.isEmpty()) return "nothing to inflame";
                provs.sort((x, y) -> Double.compare(nearestOwn(gs, a, x), nearestOwn(gs, a, y)));
                int n = Math.min(3, provs.size());
                for (int i = 0; i < n; i++) gs.unrest[provs.get(i)] = Math.min(100, gs.unrest[provs.get(i)] + 32);
                return "unrest flares in " + n + " provinces";
            }
            case SABOTAGE -> {
                List<Integer> provs = new ArrayList<>();
                for (int p = 0; p < gs.P; p++) if (gs.owner[p] == b && (gs.factory[p] > 0 || gs.radar[p] > 0 || gs.airfield[p] > 0 || gs.fort[p] > 0)) provs.add(p);
                provs.sort((x, y) -> Double.compare(gs.world.provinces.get(y).gdp, gs.world.provinces.get(x).gdp));
                int hit = 0;
                for (int i = 0; i < Math.min(2, provs.size()); i++) {
                    int p = provs.get(i);
                    if (gs.factory[p] > 0) gs.factory[p]--;
                    else if (gs.radar[p] > 0) gs.radar[p]--;
                    else if (gs.airfield[p] > 0) gs.airfield[p]--;
                    else if (gs.fort[p] > 0) gs.fort[p]--;
                    hit++;
                }
                if (!t.constructions.isEmpty()) t.constructions.remove(0);
                return hit == 0 ? "nothing worth destroying" : "buildings destroyed in " + hit + " provinces, construction halted";
            }
            default -> {
                Track tr = bestStealTrack(gs, a, b);
                if (tr == null) return "nothing to steal";
                gs.ns[a].tech[tr.ordinal()]++;
                return "you gain " + tr.label + " level " + gs.ns[a].tech[tr.ordinal()];
            }
        }
    }

    private static double nearestOwn(GameState gs, int a, int p) {
        double best = 1e18;
        for (int q : gs.world.nations.get(a).provinces) best = Math.min(best, gs.dist(p, q));
        return best;
    }

    /** Fake contacts in places the victim cannot see clearly. They fade like any stale sighting. */
    private static int phantoms(GameState gs, int a, int b, Random rnd) {
        Intel in = gs.intelOf(b);
        List<Integer> spots = new ArrayList<>();
        for (int p = 0; p < gs.P; p++) {
            if (gs.owner[p] == b || gs.isSea(p) || in.quality[p] >= 2) continue;
            for (int nb : gs.world.provinces.get(p).neighbours) if (gs.owner[nb] == b) { spots.add(p); break; }
        }
        int n = Math.min(spots.size(), 2 + rnd.nextInt(3));
        for (int i = 0; i < n; i++) {
            int p = spots.remove(rnd.nextInt(spots.size()));
            Intel.Sighting s = new Intel.Sighting();
            s.armyId = -1000 - gs.day * 10 - i; s.province = p; s.day = gs.day; s.quality = 1;
            s.ownerKnown = false; s.owner = a; s.strengthEstimate = 0;
            in.sightings.put(s.armyId, s);
        }
        return n;
    }
}
