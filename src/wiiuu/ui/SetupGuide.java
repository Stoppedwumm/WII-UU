package wiiuu.ui;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import wiiuu.core.Config;
import wiiuu.net.QrCode;

/**
 * The setup guide WII-UU shows on its first start (and again from Settings > General), like a
 * console's first-time setup: welcome, look, sound, the phone as GamePad, where games go,
 * controllers, done. It is drawn over the menu and steered like the menu (arrows / stick, A to
 * choose, B back, L / R to skip a step), so it works on the TV with the phone, a controller,
 * keyboard or mouse; every choice applies straight away and is saved.
 *
 * <p>Animated throughout: the card pops in, steps slide past each other, the focus frame glides,
 * switches slide, the logo's letters drop in under a shine, a ring pulses while waiting for a
 * phone, and the last step draws a tick and throws confetti.
 */
final class SetupGuide {
    private enum Step { WELCOME, LOOK, SOUND, GAMEPAD, GAMES, CONTROLLERS, DONE }

    private static final long SLIDE_MS = 480, OPEN_MS = 520, CLOSE_MS = 420;

    /** One choosable row: a radio choice, a switch, or a button; its rectangle is set while painting. */
    private static final class Item {
        final String label, kind;           // kind: radio, switch, button
        final int row;
        Rectangle2D rect;
        float knob = -1;                    // switch knob 0..1, animated
        Item(String label, String kind, int row) {
            this.label = label;
            this.kind = kind;
            this.row = row;
        }
    }

    private final MenuView view;
    private final Config config;
    private final Runnable onClose;
    private final long opened = System.currentTimeMillis();
    private long closing;

    private Step step = Step.WELCOME;
    private Step leaving;                    // the step sliding out, during a change
    private int direction = 1;
    private long changed;
    private int focus;
    private final float[] frame = new float[4];
    private boolean frameSet;
    private long connectedAt;               // when the first phone connected on the GamePad step
    private final List<float[]> confetti = new ArrayList<>();   // x, y, vx, vy, spin, angle, colour, size
    private final Random random = new Random();

    SetupGuide(MenuView view, Config config, Runnable onClose) {
        this.view = view;
        this.config = config;
        this.onClose = onClose;
        Sfx.chime();
    }

    // ---- what each step offers ------------------------------------------------------------------

    private List<Item> items(Step s) {
        List<Item> out = new ArrayList<>();
        switch (s) {
            case LOOK -> {
                out.add(new Item("Follow the system", "radio", 0));
                out.add(new Item("Light", "radio", 1));
                out.add(new Item("Dark", "radio", 2));
            }
            case SOUND -> {
                out.add(new Item("Menu music", "switch", 0));
                out.add(new Item("Menu sounds", "switch", 1));
                out.add(new Item("Music visualizer", "switch", 2));
            }
            case GAMES -> out.add(new Item("RetroArch mode", "switch", 0));
            default -> { }
        }
        int nav = out.size();
        if (s == Step.WELCOME) {
            out.add(new Item("Set up later", "button", nav));
            out.add(new Item("Start", "button", nav));
        } else if (s == Step.DONE) {
            out.add(new Item("Back", "button", nav));
            out.add(new Item("Let's go!", "button", nav));
        } else {
            out.add(new Item("Back", "button", nav));
            out.add(new Item("Next", "button", nav));
        }
        return out;
    }

    private List<Item> current = items(Step.WELCOME);
    private List<Item> previous = List.of();

    {
        focus = current.size() - 1;          // "Start"
    }

    private boolean selected(Item it) {
        return switch (it.label) {
            case "Follow the system" -> theme().equals("auto");
            case "Light" -> theme().equals("light");
            case "Dark" -> theme().equals("dark");
            case "Menu music" -> config.getBool("ui.music", true);
            case "Menu sounds" -> config.getBool("ui.sounds", true);
            case "Music visualizer" -> config.getBool("ui.visualizer", true);
            case "RetroArch mode" -> config.getBool("retroarch.enabled", false);
            default -> false;
        };
    }

    private String theme() {
        return config.get("ui.theme", "auto").trim().toLowerCase(Locale.ROOT);
    }

    // ---- input ----------------------------------------------------------------------------------

