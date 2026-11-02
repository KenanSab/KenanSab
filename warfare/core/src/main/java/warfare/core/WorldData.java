package warfare.core;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Static, immutable world: provinces, nations, geometry. Loaded from assets/map. */
public final class WorldData {
    public static final double DEG_PER_PX = 0.15, LAT_TOP = 84.0;

    public static final class ProvinceInfo {
        public int id;
        public boolean sea;
        public int nation = -1;            // start owner (index into nations), -1 for sea
        public String name;
        public double cx, cy;              // centroid in map pixels
        public int pixels;
        public Terrain terrain;
        public int[] neighbours;
        public double[] distKm;            // distance to each neighbour
        public double lat, lon;
        public double pop;                 // inhabitants
        public double gdp;                 // million USD / year
        public double oil;                 // oil output points
        public double weight;              // share of national weight
    }

    public static final class NationInfo {
        public int index;
        public String tag, name, continent, bloc, iso2 = "", capitalName = "";
        public long population;
        public double gdp;
        public int capital;
        public double aggression;
        public double oilProduction;
        public int nukes;
        public List<Integer> provinces = new ArrayList<>();
    }

    public int width, height;
    public int[] idMap;                    // province id per pixel, -1 = none
    public final List<ProvinceInfo> provinces = new ArrayList<>();
    public final List<NationInfo> nations = new ArrayList<>();
    public final Map<String, Integer> nationByTag = new HashMap<>();

    public int landCount() { int c = 0; for (ProvinceInfo p : provinces) if (!p.sea) c++; return c; }

    public static WorldData load(Path dir) throws IOException {
        WorldData w = new WorldData();
        BufferedImage img = ImageIO.read(dir.resolve("provinces.png").toFile());
        w.width = img.getWidth(); w.height = img.getHeight();
        w.idMap = new int[w.width * w.height];
        for (int y = 0; y < w.height; y++)
            for (int x = 0; x < w.width; x++)
                w.idMap[y * w.width + x] = (img.getRGB(x, y) & 0xFFFFFF) - 1;

        List<String> nl = Files.readAllLines(dir.resolve("nations.tsv"));
        for (int i = 1; i < nl.size(); i++) {
            String[] f = nl.get(i).split("\t");
            NationInfo n = new NationInfo();
            n.index = w.nations.size();
            n.tag = f[0]; n.name = f[1]; n.continent = f[2];
            n.population = Long.parseLong(f[3]); n.gdp = Math.max(500, Double.parseDouble(f[4]));
            n.capital = Integer.parseInt(f[5]); n.bloc = f[6];
            if (f.length > 7) n.iso2 = f[7];
            if (f.length > 8) n.capitalName = f[8];
            n.aggression = (Math.abs((n.tag + "agg").hashCode() % 1000)) / 1000.0;
            w.nations.add(n);
            w.nationByTag.put(n.tag, n.index);
        }
        List<String> pl = Files.readAllLines(dir.resolve("provinces.tsv"));
        for (int i = 1; i < pl.size(); i++) {
            String[] f = pl.get(i).split("\t");
            ProvinceInfo p = new ProvinceInfo();
            p.id = Integer.parseInt(f[0]);
            p.sea = f[1].equals("SEA");
            p.nation = p.sea ? -1 : w.nationByTag.getOrDefault(f[2], -1);
            p.name = f[3];
            p.cx = Double.parseDouble(f[4]); p.cy = Double.parseDouble(f[5]);
            p.pixels = Integer.parseInt(f[6]);
            p.terrain = Terrain.valueOf(f[7]);
            p.lon = -180 + p.cx * DEG_PER_PX; p.lat = LAT_TOP - p.cy * DEG_PER_PX;
            String[] nb = f.length > 8 && !f[8].isEmpty() ? f[8].split(",") : new String[0];
            p.neighbours = new int[nb.length];
            for (int k = 0; k < nb.length; k++) p.neighbours[k] = Integer.parseInt(nb[k]);
            w.provinces.add(p);
        }
        for (ProvinceInfo p : w.provinces) {
            p.distKm = new double[p.neighbours.length];
            for (int k = 0; k < p.neighbours.length; k++) {
                ProvinceInfo o = w.provinces.get(p.neighbours[k]);
                double midLat = Math.toRadians((p.lat + o.lat) / 2);
                double dx = (p.cx - o.cx) * DEG_PER_PX * Math.cos(midLat) * 111.2;
                double dy = (p.cy - o.cy) * DEG_PER_PX * 111.2;
                p.distKm[k] = Math.max(15, Math.hypot(dx, dy));
            }
            if (!p.sea && p.nation >= 0) w.nations.get(p.nation).provinces.add(p.id);
        }
        // distribute population / GDP over provinces (weighted by area; capital gets a boost)
        for (NationInfo n : w.nations) {
            double total = 0;
            for (int pid : n.provinces) {
                ProvinceInfo p = w.provinces.get(pid);
                p.weight = p.pixels * Math.cos(Math.toRadians(p.lat)) * (p.terrain == Terrain.ARCTIC ? 0.2 : p.terrain == Terrain.DESERT ? 0.4 : 1.0);
                if (pid == n.capital) p.weight *= 4;
                total += p.weight;
            }
            for (int pid : n.provinces) {
                ProvinceInfo p = w.provinces.get(pid);
                double share = total > 0 ? p.weight / total : 1.0 / n.provinces.size();
                p.pop = n.population * share;
                p.gdp = n.gdp * share;
            }
            n.oilProduction = oilTable().getOrDefault(n.tag, 0.0);
            n.nukes = nukeTable().getOrDefault(n.tag, 0);
            for (int pid : n.provinces) w.provinces.get(pid).oil = n.oilProduction / n.provinces.size();
        }
        return w;
    }

    static Map<String, Double> oilTable() {
        Map<String, Double> m = new HashMap<>();
        String[] t = {"SAU:12", "RUS:11", "USA:12", "CAN:5", "IRQ:5", "IRN:4", "ARE:4", "KWT:3", "CHN:4", "BRA:3.5", "NOR:2", "KAZ:2",
                "NGA:2", "QAT:2", "MEX:2", "LBY:1.5", "DZA:1.5", "AGO:1.5", "VEN:1.5", "GBR:1", "OMN:1", "IND:1", "IDN:1", "COL:1", "EGY:0.7", "AZE:0.8"};
        for (String s : t) { String[] a = s.split(":"); m.put(a[0], Double.parseDouble(a[1])); }
        return m;
    }

    static Map<String, Integer> nukeTable() {
        Map<String, Integer> m = new HashMap<>();
        String[] t = {"USA:30", "RUS:30", "CHN:12", "GBR:8", "FRA:8", "IND:5", "PAK:5", "ISR:4", "PRK:2"};
        for (String s : t) { String[] a = s.split(":"); m.put(a[0], Integer.parseInt(a[1])); }
        return m;
    }
}
