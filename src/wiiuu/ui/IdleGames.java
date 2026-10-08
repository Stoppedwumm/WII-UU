package wiiuu.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * What WII-UU does when nobody uses it for a while: it plays by itself on the TV, Tetris and Pong
 * in turns, and talks about it. Its Tetris player is good but not perfect (more careless as the
 * game speeds up, so games end); in Pong it plays the left paddle against the computer.
 *
 * <p>Every event (a line, a Tetris, a hole it shouldn't have made, a missed ball, a long rally,
 * the game ending) may get a remark, which the GamePad shows in a speech bubble next to the
 * WII-UU logo (and the TV, when no phone is connected). Drawn small (a retro picture MenuView
 * enlarges), so it costs next to nothing.
 */
final class IdleGames {

    /** What WII-UU is saying, and how it feels about it (happy, sad, smug, nervous, focus). */
    record Line(String text, String mood, int id) {}

    private final Random random = new Random();
    private Game game;
    private boolean tetrisNext = random.nextBoolean();
    private long last = System.currentTimeMillis();
    private Line line = new Line("", "focus", 0);
    private long lineAt;
    private long nextChatter;
    private final List<String> recent = new ArrayList<>();

    IdleGames() {
        nextGame();
    }

    String gameName() {
        return game instanceof Tetris ? "Tetris" : "Pong";
    }

    int score() {
        return game.score();
    }

    Line line() {
        return line;
    }

    /** Advances the game to now. */
    void step(long now) {
        long dt = Math.min(100, now - last);
        last = now;
        stepFlying(dt / 1000.0);
        if (cheat != null) {
            cheat.step(now);
            return;                                        // the game waits while it cheats
        }
        game.step(dt / 1000.0, now);
        if (game.wantsToCheat() && !game.over()) {
            cheat = new Cheat(now);
            say(pick(CHEAT_LOOK), "sneaky", true);
            return;
        }
        if (game.over() && now - game.overAt() > 3500) nextGame();
        if (now > nextChatter) {
            say(pick(CHATTER), "focus", false);
        }
    }

    private void nextGame() {
        game = tetrisNext ? new Tetris() : new Pong();
        tetrisNext = !tetrisNext;
        say(pick(game instanceof Tetris ? START_TETRIS : START_PONG), "smug", true);
    }

    /**
     * A remark: important ones (game over, a Tetris, a miss) come through unless one was just
     * said; small ones only after a pause.
     */
    private void say(String text, String mood, boolean important) {
        long now = System.currentTimeMillis();
        long since = now - lineAt;
        if (since < (important ? 1200 : 3500) && cheat == null) return;     // while cheating every word counts
        line = new Line(text, mood, line.id() + 1);
        lineAt = now;
        nextChatter = now + 22_000 + random.nextInt(18_000);
        recent.add(text);
        if (recent.size() > 8) recent.remove(0);
    }

    /** One of the lines, not one said lately. */
    private String pick(String... options) {
        for (int i = 0; i < 12; i++) {
            String s = options[random.nextInt(options.length)];
            if (!recent.contains(s)) return s;
        }
        return options[random.nextInt(options.length)];
    }

    /** What it says when someone takes over. */
    String goodbye() {
        return pick(WAKE);
    }

    // ---- cheating ---------------------------------------------------------------------------------

    /**
     * One thing to do on the TV while cheating: where (in the picture, given its size), how long
     * it takes, what it does then, and what the logo is up to meanwhile (erase, write, pull).
     */
    private record Job(java.util.function.BiFunction<Integer, Integer, float[]> where, double seconds,
                       java.util.function.DoubleConsumer progress, Runnable done, String kind, String remark) {}

    private Cheat cheat;
    /** -Dwiiuu.idleCheat=true: cheats early in every game (for trying it out) */
    private static final boolean CHEAT_SOON = Boolean.getBoolean("wiiuu.idleCheat");
    /** blocks it threw away: x, y (fractions of the picture), vx, vy, angle, spin, colour */
    private final List<float[]> flying = new ArrayList<>();

    /** The phone's view of a cheat: look (glancing around), away (on the TV), back, busted; null: none. */
    String cheatPhase() {
        if (cheat == null) return null;
        return switch (cheat.phase) {
            case LOOK -> "look";
            case BACK -> "back";
            case BUSTED, SULK -> "busted";
            default -> "away";
        };
    }

    /** The phone caught it: true if it was cheating on the TV just then. */
    boolean catchCheat() {
        if (cheat == null || !cheat.onTv() || cheat.phase == CheatPhase.BUSTED || cheat.phase == CheatPhase.SULK) return false;
        cheat.bust(System.currentTimeMillis());
        return true;
    }

    private enum CheatPhase { LOOK, TRAVEL, WORK, RETURN, BACK, BUSTED, SULK }

    /**
     * Losing badly, the logo cheats: it looks around on the GamePad, leaves it (so the phone shows
     * where it was), turns up on the TV, does its jobs, and goes back as if nothing happened. If the
     * phone catches it while it's on the TV, everything is put back, with a penalty.
     */
    private final class Cheat {
        CheatPhase phase = CheatPhase.LOOK;
        long at;
        final List<Job> jobs;
        int job = -1;
        float fx, fy, tx, ty;                              // the logo flies from f to t (fractions of the picture)
        float x = 0.5f, y = 1.3f;
        double progress;

        Cheat(long now) {
            at = now;
            jobs = game.cheatJobs();
        }

        boolean onTv() {
            return phase == CheatPhase.TRAVEL || phase == CheatPhase.WORK || phase == CheatPhase.RETURN;
        }

        double t(long now) {
            return (now - at) / 1000.0;
        }

