package warfare.core;

public enum UnitType {
    INFANTRY("Infantry", 3, 5, 10, 22, 10, 1, 0.0, 6),
    MECHANIZED("Mechanized Infantry", 5, 5, 14, 38, 25, 1, 0.003, 9),
    ARMOR("Armor", 10, 6, 16, 36, 45, 1, 0.005, 12),
    ARTILLERY("Artillery / Rocket Artillery", 9, 2, 6, 24, 30, 1, 0.002, 10),
    ANTI_AIR("Air Defense", 1, 3, 6, 24, 20, 1, 0.001, 8),
    ANTI_TANK("Anti-Tank", 6, 4, 7, 24, 20, 1, 0.001, 8),
    SPECIAL_FORCES("Special Forces", 6, 4, 8, 28, 40, 1, 0.001, 14);

    public final String label;
    public final double attack, defense, hp, speedKmDay, cost, manpower, oilUse;
    public final int buildDays;

    UnitType(String label, double attack, double defense, double hp, double speed, double cost, double manpower, double oil, int days) {
        this.label = label; this.attack = attack; this.defense = defense; this.hp = hp;
        this.speedKmDay = speed; this.cost = cost; this.manpower = manpower; this.oilUse = oil; this.buildDays = days;
    }

    public static final int N = values().length;
}
