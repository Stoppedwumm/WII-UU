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
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * An idle-time sketch: WII-UU produces a Future House banger in "FL Stoodio" (made up), builds it
 * track by track, then has the idea of putting the Super Mario Bros. theme in it. Build-up, drop,
 * export, upload... copyright claim. Red and blue lights, and the COPYRIGHT S.W.A.T. team bursts in
 * with a giant TAKEDOWN stamp and leaves with its MIDI keyboard. With its own music
 * ({@link StudioTrack}; the menu's pauses meanwhile), whose "Mario" lead is an original tune.
 *
 * <p>Worked out from the time since it started, like {@link IdleWeb}: see {@link #SCRIPT}.
 */
final class IdleStudio implements IdleGames.Game {

    private static final double END = 58, BEAT = 60.0 / 128;
    private final BiConsumer<String, String> say;
    private long started = System.currentTimeMillis();
    private final long created = started;
    private volatile short[] track;                     // its music, rendered in the background
    private int[] playing;                              // the mixer's handle while it plays
    private boolean waited;
    private final List<String> spoken = new ArrayList<>();
    private double t;
    private boolean done;
    private long doneAt;

    IdleStudio(BiConsumer<String, String> say) {
        this.say = say;
        if (IdleGames.musicAllowed) {
            Thread render = new Thread(() -> track = StudioTrack.render(), "idle-studio-music");
            render.setDaemon(true);
            render.setPriority(Thread.MIN_PRIORITY);
            render.start();
        } else waited = true;
    }

    private static final Object[][] SCRIPT = {
            {0.6, "Today I'm making a banger.", "happy"},
            {3.2, "Four on the floor. Kick, kick, kick, kick.", "focus"},
            {7.0, "Sidechain on everything. Obviously.", "smug"},
            {11.0, "Saw lead... vocal chops... it needs something.", "focus"},
            {14.5, "OH. I know.", "sneaky"},
            {18.0, "Super Mario Bros... but Future House.", "happy"},
            {22.0, "Build-up...", "focus"},
            {26.0, "...AND DROP!", "happy"},
            {33.4, "Uploaded! Okay, let's see how many views...", "happy"},
            {36.2, "Wait. What's that light?", "nervous"},
            {39.5, "It's a REMIX! Fair use! FAIR USE!", "nervous"},
            {45.5, "Not the MIDI keyboard!", "sad"},
            {51.0, "...Okay. New track. 100% original.", "focus"},
            {55.0, "Masterpiece.", "smug"},
    };

    @Override
    public void step(double dt, long now) {
        if (done) return;
        if (!waited) {
            // hold the first frame until the music is ready (a few seconds at most), so they stay in step
            short[] pcm = track;
            if (pcm == null && now - created < 5000) {
                started = now;
                return;
            }
            waited = true;
            started = now;
            if (pcm != null) {
                float volume = MenuAudio.get().musicVolume();
                playing = MenuAudio.get().playTrack(pcm, Math.max(0.15f, volume) * 1.1f);
            }
        }
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
            stop();
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

    @Override
    public boolean ownMusic() {
        return !done && IdleGames.musicAllowed;
    }

    @Override
    public void stop() {
        if (playing != null) MenuAudio.get().stopSpeech(playing);
        playing = null;
    }

    private static double clamp(double a) {
        return Math.max(0, Math.min(1, a));
    }

    private static double ease(double a) {
        a = clamp(a);
        return a < 0.5 ? 2 * a * a : 1 - Math.pow(-2 * a + 2, 2) / 2;
    }

    // ---- the timeline --------------------------------------------------------------------------------

    private static final double ROLL = 15.5, BUILD = 22, DROP = 26, EXPORT = 29.5, CLAIM = 35, SIREN = 35.8,
            BURST = 38.5, STAMP = 42, LEAVE = 46.5, GONE = 49.5;

    /** Whether the track is playing (the playhead runs, the speakers thump). */
    private boolean playing() {
        return t >= 2 && t < ROLL || t >= 19 && t < EXPORT;
    }

    /** How hard it thumps right now, 0..1: on the beat, faster in the build-up, huge after the drop. */
    private double thump() {
        if (!playing()) return 0;
        double beat = t >= BUILD && t < DROP ? BEAT / (t < 24 ? 2 : 4) : BEAT;
        double p = (t % beat) / beat;
        double k = Math.exp(-p * 6);
        return t >= DROP ? k : k * 0.5;
    }

    private boolean sirens() {
        return t >= SIREN && t < GONE + 0.5;
    }

    // ---- the room ------------------------------------------------------------------------------------

    @Override
    public void paint(Graphics2D g, int w, int h) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        double fade = t > END - 2.5 ? Math.max(0, (END - t) / 2.5) : 1;
        java.awt.Composite c = g.getComposite();
        g.setComposite(java.awt.AlphaComposite.SrcOver.derive((float) fade));
        g.setPaint(new GradientPaint(0, 0, new Color(0x2A1E4A), 0, h * 0.8f, new Color(0x1E1638)));
        g.fillRect(0, 0, w, h);
        // an LED strip that pulses with the beat
        float led = (float) thump();
        g.setColor(new Color(Color.HSBtoRGB((float) (t * 0.05 % 1), 0.7f, 0.5f + led * 0.5f)));
        g.fillRect(0, (int) (h * 0.06f), w, 3);
        g.setColor(new Color(0x15102A));
        g.fillRect(0, (int) (h * 0.84f), w, h);
        window(g, w, h);
        door(g, w, h);
        if (t >= DROP && t < EXPORT) {                              // the drop: the room flashes
            float f = (float) (thump() * 0.25);
            g.setColor(new Color(1f, 1f, 1f, f));
            g.fillRect(0, 0, w, h);
        }
        desk(g, w, h);
        guy(g, w, h);
        team(g, w, h);
        if (sirens()) {                                             // red, then blue, all over
            boolean red = ((int) (t * 4)) % 2 == 0;
            g.setColor(red ? new Color(255, 40, 40, 38) : new Color(40, 90, 255, 38));
            g.fillRect(0, 0, w, h);
        }
        g.setComposite(c);
    }

    private void window(Graphics2D g, int w, int h) {
        float x = w * 0.72f, y = h * 0.12f, ww = w * 0.12f, wh = h * 0.3f;
        g.setColor(sirens() ? (((int) (t * 4)) % 2 == 0 ? new Color(0xC62828) : new Color(0x1E4FD8)) : new Color(0x7FA7D9));
        g.fillRect((int) x, (int) y, (int) ww, (int) wh);
        g.setColor(new Color(0xD8D2E8));                            // blinds
        for (int i = 0; i < 8; i++) g.fillRect((int) x, (int) (y + i * wh / 8), (int) ww, (int) (wh / 16));
        g.setColor(new Color(0x3B2F5E));
        g.setStroke(new BasicStroke(3));
        g.drawRect((int) x, (int) y, (int) ww, (int) wh);
        g.setStroke(new BasicStroke(1));
    }

    /** The door on the right: shut, burst open, shut again. */
    private void door(Graphics2D g, int w, int h) {
        float x = w * 0.87f, y = h * 0.2f, dw = w * 0.11f, dh = h * 0.84f - y;
        g.setColor(new Color(0x120D22));
        g.fillRect((int) x, (int) y, (int) dw, (int) dh);
        double open = t < BURST ? 0 : t < BURST + 0.25 ? ease((t - BURST) / 0.25) : t < GONE ? 1 : 1 - ease((t - GONE) / 0.5);
        float shut = (float) (dw * (1 - open * 0.85));
        float shake = t >= BURST - 1.2 && t < BURST ? (float) Math.sin(t * 60) * 2 : 0;    // BANG BANG
        g.setColor(new Color(0x5D4037));
        g.fillRect((int) (x + shake), (int) y, (int) shut, (int) dh);
        g.setColor(new Color(0xFFC107));
        if (open < 0.5) g.fill(new Ellipse2D.Float(x + shut * 0.8f + shake, y + dh * 0.5f, 5, 5));
        if (t >= BURST - 1.2 && t < BURST) {
            g.setFont(MenuView.font(Font.BOLD, h * 0.05f));
            g.setColor(Color.WHITE);
            g.drawString(((int) (t * 3)) % 2 == 0 ? "BANG" : "BANG!", x - w * 0.09f, y - h * 0.02f);
        }
    }

    // ---- the desk ------------------------------------------------------------------------------------

    private static final float MX = 0.27f, MY = 0.12f, MW = 0.4f, MH = 0.5f;

    private void desk(Graphics2D g, int w, int h) {
        float dy = h * 0.72f;
        g.setColor(new Color(0x0F0B1C));
        g.fillRect((int) (w * 0.06f), (int) dy, (int) (w * 0.74f), (int) (h * 0.035f));
        g.fillRect((int) (w * 0.08f), (int) dy, (int) (w * 0.02f), (int) (h * 0.84f - dy));
        g.fillRect((int) (w * 0.76f), (int) dy, (int) (w * 0.02f), (int) (h * 0.84f - dy));
        // speakers, thumping (gone with the keyboard? no: they leave those)
        float boom = (float) thump();
        for (int side = 0; side < 2; side++) {
            float sw = w * 0.07f, sh = h * 0.24f;
            float sx = side == 0 ? w * MX - sw - w * 0.02f : w * (MX + MW) + w * 0.02f, sy = dy - sh;
            float grow = boom * (t >= DROP ? 0.12f : 0.05f);
            AffineTransform at = g.getTransform();
            g.translate(sx + sw / 2, sy + sh);
            g.scale(1 + grow, 1 + grow);
            g.translate(-sw / 2, -sh);
            g.setColor(new Color(0x111018));
            g.fill(new RoundRectangle2D.Float(0, 0, sw, sh, 8, 8));
            g.setColor(new Color(0x2C2B38));
            g.fill(new Ellipse2D.Float(sw * 0.15f, sh * 0.45f, sw * 0.7f, sw * 0.7f));
            g.fill(new Ellipse2D.Float(sw * 0.32f, sh * 0.12f, sw * 0.36f, sw * 0.36f));
            g.setColor(new Color(0xFFB300));
            g.draw(new Ellipse2D.Float(sw * 0.15f, sh * 0.45f, sw * 0.7f, sw * 0.7f));
            g.setTransform(at);
        }
        // the monitor
        float mx = w * MX, my = h * MY, mw = w * MW, mh = h * MH;
        g.setColor(new Color(0x08070C));
        g.fill(new RoundRectangle2D.Float(mx, my, mw, mh, 10, 10));
        g.fillRect((int) (mx + mw * 0.46f), (int) (my + mh), (int) (mw * 0.08f), (int) (dy - my - mh));
        g.fillRect((int) (mx + mw * 0.36f), (int) (dy - 4), (int) (mw * 0.28f), 4);
        screen(g, mx + 5, my + 5, mw - 10, mh - 12, h);
        // the MIDI keyboard, until it's taken away
        if (t < LEAVE - 0.6) keyboard(g, w * 0.36f, dy - h * 0.035f, w * 0.22f, h * 0.035f);
        // the TAKEDOWN stamp's mark
        if (t >= STAMP + 0.35) {
            AffineTransform at = g.getTransform();
            g.rotate(-0.12, mx + mw / 2, my + mh / 2);
            g.setColor(new Color(220, 30, 30, 220));
            g.setStroke(new BasicStroke(4));
            g.setFont(MenuView.font(Font.BOLD, h * 0.08f));
            FontMetrics fm = g.getFontMetrics();
            String word = "TAKEDOWN";
            float tw = fm.stringWidth(word);
            g.drawRect((int) (mx + (mw - tw) / 2 - 8), (int) (my + mh / 2 - fm.getAscent() * 0.75f), (int) (tw + 16), (int) (fm.getAscent() * 1.05f));
            g.drawString(word, mx + (mw - tw) / 2, my + mh / 2 + fm.getAscent() * 0.2f);
            g.setStroke(new BasicStroke(1));
            g.setTransform(at);
        }
    }

    private static void keyboard(Graphics2D g, float x, float y, float w, float h) {
        g.setColor(new Color(0x1C1A26));
        g.fillRect((int) x, (int) y, (int) w, (int) h);
        g.setColor(Color.WHITE);
        int keys = 21;
        for (int i = 0; i < keys; i++) g.fillRect((int) (x + 3 + i * (w - 6) / keys), (int) (y + h * 0.3f), (int) ((w - 6) / keys - 1), (int) (h * 0.65f));
        g.setColor(Color.BLACK);
        for (int i = 0; i < keys - 1; i++) {
            if (i % 7 == 2 || i % 7 == 6) continue;
            g.fillRect((int) (x + 3 + (i + 0.65f) * (w - 6) / keys), (int) (y + h * 0.3f), (int) ((w - 6) / keys * 0.6f), (int) (h * 0.4f));
        }
    }

    // ---- the screen ----------------------------------------------------------------------------------

    private static final String[] TRACKS = {"Kick", "Clap", "Hats", "Sub bass", "Saw lead", "Vocal chops", "Riser", "MARIO"};
    private static final Color[] TRACK_COLORS = {new Color(0xEF5350), new Color(0xFFA726), new Color(0xFFEE58), new Color(0x66BB6A),
            new Color(0x29B6F6), new Color(0xAB47BC), new Color(0x8D6E63), new Color(0xE53935)};
    /** When each track's first pattern shows up. */
    private static final double[] TRACK_AT = {2.2, 4.4, 5.6, 7.6, 9.4, 11.6, 21.0, 19.0};

    private void screen(Graphics2D g, float x, float y, float w, float h, int H) {
        java.awt.Shape clip = g.getClip();
        g.clipRect((int) x, (int) y, (int) w, (int) h);
        if (t >= STAMP + 0.35) {                                    // removed
            g.setColor(new Color(0x111111));
            g.fillRect((int) x, (int) y, (int) w, (int) h);
            g.setColor(new Color(0x9E9E9E));
            g.setFont(MenuView.font(Font.PLAIN, H * 0.026f));
            String s = t < 52.5 ? "This track has been removed." : "New project: 100% original.flp";
            g.drawString(s, x + w * 0.06f, y + h * 0.15f);
            if (t >= 53.5) {                                        // one lonely note
                g.setColor(new Color(0x2B2B33));
                g.fillRect((int) (x + w * 0.06f), (int) (y + h * 0.3f), (int) (w * 0.88f), (int) (h * 0.5f));
                g.setColor(new Color(0x66BB6A));
                g.fillRect((int) (x + w * 0.74f), (int) (y + h * 0.34f), (int) (w * 0.06f), (int) (h * 0.04f));
            }
            g.setClip(clip);
            return;
        }
        // the title bar
        float bar = h * 0.1f;
        g.setColor(new Color(0x3A3F44));
        g.fillRect((int) x, (int) y, (int) w, (int) bar);
        g.setColor(new Color(0xFF8F00));
        g.setFont(MenuView.font(Font.BOLD, H * 0.024f));
        g.drawString("FL Stoodio", x + w * 0.02f, y + bar * 0.66f);
        float after = x + w * 0.02f + g.getFontMetrics().stringWidth("FL Stoodio") + w * 0.03f;
        g.setColor(new Color(0xCFD8DC));
        g.setFont(MenuView.font(Font.PLAIN, H * 0.02f));
        g.drawString(t < ROLL ? "banger.flp" : "Mario House (FINAL v2 REAL).flp", after, y + bar * 0.66f);
        g.setColor(new Color(0x80E27E));
        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, Math.max(8, Math.round(H * 0.022f))));
        g.drawString("128.000", x + w * 0.84f, y + bar * 0.66f);
        float py = y + bar, ph = h - bar;
        if (t >= ROLL && t < 19) pianoRoll(g, x, py, w, ph, H);
        else playlist(g, x, py, w, ph, H);
        if (t >= EXPORT && t < CLAIM + 2.6) export(g, x, py, w, ph, H);
        if (t >= CLAIM) claim(g, x, py, w, ph, H);
        g.setClip(clip);
    }

    private void playlist(Graphics2D g, float x, float y, float w, float h, int H) {
        g.setColor(new Color(0x2B3035));
        g.fillRect((int) x, (int) y, (int) w, (int) h);
        float names = w * 0.2f, rowH = h / TRACKS.length, grid = w - names;
        int bars = 16;
        g.setFont(MenuView.font(Font.PLAIN, H * 0.018f));
        for (int i = 0; i < TRACKS.length; i++) {
            float ry = y + i * rowH;
            g.setColor(i % 2 == 0 ? new Color(0x33393F) : new Color(0x30363B));
            g.fillRect((int) (x + names), (int) ry, (int) grid, (int) rowH);
            if (t < TRACK_AT[i]) continue;
            g.setColor(new Color(0x44494F));
            g.fillRect((int) x, (int) ry, (int) names - 1, (int) rowH - 1);
            g.setColor(TRACK_COLORS[i]);
            g.drawString(i == 7 ? "MARIO (!!)" : TRACKS[i], x + 4, ry + rowH * 0.68f);
            // pattern blocks: where each track plays (the riser only in the build-up, Mario everywhere)
            int shown = (int) Math.min(bars, (t - TRACK_AT[i]) * 6 + 1);
            for (int b = 0; b < shown; b++) {
                boolean on = switch (i) {
                    case 1, 2 -> b >= 2;
                    case 4 -> b >= 4;
                    case 5 -> b % 4 >= 2;
                    case 6 -> b >= 6 && b < 8;
                    default -> true;
                };
                if (!on) continue;
                float bx = x + names + b * grid / bars;
                g.setColor(TRACK_COLORS[i].darker());
                g.fillRect((int) bx + 1, (int) ry + 2, (int) (grid / bars) - 2, (int) rowH - 4);
                g.setColor(TRACK_COLORS[i]);
                g.fillRect((int) bx + 1, (int) ry + 2, (int) (grid / bars) - 2, 3);
            }
        }
        if (playing()) {                                            // the playhead
            double bar = BEAT * 4;
            float px = x + names + (float) (((t / bar) % bars) / bars * grid);
            g.setColor(new Color(0x80E27E));
            g.drawLine((int) px, (int) y, (int) px, (int) (y + h));
        }
        if (t >= DROP && t < EXPORT) {                              // DROP!!
            g.setFont(MenuView.font(Font.BOLD, H * 0.07f));
            g.setColor(new Color(255, 255, 255, (int) (160 + 90 * thump())));
            FontMetrics fm = g.getFontMetrics();
            g.drawString("DROP!!", x + (w - fm.stringWidth("DROP!!")) / 2, y + h * 0.55f);
        }
    }

    /** The piano roll: super_mario_bros_theme.mid gets dragged in, and the notes fill it. */
    private void pianoRoll(Graphics2D g, float x, float y, float w, float h, int H) {
        g.setColor(new Color(0x23272B));
        g.fillRect((int) x, (int) y, (int) w, (int) h);
        float keys = w * 0.08f;
        int rows = 14;
        for (int r = 0; r < rows; r++) {
            boolean black = r % 7 == 1 || r % 7 == 3 || r % 7 == 5;
            g.setColor(black ? new Color(0x1B1B1B) : new Color(0xEEEEEE));
            g.fillRect((int) x, (int) (y + r * h / rows), (int) keys, (int) (h / rows) - 1);
            g.setColor(new Color(0x2C3136));
            g.drawLine((int) (x + keys), (int) (y + r * h / rows), (int) (x + w), (int) (y + r * h / rows));
        }
        // a bouncy little tune (drawn, never played)
        int[][] notes = {{0, 3}, {1, 3}, {3, 3}, {5, 5}, {6, 3}, {8, 1}, {10, 8}, {12, 5}, {14, 2}, {16, 6},
                {17, 4}, {19, 3}, {21, 7}, {22, 6}, {24, 5}, {26, 4}, {27, 3}, {29, 6}, {31, 8}};
        float step = (w - keys) / 34;
        int count = (int) Math.max(0, Math.min(notes.length, (t - ROLL - 1.2) * 10));
        for (int i = 0; i < count; i++) {
            float nx = x + keys + notes[i][0] * step, ny = y + (rows - 1 - notes[i][1] - 2) * h / rows;
            g.setColor(new Color(0xE53935));
            g.fill(new RoundRectangle2D.Float(nx, ny + 1, step * 1.6f, h / rows - 2, 4, 4));
        }
        // the file being dragged in
        if (t < ROLL + 1.6) {
            double k = ease((t - ROLL) / 1.2);
            float fx = (float) (x + w * 0.7f - k * w * 0.4f), fy = (float) (y + h * 0.15f + k * h * 0.2f);
            g.setColor(new Color(0xFAFAFA));
            g.fillRect((int) fx, (int) fy, (int) (w * 0.42f), (int) (h * 0.12f));
            g.setColor(new Color(0x212121));
            g.setFont(MenuView.font(Font.BOLD, H * 0.02f));
            g.drawString("super_mario_bros_theme.mid", fx + 6, fy + h * 0.08f);
        }
    }

    /** Export, then upload. */
    private void export(Graphics2D g, float x, float y, float w, float h, int H) {
        float dw = w * 0.78f, dh = h * 0.42f, dx = x + (w - dw) / 2, dy = y + (h - dh) / 2;
        g.setColor(new Color(0, 0, 0, 120));
        g.fillRect((int) x, (int) y, (int) w, (int) h);
        g.setColor(new Color(0x3A3F44));
        g.fill(new RoundRectangle2D.Float(dx, dy, dw, dh, 8, 8));
        boolean upload = t >= 32;
        g.setColor(Color.WHITE);
        g.setFont(MenuView.font(Font.BOLD, H * 0.024f));
        g.drawString(upload ? "Uploading to VidTube..." : "Exporting...", dx + dw * 0.05f, dy + dh * 0.28f);
        g.setColor(new Color(0xCFD8DC));
        g.setFont(MenuView.font(Font.PLAIN, H * 0.019f));
        g.drawString("WII-UU - Mario House (FINAL v2 REAL).wav", dx + dw * 0.05f, dy + dh * 0.5f);
        double k = upload ? clamp((t - 32) / 1.3) : clamp((t - EXPORT) / 2.3);
        g.setColor(new Color(0x23272B));
        g.fillRect((int) (dx + dw * 0.05f), (int) (dy + dh * 0.66f), (int) (dw * 0.9f), 8);
        g.setColor(upload ? new Color(0xE53935) : new Color(0xFF8F00));
        g.fillRect((int) (dx + dw * 0.05f), (int) (dy + dh * 0.66f), (int) (dw * 0.9f * k), 8);
        if (upload && k >= 1) {
            g.setColor(new Color(0x80E27E));
            g.drawString("Uploaded!  Views: 0", dx + dw * 0.05f, dy + dh * 0.9f);
        }
    }

    private void claim(Graphics2D g, float x, float y, float w, float h, int H) {
        double k = ease((t - CLAIM) / 0.4);
        float dw = w * 0.86f, dh = h * 0.5f, dx = x + (w - dw) / 2, dy = (float) (y + h - dh * k - h * 0.04f);
        g.setColor(new Color(0xFFF3F3));
        g.fill(new RoundRectangle2D.Float(dx, dy, dw, dh, 8, 8));
        g.setColor(new Color(0xD32F2F));
        g.fillRect((int) dx, (int) dy, (int) dw, (int) (dh * 0.22f));
        g.setColor(Color.WHITE);
        g.setFont(MenuView.font(Font.BOLD, H * 0.024f));
        g.drawString("! Copyright claim", dx + dw * 0.04f, dy + dh * 0.16f);
        g.setColor(new Color(0x212121));
        g.setFont(MenuView.font(Font.PLAIN, H * 0.02f));
        g.drawString("Matched: \"Super Mario Bros.\" (100%)", dx + dw * 0.04f, dy + dh * 0.42f);
        g.drawString("Also matched: \"every Future House track\" (98%)", dx + dw * 0.04f, dy + dh * 0.6f);
        g.setColor(new Color(0xD32F2F));
        g.setFont(MenuView.font(Font.BOLD, H * 0.02f));
        g.drawString("A team is on its way to you.", dx + dw * 0.04f, dy + dh * 0.8f);
    }

    // ---- WII-UU --------------------------------------------------------------------------------------

    private void guy(Graphics2D g, int w, int h) {
        float s = h * 0.06f, floor = h * 0.84f;
        boolean hands = t >= BURST + 0.3 && t < STAMP + 2;         // hands up
        double hop = t >= BURST && t < BURST + 0.5 ? Math.sin(Math.PI * (t - BURST) / 0.5) : 0;
        float cx = w * 0.1f, cy = (float) (h * 0.6f - hop * s * 1.3f);
        if (playing()) cy += (float) (thump() * s * (t >= DROP ? 0.35f : 0.18f));   // head-banging
        // the chair
        g.setColor(new Color(0x0F0B1C));
        g.fillRect((int) (cx - s * 0.7f), (int) (h * 0.6f + s * 0.6f), (int) (s * 1.4f), (int) (s * 0.25f));
        g.fillRect((int) (cx - s * 0.1f), (int) (h * 0.6f + s * 0.8f), (int) (s * 0.2f), (int) (floor - h * 0.6f - s * 0.8f));
        float cw = s * 2.6f, ch = s;
        if (hands) {
            g.setColor(new Color(0x2B2F36));
            float wave = (float) Math.sin(t * 10) * s * 0.08f;
            g.fill(new Ellipse2D.Float(cx - cw / 2 - s * 0.2f + wave, cy - ch * 1.6f, s * 0.32f, s * 0.32f));
            g.fill(new Ellipse2D.Float(cx + cw / 2 - s * 0.1f - wave, cy - ch * 1.6f, s * 0.32f, s * 0.32f));
        }
        g.setColor(new Color(0, 0, 0, 70));
        g.fill(new RoundRectangle2D.Float(cx - cw / 2 + 2, cy - ch / 2 + 3, cw, ch, ch * 0.6f, ch * 0.6f));
        g.setColor(Color.WHITE);
        g.fill(new RoundRectangle2D.Float(cx - cw / 2, cy - ch / 2, cw, ch, ch * 0.6f, ch * 0.6f));
        g.setFont(MenuView.font(Font.BOLD, s * 0.55f));
        FontMetrics fm = g.getFontMetrics();
        float lx = cx - fm.stringWidth("WII-UU") / 2f, ly = cy + fm.getAscent() * 0.38f;
        g.setColor(new Color(0x2B2F36));
        g.drawString("WII-", lx, ly);
        g.setColor(MenuView.ACCENT);
        g.drawString("UU", lx + fm.stringWidth("WII-"), ly);
        // googly eyes: on the screen, then on the door
        boolean scared = t >= SIREN && t < GONE + 1;
        float look = t >= SIREN && t < GONE ? 1 : 0.8f, up = t >= SIREN && t < GONE ? 0.2f : -0.3f;
        for (int e = -1; e <= 1; e += 2) {
            float ex = cx + e * s * 0.42f, ey = cy - ch / 2 - s * 0.12f, er = s * (scared ? 0.32f : 0.26f);
            g.setColor(Color.WHITE);
            g.fill(new Ellipse2D.Float(ex - er, ey - er, er * 2, er * 2));
            g.setColor(new Color(0x2B2F36));
            g.setStroke(new BasicStroke(Math.max(1, s * 0.05f)));
            g.draw(new Ellipse2D.Float(ex - er, ey - er, er * 2, er * 2));
            float pr = scared ? er * 0.55f : er;
            g.fill(new Ellipse2D.Float(ex - pr * 0.5f + look * er * 0.45f, ey - pr * 0.5f + up * er * 0.4f, pr, pr));
        }
        g.setStroke(new BasicStroke(1));
        // headphones (until the team turns up)
        if (t < BURST) {
            g.setColor(new Color(0x212121));
            g.setStroke(new BasicStroke(Math.max(2, s * 0.12f)));
            g.drawArc((int) (cx - cw * 0.45f), (int) (cy - ch * 1.25f), (int) (cw * 0.9f), (int) (ch * 1.6f), 10, 160);
            g.setStroke(new BasicStroke(1));
            g.setColor(new Color(0xE53935));
            g.fill(new RoundRectangle2D.Float(cx - cw * 0.52f, cy - ch * 0.45f, s * 0.3f, s * 0.6f, 4, 4));
            g.fill(new RoundRectangle2D.Float(cx + cw * 0.52f - s * 0.3f, cy - ch * 0.45f, s * 0.3f, s * 0.6f, 4, 4));
        }
        // note symbols while it's in the zone
        if (playing() && t >= DROP) {
            g.setFont(MenuView.font(Font.BOLD, s * 0.6f));
            for (int i = 0; i < 3; i++) {
                double p = (t * 0.7 + i / 3.0) % 1;
                g.setColor(new Color(255, 255, 255, (int) (220 * (1 - p))));
                g.drawString("♪", cx - cw * 0.3f + i * s * 0.8f, cy - ch - (float) (p * s * 2.2));
            }
        }
    }

    // ---- the COPYRIGHT S.W.A.T. team -----------------------------------------------------------------

    private void team(Graphics2D g, int w, int h) {
        if (t < BURST || t >= GONE + 0.3) return;
        float s = h * 0.07f, floor = h * 0.84f;
        for (int i = 0; i < 3; i++) {
            float door = w * 0.93f, spot = w * (0.46f + i * 0.13f);
            double in = ease((t - BURST - 0.15 - i * 0.25) / 0.9);
            double out = ease((t - LEAVE - i * 0.2) / 1.6);
            float x = (float) (door + (spot - door) * in + (door + w * 0.1f - spot) * out);
            boolean walking = in > 0 && in < 1 || out > 0 && out < 1;
            float bob = walking ? (float) Math.abs(Math.sin(t * 14 + i)) * s * 0.1f : 0;
            float top = floor - s * 3.2f - bob;
            // the leader brings the stamp down; the last one carries the keyboard out
            if (i == 0) stamp(g, x, top, s, w, h);
            trooper(g, x, top, s, floor, walking, i);
            if (i == 2 && t >= LEAVE - 0.6) keyboard(g, x - s * 1.4f, top + s * 1.4f, s * 2.8f, s * 0.4f);
        }
        if (t < BURST + 1.3) {                                      // "COPYRIGHT S.W.A.T.!"
            g.setFont(MenuView.font(Font.BOLD, h * 0.055f));
            String shout = "COPYRIGHT S.W.A.T.!";
            FontMetrics fm = g.getFontMetrics();
            float k = (float) ease((t - BURST) / 0.3);
            g.setColor(new Color(0, 0, 0, (int) (150 * k)));
            g.fillRect((int) (w * 0.4f - 10), (int) (h * 0.28f - fm.getAscent()), fm.stringWidth(shout) + 20, (int) (fm.getHeight() * 1.1f));
            g.setColor(new Color(1f, 0.85f, 0.2f, k));
            g.drawString(shout, w * 0.4f, h * 0.28f);
        }
    }

    private void trooper(Graphics2D g, float x, float top, float s, float floor, boolean walking, int i) {
        // legs
        g.setColor(new Color(0x10131C));
        float step = walking ? (float) Math.sin(t * 14 + i) * s * 0.25f : 0;
        g.fillRect((int) (x - s * 0.45f + step), (int) (top + s * 2.3f), (int) (s * 0.35f), (int) (floor - top - s * 2.3f));
        g.fillRect((int) (x + s * 0.1f - step), (int) (top + s * 2.3f), (int) (s * 0.35f), (int) (floor - top - s * 2.3f));
        // body and vest with the ©
        g.setColor(new Color(0x1A2236));
        g.fill(new RoundRectangle2D.Float(x - s * 0.6f, top + s * 0.85f, s * 1.2f, s * 1.6f, s * 0.4f, s * 0.4f));
        g.setColor(new Color(0x263252));
        g.fill(new RoundRectangle2D.Float(x - s * 0.5f, top + s * 1.0f, s, s * 1.1f, s * 0.2f, s * 0.2f));
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(Math.max(1.5f, s * 0.07f)));
        g.draw(new Ellipse2D.Float(x - s * 0.28f, top + s * 1.25f, s * 0.56f, s * 0.56f));
        g.setFont(MenuView.font(Font.BOLD, s * 0.4f));
        FontMetrics fm = g.getFontMetrics();
        g.drawString("C", x - fm.stringWidth("C") / 2f, top + s * 1.53f + fm.getAscent() * 0.35f);
        g.setStroke(new BasicStroke(1));
        // helmet and visor
        g.setColor(new Color(0x10131C));
        g.fill(new Ellipse2D.Float(x - s * 0.45f, top, s * 0.9f, s * 0.95f));
        g.setColor(new Color(0x5C6BC0));
        g.fill(new RoundRectangle2D.Float(x - s * 0.38f, top + s * 0.35f, s * 0.76f, s * 0.25f, s * 0.2f, s * 0.2f));
    }

    /** The giant rubber stamp: raised, then brought down on the monitor. */
    private void stamp(Graphics2D g, float x, float top, float s, int w, int h) {
        float tx = w * (MX + MW / 2), ty = h * (MY + MH / 2);
        double k;
        if (t < STAMP - 1) k = 0;                                   // carried
        else if (t < STAMP) k = ease(t - STAMP + 1) * 0.6;          // lifted toward the screen
        else if (t < STAMP + 0.35) k = 0.6 + 0.4 * ease((t - STAMP) / 0.35);
        else if (t < STAMP + 1.2) k = 1;
        else k = Math.max(0, 1 - (t - STAMP - 1.2) / 0.8);
        float hx = x - s * 0.7f, hy = top + s * 1.2f;
        float sx = (float) (hx + (tx - hx) * k), sy = (float) (hy + (ty - s * 0.6f - hy) * k);
        g.setColor(new Color(0x6D4C41));
        g.fill(new Ellipse2D.Float(sx - s * 0.25f, sy - s * 0.9f, s * 0.5f, s * 0.4f));
        g.fillRect((int) (sx - s * 0.1f), (int) (sy - s * 0.6f), (int) (s * 0.2f), (int) (s * 0.5f));
        g.setColor(new Color(0x37474F));
        g.fillRect((int) (sx - s * 0.6f), (int) (sy - s * 0.15f), (int) (s * 1.2f), (int) (s * 0.3f));
        g.setColor(new Color(0xC62828));
        g.fillRect((int) (sx - s * 0.6f), (int) (sy + s * 0.12f), (int) (s * 1.2f), (int) (s * 0.1f));
        if (t >= STAMP + 0.35 && t < STAMP + 0.8) {                 // KA-CHUNK
            g.setFont(MenuView.font(Font.BOLD, h * 0.05f));
            g.setColor(Color.WHITE);
            g.drawString("KA-CHUNK!", sx + s, sy - s);
        }
    }
}