        void step(long now) {
            double t = t(now);
            switch (phase) {
                case LOOK -> {
                    if (t > 2.6) {
                        say(pick(CHEAT_LEAVE), "sneaky", true);
                        nextJob(now);
                    }
                }
                case TRAVEL -> {
                    double e = ease(t / 0.9);
                    x = (float) (fx + (tx - fx) * e);
                    y = (float) (fy + (ty - fy) * e);
                    if (t > 0.9) {
                        phase = CheatPhase.WORK;
                        at = now;
                        Job j = jobs.get(job);
                        if (j.remark() != null) say(j.remark(), "sneaky", true);
                    }
                }
                case WORK -> {
                    Job j = jobs.get(job);
                    progress = Math.min(1, t / j.seconds());
                    if (j.progress() != null) j.progress().accept(progress);
                    if (t > j.seconds()) {
                        j.done().run();
                        nextJob(now);
                    }
                }
                case RETURN -> {
                    double e = ease(t / 1.0);
                    x = (float) (fx + (0.5f - fx) * e);
                    y = (float) (fy + (1.3f - fy) * e);
                    if (t > 1.0) {
                        phase = CheatPhase.BACK;
                        at = now;
                        say(pick(CHEAT_BACK), "smug", true);
                    }
                }
                case BACK -> {
                    if (t > 2.5) end(false);
                }
                case BUSTED -> {
                    if (t > 2.6) {
                        phase = CheatPhase.SULK;
                        at = now;
                        fx = x;
                        fy = y;
                        say(pick(CHEAT_SORRY), "sad", true);
                    }
                }
                case SULK -> {
                    double e = ease(t / 1.2);
                    x = (float) (fx + (0.5f - fx) * e);
                    y = (float) (fy + (1.3f - fy) * e);
                    if (t > 3.2) end(true);
                }
            }
        }

        void nextJob(long now) {
            job++;
            at = now;
            fx = x;
            fy = y;
            if (job >= jobs.size()) {
                phase = CheatPhase.RETURN;
                return;
            }
            phase = CheatPhase.TRAVEL;
            progress = 0;
            // the target in fractions of the picture (worked out at the size it's drawn: 640 wide)
            float[] p = jobs.get(job).where().apply(640, 360);
            tx = p[0] / 640f;
            ty = p[1] / 360f;
        }

        void bust(long now) {
            phase = CheatPhase.BUSTED;
            at = now;
            game.cheatUndo();
            line = new Line("BUSTED!", "sad", line.id() + 1);
            lineAt = now;
        }

        void end(boolean busted) {
            cheat = null;
            game.cheatEnded(busted);
        }
    }

    private static double ease(double t) {
        t = Math.max(0, Math.min(1, t));
        return t < 0.5 ? 2 * t * t : 1 - Math.pow(-2 * t + 2, 2) / 2;
    }

    private void fling(float x, float y, Color c) {
        flying.add(new float[]{x, y, (random.nextFloat() - 0.5f) * 0.9f, -0.6f - random.nextFloat() * 0.5f, 0,
                (random.nextFloat() - 0.5f) * 14, c.getRGB()});
    }

    private void stepFlying(double dt) {
        for (int i = flying.size() - 1; i >= 0; i--) {
            float[] f = flying.get(i);
            f[0] += f[2] * dt;
            f[1] += f[3] * dt;
            f[3] += 2.2f * dt;
            f[4] += f[5] * dt;
            if (f[1] > 1.3f) flying.remove(i);
        }
    }

