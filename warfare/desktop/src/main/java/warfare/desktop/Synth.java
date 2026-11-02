package warfare.desktop;

import java.util.Random;

/**
 * Procedural audio. Everything here is original and generated from maths, so no music licences are needed.
 * The score is four loopable "stems" (strings pad, brass/low drone, taiko percussion, staccato ostinato) in A minor
 * at 96 BPM; the engine fades stems in and out as tension rises. All functions are pure and deterministic.
 */
final class Synth {
    private Synth() {}

    static final int SR = 32000;
    static final double BPM = 96, BEAT = 60.0 / BPM;

    /** {root midi, third, fifth}: Am F C G | Am F Dm E */
    static final int[][] CHORDS = {
            {57, 3, 7}, {53, 4, 7}, {48, 4, 7}, {55, 4, 7}, {57, 3, 7}, {53, 4, 7}, {50, 3, 7}, {52, 4, 7}};

    record Stems(float[] padL, float[] padR, float[] bass, float[] perc, float[] ostinato) {
        int length() { return padL.length; }
    }

    static double midi(double n) { return 440.0 * Math.pow(2, (n - 69) / 12.0); }

    static int samplesFor(int bars) { return (int) Math.round(SR * BEAT * 4 * bars); }

    // ------------------------------------------------------------------ score
    static Stems render(int bars, long seed) {
        int n = samplesFor(bars);
        int chords = Math.max(1, bars / 2);
        int chordLen = n / chords;
        float[] padL = new float[n], padR = new float[n], bass = new float[n], perc = new float[n], osti = new float[n];
        Random rng = new Random(seed);
        for (int c = 0; c < chords; c++) {
            int[] ch = CHORDS[c % CHORDS.length];
            int root = ch[0];
            int start = c * chordLen;
            int[] notes = {root, root + 12, root + ch[1] + 12, root + ch[2] + 12, root + ch[2] + 24, root + 24};
            // ---- strings pad: three detuned saws per voice, slow swell, tails wrap round the loop
            double[] detL = {-9, -2, 6}, detR = {-6, 2, 9};
            for (int v = 0; v < notes.length; v++) {
                double amp = v < 4 ? 0.050 : 0.026;
                addSaws(padL, start, chordLen, notes[v], detL, amp, 1.7, 1.9, rng);
                addSaws(padR, start, chordLen, notes[v], detR, amp, 1.7, 1.9, rng);
            }
            // ---- low brass / drone
            addVoice(bass, start, chordLen, root - 24, 0.20, 0.5, 1.2, true);
            addVoice(bass, start, chordLen, root - 12, 0.10, 0.9, 1.3, false);
            // ---- staccato ostinato: eighth notes over the chord tones
            int[] pattern = {0, 2, 3, 2, 0, 2, 3, 4};
            int eighth = (int) Math.round(SR * BEAT / 2);
            for (int k = 0; start + k * eighth < start + chordLen; k++) {
                int note = notes[pattern[k % pattern.length]] + 12;
                double accent = k % 4 == 0 ? 1.0 : 0.62;
                addPluck(osti, start + k * eighth, note, 0.075 * accent);
            }
        }
        // ---- taiko / snare, one bar at a time
        int barLen = (int) Math.round(SR * BEAT * 4);
        for (int bar = 0; bar < bars; bar++) {
            int b0 = bar * barLen;
            addTaiko(perc, b0, 1.0);
            addTaiko(perc, b0 + (int) Math.round(SR * BEAT * 2), 0.8);
            addTaiko(perc, b0 + (int) Math.round(SR * BEAT * 2.5), 0.45);
            addSnare(perc, b0 + (int) Math.round(SR * BEAT), 0.38, rng);
            addSnare(perc, b0 + (int) Math.round(SR * BEAT * 3), 0.5, rng);
            if (bar % 4 == 3) for (int k = 0; k < 4; k++) addSnare(perc, b0 + (int) Math.round(SR * BEAT * (3.0 + k * 0.25)), 0.22 + 0.1 * k, rng);
        }
        lowpass(padL, 2400, 2); lowpass(padR, 2400, 2);
        lowpass(bass, 500, 2);
        lowpass(osti, 3200, 1);
        reverbLoop(padL, 0.34); reverbLoop(padR, 0.34, 7);
        reverbLoop(osti, 0.28);
        reverbLoop(perc, 0.16);
        normalise(padL, padR, 0.9);
        normalise(bass, 0.9);
        normalise(perc, 0.95);
        normalise(osti, 0.8);
        return new Stems(padL, padR, bass, perc, osti);
    }

