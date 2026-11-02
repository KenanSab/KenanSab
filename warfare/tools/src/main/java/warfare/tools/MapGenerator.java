package warfare.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.GZIPOutputStream;

/**
 * Builds the Warfare world map from Natural Earth (public domain) at 10m detail.
 *
 * Provinces are the real administrative regions (states, oblasts, departments, ...). Regions that are far bigger than
 * average are split into several provinces, tiny ones are merged into a neighbour. Sea zones are a coarse Voronoi
 * partition of the oceans. Everything is rasterised at 0.06 degrees/pixel, then every border is traced into a smooth
 * vector arc, so the client can draw antialiased shapes at any zoom.
 *
 * Output (map units = 0.15 degrees, matching the 2400x947 grid used by the game):
 *   provinces.png   2400x947 id map (id+1) used for mouse picking
 *   provinces.tsv   id, type, nation, name, cx, cy, pixels, terrain, neighbours
 *   nations.tsv     tag, name, continent, population, gdp, capital, bloc
 *   arcs.bin.gz     smoothed border arcs: p1, p2, points (float x,y in map units)
 *
 * Usage: MapGenerator admin0.geojson admin1.geojson outDir [splitArea=5200] [minLandPx=60] [seaZones=260]
 */
public final class MapGenerator {
    static final int HW = 6000;
    static final double HDEG = 360.0 / HW;                 // 0.06 deg per hi-res pixel
    static final double LAT_TOP = 84.0, LAT_BOT = -58.0;
    static final int HH = (int) Math.round((LAT_TOP - LAT_BOT) / HDEG);
    static final double SCALE = 0.15 / HDEG;               // hi-res pixels per map unit (2.5)
    static final int OUT_W = 2400, OUT_H = (int) Math.round((LAT_TOP - LAT_BOT) / 0.15);

    static double pxX(double lon) { return (lon + 180) / HDEG; }
    static double pxY(double lat) { return (LAT_TOP - lat) / HDEG; }
    static double lonOf(double x) { return -180 + x * HDEG; }
    static double latOf(double y) { return LAT_TOP - y * HDEG; }

    static final class Nation {
        String tag, name, continent, iso2 = "";
        long pop; double gdp, labelLon, labelLat;
        int index;
        List<Path2D.Double> shapes = new ArrayList<>();
        double bboxArea;
    }

    static final class Region {
        String name; int nation; Path2D.Double shape; double bboxArea;
    }

