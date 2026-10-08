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
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * An idle-time sketch that does real work: WII-UU sits at a computer, scrolls through pictures on
 * the web, prints the covers of your games, sorts the printouts and pins them up on the WII-UU menu
 * on the wall. The covers come from a {@link Source} (Main's: the scraper finds ones your games
 * are missing, so the real menu has them afterwards; else covers you have, else drawn ones).
 *
 * <p>Worked out from the time since it started, like {@link IdleWeb}: see {@link #SCRIPT}.
 */
public final class IdleArt implements IdleGames.Game {

    /** One printout: the game, its console's colour, and its picture (null: drawn instead). */
    public record Art(String title, String console, Color color, BufferedImage image) {}

    /** Where the pictures come from; asked once per sketch, on a background thread (it may download). */
    public interface Source {
        List<Art> pictures(int count);
    }

    private static volatile Source source = n -> List.of();

    /** Main's source of covers. */
    public static void setSource(Source s) {
        source = s;
    }

    private static final int COUNT = 6;
    private static final double END = 60;
    private final BiConsumer<String, String> say;
    private final long started = System.currentTimeMillis();
    private final List<String> spoken = new ArrayList<>();
    private volatile List<Art> arts;
    private List<Art> sorted;
    private double t;
    private boolean done;
    private long doneAt;

    IdleArt(BiConsumer<String, String> say) {
        this.say = say;
        Thread fetch = new Thread(() -> {
            List<Art> got;
            try {
                got = new ArrayList<>(source.pictures(COUNT));
            } catch (RuntimeException e) {
                got = new ArrayList<>();
            }
            arts = got;
        }, "idle-art");
        fetch.setDaemon(true);
        fetch.start();
    }

    private static final Object[][] SCRIPT = {
            {0.6, "Time to make the menu prettier.", "focus"},
            {4.5, "Box art... box art... ooh, that one.", "happy"},
            {9.0, "Nope, that's fan art.", "smug"},
            {13.0, "Found them!", "happy"},
            {18.5, "Printing... printing...", "focus"},
            {23.5, "Paper jam? No. Okay. Phew.", "nervous"},
            {29.0, "Now sort them. Alphabetically. Obviously.", "focus"},
            {35.0, "Very professional.", "smug"},
            {39.5, "And up they go.", "happy"},
            {45.5, "A bit to the left... perfect.", "focus"},
            {54.0, "There. Much better.", "smug"},
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

    private static double ease(double a) {
        a = Math.max(0, Math.min(1, a));
        return a < 0.5 ? 2 * a * a : 1 - Math.pow(-2 * a + 2, 2) / 2;
    }

    /** The printouts: what the source found, filled up with drawn ones (until it has answered: all drawn). */
    private List<Art> art() {
        List<Art> a = arts;
        if (sorted != null) return sorted;
        List<Art> out = new ArrayList<>(a == null ? List.of() : a);
        String[][] fill = {{"Your favourite game", "WII-UU"}, {"That one game", "WII-UU"}, {"A classic", "WII-UU"},
                {"Mystery game", "WII-UU"}, {"Some sequel", "WII-UU"}, {"Untitled", "WII-UU"}};
        Color[] colors = {new Color(0xE53935), new Color(0x1E88E5), new Color(0x43A047), new Color(0xFB8C00), new Color(0x8E24AA), new Color(0x00897B)};
        for (int i = 0; out.size() < COUNT; i++) out.add(new Art(fill[i][0], fill[i][1], colors[i], null));
        if (t >= 17) {
            // from printing on, the set is fixed (and, from sorting on, in order)
            sorted = out;
        }
        return out;
    }

    private List<Art> inOrder() {
        List<Art> s = new ArrayList<>(art());
        s.sort(Comparator.comparing(a -> a.title().toLowerCase()));
        return s;
    }

    // ---- the room ------------------------------------------------------------------------------------

    @Override
    public void paint(Graphics2D g, int w, int h) {
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        double fade = t > END - 2.5 ? Math.max(0, (END - t) / 2.5) : 1;
        java.awt.Composite c = g.getComposite();
        g.setComposite(java.awt.AlphaComposite.SrcOver.derive((float) fade));
        // wall and floor
        g.setPaint(new GradientPaint(0, 0, new Color(0xF3EFE6), 0, h * 0.8f, new Color(0xE2DCCF)));
        g.fillRect(0, 0, w, h);
        g.setColor(new Color(0xB08D63));
        g.fillRect(0, (int) (h * 0.82f), w, h);
        g.setColor(new Color(0x9C7A52));
        for (int i = 0; i < 12; i++) g.fillRect(i * w / 11, (int) (h * 0.82f), 2, h);
        board(g, w, h);
        desk(g, w, h);
        sheets(g, w, h);
        guy(g, w, h);
        g.setComposite(c);
    }

    // where things are
    private static final float BX = 0.53f, BY = 0.14f, BW = 0.42f, BH = 0.46f;     // the menu on the wall

    /** A slot on the wall menu (3 x 2): its top-left corner and size, in pixels. */
    private float[] slot(int i, int w, int h) {
        float sw = BW * w * 0.27f, sh = BH * h * 0.36f;
        float x = BX * w + BW * w * (0.06f + (i % 3) * 0.315f), y = BY * h + BH * h * (0.2f + (i / 3) * 0.41f);
        return new float[]{x, y, sw, sh};
    }

    private void board(Graphics2D g, int w, int h) {
        float x = BX * w, y = BY * h, bw = BW * w, bh = BH * h;
        g.setColor(new Color(0x6D4C41));
        g.fill(new RoundRectangle2D.Float(x - 6, y - 6, bw + 12, bh + 12, 12, 12));
        g.setPaint(new GradientPaint(0, y, new Color(0xF7F8FA), 0, y + bh, new Color(0xE2E6EA)));
        g.fill(new RoundRectangle2D.Float(x, y, bw, bh, 8, 8));
        g.setFont(MenuView.font(Font.BOLD, h * 0.04f));
        g.setColor(new Color(0x3C4043));
        g.drawString("WII-", x + bw * 0.04f, y + bh * 0.13f);
        g.setColor(MenuView.ACCENT);
        g.drawString("UU", x + bw * 0.04f + g.getFontMetrics().stringWidth("WII-"), y + bh * 0.13f);
        List<Art> order = inOrder();
        for (int i = 0; i < COUNT; i++) {
            float[] s = slot(i, w, h);
            g.setColor(new Color(0, 0, 0, 25));
            g.fill(new RoundRectangle2D.Float(s[0], s[1], s[2], s[3], 8, 8));
            g.setColor(new Color(0, 0, 0, 50));
            g.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1, new float[]{4, 3}, 0));
            g.draw(new RoundRectangle2D.Float(s[0], s[1], s[2], s[3], 8, 8));
            g.setStroke(new BasicStroke(1));
            if (t < 38) {
                g.setFont(MenuView.font(Font.PLAIN, h * 0.022f));
                g.setColor(new Color(0x9AA0A6));
                g.drawString("?", s[0] + s[2] / 2 - 3, s[1] + s[3] / 2 + 4);
            }
        }
        if (t >= 38) for (int i = 0; i < COUNT; i++) if (t >= hangAt(i) + 1.0) {
            float[] s = slot(i, w, h);
            printout(g, order.get(i), s[0], s[1], s[2], s[3], 0, true);
        }
    }

    private void desk(Graphics2D g, int w, int h) {
        float dy = h * 0.62f;
        // desk
        g.setColor(new Color(0x795548));
        g.fillRect((int) (w * 0.03f), (int) dy, (int) (w * 0.46f), (int) (h * 0.04f));
        g.fillRect((int) (w * 0.05f), (int) dy, (int) (w * 0.02f), (int) (h * 0.2f));
        g.fillRect((int) (w * 0.45f), (int) dy, (int) (w * 0.02f), (int) (h * 0.2f));
        // monitor
        float mx = w * 0.06f, my = h * 0.2f, mw = w * 0.27f, mh = h * 0.33f;
        g.setColor(new Color(0x263238));
        g.fill(new RoundRectangle2D.Float(mx, my, mw, mh, 10, 10));
        g.fillRect((int) (mx + mw * 0.45f), (int) (my + mh), (int) (mw * 0.1f), (int) (dy - my - mh));
        g.fillRect((int) (mx + mw * 0.32f), (int) (dy - 4), (int) (mw * 0.36f), 4);
        screen(g, mx + 5, my + 5, mw - 10, mh - 10, h);
        // keyboard
        g.setColor(new Color(0x455A64));
        g.fillRect((int) (w * 0.1f), (int) (dy - h * 0.018f), (int) (w * 0.16f), (int) (h * 0.018f));
        // printer
        float px = w * 0.35f, py = dy - h * 0.12f, pw = w * 0.12f, ph = h * 0.12f;
        boolean printing = t >= 17 && t < 28;
        float shake = printing ? (float) Math.sin(t * 60) * 1.2f : 0;
        g.setColor(new Color(0xECEFF1));
        g.fill(new RoundRectangle2D.Float(px + shake, py, pw, ph, 8, 8));
        g.setColor(new Color(0x90A4AE));
        g.fillRect((int) (px + pw * 0.15f + shake), (int) (py + ph * 0.25f), (int) (pw * 0.7f), 3);
        g.setColor(printing ? new Color(0x43A047) : new Color(0xB0BEC5));
        g.fill(new Ellipse2D.Float(px + pw * 0.82f + shake, py + ph * 0.6f, 5, 5));
        if (printing && ((int) (t * 4)) % 2 == 0) {
            g.setFont(MenuView.font(Font.BOLD, h * 0.025f));
            g.setColor(new Color(0x546E7A));
            g.drawString("brrrrt", px + pw * 0.1f, py - h * 0.015f);
        }
    }

    /** What's on the monitor: an image search scrolling past, or the printing dialog. */
    private void screen(Graphics2D g, float x, float y, float w, float h, int H) {
        g.setColor(Color.WHITE);
        g.fillRect((int) x, (int) y, (int) w, (int) h);
        java.awt.Shape clip = g.getClip();
        g.clipRect((int) x, (int) y, (int) w, (int) h);
        if (t < 17) {
            g.setColor(new Color(0xF1F3F4));
            g.fillRect((int) x, (int) y, (int) w, (int) (h * 0.15f));
            g.setColor(new Color(0x202124));
            g.setFont(MenuView.font(Font.PLAIN, H * 0.018f));
            g.drawString("box art, high quality, NOT fan art", x + 4, y + h * 0.1f);
            // a grid of pictures scrolling up; the picked ones get a tick
            List<Art> a = art();
            float cell = w / 4f, scroll = (float) ((t - 1) * cell * 0.55);
            for (int i = 0; i < 40; i++) {
                float cx = x + (i % 4) * cell + 2, cy = y + h * 0.18f + (i / 4) * cell * 1.3f - scroll;
                if (cy > y + h || cy + cell * 1.25f < y + h * 0.15f) continue;
                Art art = a.get(i % a.size());
                boolean picked = i % 7 == 3 && (i / 7) < COUNT && t > 3 + (i / 7) * 1.6;
                printout(g, i % 3 == 1 ? new Art("", "", Color.getHSBColor(i * 0.13f, 0.4f, 0.9f), null) : art, cx, cy, cell - 4, cell * 1.25f, 0, false);
                if (picked) {
                    g.setColor(new Color(0x34A853));
                    g.fill(new Ellipse2D.Float(cx + cell - 14, cy + 2, 10, 10));
                    g.setColor(Color.WHITE);
                    g.setStroke(new BasicStroke(1.6f));
                    g.drawLine((int) (cx + cell - 12), (int) (cy + 7), (int) (cx + cell - 10), (int) (cy + 9));
                    g.drawLine((int) (cx + cell - 10), (int) (cy + 9), (int) (cx + cell - 6), (int) (cy + 4));
                    g.setStroke(new BasicStroke(1));
                }
            }
        } else {
            g.setColor(new Color(0x202124));
            g.setFont(MenuView.font(Font.BOLD, H * 0.024f));
            g.drawString(t < 28 ? "Printing " + Math.min(COUNT, 1 + (int) ((t - 17) / 1.6)) + " of " + COUNT + "..." : "Printed " + COUNT + " pages", x + 6, y + h * 0.3f);
            g.setColor(new Color(0xDADCE0));
            g.fillRect((int) (x + 6), (int) (y + h * 0.45f), (int) (w - 12), 6);
            g.setColor(new Color(0x1A73E8));
            g.fillRect((int) (x + 6), (int) (y + h * 0.45f), (int) ((w - 12) * Math.min(1, (t - 17) / 10)), 6);
        }
        g.setClip(clip);
    }

    /** When printout i (in sorted order) goes up on the wall. */
    private static double hangAt(int i) {
        return 39.5 + i * 2.4;
    }

    /** The printouts: out of the printer onto a pile, sorted on the desk, then up onto the wall. */
    private void sheets(Graphics2D g, int w, int h) {
        if (t < 17) return;
        List<Art> printed = art(), order = inOrder();
        float sw = w * 0.07f, sh = sw * 1.35f;
        float pileX = w * 0.36f, pileY = h * 0.62f - sh - 2;
        for (int i = 0; i < COUNT; i++) {
            double out = 17.6 + i * 1.6;
            if (t < out) continue;
            Art a = printed.get(i);
            int pos = order.indexOf(a);
            float x, y;
            double rot;
            if (t < out + 1.0) {                                    // sliding out of the printer
                double k = ease(t - out);
                x = (float) (w * 0.37f + k * w * 0.01f);
                y = (float) (h * 0.5f - (1 - k) * h * 0.02f + k * (pileY - h * 0.5f));
                rot = 0;
            } else if (t < 28.5) {                                  // on the pile
                x = pileX + i * 1.5f;
                y = pileY - i * 1.5f;
                rot = (i % 3 - 1) * 0.06;
            } else if (t < hangAt(pos)) {                           // sorted, in a row on the desk
                double k = ease((t - 28.5 - pos * 0.5) / 1.2);
                float tx = w * 0.04f + pos * (sw + 3), ty = h * 0.62f - sh - 2;
                x = (float) (pileX + (tx - pileX) * k);
                y = (float) (pileY + (ty - pileY) * k - Math.sin(k * Math.PI) * h * 0.08f);
                rot = (1 - k) * 0.2;
            } else if (t < hangAt(pos) + 1.0) {                     // carried up to its slot
                double k = ease(t - hangAt(pos));
                float[] s = slot(pos, w, h);
                float fx = w * 0.04f + pos * (sw + 3), fy = h * 0.62f - sh - 2;
                x = (float) (fx + (s[0] - fx) * k);
                y = (float) (fy + (s[1] - fy) * k - Math.sin(k * Math.PI) * h * 0.1f);
                rot = Math.sin(k * Math.PI) * 0.25;
                printout(g, a, x, y, (float) (sw + (s[2] - sw) * k), (float) (sh + (s[3] - sh) * k), rot, false);
                continue;
            } else continue;                                        // on the wall (the board draws it)
            printout(g, a, x, y, sw, sh, rot, false);
        }
    }

    /** One printout: white paper, the cover (or a drawn one: the console's colour and the name), maybe a pin. */
    private void printout(Graphics2D g, Art a, float x, float y, float w, float h, double rot, boolean pinned) {
        AffineTransform at = g.getTransform();
        g.translate(x + w / 2, y + h / 2);
        g.rotate(rot);
        g.translate(-w / 2, -h / 2);
        g.setColor(new Color(0, 0, 0, 40));
        g.fillRect(2, 2, (int) w, (int) h);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, (int) w, (int) h);
        float m = Math.max(1.5f, w * 0.06f);
        if (a.image() != null) {
            BufferedImage img = a.image();
            double s = Math.min((w - 2 * m) / img.getWidth(), (h - 2 * m) / img.getHeight());
            int iw = (int) (img.getWidth() * s), ih = (int) (img.getHeight() * s);
            g.drawImage(img, (int) ((w - iw) / 2), (int) ((h - ih) / 2), iw, ih, null);
        } else {
            g.setColor(a.color());
            g.fillRect((int) m, (int) m, (int) (w - 2 * m), (int) (h - 2 * m));
            if (!a.title().isEmpty() && w > 18) {
                g.setColor(Color.WHITE);
                float fs = Math.max(5f, w * 0.13f);
                g.setFont(MenuView.font(Font.BOLD, fs));
                FontMetrics fm = g.getFontMetrics();
                float ty = h * 0.45f;
                for (String word : wrap(a.title(), fm, w - 2 * m - 2)) {
                    g.drawString(word, (w - fm.stringWidth(word)) / 2, ty);
                    ty += fm.getHeight() * 0.9f;
                }
            }
        }
        if (pinned) {
            g.setColor(new Color(0xE53935));
            g.fill(new Ellipse2D.Float(w / 2 - 3, -2, 6, 6));
        }
        g.setTransform(at);
    }

    private static List<String> wrap(String s, FontMetrics fm, float width) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : s.split(" ")) {
            String test = line.length() == 0 ? word : line + " " + word;
            if (fm.stringWidth(test) > width && line.length() > 0) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else line = new StringBuilder(test);
        }
        if (line.length() > 0) lines.add(line.toString());
        return lines.size() > 3 ? lines.subList(0, 3) : lines;
    }

    /** The WII-UU guy: on its stool at the desk, at the pile, then at the wall with each printout. */
    private void guy(Graphics2D g, int w, int h) {
        double x, y;
        float floor = h * 0.8f;
        if (t < 27) {                                               // at the computer
            x = 0.2;
            y = 0.56 + Math.abs(Math.sin(t * 3)) * 0.005;
        } else if (t < 38) {                                        // over to the pile and the desk
            double k = ease((t - 27) / 1.2);
            x = 0.2 + k * 0.12;
            y = 0.56;
        } else {                                                    // up and down the ladder to the wall
            int i = Math.max(0, Math.min(COUNT - 1, (int) ((t - 39.5) / 2.4)));
            double local = t - hangAt(i);
            float[] s = slot(i, w, h);
            double tx = (s[0] + s[2] * 0.5) / w - 0.06, ty = (s[1] + s[3] * 0.9) / h;
            double k = t < 39.5 ? ease((t - 38) / 1.5) : ease(local / 0.8);
            double fromX = t < 39.5 ? 0.32 : i == 0 ? 0.32 : (slot(i - 1, w, h)[0] + slot(i - 1, w, h)[2] * 0.5) / w - 0.06;
            double fromY = t < 39.5 ? 0.56 : i == 0 ? 0.56 : (slot(i - 1, w, h)[1] + slot(i - 1, w, h)[3] * 0.9) / h;
            x = fromX + (tx - fromX) * k;
            y = fromY + (ty - fromY) * k;
            if (t > 39.5 + COUNT * 2.4) {
                double back = ease((t - 39.5 - COUNT * 2.4) / 1.5);
                x += (0.42 - x) * back;
                y += (0.66 - y) * back;
            }
        }
        float s = h * 0.06f, cx = (float) (x * w), cy = (float) (y * h);
        // the stool while at the desk
        if (t < 27) {
            g.setColor(new Color(0x5D4037));
            g.fillRect((int) (cx - s * 0.6f), (int) (cy + s * 0.6f), (int) (s * 1.2f), (int) (s * 0.25f));
            g.fillRect((int) (cx - s * 0.1f), (int) (cy + s * 0.8f), (int) (s * 0.2f), (int) (floor - cy - s * 0.8f));
        } else {
            // little legs, walking
            g.setColor(new Color(0x37474F));
            g.setStroke(new BasicStroke(Math.max(1.5f, s * 0.12f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            float step = (float) Math.sin(t * 12) * s * 0.25f;
            g.drawLine((int) (cx - s * 0.35f), (int) (cy + s * 0.45f), (int) (cx - s * 0.35f + step), (int) (cy + s * 1.1f));
            g.drawLine((int) (cx + s * 0.35f), (int) (cy + s * 0.45f), (int) (cx + s * 0.35f - step), (int) (cy + s * 1.1f));
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
        // googly eyes: on the screen, the pile, or the wall
        float look = t < 27 ? -1 : t < 38 ? 0.3f : 1;
        float up = t < 27 ? -0.2f : t < 38 ? 0.6f : -0.5f;
        for (int e = -1; e <= 1; e += 2) {
            float ex = cx + e * s * 0.42f, ey = cy - ch / 2 - s * 0.12f, er = s * 0.26f;
            g.setColor(Color.WHITE);
            g.fill(new Ellipse2D.Float(ex - er, ey - er, er * 2, er * 2));
            g.setColor(new Color(0x2B2F36));
            g.setStroke(new BasicStroke(Math.max(1, s * 0.05f)));
            g.draw(new Ellipse2D.Float(ex - er, ey - er, er * 2, er * 2));
            g.fill(new Ellipse2D.Float(ex - er * 0.5f + look * er * 0.4f, ey - er * 0.5f + up * er * 0.4f, er, er));
        }
        g.setStroke(new BasicStroke(1));
        // typing / scrolling hands at the computer
        if (t < 17) {
            g.setColor(new Color(0x2B2F36));
            float hx = (float) (Math.sin(t * 18) * s * 0.15f);
            g.fill(new Ellipse2D.Float(cx - cw / 2 - s * 0.35f + hx, cy + s * 0.2f, s * 0.3f, s * 0.3f));
        }
        // the pin, while pinning
        if (t >= 39.5 && t < 39.5 + COUNT * 2.4) {
            int i = (int) ((t - 39.5) / 2.4);
            double local = t - hangAt(i);
            if (local > 0.9 && local < 1.6) {
                float[] sl = slot(i, w, h);
                g.setColor(new Color(0xE53935));
                g.fill(new Ellipse2D.Float(sl[0] + sl[2] / 2 - 3, sl[1] - 2 - (float) Math.max(0, 1.2 - local) * 10, 6, 6));
            }
        }
    }
}
