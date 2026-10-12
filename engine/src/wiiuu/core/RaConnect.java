package wiiuu.core;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * RetroAchievements for games Choccy Milk plays: signing in (once, with the password; only the
 * token it gives is kept), finding the game by its ROM hash, its achievements and which ones the
 * player has, and unlocking them. The same requests RetroArch makes (rcheevos' dorequest.php API).
 *
 * <p>Unlocks are softcore: Choccy Milk allows save states, cheats... nothing that would make
 * hardcore fair to check.
 */
public final class RaConnect {
    /** One achievement: its id, what it's called, and its logic (MemAddr). */
    public record Achievement(int id, String title, String description, int points, String memAddr, String badge) {}

    /** A game's achievements for this player: which ones they have already, and its hash. */
    public record GameSet(int id, String title, int console, List<Achievement> achievements, Set<Integer> unlocked,
                          String hash) {
        public int points() {
            return achievements.stream().mapToInt(Achievement::points).sum();
        }

        public List<Achievement> locked() {
            return achievements.stream().filter(a -> !unlocked.contains(a.id())).toList();
        }
    }

    private static final String URL = System.getProperty("wiiuu.raConnect", "https://retroachievements.org/dorequest.php");

    private final Config config;
    private final String userAgent;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    /** @param product "WII-UU/1.9.81" or "ChoccyMilk/1.9.81": who's asking (RetroAchievements wants to know) */
    public RaConnect(Config config, String product) {
        this.config = config;
        String os = System.getProperty("os.name", "");
        this.userAgent = product + " (" + os + " " + System.getProperty("os.version", "") + ") ChoccyMilk";
    }

    /** Signed in for unlocking: a user name and a token. */
    public boolean signedIn() {
        return !user().isEmpty() && !token().isEmpty()
                && config.get("ra.tokenUser", "").equalsIgnoreCase(user());     // a token for another account doesn't count
    }

    /** Whether Choccy Milk should unlock achievements (signed in or able to, and not switched off). */
    public boolean enabled() {
        return (signedIn() || !user().isEmpty() && !password().isEmpty()) && config.getBool("ra.choccy", true);
    }

    /** The password RetroArch mode keeps (Settings), if any: Choccy Milk gets its token with it. */
    private String password() {
        return config.get("ra.password", "");
    }

    /** Signs in with the saved password if there's no token yet. Null when signed in, else why not. */
    public String ensureSignedIn() {
        if (signedIn()) return null;
        if (user().isEmpty() || password().isEmpty()) return "Enter your RetroAchievements user name and password in Settings";
        return login(user(), password());
    }

    public String user() {
        return config.get("ra.user", "").trim();
    }

    private String token() {
        return config.get("ra.token", "").trim();
    }

    /** Signs in with the password, keeps the token (not the password). Null when done, else why not. */
    public String login(String user, String password) {
        try {
            Map<String, Object> j = post("r", "login2", "u", user.trim(), "p", password);
            if (!ok(j)) return error(j, "RetroAchievements didn't accept that");
            String token = MiniJson.string(j.get("Token"));
            if (token == null || token.isEmpty()) return "RetroAchievements gave no token";
            String name = MiniJson.string(j.get("User"));
            config.set("ra.user", name == null || name.isEmpty() ? user.trim() : name);
            config.set("ra.token", token);
            config.set("ra.tokenUser", config.get("ra.user", ""));
            config.save();
            return null;
        } catch (IOException e) {
            return "Couldn't reach RetroAchievements: " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Stopped";
        }
    }

    /** Forgets the token. */
    public void logout() {
        config.set("ra.token", null);
        config.save();
    }

    /**
     * A game's achievements for this player, or null when RetroAchievements doesn't know the ROM
     * (or it has none). Also starts a play session (that's what shows "playing" on the website).
     */
    public GameSet load(Game game) throws IOException, InterruptedException {
        String hash = RaHash.hash(game);
        if (hash == null) return null;
        String why = ensureSignedIn();
        if (why != null) throw new IOException(why);
        Map<String, Object> id = post("r", "gameid", "m", hash);
        int gameId = num(id.get("GameID"));
        if (!ok(id) || gameId <= 0) return null;
        Map<String, Object> patch = post("r", "patch", "u", user(), "t", token(), "g", Integer.toString(gameId));
        if (!ok(patch)) throw new IOException(error(patch, "no achievement list"));
        Map<String, Object> data = MiniJson.object(patch.get("PatchData"));
        if (data == null) return null;
        List<Achievement> list = new ArrayList<>();
        List<Object> all = MiniJson.array(data.get("Achievements"));
        if (all != null) {
            for (Object o : all) {
                Map<String, Object> a = MiniJson.object(o);
                if (a == null || num(a.get("Flags")) != 3) continue;          // 3: official (5: unofficial)
                String logic = MiniJson.string(a.get("MemAddr"));
                if (logic == null || logic.isEmpty()) continue;
                list.add(new Achievement(num(a.get("ID")), str(a.get("Title")), str(a.get("Description")),
                        num(a.get("Points")), logic, str(a.get("BadgeName"))));
            }
        }
        Set<Integer> unlocked = new HashSet<>();
        Map<String, Object> session = post("r", "startsession", "u", user(), "t", token(), "g", Integer.toString(gameId),
                "h", "0", "m", hash, "l", "11.0");
        if (ok(session)) {
            for (String key : new String[]{"Unlocks", "HardcoreUnlocks"}) {         // hardcore ones count for softcore too
                List<Object> u = MiniJson.array(session.get(key));
                if (u != null) for (Object o : u) {
                    Map<String, Object> m = MiniJson.object(o);
                    if (m != null) unlocked.add(num(m.get("ID")));
                }
            }
        } else {
            Map<String, Object> old = post("r", "unlocks", "u", user(), "t", token(), "g", Integer.toString(gameId), "h", "0");
            List<Object> u = MiniJson.array(old.get("UserUnlocks"));
            if (u != null) for (Object o : u) unlocked.add(num(o));
        }
        return new GameSet(gameId, str(data.get("Title")), num(data.get("ConsoleID")), List.copyOf(list), unlocked, hash);
    }

    /** Unlocks an achievement (softcore). Null when done (or the player had it already), else why not. */
    public String award(int achievement, String hash) {
        try {
            String v = RaHash.md5((achievement + user() + "0").getBytes(StandardCharsets.UTF_8));
            if (ensureSignedIn() != null) return "not signed in";
            Map<String, Object> j = post("r", "awardachievement", "u", user(), "t", token(), "a", Integer.toString(achievement),
                    "h", "0", "m", hash, "v", v);
            if (ok(j)) return null;
            String why = error(j, "not accepted");
            return why.toLowerCase(Locale.ROOT).contains("already") ? null : why;
        } catch (Exception e) {
            return "Couldn't reach RetroAchievements: " + e.getMessage();
        }
    }

    /** The badge picture of an achievement. */
    public static String badgeUrl(Achievement a) {
        return "https://media.retroachievements.org/Badge/" + a.badge() + ".png";
    }

    // ---- plumbing ----------------------------------------------------------------------------------

    private Map<String, Object> post(String... kv) throws IOException, InterruptedException {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (body.length() > 0) body.append('&');
            body.append(kv[i]).append('=').append(URLEncoder.encode(kv[i + 1], StandardCharsets.UTF_8));
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(URL)).timeout(Duration.ofSeconds(20))
                .header("User-Agent", userAgent).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        Map<String, Object> j;
        try {
            j = MiniJson.object(MiniJson.parse(r.body()));
        } catch (RuntimeException e) {
            j = null;
        }
        if (j == null) {
            throw new IOException(r.statusCode() == 200 ? "an answer that isn't JSON" : "error " + r.statusCode());
        }
        return j;
    }

    private static boolean ok(Map<String, Object> j) {
        return Boolean.TRUE.equals(j.get("Success"));
    }

    private static String error(Map<String, Object> j, String fallback) {
        String e = MiniJson.string(j.get("Error"));
        return e == null || e.isEmpty() ? fallback : e;
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

    private static String str(Object v) {
        String s = MiniJson.string(v);
        return s == null ? "" : s;
    }

    /** For tests: the parameters as a map. */
    static Map<String, String> form(String body) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String p : body.split("&")) {
            int eq = p.indexOf('=');
            if (eq > 0) m.put(p.substring(0, eq), java.net.URLDecoder.decode(p.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return m;
    }
}
