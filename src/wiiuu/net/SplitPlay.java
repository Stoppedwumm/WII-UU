package wiiuu.net;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import com.sun.net.httpserver.HttpExchange;

import wiiuu.core.Choccy;
import wiiuu.core.Config;
import wiiuu.core.Game;
import wiiuu.core.MiniJson;

/**
 * Sharing the work of a Choccy Milk game between linked WII-UUs.
 *
 * <p>A console can't be emulated by two computers at once: every frame needs the one before it,
 * a few hundred times a second, far faster than any network answers. So the work is split where
 * it can be: the strongest computer runs the console (the core, the game's sound, achievements,
 * save states), and the one you sit at does the rest: it shows the picture (with its own
 * upscaling and widescreen), plays the sound, and reads the controllers and phones. The two talk
 * over a plain TCP connection (port 26781): the controls one way, the frames (only what changed,
 * compressed) and the sound the other.
 *
 * <p>Each computer gets an efficiency score per console, 1 to 10 (1: horrible, 10: it would run
 * GTA 6), from how fast it runs that console (a quick processor test, replaced by real speeds as
 * games are played), and for another computer also the ping and the bandwidth between the two.
 * With "play on the best computer" on, a game starts where the score is clearly better.
 */
public final class SplitPlay {
    public static final int PORT = 26781;

    /** What SplitPlay needs from this WII-UU to play a game for another one. */
    public interface Games {
        /** Whether this WII-UU plays something itself now. */
        boolean busy();

        /** Choccy Milk's core for this console here (an IOException says why there's none). */
        Path core(String system) throws IOException;

        Path systemDir();

        Map<String, String> options(String system);
    }

    private final Config config;
    private final Peers peers;
    private final Games games;
    private final SecureRandom random = new SecureRandom();

    SplitPlay(Config config, Peers peers, Games games) {
        this.config = config;
        this.peers = peers;
        this.games = games;
    }

    // ---- the score ----------------------------------------------------------------------------------

    /** The words for a score (index 1 to 10). */
    private static final String[] WORDS = {"", "Horrible", "Awful", "Bad", "Meh", "Playable", "OK", "Good", "Great", "Superb",
            "Holy shit, this will run GTA 6"};

    /**
     * How much of the processor test's score a console needs for full speed with Choccy Milk's
     * usual core (the test scores 100 on the 2.8 GHz Xeon core these were measured on: the N64 ran
     * at about 120% there with its software renderer, a simple NES game at well over 1000%; the rest
     * are rough guesses in between). Real speeds replace these as soon as a game of that console
     * has been played on that computer.
     */
    private static final Map<String, Double> NEEDS = Map.ofEntries(
            Map.entry("nes", 6.0), Map.entry("gb", 5.0), Map.entry("gbc", 5.0), Map.entry("sms", 5.0), Map.entry("gg", 5.0),
            Map.entry("genesis", 9.0), Map.entry("snes", 16.0), Map.entry("gba", 14.0), Map.entry("pce", 7.0),
            Map.entry("ps1", 30.0), Map.entry("nds", 45.0), Map.entry("n64", 85.0), Map.entry("saturn", 80.0),
            Map.entry("psp", 90.0), Map.entry("dreamcast", 90.0));

    /** Megabits a second the frames and sound of a console take over the network (compressed). */
    private static final Map<String, Double> TRAFFIC = Map.ofEntries(
            Map.entry("nes", 4.0), Map.entry("gb", 3.0), Map.entry("gbc", 3.0), Map.entry("sms", 4.0), Map.entry("gg", 3.0),
            Map.entry("genesis", 6.0), Map.entry("snes", 6.0), Map.entry("gba", 5.0), Map.entry("pce", 5.0),
            Map.entry("ps1", 14.0), Map.entry("nds", 8.0), Map.entry("n64", 18.0), Map.entry("saturn", 16.0));

    /** One computer's score for one console. */
    public record Rating(String name, Peers.Link link, int score, double speed, double ping, double mbit, double traffic,
                         boolean measured, String problem) {
        public String words() {
            return WORDS[Math.max(1, Math.min(10, score))];
        }

        /** "8/10 · Great". */
        public String label() {
            return problem != null ? "can't: " + problem : score + "/10 · " + words();
        }

        /** "runs it at 240% · ping 4 ms · 300 Mbit/s for 6" (what the score comes from). */
        public String details() {
            if (problem != null) return problem;
            StringBuilder b = new StringBuilder();
            b.append(speed >= 1000 ? "far more than full speed" : "runs it at " + Math.round(speed) + "%");
            b.append(measured ? " (played)" : " (estimate)");
            if (link != null) {
                b.append(" · ping ").append(ping < 1 ? "<1" : Long.toString(Math.round(ping))).append(" ms");
                b.append(" · ").append(Math.round(mbit)).append(" Mbit/s for ").append(Math.round(traffic));
            }
            return b.toString();
        }

        public boolean usable() {
            return problem == null;
        }
    }

