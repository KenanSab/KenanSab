package warfare.desktop;

import warfare.core.WorldData;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

/** Decorative real-world layers from Natural Earth (public domain): rivers, lakes and major cities. All in map units. */
final class MapExtras {
    record River(int rank, Path2D.Float path, Rectangle2D.Float bounds) {}
    record Lake(int rank, Path2D.Float path, Rectangle2D.Float bounds) {}
    record City(String name, float x, float y, long population, boolean capital, int province) {}

    final List<River> rivers = new ArrayList<>();
    final List<Lake> lakes = new ArrayList<>();
    final List<City> cities = new ArrayList<>();

    static MapExtras load(Path dir, WorldData w) {
        MapExtras e = new MapExtras();
        try { e.readRivers(dir.resolve("rivers.bin.gz")); } catch (Exception ex) { /* optional asset */ }
        try { e.readLakes(dir.resolve("lakes.bin.gz")); } catch (Exception ex) { /* optional asset */ }
        try { e.readCities(dir.resolve("cities.tsv"), w); } catch (Exception ex) { /* optional asset */ }
        return e;
    }

    private static Rectangle2D.Float box(Path2D p) {
        Rectangle2D b = p.getBounds2D();
        return new Rectangle2D.Float((float) b.getX(), (float) b.getY(), (float) b.getWidth(), (float) b.getHeight());
    }

    /** Smooth curve through the midpoints of the polyline, so rivers meander instead of showing corners. */
    private static Path2D.Float smooth(float[] p, int n, boolean closed) {
        Path2D.Float path = new Path2D.Float();
        if (closed) {
            float mx = (p[2 * (n - 1)] + p[0]) / 2, my = (p[2 * (n - 1) + 1] + p[1]) / 2;
            path.moveTo(mx, my);
            for (int i = 0; i < n; i++) {
                int j = (i + 1) % n;
                path.quadTo(p[2 * i], p[2 * i + 1], (p[2 * i] + p[2 * j]) / 2, (p[2 * i + 1] + p[2 * j + 1]) / 2);
            }
            path.closePath();
        } else {
            path.moveTo(p[0], p[1]);
            for (int i = 1; i < n - 1; i++) path.quadTo(p[2 * i], p[2 * i + 1], (p[2 * i] + p[2 * i + 2]) / 2, (p[2 * i + 1] + p[2 * i + 3]) / 2);
            path.lineTo(p[2 * (n - 1)], p[2 * (n - 1) + 1]);
        }
        return path;
    }

    private void readRivers(Path f) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(f))))) {
            int n = in.readInt();
            for (int i = 0; i < n; i++) {
                int rank = in.readByte(), c = in.readShort();
                float[] p = new float[c * 2];
                for (int k = 0; k < p.length; k++) p[k] = in.readFloat();
                if (c < 2) continue;
                Path2D.Float path = smooth(p, c, false);
                rivers.add(new River(rank, path, box(path)));
            }
        }
    }

    private void readLakes(Path f) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(f))))) {
            int n = in.readInt();
            for (int i = 0; i < n; i++) {
                int rank = in.readByte(), c = in.readShort();
                float[] p = new float[c * 2];
                for (int k = 0; k < p.length; k++) p[k] = in.readFloat();
                if (c < 3) continue;
                Path2D.Float path = smooth(p, c, true);
                lakes.add(new Lake(rank, path, box(path)));
            }
        }
    }

    private void readCities(Path f, WorldData w) throws IOException {
        List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
        for (int i = 1; i < lines.size(); i++) {
            String[] t = lines.get(i).split("\t");
            if (t.length < 5) continue;
            float x = Float.parseFloat(t[1]), y = Float.parseFloat(t[2]);
            int prov = landProvinceAt(w, (int) x, (int) y);
            if (prov < 0) continue;
            cities.add(new City(t[0], x, y, Long.parseLong(t[3]), t[4].equals("1"), prov));
        }
    }

    /** Province under the point; a coastal city that falls into the sea is snapped to the nearest land within a few units. */
    private static int landProvinceAt(WorldData w, int x, int y) {
        for (int r = 0; r <= 4; r++)
            for (int dy = -r; dy <= r; dy++)
                for (int dx = -r; dx <= r; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dy)) != r) continue;
                    int px = x + dx, py = y + dy;
                    if (px < 0 || py < 0 || px >= w.width || py >= w.height) continue;
                    int id = w.idMap[py * w.width + px];
                    if (id >= 0 && !w.provinces.get(id).sea) return id;
                }
        return -1;
    }
}
