package wiiuu.ui;

/** Soft synthesized menu blips, so no audio files need to ship. Fails silently without audio. */
final class Sfx {
    private static final float RATE = MenuAudio.RATE;
    private static volatile boolean enabled = true;

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

    private static volatile short[] bootCache;

    /** Synthesizes the chime in the background ahead of time, so playing it never stalls a frame. */
    static void prepareBoot() {
        if (bootCache != null) return;
        Thread t = new Thread(() -> bootCache = bootPcm(), "boot-chime");
        t.setDaemon(true);
        t.start();
    }

    static void boot() {
        if (!enabled) return;
        short[] pcm = bootCache;
        if (pcm != null) {
            MenuAudio.get().play(pcm, 1f);
            return;
        }
        Thread t = new Thread(() -> MenuAudio.get().play(bootCache = bootPcm(), 1f), "boot-chime");
        t.setDaemon(true);
        t.start();
    }

    /** The start-up chime: a rising Fmaj9 of soft bells over a warm pad (about 3 s). */
    static short[] bootPcm() {
        double[] bells = {698.46, 880.00, 1046.50, 1318.51, 1567.98};       // F5 A5 C6 E6 G6
        double[] pad = {174.61, 261.63, 329.63, 440.00};                     // F3 C4 E4 A4
        int total = (int) (RATE * 3.2);
        float[] mix = new float[total];
        for (int k = 0; k < bells.length; k++) {
            int start = (int) (RATE * (0.09 * k));
            double f = bells[k];
            for (int n = 0; start + n < total; n++) {
                double t = n / RATE;
                double env = Math.min(1, t / 0.004) * Math.exp(-t * 2.2);
                // bell: fundamental plus a quickly fading inharmonic shimmer
                double s = Math.sin(2 * Math.PI * f * t) + 0.35 * Math.exp(-t * 7) * Math.sin(2 * Math.PI * f * 2.76 * t);
                mix[start + n] += (float) (s * env * 0.11);
            }
        }
        for (double f : pad) {
            for (int n = 0; n < total; n++) {
                double t = n / RATE;
                double env = Math.min(1, t / 0.5) * Math.min(1, (3.2 - t) / 1.4);
                double s = Math.sin(2 * Math.PI * f * t + 0.6 * Math.sin(2 * Math.PI * 0.8 * t))
                        + 0.2 * Math.sin(2 * Math.PI * 2 * f * t);
                mix[n] += (float) (s * env * 0.025);
            }
        }
        short[] pcm = new short[total];
        for (int i = 0; i < total; i++) pcm[i] = (short) (Math.max(-1, Math.min(1, mix[i])) * Short.MAX_VALUE);
        return pcm;
    }

    /** A thread that renders menu pictures (the first-boot intro's shots) and must stay silent. */
    static volatile Thread quiet;

    private static void play(double[] notes, double noteSeconds, double volume) {
        if (!enabled || Thread.currentThread() == quiet) return;
        int perNote = (int) (RATE * noteSeconds);
        short[] pcm = new short[perNote * notes.length];
        int i = 0;
        for (double f : notes) {
            for (int n = 0; n < perNote; n++) {
                double t = n / RATE;
                double env = Math.min(1, n / (RATE * 0.004)) * Math.exp(-t * 38);
                double s = (Math.sin(2 * Math.PI * f * t) + 0.25 * Math.sin(4 * Math.PI * f * t)) * env * volume;
                pcm[i++] = (short) (Math.max(-1, Math.min(1, s)) * Short.MAX_VALUE);
            }
        }
        MenuAudio.get().play(pcm, 1f);
    }
}
