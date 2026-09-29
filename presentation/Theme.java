import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The presentation's own music and chimes, synthesized from scratch (no samples), timed to the
 * slides: talk.html exports its timeline (render2.mjs with CUES_OUT), and this writes
 *   music.wav  the score: an ident, then one theme per part of the talk, and an outro reprise
 *   sfx.wav    the chimes: slide turns, points appearing, part title cards
 *
 * All themes share one tempo, so a new theme starts on the next bar line and the old one rings
 * out underneath it. Chimes are tuned to the key of whatever theme is playing.
 *
 * usage: java Theme.java cues.txt music.wav sfx.wav
 * cues.txt lines: "duration <s>", "part <s> <theme>", "cue <s> <type> <n>"
 */
public final class Theme {
    static final int SR = 48000;
    static final double BPM = 100, BEAT = 60 / BPM, BAR = 4 * BEAT;
    /** when the low boom of a part card's sting lands, after the card starts */
    static final double ACT_BOOM = 0.55;

    // ---- a stereo bus with a reverb send --------------------------------------------------------
    static final class Bus {
        final float[] l, r, sl, sr;

        Bus(int n) {
            l = new float[n]; r = new float[n]; sl = new float[n]; sr = new float[n];
        }

        void put(int i, double s, double pan, double send) {
            if (i < 0 || i >= l.length) return;
            double gl = Math.cos((pan + 1) * Math.PI / 4), gr = Math.sin((pan + 1) * Math.PI / 4);
            l[i] += (float) (s * gl); r[i] += (float) (s * gr);
            sl[i] += (float) (s * gl * send); sr[i] += (float) (s * gr * send);
        }

        /** Adds the reverb of the send bus (a small Freeverb: 4 damped combs, 2 allpasses per side). */
        void reverb(double wet, double room) {
            float[] wl = verb(sl, 0, room), wr = verb(sr, 23, room);
            for (int i = 0; i < l.length; i++) { l[i] += (float) (wl[i] * wet); r[i] += (float) (wr[i] * wet); }
        }

        static float[] verb(float[] in, int spread, double room) {
            int[] combs = {1116, 1188, 1277, 1356, 1422, 1491};
            int[] alls = {556, 441, 341};
            float[] out = new float[in.length];
            for (int c : combs) {
                int len = (int) ((c + spread) * SR / 44100.0);
                float[] buf = new float[len];
                double store = 0;
                int p = 0;
                for (int i = 0; i < in.length; i++) {
                    double y = buf[p];
                    store = y * 0.75 + store * 0.25;                       // damping
                    buf[p] = (float) (in[i] * 0.3 + store * room);
                    out[i] += (float) y;
                    if (++p == len) p = 0;
                }
            }
            for (int a : alls) {
                int len = (int) ((a + spread) * SR / 44100.0);
                float[] buf = new float[len];
                int p = 0;
                for (int i = 0; i < in.length; i++) {
                    double b = buf[p], x = out[i];
                    out[i] = (float) (b - x * 0.5);
                    buf[p] = (float) (x + b * 0.5);
                    if (++p == len) p = 0;
                }
            }
            for (int i = 0; i < out.length; i++) out[i] /= combs.length;
            return out;
        }
    }

    // ---- oscillators --------------------------------------------------------------------------------
    static final int TABLE = 4096;
    static final float[] SAW = table(false), SQUARE = table(true);

    static float[] table(boolean odd) {
        float[] t = new float[TABLE];
        for (int k = 1; k <= 36; k++) {
            if (odd && k % 2 == 0) continue;
            for (int i = 0; i < TABLE; i++) t[i] += (float) (Math.sin(2 * Math.PI * k * i / TABLE) / k);
        }
        float max = 0;
        for (float v : t) max = Math.max(max, Math.abs(v));
        for (int i = 0; i < TABLE; i++) t[i] /= max;
        return t;
    }

