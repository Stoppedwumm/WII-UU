package wiiuu.ui;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;

/**
 * FFT spectrum analysis and ambient/fullscreen painting for host-provided normalized mono audio.
 * Supply samples at a fixed update cadence for smoothing; update and paint on the same UI thread.
 * Branding, track text and exit hints are supplied by the host. No playback or input is installed.
 */
public class AudioVisualizer {
    public static final int SAMPLE_COUNT = 2048;
    private static final int N = SAMPLE_COUNT;                     // FFT size: 46 ms at 44.1 kHz
    public static final int BANDS = 48;

    private final float[] samples = new float[N];
    private final double[] re = new double[N], im = new double[N];
    private final float[] level = new float[BANDS];        // 0..1, smoothed
    private final float[] wave = new float[512];
    private float bass, loudest = 1e-4f, wavePeak = 1e-3f;
    private final double sampleRate;
    private final java.util.function.LongSupplier clock;
    private final long born;

    /** Audio in normalized mono samples; no audio device or playback is opened. */
    public AudioVisualizer(double sampleRate) {
        this(sampleRate, System::nanoTime);
    }

    /** Supply a monotonic nanosecond clock for deterministic ring rotation. */
    public AudioVisualizer(double sampleRate, java.util.function.LongSupplier clock) {
        if (!Double.isFinite(sampleRate) || sampleRate < 8000 || sampleRate > 384000)
            throw new IllegalArgumentException("Sample rate must be 8000..384000 Hz");
        this.sampleRate = sampleRate;
        this.clock = java.util.Objects.requireNonNull(clock);
        this.born = clock.getAsLong();
    }

    /** Updates the bars from {@code mono}, the last {@value #SAMPLE_COUNT} samples before exclusive end, zero-padded when fewer are available. */
    public void update(float[] mono, int end) {
        java.util.Objects.requireNonNull(mono);
        if (end < 0 || end > mono.length) throw new IndexOutOfBoundsException("end must be within mono");
        for (int i = 0; i < N; i++) {
            int k = end - N + i;
            float value = k >= 0 ? mono[k] : 0f;
            samples[i] = Float.isFinite(value) ? Math.max(-1, Math.min(1, value)) : 0f;
        }
        analyse();
    }

    /** A snapshot of the bars, 0..1, low to high. */
    public float[] levels() {
        return level.clone();
    }

    /** The bass, 0..1: jumps with each kick, then falls back. */
    public float bass() {
        return bass;
    }

    /** A snapshot of the waveform just supplied, normalized to about -1..1. */
    public float[] wave() {
        return wave.clone();
    }

