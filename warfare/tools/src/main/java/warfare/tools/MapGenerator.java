package warfare.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Builds the Warfare world map from Natural Earth country borders (public domain).
 *
 * Output (equirectangular raster, 0.15 degrees per pixel):
 *   provinces.png  - every pixel encodes (provinceId + 1); 0 = off map
 *   provinces.tsv  - id, type, nation, name, cx, cy, pixels, terrain, neighbours
 *   nations.tsv    - tag, name, continent, population, gdp, capital province, bloc
 *
 * Usage: MapGenerator <countries.geojson> <outputDir> [targetLandProvinces] [seaZones]
 */
public final class MapGenerator {
    static final int W = 2400;
    static final double DEG = 360.0 / W;
    static final double LAT_TOP = 84.0, LAT_BOT = -58.0;
    static final int H = (int) Math.round((LAT_TOP - LAT_BOT) / DEG);

    static double lonOf(double x) { return -180 + x * DEG; }
    static double latOf(double y) { return LAT_TOP - y * DEG; }
    static double pxX(double lon) { return (lon + 180) / DEG; }
    static double pxY(double lat) { return (LAT_TOP - lat) / DEG; }

    record Nation(String tag, String name, String continent, long pop, double gdp,
                  double labelLon, double labelLat, List<Path2D.Double> shapes, int index) {}

    public static void main(String[] args) throws Exception {
        Path geo = Path.of(args.length > 0 ? args[0] : "countries.geojson");
        Path out = Path.of(args.length > 1 ? args[1] : "assets/map");
        int targetLand = args.length > 2 ? Integer.parseInt(args[2]) : 3300;
        int seaZones = args.length > 3 ? Integer.parseInt(args[3]) : 260;
        Files.createDirectories(out);

        List<Nation> nations = readNations(geo);
        System.out.println("nations (by sovereignty): " + nations.size());

        // ---- rasterise countries ----
        int[] country = new int[W * H]; // nation index + 1, 0 = ocean
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        List<Nation> byArea = new ArrayList<>(nations);
        byArea.sort(Comparator.comparingDouble((Nation n) -> -n.shapes.stream().mapToDouble(s -> s.getBounds2D().getWidth() * s.getBounds2D().getHeight()).sum()));
        for (Nation n : byArea) {
            g.setColor(new Color(n.index + 1));
            for (Path2D.Double s : n.shapes) g.fill(s);
        }
        g.dispose();
        for (int i = 0; i < W * H; i++) country[i] = img.getRGB(i % W, i / W) & 0xFFFFFF;
        // tiny states vanish at this resolution: force at least one pixel at their label point
        for (Nation n : nations) {
            boolean any = false;
            for (int c : country) if (c == n.index + 1) { any = true; break; }
            if (any) continue;
            int x = (int) pxX(n.labelLon), y = (int) pxY(n.labelLat);
            if (x >= 0 && x < W && y >= 0 && y < H) country[y * W + x] = n.index + 1;
        }

        // ---- land provinces ----
        double[] cosLat = new double[H];
        for (int y = 0; y < H; y++) cosLat[y] = Math.cos(Math.toRadians(latOf(y + 0.5)));
        double[] weighted = new double[nations.size()];
        List<List<Integer>> pixelsOf = new ArrayList<>();
        for (int i = 0; i < nations.size(); i++) pixelsOf.add(new ArrayList<>());
        for (int i = 0; i < W * H; i++) {
            int c = country[i];
            if (c > 0) { pixelsOf.get(c - 1).add(i); weighted[c - 1] += cosLat[i / W]; }
        }
        double lo = 1, hi = 100000;
        for (int it = 0; it < 40; it++) {
            double mid = (lo + hi) / 2;
            int total = 0;
            for (double w : weighted) if (w > 0) total += Math.max(1, (int) Math.round(w / mid));
            if (total > targetLand) lo = mid; else hi = mid;
        }
        double areaPerProv = hi;

        int[] prov = new int[W * H];
        Arrays.fill(prov, -1);
        int nextId = 0;
        Random rng = new Random(1962);
        for (Nation n : nations) {
            List<Integer> px = pixelsOf.get(n.index);
            if (px.isEmpty()) continue;
            int k = Math.max(1, (int) Math.round(weighted[n.index] / areaPerProv));
            k = Math.min(k, px.size());
            int[] labels = kmeans(px, k, cosLat, rng, false);
            for (int j = 0; j < px.size(); j++) prov[px.get(j)] = nextId + labels[j];
            nextId += k;
        }
        System.out.println("raw land cells: " + nextId);

        // ---- sea zones ----
        List<Integer> sea = new ArrayList<>();
        for (int i = 0; i < W * H; i++) if (country[i] == 0) sea.add(i);
        int[] seaLabels = kmeans(sea, seaZones, cosLat, rng, true);
        for (int j = 0; j < sea.size(); j++) prov[sea.get(j)] = nextId + seaLabels[j];
        int seaBase = nextId;
        nextId += seaZones;

        // ---- connected components, merge fragments ----
        Result r = splitAndMerge(prov, country, nextId, seaBase);
        System.out.println("final provinces: land=" + r.landCount + " sea=" + r.seaCount);

        write(out, nations, country, r, cosLat);
    }

