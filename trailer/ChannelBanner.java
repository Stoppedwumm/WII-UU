package wiiuu.ui;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Line2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.File;
import java.util.Random;
import javax.imageio.ImageIO;

/**
 * The YouTube channel banner (2560 x 1440), in the look of the Extended Mix video: stars streaking
 * from the centre, a neon grid floor, a spectrum halo and the 3D logo (Logo3D). The logo and words
 * stay inside the 1546 x 423 middle that YouTube shows on every device.
 *
 * <pre>usage: ChannelBanner out.png fontdir</pre>
 */
public final class ChannelBanner {
    static final int W = 2560, H = 1440;
    static final int SAFE_W = 1546, SAFE_H = 423;          // visible everywhere
    static final float CX = W / 2f, CY = H / 2f;

    public static void main(String[] args) throws Exception {
        File fonts = new File(args[1]);
        Font black = font(fonts, "Inter-900.ttf", Font.BOLD), heavy = font(fonts, "Inter-800.ttf", Font.BOLD),
                semi = font(fonts, "Inter-600.ttf", Font.PLAIN);
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        int[] rgb = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
        Graphics2D g = pen(img);
        g.setPaint(new GradientPaint(0, 0, new Color(5, 7, 18), 0, H, new Color(14, 10, 38)));
        g.fillRect(0, 0, W, H);
        g.setPaint(new RadialGradientPaint(CX, CY - 40, W * 0.45f, new float[]{0, 1},
                new Color[]{new Color(20, 60, 130, 150), new Color(20, 60, 130, 0)}));
        g.fillRect(0, 0, W, H);
        stars(g);
        grid(g, CY + SAFE_H / 2f + 12);
        halo(g, CX, CY - 50, 250);
        g.dispose();

        // the 3D logo, rendered at twice the size for smooth edges
        Logo3D logo = new Logo3D(black.deriveFont(200f), "WII-UU", 4, 8f, 1.4f);
        int[] up = new int[W * 2 * H * 2];
        float[] z = new float[up.length];
        for (int y = 0; y < H * 2; y++) for (int x = 0; x < W * 2; x++) up[y * W * 2 + x] = rgb[(y >> 1) * W + (x >> 1)];
        logo.render(up, z, W * 2, H * 2, -0.06, 0.2, -0.02, 0, 0.62, 13, H * 2 * 0.98, 1.1, 1);
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int a = up[y * 2 * W * 2 + x * 2], b = up[y * 2 * W * 2 + x * 2 + 1], c = up[(y * 2 + 1) * W * 2 + x * 2],
                        d = up[(y * 2 + 1) * W * 2 + x * 2 + 1];
                rgb[y * W + x] = avg(a, b, c, d);
            }
        }

        g = pen(img);
        // a soft dark band behind the words, over the halo
        Color band = new Color(5, 7, 20, 190), clear = new Color(5, 7, 20, 0);
        g.setPaint(new LinearGradientPaint(0, CY + 70, 0, CY + 200, new float[]{0, 0.3f, 0.7f, 1}, new Color[]{clear, band, band, clear}));
        g.fill(new java.awt.geom.Rectangle2D.Float(0, CY + 70, W, 130));
        String line = "Your PC, a Wii U–style console  ·  your phone, the GamePad";
        centered(g, heavy.deriveFont(44f), line, CX, CY + 118, Color.WHITE);
        centered(g, semi.deriveFont(32f), "Free download  ·  wiiuu.stoppedwumm.net", CX, CY + 172, new Color(110, 215, 255));
        g.dispose();
        glow(img);
        ImageIO.write(img, "png", new File(args[0]));
    }

    private static Font font(File dir, String file, int fallback) {
        try {
            return Font.createFont(Font.TRUETYPE_FONT, new File(dir, file));
        } catch (Exception e) {
            return new Font(Font.SANS_SERIF, fallback, 12);
        }
    }

    private static Graphics2D pen(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        return g;
    }

    private static int avg(int a, int b, int c, int d) {
        int r = ((a >> 16 & 255) + (b >> 16 & 255) + (c >> 16 & 255) + (d >> 16 & 255)) >> 2;
        int g = ((a >> 8 & 255) + (b >> 8 & 255) + (c >> 8 & 255) + (d >> 8 & 255)) >> 2;
        int bl = ((a & 255) + (b & 255) + (c & 255) + (d & 255)) >> 2;
        return (r << 16) | (g << 8) | bl;
    }

    /** Streaks flying out of the centre, longer and brighter further out. */
    private static void stars(Graphics2D g) {
        Random rnd = new Random(11);
        for (int i = 0; i < 1100; i++) {
            double a = rnd.nextDouble() * Math.PI * 2, r = 560 + Math.pow(rnd.nextDouble(), 0.8) * W * 0.6;   // none over the words
            double len = r * (0.04 + rnd.nextDouble() * 0.12);
            float x0 = (float) (CX + Math.cos(a) * r), y0 = (float) (CY - 40 + Math.sin(a) * r * 0.62);
            float x1 = (float) (CX + Math.cos(a) * (r + len)), y1 = (float) (CY - 40 + Math.sin(a) * (r + len) * 0.62);
            float k = (float) Math.min(1, r / (W * 0.5));
            g.setStroke(new BasicStroke(1f + 2.5f * k, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(170, 220, 255, (int) (60 + 150 * k * rnd.nextDouble())));
            g.draw(new Line2D.Float(x0, y0, x1, y1));
        }
    }

    /** The neon floor from the horizon down to the bottom edge. */
    private static void grid(Graphics2D g, float horizon) {
        float f = H * 0.9f, cam = 1.1f;
        g.setPaint(new GradientPaint(0, horizon - 30, new Color(224, 64, 251, 0), 0, horizon + 2, new Color(224, 64, 251, 120)));
        g.fillRect(0, (int) horizon - 30, W, 32);
        g.setStroke(new BasicStroke(2.2f));
        double near = cam * f / (H - horizon), spacing = 1.6;
        for (int k = 0; k < 16; k++) {
            double d = near + (k + 0.7) * spacing;
            float y = (float) (horizon + cam * f / d);
            int a = (int) Math.min(255, 220 * Math.min(1, 6 / d) * Math.min(1, (y - horizon) / 25));
            g.setColor(new Color(224, 64, 251, Math.max(0, a)));
            g.draw(new Line2D.Float(0, y, W, y));
        }
        for (int j = -40; j <= 40; j++) {
            double X = j * spacing * 0.55;
            float x0 = (float) (CX + X * f / near), x1 = (float) (CX + X * f / 40), y1 = (float) (horizon + cam * f / 40);
            g.setPaint(new GradientPaint(x1, y1, new Color(0, 200, 255, 0), x0, H, new Color(0, 200, 255, 190)));
            g.draw(new Line2D.Float(x0, H, x1, y1));
        }
        // fade the floor into the sky
        g.setPaint(new LinearGradientPaint(0, horizon, 0, horizon + 60, new float[]{0, 1},
                new Color[]{new Color(10, 9, 30, 160), new Color(10, 9, 30, 0)}));
        g.fillRect(0, (int) horizon, W, 60);
    }

    /** A spectrum ring like the visualizer's, behind the logo: a still from the music. */
    private static void halo(Graphics2D g, float cx, float cy, float r0) {
        Random rnd = new Random(4);
        int spokes = 120;
        Graphics2D c = (Graphics2D) g.create();
        c.setComposite(AlphaComposite.SrcOver.derive(0.75f));
        for (int pass = 0; pass < 2; pass++) {
            c.setStroke(new BasicStroke(pass == 0 ? 22 : 9, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            Random r = new Random(4);
            for (int k = 0; k < spokes; k++) {
                int b = k < spokes / 2 ? k : spokes - 1 - k;
                double band = b / (spokes / 2.0);
                double level = Math.max(0.08, (0.9 - 0.6 * band) * (0.55 + 0.45 * Math.sin(band * 9 + 1)) + 0.15 * r.nextDouble());
                double a = -Math.PI / 2 + 2 * Math.PI * k / spokes;
                float l = (float) (8 + level * 230);
                Color col = Color.getHSBColor((float) (0.53 + 0.33 * band) % 1f, pass == 0 ? 0.8f : 0.45f, 1f);
                c.setColor(new Color(col.getRed(), col.getGreen(), col.getBlue(), pass == 0 ? 55 : 150));
                double cs = Math.cos(a), sn = Math.sin(a) * 0.9;
                c.draw(new Line2D.Double(cx + cs * r0, cy + sn * r0, cx + cs * (r0 + l), cy + sn * (r0 + l)));
            }
        }
        c.dispose();
        rnd.nextInt();
    }

    private static void centered(Graphics2D g, Font f, String s, float cx, float y, Color c) {
        g.setFont(f);
        FontMetrics fm = g.getFontMetrics();
        g.setColor(c);
        g.drawString(s, cx - fm.stringWidth(s) / 2f, y);
    }

    /** Bright parts bleed light: a blurred, darkened-away small copy added on top. */
    private static void glow(BufferedImage img) {
        int sw = W / 8, sh = H / 8;
        BufferedImage small = new BufferedImage(sw, sh, BufferedImage.TYPE_INT_RGB);
        Graphics2D s = small.createGraphics();
        s.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        s.drawImage(img, 0, 0, sw, sh, null);
        s.dispose();
        int[] p = ((DataBufferInt) small.getRaster().getDataBuffer()).getData();
        for (int i = 0; i < p.length; i++) {
            int r = p[i] >> 16 & 255, gg = p[i] >> 8 & 255, b = p[i] & 255;
            float lum = 0.3f * r + 0.59f * gg + 0.11f * b, k = Math.max(0, lum - 100) / Math.max(1, lum);
            p[i] = ((int) (r * k) << 16) | ((int) (gg * k) << 8) | (int) (b * k);
        }
        for (int pass = 0; pass < 3; pass++) box(p, sw, sh, 3);
        BufferedImage big = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D b = big.createGraphics();
        b.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        b.drawImage(small, 0, 0, W, H, null);
        b.dispose();
        int[] d = ((DataBufferInt) img.getRaster().getDataBuffer()).getData(), add = ((DataBufferInt) big.getRaster().getDataBuffer()).getData();
        for (int i = 0; i < d.length; i++) {
            int r = Math.min(255, (d[i] >> 16 & 255) + (add[i] >> 16 & 255)), gg = Math.min(255, (d[i] >> 8 & 255) + (add[i] >> 8 & 255)),
                    bl = Math.min(255, (d[i] & 255) + (add[i] & 255));
            d[i] = (r << 16) | (gg << 8) | bl;
        }
    }

    private static void box(int[] p, int w, int h, int r) {
        int[] t = new int[p.length];
        for (int pass = 0; pass < 2; pass++) {
            int[] src = pass == 0 ? p : t, dst = pass == 0 ? t : p;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int rr = 0, gg = 0, bb = 0, n = 0;
                    for (int k = -r; k <= r; k++) {
                        int xx = pass == 0 ? Math.max(0, Math.min(w - 1, x + k)) : x, yy = pass == 0 ? y : Math.max(0, Math.min(h - 1, y + k));
                        int v = src[yy * w + xx];
                        rr += v >> 16 & 255;
                        gg += v >> 8 & 255;
                        bb += v & 255;
                        n++;
                    }
                    dst[y * w + x] = ((rr / n) << 16) | ((gg / n) << 8) | (bb / n);
                }
            }
        }
    }
}
