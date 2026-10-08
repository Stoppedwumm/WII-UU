package wiiuu.ui;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * WII-UU itself, as a little helper in a corner of the menu and of Settings: the WII-UU card with
 * googly eyes and little legs. It walks in, blinks, watches a point (the selection), hops when it
 * has something to say, and says it in a speech bubble that types out and fades a few seconds
 * later. Quiet: only the setup guide gives it a voice.
 */
final class Mascot {
    private static final long TYPE_MS = 28, STAY_MS = 5000, FADE_MS = 400, BLINK_MS = 4700, HOP_MS = 420;

    private final long created = System.currentTimeMillis();
    private final Random random = new Random();
    private String text;
    private long saidAt, hopAt, lastSaid;
    private float lookX = Float.NaN, lookY = Float.NaN;
    private Rectangle2D body;

    /** Says something (and hops). */
    void say(String line) {
        if (line == null || line.isBlank()) return;
        text = line;
        saidAt = hopAt = lastSaid = System.currentTimeMillis();
    }

    /** Says one of these, at random. */
    void sayOne(String... lines) {
        say(lines[random.nextInt(lines.length)]);
    }

    /** How long since it last said something (ms). */
    long quietFor() {
        return lastSaid == 0 ? Long.MAX_VALUE : System.currentTimeMillis() - lastSaid;
    }

    boolean talking() {
        return text != null && System.currentTimeMillis() - saidAt < typeTime() + STAY_MS + FADE_MS;
    }

    void hop() {
        hopAt = System.currentTimeMillis();
    }

    /** Where it looks (screen coordinates). */
    void look(float x, float y) {
        lookX = x;
        lookY = y;
    }

    /** Whether a click at x, y hits it. */
    boolean hit(double x, double y) {
        return body != null && body.contains(x, y);
    }

    private long typeTime() {
        return text == null ? 0 : text.length() * TYPE_MS;
    }

    /** Whether the next frame looks different (the caller repaints only then). */
    boolean needsFrame() {
        long now = System.currentTimeMillis(), since = now - saidAt;
        if (now - created < 1300 || now - hopAt < HOP_MS + 30) return true;
        if ((now - created) % BLINK_MS < 200) return true;
        if (text == null) return false;
        return since < typeTime() + 60 || since > typeTime() + STAY_MS - 50 && since < typeTime() + STAY_MS + FADE_MS + 60;
    }

