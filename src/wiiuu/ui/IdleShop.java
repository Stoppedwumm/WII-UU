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
import java.util.Locale;
import java.util.function.BiConsumer;

/**
 * An idle-time sketch: WII-UU goes online shopping on amazin.shop (a made-up store). It searches
 * for an HDMI cable, picks the sponsored golden one, gets talked into everything under "customers
 * also bought", stutters on the 1-Click button until it has 47 of them, gets its payment declined
 * ("the card holder is a video game console"), sends the owner its wish list, and finally gets a
 * free sample delivered: one centimetre of golden HDMI cable.
 *
 * <p>All drawn, nothing is visited or bought; worked out from the time since it started (see
 * {@link #SCRIPT} and {@link #MOUSE}), like {@link IdleWeb}.
 */
final class IdleShop implements IdleGames.Game {
    static final String URL = "amazin.shop";
    private static final double END = 60;

    private final BiConsumer<String, String> say;
    private final long started = System.currentTimeMillis();
    private final List<String> spoken = new ArrayList<>();
    private double t;
    private boolean done;
    private long doneAt;

    IdleShop(BiConsumer<String, String> say) {
        this.say = say;
    }

    /** What it says, when: time, words, mood. */
    private static final Object[][] SCRIPT = {
            {0.6, "I need a new HDMI cable. For... science.", "focus"},
            {7.0, "Ahh, online shopping. My favourite.", "happy"},
            {12.6, "Obviously the golden one.", "smug"},
            {17.5, "$899? For a cable this good, that's a bargain.", "smug"},
            {20.4, "+500 FPS? Take my money.", "happy"},
            {22.6, "A REAL Wii U GamePad? ...I'm not jealous. I'm NOT.", "nervous"},
            {25.6, "Wait. Why is it still clicking?", "nervous"},
            {28.0, "47 golden cables. That's... fine. Very normal.", "nervous"},
            {31.5, "$42,317.53. Hmm. Okay. Checkout.", "focus"},
            {34.8, "Declined?! Rude.", "shocked"},
            {38.0, "Fine. 100 arcade tokens it is.", "sneaky"},
            {40.3, "Declined AGAIN?!", "sad"},
            {43.0, "Okay. Wish list. Sent to the owner's phone. No pressure.", "sneaky"},
            {48.0, "Wait, a delivery? For me?", "shocked"},
            {53.5, "A free sample! One centimetre. Of glory.", "happy"},
            {57.0, "Best. Purchase. Ever.", "smug"},
    };

    /** The pointer: time, x, y (fractions of the picture), click. */
    private static final double[][] MOUSE = {
            {0, 0.5, 0.6, 0}, {6.5, 0.5, 0.6, 0}, {8.5, 0.5, 0.25, 1}, {11.4, 0.5, 0.25, 0}, {12.8, 0.6, 0.53, 1},
            {15.2, 0.72, 0.62, 1}, {19.2, 0.3, 0.86, 0}, {20.6, 0.3, 0.86, 1}, {21.6, 0.53, 0.86, 0}, {23.6, 0.72, 0.72, 1},
            {28.8, 0.72, 0.72, 0}, {30.0, 0.78, 0.2, 1}, {33.4, 0.75, 0.7, 1}, {36.8, 0.42, 0.62, 1}, {39.5, 0.75, 0.7, 1},
            {41.8, 0.3, 0.78, 1}, {44.5, 0.5, 0.6, 0}};

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

    /** How many golden cables are in the cart (the 1-Click button stutters from 23.6 s). */
    private int quantity() {
        if (t < 15.2) return 0;
        if (t < 23.6) return 1;
        return (int) Math.min(47, 1 + Math.pow(Math.max(0, t - 23.6) * 3.4, 1.35));
    }

    private int cart() {
        int extras = (t >= 20.6 ? 1 : 0);
        return quantity() + extras;
    }

    // ---- drawing -----------------------------------------------------------------------------------

    private static final Color NAVY = new Color(0x131921), ORANGE = new Color(0xFF9900), PRICE = new Color(0xB12704);
    private static final float WX = 0.04f, WY = 0.11f, WW = 0.92f, WH = 0.86f;

