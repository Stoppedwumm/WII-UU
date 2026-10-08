package wiiuu.ui;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import wiiuu.input.PadButton;

/**
 * The boss fight from Settings > Developer: you, a little GamePad, against WII-UU, which has found
 * a gun. Move with the arrows / stick, shoot with A (or Space / Enter), quit with Esc or -.
 * Three phases as its health goes down: a pistol (aimed shots and bursts), two pistols (spreads),
 * and A BIGGER GUN (a sweeping minigun). It taunts you in a speech bubble the whole time, and has
 * its own music (the menu's pauses).
 *
 * <p>Played on a 1600 x 900 arena, scaled to the screen.
 */
final class BossFight {
    private static final float W = 1600, H = 900;
    private static final int BOSS_HP = 240, LIVES = 5;

    private enum State { INTRO, FIGHT, WON, LOST }

    private final Random random = new Random();
    private final Runnable onQuit;
    private State state = State.INTRO;
    private long stateAt = System.currentTimeMillis(), last = stateAt, started;

    // you
    private float px = W / 2, py = H * 0.82f;
    private int lives = LIVES;
    private long hitAt;
    private double fireCooldown;
    private final Set<PadButton> held = EnumSet.noneOf(PadButton.class);
    private boolean kLeft, kRight, kUp, kDown, kFire;

    // it
    private float bx = W / 2, by = H * 0.2f;
    private int hp = BOSS_HP;
    private int phase = 1;
    private double gunTimer = 1.5, sweep, phaseCalm;
    private long bossHitAt;
    private float gunAngle = (float) (Math.PI / 2);

    private final List<float[]> mine = new ArrayList<>(), theirs = new ArrayList<>();
    private final List<float[]> sparks = new ArrayList<>();      // x, y, vx, vy, life, colour

    // what it says
    private String line;
    private long lineAt, nextTaunt;

    private int[] music;

    BossFight(Runnable onQuit) {
        this.onQuit = onQuit;
        say("Oh, you want to fight me? Cool. I have a gun now.");
        nextTaunt = stateAt + 9000;
        startMusic();
    }

    // ---- input --------------------------------------------------------------------------------------

    /** A key from the keyboard (down or up). */
    void key(int code, boolean down) {
        switch (code) {
            case java.awt.event.KeyEvent.VK_LEFT, java.awt.event.KeyEvent.VK_A -> kLeft = down;
            case java.awt.event.KeyEvent.VK_RIGHT, java.awt.event.KeyEvent.VK_D -> kRight = down;
            case java.awt.event.KeyEvent.VK_UP, java.awt.event.KeyEvent.VK_W -> kUp = down;
            case java.awt.event.KeyEvent.VK_DOWN, java.awt.event.KeyEvent.VK_S -> kDown = down;
            case java.awt.event.KeyEvent.VK_SPACE, java.awt.event.KeyEvent.VK_ENTER, java.awt.event.KeyEvent.VK_J -> {
                kFire = down;
                if (down) confirm();
            }
            case java.awt.event.KeyEvent.VK_ESCAPE, java.awt.event.KeyEvent.VK_BACK_SPACE -> {
                if (down) quit();
            }
            default -> { }
        }
    }

    /** A button on a phone or controller. */
    void pad(PadButton b, boolean down) {
        if (down) held.add(b);
        else held.remove(b);
        if (!down) return;
        if (b == PadButton.A || b == PadButton.B && state != State.FIGHT) {
            if (b == PadButton.B && state != State.FIGHT) quit();
            else confirm();
        }
        if (b == PadButton.MINUS || b == PadButton.HOME) quit();
    }

    private void confirm() {
        if ((state == State.WON || state == State.LOST) && System.currentTimeMillis() - stateAt > 1200) restart();
    }

    private void quit() {
        stopMusic();
        onQuit.run();
    }

    private void restart() {
        state = State.INTRO;
        stateAt = System.currentTimeMillis();
        px = W / 2;
        py = H * 0.82f;
        lives = LIVES;
        hp = BOSS_HP;
        phase = 1;
        gunTimer = 1.5;
        mine.clear();
        theirs.clear();
        sparks.clear();
        say(random.nextBoolean() ? "Round two? Bold." : "Back for more? My gun missed you.");
    }

    private void say(String s) {
        line = s;
        lineAt = System.currentTimeMillis();
    }