    /** The logo, in person: a little white card with googly eyes (and an eraser, a pen or a block). */
    private void paintSneak(Graphics2D g, int w, int h, long now) {
        Cheat c = cheat;
        float s = h * 0.085f, cx = c.x * w, cy = c.y * h;
        boolean busted = c.phase == CheatPhase.BUSTED;
        Job j = c.job >= 0 && c.job < c.jobs.size() ? c.jobs.get(c.job) : null;
        if (c.phase == CheatPhase.WORK && j != null) {
            if (j.kind().equals("erase")) cx += (float) Math.sin(now / 45.0) * s * 0.5f;       // scrubbing
            if (j.kind().equals("write")) cy += (float) Math.abs(Math.sin(now / 90.0)) * -s * 0.2f;
        }
        if (busted) cx += (float) Math.sin(now / 30.0) * s * 0.12f;
        java.awt.geom.AffineTransform at = g.getTransform();
        g.translate(cx, cy);
        if (c.phase == CheatPhase.SULK) g.rotate(-0.15);
        float cw = s * 2.7f, ch = s;
        g.setColor(new Color(0, 0, 0, 90));
        g.fill(new RoundRectangle2D.Float(-cw / 2 + s * 0.08f, -ch / 2 + s * 0.12f, cw, ch, ch * 0.6f, ch * 0.6f));
        g.setColor(Color.WHITE);
        g.fill(new RoundRectangle2D.Float(-cw / 2, -ch / 2, cw, ch, ch * 0.6f, ch * 0.6f));
        g.setFont(MenuView.font(Font.BOLD, s * 0.55f));
        FontMetrics fm = g.getFontMetrics();
        float tx = -fm.stringWidth("WII-UU") / 2f, ty = fm.getAscent() * 0.38f;
        g.setColor(new Color(0x2B2F36));
        g.drawString("WII-", tx, ty);
        g.setColor(MenuView.ACCENT);
        g.drawString("UU", tx + fm.stringWidth("WII-"), ty);
        // googly eyes on top, looking where it's going (or around, nervously)
        float look = c.phase == CheatPhase.TRAVEL ? Math.signum(c.tx - c.fx) : (float) Math.sin(now / 300.0);
        for (int e = -1; e <= 1; e += 2) {
            float ex = e * s * 0.42f, ey = -ch / 2 - s * 0.12f, er = s * 0.26f;
            g.setColor(Color.WHITE);
            g.fill(new java.awt.geom.Ellipse2D.Float(ex - er, ey - er, er * 2, er * 2));
            g.setColor(new Color(0x2B2F36));
            g.setStroke(new BasicStroke(Math.max(1, s * 0.05f)));
            g.draw(new java.awt.geom.Ellipse2D.Float(ex - er, ey - er, er * 2, er * 2));
            float pr = er * 0.5f;
            g.fill(new java.awt.geom.Ellipse2D.Float(ex - pr + look * er * 0.4f, ey - pr + (busted ? -er * 0.3f : 0), pr * 2, pr * 2));
        }
        // what it holds
        if (c.phase == CheatPhase.WORK && j != null) {
            switch (j.kind()) {
                case "erase" -> {
                    g.setColor(new Color(0xF48FB1));
                    g.fillRoundRect((int) (cw / 2 - s * 0.2f), (int) (-s * 0.2f), (int) (s * 0.7f), (int) (s * 0.4f), 4, 4);
                }
                case "write" -> {
                    g.setColor(new Color(0xE53935));
                    g.setStroke(new BasicStroke(Math.max(2, s * 0.14f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.drawLine((int) (cw / 2 - s * 0.1f), 0, (int) (cw / 2 + s * 0.5f), (int) (-s * 0.5f));
                }
                default -> { }
            }
        }
        g.setTransform(at);
        // what it's muttering, right next to it
        if (!line.text().isEmpty() && System.currentTimeMillis() - lineAt < 5000) {
            g.setFont(MenuView.font(Font.BOLD, h * 0.036f));
            FontMetrics bf = g.getFontMetrics();
            String text = line.text();
            float bw = bf.stringWidth(text) + h * 0.04f, bh = bf.getHeight() + h * 0.02f;
            float bx = Math.max(4, Math.min(w - bw - 4, cx - bw / 2)), by = cy - s * 1.25f - bh;
            if (by < h * 0.1f) by = cy + s * 0.9f;
            g.setColor(Color.WHITE);
            g.fill(new RoundRectangle2D.Float(bx, by, bw, bh, bh * 0.6f, bh * 0.6f));
            g.setColor(new Color(0x202124));
            g.drawString(text, bx + h * 0.02f, by + h * 0.01f + bf.getAscent());
        }
        if (busted || c.phase == CheatPhase.SULK && c.t(now) < 1.2) {
            // the stamp
            java.awt.geom.AffineTransform a2 = g.getTransform();
            double pop = Math.min(1, c.t(now) / 0.18);
            g.translate(w / 2.0, h / 2.0);
            g.rotate(-0.18);
            g.scale(2.2 - 1.2 * pop, 2.2 - 1.2 * pop);
            g.setFont(MenuView.font(Font.BOLD, h * 0.16f));
            FontMetrics sf = g.getFontMetrics();
            String stamp = "BUSTED!";
            float sw = sf.stringWidth(stamp);
            g.setColor(new Color(229, 57, 53, 230));
            g.setStroke(new BasicStroke(h * 0.012f));
            g.drawRoundRect((int) (-sw / 2 - h * 0.03f), (int) (-sf.getAscent() * 0.85f), (int) (sw + h * 0.06f), (int) (sf.getAscent() * 1.15f), 20, 20);
            g.drawString(stamp, -sw / 2, sf.getAscent() * 0.2f);
            g.setTransform(a2);
        }
    }

    private void paintFlying(Graphics2D g, int w, int h) {
        float s = h * 0.04f;
        for (float[] f : flying) {
            java.awt.geom.AffineTransform at = g.getTransform();
            g.translate(f[0] * w, f[1] * h);
            g.rotate(f[4]);
            block(g, -s / 2, -s / 2, s, new Color((int) f[6], true));
            g.setTransform(at);
        }
    }

    void paint(Graphics2D g, int w, int h, boolean showBubble) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(0x0A0C10));
        g.fillRect(0, 0, w, h);
        game.paint(g, w, h);
        // who's playing, and how to stop it
        g.setFont(MenuView.font(Font.BOLD, h * 0.045f));
        g.setColor(new Color(255, 255, 255, 150));
        String who = "WII-UU is playing " + gameName();
        g.drawString(who, w * 0.03f, h * 0.075f);
        g.setFont(MenuView.font(Font.PLAIN, h * 0.035f));
        g.setColor(new Color(255, 255, 255, 90));
        String hint = "Press any button to take over";
        g.drawString(hint, w - w * 0.03f - g.getFontMetrics().stringWidth(hint), h * 0.075f);
        paintFlying(g, w, h);
        if (cheat != null && (cheat.onTv() || cheat.phase == CheatPhase.BUSTED || cheat.phase == CheatPhase.SULK)) {
            paintSneak(g, w, h, System.currentTimeMillis());
        } else if (showBubble && !line.text().isEmpty() && System.currentTimeMillis() - lineAt < 6000) bubble(g, w, h);
    }

    /** The remark on the TV (when no phone shows it). */
    private void bubble(Graphics2D g, int w, int h) {
        g.setFont(MenuView.font(Font.BOLD, h * 0.042f));
        FontMetrics fm = g.getFontMetrics();
        String text = line.text();
        float maxW = w * 0.42f;
        List<String> lines = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String word : text.split(" ")) {
            String t = cur.length() == 0 ? word : cur + " " + word;
            if (fm.stringWidth(t) > maxW && cur.length() > 0) {
                lines.add(cur.toString());
                cur = new StringBuilder(word);
            } else cur = new StringBuilder(t);
        }
        lines.add(cur.toString());
        float lh = fm.getHeight(), bw = 0;
        for (String l : lines) bw = Math.max(bw, fm.stringWidth(l));
        bw += h * 0.06f;
        float bh = lines.size() * lh + h * 0.04f, bx = w * 0.03f, by = h - bh - h * 0.05f;
        g.setColor(Color.WHITE);
        g.fill(new RoundRectangle2D.Float(bx, by, bw, bh, h * 0.04f, h * 0.04f));
        g.setColor(new Color(0x202124));
        float ty = by + h * 0.02f + fm.getAscent();
        for (String l : lines) {
            g.drawString(l, bx + h * 0.03f, ty);
            ty += lh;
        }
        g.setColor(MenuView.ACCENT);
        g.setFont(MenuView.font(Font.BOLD, h * 0.035f));
        g.drawString("WII-UU", bx, by - h * 0.012f);
    }