    public static void main(String[] args) throws Exception {
        Path a0 = Path.of(args[0]), a1 = Path.of(args[1]), out = Path.of(args[2]);
        double splitArea = args.length > 3 ? Double.parseDouble(args[3]) : 900;
        double minArea = args.length > 4 ? Double.parseDouble(args[4]) : 420;      // smallest wanted province, in cos-weighted pixels (~44 km2 each)
        int seaZones = args.length > 5 ? Integer.parseInt(args[5]) : 260;
        Path places = args.length > 6 ? Path.of(args[6]) : null;
        Files.createDirectories(out);
        long t0 = System.currentTimeMillis();

        Map<String, Integer> admToNation = new HashMap<>(), nameToNation = new HashMap<>();
        List<Nation> nations = readNations(a0, admToNation, nameToNation);
        List<Region> regions = readRegions(a1, admToNation, nameToNation);
        System.out.println("nations=" + nations.size() + " regions=" + regions.size() + "  (" + (System.currentTimeMillis() - t0) / 1000 + "s)");

        // ---- rasterise nations (byte: nation index+1) ----
        byte[] nat = new byte[HW * HH];
        {
            BufferedImage img = new BufferedImage(HW, HH, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            List<Nation> byArea = new ArrayList<>(nations);
            byArea.sort(Comparator.comparingDouble((Nation n) -> -n.bboxArea));
            for (Nation n : byArea) { g.setColor(new Color(n.index + 1)); for (Path2D.Double s : n.shapes) g.fill(s); }
            g.dispose();
            int[] d = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
            for (int i = 0; i < d.length; i++) nat[i] = (byte) (d[i] & 0xFF);
        }
        // tiny states vanish at this resolution: stamp a 5x5 block at their label point
        int[] natPixels = new int[nations.size() + 1];
        for (byte b : nat) natPixels[b & 0xFF]++;
        for (Nation n : nations) {
            if (natPixels[n.index + 1] >= 25) continue;
            int cx = (int) pxX(n.labelLon), cy = (int) pxY(n.labelLat);
            for (int dy = -2; dy <= 2; dy++) for (int dx = -2; dx <= 2; dx++) {
                int x = cx + dx, y = cy + dy;
                if (x >= 0 && x < HW && y >= 0 && y < HH) nat[y * HW + x] = (byte) (n.index + 1);
            }
        }

        // ---- rasterise regions ----
        int R = regions.size();
        int[] prov = new int[HW * HH];
        Arrays.fill(prov, -1);
        {
            BufferedImage img = new BufferedImage(HW, HH, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < R; i++) order.add(i);
            order.sort(Comparator.comparingDouble(i -> -regions.get(i).bboxArea));
            for (int i : order) { g.setColor(new Color(i + 1)); g.fill(regions.get(i).shape); }
            g.dispose();
            int[] d = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
            for (int i = 0; i < d.length; i++) {
                int r = (d[i] & 0xFFFFFF) - 1;
                if (r >= 0 && nat[i] != 0 && regions.get(r).nation == (nat[i] & 0xFF) - 1) prov[i] = r;
            }
        }
        // nations with no region pixels get one fallback province; the rest flood-fill outwards from their regions
        int[] assigned = new int[nations.size()];
        for (int i = 0; i < prov.length; i++) if (prov[i] >= 0) assigned[(nat[i] & 0xFF) - 1]++;
        for (int i = 0; i < prov.length; i++) {
            int n = (nat[i] & 0xFF) - 1;
            if (n >= 0 && prov[i] < 0 && assigned[n] == 0) prov[i] = R + n;
        }
        {
            int[] queue = new int[prov.length];
            int head = 0, tail = 0;
            for (int i = 0; i < prov.length; i++) if (prov[i] >= 0) queue[tail++] = i;
            while (head < tail) {
                int p = queue[head++];
                int x = p % HW, y = p / HW;
                int[] nb = {x > 0 ? p - 1 : -1, x < HW - 1 ? p + 1 : -1, y > 0 ? p - HW : -1, y < HH - 1 ? p + HW : -1};
                for (int q : nb) if (q >= 0 && prov[q] < 0 && nat[q] == nat[p]) { prov[q] = prov[p]; queue[tail++] = q; }
            }
        }

        // ---- split oversized regions ----
        double[] cosLat = new double[HH];
        for (int y = 0; y < HH; y++) cosLat[y] = Math.cos(Math.toRadians(latOf(y + 0.5)));
        int keys = R + nations.size();
        double[] wsum = new double[keys];
        int[] cnt = new int[keys];
        for (int i = 0; i < prov.length; i++) if (prov[i] >= 0) { wsum[prov[i]] += cosLat[i / HW]; cnt[prov[i]]++; }
        int nextProv = keys;
        List<Integer> origin = new ArrayList<>();
        Random rng = new Random(1962);
        int splits = 0;
        for (int key = 0; key < keys; key++) {
            int parts = (int) Math.max(1, Math.round(wsum[key] / splitArea));
            if (parts <= 1 || cnt[key] == 0) continue;
            int[] px = new int[cnt[key]];
            int c = 0;
            for (int i = 0; i < prov.length; i++) if (prov[i] == key) px[c++] = i;
            int[] lab = kmeansPixels(px, parts, cosLat, rng);
            for (int j = 0; j < px.length; j++) if (lab[j] > 0) prov[px[j]] = nextProv + lab[j] - 1;
            for (int q = 1; q < parts; q++) origin.add(key);
            nextProv += parts - 1;
            splits += parts - 1;
        }
        System.out.println("splits added " + splits + " provinces");

        // ---- sea zones: coarse voronoi, then every ocean pixel takes its coarse cell's label ----
        int seaBase = nextProv;
        {
            int cell = 5, cw = HW / cell, ch = (HH + cell - 1) / cell;
            List<double[]> oceanPts = new ArrayList<>();
            for (int cy = 0; cy < ch; cy++) for (int cx = 0; cx < cw; cx++) {
                int x = cx * cell + cell / 2, y = Math.min(HH - 1, cy * cell + cell / 2);
                if (nat[y * HW + x] == 0) oceanPts.add(new double[]{cx, cy});
            }
            double[][] seeds = farthestSeeds(oceanPts, seaZones, rng);
            for (int i = 0; i < 4; i++) lloyd(seeds, oceanPts, rng);
            int[] cellLabel = new int[cw * ch];
            for (int cy = 0; cy < ch; cy++) for (int cx = 0; cx < cw; cx++) {
                int best = 0; double bd = Double.MAX_VALUE;
                for (int s = 0; s < seeds.length; s++) {
                    double dx = cx - seeds[s][0], dy = cy - seeds[s][1], d = dx * dx + dy * dy;
                    if (d < bd) { bd = d; best = s; }
                }
                cellLabel[cy * cw + cx] = best;
            }
            for (int i = 0; i < prov.length; i++) {
                if (nat[i] != 0) continue;
                int x = i % HW, y = i / HW;
                prov[i] = seaBase + cellLabel[Math.min(ch - 1, y / cell) * cw + Math.min(cw - 1, x / cell)];
            }
            nextProv += seeds.length;
        }

        Result r = splitAndMerge(prov, nat, 12, 3000, cosLat, minArea);
        System.out.println("final provinces: land=" + r.landCount + " sea=" + r.seaCount + "  (" + (System.currentTimeMillis() - t0) / 1000 + "s)");
        String[] names = buildNames(nations, regions, prov, origin, keys, R, r);
        Map<String, double[]> capitals = places == null ? Map.of() : readCapitals(places);
        write(out, nations, nat, r, names, capitals);
        System.out.println("done in " + (System.currentTimeMillis() - t0) / 1000 + "s");
    }

    /** adm0 code -> {lon, lat} of its national capital; city names kept in a side map. */
    static final Map<String, String> capitalNames = new HashMap<>();

    static Map<String, double[]> readCapitals(Path file) throws IOException {
        Map<String, double[]> out = new HashMap<>();
        try (JsonReader rd = features(file)) {
            while (rd.hasNext()) {
                JsonObject f = JsonParser.parseReader(rd).getAsJsonObject();
                JsonObject p = f.getAsJsonObject("properties");
                if (!str(p, "featurecla").equals("Admin-0 capital")) continue;
                String a = str(p, "adm0_a3");
                out.putIfAbsent(a, new double[]{p.get("longitude").getAsDouble(), p.get("latitude").getAsDouble()});
                capitalNames.putIfAbsent(a, str(p, "name"));
            }
        }
        return out;
    }

    /** Province containing the capital (or the nearest province of the same nation), -1 if none. */
    static int capitalProvince(Nation n, Result r, double lon, double lat) {
        int cx = (int) pxX(lon), cy = (int) pxY(lat);
        for (int rad = 0; rad <= 60; rad++) {
            for (int dy = -rad; dy <= rad; dy++) for (int dx = -rad; dx <= rad; dx++) {
                if (Math.max(Math.abs(dx), Math.abs(dy)) != rad) continue;
                int x = cx + dx, y = cy + dy;
                if (x < 0 || y < 0 || x >= HW || y >= HH) continue;
                int pid = r.id[y * HW + x];
                if (!r.isSea[pid] && r.nation[pid] == n.index + 1) return pid;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------ input
    static JsonReader features(Path file) throws IOException {
        JsonReader r = new JsonReader(new BufferedReader(new FileReader(file.toFile(), StandardCharsets.UTF_8), 1 << 20));
        r.beginObject();
        while (r.hasNext()) {
            if (r.nextName().equals("features")) { r.beginArray(); return r; }
            r.skipValue();
        }
        throw new IOException("no features");
    }

    static String str(JsonObject p, String k) { return p.has(k) && !p.get(k).isJsonNull() ? p.get(k).getAsString() : ""; }

    static List<Nation> readNations(Path geo, Map<String, Integer> admToNation, Map<String, Integer> nameToNation) throws IOException {
        Map<String, Nation> map = new LinkedHashMap<>();
        Map<String, String> tagOf = new HashMap<>();
        Map<String, List<String[]>> pending = new HashMap<>();
        try (JsonReader rd = features(geo)) {
            while (rd.hasNext()) {
                JsonObject f = JsonParser.parseReader(rd).getAsJsonObject();
                JsonObject p = f.getAsJsonObject("properties");
                String continent = str(p, "CONTINENT");
                if (continent.equals("Antarctica") || continent.equals("Seven seas (open ocean)")) continue;
                String sov = str(p, "SOV_A3"), adm = str(p, "ADM0_A3"), sovName = str(p, "SOVEREIGNT");
                if (sov.isEmpty()) sov = adm;
                long pop = p.has("POP_EST") && !p.get("POP_EST").isJsonNull() ? p.get("POP_EST").getAsLong() : 0;
                double gdp = p.has("GDP_MD") && !p.get("GDP_MD").isJsonNull() ? p.get("GDP_MD").getAsDouble() : 0;
                double lx = p.has("LABEL_X") && !p.get("LABEL_X").isJsonNull() ? p.get("LABEL_X").getAsDouble() : 0;
                double ly = p.has("LABEL_Y") && !p.get("LABEL_Y").isJsonNull() ? p.get("LABEL_Y").getAsDouble() : 0;
                String iso = str(p, "ISO_A2_EH");
                if (iso.isEmpty() || iso.equals("-99")) iso = str(p, "ISO_A2");
                if (iso.equals("-99")) iso = "";
                Nation n = map.get(sov);
                if (n == null) {
                    n = new Nation(); n.name = sovName; n.continent = continent; n.labelLon = lx; n.labelLat = ly; n.tag = sov;
                    map.put(sov, n);
                }
                n.pop += pop; n.gdp += gdp;
                if (n.iso2.isEmpty()) n.iso2 = iso;
                if (str(p, "ADMIN").equals(sovName)) { if (!iso.isEmpty()) n.iso2 = iso; n.continent = continent; n.labelLon = lx; n.labelLat = ly; tagOf.put(sov, adm); }
                pending.computeIfAbsent(sov, k -> new ArrayList<>()).add(new String[]{adm, str(p, "ADMIN")});
                JsonObject geom = f.getAsJsonObject("geometry");
                if (geom == null || geom.get("type").isJsonNull()) continue;
                JsonArray coords = geom.getAsJsonArray("coordinates");
                if (geom.get("type").getAsString().equals("Polygon")) addShape(n, coords);
                else for (JsonElement poly : coords) addShape(n, poly.getAsJsonArray());
            }
        }
        List<Nation> res = new ArrayList<>();
        for (var e : map.entrySet()) {
            Nation n = e.getValue();
            if (n.pop == 0 && n.gdp == 0) continue;     // uninhabited disputed bits (reefs, no-man's-lands): not countries
            n.index = res.size();
            String t = tagOf.getOrDefault(e.getKey(), e.getKey());
            n.tag = t.equals("-99") ? "X" + n.index : t;
            res.add(n);
            for (String[] a : pending.get(e.getKey())) { admToNation.put(a[0], n.index); nameToNation.put(a[1], n.index); }
        }
        return res;
    }

    static void addShape(Nation n, JsonArray rings) {
        Path2D.Double p = polygon(rings);
        n.shapes.add(p);
        var b = p.getBounds2D();
        n.bboxArea += b.getWidth() * b.getHeight();
    }

    static List<Region> readRegions(Path geo, Map<String, Integer> admToNation, Map<String, Integer> nameToNation) throws IOException {
        List<Region> res = new ArrayList<>();
        try (JsonReader rd = features(geo)) {
            while (rd.hasNext()) {
                JsonObject f = JsonParser.parseReader(rd).getAsJsonObject();
                JsonObject p = f.getAsJsonObject("properties");
                Integer nat = admToNation.get(str(p, "adm0_a3"));
                if (nat == null) nat = nameToNation.get(str(p, "admin"));
                JsonObject geom = f.getAsJsonObject("geometry");
                if (nat == null || geom == null || geom.get("type").isJsonNull()) continue;
                Region r = new Region();
                r.name = str(p, "name"); r.nation = nat;
                Path2D.Double path = new Path2D.Double(Path2D.WIND_EVEN_ODD);
                JsonArray coords = geom.getAsJsonArray("coordinates");
                if (geom.get("type").getAsString().equals("Polygon")) path.append(polygon(coords), false);
                else for (JsonElement poly : coords) path.append(polygon(poly.getAsJsonArray()), false);
                r.shape = path;
                var b = path.getBounds2D();
                r.bboxArea = b.getWidth() * b.getHeight();
                res.add(r);
            }
        }
        return res;
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
    /** Returns labels 0..k-1 for the given pixels (isotropic plane scaled by mean cos(lat)). */
    static int[] kmeansPixels(int[] px, int k, double[] cosLat, Random rng) {
        int n = px.length;
        double sum = 0;
        for (int p : px) sum += cosLat[p / HW];
        double cosC = sum / n;
        int step = Math.max(1, n / 40000);
        List<double[]> pts = new ArrayList<>();
        for (int i = 0; i < n; i += step) { int p = px[rng.nextInt(n)]; pts.add(new double[]{(p % HW) * cosC, p / HW}); }
        double[][] seeds = farthestSeeds(pts, Math.min(k, pts.size()), rng);
        for (int it = 0; it < 5; it++) lloyd(seeds, pts, rng);
        int[] label = new int[n];
        for (int i = 0; i < n; i++) {
            double x = (px[i] % HW) * cosC, y = px[i] / HW;
            int best = 0; double bd = Double.MAX_VALUE;
            for (int s = 0; s < seeds.length; s++) {
                double dx = x - seeds[s][0], dy = y - seeds[s][1], d = dx * dx + dy * dy;
                if (d < bd) { bd = d; best = s; }
            }
            label[i] = best;
        }
        return label;
    }

    static double[][] farthestSeeds(List<double[]> pts, int k, Random rng) {
        k = Math.min(k, pts.size());
        double[][] seeds = new double[k][];
        double[] dmin = new double[pts.size()];
        Arrays.fill(dmin, Double.MAX_VALUE);
        double[] first = pts.get(rng.nextInt(pts.size()));
        seeds[0] = first.clone();
        for (int c = 1; c < k; c++) {
            int best = 0; double bd = -1;
            for (int j = 0; j < pts.size(); j++) {
                double[] q = pts.get(j);
                double dx = q[0] - seeds[c - 1][0], dy = q[1] - seeds[c - 1][1], d = dx * dx + dy * dy;
                if (d < dmin[j]) dmin[j] = d;
                if (dmin[j] > bd) { bd = dmin[j]; best = j; }
            }
            seeds[c] = pts.get(best).clone();
        }
        return seeds;
    }

    static void lloyd(double[][] seeds, List<double[]> pts, Random rng) {
        double[][] acc = new double[seeds.length][3];
        for (double[] q : pts) {
            int best = 0; double bd = Double.MAX_VALUE;
            for (int s = 0; s < seeds.length; s++) {
                double dx = q[0] - seeds[s][0], dy = q[1] - seeds[s][1], d = dx * dx + dy * dy;
                if (d < bd) { bd = d; best = s; }
            }
            acc[best][0] += q[0]; acc[best][1] += q[1]; acc[best][2]++;
        }
        for (int s = 0; s < seeds.length; s++) if (acc[s][2] > 0) { seeds[s][0] = acc[s][0] / acc[s][2]; seeds[s][1] = acc[s][1] / acc[s][2]; }
    }

    // ------------------------------------------------------------------ fragments
    record Result(int[] id, int landCount, int seaCount, int[] nation, boolean[] isSea) {}

    static Result splitAndMerge(int[] prov, byte[] nat, int minLand, int minSea, double[] cosLat, double minArea) {
        int total = prov.length;
        int[] comp = new int[total];
        Arrays.fill(comp, -1);
        List<Integer> sizes = new ArrayList<>();
        int[] queue = new int[total];
        for (int s = 0; s < total; s++) {
            if (comp[s] != -1) continue;
            int id = sizes.size(), head = 0, tail = 0;
            queue[tail++] = s; comp[s] = id;
            while (head < tail) {
                int p = queue[head++];
                int x = p % HW, y = p / HW;
                if (x > 0 && comp[p - 1] == -1 && prov[p - 1] == prov[p]) { comp[p - 1] = id; queue[tail++] = p - 1; }
                if (x < HW - 1 && comp[p + 1] == -1 && prov[p + 1] == prov[p]) { comp[p + 1] = id; queue[tail++] = p + 1; }
                if (y > 0 && comp[p - HW] == -1 && prov[p - HW] == prov[p]) { comp[p - HW] = id; queue[tail++] = p - HW; }
                if (y < HH - 1 && comp[p + HW] == -1 && prov[p + HW] == prov[p]) { comp[p + HW] = id; queue[tail++] = p + HW; }
            }
            sizes.add(tail);
        }
        int nc = sizes.size();
        boolean[] compSea = new boolean[nc];
        int[] compNation = new int[nc];
        for (int s = 0; s < total; s++) { int c = comp[s]; compSea[c] = nat[s] == 0; compNation[c] = nat[s] & 0xFF; }
        int[] parent = new int[nc];
        for (int i = 0; i < nc; i++) parent[i] = i;
        agglomerateLand(comp, compSea, compNation, parent, sizes, nc, cosLat, minArea);
        Integer[] order = new Integer[nc];
        for (int i = 0; i < nc; i++) order[i] = i;
        Arrays.sort(order, Comparator.comparingInt(sizes::get));
        Map<Integer, List<Integer>> small = new HashMap<>();
        for (int c = 0; c < nc; c++) if (sizes.get(c) < (compSea[c] ? minSea : minLand)) small.put(c, new ArrayList<>());
        for (int s = 0; s < total; s++) { List<Integer> l = small.get(comp[s]); if (l != null) l.add(s); }
        for (int c : order) {
            List<Integer> pix = small.get(c);
            if (pix == null || find(parent, c) != c) continue;
            int root = find(parent, c);
            Map<Integer, Integer> same = new HashMap<>(), any = new HashMap<>();
            for (int s : pix) {
                int x = s % HW, y = s / HW;
                int[] nb = {x > 0 ? s - 1 : -1, x < HW - 1 ? s + 1 : -1, y > 0 ? s - HW : -1, y < HH - 1 ? s + HW : -1};
                for (int q : nb) {
                    if (q < 0 || comp[q] == c) continue;
                    int o = find(parent, comp[q]);
                    if (o == root) continue;
                    any.merge(o, 1, Integer::sum);
                    boolean ok = compSea[c] ? compSea[o] : (!compSea[o] && compNation[o] == compNation[c]);
                    if (ok) same.merge(o, 1, Integer::sum);
                }
            }
            Map<Integer, Integer> use = !same.isEmpty() ? same : null;
            if (use == null && compSea[c]) use = any;                          // inland water body: fold into land
            if (use == null && !compSea[c] && sizes.get(c) < 6) use = any;      // speck of land: drown or absorb it
            if (use == null || use.isEmpty()) continue;
            int best = Collections.max(use.entrySet(), Map.Entry.comparingByValue()).getKey();
            parent[root] = best;
            List<Integer> bl = small.get(best);
            if (bl != null) bl.addAll(pix);
        }
        int[] newId = new int[nc];
        Arrays.fill(newId, -1);
        boolean[] rootSea = new boolean[nc];
        for (int c = 0; c < nc; c++) { int r = find(parent, c); rootSea[r] = compSea[r]; }
        int land = 0, seaN = 0;
        for (int pass = 0; pass < 2; pass++)
            for (int s = 0; s < total; s++) {
                int r = find(parent, comp[s]);
                if ((pass == 0) == rootSea[r]) continue;
                if (newId[r] == -1) newId[r] = pass == 0 ? land++ : land + seaN++;
            }
        int[] pid = new int[total];
        for (int s = 0; s < total; s++) pid[s] = newId[find(parent, comp[s])];
        int n = land + seaN;
        int[] nationOf = new int[n];
        boolean[] seaOf = new boolean[n];
        for (int s = 0; s < total; s++) {
            int r = find(parent, comp[s]);
            seaOf[pid[s]] = rootSea[r];
            nationOf[pid[s]] = rootSea[r] ? 0 : compNation[r];
        }
        return new Result(pid, land, seaN, nationOf, seaOf);
    }


    /**
     * Makes provinces comparable in size across nations: land pieces smaller than minArea (cos-weighted pixels) are
     * merged into their smallest well-connected neighbour of the same nation; remote small islands join the nearest
     * province of their nation as archipelago provinces.
     */
    static void agglomerateLand(int[] comp, boolean[] compSea, int[] compNation, int[] parent, List<Integer> sizes, int nc, double[] cosLat, double minArea) {
        double[] area = new double[nc], sx = new double[nc], sy = new double[nc];
        List<Map<Integer, Integer>> adj = new ArrayList<>();
        for (int i = 0; i < nc; i++) adj.add(compSea[i] ? null : new HashMap<>());
        for (int p = 0; p < comp.length; p++) {
            int c = comp[p];
            if (compSea[c]) continue;
            int x = p % HW, y = p / HW;
            area[c] += cosLat[y]; sx[c] += x; sy[c] += y;
            if (x < HW - 1) link(adj, comp, compSea, compNation, c, comp[p + 1]);
            if (y < HH - 1) link(adj, comp, compSea, compNation, c, comp[p + HW]);
        }
        PriorityQueue<double[]> pq = new PriorityQueue<>(Comparator.comparingDouble(a -> a[0]));
        for (int c = 0; c < nc; c++) if (!compSea[c] && area[c] < minArea) pq.add(new double[]{area[c], c});
        int merged = 0;
        while (!pq.isEmpty()) {
            double[] e = pq.poll();
            int r = (int) e[1];
            if (parent[r] != r || area[r] != e[0] || area[r] >= minArea) continue;
            Map<Integer, Integer> nb = adj.get(r);
            int best = -1; double bestArea = Double.MAX_VALUE;
            for (var en : nb.entrySet()) if (en.getValue() >= 8 && area[en.getKey()] < bestArea) { bestArea = area[en.getKey()]; best = en.getKey(); }
            if (best < 0) { int bc = 0; for (var en : nb.entrySet()) if (en.getValue() > bc) { bc = en.getValue(); best = en.getKey(); } }
            if (best < 0) continue;                                    // an island: handled below
            parent[r] = best;
            area[best] += area[r]; sx[best] += sx[r]; sy[best] += sy[r];
            sizes.set(best, sizes.get(best) + sizes.get(r));
            Map<Integer, Integer> ab = adj.get(best);
            for (var en : nb.entrySet()) {
                int n = en.getKey();
                if (n == best) continue;
                ab.merge(n, en.getValue(), Integer::sum);
                Map<Integer, Integer> an = adj.get(n);
                an.remove(r);
                an.merge(best, en.getValue(), Integer::sum);
            }
            ab.remove(r);
            merged++;
            if (area[best] < minArea) pq.add(new double[]{area[best], best});
        }
        // remote small islands: join the nearest province of the same nation within ~8 degrees
        int joined = 0;
        for (int c = 0; c < nc; c++) {
            if (compSea[c] || parent[c] != c || area[c] >= minArea || !adj.get(c).isEmpty()) continue;
            double cx = sx[c] / sizes.get(c), cy = sy[c] / sizes.get(c);
            int bestC = -1; double bd = 135;
            for (int o = 0; o < nc; o++) {
                if (o == c || compSea[o] || parent[o] != o || compNation[o] != compNation[c] || area[o] < minArea * 0.5) continue;
                double d = Math.hypot(sx[o] / sizes.get(o) - cx, sy[o] / sizes.get(o) - cy);
                if (d < bd) { bd = d; bestC = o; }
            }
            if (bestC >= 0) { parent[c] = bestC; area[bestC] += area[c]; sx[bestC] += sx[c]; sy[bestC] += sy[c]; sizes.set(bestC, sizes.get(bestC) + sizes.get(c)); joined++; }
        }
        System.out.println("agglomeration: merged " + merged + " small provinces, joined " + joined + " islands");
    }

    private static void link(List<Map<Integer, Integer>> adj, int[] comp, boolean[] compSea, int[] compNation, int a, int b) {
        if (a == b || compSea[b] || compNation[a] != compNation[b]) return;
        adj.get(a).merge(b, 1, Integer::sum);
        adj.get(b).merge(a, 1, Integer::sum);
    }

    static int find(int[] parent, int x) {
        while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x]; }
        return x;
    }

    // ------------------------------------------------------------------ output
    static void write(Path out, List<Nation> nations, byte[] nat, Result r, String[] names, Map<String, double[]> capitals) throws Exception {
        int total = r.landCount + r.seaCount;
        long[] cnt = new long[total];
        double[] sx = new double[total], sy = new double[total];
        List<Set<Integer>> nb = new ArrayList<>();
        for (int i = 0; i < total; i++) nb.add(new TreeSet<>());
        int[] id = r.id;
        for (int y = 0; y < HH; y++)
            for (int x = 0; x < HW; x++) {
                int p = y * HW + x, a = id[p];
                cnt[a]++; sx[a] += x + 0.5; sy[a] += y + 0.5;
                if (x < HW - 1 && id[p + 1] != a) { nb.get(a).add(id[p + 1]); nb.get(id[p + 1]).add(a); }
                if (y < HH - 1 && id[p + HW] != a) { nb.get(a).add(id[p + HW]); nb.get(id[p + HW]).add(a); }
            }
        BufferedImage img = new BufferedImage(OUT_W, OUT_H, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < OUT_H; y++)
            for (int x = 0; x < OUT_W; x++) {
                int hx = Math.min(HW - 1, (int) ((x + 0.5) * SCALE)), hy = Math.min(HH - 1, (int) ((y + 0.5) * SCALE));
                img.setRGB(x, y, id[hy * HW + hx] + 1);
            }
        ImageIO.write(img, "png", out.resolve("provinces.png").toFile());

        double[] cx = new double[total], cy = new double[total];
        for (int i = 0; i < total; i++) {
            double mx = sx[i] / cnt[i], my = sy[i] / cnt[i];
            if (!r.isSea[i] && id[Math.min(HH - 1, (int) my) * HW + Math.min(HW - 1, (int) mx)] != i) {
                // ring-shaped or multi-part provinces: put the marker on the province's own land, nearest to the middle
                outer:
                for (int rad = 1; rad < 160; rad++)
                    for (int dy = -rad; dy <= rad; dy++) for (int dx = -rad; dx <= rad; dx++) {
                        if (Math.max(Math.abs(dx), Math.abs(dy)) != rad) continue;
                        int x = (int) mx + dx, y = (int) my + dy;
                        if (x < 0 || y < 0 || x >= HW || y >= HH) continue;
                        if (id[y * HW + x] == i) { mx = x; my = y; break outer; }
                    }
            }
            cx[i] = mx / SCALE; cy[i] = my / SCALE;
        }
        int[] capital = new int[nations.size()];
        Arrays.fill(capital, -1);
        String[] capName = new String[nations.size()];
        for (Nation n : nations) {
            double[] c = capitals.get(n.tag);
            if (c == null) continue;
            int cp = capitalProvince(n, r, c[0], c[1]);
            if (cp >= 0) { capital[n.index] = cp; capName[n.index] = capitalNames.get(n.tag); }
        }
        for (Nation n : nations) {
            if (capital[n.index] >= 0) continue;
            double lx = pxX(n.labelLon) / SCALE, ly = pxY(n.labelLat) / SCALE, bd = Double.MAX_VALUE;
            for (int i = 0; i < r.landCount; i++) {
                if (r.nation[i] != n.index + 1) continue;
                double d = (cx[i] - lx) * (cx[i] - lx) + (cy[i] - ly) * (cy[i] - ly);
                if (d < bd) { bd = d; capital[n.index] = i; }
            }
        }
        try (PrintWriter pw = new PrintWriter(out.resolve("provinces.tsv").toFile(), StandardCharsets.UTF_8)) {
            pw.println("id\ttype\tnation\tname\tcx\tcy\tpixels\tterrain\tneighbours");
            for (int i = 0; i < total; i++) {
                boolean sea = r.isSea[i];
                String name = names[i];
                String tag = sea ? "" : nations.get(r.nation[i] - 1).tag;
                double lon = lonOf(cx[i] * SCALE), lat = latOf(cy[i] * SCALE);
                String terrain = sea ? "SEA" : terrain(lon, lat, nations.get(r.nation[i] - 1).continent);
                StringJoiner sj = new StringJoiner(",");
                for (int o : nb.get(i)) sj.add(String.valueOf(o));
                pw.printf(Locale.ROOT, "%d\t%s\t%s\t%s\t%.2f\t%.2f\t%d\t%s\t%s%n", i, sea ? "SEA" : "LAND", tag, name,
                        cx[i], cy[i], Math.max(1, Math.round(cnt[i] / (SCALE * SCALE))), terrain, sj);
            }
        }
        Map<String, String> blocs = blocs();
        int playable = 0;
        try (PrintWriter pw = new PrintWriter(out.resolve("nations.tsv").toFile(), StandardCharsets.UTF_8)) {
            pw.println("tag\tname\tcontinent\tpopulation\tgdpMillions\tcapital\tbloc\tiso2\tcapitalName");
            for (Nation n : nations) {
                if (capital[n.index] < 0) continue;
                playable++;
                pw.printf(Locale.ROOT, "%s\t%s\t%s\t%d\t%.0f\t%d\t%s\t%s\t%s%n", n.tag, n.name, n.continent, n.pop, n.gdp, capital[n.index], blocs.getOrDefault(n.tag, "NONE"), n.iso2.toLowerCase(), capName[n.index] == null ? "" : capName[n.index]);
            }
        }
        System.out.println("playable nations: " + playable);
        writeArcs(out, id);
    }


    static String[] buildNames(List<Nation> nations, List<Region> regions, int[] prov, List<Integer> origin, int keys, int R, Result r) {
        int total = r.landCount + r.seaCount;
        Map<Long, Integer> votes = new HashMap<>();
        double[] sx = new double[total], sy = new double[total];
        long[] cnt = new long[total];
        for (int i = 0; i < prov.length; i += 7) {
            int f = r.id[i];
            cnt[f]++; sx[f] += i % HW; sy[f] += i / HW;
            if (r.isSea[f]) continue;
            votes.merge(((long) f << 32) | (prov[i] & 0xFFFFFFFFL), 1, Integer::sum);
        }
        int[] best = new int[total], bestN = new int[total];
        Arrays.fill(best, -1);
        for (var e : votes.entrySet()) {
            int f = (int) (e.getKey() >> 32), key = (int) (long) e.getKey();
            if (e.getValue() > bestN[f]) { bestN[f] = e.getValue(); best[f] = key; }
        }
        String[] names = new String[total];
        Map<String, List<Integer>> groups = new LinkedHashMap<>();
        for (int f = 0; f < r.landCount; f++) {
            Nation n = nations.get(r.nation[f] - 1);
            int key = best[f];
            int base = key >= keys ? (key - keys < origin.size() ? origin.get(key - keys) : -1) : key;
            String nm = base >= 0 && base < R ? regions.get(base).name : n.name;
            if (nm.isEmpty()) nm = n.name;
            names[f] = nm;
            groups.computeIfAbsent(n.tag + "|" + nm, k -> new ArrayList<>()).add(f);
        }
        for (List<Integer> g : groups.values()) {
            if (g.size() < 2) continue;
            double mx = 0, my = 0;
            for (int f : g) { mx += sx[f] / cnt[f]; my += sy[f] / cnt[f]; }
            mx /= g.size(); my /= g.size();
            Map<String, Integer> used = new HashMap<>();
            for (int f : g) {
                double dx = sx[f] / cnt[f] - mx, dy = sy[f] / cnt[f] - my;
                String dir = Math.abs(dx) > Math.abs(dy) ? (dx > 0 ? "Eastern " : "Western ") : (dy > 0 ? "Southern " : "Northern ");
                if (Math.hypot(dx, dy) < 25) dir = "Central ";
                String nm = dir + names[f];
                int c = used.merge(nm, 1, Integer::sum);
                names[f] = c > 1 ? nm + " " + c : nm;
            }
        }
        Map<String, Integer> seaCount = new HashMap<>();
        for (int f = r.landCount; f < total; f++) {
            double lon = lonOf(sx[f] / cnt[f]), lat = latOf(sy[f] / cnt[f]);
            String nm = seaName(lon, lat);
            int c = seaCount.merge(nm, 1, Integer::sum);
            names[f] = c > 1 ? nm + " " + c : nm;
        }
        return names;
    }

    static String seaName(double lon, double lat) {
        if (in(lon, lat, -6, 36, 30, 46)) return "Mediterranean Sea";
        if (in(lon, lat, 27, 42, 40, 47)) return "Black Sea";
        if (in(lon, lat, 46, 55, 36, 47)) return "Caspian Sea";
        if (in(lon, lat, 9, 30, 53, 66)) return "Baltic Sea";
        if (in(lon, lat, -4, 9, 51, 62)) return "North Sea";
        if (in(lon, lat, 32, 44, 12, 30)) return "Red Sea";
        if (in(lon, lat, 48, 57, 24, 31)) return "Persian Gulf";
        if (in(lon, lat, -98, -81, 18, 31)) return "Gulf of Mexico";
        if (in(lon, lat, -86, -60, 9, 23)) return "Caribbean Sea";
        if (in(lon, lat, -95, -76, 51, 64)) return "Hudson Bay";
        if (in(lon, lat, 127, 142, 33, 52)) return "Sea of Japan";
        if (in(lon, lat, 105, 121, 3, 23)) return "South China Sea";
        if (in(lon, lat, 80, 100, 5, 22)) return "Bay of Bengal";
        if (in(lon, lat, 52, 75, 5, 25)) return "Arabian Sea";
        if (in(lon, lat, 142, 180, 50, 66) || in(lon, lat, -180, -160, 50, 66)) return "Bering Sea";
        if (lat > 66) return "Arctic Ocean";
        if (lat < -45) return "Southern Ocean";
        if (lon > 20 && lon < 120 && lat < 25) return "Indian Ocean";
        if (lon >= -70 && lon <= 20) return lat >= 0 ? "North Atlantic" : "South Atlantic";
        if (lon > 120 || lon < -70) return lat >= 0 ? "North Pacific" : "South Pacific";
        return "Ocean";
    }

    // ------------------------------------------------------------------ vector borders
    static int idAt(int[] id, int x, int y) { return (x < 0 || y < 0 || x >= HW || y >= HH) ? -1 : id[y * HW + x]; }

    /** Edge codes: h(x,y) joins corners (x,y)-(x+1,y); v(x,y) joins (x,y)-(x,y+1). code = (y*(HW+1)+x)*2 + (h?0:1). */
    static boolean hExists(int[] id, int x, int y) { return x >= 0 && x < HW && y >= 0 && y <= HH && idAt(id, x, y - 1) != idAt(id, x, y); }
    static boolean vExists(int[] id, int x, int y) { return y >= 0 && y < HH && x >= 0 && x <= HW && idAt(id, x - 1, y) != idAt(id, x, y); }
    static int code(int x, int y, boolean h) { return (y * (HW + 1) + x) * 2 + (h ? 0 : 1); }

    static int[] incident(int[] id, int x, int y, int[] buf) {
        int n = 0;
        if (hExists(id, x, y)) buf[n++] = code(x, y, true);
        if (hExists(id, x - 1, y)) buf[n++] = code(x - 1, y, true);
        if (vExists(id, x, y)) buf[n++] = code(x, y, false);
        if (vExists(id, x, y - 1)) buf[n++] = code(x, y - 1, false);
        buf[4] = n;
        return buf;
    }

    static void writeArcs(Path out, int[] id) throws IOException {
        BitSet visited = new BitSet((HW + 1) * (HH + 1) * 2);
        List<float[]> arcs = new ArrayList<>();
        List<int[]> pairs = new ArrayList<>();
        int[] buf = new int[5];
        long points = 0;
        for (int y = 0; y <= HH; y++) {
            for (int x = 0; x <= HW; x++) {
                incident(id, x, y, buf);
                int deg = buf[4];
                if (deg == 0 || deg == 2) continue;
                int[] edges = Arrays.copyOf(buf, deg);
                for (int e : edges) {
                    if (visited.get(e)) continue;
                    float[] arc = trace(id, x, y, e, visited, buf);
                    if (arc == null) continue;
                    arcs.add(arc);
                    pairs.add(pairOf(id, e));
                    points += arc.length / 2;
                }
            }
        }
        // closed loops that never touch a junction
        for (int y = 0; y <= HH; y++) {
            for (int x = 0; x <= HW; x++) {
                for (int h = 0; h < 2; h++) {
                    int e = code(x, y, h == 0);
                    if (visited.get(e)) continue;
                    if (h == 0 ? !hExists(id, x, y) : !vExists(id, x, y)) continue;
                    float[] arc = trace(id, x, y, e, visited, buf);
                    if (arc == null) continue;
                    arcs.add(arc);
                    pairs.add(pairOf(id, e));
                    points += arc.length / 2;
                }
            }
        }
        System.out.println("arcs=" + arcs.size() + " points=" + points);
        try (DataOutputStream o = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(out.resolve("arcs.bin.gz")))))) {
            o.writeInt(arcs.size());
            for (int i = 0; i < arcs.size(); i++) {
                float[] a = arcs.get(i);
                int[] pr = pairs.get(i);
                o.writeInt(pr[0]); o.writeInt(pr[1]);
                o.writeInt(a.length / 2);
                for (float v : a) o.writeFloat(v);
            }
        }
    }