    // ------------------------------------------------------------------ geo input
    static List<Nation> readNations(Path geo) throws Exception {
        JsonObject root = JsonParser.parseString(Files.readString(geo, StandardCharsets.UTF_8)).getAsJsonObject();
        Map<String, Nation> map = new LinkedHashMap<>();
        Map<String, Double> bestPop = new HashMap<>();
        Map<String, List<Path2D.Double>> shapes = new LinkedHashMap<>();
        Map<String, String[]> meta = new LinkedHashMap<>();
        Map<String, String> tagOf = new HashMap<>();
        for (JsonElement fe : root.getAsJsonArray("features")) {
            JsonObject f = fe.getAsJsonObject();
            JsonObject p = f.getAsJsonObject("properties");
            String continent = str(p, "CONTINENT");
            if (continent.equals("Antarctica") || continent.equals("Seven seas (open ocean)")) continue;
            String sov = str(p, "SOV_A3");
            String adm = str(p, "ADM0_A3");
            if (sov.isEmpty()) sov = adm;
            String sovName = str(p, "SOVEREIGNT");
            long pop = p.has("POP_EST") && !p.get("POP_EST").isJsonNull() ? p.get("POP_EST").getAsLong() : 0;
            double gdp = p.has("GDP_MD") && !p.get("GDP_MD").isJsonNull() ? p.get("GDP_MD").getAsDouble() : 0;
            double lx = p.has("LABEL_X") && !p.get("LABEL_X").isJsonNull() ? p.get("LABEL_X").getAsDouble() : 0;
            double ly = p.has("LABEL_Y") && !p.get("LABEL_Y").isJsonNull() ? p.get("LABEL_Y").getAsDouble() : 0;
            // the feature carrying the sovereign's own ADM0 code is its heartland: use it for label/capital
            boolean heart = str(p, "ADMIN").equals(sovName);
            String[] m = meta.get(sov);
            if (m == null) {
                meta.put(sov, new String[]{sovName, continent, "0", "0", String.valueOf(lx), String.valueOf(ly)});
                m = meta.get(sov);
            }
            m[2] = String.valueOf(Long.parseLong(m[2]) + pop);
            m[3] = String.valueOf(Double.parseDouble(m[3]) + gdp);
            if (heart) { m[1] = continent; m[4] = String.valueOf(lx); m[5] = String.valueOf(ly); tagOf.put(sov, adm); }
            JsonObject geom = f.getAsJsonObject("geometry");
            if (geom == null || geom.get("type").isJsonNull()) continue;
            List<Path2D.Double> list = shapes.computeIfAbsent(sov, k -> new ArrayList<>());
            String type = geom.get("type").getAsString();
            JsonArray coords = geom.getAsJsonArray("coordinates");
            if (type.equals("Polygon")) list.add(polygon(coords));
            else if (type.equals("MultiPolygon")) for (JsonElement poly : coords) list.add(polygon(poly.getAsJsonArray()));
        }
        List<Nation> res = new ArrayList<>();
        for (var e : meta.entrySet()) {
            String[] m = e.getValue();
            String tag = tagOf.getOrDefault(e.getKey(), e.getKey());
            if (tag.equals("-99")) tag = "X" + res.size();
            res.add(new Nation(tag, m[0], m[1], Long.parseLong(m[2]), Double.parseDouble(m[3]),
                    Double.parseDouble(m[4]), Double.parseDouble(m[5]), shapes.getOrDefault(e.getKey(), List.of()), res.size()));
        }
        return res;
    }

