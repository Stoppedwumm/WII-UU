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
import java.security.SecureRandom;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import wiiuu.core.Config;
import wiiuu.core.Game;
import wiiuu.core.GameSystem;
import wiiuu.core.Launcher;
import wiiuu.core.Library;
import wiiuu.input.InputRouter;
import wiiuu.input.KeyMap;
import wiiuu.input.PadButton;

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
    }

    private static final long STALE_MS = 5000;
    private static final long FORGET_MS = 120_000;

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
    private final String code;
    private final Map<String, Client> clients = new ConcurrentHashMap<>();
    private HttpServer http;
    private ScheduledExecutorService reaper;
    private byte[] pageCache;
    private byte[] iconCache;
    private int port;

    public GamepadServer(Config config, Library library, Launcher launcher, InputRouter router, Host host) {
        this.config = config;
        this.library = library;
        this.launcher = launcher;
        this.router = router;
        this.host = host;
        String fixed = config.get("server.code", "").trim();
        this.code = fixed.isEmpty() ? String.format("%04d", new SecureRandom().nextInt(10000)) : fixed;
    }

    public void start(int port) throws IOException {
        this.port = port;
        http = HttpServer.create(new InetSocketAddress(port), 0);
        http.setExecutor(Executors.newFixedThreadPool(8, r -> {
            Thread t = new Thread(r, "gamepad-http");
            t.setDaemon(true);
            return t;
        }));
        http.createContext("/", this::handle);
        http.start();
        reaper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "gamepad-reaper");
            t.setDaemon(true);
            return t;
        });
        reaper.scheduleAtFixedRate(this::reap, 1, 1, TimeUnit.SECONDS);
        System.out.println("[gamepad] open " + url() + " on your phone (code " + code + ")");
    }

    public void stop() {
        if (http != null) http.stop(0);
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
        } else {
            touch(c);
        }
        host.padsChanged(connectedPads());
        json(ex, 200, new Json().obj().kv("id", c.id).kv("player", c.player).endObj());
    }

    private Client auth(HttpExchange ex) {
        String id = ex.getRequestHeaders().getFirst("X-Pad");
        if (id == null) return null;
        Client c = clients.get(id);
        if (c != null) touch(c);
        return c;
    }

    private void touch(Client c) {
        c.lastSeen = System.currentTimeMillis();
        if (!c.active) {
            c.active = true;
            c.player = freePlayer(c);
            host.padsChanged(connectedPads());
        }
    }

    private synchronized int freePlayer(Client self) {
        if (self != null && self.player > 0 && clients.values().stream()
                .noneMatch(o -> o != self && o.active && o.player == self.player)) {
            return self.player;
        }
        for (int p = 1; p <= KeyMap.MAX_PLAYERS; p++) {
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
                if (c.player > 0) router.releaseAll(c.player);
                changed = true;
            } else if (!c.active && now - c.lastSeen > FORGET_MS) {
                clients.remove(c.id);
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
                    if (b != null) router.button(c.player, b, p[2].equals("1"));
                } else if (p.length == 4 && p[0].equals("s")) {
                    router.stick(c.player, Integer.parseInt(p[1]), clamp(Float.parseFloat(p[2])), clamp(Float.parseFloat(p[3])));
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
                .kv("mode", g == null ? "menu" : "game")
                .kv("player", c.player)
                .kv("pads", connectedPads())
                .kv("library", library.snapshot().version())
                .kv("keys", router.canInjectKeys());
        j.key("game");
        if (g == null) j.val((String) null);
        else j.obj().kv("name", g.name()).kv("system", g.system().name()).kv("color", g.system().hexColor()).endObj();
        return j.endObj();
    }

    private Json libraryJson() {
        Library.Snapshot snap = library.snapshot();
        Json j = new Json().obj().kv("version", snap.version()).key("systems").arr();
        for (GameSystem s : wiiuu.core.Systems.ALL) {
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
