package warfare.core;

import java.io.Serializable;
import java.util.*;

public final class NationState implements Serializable {
    public enum Track { LAND("Land Doctrine"), AIR("Air Power"), INTEL("Intelligence & Space");
        public final String label; Track(String l) { label = l; } }
    public enum Mission { RECON, AIR_SUPERIORITY, BOMBING, CLOSE_SUPPORT }

    public static final class AirWing implements Serializable {
        public int id, base, target = -1;
        public Mission mission = Mission.RECON;
        public double strength = 1;
    }
    public static final class Recruit implements Serializable {
        public int province, daysLeft;
        public final double[] units = new double[UnitType.N];
    }
    public static final class Construction implements Serializable {
        public int province, daysLeft;
        public Building type;
    }
    public static final class Deal implements Serializable {
        public int seller, buyer;
        public double oilPerDay, moneyPerDay;
    }

    public final int index;
    public boolean alive = true;
    public double money = 100, manpower = 50, oil = 100, stability = 65, warWeariness = 0;
    public int nukes, transports, lastWarDay = -1000;
    public final int[] tech = new int[Track.values().length];
    public Track researching = null;
    public double researchLeft;
    public final List<Recruit> recruitQueue = new ArrayList<>();
    public final List<Construction> constructions = new ArrayList<>();
    public final List<AirWing> airWings = new ArrayList<>();
    public final Map<Integer, Double> agents = new HashMap<>();   // target nation -> intel network level 0..100
    public double lastIncome, lastOilDelta;
    public boolean blockaded;

    public NationState(int index) { this.index = index; }
}