    void navigate(int dx, int dy) {
        if (closing != 0) return;
        Item at = current.get(focus);
        int target = focus;
        if (dy != 0) {
            int row = at.row + dy;
            for (int i = 0; i < current.size(); i++) {
                if (current.get(i).row == row) {
                    target = i;
                    // into the button row: the forward button
                    if (current.get(i).kind.equals("button")) target = current.size() - 1;
                    break;
                }
            }
        } else if (dx != 0) {
            int i = focus + dx;
            if (i >= 0 && i < current.size() && current.get(i).row == at.row) target = i;
            else if (at.kind.equals("switch")) {                 // left / right also flip a switch
                activate();
                return;
            }
        }
        if (target == focus) Sfx.bump();
        else {
            focus = target;
            Sfx.move();
        }
    }

    void activate() {
        if (closing != 0) return;
        Item it = current.get(focus);
        switch (it.label) {
            case "Start", "Next", "Let's go!" -> next();
            case "Back" -> back();
            case "Set up later" -> finish(false);
            case "Follow the system" -> setTheme("auto");
            case "Light" -> setTheme("light");
            case "Dark" -> setTheme("dark");
            case "Menu music" -> {
                boolean on = !config.getBool("ui.music", true);
                save("ui.music", on);
                view.setMusicEnabled(on);
            }
            case "Menu sounds" -> {
                boolean on = !config.getBool("ui.sounds", true);
                save("ui.sounds", on);
                view.setSoundsEnabled(on);
            }
            case "Music visualizer" -> {
                save("ui.visualizer", !config.getBool("ui.visualizer", true));
                view.setMusicEnabled(config.getBool("ui.music", true));     // re-reads the visualizer setting
            }
            case "RetroArch mode" -> save("retroarch.enabled", !config.getBool("retroarch.enabled", false));
            default -> { }
        }
        if (!it.kind.equals("button")) Sfx.select();
    }

    void back() {
        if (closing != 0) return;
        if (step == Step.WELCOME) {
            Sfx.bump();
            return;
        }
        go(Step.values()[step.ordinal() - 1], -1);
        Sfx.back();
    }

    void next() {
        if (step == Step.DONE) {
            finish(true);
            return;
        }
        go(Step.values()[step.ordinal() + 1], 1);
        Sfx.select();
    }

    void page(int delta) {
        if (closing != 0) return;
        if (delta < 0) back();
        else if (step != Step.DONE) next();
    }

    /** A click: the item under it, if any. */
    void click(double x, double y) {
        for (int i = 0; i < current.size(); i++) {
            Rectangle2D r = current.get(i).rect;
            if (r != null && r.contains(x, y)) {
                focus = i;
                activate();
                return;
            }
        }
    }

    private void go(Step to, int dir) {
        leaving = step;
        previous = current;
        step = to;
        current = items(to);
        direction = dir;
        changed = System.currentTimeMillis();
        // the first choice if the step has any, else the forward button
        focus = current.get(0).kind.equals("button") ? current.size() - 1 : 0;
        if (to == Step.GAMEPAD) connectedAt = view.padCount() > 0 ? 1 : 0;
        if (to == Step.DONE) burst();
    }

    private void setTheme(String mode) {
        config.set("ui.theme", mode);
        config.save();
        view.setThemeMode(mode);
    }

    private void save(String key, boolean value) {
        config.set(key, Boolean.toString(value));
        config.save();
    }

    private void finish(boolean done) {
        config.set("ui.setupDone", "true");
        config.save();
        closing = System.currentTimeMillis();
        if (done) Sfx.chime();
        else Sfx.back();
    }

    // ---- animation ------------------------------------------------------------------------------

    /** Advances the animation; false once the guide has faded out (the menu then drops it). */
    boolean tick() {
        long now = System.currentTimeMillis();
        if (closing != 0 && now - closing > CLOSE_MS) {
            onClose.run();
            return false;
        }
        if (step == Step.GAMEPAD && connectedAt == 0 && view.padCount() > 0) {
            connectedAt = now;
            Sfx.chime();
        }
        for (int i = confetti.size() - 1; i >= 0; i--) {
            float[] p = confetti.get(i);
            p[0] += p[2];
            p[1] += p[3];
            p[3] += 0.18f;                          // gravity
            p[2] *= 0.99f;
            p[5] += p[4];
            if (p[1] > 1.2f * 2000) confetti.remove(i);
        }
        for (Item it : current) {
            if (!it.kind.equals("switch")) continue;
            float goal = selected(it) ? 1 : 0;
            it.knob = it.knob < 0 ? goal : it.knob + (goal - it.knob) * 0.3f;
        }
        return true;
    }