    static int[] pairOf(int[] id, int e) {
        int t = e & 1, c = e >> 1, x = c % (HW + 1), y = c / (HW + 1);
        if (t == 0) return new int[]{idAt(id, x, y - 1), idAt(id, x, y)};
        return new int[]{idAt(id, x - 1, y), idAt(id, x, y)};
    }

    /** Follow an arc from corner (sx,sy) along edge e until a junction (or back to the start), then simplify and smooth it. */
    static float[] trace(int[] id, int sx, int sy, int e, BitSet visited, int[] buf) {
        List<int[]> pts = new ArrayList<>();
        pts.add(new int[]{sx, sy});
        int cx = sx, cy = sy, cur = e;
        boolean loop = false;
        while (true) {
            visited.set(cur);
            int t = cur & 1, c = cur >> 1, ex = c % (HW + 1), ey = c / (HW + 1);
            int nx, ny;
            if (t == 0) { if (ex == cx && ey == cy) { nx = ex + 1; ny = ey; } else { nx = ex; ny = ey; } }
            else { if (ex == cx && ey == cy) { nx = ex; ny = ey + 1; } else { nx = ex; ny = ey; } }
            cx = nx; cy = ny;
            pts.add(new int[]{cx, cy});
            incident(id, cx, cy, buf);
            int deg = buf[4];
            if (deg != 2) break;
            if (cx == sx && cy == sy) { loop = true; break; }
            int next = buf[0] == cur ? buf[1] : buf[0];
            if (visited.get(next)) { break; }
            cur = next;
        }
        int[] pr = pairOf(id, e);
        double[][] poly = new double[pts.size()][2];
        for (int i = 0; i < poly.length; i++) { poly[i][0] = pts.get(i)[0]; poly[i][1] = pts.get(i)[1]; }
        boolean frame = pr[0] < 0 || pr[1] < 0;
        poly = simplify(poly, frame ? 0.5 : 1.1);
        if (!frame && poly.length >= 3) { poly = chaikin(poly, loop); poly = chaikin(poly, loop); }
        float[] res = new float[poly.length * 2];
        for (int i = 0; i < poly.length; i++) { res[2 * i] = (float) (poly[i][0] / SCALE); res[2 * i + 1] = (float) (poly[i][1] / SCALE); }
        return res;
    }