    static double look(float[] t, double phase) {
        double p = (phase - Math.floor(phase)) * TABLE;
        int i = (int) p;
        double f = p - i;
        return t[i] * (1 - f) + t[(i + 1) % TABLE] * f;
    }

    static double hz(double midi) {
        return 440 * Math.pow(2, (midi - 69) / 12.0);
    }

    static int at(double t) {
        return (int) Math.round(t * SR);
    }

    // ---- instruments ----------------------------------------------------------------------------------
    /** FM bell: bright strike that mellows. */
    static void bell(Bus b, double t, double midi, double v, double pan, double decay, double send) {
        double f = hz(midi);
        int s0 = at(t), len = at(decay * 4);
        for (int i = 0; i < len; i++) {
            double x = i / (double) SR, env = Math.exp(-x / decay) * Math.min(1, x / 0.002);
            double idx = 2.0 * Math.exp(-x / (decay * 0.2));
            double s = Math.sin(2 * Math.PI * f * x + idx * Math.sin(2 * Math.PI * f * 3.5 * x)) * env
                    + 0.18 * Math.sin(2 * Math.PI * f * 2.01 * x) * Math.exp(-x / (decay * 0.4));
            b.put(s0 + i, s * v, pan, send);
        }
    }

    /** Marimba: a wooden tone bar with quick upper partials. */
    static void marimba(Bus b, double t, double midi, double v, double pan, double send) {
        double f = hz(midi);
        int s0 = at(t), len = at(1.4);
        for (int i = 0; i < len; i++) {
            double x = i / (double) SR, a = Math.min(1, x / 0.0015);
            double s = (Math.sin(2 * Math.PI * f * x) + 0.35 * Math.sin(2 * Math.PI * f * 3.93 * x) * Math.exp(-x / 0.05)
                    + 0.1 * Math.sin(2 * Math.PI * f * 9.8 * x) * Math.exp(-x / 0.015)) * Math.exp(-x / 0.32) * a;
            b.put(s0 + i, s * v, pan, send);
        }
    }

    /** Plucked string (Karplus-Strong), with optional echoes for a ping-pong delay. */
    static void pluck(Bus b, double t, double midi, double v, double pan, double send, double bright, long seed, boolean echo) {
        double f = hz(midi);
        int n = Math.max(2, (int) Math.round(SR / f)), len = at(1.1);
        double[] buf = new double[n];
        Random rnd = new Random(seed);
        double lp = 0;
        for (int i = 0; i < n; i++) { lp += bright * (rnd.nextDouble() * 2 - 1 - lp); buf[i] = lp; }
        double[] out = new double[len];
        for (int i = 0, p = 0; i < len; i++) {
            int q = (p + 1) % n;
            double y = buf[p];
            buf[p] = (buf[p] + buf[q]) * 0.5 * 0.994;
            out[i] = y * Math.min(1, (len - i) / (double) at(0.05));
            p = q;
        }
        int s0 = at(t);
        for (int i = 0; i < len; i++) b.put(s0 + i, out[i] * v, pan, send);
        if (echo) {
            int d = at(BEAT * 0.75);
            for (int e = 1; e <= 3; e++) {
                double g = Math.pow(0.38, e), ep = e % 2 == 1 ? -0.7 : 0.7;
                for (int i = 0; i < len; i++) b.put(s0 + e * d + i, out[i] * v * g, ep, send * 1.5);
            }
        }
    }

    /** Warm pad: three detuned saws through a soft low-pass, slow attack and release. */
    static void pad(Bus b, double t, double len, double midi, double v, double cutoff, double send) {
        double f = hz(midi);
        int s0 = at(t), n = at(len + 1.4);
        double[] det = {-0.07, 0, 0.07};
        double[] pans = {-0.6, 0, 0.6};
        double a = 1 - Math.exp(-2 * Math.PI * cutoff / SR);
        for (int k = 0; k < 3; k++) {
            double fk = f * Math.pow(2, det[k] / 12), y1 = 0, y2 = 0, ph = k * 0.31;
            for (int i = 0; i < n; i++) {
                double x = i / (double) SR;
                double env = Math.min(1, x / 0.9) * (x > len ? Math.exp(-(x - len) / 0.45) : 1);
                ph += fk / SR;
                double s = look(SAW, ph);
                y1 += a * (s - y1);
                y2 += a * (y1 - y2);                                     // 12 dB/oct
                b.put(s0 + i, y2 * env * v / 3, pans[k], send);
            }
        }
    }

