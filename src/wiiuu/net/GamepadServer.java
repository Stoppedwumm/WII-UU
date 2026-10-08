package wiiuu.net;

import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import wiiuu.core.Config;
import wiiuu.core.Game;
import wiiuu.core.GameSystem;
import wiiuu.core.Launcher;
import wiiuu.core.Library;
import wiiuu.input.InputRouter;
import wiiuu.input.KeyMap;
import wiiuu.input.PadButton;
import wiiuu.input.VirtualPads;
import wiiuu.screen.ScreenProfile;
import wiiuu.screen.AudioStreamer;
import wiiuu.screen.ScreenStreamer;

/**
 * Serves the phone gamepad web page and its small API:
 * <pre>
 *   POST /api/hello   code=1234[&id=..]  -> {id, player}
 *   GET  /api/status                      -> {mode, player, game, pads, library}
 *   GET  /api/library                     -> systems + games
 *   POST /api/input   "b A 1\ns 0 0.5 -0.2" (button / stick lines)
 *   POST /api/launch  sys=nes&id=abcd
 *   POST /api/close
 * </pre>
 * Every /api call except hello needs the X-Pad header returned by hello.
 */
public final class GamepadServer {

    /** Callbacks into the app. */
    public interface Host {
        /** @return null on success, otherwise an error message for the phone */
        String launchFromPad(Game game);

        void closeGame();

        void padsChanged(int connected);

        /** OpenBased, for its state and setup from the phone; null without it. */
        default wiiuu.core.OpenBased openBased() {
            return null;
        }

        /** Saves the OpenBased server and token (a blank token keeps the one set) and loads the list: null, or what's wrong. */
        default String setUpOpenBased(String url, String token) {
            return "OpenBased isn't available";
        }

        /** What WII-UU plays by itself while nobody uses it (null: it doesn't), for the GamePad. */
        default Idle idle() {
            return null;
        }
    }

    /** WII-UU playing by itself: the game, its score, and its latest remark (with an id and mood). */
    public record Idle(String game, int score, String line, int lineId, String mood) {}

    private static final long STALE_MS = 5000;
    /** paired phones are remembered (also across restarts) until unused for this long */
    private static final long FORGET_MS = 30L * 24 * 3600 * 1000;

    private static final class Client {
        final String id;
        volatile int player;
        volatile long lastSeen = System.currentTimeMillis();
        volatile boolean active = true;

        Client(String id, int player) {
            this.id = id;
            this.player = player;
        }
    }

    private final Config config;
    private final Library library;
    private final Launcher launcher;
    private final InputRouter router;
    private final Host host;
    private final ScreenStreamer screen;
    private final DsuServer dsu;
    private volatile VirtualPads pads;
    private final String code;
    private final Map<String, Client> clients = new ConcurrentHashMap<>();
    private HttpServer http;
    private HttpsServer https;
    private int httpsPort;
    private ScheduledExecutorService reaper;
    private byte[] pageCache;
    private byte[] musicCache;
    private byte[] iconCache;
    private int port;

    public GamepadServer(Config config, Library library, Launcher launcher, InputRouter router, Host host,
                         ScreenStreamer screen, DsuServer dsu) {
        this.config = config;
        this.library = library;
        this.launcher = launcher;
        this.router = router;
        this.host = host;
        this.screen = screen;
        this.dsu = dsu;
        String fixed = config.get("server.code", "").trim();
        if (fixed.isEmpty()) {
            // generated once and remembered, so phones stay paired when WII-UU restarts
            fixed = String.format("%04d", new SecureRandom().nextInt(10000));
            config.set("server.code", fixed);
            config.save();
        }
        this.code = fixed;
        loadClients();
    }

    // ---- remembered phones ---------------------------------------------------------------

    private Path clientsFile() {
        return config.home().resolve("pads.properties");
    }

