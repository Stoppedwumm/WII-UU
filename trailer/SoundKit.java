package wiiuu.ui;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

/**
 * A sound kit for WII-UU trailers: stingers (short musical logos) and sound effects, all
 * synthesized from scratch with WII-UU's own sounds where it has them (the boot chime, the menu
 * blips, the menu music's instruments and room). Everything is in F major, the key of WII-UU's
 * menu theme, so stingers fit under or after its music; the house sting is at 128 BPM like the
 * Extended Mix. Writes 44.1 kHz 16-bit stereo WAVs; trailer/render-soundkit.sh converts and zips.
 *
 * <pre>usage: SoundKit outdir</pre>
 */
public final class SoundKit {
    static final int RATE = MenuAudio.RATE;
    private static final Random NOISE = new Random(21);

    public static void main(String[] args) throws IOException {
        Path out = Path.of(args[0]);
        Path st = out.resolve("Stingers"), fx = out.resolve("SFX"), ui = out.resolve("UI");
        Files.createDirectories(st);
        Files.createDirectories(fx);
        Files.createDirectories(ui);

        write(st.resolve("WII-UU Stinger - Boot Chime.wav"), bootChime());
        write(st.resolve("WII-UU Stinger - Logo Impact.wav"), logoImpact());
        write(st.resolve("WII-UU Stinger - Menu Jazz.wav"), menuJazz());
        write(st.resolve("WII-UU Stinger - 8-bit.wav"), eightBit());
        write(st.resolve("WII-UU Stinger - House Drop.wav"), houseDrop());
        write(st.resolve("WII-UU Stinger - Outro.wav"), outro());
        write(st.resolve("WII-UU Stinger - Swoosh Bell.wav"), swooshBell());

        write(fx.resolve("Whoosh Short.wav"), whoosh(0.55, 500, 7000, 1));
        write(fx.resolve("Whoosh Long.wav"), whoosh(1.5, 300, 9000, -1));
        write(fx.resolve("Whoosh Down.wav"), whoosh(0.9, 9000, 400, 1));
        write(fx.resolve("Riser 4s.wav"), riser(4));
        write(fx.resolve("Riser 8s.wav"), riser(8));
        write(fx.resolve("Downlifter.wav"), downlifter());
        write(fx.resolve("Reverse Swell.wav"), reverseSwell());
        write(fx.resolve("Impact Boom.wav"), impact(1.0, 2.8));
        write(fx.resolve("Impact Hit.wav"), hit());
        write(fx.resolve("Sub Drop.wav"), subDrop());
        write(fx.resolve("Shimmer.wav"), shimmer());
        write(fx.resolve("Glitch Stutter.wav"), glitch());
        write(fx.resolve("Tape Stop.wav"), tapeStop());
        write(fx.resolve("Pop.wav"), pop());
        write(fx.resolve("Snare Roll 2 Bars.wav"), snareRoll());

        // the menu's own blips (Sfx), at the same pitches and lengths
        write(ui.resolve("UI Move.wav"), blips(new double[]{1320}, 0.035, 0.6));
        write(ui.resolve("UI Select.wav"), blips(new double[]{880, 1320}, 0.07, 0.6));
        write(ui.resolve("UI Back.wav"), blips(new double[]{990, 660}, 0.06, 0.6));
        write(ui.resolve("UI Bump.wav"), blips(new double[]{220}, 0.05, 0.6));
        write(ui.resolve("UI Chime.wav"), blips(new double[]{1047, 1319, 1568}, 0.09, 0.6));
        write(ui.resolve("UI Phone Paired.wav"), paired());
        write(ui.resolve("UI Notification.wav"), notification());
    }

    // ---- stingers ---------------------------------------------------------------------------

    private static float[][] bootChime() {
        float[][] b = buf(6);
        short[] c = Sfx.bootPcm();
        for (int i = 0; i < c.length; i++) {
            b[0][i] += c[i] / 32768f;
            b[1][i] += c[i] / 32768f;
        }
        room(b, 0.6f);
        return b;
    }

