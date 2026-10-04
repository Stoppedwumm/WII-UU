package wiiuu.ui;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Line2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import wiiuu.core.GameSystem;
import wiiuu.core.Systems;

/**
 * The first boot's opening, between the start-up animation and the setup guide: about eight
 * seconds of fast cuts on a 128 BPM beat (Tunes.introJingle), like a console's launch trailer:
 * a logo slam, the camera rushing through pictures of the real menu (the home screen, a console's
 * games, the GamePad screen, page two; taken while the start-up animation ran), consoles flashing
 * by in their colours, big words, a tunnel of tiles, and the logo again before the guide appears.
 * Any button skips it.
 *
 * <p>Cheap to draw: every cut is one picture copied with a transform, a few rectangles and a word;
 * a white flash hides each cut.
 */
final class FirstBootIntro {
    static final double BPM = 128, BEAT = 60.0 / BPM;

    /** What a cut shows. */
    private enum Kind { SLAM, SHOT, TILE, TUNNEL, FINAL }

    private record Cut(double start, double beats, Kind kind, int shot, String word, GameSystem system,
                       double zoomFrom, double zoomTo, double panX, double panY) {}

    private final List<Cut> cuts = new ArrayList<>();
    private final BufferedImage[] shots;
    private final long started = System.nanoTime();
    private final double length;
    private final Random random = new Random(3);
    private boolean skipped;

    /**
     * @param shots pictures of the menu: home, a console's games, the GamePad screen, page two
     *              (any may be null; those cuts become console flashes)
     */
    FirstBootIntro(BufferedImage[] shots) {
        this.shots = shots;
        double t = 0;
        t = add(t, 1, Kind.SLAM, -1, "WII-UU", null, 1, 1, 0, 0);
        t = add(t, 1, Kind.SHOT, 0, "PLAY", null, 1.0, 1.35, -0.06, 0.04);
        for (String id : new String[]{"nes", "snes", "n64", "wiiu"}) t = add(t, 0.5, Kind.TILE, -1, null, sys(id), 1, 1, 0, 0);
        t = add(t, 1, Kind.SHOT, 1, "EVERYTHING", null, 1.25, 1.05, 0.08, 0);
        t = add(t, 1, Kind.SHOT, 2, "YOUR PHONE IS THE GAMEPAD", null, 1.4, 1.0, 0, -0.03);
        for (String id : new String[]{"ps1", "switch", "gba", "dc"}) t = add(t, 0.5, Kind.TILE, -1, null, sys(id), 1, 1, 0, 0);
        t = add(t, 1, Kind.SHOT, 3, "YOUR GAMES", null, 1.05, 1.3, 0.05, -0.05);
        t = add(t, 2, Kind.TUNNEL, -1, "ONE CONSOLE.", null, 1, 1, 0, 0);
        t = add(t, 3, Kind.FINAL, -1, "WII-UU", null, 1, 1, 0, 0);
        length = t * BEAT;
    }

    private static GameSystem sys(String id) {
        return Systems.byId(id).orElse(Systems.ALL.get(0));
    }

    private double add(double at, double beats, Kind kind, int shot, String word, GameSystem system,
                       double zoomFrom, double zoomTo, double panX, double panY) {
        // a shot whose picture is missing becomes a console flash
        if (kind == Kind.SHOT && (shot >= shots.length || shots[shot] == null)) {
            kind = Kind.TILE;
            system = Systems.ALL.get((shot * 5 + 3) % Systems.ALL.size());
        }
        cuts.add(new Cut(at, beats, kind, shot, word, system, zoomFrom, zoomTo, panX, panY));
        return at + beats;
    }

    /** The beats where a cut starts (for the jingle's stabs). */
    double[] cutBeats() {
        double[] b = new double[cuts.size()];
        for (int i = 0; i < b.length; i++) b[i] = cuts.get(i).start();
        return b;
    }

    /** How many beats the cuts last before the final logo's impact. */
    int impactBeat() {
        return (int) Math.round(cuts.get(cuts.size() - 1).start());
    }

    double seconds() {
        return (System.nanoTime() - started) / 1e9;
    }

    boolean done() {
        return skipped || seconds() >= length;
    }

    void skip() {
        skipped = true;
    }

    // ---- painting ---------------------------------------------------------------------------------

