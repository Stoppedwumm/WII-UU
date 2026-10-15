package wiiuu.core;

import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import wiiuu.input.PadButton;

/**
 * Choccy Milk, WII-UU's own libretro frontend, from WII-UU's side: finds a Java 22+ to run it on
 * (WII-UU itself runs on 17), starts it ({@code wiiuu.choccy.ChoccyMilk}, in its own process, so a
 * crashing core can't take WII-UU along), sends it the players' buttons and sticks, and hands the
 * frames it sends to WII-UU's screen.
 */
public final class Choccy {

    /** Where the frames go: 0xRRGGBB pixels, w x h, to be shown at this aspect ratio. Called on the reading thread. */
    public interface Screen {
        void frame(int[] rgb, int w, int h, float aspect);
    }

    private Choccy() {}

    private static volatile Path javaExe;
    private static volatile boolean looked;
    private static volatile Path home = Path.of(System.getProperty("user.home"), ".wiiuu");

    /** WII-UU's settings folder (shared by the Choccy Milk app): Choccy Milk's own Java goes into choccy/jre there. */
    public static synchronized void setHome(Path wiiuuHome) {
        home = wiiuuHome;
        looked = false;
        javaExe = null;
    }

    private static Path ownJre() {
        return home.resolve("choccy").resolve("jre");
    }

    /** Whether Choccy Milk can run here: it's in this build, and there is a Java 22 or newer. */
    public static boolean available() {
        return built() && java() != null;
    }

    static boolean built() {
        return Choccy.class.getResource("/wiiuu/choccy/ChoccyMilk.class") != null;
    }

    /** Why not, for Settings (null when it can). */
    public static String unavailable() {
        if (!built()) return "This copy of WII-UU was built without Choccy Milk (by a JDK older than 22): "
                + "update WII-UU, or run install.sh again";
        if (java() == null && !canGetJava()) {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            return "Choccy Milk needs Java 22 or newer next to WII-UU's"
                    + (os.contains("linux") ? ": sudo apt install openjdk-25-jre"
                    : os.contains("mac") ? ": brew install openjdk" : ": install a current Java (JDK 25)");
        }
        return null;
    }

    /** A Java 22+ to run Choccy Milk on: this one if it's new enough, else one installed beside it. */
    public static Path java() {
        if (looked) return javaExe;
        synchronized (Choccy.class) {
            if (looked) return javaExe;
            if (Runtime.version().feature() >= 22 && !translated()) {
                javaExe = ProcessHandle.current().info().command().map(Path::of).orElse(null);
            }
            if (javaExe == null) javaExe = findJava(List.of(ownJre()));
            if (javaExe == null) javaExe = findJava(List.of());
            looked = true;
            if (javaExe != null) System.out.println("[choccy] runs on " + javaExe);
            return javaExe;
        }
    }

