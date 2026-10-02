package wiiuu.ui;

import java.util.Random;

/**
 * WII-UU's own background music, in the spirit of a relaxed console menu: a laid-back, lightly
 * swung 16-bar loop in F major with electric piano, bass, a vibraphone lead and soft percussion.
 * It is an original tune, written as note lists below and synthesized at start-up, so no audio
 * files ship and nothing is copied from any console.
 *
 * <p>Everything is rendered into one seamless loop: notes and reverb that ring past the end
 * wrap around to the start.
 */
final class MenuMusic {
    private static final int RATE = MenuAudio.RATE;
    private static final double BPM = 100;
    private static final double BEAT = 60.0 / BPM;
    private static final int BARS = 16;
    private static final double SWING = 0.07;          // late off-beats, in beats

    private MenuMusic() {}

    // ---- the tune ---------------------------------------------------------------------------

    /** Chords: bass root (MIDI), then the voicing. One or two per bar (the second from beat 2). */
    private static final int[][][] CHORDS = {
            {{41, 57, 60, 64}},                         // 1  Fmaj7
            {{45, 55, 60, 64}},                         // 2  Am7
            {{46, 57, 62, 65}},                         // 3  Bbmaj7
            {{48, 58, 65, 67}, {48, 58, 64, 67}},       // 4  C7sus4  C7
            {{41, 57, 60, 64}},                         // 5  Fmaj7
            {{50, 57, 60, 65}},                         // 6  Dm7
            {{43, 53, 58, 62}},                         // 7  Gm7
            {{48, 58, 62, 64}},                         // 8  C9
            {{46, 57, 62, 65}},                         // 9  Bbmaj7
            {{46, 55, 61, 65}},                         // 10 Bbm6
            {{45, 55, 60, 64}},                         // 11 Am7
            {{50, 60, 63, 66}},                         // 12 D7b9
            {{43, 53, 58, 62}},                         // 13 Gm7
            {{48, 58, 64, 67}},                         // 14 C7
            {{41, 57, 60, 64}},                         // 15 Fmaj7
            {{43, 53, 58, 62}, {48, 58, 64, 67}},       // 16 Gm7  C7
    };

    /** Melody: bar (1-based), beat, MIDI note, length in beats. */
    private static final double[][] MELODY = {
            {1, 0, 69, .5}, {1, .5, 72, .5}, {1, 1, 76, 1}, {1, 2.5, 74, .5}, {1, 3, 72, 1},
            {2, 0, 76, 1.5}, {2, 1.5, 79, .5}, {2, 2, 76, .5}, {2, 2.5, 72, 1.5},
            {3, 0, 74, .5}, {3, .5, 77, .5}, {3, 1, 81, 1.5}, {3, 2.5, 79, .5}, {3, 3, 77, 1},
            {4, 0, 79, 2}, {4, 2.5, 76, .5}, {4, 3, 77, .5}, {4, 3.5, 79, .5},
            {5, 0, 81, 1}, {5, 1, 79, .5}, {5, 1.5, 77, .5}, {5, 2, 76, 1}, {5, 3, 72, 1},
            {6, 0, 74, .5}, {6, .5, 77, 1}, {6, 2, 69, .5}, {6, 2.5, 72, .5}, {6, 3, 74, 1},
            {7, 0, 70, .5}, {7, .5, 74, .5}, {7, 1, 77, 1}, {7, 2, 76, .5}, {7, 2.5, 74, .5}, {7, 3, 72, 1},
            {8, 0, 74, 1}, {8, 1, 76, .5}, {8, 1.5, 79, 2.5},
            {9, .5, 77, .5}, {9, 1, 81, .5}, {9, 1.5, 84, 1}, {9, 2.5, 81, .5}, {9, 3, 77, 1},
            {10, 0, 79, 1}, {10, 1, 77, .5}, {10, 1.5, 73, 1.5}, {10, 3, 70, 1},
            {11, 0, 72, .5}, {11, .5, 76, .5}, {11, 1, 79, 1}, {11, 2, 81, .5}, {11, 2.5, 79, .5}, {11, 3, 76, 1},
            {12, 0, 78, 1}, {12, 1, 75, .5}, {12, 1.5, 72, .5}, {12, 2, 69, 1.5},
            {13, 0, 70, .5}, {13, .5, 74, .5}, {13, 1, 77, .5}, {13, 1.5, 81, 1}, {13, 2.5, 79, .5}, {13, 3, 77, 1},
            {14, 0, 76, 1.5}, {14, 1.5, 79, .5}, {14, 2, 82, 1}, {14, 3, 81, .5}, {14, 3.5, 79, .5},
            {15, 0, 81, 1}, {15, 1, 77, .5}, {15, 1.5, 72, .5}, {15, 2, 76, 2},
            {16, 1, 74, .5}, {16, 1.5, 76, .5}, {16, 2, 77, .5}, {16, 2.5, 79, .5}, {16, 3, 76, .5}, {16, 3.5, 72, .5},
    };

