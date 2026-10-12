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