    private static Path findJava(List<Path> only) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        boolean windows = os.contains("win");
        List<Path> roots = new ArrayList<>(only);
        if (!only.isEmpty()) {
            // (just these)
        } else if (os.contains("mac")) {
            roots.add(Path.of("/Library/Java/JavaVirtualMachines"));
            roots.add(Path.of(System.getProperty("user.home"), "Library", "Java", "JavaVirtualMachines"));
        } else if (windows) {
            for (String pf : new String[]{System.getenv("ProgramFiles"), System.getenv("ProgramW6432")}) {
                if (pf == null) continue;
                for (String vendor : new String[]{"Java", "Eclipse Adoptium", "Microsoft", "Zulu", "Amazon Corretto", "BellSoft"}) {
                    roots.add(Path.of(pf, vendor));
                }
            }
        } else {
            roots.add(Path.of("/usr/lib/jvm"));
            roots.add(Path.of("/opt/java"));
            roots.add(Path.of(System.getProperty("user.home"), ".sdkman", "candidates", "java"));
            roots.add(Path.of(System.getProperty("user.home"), ".jdks"));
        }
        if (only.isEmpty() && os.contains("mac")) {
            // Homebrew: /opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home (and /usr/local on Intel),
            // and its Cellar (/opt/homebrew/Cellar/openjdk/25.0.1/...)
            for (String brew : new String[]{"/opt/homebrew/opt", "/usr/local/opt"}) roots.add(Path.of(brew));
            for (String cellar : new String[]{"/opt/homebrew/Cellar", "/usr/local/Cellar"}) {
                for (String f : new String[]{"openjdk", "openjdk@25", "openjdk@24", "openjdk@23", "openjdk@22"}) {
                    roots.add(Path.of(cellar, f));
                }
            }
        }
        Path best = null;
        int bestVersion = 0;
        for (Path root : roots) {
            if (!Files.isDirectory(root)) continue;
            try (var dirs = Files.list(root)) {
                for (Path d : dirs.toList()) {
                    Path brewJdk = d.resolve("libexec").resolve("openjdk.jdk").resolve("Contents").resolve("Home");
                    for (Path home : new Path[]{d, d.resolve("Contents").resolve("Home"), brewJdk}) {
                        Path exe = home.resolve("bin").resolve(windows ? "java.exe" : "java");
                        if (!Files.isExecutable(exe)) continue;
                        int v = version(home);
                        if (v >= 22 && v > bestVersion && sameArch(home)) {
                            best = exe;
                            bestVersion = v;
                        }
                    }
                }
            } catch (IOException ignored) {
                // look elsewhere
            }
        }
        return best;
    }

    // ---- Choccy Milk's own Java -------------------------------------------------------------------

    private static volatile boolean downloading;

    /** Where Eclipse Adoptium has a Java 25 runtime for this computer, or null. */
    private static String jreUrl() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String a = arch();
        String o = os.contains("mac") ? "mac" : os.contains("win") ? "windows" : os.contains("linux") ? "linux" : null;
        String ar = a.equals("arm64") ? "aarch64" : a.equals("x64") ? "x64" : null;
        if (o == null || ar == null) return null;
        return "https://api.adoptium.net/v3/binary/latest/25/ga/" + o + "/" + ar + "/jre/hotspot/normal/eclipse";
    }

    /** Whether WII-UU can fetch a Java for Choccy Milk itself (when there's none). */
    public static boolean canGetJava() {
        return built() && jreUrl() != null;
    }

    /**
     * Downloads a Java 25 runtime (Eclipse Temurin, about 50 MB) into ~/.wiiuu/choccy/jre, in the
     * background; then runs done (with Choccy Milk available, or not if it failed).
     */
    public static void getJava(Runnable done) {
        if (downloading) return;
        downloading = true;
        Thread t = new Thread(() -> {
            Path dir = ownJre(), tmp = dir.resolveSibling("jre-download");
            try {
                String url = jreUrl();
                boolean zip = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
                Files.createDirectories(dir.getParent());
                Path file = dir.resolveSibling(zip ? "jre.zip" : "jre.tar.gz");
                System.out.println("[choccy] getting Java for Choccy Milk: " + url);
                java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder()
                        .followRedirects(java.net.http.HttpClient.Redirect.ALWAYS).connectTimeout(java.time.Duration.ofSeconds(20)).build();
                java.net.http.HttpResponse<Path> res = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                        .header("User-Agent", "WII-UU").build(), java.net.http.HttpResponse.BodyHandlers.ofFile(file));
                if (res.statusCode() != 200) throw new IOException("download answered " + res.statusCode());
                deleteTree(tmp);
                Files.createDirectories(tmp);
                if (zip) unzip(file, tmp);
                else {
                    Process p = new ProcessBuilder("tar", "-xzf", file.toString(), "-C", tmp.toString())
                            .redirectErrorStream(true).start();
                    String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    if (p.waitFor() != 0) throw new IOException("tar: " + out.trim());
                }
                deleteTree(dir);
                Files.move(tmp, dir);
                Files.deleteIfExists(file);
                synchronized (Choccy.class) {
                    looked = false;
                    javaExe = null;
                }
                System.out.println("[choccy] Java for Choccy Milk: " + java());
            } catch (Exception e) {
                System.err.println("[choccy] couldn't get Java: " + e.getMessage());
            } finally {
                downloading = false;
                done.run();
            }
        }, "choccy-java");
        t.setDaemon(true);
        t.start();
    }

    private static void unzip(Path zip, Path to) throws IOException {
        try (java.util.zip.ZipInputStream z = new java.util.zip.ZipInputStream(Files.newInputStream(zip))) {
            for (java.util.zip.ZipEntry e; (e = z.getNextEntry()) != null; ) {
                Path out = to.resolve(e.getName()).normalize();
                if (!out.startsWith(to)) continue;
                if (e.isDirectory()) Files.createDirectories(out);
                else {
                    Files.createDirectories(out.getParent());
                    Files.copy(z, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (var walk = Files.walk(p)) {
            for (Path q : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(q);
        }
    }

    /** A Java's major version, from its "release" file (JAVA_VERSION="25.0.4"); 0 if unknown. */
    private static int version(Path javaHome) {
        try {
            for (String line : Files.readAllLines(javaHome.resolve("release"))) {
                Matcher m = Pattern.compile("^JAVA_VERSION=\"(?:1\\.)?(\\d+)").matcher(line.trim());
                if (m.find()) return Integer.parseInt(m.group(1));
            }
        } catch (IOException | RuntimeException ignored) {
            // not a JDK we can read
        }
        return 0;
    }

    /**
     * Whether that Java is built for the same processor as WII-UU's (its release file's OS_ARCH):
     * the cores WII-UU finds and downloads are for this one, and an ARM core can't load into an
     * Intel Java (a Mac with Rosetta, say). Unknown counts as the same.
     */
    private static boolean sameArch(Path javaHome) {
        String mine = arch();
        try {
            for (String line : Files.readAllLines(javaHome.resolve("release"))) {
                if (line.startsWith("OS_ARCH=")) return arch(line.substring(8).replace("\"", "").trim()).equals(mine);
            }
        } catch (IOException | RuntimeException ignored) {
            // can't tell
        }
        return true;
    }

    private static volatile String nativeArch;

    /**
     * The processor Choccy Milk runs on ("arm64", "x64"...): this computer's own, also when WII-UU
     * itself runs translated (an Intel Java on an Apple Silicon Mac, through Rosetta), because it's
     * its own process and a game is much faster without translation.
     */
    public static String arch() {
        String a = nativeArch;
        if (a != null) return a;
        a = arch(System.getProperty("os.arch", ""));
        if (a.equals("x64") && System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")) {
            try {
                Process p = new ProcessBuilder("sysctl", "-n", "hw.optional.arm64").redirectErrorStream(true).start();
                String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                p.waitFor();
                if (out.equals("1")) a = "arm64";
            } catch (IOException | RuntimeException e) {
                // an Intel Mac, then
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        nativeArch = a;
        return a;
    }

    /** Whether WII-UU runs translated (Rosetta): then Choccy Milk needs its own Java and cores. */
    public static boolean translated() {
        return !arch().equals(arch(System.getProperty("os.arch", "")));
    }

    private static String arch(String a) {
        a = a.toLowerCase(Locale.ROOT);
        if (a.contains("aarch64") || a.contains("arm64")) return "arm64";
        if (a.contains("amd64") || a.contains("x86_64")) return "x64";
        return a;
    }

    /** What to put on the class path: WII-UU's jar (or its classes folder, when run from a build). */
    private static String classPath() {
        try {
            return Path.of(Choccy.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        } catch (Exception e) {
            return System.getProperty("java.class.path");
        }
    }

    /** The command that plays rom with core. */
    public static List<String> command(Path core, Path rom, Path saves, Path system, Map<String, String> options) {
        List<String> cmd = new ArrayList<>(List.of(java().toString(), "--enable-native-access=ALL-UNNAMED",
                "-XX:+DisplayVMOutputToStderr", "-cp", classPath(), "wiiuu.choccy.ChoccyMilk",
                core.toString(), rom.toAbsolutePath().toString(), saves.toString(), system.toString()));
        for (var e : options.entrySet()) cmd.add(e.getKey() + "=" + e.getValue());
        return cmd;
    }

    // ---- a game playing ----------------------------------------------------------------------------

    /** A running Choccy Milk: reads its frames and messages, sends it input. */
    public static final class Session {
        private final Process process;
        private final OutputStream in;
        private volatile String core = "";
        private volatile String ending;            // its last word: "" when all went well

        Session(Process process, Screen screen, Path log) {
            this.process = process;
            this.in = process.getOutputStream();
            Thread t = new Thread(() -> read(screen, log), "choccy-frames");
            t.setDaemon(true);
            t.start();
        }

        private void read(Screen screen, Path log) {
            try (DataInputStream s = new DataInputStream(new java.io.BufferedInputStream(process.getInputStream(), 1 << 16))) {
                byte[] raw = new byte[0];
                while (true) {
                    int kind = s.read();
                    if (kind < 0) break;
                    if (kind == 'F') {
                        int w = s.readInt(), h = s.readInt();
                        float aspect = s.readFloat();
                        if (w <= 0 || h <= 0 || w > 4096 || h > 4096) break;
                        int n = w * h;
                        if (raw.length < n * 4) raw = new byte[n * 4];
                        s.readFully(raw, 0, n * 4);
                        int[] frame = new int[n];            // a fresh array each time: the screen keeps the last one
                        for (int i = 0, j = 0; i < n; i++, j += 4) {
                            frame[i] = (raw[j + 1] & 0xff) << 16 | (raw[j + 2] & 0xff) << 8 | (raw[j + 3] & 0xff);
                        }
                        screen.frame(frame, w, h, aspect);
                    } else if (kind == 'R') {
                        core = line(s);
                        System.out.println("[choccy] playing with " + core);
                    } else if (kind == 'X') {
                        ending = line(s);
                    } else if (kind == 'N') {                   // something to show ("State 1 saved")
                        String text = line(s);
                        try {
                            onNotice.accept(text);
                        } catch (RuntimeException e) {
                            System.err.println("[choccy] " + e);
                        }
                    } else if (kind == 'P') {                   // how fast it runs (100: full speed)
                        try {
                            int pct = Integer.parseInt(line(s).trim());
                            speed = pct;
                            onSpeed.accept(pct);
                        } catch (RuntimeException e) {
                            // not a number
                        }
                    } else if (kind == 'A') {                   // an achievement unlocked
                        String id = line(s);
                        try {
                            onAchievement.accept(Integer.parseInt(id.trim()));
                        } catch (RuntimeException e) {
                            System.err.println("[achievements] " + e);
                        }
                    } else {
                        break;                               // out of step: stop reading
                    }
                }
            } catch (IOException ignored) {
                // it's gone
            }
        }

        private static String line(DataInputStream s) throws IOException {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            for (int c; (c = s.read()) >= 0 && c != '\n'; ) b.write(c);
            return b.toString(StandardCharsets.UTF_8);
        }

        /** Why it stopped (after it has): "" or null when all went well. */
        public String ending() {
            return ending;
        }

        public String core() {
            return core;
        }

        private volatile java.util.function.IntConsumer onAchievement = id -> { };
        private volatile java.util.function.IntConsumer onSpeed = pct -> { };
        private volatile int speed = -1;

        /** Gets how fast the game runs (percent of full speed), every ten seconds, on the reading thread. */
        public void onSpeed(java.util.function.IntConsumer listener) {
            onSpeed = listener;
        }

        /** How fast it ran at the last report (percent of full speed), -1 before the first. */
        public int speed() {
            return speed;
        }
        private volatile java.util.function.Consumer<String> onNotice = t -> { };

        /** Gets what the player has to say ("State 1 saved"), on the reading thread. */
        public void onNotice(java.util.function.Consumer<String> listener) {
            onNotice = listener;
        }

        /** The save-state slot in use (0-9; 0 is the plain ROM.state). */
        private volatile int slot;

        public int slot() {
            return slot;
        }

        /** Another slot (wraps around 0-9); returns it. */
        public int slot(int step) {
            slot = Math.floorMod(slot + step, 10);
            return slot;
        }

        /** Saves the game as it is now into the slot (a save state). */
        public void saveState() {
            send("save " + slot);
        }

        /** Goes back to the state saved in the slot. */
        public void loadState() {
            send("load " + slot);
        }

        /** Saves, loads ("save", "load") or changes the slot ("slotup", "slotdown", "slot3"); the text to show for a slot change, else null. */
        public String state(String action) {
            switch (action) {
                case "save" -> saveState();
                case "load" -> loadState();
                case "slotup" -> {
                    return "State slot " + slot(1);
                }
                case "slotdown" -> {
                    return "State slot " + slot(-1);
                }
                default -> {
                    if (action.startsWith("slot")) {
                        try {
                            slot = Math.max(0, Math.min(9, Integer.parseInt(action.substring(4))));
                            return "State slot " + slot;
                        } catch (NumberFormatException ignored) {
                            // not a slot
                        }
                    }
                }
            }
            return null;
        }

        /** Gets the ids of achievements as they unlock (on the reading thread). */
        public void onAchievement(java.util.function.IntConsumer listener) {
            onAchievement = listener;
        }

        /** Gives the player achievements to watch for: the console (RetroAchievements' number) and their logic. */
        public void achievements(int console, java.util.List<RaConnect.Achievement> list) {
            send("c " + console);
            for (RaConnect.Achievement a : list) send("a " + a.id() + " " + a.memAddr().replace('\n', ' '));
        }

        private synchronized void send(String line) {
            try {
                in.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                in.flush();
            } catch (IOException ignored) {
                // it's gone
            }
        }

        public void button(int port, PadButton b, boolean down) {
            int id = retroId(b);
            if (id >= 0) send("b " + port + " " + id + " " + (down ? 1 : 0));
        }

        public void stick(int port, int stick, float x, float y) {
            send(String.format(Locale.ROOT, "s %d %d %.3f %.3f", port, stick, x, y));
        }

        /** Asks it to save and stop. */
        public void quit() {
            send("q");
        }

        /** The player process. */
        public Process process() {
            return process;
        }

        private final Map<Integer, java.util.Set<PadButton>> stickDpad = new java.util.concurrent.ConcurrentHashMap<>();

        /** A stick that also works the d-pad (for consoles without analog sticks, see {@link #stickIsDpad}). */
        public void stickAsDpad(int port, float x, float y) {
            java.util.Set<PadButton> was = stickDpad.computeIfAbsent(port, k -> java.util.EnumSet.noneOf(PadButton.class));
            java.util.Set<PadButton> now = java.util.EnumSet.noneOf(PadButton.class);
            if (x < -0.5f) now.add(PadButton.LEFT);
            if (x > 0.5f) now.add(PadButton.RIGHT);
            if (y < -0.5f) now.add(PadButton.UP);
            if (y > 0.5f) now.add(PadButton.DOWN);
            for (PadButton b : PadButton.values()) {
                if (now.contains(b) != was.contains(b)) button(port, b, now.contains(b));
            }
            was.clear();
            was.addAll(now);
        }
    }

    static Session start(Process p, Screen screen, Path log) {
        return new Session(p, screen, log);
    }

    /**
     * A game Choccy Milk plays somewhere else (on another WII-UU): {@code p} speaks the same as the
     * player process does (its frames and messages on the input stream, the controls to its output).
     */
    public static Session attach(Process p, Screen screen, Path log) {
        return new Session(p, screen, log);
    }

    /**
     * Starts a game: core plays rom, saves in saves, BIOS files from system, core options; the
     * player's messages go to log, its frames to screen.
     */
    public static Session play(Path core, Path rom, Path saves, Path system, Map<String, String> options,
                               Screen screen, Path log) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command(core, rom, saves, system, options));
        try {
            Files.createDirectories(log.getParent());
            pb.redirectError(log.toFile());
        } catch (IOException e) {
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        }
        return start(pb.start(), screen, log);
    }

    /** RetroPad button ids (libretro's RETRO_DEVICE_ID_JOYPAD_*): WII-UU's layout is the same as the RetroPad's. */
    static int retroId(PadButton b) {
        return switch (b) {
            case B -> 0;
            case Y -> 1;
            case MINUS -> 2;
            case PLUS -> 3;
            case UP -> 4;
            case DOWN -> 5;
            case LEFT -> 6;
            case RIGHT -> 7;
            case A -> 8;
            case X -> 9;
            case L -> 10;
            case R -> 11;
            case ZL -> 12;
            case ZR -> 13;
            case L3 -> 14;
            case R3 -> 15;
            default -> -1;
        };
    }

    /** Consoles whose games don't use an analog stick: there the left stick works the d-pad too. */
    public static boolean stickIsDpad(String system) {
        return switch (system) {
            case "nes", "snes", "gb", "gba", "sms", "genesis", "saturn", "nds" -> true;
            default -> false;
        };
    }

    static String fileSep() {
        return File.separator;
    }
}