    /** Electric piano: FM tine, bell-like at the start, round after. */
    static void ep(Bus b, double t, double len, double midi, double v, double pan, double send) {
        double f = hz(midi);
        int s0 = at(t), n = at(len + 0.8);
        for (int i = 0; i < n; i++) {
            double x = i / (double) SR, idx = 1.3 * Math.exp(-x / 0.25);
            double env = Math.exp(-x / 1.6) * Math.min(1, x / 0.003) * (x > len ? Math.exp(-(x - len) / 0.12) : 1);
            double s = Math.sin(2 * Math.PI * f * x + idx * Math.sin(2 * Math.PI * f * x))
                    + 0.12 * Math.sin(2 * Math.PI * f * 7 * x) * Math.exp(-x / 0.03);
            b.put(s0 + i, s * env * v, pan, send);
        }
    }

    /** Round bass: sine plus a little second harmonic, gently saturated. */
    static void bass(Bus b, double t, double len, double midi, double v, boolean pulse) {
        double f = hz(midi), y = 0, a = 1 - Math.exp(-2 * Math.PI * 520 / SR), ph = 0;
        int s0 = at(t), n = at(len + 0.08);
        for (int i = 0; i < n; i++) {
            double x = i / (double) SR, env = Math.min(1, x / 0.006) * (x > len ? Math.max(0, 1 - (x - len) / 0.08) : 1);
            ph += f / SR;
            double s;
            if (pulse) { y += a * (look(SQUARE, ph) - y); s = y * 0.8 + 0.5 * Math.sin(2 * Math.PI * ph); }
            else s = Math.sin(2 * Math.PI * ph) + 0.22 * Math.sin(4 * Math.PI * ph);
            b.put(s0 + i, Math.tanh(1.4 * s) / Math.tanh(1.4) * env * v, 0, 0.02);
        }
    }

    static void kick(Bus b, double t, double v) {
        int s0 = at(t), n = at(0.5);
        double ph = 0;
        for (int i = 0; i < n; i++) {
            double x = i / (double) SR, f = 45 + 75 * Math.exp(-x / 0.035);
            ph += f / SR;
            double s = Math.sin(2 * Math.PI * ph) * Math.exp(-x / 0.22) + (x < 0.004 ? 0.3 * (1 - x / 0.004) : 0);
            b.put(s0 + i, s * v, 0, 0.02);
        }
    }

    static void noiseHit(Bus b, double t, double v, double pan, double dec, double hp, double lpHz, double send, long seed) {
        Random rnd = new Random(seed);
        int s0 = at(t), n = at(dec * 6);
        double lp = 0, lp2 = 0, a = 1 - Math.exp(-2 * Math.PI * hp / SR), a2 = 1 - Math.exp(-2 * Math.PI * lpHz / SR);
        for (int i = 0; i < n; i++) {
            double x = i / (double) SR, w = rnd.nextDouble() * 2 - 1;
            lp += a * (w - lp);
            lp2 += a2 * ((w - lp) - lp2);
            b.put(s0 + i, lp2 * Math.exp(-x / dec) * Math.min(1, x / 0.001) * v, pan, send);
        }
    }

    static void clap(Bus b, double t, double v, long seed) {
        for (int k = 0; k < 3; k++) noiseHit(b, t + k * 0.011, v * (k == 2 ? 1 : 0.6), 0, k == 2 ? 0.09 : 0.012, 900, 5200, 0.25, seed + k);
    }