    @Override
    public void paint(Graphics2D g, int w, int h) {
        g.setPaint(new GradientPaint(0, 0, new Color(0x2B3A55), 0, h, new Color(0x141B2B)));
        g.fillRect(0, 0, w, h);
        double fade = t > END - 2.5 ? Math.max(0, (END - t) / 2.5) : 1;
        java.awt.Composite c = g.getComposite();
        g.setComposite(java.awt.AlphaComposite.SrcOver.derive((float) fade));
        window(g, w, h);
        if (t >= 46 && t < END - 1) delivery(g, w, h);
        if (t < 45) pointer(g, w, h);
        g.setComposite(c);
    }

    private void window(Graphics2D g, int w, int h) {
        float x = WX * w, y = WY * h, ww = WW * w, wh = WH * h, bar = h * 0.065f;
        g.setColor(new Color(0, 0, 0, 90));
        g.fillRoundRect((int) x + 4, (int) y + 6, (int) ww, (int) wh, 14, 14);
        g.setColor(new Color(0xDADDE1));
        g.fill(new RoundRectangle2D.Float(x, y, ww, wh, 14, 14));
        g.setColor(new Color(0xBFC4CA));
        g.fill(new RoundRectangle2D.Float(x, y, ww, bar, 14, 14));
        g.fillRect((int) x, (int) (y + bar / 2), (int) ww, (int) (bar / 2));
        g.setColor(new Color(0xEEF0F2));
        g.fill(new RoundRectangle2D.Float(x + ww * 0.02f, y + bar * 0.18f, ww * 0.3f, bar * 0.82f + 2, 10, 10));
        g.setFont(MenuView.font(Font.PLAIN, bar * 0.4f));
        g.setColor(new Color(0x3C4043));
        g.drawString(t < 6 ? "New tab" : "amazin' - Everything, delivered*", x + ww * 0.035f, y + bar * 0.72f);
        for (int i = 0; i < 3; i++) {
            g.setColor(new Color[]{new Color(0xFF5F57), new Color(0xFEBC2E), new Color(0x28C840)}[i]);
            g.fill(new Ellipse2D.Float(x + ww - bar * (1.1f + i * 0.6f), y + bar * 0.32f, bar * 0.36f, bar * 0.36f));
        }
        float ay = y + bar;
        g.setColor(new Color(0xEEF0F2));
        g.fillRect((int) x, (int) ay, (int) ww, (int) bar);
        g.setColor(Color.WHITE);
        g.fill(new RoundRectangle2D.Float(x + ww * 0.08f, ay + bar * 0.15f, ww * 0.84f, bar * 0.7f, bar * 0.7f, bar * 0.7f));
        g.setFont(MenuView.font(Font.PLAIN, bar * 0.4f));
        g.setColor(new Color(0x202124));
        int typed = (int) Math.max(0, Math.min(URL.length(), (t - 1.5) * 5));
        String shown = "https://" + URL.substring(0, typed);
        g.drawString(shown, x + ww * 0.1f, ay + bar * 0.63f);
        if (t < 5 && ((int) (t * 3)) % 2 == 0) g.fillRect((int) (x + ww * 0.1f + g.getFontMetrics().stringWidth(shown)) + 1, (int) (ay + bar * 0.3f), 1, (int) (bar * 0.42f));
        float py = ay + bar, ph = wh - bar * 2;
        if (t >= 4.5 && t < 5.8) {
            g.setColor(new Color(0x1A73E8));
            g.fillRect((int) x, (int) py, (int) (ww * ease((t - 4.5) / 1.3)), 3);
        }
        if (t < 5.6) return;
        java.awt.Shape clip = g.getClip();
        g.clipRect((int) x, (int) py, (int) ww, (int) ph);
        g.setColor(Color.WHITE);
        g.fillRect((int) x, (int) py, (int) ww, (int) ph);
        header(g, x, py, ww, h);
        float cy = py + h * 0.11f, ch = ph - h * 0.11f;
        if (t < 11.4) home(g, x, cy, ww, ch, h);
        else if (t < 15.2) results(g, x, cy, ww, ch, h);
        else if (t < 30.0) product(g, x, cy, ww, ch, h);
        else checkout(g, x, cy, ww, ch, h);
        g.setClip(clip);
    }

