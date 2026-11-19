package wiiuu.ui;

import java.awt.Color;
import java.awt.Graphics2D;

/** WII-UU audio capture and branding around the reusable spectrum component. */
final class Visualizer extends AudioVisualizer {
    private final float[] samples = new float[SAMPLE_COUNT];

    Visualizer() {
        super(MenuAudio.RATE);
    }

    void update() {
        MenuAudio.get().scope(samples);
        update(samples, samples.length);
    }

    void paintFull(Graphics2D g, int w, int h, String title, String subtitle) {
        paintFull(g, w, h, title, subtitle, "WII-", "UU", new Color(0x00A8E8), "V or Esc: back");
    }
}
