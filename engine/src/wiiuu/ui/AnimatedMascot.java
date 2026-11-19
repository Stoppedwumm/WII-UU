package wiiuu.ui;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.util.List;
import java.util.Random;

/**
 * An animated branded card with speech bubbles, extracted from WII-UU: a card with
 * googly eyes and little legs. It walks in, blinks, watches a point (the selection), hops when it
 * has something to say, and says it in a speech bubble that types out and fades a few seconds
 * later. The host supplies messages, appearance, a clock, input and repaint scheduling.
 * Use one instance on one UI thread; it does not play audio.
 */
public class AnimatedMascot {
    private static final long TYPE_MS = 28, STAY_MS = 5000, FADE_MS = 400, BLINK_MS = 4700, HOP_MS = 420;

    /** Branding and live appearance supplied by the host; font sizes are in points. */
    public record Style(String label, String accentLabel, Color accent, boolean dark,
                        java.util.function.BiFunction<Integer, Float, Font> font) {
        public Style {
            java.util.Objects.requireNonNull(label);
            java.util.Objects.requireNonNull(accentLabel);
            java.util.Objects.requireNonNull(accent);
            java.util.Objects.requireNonNull(font);
        }
    }

    private final java.util.function.Supplier<Style> style;
    private final java.util.function.LongSupplier clock;
    private final long created;
    private boolean hasSaid;

    /** Use a fixed appearance and the system millisecond clock. Host owns repaint/input scheduling. */
    public AnimatedMascot(Style style) {
        this(() -> style, System::currentTimeMillis);
        java.util.Objects.requireNonNull(style);
    }

    /** Supply a live theme and a monotonic millisecond clock for deterministic animation. */
    public AnimatedMascot(java.util.function.Supplier<Style> style, java.util.function.LongSupplier clock) {
        this.style = java.util.Objects.requireNonNull(style);
        this.clock = java.util.Objects.requireNonNull(clock);
        this.created = clock.getAsLong();
        this.hopAt = created - HOP_MS;
    }
    private final Random random = new Random();
    private String text;
    private long saidAt, hopAt, lastSaid;
    private float lookX = Float.NaN, lookY = Float.NaN;
    private Rectangle2D body;

    /** Says something (and hops). */
    public void say(String line) {
        if (line == null || line.isBlank()) return;
        hasSaid = true;
        text = line;
        saidAt = hopAt = lastSaid = clock.getAsLong();
    }

    /** Says one of these, at random. */
    public void sayOne(String... lines) {
        if (lines.length > 0) say(lines[random.nextInt(lines.length)]);
    }

    /** How long since it last said something (ms). */
    public long quietFor() {
        return !hasSaid ? Long.MAX_VALUE : clock.getAsLong() - lastSaid;
    }

    public boolean talking() {
        return text != null && clock.getAsLong() - saidAt < typeTime() + STAY_MS + FADE_MS;
    }

    public void hop() {
        hopAt = clock.getAsLong();
    }

    /** Where it looks (screen coordinates). */
    public void look(float x, float y) {
        lookX = x;
        lookY = y;
    }

    /** Whether a click at x, y hits it. */
    public boolean hit(double x, double y) {
        return body != null && body.contains(x, y);
    }

    private long typeTime() {
        return text == null ? 0 : text.length() * TYPE_MS;
    }

    /** Whether the next frame looks different (the caller repaints only then). */
    public boolean needsFrame() {
        long now = clock.getAsLong(), since = now - saidAt;
        if (now - created < 1300 || now - hopAt < HOP_MS + 30) return true;
        if ((now - created) % BLINK_MS < 200) return true;
        if (text == null) return false;
        return since < typeTime() + 60 || since > typeTime() + STAY_MS - 50 && since < typeTime() + STAY_MS + FADE_MS + 60;
    }

    /**
     * Paints it standing at footX, footY, with a body s high (2.6 s wide), and its bubble to the
     * right (bubbleAbove false) or above it, at most bubbleW wide.
     */
    public void paint(Graphics2D g, float footX, float footY, float s, boolean bubbleAbove, float bubbleW) {
        paint(g, footX, footY, s, bubbleAbove, bubbleW, false, 0);
    }

    public void paintWalking(Graphics2D g, float footX, float footY, float s, float stride) {
        paint(g, footX, footY, s, false, 0, true, stride);
    }

    private void paint(Graphics2D graphics, float footX, float footY, float s, boolean bubbleAbove,
                       float bubbleW, boolean walkingScene, float stride) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            Style appearance = java.util.Objects.requireNonNull(style.get());
            long now = clock.getAsLong();
            float bodyW = s * 2.6f, bodyH = s;
            double in = walkingScene ? 1 : ease((now - created) / 900.0);
            float x = footX - (float) ((1 - in) * (footX + bodyW));
            double hop = Math.min(1, (now - hopAt) / (double) HOP_MS);
            float lift = (float) (Math.sin(Math.PI * hop) * s * 0.45f);
            float y = footY - s * 0.95f - bodyH / 2 - lift;
            // legs
            g.setColor(walkingScene || appearance.dark() ? new Color(0xB0BEC5) : new Color(0x37474F));
            g.setStroke(new BasicStroke(Math.max(2f, s * 0.12f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            float walk = walkingScene ? stride * s * 0.25f : in < 1 ? (float) Math.sin(now / 60.0) * s * 0.25f : 0;
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
            g.setFont(appearance.font().apply(Font.BOLD, s * 0.55f));
            FontMetrics fm = g.getFontMetrics();
            float lx = x - fm.stringWidth(appearance.label() + appearance.accentLabel()) / 2f, ly = y + fm.getAscent() * 0.38f;
            g.setColor(new Color(0x2B2F36));
            g.drawString(appearance.label(), lx, ly);
            g.setColor(appearance.accent());
            g.drawString(appearance.accentLabel(), lx + fm.stringWidth(appearance.label()), ly);
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
            if (!walkingScene && in >= 1) bubble(g, x, y, bodyW, bodyH, s, bubbleAbove, bubbleW, now, appearance);
        } finally {
            g.dispose();
        }
    }

    private void bubble(Graphics2D g, float x, float y, float bodyW, float bodyH, float s, boolean above, float maxW, long now, Style appearance) {
        if (text == null) return;
        long since = now - saidAt;
        float typed = Math.min(1f, since / (float) Math.max(1, typeTime()));
        float alpha = (float) (1 - Math.max(0, Math.min(1, (since - typeTime() - STAY_MS) / (double) FADE_MS)));
        if (alpha <= 0) return;
        Composite c = g.getComposite();
        float base = c instanceof AlphaComposite a ? a.getAlpha() : 1f;
        g.setComposite(AlphaComposite.SrcOver.derive(base * alpha));
        g.setFont(appearance.font().apply(Font.PLAIN, s * 0.5f));
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
            if (n > 0 && n < l.length() && Character.isHighSurrogate(l.charAt(n - 1))
                    && Character.isLowSurrogate(l.charAt(n))) n--;
            if (n > 0) g.drawString(l.substring(0, n), bx + pad, ty);
            done += l.length() + 1;
            ty += lineH;
        }
        g.setComposite(c);
    }

    private static List<String> wrap(String text, FontMetrics fm, float width) {
        return UiText.wrap(text, fm, width, Integer.MAX_VALUE);
    }

    private static double ease(double t) {
        t = Math.max(0, Math.min(1, t));
        return 1 - Math.pow(1 - t, 3);
    }

}
