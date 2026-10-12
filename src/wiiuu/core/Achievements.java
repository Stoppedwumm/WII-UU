package wiiuu.core;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * RetroAchievements (retroachievements.org): your progress on your games, from its Web API.
 *
 * <p>Needs your RetroAchievements user name and Web API key (on the site: Settings > Keys), in
 * {@code ra.user} and {@code ra.apikey}. Each game is recognized the way RetroAchievements does
 * it, by an MD5 of the ROM (NES and SNES headers skipped, N64 byte order fixed, the DS's code and
 * icon); for discs, and when the hash isn't known, by its title. Console game lists are cached for
 * a week in ~/.wiiuu/cache/retroachievements.
 *
 * <p>The achievements themselves unlock in the emulator: in RetroArch mode WII-UU signs RetroArch
 * in with {@code ra.password} (see {@link #retroarchConfig(Config)}).
 */
public final class Achievements {

    /** Your progress on one game. */
    public record Progress(int gameId, String title, int awarded, int total, boolean hardcore) {
        public boolean complete() {
            return total > 0 && awarded >= total;
        }

        /** "12/40", or "100%". */
        public String label() {
            return complete() ? "100%" : awarded + "/" + total;
        }
    }

    /** An achievement you unlocked. */
    public record Unlock(String title, String game, int points, boolean hardcore) {}

    /** RetroAchievements' console numbers, for the consoles WII-UU knows that have achievements. */
    private static final Map<String, int[]> CONSOLES = Map.ofEntries(
            Map.entry("nes", new int[]{7}), Map.entry("snes", new int[]{3}), Map.entry("gb", new int[]{4, 6}),
            Map.entry("n64", new int[]{2}), Map.entry("gba", new int[]{5}), Map.entry("nds", new int[]{18}),
            Map.entry("gc", new int[]{16}), Map.entry("sms", new int[]{11}), Map.entry("genesis", new int[]{1}),
            Map.entry("saturn", new int[]{39}), Map.entry("dc", new int[]{40}), Map.entry("ps1", new int[]{12}),
            Map.entry("ps2", new int[]{21}), Map.entry("psp", new int[]{41}));

    /** Consoles whose ROMs are hashed as files (the rest, discs, are matched by title). */
    private static final java.util.Set<String> HASHED = java.util.Set.of("nes", "snes", "gb", "n64", "gba", "nds", "sms", "genesis");

    private static final String API = System.getProperty("wiiuu.raApi", "https://retroachievements.org/API/");   // (for tests)
    private static final long LIST_DAYS = 7;

    private final Config config;
    private final Path cache;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private final Map<Path, Progress> progress = new ConcurrentHashMap<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private volatile boolean running;
    private volatile String status = "";

    public Achievements(Config config) {
        this.config = config;
        this.cache = config.home().resolve("cache").resolve("retroachievements");
    }

    public boolean configured() {
        return !user().isBlank() && !key().isBlank();
    }

    private String user() {
        return config.get("ra.user", "").trim();
    }

    private String key() {
        return config.get("ra.apikey", "").trim();
    }

    /** Whether WII-UU shows your progress on the game tiles. */
    public boolean enabled() {
        return configured() && config.getBool("ra.enabled", true);
    }

    /** Your progress on this game, if it has achievements (null: none, unknown, or not looked up yet). */
    public Progress progress(Game g) {
        return enabled() ? progress.get(g.path()) : null;
    }

    /** Called (on a background thread) when progress has been looked up again. */
    public void addListener(Runnable r) {
        listeners.add(r);
    }

    /** The last result, for Settings: signed in as..., or what went wrong. */
    public String status() {
        return status;
    }

    public boolean running() {
        return running;
    }

    // ---- looking things up ------------------------------------------------------------------------

    /** Looks up your progress on these games (in the background; listeners hear when it's done). */
    public void refresh(List<Game> games) {
        if (!enabled() || running) return;
        running = true;
        Thread t = new Thread(() -> {
            try {
                Map<Path, Progress> found = lookUp(games);
                progress.clear();
                progress.putAll(found);
                for (Runnable r : listeners) r.run();
            } catch (Exception e) {
                status = "Couldn't reach RetroAchievements: " + message(e);
                System.err.println("[achievements] " + status);
            } finally {
                running = false;
            }
        }, "achievements");
        t.setDaemon(true);
        t.start();
    }

    private Map<Path, Progress> lookUp(List<Game> games) throws IOException, InterruptedException {
        // your progress on every game you've played (the site's own totals)
        Map<Integer, int[]> mine = new HashMap<>();                 // game id -> {awarded, total, hardcore awarded}
        for (int offset = 0; ; offset += 500) {
            Map<String, Object> page = MiniJson.object(MiniJson.parse(get("API_GetUserCompletionProgress.php",
                    "u", user(), "c", "500", "o", String.valueOf(offset))));
            List<Object> results = MiniJson.array(page.get("Results"));
            for (Object o : results) {
                Map<String, Object> r = MiniJson.object(o);
                mine.put(num(r.get("GameID")), new int[]{num(r.get("NumAwarded")), num(r.get("MaxPossible")),
                        num(r.get("NumAwardedHardcore"))});
            }
            int total = num(page.get("Total"));
            if (results.isEmpty() || offset + results.size() >= total) break;
        }
        Map<String, Object> profile = MiniJson.object(MiniJson.parse(get("API_GetUserProfile.php", "u", user())));
        status = "Signed in as " + MiniJson.string(profile.get("User")) + " · " + num(profile.get("TotalPoints")) + " points";
        // which of your games is which
        Properties ids = loadIds();
        Map<Integer, List<Object>> lists = new HashMap<>();
        Map<Path, Progress> out = new HashMap<>();
        for (Game g : games) {
            int[] consoles = CONSOLES.get(g.system().id());
            if (consoles == null || !Files.exists(g.path())) continue;
            String idKey = idKey(g);
            int id;
            if (ids.containsKey(idKey)) id = Integer.parseInt(ids.getProperty(idKey));
            else {
                id = identify(g, consoles, lists);
                ids.setProperty(idKey, String.valueOf(id));
            }
            if (id <= 0) continue;
            int[] p = mine.get(id);
            int total = p != null ? p[1] : totalFor(id, consoles, lists);
            if (total <= 0) continue;
            out.put(g.path(), new Progress(id, titleFor(id, consoles, lists), p == null ? 0 : p[0], total, p != null && p[2] >= total));
        }
        saveIds(ids);
        return out;
    }

    /** Finds a game's RetroAchievements id: by its hash, else by its title. 0 if it has none. */
    private int identify(Game g, int[] consoles, Map<Integer, List<Object>> lists) throws IOException, InterruptedException {
        String md5 = HASHED.contains(g.system().id()) ? hash(g) : null;
        String want = Scraper.key(g.path().getFileName().toString().replaceFirst("\\.[^.]+$", "")
                .replaceAll("\\s*[\\(\\[][^)\\]]*[\\)\\]]", ""));
        int byTitle = 0;
        for (int console : consoles) {
            if (console == 6 && !g.path().toString().toLowerCase(Locale.ROOT).endsWith(".gbc")
                    && !g.path().toString().toLowerCase(Locale.ROOT).endsWith(".zip")) continue;
            for (Object o : list(console, lists)) {
                Map<String, Object> e = MiniJson.object(o);
                if (md5 != null) {
                    for (Object h : MiniJson.array(e.get("Hashes"))) {
                        if (md5.equalsIgnoreCase(MiniJson.string(h))) return num(e.get("ID"));
                    }
                }
                String title = MiniJson.string(e.get("Title"));
                if (byTitle == 0 && title != null && !title.startsWith("~") && !title.contains("[Subset")
                        && Scraper.key(title).equals(want)) byTitle = num(e.get("ID"));
            }
        }
        return byTitle;
    }

    private int totalFor(int id, int[] consoles, Map<Integer, List<Object>> lists) throws IOException, InterruptedException {
        Map<String, Object> e = entry(id, consoles, lists);
        return e == null ? 0 : num(e.get("NumAchievements"));
    }

    private String titleFor(int id, int[] consoles, Map<Integer, List<Object>> lists) throws IOException, InterruptedException {
        Map<String, Object> e = entry(id, consoles, lists);
        return e == null ? "" : MiniJson.string(e.get("Title"));
    }

    private Map<String, Object> entry(int id, int[] consoles, Map<Integer, List<Object>> lists) throws IOException, InterruptedException {
        for (int console : consoles) {
            for (Object o : list(console, lists)) {
                Map<String, Object> e = MiniJson.object(o);
                if (num(e.get("ID")) == id) return e;
            }
        }
        return null;
    }

    /** A console's games that have achievements, with their hashes (cached for a week). */
    private List<Object> list(int console, Map<Integer, List<Object>> lists) throws IOException, InterruptedException {
        List<Object> l = lists.get(console);
        if (l != null) return l;
        Path file = cache.resolve("games-" + console + ".json");
        String json = null;
        if (Files.isRegularFile(file) && System.currentTimeMillis() - Files.getLastModifiedTime(file).toMillis() < LIST_DAYS * 86_400_000L) {
            json = Files.readString(file);
        } else {
            json = get("API_GetGameList.php", "i", String.valueOf(console), "h", "1", "f", "1");
            Files.createDirectories(cache);
            Files.writeString(file, json);
        }
        l = MiniJson.array(MiniJson.parse(json));
        lists.put(console, l);
        return l;
    }

    /** What you unlocked in the last few minutes (after a game), newest first. */
    public List<Unlock> recent(int minutes) {
        List<Unlock> out = new ArrayList<>();
        if (!enabled()) return out;
        try {
            for (Object o : MiniJson.array(MiniJson.parse(get("API_GetUserRecentAchievements.php", "u", user(),
                    "m", String.valueOf(Math.max(1, minutes)))))) {
                Map<String, Object> a = MiniJson.object(o);
                Object hc = a.get("HardcoreMode");
                out.add(new Unlock(MiniJson.string(a.get("Title")), MiniJson.string(a.get("GameTitle")), num(a.get("Points")),
                        hc != null && (hc.equals(Boolean.TRUE) || num(hc) == 1)));
            }
        } catch (Exception e) {
            System.err.println("[achievements] recent: " + message(e));
        }
        return out;
    }

    /** Checks the user name and key (for Settings): "Signed in as ..." or what's wrong. */
    public String check() {
        if (!configured()) return status = "Enter your user name and Web API key";
        try {
            Map<String, Object> profile = MiniJson.object(MiniJson.parse(get("API_GetUserProfile.php", "u", user())));
            String name = MiniJson.string(profile.get("User"));
            if (name == null) return status = "RetroAchievements doesn't know that user";
            return status = "Signed in as " + name + " · " + num(profile.get("TotalPoints")) + " points";
        } catch (Exception e) {
            return status = message(e);
        }
    }

    // ---- RetroArch --------------------------------------------------------------------------------

    /**
     * Lines for the config WII-UU gives RetroArch: achievements on, signed in as you (with your
     * RetroAchievements password: the Web API key can't sign an emulator in), hardcore if wanted.
     * Empty without a password, or with achievements in RetroArch turned off.
     */
    public static String retroarchConfig(Config config) {
        String pass = config.get("ra.password", ""), user = config.get("ra.user", "").trim();
        if (user.isBlank() || pass.isBlank() || !config.getBool("ra.retroarch", true)) return "";
        return "cheevos_enable = \"true\"\n"
                + "cheevos_username = \"" + user.replace("\"", "") + "\"\n"
                + "cheevos_password = \"" + pass.replace("\"", "") + "\"\n"
                + "cheevos_hardcore_mode_enable = \"" + config.getBool("ra.hardcore", false) + "\"\n"
                + "cheevos_badges_enable = \"true\"\n";
    }

    // ---- hashing, as RetroAchievements does it ------------------------------------------------------

    /** The ROM's RetroAchievements hash (lower-case hex), or null if it can't be read. */
    static String hash(Game g) {
        return RaHash.hash(g);
    }

    static byte[] prepare(String system, byte[] rom) {
        return RaHash.prepare(system, rom);
    }

    static String md5(byte[] data) throws Exception {
        return RaHash.md5(data);
    }

    // ---- plumbing ---------------------------------------------------------------------------------

    private static String idKey(Game g) throws IOException {
        return g.path().toAbsolutePath() + "|" + Files.size(g.path()) + "|" + Files.getLastModifiedTime(g.path()).toMillis();
    }

    private Properties loadIds() {
        Properties p = new Properties();
        Path f = cache.resolve("ids.properties");
        if (Files.isRegularFile(f)) {
            try (var in = Files.newBufferedReader(f)) {
                p.load(in);
            } catch (IOException ignored) {
                // looked up again
            }
        }
        return p;
    }

    private void saveIds(Properties p) {
        try {
            Files.createDirectories(cache);
            try (var out = Files.newBufferedWriter(cache.resolve("ids.properties"))) {
                p.store(out, "WII-UU: which RetroAchievements game each ROM is (0: none)");
            }
        } catch (IOException ignored) {
            // looked up again next time
        }
    }

    private String get(String endpoint, String... params) throws IOException, InterruptedException {
        StringBuilder url = new StringBuilder(API).append(endpoint).append("?y=").append(enc(key()));
        for (int i = 0; i + 1 < params.length; i += 2) url.append('&').append(params[i]).append('=').append(enc(params[i + 1]));
        HttpRequest req = HttpRequest.newBuilder(URI.create(url.toString())).timeout(Duration.ofSeconds(40))
                .header("User-Agent", "WII-UU (achievement progress)").GET().build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() == 401) throw new IOException("RetroAchievements didn't accept the Web API key");
        if (res.statusCode() == 429) throw new IOException("RetroAchievements says: too many requests, try again later");
        if (res.statusCode() != 200) throw new IOException("RetroAchievements answered " + res.statusCode());
        return res.body();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static int num(Object v) {
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    private static String message(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
