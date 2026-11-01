package warfare.desktop;

import java.awt.Color;

final class NationColors {
    private NationColors() {}

    static Color of(String tag) {
        int h = Math.abs((tag + "x").hashCode());
        float hue = (h % 360) / 360f;
        float sat = 0.42f + ((h / 360) % 30) / 100f;
        return Color.getHSBColor(hue, sat, 0.86f);
    }

    static int rgb(Color c) { return c.getRGB() & 0xFFFFFF; }

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
