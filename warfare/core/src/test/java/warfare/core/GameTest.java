package warfare.core;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class GameTest {
    static WorldData world;

    @BeforeAll
    static void load() throws Exception {
        world = WorldData.load(Path.of("../assets/map"));
    }

    static GameState game(String tag) { return new GameState(world, world.nationByTag.get(tag), 42); }

    @Test
    void mapScale() {
        assertTrue(world.landCount() >= 3000, "land provinces: " + world.landCount());
        assertTrue(world.nations.size() >= 190, "nations: " + world.nations.size());
        for (WorldData.NationInfo n : world.nations) assertFalse(n.provinces.isEmpty(), n.tag);
    }

    @Test
    void tacticsFormBalancedCycle() {
        for (Tactic a : Tactic.values()) {
            int wins = 0;
            for (Tactic b : Tactic.values()) {
                if (a == b) continue;
                assertTrue(a.beats(b) ^ b.beats(a), a + " vs " + b);
                if (a.beats(b)) wins++;
            }
            assertEquals(2, wins, a.toString());
        }
    }

    Army place(GameState gs, int owner, int province, double inf, double armor) {
        Army a = new Army(gs.nextArmyId++, owner, province, "T" + owner);
        a.units[UnitType.INFANTRY.ordinal()] = inf;
        a.units[UnitType.ARMOR.ordinal()] = armor;
        a.morale = 80;
        gs.armies.put(a.id, a);
        return a;
    }

    int landNeighbour(GameState gs, int p) {
        for (int nb : gs.prov(p).neighbours) if (!gs.isSea(nb)) return nb;
        return -1;
    }

    @Test
    void fogHidesAndRemembersEnemies() {
        GameState gs = game("FRA");
        int me = gs.player;
        gs.armies.values().removeIf(a -> a.owner == me);
        int enemy = world.nationByTag.get("JPN");
        // distant enemy army: not seen
        Army far = place(gs, enemy, world.nations.get(enemy).capital, 6, 0);
        Vision.updateAll(gs);
        assertNull(gs.intel[me].sightingOf(far.id), "distant armies are hidden");
        // enemy army inside our territory: seen with an estimate, owner known, composition hidden
        int home = world.nations.get(me).capital;
        Army near = place(gs, enemy, home, 6, 2);
        Vision.updateAll(gs);
        Intel.Sighting s = gs.intel[me].sightingOf(near.id);
        assertNotNull(s);
        assertTrue(s.ownerKnown);
        assertNull(s.composition, "composition needs exact quality 3");
        // our own army in the same province gives quality 3: exact composition
        place(gs, me, home, 5, 0);
        Vision.updateAll(gs);
        s = gs.intel[me].sightingOf(near.id);
        assertNotNull(s.composition);
        // enemy leaves out of sight: the sighting goes stale and expires
        near.province = world.nations.get(enemy).capital;
        gs.armies.values().removeIf(a -> a.owner == me);
        for (int i = 0; i < 3; i++) { gs.day++; Vision.updateAll(gs); }
        // province now seen as empty (we own it) => sighting dropped, intel is updated
        assertNull(gs.intel[me].sightingOf(near.id));
        // a sighting in a province we cannot currently see persists, then expires after 45 days
        Intel in = gs.intel[me];
        Intel.Sighting old = new Intel.Sighting();
        old.armyId = 9999; old.province = world.nations.get(enemy).capital; old.day = gs.day; old.quality = 2;
        in.sightings.put(9999, old);
        gs.day += 20; in.expire(gs.day);
        assertNotNull(in.sightingOf(9999));
        assertTrue(Intel.confidence(gs.day - old.day) < 1.0);
        gs.day += 30; in.expire(gs.day);
        assertNull(in.sightingOf(9999));
    }

    @Test
    void counterTacticWinsMoreOften() {
        int flankWins = 0, trials = 60;
        for (int t = 0; t < trials; t++) {
            GameState gs = new GameState(world, world.nationByTag.get("FRA"), 100 + t);
            int a = world.nationByTag.get("DEU"), b = world.nationByTag.get("POL");
            // find two adjacent land provinces of different owners is not needed: place both in a plains province
            int p = world.nations.get(a).capital;
            gs.armies.clear();
            Army att = place(gs, a, p, 10, 4), def = place(gs, b, p, 10, 4);
            gs.war[a][b] = gs.war[b][a] = true;
            gs.controller[p] = b; gs.owner[p] = b;
            gs.setTactic(att, Tactic.FLANKING);
            gs.setTactic(def, Tactic.HOLD_THE_LINE);
            att.mode = def.mode = Army.Mode.MANUAL;
            War.contacts(gs);
            assertEquals(1, gs.battles.size());
            for (int d = 0; d < 25 && !gs.battles.isEmpty(); d++) { gs.day++; War.resolveBattles(gs); }
            if (att.alive() && (!def.alive() || att.hp() > def.hp())) flankWins++;
        }
        assertTrue(flankWins > trials * 0.7, "flanking should beat hold the line: " + flankWins + "/" + trials);
    }

    @Test
    void generalModeUsesCompositionAndTerrain() {
        GameState gs = game("FRA");
        int p = world.nations.get(gs.player).capital;
        Army armor = place(gs, gs.player, p, 2, 12);
        Army foe = place(gs, world.nationByTag.get("DEU"), p, 10, 0);
        Tactic t = TacticAdvisor.choose(gs, armor, foe, true);
        assertTrue(t.offensive || t == Tactic.AMBUSH, t.toString());
        Army line = place(gs, gs.player, p, 12, 0);
        line.units[UnitType.ANTI_TANK.ordinal()] = 4;
        Tactic d = TacticAdvisor.choose(gs, line, foe, false);
        assertNotNull(d);
    }

    @Test
    void worldSimulatesForAYear() {
        GameState gs = game("USA");
        long t0 = System.nanoTime();
        for (int i = 0; i < 365; i++) gs.advanceDay();
        double secs = (System.nanoTime() - t0) / 1e9;
        System.out.printf("365 days simulated in %.2fs, armies=%d, news=%d%n", secs, gs.armies.size(), gs.news.size());
        assertTrue(secs < 120);
        assertTrue(gs.ns[gs.player].alive);
        for (NationState s : gs.ns) { assertFalse(Double.isNaN(s.money)); assertFalse(Double.isNaN(s.oil)); }
    }

    @Test
    void warPlaysOutAndConquersProvinces() {
        GameState gs = game("RUS");
        int a = gs.player, b = world.nationByTag.get("UKR");
        gs.declareWar(a, b);
        int before = gs.ownedProvinces(b);
        for (int i = 0; i < 400; i++) {
            if (gs.day % 2 == 0) for (Army ar : gs.armies.values()) if (ar.owner == a && ar.path.isEmpty() && ar.battleId < 0) Ai.manageArmies(gs, a);
            gs.advanceDay();
            gs.pauseRequested = false; gs.pendingEvents.clear();
        }
        System.out.println("UKR provinces: " + before + " -> " + gs.ownedProvinces(b) + ", RUS now " + gs.ownedProvinces(a)
                + ", reports=" + gs.reports.size());
        assertFalse(gs.reports.isEmpty(), "battles must have been fought");
        System.out.println(gs.reports.get(0).text);
    }

    @Test
    void nukesDevastateAndSaveLoadRoundTrips() throws Exception {
        GameState gs = game("USA");
        int target = world.nations.get(world.nationByTag.get("RUS")).capital;
        assertTrue(gs.launchNuke(gs.player, target).ok);
        gs.advanceDay(); gs.advanceDay();
        assertTrue(gs.devastation[target] < 0.2f);
        Path f = Files.createTempFile("warfare", ".sav");
        gs.save(f);
        GameState back = GameState.load(f, world);
        assertEquals(gs.day, back.day);
        assertEquals(gs.armies.size(), back.armies.size());
        assertEquals(gs.devastation[target], back.devastation[target]);
        back.advanceDay();
    }

    // ------------------------------------------------------------------ invariants + soak
    static void checkInvariants(GameState gs, String when) {
        for (int p = 0; p < gs.P; p++) {
            if (gs.isSea(p)) { assertEquals(-2, gs.owner[p], when + " sea owner"); continue; }
            assertTrue(gs.owner[p] >= 0 && gs.owner[p] < gs.N, when + " owner of " + p + " = " + gs.owner[p]);
            assertTrue(gs.ns[gs.owner[p]].alive, when + " province " + p + " owned by dead nation");
            assertTrue(gs.devastation[p] >= 0 && gs.devastation[p] <= 1.0001f, when + " devastation");
            assertTrue(gs.unrest[p] >= 0 && gs.unrest[p] <= 100.001f, when + " unrest");
        }
        for (Army a : gs.armies.values()) {
            assertTrue(a.alive(), when + " dead army kept");
            assertTrue(a.province >= 0 && a.province < gs.P, when + " army province");
            if (gs.isSea(a.province)) assertTrue(a.embarked, when + " army at sea must be embarked");
            for (double u : a.units) { assertFalse(Double.isNaN(u) || u < 0, when + " unit count " + u); }
            assertTrue(a.morale >= 0 && a.morale <= 100.001, when + " morale " + a.morale);
            if (a.battleId >= 0) assertTrue(gs.battles.containsKey(a.battleId), when + " dangling battle");
        }
        for (Combat.Battle b : gs.battles.values()) {
            assertTrue(gs.armies.containsKey(b.attacker) && gs.armies.containsKey(b.defender), when + " battle without armies");
        }
        for (int n = 0; n < gs.N; n++) {
            NationState s = gs.ns[n];
            for (double v : new double[]{s.money, s.manpower, s.oil, s.stability}) assertFalse(Double.isNaN(v) || Double.isInfinite(v), when + " nation stat");
            assertTrue(s.stability >= 0 && s.stability <= 100, when + " stability");
            assertTrue(s.manpower >= -0.001, when + " manpower " + s.manpower);
            assertEquals(false, gs.war[n][n]);
            for (int m = 0; m < gs.N; m++) assertEquals(gs.war[n][m], gs.war[m][n], when + " war symmetry");
        }
    }

    @Test
    void soakThreeYearsKeepsTheWorldConsistent() {
        for (String tag : new String[]{"FRA", "BRA"}) {
            GameState gs = game(tag);
            int wars = 0, maxWars = 0;
            for (int d = 0; d < 1100; d++) {
                gs.advanceDay();
                gs.pauseRequested = false; gs.pendingEvents.clear(); gs.awaitingTactic.clear();
                if (d % 100 == 99) {
                    checkInvariants(gs, tag + " day " + gs.day);
                    int w = 0;
                    for (int a = 0; a < gs.N; a++) for (int b = a + 1; b < gs.N; b++) if (gs.war[a][b]) w++;
                    maxWars = Math.max(maxWars, w);
                }
            }
            int alive = 0;
            for (NationState s : gs.ns) if (s.alive) alive++;
            System.out.println(tag + " soak: nations alive " + alive + "/" + gs.N + ", max concurrent wars " + maxWars + ", armies " + gs.armies.size());
            assertTrue(alive > gs.N * 0.8, "world should not collapse: " + alive);
        }
    }

    @Test
    void achievementsUnlockFromStatistics() {
        GameState gs = game("FRA");
        java.util.Set<String> have = new java.util.HashSet<>();
        assertTrue(Achievements.newlyUnlocked(gs, have).stream().noneMatch(d -> d.id().equals("FIRST_BLOOD")));
        gs.stBattlesWon = 1; gs.stCaptures = 12; gs.stNukes = 1;
        var ids = Achievements.newlyUnlocked(gs, have).stream().map(Achievements.Def::id).toList();
        assertTrue(ids.contains("FIRST_BLOOD") && ids.contains("LAND_GRAB") && ids.contains("DOOMSDAY"), ids.toString());
        assertFalse(ids.contains("CONQUEROR"));
        have.addAll(ids);
        assertTrue(Achievements.newlyUnlocked(gs, have).stream().noneMatch(d -> ids.contains(d.id())), "no double unlock");
        for (Achievements.Def d : Achievements.ALL) assertNotNull(Achievements.byId(d.id()));
    }

    @Test
    void realBattlesFeedAchievementStatistics() {
        GameState gs = game("RUS");
        int me = gs.player, foe = world.nationByTag.get("UKR");
        int p = world.nations.get(foe).capital;
        gs.armies.clear();
        Army mine = place(gs, me, p, 14, 6), theirs = place(gs, foe, p, 3, 0);
        gs.war[me][foe] = gs.war[foe][me] = true;
        gs.controller[p] = foe; gs.owner[p] = foe;
        gs.setTactic(mine, Tactic.FLANKING); gs.setTactic(theirs, Tactic.HOLD_THE_LINE);
        War.contacts(gs);
        for (int d = 0; d < 25 && !gs.battles.isEmpty(); d++) { gs.day++; War.resolveBattles(gs); }
        assertEquals(1, gs.stBattlesWon);
        assertEquals(1, gs.stTacticWins, "flanking counters hold the line");
        assertFalse(gs.reports.isEmpty());
        assertEquals("Flanking / Envelopment", gs.reports.get(0).attackerTactic.isEmpty() ? gs.reports.get(0).defenderTactic : gs.reports.get(0).attackerTactic.equals("Flanking / Envelopment") ? "Flanking / Envelopment" : "?");
        // and capturing the capital counts
        for (int i = 0; i < 12 && gs.owner[p] == foe; i++) { gs.day++; War.occupation(gs); }
        assertEquals(me, gs.owner[p]);
        assertTrue(gs.stCaptures >= 1 && gs.stCapitals >= 1);
    }

    @Test
    void ironmanFlagSurvivesSaveLoad() throws Exception {
        GameState gs = game("FRA");
        gs.ironman = true; gs.stCaptures = 7;
        Path f = Files.createTempFile("warfare-iron", ".sav");
        gs.save(f);
        GameState back = GameState.load(f, world);
        assertTrue(back.ironman);
        assertEquals(7, back.stCaptures);
        back.advanceDay();
        checkInvariants(back, "after load");
    }

    @Test
    void decoysFoolTheFarSightAndAreExposedUpClose() {
        GameState gs = game("FRA");
        int me = gs.player, enemy = world.nationByTag.get("RUS");
        gs.armies.clear();
        int home = world.nations.get(me).capital;
        // an enemy decoy of "9 battalions" inside our territory, seen at estimate quality only
        Army d = place(gs, enemy, home, 1, 0);
        d.decoy = true; d.fake = 9; d.name = "decoy";
        Vision.updateAll(gs);
        Intel.Sighting s = gs.intel[me].sightingOf(d.id);
        assertNotNull(s);
        assertTrue(s.strengthEstimate > 60, "looks like a big army: " + s.strengthEstimate);
        assertFalse(s.decoy);
        // our own army in the same province gives exact vision: the ruse is exposed
        place(gs, me, home, 5, 0);
        Vision.updateAll(gs);
        assertTrue(gs.intel[me].sightingOf(d.id).decoy);
        // at war, contact removes the decoy without a battle and stalls the enemy
        gs.war[me][enemy] = gs.war[enemy][me] = true;
        War.contacts(gs);
        gs.armies.values().removeIf(a -> !a.alive());
        assertTrue(gs.battles.isEmpty());
        assertFalse(gs.armies.containsKey(d.id));
    }

    @Test
    void decoyDeploymentCostsMoneyAndIsCapped() {
        GameState gs = game("FRA");
        int home = world.nations.get(gs.player).capital;
        double before = gs.ns[gs.player].money;
        assertTrue(gs.deployDecoy(gs.player, home, 8).ok);
        assertEquals(before - 16, gs.ns[gs.player].money, 1e-6);
        for (int i = 0; i < 3; i++) assertTrue(gs.deployDecoy(gs.player, home, 4).ok);
        assertFalse(gs.deployDecoy(gs.player, home, 4).ok, "cap of four decoys");
    }

    @Test
    void sabotageRaidsSucceedSometimesAndCostUnitsWhenTheyFail() {
        int wins = 0, fails = 0;
        for (int seed = 0; seed < 40; seed++) {
            GameState gs = new GameState(world, world.nationByTag.get("RUS"), 500 + seed);
            int me = gs.player, foe = world.nationByTag.get("UKR");
            gs.war[me][foe] = gs.war[foe][me] = true;
            int target = world.nations.get(foe).capital;
            Army a = place(gs, me, target, 4, 0);
            a.units[UnitType.SPECIAL_FORCES.ordinal()] = 2;
            gs.fort[target] = 2;
            GameState.Result r = gs.raid(a, target);
            if (r.ok) { wins++; assertEquals(1, gs.fort[target]); } else { fails++; assertEquals(1, a.units[UnitType.SPECIAL_FORCES.ordinal()], 1e-9); }
            assertFalse(gs.raid(a, target).ok, "cooldown");
        }
        assertTrue(wins > 5 && fails > 5, "wins " + wins + " fails " + fails);
    }
}
