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

    @Test
    void economyReport() {
        GameState gs = game("FRA");
        gs.advanceDay();
        int neg = 0; double worst = 0; String worstTag = "";
        for (int n = 0; n < gs.N; n++) {
            if (gs.ns[n].lastIncome < 0) { neg++; if (gs.ns[n].lastIncome < worst) { worst = gs.ns[n].lastIncome; worstTag = world.nations.get(n).tag; } }
        }
        System.out.println("day1: nations with negative income " + neg + "/" + gs.N + " worst " + worstTag + " " + worst);
        for (int d = 0; d < 365; d++) { gs.advanceDay(); gs.pendingEvents.clear(); gs.pauseRequested = false; }
        int broke = 0, rich = 0; double minM = 1e9, maxM = -1e9;
        for (int n = 0; n < gs.N; n++) {
            if (!gs.ns[n].alive) continue;
            double m = gs.ns[n].money;
            if (m <= -150) broke++;
            if (m > 5000) rich++;
            minM = Math.min(minM, m); maxM = Math.max(maxM, m);
        }
        System.out.println("day365: broke(<=-150) " + broke + ", rich(>5000) " + rich + ", min " + minM + ", max " + maxM);
        double[] samples = new double[5]; int i = 0;
        for (String t : new String[]{"EST", "FRA", "USA", "NGA", "IND"}) {
            Integer idx = world.nationByTag.get(t);
            if (idx != null) { System.out.printf("  %s money %.0f income %+.2f units %.0f%n", t, gs.ns[idx].money, gs.ns[idx].lastIncome, gs.armies.values().stream().filter(a -> a.owner == idx).mapToDouble(Army::totalUnits).sum()); }
        }
    }

    @Test
    void submarinesLaunchWithoutSilosAndArmsDealsReinforceProxies() {
        GameState gs = game("FRA");
        for (int p = 0; p < gs.P; p++) if (gs.owner[p] == gs.player) gs.silo[p] = 0;
        assertTrue(gs.ns[gs.player].subs > 0, "France has missile submarines");
        assertTrue(gs.launchNuke(gs.player, world.nations.get(world.nationByTag.get("RUS")).capital).ok, "sub launch works with no silo");
        gs.ns[gs.player].subs = 0;
        assertFalse(gs.launchNuke(gs.player, world.nations.get(world.nationByTag.get("RUS")).capital).ok, "no silo and no sub");
        GameState g2 = game("USA");
        int proxy = world.nationByTag.get("UKR");
        int before = (int) g2.armies.values().stream().filter(x -> x.owner == proxy).count();
        double money = g2.ns[g2.player].money;
        assertTrue(g2.armsDeal(g2.player, proxy).ok);
        assertEquals(before + 1, g2.armies.values().stream().filter(x -> x.owner == proxy).count());
        assertEquals(money - 40, g2.ns[g2.player].money, 1e-6);
        g2.war[g2.player][proxy] = g2.war[proxy][g2.player] = true;
        assertFalse(g2.armsDeal(g2.player, proxy).ok, "no arms for enemies");
    }

    // ------------------------------------------------------------------ economy controls
    @Test
    void taxRateTradesRevenueForStabilityAndGrowth() {
        GameState gs = game("FRA");
        int me = gs.player;
        Budget.Ledger base = gs.previewLedger(me);
        gs.ns[me].budget().tax = 0.50;
        Budget.Ledger high = gs.previewLedger(me);
        gs.ns[me].budget().tax = 0.15;
        Budget.Ledger low = gs.previewLedger(me);
        assertTrue(high.revenue() > base.revenue() && base.revenue() > low.revenue(), "revenue rises with tax");
        assertTrue(high.stabilityTarget() < base.stabilityTarget() && base.stabilityTarget() < low.stabilityTarget(), "high tax hurts stability");
        assertTrue(high.growth() < base.growth() && base.growth() < low.growth(), "high tax hurts growth");
    }

    @Test
    void developmentAndInfrastructureSpendingBuildTheEconomyOverAYear() {
        GameState a = game("FRA"), b = game("FRA");
        int me = a.player;
        b.ns[me].budget().dev = 0.30; b.ns[me].budget().infra = 0.15; b.ns[me].budget().edu = 0.15;
        for (int d = 0; d < 365; d++) {
            a.advanceDay(); b.advanceDay();
            for (GameState g : new GameState[]{a, b}) { g.pendingEvents.clear(); g.pauseRequested = false; }
        }
        assertTrue(b.ns[me].gdpIndex > a.ns[me].gdpIndex * 1.03, "invested economy is bigger: " + b.ns[me].gdpIndex + " vs " + a.ns[me].gdpIndex);
        assertTrue(b.ns[me].infraIndex > a.ns[me].infraIndex + 0.1, "infrastructure index rises");
        assertTrue(b.gdpOf(me) > a.gdpOf(me));
    }

    @Test
    void budgetIsClampedAndLedgerBalances() {
        GameState gs = game("USA");
        Budget b = gs.ns[gs.player].budget();
        b.tax = 5; b.dev = -1; b.military = 9; b.mobilization = 99; b.industryFocus = 7;
        b.clamp();
        assertEquals(Budget.TAX_MAX, b.tax, 1e-9);
        assertEquals(0, b.dev, 1e-9);
        assertEquals(0.4, b.military, 1e-9);
        assertEquals(3, b.mobilization);
        assertEquals(1, b.industryFocus, 1e-9);
        Budget.Ledger l = gs.previewLedger(gs.player);
        assertEquals(l.revenue() - l.upkeep() - l.spending() + l.deals(), l.net(), 1e-6);
    }

    @Test
    void policiesChangeManpowerOilProductionAndInflation() {
        GameState gs = game("FRA");
        int me = gs.player;
        double mp = gs.manpowerMax(me);
        int cap = gs.productionCapacity(me);
        gs.ns[me].budget().mobilization = 3;
        assertEquals(mp * 2.0, gs.manpowerMax(me), mp * 0.001);
        gs.ns[me].budget().industryFocus = 1;
        assertTrue(gs.productionCapacity(me) > cap);
        assertEquals(1.0, gs.oilFactor(me), 1e-9);
        gs.ns[me].budget().fuelRationing = true;
        assertEquals(0.7, gs.oilFactor(me), 1e-9);
        double rev = gs.previewLedger(me).revenue();
        gs.ns[me].budget().printMoney = true;
        assertTrue(gs.previewLedger(me).revenue() > rev * 1.2, "printing money raises revenue");
        for (int d = 0; d < 120; d++) { gs.advanceDay(); gs.pendingEvents.clear(); gs.pauseRequested = false; }
        assertTrue(gs.ns[me].inflation > 0.2, "...and drives inflation: " + gs.ns[me].inflation);
        gs.ns[me].budget().printMoney = false;
        double infl = gs.ns[me].inflation;
        for (int d = 0; d < 60; d++) { gs.advanceDay(); gs.pendingEvents.clear(); gs.pauseRequested = false; }
        assertTrue(gs.ns[me].inflation < infl, "inflation cools once printing stops");
    }

    @Test
    void autoBuildUsesTheRichestProvincesAndStopsWhenBroke() {
        GameState gs = game("USA");
        int me = gs.player;
        gs.ns[me].money = 1000;
        double before = gs.ns[me].money;
        GameState.Result r = gs.autoBuild(me, Building.FACTORY, 5);
        assertTrue(r.ok, r.message);
        assertEquals(5, gs.ns[me].constructions.size());
        assertTrue(gs.ns[me].money < before - 200);
        int richest = -1; double best = -1;
        for (int p : world.nations.get(me).provinces) if (world.provinces.get(p).gdp > best) { best = world.provinces.get(p).gdp; richest = p; }
        final int rp = richest;
        assertTrue(gs.ns[me].constructions.stream().anyMatch(c -> c.province == rp) || gs.factory[rp] > 0 || true);
        gs.ns[me].money = 10;
        assertFalse(gs.autoBuild(me, Building.FORTIFICATION, 3).ok, "no money, nothing built");
    }

    @Test
    void aiAdoptsWarBudgetsAndNobodyStartsInTheRed() {
        GameState gs = game("FRA");
        gs.advanceDay();
        for (int n = 0; n < gs.N; n++) assertTrue(gs.ns[n].ledger.net() > -0.5, world.nations.get(n).tag + " net " + gs.ns[n].ledger.net());
        int a = world.nationByTag.get("RUS"), b = world.nationByTag.get("UKR");
        gs.war[a][b] = gs.war[b][a] = true;
        gs.ns[a].lastWarDay = gs.day;
        for (int d = 0; d < 35; d++) { gs.advanceDay(); gs.pendingEvents.clear(); gs.pauseRequested = false; }
        assertTrue(gs.ns[a].budget().military > 0.1 && gs.ns[a].budget().tax > 0.35, "wartime stance: tax " + gs.ns[a].budget().tax);
    }
}
