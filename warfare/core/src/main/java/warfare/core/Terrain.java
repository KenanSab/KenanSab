package warfare.core;

public enum Terrain {
    PLAINS(1.0, 1.0), FOREST(1.4, 1.2), MOUNTAIN(2.2, 1.5), DESERT(1.3, 0.95), JUNGLE(1.8, 1.25), ARCTIC(1.8, 1.1), SEA(1.0, 1.0);

    public final double moveCost;
    public final double defense;

    Terrain(double moveCost, double defense) { this.moveCost = moveCost; this.defense = defense; }

    /** Seasonal movement multiplier: northern winter, monsoon, spring mud. Day 0 = 1 Nov. */
    public static double weather(double lat, int dayOfYear0Nov, Terrain t) {
        int doy = (dayOfYear0Nov + 304) % 365; // day-of-year (0 = 1 Jan)
        boolean northWinter = doy < 60 || doy > 335;
        boolean southWinter = doy > 150 && doy < 245;
        double m = 1.0;
        if (lat > 42 && northWinter) m *= 1.35;
        else if (lat < -35 && southWinter) m *= 1.2;
        if (lat > 5 && lat < 30 && doy > 160 && doy < 270 && (t == JUNGLE || t == PLAINS || t == FOREST)) m *= 1.25;
        if (lat > 40 && doy > 60 && doy < 110 && (t == PLAINS || t == FOREST)) m *= 1.15; // spring mud
        return m;
    }
}
