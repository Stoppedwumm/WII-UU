package choccymilk;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FileDialog;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.GeneralPath;
import java.awt.geom.RoundRectangle2D;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import wiiuu.core.Choccy;
import wiiuu.core.Config;
import wiiuu.core.Cores;
import wiiuu.core.Game;
import wiiuu.core.GameSystem;
import wiiuu.core.Library;
import wiiuu.core.Systems;
import wiiuu.engine.GameScreen;
import wiiuu.input.LocalPads;
import wiiuu.input.PadButton;
import wiiuu.input.PadSink;

/**
 * Choccy Milk on its own: a game list and WII-UU's libretro player in one window, without WII-UU.
 *
 * <p>It uses the WII-UU engine (the library both share): the same settings folder, ROM folders,
 * saves, cores and Java, so games show up in both and saves carry over. Keyboard: arrows choose,
 * Enter (or X) plays, Esc goes back; in a game the same keys as in WII-UU (arrows, X Z S A, Q W,
 * E R, Enter, Space). F11: full screen. O: open a file. F5: look for games again. T: Cocoa (dark) or
 * Milk (light) look. Controllers work too (Linux).
 */
public final class App extends JPanel implements PadSink {
    public static final String NAME = "Choccy Milk";

    /** the look (Cocoa or Milk), and the start animation while it plays */
    private volatile Look look;
    private volatile Intro intro;
    private final Timer animation = new Timer(16, e -> repaint());

    private final Config config;
    private final Cores cores;
    private final Library library;
    private final GameScreen screen = new GameScreen();
    private final wiiuu.core.RaConnect ra;
    private JFrame frame;

    /** consoles with games and cores, and the games of the chosen one */
    private List<GameSystem> systems = List.of();
    private int sys, sel, scroll;
    private boolean onGames;
    private volatile Choccy.Session session;
    private volatile Game playing;
    private volatile String status = "";
    private volatile long statusAt;
    private final boolean systemDark;

    App(Config config, boolean systemDark) {
        this.config = config;
        this.systemDark = systemDark;
        this.look = Look.pick(config.get("choccy.theme", "auto"), systemDark);
        this.cores = new Cores(config);
        this.library = new Library(config);
        String version = App.class.getPackage().getImplementationVersion();
        this.ra = new wiiuu.core.RaConnect(config, "ChoccyMilk/" + (version == null ? "dev" : version));
        Choccy.setHome(config.home());
        setBackground(look.bgBottom);
        setPreferredSize(new Dimension(1100, 680));
        setFocusable(true);
        setFocusTraversalKeysEnabled(false);
        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                key(e, true);
            }

