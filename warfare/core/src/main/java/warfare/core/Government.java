package warfare.core;

import java.util.*;

/** Government types. They change how a nation handles war weariness, tax anger and coups, and whether it holds elections. */
public enum Government {
    DEMOCRACY("Democracy", 1.6, 3, 1.3, 0.6, true),
    REPUBLIC("Republic", 1.2, 0, 1.0, 1.0, true),
    MONARCHY("Monarchy", 0.9, 2, 0.9, 0.8, false),
    AUTOCRACY("Autocracy", 0.7, -2, 0.6, 1.5, false),
    ONE_PARTY("One-party state", 0.7, 2, 0.7, 0.7, false),
    THEOCRACY("Theocracy", 0.8, 2, 0.7, 0.9, false),
    JUNTA("Military junta", 0.8, -4, 0.8, 2.0, false);

    public final String label;
    public final double warWeariness, taxAnger, coupRisk;
    public final int stabilityOffset;
    public final boolean elections;

    Government(String label, double warWeariness, int stabilityOffset, double taxAnger, double coupRisk, boolean elections) {
        this.label = label; this.warWeariness = warWeariness; this.stabilityOffset = stabilityOffset; this.taxAnger = taxAnger;
        this.coupRisk = coupRisk; this.elections = elections;
    }

    private static final Map<String, Government> BY_TAG = new HashMap<>();
    private static void put(Government g, String tags) { for (String t : tags.split(" ")) BY_TAG.put(t, g); }
    static {
        put(DEMOCRACY, "USA CAN GBR FRA DEU ITA ESP PRT NLD BEL LUX DNK NOR SWE FIN ISL IRL CHE AUT CZE SVK POL HUN SVN HRV EST LVA LTU GRC CYP MLT AUS NZL JPN KOR TWN IND ISR CHL URY CRI BRA ARG MEX COL PER BWA ZAF GHA JAM TTO BRB MUS CPV NAM LIE AND SMR MCO");
        put(REPUBLIC, "TUR UKR ROU BGR SRB ALB MKD MNE BIH MDA GEO ARM IDN PHL MYS PAK BGD LKA NPL MNG NGA KEN TZA ETH SEN PAN ECU BOL PRY DOM GTM HND SLV LBR SLE MWI ZMB MOZ MAR TUN IRQ LBN PNG FJI KOS");
        put(MONARCHY, "SAU ARE QAT KWT OMN BHR JOR BRN SWZ LSO KHM BTN");
        put(AUTOCRACY, "RUS BLR KAZ UZB TKM TJK KGZ AZE EGY SYR VEN NIC ZWE CMR TGO UGA DZA AGO GNQ GAB COG RWA DJI TCD BDI SSD SDN LBY CAF");
        put(ONE_PARTY, "CHN VNM LAO CUB PRK ERI");
        put(THEOCRACY, "IRN AFG VAT");
        put(JUNTA, "MMR THA MLI BFA NER GIN GNB");
    }

    public static Government of(String tag) { return BY_TAG.getOrDefault(tag, REPUBLIC); }
}
