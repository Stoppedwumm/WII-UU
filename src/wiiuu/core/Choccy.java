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

    private static volatile Path java;
    private static volatile boolean looked;

    /** Whether Choccy Milk can run here: it's in this build, and there is a Java 22 or newer. */
    public static boolean available() {
        return built() && java() != null;
    }

    static boolean built() {
        return Choccy.class.getResource("/wiiuu/choccy/ChoccyMilk.class") != null;
    }

    /** Why not, for Settings (null when it can). */
    public static String unavailable() {
        if (!built()) return "This copy of WII-UU was built without Choccy Milk";
        if (java() == null) {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            return "Choccy Milk needs Java 22 or newer next to WII-UU's"
                    + (os.contains("linux") ? ": sudo apt install openjdk-25-jre"
                    : os.contains("mac") ? ": brew install openjdk" : ": install a current Java (JDK 25)");
        }
        return null;
    }

    /** A Java 22+ to run Choccy Milk on: this one if it's new enough, else one installed beside it. */
    public static Path java() {
        if (looked) return java;
        synchronized (Choccy.class) {
            if (looked) return java;
            if (Runtime.version().feature() >= 22) {
                java = ProcessHandle.current().info().command().map(Path::of).orElse(null);
            }
            if (java == null) java = findJava();
            looked = true;
            if (java != null) System.out.println("[choccy] runs on " + java);
            return java;
        }
    }

    private static Path findJava() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        boolean windows = os.contains("win");
        List<Path> roots = new ArrayList<>();
        if (os.contains("mac")) {
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
        if (os.contains("mac")) {
            // Homebrew: /opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home (and /usr/local on Intel)
            for (String brew : new String[]{"/opt/homebrew/opt", "/usr/local/opt"}) roots.add(Path.of(brew));
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
                        if (v >= 22 && v > bestVersion) {
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

    /** What to put on the class path: WII-UU's jar (or its classes folder, when run from a build). */
    private static String classPath() {
        try {
            return Path.of(Choccy.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        } catch (Exception e) {
            return System.getProperty("java.class.path");
        }
    }

    /** The command that plays rom with core. */
    static List<String> command(Path core, Path rom, Path saves, Path system, Map<String, String> options) {
        List<String> cmd = new ArrayList<>(List.of(java().toString(), "--enable-native-access=ALL-UNNAMED",
                "-Xss2m", "-cp", classPath(), "wiiuu.choccy.ChoccyMilk",
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
    }

    static Session start(Process p, Screen screen, Path log) {
        return new Session(p, screen, log);
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
    static boolean stickIsDpad(String system) {
        return switch (system) {
            case "nes", "snes", "gb", "gba", "sms", "genesis", "saturn", "nds" -> true;
            default -> false;
        };
    }

    static String fileSep() {
        return File.separator;
    }
}