    private static double saw(double phase) { return 2 * (phase - Math.floor(phase + 0.5)); }

    private static void addSaws(float[] out, int start, int len, int note, double[] cents, double amp, double atk, double rel, Random rng) {
        int tailSamples = (int) (rel * SR);
        double f = midi(note);
        double[] ph = new double[cents.length];
        for (int i = 0; i < ph.length; i++) ph[i] = rng.nextDouble();
        int total = len + tailSamples;
        for (int k = 0; k < total; k++) {
            double s = 0;
            for (int i = 0; i < cents.length; i++) {
                ph[i] += f * Math.pow(2, cents[i] / 1200.0) / SR;
                s += saw(ph[i]);
            }
            double t = k / (double) SR;
            double a = Math.min(1, t / atk);
            a = a * a * (3 - 2 * a);
            double r = k <= len ? 1 : Math.max(0, 1 - (k - len) / (double) tailSamples);
            r = r * r * (3 - 2 * r);
            double trem = 1 + 0.07 * Math.sin(2 * Math.PI * 0.23 * (start + k) / SR);
            out[(start + k) % out.length] += (float) (s / cents.length * amp * a * r * trem);
        }
    }

    private static void addVoice(float[] out, int start, int len, int note, double amp, double atk, double rel, boolean sine) {
        int tail = (int) (rel * SR);
        double f = midi(note), ph = 0;
        for (int k = 0; k < len + tail; k++) {
            ph += f / SR;
            double s = sine ? Math.sin(2 * Math.PI * ph) * 0.7 + saw(ph) * 0.3 : saw(ph) * 0.8 + Math.sin(2 * Math.PI * ph) * 0.2;
            double a = Math.min(1, k / (atk * SR));
            a = a * a * (3 - 2 * a);
            double r = k <= len ? 1 : Math.max(0, 1 - (k - len) / (double) tail);
            double swell = 0.78 + 0.22 * Math.sin(Math.PI * 2 * k / (SR * BEAT * 4));
            out[(start + k) % out.length] += (float) (s * amp * a * r * swell);
        }
    }

    private static void addPluck(float[] out, int start, int note, double amp) {
        double f = midi(note), ph = 0;
        int len = (int) (0.20 * SR);
        for (int k = 0; k < len; k++) {
            ph += f / SR;
            double e = Math.exp(-k / (0.055 * SR));
            out[(start + k) % out.length] += (float) (saw(ph) * e * amp);
        }
    }

    private static void addTaiko(float[] out, int start, double amp) {
        int len = (int) (0.75 * SR);
        double ph = 0;
        for (int k = 0; k < len; k++) {
            double t = k / (double) SR;
            double f = 48 + 72 * Math.exp(-t * 22);
            ph += f / SR;
            double body = Math.sin(2 * Math.PI * ph) * Math.exp(-t * 6.0);
            double click = Math.sin(2 * Math.PI * 190 * t) * Math.exp(-t * 60) * 0.35;
            out[(start + k) % out.length] += (float) ((body + click) * amp * 0.9);
        }
    }