    /** Electric piano comping: beat and length, the same light pattern every bar. */
    private static final double[][] COMP = {{0, .9}, {1.5, .45}, {2.5, .45}, {3.5, .4}};

    // ---- rendering --------------------------------------------------------------------------

    /** Renders the loop: interleaved 16-bit stereo at 44.1 kHz (about 38 s, 6.8 MB). */
    static short[] render() {
        int frames = (int) Math.round(BARS * 4 * BEAT * RATE);
        float[] left = new float[frames], right = new float[frames];
        Random noise = new Random(7);

        for (int bar = 0; bar < BARS; bar++) {
            int[][] chords = CHORDS[bar];
            for (double[] hit : COMP) {
                int[] chord = chords[chords.length > 1 && hit[0] >= 2 ? 1 : 0];
                for (int k = 1; k < chord.length; k++) {
                    // a hair of strum, lower notes first
                    electricPiano(left, right, time(bar, hit[0]) + k * 0.008, hit[1] * BEAT, midi(chord[k]),
                            hit[0] == 0 ? 0.075 : 0.055);
                }
            }
            // bass: root, fifth, root, then a chromatic step into the next bar's root
            for (int half = 0; half < chords.length; half++) {
                int root = chords[half][0];
                double from = half * 2.0;
                boolean whole = chords.length == 1;
                bass(left, right, time(bar, from), (whole ? 1.4 : 0.9) * BEAT, midi(root));
                if (whole) {
                    bass(left, right, time(bar, 1.5), 0.45 * BEAT, midi(root + 7));
                    bass(left, right, time(bar, 2), 1.3 * BEAT, midi(root));
                } else {
                    bass(left, right, time(bar, from + 1), 0.45 * BEAT, midi(root + 7));
                }
            }
            int nextRoot = CHORDS[(bar + 1) % BARS][0][0];
            int lastRoot = chords[chords.length - 1][0];
            bass(left, right, time(bar, 3.5), 0.45 * BEAT, midi(nextRoot + (nextRoot > lastRoot ? -1 : 1)));

            // percussion: shaker on every eighth, rim on 2 and 4, a soft kick on 1 and the "and" of 3
            for (int e = 0; e < 8; e++) shaker(left, right, time(bar, e * 0.5), e % 2 == 1 ? 0.05 : 0.03, noise);
            rim(left, right, time(bar, 1), noise);
            rim(left, right, time(bar, 3), noise);
            kick(left, right, time(bar, 0));
            kick(left, right, time(bar, 2.5));
        }
        for (double[] n : MELODY) {
            vibes(left, right, time((int) n[0] - 1, n[1]), n[3] * BEAT, midi((int) n[2]));
        }

        reverb(left, right);
        // normalise to a comfortable level and interleave
        float peak = 1e-6f;
        for (int i = 0; i < frames; i++) peak = Math.max(peak, Math.max(Math.abs(left[i]), Math.abs(right[i])));
        float gain = 0.8f / peak;
        short[] out = new short[frames * 2];
        for (int i = 0; i < frames; i++) {
            out[i * 2] = (short) (Math.max(-1f, Math.min(1f, left[i] * gain)) * 32767);
            out[i * 2 + 1] = (short) (Math.max(-1f, Math.min(1f, right[i] * gain)) * 32767);
        }
        return out;
    }

    private static double time(int bar, double beat) {
        double frac = beat - Math.floor(beat);
        if (Math.abs(frac - 0.5) < 1e-6) beat += SWING;
        return (bar * 4 + beat) * BEAT;
    }

    private static double midi(int note) {
        return 440 * Math.pow(2, (note - 69) / 12.0);
    }

    /** Adds a sample at frame i, wrapping past the end so the loop is seamless. */
    private static void add(float[] buf, int i, double v) {
        buf[i % buf.length] += (float) v;
    }

    // ---- instruments ------------------------------------------------------------------------

    /** Rhodes-like: FM with a bright attack that mellows, and a gentle auto-pan. */
    static void electricPiano(float[] l, float[] r, double at, double len, double f, double vol) {
        int start = (int) (at * RATE), n = (int) ((len + 0.35) * RATE);
        for (int i = 0; i < n; i++) {
            double t = i / (double) RATE;
            double env = Math.min(1, t / 0.004) * Math.exp(-t * 1.8) * release(t, len, 0.25);
            double index = 1.6 * Math.exp(-t * 7) + 0.3;
            double s = Math.sin(2 * Math.PI * f * t + index * Math.sin(2 * Math.PI * f * t))
                    + 0.12 * Math.exp(-t * 12) * Math.sin(2 * Math.PI * f * 14 * t);   // tine "tick"
            double pan = 0.5 + 0.25 * Math.sin(2 * Math.PI * 1.1 * (at + t));
            s *= env * vol;
            add(l, start + i, s * (1 - pan) * 1.4);
            add(r, start + i, s * pan * 1.4);
        }
    }

