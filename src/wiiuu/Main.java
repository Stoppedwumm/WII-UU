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
import wiiuu.ui.SettingsDialog;

/** WII-UU: a Wii U styled emulator launcher with a phone-as-GamePad web server. */
public final class Main implements MenuView.Actions, GamepadServer.Host, Launcher.Listener {
    public static final String VERSION = "1.4.0";

    private final Config config;
    private final Library library;
    private final Launcher launcher;
    private final InputRouter router;
    private GamepadServer server;
    private DsuServer dsuServer;
    private volatile VirtualPads vpads;
    private JFrame frame;
    private MenuView view;

    private Main(Config config) {
        this.config = config;
        this.library = new Library(config);
        this.launcher = new Launcher(config);
        this.router = new InputRouter(new KeyMap(config), launcher::isRunning);
        // type each emulator's own default keys (Dolphin, PPSSPP, mGBA, melonDS, ...), so nothing needs mapping
        router.setKeyProfile(() -> {
            Game g = launcher.current();
            return g == null ? null : KeyMap.profileFor(g.system().id(), config.command(g.system()));
        });
        // Typing keys into emulators is the fallback for when real virtual controllers aren't available:
        // input.keys = auto (default: only without virtual pads) | on | off; system.<id>.keys overrides.
        router.setKeysEnabled(() -> {
            Game g = launcher.current();
            if (g == null) return true;
            String perSystem = config.get("system." + g.system().id() + ".keys", null);
            if (perSystem != null) return Boolean.parseBoolean(perSystem.trim());
            return switch (config.get("input.keys", "auto").trim().toLowerCase()) {
                case "on", "true" -> true;
                case "off", "false" -> false;
                default -> vpads == null || !vpads.usable();
            };
        });
        launcher.addListener(this);
    }

    public static void main(String[] args) throws Exception {
        // the phone server sends small packets at once (read when the HTTP server first loads)
        System.setProperty("sun.net.httpserver.nodelay", "true");
        Path home = Config.defaultHome();
        Boolean fullscreen = null;
        Integer port = null;
        boolean noServer = false;
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
                    ImageIO.write(appIcon(256), "png", Paths.get(args[++i]).toFile());
                    return;
                }
                case "--upgrade", "--check-update" -> {
                    System.exit(cliUpgrade(new Config(home), args[i].equals("--upgrade")));
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
                              --check-update / --upgrade  check for / install a newer version
                            Keys: arrows move, Enter opens, Esc back, F1 settings, F2 GamePad, F5 refresh,
                                  F11 fullscreen, Ctrl+Q closes a running game.""".formatted(VERSION));
                    return;
                }
                default -> System.err.println("Ignoring unknown option " + args[i]);
            }
        }
        Config config = new Config(home);
        if (port != null) config.set("server.port", port.toString());
        if (fullscreen != null) config.set("ui.fullscreen", fullscreen.toString());
        if (!Files.exists(home.resolve("config.properties"))) config.save();

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
        app.library.addListener(s -> SwingUtilities.invokeLater(() -> app.view.setSnapshot(s)));
        app.library.rescanAsync();
        if (startServer) app.startServer();
        if (config.getBool("update.check", true)) app.checkForUpdateQuietly();
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
        if (screen != null) {
            screen.setOnBlocked(msg -> SwingUtilities.invokeLater(() -> view.showToast(
                    System.getProperty("os.name", "").toLowerCase().contains("mac")
                            ? "GamePad screen is black: allow Screen Recording for WII-UU (Settings F1 > General > macOS permissions)"
                            : "GamePad screen is black: screen capture seems blocked")));
        }
        server = new GamepadServer(config, library, launcher, router, this, screen, dsu);
        if (config.getBool("audio.enabled", true)) {
            server.setAudio(new AudioStreamer(config, screen != null ? screen::macAudioHelper : () -> null));
        }
        if (config.getBool("input.gamepad", true)) {
            VirtualPads v = new VirtualPads();
            if (v.start(config.home())) {
                vpads = v;
                server.setVirtualPads(v);
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
        frame = new JFrame("WII-UU");
        frame.setIconImage(appIcon(64));
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
        if (on) frame.setExtendedState(Frame.MAXIMIZED_BOTH);
        else {
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
            view.showToast(e.getMessage());
        }
    }

    @Override
    public void openSettings() {
        new SettingsDialog(frame, config, () -> {
            view.setSoundsEnabled(config.getBool("ui.sounds", true));
            view.setMusicEnabled(config.getBool("ui.music", true));
            view.setThemeMode(config.get("ui.theme", "auto"));
            view.showToast("Settings saved");
            library.rescanAsync();
        }).withUpdater(new Updater(config, VERSION), this::quit).setVisible(true);
        view.requestFocusInWindow();
    }

    @Override
    public void quit() {
        launcher.stop(true);        // make sure the emulator is really gone before we exit
        if (server != null) server.stop();
        if (dsuServer != null) dsuServer.stop();
        if (vpads != null) vpads.stop();
        System.exit(0);
    }

    @Override
    public void refresh() {
        library.rescanAsync();
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
    public void padsChanged(int connected) {
        SwingUtilities.invokeLater(() -> {
            if (view != null) view.setPads(connected);
        });
    }

    // ---- Launcher.Listener --------------------------------------------------------------

    @Override
    public void started(Game game) {
        SwingUtilities.invokeLater(() -> {
            view.setPlaying(game);
            if (config.getBool("ui.minimizeOnLaunch", true)) frame.setState(Frame.ICONIFIED);
        });
    }

    @Override
    public void exited(Game game, int exitCode, boolean quickFailure, Path log) {
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

    /** Background check at start; a newer version shows a toast pointing at Settings. */
    private void checkForUpdateQuietly() {
        Thread t = new Thread(() -> {
            try {
                Updater.Release rel = new Updater(config, VERSION).check();
                if (rel != null) {
                    SwingUtilities.invokeLater(() -> view.showToast("WII-UU " + rel.version()
                            + " is available - Settings (F1) > General > Check for updates"));
                }
            } catch (Exception ignored) {
                // offline or site unreachable: try again next start
            }
        }, "update-check");
        t.setDaemon(true);
        t.start();
    }

    /** {@code wiiuu --upgrade} / {@code --check-update} from a terminal. */
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
