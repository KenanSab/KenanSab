package warfare.desktop;

import org.junit.jupiter.api.*;
import warfare.core.*;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Path;

class PerfProbeTest {
    @Test
    void probe() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        WarfareApp.assets = Path.of("../assets/map").toAbsolutePath().normalize();
        Fonts.load(WarfareApp.assets); Assets.init(WarfareApp.assets); WarfareApp.theme();
        WorldData world = WorldData.load(WarfareApp.assets);
        Settings.tutorial = false; Wf.Window.instant = true;
        GameState gs = new GameState(world, world.nationByTag.get("FRA"), 1);
        JFrame[] fr = new JFrame[1]; GameUi[] ui = new GameUi[1];
        SwingUtilities.invokeAndWait(() -> { fr[0] = new JFrame("p"); fr[0].setSize(1500, 900); ui[0] = new GameUi(gs, fr[0]); WarfareApp.install(fr[0], ui[0]); fr[0].setVisible(true); });
        Thread.sleep(500);
        MapPanel map = ui[0].map;
        for (double z : new double[]{1.0, 2.5, 6, 12}) {
            long[] t = new long[3];
            SwingUtilities.invokeAndWait(() -> map.centerOn(gs.prov(gs.player == 0 ? 0 : world.nations.get(gs.player).capital).id, z));
            for (int i = 0; i < 6; i++) {
                SwingUtilities.invokeAndWait(() -> { long a = System.nanoTime(); map.viewChanged(); map.paintImmediately(map.getBounds()); t[0] += System.nanoTime() - a; });
            }
            for (int i = 0; i < 12; i++) {
                SwingUtilities.invokeAndWait(() -> { long a = System.nanoTime(); map.paintImmediately(map.getBounds()); t[1] += System.nanoTime() - a; });
            }
            for (int i = 0; i < 6; i++) {
                SwingUtilities.invokeAndWait(() -> { long a = System.nanoTime(); ((JComponent) fr[0].getContentPane()).paintImmediately(0, 0, 1500, 900); t[2] += System.nanoTime() - a; });
            }
            System.out.printf("SEG blit %.1f fx %.1f hl %.1f names %.1f cities %.1f armies %.1f ctl %.1f (ms/paint)%n", map.seg[0]/1e6/Math.max(1,map.paints), map.seg[1]/1e6/Math.max(1,map.paints), map.seg[2]/1e6/Math.max(1,map.paints), map.seg[3]/1e6/Math.max(1,map.paints), map.seg[4]/1e6/Math.max(1,map.paints), map.seg[5]/1e6/Math.max(1,map.paints), map.seg[6]/1e6/Math.max(1,map.paints));
            java.util.Arrays.fill(map.seg, 0); map.paints = 0; map.paintNs = 0;
            System.out.printf("PERF zoom %.1f: view-change paint %.1f ms | steady map paint %.1f ms | whole window %.1f ms%n", z, t[0] / 6e6, t[1] / 12e6, t[2] / 6e6);
        }
        for (String key : new String[]{"economy", "diplomacy", "production", "reports"}) {
            SwingUtilities.invokeAndWait(() -> { ui[0].pick(key); ui[0].refresh(); });
            long[] t = new long[1];
            for (int i = 0; i < 8; i++) SwingUtilities.invokeAndWait(() -> { long a = System.nanoTime(); ((JComponent) fr[0].getContentPane()).paintImmediately(0, 0, 1500, 900); t[0] += System.nanoTime() - a; });
            long[] w = new long[1];
            for (int i = 0; i < 8; i++) SwingUtilities.invokeAndWait(() -> { long a = System.nanoTime(); ui[0].windows.get(ui[0].openWindow).paintImmediately(ui[0].windows.get(ui[0].openWindow).getBounds().getBounds().getSize() == null ? new Rectangle() : new Rectangle(0, 0, ui[0].windows.get(ui[0].openWindow).getWidth(), ui[0].windows.get(ui[0].openWindow).getHeight())); w[0] += System.nanoTime() - a; });
            System.out.printf("WIN %s: whole window %.1f ms, window only %.1f ms%n", key, t[0] / 8e6, w[0] / 8e6);
            SwingUtilities.invokeAndWait(() -> ui[0].pick(key));
        }
        SwingUtilities.invokeAndWait(() -> fr[0].dispose());
    }
}
