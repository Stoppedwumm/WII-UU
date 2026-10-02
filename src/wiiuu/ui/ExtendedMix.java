package wiiuu.ui;

import java.util.List;

/**
 * The WII-UU Extended Mix: every Future House and Color House remix back to back at one tempo,
 * like a DJ set. Each plays as its extended version ({@link Tunes#extended}); the outro of one and
 * the intro of the next play together, beat-matched, while one fades out and the other in.
 *
 * <p>A whole mix is about 16 minutes, too much to keep in memory, so it is made one segment at a
 * time: a segment is one track from the end of its intro up to and including the blend into the
 * next one, so segments follow each other seamlessly and the last leads back into the first.
 */
final class ExtendedMix {
    static final String ID = "mix";
    static final String NAME = "WII-UU Extended Mix (non-stop, every dance remix)";
    static final double BPM = 128;

    /** Future and Color House take turns, and no tune comes twice in a row. */
    private static final List<String> ORDER = List.of(
            "wiiuu-house", "korobeiniki-color", "mountainking-house", "odetojoy-color",
            "furelise-house", "turkishmarch-color", "greensleeves-house", "canon-color",
            "wiiuu-color", "korobeiniki-house", "mountainking-color", "odetojoy-house",
            "furelise-color", "turkishmarch-house", "greensleeves-color", "canon-house");

    private ExtendedMix() {}

    /** One stretch of the mix; {@code following} is the next track's extended version, for the next segment. */
    record Segment(int index, short[] pcm, String title, short[] following) {}

    /** The track ids in mix order (for the music video). */
    static List<String> order() {
        return ORDER;
    }

    static int size() {
        return ORDER.size();
    }

    /** The name of the {@code i}th track, as the menu lists it. */
    static String title(int i) {
        String id = ORDER.get(Math.floorMod(i, ORDER.size()));
        if (id.startsWith("wiiuu-")) {
            return "WII-UU (original) – " + (id.endsWith(Tunes.HOUSE) ? "Future House" : "Color House") + " remix";
        }
        for (Tunes.Track t : Tunes.tracks()) if (t.id().equals(id)) return t.name();
        return id;
    }

    /**
     * Makes segment {@code i}. {@code track} is track i's extended version when the previous
     * segment already made it (its {@code following}), or null.
     */
    static Segment segment(int i, short[] track) {
        int n = ORDER.size();
        i = Math.floorMod(i, n);
        short[] cur = track != null ? track : Tunes.extended(ORDER.get(i), BPM);
        short[] next = Tunes.extended(ORDER.get((i + 1) % n), BPM);
        int blend = (int) Math.round(Tunes.EXTENDED_BARS * 4 * 60 / BPM * MenuAudio.RATE);
        int frames = cur.length / 2 - blend;                     // from the end of the intro to the end
        short[] out = new short[frames * 2];
        System.arraycopy(cur, blend * 2, out, 0, frames * 2);
        int from = frames - blend;
        for (int f = 0; f < blend; f++) {
            // equal-power crossfade over the blend; the kicks line up, so the beat never stumbles
            double x = (f + 0.5) / blend, a = Math.cos(x * Math.PI / 2), b = Math.sin(x * Math.PI / 2);
            for (int c = 0; c < 2; c++) {
                int o = (from + f) * 2 + c;
                out[o] = (short) Math.max(-32768, Math.min(32767, Math.round(out[o] * a + next[f * 2 + c] * b)));
            }
        }
        return new Segment(i, out, title(i), next);
    }
}
