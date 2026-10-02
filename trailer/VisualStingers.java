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
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.BufferedOutputStream;
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
 * Visual stingers for WII-UU trailers: short animated logo stings and transitions, each with its
 * sound from the sound kit (SoundKit). Every frame is drawn with a transparent background, then
 * written twice by ffmpeg: a ProRes 4444 .mov with alpha (to lay over footage) and an .mp4 on
 * WII-UU's dark background. Uses Logo3D for the 3D logo. Run by render-stingers.sh.
 *
 * <pre>usage: VisualStingers outdir fontdir sounddir</pre>
 */
public final class VisualStingers {
    static final int W = 1920, H = 1080, FPS = 30;
    static final Color BLUE = new Color(0x00A8E8), TEXT = new Color(0x3C4043);

    private final Font black, heavy, semi;
    private final Logo3D logo;
    private final Path out, sounds;

    private VisualStingers(Path out, Path fonts, Path sounds) {
        this.out = out;
        this.sounds = sounds;
        black = font(fonts, "Inter-900.ttf", Font.BOLD);
        heavy = font(fonts, "Inter-800.ttf", Font.BOLD);
        semi = font(fonts, "Inter-600.ttf", Font.PLAIN);
        logo = new Logo3D(black.deriveFont(200f), "WII-UU", 4, 8f, 1.4f);
    }

    private static Font font(Path dir, String file, int fallback) {
        try {
            return Font.createFont(Font.TRUETYPE_FONT, dir.resolve(file).toFile());
        } catch (Exception e) {
            return new Font(Font.SANS_SERIF, fallback, 12);
        }
    }