    // ---- the game -----------------------------------------------------------------------------------

    void step() {
        long now = System.currentTimeMillis();
        double dt = Math.min(0.05, (now - last) / 1000.0);
        last = now;
        keepMusic();
        for (int i = sparks.size() - 1; i >= 0; i--) {
            float[] s = sparks.get(i);
            s[0] += s[2] * dt;
            s[1] += s[3] * dt;
            s[4] -= dt;
            if (s[4] <= 0) sparks.remove(i);
        }
        if (state == State.INTRO) {
            by = (float) (H * 0.2f - (1 - Math.min(1, (now - stateAt) / 1200.0)) * H * 0.4f);
            if (now - stateAt > 2600) {
                state = State.FIGHT;
                stateAt = started = now;
            }
            return;
        }
        if (state == State.WON) {
            by += (float) (dt * 260);                                // falls off the screen
            return;
        }
        if (state == State.LOST) return;
        // you move
        float dx = 0, dy = 0;
        if (kLeft || held.contains(PadButton.LEFT) || held.contains(PadButton.LS_LEFT)) dx -= 1;
        if (kRight || held.contains(PadButton.RIGHT) || held.contains(PadButton.LS_RIGHT)) dx += 1;
        if (kUp || held.contains(PadButton.UP) || held.contains(PadButton.LS_UP)) dy -= 1;
        if (kDown || held.contains(PadButton.DOWN) || held.contains(PadButton.LS_DOWN)) dy += 1;
        double len = Math.hypot(dx, dy);
        if (len > 0) {
            px += (float) (dx / len * 620 * dt);
            py += (float) (dy / len * 620 * dt);
        }
        px = Math.max(30, Math.min(W - 30, px));
        py = Math.max(H * 0.4f, Math.min(H - 30, py));
        // you shoot
        fireCooldown -= dt;
        if ((kFire || held.contains(PadButton.A) || held.contains(PadButton.B) || held.contains(PadButton.ZR)) && fireCooldown <= 0) {
            fireCooldown = 0.11;
            mine.add(new float[]{px - 10, py - 24, 0, -1100, 6});
            mine.add(new float[]{px + 10, py - 24, 0, -1100, 6});
        }
        // it moves: side to side, a little up and down, faster when angry
        double t = (now - started) / 1000.0;
        double speed = phase == 1 ? 0.6 : phase == 2 ? 0.85 : 1.1;
        bx = (float) (W / 2 + Math.sin(t * speed) * W * 0.34);
        by = (float) (H * 0.2f + Math.sin(t * speed * 1.7) * H * 0.05);
        gunAngle = (float) Math.atan2(py - (by + 20), px - (bx + 110));
        // it shoots
        phaseCalm -= dt;
        gunTimer -= dt;
        if (phaseCalm <= 0) shootAtYou(dt);
        // taunts
        if (now > nextTaunt) {
            say(TAUNTS[phase - 1][random.nextInt(TAUNTS[phase - 1].length)]);
            nextTaunt = now + 7000 + random.nextInt(4000);
        }
        // shots fly
        move(mine, dt);
        move(theirs, dt);
        // your shots hit it
        for (int i = mine.size() - 1; i >= 0; i--) {
            float[] s = mine.get(i);
            if (Math.abs(s[0] - bx) < 95 && Math.abs(s[1] - by) < 55) {
                mine.remove(i);
                if (phaseCalm > 0) continue;                          // shrugs it off while it changes guns
                hp--;
                bossHitAt = now;
                burst(s[0], s[1], new Color(0x29B6F6), 4);
                if (hp <= 0) {
                    win(now);
                    return;
                }
                int want = hp > BOSS_HP * 2 / 3 ? 1 : hp > BOSS_HP / 3 ? 2 : 3;
                if (want != phase) {
                    phase = want;
                    phaseCalm = 1.8;
                    theirs.clear();
                    say(phase == 2 ? "Okay. Two guns. Twice the fun." : "That's it. Time for A BIGGER GUN.");
                    nextTaunt = now + 6000;
                } else if (random.nextInt(25) == 0) say(random.nextBoolean() ? "Ow!" : "Hey! That's my face!");
            }
        }
        // its shots hit you (a tiny hitbox: the dot in the middle)
        if (now - hitAt > 1300) {
            for (int i = theirs.size() - 1; i >= 0; i--) {
                float[] s = theirs.get(i);
                if (Math.hypot(s[0] - px, s[1] - py) < s[4] + 7) {
                    theirs.remove(i);
                    lives--;
                    hitAt = now;
                    burst(px, py, new Color(0xFFCA28), 18);
                    Sfx.bump();
                    if (lives <= 0) {
                        state = State.LOST;
                        stateAt = now;
                        say(random.nextBoolean() ? "GG EZ." : "Skill issue.");
                        return;
                    }
                    if (random.nextBoolean()) say(random.nextBoolean() ? "Got you!" : "Boom. Headshot.");
                    break;
                }
            }
        }
    }

