package wiiuu.ui;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

/**
 * The setup guide mascot's voice: short lines recorded ahead of time (resources/voice, made with
 * scripts/make-voice.sh) and played through the menu's mixer, with the music ducking under them.
 * One line at a time; a new one cuts the last off. Knows how loud it is right now, so the mascot
 * can bob along. Fails silently: without the files or a sound card the guide is just quiet.
 */
final class GuideVoice {
    private static final int RATE = MenuAudio.RATE, STEP = RATE / 50;     // loudness per 20 ms
    private static final Map<String, Line> cache = new HashMap<>();

    private record Line(short[] pcm, float[] level) {}

    private Line playing;
    private int[] pos;
    private boolean on = true;

    void setEnabled(boolean enabled) {
        on = enabled;
        if (!enabled) stop();
    }

    /** Says a line (by id); false if it can't, so the caller can time the bubble itself. */
    boolean say(String id) {
        stop();
        if (!on) return false;
        Line l = load(id);
        if (l == null) return false;
        pos = MenuAudio.get().speak(l.pcm(), 0.9f);
        if (pos == null) return false;
        playing = l;
        return true;
    }

    void stop() {
        if (pos != null) MenuAudio.get().stopSpeech(pos);
        playing = null;
        pos = null;
    }

    /** How far through the line it is, 0..1 (1 when nothing plays). */
    float progress() {
        Line l = playing;
        int[] p = pos;
        if (l == null || p == null) return 1;
        return Math.min(1f, p[0] / (float) l.pcm().length);
    }

    boolean speaking() {
        return progress() < 1;
    }

    /** How loud it is right now, 0..1. */
    float level() {
        Line l = playing;
        int[] p = pos;
        if (l == null || p == null || p[0] >= l.pcm().length) return 0;
        return l.level()[Math.min(l.level().length - 1, p[0] / STEP)];
    }

    private static synchronized Line load(String id) {
        if (cache.containsKey(id)) return cache.get(id);
        Line line = null;
        try (InputStream raw = GuideVoice.class.getResourceAsStream("/voice/" + id + ".wav")) {
            if (raw != null) {
                AudioInputStream in = AudioSystem.getAudioInputStream(new BufferedInputStream(raw));
                AudioFormat src = in.getFormat();
                AudioFormat pcm16 = new AudioFormat(src.getSampleRate(), 16, 1, true, false);
                byte[] bytes = AudioSystem.getAudioInputStream(pcm16, in).readAllBytes();
                int n = bytes.length / 2;
                double ratio = RATE / src.getSampleRate();
                short[] out = new short[(int) (n * ratio)];
                for (int i = 0; i < out.length; i++) {          // linear resampling to the mixer's rate
                    double at = i / ratio;
                    int a = (int) at, b = Math.min(n - 1, a + 1);
                    double f = at - a;
                    out[i] = (short) (sample(bytes, a) * (1 - f) + sample(bytes, b) * f);
                }
                float[] level = new float[out.length / STEP + 1];
                float peak = 1;
                for (int k = 0; k < level.length; k++) {
                    double sum = 0;
                    int from = k * STEP, to = Math.min(out.length, from + STEP);
                    for (int i = from; i < to; i++) sum += out[i] * (double) out[i];
                    level[k] = (float) Math.sqrt(sum / Math.max(1, to - from));
                    peak = Math.max(peak, level[k]);
                }
                for (int k = 0; k < level.length; k++) level[k] = Math.min(1f, level[k] / peak * 1.3f);
                line = new Line(out, level);
            }
        } catch (Exception | LinkageError e) {
            line = null;
        }
        cache.put(id, line);
        return line;
    }

    private static int sample(byte[] b, int i) {
        return (short) ((b[i * 2] & 0xff) | (b[i * 2 + 1] << 8));
    }
}
