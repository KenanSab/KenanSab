package warfare.core;

import warfare.core.NationState.Track;

import java.util.*;

/** AI nations. They only act on their own Intel (fog of war applies), plus fuzzy strength estimates for peacetime planning. */
public final class Ai {
    private Ai() {}

    static void step(GameState gs, int n) {
        NationState s = gs.ns[n];
        if (!s.alive) return;
        boolean war = gs.atWarAny(n);
        if (gs.day % 12 == n % 12 && !war) considerWar(gs, n);
        if (war) { manageArmies(gs, n); considerPeace(gs, n); }
        economy(gs, n, war);
    }

    static void considerWar(GameState gs, int n) {
        WorldData.NationInfo ni = gs.world.nations.get(n);
        if (ni.aggression < 0.62 || gs.ns[n].stability < 40) return;
        int wars = 0;
        for (int a = 0; a < gs.N; a++) for (int b = a + 1; b < gs.N; b++) if (gs.war[a][b]) wars++;
        if (wars >= 5 || gs.rng.nextDouble() > 0.05) return;
        Set<Integer> neighbours = new LinkedHashSet<>();
        for (int pid : ni.provinces)
            for (int nb : gs.world.provinces.get(pid).neighbours)
                if (!gs.isSea(nb) && gs.owner[nb] >= 0 && gs.owner[nb] != n) neighbours.add(gs.owner[nb]);
        double mine = Math.max(1, gs.nationHp(n));
        int best = -1; double bestRatio = 2.2;
        for (int t : neighbours) {
            if (gs.isAlly(n, t) || !gs.ns[t].alive || gs.ns[t].nukes > 0 && ni.nukes == 0) continue;
            double theirs = Math.max(1, Vision.estimateStrength(gs, n, t));
            double ratio = mine / theirs;
            if (ratio > bestRatio) { bestRatio = ratio; best = t; }
        }
        if (best >= 0) gs.declareWar(n, best);
    }

    public static void manageArmies(GameState gs, int n) {
        Intel in = gs.intelOf(n);
        List<Army> mine = new ArrayList<>();
        for (Army a : gs.armies.values()) if (a.owner == n && a.battleId < 0 && a.path.isEmpty() && a.alive() && !a.embarked) mine.add(a);
        if (mine.isEmpty()) return;
        // enemy-held provinces next to land we control are the frontier
        Set<Integer> frontier = new LinkedHashSet<>();
        for (int p = 0; p < gs.P; p++) {
            if (gs.isSea(p) || gs.controller[p] != n && !gs.isAlly(n, gs.controller[p])) continue;
            for (int nb : gs.world.provinces.get(p).neighbours)
                if (!gs.isSea(nb) && in.knownOwner[nb] >= 0 && gs.atWar(n, in.knownOwner[nb]) && gs.atWar(n, gs.controller[nb])) frontier.add(nb);
        }
        List<Integer> targets = new ArrayList<>(frontier);
        Collections.shuffle(targets, gs.rng);
        for (Army a : mine) {
            if (targets.isEmpty()) {
                if (gs.rng.nextDouble() < 0.1) reposition(gs, a, n);
                continue;
            }
            double myHp = a.hp();
            int bestT = -1; double bestScore = 0.9;
            int tried = 0;
            for (int t : targets) {
                if (tried++ > 14) break;
                double est = 55 * (1 + 0.3 * in.knownFort[t]);
                for (Intel.Sighting sg : in.activeSightings(gs.day))
                    if (sg.province == t && sg.quality >= 2) est = Math.max(est, sg.strengthEstimate * (1 + 0.2 * in.knownFort[t]) * Intel.confidence(gs.day - sg.day) + 5);
                double d = gs.dist(a.province, t);
                double score = myHp / est - d / 4000.0;
                if (score > bestScore) { bestScore = score; bestT = t; }
            }
            if (bestT >= 0) gs.orderMove(a, bestT);
        }
    }