    private static final String[][] TAUNTS = {
            {"Where did I get a gun? Don't worry about it.", "Pew pew.", "Hold still!", "This is a family-friendly console, I'll have you know.",
                    "I'm aiming for the A button."},
            {"One gun for each hand. I have hands now.", "Reloading... just kidding. Infinite ammo.", "Dodge this! And this! And this!",
                    "I learned this from Tetris."},
            {"BRRRRRRT.", "This gun is bigger than me. Literally.", "You're still alive? Rude.", "MY MENU. MY RULES."}};

    private void shootAtYou(double dt) {
        float gx = bx + 110, gy = by + 20;
        switch (phase) {
            case 1 -> {                                              // aimed shots, every so often a burst of three
                if (gunTimer <= 0) {
                    boolean burst = random.nextInt(3) == 0;
                    for (int k = 0; k < (burst ? 3 : 1); k++) aimed(gx, gy, 430 + k * 60, 0);
                    gunTimer = burst ? 1.0 : 0.6;
                }
            }
            case 2 -> {                                              // both guns: spreads of five
                if (gunTimer <= 0) {
                    for (int side = -1; side <= 1; side += 2) {
                        float x = bx + side * 110;
                        for (int k = -2; k <= 2; k++) aimed(x, gy, 380, k * 0.22);
                    }
                    gunTimer = 1.05;
                }
            }
            default -> {                                             // the minigun: a sweeping stream, and big slow shells
                sweep += dt * 1.6;
                if (gunTimer <= 0) {
                    double a = Math.PI / 2 + Math.sin(sweep) * 1.0;
                    theirs.add(new float[]{bx, by + 60, (float) (Math.cos(a) * 520), (float) (Math.sin(a) * 520), 7});
                    gunTimer = 0.055;
                    if (random.nextInt(40) == 0) aimedBig(bx, by + 60);
                }
            }
        }
    }

    private void aimed(float x, float y, float speed, double offset) {
        double a = Math.atan2(py - y, px - x) + offset;
        theirs.add(new float[]{x, y, (float) (Math.cos(a) * speed), (float) (Math.sin(a) * speed), 8});
    }

    private void aimedBig(float x, float y) {
        double a = Math.atan2(py - y, px - x);
        theirs.add(new float[]{x, y, (float) (Math.cos(a) * 260), (float) (Math.sin(a) * 260), 18});
    }

    private static void move(List<float[]> shots, double dt) {
        for (int i = shots.size() - 1; i >= 0; i--) {
            float[] s = shots.get(i);
            s[0] += s[2] * dt;
            s[1] += s[3] * dt;
            if (s[0] < -40 || s[0] > W + 40 || s[1] < -40 || s[1] > H + 40) shots.remove(i);
        }
    }

    private void burst(float x, float y, Color c, int n) {
        for (int i = 0; i < n; i++) {
            double a = random.nextDouble() * Math.PI * 2, v = 80 + random.nextDouble() * 260;
            sparks.add(new float[]{x, y, (float) (Math.cos(a) * v), (float) (Math.sin(a) * v), 0.4f + random.nextFloat() * 0.3f, c.getRGB()});
        }
    }

    private void win(long now) {
        state = State.WON;
        stateAt = now;
        theirs.clear();
        burst(bx, by, new Color(0xFFFFFF), 60);
        burst(bx, by, MenuView.ACCENT, 40);
        Sfx.chime();
        say("Okay, okay! You win. I'm putting the gun away. ...Where do I put it?");
    }

    // ---- painting -----------------------------------------------------------------------------------

