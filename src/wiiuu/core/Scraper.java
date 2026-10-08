package wiiuu.core;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

/**
 * Finds box art for games that have none, online, and saves it where WII-UU looks for covers
 * ({@code <console folder>/covers/<game>.png}); it never replaces a cover that is there.
 *
 * <p>Two sources: ScreenScraper.fr, when its developer credentials are set (scraper.ss.devid /
 * devpassword, plus your own scraper.ss.user / password for more requests a day), which recognises
 * a game by its file; and libretro-thumbnails (thumbnails.libretro.com, the box art RetroArch
 * uses, no account), which goes by name: its list of names per console is fetched once (kept for a
 * month) and matched against the file's name, preferring USA / World / Europe releases.
 */
public final class Scraper {

    /** How a run went: games looked at, covers found, and the game just done (null at the end). */
    public record Progress(int done, int total, int found, Game current, Path cover) {}

    private static final String LIBRETRO = "https://thumbnails.libretro.com/";
    private static final Map<String, String[]> LIBRETRO_SYSTEMS = Map.ofEntries(
            Map.entry("nes", new String[]{"Nintendo - Nintendo Entertainment System"}),
            Map.entry("snes", new String[]{"Nintendo - Super Nintendo Entertainment System"}),
            Map.entry("gb", new String[]{"Nintendo - Game Boy", "Nintendo - Game Boy Color"}),
            Map.entry("n64", new String[]{"Nintendo - Nintendo 64"}),
            Map.entry("gba", new String[]{"Nintendo - Game Boy Advance"}),
            Map.entry("gc", new String[]{"Nintendo - GameCube"}),
            Map.entry("nds", new String[]{"Nintendo - Nintendo DS"}),
            Map.entry("wii", new String[]{"Nintendo - Wii"}),
            Map.entry("3ds", new String[]{"Nintendo - Nintendo 3DS"}),
            Map.entry("sms", new String[]{"Sega - Master System - Mark III"}),
            Map.entry("genesis", new String[]{"Sega - Mega Drive - Genesis"}),
            Map.entry("saturn", new String[]{"Sega - Saturn"}),
            Map.entry("dc", new String[]{"Sega - Dreamcast"}),
            Map.entry("ps1", new String[]{"Sony - PlayStation"}),
            Map.entry("ps2", new String[]{"Sony - PlayStation 2"}),
            Map.entry("psp", new String[]{"Sony - PlayStation Portable"}),
            Map.entry("ps3", new String[]{"Sony - PlayStation 3"}));
    /** ScreenScraper's console numbers. */
    private static final Map<String, Integer> SS_SYSTEMS = Map.ofEntries(
            Map.entry("nes", 3), Map.entry("snes", 4), Map.entry("gb", 9), Map.entry("n64", 14), Map.entry("gba", 12),
            Map.entry("gc", 13), Map.entry("nds", 15), Map.entry("wii", 16), Map.entry("3ds", 17), Map.entry("wiiu", 18),
            Map.entry("switch", 225), Map.entry("sms", 2), Map.entry("genesis", 1), Map.entry("saturn", 22),
            Map.entry("dc", 23), Map.entry("ps1", 57), Map.entry("ps2", 58), Map.entry("psp", 61), Map.entry("ps3", 59));

    private final Config config;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private final Map<String, List<String>> names = new HashMap<>();
    private volatile boolean running;

    public Scraper(Config config) {
        this.config = config;
    }

    /** Whether WII-UU looks for missing covers by itself (after a scan, and in its idle time). */
    public boolean automatic() {
        return config.getBool("scraper.auto", true);
    }

    public boolean running() {
        return running;
    }

    /** Games that could get a cover: on a console this knows, with none yet. */
    public static boolean wantsCover(Game g) {
        if (g.system() == Systems.OPENBASED || g.system() == Systems.REACTIONS) return false;
        if (!LIBRETRO_SYSTEMS.containsKey(g.system().id()) && !SS_SYSTEMS.containsKey(g.system().id())) return false;
        for (Path c : g.covers()) if (Files.isRegularFile(c)) return false;
        return true;
    }