    static String str(JsonObject p, String k) {
        return p.has(k) && !p.get(k).isJsonNull() ? p.get(k).getAsString() : "";
    }

    static Path2D.Double polygon(JsonArray rings) {
        Path2D.Double path = new Path2D.Double(Path2D.WIND_EVEN_ODD);
        for (JsonElement ringEl : rings) {
            boolean first = true;
            for (JsonElement ptEl : ringEl.getAsJsonArray()) {
                JsonArray pt = ptEl.getAsJsonArray();
                double x = pxX(pt.get(0).getAsDouble()), y = pxY(pt.get(1).getAsDouble());
                if (first) { path.moveTo(x, y); first = false; } else path.lineTo(x, y);
            }
            path.closePath();
        }
        return path;
    }

    // ------------------------------------------------------------------ clustering
    /** Farthest-point seeding followed by a few Lloyd iterations in an isotropic (cos-lat scaled) plane. */
    static int[] kmeans(List<Integer> px, int k, double[] cosLat, Random rng, boolean global) {
        int n = px.size();
        double sumLat = 0;
        for (int p : px) sumLat += cosLat[p / W];
        double cosC = global ? 1.0 : sumLat / n;
        double[] xs = new double[n], ys = new double[n];
        for (int i = 0; i < n; i++) {
            int p = px.get(i);
            int y = p / W, x = p % W;
            xs[i] = x * cosC;
            ys[i] = y;
        }
        int[] label = new int[n];
        if (k <= 1) return label;
        int step = Math.max(1, n / 60000);
        int m = (n + step - 1) / step;
        double[] sx = new double[m], sy = new double[m];
        for (int j = 0; j < m; j++) { int i = step == 1 ? j : rng.nextInt(n); sx[j] = xs[i]; sy[j] = ys[i]; }
        double[] cx = new double[k], cy = new double[k];
        double[] dmin = new double[m];
        Arrays.fill(dmin, Double.MAX_VALUE);
        int first = rng.nextInt(m);
        cx[0] = sx[first]; cy[0] = sy[first];
        for (int c = 1; c < k; c++) {
            int best = 0; double bd = -1;
            for (int j = 0; j < m; j++) {
                double dx = sx[j] - cx[c - 1], dy = sy[j] - cy[c - 1];
                double d = dx * dx + dy * dy;
                if (d < dmin[j]) dmin[j] = d;
                if (dmin[j] > bd) { bd = dmin[j]; best = j; }
            }
            cx[c] = sx[best]; cy[c] = sy[best];
        }
        double[] ax = new double[k], ay = new double[k];
        int[] cnt = new int[k];
        for (int iter = 0; iter < 5; iter++) {
            Arrays.fill(ax, 0); Arrays.fill(ay, 0); Arrays.fill(cnt, 0);
            for (int j = 0; j < m; j++) {
                int b = nearest(sx[j], sy[j], cx, cy);
                ax[b] += sx[j]; ay[b] += sy[j]; cnt[b]++;
            }
            for (int c = 0; c < k; c++) if (cnt[c] > 0) { cx[c] = ax[c] / cnt[c]; cy[c] = ay[c] / cnt[c]; }
        }
        for (int i = 0; i < n; i++) label[i] = nearest(xs[i], ys[i], cx, cy);
        return label;
    }

