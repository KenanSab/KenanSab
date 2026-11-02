package warfare.tools;

import com.google.gson.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;

/**
 * Builds the decorative map layers from Natural Earth (public domain):
 * <pre>
 *   rivers.bin.gz   int n; n x { byte rank(0 major..3 minor), short pts, pts x (float x, float y) }   map units
 *   lakes.bin.gz    int n; n x { byte scalerank(0 big..9 small), short pts, pts x (float x, float y) }  outer rings only
 *   cities.tsv      name, x, y, population, capital(0/1), iso2   (top cities and all national capitals)
 * </pre>
 * Map units are 0.15 degrees, identical to {@link MapGenerator}.
 * Usage: ExtraLayers rivers.geojson lakes.geojson places.geojson outDir
 */
public final class ExtraLayers {
    static final double UNIT = 0.15, LAT_TOP = 84.0, LAT_BOT = -58.0;

    static double mx(double lon) { return (lon + 180) / UNIT; }
    static double my(double lat) { return (LAT_TOP - lat) / UNIT; }

    public static void main(String[] a) throws Exception {
        Path out = Path.of(a[3]);
        Files.createDirectories(out);
        rivers(Path.of(a[0]), out.resolve("rivers.bin.gz"));
        lakes(Path.of(a[1]), out.resolve("lakes.bin.gz"));
        cities(Path.of(a[2]), out.resolve("cities.tsv"));
    }