    static void hat(Bus b, double t, double v, double pan, long seed) {
        noiseHit(b, t, v, pan, 0.028, 6500, 16000, 0.05, seed);
    }

    static void shaker(Bus b, double t, double v, long seed) {
        Random rnd = new Random(seed);
        int s0 = at(t), n = at(0.12);
        double lp = 0, a = 1 - Math.exp(-2 * Math.PI * 4500 / SR);
        for (int i = 0; i < n; i++) {
            double x = i / (double) SR, w = rnd.nextDouble() * 2 - 1;
            lp += a * (w - lp);
            double env = Math.min(1, x / 0.012) * Math.exp(-x / 0.035);
            b.put(s0 + i, (w - lp) * env * v, 0.3, 0.05);
        }
    }

    static void rim(Bus b, double t, double v) {
        int s0 = at(t), n = at(0.08);
        for (int i = 0; i < n; i++) {
            double x = i / (double) SR;
            b.put(s0 + i, (Math.sin(2 * Math.PI * 1750 * x) * 0.6 + Math.sin(2 * Math.PI * 520 * x) * 0.5) * Math.exp(-x / 0.012) * v, -0.3, 0.2);
        }
    }

    /** Air moving past: noise through a resonant band-pass that sweeps, panned across. */
    static void whoosh(Bus b, double t, double len, double f0, double f1, double v, double pan0, double pan1, long seed) {
        Random rnd = new Random(seed);
        int s0 = at(t), n = at(len);
        double low = 0, band = 0, q = 0.35;
        for (int i = 0; i < n; i++) {
            double u = i / (double) n, fc = f0 * Math.pow(f1 / f0, Math.sin(u * Math.PI / 2));
            double f = 2 * Math.sin(Math.PI * Math.min(fc, SR / 6.0) / SR);
            double w = rnd.nextDouble() * 2 - 1;
            low += f * band;
            double high = w - low - q * band;
            band += f * high;
            double env = Math.pow(Math.sin(Math.PI * Math.min(1, u * 1.15)), 1.6);
            b.put(s0 + i, band * env * v, pan0 + (pan1 - pan0) * u, 0.35);
        }
    }

    static void boom(Bus b, double t, double v) {
        int s0 = at(t), n = at(2.5);
        double ph = 0;
        for (int i = 0; i < n; i++) {
            double x = i / (double) SR, f = 38 + 30 * Math.exp(-x / 0.15);
            ph += f / SR;
            b.put(s0 + i, Math.sin(2 * Math.PI * ph) * Math.exp(-x / 0.7) * Math.min(1, x / 0.01) * v, 0, 0.3);
        }
    }

    // ---- the themes ---------------------------------------------------------------------------------
    /** A theme: its key's pentatonic scale for the chimes, and what it plays in each bar. */
    interface Part {
        void bar(Bus b, double t, int i, int bars);

        int[] scale();
    }

    // chords: {bass, pad voicing...}
    static final int[][] PLAY = {{38, 62, 66, 69, 73}, {35, 59, 62, 66, 69}, {31, 59, 62, 66, 67}, {33, 57, 61, 64, 69}};
    static final int[][] TECH = {{35, 59, 62, 66}, {31, 59, 62, 67}, {38, 57, 62, 66}, {33, 57, 61, 64}};
    static final int[][] HOME = {{43, 55, 59, 62, 66}, {40, 55, 59, 62, 64}, {36, 55, 59, 62, 64}, {38, 54, 57, 59, 62}};
    static final int[] D_PENTA = {74, 76, 78, 81, 83, 86, 88, 90};
    static final int[] B_PENTA = {71, 74, 76, 78, 81, 83, 86, 88};
    static final int[] G_PENTA = {67, 69, 71, 74, 76, 79, 81, 83};
    /** WII-UU's motif: [midi, beat, length in beats] */
    static final double[][] MOTIF = {{74, 0, .5}, {78, .5, .5}, {81, 1, .5}, {86, 1.5, 1}, {85, 2.5, .5}, {81, 3, 1}, {86, 4, 3}};