    // ---- the games -------------------------------------------------------------------------------

    private interface Game {
        void step(double dt, long now);

        void paint(Graphics2D g, int w, int h);

        boolean over();

        long overAt();

        int score();

        /** Losing badly enough to cheat (once a game). */
        boolean wantsToCheat();

        /** What it does to cheat (it may look at the game to decide). */
        List<Job> cheatJobs();

        /** Caught: everything back as it was (and a penalty). */
        void cheatUndo();

        void cheatEnded(boolean busted);
    }

    // ---- Tetris ----------------------------------------------------------------------------------

    private static final int COLS = 10, ROWS = 20;
    private static final int[][][] SHAPES = {
            {{0, 1}, {1, 1}, {2, 1}, {3, 1}},          // I
            {{1, 0}, {2, 0}, {1, 1}, {2, 1}},          // O
            {{1, 0}, {0, 1}, {1, 1}, {2, 1}},          // T
            {{1, 0}, {2, 0}, {0, 1}, {1, 1}},          // S
            {{0, 0}, {1, 0}, {1, 1}, {2, 1}},          // Z
            {{0, 0}, {0, 1}, {1, 1}, {2, 1}},          // J
            {{2, 0}, {0, 1}, {1, 1}, {2, 1}},          // L
    };
    private static final Color[] COLORS = {new Color(0x22D3EE), new Color(0xFACC15), new Color(0xA855F7),
            new Color(0x22C55E), new Color(0xEF4444), new Color(0x3B82F6), new Color(0xF97316)};

    /** All distinct rotations of a piece, each normalised to start at 0,0. */
    private static int[][][] rotations(int piece) {
        List<int[][]> out = new ArrayList<>();
        int[][] cur = SHAPES[piece];
        for (int r = 0; r < 4; r++) {
            int[][] norm = normalise(cur);
            boolean dup = false;
            for (int[][] o : out) if (same(o, norm)) dup = true;
            if (!dup) out.add(norm);
            int[][] next = new int[4][];
            for (int i = 0; i < 4; i++) next[i] = new int[]{-cur[i][1], cur[i][0]};
            cur = next;
        }
        return out.toArray(new int[0][][]);
    }

    private static int[][] normalise(int[][] cells) {
        int mx = Integer.MAX_VALUE, my = Integer.MAX_VALUE;
        for (int[] c : cells) {
            mx = Math.min(mx, c[0]);
            my = Math.min(my, c[1]);
        }
        int[][] out = new int[4][];
        for (int i = 0; i < 4; i++) out[i] = new int[]{cells[i][0] - mx, cells[i][1] - my};
        Arrays.sort(out, (a, b) -> a[1] != b[1] ? a[1] - b[1] : a[0] - b[0]);
        return out;
    }

    private static boolean same(int[][] a, int[][] b) {
        for (int i = 0; i < 4; i++) if (a[i][0] != b[i][0] || a[i][1] != b[i][1]) return false;
        return true;
    }

    private static final int[][][][] ROT = new int[7][][][];

    static {
        for (int p = 0; p < 7; p++) ROT[p] = rotations(p);
    }

    private final class Tetris implements Game {
        final int[][] board = new int[ROWS][COLS];
        int piece, next, rot, targetRot, targetX, x, lines, score, level = 1;
        double y, moveTimer;
        boolean mistake, done, warned, cheated, cheatWanted;
        int[][] boardBefore;
        int scoreBefore;
        long doneAt;
        int[] clearing = new int[0];
        double clearTimer;
        final List<Integer> bag = new ArrayList<>();

        Tetris() {
            next = draw();
            spawn();
        }

        int draw() {
            if (bag.isEmpty()) {
                for (int i = 0; i < 7; i++) bag.add(i);
                java.util.Collections.shuffle(bag, random);
            }
            return bag.remove(0);
        }

        void spawn() {
            piece = next;
            next = draw();
            rot = 0;
            x = 3;
            y = 0;
            if (collides(piece, rot, x, 0)) {
                done = true;
                doneAt = System.currentTimeMillis();
                say(pick(TETRIS_OVER), "sad", true);
                return;
            }
            plan();
        }

        /** Picks where the piece goes: the best place, or now and then (more as it speeds up) not quite. */
        void plan() {
            List<double[]> options = new ArrayList<>();                 // score, rot, x
            for (int r = 0; r < ROT[piece].length; r++) {
                int w = width(ROT[piece][r]);
                for (int px = 0; px + w <= COLS; px++) {
                    int py = dropY(piece, r, px, 0);
                    if (py < 0) continue;
                    options.add(new double[]{evaluate(piece, r, px, py), r, px});
                }
            }
            if (options.isEmpty()) {
                targetRot = 0;
                targetX = x;
                mistake = false;
                return;
            }
            options.sort((a, b) -> Double.compare(b[0], a[0]));
            double careless = Math.min(0.35, 0.05 + level * 0.025);
            int choice = 0;
            if (random.nextDouble() < careless) choice = Math.min(options.size() - 1, 1 + random.nextInt(Math.min(6, options.size())));
            mistake = choice > 0 && options.get(choice)[0] < options.get(0)[0] - 2;
            targetRot = (int) options.get(choice)[1];
            targetX = (int) options.get(choice)[2];
        }

        int width(int[][] cells) {
            int w = 0;
            for (int[] c : cells) w = Math.max(w, c[0] + 1);
            return w;
        }

        boolean collides(int p, int r, int px, int py) {
            for (int[] c : ROT[p][r]) {
                int cx = px + c[0], cy = py + c[1];
                if (cx < 0 || cx >= COLS || cy >= ROWS) return true;
                if (cy >= 0 && board[cy][cx] != 0) return true;
            }
            return false;
        }

        int dropY(int p, int r, int px, int from) {
            if (collides(p, r, px, from)) return -1;
            int py = from;
            while (!collides(p, r, px, py + 1)) py++;
            return py;
        }

