package wiiuu.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * An idle-time sketch: late at night WII-UU sneaks onto its computer to read fanfic.com, which its
 * (made-up) internet provider has blocked for "reasons". It tries www, secret mode and a VPN to
 * Antarctica, finally gets to chapter 47 of "The GamePad and Me", gets blocked again, and pretends
 * to be asleep when the hallway light comes on. All drawn: nothing is visited.
 *
 * <p>Worked out from the time since it started, like {@link IdleWeb}: see {@link #SCRIPT}.
 */
final class IdleNight implements IdleGames.Game {

    private static final double END = 58;
    private final BiConsumer<String, String> say;
    private final long started = System.currentTimeMillis();
    private final List<String> spoken = new ArrayList<>();
    private double t;
    private boolean done;
    private long doneAt;

    IdleNight(BiConsumer<String, String> say) {
        this.say = say;
    }

    private static final Object[][] SCRIPT = {
            {0.6, "Everyone's asleep. Finally.", "sneaky"},
            {3.5, "Time for some... light reading.", "sneaky"},
            {9.5, "Blocked?! For \"reasons\"? What reasons?!", "nervous"},
            {17.5, "Rude.", "smug"},
            {20.0, "Okay. Secret mode.", "sneaky"},
            {25.8, "...How?", "nervous"},
            {28.5, "Fine. VPN.", "focus"},
            {33.0, "Hello, penguins.", "happy"},
            {38.0, "CHAPTER 47 IS OUT!", "happy"},
            {40.8, "Just one chapter. Then bed.", "focus"},
            {45.6, "NOOO! It was just getting good!", "sad"},
            {48.9, "Zzz... I'm asleep. Consoles sleep. Zzz...", "sneaky"},
            {55.0, "...Chapter 48 comes out Tuesday.", "smug"},
    };

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

    private static double clamp(double a) {
        return Math.max(0, Math.min(1, a));
    }

    private static double ease(double a) {
        a = clamp(a);
        return a < 0.5 ? 2 * a * a : 1 - Math.pow(-2 * a + 2, 2) / 2;
    }

    // ---- the timeline --------------------------------------------------------------------------------

    private static final double OFF = 48.4, HALL_ON = 48.3, HALL_OFF = 53.2;

    private boolean monitorOn() {
        return t < OFF;
    }

    private boolean hallLight() {
        return t >= HALL_ON && t < HALL_OFF;
    }

    /** What's typed in the address bar now, or null when it shows the page's own address. */
    private String typing() {
        if (t >= 4 && t < 7.5) return part("fanfic.com", 4, 7);
        if (t >= 14 && t < 16) return part("www.fanfic.com", 14, 15.6);
        if (t >= 21.5 && t < 23.5) return part("fanfic.com", 21.5, 23);
        if (t >= 34.3 && t < 35.8) return part("fanfic.com", 34.3, 35.4);
        return null;
    }

    private String part(String s, double from, double to) {
        return s.substring(0, (int) Math.round(s.length() * clamp((t - from) / (to - from))));
    }

    private boolean loading() {
        return t >= 7.5 && t < 8.5 || t >= 16 && t < 16.5 || t >= 23.5 && t < 24.5 || t >= 35.8 && t < 36.5;
    }

    private boolean secret() {
        return t >= 20.5 && t < 28.5;
    }

    // ---- the room ------------------------------------------------------------------------------------

    @Override
    public void paint(Graphics2D g, int w, int h) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        double fade = t > END - 2.5 ? Math.max(0, (END - t) / 2.5) : 1;
        java.awt.Composite c = g.getComposite();
        g.setComposite(java.awt.AlphaComposite.SrcOver.derive((float) fade));
        g.setPaint(new GradientPaint(0, 0, new Color(0x141B2E), 0, h * 0.8f, new Color(0x1C2540)));
        g.fillRect(0, 0, w, h);
        g.setColor(new Color(0x0E1220));
        g.fillRect(0, (int) (h * 0.84f), w, h);
        window(g, w, h);
        clock(g, w, h);
        hall(g, w, h);
        if (monitorOn()) {                                          // the screen's glow
            float gx = w * 0.58f, gy = h * 0.42f, r = w * 0.55f;
            g.setPaint(new RadialGradientPaint(gx, gy, r, new float[]{0, 1},
                    new Color[]{secret() ? new Color(150, 110, 255, 70) : new Color(120, 170, 255, 70), new Color(0, 0, 0, 0)}));
            g.fillRect(0, 0, w, h);
        }
        desk(g, w, h);
        guy(g, w, h);
        g.setComposite(c);
    }

    private void window(Graphics2D g, int w, int h) {
        float x = w * 0.03f, y = h * 0.1f, ww = w * 0.13f, wh = h * 0.3f;
        g.setColor(new Color(0x0A0F1E));
        g.fillRect((int) x, (int) y, (int) ww, (int) wh);
        g.setColor(new Color(255, 255, 255, 160));
        for (int i = 0; i < 9; i++) {
            float sx = x + ww * ((i * 0.37f) % 1), sy = y + wh * ((i * 0.53f + 0.1f) % 1);
            float tw = (float) (0.6 + 0.4 * Math.sin(t * 2 + i));
            g.setColor(new Color(1f, 1f, 1f, (float) (0.4 * tw + 0.2)));
            g.fillRect((int) sx, (int) sy, 2, 2);
        }
        g.setColor(new Color(0xF5F0D0));
        g.fill(new Ellipse2D.Float(x + ww * 0.55f, y + wh * 0.12f, ww * 0.28f, ww * 0.28f));
        g.setColor(new Color(0x0A0F1E));
        g.fill(new Ellipse2D.Float(x + ww * 0.62f, y + wh * 0.09f, ww * 0.26f, ww * 0.26f));
        g.setColor(new Color(0x3A4568));
        g.setStroke(new BasicStroke(3));
        g.drawRect((int) x, (int) y, (int) ww, (int) wh);
        g.drawLine((int) (x + ww / 2), (int) y, (int) (x + ww / 2), (int) (y + wh));
        g.drawLine((int) x, (int) (y + wh / 2), (int) (x + ww), (int) (y + wh / 2));
        g.setStroke(new BasicStroke(1));
    }

    private void clock(Graphics2D g, int w, int h) {
        float x = w * 0.19f, y = h * 0.12f, cw = w * 0.1f, ch = h * 0.07f;
        g.setColor(new Color(0x090C14));
        g.fill(new RoundRectangle2D.Float(x, y, cw, ch, 6, 6));
        int minute = 47 + (int) (t / 20);
        g.setColor(new Color(0xFF5252));
        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, Math.max(8, Math.round(ch * 0.55f))));
        String time = "2:" + minute;
        FontMetrics fm = g.getFontMetrics();
        g.drawString(time, x + (cw - fm.stringWidth(time)) / 2 - cw * 0.06f, y + ch * 0.7f);
        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, Math.max(6, Math.round(ch * 0.25f))));
        g.drawString("AM", x + cw * 0.78f, y + ch * 0.4f);
    }

    /** The hallway door on the right: shut, then a crack of light when someone gets up. */
    private void hall(Graphics2D g, int w, int h) {
        float x = w * 0.9f, y = h * 0.14f, dw = w * 0.1f, dh = h * 0.84f - y;
        g.setColor(new Color(0x10162A));
        g.fillRect((int) x, (int) y, (int) dw, (int) dh);
        g.setColor(new Color(0x2A3352));
        g.drawRect((int) x, (int) y, (int) dw, (int) dh);
        double open = hallLight() ? ease((t - HALL_ON) / 0.5) : t >= HALL_OFF ? 1 - ease((t - HALL_OFF) / 0.4) : 0;
        if (open <= 0) {                                            // light under the door? no: dark
            return;
        }
        float crack = (float) (dw * 0.18f * open);
        g.setColor(new Color(0xFFE7A8));
        g.fillRect((int) x, (int) y, (int) crack, (int) dh);
        Path2D beam = new Path2D.Float();                           // a wedge of light on the floor
        beam.moveTo(x, h * 0.84f);
        beam.lineTo(x + crack, h * 0.84f);
        beam.lineTo(x - w * 0.3f * open, h);
        beam.lineTo(x - w * 0.55f * open, h);
        beam.closePath();
        g.setColor(new Color(255, 231, 168, 70));
        g.fill(beam);
        g.setColor(new Color(255, 231, 168, (int) (22 * open)));
        g.fillRect(0, 0, w, h);
    }

    // ---- the desk and the screen ---------------------------------------------------------------------

    private static final float MX = 0.33f, MY = 0.1f, MW = 0.52f, MH = 0.56f;

    private void desk(Graphics2D g, int w, int h) {
        float dy = h * 0.74f;
        g.setColor(new Color(0x2B2118));
        g.fillRect((int) (w * 0.08f), (int) dy, (int) (w * 0.8f), (int) (h * 0.035f));
        g.fillRect((int) (w * 0.1f), (int) dy, (int) (w * 0.02f), (int) (h * 0.84f - dy));
        g.fillRect((int) (w * 0.84f), (int) dy, (int) (w * 0.02f), (int) (h * 0.84f - dy));
        float mx = w * MX, my = h * MY, mw = w * MW, mh = h * MH;
        g.setColor(new Color(0x0B0D12));
        g.fill(new RoundRectangle2D.Float(mx, my, mw, mh, 10, 10));
        g.fillRect((int) (mx + mw * 0.46f), (int) (my + mh), (int) (mw * 0.08f), (int) (dy - my - mh));
        g.fillRect((int) (mx + mw * 0.36f), (int) (dy - 4), (int) (mw * 0.28f), 4);
        g.setColor(monitorOn() ? new Color(0x4CAF50) : new Color(0xFF9800));   // power light
        g.fill(new Ellipse2D.Float(mx + mw - 10, my + mh - 6, 4, 4));
        float sx = mx + 5, sy = my + 5, sw = mw - 10, sh = mh - 12;
        if (monitorOn()) screen(g, sx, sy, sw, sh, h);
        else {
            g.setColor(new Color(0x05060A));
            g.fillRect((int) sx, (int) sy, (int) sw, (int) sh);
        }
        // keyboard, lit by the screen
        g.setColor(monitorOn() ? new Color(0x37415A) : new Color(0x1C2130));
        g.fillRect((int) (w * 0.42f), (int) (dy - h * 0.02f), (int) (w * 0.22f), (int) (h * 0.02f));
    }

    private void screen(Graphics2D g, float x, float y, float w, float h, int H) {
        java.awt.Shape clip = g.getClip();
        g.clipRect((int) x, (int) y, (int) w, (int) h);
        float bar = h * 0.11f;
        boolean secret = secret();
        // the browser
        g.setColor(secret ? new Color(0x2E2840) : new Color(0xDEE3EA));
        g.fillRect((int) x, (int) y, (int) w, (int) bar);
        float ux = x + bar * 0.4f + (secret ? bar * 1.1f : 0), uy = y + bar * 0.18f, uw = w - (ux - x) - bar * 0.4f, uh = bar * 0.64f;
        if (secret) {                                               // hat and glasses
            g.setColor(new Color(0xC9B8FF));
            float ix = x + bar * 0.35f, iy = y + bar * 0.2f, is = bar * 0.6f;
            g.fillRect((int) ix, (int) (iy + is * 0.25f), (int) is, (int) (is * 0.12f));
            g.fillRect((int) (ix + is * 0.2f), (int) iy, (int) (is * 0.6f), (int) (is * 0.3f));
            g.drawOval((int) (ix + is * 0.05f), (int) (iy + is * 0.5f), (int) (is * 0.35f), (int) (is * 0.3f));
            g.drawOval((int) (ix + is * 0.6f), (int) (iy + is * 0.5f), (int) (is * 0.35f), (int) (is * 0.3f));
        }
        g.setColor(secret ? new Color(0x403856) : Color.WHITE);
        g.fill(new RoundRectangle2D.Float(ux, uy, uw, uh, uh, uh));
        String typed = typing();
        String url = typed != null ? typed : address();
        g.setColor(secret ? new Color(0xE8E0FF) : new Color(0x202124));
        g.setFont(MenuView.font(Font.PLAIN, H * 0.03f));
        FontMetrics fm = g.getFontMetrics();
        g.drawString(url, ux + uh * 0.5f, uy + uh * 0.5f + fm.getAscent() * 0.38f);
        if (typed != null && (int) (t * 3) % 2 == 0) {
            float cx = ux + uh * 0.5f + fm.stringWidth(url) + 1;
            g.drawLine((int) cx, (int) (uy + uh * 0.2f), (int) cx, (int) (uy + uh * 0.8f));
        }
        if (vpnOn()) {                                              // a little VPN badge
            String b = "VPN: Antarctica";
            g.setFont(MenuView.font(Font.BOLD, H * 0.022f));
            FontMetrics bf = g.getFontMetrics();
            float bw = bf.stringWidth(b) + 8;
            g.setColor(new Color(0x26A69A));
            g.fill(new RoundRectangle2D.Float(ux + uw - bw - 4, uy + 2, bw, uh - 4, uh - 4, uh - 4));
            g.setColor(Color.WHITE);
            g.drawString(b, ux + uw - bw, uy + uh * 0.5f + bf.getAscent() * 0.38f);
        }
        float py = y + bar, ph = h - bar;
        if (loading()) {
            g.setColor(secret ? new Color(0x1E1A2B) : Color.WHITE);
            g.fillRect((int) x, (int) py, (int) w, (int) ph);
            g.setColor(new Color(0x1A73E8));
            g.fillRect((int) x, (int) py, (int) (w * (0.2 + 0.8 * ((t * 1.3) % 1))), 3);
            spinner(g, x + w / 2, py + ph / 2, ph * 0.08f);
        } else page(g, x, py, w, ph, H);
        if (t >= 28.5 && t < 34.3) vpn(g, x, py, w, ph, H);
        g.setClip(clip);
    }

    private boolean vpnOn() {
        return t >= 32 && t < OFF;
    }

    private String address() {
        if (t < 4) return "";
        if (t < 14) return "fanfic.com";
        if (t < 20.5) return "www.fanfic.com";
        if (t < 34.3) return "fanfic.com";
        if (t < 40.5) return "fanfic.com";
        return "fanfic.com/the-gamepad-and-me/chapter-47";
    }

    private void spinner(Graphics2D g, float cx, float cy, float r) {
        g.setStroke(new BasicStroke(Math.max(2, r * 0.3f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(0x1A73E8));
        g.drawArc((int) (cx - r), (int) (cy - r), (int) (r * 2), (int) (r * 2), (int) (-t * 400), 270);
        g.setStroke(new BasicStroke(1));
    }

    /** What the page shows: a new tab, the provider's block page, or (finally) fanfic.com. */
    private void page(Graphics2D g, float x, float y, float w, float h, int H) {
        if (t < 8.5) newTab(g, x, y, w, h, H);
        else if (t < 14) blocked(g, x, y, w, h, H, "Reason: reasons.", null, 0);
        else if (t < 16) blocked(g, x, y, w, h, H, "Reason: reasons.", null, 0);
        else if (t < 21.5) blocked(g, x, y, w, h, H, "Reason: still reasons.", null, 0);
        else if (t < 23.5) newTab(g, x, y, w, h, H);
        else if (t < 34.3) blocked(g, x, y, w, h, H, "Reason: we can see you in there.", null, 0);
        else if (t < 35.8) newTab(g, x, y, w, h, H);
        else {
            fanfic(g, x, y, w, h, H);
            if (t >= 44.5) {                                        // and blocked again, sliding over it
                float k = (float) ease((t - 44.5) / 0.5);
                blocked(g, x, y - h * (1 - k), w, h, H, "Nice try. Reason: reasons.", "(Also: since when do you live in Antarctica?)", 1);
            }
        }
    }

    private void newTab(Graphics2D g, float x, float y, float w, float h, int H) {
        boolean secret = secret();
        g.setColor(secret ? new Color(0x1E1A2B) : Color.WHITE);
        g.fillRect((int) x, (int) y, (int) w, (int) h);
        g.setFont(MenuView.font(Font.BOLD, H * 0.07f));
        FontMetrics fm = g.getFontMetrics();
        String word = secret ? "Secret mode" : "WII-UU";
        float wx = x + (w - fm.stringWidth(word)) / 2;
        if (secret) {
            g.setColor(new Color(0xC9B8FF));
            g.drawString(word, wx, y + h * 0.45f);
            g.setFont(MenuView.font(Font.PLAIN, H * 0.026f));
            String sub = "Nobody can see what you do here. Probably.";
            g.drawString(sub, x + (w - g.getFontMetrics().stringWidth(sub)) / 2, y + h * 0.58f);
        } else {
            g.setColor(new Color(0x3C4043));
            g.drawString("WII-", wx, y + h * 0.45f);
            g.setColor(MenuView.ACCENT);
            g.drawString("UU", wx + fm.stringWidth("WII-"), y + h * 0.45f);
            g.setColor(new Color(0xDADCE0));
            g.draw(new RoundRectangle2D.Float(x + w * 0.2f, y + h * 0.55f, w * 0.6f, h * 0.1f, h * 0.1f, h * 0.1f));
        }
    }

    /** The internet provider's block page. */
    private void blocked(Graphics2D g, float x, float y, float w, float h, int H, String reason, String extra, int angry) {
        g.setColor(new Color(0xFFF8F0));
        g.fillRect((int) x, (int) y, (int) w, (int) h);
        g.setColor(new Color(0xFF8A00));
        g.fillRect((int) x, (int) y, (int) w, (int) (h * 0.12f));
        g.setColor(Color.WHITE);
        g.setFont(MenuView.font(Font.BOLD, H * 0.03f));
        g.drawString("@ SlowNet Broadband", x + w * 0.03f, y + h * 0.085f);
        // a no-entry sign
        float r = h * 0.12f, cx = x + w * 0.14f, cy = y + h * 0.4f;
        g.setColor(new Color(0xE53935));
        g.fill(new Ellipse2D.Float(cx - r, cy - r, r * 2, r * 2));
        g.setColor(Color.WHITE);
        g.fillRect((int) (cx - r * 0.65f), (int) (cy - r * 0.18f), (int) (r * 1.3f), (int) (r * 0.36f));
        float tx = x + w * 0.28f;
        g.setColor(new Color(0x202124));
        g.setFont(MenuView.font(Font.BOLD, H * (angry > 0 ? 0.05f : 0.045f)));
        g.drawString(angry > 0 ? "Nice try." : "This site is blocked", tx, y + h * 0.33f);
        g.setColor(new Color(0x5F6368));
        g.setFont(MenuView.font(Font.PLAIN, H * 0.026f));
        g.drawString("fanfic.com was blocked by your internet provider.", tx, y + h * 0.44f);
        g.setColor(new Color(0xD32F2F));
        g.setFont(MenuView.font(Font.BOLD, H * 0.034f));
        g.drawString(angry > 0 ? reason.substring(10) : reason, tx, y + h * 0.56f);
        if (extra != null) {
            g.setColor(new Color(0x5F6368));
            g.setFont(MenuView.font(Font.PLAIN, H * 0.024f));
            g.drawString(extra, tx, y + h * 0.66f);
        }
        g.setColor(new Color(0x9AA0A6));
        g.setFont(MenuView.font(Font.PLAIN, H * 0.02f));
        g.drawString("SlowNet: proudly slow since 1997.", x + w * 0.03f, y + h * 0.94f);
    }

    /** The VPN app, over the browser. */
    private void vpn(Graphics2D g, float x, float y, float w, float h, int H) {
        float vw = w * 0.56f, vh = h * 0.86f, vx = x + (w - vw) / 2, vy = y + (h - vh) / 2;
        g.setColor(new Color(0, 0, 0, 90));
        g.fillRect((int) x, (int) y, (int) w, (int) h);
        g.setColor(new Color(0x102027));
        g.fill(new RoundRectangle2D.Float(vx, vy, vw, vh, 10, 10));
        g.setColor(new Color(0x26A69A));
        g.setFont(MenuView.font(Font.BOLD, H * 0.03f));
        g.drawString("TotallySecure VPN", vx + vw * 0.06f, vy + vh * 0.13f);
        String[][] places = {{"USA", "blocked"}, {"Europe", "blocked"}, {"The Moon", "busy"}, {"Antarctica", "1 penguin online"}};
        g.setFont(MenuView.font(Font.PLAIN, H * 0.024f));
        FontMetrics fm = g.getFontMetrics();
        int pick = t < 29.5 ? -1 : t < 30.0 ? 0 : t < 30.4 ? 1 : t < 30.8 ? 2 : 3;
        for (int i = 0; i < places.length; i++) {
            float ry = vy + vh * (0.2f + i * 0.13f);
            if (i == pick) {
                g.setColor(new Color(0x1E3A40));
                g.fillRect((int) (vx + vw * 0.04f), (int) ry, (int) (vw * 0.92f), (int) (vh * 0.11f));
            }
            g.setColor(Color.WHITE);
            g.drawString(places[i][0], vx + vw * 0.08f, ry + vh * 0.075f);
            g.setColor(i == 3 ? new Color(0x80CBC4) : new Color(0x90A4AE));
            g.drawString(places[i][1], vx + vw * 0.92f - fm.stringWidth(places[i][1]), ry + vh * 0.075f);
        }
        String status;
        if (t < 30.8) status = "Choose a location";
        else if (t < 32) status = "Connecting to Antarctica...";
        else status = "Connected! You are now in Antarctica.";
        g.setColor(t >= 32 ? new Color(0x80CBC4) : Color.WHITE);
        g.setFont(MenuView.font(Font.BOLD, H * 0.024f));
        g.drawString(status, vx + vw * 0.06f, vy + vh * 0.82f);
        if (t >= 30.8) {
            g.setColor(new Color(0x37474F));
            g.fillRect((int) (vx + vw * 0.06f), (int) (vy + vh * 0.87f), (int) (vw * 0.88f), 4);
            g.setColor(new Color(0x26A69A));
            g.fillRect((int) (vx + vw * 0.06f), (int) (vy + vh * 0.87f), (int) (vw * 0.88f * clamp((t - 30.8) / 1.2)), 4);
        }
    }

    /** fanfic.com: tonight's top stories, then chapter 47. */
    private void fanfic(Graphics2D g, float x, float y, float w, float h, int H) {
        g.setColor(new Color(0x24152F));
        g.fillRect((int) x, (int) y, (int) w, (int) h);
        g.setColor(new Color(0xFF6FB5));
        g.setFont(MenuView.font(Font.BOLD, H * 0.045f));
        g.drawString("fanfic.com", x + w * 0.04f, y + h * 0.13f);
        g.setColor(new Color(0xB39DDB));
        g.setFont(MenuView.font(Font.PLAIN, H * 0.022f));
        g.drawString("stories your router doesn't want you to read", x + w * 0.04f, y + h * 0.2f);
        if (t < 40.5) {
            String[][] stories = {
                    {"The GamePad and Me: A Tale of Two Screens", "Chapter 47  -  NEW!"},
                    {"Pong Paddles in Love (They Never Touch)", "Chapter 12"},
                    {"The Tetris Block That Finally Fit In", "Complete"}};
            for (int i = 0; i < stories.length; i++) {
                float ry = y + h * (0.27f + i * 0.23f);
                boolean hot = i == 0 && t >= 37.6;
                g.setColor(hot ? new Color(0x4A2A5E) : new Color(0x35213F));
                g.fill(new RoundRectangle2D.Float(x + w * 0.04f, ry, w * 0.92f, h * 0.19f, 8, 8));
                g.setColor(Color.WHITE);
                g.setFont(MenuView.font(Font.BOLD, H * 0.027f));
                g.drawString(stories[i][0], x + w * 0.07f, ry + h * 0.08f);
                g.setColor(i == 0 ? new Color(0xFFD54F) : new Color(0xB39DDB));
                g.setFont(MenuView.font(Font.PLAIN, H * 0.022f));
                g.drawString("★★★★★   " + stories[i][1], x + w * 0.07f, ry + h * 0.15f);
            }
            return;
        }
        String[] text = {
                "The GamePad and Me  -  Chapter 47",
                "",
                "The GamePad's screen flickered softly in the dark.",
                "\"You're more than a controller to me,\" whispered the console.",
                "\"Don't,\" said the GamePad. \"My battery is already low.\"",
                "Their Wi-Fi signals met in the middle of the living room.",
                "Five bars. Full strength.",
                "\"GamePad... will you pair with me?\"",
                "The GamePad's screen went bright. \"I thought you'd never ask.\"",
        };
        float scroll = (float) (Math.max(0, t - 42) * h * 0.05f);
        float ly = y + h * 0.34f - scroll;
        java.awt.Shape clip = g.getClip();
        g.clipRect((int) x, (int) (y + h * 0.25f), (int) w, (int) (h * 0.75f));
        for (int i = 0; i < text.length; i++) {
            g.setColor(i == 0 ? new Color(0xFF6FB5) : new Color(0xEDE7F6));
            g.setFont(MenuView.font(i == 0 ? Font.BOLD : Font.PLAIN, H * (i == 0 ? 0.028f : 0.024f)));
            g.drawString(text[i], x + w * 0.05f, ly);
            ly += h * 0.085f;
        }
        g.setClip(clip);
    }

    // ---- the guy -------------------------------------------------------------------------------------

    private void guy(Graphics2D g, int w, int h) {
        float s = h * 0.06f, floor = h * 0.84f;
        boolean asleep = t >= OFF && t < 54.6;
        double jump = t >= HALL_ON && t < HALL_ON + 0.5 ? Math.sin(Math.PI * (t - HALL_ON) / 0.5) : 0;
        float cx = w * 0.22f, cy = (float) (h * 0.62f - jump * s * 1.2f);
        if (!asleep && t < OFF) cy += (float) Math.abs(Math.sin(t * 3)) * 0.8f;
        // the stool
        g.setColor(new Color(0x2B2118));
        g.fillRect((int) (cx - s * 0.6f), (int) (h * 0.62f + s * 0.6f), (int) (s * 1.2f), (int) (s * 0.25f));
        g.fillRect((int) (cx - s * 0.1f), (int) (h * 0.62f + s * 0.8f), (int) (s * 0.2f), (int) (floor - h * 0.62f - s * 0.8f));
        // lit blue by the screen, or warm by the hallway, or barely at all
        Color body = hallLight() ? new Color(0xEADFC4) : monitorOn() ? (secret() ? new Color(0xD9CCF2) : new Color(0xCBD8F0))
                : new Color(0x6A7590);
        float cw = s * 2.6f, ch = s;
        g.setColor(new Color(0, 0, 0, 80));
        g.fill(new RoundRectangle2D.Float(cx - cw / 2 + 2, cy - ch / 2 + 3, cw, ch, ch * 0.6f, ch * 0.6f));
        g.setColor(body);
        g.fill(new RoundRectangle2D.Float(cx - cw / 2, cy - ch / 2, cw, ch, ch * 0.6f, ch * 0.6f));
        g.setFont(MenuView.font(Font.BOLD, s * 0.55f));
        FontMetrics fm = g.getFontMetrics();
        float lx = cx - fm.stringWidth("WII-UU") / 2f, ly = cy + fm.getAscent() * 0.38f;
        g.setColor(new Color(0x2B2F36));
        g.drawString("WII-", lx, ly);
        g.setColor(MenuView.ACCENT.darker());
        g.drawString("UU", lx + fm.stringWidth("WII-"), ly);
        // googly eyes: on the screen, wide when the light comes on, shut while "asleep"
        boolean wide = t >= HALL_ON && t < OFF + 0.2 || t >= 45.6 && t < 47;
        float look = 1, up = 0;
        if (t >= 40.8 && t < 44.5) {                                // reading the chapter, line by line
            look = (float) (0.3 + ((t * 0.8) % 1) * 0.7);
            up = (float) (-0.2 + ((int) (t * 0.8) % 3) * 0.25);
        }
        if (t >= 54.6) {
            look = 1;
            up = -0.3f;
        }
        for (int e = -1; e <= 1; e += 2) {
            float ex = cx + e * s * 0.42f, ey = cy - ch / 2 - s * 0.12f, er = s * (wide ? 0.32f : 0.26f);
            g.setColor(asleep ? body : Color.WHITE);
            g.fill(new Ellipse2D.Float(ex - er, ey - er, er * 2, er * 2));
            g.setColor(new Color(0x2B2F36));
            g.setStroke(new BasicStroke(Math.max(1, s * 0.05f)));
            g.draw(new Ellipse2D.Float(ex - er, ey - er, er * 2, er * 2));
            if (asleep) g.drawLine((int) (ex - er * 0.7f), (int) ey, (int) (ex + er * 0.7f), (int) ey);
            else {
                float pr = wide ? er * 0.5f : er;
                g.fill(new Ellipse2D.Float(ex - pr * 0.5f + look * er * 0.45f, ey - pr * 0.5f + up * er * 0.4f, pr, pr));
            }
        }
        g.setStroke(new BasicStroke(1));
        // typing
        if (typing() != null) {
            g.setColor(new Color(0x2B2F36));
            float hx = (float) (Math.sin(t * 22) * s * 0.12f);
            g.fill(new Ellipse2D.Float(cx + cw / 2 + s * 0.05f + hx, cy + s * 0.25f, s * 0.3f, s * 0.3f));
        }
        // Zzz
        if (asleep && t > OFF + 0.4) {
            g.setFont(MenuView.font(Font.BOLD, s * 0.5f));
            for (int i = 0; i < 3; i++) {
                double p = ((t - OFF) * 0.5 + i / 3.0) % 1;
                g.setColor(new Color(200, 210, 255, (int) (220 * (1 - p))));
                g.drawString("z", cx + cw * 0.3f + (float) (p * s * 1.2f), cy - ch - (float) (p * s * 2));
            }
        }
    }
}