    static void motif(Bus b, double t, double v, int transpose) {
        for (double[] m : MOTIF) {
            bell(b, t + m[1] * BEAT, m[0] + transpose, v, (m[0] - 80) / 12.0, 0.55 + m[2] * 0.3, 0.45);
            bell(b, t + m[1] * BEAT, m[0] + transpose - 12, v * 0.35, 0, 0.5, 0.4);
        }
    }

    static final Part INTRO = new Part() {
        public void bar(Bus b, double t, int i, int bars) {
            int[] c = PLAY[0];
            if (i == 0) {
                for (int k = 1; k < c.length; k++) pad(b, t, BAR * 2.2, c[k] - 12, 0.16, 1400, 0.5);
                bass(b, t, BAR * 2, 38, 0.18, false);
                motif(b, t + BEAT * 0.5, 0.2, 0);
            } else {
                int[] ch = PLAY[i % 4];
                for (int k = 1; k < ch.length; k++) pad(b, t, BAR, ch[k] - 12, 0.12, 1100, 0.5);
                for (int s = 0; s < 8; s++) marimba(b, t + s * BEAT / 2, ch[1 + (s * 3) % (ch.length - 1)] + 12, 0.08 + 0.03 * (s % 2 == 0 ? 1 : 0), s % 2 == 0 ? -0.4 : 0.4, 0.35);
                bass(b, t, BAR * 0.9, ch[0], 0.2, false);
            }
        }

        public int[] scale() { return D_PENTA; }
    };

    static final Part PLAY_THEME = new Part() {
        final int[] arp = {1, 2, 3, 4, 3, 2, 3, 4};

        public void bar(Bus b, double t, int i, int bars) {
            int[] c = PLAY[i % 4];
            int phrase = (i / 8) % 4;
            boolean breakdown = i % 32 >= 30, first = i < 2;
            for (int k = 1; k < c.length; k++) pad(b, t, BAR, c[k] - 12, 0.1, 1500, 0.5);
            for (int s = 0; s < 8; s++) {
                int idx = arp[(s + (phrase == 2 ? 2 : 0)) % 8];
                marimba(b, t + s * BEAT / 2, c[Math.min(idx, c.length - 1)] + 12, s % 4 == 0 ? 0.13 : 0.09, s % 2 == 0 ? -0.35 : 0.35, 0.3);
            }
            bass(b, t, BEAT * 1.4, c[0], 0.24, false);
            bass(b, t + BEAT * 1.5, BEAT * 0.4, c[0], 0.16, false);
            bass(b, t + BEAT * 2, BEAT * 1.8, c[0] + (i % 4 == 3 ? 4 : 7), 0.18, false);
            if (breakdown || first) return;
            kick(b, t, 0.34); kick(b, t + 2 * BEAT, 0.3);
            if (phrase >= 1) { clap(b, t + BEAT, 0.12, i * 7L); clap(b, t + 3 * BEAT, 0.12, i * 7L + 3); }
            for (int s = 0; s < 16; s++) shaker(b, t + s * BEAT / 4, s % 4 == 2 ? 0.06 : 0.035, i * 100L + s);
            if (phrase == 2 || phrase == 3) {                      // a bell counter-melody
                int[] top = {c[c.length - 1] + 12, c[2] + 12};
                bell(b, t, top[0], 0.07, 0.5, 0.8, 0.5);
                bell(b, t + 2.5 * BEAT, top[1], 0.06, -0.5, 0.7, 0.5);
            }
        }

        public int[] scale() { return D_PENTA; }
    };