    /** A boom and crash, WII-UU's chime on top, and a shimmering F major pad. */
    private static float[][] logoImpact() {
        float[][] b = buf(8);
        impact(b, 0, 1.0, 2.8);
        mixMono(b, Sfx.bootPcm(), 0.02, 0.9);
        pad(b, 0.02, 4.5, new int[]{41, 53, 60, 64, 67, 72}, 0.05);
        shimmer(b, 0.15, 1.6, 0.06);
        room(b, 0.5f);
        return b;
    }

    /** The menu's sound: electric piano and vibraphone, the theme's opening notes, a bass and soft drums. */
    private static float[][] menuJazz() {
        float[][] b = buf(9);
        double beat = 0.6;                                                 // 100 BPM, the menu theme's tempo
        for (double[] hit : new double[][]{{0, 0.9}, {1.5, 0.45}, {2.5, 2.6}}) {
            int[] chord = hit[0] < 2 ? new int[]{57, 60, 64, 67} : new int[]{57, 60, 64, 67, 69};   // Fmaj9
            for (int k = 0; k < chord.length; k++) {
                MenuMusic.electricPiano(b[0], b[1], hit[0] * beat + k * 0.008, hit[1] * beat, hz(chord[k]), hit[0] == 0 ? 0.075 : 0.06);
            }
        }
        double[][] vibes = {{0, 69, 0.5}, {0.5, 72, 0.5}, {1, 76, 1}, {2.07, 79, 0.43}, {2.5, 81, 3}};  // A C E G A
        for (double[] v : vibes) MenuMusic.vibes(b[0], b[1], v[0] * beat, v[2] * beat, hz((int) v[1]));
        MenuMusic.bass(b[0], b[1], 0, 1.4 * beat, hz(41));
        MenuMusic.bass(b[0], b[1], 1.5 * beat, 0.45 * beat, hz(48));
        MenuMusic.bass(b[0], b[1], 2.5 * beat, 2.5 * beat, hz(41));
        MenuMusic.kick(b[0], b[1], 0);
        MenuMusic.kick(b[0], b[1], 2.5 * beat);
        for (int e = 0; e < 6; e++) MenuMusic.shaker(b[0], b[1], e * 0.5 * beat, e % 2 == 1 ? 0.05 : 0.03, NOISE);
        MenuMusic.rim(b[0], b[1], beat, NOISE);
        room(b, 1f);
        return b;
    }

    /** A pulse-wave run up two octaves of F major, then the chord with a triangle bass. */
    private static float[][] eightBit() {
        float[][] b = buf(4);
        int[] run = {65, 69, 72, 77, 81, 84, 89};
        double step = 0.06;
        for (int i = 0; i < run.length; i++) pulse(b, i * step, step * 0.9, run[i], 0.25, 0.16);
        double at = run.length * step + 0.04;
        for (int n : new int[]{77, 81, 84}) pulse(b, at, 0.9, n, 0.5, 0.09);
        pulse(b, at, 0.9, 89, 0.125, 0.12);
        triangle(b, at, 0.9, 53, 0.3);
        noiseHit(b, at, 0.15, 0.25);
        return b;
    }

    /** One bar of riser and snare roll at 128 BPM into the drop: kick, sub and a saw chord stab. */
    private static float[][] houseDrop() {
        float[][] b = buf(8);
        double beat = 60 / 128.0, bar = 4 * beat;
        sweep(b, 0, bar, 400, 11000, 0.05, 0.6, 1.4);
        for (int k = 0; k < 8; k++) snare(b, k * beat / 2, 0.3 + 0.05 * k);
        for (int k = 0; k < 8; k++) snare(b, bar / 2 + k * beat / 8, 0.7 + 0.06 * k);
        double drop = bar;
        kick(b, drop, 0.9);
        sub(b, drop, 1.2, hz(29), 0.5);
        for (int n : new int[]{53, 57, 60, 64, 69}) saws(b, drop, 0.55, n, 0.07);
        impact(b, drop, 0.6, 1.8);
        noiseSweep(b, drop, 1.6, 9000, 600, 0.12, 0);
        room(b, 0.4f);
        return b;
    }

