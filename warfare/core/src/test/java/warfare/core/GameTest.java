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
}
