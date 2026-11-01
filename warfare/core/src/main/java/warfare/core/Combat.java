package warfare.core;

import java.io.Serializable;
import java.util.Random;

/** Tactic-driven battle resolution. Pure functions plus a small Battle record. */
public final class Combat {
    private Combat() {}

    public static final class Battle implements Serializable {
        public int id, province, attacker, defender;   // army ids
        public int startDay, days;
        public boolean attackerUnseen, defenderUnseen; // true if the other side had no vision of them at contact
        public double attStartHp, defStartHp, attLost, defLost;
        public Tactic attTactic, defTactic;            // last used
        public String lastNote = "";
        public boolean finished;
        public int winnerNation = -2;
    }

    public static final class Report implements Serializable {
        public int day, province, attackerNation, defenderNation, winnerNation;
        public String attackerName, defenderName, provinceName, text;
        public double attLost, defLost;
        public int days;
    }

    /** How well a tactic suits an army's composition (0.8 .. 1.3). */
    public static double affinity(Tactic t, Army a) {
        double armor = a.share(UnitType.ARMOR), mech = a.share(UnitType.MECHANIZED), art = a.share(UnitType.ARTILLERY);
        double inf = a.share(UnitType.INFANTRY), at = a.share(UnitType.ANTI_TANK), sf = a.share(UnitType.SPECIAL_FORCES);
        double aa = a.share(UnitType.ANTI_AIR);
        double v = switch (t) {
            case FRONTAL_ASSAULT -> 0.85 + 0.9 * (armor + art) + 0.2 * mech;
            case FLANKING -> 0.8 + 0.9 * (armor + mech) + 1.0 * sf;
            case AMBUSH -> 0.85 + 0.5 * inf + 1.5 * sf + 0.6 * at;
            case HOLD_THE_LINE -> 0.85 + 0.45 * inf + 0.8 * art + 0.9 * at;
            case DEFENSE_IN_DEPTH -> 0.85 + 0.4 * inf + 0.6 * mech + 0.5 * aa + 0.4 * armor;
        };
        return Math.min(1.3, v);
    }

    /** Terrain suitability of a tactic (0.8 .. 1.25). */
    public static double terrainFit(Tactic t, Terrain ter) {
        return switch (ter) {
            case MOUNTAIN -> switch (t) { case AMBUSH, HOLD_THE_LINE -> 1.2; case FRONTAL_ASSAULT, FLANKING -> 0.8; default -> 1.0; };
            case FOREST, JUNGLE -> switch (t) { case AMBUSH -> 1.25; case DEFENSE_IN_DEPTH -> 1.1; case FLANKING -> 0.85; default -> 1.0; };
            case PLAINS -> switch (t) { case FLANKING, FRONTAL_ASSAULT -> 1.15; case AMBUSH -> 0.85; default -> 1.0; };
            case DESERT -> switch (t) { case FLANKING -> 1.2; case DEFENSE_IN_DEPTH -> 1.1; case AMBUSH -> 0.85; default -> 1.0; };
            case ARCTIC -> switch (t) { case HOLD_THE_LINE, DEFENSE_IN_DEPTH -> 1.1; case FLANKING -> 0.8; default -> 0.95; };
            default -> 1.0;
        };
    }

    public static double counter(Tactic mine, Tactic theirs) {
        if (mine.beats(theirs)) return 1.30;
        if (theirs.beats(mine)) return 0.80;
        return 1.0;
    }

    /** Overall tactic effectiveness for one side. */
    public static double effectiveness(Tactic mine, Tactic theirs, Army a, Terrain ter, boolean unseen) {
        double e = affinity(mine, a) * terrainFit(mine, ter) * counter(mine, theirs);
        if (unseen) e *= mine == Tactic.AMBUSH ? 1.25 : 1.08;
        return e;
    }

