package choccymilk;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Ellipse2D;
import java.awt.geom.GeneralPath;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Choccy Milk's own look: Cocoa (dark) and Milk (light), following the computer unless chosen
 * (choccy.theme = auto, cocoa or milk; T switches). A chocolate band with drips across the top,
 * the glass of chocolate milk as its logo (also the window icon), rounded type when the computer
 * has some.
 */
final class Look {
    final String name;
    final boolean dark;
    /** the page: a gradient from top to bottom */
    final Color bgTop, bgBottom;
    /** the chocolate band at the top, and the drips off it */
    final Color band, bandShade;
    /** lists sit on panels */
    final Color panel, panelEdge;
    /** the chosen row: a chocolate pill, lighter at the top */
    final Color pickTop, pickBottom, pickText;
    /** the chosen row while the other column has the focus */
    final Color pickIdle;
    final Color text, dim, accent, milk;
    /** the chocolate in the glass */
    final Color choc, chocLight;

    private Look(String name, boolean dark, Color bgTop, Color bgBottom, Color band, Color bandShade, Color panel,
                 Color panelEdge, Color pickTop, Color pickBottom, Color pickText, Color pickIdle, Color text, Color dim,
                 Color accent, Color milk, Color choc, Color chocLight) {
        this.name = name;
        this.dark = dark;
        this.bgTop = bgTop;
        this.bgBottom = bgBottom;
        this.band = band;
        this.bandShade = bandShade;
        this.panel = panel;
        this.panelEdge = panelEdge;
        this.pickTop = pickTop;
        this.pickBottom = pickBottom;
        this.pickText = pickText;
        this.pickIdle = pickIdle;
        this.text = text;
        this.dim = dim;
        this.accent = accent;
        this.milk = milk;
        this.choc = choc;
        this.chocLight = chocLight;
    }

    static final Look COCOA = new Look("Cocoa", true,
            new Color(0x1F130D), new Color(0x0F0906),
            new Color(0x6A3D26), new Color(0x4A2919),
            new Color(0x2F1F16), new Color(0x46301F),
            new Color(0xA0623C), new Color(0x6E3D24), new Color(0xFFF4EA), new Color(0x3F2A1D),
            new Color(0xF7ECE2), new Color(0xB79C88), new Color(0xE3A877), new Color(0xF6E9DC),
            new Color(0x6B3E26), new Color(0x9A6040));

    static final Look MILK = new Look("Milk", false,
            new Color(0xFFF9F2), new Color(0xF2E2D1),
            new Color(0x6B3E26), new Color(0x4E2B19),
            new Color(0xFFFFFF), new Color(0xE9D7C5),
            new Color(0x8C5232), new Color(0x5E3420), new Color(0xFFF7EF), new Color(0xF0E1D2),
            new Color(0x3B2417), new Color(0x8E7262), new Color(0xB4683A), new Color(0xFFF7EE),
            new Color(0x6B3E26), new Color(0x9A6040));

    /** The look to use: choccy.theme, or the computer's dark / light mode. */
    static Look pick(String setting, boolean systemDark) {
        return switch (setting == null ? "auto" : setting.trim().toLowerCase(Locale.ROOT)) {
            case "cocoa", "dark" -> COCOA;
            case "milk", "light" -> MILK;
            default -> systemDark ? COCOA : MILK;
        };
    }

    // ---- type -------------------------------------------------------------------------------------

    private static String family;

