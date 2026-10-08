package wiiuu.ui;

import java.util.Random;

/**
 * The music for {@link IdleStudio}: the Future House track WII-UU builds, synthesized to the
 * sketch's timeline (seconds from its start, 128 BPM, beats on multiples of 60/128 s). Each part
 * comes in when its pattern appears in the playlist, the music stops while the piano roll is open,
 * comes back with the "Mario" lead (an original bouncy tune: the one drawn in the piano roll, not
 * Nintendo's), builds up with a snare roll and a riser, drops, and stops for the export. Then the
 * door, the sirens, the stamp and one lonely note. Mono, at {@link MenuAudio#RATE}.
 */
final class StudioTrack {
    private static final int RATE = MenuAudio.RATE;
    private static final double BEAT = 60.0 / 128, BAR = BEAT * 4, LENGTH = 58;

    // when the parts come in (the playlist's TRACK_AT), when the music pauses and comes back
    static final double KICK = 2.2, CLAP = 4.4, HATS = 5.6, SUB = 7.6, SAW = 9.4, CHOPS = 11.6,
            PAUSE = 15.5, BACK = 19.0, BUILD = 22, GAP = 25.55, DROP = 26, STOP = 29.5;

    private final float[] mix = new float[(int) (LENGTH * RATE)];
    private final float[] duck = new float[mix.length];       // the sidechain: dips on every kick
    private final Random random = new Random(7);

    private StudioTrack() {}

    static short[] render() {
        StudioTrack s = new StudioTrack();
        s.build();
        return s.toPcm();
    }

    /** A, F, C, G (minor, then bright), one bar each, counted from the start. */
    private static final double[][] CHORDS = {
            {220.00, 261.63, 329.63}, {174.61, 220.00, 261.63}, {261.63, 329.63, 392.00}, {196.00, 246.94, 293.66}};
    private static final double[] ROOTS = {55.00, 43.65, 65.41, 49.00};

    private static int chord(double t) {
        return (int) (t / BAR) % 4;
    }

    /** First beat at or after t. */
    private static double onBeat(double t) {
        return Math.ceil(t / BEAT - 1e-6) * BEAT;
    }

    private static boolean playing(double t) {
        return t >= KICK && t < PAUSE || t >= BACK && t < GAP || t >= DROP && t < STOP;
    }

    private void build() {
        java.util.Arrays.fill(duck, 1f);
        // the beat: kick on every beat (eighths, then sixteenths in the build-up), the sidechain with it
        for (double t = onBeat(KICK); t < STOP; t += BEAT) {
            if (!playing(t)) continue;
            if (t >= BUILD && t < GAP) {
                double step = t < 24 ? BEAT / 2 : BEAT / 4;
                for (double k = t; k < t + BEAT - 1e-6; k += step) kick(k, 0.8);
            } else {
                kick(t, 1.0);
                sidechain(t);
            }
        }
        // clap on 2 and 4, hats on the off-beats
        for (double t = onBeat(CLAP); t < STOP; t += BEAT) {
            if (!playing(t) || t >= BUILD && t < DROP) continue;
            if (Math.round(t / BEAT) % 2 == 1) clap(t);
        }
        for (double t = onBeat(HATS) + BEAT / 2; t < STOP; t += BEAT / 2) {
            if (!playing(t) || t >= BUILD && t < DROP) continue;
            boolean off = Math.round(t / (BEAT / 2)) % 2 == 1;
            hat(t, off ? 0.16 : 0.06);
        }
        // sub bass on the off-beats (bouncing octaves after the drop)
        for (double t = onBeat(SUB); t < STOP; t += BEAT / 4) {
            if (!playing(t) || t >= BUILD && t < DROP) continue;
            int sixteenth = (int) Math.round(t / (BEAT / 4)) % 4;
            double root = ROOTS[chord(t)];
            if (t >= DROP) {
                if (sixteenth != 0) bass(t, root * (sixteenth == 2 ? 2 : 1), BEAT / 4 * 0.9, 0.34);
            } else if (sixteenth == 2) bass(t, root, BEAT / 2 * 0.9, 0.38);
        }
        // saw chords: off-beat stabs, pumping
        for (double t = onBeat(SAW) + BEAT / 2; t < STOP; t += BEAT) {
            if (!playing(t) || t >= BUILD && t < DROP) continue;
            for (double f : CHORDS[chord(t)]) saw(t, f * (t >= DROP ? 2 : 1), BEAT * 0.45, t >= DROP ? 0.07 : 0.05);
        }
        // vocal chops: "oh", "ah" on the chord tones
        for (double t = onBeat(CHOPS); t < STOP; t += BEAT / 2) {
            if (!playing(t) || t >= BUILD && t < DROP) continue;
            int step = (int) Math.round(t / (BEAT / 2)) % 8;
            if (step == 3 || step == 6 || step == 7) chop(t, CHORDS[chord(t)][step % 3] * 2, BEAT * 0.4, step);
        }
        // the "Mario" lead: the bouncy tune from the piano roll, square wave, two bars round
        for (double bar = onBeat(BACK); bar < STOP; bar += BAR * 2) {
            for (int[] n : TUNE) {
                double t = bar + n[0] * BEAT / 4;
                if (t >= STOP || !playing(t)) continue;
                square(t, SCALE[n[1]], BEAT / 4 * 1.5, t >= DROP ? 0.11 : 0.08);
            }
        }
        // the build-up: snare roll speeding up, a riser, then a breath before the drop
        for (double t = BUILD; t < GAP; ) {
            double step = t < 23.4 ? BEAT : t < 24.4 ? BEAT / 2 : t < 25 ? BEAT / 4 : BEAT / 8;
            snare(t, 0.12 + 0.2 * (t - BUILD) / (GAP - BUILD));
            t += step;
        }
        riser(BUILD, GAP);
        crash(DROP, 0.25);
        // the rest of the story
        for (double b : new double[]{37.3, 37.65, 38.0, 38.3}) thud(b, 0.55);
        crash(38.5, 0.35);
        thud(38.5, 0.8);
        sirens(35.8, 49.8);
        thud(42.35, 0.9);
        click(42.35, 0.4);
        square(53.6, SCALE[3], 0.35, 0.12);
    }