    /** Phones paired before a restart come back as known (inactive) clients with their old player number. */
    private void loadClients() {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(clientsFile())) {
            p.load(in);
        } catch (IOException e) {
            return;
        }
        long now = System.currentTimeMillis();
        for (String id : p.stringPropertyNames()) {
            String[] v = p.getProperty(id).split(",");
            try {
                long seen = v.length > 1 ? Long.parseLong(v[1].trim()) : now;
                if (now - seen > FORGET_MS) continue;
                Client c = new Client(id, Integer.parseInt(v[0].trim()));
                c.active = false;
                c.lastSeen = seen;
                clients.put(id, c);
            } catch (NumberFormatException ignored) {
                // corrupt line
            }
        }
    }

    private synchronized void saveClients() {
        Properties p = new Properties();
        for (Client c : clients.values()) p.setProperty(c.id, c.player + "," + c.lastSeen);
        try {
            Files.createDirectories(config.home());
            try (var out = Files.newOutputStream(clientsFile())) {
                p.store(out, "WII-UU paired phones: id = player,lastSeen");
            }
        } catch (IOException e) {
            System.err.println("[gamepad] could not save paired phones: " + e.getMessage());
        }
    }

    private volatile AudioStreamer audio;

    /** The PC's sound for the phones (optional). */
    /** a message for the phones (shown once each), e.g. why a DS game's screens aren't split */
    private volatile String notice;
    private volatile long noticeId;

    public void notice(String text) {
        notice = text;
        noticeId++;
    }

    public void setAudio(AudioStreamer audio) {
        this.audio = audio;
    }

    /** Raw 16-bit stereo PCM newer than {@code after}; 204 (with the reason, if any) when none came. */
    private void audio(HttpExchange ex, Map<String, String> q) throws IOException {
        AudioStreamer a = audio;
        if (a == null) {
            json(ex, 404, error("sound is off (audio.enabled=false)"));
            return;
        }
        // phones that make the menu music themselves say so; for any other, it's sent along
        if (!"1".equals(q.get("music"))) wiiuu.screen.OwnSound.listenerWithoutMusic();
        long after;
        try {
            after = Long.parseLong(q.getOrDefault("after", "-1"));
        } catch (NumberFormatException e) {
            after = -1;
        }
        AudioStreamer.Chunk c;
        try {
            c = a.next(after, 1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.getResponseHeaders().set("X-Rate", Integer.toString(AudioStreamer.RATE));
        if (c == null) {
            String why = a.problem();
            if (why != null) ex.getResponseHeaders().set("X-Problem", why.replaceAll("[^\\x20-\\x7e]", " "));
            ex.sendResponseHeaders(204, -1);
            return;
        }
        ex.getResponseHeaders().set("Content-Type", "application/octet-stream");
        ex.getResponseHeaders().set("X-Seq", Long.toString(c.seq()));
        ex.sendResponseHeaders(200, c.pcm().length == 0 ? -1 : c.pcm().length);
        if (c.pcm().length > 0) {
            try (OutputStream out = ex.getResponseBody()) {
                out.write(c.pcm());
            } catch (IOException closed) {
                // phone went away
            }
        }
    }

    /** Real virtual controllers (Linux uinput), fed alongside keys and DSU. */
    public void setVirtualPads(VirtualPads pads) {
        this.pads = pads;
    }

    public void start(int port) throws IOException {
        this.port = port;
        // cached pool: every open screen stream holds a thread for as long as it is watched
        var pool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "gamepad-http");
            t.setDaemon(true);
            return t;
        });
        // send small packets (button replies, frame headers) at once instead of waiting to batch them
        System.setProperty("sun.net.httpserver.nodelay", "true");
        http = HttpServer.create(new InetSocketAddress(port), 0);
        http.setExecutor(pool);
        http.createContext("/", this::handle);
        http.start();
        if (config.getBool("server.https", true)) {
            // phones only give web pages the gyro over https, so offer a second, secure address
            int hp = config.getInt("server.httpsPort", 8443);
            try {
                https = HttpsServer.create(new InetSocketAddress(hp), 0);
                https.setHttpsConfigurator(new HttpsConfigurator(Tls.context(config.home(), lanAddress())));
                https.setExecutor(pool);
                https.createContext("/", this::handle);
                https.start();
                httpsPort = hp;
            } catch (Exception e) {
                https = null;
                System.err.println("[gamepad] https (for motion controls) unavailable: " + e.getMessage());
            }
        }
        reaper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "gamepad-reaper");
            t.setDaemon(true);
            return t;
        });
        reaper.scheduleAtFixedRate(this::reap, 1, 1, TimeUnit.SECONDS);
        System.out.println("[gamepad] open " + url() + " on your phone (code " + code + ")");
    }

    /** why WII-UU is closing (quit, restart, update, desktop, shutdown), told to the phones; null while running */
    private volatile String closing;
    private volatile boolean stopped;

    /**
     * Tells the phones that WII-UU is closing, so they show "WII-UU is not running" at once (and
     * why) instead of finding out when it stops answering: waits until each has asked once more
     * (they ask every second). Returns at once when no phone is connected.
     */
    public void announceClosing(String why) {
        if (stopped || closing != null) return;
        closing = why;
        if (connectedPads() == 0) return;
        try {
            Thread.sleep(1300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void stop() {
        stopped = true;
        saveClients();
        if (http != null) http.stop(0);
        if (https != null) https.stop(0);
        if (reaper != null) reaper.shutdownNow();
    }

    public String code() {
        return code;
    }

    public boolean requiresCode() {
        return config.getBool("server.requireCode", true);
    }

    public String url() {
        return "http://" + lanAddress() + ":" + port + "/";
    }

    /** The secure address (needed for gyro on phones), or null. */
    public String httpsUrl() {
        return https == null ? null : "https://" + lanAddress() + ":" + httpsPort + "/";
    }

    /** URL including the pairing code, used for the QR code. */
    public String pairingUrl() {
        return requiresCode() ? url() + "?code=" + code : url();
    }

    public int connectedPads() {
        return (int) clients.values().stream().filter(c -> c.active).count();
    }

    public static String lanAddress() {
        try {
            String fallback = null;
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                String n = ni.getName().toLowerCase();
                boolean virtualish = n.startsWith("docker") || n.startsWith("veth") || n.startsWith("br-")
                        || n.startsWith("vmnet") || n.startsWith("vbox") || n.startsWith("virbr");
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress() || a.isLinkLocalAddress()) continue;
                    if (a.isSiteLocalAddress() && !virtualish) return a.getHostAddress();
                    if (fallback == null) fallback = a.getHostAddress();
                }
            }
            if (fallback != null) return fallback;
        } catch (IOException ignored) {
            // fall through
        }
        return "localhost";
    }

    // ---- request handling ---------------------------------------------------------------

    private void handle(HttpExchange ex) throws IOException {
        try (ex) {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            switch (path) {
                case "/", "/index.html" -> send(ex, 200, "text/html; charset=utf-8", page());
                case "/manifest.json" -> send(ex, 200, "application/manifest+json", manifest());
                case "/music.js" -> send(ex, 200, "text/javascript; charset=utf-8", musicScript());
                case "/openbased/callback" -> openBasedCallback(ex);
                case "/icon.png" -> send(ex, 200, "image/png", icon());
                case "/api/hello" -> hello(ex);
                default -> {
                    if (!path.startsWith("/api/")) {
                        send(ex, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
                        return;
                    }
                    Client c = auth(ex);
                    if (c == null) {
                        json(ex, 401, new Json().obj().kv("error", "not paired").endObj());
                        return;
                    }
                    switch (path) {
                        case "/api/status" -> json(ex, 200, status(c));
                        case "/api/stream" -> stream(ex, query(ex).getOrDefault("m", "tv"), "1".equals(query(ex).get("raw")));
                        case "/api/frame" -> frame(ex, query(ex));
                        case "/api/audio" -> audio(ex, query(ex));
                        case "/api/library" -> json(ex, 200, libraryJson());
                        case "/api/input" -> {
                            if (!"POST".equals(method)) { json(ex, 405, error("POST only")); return; }
                            input(c, body(ex));
                            ex.sendResponseHeaders(204, -1);
                        }
                        case "/api/launch" -> {
                            if (!"POST".equals(method)) { json(ex, 405, error("POST only")); return; }
                            Map<String, String> f = form(body(ex));
                            var game = library.snapshot().find(f.getOrDefault("sys", ""), f.getOrDefault("id", ""));
                            if (game.isEmpty()) { json(ex, 404, error("Game not found - refresh the list")); return; }
                            String err = host.launchFromPad(game.get());
                            json(ex, err == null ? 200 : 409, err == null ? new Json().obj().kv("ok", true).endObj() : error(err));
                        }
                        case "/api/close" -> {
                            host.closeGame();
                            json(ex, 200, new Json().obj().kv("ok", true).endObj());
                        }
                        case "/api/openbased" -> {
                            if (!"POST".equals(method)) { json(ex, 405, error("POST only")); return; }
                            Map<String, String> f = form(body(ex));
                            wiiuu.core.OpenBased ob = host.openBased();
                            String action = f.getOrDefault("action", "");
                            if (ob != null && action.equals("signin")) {
                                // OAuth: the phone opens OpenBased's sign-in page, which comes back to /openbased/callback
                                String url = f.getOrDefault("url", "").trim();
                                if (!url.isEmpty()) {
                                    config.set("openbased.url", url);
                                    config.save();
                                }
                                try {
                                    String login = ob.loginUrl(ob.redirectUri(url()), f.get("back"));
                                    json(ex, 200, new Json().obj().kv("login", login).endObj());
                                } catch (IOException e) {
                                    json(ex, 422, error(e.getMessage()));
                                }
                                return;
                            }
                            if (ob != null && action.equals("signout")) {
                                ob.signOut();
                                json(ex, 200, new Json().obj().kv("ok", true).endObj());
                                return;
                            }
                            String err = host.setUpOpenBased(f.getOrDefault("url", ""), f.getOrDefault("token", ""));
                            json(ex, err == null ? 200 : 422, err == null ? new Json().obj().kv("ok", true).endObj() : error(err));
                        }
                        default -> json(ex, 404, error("unknown endpoint"));
                    }
                }
            }
        } catch (RuntimeException e) {
            System.err.println("[gamepad] " + e);
        }
    }

    private void hello(HttpExchange ex) throws IOException {
        Map<String, String> f = form(body(ex));
        if (requiresCode() && !code.equals(f.getOrDefault("code", "").trim())) {
            json(ex, 403, error("Wrong code - check the TV screen"));
            return;
        }
        Client c = clients.get(f.getOrDefault("id", ""));
        if (c == null) {
            c = new Client(UUID.randomUUID().toString(), freePlayer(null));
            clients.put(c.id, c);
            if (dsu != null && c.player > 0) dsu.setConnected(c.player, true);
        } else {
            touch(c);
        }
        saveClients();
        host.padsChanged(connectedPads());
        json(ex, 200, new Json().obj().kv("id", c.id).kv("player", c.player).endObj());
    }

    private Client auth(HttpExchange ex) {
        String id = ex.getRequestHeaders().getFirst("X-Pad");
        if (id == null) id = query(ex).get("c");     // <img> stream requests can't send headers
        if (id == null) return null;
        Client c = clients.get(id);
        if (c != null) touch(c);
        return c;
    }

    private void touch(Client c) {
        c.lastSeen = System.currentTimeMillis();
        if (!c.active) {
            c.active = true;
            int before = c.player;
            c.player = freePlayer(c);
            host.padsChanged(connectedPads());
            if (c.player != before) saveClients();
        }
        if (dsu != null && c.player > 0) dsu.setConnected(c.player, true);
    }

    private synchronized int freePlayer(Client self) {
        if (self != null && self.player > 0 && clients.values().stream()
                .noneMatch(o -> o != self && o.active && o.player == self.player)) {
            return self.player;
        }
        for (int p = 1; p <= wiiuu.core.Buzz.players(config); p++) {       // 4, or 8 in 8-player mode
            int pp = p;
            if (clients.values().stream().noneMatch(o -> o != self && o.active && o.player == pp)) return p;
        }
        return 0; // spectator: can browse and launch, input is ignored
    }

    private void reap() {
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (Client c : clients.values()) {
            if (c.active && now - c.lastSeen > STALE_MS) {
                c.active = false;
                if (c.player > 0) {
                    router.releaseAll(c.player);
                    if (dsu != null) dsu.setConnected(c.player, false);
                    if (pads != null) pads.release(c.player);
                }
                if (screen != null) screen.releaseTouch();
                changed = true;
            } else if (!c.active && now - c.lastSeen > FORGET_MS) {
                clients.remove(c.id);
                saveClients();
            }
        }
        if (changed) host.padsChanged(connectedPads());
    }

    private void input(Client c, String body) {
        if (c.player <= 0) return;
        for (String line : body.split("\n")) {
            String[] p = line.trim().split(" ");
            try {
                if (p.length == 3 && p[0].equals("b")) {
                    PadButton b = PadButton.parse(p[1]);
                    if (b != null) {
                        router.button(c.player, b, p[2].equals("1"));
                        if (dsu != null) dsu.button(c.player, b, p[2].equals("1"));
                        if (pads != null && launcher.isRunning()) pads.button(c.player, b, p[2].equals("1"));
                    }
                } else if (p.length == 4 && p[0].equals("s")) {
                    int stick = Integer.parseInt(p[1]);
                    float x = clamp(Float.parseFloat(p[2])), y = clamp(Float.parseFloat(p[3]));
                    router.stick(c.player, stick, x, y);
                    if (dsu != null) dsu.stick(c.player, stick, x, y);
                    if (pads != null && launcher.isRunning()) pads.stick(c.player, stick, x, y);
                } else if (p.length == 5 && p[0].equals("t")) {
                    // touch on the GamePad screen: "t <mode> <x> <y> <1 down | 2 move | 0 up>", x/y in 0..1
                    float x = Float.parseFloat(p[2]), y = Float.parseFloat(p[3]);
                    int state = Integer.parseInt(p[4]);
                    if (screen != null) screen.touch(p[1], x, y, state);
                    if (dsu != null) dsu.touch(c.player, x, y, state != 0);
                } else if (p.length == 7 && p[0].equals("m")) {
                    // motion, already in controller axes: accel (g) x y z, gyro (deg/s) pitch yaw roll
                    if (dsu != null) {
                        float[] v = new float[6];
                        for (int i = 0; i < 6; i++) {
                            float f = Float.parseFloat(p[i + 1]);
                            v[i] = Float.isFinite(f) ? Math.max(-4000, Math.min(4000, f)) : 0;
                        }
                        dsu.motion(c.player, v[0], v[1], v[2], v[3], v[4], v[5]);
                    }
                }
            } catch (NumberFormatException ignored) {
                // malformed line from the client; skip it
            }
        }
    }

    private static float clamp(float v) {
        return Float.isNaN(v) ? 0 : Math.max(-1, Math.min(1, v));
    }

    // ---- JSON payloads ------------------------------------------------------------------

    private Json status(Client c) {
        Game g = launcher.current();
        Json j = new Json().obj()
                .kv("closing", closing)
                .kv("mode", g == null ? "menu" : "game")
                .kv("player", c.player)
                .kv("pads", connectedPads())
                .kv("library", library.snapshot().version())
                .kv("keys", router.canInjectKeys())
                .kv("notice", notice).kv("noticeId", noticeId)
                .kv("keysWhy", router.canInjectKeys() ? null
                        : System.getProperty("os.name", "").toLowerCase().contains("mac") ? wiiuu.Main.MAC_KEYS_HELP
                        : "Key injection unavailable on the PC (headless or Wayland-only session).")
                .kv("tv", screen != null && screen.available())
                .kv("dsu", dsu != null)
                .kv("pad", pads != null && pads.usable())
                .kv("stream", screen == null ? "off" : screen.backend())
                .kv("audio", audio != null)
                .kv("captureBlocked", screen != null && screen.captureBlocked())
                .kv("buzz", g != null && wiiuu.core.Buzz.active(config, g))
                .kv("maxPlayers", wiiuu.core.Buzz.players(config));
        String secure = httpsUrl();
        j.key("https");
        if (secure == null) j.val((String) null);
        else j.val(secure + (requiresCode() ? "?code=" + code : ""));
        ScreenProfile second = screen == null ? null : screen.secondScreen();
        j.key("second");
        if (second == null) j.val((String) null);
        else j.val(second.label());
        j.key("game");
        if (g == null) j.val((String) null);
        else j.obj().kv("name", g.name()).kv("system", g.system().name()).kv("color", g.system().hexColor()).endObj();
        // OpenBased: set up or not (never the token), how many videos, what's wrong
        wiiuu.core.OpenBased ob = host.openBased();
        j.key("openbased");
        if (ob == null) j.val((String) null);
        else j.obj().kv("configured", wiiuu.core.OpenBased.configured(config)).kv("url", config.get("openbased.url", ""))
                .kv("count", ob.count()).kv("problem", ob.problem()).kv("user", ob.user())
                .kv("clientConfig", ob.clientConfig(ob.redirectUri(url()))).endObj();
        // the menu music, for phones that make it themselves (web/music.js); "local" when the sound
        // they get leaves it out
        Idle idle = host.idle();
        j.key("idle");
        if (idle == null) j.val((String) null);
        else j.obj().kv("game", idle.game()).kv("score", idle.score()).kv("line", idle.line())
                .kv("lineId", idle.lineId()).kv("mood", idle.mood()).endObj();
        wiiuu.screen.OwnSound.Music m = wiiuu.screen.OwnSound.music();
        j.key("music");
        if (m == null || m.id() == null) j.val((String) null);
        else j.obj().kv("id", m.id()).kv("pos", m.pos()).kv("length", m.length()).kv("on", m.on())
                .kv("volume", m.volume()).kv("next", m.next()).kv("local", wiiuu.screen.OwnSound.phoneMakes(m.id())).endObj();
        return j.endObj();
    }

    private Json libraryJson() {
        Library.Snapshot snap = library.snapshot();
        Json j = new Json().obj().kv("version", snap.version()).key("systems").arr();
        for (GameSystem s : wiiuu.core.Systems.MENU) {
            List<Game> games = snap.of(s);
            if (config.hidden(s) || games.isEmpty()) continue;
            j.obj().kv("id", s.id()).kv("name", s.name()).kv("short", s.shortName()).kv("color", s.hexColor())
                    .key("games").arr();
            for (Game g : games) j.obj().kv("id", g.id()).kv("name", g.name()).endObj();
            j.endArr().endObj();
        }
        return j.endArr().endObj();
    }

    private static Json error(String msg) {
        return new Json().obj().kv("error", msg).endObj();
    }

    // ---- static assets ------------------------------------------------------------------

    private byte[] page() throws IOException {
        if (pageCache == null) {
            try (InputStream in = GamepadServer.class.getResourceAsStream("/web/pad.html")) {
                if (in == null) throw new IOException("pad.html missing from jar");
                pageCache = in.readAllBytes();
            }
        }
        return pageCache;
    }

    /**
     * Where OpenBased's sign-in page sends the browser back to (OAuth2 redirect): the state ties it to
     * a sign-in WII-UU started, so this needs no pairing.
     */
    private void openBasedCallback(HttpExchange ex) throws IOException {
        wiiuu.core.OpenBased ob = host.openBased();
        Map<String, String> q = query(ex);
        wiiuu.core.OpenBased.Login r = ob == null ? new wiiuu.core.OpenBased.Login("OpenBased isn't available", null, null)
                : ob.finishLogin(q.get("state"), q.get("code"), q.get("error"), q.get("error_description"));
        String back = r.back() != null && r.back().matches("https?://[A-Za-z0-9.:\\[\\]-]+/?") ? r.back() : "/";
        String title = r.error() == null ? "Signed in to OpenBased" : "Not signed in";
        String text = r.error() == null
                ? (r.user() != null ? "WII-UU is signed in as " + r.user() + ". " : "WII-UU is signed in. ")
                  + "Your videos are on the home screen."
                : r.error();
        String page = "<!doctype html><html><head><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>"
                + "<title>" + html(title) + "</title><style>body{font-family:system-ui,sans-serif;background:#eef1f4;color:#2b3036;"
                + "display:flex;min-height:100vh;margin:0;align-items:center;justify-content:center;text-align:center}"
                + "main{background:#fff;border-radius:16px;padding:28px 32px;max-width:420px;box-shadow:0 6px 24px #0002}"
                + "h1{font-size:20px;margin:0 0 10px;color:" + (r.error() == null ? "#6c4ce0" : "#c62828") + "}"
                + "a{display:inline-block;margin-top:18px;padding:10px 22px;border-radius:99px;background:#009ac7;color:#fff;"
                + "text-decoration:none;font-weight:700}</style></head><body><main><h1>" + html(title) + "</h1><p>" + html(text)
                + "</p><a href='" + html(back) + "'>Back to the GamePad</a></main></body></html>";
        send(ex, r.error() == null ? 200 : 400, "text/html; charset=utf-8", page.getBytes(StandardCharsets.UTF_8));
        if (r.error() == null && r.user() != null) notice("OpenBased: signed in as " + r.user());
    }

    private static String html(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;").replace("\"", "&quot;");
    }

    /** The menu music's synthesizer, for phones that make the music themselves. */
    private byte[] musicScript() throws IOException {
        if (musicCache == null) {
            try (InputStream in = GamepadServer.class.getResourceAsStream("/web/music.js")) {
                if (in == null) throw new IOException("music.js missing from jar");
                musicCache = in.readAllBytes();
            }
        }
        return musicCache;
    }

    private static byte[] manifest() {
        return ("{\"name\":\"WII-UU GamePad\",\"short_name\":\"GamePad\",\"start_url\":\"/\","
                + "\"display\":\"fullscreen\",\"orientation\":\"landscape\",\"background_color\":\"#1c1f24\","
                + "\"theme_color\":\"#1c1f24\",\"icons\":[{\"src\":\"/icon.png\",\"sizes\":\"192x192\",\"type\":\"image/png\"}]}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private synchronized byte[] icon() throws IOException {
        if (iconCache != null) return iconCache;
        BufferedImage img = new BufferedImage(192, 192, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setPaint(new GradientPaint(0, 0, new Color(0x2A2E35), 0, 192, new Color(0x121417)));
        g.fill(new RoundRectangle2D.Float(0, 0, 192, 192, 48, 48));
        g.setColor(new Color(0x0AB5F0));
        g.fill(new RoundRectangle2D.Float(46, 52, 100, 88, 18, 18));
        g.setColor(Color.WHITE);
        g.fillOval(18, 88, 16, 16);
        g.fillOval(158, 88, 16, 16);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return iconCache = out.toByteArray();
    }

    /**
     * @param raw the page's own player reads the bytes itself: send them as a plain byte stream, because
     *            WebKit (Safari, every iPhone browser) handles multipart/x-mixed-replace specially and
     *            never hands it to fetch(); &lt;img&gt; players need the multipart type
     */
    private void stream(HttpExchange ex, String mode, boolean raw) throws IOException {
        if (screen == null) {
            json(ex, 404, error("screen streaming is off"));
            return;
        }
        ex.getResponseHeaders().set("Content-Type", raw ? "application/octet-stream"
                : "multipart/x-mixed-replace; boundary=" + ScreenStreamer.BOUNDARY);
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(200, 0);
        try (OutputStream out = ex.getResponseBody()) {
            screen.stream("second".equals(mode) ? "second" : "tv", out);
        } catch (IOException closed) {
            // phone stopped watching
        }
    }

    /** One JPEG of the screen, newer than {@code after}; 204 when none arrived within two seconds. */
    private void frame(HttpExchange ex, Map<String, String> q) throws IOException {
        if (screen == null) {
            json(ex, 404, error("screen streaming is off"));
            return;
        }
        long after;
        try {
            after = Long.parseLong(q.getOrDefault("after", "0"));
        } catch (NumberFormatException e) {
            after = 0;
        }
        ScreenStreamer.Frame f;
        try {
            f = screen.nextFrame("second".equals(q.get("m")) ? "second" : "tv", after, 2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        if (f == null) {
            ex.sendResponseHeaders(204, -1);
            return;
        }
        ex.getResponseHeaders().set("Content-Type", "image/jpeg");
        ex.getResponseHeaders().set("X-Seq", Long.toString(f.seq()));
        ex.sendResponseHeaders(200, f.jpeg().length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(f.jpeg());
        } catch (IOException closed) {
            // phone went away
        }
    }

    private static Map<String, String> query(HttpExchange ex) {
        String q = ex.getRequestURI().getRawQuery();
        return q == null ? Map.of() : form(q);
    }

    // ---- helpers ------------------------------------------------------------------------

    private static String body(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            byte[] b = in.readNBytes(64 * 1024);
            return new String(b, StandardCharsets.UTF_8);
        }
    }

    private static Map<String, String> form(String body) {
        Map<String, String> m = new HashMap<>();
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            m.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    private static void json(HttpExchange ex, int status, Json j) throws IOException {
        send(ex, status, "application/json; charset=utf-8", j.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange ex, int status, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }
}
