package wiiuu.ui;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Toolkit;
import java.awt.Transparency;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import wiiuu.core.Config;
import wiiuu.core.Game;
import wiiuu.core.GameSystem;
import wiiuu.core.Library;
import wiiuu.core.Systems;
import wiiuu.input.InputRouter;
import wiiuu.net.QrCode;

/**
 * The whole TV screen, painted by hand in a Wii U Menu style: a light, softly striped
 * background, a paged 5x3 grid of rounded tiles with a gliding blue cursor, and a dock.
 */
public final class MenuView extends JComponent implements InputRouter.MenuActions {

    public interface Actions {
        void launch(Game game);

        void openSettings();

        void quit();

        /**
         * Console mode (WII-UU started as the whole session by wiiuu-console): "desktop" switches to
         * the desktop session, "restart" and "shutdown" restart or power off the computer.
         */
        default void power(String action) {
            quit();
        }

        void refresh();

        void closeGame();
    }

    // ---- colours: one set per theme (see applyTheme); only changed on the Swing thread ------
    static Color ACCENT, TEXT, TEXT_DIM, CARD;
    private static Color DIVIDER, DOT_OFF, ICON, BUTTON, BG_TOP, BG_BOTTOM, STRIPE, BLOOM, BLOOM_ACCENT,
            SHELF, OUTLINE, SHADOW_RGB, DIM, TOAST, BOOT_TOP, BOOT_BOTTOM, BOOT_FADE;
    private static int shadowBoost = 1;
    static boolean dark;

    static {
        applyTheme(false);
    }

    /** Switches every colour of the menu between the light and the dark set. */
    static void applyTheme(boolean darkTheme) {
        dark = darkTheme;
        if (!darkTheme) {
            ACCENT = new Color(0x00A8E8);
            TEXT = new Color(0x3C4043);
            TEXT_DIM = new Color(0x8A9099);
            CARD = Color.WHITE;
            DIVIDER = new Color(0xD5D9DE);
            DOT_OFF = new Color(0xC3C8CE);
            ICON = new Color(0x6B7178);
            BUTTON = new Color(0xEEF0F2);
            BG_TOP = new Color(0xF7F8FA);
            BG_BOTTOM = new Color(0xE2E6EA);
            STRIPE = new Color(0, 0, 0, 9);
            BLOOM = new Color(255, 255, 255, 170);
            BLOOM_ACCENT = new Color(0, 168, 232, 26);
            SHELF = new Color(255, 255, 255, 120);
            OUTLINE = new Color(0, 0, 0, 22);
            SHADOW_RGB = new Color(40, 50, 60);
            shadowBoost = 1;
            DIM = new Color(235, 238, 241, 215);
            TOAST = new Color(40, 44, 50, 225);
            BOOT_TOP = Color.WHITE;
            BOOT_BOTTOM = new Color(0xEEF2F5);
            BOOT_FADE = new Color(0xF6F8FA);
        } else {
            ACCENT = new Color(0x26B9F2);
            TEXT = new Color(0xE6E9EC);
            TEXT_DIM = new Color(0x9AA3AD);
            CARD = new Color(0x262B31);
            DIVIDER = new Color(0x3C434B);
            DOT_OFF = new Color(0x4A525B);
            ICON = new Color(0xAEB6BF);
            BUTTON = new Color(0x343A42);
            BG_TOP = new Color(0x1C2025);
            BG_BOTTOM = new Color(0x0F1113);
            STRIPE = new Color(255, 255, 255, 6);
            BLOOM = new Color(255, 255, 255, 16);
            BLOOM_ACCENT = new Color(38, 185, 242, 30);
            SHELF = new Color(255, 255, 255, 16);
            OUTLINE = new Color(255, 255, 255, 18);
            SHADOW_RGB = new Color(0, 0, 0);
            shadowBoost = 3;
            DIM = new Color(12, 14, 16, 215);
            TOAST = new Color(58, 64, 72, 240);
            BOOT_TOP = new Color(0x1B1F24);
            BOOT_BOTTOM = new Color(0x0F1113);
            BOOT_FADE = new Color(0x16191D);
        }
    }

    private volatile String themeMode = "";
    private Thread themeWatcher;

    /**
     * ui.theme: "light", "dark", or "auto" to follow the system's appearance (checked again every
     * 15 seconds, so switching the Mac or PC to dark mode switches WII-UU too).
     */
    public void setThemeMode(String mode) {
        mode = mode == null ? "auto" : mode.trim().toLowerCase(Locale.ROOT);
        themeMode = mode;
        switch (mode) {
            case "dark" -> setTheme(true);
            case "light" -> setTheme(false);
            default -> {
                setTheme(SystemTheme.isDark());
                if (themeWatcher == null) {
                    themeWatcher = new Thread(() -> {
                        while (true) {
                            try {
                                Thread.sleep(15_000);
                            } catch (InterruptedException e) {
                                return;
                            }
                            if (!themeMode.equals("dark") && !themeMode.equals("light")) {
                                boolean d = SystemTheme.isDark();
                                SwingUtilities.invokeLater(() -> {
                                    if (!themeMode.equals("dark") && !themeMode.equals("light")) setTheme(d);
                                });
                            }
                        }
                    }, "theme-watch");
                    themeWatcher.setDaemon(true);
                    themeWatcher.start();
                }
            }
        }
    }

    /** Switches the theme while running: every cached picture is redrawn in the new colours. */
    public void setTheme(boolean darkTheme) {
        if (darkTheme == dark) return;
        applyTheme(darkTheme);
        synchronized (sprites) {
            sprites.clear();
            spriteBytes = 0;
        }
        bg = null;
        topKey = dockKey = bootCacheKey = null;
        repaint();
    }

    private static final int COLS = 5, ROWS = 3, PER_PAGE = COLS * ROWS;
    private static final String FONT = pickFont();
    /** Sprites are drawn at the selected (largest) size so zooming in never upsamples. */
    private static final float SPRITE_SCALE = 1.06f;
    private static final Map<Long, Font> FONTS = new ConcurrentHashMap<>();

    private enum Screen { HOME, GAMES }

    private enum Dock {
        GAMEPAD("GamePad"), SETTINGS("Settings"), REFRESH("Refresh"), POWER("Power");
        final String label;

        Dock(String label) {
            this.label = label;
        }
    }

    private static final class Tile {
        final GameSystem system;
        final Game game;
        final int count;
        float scale = 1f;

        Object id() {
            return game != null ? game.path() : system.id();
        }

        Tile(GameSystem system, Game game, int count) {
            this.system = system;
            this.game = game;
            this.count = count;
        }
    }

    private record Layout(float w, float h, float topH, float gridX, float gridY, float gridW, float gridH,
                          float cellW, float cellH, float tileW, float tileH, float dockY, float dockH) {}

    private final Config config;
    private final Actions actions;
    private Library.Snapshot snap = new Library.Snapshot(0, Map.of());
    private Screen screen = Screen.HOME;
    private GameSystem openSystem;
    private int homeSel;
    private final List<Tile> tiles = new ArrayList<>();
    private int sel;
    private boolean inDock;
    private int dockSel;

    // animation state
    private float scrollPage;
    private final float[] hl = new float[4];
    private boolean hlInit;
    private long lastSecond;

    // boot animation (seconds): logo, then the menu fades in at REVEAL and pops its tiles in
    private static final double BOOT_REVEAL = 2.9, BOOT_END = 3.7;
    private boolean booting;
    private long bootStart;              // System.nanoTime() of t = 0, set once the window is shown
    private long revealAt;               // ms, tiles pop in one after another from here
    private boolean chimed, revealed;
    private static final double BOOT_DELAY = 0.3;
    private BufferedImage bootBg, bootLogoImg, bootSheenImg, bootTagImg;
    private String bootCacheKey;
    private boolean musicEnabled;
    private String musicChoice;              // the ui.musicTrack setting now loaded
    private volatile int musicGen;           // bumped on every change; stale renders are dropped
    private volatile short[] upNext;         // "all": the next track, ready when the current one ends
    private volatile String upNextId;
    private volatile String musicTitle;      // what's playing, for the visualizer
    private final Visualizer viz = new Visualizer();
    private boolean vizOn;                   // the strip behind the tiles (ui.visualizer)
    private boolean vizFull;                 // full screen (V)

    // overlays / status
    private boolean showPad;
    private boolean confirmQuit;            // the Power menu is open
    private int powerSel;
    /** started by wiiuu-console as the whole session (SteamOS-style console mode) */
    static final boolean CONSOLE = "1".equals(System.getenv("WIIUU_CONSOLE"));

    private record PowerItem(String label, String action) {}

    private static List<PowerItem> powerItems() {
        return CONSOLE
                ? List.of(new PowerItem("Desktop Mode", "desktop"), new PowerItem("Restart", "restart"),
                        new PowerItem("Shut Down", "shutdown"), new PowerItem("Cancel", null))
                : List.of(new PowerItem("Quit WII-UU", "quit"), new PowerItem("Cancel", null));
    }

    /** Opens the Power menu (the dock's Power button). */
    public void showPowerMenu() {
        confirmQuit = true;
        powerSel = 0;
        repaint();
    }

    private void choosePower(int i) {
        PowerItem item = powerItems().get(i);
        if (item.action() == null) {
            back();
            return;
        }
        Sfx.select();
        if (item.action().equals("quit")) actions.quit();
        else actions.power(item.action());
    }
    private String toast;
    private long toastUntil;
    private Game playing;
    private int pads;
    private String padUrl = "";
    private String padCode = "";
    private boolean padNeedsCode = true;
    private QrCode qr;
    private boolean serverOn;