    /** A gentle ending: vibraphone falling through Fmaj9 over a long electric piano chord. */
    private static float[][] outro() {
        float[][] b = buf(9);
        int[] fall = {79, 76, 72, 69, 65};
        for (int i = 0; i < fall.length; i++) MenuMusic.vibes(b[0], b[1], i * 0.22, i == fall.length - 1 ? 2.5 : 0.4, hz(fall[i]));
        for (int k = 0; k < 4; k++) MenuMusic.electricPiano(b[0], b[1], 1.0 + k * 0.012, 2.6, hz(new int[]{57, 60, 64, 67}[k]), 0.07);
        MenuMusic.bass(b[0], b[1], 1.0, 2.6, hz(41));
        mixMono(b, Sfx.bootPcm(), 1.0, 0.35);
        room(b, 1.2f);
        return b;
    }

    /** A quick whoosh that lands on a bell: for title cards. */
    private static float[][] swooshBell() {
        float[][] b = buf(5);
        noiseSweep(b, 0, 0.45, 600, 8000, 0, 0.3);
        bell(b, 0.42, 1568, 0.25);
        bell(b, 0.42, 2093, 0.12);
        room(b, 0.6f);
        return b;
    }

    // ---- effects ----------------------------------------------------------------------------

    private static float[][] whoosh(double len, double f0, double f1, int pan) {
        float[][] b = buf(len + 1);
        int n = (int) (len * RATE);
        Svf l = new Svf(), r = new Svf();
        for (int i = 0; i < n; i++) {
            double p = i / (double) n, f = f0 * Math.pow(f1 / f0, p), g = Svf.g(f);
            double env = Math.sin(Math.PI * Math.pow(p, 0.8));
            double side = 0.5 + 0.45 * pan * (p * 2 - 1);                  // moves across
            double x = l.bp(NOISE.nextDouble() * 2 - 1, g, 1 / 2.0), y = r.bp(NOISE.nextDouble() * 2 - 1, g, 1 / 2.0);
            b[0][i] += x * env * 0.8 * (1 - side) * 2;
            b[1][i] += y * env * 0.8 * side * 2;
        }
        room(b, 0.3f);
        return b;
    }

    private static float[][] riser(double len) {
        float[][] b = buf(len + 1);
        sweep(b, 0, len, 300, 12000, 0, 0.35, 1.6);
        // a saw climbing two octaves, opening up as it goes
        int n = (int) (len * RATE);
        double phase = 0, lp = 0;
        for (int i = 0; i < n; i++) {
            double p = i / (double) n;
            phase += 55 * Math.pow(4, p) / RATE;
            double saw = 2 * (phase % 1) - 1;
            lp += (0.01 + 0.25 * p * p) * (saw - lp);
            double v = lp * 0.18 * p * Math.min(1, (1 - p) * 40);
            b[0][i] += v;
            b[1][i] += v;
        }
        room(b, 0.3f);
        return b;
    }

    private static float[][] downlifter() {
        float[][] b = buf(4);
        noiseSweep(b, 0, 2.8, 10000, 200, 0.35, 0);
        int n = (int) (2.8 * RATE);
        double phase = 0;
        for (int i = 0; i < n; i++) {
            double p = i / (double) n;
            phase += 220 * Math.pow(0.125, p) / RATE;
            double v = Math.sin(2 * Math.PI * phase) * 0.15 * (1 - p);
            b[0][i] += v;
            b[1][i] += v;
        }
        room(b, 0.4f);
        return b;
    }

