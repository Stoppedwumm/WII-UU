package wiiuu;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.KeyboardFocusManager;
import java.awt.RenderingHints;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CompletableFuture;

import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import wiiuu.core.Config;
import wiiuu.core.Game;
import wiiuu.core.Launcher;
import wiiuu.core.Library;
import wiiuu.core.Updater;
import wiiuu.input.InputRouter;
import wiiuu.input.KeyMap;
import wiiuu.input.VirtualPads;
import wiiuu.net.DsuServer;
import wiiuu.net.GamepadServer;
import wiiuu.screen.AudioStreamer;
import wiiuu.screen.ScreenStreamer;
import wiiuu.ui.MenuView;
import wiiuu.ui.SettingsScreen;

/** WII-UU: a Wii U styled emulator launcher with a phone-as-GamePad web server. */
public final class Main implements MenuView.Actions, GamepadServer.Host, Launcher.Listener {
    public static final String VERSION = "1.9.44";

    private final Config config;
    private final Library library;
    private final Launcher launcher;
    private final wiiuu.core.OpenBased openBased;
    /** split screens for DS/3DS in RetroArch mode: top on the TV, bottom on the phone */
    private final wiiuu.screen.DualScreen dual;
    /** reasons for no split screens already shown this session (each once) */
    private final java.util.Set<String> shownSplitProblem = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final InputRouter router;
    private GamepadServer server;
    private DsuServer dsuServer;
    private volatile VirtualPads vpads;
    private volatile ScreenStreamer screen;
    /** macOS: whether WII-UU may press keys (Accessibility); null = not checked yet */
    private volatile Boolean macKeysAllowed;
    private boolean macKeysPrompted;
    private JFrame frame;
    private MenuView view;

    private Main(Config config) {
        this.config = config;
        this.library = new Library(config);
        this.launcher = new Launcher(config);
        this.openBased = new wiiuu.core.OpenBased(config, library);
        launcher.setOpenBased(openBased);
        launcher.addListener(openBased);
        this.router = new InputRouter(new KeyMap(config), launcher::isRunning);
        // type each emulator's own default keys (Dolphin, PPSSPP, mGBA, melonDS, ...), so nothing needs mapping
        router.setKeyProfile(() -> {
            Game g = launcher.current();
            if (g == null || launcher.inRetroArch()) return null;     // RetroArch is given WII-UU's own keys
            return KeyMap.profileFor(g.system().id(), config.command(g.system()));
        });
        // Typing keys into emulators is the fallback for when real virtual controllers aren't available:
        // input.keys = auto (default: only without virtual pads) | on | off; system.<id>.keys overrides.
        router.setKeysEnabled(() -> typesKeysFor(launcher.current()));
        launcher.setTypesKeys(this::typesKeysFor);
        launcher.addListener(this);
        wiiuu.screen.WinScript.setDir(config.home().resolve("bin"));
        wiiuu.screen.VirtualDisplay.recover(config);
        dual = new wiiuu.screen.DualScreen(config);
        // the TV's area is taken before the displays change (it's where WII-UU's window is)
        launcher.setSplit(g -> {
            java.awt.Rectangle r = dual.prepare(g, frame == null ? null : frame.getGraphicsConfiguration().getBounds());
            String why = dual.problem();
            if (r == null && why != null && shownSplitProblem.add(why)) {
                // say it where it's seen: RetroArch covers the TV, so on the phone too
                String msg = "Both DS screens stay on the TV: " + why.replace("; the TV shows both screens", "");
                if (server != null) server.notice(msg);
                SwingUtilities.invokeLater(() -> view.showToast(msg));
            }
            return r;
        });
        Runtime.getRuntime().addShutdownHook(new Thread(dual::end, "split-cleanup"));
    }

