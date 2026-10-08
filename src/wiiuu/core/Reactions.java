package wiiuu.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Reaction mode: videos with WII-UU's own reactions, made in the reactions editor
 * (WII-UU-Reactions.jar, {@code wiiuu.react.Editor}) and saved as a pack: a .zip with the videos,
 * their captions (.vtt), thumbnails and reactions.json.
 *
 * <p>Packs go in ~/.wiiuu/reactions (a .zip, or the same unpacked in a folder). Their videos are a
 * channel on the home screen ({@link Systems#REACTIONS}) and play full screen in the video player
 * (mpv best) with the reactions as captions; meanwhile the GamePad shows the WII-UU logo saying
 * each one when it comes up, following the player's position (mpv's IPC; otherwise the clock).
 */
public final class Reactions implements Launcher.Listener {

    /** One reaction: at a time in the video, for a while, with a mood (happy, sad, smug, nervous, focus, sneaky, shocked). */
    public record Reaction(double at, double duration, String text, String mood) {}

    /** A video of a pack, unpacked. */
    public record Video(String title, Path file, Path captions, Path thumbnail, List<Reaction> reactions) {}

    /** What WII-UU is saying while a video plays, for the GamePad. */
    public record Now(String title, String text, String mood, int id, double position) {}

    private static final String OS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    private final Config config;
    private final Library library;
    private final Map<Path, Video> videos = new LinkedHashMap<>();
    private volatile Watcher watcher;

    public Reactions(Config config, Library library) {
        this.config = config;
        this.library = library;
    }

    public static boolean handles(Game g) {
        return g != null && g.system() == Systems.REACTIONS;
    }

    public Path folder() {
        return config.home().resolve("reactions");
    }

    /** Looks for packs (unpacking new .zip ones) and lists their videos as the Reactions channel. */
    public synchronized void refresh() {
        videos.clear();
        List<Game> games = new ArrayList<>();
        List<Path> packs = new ArrayList<>();
        Path dir = folder();
        if (Files.isDirectory(dir)) {
            try (Stream<Path> list = Files.list(dir)) {
                for (Path p : list.sorted().toList()) if (!p.getFileName().toString().startsWith(".")) packs.add(p);
            } catch (IOException e) {
                System.err.println("[reactions] " + e.getMessage());
            }
        }
        packs.addAll(builtIn(packs));
        for (Path p : packs) {
            String n = p.getFileName().toString();
            try {
                Path packDir = n.toLowerCase(Locale.ROOT).endsWith(".zip") ? unpack(p)
                        : Files.isRegularFile(p.resolve("reactions.json")) ? p : null;
                if (packDir == null) continue;
                for (Video v : read(packDir)) {
                    videos.put(v.file(), v);
                    games.add(new Game(Systems.REACTIONS, v.title(), v.file(),
                            v.thumbnail() != null ? List.of(v.thumbnail()) : List.of()));
                }
            } catch (IOException | RuntimeException e) {
                System.err.println("[reactions] skipping " + n + ": " + e.getMessage());
            }
        }
        library.setRemote(Systems.REACTIONS, games);
    }

    /**
     * The packs that come with WII-UU (resources/reactions), as files in .builtin (copied once):
     * an example to start with. Left out with reactions.examples=false, and when the same pack
     * (same size) is in the folder already.
     */
    private List<Path> builtIn(List<Path> own) {
        if (!config.getBool("reactions.examples", true)) return List.of();
        List<Path> out = new ArrayList<>();
        String index;
        try (InputStream in = Reactions.class.getResourceAsStream("/reactions/index.txt")) {
            if (in == null) return out;
            index = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return out;
        }
        java.util.Set<Long> sizes = new java.util.HashSet<>();
        for (Path p : own) {
            try {
                if (Files.isRegularFile(p)) sizes.add(Files.size(p));
            } catch (IOException ignored) {
                // not comparable
            }
        }
        Path dir = folder().resolve(".builtin");
        for (String line : index.split("\n")) {
            String name = line.trim();
            if (name.isEmpty() || name.startsWith("#")) continue;
            try (InputStream in = Reactions.class.getResourceAsStream("/reactions/" + name)) {
                if (in == null) continue;
                byte[] data = in.readAllBytes();
                if (sizes.contains((long) data.length)) continue;                 // they have it already
                Path f = dir.resolve(name);
                if (!Files.isRegularFile(f) || Files.size(f) != data.length) {
                    Files.createDirectories(dir);
                    Files.write(f, data);
                }
                out.add(f);
            } catch (IOException e) {
                System.err.println("[reactions] example " + name + ": " + e.getMessage());
            }
        }
        return out;
    }

    public void refreshAsync() {
        Thread t = new Thread(this::refresh, "reactions-scan");
        t.setDaemon(true);
        t.start();
    }

    public synchronized int count() {
        return videos.size();
    }

    /** A .zip's contents, unpacked once into .unpacked (again when the .zip changes). */
    private Path unpack(Path zip) throws IOException {
        String base = zip.getFileName().toString().replaceAll("(?i)\\.zip$", "");
        String key = base + "-" + Files.size(zip) + "-" + Files.getLastModifiedTime(zip).toMillis();
        Path cache = folder().resolve(".unpacked");
        Path target = cache.resolve(key);
        if (Files.isRegularFile(target.resolve("reactions.json"))) return target;
        Files.createDirectories(cache);
        // older unpacks of the same pack go
        try (Stream<Path> old = Files.list(cache)) {
            for (Path o : old.filter(o -> o.getFileName().toString().startsWith(base + "-")).toList()) deleteTree(o);
        }
        Path tmp = cache.resolve(key + ".part");
        deleteTree(tmp);
        Files.createDirectories(tmp);
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                Path out = tmp.resolve(e.getName()).normalize();
                if (!out.startsWith(tmp)) throw new IOException("bad entry " + e.getName());   // no ../ tricks
                if (e.isDirectory()) {
                    Files.createDirectories(out);
                    continue;
                }
                Files.createDirectories(out.getParent());
                Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        if (!Files.isRegularFile(tmp.resolve("reactions.json"))) {
            deleteTree(tmp);
            throw new IOException("not a reactions pack (no reactions.json)");
        }
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
        return target;
    }

    private static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (Stream<Path> all = Files.walk(p)) {
            for (Path q : all.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(q);
        }
    }

    /** The videos of an unpacked pack (reactions.json, as the editor writes it). */
    static List<Video> read(Path dir) throws IOException {
        Map<String, Object> root = MiniJson.object(MiniJson.parse(Files.readString(dir.resolve("reactions.json"), StandardCharsets.UTF_8)));
        if (!"wiiuu-reactions".equals(root.get("format"))) throw new IOException("not a WII-UU reactions pack");
        List<Video> out = new ArrayList<>();
        for (Object o : MiniJson.array(root.get("videos"))) {
            Map<String, Object> v = MiniJson.object(o);
            String file = MiniJson.string(v.get("file"));
            if (file == null) continue;
            Path video = dir.resolve(file).normalize();
            if (!video.startsWith(dir) || !Files.isRegularFile(video)) continue;
            String captions = MiniJson.string(v.get("captions")), thumb = MiniJson.string(v.get("thumbnail"));
            List<Reaction> rs = new ArrayList<>();
            for (Object ro : MiniJson.array(v.get("reactions"))) {
                Map<String, Object> r = MiniJson.object(ro);
                Double at = MiniJson.number(r.get("at")), dur = MiniJson.number(r.get("duration"));
                String text = MiniJson.string(r.get("text")), mood = MiniJson.string(r.get("mood"));
                if (at == null || text == null) continue;
                rs.add(new Reaction(at, dur == null ? 4 : dur, text, mood == null ? "focus" : mood));
            }
            rs.sort(Comparator.comparingDouble(Reaction::at));
            String title = MiniJson.string(v.get("title"));
            out.add(new Video(title == null || title.isBlank() ? video.getFileName().toString() : title, video,
                    captions == null ? null : existing(dir.resolve(captions).normalize(), dir),
                    thumb == null ? null : existing(dir.resolve(thumb).normalize(), dir), List.copyOf(rs)));
        }
        return out;
    }

    private static Path existing(Path p, Path dir) {
        return p.startsWith(dir) && Files.isRegularFile(p) ? p : null;
    }

    // ---- playing --------------------------------------------------------------------------------

    /** The player for a reaction video, with WII-UU's reactions as captions. */
    public List<String> command(Game game) throws Launcher.LaunchException {
        Video v;
        synchronized (this) {
            v = videos.get(game.path());
        }
        if (v == null) throw new Launcher.LaunchException("That video isn't there any more: look for packs again (−)");
        String file = v.file().toString();
        String subs = v.captions() == null ? null : v.captions().toString();
        String custom = config.get("openbased.player", "").trim();
        if (!custom.isEmpty()) {
            List<String> cmd = new ArrayList<>();
            for (String part : OpenBased.split(custom)) cmd.add(part.replace("{url}", file).replace("{title}", v.title()).replace("{start}", "0"));
            watcher = new Watcher(v, null);
            return cmd;
        }
        String mpv = OpenBased.find("mpv", "/opt/homebrew/bin/mpv", "/usr/local/bin/mpv", "/Applications/mpv.app/Contents/MacOS/mpv",
                "C:\\Program Files\\mpv\\mpv.exe");
        if (mpv != null) {
            String ipc = ipcPath();
            watcher = new Watcher(v, ipc);
            List<String> cmd = new ArrayList<>(List.of(mpv, "--fs", "--force-window=immediate", "--keep-open=no", "--osd-level=1",
                    "--title=" + v.title(), "--force-media-title=" + v.title(), "--input-ipc-server=" + ipc,
                    "--sub-font-size=46", "--sub-border-size=3", "--sub-color=#FFFFFF", "--sub-border-color=#00A8E8"));
            if (subs != null) cmd.add("--sub-file=" + subs);
            cmd.add(file);
            return cmd;
        }
        String vlc = OpenBased.find("vlc", "/Applications/VLC.app/Contents/MacOS/VLC", "C:\\Program Files\\VideoLAN\\VLC\\vlc.exe",
                "C:\\Program Files (x86)\\VideoLAN\\VLC\\vlc.exe");
        if (vlc != null) {
            watcher = new Watcher(v, null);
            List<String> cmd = new ArrayList<>(List.of(vlc, "--fullscreen", "--play-and-exit", "--no-video-title-show", "--meta-title=" + v.title()));
            if (subs != null) cmd.add("--sub-file=" + subs);
            cmd.add(file);
            return cmd;
        }
        String ffplay = OpenBased.find("ffplay");
        if (ffplay != null) {
            watcher = new Watcher(v, null);
            return List.of(ffplay, "-fs", "-autoexit", "-loglevel", "warning", "-window_title", v.title(), file);
        }
        throw new Launcher.LaunchException("To watch reaction videos, install mpv" + (OS.contains("mac") ? " (brew install mpv)"
                : OS.contains("win") ? " (mpv.io)" : " (sudo apt install mpv)") + ", or VLC");
    }

    private String ipcPath() {
        if (OS.contains("win")) return "\\\\.\\pipe\\wiiuu-react-" + ProcessHandle.current().pid();
        Path dir = config.home().resolve("run");
        try {
            Files.createDirectories(dir);
        } catch (IOException ignored) {
            // mpv says why if it can't make the socket
        }
        return dir.resolve("react.sock").toString();
    }

    /** What WII-UU says right now (null while no reaction video plays). */
    public Now now() {
        Watcher w = watcher;
        return w == null || !w.running ? null : w.now;
    }

    @Override
    public void started(Game game) {
        Watcher w = watcher;
        if (handles(game) && w != null) {
            w.running = true;
            w.start();
        }
    }

    @Override
    public void exited(Game game, int exitCode, boolean quickFailure, Path log) {
        Watcher w = watcher;
        if (!handles(game) || w == null) return;
        watcher = null;
        w.running = false;
        w.interrupt();
    }

    /**
     * Follows the video: asks mpv where it is (four times a second), or without mpv counts the time
     * since it started, and keeps {@link #now()} on the reaction that is on.
     */
    private static final class Watcher extends Thread {
        final Video video;
        final String ipc;
        volatile boolean running;
        volatile Now now;
        final long started = System.currentTimeMillis();

        Watcher(Video video, String ipc) {
            super("reactions-watch");
            setDaemon(true);
            this.video = video;
            this.ipc = ipc;
            this.now = new Now(video.title(), "", "focus", 0, 0);
        }

        @Override
        public void run() {
            int shown = -1, id = 0;
            double pos = 0;
            long lastAsk = 0;
            while (running) {
                try {
                    Thread.sleep(250);
                } catch (InterruptedException e) {
                    return;
                }
                Double p = ipc == null ? null : OpenBased.askMpv(ipc, "time-pos");
                if (p != null) {
                    pos = p;
                    lastAsk = System.currentTimeMillis();
                } else if (ipc == null) {
                    pos = (System.currentTimeMillis() - started) / 1000.0 - 1.0;   // no mpv: the clock (the player takes a moment)
                } else if (lastAsk == 0) {
                    pos = -1;                                      // mpv is still starting (an AV1 video can take a while)
                }
                // the latest reaction that has started and isn't over
                int on = -1;
                List<Reaction> rs = video.reactions();
                for (int i = 0; i < rs.size(); i++) {
                    Reaction r = rs.get(i);
                    if (r.at() <= pos && pos < r.at() + r.duration()) on = i;
                }
                if (on != shown) {
                    shown = on;
                    id++;
                }
                Reaction r = on >= 0 ? rs.get(on) : null;
                now = new Now(video.title(), r == null ? "" : r.text(), r == null ? "focus" : r.mood(), id, pos);
            }
        }
    }
}
