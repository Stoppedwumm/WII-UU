package wiiuu.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Menu music in the style of an 8-bit console: a pulse-wave lead, a broken-chord pulse, a
 * triangle bass and noise drums. The melodies are old public-domain tunes (a folk song,
 * Grieg, Beethoven); the arrangements are WII-UU's own, synthesized at start-up like
 * {@link MenuMusic}, so no audio files ship.
 */
final class ChipTunes {
    private static final int RATE = MenuAudio.RATE;

    private ChipTunes() {}

    /**
     * One tune. {@code melody}: "note/beats" tokens ("E5/1", "C#4/.5", "r/1" for a rest).
     * {@code chords}: one per bar, or two split at beat 3 ("Dm|G"). {@code drums}: eight
     * eighth-note steps per bar (k kick, s snare, h hat, x kick and hat, - nothing).
     */
    private record Tune(double bpm, String melody, String chords, String drums, int doubleFromBar) {}

    // ---- the tunes --------------------------------------------------------------------------

    private static final String KORO_A = """
            E5/1 B4/.5 C5/.5 D5/1 C5/.5 B4/.5  A4/1 A4/.5 C5/.5 E5/1 D5/.5 C5/.5
            B4/1.5 C5/.5 D5/1 E5/1  C5/1 A4/1 A4/1 r/1
            r/.5 D5/1 F5/.5 A5/1 G5/.5 F5/.5  E5/1.5 C5/.5 E5/1 D5/.5 C5/.5
            B4/1 B4/.5 C5/.5 D5/1 E5/1  C5/1 A4/1 A4/1 r/1
            """;
    private static final String KORO_CHORDS = "E Am E Am Dm C E7 Am ";

    /** Korobeiniki, the Russian folk song that became the Tetris theme. */
    private static final Tune KOROBEINIKI = new Tune(150, KORO_A + KORO_A, KORO_CHORDS + KORO_CHORDS,
            "x-h-s-h-x-hxs-h-", 8);

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

    /** In the Hall of the Mountain King (Grieg, 1875). */
    private static final Tune MOUNTAIN_KING = new Tune(138, KING_A + KING_A + KING_B + up(KING_A),
            KING_CHORDS_A + KING_CHORDS_A + KING_CHORDS_B + KING_CHORDS_A, "k-h-s-h-k-h-s-hh", 4);

    private static final String JOY_1 = "F#5/1 F#5/1 G5/1 A5/1  A5/1 G5/1 F#5/1 E5/1  D5/1 D5/1 E5/1 F#5/1 ";
    private static final String JOY_2 = """
            E5/1 E5/1 F#5/1 D5/1  E5/1 F#5/.5 G5/.5 F#5/1 D5/1  E5/1 F#5/.5 G5/.5 F#5/1 E5/1  D5/1 E5/1 A4/2
            """;
    private static final String JOY_MELODY = JOY_1 + "F#5/1.5 E5/.5 E5/2 " + JOY_1 + "E5/1.5 D5/.5 D5/2 "
            + JOY_2 + JOY_1 + "E5/1.5 D5/.5 D5/2 ";

    /** Ode to Joy (Beethoven, 1824). */
    private static final Tune ODE_TO_JOY = new Tune(132, JOY_MELODY,
            "D A7 D D|A D A7 D A|D A|D A|D A|D Bm|A D A7 D A|D ", "k-h-s-h-k-k-s-h-", 8);

    /** Renders a tune by id ("korobeiniki", "mountainking", "odetojoy"); null for an unknown id. */
    static short[] render(String id) {
        Tune t = switch (id) {
            case "korobeiniki" -> KOROBEINIKI;
            case "mountainking" -> MOUNTAIN_KING;
            case "odetojoy" -> ODE_TO_JOY;
            default -> null;
        };
        return t == null ? null : render(t);
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

    // ---- rendering --------------------------------------------------------------------------

    private static short[] render(Tune t) {
        double beat = 60.0 / t.bpm();
        String[] bars = t.chords().trim().split("\\s+");
        double beats = length(t.melody());
        if (Math.abs(beats - bars.length * 4) > 1e-6) throw new IllegalStateException("melody and chords differ in length");
        int frames = (int) Math.round(beats * beat * RATE);
        float[] l = new float[frames], r = new float[frames];
        Random noise = new Random(11);

        for (Note n : melody(t.melody())) {
            boolean doubled = n.beat() >= t.doubleFromBar() * 4;
            pulse(l, r, n.beat() * beat, n.len() * beat * 0.92, hz(n.midi()), 0.25, 0.16, 0.42, true);
            // the second time round a second pulse doubles the tune an octave down
            if (doubled) pulse(l, r, n.beat() * beat, n.len() * beat * 0.92, hz(n.midi() - 12), 0.5, 0.06, 0.6, false);
        }
        for (int b = 0; b < bars.length; b++) {
            String[] halves = bars[b].split("\\|");
            for (int step = 0; step < 8; step++) {
                int[] c = chord(halves[halves.length > 1 && step >= 4 ? 1 : 0]);
                double at = (b * 4 + step * 0.5) * beat;
                // broken chord on eighths around middle C
                int tones = c.length - 1;
                int[] order = tones == 4 ? new int[]{0, 1, 2, 3, 2, 1, 2, 3} : new int[]{0, 1, 2, 1, 0, 1, 2, 1};
                int note = 60 + (c[0] >= 7 ? c[0] - 12 : c[0]) + c[1 + order[step]];
                pulse(l, r, at, 0.5 * beat * 0.6, hz(note), 0.125, 0.045, 0.7, false);
                // bass on every beat: root low, root high, fifth, root high
                if (step % 2 == 0) {
                    int root = 36 + c[0];
                    int bn = switch (step) {
                        case 0 -> root;
                        case 4 -> root + c[3];
                        default -> root + 12;
                    };
                    triangle(l, r, at, beat * 0.8, hz(bn), 0.22);
                }
                switch (t.drums().charAt((b % (t.drums().length() / 8)) * 8 + step)) {
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

        MenuMusic.reverb(l, r);
        float peak = 1e-6f;
        for (int i = 0; i < frames; i++) peak = Math.max(peak, Math.max(Math.abs(l[i]), Math.abs(r[i])));
        float gain = 0.75f / peak;
        short[] out = new short[frames * 2];
        for (int i = 0; i < frames; i++) {
            out[i * 2] = (short) (Math.max(-1f, Math.min(1f, l[i] * gain)) * 32767);
            out[i * 2 + 1] = (short) (Math.max(-1f, Math.min(1f, r[i] * gain)) * 32767);
        }
        return out;
    }

    private static double hz(int midi) {
        return 440 * Math.pow(2, (midi - 69) / 12.0);
    }

    private static void add(float[] buf, int i, double v) {
        buf[i % buf.length] += (float) v;
    }

    // ---- instruments ------------------------------------------------------------------------

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
}