    /** A cymbal-like swell that grows to the cut (a reversed crash). */
    private static float[][] reverseSwell() {
        float[][] b = buf(2.2);
        int n = (int) (2 * RATE);
        Svf l = new Svf(), r = new Svf();
        double g = Svf.g(5000);
        for (int i = 0; i < n; i++) {
            double t = (n - i) / (double) RATE;                            // time to the end
            double env = Math.exp(-t * 2.2) * 0.5;
            l.run(NOISE.nextDouble() * 2 - 1, g, 1 / 0.7);
            r.run(NOISE.nextDouble() * 2 - 1, g, 1 / 0.7);
            b[0][i] += l.hp * env;
            b[1][i] += r.hp * env;
        }
        return b;
    }

    private static float[][] impact(double vol, double len) {
        float[][] b = buf(len + 1);
        impact(b, 0, vol, len);
        room(b, 0.4f);
        return b;
    }

    private static float[][] hit() {
        float[][] b = buf(1.5);
        kick(b, 0, 1);
        snare(b, 0, 0.6);
        noiseHit(b, 0, 0.4, 0.3);
        room(b, 0.5f);
        return b;
    }

    private static float[][] subDrop() {
        float[][] b = buf(4);
        int n = (int) (3.6 * RATE);
        double phase = 0;
        for (int i = 0; i < n; i++) {
            double t = i / (double) RATE;
            phase += (28 + 140 * Math.exp(-t * 2.5)) / RATE;
            double v = Math.tanh(1.6 * Math.sin(2 * Math.PI * phase)) * Math.exp(-t * 1.3) * 0.8;
            b[0][i] += v;
            b[1][i] += v;
        }
        return b;
    }

    private static float[][] shimmer() {
        float[][] b = buf(5);
        shimmer(b, 0, 2.2, 0.12);
        room(b, 1f);
        return b;
    }

    /** A chord chopped into ever shorter repeats, bit-crushed. */
    private static float[][] glitch() {
        float[][] b = buf(1.6);
        float[][] chord = buf(0.3);
        for (int n : new int[]{53, 60, 64, 69}) saws(chord, 0, 0.25, n, 0.12);
        double at = 0;
        double[] sizes = {0.12, 0.12, 0.06, 0.06, 0.06, 0.03, 0.03, 0.03, 0.03, 0.015, 0.015, 0.015, 0.015, 0.015, 0.015};
        for (int k = 0; k < sizes.length; k++) {
            int n = (int) (sizes[k] * RATE), s = (int) (at * RATE);
            double crush = k < 6 ? 1 : 1 + k;                               // fewer levels as it speeds up
            for (int i = 0; i < n; i++) {
                for (int c = 0; c < 2; c++) {
                    double v = chord[c][i % chord[c].length];
                    v = Math.round(v * 32 / crush) * crush / 32;
                    b[c][s + i] += v * Math.min(1, (n - i) / 30.0);
                }
            }
            at += sizes[k];
        }
        return b;
    }

    /** A chord whose tape slows to a stop. */
    private static float[][] tapeStop() {
        float[][] b = buf(2.2);
        double len = 1.2, speed = 1, pos = 0;
        double[] phase = new double[5];
        int[] notes = {41, 53, 60, 64, 69};
        int n = (int) (len * RATE);
        for (int i = 0; i < n; i++) {
            double p = i / (double) n;
            speed = Math.pow(1 - p, 1.6);
            double v = 0;
            for (int k = 0; k < notes.length; k++) {
                phase[k] += hz(notes[k]) * speed / RATE;
                v += (2 * (phase[k] % 1) - 1) * (k == 0 ? 0.25 : 0.1);
            }
            pos += speed;
            v = Math.tanh(v * 1.5) * 0.6 * Math.min(1, i / 200.0);
            b[0][i] += v;
            b[1][i] += v;
        }
        return lowpass(b, 3000);
    }

