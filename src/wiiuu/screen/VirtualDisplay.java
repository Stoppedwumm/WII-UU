package wiiuu.screen;

import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import wiiuu.core.Config;

/**
 * A display the TV doesn't show, for the emulator's window when a game's two screens are split
 * (the top one on the TV, the bottom one on the phone).
 *
 * <ul>
 *   <li><b>Linux (X11):</b> made on the spot. The X screen is widened past the right edge of the
 *       monitors ({@code xrandr --fb}) and the new strip is declared a monitor of its own
 *       ({@code xrandr --setmonitor}), so window managers place and keep windows there. No screen
 *       shows it, but screen capture reaches it. The X server keeps the pointer on areas an output
 *       shows, so the TV's output gets a panning area that includes the strip, with its tracking
 *       area (where the picture would scroll) limited to the TV itself: taps reach the strip, the
 *       TV never moves. A monitor pinned to the TV keeps the TV's size for windows. Undone when
 *       the game ends, or on the next start if WII-UU was killed.</li>
 *   <li><b>Windows:</b> Windows can't make a display without a driver, so a virtual display must be
 *       installed (a virtual display driver, e.g. "Virtual Display Driver"). WII-UU uses a display
 *       whose adapter name says "virtual" (or {@code screen.split.display=<n>}) and leaves it as is.</li>
 * </ul>
 * Rectangles are in real screen pixels.
 */
public final class VirtualDisplay {
    private static final String OS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    static final String NAME = "WIIUU-GAMEPAD";

    static final String TV_NAME = "WIIUU-TV";

    private final Rectangle area;
    private final String restoreFb;       // "WxH" to shrink the X screen back to, or null
    private final Path stateFile;
    private volatile String pannedOutput;  // the TV's output, while its panning includes the strip
    private volatile Rectangle tv;         // the TV's area, real pixels (Linux), or null

    /** The TV's area in real pixels, when known (Linux), else null. */
    public Rectangle tvArea() {
        return tv == null ? null : new Rectangle(tv);
    }

    private VirtualDisplay(Rectangle area, String restoreFb, Path stateFile) {
        this.area = area;
        this.restoreFb = restoreFb;
        this.stateFile = stateFile;
    }

    /** Why the last {@link #open} gave no display (for the TV and the phone), or null. */
    private static volatile String problem;

    public static String problem() {
        return problem;
    }

    /** Logs a step; one that ends in "the TV shows both screens" is kept as the reason there is no split. */
    private static volatile Path logFile;