    private void burst() {
        confetti.clear();
        Color[] colors = {MenuView.ACCENT, new Color(0xFFC107), new Color(0x4CAF50), new Color(0xE91E63), new Color(0x7C4DFF)};
        for (int i = 0; i < 140; i++) {
            double a = -Math.PI / 2 + (random.nextDouble() - 0.5) * 2.2;
            float speed = 7 + random.nextFloat() * 11;
            confetti.add(new float[]{0, 0, (float) Math.cos(a) * speed, (float) Math.sin(a) * speed,
                    (random.nextFloat() - 0.5f) * 0.4f, random.nextFloat() * 6.28f,
                    colors[i % colors.length].getRGB(), 6 + random.nextFloat() * 8});
        }
    }

    private static double ease(double t) {
        t = Math.max(0, Math.min(1, t));
        return 1 - Math.pow(1 - t, 3);
    }

    private static double springy(double t) {          // overshoots a little, then settles
        t = Math.max(0, Math.min(1, t));
        return 1 - Math.pow(1 - t, 3) * Math.cos(t * Math.PI * 1.6);
    }

    // ---- painting -------------------------------------------------------------------------------

    void paint(Graphics2D g0, int w, int h) {
        Graphics2D g = (Graphics2D) g0.create();
        long now = System.currentTimeMillis();
        double open = ease((now - opened) / (double) OPEN_MS);
        double fade = closing != 0 ? 1 - ease((now - closing) / (double) CLOSE_MS) : open;
        Composite base = g.getComposite();
        g.setComposite(AlphaComposite.SrcOver.derive((float) fade));

        paintBackdrop(g, w, h, now);

        float cw = (float) Math.min(w * 0.74, 1120), ch = (float) Math.min(h * 0.76, 700);
        float cx = (w - cw) / 2, cy = (h - ch) / 2 - h * 0.02f;
        double pop = closing != 0 ? 1 - 0.04 * ease((now - closing) / (double) CLOSE_MS) : 0.9 + 0.1 * springy((now - opened) / (double) OPEN_MS);
        AffineTransform at = g.getTransform();
        g.translate(w / 2.0, h / 2.0);
        g.scale(pop, pop);
        g.translate(-w / 2.0, -h / 2.0);

        RoundRectangle2D card = new RoundRectangle2D.Float(cx, cy, cw, ch, 44, 44);
        for (int i = 6; i >= 1; i--) {                  // soft shadow
            g.setColor(new Color(0, 0, 0, MenuView.dark ? 22 : 12));
            g.fill(new RoundRectangle2D.Float(cx - i * 2, cy - i * 2 + 10, cw + i * 4, ch + i * 4, 44 + i * 4, 44 + i * 4));
        }
        g.setColor(MenuView.CARD);
        g.fill(card);
        java.awt.Shape clip = g.getClip();
        g.clip(card);

        double t = changed == 0 ? 1 : ease((now - changed) / (double) SLIDE_MS);
        if (t < 1 && leaving != null) {
            paintStep(g, leaving, previous, cx - (float) (direction * t * cw * 0.35), cy, cw, ch, (float) (1 - t), now, false);
        }
        paintStep(g, step, current, cx + (float) (direction * (1 - t) * cw * 0.35), cy, cw, ch, (float) t, now, true);
        g.setClip(clip);

        paintDots(g, cx, cy + ch + h * 0.035f, cw, now);
        paintConfetti(g, w / 2f, cy + ch * 0.32f);
        g.setTransform(at);
        g.setComposite(base);
        g.dispose();
    }

    private void paintBackdrop(Graphics2D g, int w, int h, long now) {
        Color a = MenuView.ACCENT;
        g.setPaint(new GradientPaint(0, 0, MenuView.dark ? new Color(10, 14, 20, 236) : new Color(236, 244, 250, 240),
                0, h, MenuView.dark ? new Color(16, 32, 44, 240) : new Color(214, 234, 246, 244)));
        g.fillRect(0, 0, w, h);
        // slow, soft bubbles drifting up
        double s = now / 1000.0;
        for (int i = 0; i < 14; i++) {
            double phase = i * 1.7;
            double x = (0.5 + 0.45 * Math.sin(s * 0.11 + phase)) * w;
            double y = h - ((s * (14 + i * 3) + i * 137) % (h + 300)) + 150;
            double r = 30 + (i * 37 % 90);
            g.setColor(new Color(a.getRed(), a.getGreen(), a.getBlue(), MenuView.dark ? 20 : 26));
            g.fill(new Ellipse2D.Double(x - r, y - r, r * 2, r * 2));
        }
    }