    /** Looks for covers for these games, one after the other; tells {@code progress} after each. */
    public Progress run(List<Game> games, Consumer<Progress> progress) {
        if (running) return new Progress(0, 0, 0, null, null);
        running = true;
        int done = 0, found = 0;
        try {
            List<Game> todo = games.stream().filter(Scraper::wantsCover).toList();
            for (Game g : todo) {
                Path cover = null;
                try {
                    cover = find(g);
                } catch (IOException | InterruptedException e) {
                    System.err.println("[scraper] " + g.name() + ": " + e.getMessage());
                    if (e instanceof InterruptedException) break;
                }
                done++;
                if (cover != null) found++;
                progress.accept(new Progress(done, todo.size(), found, g, cover));
            }
            Progress end = new Progress(done, todo.size(), found, null, null);
            progress.accept(end);
            return end;
        } finally {
            running = false;
        }
    }

    /** One game's cover: found, saved, and where; null if there's none to be had. */
    public Path find(Game g) throws IOException, InterruptedException {
        if (!wantsCover(g)) return null;
        byte[] image = null;
        if (screenScraperSet()) image = fromScreenScraper(g);
        if (image == null) image = fromLibretro(g);
        if (image == null) return null;
        String ext = image.length > 3 && (image[0] & 0xFF) == 0xFF && (image[1] & 0xFF) == 0xD8 ? "jpg" : "png";
        Path target = null;
        for (Path c : g.covers()) {
            if (c.getParent() != null && c.getParent().getFileName().toString().equalsIgnoreCase("covers")
                    && c.getFileName().toString().endsWith("." + ext)) {
                target = c;
                break;
            }
        }
        if (target == null) return null;
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".part");
        Files.write(tmp, image);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        System.out.println("[scraper] " + g.system().shortName() + " " + g.name() + " -> " + target.getFileName());
        return target;
    }

    // ---- libretro-thumbnails ------------------------------------------------------------------------

    private byte[] fromLibretro(Game g) throws IOException, InterruptedException {
        String[] folders = LIBRETRO_SYSTEMS.get(g.system().id());
        if (folders == null) return null;
        String file = g.path().getFileName().toString().toLowerCase(Locale.ROOT);
        if (g.system().id().equals("gb") && file.endsWith(".gbc")) folders = new String[]{folders[1], folders[0]};
        String base = Library.stripExtension(g.path().getFileName().toString());
        for (String folder : folders) {
            String match = match(base, g.name(), libretroNames(folder));
            if (match == null) continue;
            String url = LIBRETRO + enc(folder) + "/Named_Boxarts/" + enc(match) + ".png";
            byte[] img = get(url);
            if (img != null && img.length > 100) return img;
        }
        return null;
    }

    /** The box-art names libretro has for a console (cached in ~/.wiiuu/cache/scraper for a month). */
    private synchronized List<String> libretroNames(String folder) throws IOException, InterruptedException {
        List<String> known = names.get(folder);
        if (known != null) return known;
        Path cache = config.home().resolve("cache").resolve("scraper").resolve(folder.replaceAll("[^A-Za-z0-9]+", "_") + ".txt");
        if (Files.isRegularFile(cache) && System.currentTimeMillis() - Files.getLastModifiedTime(cache).toMillis() < 30L * 24 * 3600 * 1000) {
            known = Files.readAllLines(cache, StandardCharsets.UTF_8);
        } else {
            byte[] page = get(LIBRETRO + enc(folder) + "/Named_Boxarts/");
            if (page == null) return List.of();
            known = new ArrayList<>();
            Matcher m = Pattern.compile("href=\"([^\"?/]+)\\.png\"").matcher(new String(page, StandardCharsets.UTF_8));
            while (m.find()) known.add(URLDecoder.decode(m.group(1).replace("+", "%2B"), StandardCharsets.UTF_8));
            Files.createDirectories(cache.getParent());
            Files.write(cache, known, StandardCharsets.UTF_8);
        }
        names.put(folder, known);
        return known;
    }

    /**
     * The libretro name for a file: the same name (libretro writes &*\/:`<>?\| as _), else the same
     * title once tags, punctuation and a trailing ", The" are set aside, preferring USA / World /
     * Europe and leaving out betas and demos.
     */
    static String match(String fileBase, String title, List<String> known) {
        String exact = fileBase.replaceAll("[&*/:`<>?\\\\|]", "_");
        for (String k : known) if (k.equalsIgnoreCase(exact)) return k;
        String want = key(title);
        if (want.length() < 2) return null;
        String best = null;
        int bestScore = Integer.MIN_VALUE;
        for (String k : known) {
            String kk = key(Library.cleanTitle(k));
            int score;
            if (kk.equals(want)) score = 100;
            else if (want.length() >= 6 && kk.startsWith(want) && kk.length() - want.length() <= 12) score = 40 - (kk.length() - want.length());
            else continue;
            String tags = k.toLowerCase(Locale.ROOT);
            if (tags.contains("(usa")) score += 9;
            else if (tags.contains("(world")) score += 8;
            else if (tags.contains("(europe")) score += 6;
            if (tags.matches(".*\\((beta|proto|demo|sample|kiosk|pirate|unl)[^)]*\\).*")) score -= 30;
            if (tags.contains("(rev")) score -= 1;
            score -= 3 * Math.max(0, k.length() - k.replace("(", "").length() - 1);    // the plain release before special editions
            if (score > bestScore) {
                bestScore = score;
                best = k;
            }
        }
        return best;
    }

    /** "Legend of Zelda, The" and "The Legend of Zelda" alike: lower case, letters and digits only. */
    static String key(String title) {
        String s = title.toLowerCase(Locale.ROOT).trim();
        Matcher m = Pattern.compile("^(.*), (the|a|an)(\\b.*)$").matcher(s);
        if (m.matches()) s = m.group(2) + " " + m.group(1) + m.group(3);
        s = s.replace("&", "and");
        return s.replaceAll("[^a-z0-9]+", "");
    }

    // ---- ScreenScraper ---------------------------------------------------------------------------------

    private boolean screenScraperSet() {
        return !config.get("scraper.ss.devid", "").isBlank() && !config.get("scraper.ss.devpassword", "").isBlank();
    }

    private byte[] fromScreenScraper(Game g) throws IOException, InterruptedException {
        Integer system = SS_SYSTEMS.get(g.system().id());
        if (system == null || !Files.isRegularFile(g.path())) return null;
        long size = Files.size(g.path());
        StringBuilder q = new StringBuilder("https://api.screenscraper.fr/api2/jeuInfos.php?output=json&softname=WII-UU")
                .append("&devid=").append(enc(config.get("scraper.ss.devid", "")))
                .append("&devpassword=").append(enc(config.get("scraper.ss.devpassword", "")))
                .append("&systemeid=").append(system).append("&romtype=rom")
                .append("&romnom=").append(enc(g.path().getFileName().toString())).append("&romtaille=").append(size);
        if (!config.get("scraper.ss.user", "").isBlank()) {
            q.append("&ssid=").append(enc(config.get("scraper.ss.user", ""))).append("&sspassword=").append(enc(config.get("scraper.ss.password", "")));
        }
        if (size < 64L * 1024 * 1024) q.append("&crc=").append(crc(g.path()));
        byte[] answer = get(q.toString());
        if (answer == null) return null;
        Object root;
        try {
            root = MiniJson.parse(new String(answer, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            return null;                                   // "game not found" and other errors come as text
        }
        Map<String, Object> jeu = MiniJson.object(MiniJson.object(MiniJson.object(root).get("response")).get("jeu"));
        String best = null;
        int bestRank = Integer.MAX_VALUE;
        List<String> regions = List.of("us", "wor", "eu", "ss", "jp");
        for (Object o : MiniJson.array(jeu.get("medias"))) {
            Map<String, Object> media = MiniJson.object(o);
            if (!"box-2D".equals(media.get("type"))) continue;
            int rank = regions.indexOf(String.valueOf(media.get("region")));
            if (rank < 0) rank = regions.size();
            if (rank < bestRank) {
                bestRank = rank;
                best = MiniJson.string(media.get("url"));
            }
        }
        return best == null ? null : get(best + (best.contains("?") ? "&" : "?") + "maxwidth=600");
    }

    private static String crc(Path file) throws IOException {
        CRC32 crc = new CRC32();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) crc.update(buf, 0, n);
        }
        return String.format("%08X", crc.getValue());
    }

    // ---- http ------------------------------------------------------------------------------------------

    private byte[] get(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(40))
                .header("User-Agent", "WII-UU (cover scraper)").GET().build();
        HttpResponse<byte[]> r = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        return r.statusCode() == 200 ? r.body() : null;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