    /** {sixteenth, scale step}: the notes drawn in the piano roll. */
    private static final int[][] TUNE = {{0, 3}, {1, 3}, {3, 3}, {5, 5}, {6, 3}, {8, 1}, {10, 8}, {12, 5}, {14, 2}, {16, 6},
            {17, 4}, {19, 3}, {21, 7}, {22, 6}, {24, 5}, {26, 4}, {27, 3}, {29, 6}, {31, 8}};
    private static final double[] SCALE = {440.00, 493.88, 523.25, 587.33, 659.25, 783.99, 880.00, 987.77, 1046.50};

    // ---- instruments --------------------------------------------------------------------------------

    private void kick(double at, double gain) {
        int s = (int) (at * RATE), n = (int) (0.32 * RATE);
        double phase = 0;
        for (int i = 0; i < n && s + i < mix.length; i++) {
            double t = i / (double) RATE;
            double f = 45 + 110 * Math.exp(-t * 30);
            phase += 2 * Math.PI * f / RATE;
            double env = Math.exp(-t * 9) * Math.min(1, t * 2000);
            mix[s + i] += (float) (Math.sin(phase) * env * 0.9 * gain + (i < 90 ? (random.nextDouble() - 0.5) * 0.15 * gain * (1 - i / 90.0) : 0));
        }
    }

    private void sidechain(double at) {
        int s = (int) (at * RATE), n = (int) (BEAT * RATE);
        for (int i = 0; i < n && s + i < duck.length; i++) {
            double t = i / (double) RATE;
            duck[s + i] = (float) Math.min(duck[s + i], 1 - 0.85 * Math.exp(-t * 9));
        }
    }

    private void clap(double at) {
        int s = (int) (at * RATE), n = (int) (0.22 * RATE);
        double lp = 0, prev = 0;
        for (int i = 0; i < n && s + i < mix.length; i++) {
            double t = i / (double) RATE;
            double burst = t < 0.03 ? (Math.floor(t / 0.01) % 2 == 0 ? 1 : 0.4) : Math.exp(-(t - 0.03) * 18);
            double x = random.nextDouble() - 0.5;
            double hp = x - prev;                                   // a bit brighter
            prev = x;
            lp += (hp - lp) * 0.45;
            mix[s + i] += (float) (lp * burst * 0.55);
        }
    }

    private void snare(double at, double gain) {
        int s = (int) (at * RATE), n = (int) (0.12 * RATE);
        for (int i = 0; i < n && s + i < mix.length; i++) {
            double t = i / (double) RATE;
            double env = Math.exp(-t * 28);
            mix[s + i] += (float) (((random.nextDouble() - 0.5) * 0.8 + Math.sin(2 * Math.PI * 190 * t) * 0.4) * env * gain);
        }
    }

    private void hat(double at, double gain) {
        int s = (int) (at * RATE), n = (int) (0.05 * RATE);
        double prev = 0;
        for (int i = 0; i < n && s + i < mix.length; i++) {
            double x = random.nextDouble() - 0.5, hp = x - prev;
            prev = x;
            mix[s + i] += (float) (hp * Math.exp(-i / (double) RATE * 70) * gain);
        }
    }

    private void bass(double at, double f, double len, double gain) {
        int s = (int) (at * RATE), n = (int) (len * RATE);
        for (int i = 0; i < n && s + i < mix.length; i++) {
            double t = i / (double) RATE;
            double env = Math.min(1, t * 300) * Math.min(1, (len - t) * 60);
            double x = Math.sin(2 * Math.PI * f * t) + 0.25 * Math.sin(2 * Math.PI * f * 2 * t);
            mix[s + i] += (float) (x * env * gain * duck[s + i]);
        }
    }