    private static float[][] pop() {
        float[][] b = buf(0.5);
        int n = (int) (0.12 * RATE);
        double phase = 0;
        for (int i = 0; i < n; i++) {
            double t = i / (double) RATE;
            phase += (500 + 900 * (1 - Math.exp(-t * 60))) / RATE;
            double v = Math.sin(2 * Math.PI * phase) * Math.exp(-t * 35) * 0.7;
            b[0][i] += v;
            b[1][i] += v;
        }
        return b;
    }

    private static float[][] snareRoll() {
        float[][] b = buf(4.5);
        double beat = 60 / 128.0, bar = 4 * beat;
        for (int k = 0; k < 8; k++) snare(b, k * beat / 2, 0.15 + 0.02 * k);
        for (int k = 0; k < 8; k++) snare(b, bar + k * beat / 4, 0.32 + 0.02 * k);
        for (int k = 0; k < 16; k++) snare(b, bar + bar / 2 + k * beat / 8, 0.48 + 0.025 * k);
        room(b, 0.3f);
        return b;
    }

    private static float[][] blips(double[] notes, double noteSeconds, double volume) {
        float[][] b = buf(notes.length * noteSeconds + 0.3);
        int per = (int) (RATE * noteSeconds), i = 0;
        for (double f : notes) {
            for (int n = 0; n < per; n++) {
                double t = n / (double) RATE;
                double env = Math.min(1, n / (RATE * 0.004)) * Math.exp(-t * 38);
                double s = (Math.sin(2 * Math.PI * f * t) + 0.25 * Math.sin(4 * Math.PI * f * t)) * env * volume;
                b[0][i] += s;
                b[1][i] += s;
                i++;
            }
        }
        return b;
    }

    /** Two bells, a fifth apart: a phone has joined as the GamePad. */
    private static float[][] paired() {
        float[][] b = buf(2.5);
        bell(b, 0, 1047, 0.3);
        bell(b, 0.12, 1568, 0.3);
        room(b, 0.5f);
        return b;
    }

    private static float[][] notification() {
        float[][] b = buf(2);
        bell(b, 0, 1319, 0.25);
        bell(b, 0.09, 1047, 0.22);
        room(b, 0.4f);
        return b;
    }

    // ---- building blocks --------------------------------------------------------------------

    private static float[][] buf(double seconds) {
        int n = (int) (seconds * RATE) + RATE * 3;                          // room for the reverb tail
        return new float[][]{new float[n], new float[n]};
    }

    private static double hz(double midi) {
        return 440 * Math.pow(2, (midi - 69) / 12.0);
    }

    private static void add(float[][] b, int i, double l, double r) {
        if (i < 0 || i >= b[0].length) return;
        b[0][i] += (float) l;
        b[1][i] += (float) r;
    }

    private static void mixMono(float[][] b, short[] pcm, double at, double vol) {
        int s = (int) (at * RATE);
        for (int i = 0; i < pcm.length; i++) add(b, s + i, pcm[i] / 32768.0 * vol, pcm[i] / 32768.0 * vol);
    }

    /** The menu music's room, with {@code wet} as much reverb as there (1 = the same). */
    private static void room(float[][] b, float wet) {
        float[] l = b[0].clone(), r = b[1].clone();
        MenuMusic.reverb(l, r);                                             // adds 22 % wet
        for (int i = 0; i < l.length; i++) {
            b[0][i] += (l[i] - b[0][i]) * wet * 1.6f;
            b[1][i] += (r[i] - b[1][i]) * wet * 1.6f;
        }
    }

    private static void impact(float[][] b, double at, double vol, double len) {
        int s = (int) (at * RATE), n = (int) (len * RATE);
        double phase = 0, lp = 0;
        for (int i = 0; i < n; i++) {
            double t = i / (double) RATE;
            phase += 2 * Math.PI * (34 + 90 * Math.exp(-t * 9)) / RATE;
            double boom = Math.sin(phase) * Math.exp(-t * 1.6) * 0.9;
            lp += 0.08 * ((NOISE.nextDouble() * 2 - 1) - lp);
            double crash = lp * Math.exp(-t * 2.2) * 1.2 + (NOISE.nextDouble() * 2 - 1) * Math.exp(-t * 9) * 0.25;
            add(b, s + i, (boom + crash) * vol * 0.6, (boom + crash * 0.9) * vol * 0.6);
        }
    }