    void paint(Graphics2D g0, int w, int h) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, w, h);
        float scale = Math.min(w / W, h / H);
        g.translate((w - W * scale) / 2, (h - H * scale) / 2);
        g.scale(scale, scale);
        g.clipRect(0, 0, (int) W, (int) H);
        long now = System.currentTimeMillis();
        // the arena: the menu, gone dark and red
        g.setPaint(new GradientPaint(0, 0, new Color(0x2A0E14), 0, H, new Color(0x0E0A1A)));
        g.fillRect(0, 0, (int) W, (int) H);
        g.setColor(new Color(255, 255, 255, 14));
        for (int i = 0; i < 18; i++) {                               // ghosts of menu tiles
            float tx = 60 + (i % 6) * 260, ty = 110 + (i / 6) * 230;
            g.fill(new RoundRectangle2D.Float(tx, ty, 200, 170, 24, 24));
        }
        boolean shake = now - hitAt < 250;
        if (shake) g.translate(random.nextInt(9) - 4, random.nextInt(9) - 4);
        // sparks, shots
        for (float[] s : sparks) {
            g.setColor(new Color((int) s[5] & 0xFFFFFF | (int) (Math.max(0, Math.min(1, s[4] * 2)) * 255) << 24, true));
            g.fill(new Ellipse2D.Float(s[0] - 3, s[1] - 3, 6, 6));
        }
        g.setColor(new Color(0x4FC3F7));
        for (float[] s : mine) g.fill(new RoundRectangle2D.Float(s[0] - 3, s[1] - 10, 6, 20, 6, 6));
        for (float[] s : theirs) {
            g.setColor(new Color(0xFFCA28));
            g.fill(new Ellipse2D.Float(s[0] - s[4], s[1] - s[4], s[4] * 2, s[4] * 2));
            g.setColor(new Color(0xFF7043));
            g.fill(new Ellipse2D.Float(s[0] - s[4] * 0.55f, s[1] - s[4] * 0.55f, s[4] * 1.1f, s[4] * 1.1f));
        }
        boss(g, now);
        if (state != State.LOST) you(g, now);
        g.setTransform(new AffineTransform(g0.getTransform()));
        g.translate((w - W * scale) / 2, (h - H * scale) / 2);
        g.scale(scale, scale);
        hud(g, now);
        g.dispose();
    }

    private void boss(Graphics2D g, long now) {
        float s = 58, cw = s * 3.2f, ch = s * 1.25f;
        float x = bx, y = by;
        AffineTransform at = g.getTransform();
        if (state == State.WON) {
            g.rotate((now - stateAt) / 300.0, x, y);
        }
        boolean flash = now - bossHitAt < 70 || phaseCalm > 0 && (now / 100) % 2 == 0;
        // the gun(s), behind the hand
        if (phase == 2) gun(g, x - 110, y + 20, (float) Math.atan2(py - y, px - (x - 110)), 1f, now);
        gun(g, x + 110, y + 20, phase == 3 ? (float) (Math.PI / 2 + Math.sin(sweep) * 1.0) : gunAngle, phase == 3 ? 1.9f : 1f, now);
        g.setColor(new Color(0, 0, 0, 90));
        g.fill(new RoundRectangle2D.Float(x - cw / 2 + 4, y - ch / 2 + 8, cw, ch, ch * 0.6f, ch * 0.6f));
        g.setColor(flash ? new Color(0xFFCDD2) : Color.WHITE);
        g.fill(new RoundRectangle2D.Float(x - cw / 2, y - ch / 2, cw, ch, ch * 0.6f, ch * 0.6f));
        g.setFont(MenuView.font(Font.BOLD, s * 0.62f));
        FontMetrics fm = g.getFontMetrics();
        float lx = x - fm.stringWidth("WII-UU") / 2f, ly = y + fm.getAscent() * 0.38f;
        g.setColor(new Color(0x2B2F36));
        g.drawString("WII-", lx, ly);
        g.setColor(MenuView.ACCENT);
        g.drawString("UU", lx + fm.stringWidth("WII-"), ly);
        // angry googly eyes, on you
        for (int e = -1; e <= 1; e += 2) {
            float ex = x + e * s * 0.5f, ey = y - ch / 2 - s * 0.18f, er = s * 0.32f;
            g.setColor(Color.WHITE);
            g.fill(new Ellipse2D.Float(ex - er, ey - er, er * 2, er * 2));
            g.setColor(new Color(0x2B2F36));
            g.setStroke(new BasicStroke(3));
            g.draw(new Ellipse2D.Float(ex - er, ey - er, er * 2, er * 2));
            double a = Math.atan2(py - ey, px - ex);
            float pr = er * 0.95f;
            if (state == State.WON) g.drawLine((int) (ex - er * 0.6f), (int) (ey - er * 0.6f), (int) (ex + er * 0.6f), (int) (ey + er * 0.6f));
            else g.fill(new Ellipse2D.Float(ex - pr / 2 + (float) Math.cos(a) * er * 0.45f, ey - pr / 2 + (float) Math.sin(a) * er * 0.45f, pr, pr));
            g.setStroke(new BasicStroke(5, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawLine((int) (ex - e * er * 1.0f), (int) (ey - er * 1.5f), (int) (ex + e * er * 0.8f), (int) (ey - er * 0.9f));   // eyebrows
            g.setStroke(new BasicStroke(1));
        }
        g.setTransform(at);
        // its speech bubble
        if (line != null && now - lineAt < 4200) bubble(g, line, x, y - ch / 2 - s * 0.6f, now);
    }

    /** A cartoon pistol (or, at size 1.9, a much bigger gun with a barrel cluster). */
    private void gun(Graphics2D g, float x, float y, float angle, float size, long now) {
        AffineTransform at = g.getTransform();
        g.translate(x, y);
        g.rotate(angle);
        g.scale(size * 1.45, size * 1.45);
        g.setColor(new Color(0xB0BEC5));
        g.fill(new RoundRectangle2D.Float(-10, -13, 72, 20, 6, 6));                 // slide and barrel
        g.setColor(new Color(0x78909C));
        g.fillRect(-6, -9, 60, 3);                                                  // a groove along the slide
        g.setColor(new Color(0x546E7A));
        Path2D grip = new Path2D.Float();
        grip.moveTo(-8, 5);
        grip.lineTo(14, 5);
        grip.lineTo(8, 36);
        grip.lineTo(-14, 36);
        grip.closePath();
        g.fill(grip);
        g.setColor(new Color(0xB0BEC5));
        g.setStroke(new BasicStroke(3));
        g.drawArc(12, 2, 18, 16, 180, 180);                                         // trigger guard
        g.setStroke(new BasicStroke(1));
        g.setColor(new Color(0x263238));
        g.draw(new RoundRectangle2D.Float(-10, -13, 72, 20, 6, 6));
        if (size > 1.5f) {                                                          // the minigun barrels
            g.setColor(new Color(0x546E7A));
            for (int k = -1; k <= 1; k++) g.fillRect(56, k * 7 - 3, 40, 6);
        }
        // a hand (round, white, like its eyes)
        g.setColor(Color.WHITE);
        g.fill(new Ellipse2D.Float(-12, 0, 22, 22));
        g.setColor(new Color(0x2B2F36));
        g.draw(new Ellipse2D.Float(-12, 0, 22, 22));
        // muzzle flash right after a shot
        if (state == State.FIGHT && phaseCalm <= 0 && (phase == 3 || gunTimer > (phase == 1 ? 0.45 : 0.9))) {
            float tip = size > 1.5f ? 96 : 64;
            g.setColor(new Color(0xFFEB3B));
            Path2D flash = new Path2D.Float();
            flash.moveTo(tip, 0);
            flash.lineTo(tip + 18, -10);
            flash.lineTo(tip + 12, 0);
            flash.lineTo(tip + 18, 10);
            flash.closePath();
            g.fill(flash);
        }
        g.setTransform(at);
    }

    private void you(Graphics2D g, long now) {
        boolean blink = now - hitAt < 1300 && (now / 90) % 2 == 0;
        if (blink) return;
        float w = 64, h = 38;
        g.setColor(new Color(0, 0, 0, 90));
        g.fill(new RoundRectangle2D.Float(px - w / 2 + 3, py - h / 2 + 5, w, h, 16, 16));
        g.setColor(new Color(0xECEFF1));
        g.fill(new RoundRectangle2D.Float(px - w / 2, py - h / 2, w, h, 16, 16));
        g.setColor(new Color(0x263238));
        g.fill(new RoundRectangle2D.Float(px - 15, py - 11, 30, 22, 4, 4));
        g.setColor(MenuView.ACCENT);
        g.fill(new Ellipse2D.Float(px - 3, py - 3, 6, 6));                         // your hitbox
        g.setColor(new Color(0x546E7A));
        g.fill(new Ellipse2D.Float(px - 27, py - 6, 9, 9));
        g.fill(new Ellipse2D.Float(px + 18, py - 6, 9, 9));
    }

    private void bubble(Graphics2D g, String text, float cx, float bottom, long now) {
        g.setFont(MenuView.font(Font.BOLD, 26));
        FontMetrics fm = g.getFontMetrics();
        int shown = (int) Math.min(text.length(), (now - lineAt) / 25);
        String part = text.substring(0, shown);
        float tw = Math.min(fm.stringWidth(text), 640), pad = 16;
        List<String> lines = wrap(text, fm, 640);
        float bw = tw + pad * 2, bh = lines.size() * fm.getHeight() + pad * 1.2f;
        float x = Math.max(10, Math.min(W - bw - 10, cx - bw / 2)), y = Math.max(10, bottom - bh);
        g.setColor(Color.WHITE);
        g.fill(new RoundRectangle2D.Float(x, y, bw, bh, 22, 22));
        g.setColor(new Color(0x2B2F36));
        int done = 0;
        float ty = y + pad * 0.6f + fm.getAscent();
        for (String l : lines) {
            int n = Math.max(0, Math.min(l.length(), part.length() - done));
            if (n > 0) g.drawString(l.substring(0, n), x + pad, ty);
            done += l.length() + 1;
            ty += fm.getHeight();
        }
    }

    private static List<String> wrap(String text, FontMetrics fm, float width) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String test = line.length() == 0 ? word : line + " " + word;
            if (fm.stringWidth(test) > width && line.length() > 0) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else line = new StringBuilder(test);
        }
        if (line.length() > 0) lines.add(line.toString());
        return lines;
    }

    private void hud(Graphics2D g, long now) {
        // its health, on top
        float bw = W * 0.6f, bxx = (W - bw) / 2, byy = H - 54;
        g.setFont(MenuView.font(Font.BOLD, 22));
        g.setColor(Color.WHITE);
        String name = phase == 1 ? "WII-UU, armed" : phase == 2 ? "WII-UU, dual-wielding" : "WII-UU, with A BIGGER GUN";
        g.drawString(name, bxx, byy - 10);
        g.setColor(new Color(255, 255, 255, 40));
        g.fill(new RoundRectangle2D.Float(bxx, byy, bw, 16, 16, 16));
        g.setColor(phase == 3 ? new Color(0xE53935) : phase == 2 ? new Color(0xFB8C00) : new Color(0xFDD835));
        g.fill(new RoundRectangle2D.Float(bxx, byy, bw * Math.max(0, hp) / BOSS_HP, 16, 16, 16));
        // your hearts
        for (int i = 0; i < LIVES; i++) heart(g, 40 + i * 44, 44, i < lives);
        g.setFont(MenuView.font(Font.PLAIN, 18));
        g.setColor(new Color(255, 255, 255, 140));
        String help = "Move: arrows / stick    Shoot: A / Space (hold)    Quit: Esc / −";
        g.drawString(help, W - g.getFontMetrics().stringWidth(help) - 24, 40);
        if (state == State.INTRO) big(g, "BOSS FIGHT", "WII-UU has a gun.", now - stateAt);
        else if (state == State.WON) big(g, "YOU BEAT WII-UU", "in " + (stateAt - started) / 1000 + " s  ·  A: again   B: back to the menu", now - stateAt);
        else if (state == State.LOST) big(g, "WASTED", "A: try again   B: back to the menu", now - stateAt);
    }

    private static void big(Graphics2D g, String title, String sub, long since) {
        float k = (float) Math.min(1, since / 400.0);
        Composite c = g.getComposite();
        g.setComposite(AlphaComposite.SrcOver.derive(k));
        g.setFont(MenuView.font(Font.BOLD, 96));
        FontMetrics fm = g.getFontMetrics();
        g.setColor(new Color(0, 0, 0, 140));
        g.fillRect(0, (int) (H * 0.42f), (int) W, 190);
        g.setColor(title.equals("WASTED") ? new Color(0xE53935) : Color.WHITE);
        g.drawString(title, (W - fm.stringWidth(title)) / 2, H * 0.42f + 105);
        g.setFont(MenuView.font(Font.PLAIN, 30));
        fm = g.getFontMetrics();
        g.setColor(Color.WHITE);
        g.drawString(sub, (W - fm.stringWidth(sub)) / 2, H * 0.42f + 160);
        g.setComposite(c);
    }

    private static void heart(Graphics2D g, float x, float y, boolean full) {
        Path2D p = new Path2D.Float();
        float s = 16;
        p.moveTo(x, y + s * 0.4f);
        p.curveTo(x - s * 1.2f, y - s * 0.5f, x - s * 0.4f, y - s * 1.2f, x, y - s * 0.45f);
        p.curveTo(x + s * 0.4f, y - s * 1.2f, x + s * 1.2f, y - s * 0.5f, x, y + s * 0.4f);
        g.setColor(full ? new Color(0xE53935) : new Color(255, 255, 255, 50));
        g.fill(p);
    }

    // ---- the music: a driving loop, synthesized once ------------------------------------------------

    private static volatile short[] loop;

    private void startMusic() {
        if (!IdleGames.musicAllowed) return;
        Thread t = new Thread(() -> {
            if (loop == null) loop = renderLoop();
            keepMusic();
        }, "boss-music");
        t.setDaemon(true);
        t.start();
    }

    /** Starts the loop again each time it ends. */
    private synchronized void keepMusic() {
        short[] l = loop;
        if (l == null || !IdleGames.musicAllowed || stopped) return;
        if (music == null || music[0] >= l.length) {
            float v = Math.max(0.15f, MenuAudio.get().musicVolume()) * 1.1f;
            music = MenuAudio.get().playTrack(l, v);
            if (music == null) stopped = true;                        // no sound card
        }
    }

    private boolean stopped;

    synchronized void stopMusic() {
        stopped = true;
        if (music != null) MenuAudio.get().stopSpeech(music);
        music = null;
    }

    /** 8 bars at 150 BPM in A minor: kick, snare, hats, a galloping bass and a square-wave riff. */
    private static short[] renderLoop() {
        int rate = MenuAudio.RATE;
        double beat = 60.0 / 150;
        int n = (int) (beat * 32 * rate);
        float[] mix = new float[n];
        Random r = new Random(3);
        double[] roots = {55.0, 55.0, 43.65, 49.0, 55.0, 55.0, 41.2, 49.0};       // A A F G A A E G
        int[] riff = {0, 3, 7, 3, 12, 10, 7, 3};                                   // semitones over the root, an octave up
        for (int b = 0; b < 32; b++) {
            double t0 = b * beat;
            add(mix, t0, 0.3, rate, (t, i) -> Math.sin(2 * Math.PI * (45 + 100 * Math.exp(-t * 30)) * t) * Math.exp(-t * 9) * 0.9);
            if (b % 2 == 1) add(mix, t0, 0.15, rate, (t, i) -> (r.nextDouble() - 0.5) * Math.exp(-t * 22) * 0.7);
            for (int e = 0; e < 2; e++) add(mix, t0 + e * beat / 2 + beat / 4, 0.04, rate, (t, i) -> (r.nextDouble() - 0.5) * Math.exp(-t * 80) * 0.25);
            double root = roots[b / 4];
            for (int s = 0; s < 4; s++) {
                if (s == 1) continue;                                                  // da-da-dum gallop
                add(mix, t0 + s * beat / 4, beat / 4 * 0.9, rate,
                        (t, i) -> (((root * 2 * t) % 1) * 2 - 1) * 0.28 * Math.min(1, (beat / 4 * 0.9 - t) * 60));
            }
            double note = root * 4 * Math.pow(2, riff[b % 8] / 12.0);
            add(mix, t0, beat * 0.45, rate, (t, i) -> (((note * t) % 1) < 0.5 ? 0.12 : -0.12) * Math.min(1, (beat * 0.45 - t) * 40));
        }
        float peak = 0.01f;
        for (float v : mix) peak = Math.max(peak, Math.abs(v));
        short[] out = new short[n];
        for (int i = 0; i < n; i++) out[i] = (short) (mix[i] / peak * 0.85f * 32767);
        return out;
    }

    private interface Wave {
        double at(double t, int i);
    }

    private static void add(float[] mix, double from, double len, int rate, Wave w) {
        int s = (int) (from * rate), n = (int) (len * rate);
        for (int i = 0; i < n && s + i < mix.length; i++) mix[s + i] += (float) w.at(i / (double) rate, i);
    }
}