    static void bass(float[] l, float[] r, double at, double len, double f) {
        int start = (int) (at * RATE), n = (int) ((len + 0.12) * RATE);
        for (int i = 0; i < n; i++) {
            double t = i / (double) RATE;
            double env = Math.min(1, t / 0.006) * Math.exp(-t * 1.6) * release(t, len, 0.08);
            double s = Math.sin(2 * Math.PI * f * t) + 0.35 * Math.exp(-t * 5) * Math.sin(4 * Math.PI * f * t)
                    + 0.08 * Math.sin(6 * Math.PI * f * t);
            s *= env * 0.2;
            add(l, start + i, s);
            add(r, start + i, s);
        }
    }

    /** Vibraphone-like lead: soft mallet, motor tremolo, a little to the right. */
    static void vibes(float[] l, float[] r, double at, double len, double f) {
        int start = (int) (at * RATE), n = (int) ((len + 0.6) * RATE);
        for (int i = 0; i < n; i++) {
            double t = i / (double) RATE;
            double env = Math.min(1, t / 0.003) * Math.exp(-t * 1.1) * release(t, len, 0.45);
            double trem = 1 + 0.22 * Math.sin(2 * Math.PI * 5.2 * t);
            double s = Math.sin(2 * Math.PI * f * t) + 0.3 * Math.exp(-t * 9) * Math.sin(2 * Math.PI * f * 4 * t)
                    + 0.08 * Math.exp(-t * 20) * Math.sin(2 * Math.PI * f * 9.9 * t);
            s *= env * trem * 0.13;
            add(l, start + i, s * 0.42);
            add(r, start + i, s * 0.58);
        }
    }

    static void shaker(float[] l, float[] r, double at, double vol, Random noise) {
        int start = (int) (at * RATE), n = (int) (0.07 * RATE);
        double prev = 0;
        for (int i = 0; i < n; i++) {
            double t = i / (double) RATE;
            double w = noise.nextDouble() * 2 - 1, hp = w - prev;       // high-passed noise
            prev = w;
            double env = Math.min(1, t / 0.01) * Math.exp(-t * 55);
            add(l, start + i, hp * env * vol * 0.6);
            add(r, start + i, hp * env * vol * 0.35);
        }
    }

    static void rim(float[] l, float[] r, double at, Random noise) {
        int start = (int) (at * RATE), n = (int) (0.05 * RATE);
        for (int i = 0; i < n; i++) {
            double t = i / (double) RATE;
            double env = Math.exp(-t * 110);
            double s = (Math.sin(2 * Math.PI * 1750 * t) * 0.6 + (noise.nextDouble() * 2 - 1) * 0.4) * env * 0.05;
            add(l, start + i, s * 0.45);
            add(r, start + i, s * 0.55);
        }
    }

    static void kick(float[] l, float[] r, double at) {
        int start = (int) (at * RATE), n = (int) (0.25 * RATE);
        double phase = 0;
        for (int i = 0; i < n; i++) {
            double t = i / (double) RATE;
            double f = 48 + 70 * Math.exp(-t * 30);
            phase += 2 * Math.PI * f / RATE;
            double s = Math.sin(phase) * Math.exp(-t * 14) * 0.16;
            add(l, start + i, s);
            add(r, start + i, s);
        }
    }

    /** 1 while the note is held, then fades over {@code rel} seconds. */
    private static double release(double t, double len, double rel) {
        return t <= len ? 1 : Math.max(0, 1 - (t - len) / rel);
    }

    // ---- room -------------------------------------------------------------------------------

    /** A small, soft room (Schroeder style: combs then all-passes), applied around the loop. */
    static void reverb(float[] l, float[] r) {
        int n = l.length;
        float[] wetL = new float[n], wetR = new float[n];
        room(l, wetL, 0);
        room(r, wetR, 23);
        for (int i = 0; i < n; i++) {
            l[i] += wetL[i] * 0.22f;
            r[i] += wetR[i] * 0.22f;
        }
    }

    private static void room(float[] in, float[] out, int spread) {
        int[] combs = {1557, 1617, 1491, 1422};
        int[] passes = {225, 556};
        int n = in.length;
        // run twice around the loop so the tail from the end rings into the start
        for (int c : combs) {
            int d = c + spread;
            float[] buf = new float[d];
            float store = 0;
            int p = 0;
            for (int pass = 0; pass < 2; pass++) {
                for (int i = 0; i < n; i++) {
                    float y = buf[p];
                    store = y * 0.75f + store * 0.25f;         // damping
                    buf[p] = in[i] + store * 0.8f;
                    if (++p >= d) p = 0;
                    if (pass == 1) out[i] += y * 0.25f;
                }
            }
        }
        for (int a : passes) {
            int d = a + spread;
            float[] buf = new float[d];
            int p = 0;
            float[] src = out.clone();
            for (int pass = 0; pass < 2; pass++) {
                for (int i = 0; i < n; i++) {
                    float b = buf[p];
                    float y = -src[i] + b;
                    buf[p] = src[i] + b * 0.5f;
                    if (++p >= d) p = 0;
                    if (pass == 1) out[i] = y;
                }
            }
        }
    }
}