    static void reposition(GameState gs, Army a, int n) {
        // idle in peace-time: stay put; in war with no known frontier, march to the nearest border province
        WorldData.NationInfo ni = gs.world.nations.get(n);
        int best = -1; double bd = Double.MAX_VALUE;
        for (int pid : ni.provinces) {
            if (gs.controller[pid] != n) continue;
            for (int nb : gs.world.provinces.get(pid).neighbours) {
                if (gs.isSea(nb) || gs.owner[nb] < 0 || !gs.atWar(n, gs.owner[nb])) continue;
                double d = gs.dist(a.province, pid);
                if (d < bd) { bd = d; best = pid; }
            }
        }
        if (best >= 0 && best != a.province) gs.orderMove(a, best);
    }

    static void considerPeace(GameState gs, int n) {
        if (gs.day % 6 != n % 6) return;
        for (int e = 0; e < gs.N; e++) {
            if (!gs.war[n][e]) continue;
            double mine = Math.max(1, gs.nationHp(n)), theirs = Math.max(1, Vision.estimateStrength(gs, n, e));
            double lost = 1 - gs.ownedProvinces(n) / (double) Math.max(1, gs.world.nations.get(n).provinces.size());
            boolean wantPeace = mine < theirs * 0.6 || lost > 0.2 || gs.ns[n].warWeariness > 55 || gs.ns[n].stability < 30 || gs.day - gs.ns[n].lastWarDay > 160;
            if (!wantPeace) continue;
            if (e == gs.player) {
                boolean pending = false;
                for (GameEvent ev : gs.pendingEvents) if (ev.id.equals("peace:" + n)) pending = true;
                if (!pending && gs.rng.nextDouble() < 0.3)
                    Events.post(gs, new GameEvent("peace:" + n, gs.player, gs.nationName(n) + " proposes peace",
                            gs.nationName(n) + " is tired of the war and offers a ceasefire on current lines.", List.of("Accept peace", "Refuse")));
            } else if (e != n) gs.offerPeace(n, e);
        }
    }

    static void economy(GameState gs, int n, boolean war) {
        NationState s = gs.ns[n];
        if (gs.day % 30 == n % 30) {
            Budget b = Budget.forAi(war, s.warWeariness > 50);
            if (s.ledger != null && s.ledger.net() < 0 && s.money < 120) { b.dev = b.infra = b.edu = 0; b.military = Math.min(b.military, 0.05); }
            b.printMoney = war && s.money < -100;
            s.budget = b;
        }
        WorldData.NationInfo ni = gs.world.nations.get(n);
        if (s.researching == null && s.money > 220 && gs.rng.nextDouble() < 0.1) gs.research(n, Track.values()[gs.rng.nextInt(3)]);
        if (s.money > 150 && gs.rng.nextDouble() < 0.1) {
            int p = ni.provinces.get(gs.rng.nextInt(ni.provinces.size()));
            if (gs.owner[p] == n && gs.controller[p] == n)
                gs.build(n, p, gs.rng.nextBoolean() ? Building.FACTORY : (war ? Building.FORTIFICATION : Building.INFRASTRUCTURE));
        }
        double threshold = war ? 60 : 160;
        double cap = gs.baseUnits[n] * (war ? 2.6 : 1.25);
        if (s.money > threshold && gs.unitsOf(n) < cap && s.manpower > 12 && s.recruitQueue.size() < gs.productionCapacity(n)) {
            double rich = ni.gdp / Math.max(1, ni.population / 1e6);
            double[] comp = new double[UnitType.N];
            comp[UnitType.INFANTRY.ordinal()] = 4;
            comp[UnitType.ARTILLERY.ordinal()] = 1;
            comp[UnitType.ANTI_TANK.ordinal()] = 1;
            if (rich > 8000) { comp[UnitType.MECHANIZED.ordinal()] = 2; comp[UnitType.ARMOR.ordinal()] = 1; }
            int at = ni.capital;
            for (int pid : ni.provinces) if (gs.controller[pid] == n && gs.rng.nextDouble() < 0.02) { at = pid; break; }
            if (gs.owner[at] == n && gs.controller[at] == n) gs.recruit(n, at, comp);
        }
        if (s.nukes > 0 && war && gs.rng.nextDouble() < 0.004 && gs.ownedProvinces(n) < 0.5 * ni.provinces.size()) {
            // desperate: strike the enemy capital we know about
            for (int e = 0; e < gs.N; e++) if (gs.war[n][e] && gs.ns[e].alive) { gs.launchNuke(n, gs.world.nations.get(e).capital); break; }
        }
    }
}