    static final Part TECH_THEME = new Part() {
        final int[] pat = {0, 1, 2, 1, 3, 1, 2, 1, 0, 2, 3, 2, 1, 2, 3, 2};

        public void bar(Bus b, double t, int i, int bars) {
            int[] c = TECH[i % 4];
            int phrase = (i / 8) % 4;
            boolean breakdown = i % 32 >= 30, first = i < 2;
            double sweep = 700 + 900 * (0.5 - 0.5 * Math.cos(2 * Math.PI * (i % 8) / 8.0));
            for (int k = 1; k < c.length; k++) pad(b, t, BAR, c[k] - 12, 0.11, sweep, 0.45);
            for (int s = 0; s < 16; s++) {
                if (s % 2 == 1 && phrase == 0) continue;
                int n = c[1 + pat[s] % (c.length - 1)] + (pat[s] == 3 ? 24 : 12);
                pluck(b, t + s * BEAT / 4, n, s % 4 == 0 ? 0.2 : 0.13, s % 2 == 0 ? -0.25 : 0.25, 0.2, 0.55, i * 31L + s, s % 4 == 0);
            }
            for (int s = 0; s < 8; s++) bass(b, t + s * BEAT / 2, BEAT * 0.38, c[0] + (s % 4 == 3 ? 12 : 0), s % 2 == 0 ? 0.2 : 0.14, true);
            if (breakdown || first) return;
            for (int s = 0; s < 4; s++) kick(b, t + s * BEAT, 0.3);
            for (int s = 0; s < 4; s++) hat(b, t + s * BEAT + BEAT / 2, 0.07, 0.3, i * 13L + s);
            if (phrase >= 1) rim(b, t + 3 * BEAT + BEAT * 0.75, 0.08);
            if (phrase >= 2) for (int s = 0; s < 16; s += 2) hat(b, t + s * BEAT / 4, 0.025, -0.3, i * 17L + s);
        }

        public int[] scale() { return B_PENTA; }
    };

    static final Part HOME_THEME = new Part() {
        public void bar(Bus b, double t, int i, int bars) {
            int[] c = HOME[i % 4];
            int phrase = (i / 8) % 4;
            boolean first = i < 2;
            for (int k = 1; k < c.length; k++) pad(b, t, BAR, c[k] - 12, 0.08, 1200, 0.5);
            // EP comping: on the and of 1 and on 3
            for (double beat : new double[]{0.5, 2, 3.5}) {
                for (int k = 1; k < c.length; k++) ep(b, t + beat * BEAT, BEAT * 0.9, c[k], 0.045, (k - 2.5) * 0.3, 0.35);
            }
            bass(b, t, BEAT * 1.7, c[0], 0.24, false);
            bass(b, t + BEAT * 2, BEAT * 0.9, c[0] + 7, 0.17, false);
            bass(b, t + BEAT * 3, BEAT * 0.9, c[0] + (i % 2 == 0 ? 9 : 5), 0.15, false);
            if (first) return;
            kick(b, t, 0.3); kick(b, t + 2.5 * BEAT, 0.22);
            for (int s = 0; s < 8; s++) shaker(b, t + s * BEAT / 2, s % 2 == 1 ? 0.06 : 0.035, i * 90L + s);
            if (phrase >= 1) { rim(b, t + BEAT, 0.07); rim(b, t + 3 * BEAT, 0.07); }
            if (phrase >= 2 && i % 2 == 0) {
                bell(b, t + 1.5 * BEAT, c[c.length - 1] + 12, 0.06, 0.4, 0.7, 0.5);
                bell(b, t + 2 * BEAT, c[c.length - 2] + 12, 0.05, -0.4, 0.7, 0.5);
            }
        }

        public int[] scale() { return G_PENTA; }
    };

