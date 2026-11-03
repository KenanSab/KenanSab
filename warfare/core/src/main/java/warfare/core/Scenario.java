package warfare.core;

/** How the world starts: peaceful, or with some of today's flashpoints already burning. */
public enum Scenario {
    QUIET("Quiet world", "No wars. Tensions build from the news and from your own choices.", new String[0][]),
    FLASHPOINTS("Flashpoints", "Four wars are already under way: Russia-Ukraine, India-Pakistan, Armenia-Azerbaijan, Ethiopia-Eritrea.",
            new String[][]{{"RUS", "UKR"}, {"IND", "PAK"}, {"AZE", "ARM"}, {"ETH", "ERI"}}),
    WORLD_ON_FIRE("World on fire", "Ten wars on four continents, alliances being dragged in. Expect the world to go to pieces.",
            new String[][]{{"RUS", "UKR"}, {"IND", "PAK"}, {"AZE", "ARM"}, {"ETH", "ERI"}, {"CHN", "TWN"}, {"ISR", "IRN"}, {"PRK", "KOR"}, {"SAU", "YEM"}, {"TUR", "SYR"}, {"VEN", "GUY"}});

    public final String label, blurb;
    final String[][] wars;

    Scenario(String label, String blurb, String[][] wars) { this.label = label; this.blurb = blurb; this.wars = wars; }

    /** Starts the wars of this scenario: dated back a few months so peace talks are possible, with weariness already building. */
    void apply(GameState gs) {
        for (String[] pair : wars) {
            Integer a = gs.world.nationByTag.get(pair[0]), b = gs.world.nationByTag.get(pair[1]);
            if (a == null || b == null || gs.war[a][b] || gs.isAlly(a, b)) continue;
            gs.declareWar(a, b);
            gs.warStart[a][b] = gs.warStart[b][a] = gs.day - 90;
            gs.ns[a].lastWarDay = gs.ns[b].lastWarDay = gs.day - 90;
            gs.ns[a].warWeariness = Math.max(gs.ns[a].warWeariness, 12);
            gs.ns[b].warWeariness = Math.max(gs.ns[b].warWeariness, 12);
        }
        gs.news.clear();
        if (wars.length > 0) gs.news("The year is 2026. " + label + ": " + blurb);
    }
}
