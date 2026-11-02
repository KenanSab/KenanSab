package warfare.core;

import java.io.Serializable;
import java.util.ArrayDeque;
import java.util.Deque;

public final class Army implements Serializable {
    public enum Mode { GENERAL, MANUAL }

    public final int id;
    public int owner;                  // nation index, -1 = rebels
    public int province;
    public int prevProvince = -1;
    public String name;
    public final double[] units = new double[UnitType.N];
    public double morale = 75;
    public Mode mode = Mode.GENERAL;
    public Tactic tactic = null;       // manual stance (null = ask at contact)
    public final Deque<Integer> path = new ArrayDeque<>();
    public double progressKm;
    public boolean embarked;
    public int battleId = -1;
    public int occupyingSince = -1;
    public boolean decoy;          // fake force: looks like `fake` battalions from afar, exposed up close
    public int fake;
    public int raidCooldown;

    public Army(int id, int owner, int province, String name) {
        this.id = id; this.owner = owner; this.province = province; this.name = name;
    }

    public double totalUnits() { double s = 0; for (double u : units) s += u; return s; }

    public double hp() { double s = 0; for (UnitType t : UnitType.values()) s += units[t.ordinal()] * t.hp; return s; }

    public double share(UnitType... types) {
        double tot = totalUnits();
        if (tot <= 0) return 0;
        double s = 0;
        for (UnitType t : types) s += units[t.ordinal()];
        return s / tot;
    }

    /** Slowest unit decides march speed (km per day). */
    public double speed() {
        double sp = Double.MAX_VALUE;
        for (UnitType t : UnitType.values()) if (units[t.ordinal()] > 0.5) sp = Math.min(sp, t.speedKmDay);
        return sp == Double.MAX_VALUE ? 20 : sp;
    }

    public double oilUsePerDay() {
        double s = 0;
        for (UnitType t : UnitType.values()) s += units[t.ordinal()] * t.oilUse;
        return s;
    }

    public boolean alive() { return totalUnits() >= 0.5; }

    public void absorb(Army o) {
        double a = hp(), b = o.hp();
        for (int i = 0; i < units.length; i++) units[i] += o.units[i];
        if (a + b > 0) morale = (morale * a + o.morale * b) / (a + b);
        for (int i = 0; i < o.units.length; i++) o.units[i] = 0;
    }
}