    private static JsonObject read(Path p) throws IOException {
        try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) { return JsonParser.parseReader(r).getAsJsonObject(); }
    }

    private static List<double[]> line(JsonArray coords) {
        List<double[]> pts = new ArrayList<>();
        for (JsonElement e : coords) {
            JsonArray c = e.getAsJsonArray();
            pts.add(new double[]{mx(c.get(0).getAsDouble()), my(c.get(1).getAsDouble())});
        }
        return pts;
    }

    /** Douglas-Peucker simplification. */
    static List<double[]> simplify(List<double[]> pts, double eps) {
        if (pts.size() < 3) return pts;
        boolean[] keep = new boolean[pts.size()];
        keep[0] = keep[pts.size() - 1] = true;
        Deque<int[]> st = new ArrayDeque<>();
        st.push(new int[]{0, pts.size() - 1});
        while (!st.isEmpty()) {
            int[] s = st.pop();
            double best = 0; int bi = -1;
            double[] p0 = pts.get(s[0]), p1 = pts.get(s[1]);
            for (int i = s[0] + 1; i < s[1]; i++) {
                double d = dist(pts.get(i), p0, p1);
                if (d > best) { best = d; bi = i; }
            }
            if (bi >= 0 && best > eps) { keep[bi] = true; st.push(new int[]{s[0], bi}); st.push(new int[]{bi, s[1]}); }
        }
        List<double[]> r = new ArrayList<>();
        for (int i = 0; i < keep.length; i++) if (keep[i]) r.add(pts.get(i));
        return r;
    }

    static double dist(double[] p, double[] a, double[] b) {
        double dx = b[0] - a[0], dy = b[1] - a[1], l2 = dx * dx + dy * dy;
        if (l2 == 0) return Math.hypot(p[0] - a[0], p[1] - a[1]);
        double t = Math.max(0, Math.min(1, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / l2));
        return Math.hypot(p[0] - (a[0] + t * dx), p[1] - (a[1] + t * dy));
    }

    private static void rivers(Path in, Path out) throws IOException {
        List<Object[]> lines = new ArrayList<>();
        for (JsonElement fe : read(in).getAsJsonArray("features")) {
            JsonObject f = fe.getAsJsonObject(), pr = f.getAsJsonObject("properties");
            if ("Lake Centerline".equals(pr.get("featurecla").getAsString())) continue;
            int sr = (int) Math.round(pr.get("scalerank").getAsDouble());
            int rank = sr <= 2 ? 0 : sr <= 5 ? 1 : sr <= 7 ? 2 : 3;
            JsonObject g = f.getAsJsonObject("geometry");
            List<JsonArray> parts = new ArrayList<>();
            if (g.get("type").getAsString().equals("LineString")) parts.add(g.getAsJsonArray("coordinates"));
            else for (JsonElement e : g.getAsJsonArray("coordinates")) parts.add(e.getAsJsonArray());
            for (JsonArray part : parts) {
                List<double[]> pts = simplify(line(part), 0.05);
                pts.removeIf(p -> p[1] < 0 || p[1] > (LAT_TOP - LAT_BOT) / UNIT);
                if (pts.size() >= 2) lines.add(new Object[]{rank, pts});
            }
        }
        try (DataOutputStream o = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(out))))) {
            o.writeInt(lines.size());
            for (Object[] l : lines) {
                @SuppressWarnings("unchecked") List<double[]> pts = (List<double[]>) l[1];
                o.writeByte((int) l[0]); o.writeShort(pts.size());
                for (double[] p : pts) { o.writeFloat((float) p[0]); o.writeFloat((float) p[1]); }
            }
        }
        System.out.println("rivers: " + lines.size() + " polylines");
    }

    private static void lakes(Path in, Path out) throws IOException {
        List<Object[]> rings = new ArrayList<>();
        for (JsonElement fe : read(in).getAsJsonArray("features")) {
            JsonObject f = fe.getAsJsonObject(), pr = f.getAsJsonObject("properties");
            int sr = pr.get("scalerank").getAsInt();
            JsonObject g = f.getAsJsonObject("geometry");
            List<JsonArray> polys = new ArrayList<>();
            if (g.get("type").getAsString().equals("Polygon")) polys.add(g.getAsJsonArray("coordinates"));
            else for (JsonElement e : g.getAsJsonArray("coordinates")) polys.add(e.getAsJsonArray());
            for (JsonArray poly : polys) {
                List<double[]> pts = simplify(line(poly.get(0).getAsJsonArray()), 0.03);
                if (pts.size() < 4) continue;
                double cy = pts.get(0)[1];
                if (cy < 0 || cy > (LAT_TOP - LAT_BOT) / UNIT) continue;
                rings.add(new Object[]{sr, pts});
            }
        }
        try (DataOutputStream o = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(out))))) {
            o.writeInt(rings.size());
            for (Object[] l : rings) {
                @SuppressWarnings("unchecked") List<double[]> pts = (List<double[]>) l[1];
                o.writeByte((int) l[0]); o.writeShort(pts.size());
                for (double[] p : pts) { o.writeFloat((float) p[0]); o.writeFloat((float) p[1]); }
            }
        }
        System.out.println("lakes: " + rings.size() + " rings");
    }

    private static void cities(Path in, Path out) throws IOException {
        List<String[]> rows = new ArrayList<>();
        for (JsonElement fe : read(in).getAsJsonArray("features")) {
            JsonObject pr = fe.getAsJsonObject().getAsJsonObject("properties");
            long pop = pr.has("pop_max") && !pr.get("pop_max").isJsonNull() ? pr.get("pop_max").getAsLong() : 0;
            boolean cap = pr.has("adm0cap") && !pr.get("adm0cap").isJsonNull() && pr.get("adm0cap").getAsInt() == 1;
            if (pop < 400_000 && !cap) continue;
            double lat = pr.get("latitude").getAsDouble(), lon = pr.get("longitude").getAsDouble();
            if (lat < LAT_BOT || lat > LAT_TOP) continue;
            String name = pr.get("name").getAsString().replace('\t', ' ');
            String iso = pr.has("iso_a2") && !pr.get("iso_a2").isJsonNull() ? pr.get("iso_a2").getAsString() : "";
            rows.add(new String[]{name, String.format(Locale.ROOT, "%.2f", mx(lon)), String.format(Locale.ROOT, "%.2f", my(lat)), String.valueOf(pop), cap ? "1" : "0", iso});
        }
        rows.sort((p, q) -> Long.compare(Long.parseLong(q[3]), Long.parseLong(p[3])));
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out, StandardCharsets.UTF_8))) {
            w.println("name\tx\ty\tpopulation\tcapital\tiso2");
            for (String[] r : rows) w.println(String.join("\t", r));
        }
        System.out.println("cities: " + rows.size());
    }
}