    private static void kick(float[][] b, double at, double vol) {
        int s = (int) (at * RATE), n = (int) (0.4 * RATE);
        double phase = 0;
        for (int i = 0; i < n; i++) {
            double t = i / (double) RATE;
            phase += 2 * Math.PI * (46 + 140 * Math.exp(-t * 28)) / RATE;
            double v = Math.sin(phase) * Math.exp(-t * 7);
            if (t < 0.003) v += (1 - t / 0.003) * 0.4 * Math.sin(2 * Math.PI * 3000 * t);
            v = Math.tanh(1.5 * v) * 0.6 * vol;
            add(b, s + i, v, v);
        }
    }

    private static void snare(float[][] b, double at, double vol) {
        int s = (int) (at * RATE), n = (int) (0.16 * RATE);
        Svf f = new Svf();
        double g = Svf.g(2200);
        for (int i = 0; i < n; i++) {
            double t = i / (double) RATE;
            double v = (f.bp(NOISE.nextDouble() * 2 - 1, g, 1 / 0.8) * 1.6 * Math.exp(-t * 25)
                    + Math.sin(2 * Math.PI * 200 * t) * Math.exp(-t * 35) * 0.5) * vol * 0.5;
            add(b, s + i, v, v);
        }
    }

    private static void noiseHit(float[][] b, double at, double len, double vol) {
        int s = (int) (at * RATE), n = (int) (len * RATE);
        Svf l = new Svf(), r = new Svf();
        double g = Svf.g(6000);
        for (int i = 0; i < n; i++) {
            double t = i / (double) RATE, env = Math.exp(-t * 18) * vol;
            l.run(NOISE.nextDouble() * 2 - 1, g, 1);
            r.run(NOISE.nextDouble() * 2 - 1, g, 1);
            add(b, s + i, l.hp * env, r.hp * env);
        }
    }

    private static void sub(float[][] b, double at, double len, double f, double vol) {
        int s = (int) (at * RATE), n = (int) (len * RATE);
        for (int i = 0; i < n; i++) {
            double t = i / (double) RATE, v = Math.sin(2 * Math.PI * f * t) * Math.min(1, t / 0.005) * Math.exp(-t * 1.5) * vol;
            add(b, s + i, v, v);
        }
    }

    /** Detuned saws through a closing low-pass: a house chord stab. */
    private static void saws(float[][] b, double at, double len, int note, double vol) {
        int s = (int) (at * RATE), n = (int) ((len + 0.1) * RATE);
        double[] ratio = {Math.pow(2, -14 / 1200.0), 1, Math.pow(2, 14 / 1200.0)}, phase = {0, 0.33, 0.66};
        Svf l = new Svf(), r = new Svf();
        double f = hz(note);
        for (int i = 0; i < n; i++) {
            double t = i / (double) RATE;
            double g = Svf.g(700 + 7000 * Math.exp(-t * 7));
            double sl = 0, sr = 0;
            for (int k = 0; k < 3; k++) {
                phase[k] = (phase[k] + f * ratio[k] / RATE) % 1;
                double v = 2 * phase[k] - 1;
                sl += v * (k == 2 ? 0.3 : 1);
                sr += v * (k == 0 ? 0.3 : 1);
            }
            double env = Math.min(1, t / 0.003) * (t <= len ? 0.5 + 0.5 * Math.exp(-t * 4) : Math.max(0, 1 - (t - len) / 0.1));
            add(b, s + i, Math.tanh(l.run(sl, g, 1) * 0.8) * env * vol, Math.tanh(r.run(sr, g, 1) * 0.8) * env * vol);
        }
    }

