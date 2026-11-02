package warfare.core;

import java.io.Serializable;
import java.util.*;

/**
 * One nation's picture of the world. Everything the player (or AI) is allowed to know lives here.
 * Quality of what is seen right now: 0 nothing, 1 contact only, 2 owner + strength estimate, 3 exact + composition.
 * Old information decays: ownership turns stale, sightings expire after {@link #SIGHTING_LIFETIME} days.
 */
public final class Intel implements Serializable {
    public static final int SIGHTING_LIFETIME = 45;

    public static final class Sighting implements Serializable {
        public int armyId, owner, province, day, quality;
        public double strengthEstimate;      // HP-based, fuzzy at quality 2
        public double[] composition;         // null unless quality 3
        public boolean ownerKnown;
        public boolean decoy;                // only true once quality 3 vision exposed it
    }

    public final byte[] quality;             // current-day vision quality per province
    public final int[] lastSeen;             // day each province was last seen (negative = atlas knowledge)
    public final int[] knownOwner;           // owner as last observed
    public final byte[] knownFort;           // fortification level as last observed (buildings are hidden info)
    public final Map<Integer, Sighting> sightings = new HashMap<>();
    public int computedDay = -1;
    public final byte[] base;                // own sensors only (shared with allies before merging)

    public Intel(int provinces) {
        quality = new byte[provinces];
        lastSeen = new int[provinces];
        knownOwner = new int[provinces];
        knownFort = new byte[provinces];
        base = new byte[provinces];
        Arrays.fill(knownOwner, -1);
    }

    public static double confidence(int ageDays) { return Math.max(0.0, 1.0 - ageDays / 60.0); }

    public double provinceConfidence(int p, int today) { return confidence(today - lastSeen[p]); }

    public Sighting sightingOf(int armyId) { return sightings.get(armyId); }

    /** Sightings the owner may still act on at this date, with their current confidence. */
    public List<Sighting> activeSightings(int today) {
        List<Sighting> out = new ArrayList<>();
        for (Sighting s : sightings.values()) if (today - s.day <= SIGHTING_LIFETIME) out.add(s);
        return out;
    }

    public void expire(int today) {
        sightings.values().removeIf(s -> today - s.day > SIGHTING_LIFETIME);
    }
}