    /** Whether WII-UU types keys into this game's emulator (else the phone is a virtual controller or DSU). */
    private boolean typesKeysFor(Game g) {
        if (g == null) return true;
        // video players only know keys
        if (wiiuu.core.OpenBased.handles(g)) return config.getBool("system.openbased.keys", true);
        if (wiiuu.core.Buzz.active(config, g)) return true;       // PCSX2's Buzz! buzzers are bound to keys
        String perSystem = config.get("system." + g.system().id() + ".keys", null);
        if (perSystem != null) return Boolean.parseBoolean(perSystem.trim());
        return switch (config.get("input.keys", "auto").trim().toLowerCase()) {
            case "on", "true" -> true;
            case "off", "false" -> false;
            default -> vpads == null || !vpads.usable();
        };
    }

    public static void main(String[] args) throws Exception {
        // the phone server sends small packets at once (read when the HTTP server first loads)
        System.setProperty("sun.net.httpserver.nodelay", "true");
        Path home = Config.defaultHome();
        Boolean fullscreen = null;
        Integer port = null;
        boolean noServer = false, setup = false, noIntro = false;
        String snapshot = null, snapshotView = "home";
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--fullscreen" -> fullscreen = true;
                case "--windowed" -> fullscreen = false;
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--no-server" -> noServer = true;
                case "--home" -> home = Paths.get(args[++i]);
                case "--snapshot" -> snapshot = args[++i];
                case "--snapshot-view" -> snapshotView = args[++i];
                case "--write-icon" -> {
                    String out = args[++i];
                    // .ico (Windows shortcuts, several sizes) or .png
                    if (out.toLowerCase(java.util.Locale.ROOT).endsWith(".ico")) writeIco(Paths.get(out));
                    else ImageIO.write(appIcon(256), "png", Paths.get(out).toFile());
                    return;
                }
                case "--upgrade", "--check-update" -> {
                    System.exit(cliUpgrade(new Config(home), args[i].equals("--upgrade")));
                }
                case "--split-check" -> {
                    wiiuu.screen.VirtualDisplay.check(new Config(home));
                    return;
                }
                case "--install-virtual-display" -> {
                    System.out.println(wiiuu.screen.VirtualDisplay.installWindows(new Config(home)));
                    return;
                }
                case "--virtual-display-off" -> {
                    // after installing the virtual display driver: keep the desktop as it was until a game needs it
                    int n = wiiuu.screen.VirtualDisplay.switchOffVirtual(new Config(home));
                    System.out.println(n > 0 ? "Virtual display switched off (WII-UU switches it on for split DS/3DS screens)" : "No virtual display was on");
                    return;
                }
                case "--changelog" -> {
                    // all of it, or only what's newer than a version: --changelog 1.9.0
                    String since = i + 1 < args.length && args[i + 1].matches("\\d+(\\.\\d+)*") ? args[++i] : null;
                    System.out.println(wiiuu.core.Changelog.text(wiiuu.core.Changelog.bundled().between(since, null)));
                    return;
                }
                case "--theme-check" -> {
                    System.exit(themeCheck(new Config(home), i + 1 < args.length ? args[++i] : null));
                }
                case "--theme-reference" -> {
                    System.out.print(wiiuu.ui.Themes.reference());
                    return;
                }
                case "--version" -> {
                    System.out.println("WII-UU " + VERSION);
                    return;
                }
                case "--help", "-h" -> {
                    System.out.println("""
                            WII-UU %s - Wii U style emulator launcher
                              --fullscreen / --windowed   override the saved window mode
                              --port N                    GamePad web server port (default 8080)
                              --no-server                 do not start the phone GamePad server
                              --home DIR                  settings folder (default ~/.wiiuu)
                              --check-update / --upgrade  check for / install a newer version (with its notes)
                              --changelog [VERSION]       what changed (since VERSION)
                              --setup                     start with the setup guide (after the first-boot intro)
                              --no-intro                  with --setup: straight to the guide, no intro
                              --theme-check FILE|NAME     check a theme (.wtheme) and show its colours
                              --theme-reference           everything the theme language knows
                              --fps                       print the menu's frame rate (every two seconds)
                              --install-virtual-display   Windows: install the virtual display (split DS/3DS screens)
                              --virtual-display-off       Windows: switch the virtual display off (split screens)
                            Keys: arrows move, Enter opens, Esc back, F1 settings, F2 GamePad, F5 refresh,
                                  F11 fullscreen, Ctrl+Q closes a running game.""".formatted(VERSION));
                    return;
                }
                case "--setup" -> setup = true;
                case "--no-intro" -> noIntro = true;
                case "--fps" -> System.setProperty("wiiuu.fps", "true");
                default -> {
                    // -Dname=value after "wiiuu" (the launcher passes it here, not to Java)
                    int eq = args[i].indexOf('=');
                    if (args[i].startsWith("-D") && eq > 2) System.setProperty(args[i].substring(2, eq), args[i].substring(eq + 1));
                    else if (args[i].startsWith("-D") && args[i].length() > 2) System.setProperty(args[i].substring(2), "true");
                    else System.err.println("Ignoring unknown option " + args[i]);
                }
            }
        }
        Config config = new Config(home);
        if (port != null) config.set("server.port", port.toString());
        if (fullscreen != null) config.set("ui.fullscreen", fullscreen.toString());
        freshInstall = !Files.exists(home.resolve("config.properties"));
        if (freshInstall) config.save();
        // never ran before (install.sh may already have written settings): the setup guide
        firstRun = config.get("app.lastVersion", null) == null && !config.getBool("ui.setupDone", false);
        // Settings > System > Restart into the setup guide
        boolean guideAsked = config.getBool("ui.setupNext", false) || setup;
        if (config.getBool("ui.setupNext", false)) {
            config.set("ui.setupNext", null);
            config.save();
        }

        Main app = new Main(config);
        if (snapshot != null) {
            app.snapshot(Paths.get(snapshot), snapshotView);
            System.exit(0);
        }
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("WII-UU needs a desktop session (no display found).");
            System.exit(1);
        }
        boolean startServer = !noServer && config.getBool("server.enabled", true);
        SwingUtilities.invokeAndWait(app::createWindow);
        // the first boot (or a restart into the guide): the fast-cut intro, then the setup guide
        if ((firstRun || guideAsked) && !noIntro && config.getBool("ui.firstBootIntro", true)) SwingUtilities.invokeLater(app.view::startFirstBoot);
        else if (firstRun || guideAsked) SwingUtilities.invokeLater(app.view::startGuide);
        app.library.addListener(s -> SwingUtilities.invokeLater(() -> app.view.setSnapshot(s)));
        app.library.rescanAsync();
        app.openBased.refreshAsync();
        if (startServer) app.startServer();
        if (config.getBool("update.check", true)) app.checkForUpdateQuietly();
        app.showWhatsNewAfterUpdate();
    }

    private void startServer() {
        DsuServer dsu = null;
        if (config.getBool("dsu.enabled", true)) {
            dsu = new DsuServer();
            dsu.setMotionSigns(config.get("dsu.motionSigns", "+++ +++"));
            try {
                // loopback only: emulators on this PC connect to 127.0.0.1:26760
                dsu.start(config.get("dsu.bind", DsuServer.loopback()), config.getInt("dsu.port", 26760));
            } catch (IOException e) {
                System.err.println("[dsu] could not start: " + e.getMessage());
                dsu = null;
            }
        }
        dsuServer = dsu;
        ScreenStreamer screen = config.getBool("stream.enabled", true) ? new ScreenStreamer(config, launcher::current) : null;
        this.screen = screen;
        if (screen != null) {
            screen.setRetroArch(launcher::inRetroArch);
            screen.setDualScreen(dual);
        }
        router.setKeysPermitted(() -> !Boolean.FALSE.equals(macKeysAllowed));
        if (screen != null) {
            screen.setOnBlocked(msg -> SwingUtilities.invokeLater(() -> view.showToast(
                    System.getProperty("os.name", "").toLowerCase().contains("mac")
                            ? "GamePad screen is black: allow Screen Recording for WII-UU (Settings F1 > System > macOS permissions)"
                            : "GamePad screen is black: screen capture seems blocked")));
        }
        server = new GamepadServer(config, library, launcher, router, this, screen, dsu);
        // killed from outside (Console Mode's session, the system shutting down): the phones still hear it
        GamepadServer phones = server;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> phones.announceClosing("quit"), "tell-phones"));
        if (config.getBool("audio.enabled", true)) {
            server.setAudio(new AudioStreamer(config, screen != null ? screen::macAudioHelper : () -> null));
        }
        if (config.getBool("input.gamepad", true)) {
            VirtualPads v = new VirtualPads();
            if (v.start(config.home())) {
                vpads = v;
                server.setVirtualPads(v);
                launcher.setVirtualPads(v::usable);
                System.out.println("[input] phones appear as virtual Xbox 360 controllers");
            } else {
                System.out.println("[input] virtual controllers unavailable (" + v.problem() + "); typing keys instead");
            }
        }
        try {
            server.start(config.port());
            SwingUtilities.invokeLater(() -> view.setServer(server.url(), server.pairingUrl(), server.code(), server.requiresCode()));
        } catch (IOException e) {
            server = null;
            SwingUtilities.invokeLater(() -> view.showToast("GamePad server could not use port " + config.port() + ": " + e.getMessage()));
        }
    }

    private void createWindow() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // default look and feel is fine
        }
        view = new MenuView(config, this);
        router.setMenu(view);
        // real USB / Bluetooth controllers in the menu too (Linux)
        if (config.getBool("input.localPads", true)) {
            new wiiuu.input.LocalPads(router, "east".equalsIgnoreCase(config.get("input.padConfirm", "south").trim())).start();
        }
        frame = new JFrame("WII-UU");
        frame.setIconImages(appIcons());          // every size, so Windows' taskbar and Alt+Tab never rescale
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                quit();
            }

            @Override
            public void windowActivated(WindowEvent e) {
                view.requestFocusInWindow();
            }
        });
        frame.setContentPane(view);
        frame.setMinimumSize(new Dimension(960, 540));
        frame.setSize(1280, 720);
        frame.setLocationRelativeTo(null);
        applyFullscreen(config.getBool("ui.fullscreen", false));
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(e -> {
            if (e.getID() == KeyEvent.KEY_PRESSED && e.getKeyCode() == KeyEvent.VK_F11 && frame.isActive()) {
                applyFullscreen(!frame.isUndecorated());
                return true;
            }
            return false;
        });
        view.requestFocusInWindow();
    }

    private void applyFullscreen(boolean on) {
        if (frame.isDisplayable() && frame.isUndecorated() == on) return;
        if (frame.isDisplayable()) frame.dispose();
        frame.setUndecorated(on);
        if (on) {
            // cover the screen ourselves too: without a window manager (a bare X session) "maximized" does nothing
            java.awt.GraphicsConfiguration gc = frame.getGraphicsConfiguration();
            if (gc != null) frame.setBounds(gc.getBounds());
            frame.setExtendedState(Frame.MAXIMIZED_BOTH);
        } else {
            frame.setExtendedState(Frame.NORMAL);
            frame.setSize(1280, 720);
            frame.setLocationRelativeTo(null);
        }
        frame.setVisible(true);
        view.requestFocusInWindow();
    }

    // ---- MenuView.Actions ---------------------------------------------------------------

    @Override
    public void launch(Game game) {
        try {
            launcher.launch(game);
        } catch (Launcher.LaunchException e) {
            dual.end();
            view.showToast(e.getMessage());
        }
    }

    @Override
    public void openSettings() {
        view.openSettings(new SettingsScreen.Services(config, openBased, () -> server == null ? null : server.url(),
                () -> view.startGuide(), this::restartIntoGuide, new Updater(config, VERSION), () -> {
                    quitReason = "update";
                    quit();
                }, () -> {
            // everything is saved as it changes; what the menu shows may have changed too
            view.setSoundsEnabled(config.getBool("ui.sounds", true));
            view.setMusicEnabled(config.getBool("ui.music", true));
            library.rescanAsync();
            openBased.refreshAsync();
        }));
        view.requestFocusInWindow();
    }

    /** Why WII-UU is about to quit, for the phones ("WII-UU is restarting", ...). */
    private String quitReason = "quit";

    /** Settings > System > Restart into the setup guide. */
    private void restartIntoGuide() {
        quitReason = "restart";
        config.set("ui.setupNext", "true");
        config.save();
        try {
            Updater.restartAfterExit();
        } catch (IOException e) {
            System.err.println("[restart] could not start WII-UU again: " + e.getMessage());
        }
        quit();
    }

    @Override
    public void quit() {
        launcher.stop(true);        // make sure the emulator is really gone before we exit
        if (server != null) {
            server.announceClosing(quitReason);
            server.stop();
        }
        if (dsuServer != null) dsuServer.stop();
        if (vpads != null) vpads.stop();
        System.exit(0);
    }

    @Override
    public void refresh() {
        library.rescanAsync();
        openBased.refreshAsync();
    }

    /**
     * Console mode: WII-UU exits with a code that tells wiiuu-session what to do next
     * (see console/wiiuu-session): 10 = desktop mode, 11 = shut down, 12 = restart.
     */
    @Override
    public void power(String action) {
        int code = switch (action) {
            case "desktop" -> 10;
            case "shutdown" -> 11;
            case "restart" -> 12;
            default -> 0;
        };
        launcher.stop(true);
        if (server != null) {
            server.announceClosing(action);
            server.stop();
        }
        if (dsuServer != null) dsuServer.stop();
        if (vpads != null) vpads.stop();
        System.exit(code);
    }

    @Override
    public void closeGame() {
        launcher.stop();
    }

    // ---- GamepadServer.Host -------------------------------------------------------------

    @Override
    public String launchFromPad(Game game) {
        CompletableFuture<String> result = new CompletableFuture<>();
        SwingUtilities.invokeLater(() -> {
            try {
                launcher.launch(game);
                result.complete(null);
            } catch (Launcher.LaunchException e) {
                view.showToast(e.getMessage());
                result.complete(e.getMessage());
            }
        });
        return result.join();
    }

    @Override
    public wiiuu.core.OpenBased openBased() {
        return openBased;
    }

    @Override
    public String setUpOpenBased(String url, String token) {
        url = url.trim();
        if (url.isEmpty()) {
            config.set("openbased.url", null);
            config.set("openbased.token", null);
            config.save();
            openBased.refresh();
            return null;
        }
        if (!token.isBlank() && !token.trim().startsWith("ob_pat_")) {
            return "That isn't an OpenBased personal access token (they start with ob_pat_)";
        }
        String oldUrl = config.get("openbased.url", null), oldToken = config.get("openbased.token", null);
        config.set("openbased.url", url);
        if (!token.isBlank()) {
            // a token made by hand: not WII-UU's own (which it would revoke on sign-out)
            config.set("openbased.token", token.trim());
            config.set("openbased.tokenId", null);
            config.set("openbased.user", null);
        }
        if (!wiiuu.core.OpenBased.configured(config)) {
            config.set("openbased.url", oldUrl);
            return "Enter a personal access token too";
        }
        openBased.refresh();
        String why = openBased.problem();
        if (why != null && oldUrl != null && oldToken != null) {
            // keep what worked before
            config.set("openbased.url", oldUrl);
            config.set("openbased.token", oldToken);
            openBased.refreshAsync();
            return why;
        }
        config.save();
        if (why == null) SwingUtilities.invokeLater(() -> view.showToast("OpenBased: " + openBased.count() + " videos"));
        return why;
    }

    @Override
    public void padsChanged(int connected) {
        SwingUtilities.invokeLater(() -> {
            if (view != null) view.setPads(connected);
        });
    }

    // ---- Launcher.Listener --------------------------------------------------------------

    @Override
    public void started(Game game) {
        dual.begin();
        SwingUtilities.invokeLater(() -> {
            view.setPlaying(game);
            if (config.getBool("ui.minimizeOnLaunch", true)) frame.setState(Frame.ICONIFIED);
        });
        if (typesKeysFor(game)) checkMacKeys();
    }

    /**
     * macOS: the phone's buttons are typed as keys, which macOS only lets through with the
     * Accessibility permission. Without it the keys vanish without any error, so ask a small helper
     * (judged by macOS as WII-UU) and say what to do. The first time, macOS also shows its own dialog.
     */
    private void checkMacKeys() {
        ScreenStreamer s = screen;
        java.nio.file.Path helper = s == null ? null : s.macTrustHelper();
        if (helper == null) return;
        Thread t = new Thread(() -> {
            boolean prompt;
            synchronized (this) {
                prompt = !macKeysPrompted;
                macKeysPrompted = true;
            }
            try {
                Process p = new ProcessBuilder(prompt ? java.util.List.of(helper.toString(), "--prompt") : java.util.List.of(helper.toString()))
                        .redirectErrorStream(true).start();
                String out = new String(p.getInputStream().readAllBytes()).trim();
                if (!p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) return;
                boolean allowed = !out.endsWith("no");
                macKeysAllowed = allowed;
                if (!allowed) {
                    System.out.println("[input] macOS blocks WII-UU's keys: Accessibility permission missing");
                    SwingUtilities.invokeLater(() -> view.showToast(MAC_KEYS_HELP));
                }
            } catch (IOException | InterruptedException e) {
                System.err.println("[input] could not check the Accessibility permission: " + e.getMessage());
            }
        }, "mac-keys-check");
        t.setDaemon(true);
        t.start();
    }

    public static final String MAC_KEYS_HELP = "GamePad buttons are blocked: turn on WII-UU under System Settings > Privacy & Security"
            + " > Accessibility (if it is already on, turn it off and on again)";

    @Override
    public void message(String text) {
        SwingUtilities.invokeLater(() -> view.showToast(text));
    }

    @Override
    public void exited(Game game, int exitCode, boolean quickFailure, Path log) {
        dual.ended(exitCode, log);
        SwingUtilities.invokeLater(() -> {
            view.setPlaying(null);
            frame.setState(Frame.NORMAL);
            frame.toFront();
            frame.requestFocus();
            view.requestFocusInWindow();
            if (quickFailure) {
                String why = Launcher.lastLogLine(log);
                view.showToast(game.system().emulator() + " stopped (error " + exitCode + ")"
                        + (why != null ? ": " + why : " - see " + log));
            }
        });
    }

    // ---- updates ------------------------------------------------------------------------

    /**
     * The first start after an update shows what changed since the version that ran before
     * (ui.whatsNew=false: only a toast).
     */
    private static boolean freshInstall, firstRun;

    private void showWhatsNewAfterUpdate() {
        String before = config.get("app.lastVersion", null);
        if (VERSION.equals(before)) return;
        config.set("app.lastVersion", VERSION);
        config.save();
        if (freshInstall || firstRun || (before != null && !Updater.newer(VERSION, before))) return;     // first install, or a downgrade
        java.util.List<wiiuu.core.Changelog.Entry> all = wiiuu.core.Changelog.bundled().between(before, VERSION);
        // updated from a version that didn't keep track: this version's notes
        java.util.List<wiiuu.core.Changelog.Entry> notes = before == null && all.size() > 1 ? all.subList(0, 1) : all;
        if (notes.isEmpty()) return;
        // after the start-up animation and the setup guide (--setup), so it doesn't cover them
        javax.swing.Timer wait = new javax.swing.Timer(500, null);
        wait.addActionListener(e -> {
            if (view.isBooting() || view.guideShowing()) return;
            wait.stop();
            if (!config.getBool("ui.whatsNew", true)) {
                view.showToast("Updated to WII-UU " + VERSION + " - what's new: Settings (F1) > System");
                return;
            }
            wiiuu.ui.ChangelogDialog.show(frame, "What's new in WII-UU " + VERSION,
                    before == null ? "WII-UU was updated to " + VERSION + "."
                            : "WII-UU was updated from " + before + " to " + VERSION + ".", notes, false);
        });
        wait.start();
    }

    /** Background check at start; a newer version shows a toast pointing at Settings. */
    private void checkForUpdateQuietly() {
        Thread t = new Thread(() -> {
            try {
                Updater.Release rel = new Updater(config, VERSION).check();
                if (rel != null) {
                    SwingUtilities.invokeLater(() -> view.showToast("WII-UU " + rel.version()
                            + " is available - Settings (F1) > System > Check for updates"));
                }
            } catch (Exception ignored) {
                // offline or site unreachable: try again next start
            }
        }, "update-check");
        t.setDaemon(true);
        t.start();
    }

    /** {@code wiiuu --upgrade} / {@code --check-update} from a terminal. */
    /** wiiuu --theme-check: works a theme out and prints its colours (as swatches), or what's wrong with it. */
    private static int themeCheck(Config config, String what) {
        if (what == null) {
            System.err.println("wiiuu --theme-check FILE.wtheme (or the name of an installed theme)");
            return 2;
        }
        String text, name;
        try {
            Path f = Paths.get(what);
            if (Files.isRegularFile(f)) {
                text = Files.readString(f);
                name = f.getFileName().toString().replaceFirst("\\.wtheme$", "");
            } else {
                wiiuu.ui.Themes.Entry e = wiiuu.ui.Themes.find(config, what);
                if (e == null) {
                    System.err.println("No theme file or installed theme called " + what);
                    return 2;
                }
                text = wiiuu.ui.Themes.read(e);
                name = e.id();
            }
        } catch (IOException e) {
            System.err.println("Can't read " + what + ": " + e.getMessage());
            return 2;
        }
        boolean colour = System.console() != null && System.getenv("NO_COLOR") == null;
        for (boolean systemDark : new boolean[]{false, true}) {
            try {
                wiiuu.ui.ThemeScript.Result r = wiiuu.ui.Themes.evaluate(text, name, systemDark);
                if (systemDark && !r.followsSystem()) break;          // the same either way
                System.out.println(r.name() + (r.author() != null ? " by " + r.author() : "") + " - "
                        + (r.dark() ? "dark" : "light") + (r.followsSystem() ? " (when the computer is " + (systemDark ? "dark)" : "light)") : ""));
                for (var e : r.colours().entrySet()) System.out.println("  " + swatch(e.getValue(), colour) + " " + String.format("%-18s %s", e.getKey(), hex(e.getValue())));
                for (var e : r.consoles().entrySet()) System.out.println("  " + swatch(e.getValue(), colour) + " " + String.format("console %-10s %s", e.getKey(), hex(e.getValue())));
                if (!r.fonts().isEmpty()) System.out.println("  font: " + String.join(", ", r.fonts()));
                for (String w : r.warnings()) System.out.println("  warning: " + w);
            } catch (wiiuu.ui.ThemeScript.Error e) {
                String[] lines = text.split("\n", -1);
                System.err.println(name + ".wtheme, " + e);
                if (e.line - 1 < lines.length) {
                    System.err.println("  " + lines[e.line - 1].replace('\t', ' '));
                    System.err.println("  " + " ".repeat(Math.max(0, e.column - 1)) + "^");
                }
                return 1;
            }
        }
        return 0;
    }

    private static String swatch(java.awt.Color c, boolean ansi) {
        if (!ansi) return "";
        return "\u001b[48;2;" + c.getRed() + ";" + c.getGreen() + ";" + c.getBlue() + "m    \u001b[0m";
    }

    private static String hex(java.awt.Color c) {
        return c.getAlpha() == 255 ? String.format("#%06X", c.getRGB() & 0xFFFFFF)
                : String.format("#%06X%02X", c.getRGB() & 0xFFFFFF, c.getAlpha());
    }

    private static int cliUpgrade(Config config, boolean install) {
        Updater u = new Updater(config, VERSION);
        try {
            System.out.println("WII-UU " + VERSION + ": checking " + config.get("update.url", Updater.DEFAULT_URL));
            Updater.Release rel = u.check();
            if (rel == null) {
                System.out.println("You have the newest version.");
                return 0;
            }
            System.out.println("Version " + rel.version() + " is available.");
            var notes = u.notes(rel);
            if (!notes.isEmpty()) System.out.println("\nWhat's new:\n\n" + wiiuu.core.Changelog.text(notes) + "\n");
            if (!install) return 0;
            System.out.println("Downloading " + rel.zipUrl());
            Path dir = u.download(rel);
            System.out.println("Checksum OK. Running the installer (settings, ROMs, paired phones are kept)...");
            return u.installNow(dir);
        } catch (Exception e) {
            System.err.println("Upgrade failed: " + e.getMessage());
            return 1;
        }
    }

    // ---- misc ---------------------------------------------------------------------------

    private static final int[] ICON_SIZES = {16, 20, 24, 32, 40, 48, 64, 128, 256};

    private static java.util.List<java.awt.Image> appIcons() {
        java.util.List<java.awt.Image> out = new java.util.ArrayList<>();
        for (int s : ICON_SIZES) out.add(appIcon(s));
        return out;
    }

    /** A Windows icon file with every size as PNG (the format Windows Vista and newer read). */
    private static void writeIco(Path out) throws IOException {
        java.util.List<byte[]> pngs = new java.util.ArrayList<>();
        for (int s : ICON_SIZES) {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            ImageIO.write(appIcon(s), "png", b);
            pngs.add(b.toByteArray());
        }
        java.nio.ByteBuffer ico = java.nio.ByteBuffer.allocate(6 + 16 * pngs.size()
                + pngs.stream().mapToInt(a -> a.length).sum()).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        ico.putShort((short) 0).putShort((short) 1).putShort((short) pngs.size());   // reserved, type icon, count
        int offset = 6 + 16 * pngs.size();
        for (int i = 0; i < pngs.size(); i++) {
            int s = ICON_SIZES[i];
            ico.put((byte) (s >= 256 ? 0 : s)).put((byte) (s >= 256 ? 0 : s))       // 0 means 256
                    .put((byte) 0).put((byte) 0).putShort((short) 1).putShort((short) 32)
                    .putInt(pngs.get(i).length).putInt(offset);
            offset += pngs.get(i).length;
        }
        for (byte[] png : pngs) ico.put(png);
        Files.write(out, ico.array());
    }

    private static BufferedImage appIcon(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.scale(size / 64.0, size / 64.0);
        g.setPaint(new GradientPaint(0, 0, new Color(0x3FC4F5), 0, 64, new Color(0x0090D0)));
        g.fill(new RoundRectangle2D.Float(2, 2, 60, 60, 18, 18));
        g.setColor(Color.WHITE);
        g.fill(new RoundRectangle2D.Float(14, 18, 36, 28, 8, 8));
        g.setColor(new Color(0x0090D0));
        g.fill(new RoundRectangle2D.Float(20, 23, 24, 18, 4, 4));
        g.dispose();
        return img;
    }

    /** Renders the menu to a PNG without a window (used for docs and testing). */
    private void snapshot(Path out, String which) throws IOException {
        MenuView v = new MenuView(config, this);
        v.setSize(1600, 900);
        v.setSnapshot(library.rescan());
        v.setServer("http://" + GamepadServer.lanAddress() + ":" + config.port() + "/",
                "http://" + GamepadServer.lanAddress() + ":" + config.port() + "/?code=4821", "4821", true);
        switch (which) {
            case "games" -> v.activate();
            case "pad" -> v.toggleGamepadInfo();
            case "power" -> v.showPowerMenu();
            case "playing" -> {
                v.activate();
                var s = library.snapshot();
                s.games().values().stream().filter(l -> !l.isEmpty()).findFirst().ifPresent(l -> v.setPlaying(l.get(0)));
            }
            default -> { }
        }
        v.settle();
        BufferedImage img = new BufferedImage(1600, 900, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        v.paint(g);
        g.dispose();
        ImageIO.write(img, "png", out.toFile());
        System.out.println("wrote " + out);
    }
}
