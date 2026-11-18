package wiiuu.choccy;

import java.util.Arrays;

/** Windowed-sinc stereo resampling, with fractional phase and history shared across batches. */
final class SincResampler {
    private static final int TAPS = 32, LEFT = TAPS / 2 - 1, PHASES = 512;
    private final double outRate;
    private final float[][] kernel = new float[PHASES + 1][TAPS];
    private double rate;
    private double position = LEFT;
    private int frames = LEFT;
    private short[] input = new short[4096], output = new short[4096];

    SincResampler(double outRate) {
        this.outRate = outRate;
    }

    private void prepare(double inRate) {
        double cutoff = 0.94 * Math.min(1.0, outRate / inRate);
        for (int phase = 0; phase <= PHASES; phase++) {
            double sum = 0;
            for (int i = 0; i < TAPS; i++) {
                double x = i - LEFT - (double) phase / PHASES;
                double angle = 2 * Math.PI * x / TAPS;
                double window = 0.42 + 0.5 * Math.cos(angle) + 0.08 * Math.cos(2 * angle);
                double sinc = x == 0 ? cutoff : Math.sin(Math.PI * cutoff * x) / (Math.PI * x);
                kernel[phase][i] = (float) (sinc * window);
                sum += kernel[phase][i];
            }
            for (int i = 0; i < TAPS; i++) kernel[phase][i] /= (float) sum;
        }
        rate = inRate;
    }

    int push(short[] pcm, int n, double inRate) {
        if (inRate != rate) prepare(inRate);
        int needed = (frames + n) * 2;
        if (input.length < needed) input = Arrays.copyOf(input, Math.max(input.length * 2, needed));
        System.arraycopy(pcm, 0, input, frames * 2, n * 2);
        frames += n;
        double step = inRate / outRate;
        int capacity = Math.max(0, (int) ((frames - position) / step) + 2) * 2;
        if (output.length < capacity) output = new short[capacity];

        int written = 0;
        while ((int) position + (TAPS - LEFT) <= frames) {
            int center = (int) position;
            float[] weights = kernel[(int) Math.round((position - center) * PHASES)];
            int from = (center - LEFT) * 2;
            float l = 0, r = 0;
            for (int i = 0; i < TAPS; i++) {
                l += input[from + i * 2] * weights[i];
                r += input[from + i * 2 + 1] * weights[i];
            }
            output[written * 2] = pcm16(l);
            output[written * 2 + 1] = pcm16(r);
            written++;
            position += step;
        }
        int drop = Math.min(frames, (int) position - LEFT);
        if (drop > 0) {
            frames -= drop;
            position -= drop;
            System.arraycopy(input, drop * 2, input, 0, frames * 2);
        }
        return written;
    }

    private static short pcm16(float value) {
        return (short) Math.max(-32768, Math.min(32767, Math.round(value)));
    }

    short[] out() {
        return output;
    }
}