    private final Map<Path, BufferedImage> covers = new ConcurrentHashMap<>();
    private final Set<Path> loading = ConcurrentHashMap.newKeySet();
    private static final BufferedImage NO_COVER = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
    private final ExecutorService coverLoader = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "covers");
        t.setDaemon(true);
        return t;
    });
    private BufferedImage bg;
    private BufferedImage topCache, dockCache;
    private volatile GraphicsConfiguration gc;

    // Tile sprites are rendered off the Swing thread and kept in a size-bounded LRU cache,
    // so opening a system or flipping pages never blocks input.
    private static final long SPRITE_BUDGET_BYTES = 128L << 20;
    private final LinkedHashMap<Object, BufferedImage> sprites = new LinkedHashMap<>(64, 0.75f, true);
    private long spriteBytes;
    private final Set<Object> spritePending = ConcurrentHashMap.newKeySet();
    private final ExecutorService spriteRenderer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "tile-sprites");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private boolean syncSprites; // snapshots render inline
    private String topKey, dockKey;

    public MenuView(Config config, Actions actions) {
        this.config = config;
        this.actions = actions;
        setFocusable(true);
        setFocusTraversalKeysEnabled(false);
        setOpaque(true);
        Sfx.setEnabled(config.getBool("ui.sounds", true));
        setThemeMode(config.get("ui.theme", "auto"));
        booting = config.getBool("ui.bootAnimation", true);
        if (booting) Sfx.prepareBoot();
        revealed = !booting;
        MenuAudio.get().setMusicVolume(config.getInt("ui.musicVolume", 45) / 100f);
        setMusicEnabled(config.getBool("ui.music", true));
        installInput();
        Timer timer = new Timer(16, e -> tick());
        timer.start();
    }

    // ---- state from the app -------------------------------------------------------------

    public void setSnapshot(Library.Snapshot s) {
        String keepSystem = screen == Screen.GAMES && openSystem != null ? openSystem.id() : null;
        Path keepGame = screen == Screen.GAMES && sel < tiles.size() ? tiles.get(sel).game.path() : null;
        GameSystem keepHome = screen == Screen.HOME && sel < tiles.size() ? tiles.get(sel).system : null;
        snap = s;
        covers.clear();
        if (keepSystem != null) {
            openSystem = Systems.byId(keepSystem).orElse(null);
            buildGameTiles();
            sel = 0;
            for (int i = 0; i < tiles.size(); i++) if (tiles.get(i).game.path().equals(keepGame)) sel = i;
        } else {
            buildHomeTiles();
            sel = 0;
            for (int i = 0; i < tiles.size(); i++) if (tiles.get(i).system == keepHome) sel = i;
        }
        scrollPage = page();
        repaint();
    }

    public void setPlaying(Game g) {
        playing = g;
        showPad = false;
        confirmQuit = false;
        updateMusic();
        repaint();
    }

    public void setPads(int connected) {
        if (connected > pads) {
            Sfx.chime();
            showToast(connected == 1 ? "GamePad connected" : connected + " GamePads connected");
            showPad = false;
        } else if (connected < pads) {
            showToast("GamePad disconnected");
        }
        pads = connected;
        repaint();
    }

    public void setServer(String url, String pairingUrl, String code, boolean needsCode) {
        serverOn = url != null;
        padUrl = url == null ? "" : url;
        padCode = code == null ? "" : code;
        padNeedsCode = needsCode;
        qr = pairingUrl == null ? null : QrCode.encode(pairingUrl);
        repaint();
    }

    /** True while the start-up animation plays. */
    public boolean isBooting() {
        return booting;
    }

    public void showToast(String text) {
        toast = text;
        toastUntil = System.currentTimeMillis() + 3200;
        repaint();
    }

    public void setSoundsEnabled(boolean on) {
        Sfx.setEnabled(on);
    }

    /** Background music on the menu; it fades out while a game runs. */
    public void setMusicEnabled(boolean on) {
        musicEnabled = on;
        vizOn = config.getBool("ui.visualizer", true);
        String choice = config.get("ui.musicTrack", MenuTracks.DEFAULT).trim();
        if (on && !booting && !choice.equals(musicChoice)) {
            musicChoice = choice;
            int gen = ++musicGen;
            upNext = null;
            if (choice.equals(ExtendedMix.ID)) {
                MenuAudio.get().onLoopEnd(null);
                playMix(gen, 0, null, true);
                updateMusic();
                return;
            }
            List<String> list = MenuTracks.playlist(musicFolder());
            boolean all = choice.equals(MenuTracks.ALL);
            String first = all ? MenuTracks.DEFAULT : list.contains(choice) ? choice : MenuTracks.DEFAULT;
            MenuAudio.get().onLoopEnd(null);
            renderMusic(gen, first, (loop, id) -> {
                MenuAudio.get().setMusic(loop, id);
                musicTitle = MenuTracks.name(first, musicFolder());
                SwingUtilities.invokeLater(this::updateMusic);
                if (all) takeTurns(gen, list, list.indexOf(first));
            });
        }
        updateMusic();
    }

    /** Each track plays twice, then the next one fades in. */
    private void takeTurns(int gen, List<String> list, int playing) {
        if (list.size() < 2) return;
        int next = (playing + 1) % list.size();
        renderMusic(gen, list.get(next), (loop, id) -> {
            upNext = loop;
            upNextId = id;
            MenuAudio.get().onLoopEnd(times -> {
                short[] n = upNext;
                if (times < 2 || n == null || gen != musicGen) return;
                upNext = null;
                MenuAudio.get().setMusic(n, upNextId);
                musicTitle = MenuTracks.name(list.get(next), musicFolder());
                takeTurns(gen, list, next);
            });
        });
    }

    /**
     * The Extended Mix, a segment at a time: segment {@code i} is made in the background and queued
     * to follow the one playing; when it starts, the next one is made. {@code track} is segment i's
     * track when the previous segment already made it.
     */
    private void playMix(int gen, int i, short[] track, boolean first) {
        Thread t = new Thread(() -> {
            ExtendedMix.Segment seg;
            try {
                seg = ExtendedMix.segment(i, track);
            } catch (RuntimeException | OutOfMemoryError e) {
                return;                                    // the segment playing goes on looping
            }
            if (gen != musicGen) return;
            Runnable started = () -> {
                musicTitle = seg.title();
                playMix(gen, i + 1, seg.following(), false);
            };
            if (first) {
                MenuAudio.get().setMusic(seg.pcm(), "mix:" + seg.index());
                started.run();
                SwingUtilities.invokeLater(this::updateMusic);
            } else {
                MenuAudio.get().queue(seg.pcm(), "mix:" + seg.index(), started);
            }
        }, "menu-mix");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    /** Renders (up to a few seconds on a Pi) off the Swing thread; WII-UU's own tune if it fails. */
    private void renderMusic(int gen, String id, java.util.function.BiConsumer<short[], String> then) {
        Thread t = new Thread(() -> {
            String made = id;
            short[] loop = MenuTracks.load(id, musicFolder());
            if (loop == null) loop = MenuTracks.load(made = MenuTracks.DEFAULT, null);
            if (loop != null && gen == musicGen) then.accept(loop, made);
        }, "menu-music");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private java.nio.file.Path musicFolder() {
        return config.home().resolve("music");
    }

    private void updateMusic() {
        MenuAudio.get().musicOn(musicEnabled && revealed && playing == null);
    }

    private void buildHomeTiles() {
        tiles.clear();
        boolean hideEmpty = config.getBool("ui.hideEmpty", false);
        for (GameSystem s : Systems.ALL) {
            int n = snap.of(s).size();
            if (config.hidden(s) || (hideEmpty && n == 0)) continue;
            tiles.add(new Tile(s, null, n));
        }
    }

    private void buildGameTiles() {
        tiles.clear();
        if (openSystem == null) return;
        for (Game g : snap.of(openSystem)) tiles.add(new Tile(openSystem, g, 0));
    }

    // ---- MenuActions --------------------------------------------------------------------

    private boolean modal() {
        return playing != null || showPad || confirmQuit;
    }

    @Override
    public void navigate(int dx, int dy) {
        if (skipBoot()) return;
        if (confirmQuit) {
            int n = powerItems().size(), step = dy != 0 ? dy : dx;
            int next = Math.max(0, Math.min(n - 1, powerSel + step));
            if (next == powerSel) Sfx.bump();
            else {
                powerSel = next;
                Sfx.move();
            }
            repaint();
            return;
        }
        if (modal()) return;
        if (inDock) {
            if (dy < 0 && !tiles.isEmpty()) {
                inDock = false;
                Sfx.move();
            } else if (dx != 0 && dockSel + dx >= 0 && dockSel + dx < Dock.values().length) {
                dockSel += dx;
                Sfx.move();
            } else {
                Sfx.bump();
            }
            repaint();
            return;
        }
        if (tiles.isEmpty()) {
            if (dy > 0) {
                inDock = true;
                Sfx.move();
            } else Sfx.bump();
            repaint();
            return;
        }
        int page = sel / PER_PAGE, local = sel % PER_PAGE, row = local / COLS, col = local % COLS;
        int last = tiles.size() - 1;
        int target = sel;
        if (dx > 0) {
            if (col < COLS - 1 && sel + 1 <= last) target = sel + 1;
            else if ((page + 1) * PER_PAGE <= last) target = Math.min(last, (page + 1) * PER_PAGE + row * COLS);
        } else if (dx < 0) {
            if (col > 0) target = sel - 1;
            else if (page > 0) target = (page - 1) * PER_PAGE + row * COLS + COLS - 1;
        } else if (dy > 0) {
            int lastOnPage = Math.min(last, page * PER_PAGE + PER_PAGE - 1);
            if (row < ROWS - 1 && sel + COLS <= lastOnPage) target = sel + COLS;
            else if (row < ROWS - 1 && (lastOnPage % PER_PAGE) / COLS > row) target = lastOnPage;
            else {
                inDock = true;
                Sfx.move();
                repaint();
                return;
            }
        } else if (dy < 0) {
            if (row > 0) target = sel - COLS;
        }
        if (target == sel) Sfx.bump();
        else {
            sel = target;
            Sfx.move();
        }
        repaint();
    }

    @Override
    public void activate() {
        if (skipBoot()) return;
        if (vizFull) {
            back();
            return;
        }
        if (confirmQuit) {
            choosePower(powerSel);
            return;
        }
        if (showPad) {
            showPad = false;
            Sfx.back();
            repaint();
            return;
        }
        if (playing != null) return;
        if (inDock) {
            Sfx.select();
            switch (Dock.values()[dockSel]) {
                case GAMEPAD -> showPad = true;
                case SETTINGS -> SwingUtilities.invokeLater(actions::openSettings);
                case REFRESH -> refresh();
                case POWER -> {
                    confirmQuit = true;
                    powerSel = 0;
                }
            }
            repaint();
            return;
        }
        if (sel >= tiles.size()) {
            Sfx.bump();
            return;
        }
        Tile t = tiles.get(sel);
        Sfx.select();
        if (screen == Screen.HOME) {
            homeSel = sel;
            openSystem = t.system;
            screen = Screen.GAMES;
            buildGameTiles();
            sel = 0;
            scrollPage = 0;
            hlInit = false;
            if (tiles.isEmpty()) inDock = false;
        } else {
            actions.launch(t.game);
        }
        repaint();
    }

    @Override
    public void back() {
        if (skipBoot()) return;
        if (vizFull) {
            vizFull = false;
            repaint();
            return;
        }
        if (confirmQuit || showPad) {
            confirmQuit = false;
            showPad = false;
            Sfx.back();
        } else if (playing != null) {
            return;
        } else if (inDock && !tiles.isEmpty()) {
            inDock = false;
            Sfx.back();
        } else if (screen == Screen.GAMES) {
            screen = Screen.HOME;
            inDock = false;
            buildHomeTiles();
            sel = Math.min(homeSel, Math.max(0, tiles.size() - 1));
            scrollPage = page();
            hlInit = false;
            Sfx.back();
        } else {
            Sfx.bump();
        }
        repaint();
    }

    @Override
    public void page(int delta) {
        if (skipBoot()) return;
        if (modal() || tiles.isEmpty()) return;
        int pages = pageCount();
        int p = page() + delta;
        if (p < 0 || p >= pages) {
            Sfx.bump();
            return;
        }
        inDock = false;
        sel = Math.min(tiles.size() - 1, p * PER_PAGE + sel % PER_PAGE);
        Sfx.move();
        repaint();
    }

    @Override
    public void toggleGamepadInfo() {
        if (skipBoot()) return;
        if (playing != null || confirmQuit) return;
        showPad = !showPad;
        if (showPad) Sfx.select();
        else Sfx.back();
        repaint();
    }

    @Override
    public void refresh() {
        showToast("Looking for games...");
        actions.refresh();
    }

    private int page() {
        return tiles.isEmpty() ? 0 : sel / PER_PAGE;
    }

    private int pageCount() {
        return Math.max(1, (tiles.size() + PER_PAGE - 1) / PER_PAGE);
    }

    // ---- keyboard & mouse ---------------------------------------------------------------

    private void installInput() {
        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (skipBoot()) return;
                if (e.isControlDown() && e.getKeyCode() == KeyEvent.VK_Q) {
                    if (playing != null) actions.closeGame();
                    return;
                }
                switch (e.getKeyCode()) {
                    case KeyEvent.VK_LEFT, KeyEvent.VK_A -> navigate(-1, 0);
                    case KeyEvent.VK_RIGHT, KeyEvent.VK_D -> navigate(1, 0);
                    case KeyEvent.VK_UP, KeyEvent.VK_W -> navigate(0, -1);
                    case KeyEvent.VK_DOWN, KeyEvent.VK_S -> navigate(0, 1);
                    case KeyEvent.VK_ENTER, KeyEvent.VK_SPACE -> activate();
                    case KeyEvent.VK_ESCAPE, KeyEvent.VK_BACK_SPACE -> back();
                    case KeyEvent.VK_PAGE_UP, KeyEvent.VK_Q -> page(-1);
                    case KeyEvent.VK_PAGE_DOWN, KeyEvent.VK_E -> page(1);
                    case KeyEvent.VK_F1 -> actions.openSettings();
                    case KeyEvent.VK_F2 -> toggleGamepadInfo();
                    case KeyEvent.VK_F5 -> refresh();
                    case KeyEvent.VK_V -> toggleVisualizer();
                    default -> { }
                }
            }
        });
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                if (skipBoot()) return;
                if (playing != null) return;
                if (vizFull) {
                    back();
                    return;
                }
                if (showPad || confirmQuit) {
                    if (confirmQuit) {
                        List<Rectangle2D> rs = powerRects();
                        for (int i = 0; i < rs.size(); i++) {
                            if (rs.get(i).contains(e.getPoint())) {
                                powerSel = i;
                                choosePower(i);
                                return;
                            }
                        }
                    }
                    back();
                    return;
                }
                Layout L = geom();
                for (int i = 0; i < Dock.values().length; i++) {
                    if (dockRect(L, i).contains(e.getPoint())) {
                        inDock = true;
                        dockSel = i;
                        activate();
                        return;
                    }
                }
                for (int i = 0; i < tiles.size(); i++) {
                    if (tileRect(L, i, scrollPage).contains(e.getPoint())) {
                        boolean again = !inDock && sel == i;
                        inDock = false;
                        sel = i;
                        if (again || e.getClickCount() >= 2) activate();
                        else Sfx.move();
                        repaint();
                        return;
                    }
                }
                if (e.getButton() == MouseEvent.BUTTON3) back();
            }

            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                page(e.getWheelRotation() > 0 ? 1 : -1);
            }
        };
        addMouseListener(mouse);
        addMouseWheelListener(mouse);
    }

    // ---- animation ----------------------------------------------------------------------

    private void tick() {
        boolean dirty = tickBoot();
        long sec = System.currentTimeMillis() / 1000;
        if (sec != lastSecond) {
            lastSecond = sec;
            dirty = true;
        }
        if (toast != null && System.currentTimeMillis() > toastUntil + 400) {
            toast = null;
            dirty = true;
        }
        if (toast != null && System.currentTimeMillis() > toastUntil) dirty = true; // fading out
        if (playing == null && getWidth() > 0) {
            float target = page();
            if (Math.abs(scrollPage - target) > 0.001f) {
                scrollPage += (target - scrollPage) * 0.2f;
                if (Math.abs(scrollPage - target) < 0.002f) scrollPage = target;
                dirty = true;
            }
            Layout L = geom();
            Rectangle2D t = cursorTarget(L);
            if (t != null) {
                float[] goal = {(float) t.getX(), (float) t.getY(), (float) t.getWidth(), (float) t.getHeight()};
                if (!hlInit) {
                    System.arraycopy(goal, 0, hl, 0, 4);
                    hlInit = true;
                    dirty = true;
                }
                for (int i = 0; i < 4; i++) {
                    float d = goal[i] - hl[i];
                    if (Math.abs(d) > 0.3f) {
                        hl[i] += d * 0.32f;
                        dirty = true;
                    } else hl[i] = goal[i];
                }
            }
            if (revealAt > 0) {
                if (System.currentTimeMillis() - revealAt < TILE_IN_DELAY_MS * PER_PAGE + TILE_IN_MS) dirty = true;
                else revealAt = 0;                   // every tile has arrived
            }
            for (int i = 0; i < tiles.size(); i++) {
                Tile tile = tiles.get(i);
                float goal = (!inDock && i == sel && !modal()) ? 1.06f : 1f;
                if (Math.abs(tile.scale - goal) > 0.001f) {
                    tile.scale += (goal - tile.scale) * 0.25f;
                    dirty = true;
                }
            }
        }
        // the visualizer moves while music plays (about 30 frames a second)
        if ((vizFull || vizOn && playing == null && !booting) && (++vizTick & 1) == 0
                && (vizFull || MenuAudio.get().musicAudible() || vizFading())) dirty = true;
        if (dirty) repaint();
    }

    private int vizTick;
    private long vizQuietSince;

    /** True for a second after the music stops, so the bars sink instead of freezing. */
    private boolean vizFading() {
        if (MenuAudio.get().musicAudible()) {
            vizQuietSince = 0;
            return true;
        }
        if (vizQuietSince == 0) vizQuietSince = System.currentTimeMillis();
        return System.currentTimeMillis() - vizQuietSince < 1200;
    }

    /** V: the full-screen visualizer on and off. */
    public void toggleVisualizer() {
        if (playing != null || booting) return;
        vizFull = !vizFull;
        if (vizFull) Sfx.select();
        else Sfx.back();
        repaint();
    }

    /** Runs the animations to completion (for offscreen snapshots). */
    public void settle() {
        syncSprites = true;
        booting = false;
        revealed = true;
        revealAt = 0;
        for (int i = 0; i < 200; i++) tick();
    }

    private Rectangle2D cursorTarget(Layout L) {
        if (inDock) {
            Rectangle2D r = dockRect(L, dockSel);
            return new Rectangle2D.Double(r.getX() - 6, r.getY() - 6, r.getWidth() + 12, r.getHeight() + 12);
        }
        if (tiles.isEmpty()) return null;
        Rectangle2D r = tileRect(L, sel, page());
        double grow = 1.06, gw = r.getWidth() * grow, gh = r.getHeight() * grow;
        return new Rectangle2D.Double(r.getCenterX() - gw / 2 - 5, r.getCenterY() - gh / 2 - 5, gw + 10, gh + 10);
    }

    // ---- boot animation -----------------------------------------------------------------

    private double bootSeconds() {
        return bootStart == 0 ? 0 : (System.nanoTime() - bootStart) / 1e9;
    }

    /** Advances the start-up sequence; true while it needs frames. */
    private boolean tickBoot() {
        if (!booting) return false;
        if (bootStart == 0) {
            if (!isShowing() || getWidth() <= 0) return false;
            // start a moment after the window appears (macOS animates into full screen first)
            bootStart = System.nanoTime() + (long) (BOOT_DELAY * 1e9);
        }
        double t = bootSeconds();
        if (!chimed && t >= 0.75) {
            chimed = true;
            Sfx.boot();
        }
        if (!warmed && t >= 1.8) warmMenu();
        if (!revealed && t >= BOOT_REVEAL) reveal();
        if (t >= BOOT_END) {
            booting = false;
            bootBg = bootLogoImg = bootSheenImg = bootTagImg = null;     // free the caches
            bootCacheKey = null;
            setMusicEnabled(musicEnabled);                  // now synthesize the music, off the animation
        }
        return true;
    }

    // tiles rise into place and fade in one after another; drawn 1:1, so it stays cheap
    private static final long TILE_IN_MS = 380, TILE_IN_DELAY_MS = 40;

    /** 0..1: how far tile i has arrived after the boot screen (1 = in place). */
    private float tileAppear(int i) {
        if (!revealed) return 0f;
        if (revealAt == 0) return 1f;
        long since = System.currentTimeMillis() - revealAt - (i % PER_PAGE) * TILE_IN_DELAY_MS;
        return (float) easeOut(since / (double) TILE_IN_MS);
    }

    private boolean warmed;

    /**
     * While the logo shows, get the menu ready: queue the first page's tile pictures for the
     * background renderer and build the top bar and dock, so the first menu frames are cheap.
     */
    private void warmMenu() {
        warmed = true;
        if (getWidth() <= 0) return;
        Layout L = geom();
        int first = page() * PER_PAGE;
        for (int i = first; i < Math.min(tiles.size(), first + PER_PAGE); i++) {
            sprite(tiles.get(i), L, 1f);
            if (i == sel) sprite(tiles.get(i), L, SPRITE_SCALE);
        }
        gc = getGraphicsConfiguration();
        // the background, top bar and dock take a few hundred ms to draw the first time: do it on a
        // worker thread and hand the pictures over, so the spinner keeps turning meanwhile
        int w = getWidth(), h = getHeight();
        Thread t = new Thread(() -> {
            String top = topKeyFor(L), dock = dockKeyFor(L);
            BufferedImage b = renderBackground(w, h), tb = renderTopBar(L), d = renderDock(L);
            SwingUtilities.invokeLater(() -> {
                if (bg == null && getWidth() == w && getHeight() == h) bg = b;
                if (topKey == null && top.equals(topKeyFor(geom()))) {
                    topCache = tb;
                    topKey = top;
                }
                if (dockKey == null && dock.equals(dockKeyFor(geom()))) {
                    dockCache = d;
                    dockKey = dock;
                }
            });
        }, "menu-warmup");
        t.setDaemon(true);
        t.start();
    }

    private void reveal() {
        revealed = true;
        revealAt = System.currentTimeMillis();
        updateMusic();
    }

    /** Any key, click or GamePad button skips ahead to the menu. */
    private boolean skipBoot() {
        if (!booting || revealed || bootStart == 0) return false;     // not on screen yet
        bootStart -= (long) ((BOOT_REVEAL - bootSeconds()) * 1e9);
        chimed = true;                                      // skipping means no chime
        reveal();
        repaint();
        return true;
    }

    private static double clamp01(double v) {
        return Math.max(0, Math.min(1, v));
    }

    private static double easeOut(double v) {
        v = clamp01(v);
        return 1 - Math.pow(1 - v, 3);
    }

    /**
     * Everything the boot screen draws is rendered once into images at the window's size, so each
     * frame only blits a few pictures (text outlines and gradients are far too slow to redo 60
     * times a second, especially on a Retina screen).
     */
    private void prepareBoot(Layout L) {
        String key = (int) L.w + "x" + (int) L.h;
        if (key.equals(bootCacheKey)) return;
        int w = (int) L.w + 1, h = (int) L.h + 1;
        GraphicsConfiguration gc = this.gc;
        bootBg = gc != null ? gc.createCompatibleImage(w, h, Transparency.OPAQUE) : new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D b = bootBg.createGraphics();
        b.setPaint(new GradientPaint(0, 0, BOOT_TOP, 0, h, BOOT_BOTTOM));
        b.fillRect(0, 0, w, h);
        b.dispose();

        // the logo: "WII-" in text grey, "UU" in the accent blue; plus a white copy for the sheen
        float size = L.h * 0.12f;
        Font f = font(Font.BOLD, size);
        java.awt.font.FontRenderContext frc = new java.awt.font.FontRenderContext(null, true, true);
        java.awt.font.GlyphVector a = f.createGlyphVector(frc, "WII-"), u = f.createGlyphVector(frc, "UU");
        float splitX = (float) a.getLogicalBounds().getWidth();
        Shape left = a.getOutline(), right = u.getOutline(splitX, 0);
        Rectangle2D bb = left.getBounds2D().createUnion(right.getBounds2D());
        int pad = (int) (size * 0.08f);
        int lw = (int) Math.ceil(bb.getWidth()) + pad * 2, lh = (int) Math.ceil(bb.getHeight()) + pad * 2;
        bootLogoImg = layer(lw, lh);
        bootSheenImg = layer(lw, lh);
        for (BufferedImage img : new BufferedImage[]{bootLogoImg, bootSheenImg}) {
            Graphics2D lg = img.createGraphics();
            quality(lg);
            lg.translate(pad - bb.getX(), pad - bb.getY());
            boolean sheen = img == bootSheenImg;
            lg.setColor(sheen ? Color.WHITE : TEXT);
            lg.fill(left);
            lg.setColor(sheen ? Color.WHITE : ACCENT);
            lg.fill(right);
            lg.dispose();
        }

        Font tf = font(Font.PLAIN, L.h * 0.028f);
        String line = "Your games, the Wii U way";
        FontMetrics fm = getFontMetrics(tf);
        bootTagImg = layer(fm.stringWidth(line) + 4, fm.getHeight() + 4);
        Graphics2D tg = bootTagImg.createGraphics();
        quality(tg);
        tg.setFont(tf);
        tg.setColor(TEXT_DIM);
        tg.drawString(line, 2, 2 + fm.getAscent());
        tg.dispose();
        bootCacheKey = key;
    }

    /**
     * White start screen: a soft ring of light spreads, the WII-UU logo rises into place and a sheen
     * crosses it, a tagline and a spinner appear, then everything fades into the menu.
     */
    private void paintBoot(Graphics2D g0, Layout L) {
        double t = bootSeconds();
        float fade = (float) (1 - easeOut((t - BOOT_REVEAL) / (BOOT_END - BOOT_REVEAL)));
        if (fade <= 0) return;
        prepareBoot(L);
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, fade));
        float w = L.w, h = L.h, cx = w / 2, cy = h * 0.46f;
        if (fade >= 1f) {
            g.drawImage(bootBg, 0, 0, null);
        } else {
            // fading out: a flat fill blends far faster than a full-screen picture
            g.setComposite(AlphaComposite.Src);
            g.setColor(new Color(BOOT_FADE.getRed(), BOOT_FADE.getGreen(), BOOT_FADE.getBlue(), Math.round(fade * 255)));
            g.setComposite(AlphaComposite.SrcOver);
            g.fillRect(0, 0, (int) w + 1, (int) h + 1);
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, fade));
        }

        // a ring of light spreading from the centre
        double ring = clamp01((t - 0.25) / 1.6);
        if (ring > 0 && ring < 1) {
            float rad = (float) (easeOut(ring) * w * 0.42f);
            float ra = (float) ((1 - ring) * 0.35);
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, fade * ra));
            g.setColor(ACCENT);
            g.setStroke(new BasicStroke(h * 0.006f));
            g.draw(new Ellipse2D.Float(cx - rad, cy - rad, rad * 2, rad * 2));
        }

        // the logo rises and settles; while fading out it zooms gently towards the viewer
        double in = easeOut((t - 0.35) / 0.7);
        if (in > 0) {
            int lw = bootLogoImg.getWidth(), lh = bootLogoImg.getHeight();
            double zoom = (0.94 + 0.06 * in) * (1 + 0.06 * (1 - fade));
            java.awt.geom.AffineTransform at = new java.awt.geom.AffineTransform();
            at.translate(cx, cy + (1 - in) * h * 0.03);
            at.scale(zoom, zoom);
            at.translate(-lw / 2.0, -lh / 2.0);
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) (fade * in)));
            g.drawImage(bootLogoImg, at, null);
            // a sheen sweeps across the letters: the white copy, shown through a few soft strips
            double sweep = (t - 1.05) / 0.8;
            if (sweep > 0 && sweep < 1) {
                double centre = -lw * 0.2 + sweep * lw * 1.4, band = lw * 0.16;
                Graphics2D sg = (Graphics2D) g.create();
                sg.transform(at);
                Shape base = sg.getClip();
                int strips = 7;
                for (int k = 0; k < strips; k++) {
                    double x0 = centre - band / 2 + k * band / strips;
                    double edge = Math.abs(k - (strips - 1) / 2.0) / ((strips - 1) / 2.0);  // 0 middle .. 1 edge
                    sg.setClip(base);
                    sg.clip(new Rectangle2D.Double(x0, 0, band / strips + 0.5, lh));
                    sg.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) (fade * 0.7 * (1 - edge * edge))));
                    sg.drawImage(bootSheenImg, 0, 0, null);
                }
                sg.dispose();
            }
        }

        // tagline
        double tag = clamp01((t - 1.45) / 0.5);
        if (tag > 0) {
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) (fade * tag)));
            g.drawImage(bootTagImg, Math.round(cx - bootTagImg.getWidth() / 2f),
                    Math.round((float) (cy + h * 0.1f - bootTagImg.getHeight() * 0.75f + (1 - tag) * h * 0.01f)), null);
        }

        // spinner, bottom right: twelve dots, a bright one chasing round
        double spin = clamp01((t - 1.7) / 0.4);
        if (spin > 0) {
            float r = h * 0.028f, sx = w - h * 0.09f, sy = h - h * 0.09f, dot = h * 0.0065f;
            g.setColor(ACCENT);
            for (int i = 0; i < 12; i++) {
                double ang = i * Math.PI / 6 - Math.PI / 2;
                double lead = ((t * 12) % 12 - i + 12) % 12;          // 0 = brightest
                float a = (float) (fade * spin * (0.18 + 0.82 * Math.max(0, 1 - lead / 6)));
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, a));
                g.fill(new Ellipse2D.Double(sx + Math.cos(ang) * r - dot, sy + Math.sin(ang) * r - dot, dot * 2, dot * 2));
            }
        }
        g.dispose();
    }

    // ---- layout -------------------------------------------------------------------------

    private Layout geom() {
        float w = Math.max(getWidth(), 640), h = Math.max(getHeight(), 400);
        float topH = h * 0.1f;
        float dockH = h * 0.17f;
        float dockY = h - dockH;
        float marginX = w * 0.06f;
        float gridX = marginX, gridY = topH + h * 0.035f;
        float gridW = w - 2 * marginX, gridH = dockY - gridY - h * 0.05f;
        float cellW = gridW / COLS, cellH = gridH / ROWS;
        float tileH = cellH * 0.84f;
        float tileW = Math.min(cellW * 0.88f, tileH * 1.25f);
        return new Layout(w, h, topH, gridX, gridY, gridW, gridH, cellW, cellH, tileW, tileH, dockY, dockH);
    }

    private Rectangle2D tileRect(Layout L, int index, float scroll) {
        int page = index / PER_PAGE, local = index % PER_PAGE;
        int r = local / COLS, c = local % COLS;
        float x = L.gridX + (page - scroll) * (L.w) + c * L.cellW + (L.cellW - L.tileW) / 2;
        float y = L.gridY + r * L.cellH + (L.cellH - L.tileH) / 2;
        return new Rectangle2D.Float(x, y, L.tileW, L.tileH);
    }

    private Rectangle2D dockRect(Layout L, int i) {
        int n = Dock.values().length;
        float size = L.dockH * 0.48f;
        float gap = size * 1.9f;
        float total = n * size + (n - 1) * (gap - size);
        float x = (L.w - total) / 2 + i * gap;
        float y = L.dockY + L.dockH * 0.14f;
        return new Rectangle2D.Float(x, y, size, size);
    }

    /** The Power menu card: a title, then one full-width button per choice. */
    private Rectangle2D powerCard() {
        float w = Math.max(getWidth(), 640), h = Math.max(getHeight(), 400);
        int n = powerItems().size();
        float bh = h * 0.075f, gap = h * 0.018f;
        float cw = Math.min(w * 0.4f, 520), ch = h * 0.16f + n * (bh + gap) + h * 0.03f;
        return new Rectangle2D.Float((w - cw) / 2, (h - ch) / 2, cw, ch);
    }

    private List<Rectangle2D> powerRects() {
        Rectangle2D card = powerCard();
        float h = Math.max(getHeight(), 400), bh = h * 0.075f, gap = h * 0.018f;
        float x = (float) card.getX() + (float) card.getWidth() * 0.1f, bw = (float) card.getWidth() * 0.8f;
        List<Rectangle2D> out = new ArrayList<>();
        for (int i = 0; i < powerItems().size(); i++) {
            out.add(new Rectangle2D.Float(x, (float) card.getY() + h * 0.16f + i * (bh + gap), bw, bh));
        }
        return out;
    }

    // ---- painting -----------------------------------------------------------------------

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        Layout L = geom();
        gc = getGraphicsConfiguration();
        if (booting && !revealed) {
            // the boot screen covers the menu completely: don't spend time drawing the menu too
            paintBoot(g, L);
            g.dispose();
            Toolkit.getDefaultToolkit().sync();
            return;
        }
        paintBackground(g);
        boolean vizStrip = vizOn && playing == null;
        if (vizStrip || vizFull) viz.update();
        if (vizStrip && !vizFull) viz.paintAmbient(g, getWidth(), (float) L.dockY - 8, getHeight() * 0.16f, ACCENT);
        paintTopBarCached(g, L);
        if (tiles.isEmpty()) paintEmpty(g, L);
        else paintGrid(g, L);
        paintDockCached(g, L);
        paintDockCursor(g);
        paintToast(g, L);
        if (playing != null) paintNowPlaying(g, L);
        else if (showPad) paintPadOverlay(g, L);
        else if (confirmQuit) paintConfirm(g);
        if (vizFull) {
            boolean mix = ExtendedMix.ID.equals(musicChoice);
            viz.paintFull(g, getWidth(), getHeight(), musicEnabled ? musicTitle : "Music is off",
                    !musicEnabled ? "Turn it on in Settings (F1) > General" : mix ? "WII-UU Extended Mix" : "Menu music");
        }
        if (booting) paintBoot(g, L);
        g.dispose();
        Toolkit.getDefaultToolkit().sync(); // flush X11 so animation doesn't stutter on Linux
    }

    private static void quality(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    }

    /** Translucent image in the screen's native format, so blitting it is cheap. */
    private BufferedImage layer(int w, int h) {
        w = Math.max(1, w);
        h = Math.max(1, h);
        GraphicsConfiguration gc = this.gc;
        return gc != null ? gc.createCompatibleImage(w, h, Transparency.TRANSLUCENT)
                : new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
    }

    private String topKeyFor(Layout L) {
        return (int) L.w + "x" + (int) L.h + "|" + (screen == Screen.GAMES && openSystem != null ? openSystem.id() : "")
                + "|" + pads + "|" + serverOn + "|" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmm"));
    }

    private String dockKeyFor(Layout L) {
        return (int) L.w + "x" + (int) L.h + "|" + screen + "|" + inDock + "|" + dockSel;
    }

    private BufferedImage renderTopBar(Layout L) {
        BufferedImage img = layer((int) L.w, (int) (L.topH * 1.2f));
        Graphics2D c = img.createGraphics();
        quality(c);
        paintTopBar(c, L);
        c.dispose();
        return img;
    }

    private BufferedImage renderDock(Layout L) {
        int top = (int) L.dockY - 8;
        BufferedImage img = layer((int) L.w, (int) L.h - top);
        Graphics2D c = img.createGraphics();
        quality(c);
        c.translate(0, -top);
        paintDock(c, L);
        c.dispose();
        return img;
    }

    private void paintTopBarCached(Graphics2D g, Layout L) {
        String key = topKeyFor(L);
        if (!key.equals(topKey)) {
            topCache = renderTopBar(L);
            topKey = key;
        }
        g.drawImage(topCache, 0, 0, null);
    }

    private void paintDockCached(Graphics2D g, Layout L) {
        String key = dockKeyFor(L);
        if (!key.equals(dockKey)) {
            dockCache = renderDock(L);
            dockKey = key;
        }
        g.drawImage(dockCache, 0, (int) L.dockY - 8, null);
    }

    private void paintBackground(Graphics2D g) {
        int w = getWidth(), h = getHeight();
        if (bg == null || bg.getWidth() != w || bg.getHeight() != h) bg = renderBackground(w, h);
        g.drawImage(bg, 0, 0, null);
    }

    private static BufferedImage renderBackground(int w, int h) {
        {
            BufferedImage bg = new BufferedImage(Math.max(1, w), Math.max(1, h), BufferedImage.TYPE_INT_RGB);
            Graphics2D b = bg.createGraphics();
            b.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            b.setPaint(new GradientPaint(0, 0, BG_TOP, 0, h, BG_BOTTOM));
            b.fillRect(0, 0, w, h);
            // the Wii U menu's faint pinstripes
            b.setColor(STRIPE);
            for (int y = 0; y < h; y += 6) b.fillRect(0, y, w, 2);
            // soft light blooms
            b.setPaint(new java.awt.RadialGradientPaint(w * 0.18f, h * 0.2f, w * 0.5f,
                    new float[]{0, 1}, new Color[]{BLOOM, new Color(BLOOM.getRed(), BLOOM.getGreen(), BLOOM.getBlue(), 0)}));
            b.fillRect(0, 0, w, h);
            b.setPaint(new java.awt.RadialGradientPaint(w * 0.9f, h * 0.95f, w * 0.45f,
                    new float[]{0, 1}, new Color[]{BLOOM_ACCENT, new Color(BLOOM_ACCENT.getRed(), BLOOM_ACCENT.getGreen(), BLOOM_ACCENT.getBlue(), 0)}));
            b.fillRect(0, 0, w, h);
            b.dispose();
            return bg;
        }
    }

    private void paintTopBar(Graphics2D g, Layout L) {
        float pad = L.w * 0.03f, cy = L.topH * 0.55f, pillH = L.topH * 0.56f;
        // logo pill
        Font logo = font(Font.BOLD, pillH * 0.5f);
        g.setFont(logo);
        FontMetrics fm = g.getFontMetrics();
        String a = "WII-", b = "UU";
        String crumb = screen == Screen.GAMES && openSystem != null ? openSystem.name() : "Home";
        Font crumbFont = font(Font.PLAIN, pillH * 0.4f);
        float crumbW = g.getFontMetrics(crumbFont).stringWidth(crumb);
        float pillW = fm.stringWidth(a + b) + crumbW + pillH * 1.5f;
        RoundRectangle2D pill = new RoundRectangle2D.Float(pad, cy - pillH / 2, pillW, pillH, pillH, pillH);
        shadow(g, pill, 3);
        g.setColor(CARD);
        g.fill(pill);
        float tx = pad + pillH * 0.45f, ty = cy + fm.getAscent() * 0.36f;
        g.setColor(TEXT);
        g.drawString(a, tx, ty);
        g.setColor(ACCENT);
        g.drawString(b, tx + fm.stringWidth(a), ty);
        float sepX = tx + fm.stringWidth(a + b) + pillH * 0.3f;
        g.setColor(DIVIDER);
        g.fill(new Rectangle2D.Float(sepX, cy - pillH * 0.25f, 2, pillH * 0.5f));
        g.setFont(crumbFont);
        g.setColor(TEXT_DIM);
        g.drawString(crumb, sepX + pillH * 0.3f, cy + g.getFontMetrics().getAscent() * 0.36f);

        // clock
        LocalDateTime now = LocalDateTime.now();
        String time = now.format(DateTimeFormatter.ofPattern("HH:mm"));
        String date = now.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()));
        Font tf = font(Font.BOLD, pillH * 0.52f);
        Font df = font(Font.PLAIN, pillH * 0.34f);
        float timeW = g.getFontMetrics(tf).stringWidth(time), dateW = g.getFontMetrics(df).stringWidth(date);
        float clockW = timeW + dateW + pillH * 1.1f;
        float clockX = L.w - pad - clockW;
        RoundRectangle2D clock = new RoundRectangle2D.Float(clockX, cy - pillH / 2, clockW, pillH, pillH, pillH);
        shadow(g, clock, 3);
        g.setColor(CARD);
        g.fill(clock);
        g.setFont(tf);
        g.setColor(TEXT);
        g.drawString(time, clockX + pillH * 0.45f, cy + g.getFontMetrics().getAscent() * 0.36f);
        g.setFont(df);
        g.setColor(TEXT_DIM);
        g.drawString(date, clockX + pillH * 0.65f + timeW, cy + g.getFontMetrics().getAscent() * 0.36f);

        // gamepad status
        String pt = !serverOn ? "GamePad off" : pads == 0 ? "Press + to connect" : pads == 1 ? "1 GamePad" : pads + " GamePads";
        Font pf = font(Font.BOLD, pillH * 0.34f);
        float ptW = g.getFontMetrics(pf).stringWidth(pt);
        float padW = ptW + pillH * 1.6f;
        float padX = clockX - pillH * 0.3f - padW;
        RoundRectangle2D pp = new RoundRectangle2D.Float(padX, cy - pillH / 2, padW, pillH, pillH, pillH);
        shadow(g, pp, 3);
        g.setColor(pads > 0 ? ACCENT : CARD);
        g.fill(pp);
        Color fg = pads > 0 ? Color.WHITE : TEXT_DIM;
        drawGamepadIcon(g, padX + pillH * 0.35f, cy - pillH * 0.2f, pillH * 0.62f, pillH * 0.4f, fg);
        g.setFont(pf);
        g.setColor(fg);
        g.drawString(pt, padX + pillH * 1.15f, cy + g.getFontMetrics().getAscent() * 0.36f);
    }

    private void paintGrid(Graphics2D g, Layout L) {
        Shape oldClip = g.getClip();
        g.clip(new Rectangle2D.Float(0, L.topH, L.w, L.dockY - L.topH));
        int first = Math.max(0, (int) Math.floor(scrollPage) * PER_PAGE);
        int last = Math.min(tiles.size() - 1, ((int) Math.ceil(scrollPage) + 1) * PER_PAGE - 1);
        for (int i = first; i <= last; i++) {
            if (i == sel && !inDock) continue; // selected tile drawn last, on top
            paintTile(g, L, i);
        }
        if (!inDock && sel >= first && sel <= last) paintTile(g, L, sel);
        if (!modal() && hlInit && !inDock) paintCursor(g, 26);
        g.setClip(oldClip);
        // warm up the neighbouring pages in the background so paging is instant
        int cur = page();
        for (int i = Math.max(0, (cur - 1) * PER_PAGE); i < Math.min(tiles.size(), (cur + 2) * PER_PAGE); i++) {
            sprite(tiles.get(i), L, 1f);
        }

        // page dots
        int pages = pageCount();
        if (pages > 1) {
            float d = L.h * 0.012f, gap = d * 2.4f;
            float x0 = (L.w - (pages - 1) * gap) / 2, y = L.dockY - L.h * 0.025f;
            for (int p = 0; p < pages; p++) {
                g.setColor(p == page() ? ACCENT : DOT_OFF);
                float s = p == page() ? d * 1.35f : d;
                g.fill(new Ellipse2D.Float(x0 + p * gap - s / 2, y - s / 2, s, s));
            }
        }
    }

    private void paintCursor(Graphics2D g, float radius) {
        RoundRectangle2D r = new RoundRectangle2D.Float(hl[0], hl[1], hl[2], hl[3], radius, radius);
        g.setColor(new Color(ACCENT.getRed(), ACCENT.getGreen(), ACCENT.getBlue(), 60));
        g.setStroke(new BasicStroke(12f));
        g.draw(r);
        g.setColor(ACCENT);
        g.setStroke(new BasicStroke(4.5f));
        g.draw(r);
        g.setStroke(new BasicStroke(1));
    }

    private void paintTile(Graphics2D g, Layout L, int i) {
        Tile t = tiles.get(i);
        Rectangle2D base = tileRect(L, i, scrollPage);
        if (base.getMaxX() < -40 || base.getX() > L.w + 40) return;
        float appear = tileAppear(i);
        if (appear <= 0f) return;
        if (appear < 1f) {
            Graphics2D ag = (Graphics2D) g.create();
            ag.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, appear));
            ag.translate(0, Math.round((1 - appear) * L.h * 0.04f));
            paintTileNow(ag, L, i, t, base);
            ag.dispose();
            return;
        }
        paintTileNow(g, L, i, t, base);
    }

    private void paintTileNow(Graphics2D g, Layout L, int i, Tile t, Rectangle2D base) {
        boolean zoomed = Math.abs(t.scale - 1f) >= 0.002f;
        BufferedImage rest = sprite(t, L, 1f);
        BufferedImage sprite = zoomed ? sprite(t, L, SPRITE_SCALE) : null;
        if (sprite == null) {
            if (rest == null) {
                paintPlaceholder(g, t, base);
                return;
            }
            if (!zoomed) {
                // resting tile: plain 1:1 copy, the cheapest possible draw
                g.drawImage(rest, (int) Math.round(base.getCenterX() - rest.getWidth() / 2.0),
                        (int) Math.round(base.getCenterY() - rest.getHeight() / 2.0), null);
                return;
            }
            sprite = rest; // zoom sprite still rendering: stretch the 1:1 one for a frame
        }
        double f = t.scale / (sprite == rest ? 1f : SPRITE_SCALE);
        double w = sprite.getWidth() * f, h = sprite.getHeight() * f;
        int x = (int) Math.round(base.getCenterX() - w / 2), y = (int) Math.round(base.getCenterY() - h / 2);
        if (Math.abs(f - 1) < 1e-3) g.drawImage(sprite, x, y, null);
        else g.drawImage(sprite, x, y, (int) Math.round(w), (int) Math.round(h), null);
    }

    /** @return the cached sprite, or null after queueing it for background rendering */
    private BufferedImage sprite(Tile t, Layout L, float scale) {
        BufferedImage cover = t.game == null ? null : cover(t.game);
        Object key = List.of(t.id(), (int) L.tileW, (int) L.tileH, scale,
                cover == null ? "" : System.identityHashCode(cover), t.count, dark);
        synchronized (sprites) {
            BufferedImage img = sprites.get(key);
            if (img != null) return img;
        }
        if (syncSprites) {
            storeSprite(key, renderSprite(t, L, scale));
            return sprite(t, L, scale);
        }
        if (spritePending.add(key)) {
            spriteRenderer.execute(() -> {
                try {
                    storeSprite(key, renderSprite(t, L, scale));
                } finally {
                    spritePending.remove(key);
                }
                repaint();
            });
        }
        return null;
    }

    private void storeSprite(Object key, BufferedImage img) {
        synchronized (sprites) {
            BufferedImage old = sprites.put(key, img);
            if (old != null) spriteBytes -= bytes(old);
            spriteBytes += bytes(img);
            var it = sprites.entrySet().iterator();
            while (spriteBytes > SPRITE_BUDGET_BYTES && it.hasNext()) {
                var e = it.next();
                if (e.getKey().equals(key)) continue;
                spriteBytes -= bytes(e.getValue());
                it.remove();
            }
        }
    }

    private static long bytes(BufferedImage img) {
        return 4L * img.getWidth() * img.getHeight();
    }

    /** Cheap stand-in shown for the frame or two before a tile's sprite is ready. */
    private static void paintPlaceholder(Graphics2D g, Tile t, Rectangle2D r) {
        float rad = (float) Math.min(r.getWidth(), r.getHeight()) * 0.12f;
        g.setColor(new Color(SHADOW_RGB.getRed(), SHADOW_RGB.getGreen(), SHADOW_RGB.getBlue(), 18 * shadowBoost));
        g.fill(new RoundRectangle2D.Double(r.getX(), r.getY() + 3, r.getWidth(), r.getHeight(), rad, rad));
        g.setColor(CARD);
        g.fill(new RoundRectangle2D.Double(r.getX(), r.getY(), r.getWidth(), r.getHeight(), rad, rad));
        g.setColor(t.game == null ? t.system.color() : vary(t.system.color(), t.game.name()));
        g.fill(new RoundRectangle2D.Double(r.getX(), r.getY(), r.getWidth(), r.getHeight() * 0.72, rad, rad));
        g.fill(new Rectangle2D.Double(r.getX(), r.getY() + rad, r.getWidth(), r.getHeight() * 0.72 - rad));
    }

    /** Renders a tile (card, art, text and soft shadow) once; re-used every frame until it changes. */
    private BufferedImage renderSprite(Tile t, Layout L, float scale) {
        float w = L.tileW * scale, h = L.tileH * scale;
        int pad = 16;
        BufferedImage img = layer((int) Math.ceil(w) + 2 * pad, (int) Math.ceil(h) + 2 * pad);
        Graphics2D g = img.createGraphics();
        quality(g);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        float rad = Math.min(w, h) * 0.12f;
        RoundRectangle2D card = new RoundRectangle2D.Float(pad, pad, w, h, rad, rad);
        shadow(g, card, 6);
        g.setColor(CARD);
        g.fill(card);
        if (t.game == null) paintSystemTile(g, t, card);
        else paintGameTile(g, t, card);
        g.setColor(OUTLINE);
        g.setStroke(new BasicStroke(1f));
        g.draw(card);
        g.dispose();
        return img;
    }

    private void paintSystemTile(Graphics2D g, Tile t, RoundRectangle2D card) {
        float x = (float) card.getX(), y = (float) card.getY(), w = (float) card.getWidth(), h = (float) card.getHeight();
        float artH = h * 0.7f;
        Shape oldClip = g.getClip();
        g.clip(card);
        Color c = t.system.color();
        g.setPaint(new GradientPaint(x, y, brighter(c, 0.18f), x, y + artH, darker(c, 0.12f)));
        g.fill(new Rectangle2D.Float(x, y, w, artH));
        // glossy sheen
        g.setPaint(new GradientPaint(x, y, new Color(255, 255, 255, 70), x, y + artH * 0.5f, new Color(255, 255, 255, 0)));
        g.fill(new Rectangle2D.Float(x, y, w, artH * 0.5f));
        g.setClip(oldClip);

        g.setColor(new Color(255, 255, 255, 190));
        g.setFont(font(Font.BOLD, h * 0.075f));
        g.drawString(t.system.maker().toUpperCase(Locale.ROOT), x + w * 0.07f, y + h * 0.13f);
        String yr = Integer.toString(t.system.year());
        g.drawString(yr, x + w * 0.93f - g.getFontMetrics().stringWidth(yr), y + h * 0.13f);

        g.setColor(Color.WHITE);
        drawFitted(g, t.system.shortName(), Font.BOLD, x + w / 2, y + artH * 0.58f, w * 0.8f, artH * 0.42f);

        g.setColor(TEXT);
        g.setFont(font(Font.BOLD, h * 0.085f));
        drawCentered(g, ellipsize(g, t.system.name(), w * 0.9f), x + w / 2, y + artH + h * 0.13f);
        g.setColor(t.count > 0 ? ACCENT : TEXT_DIM);
        g.setFont(font(Font.PLAIN, h * 0.07f));
        drawCentered(g, t.count == 0 ? "No games yet" : t.count == 1 ? "1 game" : t.count + " games",
                x + w / 2, y + artH + h * 0.24f);
    }

    private void paintGameTile(Graphics2D g, Tile t, RoundRectangle2D card) {
        float x = (float) card.getX(), y = (float) card.getY(), w = (float) card.getWidth(), h = (float) card.getHeight();
        BufferedImage cover = cover(t.game);
        Shape oldClip = g.getClip();
        g.clip(card);
        if (cover != null && cover != NO_COVER) {
            double s = Math.max(w / cover.getWidth(), h / cover.getHeight());
            double iw = cover.getWidth() * s, ih = cover.getHeight() * s;
            g.drawImage(cover, (int) (x + (w - iw) / 2), (int) (y + (h - ih) / 2), (int) iw, (int) ih, null);
            g.setPaint(new GradientPaint(x, y + h * 0.55f, new Color(0, 0, 0, 0), x, y + h, new Color(0, 0, 0, 170)));
            g.fill(new Rectangle2D.Float(x, y + h * 0.55f, w, h * 0.45f));
            g.setClip(oldClip);
            g.setColor(Color.WHITE);
            g.setFont(font(Font.BOLD, h * 0.085f));
            drawCentered(g, ellipsize(g, t.game.name(), w * 0.9f), x + w / 2, y + h * 0.92f);
            return;
        }
        Color c = vary(t.system.color(), t.game.name());
        float artH = h * 0.72f;
        g.setPaint(new GradientPaint(x, y, brighter(c, 0.3f), x + w, y + artH, c));
        g.fill(new Rectangle2D.Float(x, y, w, artH));
        // decorative rings
        g.setColor(new Color(255, 255, 255, 30));
        g.setStroke(new BasicStroke(h * 0.04f));
        g.draw(new Ellipse2D.Float(x + w * 0.55f, y - h * 0.15f, w * 0.7f, w * 0.7f));
        g.draw(new Ellipse2D.Float(x - w * 0.25f, y + artH * 0.45f, w * 0.5f, w * 0.5f));
        g.setStroke(new BasicStroke(1));
        g.setClip(oldClip);
        g.setColor(Color.WHITE);
        drawFitted(g, initials(t.game.name()), Font.BOLD, x + w / 2, y + artH * 0.52f, w * 0.7f, artH * 0.5f);
        g.setColor(new Color(255, 255, 255, 200));
        g.setFont(font(Font.BOLD, h * 0.065f));
        g.drawString(t.system.shortName(), x + w * 0.07f, y + h * 0.12f);
        g.setColor(TEXT);
        g.setFont(font(Font.BOLD, h * 0.08f));
        List<String> lines = wrap(g, t.game.name(), w * 0.88f, 2);
        float ly = y + artH + h * (lines.size() == 1 ? 0.17f : 0.11f);
        for (String line : lines) {
            drawCentered(g, line, x + w / 2, ly);
            ly += h * 0.1f;
        }
    }

    private void paintEmpty(Graphics2D g, Layout L) {
        float cw = L.w * 0.62f, ch = L.gridH * 0.62f;
        float cx = (L.w - cw) / 2, cy = L.gridY + (L.gridH - ch) / 2;
        RoundRectangle2D card = new RoundRectangle2D.Float(cx, cy, cw, ch, 36, 36);
        shadow(g, card, 6);
        g.setColor(CARD);
        g.fill(card);
        g.setColor(TEXT);
        g.setFont(font(Font.BOLD, ch * 0.1f));
        String title = openSystem == null ? "No systems enabled" : "No " + openSystem.shortName() + " games yet";
        drawCentered(g, title, L.w / 2, cy + ch * 0.22f);
        g.setFont(font(Font.PLAIN, ch * 0.06f));
        g.setColor(TEXT_DIM);
        if (openSystem != null) {
            String exts = String.join(", ", openSystem.markers().isEmpty()
                    ? openSystem.extensions().stream().sorted().map(e -> "." + e).toList()
                    : openSystem.markers().stream().map(m -> m + " (game folders)").toList());
            float ly = cy + ch * 0.38f;
            for (String s : new String[]{"Copy your games (" + exts + ") into:",
                    config.romDir(openSystem).toString(),
                    "",
                    "Emulator: " + openSystem.emulator() + "   \u2014   " + ellipsize(g, config.command(openSystem), cw * 0.5f),
                    "Press F5 / GamePad \u2212 to refresh, B to go back."}) {
                if (s.equals(config.romDir(openSystem).toString())) {
                    g.setColor(ACCENT);
                    g.setFont(font(Font.BOLD, ch * 0.06f));
                } else {
                    g.setColor(TEXT_DIM);
                    g.setFont(font(Font.PLAIN, ch * 0.055f));
                }
                drawCentered(g, ellipsize(g, s, cw * 0.9f), L.w / 2, ly);
                ly += ch * 0.1f;
            }
        }
    }

    private void paintDock(Graphics2D g, Layout L) {
        // translucent dock shelf
        Rectangle2D first = dockRect(L, 0), lastDock = dockRect(L, Dock.values().length - 1);
        float shelfX = (float) (first.getX() - first.getWidth() * 0.9f);
        float shelfW = (float) (lastDock.getMaxX() + first.getWidth() * 0.9f) - shelfX;
        RoundRectangle2D shelf = new RoundRectangle2D.Float(shelfX, L.dockY + L.dockH * 0.02f, shelfW,
                L.dockH * 0.9f, L.dockH * 0.5f, L.dockH * 0.5f);
        g.setColor(SHELF);
        g.fill(shelf);
        Dock[] items = Dock.values();
        for (int i = 0; i < items.length; i++) {
            Rectangle2D r = dockRect(L, i);
            Ellipse2D circle = new Ellipse2D.Double(r.getX(), r.getY(), r.getWidth(), r.getHeight());
            shadow(g, circle, 4);
            g.setColor(CARD);
            g.fill(circle);
            g.setColor(OUTLINE);
            g.draw(circle);
            Color ic = inDock && dockSel == i ? ACCENT : ICON;
            drawDockIcon(g, items[i], r, ic);
            g.setColor(inDock && dockSel == i ? ACCENT : TEXT_DIM);
            g.setFont(font(Font.BOLD, L.dockH * 0.12f));
            drawCentered(g, items[i].label, (float) r.getCenterX(), (float) (r.getMaxY() + L.dockH * 0.2f));
        }
        // hint line
        g.setFont(font(Font.PLAIN, L.dockH * 0.1f));
        g.setColor(TEXT_DIM);
        String hint = screen == Screen.HOME ? "A: Open   B: Back   L/R Page   + GamePad   \u2212 Refresh"
                : "A: Play   B: Back   L/R Page   + GamePad   \u2212 Refresh";
        g.drawString(hint, L.w * 0.03f, L.h - L.dockH * 0.12f);
    }

    private void paintDockCursor(Graphics2D g) {
        if (!inDock || modal() || !hlInit) return;
        RoundRectangle2D ring = new RoundRectangle2D.Float(hl[0], hl[1], hl[2], hl[3], hl[2], hl[3]);
        g.setColor(ACCENT);
        g.setStroke(new BasicStroke(4f));
        g.draw(ring);
        g.setStroke(new BasicStroke(1));
    }

    private void drawDockIcon(Graphics2D g, Dock d, Rectangle2D r, Color c) {
        float cx = (float) r.getCenterX(), cy = (float) r.getCenterY(), s = (float) r.getWidth() * 0.42f;
        g.setColor(c);
        g.setStroke(new BasicStroke(s * 0.14f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        switch (d) {
            case GAMEPAD -> drawGamepadIcon(g, cx - s * 0.75f, cy - s * 0.45f, s * 1.5f, s * 0.9f, c);
            case SETTINGS -> {
                for (int k = 0; k < 8; k++) {
                    double a = Math.PI * 2 * k / 8;
                    g.draw(new Line2D.Double(cx + Math.cos(a) * s * 0.45, cy + Math.sin(a) * s * 0.45,
                            cx + Math.cos(a) * s * 0.72, cy + Math.sin(a) * s * 0.72));
                }
                g.draw(new Ellipse2D.Float(cx - s * 0.45f, cy - s * 0.45f, s * 0.9f, s * 0.9f));
                g.fill(new Ellipse2D.Float(cx - s * 0.16f, cy - s * 0.16f, s * 0.32f, s * 0.32f));
            }
            case REFRESH -> {
                g.draw(new Arc2D.Float(cx - s * 0.6f, cy - s * 0.6f, s * 1.2f, s * 1.2f, 70, 290, Arc2D.OPEN));
                Path2D head = new Path2D.Float();
                double ax = cx + Math.cos(Math.toRadians(70)) * s * 0.6, ay = cy - Math.sin(Math.toRadians(70)) * s * 0.6;
                head.moveTo(ax - s * 0.32, ay - s * 0.22);
                head.lineTo(ax + s * 0.05, ay);
                head.lineTo(ax - s * 0.25, ay + s * 0.3);
                g.draw(head);
            }
            case POWER -> {
                g.draw(new Arc2D.Float(cx - s * 0.6f, cy - s * 0.55f, s * 1.2f, s * 1.2f, 120, 300, Arc2D.OPEN));
                g.draw(new Line2D.Float(cx, cy - s * 0.75f, cx, cy - s * 0.05f));
            }
        }
        g.setStroke(new BasicStroke(1));
    }

    private static void drawGamepadIcon(Graphics2D g, float x, float y, float w, float h, Color c) {
        g.setColor(c);
        RoundRectangle2D body = new RoundRectangle2D.Float(x, y, w, h, h * 0.5f, h * 0.5f);
        g.setStroke(new BasicStroke(Math.max(1.5f, h * 0.12f)));
        g.draw(body);
        g.fill(new RoundRectangle2D.Float(x + w * 0.28f, y + h * 0.22f, w * 0.44f, h * 0.56f, h * 0.12f, h * 0.12f));
        g.fill(new Ellipse2D.Float(x + w * 0.07f, y + h * 0.36f, h * 0.28f, h * 0.28f));
        g.fill(new Ellipse2D.Float(x + w * 0.93f - h * 0.28f, y + h * 0.36f, h * 0.28f, h * 0.28f));
        g.setStroke(new BasicStroke(1));
    }

    private void paintToast(Graphics2D g, Layout L) {
        if (toast == null) return;
        long left = toastUntil - System.currentTimeMillis();
        float alpha = left > 0 ? 1f : Math.max(0, 1f + left / 400f);
        Composite old = g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
        g.setFont(font(Font.BOLD, L.h * 0.026f));
        FontMetrics fm = g.getFontMetrics();
        float tw = fm.stringWidth(toast) + L.h * 0.07f, th = L.h * 0.06f;
        float tx = (L.w - tw) / 2, ty = L.dockY - th - L.h * 0.05f;
        RoundRectangle2D r = new RoundRectangle2D.Float(tx, ty, tw, th, th, th);
        g.setColor(TOAST);
        g.fill(r);
        g.setColor(Color.WHITE);
        drawCentered(g, toast, L.w / 2, ty + th / 2 + fm.getAscent() * 0.36f);
        g.setComposite(old);
    }

    private void dim(Graphics2D g, Layout L) {
        g.setColor(DIM);
        g.fill(new Rectangle2D.Float(0, 0, L.w, L.h));
    }

    private void paintNowPlaying(Graphics2D g, Layout L) {
        dim(g, L);
        float cw = Math.min(L.w * 0.6f, 900), ch = L.h * 0.5f;
        float cx = (L.w - cw) / 2, cy = (L.h - ch) / 2;
        RoundRectangle2D card = new RoundRectangle2D.Float(cx, cy, cw, ch, 40, 40);
        shadow(g, card, 10);
        g.setColor(CARD);
        g.fill(card);
        Shape old = g.getClip();
        g.clip(card);
        Color c = playing.system().color();
        g.setPaint(new GradientPaint(cx, cy, brighter(c, 0.2f), cx + cw, cy, c));
        g.fill(new Rectangle2D.Float(cx, cy, cw, ch * 0.3f));
        g.setClip(old);
        g.setColor(Color.WHITE);
        g.setFont(font(Font.BOLD, ch * 0.07f));
        g.drawString("NOW PLAYING", cx + cw * 0.06f, cy + ch * 0.12f);
        g.setFont(font(Font.PLAIN, ch * 0.06f));
        g.drawString(playing.system().name() + "  \u00B7  " + playing.system().emulator(), cx + cw * 0.06f, cy + ch * 0.22f);
        g.setColor(TEXT);
        g.setFont(font(Font.BOLD, ch * 0.12f));
        drawCentered(g, ellipsize(g, playing.name(), cw * 0.9f), L.w / 2, cy + ch * 0.52f);
        g.setColor(TEXT_DIM);
        g.setFont(font(Font.PLAIN, ch * 0.055f));
        drawCentered(g, "Your GamePad now sends keys to the emulator window.", L.w / 2, cy + ch * 0.68f);
        drawCentered(g, "Close the game: HOME on the GamePad, or Ctrl+Q here.", L.w / 2, cy + ch * 0.78f);
    }

    private void paintPadOverlay(Graphics2D g, Layout L) {
        dim(g, L);
        float cw = Math.min(L.w * 0.7f, 1000), ch = L.h * 0.66f;
        float cx = (L.w - cw) / 2, cy = (L.h - ch) / 2;
        RoundRectangle2D card = new RoundRectangle2D.Float(cx, cy, cw, ch, 40, 40);
        shadow(g, card, 10);
        g.setColor(CARD);
        g.fill(card);
        float qrSize = ch * 0.64f;
        float qx = cx + ch * 0.12f, qy = cy + (ch - qrSize) / 2;
        if (qr != null) {
            float cell = qrSize / (qr.size + 4);
            g.setColor(Color.WHITE);
            g.fill(new Rectangle2D.Float(qx, qy, qrSize, qrSize));
            g.setColor(new Color(0x202225));
            for (int y = 0; y < qr.size; y++)
                for (int x = 0; x < qr.size; x++)
                    if (qr.get(x, y))
                        g.fill(new Rectangle2D.Float(qx + (x + 2) * cell, qy + (y + 2) * cell, cell + 0.5f, cell + 0.5f));
            g.setColor(DIVIDER);
            g.draw(new RoundRectangle2D.Float(qx - 4, qy - 4, qrSize + 8, qrSize + 8, 16, 16));
        }
        float tx = qx + qrSize + ch * 0.1f, tw = cx + cw - tx - ch * 0.08f;
        g.setColor(TEXT);
        fitFont(g, "Use your phone as GamePad", Font.BOLD, ch * 0.07f, tw);
        g.drawString("Use your phone as GamePad", tx, cy + ch * 0.2f);
        g.setFont(font(Font.PLAIN, ch * 0.045f));
        g.setColor(TEXT_DIM);
        float ly = cy + ch * 0.32f;
        if (!serverOn) {
            g.drawString("The GamePad server is turned off in Settings.", tx, ly);
            return;
        }
        for (String s : new String[]{"1. Join the same Wi-Fi as this computer.", "2. Scan the code, or open:"}) {
            g.drawString(ellipsize(g, s, tw), tx, ly);
            ly += ch * 0.075f;
        }
        g.setColor(ACCENT);
        fitFont(g, padUrl, Font.BOLD, ch * 0.055f, tw);
        g.drawString(padUrl, tx, ly);
        ly += ch * 0.12f;
        if (padNeedsCode) {
            g.setColor(TEXT_DIM);
            g.setFont(font(Font.PLAIN, ch * 0.045f));
            g.drawString("Pairing code", tx, ly);
            ly += ch * 0.12f;
            g.setColor(TEXT);
            g.setFont(font(Font.BOLD, ch * 0.13f));
            String spaced = String.join(" ", padCode.split(""));
            g.drawString(spaced, tx, ly);
            ly += ch * 0.05f;
        }
        g.setColor(TEXT_DIM);
        g.setFont(font(Font.PLAIN, ch * 0.04f));
        g.drawString("Press B: or Esc to close", tx, cy + ch * 0.92f);
    }

    private void paintConfirm(Graphics2D g) {
        Layout L = geom();
        dim(g, L);
        Rectangle2D c = powerCard();
        RoundRectangle2D card = new RoundRectangle2D.Double(c.getX(), c.getY(), c.getWidth(), c.getHeight(), 36, 36);
        shadow(g, card, 10);
        g.setColor(CARD);
        g.fill(card);
        g.setColor(TEXT);
        g.setFont(font(Font.BOLD, L.h * 0.045f));
        drawCentered(g, CONSOLE ? "Power" : "Quit WII-UU?", (float) c.getCenterX(), (float) c.getY() + L.h * 0.095f);
        List<PowerItem> items = powerItems();
        List<Rectangle2D> rs = powerRects();
        for (int i = 0; i < items.size(); i++) {
            Rectangle2D r = rs.get(i);
            boolean on = i == powerSel;
            RoundRectangle2D b = new RoundRectangle2D.Double(r.getX(), r.getY(), r.getWidth(), r.getHeight(), r.getHeight(), r.getHeight());
            g.setColor(on ? ACCENT : BUTTON);
            g.fill(b);
            g.setColor(on ? Color.WHITE : TEXT);
            g.setFont(font(Font.BOLD, L.h * 0.03f));
            drawCentered(g, items.get(i).label(), (float) r.getCenterX(),
                    (float) (r.getCenterY() + g.getFontMetrics().getAscent() * 0.36f));
        }
    }

    // ---- covers -------------------------------------------------------------------------

    private BufferedImage cover(Game game) {
        BufferedImage img = covers.get(game.path());
        if (img != null) return img;
        if (loading.add(game.path())) {
            coverLoader.execute(() -> {
                BufferedImage found = NO_COVER;
                for (Path p : game.covers()) {
                    try {
                        if (!Files.isRegularFile(p)) continue;
                        BufferedImage raw = ImageIO.read(p.toFile());
                        if (raw != null) {
                            found = downscale(raw, 420);
                            break;
                        }
                    } catch (Exception ignored) {
                        // unreadable image: try the next candidate
                    }
                }
                covers.put(game.path(), found);
                loading.remove(game.path());
                repaint();
            });
        }
        return null;
    }

    private static BufferedImage downscale(BufferedImage src, int max) {
        double s = Math.min(1.0, (double) max / Math.max(src.getWidth(), src.getHeight()));
        int w = Math.max(1, (int) (src.getWidth() * s)), h = Math.max(1, (int) (src.getHeight() * s));
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(src.getScaledInstance(w, h, Image.SCALE_SMOOTH), 0, 0, null);
        g.dispose();
        return out;
    }

    // ---- drawing helpers ----------------------------------------------------------------

    /** Soft drop shadow built from a few growing translucent fills (fills are far cheaper than wide strokes). */
    private static void shadow(Graphics2D g, Shape s, int depth) {
        Rectangle2D b = s.getBounds2D();
        boolean round = s instanceof RoundRectangle2D;
        boolean oval = s instanceof Ellipse2D;
        double arc = round ? ((RoundRectangle2D) s).getArcWidth() : 0;
        int layers = Math.min(4, depth);
        for (int i = layers; i >= 1; i--) {
            double grow = depth * 0.45 * i / layers, dy = depth * 0.35 * i / layers + 1;
            g.setColor(new Color(SHADOW_RGB.getRed(), SHADOW_RGB.getGreen(), SHADOW_RGB.getBlue(),
                    Math.min(255, (9 + (layers - i) * 3) * shadowBoost)));
            double x = b.getX() - grow, y = b.getY() - grow + dy, w = b.getWidth() + 2 * grow, h = b.getHeight() + 2 * grow;
            if (round) g.fill(new RoundRectangle2D.Double(x, y, w, h, arc + 2 * grow, arc + 2 * grow));
            else if (oval) g.fill(new Ellipse2D.Double(x, y, w, h));
            else {
                g.translate(0, dy);
                g.fill(s);
                g.translate(0, -dy);
            }
        }
    }

    private static Font font(int style, float size) {
        float sz = Math.max(8f, Math.round(size * 2) / 2f); // half-point buckets keep the cache small
        return FONTS.computeIfAbsent(((long) style << 32) | Float.floatToIntBits(sz),
                k -> new Font(FONT, style, 1).deriveFont(style, sz));
    }

    private static String pickFont() {
        if (GraphicsEnvironment.isHeadless()) return Font.SANS_SERIF;
        Set<String> have = Set.copyOf(Arrays.asList(
                GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
        for (String f : new String[]{"Nunito", "Rodin NTLG", "Segoe UI", "Helvetica Neue", "SF Pro Display",
                "Noto Sans", "Ubuntu", "Cantarell", "DejaVu Sans"}) {
            if (have.contains(f)) return f;
        }
        return Font.SANS_SERIF;
    }

    /** Sets the largest font up to {@code size} at which {@code s} fits in {@code maxW}. */
    private static void fitFont(Graphics2D g, String s, int style, float size, float maxW) {
        Font f = font(style, size);
        float w = g.getFontMetrics(f).stringWidth(s);
        if (w > maxW) f = font(style, size * maxW / w);
        g.setFont(f);
    }

    private static void drawCentered(Graphics2D g, String s, float cx, float baseline) {
        g.drawString(s, cx - g.getFontMetrics().stringWidth(s) / 2f, baseline);
    }

    /** Draws text as large as fits the box, centred on (cx, cy). */
    private static void drawFitted(Graphics2D g, String s, int style, float cx, float cy, float maxW, float maxH) {
        float size = maxH;
        Font f = font(style, size);
        FontMetrics fm = g.getFontMetrics(f);
        if (fm.stringWidth(s) > maxW) {
            size = size * maxW / fm.stringWidth(s);
            f = font(style, size);
            fm = g.getFontMetrics(f);
        }
        g.setFont(f);
        g.drawString(s, cx - fm.stringWidth(s) / 2f, cy + (fm.getAscent() - fm.getDescent()) / 2f);
    }

    private static String ellipsize(Graphics2D g, String s, float maxW) {
        FontMetrics fm = g.getFontMetrics();
        if (fm.stringWidth(s) <= maxW) return s;
        String dots = "\u2026";
        int end = s.length();
        while (end > 0 && fm.stringWidth(s.substring(0, end) + dots) > maxW) end--;
        return s.substring(0, end).trim() + dots;
    }

    private static List<String> wrap(Graphics2D g, String s, float maxW, int maxLines) {
        FontMetrics fm = g.getFontMetrics();
        List<String> lines = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        String[] words = s.split(" ");
        for (int i = 0; i < words.length; i++) {
            String next = cur.isEmpty() ? words[i] : cur + " " + words[i];
            if (fm.stringWidth(next) <= maxW || cur.isEmpty()) {
                cur.setLength(0);
                cur.append(next);
            } else {
                lines.add(cur.toString());
                cur.setLength(0);
                cur.append(words[i]);
                if (lines.size() == maxLines - 1) {
                    cur.setLength(0);
                    cur.append(String.join(" ", Arrays.copyOfRange(words, i, words.length)));
                    break;
                }
            }
        }
        if (!cur.isEmpty()) lines.add(cur.toString());
        List<String> out = new ArrayList<>();
        for (String l : lines) out.add(ellipsize(g, l, maxW));
        return out;
    }

    private static String initials(String name) {
        StringBuilder sb = new StringBuilder();
        for (String w : name.split("[\\s:\\-]+")) {
            if (w.isEmpty()) continue;
            char c = w.charAt(0);
            if (Character.isLetterOrDigit(c)) sb.append(Character.toUpperCase(c));
            if (sb.length() == 3) break;
        }
        return sb.isEmpty() ? "?" : sb.toString();
    }

    /** Nudges hue/brightness per title so a shelf of one system isn't a single flat colour. */
    private static Color vary(Color c, String key) {
        float[] hsb = Color.RGBtoHSB(c.getRed(), c.getGreen(), c.getBlue(), null);
        int hsh = key.hashCode();
        float dh = ((hsh & 0xFF) / 255f - 0.5f) * 0.08f;
        float db = (((hsh >> 8) & 0xFF) / 255f - 0.5f) * 0.18f;
        float sat = hsb[1] < 0.1f ? hsb[1] : Math.min(1f, hsb[1] * 0.95f);
        return Color.getHSBColor((hsb[0] + dh + 1f) % 1f, sat, Math.max(0.15f, Math.min(1f, hsb[2] + db)));
    }

    private static Color brighter(Color c, float amt) {
        return new Color(mix(c.getRed(), 255, amt), mix(c.getGreen(), 255, amt), mix(c.getBlue(), 255, amt));
    }

    private static Color darker(Color c, float amt) {
        return new Color(mix(c.getRed(), 0, amt), mix(c.getGreen(), 0, amt), mix(c.getBlue(), 0, amt));
    }

    private static int mix(int a, int b, float t) {
        return Math.round(a + (b - a) * t);
    }
}
