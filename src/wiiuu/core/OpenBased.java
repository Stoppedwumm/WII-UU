package wiiuu.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.net.URI;
import java.net.URLEncoder;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Videos from an OpenBased media server, as a channel next to the consoles: its movies and episodes
 * are listed like games ({@link Systems#OPENBASED}), with their posters as covers, and play full
 * screen on the TV in a video player (mpv, else VLC or ffplay), in Console Mode as on a desktop.
 *
 * <p>Signing in is OAuth2 Authorization Code with PKCE ({@link #loginUrl}, {@link #finishLogin}): the
 * user signs in on OpenBased's own page, on the phone or in a browser, which sends them back to
 * WII-UU's GamePad server (/openbased/callback). OpenBased gives public clients only short-lived
 * access tokens and no refresh tokens, so WII-UU uses the fresh access token once, to create its own
 * personal access token ({@code ob_pat_...}, a year if the server allows it) with just the scopes it
 * needs: media.read, media.stream, history.read and history.write, plus profile so that Sign out can
 * revoke it. Signing in again replaces it and revokes the old one. A token made by hand can still be
 * pasted instead.
 *
 * <p>OpenBased must know WII-UU as a client (client id openbased.clientId, default "wiiuu"), with the
 * redirect URI {@link #redirectUri}; {@link #clientConfig} is the snippet for its application.yml.
 * OpenBased never accepts a personal token in a URL, so
 * the player is given a link to a small proxy on 127.0.0.1 that adds the token to each request
 * (with Range, so seeking works). With mpv, playback starts where it was left, and the position is
 * saved back to OpenBased (continue watching in every other OpenBased app too).
 *
 * <p>Settings: openbased.url, openbased.token (with openbased.tokenId and openbased.user when WII-UU
 * made it), openbased.clientId, openbased.redirectUri (else the GamePad server's address), and
 * openbased.player for a player command of one's own ({url}, {title} and {start} in seconds).
 */
public final class OpenBased implements Launcher.Listener {
    private static final int MAX_ITEMS = 2000;
    private static final String OS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

    private final Config config;
    private final Library library;
    private final ExecutorService posters = Executors.newFixedThreadPool(4, daemon("openbased-posters"));
    private final String secret = HexFormat.of().formatHex(new SecureRandom().generateSeed(16));

    /** mediaId -> duration in seconds (0 if unknown), for progress */
    private final Map<String, Double> durations = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile String problem;
    private volatile int count;
    private HttpServer proxy;
    private volatile Tracker tracker;

    public OpenBased(Config config, Library library) {
        this.config = config;
        this.library = library;
    }

    public static boolean configured(Config config) {
        return !config.get("openbased.url", "").isBlank() && !config.get("openbased.token", "").isBlank();
    }

    public static boolean handles(Game g) {
        return g != null && g.system() == Systems.OPENBASED;
    }

    /** Why the list couldn't be loaded, or null. */
    public String problem() {
        return problem;
    }

    private static volatile String lastProblem;

    /** The same, for the menu. */
    public static String lastProblem() {
        return lastProblem;
    }

    /** How many videos the last refresh found. */
    public int count() {
        return count;
    }

    private String base() {
        String u = config.get("openbased.url", "").trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        if (!u.isEmpty() && !u.contains("://")) u = "http://" + u;
        return u;
    }

    private String token() {
        return config.get("openbased.token", "").trim();
    }

    // ---- signing in (OAuth2 Authorization Code + PKCE) -----------------------------------------

    /** Scopes WII-UU's own token gets; "profile" lets it revoke itself when you sign out. */
    private static final String SCOPES = "media.read media.stream history.read history.write profile";
    private static final long LOGIN_MS = 10 * 60 * 1000;

    private record Pending(String verifier, String redirect, String back, long created) {}

    private final Map<String, Pending> logins = new java.util.concurrent.ConcurrentHashMap<>();

    public String clientId() {
        String id = config.get("openbased.clientId", "").trim();
        return id.isEmpty() ? "wiiuu" : id;
    }

    /** Where OpenBased sends the browser back to: WII-UU's GamePad server, unless set otherwise. */
    public String redirectUri(String gamepadBase) {
        String custom = config.get("openbased.redirectUri", "").trim();
        if (!custom.isEmpty()) return custom;
        String b = gamepadBase;
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b + "/openbased/callback";
    }

    /** The client entry OpenBased needs in its application.yml (under openbased.clients). */
    public String clientConfig(String redirectUri) {
        return "- client-id: " + clientId() + "\n  name: WII-UU\n  redirect-uris:\n    - " + redirectUri
                + "\n  grant-types: [authorization_code]\n";
    }

    /** Who WII-UU is signed in as (when it made its token itself), or null. */
    public String user() {
        String u = config.get("openbased.user", "").trim();
        return u.isEmpty() || !configured(config) ? null : u;
    }

    /**
     * Starts a sign-in: the OpenBased page to open in a browser. {@code back} is where the "done"
     * page links to (the GamePad page the phone came from), or null.
     */
    public String loginUrl(String redirectUri, String back) throws IOException {
        if (base().isEmpty()) throw new IOException("Enter the OpenBased server address first");
        long now = System.currentTimeMillis();
        logins.values().removeIf(p -> now - p.created() > LOGIN_MS);
        SecureRandom random = new SecureRandom();
        byte[] v = new byte[32], s = new byte[16];
        random.nextBytes(v);
        random.nextBytes(s);
        String verifier = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(v);
        String state = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(s);
        String challenge;
        try {
            challenge = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                    java.security.MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        logins.put(state, new Pending(verifier, redirectUri, back, now));
        return base() + "/oauth2/authorize?response_type=code"
                + "&client_id=" + enc(clientId())
                + "&redirect_uri=" + enc(redirectUri)
                + "&scope=" + enc("openid " + SCOPES)
                + "&state=" + enc(state)
                + "&code_challenge=" + enc(challenge) + "&code_challenge_method=S256";
    }

    /** Result of a sign-in: what went wrong (or null), who signed in, and where "back" goes. */
    public record Login(String error, String user, String back) {}

    /**
     * Completes a sign-in at the redirect: trades the code for an access token, makes WII-UU's own
     * token with it, and loads the list.
     */
    public synchronized Login finishLogin(String state, String code, String error, String errorDescription) {
        Pending p = state == null ? null : logins.remove(state);
        if (p == null || System.currentTimeMillis() - p.created() > LOGIN_MS) {
            return new Login("This sign-in has expired or was already used. Start it again from WII-UU.", null, null);
        }
        if (error != null) {
            return new Login("OpenBased said: " + (errorDescription != null ? errorDescription : error), null, p.back());
        }
        if (code == null || code.isBlank()) return new Login("OpenBased sent no code", null, p.back());
        try {
            // the code, for a short-lived access token
            HttpURLConnection c = (HttpURLConnection) URI.create(base() + "/oauth2/token").toURL().openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(8000);
            c.setReadTimeout(20000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            c.setRequestProperty("Accept", "application/json");
            String form = "grant_type=authorization_code&code=" + enc(code) + "&redirect_uri=" + enc(p.redirect())
                    + "&client_id=" + enc(clientId()) + "&code_verifier=" + enc(p.verifier());
            try (OutputStream out = c.getOutputStream()) {
                out.write(form.getBytes(StandardCharsets.UTF_8));
            }
            int status = c.getResponseCode();
            if (status != 200) {
                String why = null;
                try (InputStream err = c.getErrorStream()) {
                    if (err != null) {
                        Map<String, Object> e = MiniJson.object(MiniJson.parse(new String(err.readAllBytes(), StandardCharsets.UTF_8)));
                        why = MiniJson.string(e.get("error_description"));
                        if (why == null) why = MiniJson.string(e.get("error"));
                    }
                } catch (RuntimeException ignored) {
                    // not JSON
                }
                throw new IOException("the sign-in was refused (" + (why != null ? why : "HTTP " + status) + ")");
            }
            Map<String, Object> tokens;
            try (InputStream in = c.getInputStream()) {
                tokens = MiniJson.object(MiniJson.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
            }
            String access = MiniJson.string(tokens.get("access_token"));
            if (access == null) throw new IOException("OpenBased sent no access token");

            // who signed in, then WII-UU's own token (a year if allowed, else the server's default)
            String user = null;
            try {
                Map<String, Object> me = MiniJson.object(call("GET", "/api/v1/users/me", null, access));
                user = MiniJson.string(me.get("displayName"));
                if (user == null || user.isBlank()) user = MiniJson.string(me.get("username"));
            } catch (IOException ignored) {
                // the name is only for show
            }
            String name = "WII-UU (" + hostName() + ")";
            String scopes = "[\"" + SCOPES.replace(" ", "\",\"") + "\"]";
            Map<String, Object> made;
            try {
                made = MiniJson.object(call("POST", "/api/v1/tokens", "{\"name\":" + quote(name) + ",\"scopes\":" + scopes
                        + ",\"expiresIn\":" + 365L * 24 * 3600 + "}", access));
            } catch (IOException tooLong) {
                made = MiniJson.object(call("POST", "/api/v1/tokens", "{\"name\":" + quote(name) + ",\"scopes\":" + scopes + "}", access));
            }
            String token = MiniJson.string(made.get("token"));
            if (token == null) throw new IOException("OpenBased made no token");

            // replace the token WII-UU made before (revoked, so it can't be used any more)
            String oldId = config.get("openbased.tokenId", "").trim(), oldToken = token();
            config.set("openbased.token", token);
            config.set("openbased.tokenId", MiniJson.string(made.get("id")));
            config.set("openbased.user", user);
            config.save();
            if (!oldId.isEmpty() && !oldToken.isEmpty()) {
                try {
                    call("DELETE", "/api/v1/tokens/" + enc(oldId), null, access);      // the sign-in may revoke it
                } catch (IOException ignored) {
                    // already expired or revoked
                }
            }
            refresh();
            System.out.println("[openbased] signed in" + (user != null ? " as " + user : ""));
            return new Login(problem, user, p.back());
        } catch (IOException | RuntimeException e) {
            return new Login("Signing in didn't work: " + e.getMessage(), null, p.back());
        }
    }

    /** Revokes the token WII-UU made (if it did) and forgets it. */
    public synchronized void signOut() {
        String id = config.get("openbased.tokenId", "").trim(), token = token();
        if (!id.isEmpty() && !token.isEmpty()) {
            try {
                call("DELETE", "/api/v1/tokens/" + enc(id), null, token);
            } catch (IOException e) {
                System.err.println("[openbased] could not revoke WII-UU's token: " + e.getMessage());
            }
        }
        config.set("openbased.token", null);
        config.set("openbased.tokenId", null);
        config.set("openbased.user", null);
        config.save();
        refresh();
    }

    private static String hostName() {
        try {
            String h = InetAddress.getLocalHost().getHostName();
            return h == null || h.isBlank() ? "this computer" : h;
        } catch (IOException e) {
            return "this computer";
        }
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    // ---- the list -------------------------------------------------------------------------------

    /** Loads the list in the background (the channel shows it when it's there). */
    public void refreshAsync() {
        Thread t = new Thread(this::refresh, "openbased-refresh");
        t.setDaemon(true);
        t.start();
    }

    public synchronized void refresh() {
        if (!configured(config)) {
            problem = lastProblem = null;
            count = 0;
            library.setRemote(Systems.OPENBASED, List.of());
            return;
        }
        try {
            List<Game> games = fetch();
            problem = lastProblem = null;
            count = games.size();
            library.setRemote(Systems.OPENBASED, games);
            System.out.println("[openbased] " + games.size() + " videos from " + base());
        } catch (IOException | RuntimeException e) {
            problem = lastProblem = "OpenBased: " + e.getMessage();
            System.err.println("[openbased] " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private List<Game> fetch() throws IOException, InterruptedException {
        // started but not finished first, like "continue watching" in OpenBased's own apps
        Set<String> first = new LinkedHashSet<>();
        try {
            for (Object o : MiniJson.array(MiniJson.object(get("/api/v1/continue-watching?pageSize=50")).get("items"))) {
                String id = MiniJson.string(MiniJson.object(o).get("mediaId"));
                if (id != null) first.add(id);
            }
        } catch (IOException e) {
            // no history.read scope: just the list
        }
        Map<String, Map<String, Object>> all = new LinkedHashMap<>();
        for (int page = 0; all.size() < MAX_ITEMS; page++) {
            Map<String, Object> answer = MiniJson.object(get("/api/v1/media?pageSize=200&page=" + page));
            List<Object> items = MiniJson.array(answer.get("items"));
            for (Object o : items) {
                Map<String, Object> m = MiniJson.object(o);
                String id = MiniJson.string(m.get("id"));
                if (id != null) all.put(id, m);
            }
            Double total = MiniJson.number(answer.get("total"));
            if (items.isEmpty() || total == null || all.size() >= total) break;
        }
        Path cache = config.home().resolve("cache").resolve("openbased");
        Files.createDirectories(cache.resolve("posters"));
        List<String> order = new ArrayList<>();
        for (String id : first) if (all.containsKey(id)) order.add(id);
        for (String id : all.keySet()) if (!first.contains(id)) order.add(id);

        List<java.util.concurrent.Future<?>> downloads = new ArrayList<>();
        List<Game> games = new ArrayList<>();
        for (String id : order) {
            Map<String, Object> m = all.get(id);
            Double ms = MiniJson.number(m.get("duration"));
            durations.put(id, ms == null ? 0 : ms / 1000);
            List<Path> covers = new ArrayList<>();
            String poster = MiniJson.string(m.get("poster"));
            if (poster != null && poster.startsWith("/api/v1/artwork/")) {
                Path file = cache.resolve("posters").resolve(poster.substring(poster.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9_-]", ""));
                covers.add(file);
                if (!Files.exists(file)) downloads.add(posters.submit(() -> download(poster, file)));
            }
            games.add(new Game(Systems.OPENBASED, title(m), cache.resolve("media").resolve(id), covers));
        }
        for (var d : downloads) {
            try {
                d.get(30, TimeUnit.SECONDS);
            } catch (Exception e) {
                // that poster stays missing; the tile shows the title
            }
        }
        return games;
    }

    /** "Movie (2024)", or "Show – S01E02: Title" */
    static String title(Map<String, Object> m) {
        String title = MiniJson.string(m.get("title"));
        if (title == null) title = "Untitled";
        String series = MiniJson.string(m.get("seriesTitle"));
        Double season = MiniJson.number(m.get("seasonNumber")), episode = MiniJson.number(m.get("episodeNumber"));
        if (series != null && (season != null || episode != null)) {
            String se = String.format(Locale.ROOT, "S%02dE%02d", season == null ? 0 : season.intValue(), episode == null ? 0 : episode.intValue());
            return series + " \u2013 " + se + (title.equalsIgnoreCase(series) ? "" : ": " + title);
        }
        Double year = MiniJson.number(m.get("year"));
        return year == null ? title : title + " (" + year.intValue() + ")";
    }

    private void download(String path, Path file) {
        try {
            HttpURLConnection c = open(path, "GET");
            if (c.getResponseCode() != 200) return;
            Path tmp = Files.createTempFile(file.getParent(), "poster", ".tmp");
            try (InputStream in = c.getInputStream()) {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // no poster
        }
    }

    // ---- HTTP -----------------------------------------------------------------------------------

    private HttpURLConnection open(String path, String method) throws IOException {
        return open(path, method, token());
    }

    private HttpURLConnection open(String path, String method, String bearer) throws IOException {
        HttpURLConnection c = (HttpURLConnection) URI.create(base() + path).toURL().openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
        c.setRequestProperty("Authorization", "Bearer " + bearer);
        c.setRequestProperty("Accept", "application/json");
        return c;
    }

    /** One API call with {@code bearer}: the JSON answer (null for none). */
    private Object call(String method, String path, String json, String bearer) throws IOException {
        HttpURLConnection c = open(path, method, bearer);
        if (json != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = c.getOutputStream()) {
                out.write(json.getBytes(StandardCharsets.UTF_8));
            }
        }
        int code = c.getResponseCode();
        if (code / 100 != 2) throw new IOException(failure(c, code));
        if (code == 204) return null;
        try (InputStream in = c.getInputStream()) {
            String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return body.isBlank() ? null : MiniJson.parse(body);
        }
    }

    private Object get(String path) throws IOException {
        HttpURLConnection c = open(path, "GET");
        int code = c.getResponseCode();
        if (code != 200) throw new IOException(failure(c, code));
        try (InputStream in = c.getInputStream()) {
            return MiniJson.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private void put(String path, String json) throws IOException {
        HttpURLConnection c = open(path, "PUT");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        try (OutputStream out = c.getOutputStream()) {
            out.write(json.getBytes(StandardCharsets.UTF_8));
        }
        int code = c.getResponseCode();
        if (code / 100 != 2) throw new IOException(failure(c, code));
        c.getInputStream().close();
    }

    /** OpenBased's error message ({"error":..., "message":...}), or the HTTP status. */
    private static String failure(HttpURLConnection c, int code) {
        String why = null;
        try (InputStream err = c.getErrorStream()) {
            if (err != null) why = MiniJson.string(MiniJson.object(MiniJson.parse(new String(err.readAllBytes(), StandardCharsets.UTF_8))).get("message"));
        } catch (IOException | RuntimeException ignored) {
            // not JSON
        }
        if (code == 401) return "the token was not accepted (expired or revoked?)";
        if (code == 403) return why != null ? why : "the token lacks a scope (media.read, media.stream, history.read, history.write)";
        return why != null ? why : "HTTP " + code;
    }

    // ---- playing --------------------------------------------------------------------------------

    /** The player command for a video (Launcher runs it like an emulator). */
    public List<String> command(Game game) throws Launcher.LaunchException {
        if (!configured(config)) throw new Launcher.LaunchException("Set up OpenBased first (Settings, or on the phone)");
        String id = game.path().getFileName().toString();
        double start = resumeAt(id);
        String url;
        try {
            url = proxyBase() + "/" + URLEncoder.encode(id, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new Launcher.LaunchException("Could not start the video proxy: " + e.getMessage());
        }
        String startText = String.format(Locale.ROOT, "%.1f", start);
        String custom = config.get("openbased.player", "").trim();
        if (!custom.isEmpty()) {
            List<String> cmd = new ArrayList<>();
            for (String part : split(custom)) {
                cmd.add(part.replace("{url}", url).replace("{title}", game.name()).replace("{start}", startText));
            }
            return cmd;
        }
        String mpv = find("mpv", "/opt/homebrew/bin/mpv", "/usr/local/bin/mpv", "/Applications/mpv.app/Contents/MacOS/mpv",
                "C:\\Program Files\\mpv\\mpv.exe");
        if (mpv != null) {
            String ipc = ipcPath();
            tracker = new Tracker(id, ipc, start);
            return List.of(mpv, "--fs", "--force-window=immediate", "--keep-open=no", "--osd-level=1",
                    "--title=" + game.name(), "--force-media-title=" + game.name(), "--start=" + startText,
                    "--input-ipc-server=" + ipc, url);
        }
        String vlc = find("vlc", "/Applications/VLC.app/Contents/MacOS/VLC", "C:\\Program Files\\VideoLAN\\VLC\\vlc.exe",
                "C:\\Program Files (x86)\\VideoLAN\\VLC\\vlc.exe");
        if (vlc != null) {
            return List.of(vlc, "--fullscreen", "--play-and-exit", "--no-video-title-show", "--meta-title=" + game.name(),
                    "--start-time=" + startText, url);
        }
        String ffplay = find("ffplay");
        if (ffplay != null) {
            return List.of(ffplay, "-fs", "-autoexit", "-loglevel", "warning", "-window_title", game.name(), "-ss", startText, url);
        }
        throw new Launcher.LaunchException("To watch OpenBased videos, install mpv" + (OS.contains("mac") ? " (brew install mpv)"
                : OS.contains("win") ? " (mpv.io)" : " (sudo apt install mpv)") + ", or VLC");
    }

    /** Where to start: the saved position, unless it was (nearly) finished; a few seconds back for context. */
    private double resumeAt(String id) {
        try {
            Map<String, Object> p = MiniJson.object(get("/api/v1/media/" + URLEncoder.encode(id, StandardCharsets.UTF_8) + "/progress"));
            Double pos = MiniJson.number(p.get("position"));
            if (pos == null || Boolean.TRUE.equals(p.get("completed"))) return 0;
            return Math.max(0, pos - 5);
        } catch (IOException | RuntimeException e) {
            return 0;                                              // never watched, or no history scope
        }
    }

    private void saveProgress(String id, double position) {
        double duration = durations.getOrDefault(id, 0.0);
        if (duration > 0) position = Math.min(position, duration);
        String json = duration > 0
                ? String.format(Locale.ROOT, "{\"position\":%.2f,\"duration\":%.2f}", position, duration)
                : String.format(Locale.ROOT, "{\"position\":%.2f}", position);
        try {
            put("/api/v1/media/" + URLEncoder.encode(id, StandardCharsets.UTF_8) + "/progress", json);
        } catch (IOException e) {
            System.err.println("[openbased] could not save the position: " + e.getMessage());
        }
    }

    @Override
    public void started(Game game) {
        Tracker t = tracker;
        if (handles(game) && t != null) t.start();
    }

    @Override
    public void exited(Game game, int exitCode, boolean quickFailure, Path log) {
        Tracker t = tracker;
        if (!handles(game) || t == null) return;
        tracker = null;
        t.finish();
        refreshAsync();                                            // the order follows "continue watching"
    }

    /** Asks mpv where it is every few seconds (its JSON IPC) and saves that to OpenBased. */
    private final class Tracker extends Thread {
        private final String id, ipc;
        private volatile double position;
        private volatile boolean done;

        Tracker(String id, String ipc, double start) {
            super("openbased-progress");
            setDaemon(true);
            this.id = id;
            this.ipc = ipc;
            this.position = start;
        }

        @Override
        public void run() {
            long lastSaved = System.currentTimeMillis();
            double saved = position;
            while (!done) {
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    break;
                }
                Double pos = askMpv(ipc, "time-pos");
                if (pos != null) position = pos;
                if (System.currentTimeMillis() - lastSaved > 15000 && Math.abs(position - saved) > 1) {
                    saveProgress(id, position);
                    saved = position;
                    lastSaved = System.currentTimeMillis();
                }
            }
        }

        void finish() {
            done = true;
            interrupt();
            if (position > 0) saveProgress(id, position);
            if (!OS.contains("win")) {
                try {
                    Files.deleteIfExists(Path.of(ipc));
                } catch (IOException ignored) {
                    // a stale socket file is harmless
                }
            }
        }
    }

    private String ipcPath() {
        if (OS.contains("win")) return "\\\\.\\pipe\\wiiuu-mpv-" + ProcessHandle.current().pid();
        Path dir = config.home().resolve("run");
        try {
            Files.createDirectories(dir);
        } catch (IOException ignored) {
            // mpv says why if it can't create the socket
        }
        return dir.resolve("mpv.sock").toString();
    }

    /** One property from mpv's JSON IPC (Unix socket, or a named pipe on Windows), or null. */
    static Double askMpv(String ipc, String property) {
        String request = "{\"command\":[\"get_property\",\"" + property + "\"]}\n";
        try {
            if (ipc.startsWith("\\\\.\\pipe\\")) {
                try (RandomAccessFile pipe = new RandomAccessFile(ipc, "rw")) {
                    pipe.write(request.getBytes(StandardCharsets.UTF_8));
                    return answer(pipe::readLine);
                }
            }
            try (SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX)) {
                ch.connect(UnixDomainSocketAddress.of(ipc));
                ch.write(ByteBuffer.wrap(request.getBytes(StandardCharsets.UTF_8)));
                var reader = new java.io.BufferedReader(new java.io.InputStreamReader(Channels.newInputStream(ch), StandardCharsets.UTF_8));
                return answer(reader::readLine);
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private interface Lines {
        String next() throws IOException;
    }

    /** mpv sends events on the same connection; the answer is the line with "error" in it. */
    private static Double answer(Lines lines) throws IOException {
        for (int k = 0; k < 20; k++) {
            String line = lines.next();
            if (line == null) return null;
            if (!line.contains("\"error\"")) continue;
            return MiniJson.number(MiniJson.object(MiniJson.parse(line)).get("data"));
        }
        return null;
    }

    // ---- the proxy ------------------------------------------------------------------------------

    /** http://127.0.0.1:port/secret: only this computer can reach it, and only with the secret. */
    private synchronized String proxyBase() throws IOException {
        if (proxy == null) {
            proxy = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 16);
            proxy.createContext("/" + secret + "/", this::forward);
            proxy.setExecutor(Executors.newCachedThreadPool(daemon("openbased-proxy")));
            proxy.start();
        }
        return "http://127.0.0.1:" + proxy.getAddress().getPort() + "/" + secret;
    }

    /** Passes the player's request on to OpenBased with the token, Range included, and the answer back. */
    private void forward(HttpExchange ex) throws IOException {
        try (ex) {
            String method = ex.getRequestMethod();
            String id = ex.getRequestURI().getPath().substring(secret.length() + 2);
            if (!(method.equals("GET") || method.equals("HEAD")) || id.isEmpty() || id.contains("/")) {
                ex.sendResponseHeaders(404, -1);
                return;
            }
            HttpURLConnection c = open("/api/v1/media/" + URLEncoder.encode(id, StandardCharsets.UTF_8) + "/stream", method);
            c.setReadTimeout(0);
            String range = ex.getRequestHeaders().getFirst("Range");
            if (range != null) c.setRequestProperty("Range", range);
            c.setRequestProperty("Accept", "*/*");
            int code = c.getResponseCode();
            for (String h : new String[]{"Content-Type", "Content-Range", "Accept-Ranges", "Last-Modified", "ETag"}) {
                String v = c.getHeaderField(h);
                if (v != null) ex.getResponseHeaders().set(h, v);
            }
            long length = c.getContentLengthLong();
            if (method.equals("HEAD")) {
                if (length >= 0) ex.getResponseHeaders().set("Content-Length", Long.toString(length));
                ex.sendResponseHeaders(code, -1);
                return;
            }
            ex.sendResponseHeaders(code, length > 0 ? length : length == 0 ? -1 : 0);
            try (InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream(); OutputStream out = ex.getResponseBody()) {
                if (in != null) in.transferTo(out);
            } catch (IOException playerStopped) {
                // the player seeks by closing one request and opening another
            } finally {
                c.disconnect();
            }
        }
    }

    // ---- helpers --------------------------------------------------------------------------------

    /** A program on the PATH, or the first of {@code paths} that exists. */
    static String find(String name, String... paths) {
        String path = System.getenv("PATH");
        if (path != null) {
            for (String d : path.split(java.io.File.pathSeparator)) {
                for (String n : OS.contains("win") ? new String[]{name + ".exe", name} : new String[]{name}) {
                    Path p = Path.of(d, n);
                    if (Files.isExecutable(p) && !Files.isDirectory(p)) return p.toString();
                }
            }
        }
        for (String p : paths) if (Files.isExecutable(Path.of(p))) return p;
        return null;
    }

    /** Splits a command line at spaces, keeping "quoted parts" together. */
    static List<String> split(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false, any = false;
        for (char c : line.toCharArray()) {
            if (c == '"') {
                quoted = !quoted;
                any = true;
            } else if (Character.isWhitespace(c) && !quoted) {
                if (any) out.add(cur.toString());
                cur.setLength(0);
                any = false;
            } else {
                cur.append(c);
                any = true;
            }
        }
        if (any) out.add(cur.toString());
        return out;
    }

    private static java.util.concurrent.ThreadFactory daemon(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }
}
