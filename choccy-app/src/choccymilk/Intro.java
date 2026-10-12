package choccymilk;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.GeneralPath;

/**
 * Choccy Milk's start: a glass of milk drops in, chocolate syrup pours into it and gets stirred
 * in, a straw lands in it, the chocolate band runs down from the top and the name pops in letter
 * by letter, then it all fades into the game list. About three seconds; any key, click or button
 * skips it (choccy.intro=false or --no-intro: none).
 */
final class Intro {
    static final long LENGTH = 3500;

    private final long start = System.currentTimeMillis();
    private final Look look;

    Intro(Look look) {
        this.look = look;
    }

    boolean done(long now) {
        return now - start >= LENGTH;
    }

    /** How much of the intro covers the list (1, then fading to 0 at the end). */
    float cover(long now) {
        long t = now - start;
        return t < 3050 ? 1 : Math.max(0, 1 - (t - 3050) / 450f);
    }

    void paint(Graphics2D g, int w, int h, long now) {
        float t = now - start;
        float cover = cover(now);
        if (cover <= 0) return;
        Composite was = g.getComposite();
        g.setComposite(AlphaComposite.SrcOver.derive(cover));
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        float u = Math.max(0.5f, Math.min(w / 1100f, h / 680f));

        g.setPaint(new GradientPaint(0, 0, look.bgTop, 0, h, look.bgBottom));
        g.fillRect(0, 0, w, h);

        // the chocolate band runs down from the top, dripping
        float band = range(t, 1500, 2200);
        if (band > 0) {
            float bh = 96 * u;
            AffineTransform at = g.getTransform();
            g.translate(0, -bh * 1.5f * (1 - easeOut(band)));
            g.setPaint(new GradientPaint(0, 0, look.band, 0, bh, look.bandShade));
            g.fill(Look.drips(w, bh, u, easeOut(range(t, 1800, 2600))));
            g.setTransform(at);
        }

        // the glass
        float gh = Math.min(h * 0.34f, 230 * u), gw = gh * 0.72f;
        float gx = (w - gw) / 2, gy = h * 0.43f - gh * 0.62f;
        float drop = easeOutBack(range(t, 0, 520));
        float gyNow = gy - (1 - drop) * h * 0.25f;
        float appear = range(t, 0, 260);
        float pour = range(t, 380, 1450);
        float fill = 0.5f + 0.32f * easeOut(pour);
        float mix = easeInOut(range(t, 650, 1900));
        float straw = easeOutBounce(range(t, 1650, 2250));
        float slosh = (float) (Math.exp(-Math.max(0, t - 500) / 700.0) * 1.6 + (pour > 0 && pour < 1 ? 0.6 : 0));

        // syrup pouring from the top
        if (t > 380 && t < 1650) {
            float head = easeIn(range(t, 380, 640)), tail = easeIn(range(t, 1250, 1650));
            float level = gyNow + 0.5f * gw * 0.075f + (gh - gw * 0.075f * 1.6f) * (1 - fill);
            float y0 = -10 + (level + 10) * tail, y1 = -10 + (level + 10) * head;
            if (y1 > y0) {
                GeneralPath s = new GeneralPath();
                float cx = gx + gw * 0.42f, sw = Math.max(3, gw * 0.07f);
                int steps = 30;
                for (int i = 0; i <= steps; i++) {
                    float y = y0 + (y1 - y0) * i / steps;
                    float x = cx + (float) Math.sin(y / (24 * u) + t / 90.0) * 1.6f * u;
                    if (i == 0) s.moveTo(x - sw / 2, y);
                    else s.lineTo(x - sw / 2, y);
                }
                for (int i = steps; i >= 0; i--) {
                    float y = y0 + (y1 - y0) * i / steps;
                    float x = cx + (float) Math.sin(y / (24 * u) + t / 90.0) * 1.6f * u;
                    s.lineTo(x + sw / 2, y);
                }
                s.closePath();
                g.setPaint(new GradientPaint(cx - sw, 0, look.chocLight, cx + sw, 0, look.choc));
                g.fill(s);
            }
        }

        Composite c2 = g.getComposite();
        g.setComposite(AlphaComposite.SrcOver.derive(cover * appear));
        Color edge = look.dark ? new Color(0xF6E9DC) : new Color(0x6B3E26);
        Look.glass(g, gx, gyNow, gw, gh, fill, mix, straw, slosh, t / 650.0, edge, look.milk, look.choc, look.chocLight);
        g.setComposite(c2);

        // splashes where the syrup lands
        if (t > 560 && t < 1500) {
            for (int i = 0; i < 7; i++) {
                float born = 560 + i * 120, life = range(t, born, born + 520);
                if (life <= 0 || life >= 1) continue;
                float dir = (i % 2 == 0 ? -1 : 1) * (0.5f + (i * 37 % 10) / 14f);
                float x = gx + gw * 0.42f + dir * life * gw * 0.5f;
                float y = gyNow + gh * 0.12f - (float) (Math.sin(life * Math.PI) * gh * 0.22f) + life * gh * 0.08f;
                float r = (2.4f + (i % 3)) * u * (1 - life * 0.4f);
                g.setColor(Look.mix(look.milk, look.choc, Math.min(1, mix + 0.4f)));
                g.fill(new Ellipse2D.Float(x - r, y - r, r * 2, r * 2));
            }
        }

        // bubbles rising in the glass
        if (t > 1100 && t < 2900) {
            java.awt.Shape clip = g.getClip();
            g.clip(new java.awt.geom.Rectangle2D.Float(gx, gyNow + gh * (1 - fill) + 6 * u, gw, gh));
            for (int i = 0; i < 6; i++) {
                float born = 1100 + i * 230, life = range(t, born, born + 900);
                if (life <= 0 || life >= 1) continue;
                float x = gx + gw * (0.25f + 0.5f * ((i * 53) % 10) / 10f) + (float) Math.sin(life * 9 + i) * 2 * u;
                float y = gyNow + gh * 0.92f - life * gh * 0.75f;
                float r = (2 + i % 3) * u;
                g.setColor(new Color(255, 245, 235, 110));
                g.setStroke(new BasicStroke(1.2f * u));
                g.draw(new Ellipse2D.Float(x - r, y - r, r * 2, r * 2));
            }
            g.setClip(clip);
        }

        // the name, letter by letter
        String name = App.NAME;
        Font f = Look.font(Font.BOLD, 64 * u);
        g.setFont(f);
        FontMetrics fm = g.getFontMetrics();
        float tx = (w - fm.stringWidth(name)) / 2f, ty = gy + gh + 30 * u + fm.getAscent();
        for (int i = 0; i < name.length(); i++) {
            String ch = name.substring(i, i + 1);
            float cw = fm.stringWidth(ch);
            float k = range(t, 1850 + i * 55, 1850 + i * 55 + 380);
            if (k > 0 && !ch.isBlank()) {
                float s = easeOutBack(k);
                AffineTransform at = g.getTransform();
                g.translate(tx + cw / 2, ty - fm.getAscent() * 0.35f);
                g.scale(s, s);
                g.translate(-cw / 2, fm.getAscent() * 0.35f);
                Composite c3 = g.getComposite();
                g.setComposite(AlphaComposite.SrcOver.derive(cover * Math.min(1, k * 2.5f)));
                g.setColor(Look.alpha(look.choc, look.dark ? 0.55f : 0.18f));
                g.drawString(ch, 0, 4 * u);
                g.setColor(i < 6 ? (look.dark ? look.milk : look.choc) : look.accent);
                g.drawString(ch, 0, 0);
                g.setComposite(c3);
                g.setTransform(at);
            }
            tx += cw;
        }
        float tag = range(t, 2450, 2850);
        if (tag > 0) {
            g.setFont(Look.font(Font.PLAIN, 18 * u));
            FontMetrics fs = g.getFontMetrics();
            String line = "games, straight from the glass";
            Composite c4 = g.getComposite();
            g.setComposite(AlphaComposite.SrcOver.derive(cover * tag));
            g.setColor(look.dim);
            g.drawString(line, (w - fs.stringWidth(line)) / 2f, ty + 34 * u + (1 - easeOut(tag)) * 8 * u);
            g.setComposite(c4);
        }
        g.setComposite(was);
    }