    /**
     * Paints it standing at footX, footY, with a body s high (2.6 s wide), and its bubble to the
     * right (bubbleAbove false) or above it, at most bubbleW wide.
     */
    void paint(Graphics2D g, float footX, float footY, float s, boolean bubbleAbove, float bubbleW) {
        long now = System.currentTimeMillis();
        float bodyW = s * 2.6f, bodyH = s;
        double in = ease((now - created) / 900.0);
        float x = footX - (float) ((1 - in) * (footX + bodyW));
        double hop = Math.min(1, (now - hopAt) / (double) HOP_MS);
        float lift = (float) (Math.sin(Math.PI * hop) * s * 0.45f);
        float y = footY - s * 0.95f - bodyH / 2 - lift;
        // legs
        g.setColor(MenuView.dark ? new Color(0xB0BEC5) : new Color(0x37474F));
        g.setStroke(new BasicStroke(Math.max(2f, s * 0.12f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        float walk = in < 1 ? (float) Math.sin(now / 60.0) * s * 0.25f : 0;
        g.drawLine((int) (x - s * 0.4f), (int) (y + bodyH / 2), (int) (x - s * 0.45f + walk), (int) (footY - lift * 0.3f));
        g.drawLine((int) (x + s * 0.4f), (int) (y + bodyH / 2), (int) (x + s * 0.45f - walk), (int) (footY - lift * 0.3f));
        g.setStroke(new BasicStroke(1));
        // the card
        RoundRectangle2D card = new RoundRectangle2D.Float(x - bodyW / 2, y - bodyH / 2, bodyW, bodyH, bodyH * 0.6f, bodyH * 0.6f);
        body = new Rectangle2D.Float(x - bodyW / 2, y - bodyH, bodyW, bodyH * 1.5f + s);
        g.setColor(new Color(0, 0, 0, 50));
        g.fill(new RoundRectangle2D.Float(x - bodyW / 2 + 2, y - bodyH / 2 + 4, bodyW, bodyH, bodyH * 0.6f, bodyH * 0.6f));
        g.setColor(Color.WHITE);
        g.fill(card);
        g.setColor(new Color(0xCFD8DC));
        g.draw(card);
        g.setFont(MenuView.font(Font.BOLD, s * 0.55f));
        FontMetrics fm = g.getFontMetrics();
        float lx = x - fm.stringWidth("WII-UU") / 2f, ly = y + fm.getAscent() * 0.38f;
        g.setColor(new Color(0x2B2F36));
        g.drawString("WII-", lx, ly);
        g.setColor(MenuView.ACCENT);
        g.drawString("UU", lx + fm.stringWidth("WII-"), ly);
        // googly eyes
        boolean blink = (now - created) % BLINK_MS < 140;
        for (int e = -1; e <= 1; e += 2) {
            float ex = x + e * s * 0.42f, ey = y - bodyH / 2 - s * 0.12f, er = s * 0.27f;
            double dx = Float.isNaN(lookX) ? 1 : lookX - ex, dy = Float.isNaN(lookY) ? -0.3 : lookY - ey;
            double d = Math.max(1, Math.hypot(dx, dy));
            if (Float.isNaN(lookX)) d = Math.hypot(dx, dy);
            g.setColor(Color.WHITE);
            g.fill(new Ellipse2D.Float(ex - er, ey - er, er * 2, er * 2));
            g.setColor(new Color(0x2B2F36));
            g.setStroke(new BasicStroke(Math.max(1.2f, s * 0.05f)));
            g.draw(new Ellipse2D.Float(ex - er, ey - er, er * 2, er * 2));
            if (blink) g.drawLine((int) (ex - er * 0.7f), (int) ey, (int) (ex + er * 0.7f), (int) ey);
            else {
                float px = ex + (float) (dx / d * er * 0.45f), py = ey + (float) (dy / d * er * 0.45f);
                g.fill(new Ellipse2D.Float(px - er * 0.5f, py - er * 0.5f, er, er));
            }
        }
        g.setStroke(new BasicStroke(1));
        if (in >= 1) bubble(g, x, y, bodyW, bodyH, s, bubbleAbove, bubbleW, now);
    }

    private void bubble(Graphics2D g, float x, float y, float bodyW, float bodyH, float s, boolean above, float maxW, long now) {
        if (text == null) return;
        long since = now - saidAt;
        float typed = Math.min(1f, since / (float) Math.max(1, typeTime()));
        float alpha = (float) (1 - Math.max(0, Math.min(1, (since - typeTime() - STAY_MS) / (double) FADE_MS)));
        if (alpha <= 0) return;
        Composite c = g.getComposite();
        float base = c instanceof AlphaComposite a ? a.getAlpha() : 1f;
        g.setComposite(AlphaComposite.SrcOver.derive(base * alpha));
        g.setFont(MenuView.font(Font.PLAIN, s * 0.5f));
        FontMetrics fm = g.getFontMetrics();
        List<String> lines = wrap(text, fm, maxW - s * 0.6f);
        float textW = 0;
        for (String l : lines) textW = Math.max(textW, fm.stringWidth(l));
        float pad = s * 0.3f, lineH = fm.getHeight();
        float bw = textW + pad * 2, bh = lines.size() * lineH + pad * 1.4f;
        float bx, by;
        Path2D tail = new Path2D.Float();
        if (above) {
            bx = Math.max(4, x - s * 0.8f);
            by = y - bodyH / 2 - s * 0.55f - bh - s * 0.25f;
            tail.moveTo(x - s * 0.1f, by + bh - 1);
            tail.lineTo(x + s * 0.2f, y - bodyH / 2 - s * 0.45f);
            tail.lineTo(x + s * 0.6f, by + bh - 1);
        } else {
            bx = x + bodyW / 2 + s * 0.45f;
            by = y - bh / 2 - s * 0.2f;
            float mid = Math.max(by + bh * 0.3f, Math.min(by + bh * 0.7f, y - s * 0.2f));
            tail.moveTo(bx + 1, mid - s * 0.22f);
            tail.lineTo(x + bodyW / 2 + s * 0.05f, y - s * 0.2f);
            tail.lineTo(bx + 1, mid + s * 0.22f);
        }
        tail.closePath();
        float r = s * 0.5f;
        g.setColor(new Color(0, 0, 0, 40));
        g.fill(new RoundRectangle2D.Float(bx + 2, by + 4, bw, bh, r, r));
        g.setColor(Color.WHITE);
        g.fill(new RoundRectangle2D.Float(bx, by, bw, bh, r, r));
        g.fill(tail);
        g.setColor(new Color(0x2B2F36));
        int shown = Math.round(text.length() * typed), done = 0;
        float ty = by + pad * 0.7f + fm.getAscent();
        for (String l : lines) {
            int n = Math.max(0, Math.min(l.length(), shown - done));
            if (n > 0) g.drawString(l.substring(0, n), bx + pad, ty);
            done += l.length() + 1;
            ty += lineH;
        }
        g.setComposite(c);
    }

    private static List<String> wrap(String text, FontMetrics fm, float width) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String test = line.length() == 0 ? word : line + " " + word;
            if (fm.stringWidth(test) > width && line.length() > 0) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else line = new StringBuilder(test);
        }
        if (line.length() > 0) lines.add(line.toString());
        return lines;
    }

    private static double ease(double t) {
        t = Math.max(0, Math.min(1, t));
        return 1 - Math.pow(1 - t, 3);
    }

    // ---- what it says -----------------------------------------------------------------------------

    /** Hello, by the time of day. */
    static String greeting() {
        int hour = java.time.LocalTime.now().getHour();
        if (hour < 5) return "Up late, huh? Me too. I don't sleep. I'm a console.";
        if (hour < 11) return "Good morning! Ready to play something?";
        if (hour < 17) return "Good afternoon! What are we playing?";
        if (hour < 22) return "Good evening! Perfect time for a game.";
        return "Getting late... one more game?";
    }

    /** When you click it. */
    static final String[] POKED = {
            "Hi! I'm WII-UU. Well, the little one.",
            "That tickles.",
            "Press F1 for Settings. Or don't. I'm not your boss.",
            "Did you know? I make the menu music myself.",
            "Psst. Leave me alone for three minutes and I'll play by myself.",
            "Boop.",
            "I'm not a button. I'm a friend.",
            "Your phone can be a GamePad. Just saying.",
    };
}