    private void analyse() {
        for (int i = 0; i < N; i++) {
            double w = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (N - 1));    // Hann window
            re[i] = samples[i] * w;
            im[i] = 0;
        }
        fft(re, im);
        float[] raw = new float[BANDS];
        float peak = 0;
        for (int b = 0; b < BANDS; b++) {
            // log-spaced from 40 Hz to 12 kHz
            double lo = 40 * Math.pow(300, b / (double) BANDS), hi = 40 * Math.pow(300, (b + 1) / (double) BANDS);
            int i0 = Math.max(1, (int) (lo * N / sampleRate)), i1 = Math.max(i0 + 1, (int) (hi * N / sampleRate));
            double sum = 0;
            for (int i = i0; i < i1 && i < N / 2; i++) sum = Math.max(sum, Math.hypot(re[i], im[i]));
            // treble is quieter in music: tilt it up so the bars reach across
            raw[b] = (float) (sum * (1 + b / 12.0));
            peak = Math.max(peak, raw[b]);
        }
        // follow the music's loudness, so quiet and loud tracks both fill the bars
        loudest = Math.max(peak, loudest * 0.995f);
        loudest = Math.max(loudest, 1e-4f);
        float lows = 0;
        for (int b = 0; b < BANDS; b++) {
            float v = (float) Math.max(0, 1 + Math.log10(raw[b] / loudest + 1e-6) / 1.6);   // 32 dB of range
            level[b] = v > level[b] ? level[b] + (v - level[b]) * 0.6f : level[b] * 0.86f;
            if (b < 6) lows += level[b] / 6;
        }
        bass = lows > bass ? lows : bass * 0.88f;
        float now = 0;
        for (int i = 0; i < wave.length; i++) now = Math.max(now, Math.abs(samples[N - wave.length + i]));
        wavePeak = Math.max(1e-3f, Math.max(now, wavePeak * 0.97f));
        for (int i = 0; i < wave.length; i++) wave[i] = samples[N - wave.length + i] / wavePeak;
    }

    /** The strip behind the tiles: translucent bars standing on the dock's edge. */
    public void paintAmbient(Graphics2D g, int w, float baseY, float maxH, Color accent) {
        float gap = Math.max(2, w / 400f), bw = (w - gap * (BANDS + 1)) / BANDS;
        Graphics2D c = (Graphics2D) g.create();
        try {
            for (int b = 0; b < BANDS; b++) {
                float h = Math.max(2, level[b] * maxH);
                float x = gap + b * (bw + gap);
                c.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 40 + (int) (50 * level[b])));
                c.fill(new RoundRectangle2D.Float(x, baseY - h, bw, h + bw / 2, bw * 0.6f, bw * 0.6f));
            }
        } finally {
            c.dispose();
        }
    }

    /** Full screen: a dark stage, the spectrum ring around WII-UU's name, the waveform and the track. */
    public void paintFull(Graphics2D g0, int w, int h, String title, String subtitle,
                          String label, String accentLabel, Color accent, String hint) {
        if (w <= 0 || h <= 0) return;
        Graphics2D g = (Graphics2D) g0.create();
        try {
            double t = (clock.getAsLong() - born) / 1e9;
            float cx = w / 2f, cy = h * 0.44f, m = Math.min(w, h);
            // stage, with a glow that breathes with the bass
            g.setColor(new Color(6, 8, 18));
            g.fillRect(0, 0, w, h);
            float glow = 0.25f + 0.55f * bass;
            g.setPaint(new RadialGradientPaint(cx, cy, m * (0.55f + 0.1f * bass), new float[]{0, 1},
                    new Color[]{new Color(0, 140, 230, (int) (150 * glow)), new Color(0, 140, 230, 0)}));
            g.fillRect(0, 0, w, h);

            // the ring: every band twice, mirrored, slowly turning, colours sweeping from cyan to magenta
            float r0 = m * (0.16f + 0.025f * bass), len = m * 0.2f;
            int spokes = BANDS * 2;
            g.setStroke(new BasicStroke(Math.max(2f, m * 0.008f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            for (int k = 0; k < spokes; k++) {
                int b = k < BANDS ? k : spokes - 1 - k;
                double a = -Math.PI / 2 + 2 * Math.PI * k / spokes + t * 0.15;
                float l = 4 + level[b] * len;
                float hue = 0.52f + 0.35f * (b / (float) BANDS) + 0.05f * (float) Math.sin(t * 0.5);
                Color col = Color.getHSBColor(hue % 1f, 0.75f, 1f);
                g.setColor(new Color(col.getRed(), col.getGreen(), col.getBlue(), 120 + (int) (135 * level[b])));
                double cos = Math.cos(a), sin = Math.sin(a);
                g.drawLine((int) (cx + cos * r0), (int) (cy + sin * r0), (int) (cx + cos * (r0 + l)), (int) (cy + sin * (r0 + l)));
            }
            // the disc in the middle with the name
            g.setColor(new Color(10, 14, 30));
            g.fillOval((int) (cx - r0 * 0.9f), (int) (cy - r0 * 0.9f), (int) (r0 * 1.8f), (int) (r0 * 1.8f));
            g.setStroke(new BasicStroke(Math.max(1.5f, m * 0.004f)));
            g.setColor(new Color(0, 168, 232, 160 + (int) (90 * bass)));
            g.drawOval((int) (cx - r0 * 0.9f), (int) (cy - r0 * 0.9f), (int) (r0 * 1.8f), (int) (r0 * 1.8f));
            Font big = new Font(Font.SANS_SERIF, Font.BOLD, Math.round(m * 0.065f));
            g.setFont(big);
            FontMetrics fm = g.getFontMetrics();
            String wii = java.util.Objects.requireNonNull(label), uu = java.util.Objects.requireNonNull(accentLabel);
            int tw = fm.stringWidth(wii + uu), tx = (int) (cx - tw / 2f), ty = (int) (cy + fm.getAscent() * 0.35f);
            g.setColor(Color.WHITE);
            g.drawString(wii, tx, ty);
            g.setColor(accent);
            g.drawString(uu, tx + fm.stringWidth(wii), ty);

            // the waveform, across the lower part
            float wy = h * 0.8f, amp = h * 0.05f;
            Path2D.Float line = new Path2D.Float();
            for (int i = 0; i < wave.length; i++) {
                float x = w * 0.1f + w * 0.8f * i / (wave.length - 1);
                float y = wy - Math.max(-1, Math.min(1, wave[i])) * amp;
                if (i == 0) line.moveTo(x, y);
                else line.lineTo(x, y);
            }
            g.setStroke(new BasicStroke(Math.max(1.5f, m * 0.003f)));
            g.setColor(new Color(120, 220, 255, 170));
            g.draw(line);

            // what's playing, and how to leave
            Font small = new Font(Font.SANS_SERIF, Font.PLAIN, Math.round(m * 0.026f));
            Font mid = new Font(Font.SANS_SERIF, Font.BOLD, Math.round(m * 0.036f));
            drawCentered(g, mid, title == null ? "" : title, cx, h * 0.9f, new Color(255, 255, 255, 235));
            drawCentered(g, small, subtitle == null ? "" : subtitle, cx, h * 0.9f + m * 0.045f, new Color(160, 190, 220, 200));
            g.setComposite(AlphaComposite.SrcOver.derive(0.55f));
            g.setFont(small);
            g.setColor(Color.WHITE);
            g.drawString(hint == null ? "" : hint, m * 0.03f, m * 0.05f);
        } finally {
            g.dispose();
        }
    }

    private static void drawCentered(Graphics2D g, Font f, String s, float cx, float y, Color c) {
        g.setFont(f);
        FontMetrics fm = g.getFontMetrics();
        String text = UiText.ellipsize(s, fm, cx * 1.8f);
        g.setColor(c);
        g.drawString(text, cx - fm.stringWidth(text) / 2f, y);
    }

    /** In-place radix-2 FFT. */
    private static void fft(double[] re, double[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double tr = re[i];
                re[i] = re[j];
                re[j] = tr;
                double ti = im[i];
                im[i] = im[j];
                im[j] = ti;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len, wr = Math.cos(ang), wi = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double cr = 1, ci = 0;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k, b = a + len / 2;
                    double xr = re[b] * cr - im[b] * ci, xi = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - xr;
                    im[b] = im[a] - xi;
                    re[a] += xr;
                    im[a] += xi;
                    double nr = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = nr;
                }
            }
        }
    }
}