    /** The store's own bar: the name, the search, the cart. */
    private void header(Graphics2D g, float x, float y, float w, int h) {
        float hh = h * 0.11f;
        g.setColor(NAVY);
        g.fillRect((int) x, (int) y, (int) w, (int) hh);
        g.setFont(MenuView.font(Font.BOLD, hh * 0.42f));
        g.setColor(Color.WHITE);
        g.drawString("amazin'", x + w * 0.02f, y + hh * 0.62f);
        g.setFont(MenuView.font(Font.PLAIN, hh * 0.2f));
        g.setColor(ORANGE);
        g.drawString("everything, delivered*", x + w * 0.02f, y + hh * 0.88f);
        // search
        float sx = x + w * 0.2f, sw = w * 0.55f, sy = y + hh * 0.22f, sh = hh * 0.56f;
        g.setColor(Color.WHITE);
        g.fill(new RoundRectangle2D.Float(sx, sy, sw, sh, 8, 8));
        g.setColor(ORANGE);
        g.fill(new RoundRectangle2D.Float(sx + sw - sh * 1.2f, sy, sh * 1.2f, sh, 8, 8));
        g.setColor(NAVY);
        g.setStroke(new BasicStroke(Math.max(1.5f, sh * 0.09f)));
        g.draw(new Ellipse2D.Float(sx + sw - sh * 0.95f, sy + sh * 0.2f, sh * 0.42f, sh * 0.42f));
        g.drawLine((int) (sx + sw - sh * 0.58f), (int) (sy + sh * 0.58f), (int) (sx + sw - sh * 0.4f), (int) (sy + sh * 0.78f));
        String q = "hdmi cable";
        int typed = (int) Math.max(0, Math.min(q.length(), (t - 9.0) * 6));
        g.setFont(MenuView.font(Font.PLAIN, sh * 0.48f));
        g.setColor(typed == 0 ? new Color(0x9E9E9E) : new Color(0x111111));
        g.drawString(typed == 0 ? "Search amazin'" : q.substring(0, typed), sx + 8, sy + sh * 0.66f);
        // the cart, with its count
        float cx = x + w * 0.9f, cyy = y + hh * 0.5f, cs = hh * 0.34f;
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(Math.max(1.5f, cs * 0.12f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        Path2D cartShape = new Path2D.Float();
        cartShape.moveTo(cx - cs, cyy - cs * 0.6f);
        cartShape.lineTo(cx - cs * 0.7f, cyy - cs * 0.6f);
        cartShape.lineTo(cx - cs * 0.4f, cyy + cs * 0.4f);
        cartShape.lineTo(cx + cs * 0.7f, cyy + cs * 0.4f);
        cartShape.lineTo(cx + cs * 0.9f, cyy - cs * 0.3f);
        cartShape.lineTo(cx - cs * 0.6f, cyy - cs * 0.3f);
        g.draw(cartShape);
        g.fill(new Ellipse2D.Float(cx - cs * 0.35f, cyy + cs * 0.55f, cs * 0.25f, cs * 0.25f));
        g.fill(new Ellipse2D.Float(cx + cs * 0.45f, cyy + cs * 0.55f, cs * 0.25f, cs * 0.25f));
        int n = cart();
        if (n > 0) {
            g.setFont(MenuView.font(Font.BOLD, hh * 0.3f));
            g.setColor(ORANGE);
            String s = Integer.toString(n);
            g.drawString(s, cx - g.getFontMetrics().stringWidth(s) / 2f + cs * 0.1f, cyy - cs * 0.65f);
        }
    }

    private void home(Graphics2D g, float x, float y, float w, float h, int H) {
        g.setPaint(new GradientPaint(x, y, new Color(0x37475A), x + w, y, new Color(0x232F3E)));
        g.fillRect((int) x, (int) y, (int) w, (int) (h * 0.35f));
        g.setColor(Color.WHITE);
        g.setFont(MenuView.font(Font.BOLD, H * 0.06f));
        g.drawString("DEALS OF THE DAY", x + w * 0.05f, y + h * 0.16f);
        g.setFont(MenuView.font(Font.PLAIN, H * 0.03f));
        g.drawString("Up to 3% off things you don't need", x + w * 0.05f, y + h * 0.26f);
        String[][] tiles = {{"Smart toaster", "now with Wi-Fi"}, {"Sock subscription", "monthly socks"}, {"Banana slicer", "a classic"},
                {"Cable for your cable", "very important"}};
        for (int i = 0; i < tiles.length; i++) {
            float tx = x + w * (0.04f + i * 0.24f), ty = y + h * 0.42f, tw = w * 0.21f, th = h * 0.5f;
            g.setColor(new Color(0xF7F7F7));
            g.fill(new RoundRectangle2D.Float(tx, ty, tw, th, 8, 8));
            g.setColor(new Color[]{new Color(0xFFCC80), new Color(0x90CAF9), new Color(0xFFF59D), new Color(0xB0BEC5)}[i]);
            g.fill(new RoundRectangle2D.Float(tx + tw * 0.15f, ty + th * 0.1f, tw * 0.7f, th * 0.45f, 10, 10));
            g.setColor(new Color(0x111111));
            g.setFont(MenuView.font(Font.BOLD, H * 0.026f));
            g.drawString(tiles[i][0], tx + 6, ty + th * 0.7f);
            g.setFont(MenuView.font(Font.PLAIN, H * 0.022f));
            g.setColor(new Color(0x565959));
            g.drawString(tiles[i][1], tx + 6, ty + th * 0.85f);
        }
    }

    private void results(Graphics2D g, float x, float y, float w, float h, int H) {
        g.setColor(new Color(0x565959));
        g.setFont(MenuView.font(Font.PLAIN, H * 0.024f));
        g.drawString("1-3 of over 9,000 results for \"hdmi cable\"", x + w * 0.04f, y + h * 0.08f);
        String[][] rows = {{"HDMI Cable, 2 m", "$7.99", "★★★★☆ (12,408)", ""},
                {"GOLD HDMI Cable - 1,000,000x better picture", "$899.99", "★★★★★ (3)", "Sponsored"},
                {"HDMI Cable for Gamers (RGB, +FPS)", "$59.00", "★★★☆☆ (88)", ""}};
        for (int i = 0; i < rows.length; i++) {
            float ry = y + h * (0.13f + i * 0.28f), rh = h * 0.25f;
            boolean gold = i == 1;
            g.setColor(gold ? new Color(0xFFF8E1) : new Color(0xFAFAFA));
            g.fill(new RoundRectangle2D.Float(x + w * 0.04f, ry, w * 0.92f, rh, 8, 8));
            cable(g, x + w * 0.06f, ry + rh * 0.15f, w * 0.12f, rh * 0.7f, gold);
            g.setColor(new Color(0x007185));
            g.setFont(MenuView.font(Font.BOLD, H * 0.03f));
            g.drawString(rows[i][0], x + w * 0.21f, ry + rh * 0.3f);
            g.setColor(new Color(0xF4A41D));
            g.setFont(MenuView.font(Font.PLAIN, H * 0.024f));
            g.drawString(rows[i][2], x + w * 0.21f, ry + rh * 0.55f);
            g.setColor(PRICE);
            g.setFont(MenuView.font(Font.BOLD, H * 0.035f));
            g.drawString(rows[i][1], x + w * 0.21f, ry + rh * 0.86f);
            if (!rows[i][3].isEmpty()) {
                g.setColor(new Color(0x565959));
                g.setFont(MenuView.font(Font.PLAIN, H * 0.02f));
                g.drawString(rows[i][3], x + w * 0.86f, ry + rh * 0.25f);
            }
        }
    }

    private void product(Graphics2D g, float x, float y, float w, float h, int H) {
        cable(g, x + w * 0.05f, y + h * 0.08f, w * 0.3f, h * 0.48f, true);
        float tx = x + w * 0.4f;
        g.setColor(new Color(0x0F1111));
        g.setFont(MenuView.font(Font.BOLD, H * 0.04f));
        g.drawString("GOLD HDMI Cable", tx, y + h * 0.12f);
        g.setFont(MenuView.font(Font.PLAIN, H * 0.026f));
        g.drawString("1,000,000x better picture* (*compared to no cable)", tx, y + h * 0.2f);
        g.setColor(PRICE);
        g.setFont(MenuView.font(Font.BOLD, H * 0.055f));
        g.drawString("$899.99", tx, y + h * 0.33f);
        g.setColor(new Color(0x007600));
        g.setFont(MenuView.font(Font.PLAIN, H * 0.024f));
        g.drawString("Only 1 left in stock (there are 900).", tx, y + h * 0.41f);
        // the buttons
        float bw = w * 0.22f, bh = h * 0.09f;
        button(g, x + w * 0.62f, y + h * 0.45f, bw, bh, new Color(0xFFD814), "Add to Cart", H);
        boolean stutter = t >= 23.6 && t < 28.8 && ((int) (t * 14)) % 2 == 0;
        button(g, x + w * 0.62f, y + h * 0.57f, bw, bh, stutter ? new Color(0xE47911) : new Color(0xFFA41C), "Buy now with 1-Click", H);
        if (quantity() > 0) {
            g.setFont(MenuView.font(Font.BOLD, H * 0.03f));
            g.setColor(quantity() > 1 ? PRICE : new Color(0x0F1111));
            g.drawString("In your cart: " + quantity(), x + w * 0.62f, y + h * 0.43f);
        }
        // customers also bought
        g.setColor(new Color(0x0F1111));
        g.setFont(MenuView.font(Font.BOLD, H * 0.03f));
        g.drawString("Customers also bought", x + w * 0.04f, y + h * 0.7f);
        String[][] also = {{"RGB lights (+500 FPS)", "$49.99"}, {"Gaming chair (for a console)", "$399"}, {"A REAL Wii U GamePad", "$1,999"}};
        for (int i = 0; i < also.length; i++) {
            float ax = x + w * (0.04f + i * 0.31f), ay = y + h * 0.74f, aw = w * 0.28f, ah = h * 0.22f;
            boolean added = i == 0 && t >= 20.6;
            g.setColor(added ? new Color(0xE8F5E9) : new Color(0xF7F7F7));
            g.fill(new RoundRectangle2D.Float(ax, ay, aw, ah, 8, 8));
            g.setColor(i == 0 ? Color.getHSBColor((float) (t * 0.5 % 1), 0.8f, 1f) : i == 1 ? new Color(0x455A64) : Color.WHITE);
            g.fill(new RoundRectangle2D.Float(ax + 6, ay + ah * 0.15f, aw * 0.22f, ah * 0.7f, 6, 6));
            if (i == 2) {
                g.setColor(new Color(0x9E9E9E));
                g.draw(new RoundRectangle2D.Float(ax + 6, ay + ah * 0.15f, aw * 0.22f, ah * 0.7f, 6, 6));
            }
            g.setColor(new Color(0x007185));
            g.setFont(MenuView.font(Font.BOLD, H * 0.022f));
            g.drawString(also[i][0], ax + aw * 0.3f, ay + ah * 0.42f);
            g.setColor(added ? new Color(0x007600) : PRICE);
            g.drawString(added ? "Added!" : also[i][1], ax + aw * 0.3f, ay + ah * 0.75f);
        }
    }

    private void checkout(Graphics2D g, float x, float y, float w, float h, int H) {
        g.setColor(new Color(0x0F1111));
        g.setFont(MenuView.font(Font.BOLD, H * 0.045f));
        g.drawString("Checkout", x + w * 0.04f, y + h * 0.11f);
        String[][] lines = {{"GOLD HDMI Cable  x 47", "$42,299.53"}, {"RGB lights (+500 FPS)", "$49.99"}, {"Shipping (by drone, probably)", "$0.00"},
                {"\"Service fee\"", "-$31.99 (?)"}};
        g.setFont(MenuView.font(Font.PLAIN, H * 0.028f));
        for (int i = 0; i < lines.length; i++) {
            float ly = y + h * (0.22f + i * 0.08f);
            g.setColor(new Color(0x0F1111));
            g.drawString(lines[i][0], x + w * 0.06f, ly);
            g.drawString(lines[i][1], x + w * 0.45f, ly);
        }
        g.setColor(PRICE);
        g.setFont(MenuView.font(Font.BOLD, H * 0.04f));
        g.drawString("Order total: $42,317.53", x + w * 0.06f, y + h * 0.6f);
        // payment
        g.setColor(new Color(0x0F1111));
        g.setFont(MenuView.font(Font.PLAIN, H * 0.026f));
        g.drawString(t < 37 ? "Pay with: WII-UU's card" : "Pay with: 100 arcade tokens", x + w * 0.06f, y + h * 0.7f);
        button(g, x + w * 0.62f, y + h * 0.62f, w * 0.26f, h * 0.11f, new Color(0xFFD814), "Place your order", H);
        if (t >= 41.8) button(g, x + w * 0.06f, y + h * 0.82f, w * 0.3f, h * 0.1f, new Color(0xE3E6E6), "Add all to Wish List", H);
        // declined, twice
        if (t >= 33.6 && t < 36.8 || t >= 39.7 && t < 41.6) {
            boolean second = t >= 39.7;
            String a = "Payment declined", b = second ? "Arcade tokens are not money." : "Card holder is a video game console.";
            float mw = w * 0.5f, mh = h * 0.25f, mx = x + (w - mw) / 2, my = y + h * 0.3f;
            g.setColor(new Color(0, 0, 0, 90));
            g.fillRect((int) x, (int) y, (int) w, (int) h);
            g.setColor(Color.WHITE);
            g.fill(new RoundRectangle2D.Float(mx, my, mw, mh, 12, 12));
            g.setColor(PRICE);
            g.setFont(MenuView.font(Font.BOLD, H * 0.045f));
            g.drawString("⚠ " + a, mx + 14, my + mh * 0.42f);
            g.setColor(new Color(0x0F1111));
            g.setFont(MenuView.font(Font.PLAIN, H * 0.028f));
            g.drawString(b, mx + 14, my + mh * 0.75f);
        }
        if (t >= 42.4 && t < 46) {
            String s = "Wish List sent to the owner's phone ✉";
            g.setFont(MenuView.font(Font.BOLD, H * 0.034f));
            FontMetrics fm = g.getFontMetrics();
            float bw = fm.stringWidth(s) + 24, bx = x + (w - bw) / 2, by = y + h * 0.42f;
            g.setColor(new Color(0x067D62));
            g.fill(new RoundRectangle2D.Float(bx, by, bw, H * 0.07f, 12, 12));
            g.setColor(Color.WHITE);
            g.drawString(s, bx + 12, by + H * 0.048f);
        }
    }

    private static void button(Graphics2D g, float x, float y, float w, float h, Color c, String label, int H) {
        g.setColor(c);
        g.fill(new RoundRectangle2D.Float(x, y, w, h, h, h));
        g.setColor(new Color(0x0F1111));
        g.setFont(MenuView.font(Font.PLAIN, H * 0.026f));
        FontMetrics fm = g.getFontMetrics();
        g.drawString(label, x + (w - fm.stringWidth(label)) / 2, y + h * 0.66f);
    }

    /** An HDMI cable: a curl of cable and its plug (golden or not). */
    private static void cable(Graphics2D g, float x, float y, float w, float h, boolean gold) {
        g.setColor(new Color(0xF5F5F5));
        g.fill(new RoundRectangle2D.Float(x, y, w, h, 10, 10));
        Color c = gold ? new Color(0xD4AF37) : new Color(0x212121);
        g.setColor(c);
        g.setStroke(new BasicStroke(Math.max(2, h * 0.07f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        Path2D curl = new Path2D.Float();
        curl.moveTo(x + w * 0.15f, y + h * 0.8f);
        curl.curveTo(x + w * 0.1f, y + h * 0.2f, x + w * 0.7f, y + h * 0.1f, x + w * 0.6f, y + h * 0.5f);
        curl.curveTo(x + w * 0.5f, y + h * 0.9f, x + w * 0.9f, y + h * 0.8f, x + w * 0.85f, y + h * 0.35f);
        g.draw(curl);
        g.fill(new RoundRectangle2D.Float(x + w * 0.76f, y + h * 0.12f, w * 0.18f, h * 0.2f, 4, 4));
        if (gold) {
            g.setColor(new Color(255, 255, 255, 180));
            g.fill(new Ellipse2D.Float(x + w * 0.3f, y + h * 0.25f, w * 0.06f, w * 0.06f));
            g.fill(new Ellipse2D.Float(x + w * 0.62f, y + h * 0.62f, w * 0.04f, w * 0.04f));
        }
        g.setStroke(new BasicStroke(1));
    }

    /** The delivery: a van drives in, a box drops, the box opens, a tiny piece of golden cable. */
    private void delivery(Graphics2D g, int w, int h) {
        g.setColor(new Color(0, 0, 0, (int) (150 * Math.min(1, (t - 46) / 0.6))));
        g.fillRect(0, 0, w, h);
        // the street
        float ground = h * 0.78f;
        g.setColor(new Color(0x37474F));
        g.fillRect(0, (int) ground, w, h);
        g.setColor(new Color(255, 255, 255, 80));
        for (int i = 0; i < 10; i++) g.fillRect((int) (i * w / 9f - (t * 60 % (w / 9f))), (int) (ground + h * 0.1f), (int) (w * 0.05f), 3);
        // the van: in, stop, out
        double vx;
        if (t < 49) vx = -0.4 + ease((t - 46) / 3) * 0.75;
        else if (t < 51) vx = 0.35;
        else vx = 0.35 + Math.pow(Math.max(0, t - 51) / 2.5, 2) * 1.2;
        float vw = w * 0.32f, vh = h * 0.22f, x0 = (float) (vx * w), y0 = ground - vh - h * 0.03f;
        g.setColor(new Color(0x232F3E));
        g.fill(new RoundRectangle2D.Float(x0, y0, vw * 0.7f, vh, 10, 10));
        g.fill(new RoundRectangle2D.Float(x0 + vw * 0.66f, y0 + vh * 0.25f, vw * 0.34f, vh * 0.75f, 14, 14));
        g.setColor(new Color(0x90CAF9));
        g.fill(new RoundRectangle2D.Float(x0 + vw * 0.78f, y0 + vh * 0.33f, vw * 0.17f, vh * 0.3f, 6, 6));
        g.setColor(ORANGE);
        g.setFont(MenuView.font(Font.BOLD, vh * 0.24f));
        g.drawString("amazin'", x0 + vw * 0.08f, y0 + vh * 0.5f);
        g.setColor(Color.WHITE);
        g.setFont(MenuView.font(Font.PLAIN, vh * 0.12f));
        g.drawString("everything, delivered*", x0 + vw * 0.08f, y0 + vh * 0.7f);
        g.setColor(new Color(0x111111));
        for (float wx : new float[]{0.15f, 0.8f}) g.fill(new Ellipse2D.Float(x0 + vw * wx - vh * 0.13f, ground - h * 0.06f, vh * 0.26f, vh * 0.26f));
        // the box, dropped at the door, then opened
        if (t >= 49.6) {
            float bx = w * 0.62f, bs = h * 0.12f;
            double drop = Math.min(1, (t - 49.6) / 0.5);
            float by = (float) (ground - bs - (1 - drop) * h * 0.2f);
            g.setColor(new Color(0xC8A16B));
            g.fill(new RoundRectangle2D.Float(bx, by, bs * 1.3f, bs, 6, 6));
            g.setColor(new Color(0xA07A48));
            g.fillRect((int) (bx + bs * 0.55f), (int) by, (int) (bs * 0.2f), (int) bs);
            if (t >= 52.4) {
                // a tiny gleam on top: the sample
                double open = Math.min(1, (t - 52.4) / 0.6);
                float sy = (float) (by - h * 0.06f * open);
                g.setColor(new Color(0xD4AF37));
                g.fillRect((int) (bx + bs * 0.6f), (int) sy, (int) Math.max(2, bs * 0.12f), (int) Math.max(1, bs * 0.05f));
                g.setColor(new Color(255, 236, 140, (int) (200 * open)));
                for (int r = 0; r < 8; r++) {
                    double a = r * Math.PI / 4 + t;
                    g.drawLine((int) (bx + bs * 0.66f), (int) sy, (int) (bx + bs * 0.66f + Math.cos(a) * bs * 0.4f), (int) (sy + Math.sin(a) * bs * 0.4f));
                }
                g.setFont(MenuView.font(Font.BOLD, h * 0.035f));
                g.setColor(Color.WHITE);
                String s = "Free sample: 1 cm of GOLD HDMI cable";
                g.drawString(s, bx + bs * 0.65f - g.getFontMetrics().stringWidth(s) / 2f, sy - h * 0.06f);
            }
        }
    }

    private void pointer(Graphics2D g, int w, int h) {
        double[] a = MOUSE[0], b = MOUSE[MOUSE.length - 1];
        for (int i = 0; i + 1 < MOUSE.length; i++) {
            if (t >= MOUSE[i][0] && t < MOUSE[i + 1][0]) {
                a = MOUSE[i];
                b = MOUSE[i + 1];
                break;
            }
        }
        double span = b[0] - a[0], k = span <= 0 ? 1 : ease(Math.min(1, (t - a[0]) / Math.min(span, 1.1)));
        float x = (float) ((a[1] + (b[1] - a[1]) * k) * w), y = (float) ((a[2] + (b[2] - a[2]) * k) * h), s = h * 0.045f;
        boolean clicking = t >= 23.6 && t < 28.8 && ((int) (t * 14)) % 2 == 0;
        for (double[] m : MOUSE) if (m[3] == 1 && t > m[0] - 0.05 && t < m[0] + 0.25) clicking = true;
        if (clicking) {
            g.setColor(new Color(255, 160, 0, 140));
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

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "IdleShop at %.1f s", t);
    }
}