    static double[][] simplify(double[][] p, double eps) {
        if (p.length < 3) return p;
        boolean[] keep = new boolean[p.length];
        keep[0] = keep[p.length - 1] = true;
        Deque<int[]> st = new ArrayDeque<>();
        st.push(new int[]{0, p.length - 1});
        while (!st.isEmpty()) {
            int[] s = st.pop();
            double md = -1; int mi = -1;
            for (int i = s[0] + 1; i < s[1]; i++) {
                double d = segDist(p[i], p[s[0]], p[s[1]]);
                if (d > md) { md = d; mi = i; }
            }
            if (md > eps) { keep[mi] = true; st.push(new int[]{s[0], mi}); st.push(new int[]{mi, s[1]}); }
        }
        int n = 0;
        for (boolean k : keep) if (k) n++;
        double[][] r = new double[n][];
        int j = 0;
        for (int i = 0; i < p.length; i++) if (keep[i]) r[j++] = p[i];
        return r;
    }

    static double segDist(double[] p, double[] a, double[] b) {
        double dx = b[0] - a[0], dy = b[1] - a[1], l = dx * dx + dy * dy;
        if (l == 0) return Math.hypot(p[0] - a[0], p[1] - a[1]);
        double t = Math.max(0, Math.min(1, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / l));
        return Math.hypot(p[0] - (a[0] + t * dx), p[1] - (a[1] + t * dy));
    }

