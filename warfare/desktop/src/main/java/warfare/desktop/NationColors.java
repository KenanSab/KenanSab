package warfare.desktop;

import warfare.core.WorldData;

import java.awt.Color;
import java.util.*;

/**
 * Political colours. Each nation takes a colour from a hand-picked palette so that no two neighbours look alike
 * (greedy graph colouring, biggest nations first), with a small per-tag variation so that countries sharing a palette
 * entry across the world still differ.
 */
final class NationColors {
    private NationColors() {}

    private static final int[] PALETTE = {
            0xC4573B,   // brick
            0x3F83C4,   // blue
            0x56A05A,   // green
            0xD9A83C,   // ochre
            0x8E5CAD,   // purple
            0x2FA7A0,   // teal
            0xDB7B3A,   // orange
            0x8A9A3E,   // olive
            0xBA5584,   // magenta
            0x5C74C0,   // indigo
            0xB7B03C,   // lime
            0x8A6446,   // brown
            0x3E97BC,   // cyan
            0xCF7878,   // salmon
            0x7E9DB0,   // slate
            0xA374C8,   // lavender
    };

    private static final Map<String, Color> byTag = new HashMap<>();
    private static WorldData initFor;

    static synchronized void init(WorldData w) {
        if (initFor == w) return;
        initFor = w;
        byTag.clear();
        int n = w.nations.size();
        List<Set<Integer>> adj = new ArrayList<>();
        for (int i = 0; i < n; i++) adj.add(new HashSet<>());
        for (WorldData.ProvinceInfo p : w.provinces) {
            if (p.sea || p.nation < 0) continue;
            for (int q : p.neighbours) {
                WorldData.ProvinceInfo o = w.provinces.get(q);
                if (o.sea || o.nation < 0 || o.nation == p.nation) continue;
                adj.get(p.nation).add(o.nation); adj.get(o.nation).add(p.nation);
            }
        }
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> adj.get(b).size() - adj.get(a).size());
        int[] pick = new int[n];
        Arrays.fill(pick, -1);
        int[] usage = new int[PALETTE.length];
        for (int i : order) {
            boolean[] taken = new boolean[PALETTE.length];
            for (int nb : adj.get(i)) if (pick[nb] >= 0) taken[pick[nb]] = true;
            // neighbours-of-neighbours matter a little: prefer colours that are rare nearby
            int best = -1; double bestScore = 1e18;
            for (int c = 0; c < PALETTE.length; c++) {
                if (taken[c]) continue;
                double score = usage[c];
                for (int nb : adj.get(i)) for (int nb2 : adj.get(nb)) if (pick[nb2] == c) score += 3;
                score += ((Math.abs((w.nations.get(i).tag + c).hashCode())) % 100) / 100.0;   // deterministic tie-break
                if (score < bestScore) { bestScore = score; best = c; }
            }
            if (best < 0) best = Math.abs(w.nations.get(i).tag.hashCode()) % PALETTE.length;
            pick[i] = best; usage[best]++;
        }
        for (int i = 0; i < n; i++) {
            String tag = w.nations.get(i).tag;
            int h = Math.abs((tag + "x").hashCode());
            Color base = new Color(PALETTE[pick[i]]);
            float[] hsb = Color.RGBtoHSB(base.getRed(), base.getGreen(), base.getBlue(), null);
            float hue = hsb[0] + ((h % 21) - 10) / 360f;
            float sat = Math.max(0.3f, Math.min(0.9f, hsb[1] + (((h / 21) % 17) - 8) / 100f));
            float val = Math.max(0.5f, Math.min(0.95f, hsb[2] + (((h / 400) % 15) - 7) / 100f));
            byTag.put(tag, Color.getHSBColor(hue, sat, val));
        }
    }

    static Color of(String tag) {
        Color c = byTag.get(tag);
        if (c != null) return c;
        int h = Math.abs((tag + "x").hashCode());
        return Color.getHSBColor((h % 360) / 360f, 0.5f, 0.8f);
    }

    static int rgb(Color c) { return c.getRGB() & 0xFFFFFF; }

    /** The tone used for fills: a little deeper than the palette so the cream labels and white borders read well. */
    static int fill(Color c) {
        float[] hsb = Color.RGBtoHSB(c.getRed(), c.getGreen(), c.getBlue(), null);
        return Color.HSBtoRGB(hsb[0], Math.min(1f, hsb[1] * 1.05f), hsb[2] * 0.86f) & 0xFFFFFF;
    }

    static int mix(int a, int b, double t) {
        t = Math.max(0, Math.min(1, t));
        int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return (r << 16) | (g << 8) | bl;
    }

    static int scale(int c, double f) {
        int r = (int) Math.min(255, ((c >> 16) & 255) * f), g = (int) Math.min(255, ((c >> 8) & 255) * f), b = (int) Math.min(255, (c & 255) * f);
        return (r << 16) | (g << 8) | b;
    }
}