    private static void note(String msg) {
        System.out.println("[split] " + msg);
        Path log = logFile;
        if (log != null) {
            try {
                Files.writeString(log, java.time.LocalDateTime.now().withNano(0) + " " + msg + System.lineSeparator(),
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            } catch (IOException ignored) {
                // no log
            }
        }
        if (msg.contains("the TV shows both screens") || msg.startsWith("needs") || msg.startsWith("no virtual")) problem = msg;
    }

    /** Where the display is, in real screen pixels. */
    public Rectangle area() {
        return new Rectangle(area);
    }

    /**
     * Makes (Linux) or finds (Windows) a hidden display of about {@code w} x {@code h} pixels.
     *
     * @return null (with the reason printed) when there is none
     */
    public static VirtualDisplay open(Config config, int w, int h) {
        problem = null;
        logFile = config.logDir().resolve("split.log");
        try {
            Files.createDirectories(logFile.getParent());
        } catch (IOException ignored) {
            // no log
        }
        try {
            if (OS.contains("win")) return windows(config);
            if (OS.contains("mac")) return null;
            return x11(config, w, h);
        } catch (RuntimeException e) {
            note("no GamePad display (" + e.getMessage() + "); the TV shows both screens");
            return null;
        }
    }

    /** Takes the display away again (Linux); nothing to do on Windows. */
    public void close() {
        if (stateFile == null) return;
        if (winDevice != null) {
            note("switching the virtual display off: "
                    + String.join(" ", displayScript(config, "-Action", "detach", "-Device", winDevice)).replace("result ", "").trim());
        }
        else undo(pannedOutput, restoreFb);
        try {
            Files.deleteIfExists(stateFile);
        } catch (IOException ignored) {
            // nothing left to undo
        }
    }

    /** At start: undo a display left behind if WII-UU was killed during a game. */
    public static void recover(Config config) {
        if (OS.contains("mac")) return;
        Path state = config.home().resolve("virtual-display");
        if (!Files.exists(state)) return;
        try {
            String fb = null, output = null;
            for (String l : Files.readAllLines(state)) {
                if (l.startsWith("win=") && OS.contains("win")) displayScript(config, "-Action", "detach", "-Device", l.substring(4).trim());
                if (l.startsWith("fb=")) fb = l.substring(3).trim();
                if (l.startsWith("output=")) output = l.substring(7).trim();
            }
            if (!OS.contains("win")) undo(output, fb != null && fb.matches("\\d+x\\d+") ? fb : null);
            Files.deleteIfExists(state);
            note("removed the GamePad display left from an interrupted game");
        } catch (IOException ignored) {
            // try again next time
        }
    }

    private static void undo(String output, String fb) {
        run(5, "xrandr", "--delmonitor", NAME);
        run(5, "xrandr", "--delmonitor", TV_NAME);
        if (output != null && !output.isEmpty()) run(5, "xrandr", "--output", output, "--panning", "0x0");
        if (fb != null) run(5, "xrandr", "--fb", fb);
    }

    private static void save(Path state, String fb, String output) {
        try {
            Files.createDirectories(state.getParent());
            Files.writeString(state, "fb=" + (fb == null ? "" : fb) + "\noutput=" + (output == null ? "" : output) + "\n");
        } catch (IOException ignored) {
            // still try
        }
    }

    // ---- Linux / X11 ------------------------------------------------------------------------------

    private static final Pattern OUTPUT = Pattern.compile("^(\\S+) connected (?:primary )?(\\d+)x(\\d+)\\+(-?\\d+)\\+(-?\\d+)(.*)$");

    private static final Pattern CURRENT = Pattern.compile("current (\\d+) x (\\d+), maximum (\\d+) x (\\d+)");
    private static final Pattern MONITOR = Pattern.compile("^\\s*\\d+: \\S+ (\\d+)/\\d+x(\\d+)/\\d+\\+(-?\\d+)\\+(-?\\d+)\\s+(\\S+)?");

    private static VirtualDisplay x11(Config config, int w, int h) {
        if ("wayland".equalsIgnoreCase(System.getenv("XDG_SESSION_TYPE")) || System.getenv("DISPLAY") == null) {
            note("needs an X11 session (on Wayland the TV shows both screens)");
            return null;
        }
        List<String> out = run(5, "xrandr");
        Matcher m = out.isEmpty() ? null : CURRENT.matcher(out.get(0));
        if (m == null || !m.find()) {
            note("xrandr not available (install x11-xserver-utils); the TV shows both screens");
            return null;
        }
        int fbW = Integer.parseInt(m.group(1)), fbH = Integer.parseInt(m.group(2));
        int maxW = Integer.parseInt(m.group(3)), maxH = Integer.parseInt(m.group(4));
        run(5, "xrandr", "--delmonitor", NAME);                       // a leftover from before
        int right = 0;
        for (String line : run(5, "xrandr", "--listmonitors")) {
            Matcher mm = MONITOR.matcher(line);
            if (mm.find()) right = Math.max(right, Integer.parseInt(mm.group(3)) + Integer.parseInt(mm.group(1)));
        }
        if (right == 0) right = fbW;
        int needW = right + w, needH = Math.max(fbH, h);
        if (needW > maxW || needH > maxH) {
            note("the X screen can't grow to " + needW + "x" + needH + " (maximum " + maxW + "x" + maxH + "); the TV shows both screens");
            return null;
        }
        // the TV: the output whose right edge the strip joins (it gets the panning area)
        String tv = null;
        Rectangle tvArea = null;
        for (String line : out) {
            Matcher o = OUTPUT.matcher(line);
            if (!o.find() || o.group(6).contains("panning")) continue;
            Rectangle r = new Rectangle(Integer.parseInt(o.group(4)), Integer.parseInt(o.group(5)), Integer.parseInt(o.group(2)), Integer.parseInt(o.group(3)));
            if (r.x + r.width == right && (tvArea == null || r.width * (long) r.height > tvArea.width * (long) tvArea.height)) {
                tv = o.group(1);
                tvArea = r;
            }
        }
        String restore = needW > fbW || needH > fbH ? fbW + "x" + fbH : null;
        Path state = config.home().resolve("virtual-display");
        save(state, restore, tv);                                       // written first: recover() can undo a crash
        if (restore != null) run(5, "xrandr", "--fb", Math.max(fbW, needW) + "x" + needH);
        run(5, "xrandr", "--setmonitor", NAME, w + "/0x" + h + "/0+" + right + "+0", "none");
        boolean ok = run(5, "xrandr", "--listmonitors").stream().anyMatch(l -> l.contains(NAME));
        if (!ok) {
            note("the X server did not accept an extra monitor; the TV shows both screens");
            new VirtualDisplay(new Rectangle(), restore, state).close();
            return null;
        }
        VirtualDisplay d = new VirtualDisplay(new Rectangle(right, 0, w, h), restore, state);
        if (tv != null) {
            // let the pointer onto the strip (taps from the phone), without the TV ever scrolling
            run(5, "xrandr", "--output", tv, "--panning", (right + w - tvArea.x) + "x" + (Math.max(needH, tvArea.height) - tvArea.y) + "+" + tvArea.x + "+" + tvArea.y
                    + "/" + tvArea.width + "x" + tvArea.height + "+" + tvArea.x + "+" + tvArea.y);
            // "*": the primary monitor, so the TV stays the main screen for Java and window managers
            run(5, "xrandr", "--setmonitor", "*" + TV_NAME, tvArea.width + "/0x" + tvArea.height + "/0+" + tvArea.x + "+" + tvArea.y, tv);
            d.pannedOutput = tv;
            d.tv = new Rectangle(tvArea);
        } else {
            note("no output found for the TV: taps can't reach the GamePad display");
        }
        note("GamePad display " + w + "x" + h + " at +" + right + "+0 (not shown on any screen)");
        return d;
    }

    // ---- Windows ----------------------------------------------------------------------------------

    /** One display adapter from display.ps1: name, adapter, attached, primary, rect (if attached), modes. */
    record WinDisplay(String name, String adapter, boolean attached, boolean primary, Rectangle rect, List<int[]> modes, String ids) {
        boolean virtual() {
            return (adapter + " " + ids).toLowerCase(Locale.ROOT).matches(".*(virtual|iddsampledriver|iddcx|mttvdd|mtt1337).*");
        }
    }

    static List<WinDisplay> parseDisplays(List<String> lines) {
        List<WinDisplay> out = new ArrayList<>();
        for (String line : lines) {
            String[] p = line.split("\t", -1);
            if (p.length < 6 || !p[0].startsWith("\\\\.\\")) continue;
            Rectangle r = null;
            String[] xywh = p[4].split(",");
            if (xywh.length == 4) {
                try {
                    r = new Rectangle(Integer.parseInt(xywh[0].trim()), Integer.parseInt(xywh[1].trim()), Integer.parseInt(xywh[2].trim()), Integer.parseInt(xywh[3].trim()));
                } catch (NumberFormatException ignored) {
                    // not attached
                }
            }
            List<int[]> modes = new ArrayList<>();
            for (String m : p[5].trim().split("\\s+")) {
                String[] wh = m.split("x");
                if (wh.length == 2) {
                    try {
                        modes.add(new int[]{Integer.parseInt(wh[0]), Integer.parseInt(wh[1])});
                    } catch (NumberFormatException ignored) {
                        // skip
                    }
                }
            }
            String ids = (p.length > 6 ? p[6] : "") + " " + (p.length > 7 ? p[7] : "");
            out.add(new WinDisplay(p[0].trim(), p[1].trim(), p[2].trim().equals("attached"), p[3].trim().equals("primary"), r, modes, ids.trim()));
        }
        return out;
    }

    /** The mode to switch a virtual display on with: landscape, as tall as possible up to 1440 lines. */
    static int[] bestMode(List<int[]> modes) {
        int[] best = null;
        for (int[] m : modes) {
            if (m[0] < m[1] || m[1] > 1440) continue;
            if (best == null || m[1] > best[1] || (m[1] == best[1] && m[0] < best[0])) best = m;
        }
        if (best == null) {
            for (int[] m : modes) if (best == null || m[1] < best[1]) best = m;
        }
        return best;
    }

    private static List<String> displayScript(Config config, String... args) {
        Path script = config.home().resolve("bin").resolve("display.ps1");
        try (var in = VirtualDisplay.class.getResourceAsStream("/win/display.ps1")) {
            if (in == null) return List.of();
            Files.createDirectories(script.getParent());
            Files.write(script, in.readAllBytes());
        } catch (IOException e) {
            return List.of();
        }
        List<String> cmd = new ArrayList<>(List.of("powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", script.toString()));
        cmd.addAll(List.of(args));
        return run(20, cmd.toArray(new String[0]));
    }

    private static VirtualDisplay windows(Config config) {
        String pick = config.get("screen.split.display", "").trim();
        List<String> raw = displayScript(config, "-Action", "list");
        List<WinDisplay> all = parseDisplays(raw);
        if (all.isEmpty()) {
            String err = raw.stream().filter(l -> !l.isBlank()).findFirst().orElse("no answer");
            note("could not ask Windows about the displays (PowerShell: " + err.trim() + "); the TV shows both screens");
            return null;
        }
        WinDisplay v = null;
        for (WinDisplay d : all) {
            boolean chosen = pick.isEmpty() ? d.virtual() && !d.primary() : d.name().replaceAll("\\D", "").equals(pick.replaceAll("\\D", ""));
            if (chosen) {
                v = d;
                break;
            }
        }
        if (v == null) {
            note("no virtual display installed: Settings (F1) > General > Install virtual display; the TV shows both screens");
            return null;
        }
        if (v.attached() && v.rect() != null) {
            // already part of the desktop (you use it yourself): use it as it is, leave it on afterwards
            note("GamePad display: " + v.adapter() + " " + v.rect().width + "x" + v.rect().height + " (already on)");
            return new VirtualDisplay(v.rect(), null, null);
        }
        int[] mode = bestMode(v.modes());
        if (mode == null) mode = new int[]{1920, 1080};
        int right = 0, top = 0;
        for (WinDisplay d : all) {
            if (d.attached() && d.rect() != null && d != v) {
                right = Math.max(right, d.rect().x + d.rect().width);
                if (d.primary()) top = d.rect().y;
            }
        }
        Path state = config.home().resolve("virtual-display");
        try {
            Files.createDirectories(state.getParent());
            Files.writeString(state, "win=" + v.name() + "\n");         // written first: recover() can switch it off after a crash
        } catch (IOException ignored) {
            // still try
        }
        List<String> res = displayScript(config, "-Action", "attach", "-Device", v.name(), "-X", "" + right, "-Y", "" + top, "-W", "" + mode[0], "-H", "" + mode[1]);
        String how = String.join(" ", res).replace("result ", "").trim();
        note("switching the virtual display on: " + how);
        Rectangle now = null;
        for (WinDisplay d : parseDisplays(displayScript(config, "-Action", "list"))) {
            if (d.name().equals(v.name()) && d.attached()) now = d.rect();
        }
        if (now == null) {
            note("Windows would not switch the virtual display on (" + how + "); the TV shows both screens");
            try {
                Files.deleteIfExists(state);
            } catch (IOException ignored) {
                // nothing to undo
            }
            return null;
        }
        note("GamePad display: " + v.adapter() + " " + now.width + "x" + now.height + " at " + now.x + "," + now.y + " (switched on for the game)");
        VirtualDisplay d = new VirtualDisplay(now, null, state);
        d.winDevice = v.name();
        d.config = config;
        return d;
    }

    private volatile String winDevice;     // Windows: the display WII-UU switched on (switched off again at the end)
    private volatile Config config;

    /**
     * {@code wiiuu --split-check}: what Windows (or X11) reports, and a try at switching the hidden
     * display on and off, printed for a bug report.
     */
    public static void check(Config config) {
        System.out.println("WII-UU split-screen check (" + System.getProperty("os.name") + ")");
        if (OS.contains("win")) {
            List<String> raw = displayScript(config, "-Action", "list");
            System.out.println("Displays Windows reports:");
            for (String l : raw) System.out.println("  " + l.replace("\t", " | "));
            for (WinDisplay d : parseDisplays(raw)) {
                System.out.println("  " + d.name() + ": virtual=" + d.virtual() + " attached=" + d.attached() + " primary=" + d.primary()
                        + " modes=" + d.modes().size() + (d.virtual() ? " best=" + java.util.Arrays.toString(bestMode(d.modes())) : ""));
            }
        }
        System.out.println("Trying to switch the GamePad display on:");
        VirtualDisplay d = open(config, 768, 1152);
        if (d == null) {
            System.out.println("  no display: " + problem);
            return;
        }
        System.out.println("  on at " + d.area() + "; switching it off again");
        d.close();
        System.out.println("  done");
    }

    /** Whether a virtual display (driver) is installed. */
    public static boolean virtualInstalled(Config config) {
        return parseDisplays(displayScript(config, "-Action", "list")).stream().anyMatch(WinDisplay::virtual);
    }

    /**
     * Installs the Virtual Display Driver: Windows asks for administrator rights once. Afterwards its
     * display is switched off until a game needs it.
     *
     * @return what happened, in a sentence
     */
    public static String installWindows(Config config) {
        if (virtualInstalled(config)) {
            switchOffVirtual(config);
            return "The virtual display is installed.";
        }
        Path script = config.home().resolve("bin").resolve("install-vdd.ps1");
        Path log = Path.of(System.getProperty("java.io.tmpdir"), "wiiuu-vdd", "install.log");
        try (var in = VirtualDisplay.class.getResourceAsStream("/win/install-vdd.ps1")) {
            if (in == null) return "The installer script is missing from WII-UU.";
            Files.createDirectories(script.getParent());
            Files.write(script, in.readAllBytes());
            Files.deleteIfExists(log);
        } catch (IOException e) {
            return "Could not prepare the install: " + e.getMessage();
        }
        // the path goes through the environment: no quoting trouble with spaces in user names
        try {
            ProcessBuilder pb = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command",
                    "Start-Process powershell -Verb RunAs -Wait -WindowStyle Hidden -ArgumentList ('-NoProfile -ExecutionPolicy Bypass -File \"' + $env:WIIUU_VDD + '\"')");
            pb.environment().put("WIIUU_VDD", script.toString());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor(10, java.util.concurrent.TimeUnit.MINUTES);
            if (p.exitValue() != 0 && out.contains("canceled")) return "Administrator rights were not given, so nothing was installed.";
        } catch (IOException | InterruptedException e) {
            return "Could not start the install: " + e.getMessage();
        }
        String result = "";
        try {
            result = Files.readString(log).trim();
        } catch (IOException ignored) {
            // no log: see below
        }
        if (virtualInstalled(config)) {
            switchOffVirtual(config);
            return "Virtual display installed. DS and 3DS games in RetroArch mode now show the top screen on the TV and the touch screen on the phone.";
        }
        return result.startsWith("failed") ? "The install " + result : "The virtual display did not install (Administrator rights not given?).";
    }

    /** Switches off every virtual display that is on (after installing the driver: keep the desktop as it was). */
    public static int switchOffVirtual(Config config) {
        int n = 0;
        for (WinDisplay d : parseDisplays(displayScript(config, "-Action", "list"))) {
            if (d.virtual() && d.attached() && !d.primary()) {
                displayScript(config, "-Action", "detach", "-Device", d.name());
                n++;
            }
        }
        return n;
    }

    // ---- helpers ----------------------------------------------------------------------------------

    /** Real screen pixels to Java's (scaled) units. */
    public static Rectangle toJava(Rectangle r) {
        return ScreenStreamer.toJavaUnits(r);
    }

    static List<String> run(int timeoutSec, String... cmd) {
        List<String> lines = new ArrayList<>();
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String l;
                while ((l = r.readLine()) != null) lines.add(l);
            }
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) p.destroyForcibly();
        } catch (IOException e) {
            // tool not installed
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return lines;
    }
}