    private void paintDots(Graphics2D g, float cx, float y, float cw, long now) {
        int n = Step.values().length;
        float gap = 26, size = 10;
        float total = (n - 1) * gap + 22;
        float x = cx + (cw - total) / 2;
        for (int i = 0; i < n; i++) {
            boolean on = i == step.ordinal();
            float wdt = on ? 32 : size;
            g.setColor(on ? MenuView.ACCENT : i < step.ordinal() ? MenuView.TEXT_DIM : new Color(MenuView.TEXT_DIM.getRed(),
                    MenuView.TEXT_DIM.getGreen(), MenuView.TEXT_DIM.getBlue(), 90));
            g.fill(new RoundRectangle2D.Float(x, y, wdt, size, size, size));
            x += on ? gap + 22 : gap;
        }
    }

    private void paintStep(Graphics2D g, Step s, List<Item> items, float x, float y, float w, float h, float alpha,
                           long now, boolean live) {
        Composite c = g.getComposite();
        float a = ((AlphaComposite) c).getAlpha() * Math.max(0, Math.min(1, alpha));
        g.setComposite(AlphaComposite.SrcOver.derive(a));
        float pad = w * 0.07f;
        float left = x + pad, top = y + h * 0.13f, inner = w - pad * 2;
        String[] head = headline(s);
        if (s == Step.WELCOME) {
            paintLogo(g, x + w / 2, y + h * 0.36f, h * 0.2f, now);
            g.setColor(MenuView.TEXT_DIM);
            center(g, head[1], Font.PLAIN, h * 0.042f, x + w / 2, y + h * 0.56f, inner);
            center(g, "Use the arrows or stick to move, A to choose, B to go back.", Font.PLAIN, h * 0.03f,
                    x + w / 2, y + h * 0.63f, inner);
        } else if (s == Step.DONE) {
            paintTick(g, x + w / 2, y + h * 0.3f, h * 0.11f, now);
            g.setColor(MenuView.TEXT);
            center(g, head[0], Font.BOLD, h * 0.075f, x + w / 2, y + h * 0.53f, inner);
            g.setColor(MenuView.TEXT_DIM);
            center(g, head[1], Font.PLAIN, h * 0.036f, x + w / 2, y + h * 0.61f, inner);
            center(g, "This guide is in Settings (F1) > General whenever you want it again.", Font.PLAIN, h * 0.03f,
                    x + w / 2, y + h * 0.67f, inner);
        } else {
            g.setColor(MenuView.ACCENT);
            g.setFont(MenuView.font(Font.BOLD, h * 0.03f));
            g.drawString(("Step " + s.ordinal() + " of " + (Step.values().length - 2)).toUpperCase(Locale.ROOT), left, top);
            float textWidth = s == Step.GAMEPAD ? inner - h * 0.52f : inner;      // room for the QR code
            g.setColor(MenuView.TEXT);
            fit(g, head[0], Font.BOLD, h * 0.068f, textWidth);
            g.drawString(head[0], left, top + h * 0.085f);
            g.setColor(MenuView.TEXT_DIM);
            g.setFont(MenuView.font(Font.PLAIN, h * 0.034f));
            float ty = top + h * 0.15f;
            for (String line : wrap(g, head[1], textWidth)) {
                g.drawString(line, left, ty);
                ty += h * 0.048f;
            }
            if (s == Step.GAMEPAD) paintPairing(g, x + w - pad - h * 0.42f, top + h * 0.02f, h * 0.42f, left, ty, now);
            if (s == Step.GAMES) paintGames(g, left, ty + h * 0.01f, inner, h);
            if (s == Step.CONTROLLERS) paintPad(g, x + w / 2, ty + h * 0.13f, h * 0.2f, now);
        }
        paintItems(g, items, x, y, w, h, live, now);
        g.setComposite(c);
    }