    private static void pulse(float[][] b, double at, double len, int note, double duty, double vol) {
        int s = (int) (at * RATE), n = (int) ((len + 0.03) * RATE);
        double f = hz(note), phase = 0;
        for (int i = 0; i < n; i++) {
            double t = i / (double) RATE;
            double v = (phase < duty ? 1 : -1) - (2 * duty - 1);
            phase = (phase + f / RATE) % 1;
            double env = Math.min(1, t / 0.003) * (t <= len ? 0.8 + 0.2 * Math.exp(-t * 10) : Math.max(0, 1 - (t - len) / 0.03));
            add(b, s + i, v * env * vol * 0.55, v * env * vol * 0.45);
        }
    }

    private static void triangle(float[][] b, double at, double len, int note, double vol) {
        int s = (int) (at * RATE), n = (int) ((len + 0.03) * RATE);
        double f = hz(note);
        for (int i = 0; i < n; i++) {
            double t = i / (double) RATE, p = (f * t) % 1;
            double v = (4 * Math.abs(p - 0.5) - 1) * vol * (t <= len ? 1 : Math.max(0, 1 - (t - len) / 0.03));
            add(b, s + i, v, v);
        }
    }

    private static void bell(float[][] b, double at, double f, double vol) {
        int s = (int) (at * RATE), n = (int) (1.6 * RATE);
        for (int i = 0; i < n; i++) {
            double t = i / (double) RATE;
            double env = Math.min(1, t / 0.003) * Math.exp(-t * 3);
            double v = (Math.sin(2 * Math.PI * f * t) + 0.35 * Math.exp(-t * 8) * Math.sin(2 * Math.PI * f * 2.76 * t)) * env * vol;
            add(b, s + i, v, v);
        }
    }

    /** Bells glittering up through F major, panned around. */
    private static void shimmer(float[][] b, double at, double len, double vol) {
        int[] notes = {77, 81, 84, 88, 89, 93, 96, 100, 101};
        for (int k = 0; k < notes.length; k++) {
            double t0 = at + len * 0.6 * k / notes.length, f = hz(notes[k]);
            int s = (int) (t0 * RATE), n = (int) (1.5 * RATE);
            double pan = 0.5 + 0.4 * Math.sin(k * 1.9);
            for (int i = 0; i < n; i++) {
                double t = i / (double) RATE;
                double v = Math.sin(2 * Math.PI * f * t) * Math.min(1, t / 0.002) * Math.exp(-t * 3.5) * vol;
                add(b, s + i, v * (1 - pan) * 2, v * pan * 2);
            }
        }
    }

    private static void pad(float[][] b, double at, double len, int[] notes, double vol) {
        int s = (int) (at * RATE), n = (int) (len * RATE);
        for (int k = 0; k < notes.length; k++) {
            double f = hz(notes[k]);
            for (int d = -1; d <= 1; d += 2) {
                double ff = f * Math.pow(2, d * 7 / 1200.0), phase = k * 0.13 + d * 0.21, lp = 0;
                for (int i = 0; i < n; i++) {
                    double p = i / (double) n;
                    phase += ff / RATE;
                    lp += 0.05 * ((2 * (phase % 1) - 1) - lp);
                    double env = Math.min(1, p / 0.05) * Math.pow(1 - p, 1.5);
                    add(b, s + i, d < 0 ? lp * env * vol : 0, d > 0 ? lp * env * vol : 0);
                }
            }
        }
    }

    /** Filtered noise whose band moves, with {@code q} resonance. */
    private static void sweep(float[][] b, double at, double len, double f0, double f1, double v0, double v1, double q) {
        int s = (int) (at * RATE), n = (int) (len * RATE);
        Svf l = new Svf(), r = new Svf();
        for (int i = 0; i < n; i++) {
            double p = i / (double) n, g = Svf.g(f0 * Math.pow(f1 / f0, p)), vol = v0 + (v1 - v0) * p * p;
            add(b, s + i, l.bp(NOISE.nextDouble() * 2 - 1, g, 1 / q) * vol, r.bp(NOISE.nextDouble() * 2 - 1, g, 1 / q) * vol);
        }
    }

