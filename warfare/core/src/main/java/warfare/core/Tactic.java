package warfare.core;

/** Tactic cards. Soft rock-paper-scissors: each tactic beats two others and loses to two. */
public enum Tactic {
    FRONTAL_ASSAULT("Frontal Assault", "Overwhelming push at the enemy line. Loves armor and artillery.", true),
    FLANKING("Flanking / Envelopment", "Swing around the enemy. Needs fast armor, mech and special forces.", true),
    AMBUSH("Ambush / Hit-and-run", "Strike from cover. Devastating if the enemy cannot see you coming.", true),
    HOLD_THE_LINE("Hold the Line", "Dig in and absorb the blow. Infantry, artillery and anti-tank shine.", false),
    DEFENSE_IN_DEPTH("Defense in Depth / Scorched Earth", "Trade ground for time with layered reserves.", false);

    public final String label, description;
    public final boolean offensive;

    Tactic(String label, String description, boolean offensive) {
        this.label = label; this.description = description; this.offensive = offensive;
    }

    /** True if this tactic counters {@code o}. Every pair has exactly one winner. */
    public boolean beats(Tactic o) {
        return switch (this) {
            case FRONTAL_ASSAULT -> o == DEFENSE_IN_DEPTH || o == AMBUSH;
            case FLANKING -> o == HOLD_THE_LINE || o == FRONTAL_ASSAULT;
            case AMBUSH -> o == FLANKING || o == HOLD_THE_LINE;
            case HOLD_THE_LINE -> o == FRONTAL_ASSAULT || o == DEFENSE_IN_DEPTH;
            case DEFENSE_IN_DEPTH -> o == FLANKING || o == AMBUSH;
        };
    }
}
