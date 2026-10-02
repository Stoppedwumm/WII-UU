package wiiuu.ui;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * The WII-UU Extended Mix as a music video for YouTube: a 3D opening (the logo flies in through
 * hyperspace), an intro card, the whole mix non-stop with a visualizer (spectrum ring, waveform,
 * neon grid and stars moving with the music, the track playing, a progress bar), an end card and
 * a 3D ending. Everything comes from WII-UU's own code: the remixes ({@link Tunes}, mixed as in
 * {@link ExtendedMix}), the {@link Visualizer} analysis and the boot chime ({@link Sfx}).
 *
 * <p>Writes, into the output folder: the video (H.264/AAC, piped to ffmpeg), its soundtrack, a
 * thumbnail, chapters and a description. Compiled against wiiuu.jar by render-mix.sh.
 *
 * <pre>usage: MixVideo outdir fontdir [--fps 30] [--size 1920x1080] [--tracks 16] [--only from-to]
 *                 [--prepare | --part --out file.mp4]</pre>
 */
public final class MixVideo {
    static final int RATE = MenuAudio.RATE;
    static final double BEAT = 60 / ExtendedMix.BPM, BAR = 4 * BEAT;
    static final double INTRO = 5 * BAR;          // the 3D opening: 9.375 s, so the music starts on a bar
    static final double TAIL = 6.5;               // the 3D ending after the last note
    static final double BLEND = Tunes.EXTENDED_BARS * BAR;
    static final Color CYAN = new Color(0x00C8FF), MAGENTA = new Color(0xE040FB);

    private final int w, h, fps;
    private final Font black, heavy, semi;
    private final Logo3D logo;
    private final List<String> ids;
    private final List<Double> starts = new ArrayList<>();       // mix time each track's audio begins
    private final List<Double> shows = new ArrayList<>();        // video time each track is announced
    private double mixLen, total;
    private float[] mono;
    private Visualizer viz = new Visualizer();
    private Stars stars = new Stars(700, 7);
    private BufferedImage backdrop;

    private MixVideo(int w, int h, int fps, Path fonts, int tracks) throws Exception {
        this.w = w;
        this.h = h;
        this.fps = fps;
        black = font(fonts, "Inter-900.ttf", Font.BOLD);
        heavy = font(fonts, "Inter-800.ttf", Font.BOLD);
        semi = font(fonts, "Inter-600.ttf", Font.PLAIN);
        logo = new Logo3D(black.deriveFont(200f), "WII-UU", 4, 8f, 1.4f);
        ids = ExtendedMix.order().subList(0, Math.min(tracks, ExtendedMix.size()));
    }