    private static void addSnare(float[] out, int start, double amp, Random rng) {
        int len = (int) (0.22 * SR);
        double lp = 0;
        for (int k = 0; k < len; k++) {
            double t = k / (double) SR;
            double n = rng.nextDouble() * 2 - 1;
            lp += 0.55 * (n - lp);
            double hp = n - lp;
            out[(start + k) % out.length] += (float) (hp * Math.exp(-t * 20) * amp * 0.8 + Math.sin(2 * Math.PI * 185 * t) * Math.exp(-t * 28) * amp * 0.3);
        }
    }

    // ------------------------------------------------------------------ dsp helpers
    static void lowpass(float[] x, double cutoff, int passes) {
        double a = 1 - Math.exp(-2 * Math.PI * cutoff / SR);
        for (int p = 0; p < passes; p++) {
            double y = x[x.length - 1];                      // continue from the loop end for a seamless seam
            for (int i = 0; i < x.length * 2; i++) {         // two sweeps; keep results of the second
                int idx = i % x.length;
                y += a * (x[idx] - y);
                if (i >= x.length) x[idx] = (float) y;
            }
        }
    }

    static void reverbLoop(float[] x, double wet) { reverbLoop(x, wet, 0); }

    /** Schroeder reverb over a looping buffer: process two passes and keep the second so the tail wraps round. */
    static void reverbLoop(float[] x, double wet, int spread) {
        int[] combs = {1557, 1617, 1491, 1422, 1277, 1356};
        double scale = SR / 44100.0;
        float[][] cb = new float[combs.length][];
        int[] ci = new int[combs.length];
        double[] damp = new double[combs.length];
        for (int i = 0; i < combs.length; i++) cb[i] = new float[(int) ((combs[i] + spread * 11) * scale)];
        int[] aps = {225, 556};
        float[][] ab = new float[2][];
        int[] ai = new int[2];
        for (int i = 0; i < 2; i++) ab[i] = new float[(int) (aps[i] * scale)];
        float[] out = new float[x.length];
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < x.length; i++) {
                double in = x[i] * 0.2, acc = 0;
                for (int c = 0; c < cb.length; c++) {
                    double y = cb[c][ci[c]];
                    damp[c] += 0.35 * (y - damp[c]);
                    cb[c][ci[c]] = (float) (in + damp[c] * 0.84);
                    ci[c] = (ci[c] + 1) % cb[c].length;
                    acc += y;
                }
                for (int a = 0; a < 2; a++) {
                    double bufv = ab[a][ai[a]];
                    double y = -acc + bufv;
                    ab[a][ai[a]] = (float) (acc + bufv * 0.5);
                    ai[a] = (ai[a] + 1) % ab[a].length;
                    acc = y;
                }
                if (pass == 1) out[i] = (float) (x[i] * (1 - wet * 0.5) + acc * wet);
            }
        }
        System.arraycopy(out, 0, x, 0, x.length);
    }

    static void normalise(float[] x, double peak) {
        double m = 1e-9;
        for (float v : x) m = Math.max(m, Math.abs(v));
        float g = (float) (peak / m);
        for (int i = 0; i < x.length; i++) x[i] *= g;
    }

    static void normalise(float[] a, float[] b, double peak) {
        double m = 1e-9;
        for (float v : a) m = Math.max(m, Math.abs(v));
        for (float v : b) m = Math.max(m, Math.abs(v));
        float g = (float) (peak / m);
        for (int i = 0; i < a.length; i++) { a[i] *= g; b[i] *= g; }
    }

    // ------------------------------------------------------------------ sound effects
    enum Sfx { CLICK, CONFIRM, ERROR, ALERT, CHIME, BATTLE, CAPTURE, WAR_HORN, NUKE }

    static float[] sfx(Sfx s) {
        Random rng = new Random(s.ordinal() * 7919L + 13);
        switch (s) {
            case CLICK -> { return tone(0.045, t -> Math.sin(2 * Math.PI * 1500 * t) * Math.exp(-t * 90) * 0.5 + Math.sin(2 * Math.PI * 600 * t) * Math.exp(-t * 60) * 0.3); }
            case CONFIRM -> { return tone(0.22, t -> (Math.sin(2 * Math.PI * 660 * t) * (t < 0.09 ? 1 : 0) + Math.sin(2 * Math.PI * 990 * t) * (t >= 0.09 ? 1 : 0)) * Math.exp(-(t % 0.09) * 18) * 0.4); }
            case ERROR -> { return tone(0.22, t -> saw(t * 110) * Math.exp(-t * 9) * 0.4); }
            case ALERT -> { return tone(0.6, t -> Math.signum(Math.sin(2 * Math.PI * 880 * t)) * (((int) (t * 7)) % 2 == 0 ? 1 : 0) * Math.exp(-t * 1.6) * 0.22); }
            case CHIME -> { return tone(1.1, t -> (Math.sin(2 * Math.PI * 784 * t) + 0.5 * Math.sin(2 * Math.PI * 1176 * t) + 0.25 * Math.sin(2 * Math.PI * 1568 * t)) * Math.exp(-t * 4.2) * 0.3); }
            case BATTLE -> {
                float[] lp = new float[1];
                return tone(1.1, t -> {
                    double boom = Math.sin(2 * Math.PI * (40 + 90 * Math.exp(-t * 14)) * t) * Math.exp(-t * 5);
                    double n = rng.nextDouble() * 2 - 1;
                    lp[0] += 0.12 * (n - lp[0]);
                    return (boom * 0.8 + lp[0] * Math.exp(-t * 3.5) * 1.4) * 0.55;
                });
            }
            case CAPTURE -> {
                return tone(1.7, t -> {
                    double[] f = {293.66, 369.99, 440.0, 587.33};
                    double v = 0;
                    for (int i = 0; i < f.length; i++) { double on = t - i * 0.14; if (on > 0) v += saw(on * f[i]) * Math.min(1, on * 18) * Math.exp(-on * 1.7); }
                    return v * 0.16;
                });
            }
            case WAR_HORN -> {
                float[] lp = new float[1];
                return tone(2.4, t -> {
                    double raw = saw(t * 98) * 0.6 + saw(t * 147) * 0.4;
                    lp[0] += (0.04 + 0.12 * Math.min(1, t / 1.0)) * (raw - lp[0]);
                    double a = Math.min(1, t / 0.5) * Math.min(1, (2.4 - t) / 0.9);
                    return lp[0] * a * 0.9;
                });
            }
            default -> {   // NUKE
                float[] lp = new float[1], lp2 = new float[1];
                return tone(5.0, t -> {
                    double sub = Math.sin(2 * Math.PI * (22 + 40 * Math.exp(-t * 1.2)) * t) * Math.exp(-t * 0.9);
                    double n = rng.nextDouble() * 2 - 1;
                    lp[0] += (0.02 + 0.5 * Math.exp(-t * 1.6)) * (n - lp[0]);
                    lp2[0] += 0.08 * (n - lp2[0]);
                    double crackle = (rng.nextDouble() < 0.0025 * Math.exp(-t * 0.5) ? (rng.nextDouble() - 0.5) * 2 : 0);
                    double flash = t < 0.35 ? (n * 0.3) * (1 - t / 0.35) : 0;
                    return (sub * 0.9 + lp[0] * Math.exp(-t * 0.85) * 1.2 + lp2[0] * Math.exp(-t * 0.5) * 0.4 + crackle * 0.5 + flash) * 0.5 * Math.min(1, t * 40);
                });
            }
        }
    }

    interface Fn { double at(double t); }

    private static float[] tone(double seconds, Fn f) {
        float[] out = new float[(int) (seconds * SR)];
        for (int i = 0; i < out.length; i++) {
            double v = f.at(i / (double) SR);
            double fade = Math.min(1, (out.length - i) / (0.02 * SR));
            out[i] = (float) Math.max(-1, Math.min(1, v * fade));
        }
        return out;
    }
}
