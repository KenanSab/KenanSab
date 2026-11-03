package warfare.core;

import java.util.List;

/** Random events with choices. The player is asked; AI nations pick an option at random. */
public final class Events {
    private Events() {}

    static void post(GameState gs, GameEvent ev) {
        ev.day = gs.day;
        if (ev.nation == gs.player) { gs.pendingEvents.add(ev); gs.pauseRequested = true; }
        else apply(gs, ev, gs.rng.nextInt(ev.options.size()));
    }

    /** What happens if a decision is left alone: the cautious option for most, accepting the vote, crushing a revolt, refusing peace. */
    static int defaultOption(GameEvent ev) {
        if (ev.id.startsWith("peace:")) return 1;
        return switch (ev.id) { case "election", "revolt" -> 0; default -> ev.options.size() - 1; };
    }

    static void daily(GameState gs) {
        for (GameEvent ev : new java.util.ArrayList<>(gs.pendingEvents)) {
            if (gs.day - ev.day <= 45) continue;
            gs.pendingEvents.remove(ev);
            apply(gs, ev, defaultOption(ev));
            gs.news("No decision was made on \"" + ev.title + "\": it resolved itself.");
        }
        for (int n = 0; n < gs.N; n++) {
            NationState s = gs.ns[n];
            if (!s.alive) continue;
            double p = n == gs.player ? 0.005 : 0.0008;
            if (gs.rng.nextDouble() >= p) continue;
            String name = gs.nationName(n);
            if (s.stability < 22 && gs.rng.nextDouble() < 0.6) {
                post(gs, new GameEvent("revolt", n, "Revolution in " + name, "Strikes and riots have spread to the capital. The regime is shaking.",
                        List.of("Crush it with the army", "Concede sweeping reforms"))); continue;
            }
            if (s.stability < 40 && gs.rng.nextDouble() < 0.4) {
                post(gs, new GameEvent("coup", n, "Coup plot uncovered", "Intelligence reports a group of officers plotting against the government.",
                        List.of("Purge the officers", "Appease the generals with funds", "Do nothing"))); continue;
            }
            switch (gs.rng.nextInt(6)) {
                case 0 -> post(gs, new GameEvent("border", n, "Border incident", "A patrol exchanged fire with a neighbour's border guards. Public opinion is heated.",
                        List.of("Send a protest note", "Mobilise reserves", "Ignore it")));
                case 1 -> post(gs, new GameEvent("oil", n, "Oil field discovered", "Geologists report a promising oil field in a remote province.",
                        List.of("Develop the field (+oil)", "Sell the rights (+money)")));
                case 2 -> post(gs, new GameEvent("boom", n, "Industrial boom", "Factories report record output this quarter.",
                        List.of("Reinvest in industry", "Collect the tax windfall")));
                case 3 -> post(gs, new GameEvent("defector", n, "A defector arrives", "A mid-level intelligence officer from a rival state asks for asylum.",
                        List.of("Debrief him (+intel network)", "Resettle and reward (+stability)")));
                case 4 -> post(gs, new GameEvent("disaster", n, "Natural disaster", "Floods have devastated several provinces.",
                        List.of("Emergency relief (-money)", "Let the provinces cope")));
                default -> post(gs, new GameEvent("mutiny", n, "Unrest in the ranks", "Soldiers complain of unpaid wages and poor rations.",
                        List.of("Pay the arrears (-money)", "Court-martial the ringleaders")));
            }
        }
    }

    static GameState.Result apply(GameState gs, GameEvent ev, int opt) {
        NationState s = gs.ns[ev.nation];
        String msg;
        String id = ev.id.startsWith("peace:") ? "peace" : ev.id;
        switch (id) {
            case "peace" -> {
                int other = Integer.parseInt(ev.id.substring(6));
                if (opt == 0 && gs.war[ev.nation][other]) { gs.makePeace(ev.nation, other); msg = "Peace signed."; }
                else { gs.offerCooldown[other][ev.nation] = gs.day + 150; msg = "Offer refused. They will not ask again for a while."; }
            }
            case "election" -> {
                if (opt == 0) { s.stability -= 3; s.budget = new Budget(); msg = "The new government takes office with its own priorities."; }
                else { s.stability -= 10; s.gov = Government.AUTOCRACY; s.nextElection = Integer.MAX_VALUE; msg = "You cling to power. " + gs.nationName(ev.nation) + " is now an autocracy."; }
            }
            case "border" -> {
                switch (opt) {
                    case 0 -> { s.stability += 1; msg = "The note is filed away."; }
                    case 1 -> { s.manpower = Math.max(0, s.manpower - 5); s.money -= 10; s.stability += 2; msg = "Reserves mobilised."; }
                    default -> { s.stability -= 2; msg = "Critics call you weak."; }
                }
            }
            case "oil" -> { if (opt == 0) { s.oil += 60; s.money -= 15; msg = "Oil flows."; } else { s.money += 90; msg = "Rights sold."; } }
            case "boom" -> {
                if (opt == 0) { int cap = gs.world.nations.get(ev.nation).capital; if (gs.owner[cap] == ev.nation && gs.factory[cap] < 3) gs.factory[cap]++; msg = "Capital industry expanded."; }
                else { s.money += 70; msg = "Treasury swells."; }
            }
            case "defector" -> {
                if (opt == 0) {
                    int t = gs.rng.nextInt(gs.N);
                    if (t != ev.nation) s.agents.merge(t, 18.0, Double::sum);
                    msg = "The defector knows a great deal about " + gs.nationName(t) + ".";
                } else { s.stability += 2; msg = "A fine propaganda story."; }
            }
            case "disaster" -> {
                if (opt == 0) { s.money -= 40; s.stability += 3; msg = "Relief efforts steady the public."; } else { s.stability -= 4; msg = "Public anger rises."; }
            }
            case "mutiny" -> {
                if (opt == 0) { s.money -= 50; for (Army a : gs.armies.values()) if (a.owner == ev.nation) a.morale = Math.min(100, a.morale + 8); msg = "Morale recovers."; }
                else { s.stability -= 2; for (Army a : gs.armies.values()) if (a.owner == ev.nation) a.morale = Math.max(20, a.morale - 4); msg = "Discipline restored, at a cost."; }
            }
            case "coup" -> {
                switch (opt) {
                    case 0 -> { s.stability += 8; s.manpower = Math.max(0, s.manpower - 4); msg = "The plotters are purged."; }
                    case 1 -> { s.money -= 60; s.stability += 4; msg = "The generals are bought off."; }
                    default -> {
                        if (gs.rng.nextDouble() < 0.5) { s.stability = 50; s.money *= 0.7; for (Army a : gs.armies.values()) if (a.owner == ev.nation) a.morale = Math.max(20, a.morale - 15); msg = "A coup d'etat! The government falls."; }
                        else { s.stability += 1; msg = "The plot fizzles."; }
                    }
                }
            }
            case "revolt" -> {
                if (opt == 0) { s.stability += 10; s.manpower = Math.max(0, s.manpower - 8); for (Army a : gs.armies.values()) if (a.owner == ev.nation) a.morale -= 8; msg = "Order is restored by force."; }
                else { s.money -= 80; s.stability += 18; msg = "Reforms calm the streets."; }
            }
            default -> msg = "";
        }
        s.stability = Math.max(0, Math.min(100, s.stability));
        if (ev.nation == gs.player) gs.news(ev.title + ": " + msg);
        return new GameState.Result(true, msg);
    }
}
