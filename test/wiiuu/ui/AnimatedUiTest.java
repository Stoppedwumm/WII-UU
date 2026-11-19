package wiiuu.ui;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicLong;

/** Behavioral checks for shared animation and analysis, compiled against only the UI JAR. */
public final class AnimatedUiTest {
    public static void main(String[] args) {
        AtomicLong time = new AtomicLong();
        UiFonts fonts = new UiFonts(Font.SANS_SERIF);
        AnimatedMascot.Style style = new AnimatedMascot.Style("MY-", "APP", Color.BLUE, false, fonts::font);
        AnimatedMascot mascot = new AnimatedMascot(() -> style, time::get);
        check(mascot.quietFor() == Long.MAX_VALUE && !mascot.talking(), "initial silence");
        check(!mascot.hit(200, 200), "hit before first paint");
        mascot.sayOne();
        mascot.say("Hello");
        check(mascot.talking() && mascot.quietFor() == 0, "speech at clock zero");
        check(mascot.needsFrame(), "arrival needs repaint");
        time.set(2000);
        BufferedImage image = new BufferedImage(800, 600, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setClip(new Rectangle(0, 0, 790, 590));
        g.setColor(Color.PINK);
        g.setFont(fonts.font(0, 19));
        var clip = g.getClip().getBounds();
        var transform = g.getTransform();
        var font = g.getFont();
        var paint = g.getPaint();
        mascot.look(600, 100);
        mascot.paint(g, 200, 400, 40, true, 400);
        check(mascot.hit(200, 350) && !mascot.hit(700, 100), "mascot hit area follows drawing");
        check(!mascot.needsFrame(), "settled mascot pauses repaints");
        mascot.hop();
        check(mascot.needsFrame(), "hop requests repaint");
        mascot.paintWalking(g, 200, 400, 40, .5f);
        check(g.getClip().getBounds().equals(clip) && g.getTransform().equals(transform)
                && g.getFont().equals(font) && g.getPaint().equals(paint), "mascot graphics preserved");
        time.set(6000);
        check(!mascot.talking() && mascot.quietFor() == 6000, "speech expires without rendering");
        g.dispose();

        for (double rate : new double[]{44100, 48000, 96000}) {
            int lower = peak(rate, 220), higher = peak(rate, 2000);
            check(higher > lower + 8, "FFT locates higher tones at higher bands");
        }
        AudioVisualizer viz = new AudioVisualizer(48000, time::get);
        viz.update(new float[0], 0);
        for (float band : viz.levels()) check(band == 0, "silent bands");
        viz.update(new float[]{Float.NaN, Float.POSITIVE_INFINITY, -2, 2}, 4);
        for (float value : viz.wave()) check(Float.isFinite(value) && Math.abs(value) <= 1, "normalized finite waveform");
        float[] levels = viz.levels();
        levels[0] = -100;
        check(viz.levels()[0] >= 0, "analysis snapshots are independent");
        try {
            viz.update(new float[1], 2);
            throw new AssertionError("invalid sample end accepted");
        } catch (IndexOutOfBoundsException expected) { }
        try {
            new AudioVisualizer(0);
            throw new AssertionError("invalid sample rate accepted");
        } catch (IllegalArgumentException expected) { }
        g = image.createGraphics();
        g.setColor(Color.PINK);
        g.setClip(new Rectangle(5, 5, 780, 580));
        var originalClip = g.getClip().getBounds();
        for (int frame = 0; frame < 30; frame++) viz.update(new float[AudioVisualizer.SAMPLE_COUNT], AudioVisualizer.SAMPLE_COUNT);
        check(viz.bass() < .1f, "bass decays after silence");
        viz.paintAmbient(g, 800, 500, 100, Color.BLUE);
        viz.paintFull(g, 800, 600, "Track", "Artist", "MY-", "APP", Color.BLUE, "Close to leave");
        check(g.getColor().equals(Color.PINK) && g.getClip().getBounds().equals(originalClip), "visualizer graphics preserved");
        g.dispose();
        System.out.println("Shared animated UI checks passed");
    }

    private static int peak(double rate, double hz) {
        float[] tone = new float[AudioVisualizer.SAMPLE_COUNT];
        for (int i = 0; i < tone.length; i++) tone[i] = (float) (.5 * Math.sin(2 * Math.PI * hz * i / rate));
        AudioVisualizer viz = new AudioVisualizer(rate);
        for (int frame = 0; frame < 12; frame++) viz.update(tone, tone.length);
        float[] levels = viz.levels();
        int peak = 0;
        for (int i = 0; i < levels.length; i++) {
            check(Float.isFinite(levels[i]) && levels[i] >= 0 && levels[i] <= 1, "bounded analysis");
            if (levels[i] > levels[peak]) peak = i;
        }
        int expected = (int) (Math.log(hz / 40) / Math.log(300) * AudioVisualizer.BANDS);
        check(Math.abs(peak - expected) <= 2, "FFT frequency calibration");
        return peak;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
