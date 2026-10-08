package wiiuu.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.BiConsumer;

/**
 * An idle-time sketch, between WII-UU's games: it goes looking for free games on a very
 * trustworthy website (free-roms-totally-legit.biz, made up), clicks every wrong button, downloads
 * Super_Mario_64_FULL_GAME_100%_REAL.zip.exe, and gets a "virus": the screen glitches, pop-ups
 * multiply and a bug eats the page, until WII-UU swats it and pretends nothing happened.
 *
 * <p>All drawn (nothing is visited or downloaded), and worked out from the time since it started,
 * so each scene is easy to follow and to retime: see {@link #step}.
 */
final class IdleWeb implements IdleGames.Game {
    static final String URL = "free-roms-totally-legit.biz";
    private static final String SEARCH = "super mario 64";
    private static final String FILE = "Super_Mario_64_FULL_GAME_100%_REAL.zip.exe";
    private static final double END = 59;

    private final BiConsumer<String, String> say;
    private final long started = System.currentTimeMillis();
    private final Random random = new Random();
    private final List<double[]> popups = new ArrayList<>();       // appears, goes, x, y (fractions), kind
    private final List<String> spoken = new ArrayList<>();
    private double t;
    private boolean done;
    private long doneAt;

    private static final String[][] ADS = {
            {"1 WEIRD TRICK", "for INFINITE LIVES"}, {"YOUR PC IS SLOW!", "click to fix (free)"},
            {"HOT NEW EMULATORS", "in your area"}, {"DOWNLOAD MORE RAM", "100% works"}};
    private static final String[][] POPUPS = {
            {"CONGRATULATIONS!", "You are the 1,000,000th visitor!"}, {"WARNING!!", "Your PC has 99+ viruses"},
            {"Installing toolbars", "(37 of 214)"}, {"You won!", "A FREE cruise. Click OK."}, {"FREE ROMS", "Now with extra ROMs"},
            {"Your files are now", "BUGS. Thank you."}, {"ERROR", "Error: error"}, {"Are you sure?", "Too late."}};

    IdleWeb(BiConsumer<String, String> say) {
        this.say = say;
        // the pop-ups: two it closes early on, then a flood when the "virus" hits
        popups.add(new double[]{18.6, 20.4, 0.42, 0.34, 0});
        popups.add(new double[]{20.9, 22.4, 0.30, 0.46, 3});
        double at = 32;
        for (int i = 0; i < 11; i++) {
            at += 0.45 + random.nextDouble() * 0.35;
            popups.add(new double[]{at, 50.3 + i * 0.2, 0.06 + random.nextDouble() * 0.66, 0.16 + random.nextDouble() * 0.6,
                    random.nextInt(POPUPS.length)});
        }
    }

    // ---- the script --------------------------------------------------------------------------------