    /** A rounded, friendly face when the computer has one; the standard sans otherwise. */
    static synchronized String family() {
        if (family == null) {
            family = Font.SANS_SERIF;
            try {
                Set<String> have = Set.of(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
                for (String f : new String[]{"Nunito", "Varela Round", "Quicksand", "Comfortaa", "M PLUS Rounded 1c",
                        "Arial Rounded MT Bold", "SF Pro Rounded", "Ubuntu", "Segoe UI", "Helvetica Neue", "DejaVu Sans"}) {
                    if (have.contains(f)) {
                        family = f;
                        break;
                    }
                }
            } catch (RuntimeException | Error e) {
                // headless or no fonts: the standard one
            }
        }
        return family;
    }

    static Font font(int style, float size) {
        return new Font(family(), style, Math.max(1, Math.round(size)));
    }

    // ---- the page ---------------------------------------------------------------------------------

    /** The background, and the chocolate band at the top ({@code bandH} tall, drips below it). */
    void paintPage(Graphics2D g, int w, int h, float bandH, float u, long now) {
        g.setPaint(new GradientPaint(0, 0, bgTop, 0, h, bgBottom));
        g.fillRect(0, 0, w, h);
        // a milk swirl low on the page, barely there
        g.setColor(dark ? new Color(255, 240, 225, 10) : new Color(107, 62, 38, 12));
        g.setStroke(new BasicStroke(26 * u, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(wave(-40 * u, h - 70 * u, w + 80 * u, 18 * u, 2.5f, now / 4000.0));
        g.setStroke(new BasicStroke(14 * u, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(wave(-40 * u, h - 30 * u, w + 80 * u, 12 * u, 3.5f, -now / 5200.0));
        g.setPaint(new GradientPaint(0, 0, band, 0, bandH, bandShade));
        g.fill(drips(w, bandH, u, 1));
        // a glossy line along the band
        g.setColor(new Color(255, 255, 255, dark ? 18 : 30));
        g.fill(new java.awt.geom.Rectangle2D.Float(0, bandH * 0.12f, w, 3 * u));
    }

    /** The band with drips hanging off its bottom edge; {@code grow} 0..1 lets the drips run down. */
    static Shape drips(int w, float bandH, float u, float grow) {
        GeneralPath p = new GeneralPath();
        p.moveTo(0, 0);
        p.lineTo(w, 0);
        p.lineTo(w, bandH);
        // drips at fixed places, so they stay put when the window changes size
        float[] at = {0.06f, 0.17f, 0.31f, 0.44f, 0.58f, 0.71f, 0.83f, 0.94f};
        float[] len = {22, 10, 34, 14, 26, 9, 30, 16};
        float[] wid = {16, 12, 20, 13, 17, 11, 19, 14};
        for (int i = at.length - 1; i >= 0; i--) {
            float cx = at[i] * w, dw = wid[i] * u, dl = len[i] * u * grow;
            p.lineTo(cx + dw * 1.6f, bandH);
            p.curveTo(cx + dw * 0.7f, bandH, cx + dw * 0.55f, bandH + dl * 0.4f, cx + dw * 0.55f, bandH + dl);
            p.curveTo(cx + dw * 0.55f, bandH + dl + dw * 0.75f, cx - dw * 0.55f, bandH + dl + dw * 0.75f,
                    cx - dw * 0.55f, bandH + dl);
            p.curveTo(cx - dw * 0.55f, bandH + dl * 0.4f, cx - dw * 0.7f, bandH, cx - dw * 1.6f, bandH);
        }
        p.lineTo(0, bandH);
        p.closePath();
        return p;
    }

    /** A gentle wave from (x, y) across w. */
    static Shape wave(float x, float y, float w, float amp, float waves, double phase) {
        GeneralPath p = new GeneralPath();
        int steps = 48;
        for (int i = 0; i <= steps; i++) {
            double t = i / (double) steps;
            float px = x + (float) (t * w), py = y + (float) (Math.sin((t * waves + phase) * Math.PI * 2) * amp);
            if (i == 0) p.moveTo(px, py);
            else p.lineTo(px, py);
        }
        return p;
    }

    /** A soft shadow under a rounded panel. */
    void shadow(Graphics2D g, float x, float y, float w, float h, float r, float u) {
        for (int i = 4; i >= 1; i--) {
            g.setColor(new Color(40, 20, 8, dark ? 22 : 9));
            float s = i * 2.2f * u;
            g.fill(new RoundRectangle2D.Float(x - s * 0.5f, y - s * 0.2f + 3 * u, w + s, h + s, r + s, r + s));
        }
    }

    /** The chosen row's pill. */
    void pick(Graphics2D g, float x, float y, float w, float h, float u, boolean focused) {
        Shape s = new RoundRectangle2D.Float(x, y, w, h, h * 0.62f, h * 0.62f);
        if (focused) {
            g.setColor(new Color(pickBottom.getRed(), pickBottom.getGreen(), pickBottom.getBlue(), dark ? 70 : 45));
            g.fill(new RoundRectangle2D.Float(x - 2 * u, y + 2 * u, w + 4 * u, h + 2 * u, h * 0.7f, h * 0.7f));
            g.setPaint(new GradientPaint(0, y, pickTop, 0, y + h, pickBottom));
            g.fill(s);
            g.setColor(new Color(255, 255, 255, 40));
            g.fill(new RoundRectangle2D.Float(x + h * 0.3f, y + 2 * u, w - h * 0.6f, h * 0.28f, h * 0.28f, h * 0.28f));
        } else {
            g.setColor(pickIdle);
            g.fill(s);
        }
    }

    // ---- the glass of chocolate milk -----------------------------------------------------------------

    /**
     * The logo: a glass at (x, y), w wide and h tall. fill 0..1 is how full it is, mix 0..1 how much
     * chocolate is stirred in (0: plain milk), straw 0..1 how far the straw is in (0: none),
     * slosh a little wave on the top, phase moves it.
     */
    static void glass(Graphics2D g, float x, float y, float w, float h, float fill, float mix, float straw, float slosh,
                      double phase, Color edge, Color milk, Color choc, Color chocLight) {
        float t = Math.max(1.2f, w * 0.075f);                   // the glass's thickness
        float taper = w * 0.1f;
        Shape outer = glassShape(x, y, w, h, taper);
        Shape inner = glassShape(x + t, y + t * 0.5f, w - 2 * t, h - t * 1.6f, taper * 0.85f);
        // the straw first: the glass sits in front of its lower part
        if (straw > 0) {
            float sx = x + w * 0.62f, top = y - h * 0.42f + (1 - straw) * -h * 0.9f;
            float sw = Math.max(2, w * 0.11f);
            java.awt.geom.AffineTransform at = g.getTransform();
            g.rotate(Math.toRadians(12), sx, y + h * 0.5f);
            g.setColor(milk);
            g.fill(new RoundRectangle2D.Float(sx - sw / 2, top, sw, h * 1.25f, sw, sw));
            g.setColor(new Color(0xE0785A));
            g.setClip(new RoundRectangle2D.Float(sx - sw / 2, top, sw, h * 1.25f, sw, sw));
            for (float s = top - sw; s < top + h * 1.3f; s += sw * 2.2f) {
                GeneralPath stripe = new GeneralPath();
                stripe.moveTo(sx - sw, s);
                stripe.lineTo(sx + sw, s - sw * 0.9f);
                stripe.lineTo(sx + sw, s - sw * 0.9f + sw * 0.9f);
                stripe.lineTo(sx - sw, s + sw * 0.9f);
                stripe.closePath();
                g.fill(stripe);
            }
            g.setClip(null);
            g.setTransform(at);
        }
        // what's in it
        if (fill > 0) {
            java.awt.Shape clip = g.getClip();
            g.clip(inner);
            float ih = h - t * 1.6f, level = y + t * 0.5f + ih * (1 - fill);
            Color drink = mix(milk, choc, mix);
            Color top = mix(milk, chocLight, mix);
            GeneralPath body = new GeneralPath();
            float wx = x - w * 0.1f, ww = w * 1.2f;
            body.moveTo(wx, y + h * 2);
            for (int i = 0; i <= 24; i++) {
                double k = i / 24.0;
                body.lineTo(wx + (float) (k * ww), level + (float) (Math.sin((k * 1.6 + phase) * Math.PI * 2) * slosh * h * 0.035));
            }
            body.lineTo(wx + ww, y + h * 2);
            body.closePath();
            g.setPaint(new GradientPaint(0, level, top, 0, y + h, drink));
            g.fill(body);
            // foam along the top
            if (fill > 0.05f) {
                g.setColor(new Color(255, 250, 244, Math.round(200 * (1 - mix * 0.35f))));
                for (int i = 0; i < 6; i++) {
                    float bx = x + t + (w - 2 * t) * (i / 5f), r = w * (0.07f + (i % 2) * 0.03f);
                    g.fill(new Ellipse2D.Float(bx - r, level - r * 0.7f, r * 2, r * 1.3f));
                }
            }
            g.setClip(clip);
        }
        // the glass itself: a light rim and a shine
        g.setColor(new Color(edge.getRed(), edge.getGreen(), edge.getBlue(), 70));
        java.awt.geom.Area rim = new java.awt.geom.Area(outer);
        rim.subtract(new java.awt.geom.Area(inner));
        g.fill(rim);
        g.setColor(edge);
        g.setStroke(new BasicStroke(Math.max(1f, t * 0.45f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(outer);
        g.setColor(new Color(255, 255, 255, 90));
        g.fill(new RoundRectangle2D.Float(x + w * 0.16f, y + h * 0.14f, Math.max(1.5f, w * 0.07f), h * 0.6f, w * 0.07f, w * 0.07f));
    }

    private static Shape glassShape(float x, float y, float w, float h, float taper) {
        GeneralPath p = new GeneralPath();
        float r = w * 0.12f;
        p.moveTo(x, y);
        p.lineTo(x + w, y);
        p.lineTo(x + w - taper, y + h - r);
        p.quadTo(x + w - taper, y + h, x + w - taper - r, y + h);
        p.lineTo(x + taper + r, y + h);
        p.quadTo(x + taper, y + h, x + taper, y + h - r);
        p.closePath();
        return p;
    }

    static Color mix(Color a, Color b, float k) {
        k = Math.max(0, Math.min(1, k));
        return new Color(Math.round(a.getRed() + (b.getRed() - a.getRed()) * k),
                Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * k),
                Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * k));
    }

    static Color alpha(Color c, float a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.round(255 * Math.max(0, Math.min(1, a))));
    }

    // ---- the icon ---------------------------------------------------------------------------------

    /** The app icon at one size: the glass on a rounded chocolate square. */
    static BufferedImage icon(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        float s = size, m = s * 0.04f;
        g.setPaint(new GradientPaint(0, 0, new Color(0xFFF4E8), 0, s, new Color(0xF0D9C2)));
        g.fill(new RoundRectangle2D.Float(m, m, s - 2 * m, s - 2 * m, s * 0.42f, s * 0.42f));
        // chocolate dripping over the top edge
        java.awt.Shape clip = g.getClip();
        g.clip(new RoundRectangle2D.Float(m, m, s - 2 * m, s - 2 * m, s * 0.42f, s * 0.42f));
        g.setPaint(new GradientPaint(0, 0, new Color(0x7A4629), 0, s * 0.3f, new Color(0x5A321E)));
        g.translate(m, m);
        g.fill(drips(Math.round(s - 2 * m), s * 0.14f, s / 260f, 1));
        g.translate(-m, -m);
        g.setClip(clip);
        float gw = s * 0.42f, gh = s * 0.56f;
        glass(g, (s - gw) / 2 - s * 0.02f, s * 0.31f, gw, gh, 0.82f, 1, 1, 0, 0.2,
                new Color(0x5E3420), new Color(0xFFF7EE), new Color(0x6B3E26), new Color(0x9A6040));
        g.dispose();
        return img;
    }

    /** Icons for the window (each size drawn on its own, so small ones stay crisp). */
    static List<BufferedImage> icons() {
        List<BufferedImage> list = new ArrayList<>();
        for (int s : new int[]{16, 24, 32, 48, 64, 128, 256}) list.add(icon(s));
        return list;
    }
}