    void paint(Graphics2D g0, int w, int h) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        double t = seconds() / BEAT;                     // in beats
        Cut cut = cuts.get(cuts.size() - 1);
        for (Cut c : cuts) if (t >= c.start() && t < c.start() + c.beats()) cut = c;
        double p = Math.max(0, Math.min(1, (t - cut.start()) / cut.beats()));     // 0..1 through this cut
        // a quick shake right on the cut
        double kick = Math.max(0, 1 - (t - cut.start()) * 6);
        AffineTransform base = g.getTransform();
        g.translate((random.nextDouble() - 0.5) * 18 * kick, (random.nextDouble() - 0.5) * 18 * kick);
        switch (cut.kind()) {
            case SLAM -> paintSlam(g, w, h, p, cut.word());
            case SHOT -> paintShot(g, w, h, p, cut);
            case TILE -> paintTile(g, w, h, p, cut.system());
            case TUNNEL -> paintTunnel(g, w, h, p, cut.word(), t);
            case FINAL -> paintFinal(g, w, h, p, t - cut.start());
        }
        g.setTransform(base);
        // the white flash that hides every cut
        double flash = Math.max(0, 1 - (t - cut.start()) * (cut.kind() == Kind.FINAL ? 2.5 : 7));
        if (flash > 0) {
            g.setColor(new Color(1f, 1f, 1f, (float) Math.min(1, flash * 0.85)));
            g.fillRect(0, 0, w, h);
        }
        // letterbox bars, for the trailer look (they open up in the last cut)
        double bars = cut.kind() == Kind.FINAL ? Math.max(0, 1 - p * 2.2) : 1;
        g.setColor(Color.BLACK);
        int bh = (int) (h * 0.075 * bars);
        g.fillRect(0, 0, w, bh);
        g.fillRect(0, h - bh, w, bh);
        g.dispose();
    }

    private void paintSlam(Graphics2D g, int w, int h, double p, String word) {
        g.setColor(new Color(0x0B0E13));
        g.fillRect(0, 0, w, h);
        double s = 1 + 1.4 * Math.pow(1 - Math.min(1, p * 3), 3);       // slams down from big
        bigWord(g, word, w / 2.0, h * 0.56, h * 0.22 * s, Color.WHITE, MenuView.ACCENT, 4);
    }

    private void paintShot(Graphics2D g, int w, int h, double p, Cut c) {
        BufferedImage img = shots[c.shot()];
        double ease = 1 - Math.pow(1 - p, 2);
        double zoom = c.zoomFrom() + (c.zoomTo() - c.zoomFrom()) * ease;
        double sx = w / (double) img.getWidth(), sy = h / (double) img.getHeight();
        AffineTransform at = g.getTransform();
        g.translate(w / 2.0 + c.panX() * w * (ease - 0.5), h / 2.0 + c.panY() * h * (ease - 0.5));
        g.scale(zoom * sx, zoom * sy);
        g.translate(-img.getWidth() / 2.0, -img.getHeight() / 2.0);
        g.drawImage(img, 0, 0, null);
        g.setTransform(at);
        // darken towards the bottom so the word reads, and speed streaks
        g.setPaint(new GradientPaint(0, h * 0.45f, new Color(0, 0, 0, 0), 0, h, new Color(0, 0, 0, 170)));
        g.fillRect(0, (int) (h * 0.45), w, h);
        streaks(g, w, h, p, new Color(255, 255, 255, 60));
        double in = Math.min(1, p * 4);
        bigWord(g, c.word(), w / 2.0 + (1 - in) * w * 0.08, h * 0.82, h * 0.1, Color.WHITE, MenuView.ACCENT, 2);
    }

    private void paintTile(Graphics2D g, int w, int h, double p, GameSystem s) {
        Color c = s.color();
        g.setPaint(new GradientPaint(0, 0, c.brighter(), 0, h, c.darker()));
        g.fillRect(0, 0, w, h);
        // a tile card rushing towards the camera
        double z = 0.7 + p * 0.5;
        float cw = (float) (w * 0.42 * z), ch = (float) (cw * 0.62);
        float cx = (w - cw) / 2, cy = (h - ch) / 2 - h * 0.03f;
        g.setColor(new Color(255, 255, 255, 34));
        g.fill(new RoundRectangle2D.Float(cx - 14, cy - 14, cw + 28, ch + 28, 48, 48));
        g.setColor(c);
        g.fill(new RoundRectangle2D.Float(cx, cy, cw, ch, 36, 36));
        g.setColor(new Color(255, 255, 255, 210));
        g.setFont(MenuView.font(Font.BOLD, ch * 0.09f));
        g.drawString(s.maker().toUpperCase(java.util.Locale.ROOT), cx + cw * 0.06f, cy + ch * 0.14f);
        String y = Integer.toString(s.year());
        g.drawString(y, cx + cw * 0.94f - g.getFontMetrics().stringWidth(y), cy + ch * 0.14f);
        bigWord(g, s.shortName(), w / 2.0, cy + ch * 0.66, ch * 0.42, Color.WHITE, new Color(0, 0, 0, 90), 3);
        streaks(g, w, h, p, new Color(255, 255, 255, 50));
    }

    private void paintTunnel(Graphics2D g, int w, int h, double p, String word, double beats) {
        g.setColor(new Color(0x0B0E13));
        g.fillRect(0, 0, w, h);
        // tiles flying out of the centre in every console's colour
        List<GameSystem> all = Systems.ALL;
        for (int i = 0; i < 36; i++) {
            double life = ((beats * 0.55 + i * 0.137) % 1.0);
            double angle = i * 2.399;                   // golden angle: spread evenly
            double dist = Math.pow(life, 2.2) * w * 0.75;
            double size = 10 + life * life * h * 0.2;
            float x = (float) (w / 2.0 + Math.cos(angle) * dist - size / 2), y = (float) (h / 2.0 + Math.sin(angle) * dist * 0.62 - size * 0.31);
            Color c = all.get(i % all.size()).color();
            g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), (int) (255 * Math.min(1, life * 3))));
            g.fill(new RoundRectangle2D.Float(x, y, (float) size, (float) (size * 0.62), (float) size * 0.18f, (float) size * 0.18f));
        }
        double in = Math.min(1, p * 3);
        bigWord(g, word, w / 2.0, h * 0.56, h * 0.13 * (0.85 + 0.15 * in), Color.WHITE, MenuView.ACCENT, 3);
    }

    private void paintFinal(Graphics2D g, int w, int h, double p, double beatsIn) {
        boolean darkBg = MenuView.dark;
        g.setPaint(new GradientPaint(0, 0, darkBg ? new Color(10, 14, 20) : new Color(236, 244, 250), 0, h,
                darkBg ? new Color(16, 32, 44) : new Color(214, 234, 246)));
        g.fillRect(0, 0, w, h);
        // rings rippling out from the impact
        for (int i = 0; i < 3; i++) {
            double r = (beatsIn * 0.9 - i * 0.25);
            if (r <= 0) continue;
            float rad = (float) (r * w * 0.45);
            int a = (int) Math.max(0, 120 * (1 - r));
            Color c = MenuView.ACCENT;
            g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), a));
            g.setStroke(new BasicStroke(6));
            g.draw(new java.awt.geom.Ellipse2D.Float(w / 2f - rad, h * 0.48f - rad, rad * 2, rad * 2));
        }
        g.setStroke(new BasicStroke(1));
        double s = 1 + 0.6 * Math.pow(1 - Math.min(1, beatsIn * 2), 3);
        bigWord(g, "WII-UU", w / 2.0, h * 0.54, h * 0.2 * s, MenuView.TEXT, MenuView.ACCENT, 0);
        if (beatsIn > 0.8) {
            float a = (float) Math.min(1, (beatsIn - 0.8) * 1.5);
            g.setComposite(AlphaComposite.SrcOver.derive(a));
            g.setColor(MenuView.TEXT_DIM);
            g.setFont(MenuView.font(Font.PLAIN, h * 0.045f));
            String line = "Let's set you up.";
            g.drawString(line, (w - g.getFontMetrics().stringWidth(line)) / 2f, h * 0.66f);
            g.setComposite(AlphaComposite.SrcOver);
        }
    }

    /** Speed streaks across the picture, faster at the start of the cut. */
    private void streaks(Graphics2D g, int w, int h, double p, Color c) {
        g.setColor(c);
        g.setStroke(new BasicStroke(2));
        Random r = new Random(7);
        for (int i = 0; i < 18; i++) {
            float y = r.nextFloat() * h, len = w * (0.1f + r.nextFloat() * 0.25f);
            float x = (float) (((r.nextFloat() + p * (1.5 + r.nextFloat())) % 1.4 - 0.2) * w);
            g.draw(new Line2D.Float(x, y, x + len, y));
        }
        g.setStroke(new BasicStroke(1));
    }

    /**
     * A big word centred on {@code cx} at baseline {@code y}: the "UU" or the second half in the
     * accent colour when it is the logo, with a hard offset shadow for punch.
     */
    private static void bigWord(Graphics2D g, String word, double cx, double y, double size, Color main, Color shadow, int offset) {
        Font f = MenuView.font(Font.BOLD, (float) size);
        FontMetrics fm = g.getFontMetrics(f);
        double maxW = g.getDeviceConfiguration() == null ? 2000 : g.getDeviceConfiguration().getBounds().width * 0.9;
        while (fm.stringWidth(word) > maxW && size > 12) {
            size *= 0.92;
            f = MenuView.font(Font.BOLD, (float) size);
            fm = g.getFontMetrics(f);
        }
        g.setFont(f);
        float x = (float) (cx - fm.stringWidth(word) / 2.0);
        if (offset > 0) {
            g.setColor(shadow);
            g.drawString(word, x + offset * 2, (float) y + offset * 2);
        }
        if (word.equals("WII-UU")) {
            g.setColor(main);
            g.drawString("WII-", x, (float) y);
            g.setColor(MenuView.ACCENT);
            g.drawString("UU", x + fm.stringWidth("WII-"), (float) y);
        } else {
            g.setColor(main);
            g.drawString(word, x, (float) y);
        }
    }
}
