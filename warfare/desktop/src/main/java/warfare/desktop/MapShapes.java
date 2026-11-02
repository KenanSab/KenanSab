package warfare.desktop;

import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** Smooth vector geometry of every province: shared border arcs plus one fill shape per province. */
final class MapShapes {
    final Path2D.Float[] fill;
    final Rectangle2D.Float[] bounds;
    final Path2D.Float[] arcPath;
    final Rectangle2D.Float[] arcBounds;
    final int[] p1, p2;

    private MapShapes(int provinces, int arcs) {
        fill = new Path2D.Float[provinces]; bounds = new Rectangle2D.Float[provinces];
        arcPath = new Path2D.Float[arcs]; arcBounds = new Rectangle2D.Float[arcs];
        p1 = new int[arcs]; p2 = new int[arcs];
    }

    private static long key(float x, float y) { return ((long) Float.floatToIntBits(x) << 32) | (Float.floatToIntBits(y) & 0xFFFFFFFFL); }

    static MapShapes load(Path file, int provinces) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(file), 1 << 16), 1 << 16))) {
            int n = in.readInt();
            float[][] pts = new float[n][];
            MapShapes s = new MapShapes(provinces, n);
            for (int a = 0; a < n; a++) {
                s.p1[a] = in.readInt(); s.p2[a] = in.readInt();
                int c = in.readInt();
                float[] p = new float[c * 2];
                for (int i = 0; i < p.length; i++) p[i] = in.readFloat();
                pts[a] = p;
                Path2D.Float path = new Path2D.Float();
                path.moveTo(p[0], p[1]);
                for (int i = 1; i < c; i++) path.lineTo(p[2 * i], p[2 * i + 1]);
                s.arcPath[a] = path;
                Rectangle2D b = path.getBounds2D();
                s.arcBounds[a] = new Rectangle2D.Float((float) b.getX(), (float) b.getY(), (float) b.getWidth(), (float) b.getHeight());
            }
            List<Integer>[] by = new List[provinces];
            for (int a = 0; a < n; a++) {
                for (int p : new int[]{s.p1[a], s.p2[a]}) {
                    if (p < 0 || p >= provinces) continue;
                    if (by[p] == null) by[p] = new ArrayList<>();
                    if (!by[p].contains(a)) by[p].add(a);
                }
            }
            for (int p = 0; p < provinces; p++) {
                Path2D.Float path = new Path2D.Float(Path2D.WIND_EVEN_ODD);
                if (by[p] != null) assemble(path, by[p], pts);
                s.fill[p] = path;
                Rectangle2D b = path.getBounds2D();
                s.bounds[p] = new Rectangle2D.Float((float) b.getX(), (float) b.getY(), (float) b.getWidth(), (float) b.getHeight());
            }
            return s;
        }
    }

    private static void assemble(Path2D.Float path, List<Integer> list, float[][] pts) {
        Map<Long, List<Integer>> ends = new HashMap<>();
        for (int a : list) {
            float[] p = pts[a];
            long k0 = key(p[0], p[1]), k1 = key(p[p.length - 2], p[p.length - 1]);
            ends.computeIfAbsent(k0, k -> new ArrayList<>()).add(a);
            if (k1 != k0) ends.computeIfAbsent(k1, k -> new ArrayList<>()).add(a);
        }
        Set<Integer> used = new HashSet<>();
        for (int a : list) {
            if (used.contains(a)) continue;
            used.add(a);
            float[] p = pts[a];
            path.moveTo(p[0], p[1]);
            for (int i = 2; i < p.length; i += 2) path.lineTo(p[i], p[i + 1]);
            long start = key(p[0], p[1]), end = key(p[p.length - 2], p[p.length - 1]);
            while (end != start) {
                int next = -1;
                for (int b : ends.getOrDefault(end, List.of())) if (!used.contains(b)) { next = b; break; }
                if (next < 0) break;
                used.add(next);
                float[] q = pts[next];
                boolean fwd = key(q[0], q[1]) == end;
                if (fwd) { for (int i = 2; i < q.length; i += 2) path.lineTo(q[i], q[i + 1]); end = key(q[q.length - 2], q[q.length - 1]); }
                else { for (int i = q.length - 4; i >= 0; i -= 2) path.lineTo(q[i], q[i + 1]); end = key(q[0], q[1]); }
            }
            path.closePath();
        }
    }
}