    // ---- easing -----------------------------------------------------------------------------------

    /** 0 before a, 1 after b, linear between. */
    static float range(float t, float a, float b) {
        return t <= a ? 0 : t >= b ? 1 : (t - a) / (b - a);
    }

    static float easeOut(float k) {
        return 1 - (1 - k) * (1 - k) * (1 - k);
    }

    static float easeIn(float k) {
        return k * k;
    }

    static float easeInOut(float k) {
        return k < 0.5f ? 4 * k * k * k : 1 - (float) Math.pow(-2 * k + 2, 3) / 2;
    }

    static float easeOutBack(float k) {
        if (k <= 0) return 0;
        float c1 = 1.70158f, c3 = c1 + 1;
        return 1 + c3 * (float) Math.pow(k - 1, 3) + c1 * (float) Math.pow(k - 1, 2);
    }

    static float easeOutBounce(float k) {
        float n1 = 7.5625f, d1 = 2.75f;
        if (k < 1 / d1) return n1 * k * k;
        if (k < 2 / d1) return n1 * (k -= 1.5f / d1) * k + 0.75f;
        if (k < 2.5 / d1) return n1 * (k -= 2.25f / d1) * k + 0.9375f;
        return n1 * (k -= 2.625f / d1) * k + 0.984375f;
    }
}
