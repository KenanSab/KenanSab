package warfare.core;

import java.util.*;

/** Contacts, battles, occupation, stack merging and unrest. */
final class War {
    private War() {}

    static boolean hostile(GameState gs, Army a, Army b) {
        if (a.owner < 0 && b.owner < 0) return false;
        return gs.atWar(a.owner, b.owner);
    }

    static void contacts(GameState gs) {
        Map<Integer, List<Army>> byProv = new HashMap<>();
        for (Army a : gs.armies.values()) if (!a.embarked && a.alive()) byProv.computeIfAbsent(a.province, k -> new ArrayList<>()).add(a);
        for (Map.Entry<Integer, List<Army>> e : byProv.entrySet()) {
            List<Army> list = e.getValue();
            if (list.size() < 2) continue;
            int prov = e.getKey();
            for (int i = 0; i < list.size(); i++) {
                for (int j = i + 1; j < list.size(); j++) {
                    Army a = list.get(i), b = list.get(j);
                    if (a.battleId >= 0 || b.battleId >= 0 || !hostile(gs, a, b)) continue;
                    if (a.decoy || b.decoy) { expose(gs, a, b); continue; }
                    Army def, att;
                    int c = gs.controller[prov];
                    if (a.owner == c || (a.owner >= 0 && gs.isAlly(a.owner, c))) { def = a; att = b; }
                    else if (b.owner == c || (b.owner >= 0 && gs.isAlly(b.owner, c))) { def = b; att = a; }
                    else { def = a.id < b.id ? a : b; att = def == a ? b : a; }
                    start(gs, att, def);
                }
            }
        }
    }

    /** A decoy meets an enemy: the ruse is revealed, the decoy vanishes and the enemy loses a day of marching. */
    static void expose(GameState gs, Army a, Army b) {
        Army decoy = a.decoy ? a : b, other = a.decoy ? b : a;
        if (other.decoy) return;                       // two decoys ignore each other
        for (int i = 0; i < decoy.units.length; i++) decoy.units[i] = 0;
        other.progressKm = 0;
        if (decoy.owner == gs.player || other.owner == gs.player)
            gs.news("A decoy force of " + gs.nationName(decoy.owner) + " was exposed at " + gs.prov(decoy.province).name + "; " + other.name + " lost time chasing it.");
    }

    static void start(GameState gs, Army att, Army def) {
        Combat.Battle b = new Combat.Battle();
        b.id = gs.nextBattleId++; b.province = att.province; b.attacker = att.id; b.defender = def.id; b.startDay = gs.day;
        b.attStartHp = att.hp(); b.defStartHp = def.hp();
        b.attackerUnseen = def.owner < 0 || gs.intel[def.owner] == null || gs.intel[def.owner].quality[att.province] == 0;
        b.defenderUnseen = att.owner < 0 || gs.intel[att.owner] == null || gs.intel[att.owner].quality[def.province] == 0;
        att.battleId = def.battleId = b.id;
        att.path.clear(); def.path.clear();
        gs.battles.put(b.id, b);
        if (att.owner == gs.player || def.owner == gs.player) {
            gs.news("Battle begins at " + gs.prov(b.province).name + ": " + gs.nationName(att.owner) + " attacks " + gs.nationName(def.owner) + ".");
            for (Army x : new Army[]{att, def})
                if (x.owner == gs.player && x.mode == Army.Mode.MANUAL && x.tactic == null) { gs.awaitingTactic.add(x.id); gs.pauseRequested = true; }
        }
    }

    static Tactic pick(GameState gs, Army me, Army other, boolean attacking) {
        if (me.mode == Army.Mode.MANUAL && me.tactic != null) return me.tactic;
        if (me.owner < 0) return attacking ? Tactic.FRONTAL_ASSAULT : Tactic.HOLD_THE_LINE;
        return TacticAdvisor.choose(gs, me, other, attacking);
    }