    /** Advance one battle by one day. Returns true when the battle ended. */
    public static boolean resolveDay(Battle b, Army att, Army def, Terrain ter, double fort,
                                     double attTech, double defTech, boolean attOil, boolean defOil,
                                     double attAir, double defAir, Tactic ta, Tactic td, Random rng) {
        b.days++;
        b.attTactic = ta; b.defTactic = td;
        double ea = effectiveness(ta, td, att, ter, b.attackerUnseen && b.days <= 3);
        double ed = effectiveness(td, ta, def, ter, b.defenderUnseen && b.days <= 3);
        double terrainDef = ter.defense * (1 + 0.12 * fort) * (td == Tactic.HOLD_THE_LINE ? 1.15 : 1.0);
        double aAtt = attackPower(att, def, attOil) * ea * attTech * moraleMult(att) * (1 + attAir - 0.5 * defAir);
        double dAtt = attackPower(def, att, defOil) * ed * defTech * moraleMult(def) * (1 + defAir - 0.5 * attAir);
        double aDef = avgDef(att) * ea * attTech;
        double dDef = avgDef(def) * ed * defTech * terrainDef;
        double rollA = 0.92 + rng.nextDouble() * 0.16, rollD = 0.92 + rng.nextDouble() * 0.16;
        double lossToDef = 0.55 * aAtt * rollA * 10.0 / (10.0 + dDef);
        double lossToAtt = 0.55 * dAtt * rollD * 10.0 / (10.0 + aDef);
        double dl = applyLoss(def, lossToDef), al = applyLoss(att, lossToAtt);
        b.defLost += dl; b.attLost += al;
        double defFrac = dl / Math.max(1, b.defStartHp), attFrac = al / Math.max(1, b.attStartHp);
        att.morale = Math.max(0, att.morale - (3 + attFrac * 70) + (defFrac > attFrac ? 2.5 : 0));
        def.morale = Math.max(0, def.morale - (3 + defFrac * 70) + (attFrac > defFrac ? 2.5 : 0));
        b.lastNote = String.format("%s vs %s: %s", ta.label, td.label,
                ta.beats(td) ? "attacker's tactic counters" : td.beats(ta) ? "defender's tactic counters" : "no counter");
        boolean over = att.morale <= 18 || def.morale <= 18 || !att.alive() || !def.alive() || b.days >= 20;
        if (over) {
            b.finished = true;
            if (!att.alive() || att.morale <= 18) b.winnerNation = def.owner;
            else if (!def.alive() || def.morale <= 18) b.winnerNation = att.owner;
            else b.winnerNation = def.owner; // timed out: defender holds
        }
        return over;
    }

    static double moraleMult(Army a) { return 0.7 + 0.3 * Math.min(100, a.morale) / 100.0; }

    static double attackPower(Army a, Army enemy, boolean hasOil) {
        double enemyArmor = enemy.share(UnitType.ARMOR, UnitType.MECHANIZED);
        double p = 0;
        for (UnitType t : UnitType.values()) {
            double u = a.units[t.ordinal()];
            if (u <= 0) continue;
            double att = t.attack;
            if (t == UnitType.ANTI_TANK) att *= 1 + 1.8 * enemyArmor;
            if ((t == UnitType.ARMOR || t == UnitType.MECHANIZED || t == UnitType.ARTILLERY) && !hasOil) att *= 0.7;
            p += u * att;
        }
        return p;
    }

    static double avgDef(Army a) {
        double d = 0, n = 0;
        for (UnitType t : UnitType.values()) { d += a.units[t.ordinal()] * t.defense; n += a.units[t.ordinal()]; }
        return n > 0 ? d / n : 0;
    }

    /** Remove hit points proportionally from all unit types; returns HP actually lost. */
    static double applyLoss(Army a, double hpLoss) {
        double hp = a.hp();
        if (hp <= 0) return 0;
        double lost = Math.min(hp, hpLoss);
        double keep = (hp - lost) / hp;
        for (int i = 0; i < a.units.length; i++) a.units[i] *= keep;
        if (!a.alive()) for (int i = 0; i < a.units.length; i++) a.units[i] = 0;
        return lost;
    }
}
