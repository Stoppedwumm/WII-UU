package wiiuu.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.DoubleUnaryOperator;

/**
 * Old public-domain melodies (folk songs, Grieg, Beethoven, Mozart, Pachelbel) in three of
 * WII-UU's own arrangements, synthesized at start-up like {@link MenuMusic} so no audio files
 * ship:
 * <ul>
 *   <li>8-bit: a pulse-wave lead, a broken-chord pulse, a triangle bass and noise drums;</li>
 *   <li>WII-UU remix: the menu's own sound, slower and lightly swung, with vibraphone, electric
 *       piano with richer chords, bass and soft percussion;</li>
 *   <li>Future House remix: 128 BPM, a build-up with filtered chords, vocal chops, a snare roll
 *       and a riser, then a drop with kick and clap, sub and bouncing mid bass, a detuned saw
 *       lead, sidechain pumping and heavy saturation. WII-UU's own tune gets one too.</li>
 * </ul>
 */
final class Tunes {
    private static final int RATE = MenuAudio.RATE;

    private Tunes() {}

    /**
     * One tune. {@code melody}: "note/beats" tokens ("E5/1", "C#4/.5", "r/1" for a rest).
     * {@code chords}: one per bar, or two split halfway ("Dm|G"). {@code drums}: 8-bit drums, two
     * steps per beat for one or more bars (k kick, s snare, h hat, x kick and hat, - nothing).
     */
    private record Tune(String id, String name, int beatsPerBar, double bpm, double remixBpm,
                        String melody, String chords, String drums, int doubleFromBar) {}

    /** A track to choose: the id ("korobeiniki", "korobeiniki-remix") and the name to show. */
    record Track(String id, String name) {}

    static final String REMIX = "-remix";
    static final String HOUSE = "-house";

    // ---- the tunes --------------------------------------------------------------------------

    private static final String KORO_A = """
            E5/1 B4/.5 C5/.5 D5/1 C5/.5 B4/.5  A4/1 A4/.5 C5/.5 E5/1 D5/.5 C5/.5
            B4/1.5 C5/.5 D5/1 E5/1  C5/1 A4/1 A4/1 r/1
            r/.5 D5/1 F5/.5 A5/1 G5/.5 F5/.5  E5/1.5 C5/.5 E5/1 D5/.5 C5/.5
            B4/1 B4/.5 C5/.5 D5/1 E5/1  C5/1 A4/1 A4/1 r/1
            """;
    private static final String KORO_CHORDS = "E Am E Am Dm C E7 Am ";

    private static final String KING_A = """
            B3/.5 C#4/.5 D4/.5 E4/.5 F#4/.5 D4/.5 F#4/1  F4/.5 C#4/.5 F4/1 E4/.5 C4/.5 E4/1
            B3/.5 C#4/.5 D4/.5 E4/.5 F#4/.5 D4/.5 F#4/.5 B4/.5  A4/.5 F#4/.5 D4/.5 F#4/.5 A4/2
            """;
    private static final String KING_B = """
            F#4/.5 G#4/.5 A#4/.5 B4/.5 C#5/.5 A#4/.5 C#5/1  D5/.5 A#4/.5 D5/1 C#5/.5 A#4/.5 C#5/1
            F#4/.5 G#4/.5 A#4/.5 B4/.5 C#5/.5 A#4/.5 C#5/1  D5/.5 A#4/.5 D5/1 C#5/2
            """;
    private static final String KING_CHORDS_A = "Bm F#|C Bm D ";
    private static final String KING_CHORDS_B = "F# Bm|F# F# Bm|F# ";

    private static final String JOY_1 = "F#5/1 F#5/1 G5/1 A5/1  A5/1 G5/1 F#5/1 E5/1  D5/1 D5/1 E5/1 F#5/1 ";
    private static final String JOY_2 = """
            E5/1 E5/1 F#5/1 D5/1  E5/1 F#5/.5 G5/.5 F#5/1 D5/1  E5/1 F#5/.5 G5/.5 F#5/1 E5/1  D5/1 E5/1 A4/2
            """;

    // Für Elise: two bars of 3/8 make one bar here; the lead-in "E5 D#5" closes each phrase
    private static final String ELISE_A1 = """
            E5/.25 D#5/.25 E5/.25 B4/.25 D5/.25 C5/.25  A4/.5 r/.25 C4/.25 E4/.25 A4/.25
            B4/.5 r/.25 E4/.25 G#4/.25 B4/.25  C5/.5 r/.25 E4/.25 E5/.25 D#5/.25
            E5/.25 D#5/.25 E5/.25 B4/.25 D5/.25 C5/.25  A4/.5 r/.25 C4/.25 E4/.25 A4/.25
            B4/.5 r/.25 E4/.25 C5/.25 B4/.25
            """;
    private static final String ELISE_A = ELISE_A1 + "A4/.5 r/.5 E5/.25 D#5/.25 ";
    private static final String ELISE_B = """
            E5/.75 G4/.25 F5/.25 E5/.25  D5/.75 F4/.25 E5/.25 D5/.25
            C5/.75 E4/.25 D5/.25 C5/.25  B4/.5 r/.25 E4/.25 E5/.25 r/.25
            r/.25 E5/.25 E6/.25 r/.25 r/.25 D#5/.25  E5/.25 r/.25 r/.25 D#5/.25 E5/.25 D#5/.25
            """;
    private static final String ELISE_CHORDS_A = "Am E|Am Am E|Am ";