    /**
     * The score: speed counts most (below full speed it drops fast, headroom up to three times
     * full speed earns the last points), then the ping (every millisecond is input lag) and
     * whether the network has room for the picture.
     */
    static int score(double speed, double ping, double mbit, double traffic) {
        double s = Math.min(1, speed / 100);
        double headroom = Math.max(0, Math.min(1, (speed - 100) / 200));
        double speedPart = s * s * s * (0.8 + 0.2 * headroom);
        double lag = ping <= 3 ? 1 : Math.max(0.15, 1 - (ping - 3) / 50);
        double room = traffic <= 0 || Double.isInfinite(mbit) ? 99 : mbit / traffic;
        double net = room >= 4 ? 1 : room >= 1 ? 0.75 + 0.25 * (room - 1) / 3 : 0.6 * room * room;
        long n = Math.round(1 + 9 * speedPart * lag * net);
        return (int) Math.max(1, Math.min(10, n));
    }

    /** How fast this computer would run a console: real speed when played and slower than full, else the estimate. */
    static double expected(double cpu, Map<String, Integer> played, String system) {
        double need = NEEDS.getOrDefault(system, 40.0);
        double estimate = cpu <= 0 ? 0 : cpu / need * 100;
        Integer m = played.get(system);
        if (m == null) return estimate;
        return m < 95 ? m : Math.max(estimate, 100);           // at full speed it's held to 100: the estimate says how much more
    }

    /** This computer's score for a console. */
    public Rating here(String system) {
        double cpu = cpuScore(config);
        Map<String, Integer> played = playedSpeeds(config);
        String problem = Choccy.available() || Choccy.canGetJava() ? null : "no Choccy Milk here";
        double speed = expected(cpu, played, system);
        return new Rating(peers.myName() + " (this one)", null, score(speed, 0, Double.POSITIVE_INFINITY, 0), speed, 0,
                Double.POSITIVE_INFINITY, 0, played.containsKey(system), problem);
    }

    /** A linked WII-UU's score for a console (asks it, and measures the network, at most once a minute). */
    public Rating there(Peers.Link l, String system) {
        Power p = power(l);
        if (p == null) return new Rating(l.name(), l, 1, 0, 0, 0, 0, false, "not reached");
        if (!p.choccy) return new Rating(l.name(), l, 1, 0, 0, 0, 0, false, "no Choccy Milk there");
        if (p.busy) return new Rating(l.name(), l, 1, 0, 0, 0, 0, false, "it's playing something itself");
        Net n = net(l);
        double traffic = TRAFFIC.getOrDefault(system, 12.0);
        double speed = expected(p.cpu, p.played, system);
        if (n == null) return new Rating(l.name(), l, 1, speed, 0, 0, traffic, false, "the network test failed");
        return new Rating(l.name(), l, score(speed, n.ping + 2, n.mbit, traffic), speed, n.ping, n.mbit, traffic,
                p.played.containsKey(system), null);                  // (+2 ms: packing and unpacking the frames)
    }

    /** All computers' scores for a console, best first (this one first among equals). */
    public List<Rating> all(String system) {
        List<Rating> out = new ArrayList<>();
        out.add(here(system));
        for (Peers.Link l : peers.links()) {
            if (peers.online(l)) out.add(there(l, system));
        }
        out.sort((a, b) -> a.usable() != b.usable() ? (a.usable() ? -1 : 1) : Integer.compare(b.score, a.score));
        return out;
    }

    /** Where a game of this console should play: another computer when it scores clearly better (2 points), else null. */
    public Rating better(String system) {
        Rating here = here(system), best = null;
        for (Peers.Link l : peers.links()) {
            if (!peers.online(l)) continue;
            Rating r = there(l, system);
            if (r.usable() && (best == null || r.score > best.score)) best = r;
        }
        if (best == null || best.score < 6 || best.score < here.score + 2 && here.usable()) return null;
        return best;
    }

