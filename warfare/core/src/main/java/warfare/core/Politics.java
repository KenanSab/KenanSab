package warfare.core;

import java.util.List;

/** Interest-group approval and elections. Groups react to the budget, so every slider has a political price. */
public final class Politics {
    private Politics() {}

    public static final String[] GROUPS = {"Military", "Business", "Workers", "General public"};
    public static final int ELECTION_DAYS = 1461;     // four years

    /** What each group would like to be at right now, 0..100. */
    public static double[] target(NationState s, boolean atWar) {
        Budget b = s.budget();
        double mil = 50 + 250 * (b.military - 0.05) + 6 * b.mobilization + (atWar ? 6 : 0) - (b.fuelRationing ? 3 : 0);
        double biz = 50 - 150 * (b.tax - Budget.TAX_DEFAULT) + 140 * (b.dev - 0.10) + 60 * (b.infra - 0.05) - 80 * s.inflation - 4 * b.mobilization;
        double wrk = 50 + 220 * (b.welfare - 0.08) - 70 * (b.tax - Budget.TAX_DEFAULT) - 70 * (b.security - 0.05) - 7 * b.mobilization + 12 * Math.max(0, -b.industryFocus);
        double pub = s.stability - (atWar ? 4 : 0) - s.warWeariness * 0.2 + 20 * (b.welfare - 0.08);
        return new double[]{clamp(mil), clamp(biz), clamp(wrk), clamp(pub)};
    }

    public static double[] weights(Government g) {
        return switch (g) {
            case AUTOCRACY, JUNTA -> new double[]{0.45, 0.2, 0.1, 0.25};
            case ONE_PARTY, THEOCRACY -> new double[]{0.3, 0.2, 0.2, 0.3};
            case MONARCHY -> new double[]{0.3, 0.25, 0.15, 0.3};
            default -> new double[]{0.12, 0.23, 0.25, 0.40};
        };
    }

    public static double overall(NationState s) {
        double[] w = weights(s.gov), a = s.approval;
        double v = 0;
        for (int i = 0; i < 4; i++) v += w[i] * a[i];
        return v;
    }

    private static double clamp(double v) { return Math.max(0, Math.min(100, v)); }

    /** Daily: groups drift toward their target; approval feeds back into stability. */
    static void daily(GameState gs, int n) {
        NationState s = gs.ns[n];
        double[] t = target(s, gs.atWarAny(n));
        for (int i = 0; i < 4; i++) s.approval[i] += (t[i] - s.approval[i]) * 0.02;
        double ov = overall(s);
        double drift = (ov - 50) * 0.0012;                    // happy nations are steadier, unhappy ones crumble
        s.stability = Math.max(0, Math.min(100, s.stability + (s.gov.elections ? drift : drift * 0.5)));
        if (s.gov.elections && gs.day >= s.nextElection) election(gs, n);
    }

    static void election(GameState gs, int n) {
        NationState s = gs.ns[n];
        s.nextElection = gs.day + ELECTION_DAYS;
        double ov = overall(s);
        boolean won = ov >= 50;
        String nation = gs.nationName(n);
        if (won) {
            s.stability = Math.min(100, s.stability + 3);
            if (n == gs.player) gs.news("Elections: the government of " + nation + " is re-elected (approval " + (int) ov + "%).");
            return;
        }
        gs.news("Elections: the opposition wins in " + nation + " (approval " + (int) ov + "%).");
        if (n == gs.player) {
            Events.post(gs, new GameEvent("election", n, "Elections: you lost",
                    "With approval at " + (int) ov + "% the voters have turned against you. The opposition expects the keys to government.",
                    List.of("Respect the result (policies reset, stability -3)", "Contest the result (stability -10, turn to authoritarian rule)")));
        } else {
            s.stability = Math.max(0, s.stability - 3);
            Budget fresh = new Budget();
            fresh.tax = 0.22 + (Math.abs((nation + gs.day).hashCode()) % 20) / 100.0;
            s.budget = fresh;
        }
    }
}