    // Rondo alla turca: two bars of 2/4 make one bar here; the lead-in "B4 A4 G#4 A4" closes the phrase
    private static final String TURCA = """
            C5/.5 r/.5 D5/.25 C5/.25 B4/.25 C5/.25  E5/.5 r/.5 F5/.25 E5/.25 D#5/.25 E5/.25
            B5/.25 A5/.25 G#5/.25 A5/.25 B5/.25 A5/.25 G#5/.25 A5/.25  C6/1 A5/.5 C6/.5
            B5/.5 A5/.5 G5/.5 A5/.5  B5/.5 A5/.5 G5/.5 A5/.5
            B5/.5 A5/.5 G5/.5 F#5/.5  E5/1 B4/.25 A4/.25 G#4/.25 A4/.25
            """;

    private static final String GREEN = """
            C5/2 D5/1  E5/1.5 F5/.5 E5/1  D5/2 B4/1  G4/1.5 A4/.5 B4/1
            C5/2 A4/1  A4/1.5 G#4/.5 A4/1  B4/2 G#4/1  E4/2 A4/1
            C5/2 D5/1  E5/1.5 F5/.5 E5/1  D5/2 B4/1  G4/1.5 A4/.5 B4/1
            C5/1.5 B4/.5 A4/1  G#4/1.5 F#4/.5 G#4/1  A4/2 A4/1
            """;
    private static final String GREEN_CHORDS = "Am C G Em Am E E Am Am C G Em Am E Am ";

    // Pachelbel's canon: the famous line in halves, then in its lower turn, then two variations of WII-UU's own
    private static final String CANON = """
            F#5/2 E5/2  D5/2 C#5/2  B4/2 A4/2  B4/2 C#5/2
            D5/2 C#5/2  B4/2 A4/2  G4/2 F#4/2  G4/2 E4/2
            D5/1 F#5/1 A5/1 G5/1  F#5/1 D5/1 F#5/1 E5/1  D5/1 B4/1 D5/1 A4/1  G4/1 B4/1 A4/1 G4/1
            F#5/.5 E5/.5 D5/.5 E5/.5 F#5/.5 E5/.5 D5/.5 C#5/.5  B4/.5 C#5/.5 D5/.5 C#5/.5 B4/.5 A4/.5 C#5/.5 A4/.5
            G4/.5 B4/.5 D5/.5 B4/.5 A4/.5 F#4/.5 A4/.5 D5/.5  G4/.5 B4/.5 D5/.5 G5/.5 E5/.5 A5/.5 C#5/.5 E5/.5
            """;
    private static final String CANON_CHORDS = "D|A Bm|F#m G|D G|A ";

    private static final List<Tune> TUNES = List.of(
            new Tune("korobeiniki", "Korobeiniki (the Tetris theme)", 4, 150, 104, KORO_A + KORO_A,
                    KORO_CHORDS + KORO_CHORDS, "x-h-s-h-x-hxs-h-", 8),
            new Tune("mountainking", "In the Hall of the Mountain King", 4, 138, 100, KING_A + KING_A + KING_B + up(KING_A),
                    KING_CHORDS_A + KING_CHORDS_A + KING_CHORDS_B + KING_CHORDS_A, "k-h-s-h-k-h-s-hh", 4),
            new Tune("odetojoy", "Ode to Joy", 4, 132, 96,
                    JOY_1 + "F#5/1.5 E5/.5 E5/2 " + JOY_1 + "E5/1.5 D5/.5 D5/2 " + JOY_2 + JOY_1 + "E5/1.5 D5/.5 D5/2 ",
                    "D A7 D D|A D A7 D A|D A|D A|D A|D Bm|A D A7 D A|D ", "k-h-s-h-k-k-s-h-", 8),
            new Tune("furelise", "Für Elise", 3, 72, 62, ELISE_A + ELISE_A1 + "A4/.5 r/.25 B4/.25 C5/.25 D5/.25 " + ELISE_B + ELISE_A,
                    ELISE_CHORDS_A + ELISE_CHORDS_A + "C|G Am|E E " + ELISE_CHORDS_A, "k-hhs-", 4),
            new Tune("turkishmarch", "Turkish March (Rondo alla turca)", 4, 116, 88, TURCA + TURCA,
                    "Am E|Am Em B7|Em Am E|Am Em B7|Em ", "x-h-s-h-x-h-s-hh", 4),
            new Tune("greensleeves", "Greensleeves", 3, 120, 84, GREEN + GREEN,
                    GREEN_CHORDS + GREEN_CHORDS, "k-h-h-k-hhs-", 15),
            new Tune("canon", "Canon in D", 4, 100, 76, CANON, CANON_CHORDS.repeat(4), "k-h-s-h-k-k-s-h-", 8));

    /** WII-UU's own tune ({@link MenuMusic}) as notation, for its Future House remix. */
    private static final Tune THEME = new Tune("wiiuu", "WII-UU (original)", 4, 100, 100, themeMelody(),
            "F Am Bb C|C7 F Dm Gm C7 Bb Bbm Am D7 Gm C7 F Gm|C7", "--------", 99);