    /**
     * Keeps the other computers' answers and the network measurements fresh in the background while
     * "play on the best computer" is on, so starting a game doesn't wait for them.
     */
    void keepWarm() {
        java.util.concurrent.ScheduledExecutorService t = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread th = new Thread(r, "split-warm");
            th.setDaemon(true);
            th.setPriority(Thread.MIN_PRIORITY);
            return th;
        });
        t.scheduleWithFixedDelay(() -> {
            try {
                cpuScore(config);                                   // once per computer
                if (!config.getBool("choccy.split", false) || games.busy() || lending != null) return;
                for (Peers.Link l : peers.links()) {
                    if (!peers.online(l)) continue;
                    powers.remove(l.id);
                    nets.remove(l.id);
                    power(l);
                    net(l);
                }
            } catch (RuntimeException e) {
                System.err.println("[split] " + e);
            }
        }, 5, 45, TimeUnit.SECONDS);
    }

    /** Forgets the network measurements and answers, so the next scores ask again. */
    public void forget() {
        powers.clear();
        nets.clear();
    }

    // ---- this computer's power ---------------------------------------------------------------------

    private static volatile double cpu = -1;

    /**
     * The processor test's score (single core, like an emulator): 100 on a 2.8 GHz Xeon core.
     * Measured once per computer (about a second) and kept in the settings.
     */
    public static double cpuScore(Config config) {
        if (cpu > 0) return cpu;
        synchronized (SplitPlay.class) {
            if (cpu > 0) return cpu;
            String tag = Choccy.arch() + "/" + Runtime.getRuntime().availableProcessors();
            String kept = config.get("choccy.cpuScore", "");
            int at = kept.indexOf('@');
            if (at > 0 && kept.substring(at + 1).equals(tag)) {
                try {
                    cpu = Double.parseDouble(kept.substring(0, at));
                    return cpu;
                } catch (NumberFormatException ignored) {
                    // measure again
                }
            }
            cpu = bench();
            config.set("choccy.cpuScore", String.format(Locale.ROOT, "%.1f@%s", cpu, tag));
            config.save();
            System.out.printf(Locale.ROOT, "[split] processor test: %.0f%n", cpu);
            return cpu;
        }
    }

    /** Runs the processor test again. */
    public static double measureAgain(Config config) {
        synchronized (SplitPlay.class) {
            cpu = -1;
            config.set("choccy.cpuScore", "");
        }
        return cpuScore(config);
    }

    private static volatile int sink;

    /** A small made-up processor, interpreted the way an emulator does it (its speed, scaled to 100 on the reference core). */
    static double bench() {
        int[] mem = new int[1 << 16];
        java.util.Random r = new java.util.Random(64);
        for (int i = 0; i < mem.length; i++) mem[i] = r.nextInt();
        long end = System.nanoTime() + 300_000_000L;              // warming up (the JIT compiles it)
        while (System.nanoTime() < end) sink += run(mem, 200_000);
        double best = 0;
        for (int round = 0; round < 3; round++) {
            long steps = 0, t0 = System.nanoTime();
            while (System.nanoTime() - t0 < 200_000_000L) {
                sink += run(mem, 200_000);
                steps += 200_000;
            }
            double mips = steps / ((System.nanoTime() - t0) / 1e3);
            best = Math.max(best, mips);
        }
        return best * 1.06;                                       // 100 on the 2.8 GHz Xeon core it was tuned on
    }

    private static int run(int[] mem, int steps) {
        int a = 1, x = 0, y = 0, pc = 0;
        for (int n = 0; n < steps; n++) {
            int op = mem[pc];
            int arg = op >>> 3 & 0xff;
            switch (op & 7) {
                case 0 -> a = a + mem[(x + arg) & 0xffff] & 0xff;
                case 1 -> mem[(y << 8 | arg) & 0xffff] = (mem[(y << 8 | arg) & 0xffff] & ~0xff) | a;
                case 2 -> x = x + 1 & 0xff;
                case 3 -> y = (y ^ a) & 0xff;
                case 4 -> {
                    if ((a & 1) == 0) pc = pc + arg & 0xffff;
                }
                case 5 -> a = (a << 1 | a >>> 7) & 0xff;
                case 6 -> a = a - x & 0xff;
                default -> a ^= y;
            }
            pc = pc + 1 & 0xffff;
        }
        return a + x + y;
    }

    /** Remembers how fast a console ran here (Choccy Milk says so every ten seconds). */
    public static void recordSpeed(Config config, String system, int pct) {
        if (pct < 0 || system == null) return;
        String key = "choccy.speed." + system;
        int old = config.getInt(key, -1);
        int now = old < 0 ? pct : (int) Math.round(old * 0.7 + Math.min(pct, 100) * 0.3);
        if (now == old) return;
        config.set(key, Integer.toString(now));
        config.save();
    }

    static Map<String, Integer> playedSpeeds(Config config) {
        Map<String, Integer> out = new HashMap<>();
        for (String s : NEEDS.keySet()) {
            int v = config.getInt("choccy.speed." + s, -1);
            if (v >= 0) out.put(s, v);
        }
        for (wiiuu.core.GameSystem g : Peers.gameSystems()) {
            int v = config.getInt("choccy.speed." + g.id(), -1);
            if (v >= 0) out.put(g.id(), v);
        }
        return out;
    }

    // ---- the other computers' power and the network ----------------------------------------------

    private record Power(double cpu, boolean choccy, boolean busy, Map<String, Integer> played, long at) {}

    private record Net(double ping, double mbit, long at) {}

    private final Map<String, Power> powers = new ConcurrentHashMap<>();
    private final Map<String, Net> nets = new ConcurrentHashMap<>();

    private Power power(Peers.Link l) {
        Power p = powers.get(l.id);
        if (p != null && System.currentTimeMillis() - p.at < 60_000) return p;
        try {
            HttpResponse<String> r = peers.call(l, "/api/peer/power", null, Duration.ofSeconds(8), HttpResponse.BodyHandlers.ofString());
            Map<String, Object> j = MiniJson.object(MiniJson.parse(r.body()));
            if (r.statusCode() != 200 || j == null) return null;
            Map<String, Integer> played = new HashMap<>();
            Map<String, Object> sp = MiniJson.object(j.get("played"));
            if (sp != null) {
                for (Map.Entry<String, Object> e : sp.entrySet()) {
                    Double v = MiniJson.number(e.getValue());
                    if (v != null) played.put(e.getKey(), v.intValue());
                }
            }
            Double c = MiniJson.number(j.get("cpu"));
            p = new Power(c == null ? 0 : c, Boolean.TRUE.equals(j.get("choccy")), Boolean.TRUE.equals(j.get("busy")), played,
                    System.currentTimeMillis());
            powers.put(l.id, p);
            return p;
        } catch (IOException | RuntimeException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** The ping (median of five) and the bandwidth (a 4 MB download) to a linked WII-UU. */
    private Net net(Peers.Link l) {
        Net n = nets.get(l.id);
        if (n != null && System.currentTimeMillis() - n.at < 60_000) return n;
        try {
            double[] pings = new double[5];
            for (int i = 0; i < pings.length; i++) {
                long t0 = System.nanoTime();
                HttpResponse<String> r = peers.call(l, "/api/peer/ping", null, Duration.ofSeconds(4), HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() != 200) return null;
                pings[i] = (System.nanoTime() - t0) / 1e6;
            }
            java.util.Arrays.sort(pings);
            double ping = pings[2];
            int size = 4 << 20;
            long t0 = System.nanoTime();
            HttpResponse<byte[]> r = peers.call(l, "/api/peer/probe?n=" + size, null, Duration.ofSeconds(30), HttpResponse.BodyHandlers.ofByteArray());
            double secs = Math.max(1e-4, (System.nanoTime() - t0) / 1e9 - ping / 1000);
            if (r.statusCode() != 200 || r.body().length != size) return null;
            n = new Net(ping, size * 8 / secs / 1e6, System.currentTimeMillis());
            nets.put(l.id, n);
            return n;
        } catch (IOException | RuntimeException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    // ---- requests from the other WII-UUs ----------------------------------------------------------

    /** /api/peer/power, /api/peer/probe and /api/peer/play (Peers has checked the link already). */
    boolean handle(HttpExchange ex, String path, Peers.Link from, Map<String, String> q) throws IOException {
        switch (path) {
            case "/api/peer/power" -> {
                Json j = new Json().obj().kv("cpu", cpuScore(config)).kv("choccy", Choccy.available())
                        .kv("busy", games.busy() || lending != null).kv("threads", Runtime.getRuntime().availableProcessors())
                        .kv("arch", Choccy.arch()).key("played").obj();
                for (Map.Entry<String, Integer> e : playedSpeeds(config).entrySet()) j.kv(e.getKey(), e.getValue());
                Peers.replyJson(ex, 200, j.endObj().endObj());
            }
            case "/api/peer/probe" -> {
                int n;
                try {
                    n = Math.max(1, Math.min(8 << 20, Integer.parseInt(q.getOrDefault("n", "1048576"))));
                } catch (NumberFormatException e) {
                    n = 1 << 20;
                }
                byte[] b = probe(n);                                // not compressible on the way
                ex.getResponseHeaders().set("Content-Type", "application/octet-stream");
                ex.sendResponseHeaders(200, n);
                try (OutputStream out = ex.getResponseBody()) {
                    out.write(b);
                }
            }
            case "/api/peer/play" -> {
                if (!"POST".equals(ex.getRequestMethod())) {
                    Peers.replyJson(ex, 405, new Json().obj().kv("error", "POST only").endObj());
                    return true;
                }
                Map<String, String> f = Peers.form(Peers.requestBody(ex, 64 * 1024));
                try {
                    Json j = lend(from, f);
                    Peers.replyJson(ex, 200, j);
                } catch (IOException e) {
                    Peers.replyJson(ex, 409, new Json().obj().kv("error", String.valueOf(e.getMessage())).endObj());
                }
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private volatile byte[] probeBytes = new byte[0];

    /** n bytes of noise for the bandwidth test (made once). */
    private byte[] probe(int n) {
        byte[] b = probeBytes;
        if (b.length < n) {
            b = new byte[Math.max(n, 4 << 20)];
            new java.util.Random().nextBytes(b);
            probeBytes = b;
        }
        return n == b.length ? b : java.util.Arrays.copyOf(b, n);
    }

    // ---- playing a game for another WII-UU ----------------------------------------------------------

    /** the game this WII-UU plays for another one, or null */
    private volatile Lending lending;

    /** Whether this WII-UU plays a game for another one now. */
    public boolean lending() {
        return lending != null;
    }

    /** The WII-UU this one plays a game for now, or null. */
    public String lendingTo() {
        Lending l = lending;
        return l == null ? null : l.forName;
    }

    private final class Lending {
        final Process process;
        final String ticket;
        final String forName;
        final String system;
        ServerSocket server;

        Lending(Process process, String ticket, String forName, String system) {
            this.process = process;
            this.ticket = ticket;
            this.forName = forName;
            this.system = system;
        }
    }

    /** Starts the game asked for (copying it from the asker first if this one doesn't have it) and waits for the connection. */
    private synchronized Json lend(Peers.Link from, Map<String, String> f) throws IOException {
        if (games.busy() || lending != null) throw new IOException("it's playing something itself");
        if (!Choccy.available()) throw new IOException("Choccy Milk can't run there");
        String system = f.get("s"), rel = f.get("f");
        long size;
        try {
            size = Long.parseLong(f.getOrDefault("n", "-1"));
        } catch (NumberFormatException e) {
            throw new IOException("bad request");
        }
        pruneBorrowed();
        Path own = peers.gamePath(system, rel);
        if (own == null) throw new IOException("bad game");
        Path rom = own;
        if (!Files.isRegularFile(own) || Files.size(own) != size) {
            rom = borrowed(system, rel);
            try {
                peers.fetchGame(from, system, rel, size, rom);
                for (String[] other : companions(from, system, rel)) {
                    peers.fetchGame(from, system, other[0], Long.parseLong(other[1]), borrowed(system, other[0]));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted");
            }
        }
        if (rom != own) Files.setLastModifiedTime(rom, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
        Path core = games.core(system);
        Map<String, String> options = new LinkedHashMap<>(games.options(system));
        for (String line : f.getOrDefault("o", "").split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0 && !line.startsWith("wiiuu.")) options.put(line.substring(0, eq), line.substring(eq + 1));
        }
        options.put("wiiuu.sound", "pipe");
        Path saves = config.home().resolve("saves").resolve(system);
        Files.createDirectories(saves);
        ProcessBuilder pb = new ProcessBuilder(Choccy.command(core, rom, saves, games.systemDir(), options));
        Path log = config.logDir().resolve(system + "-lent.log");
        Files.createDirectories(log.getParent());
        pb.redirectError(log.toFile());
        String ticket = HexFormat.of().formatHex(token());
        Process p = pb.start();
        Lending l = new Lending(p, ticket, from.name(), system);
        ServerSocket server;
        try {
            server = new ServerSocket();
            server.setReuseAddress(true);
            try {
                server.bind(new InetSocketAddress(PORT));
            } catch (IOException e) {
                server.bind(new InetSocketAddress(0));         // in use: any free one
            }
            server.setSoTimeout(30_000);
        } catch (IOException e) {
            p.destroy();
            throw e;
        }
        l.server = server;
        lending = l;
        Thread t = new Thread(() -> serve(l), "split-lend");
        t.setDaemon(true);
        t.start();
        System.out.println("[split] playing " + rom.getFileName() + " for " + from.name() + " (" + core.getFileName() + ")");
        return new Json().obj().kv("port", server.getLocalPort()).kv("ticket", ticket).kv("core", core.getFileName().toString()).endObj();
    }

    private byte[] token() {
        byte[] b = new byte[24];
        random.nextBytes(b);
        return b;
    }

    /** Games copied in for others that no one played here for 30 days go again. */
    private void pruneBorrowed() {
        Path dir = config.home().resolve("choccy").resolve("lent");
        if (!Files.isDirectory(dir)) return;
        long old = System.currentTimeMillis() - 30L * 24 * 3600 * 1000;
        try (var all = Files.walk(dir)) {
            for (Path f : all.filter(Files::isRegularFile).toList()) {
                if (Files.getLastModifiedTime(f).toMillis() < old) Files.deleteIfExists(f);
            }
        } catch (IOException ignored) {
            // next time
        }
    }

    /** Where a game copied from the asker goes (not into the games folder: it isn't this one's). */
    private Path borrowed(String system, String rel) throws IOException {
        Path dir = config.home().resolve("choccy").resolve("lent").resolve(system).toAbsolutePath().normalize();
        Path f = dir.resolve(rel).normalize();
        if (!f.startsWith(dir) || f.equals(dir)) throw new IOException("bad game");
        return f;
    }

    /** The other files a disc image needs (a .cue's .bin tracks...): the asker's files in the same folder. */
    private List<String[]> companions(Peers.Link from, String system, String rel) throws IOException, InterruptedException {
        List<String[]> out = new ArrayList<>();
        String lower = rel.toLowerCase(Locale.ROOT);
        if (!(lower.endsWith(".cue") || lower.endsWith(".m3u") || lower.endsWith(".gdi") || lower.endsWith(".ccd")
                || lower.endsWith(".toc"))) return out;
        String dir = rel.contains("/") ? rel.substring(0, rel.lastIndexOf('/') + 1) : "";
        HttpResponse<String> r = peers.call(from, "/api/peer/roms", null, Duration.ofSeconds(20), HttpResponse.BodyHandlers.ofString());
        Map<String, Object> j = MiniJson.object(MiniJson.parse(r.body()));
        if (r.statusCode() != 200 || j == null) return out;
        for (Object o : MiniJson.array(j.get("files"))) {
            Map<String, Object> e = MiniJson.object(o);
            String s = MiniJson.string(e.get("s")), f = MiniJson.string(e.get("f"));
            Double n = MiniJson.number(e.get("n"));
            if (!system.equals(s) || f == null || n == null || f.equals(rel)) continue;
            String fd = f.contains("/") ? f.substring(0, f.lastIndexOf('/') + 1) : "";
            if (fd.equals(dir) && !dir.isEmpty()) out.add(new String[]{f, Long.toString(n.longValue())});
        }
        return out;
    }

    /** Waits for the asker, then passes the controls in and the (packed) frames and sound out until either side ends. */
    private void serve(Lending l) {
        Process p = l.process;
        try (ServerSocket server = l.server; Socket s = accept(server, l.ticket)) {
            if (s == null) {
                System.out.println("[split] " + l.forName + " didn't connect");
                return;
            }
            s.setTcpNoDelay(true);
            Thread in = new Thread(() -> controls(s, p), "split-controls");
            in.setDaemon(true);
            in.start();
            pack(new DataInputStream(new BufferedInputStream(p.getInputStream(), 1 << 16)),
                    new DataOutputStream(new BufferedOutputStream(s.getOutputStream(), 1 << 16)), l.system);
        } catch (IOException e) {
            System.out.println("[split] the connection to " + l.forName + " ended: " + e.getMessage());
        } finally {
            quit(p);
            lending = null;
            System.out.println("[split] done playing for " + l.forName);
        }
    }

    private static Socket accept(ServerSocket server, String ticket) throws IOException {
        long until = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < until) {
            Socket s;
            try {
                s = server.accept();
            } catch (java.net.SocketTimeoutException e) {
                return null;
            }
            s.setSoTimeout(5000);
            String said = readLine(s.getInputStream());
            if (said != null && MessageDigest.isEqual(said.getBytes(StandardCharsets.UTF_8), ticket.getBytes(StandardCharsets.UTF_8))) {
                s.setSoTimeout(0);
                return s;
            }
            s.close();                                      // not the one it was started for
        }
        return null;
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder b = new StringBuilder();
        for (int c; (c = in.read()) >= 0 && c != '\n'; ) {
            if (b.length() > 200) return null;
            b.append((char) c);
        }
        return b.toString();
    }

    /** The asker's controls (Choccy Milk's stdin lines), line by line, so each press goes in at once. */
    private static void controls(Socket s, Process p) {
        try (InputStream in = new BufferedInputStream(s.getInputStream()); OutputStream out = p.getOutputStream()) {
            StringBuilder line = new StringBuilder();
            for (int c; (c = in.read()) >= 0; ) {
                if (c != '\n') {
                    if (line.length() < 1 << 16) line.append((char) c);
                    continue;
                }
                out.write((line + "\n").getBytes(StandardCharsets.ISO_8859_1));
                out.flush();
                line.setLength(0);
            }
        } catch (IOException ignored) {
            // either side ended
        }
        try {
            p.getOutputStream().write("q\n".getBytes(StandardCharsets.US_ASCII));
            p.getOutputStream().flush();
        } catch (IOException ignored) {
            // gone already
        }
    }

    /** Asks the player to save and stop; stops it hard if it doesn't. */
    private static void quit(Process p) {
        try {
            p.getOutputStream().write("q\n".getBytes(StandardCharsets.US_ASCII));
            p.getOutputStream().flush();
        } catch (IOException ignored) {
            // gone already
        }
        try {
            if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
        }
    }

    /**
     * Choccy Milk's output, for the network: each frame as 'Z' (only the change from the frame
     * before, 3 bytes a pixel, deflated), the rest as it is. Speeds are remembered here too.
     */
    void pack(DataInputStream in, DataOutputStream out, String system) throws IOException {
        Deflater deflater = new Deflater(1);
        int[] prev = new int[0];
        byte[] raw = new byte[0], delta = new byte[0], packed = new byte[0];
        while (true) {
            int kind = in.read();
            if (kind < 0) break;
            switch (kind) {
                case 'F' -> {
                    int w = in.readInt(), h = in.readInt();
                    float aspect = in.readFloat();
                    if (w <= 0 || h <= 0 || w > 4096 || h > 4096) return;
                    int n = w * h;
                    if (raw.length < n * 4) raw = new byte[n * 4];
                    in.readFully(raw, 0, n * 4);
                    if (prev.length != n) prev = new int[n];       // a new size: the change from black
                    if (delta.length < n * 3) delta = new byte[n * 3];
                    for (int i = 0, j = 0, k = 0; i < n; i++, j += 4, k += 3) {
                        int px = (raw[j + 1] & 0xff) << 16 | (raw[j + 2] & 0xff) << 8 | (raw[j + 3] & 0xff);
                        int d = px ^ prev[i];
                        prev[i] = px;
                        delta[k] = (byte) (d >>> 16);
                        delta[k + 1] = (byte) (d >>> 8);
                        delta[k + 2] = (byte) d;
                    }
                    deflater.reset();
                    deflater.setInput(delta, 0, n * 3);
                    deflater.finish();
                    if (packed.length < n * 3 + 1024) packed = new byte[n * 3 + 1024];
                    int len = 0;
                    while (!deflater.finished()) {
                        if (len == packed.length) packed = java.util.Arrays.copyOf(packed, packed.length * 2);
                        len += deflater.deflate(packed, len, packed.length - len);
                    }
                    out.write('Z');
                    out.writeInt(w);
                    out.writeInt(h);
                    out.writeFloat(aspect);
                    out.writeInt(len);
                    out.write(packed, 0, len);
                    out.flush();
                }
                case 'S' -> {
                    int frames = in.readInt();
                    if (frames < 0 || frames > 1 << 20) return;
                    byte[] pcm = in.readNBytes(frames * 4);
                    out.write('S');
                    out.writeInt(frames);
                    out.write(pcm);
                    out.flush();
                }
                case 'R', 'X', 'N', 'A', 'P' -> {
                    String text = readLine(in);
                    if (text == null) return;
                    if (kind == 'P') {
                        try {
                            recordSpeed(config, system, Integer.parseInt(text.trim()));
                        } catch (NumberFormatException ignored) {
                            // not a number
                        }
                    }
                    out.write(kind);
                    out.write((text + "\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
                default -> {
                    return;                                         // out of step
                }
            }
        }
    }

    // ---- playing a game on another WII-UU -----------------------------------------------------------

    /**
     * Starts a game on a linked WII-UU (syncing its saves there first, and copying it there when it
     * doesn't have it): the process to give Choccy's session, and the core it plays with there.
     * Blocks while that WII-UU gets ready (copying a big game takes a while).
     */
    public Away open(Peers.Link l, Game game, Map<String, String> options) throws IOException {
        String system = game.system().id();
        Path dir = config.romDir(game.system()).toAbsolutePath().normalize();
        Path rom = game.path().toAbsolutePath().normalize();
        if (!rom.startsWith(dir)) throw new IOException("the game isn't in WII-UU's games folder");
        String rel = dir.relativize(rom).toString().replace('\\', '/');
        long size = Files.size(rom);
        peers.syncGame(l, system, rom.getFileName().toString());
        StringBuilder o = new StringBuilder();
        for (Map.Entry<String, String> e : options.entrySet()) o.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        String body = "s=" + Peers.encode(system) + "&f=" + Peers.encode(rel) + "&n=" + size + "&o=" + Peers.encode(o.toString());
        HttpResponse<String> r;
        try {
            r = peers.call(l, "/api/peer/play", body, Duration.ofMinutes(30), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
        Map<String, Object> j = MiniJson.object(MiniJson.parse(r.body()));
        if (j == null) throw new IOException("it answered " + r.statusCode());
        if (r.statusCode() != 200) throw new IOException(String.valueOf(MiniJson.string(j.get("error"))));
        Double port = MiniJson.number(j.get("port"));
        String ticket = MiniJson.string(j.get("ticket")), core = MiniJson.string(j.get("core"));
        if (port == null || ticket == null) throw new IOException("it answered without a connection");
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(l.host(), port.intValue()), 5000);
            s.setTcpNoDelay(true);
            s.getOutputStream().write((ticket + "\n").getBytes(StandardCharsets.US_ASCII));
            s.getOutputStream().flush();
        } catch (IOException e) {
            s.close();
            throw new IOException("can't connect to it on port " + port.intValue() + " (a firewall?): " + e.getMessage());
        }
        Remote remote = new Remote(s, () -> peers.syncGame(l, system, rom.getFileName().toString()));
        return new Away(remote, core == null ? "" : core);
    }

    /** A game started on another WII-UU: the process for Choccy's session, and the core it plays with there. */
    public record Away(Process process, String core) {}

    /**
     * A game playing on another WII-UU, looking like a Choccy Milk process to WII-UU: its input
     * stream has the frames (unpacked) and messages, its output stream takes the controls. The
     * sound plays here as it comes.
     */
    static final class Remote extends Process {
        private final Socket socket;
        private final OutputStream controls;
        private final PipedInputStream frames = new PipedInputStream(1 << 21);
        private final CountDownLatch done = new CountDownLatch(1);
        private final Runnable afterwards;
        private volatile int exit = 0;
        private final Sound sound = new Sound();

        Remote(Socket socket, Runnable afterwards) throws IOException {
            this.socket = socket;
            this.controls = new BufferedOutputStream(socket.getOutputStream(), 4096);
            this.afterwards = afterwards;
            PipedOutputStream to = new PipedOutputStream(frames);
            Thread t = new Thread(() -> unpack(to), "split-frames");
            t.setDaemon(true);
            t.start();
        }

        private void unpack(PipedOutputStream to) {
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 1 << 16));
                 DataOutputStream out = new DataOutputStream(to)) {
                unpackStream(in, out, sound);
            } catch (IOException e) {
                exit = 1;
            } finally {
                sound.close();
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // closed
                }
                try {
                    afterwards.run();                               // the saves it made there come back here
                } catch (RuntimeException e) {
                    System.err.println("[split] " + e);
                }
                done.countDown();
            }
        }

        @Override
        public OutputStream getOutputStream() {
            return controls;
        }

        @Override
        public InputStream getInputStream() {
            return frames;
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() throws InterruptedException {
            done.await();
            return exit;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            return done.await(timeout, unit);
        }

        @Override
        public int exitValue() {
            if (done.getCount() > 0) throw new IllegalThreadStateException("still playing");
            return exit;
        }

        @Override
        public boolean isAlive() {
            return done.getCount() > 0;
        }

        @Override
        public void destroy() {
            try {
                socket.close();
            } catch (IOException ignored) {
                // closed
            }
        }

        /** Not a process of this computer. */
        @Override
        public long pid() {
            return -1;
        }
    }

    /** The other side of {@link #pack}: frames back to Choccy Milk's 'F' records, the sound to the speakers. */
    static void unpackStream(DataInputStream in, DataOutputStream out, Sound sound) throws IOException {
        Inflater inflater = new Inflater();
        int[] prev = new int[0];
        byte[] packed = new byte[0], delta = new byte[0], frame = new byte[0];
        while (true) {
            int kind = in.read();
            if (kind < 0) break;
            switch (kind) {
                case 'Z' -> {
                    int w = in.readInt(), h = in.readInt();
                    float aspect = in.readFloat();
                    int len = in.readInt();
                    if (w <= 0 || h <= 0 || w > 4096 || h > 4096 || len < 0 || len > 64 << 20) throw new IOException("bad frame");
                    int n = w * h;
                    if (packed.length < len) packed = new byte[len];
                    in.readFully(packed, 0, len);
                    if (prev.length != n) prev = new int[n];
                    if (delta.length < n * 3) delta = new byte[n * 3];
                    inflater.reset();
                    inflater.setInput(packed, 0, len);
                    try {
                        int got = 0;
                        while (got < n * 3 && !inflater.finished()) {
                            int k = inflater.inflate(delta, got, n * 3 - got);
                            if (k == 0 && (inflater.needsInput() || inflater.needsDictionary())) break;
                            got += k;
                        }
                        if (got != n * 3) throw new IOException("short frame");
                    } catch (DataFormatException e) {
                        throw new IOException("bad frame: " + e.getMessage());
                    }
                    if (frame.length < 13 + n * 4) frame = new byte[13 + n * 4];
                    java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(frame);
                    b.put((byte) 'F').putInt(w).putInt(h).putFloat(aspect);
                    for (int i = 0, k = 0, j = 13; i < n; i++, k += 3, j += 4) {
                        int px = prev[i] ^ ((delta[k] & 0xff) << 16 | (delta[k + 1] & 0xff) << 8 | (delta[k + 2] & 0xff));
                        prev[i] = px;
                        frame[j] = 0;
                        frame[j + 1] = (byte) (px >>> 16);
                        frame[j + 2] = (byte) (px >>> 8);
                        frame[j + 3] = (byte) px;
                    }
                    out.write(frame, 0, 13 + n * 4);
                    out.flush();
                }
                case 'S' -> {
                    int frames = in.readInt();
                    if (frames < 0 || frames > 1 << 20) throw new IOException("bad sound");
                    byte[] pcm = in.readNBytes(frames * 4);
                    if (sound != null) sound.play(pcm);
                }
                case 'R', 'X', 'N', 'A', 'P' -> {
                    String text = readLine(in);
                    if (text == null) throw new IOException("bad message");
                    out.write(kind);
                    out.write((text + "\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
                default -> throw new IOException("out of step");
            }
        }
    }

    /** The game's sound, played here as it arrives: a small buffer, and what comes late is dropped (no growing delay). */
    static final class Sound {
        private final BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(32);
        private volatile boolean closed;
        private Thread player;

        void play(byte[] pcm) {
            if (closed) return;
            if (player == null) {
                player = new Thread(this::run, "split-sound");
                player.setDaemon(true);
                player.start();
            }
            queue.offer(pcm);                                       // full: dropped
        }

        private void run() {
            javax.sound.sampled.SourceDataLine line;
            try {
                javax.sound.sampled.AudioFormat f = new javax.sound.sampled.AudioFormat(48000, 16, 2, true, false);
                line = javax.sound.sampled.AudioSystem.getSourceDataLine(f);
                line.open(f, 48000 / 1000 * 120 * 4);                 // 120 ms
                byte[] lead = new byte[48000 / 1000 * 40 * 4];          // 40 ms of silence first: room for the network's jitter
                line.write(lead, 0, lead.length);
                line.start();
            } catch (Exception | LinkageError e) {
                System.err.println("[split] no sound here: " + e.getMessage());
                return;
            }
            int max = line.getBufferSize();
            try {
                while (!closed) {
                    byte[] pcm = queue.poll(200, TimeUnit.MILLISECONDS);
                    if (pcm == null) continue;
                    int queued = max - line.available();
                    if (queued > max * 3 / 4) continue;                  // running late: skip a bit to catch up
                    line.write(pcm, 0, pcm.length);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                line.close();
            }
        }

        void close() {
            closed = true;
        }
    }
}