    static final Part OUTRO = new Part() {
        public void bar(Bus b, double t, int i, int bars) {
            if (i == 0) {
                for (int k = 1; k < PLAY[0].length; k++) pad(b, t, BAR * 3, PLAY[0][k] - 12, 0.15, 1800, 0.55);
                bass(b, t, BAR * 2.8, 38, 0.22, false);
                motif(b, t, 0.22, 0);
                kick(b, t, 0.3);
            } else if (i < bars - 1) {
                int[] c = PLAY[(i + 1) % 4];
                for (int k = 1; k < c.length; k++) pad(b, t, BAR, c[k] - 12, 0.12, 1300, 0.55);
                for (int s = 0; s < 8; s++) marimba(b, t + s * BEAT / 2, c[1 + (s * 3) % (c.length - 1)] + 12, 0.08, s % 2 == 0 ? -0.4 : 0.4, 0.4);
                bass(b, t, BAR * 0.9, c[0], 0.18, false);
            } else {                                               // last bar: one ringing chord
                for (int k = 1; k < PLAY[0].length; k++) {
                    pad(b, t, BAR * 1.5, PLAY[0][k] - 12, 0.15, 2000, 0.6);
                    bell(b, t, PLAY[0][k] + 12, 0.07, (k - 2.5) * 0.3, 1.4, 0.6);
                }
                bass(b, t, BAR * 1.5, 38, 0.22, false);
            }
        }

        public int[] scale() { return D_PENTA; }
    };

    static Part part(String name) {
        return switch (name) {
            case "intro" -> INTRO;
            case "tech" -> TECH_THEME;
            case "home" -> HOME_THEME;
            case "outro" -> OUTRO;
            default -> PLAY_THEME;
        };
    }

    // ---- main -------------------------------------------------------------------------------------------
    record Cue(double t, String type, int n) {}