    private static Font font(Path dir, String file, int fallback) {
        try {
            return Font.createFont(Font.TRUETYPE_FONT, dir.resolve(file).toFile());
        } catch (Exception e) {
            return new Font(Font.SANS_SERIF, fallback, 12);
        }
    }

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]), fonts = Path.of(args[1]);
        int fps = 30, w = 1920, h = 1080, tracks = ExtendedMix.size();
        double from = 0, to = Double.MAX_VALUE;
        boolean prepare = false, part = false;
        Path file = null;
        for (int i = 2; i < args.length; i++) {
            if (args[i].equals("--prepare")) {
                prepare = true;
                continue;
            }
            if (args[i].equals("--part")) {
                part = true;
                continue;
            }
            switch (args[i++]) {
                case "--fps" -> fps = Integer.parseInt(args[i]);
                case "--out" -> file = Path.of(args[i]);
                case "--size" -> {
                    String[] p = args[i].split("x");
                    w = Integer.parseInt(p[0]);
                    h = Integer.parseInt(p[1]);
                }
                case "--tracks" -> tracks = Integer.parseInt(args[i]);
                case "--only" -> {
                    String[] p = args[i].split("-");
                    from = Double.parseDouble(p[0]);
                    to = Double.parseDouble(p[1]);
                }
                default -> throw new IllegalArgumentException(args[i - 1]);
            }
        }
        Files.createDirectories(out);
        MixVideo v = new MixVideo(w, h, fps, fonts, tracks);
        Path wav = out.resolve("WII-UU-Extended-Mix.wav");
        v.soundtrack(part ? null : wav);
        if (prepare) {
            // the soundtrack, chapters, description and thumbnail; the length for render-mix.sh
            v.texts(out);
            v.thumbnail(out.resolve("WII-UU-Extended-Mix-thumbnail.jpg"));
            System.out.printf(Locale.ROOT, "%.3f%n", v.total);
            return;
        }
        if (!part) {
            v.texts(out);
            v.thumbnail(out.resolve("WII-UU-Extended-Mix-thumbnail.jpg"));
        }
        String name = from > 0 || to < v.total ? String.format(Locale.ROOT, "WII-UU-Extended-Mix-%.0f-%.0f.mp4", from, Math.min(to, v.total))
                : "WII-UU-Extended-Mix.mp4";
        // a part (render-mix.sh renders several at once) is video only: the soundtrack is added to the whole
        v.video(part ? null : wav, file != null ? file : out.resolve(name), from, Math.min(to, v.total));
    }

    // ---- soundtrack -------------------------------------------------------------------------

    private void soundtrack(Path wav) throws IOException {
        List<short[]> parts = new ArrayList<>();
        double at = 0;
        for (int i = 0; i < ids.size(); i++) {
            long t0 = System.nanoTime();
            short[] pcm = Tunes.extended(ids.get(i), ExtendedMix.BPM);
            parts.add(pcm);
            starts.add(at);
            double len = pcm.length / 2.0 / RATE;
            System.err.printf(Locale.ROOT, "  track %2d/%d  %-22s %5.1f s  (%d ms)%n", i + 1, ids.size(), ids.get(i), len,
                    (System.nanoTime() - t0) / 1_000_000);
            mixLen = at + len;
            at += len - BLEND;
        }
        total = INTRO + mixLen + TAIL;
        int frames = (int) Math.ceil(total * RATE) + 1;
        float[] l = new float[frames], r = new float[frames];

        // the mix: each track fades in over the previous one's outro, beat on beat (as in ExtendedMix)
        int blend = (int) Math.round(BLEND * RATE), off = (int) Math.round(INTRO * RATE);
        for (int i = 0; i < parts.size(); i++) {
            short[] pcm = parts.get(i);
            int len = pcm.length / 2, start = off + (int) Math.round(starts.get(i) * RATE);
            for (int f = 0; f < len; f++) {
                double g = 1;
                if (i > 0 && f < blend) g = Math.sin((f + 0.5) / blend * Math.PI / 2);
                if (i < parts.size() - 1 && f >= len - blend) g = Math.cos((f - (len - blend) + 0.5) / blend * Math.PI / 2);
                l[start + f] += (float) (pcm[f * 2] / 32768.0 * g);
                r[start + f] += (float) (pcm[f * 2 + 1] / 32768.0 * g);
            }
            parts.set(i, null);
        }
        Sound.opening(l, r);
        Sound.ending(l, r, INTRO + mixLen);
        mono = new float[frames];
        try (OutputStream o = wav == null ? OutputStream.nullOutputStream() : new BufferedOutputStream(Files.newOutputStream(wav), 1 << 20)) {
            o.write(wavHeader(frames));
            byte[] buf = new byte[4096 * 4];
            int k = 0;
            for (int f = 0; f < frames; f++) {
                // a gentle limiter: the sound effects sit on top of a full-level mix
                float a = (float) Math.tanh(l[f] * 1.1) * 0.92f, b = (float) Math.tanh(r[f] * 1.1) * 0.92f;
                mono[f] = (a + b) / 2;
                short sa = (short) (a * 32767), sb = (short) (b * 32767);
                buf[k++] = (byte) sa;
                buf[k++] = (byte) (sa >> 8);
                buf[k++] = (byte) sb;
                buf[k++] = (byte) (sb >> 8);
                if (k == buf.length) {
                    o.write(buf);
                    k = 0;
                }
            }
            o.write(buf, 0, k);
        }
        for (int i = 0; i < ids.size(); i++) shows.add(INTRO + (i == 0 ? BLEND : starts.get(i) + BLEND / 2));
        System.err.printf(Locale.ROOT, "  soundtrack %s (%s)%n", wav == null ? "made" : wav, clock(total));
    }

    private static byte[] wavHeader(int frames) {
        ByteBuffer b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        int data = frames * 4;
        b.put("RIFF".getBytes()).putInt(36 + data).put("WAVE".getBytes()).put("fmt ".getBytes()).putInt(16)
                .putShort((short) 1).putShort((short) 2).putInt(RATE).putInt(RATE * 4).putShort((short) 4).putShort((short) 16)
                .put("data".getBytes()).putInt(data);
        return b.array();
    }

    // ---- chapters, description --------------------------------------------------------------

    /** "Korobeiniki (the Tetris theme)" and "Color House" from a track id. */
    private static String[] split(String title) {
        int dash = title.lastIndexOf(" – ");
        String tune = dash > 0 ? title.substring(0, dash) : title, style = dash > 0 ? title.substring(dash + 3) : "";
        return new String[]{tune, style.replace(" remix", "")};
    }

    private String title(int i) {
        return ExtendedMix.title(ExtendedMix.order().indexOf(ids.get(i)));
    }

    private void texts(Path out) throws IOException {
        StringBuilder ch = new StringBuilder("0:00 Intro\n");
        for (int i = 0; i < ids.size(); i++) {
            String[] p = split(title(i));
            ch.append(clock(shows.get(i))).append(String.format(Locale.ROOT, " %02d. %s \u2013 %s%n", i + 1, p[0], p[1]));
        }
        ch.append(clock(INTRO + mixLen - BLEND)).append(" Outro\n");
        Files.writeString(out.resolve("WII-UU-Extended-Mix-chapters.txt"), ch.toString());
        String desc = """
                WII-UU Extended Mix: %d dance remixes, non-stop at 128 BPM, with a visualizer.

                Every track is a Future House or Color House remix that WII-UU makes itself, live, from \
                public-domain melodies (Tetris' Korobeiniki, Grieg, Beethoven, Mozart, Pachelbel and more) \
                and WII-UU's own menu theme. Mixed beat-matched, DJ style, by WII-UU's Extended Mix.

                Play it yourself: in WII-UU, Settings > General > Menu music > WII-UU Extended Mix, and press V \
                for the visualizer.

                ▶ Download WII-UU (free): https://wiiuu.stoppedwumm.net
                ▶ Source: https://github.com/Stoppedwumm/WII-UU

                Tracklist
                %s
                WII-UU turns any PC or Raspberry Pi into a Wii U–style console, and your phone into the GamePad.

                Music: original arrangements by WII-UU of melodies in the public domain; no samples.
                WII-UU is not affiliated with Nintendo.
                """.formatted(ids.size(), ch);
        Files.writeString(out.resolve("WII-UU-Extended-Mix-description.txt"), desc);
        System.err.print(ch);
    }

    private static String clock(double s) {
        int t = (int) Math.floor(s);
        return t >= 3600 ? String.format("%d:%02d:%02d", t / 3600, t / 60 % 60, t % 60) : String.format("%d:%02d", t / 60, t % 60);
    }

    // ---- video ------------------------------------------------------------------------------

    private void video(Path wav, Path mp4, double from, double to) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("ffmpeg", "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "bgr0",
                "-s", w + "x" + h, "-r", Integer.toString(fps), "-i", "-"));
        if (wav != null) {
            cmd.addAll(List.of("-ss", String.format(Locale.ROOT, "%.3f", from), "-t", String.format(Locale.ROOT, "%.3f", to - from),
                    "-i", wav.toString(), "-map", "0:v", "-map", "1:a", "-c:a", "aac", "-b:a", "256k", "-shortest"));
        }
        cmd.addAll(List.of("-c:v", "libx264", "-preset", System.getenv().getOrDefault("X264_PRESET", "medium"),
                "-crf", System.getenv().getOrDefault("CRF", "21"), "-pix_fmt", "yuv420p", "-g", Integer.toString(fps * 2),
                "-movflags", "+faststart", mp4.toString()));
        Process ff = new ProcessBuilder(cmd).inheritIO().redirectInput(ProcessBuilder.Redirect.PIPE).start();
        BufferedImage frame = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int[] rgb = ((DataBufferInt) frame.getRaster().getDataBuffer()).getData();
        ByteBuffer bytes = ByteBuffer.allocate(w * h * 4).order(ByteOrder.LITTLE_ENDIAN);
        int f0 = (int) Math.round(from * fps), f1 = (int) Math.round(to * fps);
        // the visualizer and the stars carry state from frame to frame: run them from the start, so a
        // part rendered on its own continues exactly where the one before it ends
        for (int f = 0; f < f0; f++) {
            double t = f / (double) fps;
            viz.update(mono, (int) Math.round(t * RATE));
            stars.step(1.0 / fps, starSpeed(t));
        }
        long started = System.nanoTime();
        try (OutputStream o = new BufferedOutputStream(ff.getOutputStream(), 1 << 22)) {
            for (int f = f0; f < f1; f++) {
                double t = f / (double) fps;
                viz.update(mono, (int) Math.round(t * RATE));
                stars.step(1.0 / fps, starSpeed(t));
                draw(frame, rgb, t);
                bytes.clear();
                bytes.asIntBuffer().put(rgb);
                o.write(bytes.array());
                if ((f - f0) % (fps * 10) == 0) {
                    double el = (System.nanoTime() - started) / 1e9, done = (f - f0 + 1) / (double) (f1 - f0);
                    System.err.printf(Locale.ROOT, "\r  video %s / %s  (%.0f%%, %.1f fps, about %s left)   ", clock(t), clock(to), done * 100,
                            (f - f0 + 1) / el, clock(el / done - el));
                }
            }
        }
        int code = ff.waitFor();
        System.err.printf(Locale.ROOT, "%n  %s %s%n", mp4, code == 0 ? "done" : "ffmpeg failed: " + code);
        if (code != 0) System.exit(code);
    }

    /** Hyperspace in the opening, cruising with the music, warp at the end. */
    private double starSpeed(double t) {
        if (t < INTRO) return 0.4 + 5.0 * Math.pow(Math.max(0, 1 - t / 4.2), 2);
        double end = INTRO + mixLen;
        if (t >= end) return 0.25 + 4 * Math.pow(Math.max(0, (t - end - 3.2) / 2.0), 2);
        return 0.18 + 0.5 * viz.bass();
    }

    private void draw(BufferedImage frame, int[] rgb, double t) {
        Graphics2D g = frame.createGraphics();
        background(g);
        g.dispose();
        double end = INTRO + mixLen;
        if (t < INTRO) opening(frame, rgb, t);
        else if (t >= end) ending(frame, rgb, t - end);
        else scene(frame, rgb, t);
        bloom(rgb, w, h);
    }

    private static Graphics2D pen(BufferedImage frame) {
        Graphics2D g = frame.createGraphics();
        quality(g);
        return g;
    }

    private static void quality(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
    }

    private void background(Graphics2D g) {
        if (backdrop == null) {
            backdrop = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D b = backdrop.createGraphics();
            b.setPaint(new GradientPaint(0, 0, new Color(5, 7, 18), 0, h, new Color(12, 10, 34)));
            b.fillRect(0, 0, w, h);
            b.setPaint(new RadialGradientPaint(w / 2f, h * 0.45f, w * 0.6f, new float[]{0, 1},
                    new Color[]{new Color(20, 40, 90, 120), new Color(20, 40, 90, 0)}));
            b.fillRect(0, 0, w, h);
            b.dispose();
        }
        g.drawImage(backdrop, 0, 0, null);
    }

    // ---- the parts --------------------------------------------------------------------------

    private static double ease(double x) {
        x = Math.max(0, Math.min(1, x));
        return 1 - Math.pow(1 - x, 3);
    }

    private static double smooth(double x) {
        x = Math.max(0, Math.min(1, x));
        return x * x * (3 - 2 * x);
    }

    private float m() {
        return Math.min(w, h);
    }

    /** 0 – 9.4 s: hyperspace, the 3D logo spins in, impact on the chime, the title, a flash into the mix. */
    private void opening(BufferedImage frame, int[] rgb, double t) {
        Graphics2D g = pen(frame);
        stars.draw(g, w, h, (float) Math.min(1, t / 0.8));
        grid(g, t, smooth((t - 3.6) / 1.4) * 0.8, (t / BEAT) % 1);
        if (t >= 4.0) {
            // the shockwave of the impact, spreading behind the logo
            double u = t - 4.0;
            float a = (float) Math.max(0, 1 - u / 0.9);
            if (a > 0) {
                float rr = (float) (u * w * 0.75 + m() * 0.1);
                g.setStroke(new BasicStroke((float) (m() * 0.012 * a + 1)));
                g.setColor(new Color(150, 220, 255, (int) (220 * a)));
                g.draw(new java.awt.geom.Ellipse2D.Float(w / 2f - rr, h * 0.47f - rr * 0.35f, rr * 2, rr * 0.7f));
            }
        }
        g.dispose();
        double z, ry, rx, op = 1, sheen = -99, ty = 0.5, tx = 0;
        if (t < 0.6) op = 0;
        if (t < 4.0) {
            double e = ease((t - 0.6) / 3.4);
            z = 130 - (130 - 11) * e;
            ry = 4 * Math.PI * (1 - e);
            rx = 0.4 * (1 - e);
            op = Math.min(op, Math.max(0, (t - 0.6) / 0.4));
        } else {
            double u = t - 4.0;
            z = 11 + 0.25 * Math.sin(u * 1.3);
            ry = 0.13 * Math.sin(u * 0.9);
            rx = -0.06 + 0.05 * Math.sin(u * 0.7);
            sheen = -5.5 + (t - 4.7) * 7.5;
            double shake = 0.18 * Math.exp(-u * 6);
            tx = shake * Math.sin(u * 61);
            ty += shake * Math.cos(u * 47);
            if (t > 8.55) z -= 8.4 * Math.pow((t - 8.55) / (INTRO - 8.55), 2);
        }
        if (op > 0) logo3d(rgb, rx, ry, 0, tx, ty, z, sheen, op);
        g = pen(frame);
        if (t >= 4.0) {
            double u = t - 4.0;
            flash(g, 0.75 * Math.exp(-u * 5));
            // the title under the logo
            double k = ease((t - 5.0) / 0.7);
            if (k > 0 && t < 8.7) {
                float spacing = (float) (0.9 - 0.55 * k);
                spaced(g, heavy.deriveFont(m() * 0.052f), "EXTENDED MIX", w / 2f, h * 0.73f, spacing,
                        new Color(255, 255, 255, (int) (255 * k * fade(t, 8.2, 8.7))));
            }
            double k2 = ease((t - 5.8) / 0.7);
            if (k2 > 0 && t < 8.7) {
                centered(g, semi.deriveFont(m() * 0.026f), ids.size() + " dance remixes  ·  non-stop  ·  128 BPM",
                        w / 2f, h * 0.8f, new Color(150, 210, 255, (int) (230 * k2 * fade(t, 8.2, 8.7))));
            }
        }
        if (t > 8.85) flash(g, Math.pow((t - 8.85) / (INTRO - 8.85), 2));
        g.dispose();
    }

    private static double fade(double t, double from, double to) {
        return 1 - smooth((t - from) / (to - from));
    }

    /** The mix, with the intro card over the first track's intro and the end card over the last one's outro. */
    private void scene(BufferedImage frame, int[] rgb, double t) {
        Graphics2D g = pen(frame);
        double mt = t - INTRO, endCard = mixLen - BLEND;
        double card = mt < BLEND ? fade(mt, BLEND - 3, BLEND) : 0;
        double outro = smooth((mt - endCard) / 1.2);
        stars.draw(g, w, h, 1);
        double beat = (mt / BEAT) % 1;
        grid(g, mt, 0.55 + 0.45 * viz.bass(), beat);
        horizonWave(g, (1 - card) * (1 - outro));
        ring(g, (1 - card) * (1 - outro), mt);
        // corners: the brand and the website, the progress along the bottom
        brand(g, (1 - card) * (1 - outro));
        g.setFont(semi.deriveFont(m() * 0.02f));
        g.setColor(new Color(255, 255, 255, (int) (150 * (1 - card) * (1 - outro))));
        FontMetrics fm = g.getFontMetrics();
        g.drawString("wiiuu.stoppedwumm.net", w - m() * 0.045f - fm.stringWidth("wiiuu.stoppedwumm.net"), m() * 0.066f);
        progress(g, t, 1 - outro);
        nowPlaying(g, t, (1 - card) * (1 - outro));
        g.dispose();
        if (card > 0) introCard(frame, rgb, mt, card);
        if (outro > 0) endCard(frame, rgb, mt - endCard, outro);
        g = pen(frame);
        flash(g, Math.max(0, 1 - mt / 0.5));                      // out of the opening's flash
        g.dispose();
    }

    /** After the last note: the logo spins once, flies off into the stars, black. */
    private void ending(BufferedImage frame, int[] rgb, double u) {
        Graphics2D g = pen(frame);
        stars.draw(g, w, h, 1);
        grid(g, u, 0.5 * fade(u, 0, 2.5), (u / BEAT) % 1);
        g.dispose();
        double z = 16 - 5 * smooth(u / 1.2), ty = 2.4 - 1.9 * smooth(u / 1.2);
        double ry = 2 * Math.PI * smooth((u - 1.0) / 2.2);
        double away = Math.max(0, u - 3.3);
        z += 160 * away * away;
        double op = fade(u, 4.6, 5.4);
        if (op > 0) logo3d(rgb, -0.05, ry, 0, 0, ty, z, -5.5 + (u - 0.4) * 6, op);
        g = pen(frame);
        endTexts(g, fade(u, 0.2, 1.4));
        double k = ease((u - 1.4) / 0.8) * fade(u, 3.0, 3.6);
        if (k > 0) centered(g, heavy.deriveFont(m() * 0.05f), "See you in the menu!", w / 2f, h * 0.76f, new Color(255, 255, 255, (int) (255 * k)));
        flash(g, 0.5 * Math.exp(-u * 4));
        fill(g, new Color(0, 0, 0), smooth((u - 5.2) / (TAIL - 5.4)));
        g.dispose();
    }

    // ---- pieces -----------------------------------------------------------------------------

    /** The 3D logo, rendered at twice the size for smooth edges. */
    private int[] up, upZ;
    private float[] zbuf;

    private void logo3d(int[] rgb, double rx, double ry, double rz, double tx, double ty, double tz, double sheen, double op) {
        int W = w * 2, H = h * 2;
        if (up == null) {
            up = new int[W * H];
            zbuf = new float[W * H];
        }
        for (int y = 0; y < H; y++) {
            int src = (y >> 1) * w;
            for (int x = 0; x < W; x++) up[y * W + x] = rgb[src + (x >> 1)];
        }
        logo.render(up, zbuf, W, H, rx, ry, rz, tx, ty, tz, H * 1.25, sheen, op);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int a = up[(y * 2) * W + x * 2], b = up[(y * 2) * W + x * 2 + 1], c = up[(y * 2 + 1) * W + x * 2], d = up[(y * 2 + 1) * W + x * 2 + 1];
                int r = ((a >> 16 & 255) + (b >> 16 & 255) + (c >> 16 & 255) + (d >> 16 & 255)) >> 2;
                int gg = ((a >> 8 & 255) + (b >> 8 & 255) + (c >> 8 & 255) + (d >> 8 & 255)) >> 2;
                int bb = ((a & 255) + (b & 255) + (c & 255) + (d & 255)) >> 2;
                rgb[y * w + x] = (r << 16) | (gg << 8) | bb;
            }
        }
    }

    /** A neon grid floor running toward the viewer, one line per beat. */
    private void grid(Graphics2D g, double t, double bright, double beatPhase) {
        if (bright <= 0.01) return;
        float horizon = h * 0.66f, cx = w / 2f, f = h * 0.9f, cam = 1.1f;
        Graphics2D c = (Graphics2D) g.create();
        c.setPaint(new GradientPaint(0, horizon - h * 0.02f, new Color(224, 64, 251, 0), 0, horizon + 2, new Color(224, 64, 251, (int) (90 * bright))));
        c.fillRect(0, (int) (horizon - h * 0.02f), w, (int) (h * 0.02f) + 2);
        c.setStroke(new BasicStroke(Math.max(1f, m() * 0.0018f)));
        double near = cam * f / (h - horizon), spacing = 1.6;      // the floor seen from the bottom edge to far away
        for (int k = 0; k < 14; k++) {
            double d = near + (k + 1 - beatPhase) * spacing;
            float y = (float) (horizon + cam * f / d);
            if (y > h) continue;
            int a = (int) (210 * bright * Math.min(1, 6 / d) * Math.min(1, (y - horizon) / (h * 0.025)));
            c.setColor(new Color(224, 64, 251, Math.max(0, Math.min(255, a))));
            c.draw(new java.awt.geom.Line2D.Float(0, y, w, y));
        }
        for (int j = -30; j <= 30; j++) {
            double X = j * spacing * 0.55, far = 40;
            float x0 = (float) (cx + X * f / near), x1 = (float) (cx + X * f / far), y1 = (float) (horizon + cam * f / far);
            c.setPaint(new GradientPaint(x1, y1, new Color(0, 200, 255, 0), x0, h, new Color(0, 200, 255, (int) (170 * bright))));
            c.draw(new java.awt.geom.Line2D.Float(x0, h, x1, y1));
        }
        c.dispose();
    }

    private void horizonWave(Graphics2D g, double alpha) {
        float[] wave = viz.wave();
        float horizon = h * 0.66f, amp = h * 0.045f;
        Path2D.Float p = new Path2D.Float();
        for (int i = 0; i < wave.length; i++) {
            float x = w * 0.08f + w * 0.84f * i / (wave.length - 1);
            float env = (float) Math.sin(Math.PI * i / (wave.length - 1));
            float y = horizon - Math.max(-1, Math.min(1, wave[i])) * amp * env;
            if (i == 0) p.moveTo(x, y);
            else p.lineTo(x, y);
        }
        Graphics2D c = (Graphics2D) g.create();
        c.setStroke(new BasicStroke(Math.max(2f, m() * 0.0035f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        c.setColor(new Color(140, 230, 255, (int) (210 * alpha)));
        c.draw(p);
        c.dispose();
    }

    /** The spectrum ring around the WII-UU disc, pulsing with the bass. */
    private void ring(Graphics2D g, double alpha, double t) {
        if (alpha <= 0.01) return;
        float[] level = viz.levels();
        float bass = viz.bass(), cx = w / 2f, cy = h * 0.4f, m = m();
        float r0 = m * (0.135f + 0.02f * bass), len = m * 0.2f;
        int bands = level.length, spokes = bands * 2;
        Graphics2D c = (Graphics2D) g.create();
        c.setComposite(AlphaComposite.SrcOver.derive((float) alpha));
        c.setPaint(new RadialGradientPaint(cx, cy, r0 * 2.6f, new float[]{0, 1},
                new Color[]{new Color(0, 150, 255, (int) (60 + 110 * bass)), new Color(0, 150, 255, 0)}));
        c.fillOval((int) (cx - r0 * 2.6f), (int) (cy - r0 * 2.6f), (int) (r0 * 5.2f), (int) (r0 * 5.2f));
        double spin = t * 0.08;                                  // a slow turn, by the music's clock
        for (int pass = 0; pass < 2; pass++) {
            c.setStroke(new BasicStroke(pass == 0 ? m * 0.016f : m * 0.0065f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            for (int k = 0; k < spokes; k++) {
                int b = k < bands ? k : spokes - 1 - k;
                double a = -Math.PI / 2 + 2 * Math.PI * k / spokes + spin;
                float l = 3 + level[b] * len;
                float hue = 0.53f + 0.33f * (b / (float) bands);
                Color col = Color.getHSBColor(hue % 1f, pass == 0 ? 0.8f : 0.45f, 1f);
                c.setColor(new Color(col.getRed(), col.getGreen(), col.getBlue(), pass == 0 ? 70 : 150 + (int) (105 * level[b])));
                double cs = Math.cos(a), sn = Math.sin(a);
                c.draw(new java.awt.geom.Line2D.Double(cx + cs * r0, cy + sn * r0, cx + cs * (r0 + l), cy + sn * (r0 + l)));
            }
        }
        float d = r0 * 0.92f;
        c.setColor(new Color(8, 12, 30));
        c.fill(new java.awt.geom.Ellipse2D.Float(cx - d, cy - d, d * 2, d * 2));
        c.setStroke(new BasicStroke(m * 0.004f));
        c.setColor(new Color(0, 190, 255, 150 + (int) (100 * bass)));
        c.draw(new java.awt.geom.Ellipse2D.Float(cx - d, cy - d, d * 2, d * 2));
        logoText(c, black.deriveFont(m * 0.06f), cx, cy + m * 0.012f, 255);
        spaced(c, heavy.deriveFont(m * 0.017f), "EXTENDED MIX", cx, cy + m * 0.055f, 0.3f, new Color(150, 210, 255, 220));
        c.dispose();
    }

    private void logoText(Graphics2D g, Font f, float cx, float baseline, int alpha) {
        g.setFont(f);
        FontMetrics fm = g.getFontMetrics();
        int tw = fm.stringWidth("WII-UU");
        g.setColor(new Color(255, 255, 255, alpha));
        g.drawString("WII-", cx - tw / 2f, baseline);
        g.setColor(new Color(0, 168, 232, alpha));
        g.drawString("UU", cx - tw / 2f + fm.stringWidth("WII-"), baseline);
    }

    private void brand(Graphics2D g, double alpha) {
        if (alpha <= 0.01) return;
        int a = (int) (230 * alpha);
        float x = m() * 0.045f, y = m() * 0.07f;
        g.setFont(black.deriveFont(m() * 0.032f));
        FontMetrics fm = g.getFontMetrics();
        g.setColor(new Color(255, 255, 255, a));
        g.drawString("WII-", x, y);
        g.setColor(new Color(0, 168, 232, a));
        g.drawString("UU", x + fm.stringWidth("WII-"), y);
        g.setFont(heavy.deriveFont(m() * 0.0145f));
        g.setColor(new Color(150, 210, 255, (int) (200 * alpha)));
        g.drawString("EXTENDED MIX", x + fm.stringWidth("WII-UU") + m() * 0.012f, y - m() * 0.004f);
    }

    private void progress(Graphics2D g, double t, double alpha) {
        if (alpha <= 0.01) return;
        g = (Graphics2D) g.create();
        g.setComposite(AlphaComposite.SrcOver.derive((float) alpha));
        float y = h - m() * 0.006f, hh = m() * 0.006f;
        g.setColor(new Color(255, 255, 255, 30));
        g.fill(new java.awt.geom.Rectangle2D.Float(0, y, w, hh));
        g.setPaint(new GradientPaint(0, 0, CYAN, w, 0, MAGENTA));
        g.fill(new java.awt.geom.Rectangle2D.Float(0, y, (float) (w * t / total), hh));
        g.setFont(semi.deriveFont(m() * 0.019f));
        FontMetrics fm = g.getFontMetrics();
        String s = clock(t) + " / " + clock(total);
        g.setColor(new Color(255, 255, 255, 170));
        g.drawString(s, w - m() * 0.045f - fm.stringWidth(s), y - m() * 0.02f);
        g.dispose();
    }

    /** The track playing, bottom left, sliding in when the next one takes over. */
    private void nowPlaying(Graphics2D g, double t, double alpha) {
        if (alpha <= 0.01) return;
        int i = 0;
        while (i + 1 < shows.size() && t >= shows.get(i + 1)) i++;
        double since = t - shows.get(i);
        double next = i + 1 < shows.size() ? shows.get(i + 1) - t : 99;
        double in = ease(since / 0.7), out = smooth(1 - next / 0.45);
        double slide = Math.min(in, 1 - out);
        if (slide <= 0) return;
        String[] p = split(title(i));
        boolean color = p[1].startsWith("Color");
        Color accent = color ? CYAN : MAGENTA;
        Font small = heavy.deriveFont(m() * 0.019f), big = black.deriveFont(m() * 0.042f);
        FontMetrics fs = g.getFontMetrics(small), fb = g.getFontMetrics(big);
        String top = String.format("TRACK %02d / %02d   ·   %s", i + 1, ids.size(), p[1].toUpperCase(Locale.ROOT));
        float pad = m() * 0.024f, bw = Math.max(fs.stringWidth(top) + spacingExtra(small, top, 0.12f), fb.stringWidth(p[0])) + pad * 2.6f;
        float bh = m() * 0.115f, x0 = m() * 0.045f, y0 = h - m() * 0.075f - bh;
        float x = (float) (x0 - (bw + x0) * (1 - slide));
        Graphics2D c = (Graphics2D) g.create();
        c.setComposite(AlphaComposite.SrcOver.derive((float) alpha));
        c.setColor(new Color(6, 10, 26, 200));
        c.fill(new RoundRectangle2D.Float(x, y0, bw, bh, m() * 0.02f, m() * 0.02f));
        c.setColor(accent);
        c.fill(new RoundRectangle2D.Float(x, y0, m() * 0.008f, bh, m() * 0.008f, m() * 0.008f));
        spacedLeft(c, small, top, x + pad * 1.4f, y0 + bh * 0.38f, 0.12f, accent);
        c.setFont(big);
        c.setColor(Color.WHITE);
        c.drawString(p[0], x + pad * 1.4f, y0 + bh * 0.8f);
        c.dispose();
    }

    private void introCard(BufferedImage frame, int[] rgb, double mt, double alpha) {
        double u = mt;
        logo3d(rgb, -0.08 + 0.04 * Math.sin(u * 0.8), 0.22 * Math.sin(u * 0.55), 0, 0, 1.3, 13 + 30 * (1 - alpha), -6 + ((u * 2.2) % 14), alpha);
        Graphics2D g = pen(frame);
        Color white = new Color(255, 255, 255, (int) (255 * alpha));
        spaced(g, heavy.deriveFont(m() * 0.05f), "EXTENDED MIX", w / 2f, h * 0.67f, 0.35f, white);
        centered(g, semi.deriveFont(m() * 0.027f), ids.size() + " dance remixes  ·  non-stop  ·  128 BPM",
                w / 2f, h * 0.745f, new Color(150, 210, 255, (int) (230 * alpha)));
        centered(g, semi.deriveFont(m() * 0.022f), "Future House × Color House  ·  made by WII-UU itself",
                w / 2f, h * 0.795f, new Color(255, 255, 255, (int) (150 * alpha)));
        g.dispose();
    }

    private void endCard(BufferedImage frame, int[] rgb, double u, double alpha) {
        logo3d(rgb, -0.05, 0.25 * Math.sin(u * 0.6), 0, 0, 2.4, 16, -6 + ((u * 2.2) % 14), alpha);
        Graphics2D g = pen(frame);
        endTexts(g, alpha);
        g.dispose();
    }

    private void endTexts(Graphics2D g, double alpha) {
        if (alpha <= 0.01) return;
        // a soft dark band behind the words, over the bright horizon
        Color band = new Color(4, 6, 16, (int) (200 * alpha)), clear = new Color(4, 6, 16, 0);
        g.setPaint(new java.awt.LinearGradientPaint(0, h * 0.5f, 0, h * 0.86f, new float[]{0, 0.3f, 0.75f, 1},
                new Color[]{clear, band, band, clear}));
        g.fill(new java.awt.geom.Rectangle2D.Float(0, h * 0.5f, w, h * 0.36f));
        centered(g, black.deriveFont(m() * 0.055f), "Thanks for listening!", w / 2f, h * 0.6f, new Color(255, 255, 255, (int) (255 * alpha)));
        centered(g, semi.deriveFont(m() * 0.03f), "Get WII-UU free  ·  wiiuu.stoppedwumm.net", w / 2f, h * 0.68f,
                new Color(120, 220, 255, (int) (255 * alpha)));
        centered(g, semi.deriveFont(m() * 0.022f), "Turn your PC into a Wii U–style console, with your phone as the GamePad",
                w / 2f, h * 0.735f, new Color(255, 255, 255, (int) (190 * alpha)));
        centered(g, semi.deriveFont(m() * 0.019f), "Music: WII-UU's own arrangements of public-domain melodies  ·  Tracklist in the description",
                w / 2f, h * 0.79f, new Color(255, 255, 255, (int) (130 * alpha)));
    }

    private void flash(Graphics2D g, double a) {
        fill(g, Color.WHITE, a);
    }

    private void fill(Graphics2D g, Color c, double a) {
        if (a <= 0.003) return;
        g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), (int) (255 * Math.min(1, a))));
        g.fillRect(0, 0, w, h);
    }

    private void centered(Graphics2D g, Font f, String s, float cx, float y, Color c) {
        g.setFont(f);
        g.setColor(c);
        g.drawString(s, cx - g.getFontMetrics().stringWidth(s) / 2f, y);
    }

    private static float spacingExtra(Font f, String s, float em) {
        return f.getSize2D() * em * (s.length() - 1);
    }

    /** Text with letter spacing ({@code em} of the font size between letters), centred. */
    private void spaced(Graphics2D g, Font f, String s, float cx, float y, float em, Color c) {
        g.setFont(f);
        float tw = g.getFontMetrics().stringWidth(s) + spacingExtra(f, s, em);
        spacedLeft(g, f, s, cx - tw / 2, y, em, c);
    }

    private void spacedLeft(Graphics2D g, Font f, String s, float x, float y, float em, Color c) {
        g.setFont(f);
        g.setColor(c);
        FontMetrics fm = g.getFontMetrics();
        for (int i = 0; i < s.length(); i++) {
            String ch = s.substring(i, i + 1);
            g.drawString(ch, x, y);
            x += fm.stringWidth(ch) + f.getSize2D() * em;
        }
    }

    // ---- glow -------------------------------------------------------------------------------

    private int[] small;
    private float[] br, bg, bb, tmp;

    /** Bright parts bleed light around them: a blurred quarter-size copy added on top. */
    private void bloom(int[] rgb, int w, int h) {
        int sw = w / 4, sh = h / 4, n = sw * sh;
        if (br == null) {
            br = new float[n];
            bg = new float[n];
            bb = new float[n];
            tmp = new float[n];
        }
        for (int y = 0; y < sh; y++) {
            for (int x = 0; x < sw; x++) {
                float r = 0, g = 0, b = 0;
                for (int dy = 0; dy < 4; dy++) {
                    int row = (y * 4 + dy) * w + x * 4;
                    for (int dx = 0; dx < 4; dx++) {
                        int p = rgb[row + dx];
                        r += p >> 16 & 255;
                        g += p >> 8 & 255;
                        b += p & 255;
                    }
                }
                r /= 16;
                g /= 16;
                b /= 16;
                float lum = 0.3f * r + 0.59f * g + 0.11f * b, k = Math.max(0, lum - 110) / Math.max(1, lum);
                int i = y * sw + x;
                br[i] = r * k;
                bg[i] = g * k;
                bb[i] = b * k;
            }
        }
        for (float[] ch : new float[][]{br, bg, bb}) {
            for (int pass = 0; pass < 2; pass++) {
                blur(ch, tmp, sw, sh, 6, true);
                blur(tmp, ch, sw, sh, 6, false);
            }
        }
        for (int y = 0; y < h; y++) {
            float fy = Math.min(sh - 1.001f, Math.max(0, (y + 0.5f) / 4 - 0.5f));
            int y0 = (int) fy;
            float wy = fy - y0;
            for (int x = 0; x < w; x++) {
                float fx = Math.min(sw - 1.001f, Math.max(0, (x + 0.5f) / 4 - 0.5f));
                int x0 = (int) fx;
                float wx = fx - x0;
                int i00 = y0 * sw + x0, i01 = i00 + 1, i10 = i00 + sw, i11 = i10 + 1;
                float r = lerp2(br, i00, i01, i10, i11, wx, wy), g = lerp2(bg, i00, i01, i10, i11, wx, wy), b = lerp2(bb, i00, i01, i10, i11, wx, wy);
                int p = rgb[y * w + x];
                int R = Math.min(255, (p >> 16 & 255) + (int) (r * 0.9f)), G = Math.min(255, (p >> 8 & 255) + (int) (g * 0.9f)),
                        B = Math.min(255, (p & 255) + (int) (b * 0.9f));
                rgb[y * w + x] = (R << 16) | (G << 8) | B;
            }
        }
    }

    private static float lerp2(float[] a, int i00, int i01, int i10, int i11, float wx, float wy) {
        return (a[i00] * (1 - wx) + a[i01] * wx) * (1 - wy) + (a[i10] * (1 - wx) + a[i11] * wx) * wy;
    }

    private static void blur(float[] src, float[] dst, int w, int h, int r, boolean horizontal) {
        int len = horizontal ? w : h, lines = horizontal ? h : w;
        float norm = 1f / (2 * r + 1);
        for (int l = 0; l < lines; l++) {
            float sum = 0;
            for (int k = -r; k <= r; k++) sum += src[idx(l, Math.max(0, Math.min(len - 1, k)), w, horizontal)];
            for (int i = 0; i < len; i++) {
                dst[idx(l, i, w, horizontal)] = sum * norm;
                int add = Math.min(len - 1, i + r + 1), sub = Math.max(0, i - r);
                sum += src[idx(l, add, w, horizontal)] - src[idx(l, sub, w, horizontal)];
            }
        }
    }

    private static int idx(int line, int i, int w, boolean horizontal) {
        return horizontal ? line * w + i : i * w + line;
    }

    // ---- thumbnail --------------------------------------------------------------------------

    private void thumbnail(Path jpg) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int[] rgb = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
        // a drop moment: run the analysis into the first track's drop
        double at = INTRO + BLEND + 8 * BAR + 2 * BEAT;
        for (int f = 0; f < 45; f++) viz.update(mono, (int) ((at - (45 - f) / 30.0) * RATE));
        for (int f = 0; f < 120; f++) stars.step(1 / 30.0, 0.6);
        Graphics2D g = img.createGraphics();
        quality(g);
        background(g);
        stars.draw(g, w, h, 1);
        grid(g, 0, 1, 0.3);
        horizonWave(g, 1);
        g.dispose();
        logo3d(rgb, -0.1, 0.3, -0.03, 0, 1.6, 10.5, 1.2, 1);
        g = img.createGraphics();
        quality(g);
        spaced(g, black.deriveFont(m() * 0.1f), "EXTENDED MIX", w / 2f, h * 0.76f, 0.06f, Color.WHITE);
        centered(g, heavy.deriveFont(m() * 0.045f), ids.size() + " REMIXES  ·  NON-STOP", w / 2f, h * 0.86f, new Color(120, 225, 255));
        g.dispose();
        bloom(rgb, w, h);
        BufferedImage out = new BufferedImage(1280, 720, BufferedImage.TYPE_INT_RGB);
        Graphics2D o = out.createGraphics();
        o.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        o.drawImage(img, 0, 0, 1280, 720, null);
        o.dispose();
        javax.imageio.ImageIO.write(out, "jpg", jpg.toFile());
        // the video starts its own analysis and stars from scratch
        viz = new Visualizer();
        stars = new Stars(700, 7);
    }

    // ---- stars ------------------------------------------------------------------------------

    /** Points flying toward the camera, drawn as streaks as long as their speed. */
    static final class Stars {
        final float[] x, y, z, pz;
        final Random rnd;

        Stars(int n, long seed) {
            rnd = new Random(seed);
            x = new float[n];
            y = new float[n];
            z = new float[n];
            pz = new float[n];
            for (int i = 0; i < n; i++) {
                spawn(i);
                z[i] = 0.05f + rnd.nextFloat();
                pz[i] = z[i];
            }
        }

        private void spawn(int i) {
            double a = rnd.nextDouble() * Math.PI * 2, r = 0.15 + rnd.nextDouble() * 1.6;
            x[i] = (float) (Math.cos(a) * r);
            y[i] = (float) (Math.sin(a) * r * 0.75);
            z[i] = 1.05f;
            pz[i] = z[i];
        }

        void step(double dt, double speed) {
            for (int i = 0; i < z.length; i++) {
                pz[i] = z[i];
                z[i] -= (float) (speed * dt);
                if (z[i] < 0.04f) spawn(i);
            }
        }

        void draw(Graphics2D g, int w, int h, float alpha) {
            float f = Math.min(w, h) * 0.5f, cx = w / 2f, cy = h * 0.42f;
            Graphics2D c = (Graphics2D) g.create();
            for (int i = 0; i < z.length; i++) {
                float sx = cx + x[i] / z[i] * f, sy = cy + y[i] / z[i] * f;
                float qx = cx + x[i] / pz[i] * f, qy = cy + y[i] / pz[i] * f;
                if (sx < -50 || sx > w + 50 || sy < -50 || sy > h + 50) continue;
                float near = Math.min(1, (1.05f - z[i]) * 1.4f);
                c.setStroke(new BasicStroke(0.8f + 2.2f * (1 - z[i]), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                c.setColor(new Color(170, 220, 255, (int) (230 * near * alpha)));
                c.draw(new java.awt.geom.Line2D.Float(qx, qy, sx, sy));
            }
            c.dispose();
        }
    }

    // ---- sound effects for the opening and the ending ---------------------------------------

    static final class Sound {
        private static final Random NOISE = new Random(3);

        /** A riser into the logo's arrival at 4.0 s, the impact with WII-UU's chime, a swell into the first beat. */
        static void opening(float[] l, float[] r) {
            sweep(l, r, 0.2, 3.8, 300, 9000, 0, 0.22);              // noise rising with the logo
            rise(l, r, 0.6, 3.4, 55, 220, 0.12);                    // a saw climbing two octaves
            impact(l, r, 4.0, 1.0);
            chime(l, r, 4.0, 0.95);
            pad(l, r, 4.1, INTRO - 4.1, new int[]{53, 60, 64, 67, 69, 72}, 0.05);     // F major add9, swelling
            sweep(l, r, INTRO - 2.2, 2.2, 800, 12000, 0, 0.18);     // into the first beat
        }

        /** The last chord: an impact, the chime again, a long shimmering pad, and the logo's whoosh away. */
        static void ending(float[] l, float[] r, double at) {
            impact(l, r, at, 0.8);
            chime(l, r, at + 0.05, 0.75);
            pad(l, r, at, 5.5, new int[]{41, 53, 60, 64, 67, 72}, 0.06);
            sweep(l, r, at + 3.1, 1.6, 9000, 300, 0.16, 0);
        }

        private static void add(float[] buf, int i, double v) {
            if (i >= 0 && i < buf.length) buf[i] += (float) v;
        }

        private static void chime(float[] l, float[] r, double at, double vol) {
            short[] c = Sfx.bootPcm();
            int s = (int) (at * RATE);
            for (int i = 0; i < c.length; i++) {
                add(l, s + i, c[i] / 32768.0 * vol);
                add(r, s + i, c[i] / 32768.0 * vol);
            }
        }

        private static void impact(float[] l, float[] r, double at, double vol) {
            int s = (int) (at * RATE), n = (int) (2.5 * RATE);
            double phase = 0, lp = 0;
            for (int i = 0; i < n; i++) {
                double t = i / (double) RATE;
                phase += 2 * Math.PI * (34 + 90 * Math.exp(-t * 9)) / RATE;
                double boom = Math.sin(phase) * Math.exp(-t * 1.6) * 0.9;
                lp += 0.08 * ((NOISE.nextDouble() * 2 - 1) - lp);
                double crash = lp * Math.exp(-t * 2.2) * 1.2 + (NOISE.nextDouble() * 2 - 1) * Math.exp(-t * 9) * 0.25;
                add(l, s + i, (boom + crash) * vol * 0.6);
                add(r, s + i, (boom + crash * 0.9) * vol * 0.6);
            }
        }

        /** Noise through a band that moves from one frequency to another. */
        private static void sweep(float[] l, float[] r, double at, double len, double f0, double f1, double v0, double v1) {
            int s = (int) (at * RATE), n = (int) (len * RATE);
            double[] st = new double[4];
            for (int i = 0; i < n; i++) {
                double p = i / (double) n, f = f0 * Math.pow(f1 / f0, p), g = Math.tan(Math.PI * Math.min(f, 18000) / RATE), k = 1 / 1.6;
                double vol = v0 + (v1 - v0) * p * p;
                for (int ch = 0; ch < 2; ch++) {
                    double x = NOISE.nextDouble() * 2 - 1;
                    double a1 = 1 / (1 + g * (g + k)), a2 = g * a1, a3 = g * a2;
                    double v3 = x - st[ch * 2 + 1], v1b = a1 * st[ch * 2] + a2 * v3, v2 = st[ch * 2 + 1] + a2 * st[ch * 2] + a3 * v3;
                    st[ch * 2] = 2 * v1b - st[ch * 2];
                    st[ch * 2 + 1] = 2 * v2 - st[ch * 2 + 1];
                    add(ch == 0 ? l : r, s + i, v1b * vol);
                }
            }
        }

        private static void rise(float[] l, float[] r, double at, double len, double f0, double f1, double vol) {
            int s = (int) (at * RATE), n = (int) (len * RATE);
            double phase = 0, lp = 0;
            for (int i = 0; i < n; i++) {
                double p = i / (double) n;
                phase += f0 * Math.pow(f1 / f0, p) / RATE;
                double saw = 2 * (phase % 1) - 1;
                lp += (0.02 + 0.2 * p) * (saw - lp);
                double v = lp * vol * p * Math.min(1, (1 - p) * 20);
                add(l, s + i, v);
                add(r, s + i, v);
            }
        }

        /** Detuned saw pad through a soft low-pass, fading in and out. */
        private static void pad(float[] l, float[] r, double at, double len, int[] notes, double vol) {
            int s = (int) (at * RATE), n = (int) (len * RATE);
            for (int k = 0; k < notes.length; k++) {
                double f = 440 * Math.pow(2, (notes[k] - 69) / 12.0);
                for (int d = -1; d <= 1; d += 2) {
                    double ff = f * Math.pow(2, d * 7 / 1200.0), phase = k * 0.13 + d * 0.21, lp = 0;
                    boolean left = d < 0;
                    for (int i = 0; i < n; i++) {
                        double p = i / (double) n;
                        phase += ff / RATE;
                        double saw = 2 * (phase % 1) - 1;
                        lp += 0.05 * (saw - lp);
                        double env = Math.min(1, p / 0.35) * Math.min(1, (1 - p) / 0.4);
                        add(left ? l : r, s + i, lp * env * vol);
                    }
                }
            }
        }
    }
}