    public static void main(String[] args) throws Exception {
        VisualStingers v = new VisualStingers(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]));
        Files.createDirectories(v.out);
        String st = "Stingers/WII-UU Stinger - ", fx = "SFX/", ui = "UI/";
        v.render("01 Logo Spin-In", 3.0, v::spinIn, cue(0.0, fx + "Whoosh Short"), cue(0.88, st + "Logo Impact"));
        v.render("02 Logo Reveal", 3.5, v::reveal, cue(0.0, st + "Boot Chime"));
        v.render("03 House Drop", 4.0, v::houseDrop, cue(0.0, st + "House Drop"));
        v.render("04 Glitch Logo", 1.5, v::glitch, cue(0.0, fx + "Glitch Stutter"), cue(0.66, fx + "Impact Hit"));
        v.render("05 8-bit Pixel", 2.0, v::pixel, cue(0.0, st + "8-bit"));
        v.render("06 End Card", 6.0, v::endCard, cue(0.25, st + "Outro"));
        v.render("07 Swoosh Wipe (transition)", 1.2, v::wipe, cue(0.05, fx + "Whoosh Short"));
        v.render("08 Tile Pop (transition)", 1.6, v::tiles, cue(0.0, fx + "Pop"), cue(0.12, fx + "Pop"), cue(0.24, fx + "Pop"),
                cue(0.5, ui + "UI Chime"), cue(0.95, fx + "Pop"), cue(1.07, fx + "Pop"), cue(1.19, fx + "Pop"));
    }

    // ---- rendering --------------------------------------------------------------------------

    interface Frame {
        void draw(Graphics2D g, BufferedImage img, double t);
    }

    record Cue(double at, String sound) {}

    static Cue cue(double at, String sound) {
        return new Cue(at, sound);
    }

    /** Draws every frame on a transparent canvas and hands it to ffmpeg for both versions. */
    private void render(String name, double seconds, Frame f, Cue... cues) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("ffmpeg", "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "bgra",
                "-s", W + "x" + H, "-r", Integer.toString(FPS), "-i", "-"));
        for (Cue c : cues) cmd.addAll(List.of("-i", sounds.resolve(c.sound() + ".wav").toString()));
        StringBuilder fc = new StringBuilder();
        StringBuilder mix = new StringBuilder();
        for (int i = 0; i < cues.length; i++) {
            int ms = (int) Math.round(cues[i].at() * 1000);
            fc.append(String.format(Locale.ROOT, "[%d:a]adelay=%d|%d[a%d];", i + 1, ms, ms, i));
            mix.append("[a").append(i).append(']');
        }
        fc.append(mix).append(String.format(Locale.ROOT, "amix=inputs=%d:normalize=0,apad,atrim=0:%.3f,asplit=2[am][ap];", cues.length, seconds));
        fc.append(String.format(Locale.ROOT, "[0:v]split=2[vm][vp0];color=c=0x070a18:s=%dx%d:r=%d[bg];[bg][vp0]overlay=shortest=1,format=yuv420p[vp]",
                W, H, FPS));
        cmd.addAll(List.of("-filter_complex", fc.toString(),
                "-map", "[vm]", "-map", "[am]", "-c:v", "prores_ks", "-profile:v", "4", "-pix_fmt", "yuva444p10le",
                "-vendor", "apl0", "-c:a", "pcm_s24le", "-ar", "48000", out.resolve(name + ".mov").toString(),
                "-map", "[vp]", "-map", "[ap]", "-c:v", "libx264", "-crf", "16", "-preset", "slow", "-c:a", "aac", "-b:a", "256k",
                "-ar", "48000", "-movflags", "+faststart", out.resolve(name + " (preview).mp4").toString()));
        Process ff = new ProcessBuilder(cmd).inheritIO().redirectInput(ProcessBuilder.Redirect.PIPE).start();
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        int[] px = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
        ByteBuffer bytes = ByteBuffer.allocate(W * H * 4).order(ByteOrder.LITTLE_ENDIAN);
        int frames = (int) Math.round(seconds * FPS);
        try (OutputStream o = new BufferedOutputStream(ff.getOutputStream(), 1 << 22)) {
            for (int i = 0; i < frames; i++) {
                java.util.Arrays.fill(px, 0);
                Graphics2D g = pen(img);
                f.draw(g, img, i / (double) FPS);
                g.dispose();
                bytes.clear();
                bytes.asIntBuffer().put(px);
                o.write(bytes.array());
            }
        }
        if (ff.waitFor() != 0) throw new IOException("ffmpeg failed for " + name);
        System.err.printf(Locale.ROOT, "  %-32s %.1f s%n", name, seconds);
    }

    private static Graphics2D pen(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        return g;
    }

    // ---- the stingers -----------------------------------------------------------------------

    /** The logo spins in from far away, lands with a shockwave and sparks, a sheen, then fades. */
    private void spinIn(Graphics2D g, BufferedImage img, double t) {
        double land = 0.9;
        double e = ease(t / land);
        double z = t < land ? 60 - 49 * e : 11 - 0.2 * Math.sin((t - land) * 2);
        double ry = t < land ? 2 * Math.PI * (1 - e) : 0.08 * Math.sin((t - land) * 1.6);
        double op = Math.min(1, t / 0.2) * fade(t, 2.5, 3.0);
        if (t >= land) {
            double u = t - land;
            shockwave(g, W / 2f, H / 2f, u, 0.8);
            sparks(g, W / 2f, H / 2f, u, 1.0, 7);
        }
        BufferedImage layer = logoLayer(-0.04, ry, 0, 0, 0, z * (1 - 0.04 * smooth((t - 2.5) / 0.5)), -6 + (t - 1.2) * 9, op);
        glow(g, layer, 0.7 * op + (t >= land ? 1.2 * Math.exp(-(t - land) * 5) : 0));
        g.drawImage(layer, 0, 0, null);
    }

    /** Face on, the logo glows into view, a sheen sweeps across, it holds, it fades. */
    private void reveal(Graphics2D g, BufferedImage img, double t) {
        double op = smooth(t / 0.6) * fade(t, 3.0, 3.5);
        double z = 12 - 1 * ease(t / 1.2);
        BufferedImage layer = logoLayer(-0.03, 0.1 * (1 - ease(t / 2)), 0, 0, 0, z, -6 + (t - 0.6) * 10, op);
        glow(g, layer, 0.9 * op);
        g.drawImage(layer, 0, 0, null);
    }

    /** Streaks and a spectrum ring build up; on the drop (1.875 s) the logo slams in with a flash. */
    private void houseDrop(Graphics2D g, BufferedImage img, double t) {
        double drop = 1.875;
        if (t < drop) {
            double p = t / drop;
            streaks(g, p, 1 - smooth((t - drop + 0.1) / 0.1));
            ring(g, W / 2f, H / 2f, (float) (180 + 40 * p), p, (float) (0.25 + 0.6 * p), t);
        } else {
            double u = t - drop, op = fade(t, 3.5, 4.0);
            ring(g, W / 2f, H / 2f, 230, 1 - 0.5 * smooth(u / 1.5), (float) (0.85 * op), t);
            shockwave(g, W / 2f, H / 2f, u, 1.0);
            sparks(g, W / 2f, H / 2f, u, 1.2, 11);
            double scale = 1 + 0.35 * Math.exp(-u * 14);
            BufferedImage layer = logoLayer(-0.04, 0.05 * Math.sin(u * 1.5), 0, shake(u, 1), shake(u, 2), 11 / scale, -6 + u * 9, op);
            glow(g, layer, (0.8 + 1.6 * Math.exp(-u * 4)) * op);
            g.drawImage(layer, 0, 0, null);
            flash(g, 0.85 * Math.exp(-u * 7));
        }
    }

    /** The logo out of RGB split and torn slices, crisp for a moment, then glitching away. */
    private void glitch(Graphics2D g, BufferedImage img, double t) {
        double amount = t < 0.66 ? 1 - smooth(t / 0.66) * 0.85 : t > 1.15 ? smooth((t - 1.15) / 0.35) : 0;
        double op = Math.min(1, t / 0.08) * (t > 1.4 ? fade(t, 1.4, 1.5) : 1);
        BufferedImage layer = logoLayer(-0.03, 0, 0, 0, 0, 11, -99, op);
        if (amount <= 0.02) {
            glow(g, layer, 0.8);
            g.drawImage(layer, 0, 0, null);
            return;
        }
        Random r = new Random((long) (t * FPS) * 7919);
        int split = (int) (amount * 26);
        int[] dx = new int[3];
        for (int ch = 0; ch < 3; ch++) dx[ch] = (ch - 1) * split + (int) ((r.nextDouble() - 0.5) * 12 * amount);
        // red, green and blue taken from different places, and torn bands of rows shifted sideways
        int[] src = ((DataBufferInt) layer.getRaster().getDataBuffer()).getData(), dst = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
        int y = 0;
        while (y < H) {
            int band = 8 + r.nextInt(70);
            int shift = r.nextDouble() < 0.35 * amount ? (int) ((r.nextDouble() - 0.5) * 220 * amount) : 0;
            for (int yy = y; yy < Math.min(H, y + band); yy++) {
                int row = yy * W;
                for (int x = 0; x < W; x++) {
                    int a = 0, rgb = 0;
                    for (int ch = 0; ch < 3; ch++) {
                        int sx = x - dx[ch] - shift;
                        if (sx < 0 || sx >= W) continue;
                        int p = src[row + sx], al = p >>> 24;
                        if (al == 0) continue;
                        int shiftBits = 16 - 8 * ch;
                        // premultiply this channel by its own coverage, so the edges stay soft
                        rgb |= (((p >> shiftBits) & 255) * al / 255) << shiftBits;
                        a = Math.max(a, al);
                    }
                    if (a > 0) {
                        // un-premultiply by the strongest coverage
                        int R = Math.min(255, ((rgb >> 16) & 255) * 255 / a), G = Math.min(255, ((rgb >> 8) & 255) * 255 / a),
                                B = Math.min(255, (rgb & 255) * 255 / a);
                        dst[row + x] = (a << 24) | (R << 16) | (G << 8) | B;
                    }
                }
            }
            y += band;
        }
        // a flicker of scanlines
        g.setColor(new Color(255, 255, 255, (int) (40 * amount)));
        for (int line = r.nextInt(4); line < H; line += 4) g.fillRect(0, line, W, 1);
    }

    /** The logo builds up from big pixels in time with the 8-bit run, holds, then breaks up again. */
    private void pixel(Graphics2D g, BufferedImage img, double t) {
        int[] sizes = {96, 64, 40, 24, 14, 8, 4, 1};
        int step = Math.min(sizes.length - 1, (int) (t / 0.06));
        int block = t > 1.55 ? sizes[Math.max(0, sizes.length - 1 - (int) ((t - 1.55) / 0.06))] : sizes[step];
        double op = t > 1.55 + 0.06 * sizes.length ? 0 : 1;
        if (op == 0) return;
        BufferedImage flat = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D f = pen(flat);
        wordmark(f, W / 2f, H / 2f + 20, 260, Color.WHITE, BLUE);
        f.dispose();
        if (block <= 1) {
            glow(g, flat, 0.5);
            g.drawImage(flat, 0, 0, null);
            return;
        }
        // average each block, then draw it as one square (with a hairline gap, like LCD pixels)
        int[] src = ((DataBufferInt) flat.getRaster().getDataBuffer()).getData();
        for (int by = 0; by < H; by += block) {
            for (int bx = 0; bx < W; bx += block) {
                long a = 0, r = 0, gg = 0, b = 0;
                int n = 0;
                for (int y = by; y < Math.min(H, by + block); y += Math.max(1, block / 6)) {
                    for (int x = bx; x < Math.min(W, bx + block); x += Math.max(1, block / 6)) {
                        int p = src[y * W + x], al = p >>> 24;
                        a += al;
                        r += (p >> 16 & 255) * al;
                        gg += (p >> 8 & 255) * al;
                        b += (p & 255) * al;
                        n++;
                    }
                }
                if (a == 0) continue;
                int alpha = (int) Math.min(255, a / n * 1.4);
                if (alpha < 40) continue;
                g.setColor(new Color((int) (r / a), (int) (gg / a), (int) (b / a), alpha));
                int gap = block >= 8 ? Math.max(1, block / 10) : 0;
                g.fillRect(bx, by, block - gap, block - gap);
            }
        }
    }

    /** The logo rises into place, the download line and the website fade in under it. */
    private void endCard(Graphics2D g, BufferedImage img, double t) {
        double op = fade(t, 5.4, 6.0);
        double rise = ease(t / 1.0);
        BufferedImage layer = logoLayer(-0.05, 0.12 * Math.sin(t * 0.7), 0, 0, -3 + 4.4 * rise, 14, -6 + (t - 0.9) * 8, smooth(t / 0.5) * op);
        glow(g, layer, 0.7 * op);
        g.drawImage(layer, 0, 0, null);
        double a = ease((t - 0.9) / 0.6) * op, b = ease((t - 1.3) / 0.6) * op;
        centered(g, black.deriveFont(72f), "Get WII-UU free", W / 2f, H * 0.63f + (float) (20 * (1 - a)), alpha(Color.WHITE, a));
        centered(g, semi.deriveFont(46f), "wiiuu.stoppedwumm.net", W / 2f, H * 0.72f + (float) (20 * (1 - b)), alpha(new Color(110, 215, 255), b));
        double c = ease((t - 1.7) / 0.6) * op;
        centered(g, semi.deriveFont(30f), "Your PC, a Wii U–style console  ·  your phone, the GamePad", W / 2f, H * 0.79f,
                alpha(new Color(220, 228, 240), c * 0.85));
    }

    /** A blue band sweeps across and covers the screen at the middle (cut here), with the logo on it. */
    private void wipe(Graphics2D g, BufferedImage img, double t) {
        double p = t / 1.2;                                                // 0..1
        double x = -0.6 + 2.2 * easeInOut(p);                              // band centre, in screen widths
        double width = 1.6;                                                 // wide enough to cover everything at the middle
        Path2D band = new Path2D.Double();
        double skew = 0.25 * W, cx = x * W, half = width * W / 2;
        band.moveTo(cx - half + skew, 0);
        band.lineTo(cx + half + skew, 0);
        band.lineTo(cx + half - skew, H);
        band.lineTo(cx - half - skew, H);
        band.closePath();
        g.setPaint(new GradientPaint((float) (cx - half), 0, new Color(0x3FC4F5), (float) (cx + half), H, new Color(0x0078C0)));
        g.fill(band);
        // a bright leading edge
        g.setStroke(new BasicStroke(10));
        g.setColor(new Color(255, 255, 255, 200));
        g.draw(new Line2D.Double(cx + half + skew, 0, cx + half - skew, H));
        // the logo rides on the band, sharpest while it covers the screen
        double k = 1 - Math.min(1, Math.abs(p - 0.5) / 0.22);
        if (k > 0) {
            Graphics2D c = (Graphics2D) g.create();
            c.clip(band);
            c.setComposite(AlphaComposite.SrcOver.derive((float) smooth(k * 1.5)));
            wordmark(c, (float) (W / 2f + (p - 0.5) * 300), H / 2f + 20, 220, Color.WHITE, new Color(0x003B66));
            c.dispose();
        }
    }

    /** White Wii U menu tiles pop in until they cover the screen, the logo shows, they pop out. */
    private void tiles(Graphics2D g, BufferedImage img, double t) {
        int cols = 6, rows = 4;
        float tw = W / (float) cols, th = H / (float) rows;
        double cover = smooth((t - 0.32) / 0.2) * (1 - smooth((t - 0.95) / 0.2));
        if (cover > 0) {
            g.setColor(alpha(new Color(0xEEF1F4), cover));
            g.fillRect(0, 0, W, H);
        }
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                double order = (r + c) / (double) (rows + cols - 2);
                double in = t - order * 0.3, outT = t - 0.95 - order * 0.3;
                double s = outT > 0 ? 1 - backIn(outT / 0.25) : backOut(in / 0.3);
                if (s <= 0.01) continue;
                float w = (float) (tw * 0.92 * s), h = (float) (th * 0.9 * s);
                float x = c * tw + (tw - w) / 2, y = r * th + (th - h) / 2;
                g.setColor(new Color(0, 0, 0, 30));
                g.fill(new RoundRectangle2D.Float(x + 4, y + 8, w, h, 36 * (float) s, 36 * (float) s));
                g.setColor(Color.WHITE);
                g.fill(new RoundRectangle2D.Float(x, y, w, h, 36 * (float) s, 36 * (float) s));
                // a hint of the blue selection outline on the middle tiles
                if ((r == 1 || r == 2) && (c == 2 || c == 3)) {
                    g.setStroke(new BasicStroke(5));
                    g.setColor(alpha(BLUE, 0.35));
                    g.draw(new RoundRectangle2D.Float(x, y, w, h, 36 * (float) s, 36 * (float) s));
                }
            }
        }
        double k = smooth((t - 0.45) / 0.15) * (1 - smooth((t - 0.9) / 0.12));
        if (k > 0) {
            Graphics2D c = (Graphics2D) g.create();
            c.setComposite(AlphaComposite.SrcOver.derive((float) k));
            float sc = (float) (0.9 + 0.1 * backOut((t - 0.45) / 0.3));
            c.translate(W / 2f, H / 2f);
            c.scale(sc, sc);
            wordmark(c, 0, 20, 230, TEXT, BLUE);
            c.dispose();
        }
    }

    // ---- pieces -----------------------------------------------------------------------------

    private int[] up;
    private float[] zb;

    /** The 3D logo on a transparent layer (rendered at twice the size, edges from its coverage). */
    private BufferedImage logoLayer(double rx, double ry, double rz, double tx, double ty, double tz, double sheen, double opacity) {
        int w2 = W * 2, h2 = H * 2;
        if (up == null) {
            up = new int[w2 * h2];
            zb = new float[w2 * h2];
        }
        java.util.Arrays.fill(up, 0);
        BufferedImage layer = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        if (opacity <= 0.003) return layer;
        logo.render(up, zb, w2, h2, rx, ry, rz, tx, ty, tz, h2 * 1.25, sheen, 1);
        int[] dst = ((DataBufferInt) layer.getRaster().getDataBuffer()).getData();
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int n = 0, r = 0, g = 0, b = 0;
                for (int k = 0; k < 4; k++) {
                    int i = (y * 2 + (k >> 1)) * w2 + x * 2 + (k & 1);
                    if (zb[i] > 0) {
                        n++;
                        int p = up[i];
                        r += p >> 16 & 255;
                        g += p >> 8 & 255;
                        b += p & 255;
                    }
                }
                if (n > 0) dst[y * W + x] = ((int) (n * 63.75 * opacity) << 24) | (r / n << 16) | (g / n << 8) | b / n;
            }
        }
        return layer;
    }

    /** A soft blue glow from {@code layer}'s shape, drawn under it. */
    private static void glow(Graphics2D g, BufferedImage layer, double strength) {
        if (strength <= 0.01) return;
        int sw = W / 16, sh = H / 16;
        BufferedImage small = new BufferedImage(sw, sh, BufferedImage.TYPE_INT_ARGB);
        Graphics2D s = small.createGraphics();
        s.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        s.drawImage(layer, 0, 0, sw, sh, null);
        s.dispose();
        int[] p = ((DataBufferInt) small.getRaster().getDataBuffer()).getData();
        int[] blurred = new int[p.length];
        for (int y = 0; y < sh; y++) {
            for (int x = 0; x < sw; x++) {
                int a = 0, n = 0;
                for (int dy = -2; dy <= 2; dy++) {
                    for (int dx = -2; dx <= 2; dx++) {
                        int xx = Math.max(0, Math.min(sw - 1, x + dx)), yy = Math.max(0, Math.min(sh - 1, y + dy));
                        a += p[yy * sw + xx] >>> 24;
                        n++;
                    }
                }
                int al = (int) Math.min(255, a / n * strength);
                blurred[y * sw + x] = (al << 24) | 0x0090E0;
            }
        }
        small.getRaster().setDataElements(0, 0, sw, sh, blurred);
        g.drawImage(small, -W / 16, -H / 16, W + W / 8, H + H / 8, null);
    }

    private static void shockwave(Graphics2D g, float cx, float cy, double u, double strength) {
        float a = (float) Math.max(0, 1 - u / 0.8);
        if (a <= 0) return;
        float r = (float) (480 + u * W * 0.6);                            // from the logo's edge outwards
        g.setStroke(new BasicStroke(14 * a + 1));
        g.setColor(new Color(150, 220, 255, (int) (230 * a * strength)));
        g.draw(new Ellipse2D.Float(cx - r, cy - r * 0.38f, r * 2, r * 0.76f));
    }

    private static void sparks(Graphics2D g, float cx, float cy, double u, double strength, long seed) {
        Random r = new Random(seed);
        float a = (float) Math.max(0, 1 - u / 0.9);
        if (a <= 0) return;
        g.setStroke(new BasicStroke(3, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int i = 0; i < 70; i++) {
            double ang = r.nextDouble() * Math.PI * 2, speed = 500 + r.nextDouble() * 1400;
            double d = 420 + speed * (1 - Math.exp(-u * 3)) / 3, len = 20 + 60 * a;
            float x0 = (float) (cx + Math.cos(ang) * d), y0 = (float) (cy + Math.sin(ang) * d * 0.6);
            float x1 = (float) (cx + Math.cos(ang) * (d - len)), y1 = (float) (cy + Math.sin(ang) * (d - len) * 0.6);
            g.setColor(new Color(200, 235, 255, (int) (220 * a * strength * (0.5 + 0.5 * r.nextDouble()))));
            g.draw(new Line2D.Float(x0, y0, x1, y1));
        }
    }

    /** Light streaking into the centre, faster as the build goes on. */
    private static void streaks(Graphics2D g, double p, double alpha) {
        Random r = new Random(5);
        g.setStroke(new BasicStroke(3, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int i = 0; i < 160; i++) {
            double ang = r.nextDouble() * Math.PI * 2, phase = r.nextDouble();
            double d = 1 - ((phase + p * (1.5 + 3 * p)) % 1);              // 1 = far out, 0 = at the centre
            double rad = 120 + d * W * 0.75, len = 40 + 260 * p * (1 - d * 0.5);
            float x0 = (float) (W / 2 + Math.cos(ang) * rad), y0 = (float) (H / 2 + Math.sin(ang) * rad * 0.6);
            float x1 = (float) (W / 2 + Math.cos(ang) * (rad + len)), y1 = (float) (H / 2 + Math.sin(ang) * (rad + len) * 0.6);
            g.setColor(new Color(170, 220, 255, (int) (200 * alpha * (0.3 + 0.7 * p) * (1 - d * 0.6))));
            g.draw(new Line2D.Float(x0, y0, x1, y1));
        }
    }

    /** A still spectrum ring, like the visualizer's, growing with {@code level}. */
    private static void ring(Graphics2D g, float cx, float cy, float r0, double level, float alpha, double t) {
        Random r = new Random(4);
        int spokes = 96;
        Graphics2D c = (Graphics2D) g.create();
        c.setComposite(AlphaComposite.SrcOver.derive(Math.max(0, Math.min(1, alpha))));
        for (int pass = 0; pass < 2; pass++) {
            c.setStroke(new BasicStroke(pass == 0 ? 20 : 8, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            r.setSeed(4);
            for (int k = 0; k < spokes; k++) {
                int b = k < spokes / 2 ? k : spokes - 1 - k;
                double band = b / (spokes / 2.0);
                double l = Math.max(0.06, (0.9 - 0.6 * band) * (0.55 + 0.45 * Math.sin(band * 9 + 1 + t * 3)) + 0.15 * r.nextDouble()) * level;
                double a = -Math.PI / 2 + 2 * Math.PI * k / spokes + t * 0.2;
                float len = (float) (6 + l * 210);
                Color col = Color.getHSBColor((float) (0.53 + 0.33 * band) % 1f, pass == 0 ? 0.8f : 0.45f, 1f);
                c.setColor(new Color(col.getRed(), col.getGreen(), col.getBlue(), pass == 0 ? 60 : 170));
                double cs = Math.cos(a), sn = Math.sin(a);
                c.draw(new Line2D.Double(cx + cs * r0, cy + sn * r0, cx + cs * (r0 + len), cy + sn * (r0 + len)));
            }
        }
        c.dispose();
    }

    /** The flat wordmark, centred on (cx, cy), {@code height} pixels for the capitals. */
    private void wordmark(Graphics2D g, float cx, float cy, float height, Color left, Color right) {
        Font f = black.deriveFont(height * 1.38f);
        FontRenderContext frc = g.getFontRenderContext();
        GlyphVector a = f.createGlyphVector(frc, "WII-"), u = f.createGlyphVector(frc, "UU");
        float split = (float) a.getLogicalBounds().getWidth();
        Shape l = a.getOutline(), r = u.getOutline(split, 0);
        Rectangle2D b = l.getBounds2D().createUnion(r.getBounds2D());
        AffineTransform at = AffineTransform.getTranslateInstance(cx - b.getCenterX(), cy - b.getCenterY());
        g.setColor(left);
        g.fill(at.createTransformedShape(l));
        g.setColor(right);
        g.fill(at.createTransformedShape(r));
    }

    private static void flash(Graphics2D g, double a) {
        if (a <= 0.003) return;
        g.setColor(alpha(Color.WHITE, a));
        g.fillRect(0, 0, W, H);
    }

    private void centered(Graphics2D g, Font f, String s, float cx, float y, Color c) {
        g.setFont(f);
        FontMetrics fm = g.getFontMetrics();
        g.setColor(c);
        g.drawString(s, cx - fm.stringWidth(s) / 2f, y);
    }

    private static Color alpha(Color c, double a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), (int) Math.round(255 * Math.max(0, Math.min(1, a))));
    }

    private static double shake(double u, int axis) {
        return 0.25 * Math.exp(-u * 8) * Math.sin(u * (axis == 1 ? 63 : 49));
    }

    private static double ease(double x) {
        x = Math.max(0, Math.min(1, x));
        return 1 - Math.pow(1 - x, 3);
    }

    private static double easeInOut(double x) {
        x = Math.max(0, Math.min(1, x));
        return x < 0.5 ? 4 * x * x * x : 1 - Math.pow(-2 * x + 2, 3) / 2;
    }

    private static double smooth(double x) {
        x = Math.max(0, Math.min(1, x));
        return x * x * (3 - 2 * x);
    }

    private static double fade(double t, double from, double to) {
        return 1 - smooth((t - from) / (to - from));
    }

    private static double backOut(double x) {
        x = Math.max(0, Math.min(1, x));
        double c = 1.7;
        return 1 + (c + 1) * Math.pow(x - 1, 3) + c * Math.pow(x - 1, 2);
    }

    private static double backIn(double x) {
        x = Math.max(0, Math.min(1, x));
        double c = 1.7;
        return (c + 1) * x * x * x - c * x * x;
    }
}