    static void resolveBattles(GameState gs) {
        for (Combat.Battle b : new ArrayList<>(gs.battles.values())) {
            Army att = gs.armies.get(b.attacker), def = gs.armies.get(b.defender);
            if (att == null || def == null || !att.alive() || !def.alive() || att.province != def.province) { gs.endBattleQuiet(b.id); continue; }
            Tactic ta = pick(gs, att, def, true), td = pick(gs, def, att, false);
            int p = b.province;
            double fort = (gs.controller[p] == def.owner || (def.owner >= 0 && gs.isAlly(def.owner, gs.controller[p]))) ? gs.fort[p] : 0;
            boolean attOil = att.owner < 0 || gs.ns[att.owner].oil > 0, defOil = def.owner < 0 || gs.ns[def.owner].oil > 0;
            if (att.owner >= 0) gs.ns[att.owner].oil = Math.max(0, gs.ns[att.owner].oil - att.oilUsePerDay());
            if (def.owner >= 0) gs.ns[def.owner].oil = Math.max(0, gs.ns[def.owner].oil - def.oilUsePerDay());
            double attTech = att.owner >= 0 ? gs.landTechMult(att.owner) : 0.9, defTech = def.owner >= 0 ? gs.landTechMult(def.owner) : 0.9;
            double attAir = att.owner >= 0 ? gs.airSupport(att.owner, p) : 0, defAir = def.owner >= 0 ? gs.airSupport(def.owner, p) : 0;
            boolean over = Combat.resolveDay(b, att, def, gs.prov(p).terrain, fort, attTech, defTech, attOil, defOil, attAir, defAir, ta, td, gs.rng);
            if (over) finishBattle(gs, b, att, def);
        }
        gs.armies.values().removeIf(a -> !a.alive());
    }

    static void finishBattle(GameState gs, Combat.Battle b, Army att, Army def) {
        Army winner = b.winnerNation == def.owner && def.alive() && (!att.alive() || att.morale <= 18 || b.days >= 20) ? def : att;
        Army loser = winner == att ? def : att;
        if (!winner.alive()) winner = loser == att ? def : att;
        Combat.Report r = new Combat.Report();
        r.day = gs.day; r.province = b.province; r.provinceName = gs.prov(b.province).name; r.days = b.days;
        r.attackerNation = att.owner; r.defenderNation = def.owner; r.winnerNation = winner.owner;
        r.attackerName = att.name; r.defenderName = def.name; r.attLost = b.attLost; r.defLost = b.defLost;
        r.text = String.format("Battle at %s lasted %d days. %s: %s lost %.0f HP (%.0f%% of its force). %s: %s lost %.0f HP (%.0f%%). Final exchange: %s. Terrain: %s%s. Winner: %s.",
                r.provinceName, b.days,
                "Attacker", gs.nationName(att.owner), b.attLost, 100 * b.attLost / Math.max(1, b.attStartHp),
                "Defender", gs.nationName(def.owner), b.defLost, 100 * b.defLost / Math.max(1, b.defStartHp),
                b.lastNote, gs.prov(b.province).terrain, b.attackerUnseen ? " (attacker approached unseen)" : "",
                gs.nationName(winner.owner));
        if (winner.owner == gs.player && (att.owner == gs.player || def.owner == gs.player)) {
            gs.stBattlesWon++;
            Tactic mine = winner == att ? b.attTactic : b.defTactic, theirs = winner == att ? b.defTactic : b.attTactic;
            if (mine != null && theirs != null && mine.beats(theirs)) gs.stTacticWins++;
            if (mine == Tactic.AMBUSH && (winner == att ? b.attackerUnseen : b.defenderUnseen)) gs.stAmbushWins++;
        }
        r.attackerTactic = b.attTactic == null ? "" : b.attTactic.label;
        r.defenderTactic = b.defTactic == null ? "" : b.defTactic.label;
        if (att.owner == gs.player || def.owner == gs.player) gs.reports.add(r);
        else if (gs.intel[gs.player] != null && gs.intel[gs.player].quality[b.province] > 0)
            gs.news("Battle at " + r.provinceName + ": " + gs.nationName(winner.owner) + " prevails.");
        att.battleId = def.battleId = -1;
        gs.battles.remove(b.id);
        gs.awaitingTactic.remove(att.id); gs.awaitingTactic.remove(def.id);
        if (loser.alive()) retreat(gs, loser, winner);
        winner.morale = Math.min(100, winner.morale + 6);
        if (loser.owner >= 0 && winner.owner >= 0) { gs.ns[loser.owner].stability = Math.max(0, gs.ns[loser.owner].stability - 0.3); }
    }

    static void retreat(GameState gs, Army a, Army by) {
        List<Integer> options = new ArrayList<>();
        if (a.prevProvince >= 0) options.add(a.prevProvince);
        for (int nb : gs.world.provinces.get(a.province).neighbours) options.add(nb);
        for (int p : options) {
            if (gs.isSea(p) || p == a.province) continue;
            if (!gs.canEnter(a.owner, p, a)) continue;
            boolean enemyThere = false;
            for (Army o : gs.armies.values()) if (o.province == p && hostileTo(gs, a, o)) enemyThere = true;
            if (enemyThere) continue;
            a.province = p; a.path.clear(); a.morale = Math.max(30, a.morale); a.embarked = false;
            return;
        }
        gs.news(a.name + " (" + gs.nationName(a.owner) + ") is surrounded and destroyed.");
        for (int i = 0; i < a.units.length; i++) a.units[i] = 0;
    }

    static boolean hostileTo(GameState gs, Army a, Army o) { return hostile(gs, a, o) && o.alive(); }