    private String[] headline(Step s) {
        return switch (s) {
            case WELCOME -> new String[]{"Welcome", "Let's get your console ready. It only takes a minute."};
            case LOOK -> new String[]{"Pick your look", "Light, dark, or whatever your computer uses."};
            case SOUND -> new String[]{"Sound", "Music and sounds in the menu. Games keep their own sound."};
            case GAMEPAD -> new String[]{"Your phone is the GamePad",
                    "Join the same Wi-Fi as this computer, then scan the code with your phone's camera. "
                            + "It becomes a GamePad with buttons, sticks, and the TV picture."};
            case GAMES -> new String[]{"Where your games go",
                    "Copy your own game files into the folder of their console. WII-UU finds them by itself."};
            case CONTROLLERS -> new String[]{"Controllers",
                    "Phones and USB or Bluetooth controllers both work in the menu and in games. "
                            + "Press the GamePad's HOME button to close a game."};
            case DONE -> new String[]{"You're all set!", "Have fun playing."};
        };
    }

    private void paintItems(Graphics2D g, List<Item> items, float x, float y, float w, float h, boolean live, long now) {
        float pad = w * 0.07f;
        int optionRows = 0;
        for (Item it : items) if (!it.kind.equals("button")) optionRows = Math.max(optionRows, it.row + 1);
        float rowH = h * 0.085f, optTop = y + h * 0.58f - optionRows * rowH * 0.5f;
        if (step == Step.GAMES && live || items == previous && leaving == Step.GAMES) optTop = y + h * 0.66f;
        float bw = w * 0.2f, bh = h * 0.09f, by = y + h - bh - h * 0.07f;
        List<Item> buttons = new ArrayList<>();
        for (Item it : items) {
            if (it.kind.equals("button")) {
                buttons.add(it);
                continue;
            }
            float ry = optTop + it.row * rowH;
            it.rect = new Rectangle2D.Float(x + pad, ry, w - pad * 2, rowH * 0.86f);
            paintOption(g, it, (float) it.rect.getX(), ry, (float) it.rect.getWidth(), rowH * 0.86f);
        }
        float total = buttons.size() * bw + (buttons.size() - 1) * w * 0.03f;
        float bx = x + (w - total) / 2;
        if (buttons.size() == 2 && step != Step.WELCOME) bx = x + w - pad - total;
        for (Item it : buttons) {
            it.rect = new Rectangle2D.Float(bx, by, bw, bh);
            boolean primary = it == buttons.get(buttons.size() - 1);
            RoundRectangle2D r = new RoundRectangle2D.Float(bx, by, bw, bh, bh, bh);
            g.setColor(primary ? MenuView.ACCENT : MenuView.dark ? new Color(255, 255, 255, 26) : new Color(0, 0, 0, 16));
            g.fill(r);
            g.setColor(primary ? Color.WHITE : MenuView.TEXT);
            center(g, it.label, Font.BOLD, bh * 0.38f, bx + bw / 2, by + bh * 0.64f, bw * 0.9f);
            bx += bw + w * 0.03f;
        }
        if (!live || items.isEmpty() || closing != 0) return;
        // the focus frame glides to the focused item
        Rectangle2D f = items.get(Math.min(focus, items.size() - 1)).rect;
        if (f == null) return;
        float[] goal = {(float) f.getX() - 6, (float) f.getY() - 6, (float) f.getWidth() + 12, (float) f.getHeight() + 12};
        if (!frameSet) {
            System.arraycopy(goal, 0, frame, 0, 4);
            frameSet = true;
        }
        for (int i = 0; i < 4; i++) frame[i] += (goal[i] - frame[i]) * 0.3f;
        float pulse = (float) (0.5 + 0.5 * Math.sin(now / 260.0));
        g.setColor(MenuView.ACCENT);
        g.setStroke(new BasicStroke(3.5f + pulse * 1.5f));
        float r = Math.min(frame[3], h * 0.1f);
        g.draw(new RoundRectangle2D.Float(frame[0], frame[1], frame[2], frame[3], r, r));
        g.setStroke(new BasicStroke(1));
    }