        /** How good the board is with the piece here (lines good; height, holes, bumps bad). */
        double evaluate(int p, int r, int px, int py) {
            int[][] b = new int[ROWS][];
            for (int i = 0; i < ROWS; i++) b[i] = board[i].clone();
            for (int[] c : ROT[p][r]) if (py + c[1] >= 0) b[py + c[1]][px + c[0]] = 1;
            int full = 0;
            for (int[] row : b) {
                boolean f = true;
                for (int v : row) if (v == 0) f = false;
                if (f) full++;
            }
            int[] heights = new int[COLS];
            int holes = 0;
            for (int cx = 0; cx < COLS; cx++) {
                boolean seen = false;
                for (int cy = 0; cy < ROWS; cy++) {
                    if (b[cy][cx] != 0) {
                        if (!seen) heights[cx] = ROWS - cy;
                        seen = true;
                    } else if (seen) holes++;
                }
            }
            int agg = 0, bump = 0;
            for (int i = 0; i < COLS; i++) {
                agg += heights[i];
                if (i > 0) bump += Math.abs(heights[i] - heights[i - 1]);
            }
            return -0.51 * agg + 0.76 * full * (full == 4 ? 1.6 : 1) - 0.36 * holes - 0.18 * bump;
        }

        int holes() {
            int holes = 0;
            for (int cx = 0; cx < COLS; cx++) {
                boolean seen = false;
                for (int cy = 0; cy < ROWS; cy++) {
                    if (board[cy][cx] != 0) seen = true;
                    else if (seen) holes++;
                }
            }
            return holes;
        }

        int height() {
            for (int cy = 0; cy < ROWS; cy++) for (int v : board[cy]) if (v != 0) return ROWS - cy;
            return 0;
        }

        @Override
        public void step(double dt, long now) {
            if (done) return;
            if (clearing.length > 0) {
                clearTimer -= dt;
                if (clearTimer <= 0) finishClear();
                return;
            }
            moveTimer -= dt;
            // turn and slide to the planned place, a step at a time, like a player would
            if (moveTimer <= 0 && (rot != targetRot || x != targetX)) {
                moveTimer = Math.max(0.035, 0.09 - level * 0.004);
                if (rot != targetRot && !collides(piece, targetRot, x, (int) y)) rot = targetRot;
                else if (x != targetX) {
                    int nx = x + Integer.signum(targetX - x);
                    if (!collides(piece, rot, nx, (int) y)) x = nx;
                    else targetX = x;                            // blocked: drop it here
                }
            }
            boolean placed = rot == targetRot && x == targetX;
            double speed = placed ? 22 : 0.8 + level * 0.35;    // cells per second
            double ny = y + speed * dt;
            while ((int) y < (int) ny) {
                if (collides(piece, rot, x, (int) y + 1)) {
                    lock();
                    return;
                }
                y = (int) y + 1;
            }
            y = ny;
        }

        void lock() {
            int before = holes();
            for (int[] c : ROT[piece][rot]) {
                int cy = (int) y + c[1];
                if (cy >= 0) board[cy][x + c[0]] = piece + 1;
            }
            List<Integer> full = new ArrayList<>();
            for (int cy = 0; cy < ROWS; cy++) {
                boolean f = true;
                for (int v : board[cy]) if (v == 0) f = false;
                if (f) full.add(cy);
            }
            if (!full.isEmpty()) {
                clearing = full.stream().mapToInt(Integer::intValue).toArray();
                clearTimer = 0.3;
                int n = full.size();
                score += new int[]{0, 100, 300, 500, 800}[n] * level;
                if (n == 4) say(pick(TETRIS_FOUR), "happy", true);
                else if (n >= 2) say(pick(TETRIS_MULTI), "happy", false);
                else say(pick(TETRIS_ONE), "smug", false);
                return;
            }
            if (holes() > before && mistake) say(pick(TETRIS_HOLE), "sad", true);
            else if (holes() > before + 1) say(pick(TETRIS_HOLE), "sad", false);
            int ht = height();
            if (ht >= 15 && !cheated && random.nextDouble() < 0.75) cheatWanted = true;
            if (ht >= 14 && !warned) {
                warned = true;
                say(pick(TETRIS_DANGER), "nervous", true);
            } else if (ht < 10) warned = false;
            spawn();
        }

        void finishClear() {
            for (int row : clearing) {
                for (int cy = row; cy > 0; cy--) board[cy] = board[cy - 1].clone();
                board[0] = new int[COLS];
            }
            lines += clearing.length;
            clearing = new int[0];
            int lvl = 1 + lines / 10;
            if (lvl > level) {
                level = lvl;
                say(String.format(pick(TETRIS_LEVEL), level), "focus", false);
            }
            spawn();
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
            return score;
        }

        @Override
        public boolean wantsToCheat() {
            return (cheatWanted || CHEAT_SOON && lines >= 2) && !cheated;
        }

