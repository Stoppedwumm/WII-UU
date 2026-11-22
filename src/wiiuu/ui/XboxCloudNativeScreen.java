package wiiuu.ui;

import wiiuu.core.Config;
import wiiuu.core.MiniJson;
import wiiuu.core.PluginStore;
import wiiuu.core.XboxCloudRuntime;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Native WII-UU Xbox Cloud Gaming frontend. Device-code login and game selection
 * are Swing controls; only the WebRTC media player runs in portable Chromium.
 * Microsoft/Xbox grants stay inside the local Node sidecar, not the browser.
 */
public final class XboxCloudNativeScreen extends JFrame {
    private static volatile XboxCloudNativeScreen instance;

    public static synchronized void open(Config config, PluginStore.Entry entry) {
        SwingUtilities.invokeLater(() -> {
            if (instance != null && instance.isDisplayable()) {
                instance.toFront();
                instance.requestFocus();
            } else {
                instance = new XboxCloudNativeScreen(config, entry);
                instance.setVisible(true);
            }
        });
    }

    /**
     * Give connected WII-UU controllers a native focus path while the catalog is
     * visible. Once the browser stream starts, input goes to virtual Xbox pads.
     */
    public static boolean controllerButton(wiiuu.input.PadButton button, boolean down) {
        XboxCloudNativeScreen screen = instance;
        if (screen == null || !screen.isDisplayable() || screen.browser != null) return false;
        if (!down) return true;
        SwingUtilities.invokeLater(() -> {
            if (screen.closing) return;
            int index = screen.list.getSelectedIndex(), count = screen.visibleGames.getSize();
            switch (button) {
                case DOWN, LS_DOWN -> {
                    if (count > 0) screen.list.setSelectedIndex(Math.min(count - 1, index + 1));
                }
                case UP, LS_UP -> {
                    if (count > 0) screen.list.setSelectedIndex(Math.max(0, index < 0 ? 0 : index - 1));
                }
                case A -> screen.startGame();
                case B, HOME -> screen.close();
                case PLUS -> screen.startLogin();
                case R, ZR -> screen.loadGames();
                default -> { }
            }
            if (screen.list.getSelectedIndex() >= 0)
                screen.list.ensureIndexIsVisible(screen.list.getSelectedIndex());
        });
        return true;
    }

    private static volatile long lastAxisInput;

    public static boolean controllerStick(int stick, float x, float y) {
        XboxCloudNativeScreen screen = instance;
        if (screen == null || !screen.isDisplayable() || screen.browser != null) return false;
        if (stick == 0 && Math.abs(y) > 0.6f) {
            long now = System.nanoTime();
            if (now - lastAxisInput > TimeUnit.MILLISECONDS.toNanos(150)) {
                lastAxisInput = now;
                controllerButton(y > 0 ? wiiuu.input.PadButton.DOWN : wiiuu.input.PadButton.UP, true);
            }
        }
        return true;
    }

    private final Config config;
    private final PluginStore.Entry entry;
    private final JLabel status = new JLabel("Install the streaming runtime to begin");
    private final JLabel code = new JLabel("Not signed in");
    private final JLabel instructions = new JLabel("Sign in with your Microsoft account using another device");
    private final JButton install = new JButton("Install / update streaming runtime");
    private final JButton login = new JButton("Get Microsoft login code");
    private final JButton refresh = new JButton("Load available games");
    private final JButton play = new JButton("Play selected game");
    private final JButton logout = new JButton("Sign out");
    private final JButton stop = new JButton("End stream");
    private final JTextField search = new JTextField();
    private final DefaultListModel<Game> visibleGames = new DefaultListModel<>();
    private final JList<Game> list = new JList<>(visibleGames);
    private List<Game> allGames = List.of();
    private Backend backend;
    private volatile Process browser;
    private boolean closing;

    private record Game(String id, String name) {
        @Override public String toString() { return name; }
    }