    public static void main(String[] args) throws IOException {
        double duration = 0;
        List<double[]> partAt = new ArrayList<>();
        List<String> partName = new ArrayList<>();
        List<Cue> cues = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of(args[0]))) {
            String[] w = line.trim().split("\\s+");
            if (w.length < 2) continue;
            switch (w[0]) {
                case "duration" -> duration = Double.parseDouble(w[1]);
                case "part" -> { partAt.add(new double[]{Double.parseDouble(w[1])}); partName.add(w[2]); }
                case "cue" -> cues.add(new Cue(Double.parseDouble(w[1]), w[2], w.length > 3 ? Integer.parseInt(w[3]) : 0));
                default -> { }
            }
        }
        int n = at(duration + 6);

        // music: every part on its own bus (levelled separately), each bar played by the part that owns it
        int parts = partName.size();
        Bus[] buses = new Bus[parts];
        double[] start = new double[parts];
        for (int p = 0; p < parts; p++) {
            // a new theme begins with the low boom of its part card's sting
            start[p] = p == 0 ? 0 : partAt.get(p)[0] + ACT_BOOM;
        }
        for (int p = 0; p < parts; p++) {
            buses[p] = new Bus(n);
            double end = p + 1 < parts ? start[p + 1] : duration;
            // whole bars only: the old theme stops on a bar line and rings out under the new one
            int bars = Math.max(1, p + 1 < parts ? (int) Math.floor((end - start[p]) / BAR + 0.2) : (int) Math.ceil((end - start[p]) / BAR - 1e-6));
            Part th = part(partName.get(p));
            for (int i = 0; i < bars; i++) th.bar(buses[p], start[p] + i * BAR, i, bars);
            buses[p].reverb(0.9, 0.84);
            System.err.printf("  %-6s %6.1f s - %6.1f s (%d bars)%n", partName.get(p), start[p], end, bars);
        }
        float[] ml = new float[n], mr = new float[n];
        for (int p = 0; p < parts; p++) {
            double end = p + 1 < parts ? start[p + 1] : duration;
            double rms = rms(buses[p], at(start[p]), at(end));
            double gain = rms > 0 ? 0.09 / rms : 1;                 // same loudness for every theme
            for (int i = 0; i < n; i++) { ml[i] += (float) (buses[p].l[i] * gain); mr[i] += (float) (buses[p].r[i] * gain); }
        }

        // chimes, tuned to the theme playing at that moment
        Bus fx = new Bus(n);
        int slideNo = 0, reveal = 0;
        for (Cue c : cues) {
            int p = 0;
            for (int k = 0; k < parts; k++) if (partAt.get(k)[0] <= c.t + 0.01) p = k;
            int[] sc = part(partName.get(p)).scale();
            long seed = Math.round(c.t * 1000);
            switch (c.type) {
                case "slide" -> {
                    reveal = 0;
                    whoosh(fx, c.t - 0.05, 0.75, 350, 2600, 0.5, 0.7, -0.7, seed);
                    int k = (slideNo++ * 2) % (sc.length - 2);
                    bell(fx, c.t + 0.18, sc[k], 0.16, -0.2, 0.45, 0.5);
                    bell(fx, c.t + 0.3, sc[k + 2], 0.14, 0.2, 0.6, 0.5);
                }
                case "reveal" -> {
                    int note = sc[Math.min(sc.length - 1, reveal++)] + 12;
                    bell(fx, c.t, note, 0.05, reveal % 2 == 0 ? 0.35 : -0.35, 0.18, 0.4);
                    noiseHit(fx, c.t, 0.05, 0, 0.006, 3000, 12000, 0.1, seed);
                }
                case "tile" -> marimba(fx, c.t, sc[c.n % sc.length] + (c.n / sc.length) * 12, 0.07, (c.n % 10 - 4.5) / 6, 0.3);
                case "act" -> {
                    whoosh(fx, c.t - 0.4, 1.3, 180, 4200, 0.4, -0.6, 0.6, seed);
                    boom(fx, c.t + ACT_BOOM, 0.3);
                    for (int k = 0; k < 4; k++) bell(fx, c.t + ACT_BOOM + k * 0.09, sc[k * 2 % sc.length], 0.09, (k - 1.5) * 0.4, 0.8, 0.6);
                }
                case "spin" -> whoosh(fx, c.t, 1.1, 250, 1800, 0.32, -0.5, 0.5, seed);
                default -> { }
            }
        }
        fx.reverb(0.8, 0.8);

        write(args[1], ml, mr, duration, 0.7);
        write(args[2], fx.l, fx.r, duration, 0.7);
        System.err.println("  wrote " + args[1] + " and " + args[2]);
    }

    static double rms(Bus b, int from, int to) {
        double s = 0;
        int n = 0;
        for (int i = Math.max(0, from); i < Math.min(to, b.l.length); i++, n++) s += b.l[i] * b.l[i] + b.r[i] * b.r[i];
        return n == 0 ? 0 : Math.sqrt(s / (2 * n));
    }

    /** Writes a 16-bit stereo WAV, peak-normalized to {@code peak}, cut to the talk's length. */
    static void write(String file, float[] l, float[] r, double duration, double peak) throws IOException {
        int frames = Math.min(l.length, at(duration));
        float max = 1e-9f;
        for (int i = 0; i < frames; i++) max = Math.max(max, Math.max(Math.abs(l[i]), Math.abs(r[i])));
        double g = peak / max;
        try (DataOutputStream o = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file)))) {
            o.writeBytes("RIFF");
            o.writeInt(Integer.reverseBytes(36 + frames * 4));
            o.writeBytes("WAVEfmt ");
            o.writeInt(Integer.reverseBytes(16));
            o.writeShort(Short.reverseBytes((short) 1));
            o.writeShort(Short.reverseBytes((short) 2));
            o.writeInt(Integer.reverseBytes(SR));
            o.writeInt(Integer.reverseBytes(SR * 4));
            o.writeShort(Short.reverseBytes((short) 4));
            o.writeShort(Short.reverseBytes((short) 16));
            o.writeBytes("data");
            o.writeInt(Integer.reverseBytes(frames * 4));
            for (int i = 0; i < frames; i++) {
                o.writeShort(Short.reverseBytes((short) Math.round(Math.max(-1, Math.min(1, l[i] * g)) * 32767)));
                o.writeShort(Short.reverseBytes((short) Math.round(Math.max(-1, Math.min(1, r[i] * g)) * 32767)));
            }
        }
    }
}