    private void saw(double at, double f, double len, double gain) {
        int s = (int) (at * RATE), n = (int) (len * RATE);
        double lp = 0;
        for (int i = 0; i < n && s + i < mix.length; i++) {
            double t = i / (double) RATE;
            double x = 0;
            for (double d : new double[]{-0.006, 0, 0.006}) x += ((f * (1 + d) * t) % 1) * 2 - 1;   // three detuned saws
            lp += (x / 3 - lp) * 0.18;
            double env = Math.min(1, t * 400) * Math.exp(-t * 5);
            mix[s + i] += (float) (lp * env * gain * duck[s + i]);
        }
    }

    private void chop(double at, double f, double len, int vowel) {
        int s = (int) (at * RATE), n = (int) (len * RATE);
        double[] amps = vowel % 2 == 0 ? new double[]{1, 0.7, 0.5, 0.15, 0.1} : new double[]{1, 0.3, 0.6, 0.4, 0.2};
        for (int i = 0; i < n && s + i < mix.length; i++) {
            double t = i / (double) RATE;
            double vib = 1 + 0.01 * Math.sin(2 * Math.PI * 6 * t);
            double x = 0;
            for (int h = 0; h < amps.length; h++) x += amps[h] * Math.sin(2 * Math.PI * f * (h + 1) * vib * t);
            double env = Math.min(1, t * 120) * Math.min(1, (len - t) * 40);
            mix[s + i] += (float) (x * env * 0.035 * duck[s + i]);
        }
    }

    private void square(double at, double f, double len, double gain) {
        int s = (int) (at * RATE), n = (int) (len * RATE);
        for (int i = 0; i < n && s + i < mix.length; i++) {
            double t = i / (double) RATE;
            double x = ((f * t) % 1) < 0.5 ? 1 : -1;
            double env = Math.min(1, t * 500) * Math.min(1, (len - t) * 80);
            mix[s + i] += (float) (x * env * gain);
        }
    }

    private void riser(double from, double to) {
        int s = (int) (from * RATE), n = (int) ((to - from) * RATE);
        double lp = 0, phase = 0;
        for (int i = 0; i < n && s + i < mix.length; i++) {
            double k = i / (double) n;
            double cut = 0.01 + 0.5 * k * k;
            lp += ((random.nextDouble() - 0.5) - lp) * cut;
            phase += 2 * Math.PI * (200 + 1600 * k * k) / RATE;
            mix[s + i] += (float) ((lp * 0.5 + Math.sin(phase) * 0.06) * k);
        }
    }

    private void crash(double at, double gain) {
        int s = (int) (at * RATE), n = (int) (1.6 * RATE);
        double prev = 0;
        for (int i = 0; i < n && s + i < mix.length; i++) {
            double x = random.nextDouble() - 0.5, hp = x - prev;
            prev = x;
            mix[s + i] += (float) (hp * Math.exp(-i / (double) RATE * 2.5) * gain);
        }
    }

    private void thud(double at, double gain) {
        int s = (int) (at * RATE), n = (int) (0.25 * RATE);
        double lp = 0;
        for (int i = 0; i < n && s + i < mix.length; i++) {
            double t = i / (double) RATE;
            lp += ((random.nextDouble() - 0.5) - lp) * 0.05;
            double env = Math.exp(-t * 16);
            mix[s + i] += (float) ((Math.sin(2 * Math.PI * 70 * t) * 0.8 + lp * 2) * env * gain);
        }
    }

    private void click(double at, double gain) {
        int s = (int) (at * RATE);
        for (int i = 0; i < 300 && s + i < mix.length; i++) mix[s + i] += (float) ((random.nextDouble() - 0.5) * gain * (1 - i / 300.0));
    }

    /** Far-off two-tone sirens, fading in and out. */
    private void sirens(double from, double to) {
        int s = (int) (from * RATE), n = (int) ((to - from) * RATE);
        double phase = 0;
        for (int i = 0; i < n && s + i < mix.length; i++) {
            double t = i / (double) RATE;
            double f = ((int) (t / 0.5)) % 2 == 0 ? 720 : 960;
            phase += 2 * Math.PI * f / RATE;
            double env = Math.min(1, t / 1.5) * Math.min(1, (to - from - t) / 1.0);
            mix[s + i] += (float) (Math.sin(phase) * 0.035 * env);
        }
    }

    private short[] toPcm() {
        // everything before the drop a little quieter, so the drop hits
        for (int i = (int) (KICK * RATE); i < (int) (DROP * RATE); i++) mix[i] *= 0.7f;
        float peak = 0.01f;
        for (float v : mix) peak = Math.max(peak, Math.abs(v));
        float g = 0.9f / peak;
        short[] out = new short[mix.length];
        for (int i = 0; i < mix.length; i++) out[i] = (short) Math.round(Math.max(-1, Math.min(1, mix[i] * g)) * 32767);
        return out;
    }
}