    /** Remarks, at their moments: time, words, mood. */
    private static final Object[][] SCRIPT = {
            {0.6, "Hmm, where do people get games these days...", "focus"},
            {6.8, "Wow. This site looks very trustworthy.", "smug"},
            {10.2, "One weird trick for infinite lives? Interesting...", "sneaky"},
            {13.0, "Okay, searching for Mario.", "focus"},
            {18.9, "Close. Close! CLOSE!", "nervous"},
            {21.3, "Another one?!", "nervous"},
            {24.0, "3 KB/s. Great.", "sad"},
            {27.8, "Wait, why does it end in .exe?", "nervous"},
            {30.2, "Eh. Probably fine.", "smug"},
            {32.0, "Oh no.", "shocked"},
            {34.0, "Oh no no no no no.", "shocked"},
            {36.4, "I didn't click anything! (I clicked everything.)", "nervous"},
            {38.8, "CTRL+ALT+DELETE! CTRL+ALT+DELETE!", "shocked"},
            {41.0, "Okay. I'm handling it.", "focus"},
            {43.3, "Hold still!", "nervous"},
            {46.3, "Come HERE!", "nervous"},
            {48.8, "GOT IT!", "happy"},
            {52.0, "That never happened.", "smug"},
            {55.5, "Note to self: buy your games.", "sneaky"},
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

    // ---- where things are (fractions of the picture) -----------------------------------------------

    private static final float WX = 0.04f, WY = 0.11f, WW = 0.92f, WH = 0.86f;   // the browser window

    private static double ease(double a) {
        a = Math.max(0, Math.min(1, a));
        return a < 0.5 ? 2 * a * a : 1 - Math.pow(-2 * a + 2, 2) / 2;
    }

    /** The mouse pointer's way through the sketch: time, x, y (fractions), and whether it clicks there. */
    private static final double[][] MOUSE = {
            {0, 0.5, 0.6, 0}, {6.5, 0.5, 0.6, 0}, {8.2, 0.2, 0.42, 0}, {9.6, 0.2, 0.58, 0}, {11.0, 0.22, 0.43, 0},
            {12.6, 0.5, 0.42, 1}, {16.2, 0.5, 0.42, 0}, {17.8, 0.74, 0.55, 1}, {20.2, 0.66, 0.38, 1}, {22.2, 0.55, 0.5, 1},
            {23.2, 0.86, 0.67, 1}, {29.5, 0.86, 0.67, 0}, {30.6, 0.2, 0.89, 1}, {33.0, 0.5, 0.5, 0}, {40.0, 0.5, 0.5, 0}};

    private double[] mouse() {
        double[] a = MOUSE[0], b = MOUSE[MOUSE.length - 1];
        for (int i = 0; i + 1 < MOUSE.length; i++) {
            if (t >= MOUSE[i][0] && t < MOUSE[i + 1][0]) {
                a = MOUSE[i];
                b = MOUSE[i + 1];
                break;
            }
        }
        double span = b[0] - a[0], k = span <= 0 ? 1 : ease(Math.min(1, (t - a[0]) / Math.min(span, 1.1)));
        double x = a[1] + (b[1] - a[1]) * k, y = a[2] + (b[2] - a[2]) * k;
        if (t > 32 && t < 40) {                                     // the virus has the mouse now
            x += Math.sin(t * 9) * 0.06;
            y += Math.cos(t * 7) * 0.05;
        }
        boolean clicking = false;
        for (double[] m : MOUSE) if (m[3] == 1 && t > m[0] - 0.05 && t < m[0] + 0.25) clicking = true;
        return new double[]{x, y, clicking ? 1 : 0};
    }

    /** The bug, while it's loose: x, y (fractions). */
    private double[] bug() {
        double u = t - 32;
        double x = 0.5 + Math.sin(u * 1.3) * 0.32 + Math.sin(u * 3.1) * 0.05, y = 0.55 + Math.sin(u * 1.9 + 1) * 0.22;
        if (t >= 48.8) {                                            // splat: it stays where it was hit
            u = 48.8 - 32;
            x = 0.5 + Math.sin(u * 1.3) * 0.32 + Math.sin(u * 3.1) * 0.05;
            y = 0.55 + Math.sin(u * 1.9 + 1) * 0.22;
        }
        return new double[]{x, y};
    }

    // ---- drawing -----------------------------------------------------------------------------------

    @Override
    public void paint(Graphics2D g, int w, int h) {
        AffineTransform base = g.getTransform();
        boolean virus = t >= 31 && t < 50;
        if (t >= 31 && t < 33.5) g.translate((random.nextDouble() - 0.5) * w * 0.03, (random.nextDouble() - 0.5) * h * 0.03);
        // the desktop behind
        g.setPaint(new GradientPaint(0, 0, new Color(0x0F6E7A), 0, h, new Color(0x063B4A)));
        g.fillRect(0, 0, w, h);
        double fade = t > END - 3 ? Math.max(0, (END - t) / 3) : 1;
        if (fade > 0.01) {
            java.awt.Composite c = g.getComposite();
            g.setComposite(java.awt.AlphaComposite.SrcOver.derive((float) fade));
            window(g, w, h, virus);
            g.setComposite(c);
        }
        paintPopups(g, w, h);
        if (t >= 32 && t < 52) paintBug(g, w, h);
        if (t >= 40 && t < 52) paintHero(g, w, h);
        if (t < 33 || t >= 50 && t < END - 3) paintMouse(g, w, h);
        if (t >= 31 && t < 34 && ((int) (t * 12)) % 3 == 0) glitch(g, w, h);
        if (t >= 50 && t < 52.5) banner(g, w, h, "ANTIVIRUS 3000: 0 threats found (now)");
        g.setTransform(base);
    }

    private void window(Graphics2D g, int w, int h, boolean virus) {
        float x = WX * w, y = WY * h, ww = WW * w, wh = WH * h;
        float bar = h * 0.065f;
        g.setColor(new Color(0, 0, 0, 90));
        g.fillRoundRect((int) x + 4, (int) y + 6, (int) ww, (int) wh, 14, 14);
        g.setColor(new Color(0xDADDE1));
        g.fill(new RoundRectangle2D.Float(x, y, ww, wh, 14, 14));
        // the tab and the address bar
        g.setColor(new Color(0xBFC4CA));
        g.fill(new RoundRectangle2D.Float(x, y, ww, bar, 14, 14));
        g.fillRect((int) x, (int) (y + bar / 2), (int) ww, (int) (bar / 2));
        g.setColor(new Color(0xEEF0F2));
        g.fill(new RoundRectangle2D.Float(x + ww * 0.02f, y + bar * 0.18f, ww * 0.3f, bar * 0.82f + 2, 10, 10));
        g.setFont(MenuView.font(Font.PLAIN, bar * 0.4f));
        g.setColor(new Color(0x3C4043));
        g.drawString(t < 6 ? "New tab" : "FREE ROMS!!! 100% LEGIT NO VI...", x + ww * 0.035f, y + bar * 0.72f);
        for (int i = 0; i < 3; i++) {
            g.setColor(new Color[]{new Color(0xFF5F57), new Color(0xFEBC2E), new Color(0x28C840)}[i]);
            g.fill(new Ellipse2D.Float(x + ww - bar * (1.1f + i * 0.6f), y + bar * 0.32f, bar * 0.36f, bar * 0.36f));
        }
        float ay = y + bar;
        g.setColor(new Color(0xEEF0F2));
        g.fillRect((int) x, (int) ay, (int) ww, (int) bar);
        g.setColor(Color.WHITE);
        g.fill(new RoundRectangle2D.Float(x + ww * 0.08f, ay + bar * 0.15f, ww * 0.84f, bar * 0.7f, bar * 0.7f, bar * 0.7f));
        g.setFont(MenuView.font(Font.BOLD, bar * 0.38f));
        g.setColor(new Color(0xD93025));
        g.drawString("⚠ Not secure", x + ww * 0.1f, ay + bar * 0.62f);
        int typed = (int) Math.max(0, Math.min(URL.length(), (t - 1.5) * 7));
        g.setFont(MenuView.font(Font.PLAIN, bar * 0.4f));
        g.setColor(new Color(0x202124));
        float ux = x + ww * 0.1f + g.getFontMetrics(MenuView.font(Font.BOLD, bar * 0.38f)).stringWidth("⚠ Not secure  ");
        String shown = URL.substring(0, typed);
        g.drawString(shown, ux, ay + bar * 0.63f);
        if (t < 5.5 && ((int) (t * 3)) % 2 == 0) g.fillRect((int) (ux + g.getFontMetrics().stringWidth(shown)) + 1, (int) (ay + bar * 0.3f), 1, (int) (bar * 0.42f));
        // loading
        float py = ay + bar, ph = wh - bar * 2 - (t >= 23 ? bar * 1.5f : 0);
        if (t >= 5 && t < 6.2) {
            g.setColor(new Color(0x1A73E8));
            g.fillRect((int) x, (int) py, (int) (ww * ease((t - 5) / 1.2)), 3);
        }
        if (t >= 6) page(g, x, py, ww, ph, h, virus);
        if (t >= 23) downloadBar(g, x, py + ph, ww, bar * 1.5f, h);
    }

    private void page(Graphics2D g, float x, float y, float w, float h, int H, boolean virus) {
        java.awt.Shape clip = g.getClip();
        g.clipRect((int) x, (int) y, (int) w, (int) h);
        // garish stripes
        float off = (float) (t * 40 % 40);
        for (float sx = x - 80 + off; sx < x + w + 80; sx += 40) {
            g.setColor(((int) ((sx - off) / 40)) % 2 == 0 ? new Color(0xFFF176) : new Color(0xFF80AB));
            Path2D s = new Path2D.Float();
            s.moveTo(sx, y);
            s.lineTo(sx + 20, y);
            s.lineTo(sx + 20 - h * 0.6f, y + h);
            s.lineTo(sx - h * 0.6f, y + h);
            s.closePath();
            g.fill(s);
        }
        // the title, in every colour
        String title = "FREE ROMS!!!";
        g.setFont(MenuView.font(Font.BOLD, H * 0.085f));
        FontMetrics fm = g.getFontMetrics();
        float tx = x + (w - fm.stringWidth(title)) / 2, ty = y + H * 0.1f;
        for (int i = 0; i < title.length(); i++) {
            String ch = title.substring(i, i + 1);
            g.setColor(Color.getHSBColor((float) ((t * 0.6 + i * 0.08) % 1), 0.9f, 0.85f));
            g.drawString(ch, tx, ty + (float) Math.sin(t * 6 + i) * H * 0.006f);
            tx += fm.stringWidth(ch);
        }
        g.setFont(MenuView.font(Font.BOLD, H * 0.032f));
        String sub = "100% LEGIT  ★  NO VIRUS  ★  TRUST ME";
        g.setColor(new Color(0x880E4F));
        g.drawString(sub, x + (w - g.getFontMetrics().stringWidth(sub)) / 2, ty + H * 0.045f);
        // the ads down the left
        for (int i = 0; i < ADS.length; i++) {
            float ax = x + w * 0.03f, ay = y + h * (0.27f + i * 0.18f), aw = w * 0.27f, ah = h * 0.15f;
            boolean blink = ((int) (t * 3 + i)) % 2 == 0;
            g.setColor(virus ? new Color(0x2E7D32) : blink ? new Color(0xFF1744) : new Color(0x2979FF));
            g.fill(new RoundRectangle2D.Float(ax, ay, aw, ah, 8, 8));
            g.setColor(Color.WHITE);
            g.setFont(MenuView.font(Font.BOLD, H * 0.03f));
            g.drawString(virus ? "BUG BUG BUG" : ADS[i][0], ax + 6, ay + ah * 0.45f);
            g.setFont(MenuView.font(Font.PLAIN, H * 0.024f));
            g.drawString(virus ? "bug" : ADS[i][1], ax + 6, ay + ah * 0.8f);
        }
        // the search, and then the "results"
        float cx = x + w * 0.34f, cw = w * 0.62f, sy = y + h * 0.27f;
        g.setColor(Color.WHITE);
        g.fill(new RoundRectangle2D.Float(cx, sy, cw, h * 0.1f, 10, 10));
        g.setColor(new Color(0x9E9E9E));
        g.draw(new RoundRectangle2D.Float(cx, sy, cw, h * 0.1f, 10, 10));
        int typed = (int) Math.max(0, Math.min(SEARCH.length(), (t - 13.2) * 6));
        g.setFont(MenuView.font(Font.PLAIN, H * 0.032f));
        g.setColor(typed == 0 ? new Color(0x9E9E9E) : new Color(0x202124));
        g.drawString(typed == 0 ? "Search 9,999,999 FREE games..." : SEARCH.substring(0, typed), cx + 8, sy + h * 0.068f);
        if (t >= 16.2) {
            String[] results = {"Super Mario 64 (USA) [!] FULL GAME", "Super Mario 64 REAL (not fake)", "Super Mario 64 + FREE RAM"};
            for (int i = 0; i < results.length; i++) {
                float ry = sy + h * (0.15f + i * 0.17f), rh = h * 0.14f;
                g.setColor(new Color(255, 255, 255, 220));
                g.fill(new RoundRectangle2D.Float(cx, ry, cw, rh, 8, 8));
                g.setColor(virus && i == 1 ? new Color(0x2E7D32) : new Color(0x1A0DAB));
                g.setFont(MenuView.font(Font.BOLD, H * 0.03f));
                g.drawString(virus && i == 1 ? "bug bug bug bug" : results[i], cx + 8, ry + rh * 0.42f);
                g.setFont(MenuView.font(Font.PLAIN, H * 0.022f));
                g.setColor(new Color(0x5F6368));
                g.drawString("8.2 MB  ★★★★★ (3 reviews, all by the site)", cx + 8, ry + rh * 0.78f);
                // the big green button that isn't the download
                boolean blink = ((int) (t * 4 + i)) % 2 == 0;
                float bw = cw * 0.22f, bh = rh * 0.62f, bx = cx + cw - bw - 8, by = ry + (rh - bh) / 2;
                g.setColor(blink ? new Color(0x00C853) : new Color(0x64DD17));
                g.fill(new RoundRectangle2D.Float(bx, by, bw, bh, 8, 8));
                g.setColor(Color.WHITE);
                g.setFont(MenuView.font(Font.BOLD, H * 0.028f));
                g.drawString("DOWNLOAD", bx + (bw - g.getFontMetrics().stringWidth("DOWNLOAD")) / 2, by + bh * 0.68f);
            }
            // the real (tiny) download link
            g.setFont(MenuView.font(Font.PLAIN, H * 0.022f));
            g.setColor(new Color(0x1A0DAB));
            g.drawString("real download (probably)", cx + cw * 0.7f, sy + h * 0.68f);
        }
        if (virus) {
            // the bug has been eating: holes in the page
            Random r = new Random(7);
            g.setColor(new Color(0x063B4A));
            int holes = (int) Math.min(26, (t - 32) * 3);
            for (int i = 0; i < holes; i++) {
                float hx = x + r.nextFloat() * w, hy = y + r.nextFloat() * h, hs = H * (0.02f + r.nextFloat() * 0.04f);
                g.fill(new Ellipse2D.Float(hx, hy, hs, hs * 0.8f));
            }
        }
        g.setClip(clip);
    }

    private void downloadBar(Graphics2D g, float x, float y, float w, float h, int H) {
        g.setColor(new Color(0xF1F3F4));
        g.fillRect((int) x, (int) y, (int) w, (int) h);
        g.setColor(new Color(0xDADCE0));
        g.fillRect((int) x, (int) y, (int) w, 1);
        float ix = x + w * 0.02f, iy = y + h * 0.18f, iw = w * 0.36f, ih = h * 0.64f;
        g.setColor(Color.WHITE);
        g.fill(new RoundRectangle2D.Float(ix, iy, iw, ih, 8, 8));
        // progress: crawling, then all at once
        double p = t < 23.2 ? 0 : t < 28.5 ? (t - 23.2) / 5.3 * 0.12 : Math.min(1, 0.12 + (t - 28.5) * 1.6);
        if (p < 1) {
            g.setColor(new Color(0x1A73E8));
            g.fillRect((int) ix, (int) (iy + ih - 3), (int) (iw * p), 3);
        }
        g.setFont(MenuView.font(Font.BOLD, H * 0.024f));
        g.setColor(new Color(0x202124));
        String name = FILE;
        while (g.getFontMetrics().stringWidth(name) > iw - 12 && name.length() > 8) name = name.substring(0, name.length() - 4) + "...";
        if (name.length() < FILE.length()) name = "Super_Mario_64_FULL...zip.exe";
        g.drawString(name, ix + 6, iy + ih * 0.45f);
        g.setFont(MenuView.font(Font.PLAIN, H * 0.021f));
        g.setColor(new Color(0x5F6368));
        g.drawString(p >= 1 ? (t < 31 ? "Done. Open file?" : "Opened") : t < 28.5 ? "3 KB/s - 2 hours left" : "999 MB/s - huh?", ix + 6, iy + ih * 0.85f);
    }

    private void paintPopups(Graphics2D g, int w, int h) {
        for (double[] p : popups) {
            if (t < p[0] || t >= p[1]) continue;
            String[] text = POPUPS[(int) p[4]];
            float pw = w * 0.25f, ph = h * 0.15f, px = (float) (p[2] * w), py = (float) (p[3] * h);
            double pop = Math.min(1, (t - p[0]) / 0.15);
            AffineTransform at = g.getTransform();
            g.translate(px + pw / 2, py + ph / 2);
            g.scale(pop, pop);
            g.translate(-pw / 2, -ph / 2);
            g.setColor(new Color(0, 0, 0, 80));
            g.fillRect(3, 4, (int) pw, (int) ph);
            g.setColor(new Color(0xFAFAFA));
            g.fillRect(0, 0, (int) pw, (int) ph);
            g.setColor(new Color(0x1565C0));
            g.fillRect(0, 0, (int) pw, (int) (ph * 0.24f));
            g.setColor(Color.WHITE);
            g.setFont(MenuView.font(Font.BOLD, ph * 0.15f));
            g.drawString("Message", 5, ph * 0.18f);
            g.setColor(new Color(0xE53935));
            g.fillRect((int) (pw - ph * 0.24f), 0, (int) (ph * 0.24f), (int) (ph * 0.24f));
            g.setColor(Color.WHITE);
            g.drawString("x", pw - ph * 0.17f, ph * 0.18f);
            g.setColor(new Color(0x202124));
            g.setFont(MenuView.font(Font.BOLD, ph * 0.17f));
            g.drawString(text[0], 8, ph * 0.52f);
            g.setFont(MenuView.font(Font.PLAIN, ph * 0.14f));
            g.drawString(text[1], 8, ph * 0.75f);
            g.setColor(new Color(0xE0E0E0));
            g.fillRect((int) (pw * 0.68f), (int) (ph * 0.8f), (int) (pw * 0.26f), (int) (ph * 0.15f));
            g.setColor(new Color(0x202124));
            g.drawString("OK", pw * 0.77f, ph * 0.92f);
            g.setTransform(at);
        }
    }

    /** VIRUS.EXE: a green bug with angry eyes and wiggling legs (and a splat once swatted). */
    private void paintBug(Graphics2D g, int w, int h) {
        double[] b = bug();
        float bx = (float) (b[0] * w), by = (float) (b[1] * h), s = h * 0.07f;
        if (t >= 48.8) {
            g.setColor(new Color(0x43A047));
            Random r = new Random(3);
            Path2D splat = new Path2D.Float();
            for (int i = 0; i < 14; i++) {
                double a = i * Math.PI * 2 / 14, rr = s * (i % 2 == 0 ? 1.5 : 0.8) * (0.8 + r.nextDouble() * 0.5);
                if (i == 0) splat.moveTo(bx + Math.cos(a) * rr, by + Math.sin(a) * rr);
                else splat.lineTo(bx + Math.cos(a) * rr, by + Math.sin(a) * rr);
            }
            splat.closePath();
            g.fill(splat);
            if (t < 50.5) {
                g.setFont(MenuView.font(Font.BOLD, h * 0.08f));
                g.setColor(new Color(0x1B5E20));
                g.drawString("SPLAT!", bx - s + 2, by - s * 1.6f + 3);
                g.setColor(new Color(0xFFEB3B));
                g.drawString("SPLAT!", bx - s, by - s * 1.6f);
            }
            return;
        }
        double prev = (t - 32 - 0.05);
        double dx = Math.cos(prev * 1.3) * 1.3;                     // which way it's heading
        AffineTransform at = g.getTransform();
        g.translate(bx, by);
        if (dx < 0) g.scale(-1, 1);
        g.setColor(new Color(0x1B5E20));
        g.setStroke(new BasicStroke(Math.max(1.5f, s * 0.08f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int i = -1; i <= 1; i++) {
            float wig = (float) Math.sin(t * 22 + i) * s * 0.18f;
            g.drawLine((int) (i * s * 0.35f), 0, (int) (i * s * 0.45f + wig), (int) (s * 0.75f));
            g.drawLine((int) (i * s * 0.35f), 0, (int) (i * s * 0.45f - wig), (int) (-s * 0.75f));
        }
        g.setColor(new Color(0x43A047));
        g.fill(new Ellipse2D.Float(-s, -s * 0.55f, s * 2, s * 1.1f));
        g.setColor(new Color(0x66BB6A));
        g.fill(new Ellipse2D.Float(s * 0.55f, -s * 0.45f, s * 0.8f, s * 0.9f));
        for (int e = 0; e < 2; e++) {
            float ex = s * 0.85f + e * s * 0.25f, ey = -s * 0.12f;
            g.setColor(Color.WHITE);
            g.fill(new Ellipse2D.Float(ex - s * 0.12f, ey - s * 0.12f, s * 0.24f, s * 0.24f));
            g.setColor(Color.BLACK);
            g.fill(new Ellipse2D.Float(ex - s * 0.05f, ey - s * 0.05f, s * 0.1f, s * 0.1f));
            g.drawLine((int) (ex - s * 0.14f), (int) (ey - s * 0.2f - (e == 0 ? 0 : -s * 0.06f)), (int) (ex + s * 0.12f), (int) (ey - s * 0.14f - (e == 0 ? -s * 0.06f : 0)));
        }
        g.setTransform(at);
        g.setFont(MenuView.font(Font.BOLD, h * 0.03f));
        g.setColor(Color.WHITE);
        g.drawString("VIRUS.EXE", bx - s * 0.9f, by - s * 0.8f);
    }

    /** WII-UU, in person, with a fly swatter, chasing the bug. */
    private void paintHero(Graphics2D g, int w, int h) {
        double[] b = bug();
        double in = ease((t - 40) / 1.0), lag = 0.7;
        double u = Math.max(32, t - lag) - 32;
        double tx = 0.5 + Math.sin(u * 1.3) * 0.32 + Math.sin(u * 3.1) * 0.05 - 0.12, ty = 0.55 + Math.sin(u * 1.9 + 1) * 0.22 + 0.04;
        // the swats: it lunges at the bug (twice too late)
        double[] swats = {43.3, 46.3, 48.8};
        double lunge = 0;
        for (double s : swats) if (t > s - 0.35 && t < s + 0.3) lunge = 1 - Math.abs(t - s) / 0.35;
        if (t >= 48.8) {
            tx = b[0] - 0.1;
            ty = b[1] + 0.02;
        }
        tx += (b[0] - 0.1 - tx) * lunge;
        ty += (b[1] - ty) * lunge;
        double x = 0.5 + (tx - 0.5) * in, y = 1.25 + (ty - 1.25) * in;
        if (t > 50) {
            double away = ease((t - 50) / 1.5);
            y += (1.3 - y) * away;
        }
        float s = h * 0.075f, cx = (float) (x * w), cy = (float) (y * h);
        // the swatter, raised, then down on a swat
        double swing = lunge > 0.6 ? 1 : 0;
        AffineTransform at = g.getTransform();
        g.translate(cx + s * 1.2f, cy - s * 0.2f);
        g.rotate(swing > 0 ? 0.9 : -0.5);
        g.setColor(new Color(0x8D6E63));
        g.setStroke(new BasicStroke(Math.max(2, s * 0.12f)));
        g.drawLine(0, 0, (int) (s * 1.4f), 0);
        g.setColor(new Color(0xE53935));
        g.fill(new RoundRectangle2D.Float(s * 1.3f, -s * 0.45f, s * 0.9f, s * 0.9f, s * 0.2f, s * 0.2f));
        g.setColor(new Color(255, 255, 255, 90));
        for (int i = 1; i < 4; i++) {
            g.drawLine((int) (s * 1.3f + i * s * 0.22f), (int) (-s * 0.45f), (int) (s * 1.3f + i * s * 0.22f), (int) (s * 0.45f));
            g.drawLine((int) (s * 1.3f), (int) (-s * 0.45f + i * s * 0.22f), (int) (s * 2.2f), (int) (-s * 0.45f + i * s * 0.22f));
        }
        g.setTransform(at);
        // the card with googly eyes
        float cw = s * 2.7f, ch = s;
        g.setColor(Color.WHITE);
        g.fill(new RoundRectangle2D.Float(cx - cw / 2, cy - ch / 2, cw, ch, ch * 0.6f, ch * 0.6f));
        g.setFont(MenuView.font(Font.BOLD, s * 0.55f));
        FontMetrics fm = g.getFontMetrics();
        float lx = cx - fm.stringWidth("WII-UU") / 2f, ly = cy + fm.getAscent() * 0.38f;
        g.setColor(new Color(0x2B2F36));
        g.drawString("WII-", lx, ly);
        g.setColor(MenuView.ACCENT);
        g.drawString("UU", lx + fm.stringWidth("WII-"), ly);
        float look = (float) Math.signum(b[0] * w - cx);
        for (int e = -1; e <= 1; e += 2) {
            float ex = cx + e * s * 0.42f, ey = cy - ch / 2 - s * 0.12f, er = s * 0.26f;
            g.setColor(Color.WHITE);
            g.fill(new Ellipse2D.Float(ex - er, ey - er, er * 2, er * 2));
            g.setColor(new Color(0x2B2F36));
            g.setStroke(new BasicStroke(Math.max(1, s * 0.05f)));
            g.draw(new Ellipse2D.Float(ex - er, ey - er, er * 2, er * 2));
            g.fill(new Ellipse2D.Float(ex - er * 0.5f + look * er * 0.4f, ey - er * 0.5f, er, er));
        }
    }

    private void paintMouse(Graphics2D g, int w, int h) {
        double[] m = mouse();
        float x = (float) (m[0] * w), y = (float) (m[1] * h), s = h * 0.045f;
        if (m[2] > 0) {
            g.setColor(new Color(255, 255, 255, 120));
            g.setStroke(new BasicStroke(2));
            g.draw(new Ellipse2D.Float(x - s * 0.6f, y - s * 0.6f, s * 1.2f, s * 1.2f));
        }
        Path2D arrow = new Path2D.Float();
        arrow.moveTo(x, y);
        arrow.lineTo(x, y + s);
        arrow.lineTo(x + s * 0.28f, y + s * 0.75f);
        arrow.lineTo(x + s * 0.48f, y + s * 1.15f);
        arrow.lineTo(x + s * 0.62f, y + s * 1.08f);
        arrow.lineTo(x + s * 0.42f, y + s * 0.68f);
        arrow.lineTo(x + s * 0.75f, y + s * 0.68f);
        arrow.closePath();
        g.setColor(Color.WHITE);
        g.fill(arrow);
        g.setColor(Color.BLACK);
        g.setStroke(new BasicStroke(Math.max(1, s * 0.06f)));
        g.draw(arrow);
    }

    /** Glitching: shifted strips and colour bars. */
    private void glitch(Graphics2D g, int w, int h) {
        for (int i = 0; i < 9; i++) {
            float y = random.nextFloat() * h, sh = h * (0.01f + random.nextFloat() * 0.05f);
            g.setColor(Color.getHSBColor(random.nextFloat(), 0.9f, 1f));
            g.fillRect((int) (random.nextFloat() * w * 0.3f), (int) y, (int) (w * (0.3f + random.nextFloat() * 0.7f)), (int) sh);
        }
        g.setColor(new Color(0, 0, 0, 120));
        g.fillRect(0, 0, w, h);
    }

    private void banner(Graphics2D g, int w, int h, String text) {
        g.setFont(MenuView.font(Font.BOLD, h * 0.045f));
        FontMetrics fm = g.getFontMetrics();
        float bw = fm.stringWidth(text) + h * 0.06f, bh = h * 0.09f, bx = (w - bw) / 2, by = h * 0.42f;
        g.setColor(new Color(0x2E7D32));
        g.fill(new RoundRectangle2D.Float(bx, by, bw, bh, bh * 0.4f, bh * 0.4f));
        g.setColor(Color.WHITE);
        g.drawString(text, bx + h * 0.03f, by + bh * 0.66f);
    }
}
