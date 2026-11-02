package warfare.core;

import java.util.*;
import java.util.function.Predicate;

/** Achievement definitions. Evaluation is pure: persistence and Steam syncing live in the client. */
public final class Achievements {
    private Achievements() {}

    public record Def(String id, String title, String description, String icon, Predicate<GameState> test) {}

    private static int spies(GameState gs) {
        double best = 0;
        for (double v : gs.ns[gs.player].agents.values()) best = Math.max(best, v);
        return (int) best;
    }

    public static final List<Def> ALL = List.of(
            new Def("FIRST_BLOOD", "First Blood", "Win your first battle.", "battle", gs -> gs.stBattlesWon >= 1),
            new Def("VETERAN", "Veteran Command", "Win 10 battles.", "general", gs -> gs.stBattlesWon >= 10),
            new Def("TACTICIAN", "Tactician", "Win 3 battles where your tactic card countered theirs.", "t_flank", gs -> gs.stTacticWins >= 3),
            new Def("OUT_OF_NOWHERE", "Out of Nowhere", "Win a battle with Ambush while the enemy could not see you.", "t_ambush", gs -> gs.stAmbushWins >= 1),
            new Def("LAND_GRAB", "Land Grab", "Capture 10 provinces.", "wall", gs -> gs.stCaptures >= 10),
            new Def("CONQUEROR", "Conqueror", "Capture 50 provinces.", "trophy", gs -> gs.stCaptures >= 50),
            new Def("FALL_OF_A_CAPITAL", "The Fall of a Capital", "Capture an enemy capital.", "castle", gs -> gs.stCapitals >= 1),
            new Def("GREAT_POWER", "Great Power", "Control 150 provinces.", "tab_diplo", gs -> gs.ownedProvinces(gs.player) >= 150),
            new Def("TREASURY", "Full Coffers", "Hold 2,000 money.", "money", gs -> gs.ns[gs.player].money >= 2000),
            new Def("SCHOLAR", "Doctrine Reformed", "Reach level 2 in all three research tracks.", "research", gs -> {
                for (int t : gs.ns[gs.player].tech) if (t < 2) return false;
                return true;
            }),
            new Def("EYE_IN_THE_SKY", "Eye in the Sky", "Reach Intelligence & Space level 3 (satellites).", "recon", gs -> gs.ns[gs.player].tech[2] >= 3),
            new Def("SPYMASTER", "Spymaster", "Build an agent network of 70% inside a foreign state.", "spy", gs -> spies(gs) >= 70),
            new Def("COALITION", "Coalition Builder", "Sign an alliance with a nation outside your bloc.", "tab_diplo", gs -> {
                for (int n = 0; n < gs.N; n++) if (n != gs.player && gs.ally[gs.player][n] && !gs.world.nations.get(n).bloc.equals(gs.world.nations.get(gs.player).bloc)) return true;
                return false;
            }),
            new Def("DOOMSDAY", "Doomsday", "Launch a nuclear weapon.", "nuke", gs -> gs.stNukes >= 1),
            new Def("STEADY_HAND", "Steady Hand", "Keep stability above 80% for a full year.", "stability", gs -> gs.day >= 365 && gs.ns[gs.player].stability >= 80),
            new Def("SURVIVOR", "Survivor", "Survive two years.", "time", gs -> gs.day >= 730 && gs.ns[gs.player].alive));

    public static Def byId(String id) {
        for (Def d : ALL) if (d.id().equals(id)) return d;
        return null;
    }

    /** Returns the achievements newly satisfied by this state. */
    public static List<Def> newlyUnlocked(GameState gs, Set<String> already) {
        List<Def> out = new ArrayList<>();
        for (Def d : ALL) if (!already.contains(d.id()) && d.test().test(gs)) out.add(d);
        return out;
    }
}