    static double[][] chaikin(double[][] p, boolean cyclic) {
        int n = p.length;
        List<double[]> r = new ArrayList<>();
        if (cyclic && n > 3) {
            int m = n - 1; // last point repeats the first
            for (int i = 0; i < m; i++) {
                double[] a = p[i], b = p[(i + 1) % m];
                r.add(new double[]{0.75 * a[0] + 0.25 * b[0], 0.75 * a[1] + 0.25 * b[1]});
                r.add(new double[]{0.25 * a[0] + 0.75 * b[0], 0.25 * a[1] + 0.75 * b[1]});
            }
            r.add(r.get(0).clone());
            return r.toArray(new double[0][]);
        }
        r.add(p[0]);
        for (int i = 0; i < n - 1; i++) {
            double[] a = p[i], b = p[i + 1];
            if (i > 0) r.add(new double[]{0.75 * a[0] + 0.25 * b[0], 0.75 * a[1] + 0.25 * b[1]});
            if (i < n - 2) r.add(new double[]{0.25 * a[0] + 0.75 * b[0], 0.25 * a[1] + 0.75 * b[1]});
        }
        r.add(p[n - 1]);
        return r.toArray(new double[0][]);
    }

    static Map<String, String> blocs() {
        Map<String, String> m = new HashMap<>();
        for (String t : "USA CAN GBR FRA DEU ITA ESP PRT NLD BEL LUX DNK NOR ISL POL CZE SVK HUN ROU BGR HRV SVN ALB MNE MKD GRC TUR EST LVA LTU FIN SWE".split(" ")) m.put(t, "NATO");
        for (String t : "RUS BLR ARM KAZ KGZ TJK".split(" ")) m.put(t, "CSTO");
        for (String t : "CHN PRK".split(" ")) m.put(t, "EASTERN");
        for (String t : "AUS NZL JPN KOR".split(" ")) m.put(t, "PACIFIC");
        return m;
    }

    /** Placeholder terrain classifier (no relief data offline): latitude bands + regional ranges + noise. */
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
