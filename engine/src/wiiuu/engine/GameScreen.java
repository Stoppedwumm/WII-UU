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
 * A game's picture as Choccy Milk draws it: as big as fits at the game's shape (or stretched to
 * 16:9), whole source pixels or upscaled (smooth, Scale2x, Scale3x, xBR 2x / 4x, a CRT TV: {@link #look}),
 * optionally with each frame blended into the one before, on black, with a hint at the top for a few
 * seconds after it starts. Shared by WII-UU's menu and the Choccy Milk app; frames come in on the
 * player's reading thread.
 *
 * <p>The heavier effects (xBR, CRT) run on all of this computer's cores. When a linked WII-UU runs the
 * game (sharing the work), that's the work of the computer at the TV, which is idle otherwise.
 */
public final class GameScreen {
    /** The hint's color (WII-UU's warm "milk" brown). */
    public static final Color HINT = new Color(0xD7A27A);

    private volatile BufferedImage picture;
    private volatile float aspect = 4f / 3;
    private long shownAt;
    /** off (whole pixels), smooth, scale2x, scale3x, xbr2, xbr4, crt; and whether to stretch to 16:9 */
    private volatile String filter = "off";
    private volatile boolean stretch;
    /** each frame half the one before (games that flicker sprites for transparency look right) */
    private volatile boolean blend;
    private int[] before;

    /** How the next game's picture is drawn (see wiiuu.core.ChoccyPicture): the filter, "+blend" after it to blend frames. */
    public void look(String filter, boolean stretch) {
        String f = filter == null ? "off" : filter;
        this.blend = f.endsWith("+blend");
        this.filter = blend ? f.substring(0, f.length() - 6) : f;
        this.stretch = stretch;
        this.before = null;
    }

    /** A frame (0xRRGGBB pixels); aspect is its display shape (0 = as the pixels are). */
    public void frame(int[] rgb, int w, int h, float aspect) {
        String f = filter;
        if (blend) rgb = blend(rgb, w * h);
        int n = switch (f) {
            case "scale3x" -> 3;
            case "scale2x", "xbr2" -> 2;
            case "xbr4", "crt" -> 4;
            default -> 1;
        };
        if (n > 1 && w * h > 200_000) n = f.equals("crt") ? 2 : 1;   // already big (a high-resolution screen): less or no need
        BufferedImage img = new BufferedImage(w * n, h * n, BufferedImage.TYPE_INT_RGB);
        int[] out = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
        if (f.equals("crt")) crt(rgb, w, h, n, out);
        else if (n == 4 && f.equals("xbr4")) {
            int[] mid = new int[w * h * 4];
            xbr2(rgb, w, h, mid);
            xbr2(mid, w * 2, h * 2, out);
        } else if (n == 3) scale3x(rgb, w, h, out);
        else if (n == 2 && f.equals("xbr2")) xbr2(rgb, w, h, out);
        else if (n == 2) scale2x(rgb, w, h, out);
        else System.arraycopy(rgb, 0, out, 0, w * h);
        float a = aspect > 0 ? aspect : (float) w / h;
        this.aspect = stretch && a < 1.7f ? 16f / 9 : a;
        picture = img;
    }

    /** This frame half and half with the one before (the one before as it came, so nothing smears for long). */
    private int[] blend(int[] rgb, int n) {
        int[] was = before;
        before = rgb;
        if (was == null || was.length != rgb.length) return rgb;
        int[] mixed = new int[n];
        for (int i = 0; i < n; i++) {
            int a = rgb[i], b = was[i];
            mixed[i] = ((a & 0xfefefe) >>> 1) + ((b & 0xfefefe) >>> 1) + (a & b & 0x010101);
        }
        return mixed;
    }

    /** Runs rows from..to of some work on all cores (bands of rows; the work only writes its own rows). */
    private static void rows(int h, java.util.function.IntConsumer row) {
        int bands = Math.min(h, Math.max(1, Runtime.getRuntime().availableProcessors()) * 2);
        java.util.stream.IntStream.range(0, bands).parallel().forEach(b -> {
            int from = h * b / bands, to = h * (b + 1) / bands;
            for (int y = from; y < to; y++) row.accept(y);
        });
    }

    // ---- xBR (Hyllian's xBR, level 2): edges followed by their angle, so curves come out round ----

    /** How different two colours look (xBR's YUV distance: brightness counts most). */
    private static int dist(int yuvA, int yuvB) {
        int dy = Math.abs((yuvA >> 16 & 0xff) - (yuvB >> 16 & 0xff));
        int du = Math.abs((yuvA >> 8 & 0xff) - (yuvB >> 8 & 0xff));
        int dv = Math.abs((yuvA & 0xff) - (yuvB & 0xff));
        return 48 * dy + 7 * du + 6 * dv;
    }

    private static int yuv(int rgb) {
        int r = rgb >> 16 & 0xff, g = rgb >> 8 & 0xff, b = rgb & 0xff;
        int y = (299 * r + 587 * g + 114 * b) / 1000;
        int u = 128 + (-169 * r - 331 * g + 500 * b) / 1000;
        int v = 128 + (500 * r - 419 * g - 81 * b) / 1000;
        return y << 16 | Math.max(0, Math.min(255, u)) << 8 | Math.max(0, Math.min(255, v));
    }

    /** a and b mixed: wb/16 of b. */
    private static int mix(int a, int b, int wb) {
        int wa = 16 - wb;
        int r = ((a >> 16 & 0xff) * wa + (b >> 16 & 0xff) * wb) >> 4;
        int g = ((a >> 8 & 0xff) * wa + (b >> 8 & 0xff) * wb) >> 4;
        int bl = ((a & 0xff) * wa + (b & 0xff) * wb) >> 4;
        return r << 16 | g << 8 | bl;
    }

    /** xBR at 2x (4x: twice). */
    static void xbr2(int[] in, int w, int h, int[] out) {
        int[] yuv = new int[w * h];
        rows(h, y -> {
            for (int x = 0, i = y * w; x < w; x++, i++) yuv[i] = yuv(in[i]);
        });
        int ow = w * 2;
        rows(h, y -> {
            int[] px = new int[4];
            for (int x = 0; x < w; x++) {
                int e = in[y * w + x];
                px[0] = px[1] = px[2] = px[3] = e;
                // the four corners: each one the bottom-right one of the picture mirrored that way
                corner(in, yuv, w, h, x, y, 1, 1, px, 3, 2, 1);
                corner(in, yuv, w, h, x, y, 1, -1, px, 2, 3, 0);
                corner(in, yuv, w, h, x, y, -1, 1, px, 1, 0, 3);
                corner(in, yuv, w, h, x, y, -1, -1, px, 0, 1, 2);
                int o = y * 2 * ow + x * 2;
                out[o] = px[0];
                out[o + 1] = px[1];
                out[o + ow] = px[2];
                out[o + ow + 1] = px[3];
            }
        });
    }

    private static int at(int[] a, int w, int h, int x, int y) {
        x = x < 0 ? 0 : x >= w ? w - 1 : x;
        y = y < 0 ? 0 : y >= h ? h - 1 : y;
        return a[y * w + x];
    }

    /**
     * One corner of pixel E (the one towards sy / sx): where an edge crosses it, it takes the colour
     * across the edge; shallow and steep edges also tint the pixel beside it (side) or above it (up).
     * <pre>
     *     A1 B1 C1
     *  A0 A  B  C  C4
     *  D0 D  E  F  F4      (with sy = sx = 1; mirrored for the other corners)
     *  G0 G  H  I  I4
     *     G5 H5 I5
     * </pre>
     */
    private static void corner(int[] rgb, int[] yuv, int w, int h, int x, int y, int sy, int sx, int[] px,
                               int me, int side, int up) {
        int E = at(yuv, w, h, x, y), F = at(yuv, w, h, x + sx, y), H = at(yuv, w, h, x, y + sy);
        int I = at(yuv, w, h, x + sx, y + sy), C = at(yuv, w, h, x + sx, y - sy), G = at(yuv, w, h, x - sx, y + sy);
        int D = at(yuv, w, h, x - sx, y), B = at(yuv, w, h, x, y - sy);
        int F4 = at(yuv, w, h, x + 2 * sx, y), I4 = at(yuv, w, h, x + 2 * sx, y + sy);
        int H5 = at(yuv, w, h, x, y + 2 * sy), I5 = at(yuv, w, h, x + sx, y + 2 * sy);
        if (E == F || E == H) return;                      // no edge through this corner
        int across = dist(E, C) + dist(E, G) + dist(I, F4) + dist(I, H5) + 4 * dist(H, F);
        int along = dist(H, D) + dist(H, I5) + dist(F, I4) + dist(F, B) + 4 * dist(E, I);
        if (across >= along) return;
        int newColor = dist(E, F) <= dist(E, H) ? at(rgb, w, h, x + sx, y) : at(rgb, w, h, x, y + sy);
        int dFG = dist(F, G), dHC = dist(H, C);
        boolean shallow = 2 * dFG <= dHC && E != G && D != G;
        boolean steep = dFG >= 2 * dHC && E != C && B != C;
        int e = rgb[y * w + x];
        if (shallow && steep) {
            px[me] = mix(e, newColor, 13);
            px[side] = mix(px[side], newColor, 4);
            px[up] = mix(px[up], newColor, 4);
        } else if (shallow) {
            px[me] = mix(e, newColor, 12);
            px[side] = mix(px[side], newColor, 4);
        } else if (steep) {
            px[me] = mix(e, newColor, 12);
            px[up] = mix(px[up], newColor, 4);
        } else {
            px[me] = mix(e, newColor, 8);
        }
    }

    // ---- a CRT TV ------------------------------------------------------------------------------------

    /**
     * The picture as an old TV showed it, n times bigger: lines with dark gaps between them
     * (scanlines), a little softness along each line, the red / green / blue stripes of the
     * screen's mask, and somewhat brighter to make up for the dark.
     */
    static void crt(int[] in, int w, int h, int n, int[] out) {
        int ow = w * n;
        int[] line = n == 4 ? new int[]{15, 16, 13, 6} : new int[]{16, 9};         // brightness of each row of a line (/16)
        rows(h, y -> {
            for (int x = 0; x < w; x++) {
                int e = in[y * w + x], l = in[y * w + (x > 0 ? x - 1 : x)], r = in[y * w + (x < w - 1 ? x + 1 : x)];
                for (int c = 0; c < n; c++) {
                    // soft along the line: the outer columns take a bit of the neighbour
                    int px = c == 0 ? mix(e, l, 4) : c == n - 1 ? mix(e, r, 4) : e;
                    int mask = (x * n + c) % 3;                 // which stripe of the mask: red, green, blue
                    int pr = px >> 16 & 0xff, pg = px >> 8 & 0xff, pb = px & 0xff;
                    pr = pr * (mask == 0 ? 19 : 15) >> 4;
                    pg = pg * (mask == 1 ? 19 : 15) >> 4;
                    pb = pb * (mask == 2 ? 19 : 15) >> 4;
                    for (int rr = 0; rr < n; rr++) {
                        int k = line[rr];
                        int o = (y * n + rr) * ow + x * n + c;
                        out[o] = Math.min(255, pr * k >> 4) << 16 | Math.min(255, pg * k >> 4) << 8 | Math.min(255, pb * k >> 4);
                    }
                }
            }
        });
    }

    /**
     * Scale2x (AdvMAME2x): each pixel becomes 2x2, its corners taking a neighbour's colour where two
     * neighbours agree, so diagonal edges of pixel art come out smooth instead of stair-stepped.
     */
    static void scale2x(int[] in, int w, int h, int[] out) {
        int ow = w * 2;
        for (int y = 0; y < h; y++) {
            int up = (y > 0 ? y - 1 : y) * w, row = y * w, dn = (y < h - 1 ? y + 1 : y) * w;
            for (int x = 0; x < w; x++) {
                int l = x > 0 ? x - 1 : x, r = x < w - 1 ? x + 1 : x;
                int B = in[up + x], D = in[row + l], E = in[row + x], F = in[row + r], H = in[dn + x];
                int e0 = E, e1 = E, e2 = E, e3 = E;
                if (B != H && D != F) {
                    if (D == B) e0 = D;
                    if (B == F) e1 = F;
                    if (D == H) e2 = D;
                    if (H == F) e3 = F;
                }
                int o = y * 2 * ow + x * 2;
                out[o] = e0;
                out[o + 1] = e1;
                out[o + ow] = e2;
                out[o + ow + 1] = e3;
            }
        }
    }

    /** Scale3x (AdvMAME3x): the same idea at 3x3, with edge pixels as well as corners. */
    static void scale3x(int[] in, int w, int h, int[] out) {
        int ow = w * 3;
        for (int y = 0; y < h; y++) {
            int up = (y > 0 ? y - 1 : y) * w, row = y * w, dn = (y < h - 1 ? y + 1 : y) * w;
            for (int x = 0; x < w; x++) {
                int l = x > 0 ? x - 1 : x, r = x < w - 1 ? x + 1 : x;
                int A = in[up + l], B = in[up + x], C = in[up + r];
                int D = in[row + l], E = in[row + x], F = in[row + r];
                int G = in[dn + l], H = in[dn + x], I = in[dn + r];
                int e0 = E, e1 = E, e2 = E, e3 = E, e4 = E, e5 = E, e6 = E, e7 = E, e8 = E;
                if (B != H && D != F) {
                    if (D == B) e0 = D;
                    if (D == B && E != C || B == F && E != A) e1 = B;
                    if (B == F) e2 = F;
                    if (D == B && E != G || D == H && E != A) e3 = D;
                    if (B == F && E != I || H == F && E != C) e5 = F;
                    if (D == H) e6 = D;
                    if (D == H && E != I || H == F && E != G) e7 = H;
                    if (H == F) e8 = F;
                }
                int o = y * 3 * ow + x * 3;
                out[o] = e0;
                out[o + 1] = e1;
                out[o + 2] = e2;
                out[o + ow] = e3;
                out[o + ow + 1] = e4;
                out[o + ow + 2] = e5;
                out[o + 2 * ow] = e6;
                out[o + 2 * ow + 1] = e7;
                out[o + 2 * ow + 2] = e8;
            }
        }
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
        // whole pixels, or (smooth and the upscalers) softened to the screen's size
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, filter.equals("off")
                ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR : RenderingHints.VALUE_INTERPOLATION_BILINEAR);
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
        float cw = Math.min(w - 4 * u, tw + icon + pad * (noteTrophy ? 3 : 2)), ch = noteText == null ? 7.5f * u : 12 * u;
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
        g.drawString(noteTitle, tx, noteText == null ? y + ch / 2 + fb.getAscent() * 0.36f : y + ch * 0.46f);
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