    static int nearest(double x, double y, double[] cx, double[] cy) {
        int b = 0; double bd = Double.MAX_VALUE;
        for (int c = 0; c < cx.length; c++) {
            double dx = x - cx[c], dy = y - cy[c];
            double d = dx * dx + dy * dy;
            if (d < bd) { bd = d; b = c; }
        }
        return b;
    }

    // ------------------------------------------------------------------ fragments
    record Result(int[] id, int landCount, int seaCount, int[] nation, boolean[] isSea) {}

    static Result splitAndMerge(int[] prov, int[] country, int cells, int seaBase) {
        int[] comp = new int[W * H];
        Arrays.fill(comp, -1);
        List<Integer> compSize = new ArrayList<>();
        List<Integer> compCell = new ArrayList<>();
        int[] queue = new int[W * H];
        for (int s = 0; s < W * H; s++) {
            if (comp[s] != -1) continue;
            int id = compSize.size();
            int head = 0, tail = 0;
            queue[tail++] = s; comp[s] = id;
            while (head < tail) {
                int p = queue[head++];
                int x = p % W, y = p / W;
                if (x > 0) tail = push(prov, comp, queue, tail, p - 1, p, id);
                if (x < W - 1) tail = push(prov, comp, queue, tail, p + 1, p, id);
                if (y > 0) tail = push(prov, comp, queue, tail, p - W, p, id);
                if (y < H - 1) tail = push(prov, comp, queue, tail, p + W, p, id);
            }
            compSize.add(tail);
            compCell.add(prov[s]);
        }
        int nc = compSize.size();
        boolean[] compSea = new boolean[nc];
        int[] compNation = new int[nc];
        for (int s = 0; s < W * H; s++) {
            int c = comp[s];
            compSea[c] = country[s] == 0;
            compNation[c] = country[s];
        }
        // merge small fragments into the neighbour component sharing the longest border
        int[] parent = new int[nc];
        for (int i = 0; i < nc; i++) parent[i] = i;
        Integer[] order = new Integer[nc];
        for (int i = 0; i < nc; i++) order[i] = i;
        Arrays.sort(order, Comparator.comparingInt(compSize::get));
        Map<Integer, List<Integer>> small = new HashMap<>();
        for (int c = 0; c < nc; c++) if (compSize.get(c) < (compSea[c] ? 500 : 40)) small.put(c, new ArrayList<>());
        for (int s = 0; s < W * H; s++) { List<Integer> l = small.get(comp[s]); if (l != null) l.add(s); }
        for (int c : order) {
            List<Integer> pix = small.get(c);
            if (pix == null) continue;
            Map<Integer, Integer> contact = new HashMap<>();
            Map<Integer, Integer> any = new HashMap<>();
            for (int s : pix) {
                int x = s % W, y = s / W;
                int[] nb = {x > 0 ? s - 1 : -1, x < W - 1 ? s + 1 : -1, y > 0 ? s - W : -1, y < H - 1 ? s + W : -1};
                for (int q : nb) {
                    if (q < 0 || comp[q] == c) continue;
                    int o = find(parent, comp[q]);
                    if (o == find(parent, c)) continue;
                    any.merge(o, 1, Integer::sum);
                    // land merges only into land of the same nation; sea merges into sea
                    if (!compSea[c] && (compSea[o] || compNation[o] != compNation[c])) continue;
                    if (compSea[c] && !compSea[o]) continue;
                    contact.merge(o, 1, Integer::sum);
                }
            }
            if (contact.isEmpty() && compSea[c]) contact = any; // inland water body: fold into neighbouring land
            if (contact.isEmpty()) continue;
            int best = Collections.max(contact.entrySet(), Map.Entry.comparingByValue()).getKey();
            parent[find(parent, c)] = best;
            // keep pixel lists of absorbing small components consistent
            List<Integer> bl = small.get(best);
            if (bl != null) bl.addAll(pix);
        }
        // relabel
        int[] newId = new int[nc];
        Arrays.fill(newId, -1);
        int land = 0, seaN = 0;
        int[] pid = new int[W * H];
        // land first so land ids are contiguous 0..land-1, then sea
        for (int pass = 0; pass < 2; pass++) {
            for (int s = 0; s < W * H; s++) {
                int root = find(parent, comp[s]);
                boolean rootSea = compSea[root];
                if ((pass == 0) == rootSea) continue;
                if (newId[root] == -1) { newId[root] = pass == 0 ? land++ : land + seaN++; }
            }
        }
        for (int s = 0; s < W * H; s++) pid[s] = newId[find(parent, comp[s])];
        int total = land + seaN;
        int[] nationOf = new int[total];
        boolean[] seaOf = new boolean[total];
        for (int s = 0; s < W * H; s++) {
            int root = find(parent, comp[s]);
            nationOf[pid[s]] = compSea[root] ? 0 : compNation[root];
            seaOf[pid[s]] = compSea[root];
        }
        return new Result(pid, land, seaN, nationOf, seaOf);
    }

