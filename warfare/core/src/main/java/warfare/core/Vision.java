package warfare.core;

import warfare.core.NationState.AirWing;
import warfare.core.NationState.Mission;

import java.util.*;

/** Fog of war: what each nation can currently see, and what it remembers. */
public final class Vision {
    private Vision() {}

    static void raise(byte[] q, int p, int level) { if (q[p] < level) q[p] = (byte) level; }

    private static int[] stamp, queue, depth;
    private static int stampId;

    /** Spread vision from a province: levels[d] is the quality at hop distance d. Allocation-free BFS. */
    static void spread(GameState gs, int start, int[] levels, byte[] q) {
        if (stamp == null || stamp.length != gs.P) { stamp = new int[gs.P]; queue = new int[gs.P]; depth = new int[gs.P]; stampId = 0; }
        stampId++;
        int head = 0, tail = 0;
        queue[tail++] = start; stamp[start] = stampId; depth[start] = 0;
        while (head < tail) {
            int p = queue[head++];
            int d = depth[p];
            raise(q, p, levels[d]);
            if (d + 1 >= levels.length) continue;
            for (int nb : gs.world.provinces.get(p).neighbours) if (stamp[nb] != stampId) { stamp[nb] = stampId; depth[nb] = d + 1; queue[tail++] = nb; }
        }
    }

    static void computeBase(GameState gs, int n, List<Army> mine) {
        Intel in = gs.intelOf(n);
        byte[] q = in.base;
        Arrays.fill(q, (byte) 0);
        NationState s = gs.ns[n];
        boolean dark = s.blackoutUntil > gs.day;            // cyberattacked: no radar, satellites or air recon
        for (int p = 0; p < gs.P; p++) {
            if (gs.owner[p] == n) {
                raise(q, p, 2);
                for (int nb : gs.world.provinces.get(p).neighbours) raise(q, nb, 1);
                if (gs.radar[p] > 0 && !dark) spread(gs, p, new int[]{2, 1, 1, 1}, q);
            }
        }
        int extra = s.tech[2] >= 1 ? 1 : 0;
        for (Army a : mine) {
            boolean recon = a.units[UnitType.SPECIAL_FORCES.ordinal()] > 0;
            int r = (a.embarked ? 1 : 2) + extra + (recon ? 1 : 0);
            int[] lv = new int[r + 1];
            for (int d = 0; d <= r; d++) lv[d] = Math.max(1, 3 - d);
            if (recon) lv[1] = 3;
            spread(gs, a.province, lv, q);
        }
        for (AirWing w : s.airWings) {
            if (dark || w.mission != Mission.RECON || w.target < 0) continue;
            spread(gs, w.target, new int[]{3, 3, 2}, q);
        }
        if (s.tech[2] >= 2 && !dark) { // satellites sweep a moving swath of the globe
            int width = 220 + 70 * s.tech[2];
            int start = (gs.day * 61 + n * 37) % gs.world.width;
            for (int p = 0; p < gs.P; p++) {
                double cx = gs.world.provinces.get(p).cx;
                double off = (cx - start + gs.world.width) % gs.world.width;
                if (off < width) raise(q, p, s.tech[2] >= 4 ? 2 : 1);
            }
        }
        for (Map.Entry<Integer, Double> e : s.agents.entrySet()) {
            double lvl = e.getValue();
            if (lvl >= 70) {
                int cap = gs.world.nations.get(e.getKey()).capital;
                spread(gs, cap, lvl >= 90 ? new int[]{2, 2, 1} : new int[]{1, 1, 1}, q);
            }
        }
    }

    public static void updateAll(GameState gs) {
        Set<Integer> compute = new LinkedHashSet<>();
        Set<Integer> observers = new LinkedHashSet<>();
        for (int n = 0; n < gs.N; n++) {
            if (!gs.ns[n].alive || !gs.needsIntel(n)) continue;
            observers.add(n); compute.add(n);
            for (int m = 0; m < gs.N; m++) if (m != n && gs.ns[m].alive && gs.isAlly(n, m)) compute.add(m);
        }
        List<Army>[] byOwner = new List[gs.N];
        for (Army a : gs.armies.values()) {
            if (a.owner < 0) continue;
            if (byOwner[a.owner] == null) byOwner[a.owner] = new ArrayList<>();
            byOwner[a.owner].add(a);
        }
        for (int n : compute) computeBase(gs, n, byOwner[n] == null ? List.of() : byOwner[n]);
        for (int n : observers) finish(gs, n, compute);
    }

    static void finish(GameState gs, int n, Set<Integer> computed) {
        Intel in = gs.intelOf(n);
        System.arraycopy(in.base, 0, in.quality, 0, gs.P);
        for (int m : computed) {
            if (m == n || !gs.isAlly(n, m)) continue;
            byte[] o = gs.intelOf(m).base;
            for (int p = 0; p < gs.P; p++) if (o[p] > in.quality[p]) in.quality[p] = o[p];
        }
        for (int p = 0; p < gs.P; p++) {
            if (in.quality[p] > 0) {
                in.lastSeen[p] = gs.day;
                if (gs.owner[p] != -2) in.knownOwner[p] = gs.owner[p];
                if (in.quality[p] >= 2) in.knownFort[p] = gs.fort[p];
            }
        }
        Set<Integer> refreshed = new HashSet<>();
        for (Army a : gs.armies.values()) {
            if (a.owner == n || gs.isAlly(n, a.owner)) continue;
            int q = in.quality[a.province];
            if (a.embarked && q < 2) continue;
            if (q <= 0) continue;
            Intel.Sighting s = in.sightings.computeIfAbsent(a.id, k -> new Intel.Sighting());
            s.armyId = a.id; s.province = a.province; s.day = gs.day; s.quality = q;
            s.ownerKnown = q >= 2;
            s.owner = a.owner;
            double hp = a.decoy ? a.fake * 11.0 : a.hp();
            s.decoy = a.decoy && q >= 3;
            if (q >= 3) { s.strengthEstimate = hp; s.composition = a.decoy ? a.units.clone() : a.units.clone(); }
            else if (q == 2) { s.strengthEstimate = hp * (1 + fuzz(a.id, gs.day / 4) * 0.3); s.composition = null; }
            else { s.strengthEstimate = 0; s.composition = null; s.ownerKnown = false; }
            refreshed.add(a.id);
        }
        // areas we can see clearly but where the sighting no longer stands: the army has moved on or died
        in.sightings.values().removeIf(s -> !refreshed.contains(s.armyId) && in.quality[s.province] >= 2);
        in.expire(gs.day);
        in.computedDay = gs.day;
    }

    /** Deterministic noise in [-1, 1]. */
    static double fuzz(int a, int b) {
        long h = a * 0x9E3779B97F4A7C15L + b * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 29; h *= 0xBF58476D1CE4E5B9L; h ^= h >>> 32;
        return ((h & 0xFFFF) / 32767.5) - 1.0;
    }

    /** Fuzzy belief about another nation's total military strength (better with agents inside). */
    public static double estimateStrength(GameState gs, int observer, int target) {
        double noise = gs.agentLevel(observer, target) > 30 ? 0.1 : 0.4;
        return gs.nationHp(target) * (1 + fuzz(observer * 1000 + target, gs.day / 30) * noise);
    }
}
