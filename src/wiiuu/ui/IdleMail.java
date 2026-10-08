package wiiuu.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * An idle-time sketch: fan mail drops through the door, WII-UU settles into its armchair and reads
 * it. A letter from Timmy, a crayon drawing, a Crysis question (straight in the bin) and one made of
 * cut-out magazine letters, which it reads in silence before filing it with the others.
 *
 * <p>Worked out from the time since it started, like {@link IdleWeb}: see {@link #SCRIPT}.
 */
final class IdleMail implements IdleGames.Game {

    private static final double END = 56;
    private final BiConsumer<String, String> say;
    private final long started = System.currentTimeMillis();
    private final List<String> spoken = new ArrayList<>();
    private double t;
    private boolean done;
    private long doneAt;

    IdleMail(BiConsumer<String, String> say) {
        this.say = say;
    }

    private static final Object[][] SCRIPT = {
            {0.8, "Mail's here!", "happy"},
            {3.0, "Fan mail! It's all fan mail!", "happy"},
            {9.5, "Aww. Thank you, Timmy.", "happy"},
            {18.0, "I look amazing. Framing this one.", "smug"},
            {26.5, "No. Next.", "smug"},
            {29.8, "And it's in!", "happy"},
            // the fourth one is read in silence first
            {38.5, "Oh, that's not a fan letter, that's a death threat.", "focus"},
            {42.5, "Oh, welp. Another one to add to my collection.", "smug"},
            {49.0, "Anyway! Great fan mail today.", "happy"},
    };

    // when each letter is opened, and when it leaves the close-up
    private static final double[] OPEN = {7, 15.5, 24, 32.5};
    private static final double[] DONE = {13, 21.5, 28.5, 44};
    private static final Color[] ENVELOPE = {new Color(0xBBDEFB), new Color(0xF8BBD0), new Color(0xFAFAFA), new Color(0x546E7A)};

    @Override
    public void step(double dt, long now) {
        if (done) return;
        t = (now - started) / 1000.0;
        for (Object[] line : SCRIPT) {
            String text = (String) line[1];
            if (t >= (double) line[0] && !spoken.contains(text)) {
                spoken.add(text);
                say.accept(text, (String) line[2]);
            }
        }
        if (t >= END) {
            done = true;
            doneAt = now;
        }
    }

    @Override
    public boolean over() {
        return done;
    }

    @Override
    public long overAt() {
        return doneAt;
    }

    @Override
    public int score() {
        return 0;
    }

    @Override
    public boolean wantsToCheat() {
        return false;
    }

    @Override
    public List<IdleGames.Job> cheatJobs() {
        return List.of();
    }

    @Override
    public void cheatUndo() {
    }

    @Override
    public void cheatEnded(boolean busted) {
    }

    private static double ease(double a) {
        a = Math.max(0, Math.min(1, a));
        return a < 0.5 ? 2 * a * a : 1 - Math.pow(-2 * a + 2, 2) / 2;
    }

    private static double clamp(double a) {
        return Math.max(0, Math.min(1, a));
    }

    // ---- the room ------------------------------------------------------------------------------------

    // where things are (fractions of the width / height)
    private static final float FLOOR = 0.82f, CHAIR = 0.33f, TABLE = 0.2f, BIN = 0.45f;
    private static final float CAB_X = 0.78f, CAB_Y = 0.36f, CAB_W = 0.18f;
    private static final float PX = 0.47f, PY = 0.1f, PW = 0.26f, PH = 0.6f;    // the close-up of a letter

    @Override
    public void paint(Graphics2D g, int w, int h) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        double fade = t > END - 2.5 ? Math.max(0, (END - t) / 2.5) : 1;
        java.awt.Composite c = g.getComposite();
        g.setComposite(java.awt.AlphaComposite.SrcOver.derive((float) fade));
        g.setPaint(new GradientPaint(0, 0, new Color(0xEDE7F6), 0, h * 0.8f, new Color(0xD9D1E8)));
        g.fillRect(0, 0, w, h);
        g.setColor(new Color(0x8D6E63));
        g.fillRect(0, (int) (h * FLOOR), w, h);
        g.setColor(new Color(0x7B5E54));
        for (int i = 0; i < 12; i++) g.fillRect(i * w / 11, (int) (h * FLOOR), 2, h);
        door(g, w, h);
        cabinet(g, w, h);
        furniture(g, w, h);
        mail(g, w, h);
        guy(g, w, h);
        held(g, w, h);
        closeUp(g, w, h);
        g.setComposite(c);
    }

    private void door(Graphics2D g, int w, int h) {
        float x = w * 0.03f, y = h * 0.22f, dw = w * 0.13f, dh = h * FLOOR - y;
        g.setColor(new Color(0x5D4037));
        g.fillRect((int) (x - 4), (int) (y - 4), (int) (dw + 8), (int) (dh + 4));
        g.setColor(new Color(0xA1887F));
        g.fillRect((int) x, (int) y, (int) dw, (int) dh);
        g.setColor(new Color(0x8D6E63));
        g.drawRect((int) (x + dw * 0.12f), (int) (y + dh * 0.06f), (int) (dw * 0.76f), (int) (dh * 0.3f));
        g.drawRect((int) (x + dw * 0.12f), (int) (y + dh * 0.62f), (int) (dw * 0.76f), (int) (dh * 0.32f));
        g.setColor(new Color(0xFFD54F));
        g.fill(new Ellipse2D.Float(x + dw * 0.78f, y + dh * 0.5f, 6, 6));
        // the mail slot, its flap lifting while letters come through
        boolean open = t > 0.2 && t < 2.6;
        g.setColor(new Color(0x37474F));
        g.fillRect((int) (x + dw * 0.22f), (int) (y + dh * 0.45f), (int) (dw * 0.56f), open ? 6 : 3);
    }

    private void cabinet(Graphics2D g, int w, int h) {
        float x = w * CAB_X, y = h * CAB_Y, cw = w * CAB_W, ch = h * FLOOR - y, dh = ch / 3;
        g.setColor(new Color(0x78909C));
        g.fillRect((int) x, (int) y, (int) cw, (int) ch);
        String[] labels = {"FAN MAIL", "THREATS", "THREATS (2)"};
        for (int i = 0; i < 3; i++) {
            float dy = y + i * dh;
            float pull = i == 2 ? (float) drawerOut() * cw * 0.35f : 0;
            float bulge = i == 2 ? (float) Math.max(0, Math.sin(Math.max(0, t - 48.3) * 20) * Math.exp(-Math.max(0, t - 48.3) * 4)) * 3 : 0;
            if (i == 2 && (pull > 0 || t < 46)) {
                // paper sticking out of the full drawer; a heap of it when it's open
                g.setColor(new Color(0xFAFAFA));
                int sheets = pull > 0 ? 9 : 3;
                for (int k = 0; k < sheets; k++) {
                    float sx = x - pull + cw * (0.1f + (k % 5) * 0.16f), sy = dy + dh * 0.05f - (pull > 0 ? (k / 5 + 1) * dh * 0.18f : dh * 0.06f);
                    AffineTransform at = g.getTransform();
                    g.rotate((k % 3 - 1) * 0.25, sx, sy);
                    g.fillRect((int) sx, (int) sy, (int) (cw * 0.2f), (int) (dh * 0.35f));
                    g.setColor(new Color(0xB0BEC5));
                    g.drawRect((int) sx, (int) sy, (int) (cw * 0.2f), (int) (dh * 0.35f));
                    g.setColor(new Color(0xFAFAFA));
                    g.setTransform(at);
                }
            }
            g.setColor(new Color(0x90A4AE));
            g.fillRect((int) (x + 3 - pull), (int) (dy + 3 - bulge), (int) (cw - 6), (int) (dh - 6 + bulge));
            g.setColor(new Color(0x607D8B));
            g.drawRect((int) (x + 3 - pull), (int) (dy + 3 - bulge), (int) (cw - 6), (int) (dh - 6 + bulge));
            // label card and handle
            g.setColor(Color.WHITE);
            g.fillRect((int) (x + cw * 0.12f - pull), (int) (dy + dh * 0.18f), (int) (cw * 0.76f), (int) (dh * 0.3f));
            g.setColor(new Color(0x263238));
            g.setFont(MenuView.font(Font.BOLD, h * 0.028f));
            FontMetrics fm = g.getFontMetrics();
            g.drawString(labels[i], x + cw / 2 - fm.stringWidth(labels[i]) / 2f - pull, dy + dh * 0.18f + dh * 0.15f + fm.getAscent() * 0.38f);
            g.setColor(new Color(0x455A64));
            g.fill(new RoundRectangle2D.Float(x + cw * 0.38f - pull, dy + dh * 0.62f, cw * 0.24f, dh * 0.1f, 4, 4));
        }
    }

    /** How far the bottom drawer is open, 0..1. */
    private double drawerOut() {
        if (t < 46) return 0;
        if (t < 46.6) return ease((t - 46) / 0.6);
        if (t < 47.8) return 1;
        return 1 - ease((t - 47.8) / 0.5);
    }

    private void furniture(Graphics2D g, int w, int h) {
        float floor = h * FLOOR;
        // side table with the letters it keeps
        float tx = w * TABLE, ty = h * 0.68f;
        g.setColor(new Color(0x6D4C41));
        g.fillRect((int) (tx - w * 0.045f), (int) ty, (int) (w * 0.09f), (int) (h * 0.02f));
        g.fillRect((int) (tx - 2), (int) ty, 4, (int) (floor - ty));
        // the armchair
        float cx = w * CHAIR, cw = w * 0.14f;
        g.setColor(new Color(0xC62828));
        g.fill(new RoundRectangle2D.Float(cx - cw / 2, h * 0.52f, cw, h * 0.26f, 20, 20));      // back
        g.setColor(new Color(0xB71C1C));
        g.fill(new RoundRectangle2D.Float(cx - cw / 2 - 6, h * 0.66f, cw + 12, h * 0.12f, 14, 14));  // seat and arms
        g.setColor(new Color(0x4E342E));
        g.fillRect((int) (cx - cw / 2), (int) (h * 0.78f), 4, (int) (floor - h * 0.78f));
        g.fillRect((int) (cx + cw / 2 - 4), (int) (h * 0.78f), 4, (int) (floor - h * 0.78f));
        // the bin
        float bx = w * BIN, bw = w * 0.04f, bh = h * 0.08f;
        Path2D bin = new Path2D.Float();
        bin.moveTo(bx - bw / 2, floor - bh);
        bin.lineTo(bx + bw / 2, floor - bh);
        bin.lineTo(bx + bw * 0.4f, floor);
        bin.lineTo(bx - bw * 0.4f, floor);
        bin.closePath();
        g.setColor(new Color(0x9E9E9E));
        g.fill(bin);
        g.setColor(new Color(0x757575));
        for (int i = 1; i < 4; i++) g.drawLine((int) (bx - bw / 2 + i * bw / 4), (int) (floor - bh + 2), (int) (bx - bw * 0.4f + i * bw * 0.2f), (int) floor - 2);
    }

    /** The envelopes: through the slot onto the floor, then in a stack on the armchair's arm. */
    private void mail(Graphics2D g, int w, int h) {
        float ew = w * 0.045f, eh = ew * 0.65f, floor = h * FLOOR;
        float slotX = w * 0.095f, slotY = h * (0.22f + (FLOOR - 0.22f) * 0.45f);
        for (int i = 0; i < 4; i++) {
            double drop = 0.3 + i * 0.5;
            if (t < drop) continue;
            float x, y;
            double rot;
            if (t < 4.0) {                                          // falling, then lying on the floor
                double k = clamp((t - drop) / 0.6);
                float lx = w * (0.12f + i * 0.025f), ly = floor - eh - 1 - i * 2;
                x = (float) (slotX + (lx - slotX) * k);
                y = (float) (slotY + (ly - slotY) * k * k);
                rot = k * ((i % 2) * 2 - 1) * 0.3;
            } else if (t < 6.0) {                                   // picked up and carried
                double k = ease((t - 4.0) / 2.0);
                float gx = guyX(w) - ew / 2, gy = h * 0.66f - i * 2;
                x = gx;
                y = gy - (float) (Math.sin(k * Math.PI) * h * 0.02f);
                rot = 0;
            } else {                                                // a stack on the chair's arm, until opened
                if (t >= OPEN[i]) continue;
                x = w * CHAIR - w * 0.07f - ew * 0.5f - 4;
                y = h * 0.66f - eh - i * 3;
                rot = 0;
            }
            envelope(g, i, x, y, ew, eh, rot, 0);
        }
    }

    private void envelope(Graphics2D g, int i, float x, float y, float ew, float eh, double rot, double flap) {
        AffineTransform at = g.getTransform();
        g.rotate(rot, x + ew / 2, y + eh / 2);
        g.setColor(ENVELOPE[i]);
        g.fillRect((int) x, (int) y, (int) ew, (int) eh);
        g.setColor(i == 3 ? new Color(0x263238) : new Color(0x90A4AE));
        g.drawRect((int) x, (int) y, (int) ew, (int) eh);
        Path2D f = new Path2D.Float();
        f.moveTo(x, y);
        f.lineTo(x + ew / 2, y + (float) (eh * 0.55f * (1 - 2 * flap)));
        f.lineTo(x + ew, y);
        g.draw(f);
        if (i != 3) {                                               // a stamp (the last one has none)
            g.setColor(new Color(0xE53935));
            g.fillRect((int) (x + ew * 0.75f), (int) (y + eh * 0.12f), Math.max(2, (int) (ew * 0.15f)), Math.max(2, (int) (eh * 0.25f)));
        }
        g.setTransform(at);
    }

    // ---- the guy -------------------------------------------------------------------------------------

    private float guyX(int w) {
        double x;
        if (t < 3.2) x = CHAIR;
        else if (t < 4.0) x = CHAIR + (0.15 - CHAIR) * ease((t - 3.2) / 0.8);
        else if (t < 6.0) x = 0.15 + (CHAIR - 0.15) * ease((t - 4.0) / 2.0);
        else if (t < 44.3) x = CHAIR;
        else if (t < 45.8) x = CHAIR + (0.66 - CHAIR) * ease((t - 44.3) / 1.5);
        else if (t < 49.5) x = 0.66;
        else x = 0.66 + (CHAIR - 0.66) * ease((t - 49.5) / 1.5);
        return (float) (x * w);
    }

    private boolean sitting() {
        return (t < 3.2 || t >= 6.0) && (t < 44.3 || t >= 51.0);
    }

    private void guy(Graphics2D g, int w, int h) {
        float s = h * 0.06f, cx = guyX(w), cy = sitting() ? h * 0.62f : h * FLOOR - s * 1.6f;
        if (sitting()) cy += (float) Math.abs(Math.sin(t * 1.5)) * 1.5f;
        if (!sitting()) {
            g.setColor(new Color(0x37474F));
            g.setStroke(new BasicStroke(Math.max(1.5f, s * 0.12f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            float step = (float) Math.sin(t * 12) * s * 0.25f;
            boolean still = t >= 45.8 && t < 49.5;
            if (still) step = 0;
            g.drawLine((int) (cx - s * 0.35f), (int) (cy + s * 0.45f), (int) (cx - s * 0.35f + step), (int) (cy + s * 1.6f));
            g.drawLine((int) (cx + s * 0.35f), (int) (cy + s * 0.45f), (int) (cx + s * 0.35f - step), (int) (cy + s * 1.6f));
            g.setStroke(new BasicStroke(1));
        }
        float cw = s * 2.6f, ch = s;
        g.setColor(new Color(0, 0, 0, 60));
        g.fill(new RoundRectangle2D.Float(cx - cw / 2 + 2, cy - ch / 2 + 3, cw, ch, ch * 0.6f, ch * 0.6f));
        g.setColor(Color.WHITE);
        g.fill(new RoundRectangle2D.Float(cx - cw / 2, cy - ch / 2, cw, ch, ch * 0.6f, ch * 0.6f));
        g.setColor(new Color(0xCFD8DC));
        g.draw(new RoundRectangle2D.Float(cx - cw / 2, cy - ch / 2, cw, ch, ch * 0.6f, ch * 0.6f));
        g.setFont(MenuView.font(Font.BOLD, s * 0.55f));
        FontMetrics fm = g.getFontMetrics();
        float lx = cx - fm.stringWidth("WII-UU") / 2f, ly = cy + fm.getAscent() * 0.38f;
        g.setColor(new Color(0x2B2F36));
        g.drawString("WII-", lx, ly);
        g.setColor(MenuView.ACCENT);
        g.drawString("UU", lx + fm.stringWidth("WII-"), ly);
        // googly eyes: reading line by line, staring, or looking where it goes
        float look, up;
        int reading = reading();
        boolean silent = t >= 33.4 && t < 38.5;
        if (silent && t >= 37.2) {                                   // stops reading. stares.
            look = 0.9f;
            up = 0;
        } else if (reading >= 0 && t > OPEN[reading] + 1.4) {
            double r = (t - OPEN[reading] - 1.4) * (silent ? 0.6 : 0.9);
            look = (float) (-0.2 + (r % 1) * 1.2);
            up = (float) (-0.6 + Math.min(1.2, Math.floor(r) * 0.3));
        } else if (t < 6) {
            look = -1;
            up = 0.5f;
        } else if (t >= 44.3 && t < 49.5) {
            look = 1;
            up = 0.6f;
        } else {
            look = 0.6f;
            up = 0.2f;
        }
        float er = s * 0.26f * (silent && t >= 37.2 ? 1.15f : 1);
        for (int e = -1; e <= 1; e += 2) {
            float ex = cx + e * s * 0.42f, ey = cy - ch / 2 - s * 0.12f;
            g.setColor(Color.WHITE);
            g.fill(new Ellipse2D.Float(ex - er, ey - er, er * 2, er * 2));
            g.setColor(new Color(0x2B2F36));
            g.setStroke(new BasicStroke(Math.max(1, s * 0.05f)));
            g.draw(new Ellipse2D.Float(ex - er, ey - er, er * 2, er * 2));
            float pr = silent && t >= 37.2 ? er * 0.6f : er;            // tiny pupils
            g.fill(new Ellipse2D.Float(ex - pr * 0.5f + look * er * 0.4f, ey - pr * 0.5f + up * er * 0.4f, pr, pr));
        }
        g.setStroke(new BasicStroke(1));
        if (silent && t >= 37.6) {                                   // a single drop of sweat
            float k = (float) clamp((t - 37.6) / 0.9);
            g.setColor(new Color(0x4FC3F7));
            g.fill(new Ellipse2D.Float(cx + cw / 2 - s * 0.1f, cy - ch / 2 + k * s * 0.4f, s * 0.18f, s * 0.26f));
        }
    }

    /** The letter being read now (0..3), or -1. */
    private int reading() {
        for (int i = 0; i < 4; i++) if (t >= OPEN[i] && t < DONE[i]) return i;
        return -1;
    }

    // ---- the letters ---------------------------------------------------------------------------------

    /** Envelopes opening in its hands, and what happens to letters after reading. */
    private void held(Graphics2D g, int w, int h) {
        float ew = w * 0.05f, eh = ew * 0.65f;
        float hx = guyX(w) + h * 0.06f * 1.1f, hy = h * 0.64f;
        for (int i = 0; i < 4; i++) {
            if (t >= OPEN[i] && t < OPEN[i] + 1.0) envelope(g, i, hx - ew / 2, hy, ew, eh, -0.1, clamp((t - OPEN[i]) / 0.6));
        }
        // kept letters: a little pile on the side table
        float sw = w * 0.035f, sh = sw * 1.3f;
        for (int i = 0; i < 2; i++) {
            if (t < DONE[i] + 0.8) continue;
            float x = w * TABLE - sw / 2 + i * 3, y = h * 0.68f - sh * 0.35f - i * 2;
            g.setColor(Color.WHITE);
            AffineTransform at = g.getTransform();
            g.rotate((i * 2 - 1) * 0.12, x + sw / 2, y + sh / 2);
            g.fillRect((int) x, (int) y, (int) sw, (int) (sh * 0.35f));
            g.setColor(new Color(0xB0BEC5));
            g.drawRect((int) x, (int) y, (int) sw, (int) (sh * 0.35f));
            g.setTransform(at);
        }
        // the Crysis letter: a paper ball, thrown into the bin
        double c = DONE[2];
        if (t >= c + 0.6 && t < c + 1.6) {
            double k = (t - c - 0.6);
            float sx = w * (PX + PW / 2), sy = h * (PY + PH / 2), bx = w * BIN, by = h * FLOOR - h * 0.085f;
            float x = (float) (sx + (bx - sx) * k), y = (float) (sy + (by - sy) * k - Math.sin(k * Math.PI) * h * 0.25f);
            ball(g, x, y, w * 0.022f);
        } else if (t >= c + 1.6) {
            ball(g, w * BIN, h * FLOOR - h * 0.085f, w * 0.016f);
        }
        // the last one: carried to the cabinet and dropped in
        double d = DONE[3];
        if (t >= d + 0.5 && t < 47.3) {
            float x = guyX(w) + h * 0.06f, y = h * 0.6f + (sitting() ? 0 : h * 0.06f);
            if (t >= 46.5) {
                double k = ease((t - 46.5) / 0.8);
                float tx = w * CAB_X - w * CAB_W * 0.35f + w * CAB_W * 0.4f, ty = h * CAB_Y + (h * FLOOR - h * CAB_Y) * 2 / 3;
                x = (float) (x + (tx - x) * k);
                y = (float) (y + (ty - y) * k - Math.sin(k * Math.PI) * h * 0.06f);
            }
            g.setColor(new Color(0xFFF8E1));
            g.fillRect((int) x, (int) y, (int) sw, (int) (sh * 0.9f));
            g.setColor(new Color(0x8D6E63));
            g.drawRect((int) x, (int) y, (int) sw, (int) (sh * 0.9f));
        }
    }

    private static void ball(Graphics2D g, float x, float y, float r) {
        g.setColor(Color.WHITE);
        g.fill(new Ellipse2D.Float(x - r, y - r, r * 2, r * 2));
        g.setColor(new Color(0x9E9E9E));
        g.draw(new Ellipse2D.Float(x - r, y - r, r * 2, r * 2));
        g.drawLine((int) (x - r * 0.5f), (int) (y - r * 0.2f), (int) (x + r * 0.3f), (int) (y + r * 0.4f));
        g.drawLine((int) (x - r * 0.1f), (int) (y - r * 0.6f), (int) (x + r * 0.5f), (int) (y - r * 0.1f));
    }

    /** The letter being read, big, next to WII-UU: it grows out of the envelope and goes away again. */
    private void closeUp(Graphics2D g, int w, int h) {
        int i = -1;
        for (int k = 0; k < 4; k++) if (t >= OPEN[k] + 0.6 && t < DONE[k] + (k == 2 ? 0.6 : 0.5)) i = k;
        if (i < 0) return;
        double grow = ease((t - OPEN[i] - 0.6) / 0.7);
        double shrink = ease((t - DONE[i]) / (i == 2 ? 0.6 : 0.5));
        double k = grow * (1 - shrink);
        float fx = guyX(w) + h * 0.07f, fy = h * 0.62f;
        float pw = w * PW, ph = h * PH;
        float x = (float) (fx + (w * PX - fx) * k), y = (float) (fy + (h * PY - fy) * k);
        float sw = (float) (pw * Math.max(0.05, k)), sh = (float) (ph * Math.max(0.05, k));
        double rot = (i - 1.5) * 0.02 + (i == 2 ? shrink * 2.5 : 0);
        AffineTransform at = g.getTransform();
        g.translate(x + sw / 2, y + sh / 2);
        g.rotate(rot);
        g.scale(sw / pw, sh / ph);
        g.translate(-pw / 2, -ph / 2);
        letter(g, i, pw, ph);
        g.setTransform(at);
    }

    private void letter(Graphics2D g, int i, float w, float h) {
        g.setColor(new Color(0, 0, 0, 50));
        g.fillRect(4, 5, (int) w, (int) h);
        g.setColor(i == 3 ? new Color(0xF3E5AB) : Color.WHITE);
        g.fillRect(0, 0, (int) w, (int) h);
        float m = w * 0.08f, line = h * 0.1f;
        switch (i) {
            case 0 -> {                                             // Timmy, on lined paper
                g.setColor(new Color(0xBBDEFB));
                for (float y = h * 0.16f; y < h; y += line) g.drawLine(0, (int) y, (int) w, (int) y);
                g.setColor(new Color(0xEF9A9A));
                g.drawLine((int) (m * 0.7f), 0, (int) (m * 0.7f), (int) h);
                g.setColor(new Color(0x1A237E));
                g.setFont(MenuView.font(Font.ITALIC, line * 0.62f));
                String[] text = {"Dear WII-UU,", "you are the best", "console EVER!!", "I play every day", "after school.", "", "Love, Timmy (9)"};
                for (int k = 0; k < text.length; k++) g.drawString(text[k], m * 1.2f, h * 0.16f + line * k - line * 0.15f);
                heart(g, w * 0.78f, h * 0.84f, line * 0.6f);
            }
            case 1 -> {                                             // a crayon drawing of WII-UU
                g.setStroke(new BasicStroke(w * 0.012f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.setColor(new Color(0xE53935));
                g.setFont(MenuView.font(Font.BOLD, h * 0.075f));
                g.drawString("I DREW YOU!!", m, h * 0.11f);
                g.setColor(new Color(0xFDD835));                    // the sun
                g.drawOval((int) (w * 0.74f), (int) (h * 0.16f), (int) (w * 0.14f), (int) (w * 0.14f));
                for (int r = 0; r < 8; r++) {
                    double a = r * Math.PI / 4;
                    float sx = w * 0.81f, sy = h * 0.16f + w * 0.07f;
                    g.drawLine((int) (sx + Math.cos(a) * w * 0.09f), (int) (sy + Math.sin(a) * w * 0.09f),
                            (int) (sx + Math.cos(a) * w * 0.13f), (int) (sy + Math.sin(a) * w * 0.13f));
                }
                Path2D body = new Path2D.Float();                   // a wobbly rectangle with eyes
                body.moveTo(w * 0.14f, h * 0.42f);
                body.curveTo(w * 0.35f, h * 0.38f, w * 0.6f, h * 0.44f, w * 0.84f, h * 0.4f);
                body.lineTo(w * 0.86f, h * 0.6f);
                body.curveTo(w * 0.6f, h * 0.63f, w * 0.4f, h * 0.58f, w * 0.12f, h * 0.62f);
                body.closePath();
                g.setColor(new Color(0x1E88E5));
                g.draw(body);
                g.setColor(Color.BLACK);
                g.drawOval((int) (w * 0.33f), (int) (h * 0.28f), (int) (w * 0.12f), (int) (w * 0.12f));
                g.drawOval((int) (w * 0.53f), (int) (h * 0.27f), (int) (w * 0.14f), (int) (w * 0.14f));
                g.fillOval((int) (w * 0.37f), (int) (h * 0.31f), (int) (w * 0.04f), (int) (w * 0.04f));
                g.fillOval((int) (w * 0.6f), (int) (h * 0.29f), (int) (w * 0.05f), (int) (w * 0.05f));
                g.setColor(new Color(0x43A047));
                g.setFont(MenuView.font(Font.BOLD, h * 0.06f));
                g.drawString("WEE-OO", w * 0.3f, h * 0.53f);
                g.drawLine(0, (int) (h * 0.85f), (int) w, (int) (h * 0.83f));   // grass
                g.setColor(new Color(0x8E24AA));
                g.setFont(MenuView.font(Font.BOLD, h * 0.05f));
                g.drawString("by Mia", w * 0.55f, h * 0.95f);
                g.setStroke(new BasicStroke(1));
            }
            case 2 -> {                                             // typed, to the point
                g.setColor(new Color(0x212121));
                g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, Math.round(line * 0.62f)));
                String[] text = {"Dear WII-UU,", "", "Can you run Crysis?", "", "Asking for a friend.", "", "- Definitely", "  not a PC"};
                g.setFont(g.getFont().deriveFont(Font.BOLD));
                for (int k = 0; k < text.length; k++) g.drawString(text[k], m, h * 0.16f + line * 0.85f * k);
            }
            default -> {                                            // cut out of magazines
                String[][] words = {{"DEAR", "WII-UU,"}, {"YOU", "HAVE", "PLAYED"}, {"YOUR", "LAST"}, {"GAME", "OF", "PONG."}, {"SLEEP", "WELL."}, {"-", "THE", "TV"}};
                Color[] paper = {new Color(0xFFFFFF), new Color(0xFFEB3B), new Color(0x212121), new Color(0xF48FB1), new Color(0x80DEEA), new Color(0xE53935)};
                String[] fonts = {Font.SERIF, Font.SANS_SERIF, Font.MONOSPACED};
                float y = h * 0.1f;
                int n = 0;
                for (String[] row : words) {
                    float x = m * 0.7f;
                    for (String word : row) {
                        int style = n % 3 == 0 ? Font.BOLD : n % 3 == 1 ? Font.PLAIN : Font.BOLD | Font.ITALIC;
                        float size = line * (0.55f + (n * 7 % 5) * 0.06f);
                        g.setFont(new Font(fonts[n % 3], style, Math.round(size)));
                        FontMetrics fm = g.getFontMetrics();
                        float ww = fm.stringWidth(word) + 4, wh = fm.getAscent() + 2;
                        Color bg = paper[n % paper.length];
                        AffineTransform at = g.getTransform();
                        g.rotate(((n * 5) % 7 - 3) * 0.025, x + ww / 2, y);
                        g.setColor(bg);
                        g.fillRect((int) x, (int) (y - wh + 2), (int) ww, (int) wh);
                        g.setColor(bg.getRed() + bg.getGreen() + bg.getBlue() < 300 ? Color.WHITE : Color.BLACK);
                        g.drawString(word, x + 2, y);
                        g.setTransform(at);
                        x += ww + 3;
                        n++;
                    }
                    y += line * 1.25f;
                }
            }
        }
    }

    private static void heart(Graphics2D g, float x, float y, float s) {
        Path2D p = new Path2D.Float();
        p.moveTo(x, y + s * 0.3f);
        p.curveTo(x - s, y - s * 0.4f, x - s * 0.3f, y - s, x, y - s * 0.4f);
        p.curveTo(x + s * 0.3f, y - s, x + s, y - s * 0.4f, x, y + s * 0.3f);
        g.setColor(new Color(0xE53935));
        g.fill(p);
    }
}