    static int push(int[] prov, int[] comp, int[] queue, int tail, int q, int p, int id) {
        if (comp[q] == -1 && prov[q] == prov[p]) { comp[q] = id; queue[tail++] = q; }
        return tail;
    }

    static int find(int[] parent, int x) {
        while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x]; }
        return x;
    }

    // ------------------------------------------------------------------ output
    static void write(Path out, List<Nation> nations, int[] country, Result r, double[] cosLat) throws Exception {
        int total = r.landCount + r.seaCount;
        long[] cnt = new long[total];
        double[] sx = new double[total], sy = new double[total];
        List<Set<Integer>> nb = new ArrayList<>();
        for (int i = 0; i < total; i++) nb.add(new TreeSet<>());
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int p = y * W + x, id = r.id[p];
                img.setRGB(x, y, id + 1);
                cnt[id]++; sx[id] += x; sy[id] += y;
                if (x < W - 1 && r.id[p + 1] != id) { nb.get(id).add(r.id[p + 1]); nb.get(r.id[p + 1]).add(id); }
                if (y < H - 1 && r.id[p + W] != id) { nb.get(id).add(r.id[p + W]); nb.get(r.id[p + W]).add(id); }
            }
        }
        ImageIO.write(img, "png", out.resolve("provinces.png").toFile());

        // names: "<Nation> <n>" ordered by position; sea zones numbered
        Map<Integer, Integer> counter = new HashMap<>();
        // capital = province containing the nation's label point, else nearest
        int[] capital = new int[nations.size()];
        Arrays.fill(capital, -1);
        for (Nation n : nations) {
            int lx = (int) Math.round(pxX(n.labelLon)), ly = (int) Math.round(pxY(n.labelLat));
            double bd = Double.MAX_VALUE;
            for (int id = 0; id < r.landCount; id++) {
                if (r.nation[id] != n.index + 1) continue;
                double dx = sx[id] / cnt[id] - lx, dy = sy[id] / cnt[id] - ly;
                double d = dx * dx + dy * dy;
                if (d < bd) { bd = d; capital[n.index] = id; }
            }
        }
        Random terrainRng = new Random(77);
        try (PrintWriter pw = new PrintWriter(out.resolve("provinces.tsv").toFile(), StandardCharsets.UTF_8)) {
            pw.println("id\ttype\tnation\tname\tcx\tcy\tpixels\tterrain\tneighbours");
            for (int id = 0; id < total; id++) {
                boolean sea = r.isSea[id];
                String tag = sea ? "" : nations.get(r.nation[id] - 1).tag();
                String name;
                if (sea) name = "Sea Zone " + (id - r.landCount + 1);
                else {
                    int c = counter.merge(r.nation[id], 1, Integer::sum);
                    name = nations.get(r.nation[id] - 1).name() + " " + c;
                }
                double cx = sx[id] / cnt[id], cy = sy[id] / cnt[id];
                String terrain = sea ? "SEA" : terrain(lonOf(cx), latOf(cy), nations.get(r.nation[id] - 1).continent());
                StringJoiner sj = new StringJoiner(",");
                for (int o : nb.get(id)) sj.add(String.valueOf(o));
                pw.printf(Locale.ROOT, "%d\t%s\t%s\t%s\t%.1f\t%.1f\t%d\t%s\t%s%n",
                        id, sea ? "SEA" : "LAND", tag, name, cx, cy, cnt[id], terrain, sj);
            }
        }
        Map<String, String> blocs = blocs();
        try (PrintWriter pw = new PrintWriter(out.resolve("nations.tsv").toFile(), StandardCharsets.UTF_8)) {
            pw.println("tag\tname\tcontinent\tpopulation\tgdpMillions\tcapital\tbloc");
            for (Nation n : nations) {
                if (capital[n.index] < 0) continue;
                pw.printf(Locale.ROOT, "%s\t%s\t%s\t%d\t%.0f\t%d\t%s%n", n.tag, n.name, n.continent, n.pop, n.gdp,
                        capital[n.index], blocs.getOrDefault(n.tag, "NONE"));
            }
        }
        int playable = 0;
        for (Nation n : nations) if (capital[n.index] >= 0) playable++;
        System.out.println("playable nations: " + playable);
    }

    static Map<String, String> blocs() {
        Map<String, String> m = new HashMap<>();
        for (String t : "USA CAN GBR FRA DEU ITA ESP PRT NLD BEL LUX DNK NOR ISL POL CZE SVK HUN ROU BGR HRV SVN ALB MNE MKD GRC TUR EST LVA LTU FIN SWE".split(" ")) m.put(t, "NATO");
        for (String t : "RUS BLR ARM KAZ KGZ TJK".split(" ")) m.put(t, "CSTO");
        for (String t : "CHN PRK".split(" ")) m.put(t, "EASTERN");
        for (String t : "AUS NZL JPN KOR".split(" ")) m.put(t, "PACIFIC");
        return m;
    }

    /** Placeholder terrain classifier (no relief data available offline): latitude bands + regional ranges + noise. */
    static String terrain(double lon, double lat, String continent) {
        double a = Math.abs(lat);
        double noise = hash(Math.floor(lon / 2.2), Math.floor(lat / 2.2));
        if (a > 64) return "ARCTIC";
        if (in(lon, lat, 70, 105, 26, 40) || in(lon, lat, -80, -64, -50, 8) && noise > 0.25 || in(lon, lat, -125, -105, 33, 58)
                || in(lon, lat, 5, 15, 44, 48) || in(lon, lat, 38, 50, 40, 44) || in(lon, lat, 34, 42, 5, 14)
                || in(lon, lat, 57, 63, 52, 68) || in(lon, lat, 125, 145, 45, 60) && noise > 0.5) return "MOUNTAIN";
        boolean desertZone = a > 14 && a < 33 && (continent.equals("Africa") || continent.equals("Asia") || continent.equals("Oceania")
                || (continent.equals("North America") && lon < -100) || (continent.equals("South America") && lat < -18 && lon > -75 && lon < -62));
        if (desertZone && noise > 0.15 && !(lon > 95 && lat > 20 && lat < 30)) return "DESERT";
        if (a < 12 && !(continent.equals("Africa") && lon > 35)) return "JUNGLE";
        if (lat > 46 || (lat > 38 && noise > 0.55)) return noise > 0.1 ? "FOREST" : "PLAINS";
        if (noise > 0.8) return "MOUNTAIN";
        return noise > 0.55 ? "FOREST" : "PLAINS";
    }

    static boolean in(double lon, double lat, double l0, double l1, double a0, double a1) {
        return lon >= l0 && lon <= l1 && lat >= a0 && lat <= a1;
    }

    static double hash(double x, double y) {
        double s = Math.sin(x * 127.1 + y * 311.7) * 43758.5453;
        return s - Math.floor(s);
    }
}
