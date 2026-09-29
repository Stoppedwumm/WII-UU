package wiiuu.ui;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * The trailer's soundtrack: WII-UU's own boot chime at 0.6 s, then its menu music from 2.4 s
 * (so scene changes land on bar lines), fading out over the last seconds. Writes a 16-bit
 * stereo WAV. Compiled against wiiuu.jar by render.sh (same package, for MenuMusic and Sfx).
 *
 * usage: Soundtrack out.wav seconds
 */
public final class Soundtrack {
    public static void main(String[] args) throws IOException {
        int rate = MenuAudio.RATE;
        double length = Double.parseDouble(args[1]);
        int frames = (int) (length * rate);
        float[] mix = new float[frames * 2];

        short[] chime = Sfx.bootPcm();
        int at = (int) (0.6 * rate);
        for (int i = 0; i < chime.length && at + i < frames; i++) {
            float s = chime[i] / 32768f * 0.9f;
            mix[(at + i) * 2] += s;
            mix[(at + i) * 2 + 1] += s;
        }

        short[] music = MenuMusic.render();
        int loop = music.length / 2, start = (int) (2.4 * rate);
        double fadeFrom = length - 3.5;
        for (int i = 0; start + i < frames; i++) {
            int f = start + i;
            double t = f / (double) rate;
            float gain = (float) Math.min(1, i / (rate * 0.8));                        // quick fade-in
            if (t > fadeFrom) gain *= (float) Math.max(0, 1 - (t - fadeFrom) / (length - fadeFrom));
            gain *= 0.85f;
            mix[f * 2] += music[(i % loop) * 2] / 32768f * gain;
            mix[f * 2 + 1] += music[(i % loop) * 2 + 1] / 32768f * gain;
        }

        try (DataOutputStream o = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(args[0])))) {
            o.writeBytes("RIFF");
            o.writeInt(Integer.reverseBytes(36 + frames * 4));
            o.writeBytes("WAVEfmt ");
            o.writeInt(Integer.reverseBytes(16));
            o.writeShort(Short.reverseBytes((short) 1));
            o.writeShort(Short.reverseBytes((short) 2));
            o.writeInt(Integer.reverseBytes(rate));
            o.writeInt(Integer.reverseBytes(rate * 4));
            o.writeShort(Short.reverseBytes((short) 4));
            o.writeShort(Short.reverseBytes((short) 16));
            o.writeBytes("data");
            o.writeInt(Integer.reverseBytes(frames * 4));
            for (float v : mix) o.writeShort(Short.reverseBytes((short) (Math.max(-1, Math.min(1, v)) * 32767)));
        }
        System.out.println("wrote " + args[0]);
    }
}