            @Override
            public void keyReleased(KeyEvent e) {
                key(e, false);
            }
        });
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                if (skipIntro()) return;
                if (playing == null) click(e.getX(), e.getY(), e.getClickCount());
            }

            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                if (playing == null && onGames) move(e.getWheelRotation());
            }
        };
        addMouseListener(mouse);
        addMouseWheelListener(mouse);
        library.addListener(s -> SwingUtilities.invokeLater(this::refresh));
        new Timer(250, e -> {
            if (playing == null || screen.hintShowing()) repaint();
        }).start();
    }

    // ---- the game list ---------------------------------------------------------------------------

    private void refresh() {
        GameSystem was = systems.isEmpty() ? null : systems.get(Math.min(sys, systems.size() - 1));
        List<GameSystem> now = new ArrayList<>();
        for (GameSystem s : Systems.ALL) {
            if (Cores.has(s) && !library.snapshot().of(s).isEmpty()) now.add(s);
        }
        systems = now;
        sys = Math.max(0, was == null ? 0 : now.indexOf(was));
        sel = Math.min(sel, Math.max(0, games().size() - 1));
        repaint();
    }

    private List<Game> games() {
        return systems.isEmpty() ? List.of() : library.snapshot().of(systems.get(Math.min(sys, systems.size() - 1)));
    }

    private void move(int d) {
        if (onGames) {
            int n = games().size();
            if (n > 0) sel = Math.floorMod(sel + d, n);
        } else if (!systems.isEmpty()) {
            sys = Math.floorMod(sys + d, systems.size());
            sel = 0;
            scroll = 0;
        }
        repaint();
    }

    private void choose() {
        if (!onGames) {
            if (!games().isEmpty()) onGames = true;
        } else if (!games().isEmpty()) {
            play(games().get(sel));
        }
        repaint();
    }

    // ---- playing ---------------------------------------------------------------------------------

    void play(Game game) {
        if (session != null) return;
        if (!Choccy.available()) {
            if (!Choccy.canGetJava()) {
                say(Choccy.unavailable());
                return;
            }
            say("Getting Java for " + NAME + " (about 50 MB, once)… the game starts by itself");
            Choccy.getJava(() -> SwingUtilities.invokeLater(() -> {
                if (Choccy.available()) play(game);
                else say("Couldn't get Java for " + NAME + " (see the log)");
            }));
            return;
        }
        Path core;
        try {
            core = cores.coreFor(game, () -> SwingUtilities.invokeLater(() -> play(game)));
        } catch (Cores.Downloading d) {
            say(d.getMessage());
            return;
        }
        if (core == null) {
            say("No core for " + game.system().name() + " could be found or downloaded");
            return;
        }
        String id = game.system().id();
        Path log = config.logDir().resolve("choccy-" + id + ".log");
        Choccy.Session s;
        try {
            s = Choccy.play(core, game.path(), config.home().resolve("saves").resolve(id), cores.systemDir(),
                    cores.choccyOptions(id), (px, w, h, a) -> {
                        screen.frame(px, w, h, a);
                        repaint();
                    }, log);
        } catch (IOException e) {
            say(NAME + " didn't start: " + e.getMessage());
            return;
        }
        session = s;
        s.onNotice(text -> {
            screen.announce(text, null, false, 2500);
            repaint();
        });
        playing = game;
        status = "";
        wiiuu.core.ChoccyCheevos.start(ra, s, game, new wiiuu.core.ChoccyCheevos.Events() {
            @Override
            public void found(wiiuu.core.RaConnect.GameSet set) {
                if (set.warning() != null) {
                    screen.announce("Achievements off: unsupported version of " + set.title(),
                            "RetroAchievements doesn't support this ROM's version (see the log for which ones work)", false, 8000);
                    return;
                }
                screen.announce("RetroAchievements: " + set.title(), set.unlocked().size() + " of "
                        + set.achievements().size() + " achievements unlocked", false, 4000);
            }

            @Override
            public void unlocked(wiiuu.core.RaConnect.Achievement a, int have, int total, String error) {
                screen.announce(a.title(), "Achievement unlocked \u00B7 " + a.points() + " points \u00B7 " + have + " of " + total
                        + (error != null ? " (not sent: " + error + ")" : ""), true, 6000);
            }
        });
        long started = System.currentTimeMillis();
        System.out.println("[choccy] " + game.name() + " with " + core.getFileName());
        s.process().onExit().thenAccept(p -> SwingUtilities.invokeLater(() -> {
            session = null;
            playing = null;
            screen.clear();
            String why = s.ending() == null ? "" : s.ending();
            if (id.equals("n64") && p.exitValue() != 0 && System.currentTimeMillis() - started < 10000
                    && !cores.choccyN64Interpreter() && Cores.dynarecCrashed(log, p.exitValue())) {
                config.set("choccy.n64.dynarecFailed", "true");            // the interpreter from now on, and again
                config.save();
                say(Cores.dynarecAdvice());
                play(game);
            } else if (why.contains("needs OpenGL")) {
                say(game.system().name() + "'s core needs OpenGL, which " + NAME + " doesn't have (WII-UU plays it in RetroArch)");
            } else if (p.exitValue() != 0 && System.currentTimeMillis() - started < 4000) {
                say(game.name() + " stopped" + (why.isEmpty() ? " (error " + p.exitValue() + "): see " + log : ": " + why));
            }
            repaint();
        }));
        repaint();
    }

    /** Saves and stops the game (it ends by itself after a moment; stopped hard if it doesn't). */
    private void stop() {
        Choccy.Session s = session;
        if (s == null) return;
        s.quit();
        Thread t = new Thread(() -> {
            try {
                if (!s.process().waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) s.process().destroy();
            } catch (InterruptedException ignored) {
                s.process().destroy();
            }
        }, "choccy-stop");
        t.setDaemon(true);
        t.start();
    }

    // ---- the look and the start ---------------------------------------------------------------------

    /** Plays the start animation (once, when the window opens). */
    void startIntro() {
        intro = new Intro(look);
        animation.start();
    }

    /** Ends the start animation early; true if it was playing. */
    private boolean skipIntro() {
        if (intro == null) return false;
        intro = null;
        animation.stop();
        repaint();
        return true;
    }

    /** T: Cocoa (dark) or Milk (light), remembered. */
    private void switchLook() {
        look = look.dark ? Look.MILK : Look.COCOA;
        config.set("choccy.theme", look.dark ? "cocoa" : "milk");
        config.save();
        setBackground(look.bgBottom);
        say(look.name + " look" + ((look.dark == systemDark) ? " (like the computer)" : "") + "  ·  T switches");
    }

    private void say(String text) {
        status = text == null ? "" : text;
        statusAt = System.currentTimeMillis();
        System.out.println("[choccy] " + status);
        repaint();
    }

    // ---- input -----------------------------------------------------------------------------------

    private void key(KeyEvent e, boolean down) {
        int k = e.getKeyCode();
        if (down && skipIntro()) return;
        if (k == KeyEvent.VK_F11 && down) {
            fullScreen();
            return;
        }
        Choccy.Session s = session;
        if (s != null) {
            if (k == KeyEvent.VK_ESCAPE && down) {
                stop();
                return;
            }
            String state = !down ? null : switch (k) {           // save states, with RetroArch's keys
                case KeyEvent.VK_F2 -> "save";
                case KeyEvent.VK_F4 -> "load";
                case KeyEvent.VK_F6 -> "slotdown";
                case KeyEvent.VK_F7 -> "slotup";
                default -> null;
            };
            if (state != null) {
                String text = s.state(state);
                if (text != null) {
                    screen.announce(text, null, false, 2500);
                    repaint();
                }
                return;
            }
            PadButton b = GameScreen.key(k);
            if (b != null) s.button(0, b, down);
            return;
        }
        if (!down) return;
        switch (k) {
            case KeyEvent.VK_UP -> move(-1);
            case KeyEvent.VK_DOWN -> move(1);
            case KeyEvent.VK_PAGE_UP -> move(-8);
            case KeyEvent.VK_PAGE_DOWN -> move(8);
            case KeyEvent.VK_RIGHT, KeyEvent.VK_TAB -> {
                if (!games().isEmpty()) onGames = true;
                repaint();
            }
            case KeyEvent.VK_LEFT, KeyEvent.VK_ESCAPE, KeyEvent.VK_BACK_SPACE -> {
                onGames = false;
                repaint();
            }
            case KeyEvent.VK_ENTER, KeyEvent.VK_X, KeyEvent.VK_SPACE -> choose();
            case KeyEvent.VK_O -> openFile();
            case KeyEvent.VK_T -> switchLook();
            case KeyEvent.VK_F2 -> signIn();
            case KeyEvent.VK_F5 -> {
                say("Looking for games…");
                library.rescanAsync();
            }
            default -> { }
        }
    }

    /** Controllers: in a game they play it (HOME goes back); in the list they choose. */
    @Override
    public void button(int player, PadButton b, boolean down) {
        if (down && intro != null) {
            SwingUtilities.invokeLater(this::skipIntro);
            return;
        }
        Choccy.Session s = session;
        int port = player > 0 ? player - 1 : Math.max(0, -player - 1);
        if (s != null) {
            if (b == PadButton.HOME) {
                if (down) stop();
            } else {
                s.button(port, b, down);
            }
            return;
        }
        if (!down) return;
        SwingUtilities.invokeLater(() -> {
            switch (b) {
                case UP -> move(-1);
                case DOWN -> move(1);
                case RIGHT -> {
                    if (!games().isEmpty()) onGames = true;
                    repaint();
                }
                case LEFT, B -> {
                    onGames = false;
                    repaint();
                }
                case A, PLUS -> choose();
                default -> { }
            }
        });
    }

    private final java.util.Map<Integer, Integer> stickHeld = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public void stick(int player, int stick, float x, float y) {
        Choccy.Session s = session;
        Game g = playing;
        int port = player > 0 ? player - 1 : Math.max(0, -player - 1);
        if (s != null) {
            s.stick(port, stick, x, y);
            if (stick == 0 && g != null && Choccy.stickIsDpad(g.system().id())) s.stickAsDpad(port, x, y);
            return;
        }
        if (stick != 0) return;                         // the list: the left stick moves like the d-pad
        int dir = y < -0.6f ? -1 : y > 0.6f ? 1 : 0;
        Integer was = stickHeld.put(player, dir);
        if (dir != 0 && (was == null || was != dir)) SwingUtilities.invokeLater(() -> move(dir));
    }

    @Override
    public void releaseAll(int player) {
        Choccy.Session s = session;
        int port = player > 0 ? player - 1 : Math.max(0, -player - 1);
        if (s != null) for (PadButton b : PadButton.values()) s.button(port, b, false);
    }

    /** F2: RetroAchievements sign-in (the user name and password; only the token it gives is kept). */
    private void signIn() {
        javax.swing.JTextField user = new javax.swing.JTextField(config.get("ra.user", ""), 18);
        javax.swing.JPasswordField pass = new javax.swing.JPasswordField(18);
        javax.swing.JPanel p = new javax.swing.JPanel(new java.awt.GridLayout(0, 1, 4, 4));
        p.add(new javax.swing.JLabel("RetroAchievements user name"));
        p.add(user);
        p.add(new javax.swing.JLabel("Password (only the sign-in token is kept)"));
        p.add(pass);
        int ok = javax.swing.JOptionPane.showConfirmDialog(frame, p, "RetroAchievements", javax.swing.JOptionPane.OK_CANCEL_OPTION,
                javax.swing.JOptionPane.PLAIN_MESSAGE);
        if (ok != javax.swing.JOptionPane.OK_OPTION || user.getText().isBlank()) return;
        String name = user.getText().trim(), password = new String(pass.getPassword());
        say("Signing in to RetroAchievements\u2026");
        Thread t = new Thread(() -> {
            String why = ra.login(name, password);
            SwingUtilities.invokeLater(() -> say(why == null ? "Signed in as " + ra.user() + ": achievements unlock while you play" : why));
        }, "ra-signin");
        t.setDaemon(true);
        t.start();
    }

    private void openFile() {
        FileDialog d = new FileDialog(frame, "Play a game with " + NAME, FileDialog.LOAD);
        d.setVisible(true);
        if (d.getFile() == null) return;
        Path f = Path.of(d.getDirectory(), d.getFile());
        Game g = gameFor(f);
        if (g == null) say("No console with a core plays " + f.getFileName());
        else play(g);
    }

    /** A loose file as a game: the first console with cores whose games have that extension. */
    static Game gameFor(Path file) {
        String n = file.getFileName().toString();
        for (GameSystem s : Systems.ALL) {
            if (Cores.has(s) && s.matches(n)) {
                String title = n.contains(".") ? n.substring(0, n.lastIndexOf('.')) : n;
                return new Game(s, title, file, List.of());
            }
        }
        return null;
    }

    private void click(int x, int y, int count) {
        Layout l = new Layout();
        if (x < l.split) {
            int i = (int) ((y - l.top) / l.row);
            if (y >= l.top && i >= 0 && i < systems.size()) {
                sys = i;
                sel = 0;
                scroll = 0;
                onGames = false;
            }
        } else {
            int i = scroll + (int) ((y - l.top) / l.row);
            if (y >= l.top && i >= 0 && i < games().size()) {
                onGames = true;
                sel = i;
                if (count >= 2) play(games().get(i));
            }
        }
        repaint();
    }

    private void fullScreen() {
        GraphicsDevice dev = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
        boolean on = dev.getFullScreenWindow() != frame;
        frame.dispose();
        frame.setUndecorated(on);
        dev.setFullScreenWindow(on ? frame : null);
        if (!on) frame.pack();
        frame.setVisible(true);
        requestFocusInWindow();
    }

    // ---- drawing ---------------------------------------------------------------------------------

    /** Where things go, from the window's size. */
    private final class Layout {
        final int w = getWidth(), h = getHeight();
        final float u = Math.max(0.6f, Math.min(w / 1100f, h / 680f));
        final float band = 96 * u, top = 178 * u, row = 44 * u, split = Math.max(230 * u, w * 0.27f);
        final int rows = Math.max(1, (int) ((h - top - 56 * u) / row));
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0;
        if (playing != null && screen.showing()) {
            int h = getHeight();
            screen.paint(g, getWidth(), h, NAME + "  ·  Esc (or HOME): back to the list  ·  F2 / F4: save / load state",
                    new Font(Font.SANS_SERIF, Font.BOLD, Math.max(11, Math.round(h * 0.028f))));
            return;
        }
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        Layout l = new Layout();
        long now = System.currentTimeMillis();
        Intro in = intro;
        if (in == null || in.cover(now) < 1) {
            look.paintPage(g, l.w, l.h, l.band, l.u, now);
            paintTitle(g, l);
            if (systems.isEmpty()) {
                paintEmpty(g, l);
            } else {
                paintSystems(g, l);
                paintGames(g, l);
            }
            paintFooter(g, l);
        }
        if (in != null) {
            in.paint(g, l.w, l.h, now);
            if (in.done(now)) {
                intro = null;
                animation.stop();
            }
        }
    }

    private void paintTitle(Graphics2D g, Layout l) {
        float u = l.u;
        // the glass of chocolate milk, on the chocolate band
        float gx = 34 * u, gy = 26 * u, gw = 40 * u, gh = 56 * u;
        Look.glass(g, gx, gy, gw, gh, 0.8f, 1, 1, 0.5f, System.currentTimeMillis() / 1400.0,
                new Color(0xF6E9DC), new Color(0xFFF7EE), new Color(0x8A5232), new Color(0xB07650));
        g.setFont(Look.font(Font.BOLD, 40 * u));
        FontMetrics fm = g.getFontMetrics();
        float tx = gx + gw + 22 * u, ty = gy + 40 * u;
        g.setColor(new Color(0, 0, 0, 60));
        g.drawString(NAME, tx, ty + 3 * u);
        g.setColor(new Color(0xFFF7EE));
        g.drawString("Choccy ", tx, ty);
        g.setColor(new Color(0xF2C9A0));
        g.drawString("Milk", tx + fm.stringWidth("Choccy "), ty);
        g.setFont(Look.font(Font.PLAIN, 14 * u));
        g.setColor(new Color(0xE6CDB8));
        g.drawString("libretro cores, no RetroArch needed  ·  part of WII-UU", tx + 2 * u, gy + 62 * u);
        // which look, at the right of the band
        String which = look.name + " · T";
        FontMetrics fs = g.getFontMetrics();
        g.setColor(new Color(0xE6CDB8));
        g.drawString(which, l.w - 28 * u - fs.stringWidth(which), gy + 62 * u);
    }

    private void paintSystems(Graphics2D g, Layout l) {
        float u = l.u;
        g.setFont(Look.font(Font.BOLD, 17 * u));
        FontMetrics fm = g.getFontMetrics();
        g.setFont(Look.font(Font.BOLD, 13 * u));
        g.setColor(look.dim);
        g.drawString("CONSOLES", 36 * u, l.top - 24 * u);
        g.setFont(Look.font(Font.BOLD, 17 * u));
        for (int i = 0; i < systems.size(); i++) {
            GameSystem s = systems.get(i);
            float y = l.top + i * l.row;
            if (y + l.row > l.h - 56 * u) break;
            boolean chosen = i == sys;
            if (chosen) look.pick(g, 24 * u, y + 3 * u, l.split - 40 * u, l.row - 6 * u, u, !onGames);
            // the console's colour, as a little drop
            float dx = 43 * u, dy = y + l.row / 2;
            GeneralPath drop = new GeneralPath();
            drop.moveTo(dx, dy - 9 * u);
            drop.curveTo(dx + 3 * u, dy - 4 * u, dx + 7 * u, dy - 1 * u, dx + 7 * u, dy + 3 * u);
            drop.curveTo(dx + 7 * u, dy + 7 * u, dx + 3.5f * u, dy + 9 * u, dx, dy + 9 * u);
            drop.curveTo(dx - 3.5f * u, dy + 9 * u, dx - 7 * u, dy + 7 * u, dx - 7 * u, dy + 3 * u);
            drop.curveTo(dx - 7 * u, dy - 1 * u, dx - 3 * u, dy - 4 * u, dx, dy - 9 * u);
            g.setColor(s.color());
            g.fill(drop);
            g.setColor(Look.alpha(look.text, 0.22f));             // so dark ones show on Cocoa too
            g.setStroke(new BasicStroke(1f * u));
            g.draw(drop);
            g.setColor(chosen && !onGames ? look.pickText : chosen ? look.text : look.dim);
            String n = Integer.toString(library.snapshot().of(s).size());
            float room = l.split - 28 * u - fm.stringWidth(n) - 12 * u - 62 * u;
            String name = fm.stringWidth(s.name()) <= room ? s.name() : s.shortName();
            g.drawString(fit(name, fm, room), 62 * u, y + l.row / 2 + fm.getAscent() * 0.36f);
            g.drawString(n, l.split - 28 * u - fm.stringWidth(n), y + l.row / 2 + fm.getAscent() * 0.36f);
        }
    }

    private void paintGames(Graphics2D g, Layout l) {
        float u = l.u;
        List<Game> list = games();
        if (sel < scroll) scroll = sel;
        if (sel >= scroll + l.rows) scroll = sel - l.rows + 1;
        float x = l.split + 8 * u, w = l.w - x - 28 * u;
        float px = x - 10 * u, py = l.top - 10 * u, pw = w + 20 * u, ph = l.rows * l.row + 20 * u;
        g.setFont(Look.font(Font.BOLD, 13 * u));
        g.setColor(look.dim);
        String head = systems.get(Math.min(sys, systems.size() - 1)).name().toUpperCase(java.util.Locale.ROOT)
                + "  ·  " + list.size() + (list.size() == 1 ? " GAME" : " GAMES");
        g.drawString(head, x + 6 * u, l.top - 24 * u);
        look.shadow(g, px, py, pw, ph, 22 * u, u);
        g.setColor(look.panel);
        g.fill(new RoundRectangle2D.Float(px, py, pw, ph, 22 * u, 22 * u));
        g.setColor(look.panelEdge);
        g.setStroke(new BasicStroke(1.2f * u));
        g.draw(new RoundRectangle2D.Float(px, py, pw, ph, 22 * u, 22 * u));
        g.setFont(Look.font(Font.PLAIN, 18 * u));
        FontMetrics fm = g.getFontMetrics();
        for (int r = 0; r < l.rows && scroll + r < list.size(); r++) {
            int i = scroll + r;
            float y = l.top + r * l.row;
            if (i == sel) look.pick(g, x, y + 3 * u, w, l.row - 6 * u, u, onGames);
            g.setColor(i == sel && onGames ? look.pickText : i == sel ? look.text : Look.mix(look.text, look.panel, 0.18f));
            g.drawString(fit(list.get(i).name(), fm, w - 32 * u), x + 18 * u, y + l.row / 2 + fm.getAscent() * 0.36f);
        }
        if (list.size() > l.rows) {                    // where in the list
            float th = ph - 24 * u, bar = Math.max(24 * u, th * l.rows / list.size());
            float by = py + 12 * u + (th - bar) * scroll / Math.max(1, list.size() - l.rows);
            g.setColor(Look.alpha(look.accent, 0.5f));
            g.fill(new RoundRectangle2D.Float(px + pw - 9 * u, by, 4 * u, bar, 4 * u, 4 * u));
        }
    }

    private void paintEmpty(Graphics2D g, Layout l) {
        float u = l.u;
        float gh = 120 * u, gw = gh * 0.72f;
        Look.glass(g, 46 * u, l.top + 10 * u, gw, gh, 0.12f, 0, 0, 0.4f, System.currentTimeMillis() / 1600.0,
                look.dark ? look.milk : look.choc, look.milk, look.choc, look.chocLight);
        g.setFont(Look.font(Font.BOLD, 24 * u));
        g.setColor(look.text);
        g.drawString("The glass is empty.", 46 * u + gw + 30 * u, l.top + 50 * u);
        g.setFont(Look.font(Font.PLAIN, 18 * u));
        g.setColor(look.dim);
        String[] lines = {
                "Put games in a folder per console under " + config.romBase() + "  (nes, snes, gb, gba, n64, ...),",
                "the same folders WII-UU uses, then press F5.  Or press O to open a game file."};
        for (int i = 0; i < lines.length; i++) g.drawString(lines[i], 46 * u + gw + 30 * u, l.top + 86 * u + i * 30 * u);
    }

    private void paintFooter(Graphics2D g, Layout l) {
        float u = l.u;
        g.setFont(Look.font(Font.PLAIN, 14 * u));
        FontMetrics fm = g.getFontMetrics();
        boolean news = !status.isEmpty() && System.currentTimeMillis() - statusAt < 12000;
        String text = news ? status
                : "↑↓ choose   Enter play   ←→ consoles / games   O open a file   F5 look again   T look   F11 full screen   F2 "
                + (ra.signedIn() ? "RetroAchievements: " + ra.user() : "RetroAchievements sign-in");
        if (news) {                                     // news in a little caramel pill
            float tw = Math.min(fm.stringWidth(text), l.w - 80 * u) + 28 * u, th = 28 * u;
            g.setColor(Look.alpha(look.accent, look.dark ? 0.22f : 0.16f));
            g.fill(new RoundRectangle2D.Float(24 * u, l.h - 22 * u - th * 0.68f, tw, th, th, th));
            g.setColor(look.dark ? look.accent : look.choc);
        } else {
            g.setColor(look.dim);
        }
        g.drawString(fit(text, fm, l.w - 80 * u), 38 * u, l.h - 22 * u);
    }

    private static String fit(String s, FontMetrics fm, float w) {
        if (fm.stringWidth(s) <= w) return s;
        while (s.length() > 1 && fm.stringWidth(s + "…") > w) s = s.substring(0, s.length() - 1);
        return s + "…";
    }

    // ---- starting --------------------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        Path home = Config.defaultHome();
        Path rom = null;
        boolean noIntro = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--home" -> home = Path.of(args[++i]);
                case "--no-intro" -> noIntro = true;
                case "--write-icon" -> {                 // the installers' menu icon
                    javax.imageio.ImageIO.write(Look.icon(256), "png", Path.of(args[++i]).toFile());
                    return;
                }
                case "--help", "-h" -> {
                    System.out.println("usage: ChoccyMilk [--home DIR] [--no-intro] [GAME]\n"
                            + "  --home DIR  settings folder (default ~/.wiiuu, shared with WII-UU)\n"
                            + "  --no-intro  no start animation (also: choccy.intro=false)\n"
                            + "  --write-icon FILE  saves the app icon as a PNG\n"
                            + "  GAME        a game file to play right away");
                    return;
                }
                default -> rom = Path.of(args[i]);
            }
        }
        Config config = new Config(home);
        Files.createDirectories(home);
        Path start = rom;
        boolean dark = wiiuu.engine.SystemTheme.isDark();
        boolean intro = !noIntro && rom == null && config.getBool("choccy.intro", true);
        SwingUtilities.invokeLater(() -> {
            App app = new App(config, dark);
            JFrame f = new JFrame(NAME);
            app.frame = f;
            f.setIconImages(Look.icons());
            f.getContentPane().setBackground(app.look.bgTop);       // no grey before the first paint
            f.setBackground(app.look.bgTop);
            try {
                if (java.awt.Taskbar.isTaskbarSupported()) java.awt.Taskbar.getTaskbar().setIconImage(Look.icon(256));
            } catch (UnsupportedOperationException | SecurityException ignored) {
                // no Dock / taskbar icon here
            }
            f.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
            f.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent e) {
                    app.stop();                         // saves first
                    Choccy.Session s = app.session;
                    if (s != null) s.process().onExit().thenRun(() -> System.exit(0));
                    else System.exit(0);
                    new Timer(4000, x -> System.exit(0)).start();
                }
            });
            f.setContentPane(app);
            f.pack();
            f.setLocationRelativeTo(null);
            f.setVisible(true);
            app.requestFocusInWindow();
            if (intro) app.startIntro();
            new LocalPads(app, false).start();
            app.library.rescanAsync();
            if (start != null) {
                Game g = gameFor(start.toAbsolutePath());
                if (g == null) app.say("No console with a core plays " + start.getFileName());
                else app.play(g);
            }
        });
    }
}
