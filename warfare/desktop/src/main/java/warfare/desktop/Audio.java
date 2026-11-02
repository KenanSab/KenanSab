package warfare.desktop;

import javax.sound.sampled.*;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Plays the procedural score and effects on one output line. The score is built on a background thread; stems fade
 * in and out with {@link #setTension(double)}. Silently does nothing when no audio device exists (servers, CI).
 */
final class Audio {
    private static final Audio INSTANCE = new Audio();
    static Audio get() { return INSTANCE; }

    private volatile boolean running, available;
    private volatile double tension = 0.15;
    private final Map<Synth.Sfx, float[]> sfx = new EnumMap<>(Synth.Sfx.class);
    private final ConcurrentLinkedQueue<float[]> pending = new ConcurrentLinkedQueue<>();
    private Thread thread;

    boolean isAvailable() { return available; }

    void setTension(double t) { tension = Math.max(0, Math.min(1, t)); }

    void play(Synth.Sfx s) {
        if (!running || Settings.master * Settings.sfx <= 0.001) return;
        float[] b = sfx.get(s);
        if (b != null && pending.size() < 12) pending.add(b);
    }

    synchronized void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::run, "audio");
        thread.setDaemon(true);
        thread.start();
    }

    void stop() { running = false; }

    private static double smooth(double x, double a, double b) {
        double t = Math.max(0, Math.min(1, (x - a) / (b - a)));
        return t * t * (3 - 2 * t);
    }

    private void run() {
        SourceDataLine line;
        try {
            AudioFormat fmt = new AudioFormat(Synth.SR, 16, 2, true, false);
            line = AudioSystem.getSourceDataLine(fmt);
            line.open(fmt, Synth.SR / 4 * 4);
            line.start();
        } catch (Throwable e) {
            running = false;
            return;       // no audio device
        }
        available = true;
        for (Synth.Sfx s : Synth.Sfx.values()) sfx.put(s, Synth.sfx(s));
        Synth.Stems st = Synth.render(16, 1962);
        int n = st.length(), pos = 0;
        double gp = 0, gb = 0, gc = 0, go = 0;
        List<float[]> activeBuf = new ArrayList<>();
        List<int[]> activePos = new ArrayList<>();
        int chunk = 1024;
        byte[] out = new byte[chunk * 4];
        try {
            while (running) {
                double t = tension;
                double tp = 0.62 - 0.18 * t, tb = 0.10 + 0.60 * smooth(t, 0.05, 0.7), tc = 0.95 * smooth(t, 0.42, 0.72), to = 0.8 * smooth(t, 0.25, 0.65);
                gp += (tp - gp) * 0.05; gb += (tb - gb) * 0.05; gc += (tc - gc) * 0.05; go += (to - go) * 0.05;
                double mv = Settings.master * Settings.music, sv = Settings.master * Settings.sfx;
                float[] nb;
                while ((nb = pending.poll()) != null) { activeBuf.add(nb); activePos.add(new int[]{0}); }
                for (int i = 0; i < chunk; i++) {
                    double l = (st.padL()[pos] * gp + st.bass()[pos] * gb + st.perc()[pos] * gc + st.ostinato()[pos] * go) * mv * 0.95;
                    double r = (st.padR()[pos] * gp + st.bass()[pos] * gb + st.perc()[pos] * gc + st.ostinato()[pos] * go) * mv * 0.95;
                    for (int k = activeBuf.size() - 1; k >= 0; k--) {
                        int[] p = activePos.get(k);
                        float[] b = activeBuf.get(k);
                        double v = b[p[0]++] * sv * 0.8;
                        l += v; r += v;
                        if (p[0] >= b.length) { activeBuf.remove(k); activePos.remove(k); }
                    }
                    pos = (pos + 1) % n;
                    int sl = (int) (Math.tanh(l) * 32000), sr = (int) (Math.tanh(r) * 32000);
                    out[i * 4] = (byte) sl; out[i * 4 + 1] = (byte) (sl >> 8);
                    out[i * 4 + 2] = (byte) sr; out[i * 4 + 3] = (byte) (sr >> 8);
                }
                line.write(out, 0, out.length);
            }
        } catch (Throwable ignored) {
        } finally {
            available = false;
            try { line.drain(); line.close(); } catch (Throwable ignored) {}
        }
    }
}