    static void occupation(GameState gs) {
        Map<Integer, List<Army>> byProv = new HashMap<>();
        for (Army a : gs.armies.values()) if (!a.embarked && a.alive() && !a.decoy && a.battleId < 0 && a.owner >= 0) byProv.computeIfAbsent(a.province, k -> new ArrayList<>()).add(a);
        for (int p = 0; p < gs.P; p++) {
            if (gs.isSea(p)) continue;
            List<Army> here = byProv.get(p);
            int ctrl = gs.controller[p];
            Army occupier = null;
            boolean defended = false;
            if (here != null) {
                for (Army a : here) {
                    if (a.owner == ctrl || gs.isAlly(a.owner, ctrl)) defended = true;
                    else if (gs.atWar(a.owner, ctrl) && occupier == null) occupier = a;
                }
            }
            if (occupier != null && !defended) {
                double rate = 100.0 / (3 + 2 * gs.fort[p]);
                gs.occupy[p] += rate;
                if (gs.occupy[p] >= 100) capture(gs, p, occupier.owner);
            } else gs.occupy[p] = Math.max(0, gs.occupy[p] - 12);
        }
    }

    static void capture(GameState gs, int p, int by) {
        int victim = gs.owner[p];
        gs.owner[p] = gs.controller[p] = by;
        gs.occupy[p] = 0;
        gs.unrest[p] = Math.max(gs.unrest[p], 55);
        gs.fort[p] = (byte) Math.max(0, gs.fort[p] - 1);
        WorldData.NationInfo vi = victim >= 0 ? gs.world.nations.get(victim) : null;
        if (victim >= 0) {
            gs.ns[victim].stability = Math.max(0, gs.ns[victim].stability - (p == vi.capital ? 8 : 0.6));
            for (NationState.Construction c : gs.ns[victim].constructions) if (c.province == p) c.daysLeft = 0;
        }
        if (by == gs.player) { gs.stCaptures++; if (vi != null && p == vi.capital) gs.stCapitals++; }
        if (by == gs.player || victim == gs.player) gs.news(gs.nationName(by) + " captures " + gs.prov(p).name + " from " + gs.nationName(victim) + ".");
    }

    static void mergeStacks(GameState gs) {
        Map<String, Army> keep = new HashMap<>();
        for (Army a : new ArrayList<>(gs.armies.values())) {
            if (!a.path.isEmpty() || a.battleId >= 0 || !a.alive() || a.owner < 0 || a.decoy) continue;
            String key = a.owner + ":" + a.province + ":" + a.embarked;
            Army k = keep.get(key);
            if (k == null) { keep.put(key, a); continue; }
            k.absorb(a);
            a.units[0] = 0; for (int i = 0; i < a.units.length; i++) a.units[i] = 0;
        }
        gs.armies.values().removeIf(a -> !a.alive());
    }

    static void unrest(GameState gs) {
        Set<Integer> garrisoned = new HashSet<>();
        for (Army a : gs.armies.values()) if (a.owner >= 0 && !a.decoy && a.battleId < 0 && !a.embarked && a.owner == gs.controller[a.province]) garrisoned.add(a.province);
        for (int p = 0; p < gs.P; p++) {
            if (gs.isSea(p)) continue;
            int o = gs.owner[p];
            boolean foreign = gs.world.provinces.get(p).nation != o;
            double delta = foreign ? 0.22 : -0.08;
            if (o >= 0 && gs.ns[o].stability < 30) delta += 0.12;
            if (garrisoned.contains(p)) delta -= 0.5;
            gs.unrest[p] = (float) Math.max(0, Math.min(100, gs.unrest[p] + delta));
            if (gs.devastation[p] < 1f) gs.devastation[p] = Math.min(1f, gs.devastation[p] + 0.0004f);
            if (gs.unrest[p] > 85 && !garrisoned.contains(p) && gs.rng.nextDouble() < 0.03) {
                Army r = new Army(gs.nextArmyId++, GameState.REBELS, p, "Insurgents");
                r.units[UnitType.INFANTRY.ordinal()] = 4; r.units[UnitType.ANTI_TANK.ordinal()] = 1;
                r.morale = 70;
                gs.armies.put(r.id, r);
                gs.unrest[p] = 45;
                if (o == gs.player) gs.news("Insurgents rise in " + gs.prov(p).name + "!");
            }
        }
        for (Army a : gs.armies.values()) {
            if (a.owner < 0 || a.battleId >= 0) continue;
            double cap = 60 + gs.ns[a.owner].stability * 0.4;
            if (a.morale < cap) a.morale = Math.min(cap, a.morale + 0.8);
            else if (a.morale > cap) a.morale -= 0.5;
        }
    }
}
