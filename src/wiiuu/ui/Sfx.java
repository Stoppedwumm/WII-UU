package wiiuu.ui;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;

/** Soft synthesized menu blips, so no audio files need to ship. Fails silently without audio. */
final class Sfx {
    private static final float RATE = 44100f;
    private static final ExecutorService AUDIO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sfx");
        t.setDaemon(true);
        return t;
    });
    private static volatile boolean enabled = true;
    private static volatile boolean broken;

    private Sfx() {}

    static void setEnabled(boolean on) {
        enabled = on;
    }

    static void move() {
        play(new double[]{1320}, 0.035, 0.10);
    }

    static void select() {
        play(new double[]{880, 1320}, 0.07, 0.14);
    }

    static void back() {
        play(new double[]{990, 660}, 0.06, 0.12);
    }

    static void bump() {
        play(new double[]{220}, 0.05, 0.10);
    }

    static void chime() {
        play(new double[]{1047, 1319, 1568}, 0.09, 0.12);
    }

    private static void play(double[] notes, double noteSeconds, double volume) {
        if (!enabled || broken) return;
        AUDIO.execute(() -> {
            try {
                int perNote = (int) (RATE * noteSeconds);
                byte[] buf = new byte[perNote * notes.length * 2];
                int i = 0;
                for (double f : notes) {
                    for (int n = 0; n < perNote; n++) {
                        double t = n / RATE;
                        double env = Math.min(1, n / (RATE * 0.004)) * Math.exp(-t * 38);
                        double s = (Math.sin(2 * Math.PI * f * t) + 0.25 * Math.sin(4 * Math.PI * f * t)) * env * volume;
                        short v = (short) (Math.max(-1, Math.min(1, s)) * Short.MAX_VALUE);
                        buf[i++] = (byte) v;
                        buf[i++] = (byte) (v >> 8);
                    }
                }
                AudioFormat fmt = new AudioFormat(RATE, 16, 1, true, false);
                try (SourceDataLine line = AudioSystem.getSourceDataLine(fmt)) {
                    line.open(fmt, 8192);
                    line.start();
                    line.write(buf, 0, buf.length);
                    line.drain();
                }
            } catch (Exception | LinkageError e) {
                broken = true; // no audio device; stay quiet from now on
            }
        });
    }
}