    private static String themeMelody() {
        String[] names = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};
        StringBuilder sb = new StringBuilder();
        double at = 0;
        double[][] m = MenuMusic.MELODY;
        for (int i = 0; i < m.length; i++) {
            double start = (m[i][0] - 1) * 4 + m[i][1];
            if (start > at + 1e-9) sb.append("r/").append(start - at).append(' ');
            double end = start + m[i][3];
            if (i + 1 < m.length) end = Math.min(end, (m[i + 1][0] - 1) * 4 + m[i + 1][1]);
            int note = (int) m[i][2];
            sb.append(names[note % 12]).append(note / 12 - 1).append('/').append(end - start).append(' ');
            at = end;
        }
        if (at < 64) sb.append("r/").append(64 - at);
        return sb.toString();
    }

    /** WII-UU's own tune as Future House, then each tune in 8-bit, as a WII-UU remix and as Future House. */
    static List<Track> tracks() {
        List<Track> out = new ArrayList<>();
        out.add(new Track(THEME.id() + HOUSE, THEME.name() + " \u2013 Future House remix"));
        for (Tune t : TUNES) {
            out.add(new Track(t.id(), t.name()));
            out.add(new Track(t.id() + REMIX, t.name() + " \u2013 WII-UU remix"));
            out.add(new Track(t.id() + HOUSE, t.name() + " \u2013 Future House remix"));
        }
        return out;
    }

    /** Renders a track by id; null for an unknown id. */
    static short[] render(String id) {
        boolean remix = id.endsWith(REMIX), house = id.endsWith(HOUSE);
        String base = remix ? id.substring(0, id.length() - REMIX.length()) : house ? id.substring(0, id.length() - HOUSE.length()) : id;
        if (house && base.equals(THEME.id())) return house(THEME);
        for (Tune t : TUNES) if (t.id().equals(base)) return remix ? remix(t) : house ? house(t) : chip(t);
        return null;
    }

    // ---- notation ---------------------------------------------------------------------------

    private record Note(double beat, int midi, double len) {}

    private static List<Note> melody(String s) {
        List<Note> out = new ArrayList<>();
        double at = 0;
        for (String tok : s.trim().split("\\s+")) {
            String[] p = tok.split("/");
            double len = Double.parseDouble(p[1]);
            if (!p[0].equals("r")) out.add(new Note(at, midi(p[0]), len));
            at += len;
        }
        return out;
    }

    private static double length(String melody) {
        double at = 0;
        for (String tok : melody.trim().split("\\s+")) at += Double.parseDouble(tok.split("/")[1]);
        return at;
    }

    /** "C#4" -> 61 */
    private static int midi(String name) {
        int i = 1, n = "C D EF G A B".indexOf(name.charAt(0));
        if (name.charAt(i) == '#') {
            n++;
            i++;
        } else if (name.charAt(i) == 'b') {
            n--;
            i++;
        }
        return 12 * (Integer.parseInt(name.substring(i)) + 1) + n;
    }

    /** A melody an octave higher. */
    private static String up(String melody) {
        StringBuilder sb = new StringBuilder();
        for (String tok : melody.trim().split("\\s+")) {
            String[] p = tok.split("/");
            if (p[0].equals("r")) {
                sb.append(tok).append(' ');
                continue;
            }
            int d = p[0].length() - 1;
            sb.append(p[0], 0, d).append(Integer.parseInt(p[0].substring(d)) + 1).append('/').append(p[1]).append(' ');
        }
        return sb.toString();
    }

    /** "F#m" -> {root pitch class, intervals...} */
    private static int[] chord(String name) {
        int i = 1, root = "C D EF G A B".indexOf(name.charAt(0));
        if (name.length() > 1 && name.charAt(1) == '#') {
            root++;
            i++;
        } else if (name.length() > 1 && name.charAt(1) == 'b') {
            root--;
            i++;
        }
        String q = name.substring(i);
        int[] iv = switch (q) {
            case "m" -> new int[]{0, 3, 7};
            case "7" -> new int[]{0, 4, 7, 10};
            case "m7" -> new int[]{0, 3, 7, 10};
            default -> new int[]{0, 4, 7};
        };
        int[] out = new int[iv.length + 1];
        out[0] = (root + 12) % 12;
        System.arraycopy(iv, 0, out, 1, iv.length);
        return out;
    }

    /** The chord sounding at {@code beat} of a bar ("Dm|G": the second from halfway). */
    private static int[] chordAt(String bar, double beat, int beatsPerBar) {
        String[] halves = bar.split("\\|");
        return chord(halves[halves.length > 1 && beat >= beatsPerBar / 2.0 - 1e-9 ? 1 : 0]);
    }

    private static String[] bars(Tune t) {
        String[] bars = t.chords().trim().split("\\s+");
        if (Math.abs(length(t.melody()) - bars.length * t.beatsPerBar()) > 1e-6) {
            throw new IllegalStateException(t.id() + ": melody and chords differ in length");
        }
        return bars;
    }

    private static double hz(double midi) {
        return 440 * Math.pow(2, (midi - 69) / 12.0);
    }

    private static short[] finish(float[] l, float[] r, float level) {
        MenuMusic.reverb(l, r);
        int frames = l.length;
        float peak = 1e-6f;
        for (int i = 0; i < frames; i++) peak = Math.max(peak, Math.max(Math.abs(l[i]), Math.abs(r[i])));
        float gain = level / peak;
        short[] out = new short[frames * 2];
        for (int i = 0; i < frames; i++) {
            out[i * 2] = (short) (Math.max(-1f, Math.min(1f, l[i] * gain)) * 32767);
            out[i * 2 + 1] = (short) (Math.max(-1f, Math.min(1f, r[i] * gain)) * 32767);
        }
        return out;
    }

    // ---- 8-bit ------------------------------------------------------------------------------

    private static short[] chip(Tune t) {
        double beat = 60.0 / t.bpm();
        int bpb = t.beatsPerBar(), steps = bpb * 2;
        String[] bars = bars(t);
        int frames = (int) Math.round(bars.length * bpb * beat * RATE);
        float[] l = new float[frames], r = new float[frames];
        Random noise = new Random(11);

        for (Note n : melody(t.melody())) {
            double at = n.beat() * beat, len = n.len() * beat * 0.92;
            pulse(l, r, at, len, hz(n.midi()), 0.25, 0.16, 0.42, true);
            // from the second time round a second pulse doubles the tune an octave down
            if (n.beat() >= t.doubleFromBar() * bpb - 1e-9) pulse(l, r, at, len, hz(n.midi() - 12), 0.5, 0.06, 0.6, false);
        }
        int drumBars = Math.max(1, t.drums().length() / steps);
        for (int b = 0; b < bars.length; b++) {
            for (int step = 0; step < steps; step++) {
                int[] c = chordAt(bars[b], step * 0.5, bpb);
                double at = (b * bpb + step * 0.5) * beat;
                // broken chord on eighths around middle C
                int tones = c.length - 1;
                int[] order = tones == 4 ? new int[]{0, 1, 2, 3, 2, 1} : new int[]{0, 1, 2, 1};
                int note = 60 + (c[0] >= 7 ? c[0] - 12 : c[0]) + c[1 + order[step % order.length]];
                pulse(l, r, at, 0.5 * beat * 0.6, hz(note), 0.125, 0.045, 0.7, false);
                // bass on every beat: the root low on the first, then root high and fifth
                if (step % 2 == 0) {
                    int root = 36 + c[0], beatNo = step / 2;
                    int bn = beatNo == 0 ? root : beatNo % 2 == 0 ? root + c[3] : root + 12;
                    triangle(l, r, at, beat * 0.8, hz(bn), 0.22);
                }
                switch (t.drums().charAt((b % drumBars) * steps + step)) {
                    case 'k' -> kick(l, r, at);
                    case 's' -> snare(l, r, at, noise);
                    case 'h' -> hat(l, r, at, noise);
                    case 'x' -> {
                        kick(l, r, at);
                        hat(l, r, at, noise);
                    }
                    default -> { }
                }
            }
        }
        return finish(l, r, 0.75f);
    }

    private static void add(float[] buf, int i, double v) {
        buf[i % buf.length] += (float) v;
    }

    /** Band-limited pulse wave (PolyBLEP), with a little vibrato on long lead notes. */
    private static void pulse(float[] l, float[] r, double at, double len, double f, double duty, double vol,
                              double pan, boolean vibrato) {
        int start = (int) (at * RATE), n = (int) ((len + 0.04) * RATE);
        double phase = 0;
        for (int i = 0; i < n; i++) {
            double t = i / (double) RATE;
            double ff = vibrato && t > 0.18 ? f * (1 + 0.006 * Math.sin(2 * Math.PI * 5.5 * t)) : f;
            double dt = ff / RATE;
            double s = (phase < duty ? 1 : -1) + blep(phase, dt) - blep((phase - duty + 1) % 1, dt) - (2 * duty - 1);
            phase += dt;
            if (phase >= 1) phase -= 1;
            double env = Math.min(1, t / 0.004) * (0.75 + 0.25 * Math.exp(-t * 8)) * (t <= len ? 1 : Math.max(0, 1 - (t - len) / 0.04));
            s *= env * vol;
            add(l, start + i, s * (1 - pan) * 2);
            add(r, start + i, s * pan * 2);
        }
    }

    private static double blep(double t, double dt) {
        if (t < dt) {
            t /= dt;
            return t + t - t * t - 1;
        }
        if (t > 1 - dt) {
            t = (t - 1) / dt;
            return t * t + t + t + 1;
        }
        return 0;
    }

    private static void triangle(float[] l, float[] r, double at, double len, double f, double vol) {
        int start = (int) (at * RATE), n = (int) ((len + 0.03) * RATE);
        for (int i = 0; i < n; i++) {
            double t = i / (double) RATE;
            double p = (f * t) % 1;
            double s = 4 * Math.abs(p - 0.5) - 1;
            double env = Math.min(1, t / 0.003) * (t <= len ? 1 : Math.max(0, 1 - (t - len) / 0.03));
            s *= env * vol;
            add(l, start + i, s);
            add(r, start + i, s);
        }
    }

    private static void kick(float[] l, float[] r, double at) {
        int start = (int) (at * RATE), n = (int) (0.18 * RATE);
        double phase = 0;
        for (int i = 0; i < n; i++) {
            double t = i / (double) RATE;
            phase += 2 * Math.PI * (50 + 110 * Math.exp(-t * 35)) / RATE;
            double s = Math.sin(phase) * Math.exp(-t * 18) * 0.3;
            add(l, start + i, s);
            add(r, start + i, s);
        }
    }

    private static void snare(float[] l, float[] r, double at, Random noise) {
        int start = (int) (at * RATE), n = (int) (0.16 * RATE);
        for (int i = 0; i < n; i++) {
            double t = i / (double) RATE;
            double s = ((noise.nextDouble() * 2 - 1) * Math.exp(-t * 22) + Math.sin(2 * Math.PI * 190 * t) * Math.exp(-t * 30) * 0.5) * 0.12;
            add(l, start + i, s);
            add(r, start + i, s);
        }
    }

    private static void hat(float[] l, float[] r, double at, Random noise) {
        int start = (int) (at * RATE), n = (int) (0.04 * RATE);
        double prev = 0;
        for (int i = 0; i < n; i++) {
            double t = i / (double) RATE;
            double w = noise.nextDouble() * 2 - 1, hp = w - prev;
            prev = w;
            double s = hp * Math.exp(-t * 90) * 0.035;
            add(l, start + i, s * 0.4);
            add(r, start + i, s * 0.6);
        }
    }

    // ---- WII-UU remix -----------------------------------------------------------------------

    /**
     * The tune in the menu's own sound: the melody on vibraphone around the same pitch as WII-UU's
     * tune, seventh chords on electric piano, a walking-ish bass and shaker, rim and kick, swung
     * unless the melody runs in sixteenths.
     */
    private static short[] remix(Tune t) {
        double beat = 60.0 / t.remixBpm();
        int bpb = t.beatsPerBar();
        String[] bars = bars(t);
        int frames = (int) Math.round(bars.length * bpb * beat * RATE);
        float[] l = new float[frames], r = new float[frames];
        Random noise = new Random(7);

        List<Note> notes = melody(t.melody());
        boolean sixteenths = false;
        double sum = 0;
        for (Note n : notes) {
            double frac = n.beat() - Math.floor(n.beat());
            sixteenths |= Math.abs(frac - 0.25) < 1e-6 || Math.abs(frac - 0.75) < 1e-6;
            sum += n.midi();
        }
        double swing = sixteenths ? 0 : 0.07;
        // the vibraphone sits best around E5, where WII-UU's own tune is
        int shift = 12 * (int) Math.round((76 - sum / Math.max(1, notes.size())) / 12);
        for (Note n : notes) {
            MenuMusic.vibes(l, r, time(n.beat(), beat, swing), n.len() * beat, hz(n.midi() + shift));
        }

        for (int b = 0; b < bars.length; b++) {
            double bar = b * bpb;
            // electric piano: on the beat, then on the off-beats, a hair of strum
            for (double hit = 0; hit < bpb; hit += hit == 0 ? 1.5 : 1) {
                int[] voicing = voicing(chordAt(bars[b], hit, bpb));
                double len = hit == 0 ? 0.9 : 0.45;
                for (int k = 0; k < voicing.length; k++) {
                    MenuMusic.electricPiano(l, r, time(bar + hit, beat, swing) + k * 0.008, len * beat, hz(voicing[k]),
                            hit == 0 ? 0.075 : 0.055);
                }
            }
            // bass: root on the beat, fifth between, and an approach note into the next bar
            for (int k = 0; k < bpb; k++) {
                int[] c = chordAt(bars[b], k, bpb);
                int root = bassRoot(c[0]);
                boolean changes = k == 0 || chordAt(bars[b], k - 1, bpb)[0] != c[0] || (bpb == 4 && k == 2);
                MenuMusic.bass(l, r, time(bar + k, beat, swing), (k == bpb - 1 ? 0.45 : 0.9) * beat, hz(changes ? root : root + 7));
            }
            int next = bassRoot(chordAt(bars[(b + 1) % bars.length], 0, bpb)[0]);
            int last = bassRoot(chordAt(bars[b], bpb - 0.5, bpb)[0]);
            MenuMusic.bass(l, r, time(bar + bpb - 0.5, beat, swing), 0.45 * beat, hz(next + (next > last ? -1 : 1)));

            // percussion: shaker on the eighths, rim on the back beats, a soft kick
            for (int e = 0; e < bpb * 2; e++) {
                MenuMusic.shaker(l, r, time(bar + e * 0.5, beat, swing), e % 2 == 1 ? 0.05 : 0.03, noise);
            }
            for (int k = 1; k < bpb; k += bpb == 3 ? 1 : 2) MenuMusic.rim(l, r, time(bar + k, beat, swing), noise);
            MenuMusic.kick(l, r, time(bar, beat, swing));
            if (bpb == 4) MenuMusic.kick(l, r, time(bar + 2.5, beat, swing));
        }
        return finish(l, r, 0.8f);
    }

    /** Seconds from the start; off-beat eighths come a little late (swing). */
    private static double time(double beats, double beat, double swing) {
        double frac = beats - Math.floor(beats);
        if (Math.abs(frac - 0.5) < 1e-6) beats += swing;
        return beats * beat;
    }

    private static int bassRoot(int pitchClass) {
        int n = 36 + pitchClass;
        return n < 40 ? n + 12 : n;
    }

    /** Third, fifth and seventh (major seventh on major chords) between G3 and F#4. */
    private static int[] voicing(int[] c) {
        boolean minor = c[2] == 3, seventh = c.length > 4;
        int[] iv = {c[2], c[3], seventh ? c[4] : minor ? 10 : 11};
        int[] out = new int[3];
        for (int k = 0; k < 3; k++) {
            int n = 48 + c[0] + iv[k];
            while (n < 55) n += 12;
            while (n > 66) n -= 12;
            out[k] = n;
        }
        java.util.Arrays.sort(out);
        return out;
    }

    // ---- Future House remix -----------------------------------------------------------------

    private static final double HOUSE_BPM = 128;
    /** Sixteenths of the bar where the chords bounce (3-3-4-3-3). */
    private static final int[] BOUNCE = {0, 3, 6, 10, 13};

    /** A saw synth: detuned saws through a resonant low-pass with its own envelope, then drive. */
    private record Patch(int saws, double detuneCents, double attack, double decay, double sustain, double release,
                         double q, double envAmount, double envDecay, double drive, double width, double bend) {}

    private static final Patch PLUCK = new Patch(2, 12, 0.002, 0.18, 0.25, 0.08, 0.9, 1.5, 12, 1.5, 0.6, 0);
    private static final Patch LEAD = new Patch(3, 18, 0.003, 0.3, 0.55, 0.06, 0.8, 1.8, 9, 2.2, 0.8, 0);
    private static final Patch STAB = new Patch(2, 10, 0.001, 0.08, 0.2, 0.04, 1.0, 2.0, 25, 1.2, 0.5, 0);
    private static final Patch MID_BASS = new Patch(2, 8, 0.002, 0.12, 0.45, 0.03, 2.5, 4.0, 30, 3.0, 0.3, 3);

    /**
     * Two sections in one loop: a build-up over the tune's first bars (up to 8), the filter opening,
     * then the drop with the whole tune. Tunes in 3/4 are stretched to 4/4 bars.
     */
    private static short[] house(Tune t) {
        double beat = 60.0 / HOUSE_BPM, bar4 = 4 * beat, step = beat / 4;
        int bpb = t.beatsPerBar();
        double stretch = 4.0 / bpb;
        String[] bars = bars(t);
        int n = bars.length;
        int build = Math.max(4, Math.min(8, n / 4 * 2));
        int frames = (int) Math.round((build + n) * bar4 * RATE);
        float[] busL = new float[frames], busR = new float[frames];       // synths: reverb, sidechain
        float[] dryL = new float[frames], dryR = new float[frames];       // bass: sidechain only
        float[] drumL = new float[frames], drumR = new float[frames];
        Random noise = new Random(5);
        double drop = build * bar4;
        DoubleUnaryOperator opening = s -> 400 * Math.pow(20, Math.min(1, s / drop));   // the build's filter

        List<Note> notes = melody(t.melody());
        double sum = 0;
        for (Note note : notes) sum += note.midi();
        int shift = 12 * (int) Math.round((74 - sum / Math.max(1, notes.size())) / 12);

        // ---- build-up
        for (int b = 0; b < build; b++) {
            double bar = b * bar4;
            for (int s = 0; s < 16; s++) {
                double at = bar + s * step;
                int[] c = chordAt(bars[b], s / 4.0 / stretch, bpb);
                if (contains(BOUNCE, s)) {
                    for (int note : chordNotes(c)) synth(busL, busR, at, beat * 0.4, note, 0.16, PLUCK, opening);
                }
                MenuMusic.shaker(drumL, drumR, at, s % 2 == 1 ? 0.025 : 0.012, noise);
                if ((s == 6 || s == 14) && b % 2 == 1) chop(busL, busR, at, beat * 0.45, 72 + (c[0] > 7 ? c[0] - 12 : c[0]) + c[s == 6 ? 3 : 2], s == 6 ? 0 : 1, 0.12);
            }
            if (b >= build / 2 && b < build - 2) {
                houseSnare(drumL, drumR, bar + beat, 0.3, noise);
                houseSnare(drumL, drumR, bar + 3 * beat, 0.3, noise);
            }
        }
        // snare roll over the last two bars: eighths, sixteenths, thirty-seconds, louder and higher
        for (int k = 0; k < 8; k++) houseSnare(drumL, drumR, drop - 2 * bar4 + k * beat / 2, 0.15 + 0.02 * k, noise);
        for (int k = 0; k < 8; k++) houseSnare(drumL, drumR, drop - bar4 + k * step, 0.32 + 0.02 * k, noise);
        for (int k = 0; k < 16; k++) houseSnare(drumL, drumR, drop - bar4 / 2 + k * step / 2, 0.48 + 0.02 * k, noise);
        int riser = Math.min(4, build / 2);
        noiseSweep(busL, busR, drop - riser * bar4, riser * bar4, 400, 11000, 0, 0.28, noise);
        noiseSweep(busL, busR, 0, bar4, 9000, 200, 0.22, 0, noise);                   // downlifter out of the drop
        for (Note note : notes) {
            if (note.beat() >= build * bpb) break;
            double len = Math.min(note.len(), build * bpb - note.beat());
            synth(busL, busR, note.beat() * stretch * beat, len * stretch * beat * 0.95, note.midi() + shift, 0.26, PLUCK,
                    s -> opening.applyAsDouble(s) * 1.6);
        }

        // ---- drop
        for (int b = 0; b < n; b++) {
            double bar = drop + b * bar4;
            if (b % 8 == 0) crash(drumL, drumR, bar, noise);
            for (int k = 0; k < 4; k++) {
                houseKick(drumL, drumR, bar + k * beat);
                if (k % 2 == 1) clap(drumL, drumR, bar + k * beat, noise);
                ride(drumL, drumR, bar + k * beat, noise);
                hat(drumL, drumR, bar + k * beat + beat / 2, noise);
            }
            for (int s = 0; s < 16; s++) {
                double at = bar + s * step;
                MenuMusic.shaker(drumL, drumR, at, s % 2 == 1 ? 0.03 : 0.015, noise);
                if (s % 4 != 2) continue;
                // off-beats: sub, the bouncing mid bass (an octave up every other time) and short chord stabs
                int[] c = chordAt(bars[b], s / 4.0 / stretch, bpb);
                int root = bassRoot(c[0]);
                sub(dryL, dryR, at, beat * 0.45, hz(root - 12), 0.2);
                synth(dryL, dryR, at, beat * 0.32, root + (s == 6 || s == 14 ? 12 : 0), 0.24, MID_BASS, x -> 380);
                for (int note : chordNotes(c)) synth(busL, busR, at, beat * 0.2, note + 12, 0.06, STAB, x -> 4200);
            }
            if (b % 2 == 1) {
                int[] c = chordAt(bars[b], 3.5 / stretch, bpb);
                chop(busL, busR, bar + 14 * step, beat * 0.3, 72 + (c[0] > 7 ? c[0] - 12 : c[0]) + c[3], 2, 0.08);
            }
        }
        for (Note note : notes) {
            double at = drop + note.beat() * stretch * beat, len = note.len() * stretch * beat * 0.95;
            synth(busL, busR, at, len, note.midi() + shift, 0.34, LEAD, x -> 3400);
            // the second half of the drop gets an octave on top
            if (note.beat() >= n * bpb / 2.0) synth(busL, busR, at, len, note.midi() + shift + 12, 0.12, LEAD, x -> 4000);
        }

        // ---- mix: reverb on the synths, everything but the drums pumps with the kick, then squash
        MenuMusic.reverb(busL, busR);
        float[] l = new float[frames], r = new float[frames];
        int dropFrame = (int) (drop * RATE);
        double dropPower = 0;
        for (int i = 0; i < frames; i++) {
            double s = i / (double) RATE;
            double duck = 1;
            if (s >= drop) {
                double x = Math.max(0, 1 - ((s - drop) % beat) / 0.16);
                duck = 1 - 0.78 * x * x;
            }
            l[i] = (float) ((busL[i] + dryL[i]) * duck + drumL[i]);
            r[i] = (float) ((busR[i] + dryR[i]) * duck + drumR[i]);
            if (i >= dropFrame) dropPower += l[i] * l[i] + r[i] * r[i];
        }
        // the drop driven into a soft clipper (loud and dense), then brought to about the level of
        // the other tracks so switching between them doesn't jump
        double drive = 0.45 / Math.sqrt(dropPower / Math.max(1, 2.0 * (frames - dropFrame))), level = 0.42;
        short[] out = new short[frames * 2];
        for (int i = 0; i < frames; i++) {
            out[i * 2] = (short) (Math.tanh(drive * l[i]) * level * 32767);
            out[i * 2 + 1] = (short) (Math.tanh(drive * r[i]) * level * 32767);
        }
        return out;
    }

    private static boolean contains(int[] a, int v) {
        for (int x : a) if (x == v) return true;
        return false;
    }

    /** Root (C3 to B3) plus the third, fifth and seventh voicing. */
    private static int[] chordNotes(int[] c) {
        int[] v = voicing(c);
        return new int[]{48 + c[0], v[0], v[1], v[2]};
    }

    /** Zero-delay state-variable filter; {@link #run} returns the low-pass, {@code bp} and {@code hp} hold the others. */
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

        static double g(double hz) {
            return Math.tan(Math.PI * Math.min(hz, RATE * 0.45) / RATE);
        }
    }

    private static void synth(float[] l, float[] r, double at, double len, double midi, double vol, Patch p,
                              DoubleUnaryOperator cutoff) {
        int start = (int) (at * RATE), n = (int) ((len + p.release()) * RATE);
        int saws = p.saws();
        double[] phase = new double[saws], ratio = new double[saws], pan = new double[saws];
        for (int k = 0; k < saws; k++) {
            double spread = saws == 1 ? 0 : k / (saws - 1.0) * 2 - 1;
            ratio[k] = Math.pow(2, spread * p.detuneCents() / 1200);
            pan[k] = 0.5 + spread * p.width() / 2;
            phase[k] = k / (double) saws;
        }
        Svf fl = new Svf(), fr = new Svf();
        double k = 1 / p.q(), g = 0, f = hz(midi), norm = Math.tanh(p.drive());
        for (int i = 0; i < n; i++) {
            double t = i / (double) RATE;
            if ((i & 15) == 0) {
                g = Svf.g(cutoff.applyAsDouble(at + t) * (1 + p.envAmount() * Math.exp(-t * p.envDecay())));
                if (p.bend() != 0) f = hz(midi + p.bend() * Math.exp(-t * 60));
            }
            double sl = 0, sr = 0;
            for (int j = 0; j < saws; j++) {
                double dt = f * ratio[j] / RATE;
                double v = 2 * phase[j] - 1 - blep(phase[j], dt);
                phase[j] += dt;
                if (phase[j] >= 1) phase[j] -= 1;
                sl += v * (1 - pan[j]);
                sr += v * pan[j];
            }
            double env = (t < p.attack() ? t / p.attack() : p.sustain() + (1 - p.sustain()) * Math.exp(-(t - p.attack()) / p.decay()))
                    * (t <= len ? 1 : Math.max(0, 1 - (t - len) / p.release()));
            double yl = Math.tanh(p.drive() * fl.run(sl, g, k) / saws) / norm;
            double yr = Math.tanh(p.drive() * fr.run(sr, g, k) / saws) / norm;
            add(l, start + i, yl * env * vol);
            add(r, start + i, yr * env * vol);
        }
    }

    /** Formants of a, o and i, for vocal chops. */
    private static final double[][] VOWELS = {{730, 1090}, {570, 840}, {300, 2250}};

    /** A sung "ah"/"oh"/"ee" blip: a saw through two formant filters, scooping up into the note. */
    private static void chop(float[] l, float[] r, double at, double len, double midi, int vowel, double vol) {
        int start = (int) (at * RATE), n = (int) ((len + 0.03) * RATE);
        Svf a = new Svf(), b = new Svf();
        double ga = Svf.g(VOWELS[vowel][0]), gb = Svf.g(VOWELS[vowel][1]), k = 1 / 6.0, phase = 0;
        for (int i = 0; i < n; i++) {
            double t = i / (double) RATE;
            double dt = hz(midi - Math.exp(-t * 40)) / RATE;
            double v = 2 * phase - 1 - blep(phase, dt);
            phase += dt;
            if (phase >= 1) phase -= 1;
            a.run(v, ga, k);
            b.run(v, gb, k);
            double y = (a.bp + 0.6 * b.bp) * Math.min(1, t / 0.005) * Math.exp(-t * 5) * (t <= len ? 1 : Math.max(0, 1 - (t - len) / 0.03));
            add(l, start + i, y * vol * (vowel == 1 ? 0.65 : 0.35));
            add(r, start + i, y * vol * (vowel == 1 ? 0.35 : 0.65));
        }
    }

    /** Filtered noise whose band moves from {@code fromHz} to {@code toHz} (riser, downlifter). */
    private static void noiseSweep(float[] l, float[] r, double at, double len, double fromHz, double toHz,
                                   double v0, double v1, Random noise) {
        int start = (int) (at * RATE), n = (int) (len * RATE);
        Svf fl = new Svf(), fr = new Svf();
        double g = 0;
        for (int i = 0; i < n; i++) {
            double p = i / (double) n;
            if ((i & 15) == 0) g = Svf.g(fromHz * Math.pow(toHz / fromHz, p));
            fl.run(noise.nextDouble() * 2 - 1, g, 1 / 1.4);
            fr.run(noise.nextDouble() * 2 - 1, g, 1 / 1.4);
            double vol = v0 + (v1 - v0) * p * p;
            add(l, start + i, fl.bp * vol);
            add(r, start + i, fr.bp * vol);
        }
    }

    private static void houseKick(float[] l, float[] r, double at) {
        int start = (int) (at * RATE), n = (int) (0.32 * RATE);
        double phase = 0;
        for (int i = 0; i < n; i++) {
            double t = i / (double) RATE;
            phase += 2 * Math.PI * (46 + 140 * Math.exp(-t * 28)) / RATE;
            double s = Math.sin(phase) * Math.exp(-t * 7);
            if (t < 0.003) s += (1 - t / 0.003) * 0.4 * Math.sin(2 * Math.PI * 3000 * t);
            s = Math.tanh(1.5 * s) * 0.32;
            add(l, start + i, s);
            add(r, start + i, s);
        }
    }

    private static void clap(float[] l, float[] r, double at, Random noise) {
        int start = (int) (at * RATE), n = (int) (0.22 * RATE);
        Svf fl = new Svf(), fr = new Svf();
        double g = Svf.g(1400);
        for (int i = 0; i < n; i++) {
            double t = i / (double) RATE, env = 0;
            for (double o : new double[]{0, 0.008, 0.016}) if (t >= o) env += Math.exp(-(t - o) * 300);
            if (t >= 0.024) env += 0.6 * Math.exp(-(t - 0.024) * 16);
            fl.run(noise.nextDouble() * 2 - 1, g, 1 / 1.2);
            fr.run(noise.nextDouble() * 2 - 1, g, 1 / 1.2);
            add(l, start + i, fl.bp * env * 0.45);
            add(r, start + i, fr.bp * env * 0.45);
        }
    }

    private static void houseSnare(float[] l, float[] r, double at, double vol, Random noise) {
        int start = (int) (at * RATE), n = (int) (0.14 * RATE);
        Svf f = new Svf();
        double g = Svf.g(2200);
        for (int i = 0; i < n; i++) {
            double t = i / (double) RATE;
            f.run(noise.nextDouble() * 2 - 1, g, 1 / 0.8);
            double s = (f.bp * 1.6 * Math.exp(-t * 25) + Math.sin(2 * Math.PI * 200 * t) * Math.exp(-t * 35) * 0.5) * vol * 0.5;
            add(l, start + i, s);
            add(r, start + i, s);
        }
    }

    private static void ride(float[] l, float[] r, double at, Random noise) {
        int start = (int) (at * RATE), n = (int) (0.5 * RATE);
        double[] partials = {3150, 4620, 6280, 7930};
        for (int i = 0; i < n; i++) {
            double t = i / (double) RATE, s = 0;
            for (double f : partials) s += Math.sin(2 * Math.PI * f * t);
            s = (s * 0.25 + (noise.nextDouble() * 2 - 1) * 0.3) * Math.exp(-t * 5) * 0.022;
            add(l, start + i, s * 0.6);
            add(r, start + i, s * 0.4);
        }
    }

    private static void crash(float[] l, float[] r, double at, Random noise) {
        int start = (int) (at * RATE), n = (int) (2.0 * RATE);
        Svf fl = new Svf(), fr = new Svf();
        double g = Svf.g(4500);
        for (int i = 0; i < n; i++) {
            double t = i / (double) RATE, env = Math.exp(-t * 1.8) * 0.1;
            fl.run(noise.nextDouble() * 2 - 1, g, 1 / 0.7);
            fr.run(noise.nextDouble() * 2 - 1, g, 1 / 0.7);
            add(l, start + i, fl.hp * env);
            add(r, start + i, fr.hp * env);
        }
    }

    private static void sub(float[] l, float[] r, double at, double len, double f, double vol) {
        int start = (int) (at * RATE), n = (int) ((len + 0.03) * RATE);
        for (int i = 0; i < n; i++) {
            double t = i / (double) RATE;
            double s = Math.sin(2 * Math.PI * f * t) * Math.min(1, t / 0.005) * (t <= len ? 1 : Math.max(0, 1 - (t - len) / 0.03)) * vol;
            add(l, start + i, s);
            add(r, start + i, s);
        }
    }
}
