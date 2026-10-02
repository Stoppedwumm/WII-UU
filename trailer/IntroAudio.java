package wiiuu.ui;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The 3D intro's soundtrack (intro3d.html): sounds from the sound kit (SoundKit) on the moments
 * of the picture, and WII-UU's menu theme quietly under the menu. 44.1 kHz 16-bit stereo WAV.
 *
 * <pre>usage: IntroAudio soundkit-raw-dir out.wav</pre>
 */
public final class IntroAudio {
    static final int RATE = MenuAudio.RATE;
    static final double LENGTH = 15;

    public static void main(String[] args) throws IOException {
        Path kit = Path.of(args[0]);
        float[] l = new float[(int) (LENGTH * RATE)], r = new float[l.length];
        String st = "Stingers/WII-UU Stinger - ", fx = "SFX/", ui = "UI/";
        // seconds, sound, volume
        Object[][] cues = {
                {0.0, fx + "Shimmer", 0.35},
                {0.4, st + "Boot Chime", 0.8},                  // the console's light comes on
                {1.15, fx + "Riser 4s", 0.55},                  // building to the transformation's end
                {2.2, fx + "Whoosh Long", 0.6},                 // the spin
                {3.3, fx + "Shimmer", 0.4},
                {4.95, fx + "Impact Hit", 0.75},                // the TV and the phone land
                {5.05, ui + "UI Chime", 0.6},                   // the TV switches on
                {7.8, ui + "UI Select", 0.6},                   // the cursor
                {8.55, fx + "Whoosh Short", 0.6},               // the tiles break up
                {8.75, fx + "Reverse Swell", 0.6},              // into the logo
                {10.58, st + "Logo Impact", 0.9},               // the logo takes form
                {11.5, fx + "Shimmer", 0.35},                   // the light sweep
        };
        for (Object[] c : cues) add(l, r, read(kit.resolve(c[1] + ".wav")), (double) c[0], (double) c[2]);
        // a pop as each tile lands (as in intro3d.html: LAND0 + order * LAND_STEP + FALL)
        for (int k = 0; k < 12; k++) add(l, r, read(kit.resolve(fx + "Pop.wav")), 5.6 + k * 0.17 + 0.55, 0.45 + 0.03 * (k % 3));
        // the menu theme under the menu, in at 5.2, gone before the logo lands
        short[] theme = MenuMusic.render();
        int s = (int) (5.2 * RATE), e = (int) (10.4 * RATE);
        for (int i = s; i < e; i++) {
            double t = i / (double) RATE;
            double g = 0.22 * Math.min(1, (t - 5.2) / 0.6) * Math.min(1, (10.4 - t) / 1.4);
            int k = (i - s) % (theme.length / 2);
            l[i] += (float) (theme[k * 2] / 32768.0 * g);
            r[i] += (float) (theme[k * 2 + 1] / 32768.0 * g);
        }
        write(Path.of(args[1]), l, r);
    }

    /** A 16-bit stereo WAV of the kit (raw, 44.1 kHz) as [left, right]. */
    private static float[][] read(Path wav) throws IOException {
        byte[] b = Files.readAllBytes(wav);
        ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        int n = (b.length - 44) / 4;
        float[][] out = new float[2][n];
        bb.position(44);
        for (int i = 0; i < n; i++) {
            out[0][i] = bb.getShort() / 32768f;
            out[1][i] = bb.getShort() / 32768f;
        }
        return out;
    }

    private static void add(float[] l, float[] r, float[][] snd, double at, double vol) {
        int s = (int) Math.round(at * RATE);
        for (int i = 0; i < snd[0].length && s + i < l.length; i++) {
            if (s + i < 0) continue;
            l[s + i] += (float) (snd[0][i] * vol);
            r[s + i] += (float) (snd[1][i] * vol);
        }
    }

    private static void write(Path file, float[] l, float[] r) throws IOException {
        int n = l.length;
        ByteBuffer bb = ByteBuffer.allocate(44 + n * 4).order(ByteOrder.LITTLE_ENDIAN);
        bb.put("RIFF".getBytes()).putInt(36 + n * 4).put("WAVE".getBytes()).put("fmt ".getBytes()).putInt(16)
                .putShort((short) 1).putShort((short) 2).putInt(RATE).putInt(RATE * 4).putShort((short) 4).putShort((short) 16)
                .put("data".getBytes()).putInt(n * 4);
        for (int i = 0; i < n; i++) {
            // a soft limiter, and a fade over the picture's fade to black (14.3 to 15 s)
            double fade = Math.min(1, Math.max(0, (LENGTH - i / (double) RATE) / 0.7));
            bb.putShort((short) Math.round(Math.tanh(l[i] * 1.05) * 0.95 * fade * 32767));
            bb.putShort((short) Math.round(Math.tanh(r[i] * 1.05) * 0.95 * fade * 32767));
        }
        try (OutputStream o = Files.newOutputStream(file)) {
            o.write(bb.array());
        }
    }
}
