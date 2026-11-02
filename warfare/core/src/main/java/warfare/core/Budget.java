package warfare.core;

import java.io.Serializable;

/**
 * A nation's economic policy: the tax rate, how the revenue is split between six programmes, and four standing
 * policies. Shares are fractions of daily revenue; whatever is not allocated stays in the treasury.
 */
public final class Budget implements Serializable {
    public static final double TAX_MIN = 0.10, TAX_MAX = 0.70, TAX_DEFAULT = 0.30;
    public static final String[] MOBILIZATION = {"Peacetime economy", "Partial mobilization", "General mobilization", "Total war economy"};
    public static final double[] MOB_MANPOWER = {1.0, 1.25, 1.5, 2.0};
    public static final double[] MOB_GDP = {1.0, 0.97, 0.92, 0.85};
    public static final double[] MOB_STABILITY = {0, 1, 4, 10};

    public double tax = TAX_DEFAULT;
    public double dev = 0.10, infra = 0.05, edu = 0.05, welfare = 0.08, security = 0.05, military = 0.05;
    public int mobilization;            // 0..3
    public double industryFocus;        // -1 civilian ... +1 military
    public boolean fuelRationing, printMoney;

    public double allocated() { return dev + infra + edu + welfare + security + military; }

    public void clamp() {
        tax = Math.max(TAX_MIN, Math.min(TAX_MAX, tax));
        dev = c(dev, 0.4); infra = c(infra, 0.3); edu = c(edu, 0.3); welfare = c(welfare, 0.3); security = c(security, 0.3); military = c(military, 0.4);
        mobilization = Math.max(0, Math.min(3, mobilization));
        industryFocus = Math.max(-1, Math.min(1, industryFocus));
    }

    private static double c(double v, double max) { return Math.max(0, Math.min(max, v)); }

    public static Budget peacetime() { return new Budget(); }

    /** Stance an AI nation adopts: heavier taxes and military when fighting, development when at peace. */
    public static Budget forAi(boolean atWar, boolean tired) {
        Budget b = new Budget();
        if (atWar) {
            b.tax = 0.40; b.dev = 0.04; b.infra = 0.03; b.edu = 0.03; b.welfare = 0.08; b.security = 0.08; b.military = 0.15;
            b.mobilization = tired ? 0 : 1; b.industryFocus = 0.4;
        } else {
            b.dev = 0.14; b.infra = 0.06; b.edu = 0.06;
        }
        return b;
    }

    /** Snapshot of the daily accounts (also used for live previews while a slider is dragged). */
    public record Ledger(double gdp, double revenue, double upkeep, double dev, double infra, double edu, double welfare, double security,
                         double military, double deals, double net, double taxEfficiency, double growth, double stabilityTarget, double inflationDelta) implements Serializable {
        public double spending() { return dev + infra + edu + welfare + security + military; }
    }
}
