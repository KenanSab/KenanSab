package warfare.core;

import java.util.*;

/** Opinion between nations (-100..100): who trusts whom, and why. Drives alliances. */
public final class Relations {
    private Relations() {}

    private static final String[][] RIVALS = {
            {"IND", "PAK"}, {"ARM", "AZE"}, {"GRC", "TUR"}, {"ISR", "IRN"}, {"ISR", "SYR"}, {"SAU", "IRN"}, {"PRK", "KOR"}, {"CHN", "TWN"},
            {"RUS", "UKR"}, {"RUS", "GEO"}, {"ETH", "ERI"}, {"MAR", "DZA"}, {"KOR", "JPN"}, {"CHN", "IND"}, {"CHN", "JPN"}, {"RUS", "POL"},
            {"RUS", "EST"}, {"RUS", "LVA"}, {"RUS", "LTU"}, {"RUS", "USA"}, {"CHN", "USA"}, {"PRK", "USA"}, {"IRN", "USA"}, {"SRB", "KOS"},
            {"EGY", "ETH"}, {"AFG", "PAK"}, {"VEN", "USA"}, {"CUB", "USA"}, {"SDN", "SSD"}, {"RWA", "COD"}, {"CHN", "PHL"}, {"CHN", "VNM"}};
    private static final String[][] FRIENDS = {
            {"USA", "GBR"}, {"USA", "CAN"}, {"USA", "JPN"}, {"USA", "KOR"}, {"USA", "ISR"}, {"USA", "AUS"}, {"USA", "DEU"}, {"USA", "FRA"},
            {"RUS", "BLR"}, {"CHN", "PAK"}, {"CHN", "PRK"}, {"CHN", "RUS"}, {"TUR", "AZE"}, {"SAU", "ARE"}, {"GBR", "AUS"}, {"GBR", "CAN"},
            {"FRA", "DEU"}, {"IND", "RUS"}, {"IRN", "SYR"}, {"RUS", "IRN"}, {"RUS", "SYR"}, {"CHN", "KHM"}, {"CHN", "LAO"}, {"UKR", "POL"},
            {"JPN", "AUS"}, {"POL", "LTU"}, {"EST", "FIN"}, {"SWE", "FIN"}, {"NOR", "DNK"}, {"ESP", "PRT"}, {"ISR", "ARE"}};

    private static boolean pair(String[][] list, String a, String b) {
        for (String[] p : list) if ((p[0].equals(a) && p[1].equals(b)) || (p[0].equals(b) && p[1].equals(a))) return true;
        return false;
    }

    /** The part of the baseline that never changes (blocs, continents, rivalries, friendships, personal touch): computed once. */
    private static float[][] statics(GameState gs) {
        if (gs.relStatic == null) {
            float[][] m = new float[gs.N][gs.N];
            for (int a = 0; a < gs.N; a++)
                for (int b = 0; b < gs.N; b++) {
                    if (a == b) continue;
                    WorldData.NationInfo x = gs.world.nations.get(a), y = gs.world.nations.get(b);
                    double v = 0;
                    if (!x.bloc.equals("NONE") && x.bloc.equals(y.bloc)) v += 45;
                    if (x.continent.equals(y.continent)) v += 3;
                    if (pair(RIVALS, x.tag, y.tag)) v -= 65;
                    if (pair(FRIENDS, x.tag, y.tag)) v += 46;
                    v += ((x.tag + y.tag).hashCode() * 31 % 13 + 13) % 13 - 6;     // a fixed, unremarkable personal touch so no two pairs are identical
                    m[a][b] = (float) v;
                }
            gs.relStatic = m;
        }
        return gs.relStatic;
    }

    /** What opinion drifts back to when nothing special happens. */
    public static double baseline(GameState gs, int a, int b) {
        double v = statics(gs)[a][b];
        Government gx = gs.ns[a].gov, gy = gs.ns[b].gov;
        boolean dx = gx == Government.DEMOCRACY || gx == Government.REPUBLIC, dy = gy == Government.DEMOCRACY || gy == Government.REPUBLIC;
        if (gx == Government.DEMOCRACY && gy == Government.DEMOCRACY) v += 10;
        else if (gx == gy) v += 5;
        else if (dx != dy) v -= 7;
        if (gs.sharesBorder(a, b)) v -= 6;
        return Math.max(-100, Math.min(100, v));
    }

    public static void init(GameState gs) {
        for (int a = 0; a < gs.N; a++)
            for (int b = a + 1; b < gs.N; b++) {
                float v = (float) baseline(gs, a, b);
                gs.opinion[a][b] = v; gs.opinion[b][a] = v;
            }
    }

    /** Daily drift back toward the baseline (cheap: only a slice of pairs each day). */
    static void drift(GameState gs) {
        int slice = gs.day % 10;
        for (int a = slice; a < gs.N; a += 10)
            for (int b = 0; b < gs.N; b++) {
                if (a == b) continue;
                float base = (float) baseline(gs, a, b);
                gs.opinion[a][b] += (base - gs.opinion[a][b]) * 0.06f;
            }
    }

    public static void change(GameState gs, int a, int b, double delta) {
        if (a == b || a < 0 || b < 0) return;
        gs.opinion[a][b] = (float) Math.max(-100, Math.min(100, gs.opinion[a][b] + delta));
    }

    public static void both(GameState gs, int a, int b, double delta) { change(gs, a, b, delta); change(gs, b, a, delta); }

    /** How {@code b} judges an alliance with {@code a}: the score and a short explanation. */
    public record Verdict(boolean accept, double score, double needed, String reason) {}

    public static Verdict allianceVerdict(GameState gs, int a, int b) {
        double need = 50;
        double op = gs.opinion[b][a];
        StringBuilder why = new StringBuilder(String.format("their opinion of you is %+.0f", op));
        double score = op;
        int common = 0;
        for (int c = 0; c < gs.N; c++) if (c != a && c != b && gs.war[a][c] && gs.war[b][c]) common++;
        if (common > 0) { score += 22; why.append(", shared enemy +22"); }
        boolean aAtWar = gs.atWarAny(a);
        for (int c = 0; c < gs.N; c++) {
            if (gs.war[a][c] && c != b && (gs.isAlly(b, c) || gs.opinion[b][c] > 40)) { score -= 30; why.append(", you are at war with their friend ").append(gs.nationName(c)).append(" -30"); break; }
        }
        if (aAtWar && common == 0 && !gs.atWarAny(b)) { score -= 12; why.append(", they do not want to be dragged into your war -12"); }
        double ratio = gs.nationHp(a) / Math.max(1, gs.nationHp(b));
        if (ratio > 2) { score += 8; why.append(", protection of a stronger power +8"); }
        else if (ratio < 0.4) { score -= 8; why.append(", you are too weak to help them -8"); }
        if (gs.directAlliances(b) >= 4) { score -= 40; why.append(", they already have 4 alliances -40"); }
        if (gs.ns[b].stability < 25) { score -= 10; why.append(", unstable government -10"); }
        return new Verdict(score >= need, score, need, why.toString());
    }
}
