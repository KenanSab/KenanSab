package warfare.desktop;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import warfare.core.WorldData;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Audits the generated map: vector shapes must match the raster, neighbours must be symmetric, capitals sane. */
class MapAuditTest {
    static WorldData world;
    static MapShapes shapes;

    @BeforeAll
    static void load() throws Exception {
        Path dir = Path.of("../assets/map");
        world = WorldData.load(dir);
        shapes = MapShapes.load(dir.resolve("arcs.bin.gz"), world.provinces.size());
    }

    @Test
    void drawnPolygonsMatchTheirRasterArea() {
        List<String> bad = new ArrayList<>();
        int checked = 0;
        for (WorldData.ProvinceInfo p : world.provinces) {
            if (p.pixels < 60) continue;
            Rectangle b = shapes.fill[p.id].getBounds();
            if (b.width <= 0 || b.height <= 0 || b.width > 1200 || b.height > 800) continue;   // giant provinces: skip the cheap rasteriser
            checked++;
            int w = b.width + 2, h = b.height + 2;
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
            Graphics2D g = img.createGraphics();
            g.translate(1 - b.x, 1 - b.y);
            g.setColor(Color.WHITE);
            g.fill(shapes.fill[p.id]);
            g.dispose();
            byte[] d = ((java.awt.image.DataBufferByte) img.getRaster().getDataBuffer()).getData();
            int filled = 0;
            for (byte v : d) if ((v & 255) > 127) filled++;
            double ratio = filled / (double) p.pixels;
            if (ratio < 0.85 || ratio > 1.15) bad.add(p.name + " [" + p.id + "] polygon/raster = " + String.format("%.2f", ratio));
        }
        System.out.println("polygon audit: checked " + checked + " provinces, mismatches " + bad.size() + (bad.isEmpty() ? "" : " e.g. " + bad.subList(0, Math.min(8, bad.size()))));
        assertTrue(bad.size() <= checked * 0.01, "too many province shapes disagree with the raster: " + bad);
    }

    @Test
    void neighboursAreSymmetricAndNothingTouchesItself() {
        for (WorldData.ProvinceInfo p : world.provinces) {
            for (int nb : p.neighbours) {
                assertNotEquals(p.id, nb, p.name + " is its own neighbour");
                boolean back = false;
                for (int x : world.provinces.get(nb).neighbours) if (x == p.id) back = true;
                assertTrue(back, p.name + " -> " + world.provinces.get(nb).name + " not symmetric");
            }
        }
    }

    @Test
    void everyNationHasACoherentTerritoryAndCapital() {
        for (WorldData.NationInfo n : world.nations) {
            assertFalse(n.provinces.isEmpty(), n.tag + " has no provinces");
            WorldData.ProvinceInfo cap = world.provinces.get(n.capital);
            assertFalse(cap.sea, n.tag + " capital in the sea");
            assertEquals(n.index, cap.nation, n.tag + " capital belongs to another nation");
        }
        for (String[] c : new String[][]{{"FRA", "Paris"}, {"RUS", "Moscow"}, {"USA", "Washington"}, {"TUR", "Ankara"}, {"DEU", "Berlin"}, {"JPN", "Tokyo"}}) {
            WorldData.NationInfo n = world.nations.get(world.nationByTag.get(c[0]));
            assertTrue(n.capitalName.startsWith(c[1]), c[0] + " capital is " + n.capitalName);
        }
    }

    @Test
    void provinceSizesAreComparableAcrossNations() {
        double[] tur = sizeStats("TUR"), deu = sizeStats("DEU"), fra = sizeStats("FRA"), gbr = sizeStats("GBR");
        System.out.printf("median province size (map px): TUR %.0f DEU %.0f FRA %.0f GBR %.0f%n", tur[0], deu[0], fra[0], gbr[0]);
        double lo = Math.min(Math.min(tur[0], deu[0]), Math.min(fra[0], gbr[0])), hi = Math.max(Math.max(tur[0], deu[0]), Math.max(fra[0], gbr[0]));
        assertTrue(hi / lo < 2.6, "Turkey vs Germany vs France vs UK should be alike: " + lo + " .. " + hi);
        assertTrue(world.landCount() >= 3000, "land provinces " + world.landCount());
    }

    static double[] sizeStats(String tag) {
        List<Integer> px = new ArrayList<>();
        for (int pid : world.nations.get(world.nationByTag.get(tag)).provinces) px.add(world.provinces.get(pid).pixels);
        Collections.sort(px);
        return new double[]{px.get(px.size() / 2)};
    }
}