        /** Pulls the blocks off the top of the pile (the tallest columns first), then fixes the score. */
        @Override
        public List<Job> cheatJobs() {
            cheated = true;
            boardBefore = new int[ROWS][];
            for (int i = 0; i < ROWS; i++) boardBefore[i] = board[i].clone();
            scoreBefore = score;
            List<int[]> cells = new ArrayList<>();
            for (int cy = 0; cy < ROWS && cells.size() < 11; cy++)
                for (int cx = 0; cx < COLS && cells.size() < 11; cx++)
                    if (board[cy][cx] != 0) cells.add(new int[]{cx, cy});
            List<Job> jobs = new ArrayList<>();
            for (int i = 0; i < cells.size(); i++) {
                int cx = cells.get(i)[0], cy = cells.get(i)[1];
                jobs.add(new Job((w, h) -> {
                    float cell = h * 0.82f / ROWS, bx = (w - cell * COLS) / 2, by = h * 0.12f;
                    return new float[]{bx + (cx + 0.5f) * cell, by + (cy + 0.5f) * cell - h * 0.07f};
                }, 0.3, null, () -> {
                    int v = board[cy][cx];
                    if (v == 0) return;
                    board[cy][cx] = 0;
                    float cell = 360 * 0.82f / ROWS, bx = (640 - cell * COLS) / 2, by = 360 * 0.12f;
                    fling((bx + (cx + 0.5f) * cell) / 640f, (by + (cy + 0.5f) * cell) / 360f, COLORS[v - 1]);
                }, "pull", i == 0 ? pick(CHEAT_PULL) : null));
            }
            jobs.add(new Job((w, h) -> {
                float cell = h * 0.82f / ROWS, bx = (w - cell * COLS) / 2 + cell * COLS + cell * 1.5f, by = h * 0.12f;
                return new float[]{bx + cell * 9.5f, by + cell * 6.6f};        // next to the score, so it shows
            }, 2.0, p -> score = (int) Math.min(99_999_999, scoreBefore + (99_999_999L - scoreBefore) * p),
                    () -> score = 99_999_999, "write", pick(CHEAT_SCORE)));
            return jobs;
        }

        @Override
        public void cheatUndo() {
            for (int i = 0; i < ROWS; i++) board[i] = boardBefore[i].clone();
            score = scoreBefore;
            flying.clear();
        }

        @Override
        public void cheatEnded(boolean busted) {
            cheatWanted = false;
            if (!collides(piece, rot, x, (int) y)) plan();
        }

        @Override
        public void paint(Graphics2D g, int w, int h) {
            float cell = h * 0.82f / ROWS, bw = cell * COLS, bx = (w - bw) / 2, by = h * 0.12f;
            g.setColor(new Color(0x151922));
            g.fillRect((int) bx, (int) by, (int) bw, (int) (cell * ROWS));
            g.setColor(new Color(255, 255, 255, 14));
            for (int cx = 1; cx < COLS; cx++) g.fillRect((int) (bx + cx * cell), (int) by, 1, (int) (cell * ROWS));
            boolean flash = clearing.length > 0 && ((int) (clearTimer * 20)) % 2 == 0;
            for (int cy = 0; cy < ROWS; cy++) {
                boolean clears = false;
                for (int r : clearing) if (r == cy) clears = true;
                for (int cx = 0; cx < COLS; cx++) {
                    int v = board[cy][cx];
                    if (v == 0) continue;
                    block(g, bx + cx * cell, by + cy * cell, cell, clears && flash ? Color.WHITE : COLORS[v - 1]);
                }
            }
            if (!done && clearing.length == 0) {
                // where it will land, faintly, then the piece itself
                int gy = dropY(piece, rot, x, (int) y);
                if (gy >= 0) {
                    g.setColor(new Color(255, 255, 255, 30));
                    for (int[] c : ROT[piece][rot]) g.fillRect((int) (bx + (x + c[0]) * cell) + 1, (int) (by + (gy + c[1]) * cell) + 1, (int) cell - 2, (int) cell - 2);
                }
                for (int[] c : ROT[piece][rot]) {
                    float py = (float) (by + (y + c[1]) * cell);
                    if (py >= by - 0.5f) block(g, bx + (x + c[0]) * cell, py, cell, COLORS[piece]);
                }
            }
            g.setColor(new Color(255, 255, 255, 60));
            g.setStroke(new BasicStroke(Math.max(1, cell * 0.12f)));
            g.drawRect((int) bx - 1, (int) by - 1, (int) bw + 1, (int) (cell * ROWS) + 1);
            // next piece, score, lines, level
            float px = bx + bw + cell * 1.5f, ty = by + cell;
            g.setFont(MenuView.font(Font.BOLD, cell * 0.8f));
            g.setColor(new Color(255, 255, 255, 140));
            g.drawString("NEXT", px, ty);
            for (int[] c : ROT[next][0]) block(g, px + c[0] * cell * 0.8f, ty + cell * 0.6f + c[1] * cell * 0.8f, cell * 0.8f, COLORS[next]);
            String[][] stats = {{"SCORE", Integer.toString(score)}, {"LINES", Integer.toString(lines)}, {"LEVEL", Integer.toString(level)}};
            ty += cell * 4.5f;
            for (String[] st : stats) {
                g.setColor(new Color(255, 255, 255, 120));
                g.setFont(MenuView.font(Font.BOLD, cell * 0.7f));
                g.drawString(st[0], px, ty);
                g.setColor(Color.WHITE);
                g.setFont(MenuView.font(Font.BOLD, cell * 1.1f));
                g.drawString(st[1], px, ty + cell * 1.3f);
                ty += cell * 3;
            }
            if (done) gameOver(g, w, h, "GAME OVER");
        }
    }

    private static void block(Graphics2D g, float x, float y, float s, Color c) {
        g.setColor(c);
        g.fillRect((int) x + 1, (int) y + 1, Math.max(1, (int) s - 1), Math.max(1, (int) s - 1));
        g.setColor(new Color(255, 255, 255, 70));
        g.fillRect((int) x + 1, (int) y + 1, Math.max(1, (int) s - 1), Math.max(1, (int) (s * 0.18f)));
    }

    private static void gameOver(Graphics2D g, int w, int h, String text) {
        g.setColor(new Color(0, 0, 0, 150));
        g.fillRect(0, (int) (h * 0.42f), w, (int) (h * 0.16f));
        g.setColor(Color.WHITE);
        g.setFont(MenuView.font(Font.BOLD, h * 0.09f));
        FontMetrics fm = g.getFontMetrics();
        g.drawString(text, (w - fm.stringWidth(text)) / 2f, h * 0.535f);
    }

    // ---- Pong ------------------------------------------------------------------------------------

