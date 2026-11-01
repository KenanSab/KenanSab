package warfare.core;

/** The "General": picks tactic cards using only what the army's nation can see. */
public final class TacticAdvisor {
    private TacticAdvisor() {}

    public static Tactic choose(GameState gs, Army me, Army enemy, boolean attacking) {
        Terrain ter = gs.prov(me.province).terrain;
        Army believed = believedEnemy(gs, me, enemy);
        boolean unseen = false;
        Tactic best = null;
        double bestScore = -1;
        double[] prior = attacking
                ? new double[]{0.15, 0.1, 0.2, 0.3, 0.25}     // defenders: hold / depth likely
                : new double[]{0.4, 0.3, 0.3, 0, 0};            // attackers: frontal / flanking / ambush likely
        Intel.Sighting s = me.owner >= 0 && gs.intel[me.owner] != null ? gs.intel[me.owner].sightingOf(enemy.id) : null;
        if (s != null && s.composition != null) {
            // we know the composition: armour-heavy enemies flank, artillery-heavy ones assault
            if (!attacking) {
                prior[0] += 0.4 * (believed.share(UnitType.ARMOR, UnitType.ARTILLERY));
                prior[1] += 0.4 * (believed.share(UnitType.ARMOR, UnitType.MECHANIZED, UnitType.SPECIAL_FORCES));
            }
        }
        for (Tactic t : Tactic.values()) {
            if (attacking && !t.offensive && t != Tactic.AMBUSH) continue;
            if (!attacking && t.offensive && t != Tactic.AMBUSH) {
                // a defender may still counterattack with ambush; frontal/flank only as last resort
                continue;
            }
            double score = 0;
            for (Tactic e : Tactic.values()) {
                double p = attacking ? (e.offensive ? 0 : prior[e.ordinal()]) : (e.offensive ? prior[e.ordinal()] : 0);
                if (p == 0) continue;
                score += p * Combat.effectiveness(t, e, me, ter, unseen);
            }
            if (score > bestScore) { bestScore = score; best = t; }
        }
        if (best == null) best = attacking ? Tactic.FRONTAL_ASSAULT : Tactic.HOLD_THE_LINE;
        return best;
    }

    /** What our nation believes the enemy army is made of. */
    static Army believedEnemy(GameState gs, Army me, Army enemy) {
        Army b = new Army(-1, enemy.owner, enemy.province, "?");
        Intel.Sighting s = me.owner >= 0 && gs.intel[me.owner] != null ? gs.intel[me.owner].sightingOf(enemy.id) : null;
        if (s != null && s.composition != null) { System.arraycopy(s.composition, 0, b.units, 0, UnitType.N); return b; }
        double est = s != null && s.strengthEstimate > 0 ? s.strengthEstimate / 11.0 : 8;
        b.units[UnitType.INFANTRY.ordinal()] = est * 0.7;      // unknown composition: assume a typical mixed force
        b.units[UnitType.MECHANIZED.ordinal()] = est * 0.1;
        b.units[UnitType.ARMOR.ordinal()] = est * 0.1;
        b.units[UnitType.ARTILLERY.ordinal()] = est * 0.1;
        return b;
    }
}
