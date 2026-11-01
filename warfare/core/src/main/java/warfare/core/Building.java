package warfare.core;

public enum Building {
    FACTORY("Factory", 60, 20, 3, "+output and production slots"),
    INFRASTRUCTURE("Roads & Rail", 40, 14, 3, "armies move faster through here"),
    FORTIFICATION("Fortification", 50, 18, 3, "+12% defense per level"),
    AIRFIELD("Airfield", 70, 20, 2, "base for air wings"),
    RADAR("Radar Station", 80, 22, 2, "detects contacts up to 3 provinces away"),
    SILO("Missile Silo", 150, 40, 2, "required to launch nuclear weapons");

    public final String label, effect;
    public final double cost;
    public final int days, maxLevel;

    Building(String label, double cost, int days, int maxLevel, String effect) {
        this.label = label; this.cost = cost; this.days = days; this.maxLevel = maxLevel; this.effect = effect;
    }
}