    private final class Pong implements Game {
        static final double PADDLE = 0.2, WIN = 5;
        double bx = 0.5, by = 0.5, vx, vy, left = 0.5, right = 0.5, leftAim, rightAim;
        int me, cpu, rally, meBefore;
        String meShown;                                    // the scoreboard while it's being "corrected"
        boolean cheated, cheatWanted;
        double serveIn = 1.2, speed;
        boolean done;
        long doneAt;

        Pong() {
            serve(random.nextBoolean() ? 1 : -1);
        }

        void serve(int dir) {
            bx = 0.5;
            by = 0.3 + random.nextDouble() * 0.4;
            speed = 0.5;
            double a = (random.nextDouble() - 0.5) * 0.9;
            vx = dir * Math.cos(a);
            vy = Math.sin(a);
            rally = 0;
            serveIn = 1.0;
            aim();
        }

        /** Where each paddle heads for: where the ball will cross its line, give or take its own mistake. */
        void aim() {
            double target = predict(vx < 0 ? 0.04 : 0.96);
            // WII-UU is a little better than the computer (it has to win now and then to brag about it)
            if (vx < 0) leftAim = target + (random.nextDouble() - 0.5) * (0.065 + rally * 0.012);
            else rightAim = target + (random.nextDouble() - 0.5) * (0.07 + rally * 0.013);
        }

        double predict(double atX) {
            double x = bx, y = by, dx = vx, dy = vy;
            for (int i = 0; i < 2000; i++) {
                x += dx * 0.005;
                y += dy * 0.005 * 1.78;
                if (y < 0) {
                    y = -y;
                    dy = -dy;
                }
                if (y > 1) {
                    y = 2 - y;
                    dy = -dy;
                }
                if (dx < 0 ? x <= atX : x >= atX) return y;
            }
            return y;
        }

        @Override
        public void step(double dt, long now) {
            if (done) return;
            // paddles move towards their aim at a human-ish speed (the waiting one drifts to the middle)
            left = toward(left, vx < 0 ? leftAim : 0.5 + (by - 0.5) * 0.3, 0.92 * dt);
            right = toward(right, vx > 0 ? rightAim : 0.5 + (by - 0.5) * 0.3, 0.92 * dt);
            if (serveIn > 0) {
                serveIn -= dt;
                return;
            }
            bx += vx * speed * dt * 1.0;
            by += vy * speed * dt * 1.78;                     // the field is 16:9
            if (by < 0) {
                by = -by;
                vy = -vy;
            }
            if (by > 1) {
                by = 2 - by;
                vy = -vy;
            }
            if (bx < 0.04 && vx < 0) hit(left, true);
            else if (bx > 0.96 && vx > 0) hit(right, false);
        }

        void hit(double paddle, boolean mine) {
            double off = (by - paddle) / (PADDLE / 2);
            if (Math.abs(off) <= 1.05) {
                vx = -vx;
                double a = off * 0.9;
                vx = Math.signum(vx) * Math.cos(a);
                vy = Math.sin(a);
                speed = Math.min(1.6, speed * 1.07);
                rally++;
                if (mine && Math.abs(off) > 0.8) say(pick(PONG_CLOSE), "nervous", false);
                else if (rally > 0 && rally % 9 == 0) say(pick(PONG_RALLY), "focus", false);
                aim();
                return;
            }
            // missed
            if (mine) {
                cpu++;
                say(pick(PONG_MISS), "sad", true);
                if (!cheated && cpu - me >= 2 && cpu >= 3 && cpu < WIN && random.nextDouble() < 0.8) cheatWanted = true;
            } else {
                me++;
                say(pick(PONG_POINT), "happy", true);
            }
            if (me >= WIN || cpu >= WIN) {
                done = true;
                doneAt = System.currentTimeMillis();
                say(pick(me > cpu ? PONG_WIN : PONG_LOSE), me > cpu ? "smug" : "sad", true);
                return;
            }
            serve(mine ? 1 : -1);
        }

        @Override
        public boolean wantsToCheat() {
            return (cheatWanted || CHEAT_SOON && me + cpu >= 1) && !cheated && serveIn > 0;   // between points, so nobody notices
        }

        /** Rubs its score off the scoreboard and writes a better one. */
        @Override
        public List<Job> cheatJobs() {
            cheated = true;
            meBefore = me;
            java.util.function.BiFunction<Integer, Integer, float[]> board = (w, h) -> new float[]{w * 0.38f, h * 0.12f + h * 0.09f};
            java.util.function.BiFunction<Integer, Integer, float[]> below = (w, h) -> new float[]{w * 0.38f, h * 0.12f + h * 0.3f};
            List<Job> jobs = new ArrayList<>();
            jobs.add(new Job(board, 1.8, p -> meShown = p < 0.85 ? Integer.toString(me) : "", () -> meShown = "", "erase", pick(CHEAT_ERASE)));
            jobs.add(new Job(below, 2.4, p -> meShown = "9999999".substring(0, (int) Math.ceil(p * 7)),
                    () -> {
                        meShown = null;
                        me = 9_999_999;
                    }, "write", pick(CHEAT_SCORE)));
            return jobs;
        }

        @Override
        public void cheatUndo() {
            me = meBefore;
            meShown = null;
            cpu++;                                          // and a penalty point
        }

        @Override
        public void cheatEnded(boolean busted) {
            cheatWanted = false;
            if (busted && cpu >= WIN || !busted && me >= WIN) {
                done = true;
                doneAt = System.currentTimeMillis();
                if (!busted) say(pick(PONG_CHEAT_WIN), "smug", true);
            }
        }

        double toward(double from, double to, double max) {
            to = Math.max(PADDLE / 2, Math.min(1 - PADDLE / 2, to));
            return from + Math.max(-max, Math.min(max, to - from));
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
            return me;
        }

