package wiiuu.choccy;

/**
 * Stereo 16-bit sound from one rate to another, with cubic (Hermite) interpolation. Keeps its place
 * between calls, so fractional rates (32040.5 Hz) come out exact over time.
 */
final class Resampler {
    private final double outRate;
    private float[] in = new float[16384];      // input waiting, stereo, with history in front
    private int frames;
    private double pos = 1;                      // where the next output sample falls, in input frames
    private short[] out = new short[0];

    Resampler(double outRate) {
        this.outRate = outRate;
    }

    /** Adds n stereo frames at inRate; returns how many frames {@link #out()} now holds. */
    int push(short[] pcm, int n, double inRate) {
        if ((frames + n) * 2 > in.length) in = java.util.Arrays.copyOf(in, Math.max(in.length * 2, (frames + n) * 2));
        for (int i = 0; i < n * 2; i++) in[frames * 2 + i] = pcm[i];
        frames += n;
        double step = inRate / outRate;
        int max = (int) ((frames - pos) / step) + 2;
        if (out.length < max * 2) out = new short[max * 2];
        int o = 0;
        while (pos + 2 < frames) {
            int i = (int) pos;
            float t = (float) (pos - i);
            for (int c = 0; c < 2; c++) {
                float y0 = in[(i - 1) * 2 + c], y1 = in[i * 2 + c], y2 = in[(i + 1) * 2 + c], y3 = in[(i + 2) * 2 + c];
                float c1 = 0.5f * (y2 - y0), c2 = y0 - 2.5f * y1 + 2f * y2 - 0.5f * y3, c3 = 0.5f * (y3 - y0) + 1.5f * (y1 - y2);
                float v = ((c3 * t + c2) * t + c1) * t + y1;
                out[o * 2 + c] = (short) Math.max(-32768, Math.min(32767, Math.round(v)));
            }
            o++;
            pos += step;
        }
        int drop = (int) pos - 1;                   // keep one frame before the position as history
        if (drop > 0) {
            System.arraycopy(in, drop * 2, in, 0, (frames - drop) * 2);
            frames -= drop;
            pos -= drop;
        }
        return o;
    }

    /** The output of the last {@link #push}. */
    short[] out() {
        return out;
    }
}