    private static void noiseSweep(float[][] b, double at, double len, double f0, double f1, double v0, double v1) {
        sweep(b, at, len, f0, f1, v0, v1, 1.4);
    }

    private static float[][] lowpass(float[][] b, double hz) {
        Svf l = new Svf(), r = new Svf();
        double g = Svf.g(hz);
        for (int i = 0; i < b[0].length; i++) {
            b[0][i] = (float) l.run(b[0][i], g, 1.2);
            b[1][i] = (float) r.run(b[1][i], g, 1.2);
        }
        return b;
    }

    private static final class Svf {
        double s1, s2, bp, hp;

        double run(double x, double g, double k) {
            double a1 = 1 / (1 + g * (g + k)), a2 = g * a1, a3 = g * a2;
            double v3 = x - s2, v1 = a1 * s1 + a2 * v3, v2 = s2 + a2 * s1 + a3 * v3;
            s1 = 2 * v1 - s1;
            s2 = 2 * v2 - s2;
            bp = v1;
            hp = x - k * v1 - v2;
            return v2;
        }

        double bp(double x, double g, double k) {
            run(x, g, k);
            return bp;
        }

        static double g(double hz) {
            return Math.tan(Math.PI * Math.min(hz, RATE * 0.45) / RATE);
        }
    }

    // ---- writing ----------------------------------------------------------------------------

    /** Peak at -1 dBFS, silence trimmed off the end (after a short fade), 16-bit stereo WAV. */
    private static void write(Path file, float[][] b) throws IOException {
        int n = b[0].length;
        // no DC offset (a slowing saw leaves some), and a 3 ms fade-in so nothing clicks on the cut
        for (float[] ch : b) {
            double x1 = 0, y1 = 0;
            for (int i = 0; i < n; i++) {
                double y = ch[i] - x1 + 0.9995 * y1;
                x1 = ch[i];
                y1 = y;
                ch[i] = (float) (y * Math.min(1, i / (RATE * 0.003)));
            }
        }
        float peak = 1e-9f;
        for (int i = 0; i < n; i++) peak = Math.max(peak, Math.max(Math.abs(b[0][i]), Math.abs(b[1][i])));
        float gain = 0.891f / peak;
        int end = n;
        while (end > 1 && Math.abs(b[0][end - 1]) * gain < 3e-4 && Math.abs(b[1][end - 1]) * gain < 3e-4) end--;
        end = Math.min(n, end + RATE / 20);
        int fade = Math.min(end, RATE / 10);
        ByteBuffer bb = ByteBuffer.allocate(44 + end * 4).order(ByteOrder.LITTLE_ENDIAN);
        bb.put("RIFF".getBytes()).putInt(36 + end * 4).put("WAVE".getBytes()).put("fmt ".getBytes()).putInt(16)
                .putShort((short) 1).putShort((short) 2).putInt(RATE).putInt(RATE * 4).putShort((short) 4).putShort((short) 16)
                .put("data".getBytes()).putInt(end * 4);
        for (int i = 0; i < end; i++) {
            float f = i >= end - fade ? (end - i) / (float) fade : 1;
            bb.putShort((short) Math.round(Math.max(-1, Math.min(1, b[0][i] * gain * f)) * 32767));
            bb.putShort((short) Math.round(Math.max(-1, Math.min(1, b[1][i] * gain * f)) * 32767));
        }
        try (OutputStream o = Files.newOutputStream(file)) {
            o.write(bb.array());
        }
        System.err.printf("  %-45s %5.2f s%n", file.getFileName(), end / (double) RATE);
    }
}