    private void paintOption(Graphics2D g, Item it, float x, float y, float w, float h) {
        g.setColor(MenuView.dark ? new Color(255, 255, 255, 14) : new Color(0, 0, 0, 9));
        g.fill(new RoundRectangle2D.Float(x, y, w, h, h * 0.5f, h * 0.5f));
        g.setColor(MenuView.TEXT);
        g.setFont(MenuView.font(Font.PLAIN, h * 0.42f));
        g.drawString(it.label, x + h * 0.5f, y + h * 0.64f);
        boolean on = selected(it);
        if (it.kind.equals("radio")) {
            float d = h * 0.46f, rx = x + w - h * 0.5f - d, ry = y + (h - d) / 2;
            g.setColor(on ? MenuView.ACCENT : MenuView.TEXT_DIM);
            g.setStroke(new BasicStroke(2.5f));
            g.draw(new Ellipse2D.Float(rx, ry, d, d));
            if (on) g.fill(new Ellipse2D.Float(rx + d * 0.22f, ry + d * 0.22f, d * 0.56f, d * 0.56f));
            g.setStroke(new BasicStroke(1));
        } else {
            float sw = h * 1.2f, sh = h * 0.56f, sx = x + w - h * 0.5f - sw, sy = y + (h - sh) / 2;
            float k = it.knob < 0 ? (on ? 1 : 0) : it.knob;
            Color off = MenuView.dark ? new Color(90, 96, 104) : new Color(196, 202, 208);
            g.setColor(mix(off, MenuView.ACCENT, k));
            g.fill(new RoundRectangle2D.Float(sx, sy, sw, sh, sh, sh));
            g.setColor(Color.WHITE);
            float kd = sh * 0.8f;
            g.fill(new Ellipse2D.Float(sx + sh * 0.1f + k * (sw - kd - sh * 0.2f), sy + sh * 0.1f, kd, kd));
        }
    }

    private void paintLogo(Graphics2D g, float cx, float cy, float size, long now) {
        String word = "WII-UU";
        Font f = MenuView.font(Font.BOLD, size);
        FontMetrics fm = g.getFontMetrics(f);
        float x = cx - fm.stringWidth(word) / 2f;
        g.setFont(f);
        double since = (now - opened) / 1000.0;
        for (int i = 0; i < word.length(); i++) {
            String ch = word.substring(i, i + 1);
            double t = springy((since - 0.15 - i * 0.07) / 0.55);
            float drop = (float) ((1 - t) * -size * 0.9);
            Composite c = g.getComposite();
            g.setComposite(AlphaComposite.SrcOver.derive((float) (((AlphaComposite) c).getAlpha() * Math.max(0, Math.min(1, t * 1.4)))));
            g.setColor(i >= 4 ? MenuView.ACCENT : MenuView.TEXT);
            g.drawString(ch, x, cy + drop);
            g.setComposite(c);
            x += fm.stringWidth(ch);
        }
        // a shine sweeping across, every few seconds
        double sweep = ((since - 1.0) % 4.0) / 0.9;
        if (since > 1.0 && sweep < 1) {
            float sx = cx - fm.stringWidth(word) / 2f - size + (float) (sweep * (fm.stringWidth(word) + size * 2));
            java.awt.Shape clip = g.getClip();
            g.clip(f.createGlyphVector(g.getFontRenderContext(), word).getOutline(cx - fm.stringWidth(word) / 2f, cy));
            g.setPaint(new GradientPaint(sx, 0, new Color(255, 255, 255, 0), sx + size * 0.5f, 0, new Color(255, 255, 255, 170), true));
            g.fill(new Rectangle2D.Float(sx, cy - size, size, size * 1.3f));
            g.setClip(clip);
        }
    }

