package wiiuu.screen;

/**
 * WII-UU's own sound (the menu music and effects), handed straight from its mixer to the phone
 * instead of being recorded back from the system output: bit-clean, and independent of what macOS
 * records. Used while the Mac capture leaves WII-UU's own sound out ({@link MacAudio}), so it isn't
 * heard twice.
 *
 * <p>The mixer {@link #feed feeds} 44.1 kHz blocks as it plays them; {@link AudioStreamer} takes
 * the same span of time out at 48 kHz for each chunk it sends. A short cushion absorbs the two
 * sides' uneven timing, and it is reset when they drift apart.
 *
 * <p>The built-in menu music isn't even sent: the phone makes it itself (web/music.js, the same
 * synthesizer), from what {@link #music()} says plays where. Only effects and the player's own
 * music files come through here then.
 */
public final class OwnSound {
    private static final int RATE = AudioStreamer.RATE;
    private static final int CUSHION = RATE / 25;                  // 40 ms collected before it plays
    private static final int MAX = RATE * 3 / 20;                  // 150 ms: further behind, catch up
    private static final float[] buffer = new float[RATE / 2 * 2]; // 0.5 s, interleaved stereo

    // guarded by OwnSound.class
    private static boolean wanted;
    private static int start, count;      // frames
    private static boolean priming = true;
    private static double pos;            // resampling: position between prevL/R and the next frame
    private static float prevL, prevR;

    private static volatile Music music;

    private OwnSound() {}

    /**
     * The menu music now: track id ("korobeiniki-house", "mix:3" for the Extended Mix's fourth
     * segment, "file:..." for one of the player's files), position and length in seconds, whether
     * it's on (fading in) or off, its volume (0..1), and the id queued to follow it, or null.
     */
    public record Music(String id, double pos, double length, boolean on, double volume, String next) {}

    /** Called by the mixer as it plays. */
    public static void music(Music now) {
        music = now;
    }

    /** What the menu music is doing, or null before it started. */
    public static Music music() {
        return music;
    }

    private static volatile long lastWithoutMusic;

    /** A phone fetched sound without saying it makes the music itself (an older page, no Web Worker). */
    public static void listenerWithoutMusic() {
        lastWithoutMusic = System.currentTimeMillis();
    }

    /**
     * Whether the phones make the menu music {@code id} themselves (so it isn't sent): a built-in
     * track, and every phone listening says it can.
     */
    public static boolean phoneMakes(String id) {
        return id != null && !id.startsWith("file:") && wanted() && System.currentTimeMillis() - lastWithoutMusic > 3000;
    }

    /** Whether anyone takes the sound now (the mixer skips feeding otherwise). */
    public static synchronized boolean wanted() {
        return wanted;
    }

    static synchronized void want(boolean on) {
        if (wanted == on) return;
        wanted = on;
        start = count = 0;
        priming = true;
    }

    /** The mixer's output: {@code frames} interleaved stereo frames at {@code rate}, about -1..1. */
    public static synchronized void feed(float[] stereo, int frames, int rate) {
        if (!wanted) return;
        double step = rate / (double) RATE;
        for (int i = 0; i < frames; i++) {
            float l = clip(stereo[i * 2]), r = clip(stereo[i * 2 + 1]);
            // emit 48 kHz frames that fall between the previous source frame and this one
            while (pos < 1) {
                put(prevL + (l - prevL) * (float) pos, prevR + (r - prevR) * (float) pos);
                pos += step;
            }
            pos -= 1;
            prevL = l;
            prevR = r;
        }
        if (count > MAX) {                                         // far ahead of the phone: skip ahead
            int drop = count - CUSHION;
            start = (start + drop) % (buffer.length / 2);
            count -= drop;
        }
    }

    /** Mixes the next stretch of WII-UU's sound into {@code chunk} (16-bit little-endian stereo at 48 kHz). */
    static synchronized void mixInto(byte[] chunk) {
        int frames = chunk.length / 4;
        if (priming) {
            if (count < CUSHION) return;
            priming = false;
        }
        int n = Math.min(frames, count);
        for (int i = 0; i < n; i++) {
            int b = ((start + i) % (buffer.length / 2)) * 2;
            for (int c = 0; c < 2; c++) {
                int at = i * 4 + c * 2;
                int s = (short) ((chunk[at] & 0xff) | (chunk[at + 1] << 8)) + Math.round(buffer[b + c] * 32767);
                s = Math.max(-32768, Math.min(32767, s));
                chunk[at] = (byte) s;
                chunk[at + 1] = (byte) (s >> 8);
            }
        }
        start = (start + n) % (buffer.length / 2);
        count -= n;
        if (n < frames) priming = true;                            // ran dry (e.g. the music stopped)
    }

    private static void put(float l, float r) {
        int frames = buffer.length / 2;
        if (count == frames) {                                     // full: forget the oldest
            start = (start + 1) % frames;
            count--;
        }
        int b = ((start + count) % frames) * 2;
        buffer[b] = l;
        buffer[b + 1] = r;
        count++;
    }

    private static float clip(float s) {
        return s > 1f ? 1f : Math.max(-1f, s);
    }
}
