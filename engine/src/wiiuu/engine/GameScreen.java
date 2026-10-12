package wiiuu.engine;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.KeyEvent;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

import wiiuu.input.PadButton;

/**
 * A game's picture as Choccy Milk draws it: as big as fits at the game's shape, whole source pixels
 * (no smoothing), on black, with a hint at the top for a few seconds after it starts. Shared by
 * WII-UU's menu and the Choccy Milk app; frames come in on the player's reading thread.
 */
public final class GameScreen {
    /** The hint's color (WII-UU's warm "milk" brown). */
    public static final Color HINT = new Color(0xD7A27A);

    private volatile BufferedImage picture;
    private volatile float aspect = 4f / 3;
    private long shownAt;

    /** A frame (0xRRGGBB pixels); aspect is its display shape (0 = as the pixels are). */
    public void frame(int[] rgb, int w, int h, float aspect) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        System.arraycopy(rgb, 0, ((DataBufferInt) img.getRaster().getDataBuffer()).getData(), 0, w * h);
        this.aspect = aspect > 0 ? aspect : (float) w / h;
        picture = img;
    }

    /** Whether a game picture is there (a game is playing). */
    public boolean showing() {
        return picture != null;
    }

    /** The newest picture, or null. */
    public BufferedImage picture() {
        return picture;
    }

    /** Forgets the picture (the game ended); the hint shows again for the next one. */
    public void clear() {
        picture = null;
        shownAt = 0;
    }

    /**
     * Paints the picture over w x h. hint (or null) shows for 4 s from the first paint, in font
     * (sized by the caller, about 2.8 % of the height looks right).
     */
    public void paint(Graphics2D g, int w, int h, String hint, Font font) {
        BufferedImage img = picture;
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, w, h);
        if (img == null) return;
        float a = aspect;
        int dh = h, dw = Math.round(h * a);
        if (dw > w) {
            dw = w;
            dh = Math.round(w / a);
        }
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(img, (w - dw) / 2, (h - dh) / 2, dw, dh, null);
        long now = System.currentTimeMillis();
        if (shownAt == 0) shownAt = now;
        long since = now - shownAt;
        if (hint != null && since < 4000) {
            float alpha = since < 3400 ? 1f : (4000 - since) / 600f;
            Composite c = g.getComposite();
            g.setComposite(AlphaComposite.SrcOver.derive(Math.max(0, alpha)));
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setFont(font);
            FontMetrics fm = g.getFontMetrics();
            float tw = fm.stringWidth(hint) + h * 0.04f, th = h * 0.05f;
            g.setColor(new Color(0, 0, 0, 170));
            g.fill(new RoundRectangle2D.Float(h * 0.03f, h * 0.03f, tw, th, th, th));
            g.setColor(HINT);
            g.drawString(hint, h * 0.05f, h * 0.03f + th / 2 + fm.getAscent() * 0.36f);
            g.setComposite(c);
        }
        paintNote(g, w, h, font);
    }

    // ---- announcements (an achievement unlocked...) at the bottom ------------------------------------

    private volatile String noteTitle, noteText;
    private volatile boolean noteTrophy;
    private volatile long noteFrom, noteUntil;

    /** Shows a card at the bottom for a while: a title, a line under it, with a trophy for achievements. */
    public void announce(String title, String text, boolean trophy, long millis) {
        noteTitle = title;
        noteText = text;
        noteTrophy = trophy;
        noteFrom = System.currentTimeMillis();
        noteUntil = noteFrom + millis;
    }

    /** Whether a card is showing (keep repainting). */
    public boolean announcing() {
        return System.currentTimeMillis() < noteUntil;
    }

    private void paintNote(Graphics2D g, int w, int h, Font font) {
        long now = System.currentTimeMillis();
        if (now >= noteUntil || noteTitle == null) return;
        float in = Math.min(1f, (now - noteFrom) / 250f), out = Math.min(1f, (noteUntil - now) / 400f), a = Math.min(in, out);
        Composite c = g.getComposite();
        g.setComposite(AlphaComposite.SrcOver.derive(Math.max(0, a)));
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        float u = h / 100f;
        Font big = font.deriveFont(Font.BOLD, 3.2f * u), small = font.deriveFont(Font.PLAIN, 2.5f * u);
        FontMetrics fb = g.getFontMetrics(big), fs = g.getFontMetrics(small);
        float icon = noteTrophy ? 8 * u : 0, pad = 2.2f * u;
        float tw = Math.max(fb.stringWidth(noteTitle), noteText == null ? 0 : fs.stringWidth(noteText));
        float cw = Math.min(w - 4 * u, tw + icon + pad * (noteTrophy ? 3 : 2)), ch = 12 * u;
        float x = (w - cw) / 2, y = h - ch - 4 * u + (1 - in) * 3 * u;
        g.setColor(new Color(20, 14, 10, 225));
        g.fill(new RoundRectangle2D.Float(x, y, cw, ch, ch * 0.5f, ch * 0.5f));
        g.setColor(new Color(0xD7A27A));
        g.draw(new RoundRectangle2D.Float(x, y, cw, ch, ch * 0.5f, ch * 0.5f));
        float tx = x + pad;
        if (noteTrophy) {                                     // a gold cup
            float cx = x + pad + icon / 2, cy = y + ch / 2;
            g.setColor(new Color(0xF2C14E));
            g.fill(new java.awt.geom.Ellipse2D.Float(cx - icon / 2, cy - icon / 2, icon, icon));
            g.setColor(new Color(0x8A5A12));
            float cup = icon * 0.42f;
            g.fill(new RoundRectangle2D.Float(cx - cup / 2, cy - cup * 0.62f, cup, cup * 0.75f, cup * 0.5f, cup * 0.5f));
            g.fill(new java.awt.geom.Rectangle2D.Float(cx - cup * 0.09f, cy + cup * 0.1f, cup * 0.18f, cup * 0.3f));
            g.fill(new RoundRectangle2D.Float(cx - cup * 0.32f, cy + cup * 0.38f, cup * 0.64f, cup * 0.14f, cup * 0.1f, cup * 0.1f));
            tx = x + pad * 2 + icon;
        }
        g.setFont(big);
        g.setColor(new Color(0xF3E6DA));
        g.drawString(noteTitle, tx, y + ch * 0.46f);
        if (noteText != null) {
            g.setFont(small);
            g.setColor(new Color(0xD7A27A));
            g.drawString(noteText, tx, y + ch * 0.46f + fs.getAscent() * 1.15f);
        }
        g.setComposite(c);
    }

    /** Whether the hint is still showing (keep repainting until it has faded). */
    public boolean hintShowing() {
        return shownAt == 0 || System.currentTimeMillis() - shownAt < 4000;
    }

    /**
     * The keyboard's game buttons: arrows, X = A, Z = B, S = X, A = Y, Q / W = L / R, E / R = ZL / ZR,
     * Enter = Start (+), Space or Shift = Select (-). Null for other keys.
     */
    public static PadButton key(int keyCode) {
        return switch (keyCode) {
            case KeyEvent.VK_UP -> PadButton.UP;
            case KeyEvent.VK_DOWN -> PadButton.DOWN;
            case KeyEvent.VK_LEFT -> PadButton.LEFT;
            case KeyEvent.VK_RIGHT -> PadButton.RIGHT;
            case KeyEvent.VK_X -> PadButton.A;
            case KeyEvent.VK_Z -> PadButton.B;
            case KeyEvent.VK_S -> PadButton.X;
            case KeyEvent.VK_A -> PadButton.Y;
            case KeyEvent.VK_Q -> PadButton.L;
            case KeyEvent.VK_W -> PadButton.R;
            case KeyEvent.VK_E -> PadButton.ZL;
            case KeyEvent.VK_R -> PadButton.ZR;
            case KeyEvent.VK_ENTER -> PadButton.PLUS;
            case KeyEvent.VK_SPACE, KeyEvent.VK_SHIFT -> PadButton.MINUS;
            default -> null;
        };
    }
}