    private void paintPairing(Graphics2D g, float x, float y, float size, float textX, float textY, long now) {
        QrCode qr = view.pairingQr();
        g.setColor(Color.WHITE);
        g.fill(new RoundRectangle2D.Float(x - 10, y - 10, size + 20, size + 20, 24, 24));
        if (qr != null) {
            float cell = size / (qr.size + 2);
            g.setColor(new Color(0x202225));
            for (int yy = 0; yy < qr.size; yy++)
                for (int xx = 0; xx < qr.size; xx++)
                    if (qr.get(xx, yy)) g.fill(new Rectangle2D.Float(x + (xx + 1) * cell, y + (yy + 1) * cell, cell + 0.5f, cell + 0.5f));
        } else {
            g.setColor(new Color(0x80868B));
            center(g, "GamePad server is off", Font.PLAIN, size * 0.07f, x + size / 2, y + size / 2, size * 0.9f);
        }
        float h = size / 0.42f;
        g.setColor(MenuView.ACCENT);
        fit(g, view.pairingUrl(), Font.BOLD, h * 0.04f, x - textX - h * 0.04f);
        g.drawString(view.pairingUrl(), textX, textY + h * 0.02f);
        if (view.pairingNeedsCode() && !view.pairingCode().isEmpty()) {
            g.setColor(MenuView.TEXT_DIM);
            g.setFont(MenuView.font(Font.PLAIN, h * 0.03f));
            g.drawString("Pairing code", textX, textY + h * 0.09f);
            g.setColor(MenuView.TEXT);
            g.setFont(MenuView.font(Font.BOLD, h * 0.07f));
            g.drawString(String.join(" ", view.pairingCode().split("")), textX, textY + h * 0.17f);
        }
        // waiting: a ring pulsing out of a phone; connected: a tick popping in
        float px = textX + h * 0.03f, py = textY + h * 0.27f, ph = h * 0.075f;
        int pads = view.padCount();
        if (pads == 0) {
            double p = (now % 1600) / 1600.0;
            g.setColor(new Color(MenuView.ACCENT.getRed(), MenuView.ACCENT.getGreen(), MenuView.ACCENT.getBlue(), (int) (160 * (1 - p))));
            g.setStroke(new BasicStroke(3));
            float rr = (float) (ph * (0.6 + p * 0.9));
            g.draw(new Ellipse2D.Float(px - rr, py - rr, rr * 2, rr * 2));
            g.setStroke(new BasicStroke(1));
            g.setColor(MenuView.TEXT);
            g.fill(new RoundRectangle2D.Float(px - ph * 0.28f, py - ph * 0.45f, ph * 0.56f, ph * 0.9f, ph * 0.18f, ph * 0.18f));
            g.setColor(MenuView.TEXT_DIM);
            g.setFont(MenuView.font(Font.PLAIN, h * 0.032f));
            g.drawString("Waiting for a phone…", px + ph * 1.4f, py + h * 0.012f);
        } else {
            double t = springy((now - connectedAt) / 500.0);
            float r = (float) (ph * 0.7 * t);
            g.setColor(new Color(0x34A853));
            g.fill(new Ellipse2D.Float(px - r, py - r, r * 2, r * 2));
            g.setColor(Color.WHITE);
            g.setStroke(new BasicStroke(Math.max(2, r * 0.22f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            Path2D tick = new Path2D.Float();
            tick.moveTo(px - r * 0.42, py);
            tick.lineTo(px - r * 0.1, py + r * 0.32);
            tick.lineTo(px + r * 0.45, py - r * 0.3);
            g.draw(tick);
            g.setStroke(new BasicStroke(1));
            g.setColor(MenuView.TEXT);
            g.setFont(MenuView.font(Font.BOLD, h * 0.034f));
            g.drawString(pads == 1 ? "GamePad connected" : pads + " GamePads connected", px + ph * 1.4f, py + h * 0.012f);
        }
    }

    private void paintGames(Graphics2D g, float x, float y, float w, float h) {
        g.setColor(MenuView.dark ? new Color(255, 255, 255, 14) : new Color(0, 0, 0, 9));
        float bh = h * 0.16f;
        g.fill(new RoundRectangle2D.Float(x, y, w, bh, 20, 20));
        g.setColor(MenuView.TEXT);
        g.setFont(MenuView.font(Font.PLAIN, h * 0.032f));
        String base = config.romBase().toString();
        String sample = config.romBase().resolve("nes") + "   " + config.romBase().resolve("n64") + "   …";
        fit(g, base, Font.BOLD, h * 0.034f, w - 40);
        g.drawString(base, x + 20, y + bh * 0.4f);
        g.setColor(MenuView.TEXT_DIM);
        fit(g, sample, Font.PLAIN, h * 0.028f, w - 40);
        g.drawString(sample, x + 20, y + bh * 0.75f);
        int found = view.gameCount();
        g.setColor(found > 0 ? new Color(0x34A853) : MenuView.TEXT_DIM);
        g.setFont(MenuView.font(Font.PLAIN, h * 0.03f));
        g.drawString(found > 0 ? found + (found == 1 ? " game found so far" : " games found so far")
                : "RetroArch mode plays most consoles in RetroArch, if you use it.", x, y + bh + h * 0.05f);
    }

    /** A little controller whose buttons light up in turn. */
    private void paintPad(Graphics2D g, float cx, float cy, float size, long now) {
        float w = size * 2.2f, h = size;
        g.setColor(MenuView.dark ? new Color(255, 255, 255, 30) : new Color(0, 0, 0, 22));
        g.fill(new RoundRectangle2D.Float(cx - w / 2, cy - h / 2, w, h, h * 0.8f, h * 0.8f));
        int lit = (int) ((now / 450) % 6);
        float[][] dots = {{-0.62f, 0}, {-0.5f, -0.18f}, {-0.5f, 0.18f}, {0.5f, -0.2f}, {0.62f, 0}, {0.5f, 0.2f}};
        for (int i = 0; i < dots.length; i++) {
            float d = size * 0.16f;
            g.setColor(i == lit ? MenuView.ACCENT : MenuView.TEXT_DIM);
            g.fill(new Ellipse2D.Float(cx + dots[i][0] * w / 2 * 1.2f - d / 2, cy + dots[i][1] * h * 1.4f - d / 2, d, d));
        }
    }

    /** The finishing tick, drawn in a circle that grows. */
    private void paintTick(Graphics2D g, float cx, float cy, float r, long now) {
        double t = changed == 0 ? 1 : (now - changed) / 1000.0;
        double grow = springy(t / 0.5);
        float rr = (float) (r * grow);
        g.setColor(new Color(0x34A853));
        g.fill(new Ellipse2D.Float(cx - rr, cy - rr, rr * 2, rr * 2));
        double draw = Math.max(0, Math.min(1, (t - 0.3) / 0.45));
        if (draw <= 0) return;
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(r * 0.18f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        float[][] pts = {{-0.45f, 0.02f}, {-0.12f, 0.34f}, {0.48f, -0.3f}};
        double len1 = Math.hypot(pts[1][0] - pts[0][0], pts[1][1] - pts[0][1]), len2 = Math.hypot(pts[2][0] - pts[1][0], pts[2][1] - pts[1][1]);
        double along = draw * (len1 + len2);
        Path2D p = new Path2D.Float();
        p.moveTo(cx + pts[0][0] * r, cy + pts[0][1] * r);
        if (along <= len1) {
            double f = along / len1;
            p.lineTo(cx + (pts[0][0] + (pts[1][0] - pts[0][0]) * f) * r, cy + (pts[0][1] + (pts[1][1] - pts[0][1]) * f) * r);
        } else {
            p.lineTo(cx + pts[1][0] * r, cy + pts[1][1] * r);
            double f = (along - len1) / len2;
            p.lineTo(cx + (pts[1][0] + (pts[2][0] - pts[1][0]) * f) * r, cy + (pts[1][1] + (pts[2][1] - pts[1][1]) * f) * r);
        }
        g.draw(p);
        g.setStroke(new BasicStroke(1));
    }

    private void paintConfetti(Graphics2D g, float ox, float oy) {
        for (float[] p : confetti) {
            AffineTransform at = g.getTransform();
            g.translate(ox + p[0], oy + p[1]);
            g.rotate(p[5]);
            g.setColor(new Color((int) p[6], true));
            g.fill(new Rectangle2D.Float(-p[7] / 2, -p[7] / 4, p[7], p[7] / 2));
            g.setTransform(at);
        }
    }

    // ---- text -----------------------------------------------------------------------------------

    private static void center(Graphics2D g, String s, int style, float size, float cx, float baseline, float maxW) {
        fit(g, s, style, size, maxW);
        g.drawString(s, cx - g.getFontMetrics().stringWidth(s) / 2f, baseline);
    }

    private static void fit(Graphics2D g, String s, int style, float size, float maxW) {
        Font f = MenuView.font(style, size);
        while (size > 9 && g.getFontMetrics(f).stringWidth(s) > maxW) f = MenuView.font(style, size *= 0.94f);
        g.setFont(f);
    }

    private static List<String> wrap(Graphics2D g, String text, float width) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String test = line.length() == 0 ? word : line + " " + word;
            if (g.getFontMetrics().stringWidth(test) > width && line.length() > 0) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(test);
            }
        }
        if (line.length() > 0) lines.add(line.toString());
        return lines;
    }

    private static Color mix(Color a, Color b, float t) {
        t = Math.max(0, Math.min(1, t));
        return new Color((int) (a.getRed() + (b.getRed() - a.getRed()) * t), (int) (a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                (int) (a.getBlue() + (b.getBlue() - a.getBlue()) * t));
    }
}