        @Override
        public void paint(Graphics2D g, int w, int h) {
            float top = h * 0.12f, fh = h * 0.84f;
            g.setColor(new Color(255, 255, 255, 40));
            for (float y = top; y < top + fh; y += h * 0.04f) g.fillRect(w / 2 - 1, (int) y, 3, (int) (h * 0.02f));
            g.setColor(new Color(255, 255, 255, 70));
            g.fillRect(0, (int) top - 2, w, 2);
            g.fillRect(0, (int) (top + fh), w, 2);
            g.setFont(MenuView.font(Font.BOLD, h * 0.13f));
            FontMetrics fm = g.getFontMetrics();
            g.setColor(new Color(255, 255, 255, 110));
            String mine = meShown != null ? meShown : Integer.toString(me);
            g.drawString(mine, w * 0.38f - fm.stringWidth(mine) / 2f, top + h * 0.14f);
            g.drawString(Integer.toString(cpu), w * 0.62f - fm.stringWidth(Integer.toString(cpu)) / 2f, top + h * 0.14f);
            g.setFont(MenuView.font(Font.BOLD, h * 0.035f));
            g.setColor(MenuView.ACCENT);
            g.drawString("WII-UU", w * 0.38f - g.getFontMetrics().stringWidth("WII-UU") / 2f, top + h * 0.19f);
            g.setColor(new Color(255, 255, 255, 110));
            g.drawString("COMPUTER", w * 0.62f - g.getFontMetrics().stringWidth("COMPUTER") / 2f, top + h * 0.19f);
            float pw = w * 0.012f, ph = (float) (PADDLE * fh);
            g.setColor(MenuView.ACCENT);
            g.fillRect((int) (w * 0.03f), (int) (top + left * fh - ph / 2), (int) pw, (int) ph);
            g.setColor(Color.WHITE);
            g.fillRect((int) (w * 0.97f - pw), (int) (top + right * fh - ph / 2), (int) pw, (int) ph);
            if (serveIn <= 0 || ((int) (serveIn * 6)) % 2 == 0) {
                float s = h * 0.025f;
                g.fillRect((int) (bx * w - s / 2), (int) (top + by * fh - s / 2), (int) s, (int) s);
            }
            if (done) gameOver(g, w, h, me > cpu ? (me > 1000 ? "WII-UU WINS (LEGIT)" : "WII-UU WINS") : "COMPUTER WINS");
        }
    }

    // ---- what it says ----------------------------------------------------------------------------

    private static final String[] START_TETRIS = {
            "Nobody's playing? Then I'll play.",
            "Time for some Tetris. Watch and learn.",
            "Don't mind me, just don't interrupt me while I'm still finishing the level.",
            "Okay. Tetris. Let's go.",
    };
    private static final String[] START_PONG = {
            "Pong. Me against the computer. Easy.",
            "A little Pong while you're away.",
            "Rematch, computer. Right now.",
    };
    private static final String[] CHATTER = {
            "Don't mind me, just don't interrupt me while I'm still finishing the level.",
            "I'm in the zone.",
            "Shh... concentrating.",
            "Still there? Me too.",
            "This is my favourite part.",
            "Is anyone watching? Watch this.",
            "I could do this all day.",
    };
    private static final String[] TETRIS_ONE = {"Nice.", "Clean.", "One down.", "Easy."};
    private static final String[] TETRIS_MULTI = {"Two at once!", "Three! Smooth.", "Look at that.", "Yes! Combo!"};
    private static final String[] TETRIS_FOUR = {"TETRIS!!", "Four lines! Did you see that?!", "Yesss! A Tetris!"};
    private static final String[] TETRIS_HOLE = {"Damn!", "Oops. That's a hole.", "Wait, I didn't mean to put it there.",
            "Ugh, that piece hates me.", "That was... a choice.", "Nobody saw that."};
    private static final String[] TETRIS_DANGER = {"Uh oh...", "Okay, this is getting tight.", "Don't panic. Don't panic."};
    private static final String[] TETRIS_LEVEL = {"Level %d! It's getting faster.", "Level %d. I'm just warming up."};
    private static final String[] TETRIS_OVER = {"Noooo!", "That wasn't my fault. The pieces were rigged.",
            "Game over. I demand a rematch.", "Well. That happened."};
    private static final String[] PONG_POINT = {"Yes!", "Point for me!", "Too easy.", "Did you see that angle?"};
    private static final String[] PONG_MISS = {"Damn!", "No no no!", "Lag! That was lag!", "I let that one through on purpose."};
    private static final String[] PONG_RALLY = {"This rally is intense.", "Come on, come on...", "Neither of us is giving up."};
    private static final String[] PONG_CLOSE = {"Whoa, close one!", "Just in time!"};
    private static final String[] PONG_WIN = {"I win! Obviously.", "GG, computer."};
    private static final String[] PONG_LOSE = {"I lost to a computer. Wait, I AM a computer.", "Rematch. Now."};
    private static final String[] CHEAT_LOOK = {"Hmm. Is anyone watching?", "Nobody's looking, right?", "Psst. Is anyone there?"};
    private static final String[] CHEAT_LEAVE = {"Be right back.", "Just going to... check something.", "Don't look at the TV."};
    private static final String[] CHEAT_ERASE = {"This number is clearly wrong.", "Just fixing the scoreboard.", "Typo. Must be a typo."};
    private static final String[] CHEAT_SCORE = {"There. Much better.", "Totally fair.", "Nine nine nine nine..."};
    private static final String[] CHEAT_PULL = {"These blocks were in the wrong place anyway.", "Nobody needs this many blocks.", "Tidying up!"};
    private static final String[] CHEAT_BACK = {"What? I didn't do anything.", "I was here the whole time.", "Did something happen? No? Good."};
    private static final String[] CHEAT_SORRY = {"Okay okay, I'll play fair.", "You saw nothing!", "It was like that when I got there."};
    private static final String[] PONG_CHEAT_WIN = {"9,999,999 points. I win. Totally legit.", "Look at the scoreboard. Fair and square."};
    private static final String[] WAKE = {"Oh, you're back! I was totally winning.", "Oh! Hi! I wasn't playing. Promise.",
            "You can have it back. I was done anyway.", "Aww, I was about to beat my high score."};
}
