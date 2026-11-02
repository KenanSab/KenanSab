package warfare.desktop;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SynthTest {
    static double rms(float[] x) { double s = 0; for (float v : x) s += v * v; return Math.sqrt(s / x.length); }

    /** Goertzel magnitude of a single frequency over a window. */
    static double power(float[] x, int from, int len, double freq) {
        double w = 2 * Math.PI * freq / Synth.SR, c = 2 * Math.cos(w), s1 = 0, s2 = 0;
        for (int i = from; i < from + len; i++) { double s = x[i % x.length] + c * s1 - s2; s2 = s1; s1 = s; }
        return Math.sqrt(s1 * s1 + s2 * s2 - c * s1 * s2) / len;
    }

    @Test
    void stemsAreCleanAndLoopable() {
        Synth.Stems st = Synth.render(4, 1);
        assertEquals(Synth.samplesFor(4), st.length());
        for (float[] s : new float[][]{st.padL(), st.padR(), st.bass(), st.perc(), st.ostinato()}) {
            assertEquals(st.length(), s.length);
            double peak = 0;
            for (float v : s) { assertFalse(Float.isNaN(v) || Float.isInfinite(v)); peak = Math.max(peak, Math.abs(v)); }
            assertTrue(peak <= 1.0, "no clipping, peak " + peak);
            assertTrue(rms(s) > 0.01, "stem must not be silent: " + rms(s));
        }
        // loop seam: the jump between last and first sample must be no bigger than a normal sample-to-sample step
        float[] pad = st.padL();
        double maxStep = 0;
        for (int i = 1; i < 20000; i++) maxStep = Math.max(maxStep, Math.abs(pad[i] - pad[i - 1]));
        assertTrue(Math.abs(pad[0] - pad[pad.length - 1]) <= maxStep * 3 + 0.02, "seam jump " + Math.abs(pad[0] - pad[pad.length - 1]) + " vs " + maxStep);
    }

    @Test
    void padPlaysTheChordNotes() {
        Synth.Stems st = Synth.render(4, 1);
        int chordLen = st.length() / 2;
        // first chord is A minor: A (220/440 Hz) and E (659 Hz) must stand out against an unrelated pitch
        int from = (int) (chordLen * 0.45), len = (int) (Synth.SR * 0.8);
        double a = power(st.padL(), from, len, 440.0), e = power(st.padL(), from, len, 659.26), off = power(st.padL(), from, len, 497.0);
        assertTrue(a > off * 3, "A present " + a + " vs " + off);
        assertTrue(e > off * 2, "E present " + e + " vs " + off);
        // second chord is F major: F (349.2 Hz) appears there, not in the first chord
        int from2 = chordLen + (int) (chordLen * 0.45);
        assertTrue(power(st.padL(), from2, len, 349.23) > power(st.padL(), from, len, 349.23) * 2);
    }

    @Test
    void percussionHitsOnTheBeat() {
        Synth.Stems st = Synth.render(4, 1);
        int beat = (int) Math.round(Synth.SR * Synth.BEAT);
        double onBeat = rms(java.util.Arrays.copyOfRange(st.perc(), 0, (int) (0.3 * Synth.SR)));
        double offBeat = rms(java.util.Arrays.copyOfRange(st.perc(), beat / 2 + 2000, beat / 2 + 2000 + (int) (0.1 * Synth.SR)));
        assertTrue(onBeat > offBeat * 2, "kick on beat 1: " + onBeat + " vs " + offBeat);
    }

    @Test
    void everySoundEffectIsAudibleAndBounded() {
        for (Synth.Sfx s : Synth.Sfx.values()) {
            float[] b = Synth.sfx(s);
            assertTrue(b.length > Synth.SR / 40, s + " length");
            double peak = 0;
            for (float v : b) { assertFalse(Float.isNaN(v)); peak = Math.max(peak, Math.abs(v)); }
            assertTrue(peak > 0.1 && peak <= 1.0, s + " peak " + peak);
        }
        assertTrue(Synth.sfx(Synth.Sfx.NUKE).length > 3 * Synth.SR, "nuke is a long rumble");
    }

    @Test
    void engineDegradesGracefullyWithoutDevice() {
        Audio a = Audio.get();
        a.setTension(5); a.setTension(-1);   // clamped, no exception
        a.play(Synth.Sfx.CLICK);             // no-op when not started
        assertFalse(a.isAvailable());
    }
}