    private XboxCloudNativeScreen(Config config, PluginStore.Entry entry) {
        super("WII-UU | Xbox Cloud Gaming");
        this.config = config;
        this.entry = entry;
        setSize(new Dimension(820, 620));
        setMinimumSize(new Dimension(620, 480));
        setLocationRelativeTo(null);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        getContentPane().setBackground(new Color(13, 19, 29));

        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.setBorder(BorderFactory.createEmptyBorder(22, 26, 16, 26));
        JLabel title = new JLabel("Xbox Cloud Gaming");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 28));
        top.add(title); top.add(Box.createVerticalStrut(8));
        top.add(status); top.add(Box.createVerticalStrut(10));
        top.add(code); top.add(instructions);
        top.add(Box.createVerticalStrut(8));

        JPanel actions = new JPanel(new GridLayout(2, 3, 8, 8));
        for (JButton button : List.of(install, login, refresh, logout, stop)) actions.add(button);
        actions.add(new JLabel("Use Enter to start a selected title"));
        top.add(actions);
        add(top, BorderLayout.NORTH);

        JPanel middle = new JPanel(new BorderLayout(7,7));
        middle.setBorder(BorderFactory.createEmptyBorder(10, 26, 16, 26));
        search.setToolTipText("Filter the Xbox catalog by name");
        middle.add(search, BorderLayout.NORTH);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setVisibleRowCount(14);
        middle.add(new JScrollPane(list), BorderLayout.CENTER);
        middle.add(play, BorderLayout.SOUTH);
        add(middle, BorderLayout.CENTER);

        JLabel foot = new JLabel("WII-UU renders this screen natively. Chromium handles video and gamepad WebRTC only.");
        foot.setBorder(BorderFactory.createEmptyBorder(10, 26, 16, 26));
        add(foot, BorderLayout.SOUTH);

        code.setFont(code.getFont().deriveFont(Font.BOLD, 18));
        install.addActionListener(e -> {
            install.setEnabled(false);
            XboxCloudRuntime.install(config, message -> SwingUtilities.invokeLater(() -> {
                status.setText(message);
                if (!XboxCloudRuntime.installing()) install.setEnabled(true);
                if (XboxCloudRuntime.ready(config)) connectBackend();
            }));
        });
        login.addActionListener(e -> startLogin());
        refresh.addActionListener(e -> loadGames());
        logout.addActionListener(e -> command("logout", result -> {
            allGames = List.of(); updateFilter();
            code.setText("Signed out");
            status.setText("Sign in again to use Xbox Cloud Gaming");
        }));
        play.addActionListener(e -> startGame());
        stop.addActionListener(e -> stopGame());
        list.addListSelectionListener(e -> play.setEnabled(list.getSelectedValue() != null));
        list.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) startGame();
            }
        });
        search.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { updateFilter(); }
            public void removeUpdate(DocumentEvent e) { updateFilter(); }
            public void changedUpdate(DocumentEvent e) { updateFilter(); }
        });
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { close(); }
        });
        play.setEnabled(false); refresh.setEnabled(false); login.setEnabled(false);
        if (XboxCloudRuntime.ready(config)) connectBackend();
        else status.setText("Select Install / update to get the portable Chromium runtime.");
    }

    private void updateFilter() {
        Game selected = list.getSelectedValue();
        String query = search.getText().trim().toLowerCase(java.util.Locale.ROOT);
        visibleGames.clear();
        for (Game game : allGames) {
            if (game.name().toLowerCase(java.util.Locale.ROOT).contains(query))
                visibleGames.addElement(game);
        }
        if (selected != null) list.setSelectedValue(selected, true);
    }

    private void connectBackend() {
        if (backend != null || closing || !XboxCloudRuntime.ready(config)) return;
        status.setText("Starting local Xbox authentication service...");
        CompletableFuture.runAsync(() -> {
            try {
                Path root = XboxCloudRuntime.home(config);
                Path user = Files.createDirectories(config.userHome().resolve("xcloud"));
                Backend next = new Backend(root, user, message -> SwingUtilities.invokeLater(() -> onMessage(message)));
                SwingUtilities.invokeLater(() -> {
                    if (closing) { next.close(); return; }
                    backend = next;
                    login.setEnabled(true);
                    refresh.setEnabled(true);
                    command("signedIn", result -> {
                        if (Boolean.TRUE.equals(result.get("signedIn"))) {
                            code.setText("Previously signed in");
                            status.setText("Microsoft session found. Load your game catalog.");
                            loadGames();
                        } else {
                            status.setText("Ready for Microsoft device-code login.");
                        }
                    });
                });
            } catch (Exception error) {
                SwingUtilities.invokeLater(() -> status.setText("Could not start Xbox service: " + error.getMessage()));
            }
        });
    }

    private void onMessage(Map<String, Object> message) {
        if (closing) return;
        String type = MiniJson.string(message.get("event"));
        if ("code".equals(type)) {
            String value = MiniJson.string(message.get("code"));
            String link = MiniJson.string(message.get("url"));
            if (value == null || link == null) { status.setText("Invalid Microsoft login challenge"); return; }
            code.setText("Your Microsoft code: " + value);
            instructions.setText("Open " + link + " on another device and enter this code.");
            status.setText("Awaiting your approval on the Microsoft website...");
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                    new StringSelection(value), null);
        } else if ("signedIn".equals(type)) {
            code.setText("Microsoft account connected");
            status.setText("Loading your Xbox catalog...");
            loadGames();
        } else if ("authError".equals(type)) {
            status.setText("Microsoft authentication failed: " + MiniJson.string(message.get("message")));
        } else if ("ended".equals(type)) {
            status.setText("Local Xbox helper has stopped");
        }
    }

    private void command(String cmd, Consumer<Map<String, Object>> done) {
        if (backend == null) {
            status.setText("Install the streaming runtime first");
            return;
        }
        backend.call(cmd).whenComplete((result, error) -> SwingUtilities.invokeLater(() -> {
            if (closing) return;
            if (error != null) status.setText(cmd + ": " + error.getMessage());
            else done.accept(result);
        }));
    }

    private void startLogin() {
        status.setText("Requesting a device code from Microsoft...");
        command("auth", result -> status.setText("Follow the Microsoft device-code instructions above"));
    }

    private void loadGames() {
        status.setText("Fetching playable Xbox Cloud Gaming titles...");
        refresh.setEnabled(false);
        command("games", result -> {
            List<Game> loaded = new ArrayList<>();
            for (Object item : MiniJson.array(result.get("games"))) {
                Map<String, Object> row = MiniJson.object(item);
                String id = MiniJson.string(row.get("id")), name = MiniJson.string(row.get("name"));
                if (id != null && !id.isBlank() && name != null)
                    loaded.add(new Game(id, name));
            }
            allGames = List.copyOf(loaded);
            updateFilter();
            refresh.setEnabled(true);
            status.setText("Found " + loaded.size() + " playable titles. Choose one and press Play.");
        });
        // Preserve retry even after an authorization/network error.
        javax.swing.Timer reset = new javax.swing.Timer(10000, e -> refresh.setEnabled(true));
        reset.setRepeats(false); reset.start();
    }

    private void startGame() {
        Game chosen = list.getSelectedValue();
        if (chosen == null || backend == null) return;
        if (browser != null && browser.isAlive()) {
            status.setText("A cloud stream is already running; end it before starting another.");
            return;
        }
        try {
            String url = "http://127.0.0.1:" + backend.port +
                    "/?title=" + URLEncoder.encode(chosen.id(), StandardCharsets.UTF_8)
                    + "#" + backend.secret;
            Path profile = Files.createDirectories(config.userHome().resolve("xcloud/chrome-profile"));
            ProcessBuilder builder = new ProcessBuilder(
                    XboxCloudRuntime.chromium(config).toString(),
                    "--user-data-dir=" + profile.toAbsolutePath(),
                    "--app=" + url,
                    "--no-first-run", "--disable-sync", "--disable-background-networking");
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD);
            Process process = builder.start();
            browser = process;
            PluginStore.trackExternal(config, entry, process);
            status.setText("Launching " + chosen.name() + " in portable Chromium...");
            process.onExit().thenRun(() -> SwingUtilities.invokeLater(() -> {
                if (browser == process) browser = null;
                if (!closing) status.setText("Chromium player closed. Choose another game.");
            }));
        } catch (Exception error) {
            status.setText("Chromium launch failed: " + error.getMessage());
        }
    }

    private void stopGame() {
        Process process = browser;
        if (process != null && process.isAlive()) {
            process.descendants().forEach(ProcessHandle::destroy);
            process.destroy();
        }
        browser = null;
        command("stop", result -> status.setText("Xbox streaming session ended"));
    }

    private synchronized void close() {
        if (closing) return;
        closing = true;
        Process process = browser;
        if (process != null && process.isAlive()) {
            process.descendants().forEach(ProcessHandle::destroy);
            process.destroy();
        }
        if (backend != null) backend.close();
        dispose();
        instance = null;
    }

    private static final class Backend implements AutoCloseable {
        private final Process process;
        private final BufferedWriter writer;
        private final AtomicInteger serial = new AtomicInteger();
        private final ConcurrentHashMap<Integer, CompletableFuture<Map<String, Object>>> requests =
                new ConcurrentHashMap<>();
        private final Consumer<Map<String, Object>> onEvent;
        private volatile int port;
        private volatile String secret;
        private final CompletableFuture<Boolean> initialized = new CompletableFuture<>();

        Backend(Path runtime, Path user, Consumer<Map<String, Object>> onEvent)
                throws IOException, InterruptedException {
            this.onEvent = onEvent;
            ProcessBuilder builder = new ProcessBuilder("node", runtime.resolve("service.cjs").toString(),
                    runtime.toString(), user.toString()).directory(runtime.toFile())
                    .redirectError(ProcessBuilder.Redirect.appendTo(runtime.resolve("backend.log").toFile()));
            process = builder.start();
            writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            Thread reader = new Thread(this::read, "wiiuu-xcloud-backend");
            reader.setDaemon(true); reader.start();
            try { initialized.get(15, TimeUnit.SECONDS); }
            catch (Exception error) {
                process.destroyForcibly();
                throw new IOException("Xbox backend did not become ready (see backend.log)", error);
            }
        }

        private void read() {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Map<String, Object> msg;
                    try { msg = MiniJson.object(MiniJson.parse(line)); }
                    catch (RuntimeException ignored) { continue; }
                    if ("ready".equals(msg.get("event"))) {
                        Double value = MiniJson.number(msg.get("port"));
                        port = value == null ? 0 : value.intValue();
                        secret = MiniJson.string(msg.get("secret"));
                        if (port > 0 && secret != null && secret.matches("[a-f0-9]{64}"))
                            initialized.complete(true);
                        else initialized.completeExceptionally(new IOException("Invalid backend handshake"));
                        continue;
                    }
                    Double id = MiniJson.number(msg.get("id"));
                    if (id != null) {
                        CompletableFuture<Map<String, Object>> promise = requests.remove(id.intValue());
                        if (promise != null) {
                            if (Boolean.TRUE.equals(msg.get("ok")))
                                promise.complete(MiniJson.object(msg.get("result")));
                            else promise.completeExceptionally(new IOException(
                                    String.valueOf(msg.getOrDefault("error", "Unknown Xbox error"))));
                        }
                    } else { onEvent.accept(msg); }
                }
            } catch (IOException ignored) { }
            finally {
                initialized.completeExceptionally(new IOException("Backend ended unexpectedly"));
                for (var item : requests.values())
                    item.completeExceptionally(new IOException("Xbox backend exited"));
                requests.clear();
                onEvent.accept(Map.of("event", "ended"));
            }
        }

        synchronized CompletableFuture<Map<String, Object>> call(String command) {
            int id = serial.incrementAndGet();
            CompletableFuture<Map<String, Object>> response = new CompletableFuture<>();
            requests.put(id, response);
            try {
                if (!List.of("auth", "games", "signedIn", "logout", "stop").contains(command))
                    throw new IOException("Unsupported native UI command");
                writer.write("{\"id\":" + id + ",\"cmd\":\"" + command + "\"}\n");
                writer.flush();
            } catch (IOException error) {
                requests.remove(id);
                response.completeExceptionally(error);
            }
            return response.orTimeout(75, TimeUnit.SECONDS);
        }

        @Override public void close() {
            process.destroy();
            if (process.isAlive()) process.destroyForcibly();
        }
    }
}
