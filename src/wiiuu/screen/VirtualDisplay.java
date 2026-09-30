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
        try {
            if (OS.contains("win")) return windows(config);
            if (OS.contains("mac")) return null;
            return x11(config, w, h);
        } catch (RuntimeException e) {
            System.err.println("[split] no GamePad display: " + e.getMessage());
            return null;
        }
    }

    /** Takes the display away again (Linux); nothing to do on Windows. */
    public void close() {
        if (stateFile == null) return;
        undo(pannedOutput, restoreFb);
        try {
            Files.deleteIfExists(stateFile);
        } catch (IOException ignored) {
            // nothing left to undo
        }
    }

    /** At start: undo a display left behind if WII-UU was killed during a game. */
    public static void recover(Config config) {
        if (OS.contains("win") || OS.contains("mac")) return;
        Path state = config.home().resolve("virtual-display");
        if (!Files.exists(state)) return;
        try {
            String fb = null, output = null;
            for (String l : Files.readAllLines(state)) {
                if (l.startsWith("fb=")) fb = l.substring(3).trim();
                if (l.startsWith("output=")) output = l.substring(7).trim();
            }
            undo(output, fb != null && fb.matches("\\d+x\\d+") ? fb : null);
            Files.deleteIfExists(state);
            System.out.println("[split] removed the GamePad display left from an interrupted game");
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
            System.out.println("[split] needs an X11 session (on Wayland the TV shows both screens)");
            return null;
        }
        List<String> out = run(5, "xrandr");
        Matcher m = out.isEmpty() ? null : CURRENT.matcher(out.get(0));
        if (m == null || !m.find()) {
            System.out.println("[split] xrandr not available (install x11-xserver-utils); the TV shows both screens");
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
            System.out.println("[split] the X screen can't grow to " + needW + "x" + needH + " (maximum " + maxW + "x" + maxH + ")");
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
            System.out.println("[split] the X server did not accept an extra monitor; the TV shows both screens");
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
            System.out.println("[split] no output found for the TV: taps can't reach the GamePad display");
        }
        System.out.println("[split] GamePad display " + w + "x" + h + " at +" + right + "+0 (not shown on any screen)");
        return d;
    }

    // ---- Windows ----------------------------------------------------------------------------------

    // every display: position and size in real pixels, primary or not, and the adapter's name
    private static final String PS_DISPLAYS = """
            Add-Type @'
            using System; using System.Runtime.InteropServices;
            public class WiiuuDisp {
              [StructLayout(LayoutKind.Sequential, CharSet=CharSet.Unicode)] public struct DD { public int cb;
                [MarshalAs(UnmanagedType.ByValTStr, SizeConst=32)] public string Name;
                [MarshalAs(UnmanagedType.ByValTStr, SizeConst=128)] public string Str; public int Flags;
                [MarshalAs(UnmanagedType.ByValTStr, SizeConst=128)] public string Id;
                [MarshalAs(UnmanagedType.ByValTStr, SizeConst=128)] public string Key; }
              [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern bool EnumDisplayDevices(string d, int i, ref DD dd, int f);
              [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
              public static string Adapter(string name) {
                var dd = new DD(); dd.cb = Marshal.SizeOf(dd);
                for (int i = 0; EnumDisplayDevices(null, i, ref dd, 0); i++) { if (dd.Name == name) return dd.Str; dd.cb = Marshal.SizeOf(dd); }
                return "";
              }
            }
            '@
            [void][WiiuuDisp]::SetProcessDPIAware()
            Add-Type -AssemblyName System.Windows.Forms
            foreach ($s in [System.Windows.Forms.Screen]::AllScreens) {
              $b = $s.Bounds
              "$($b.X)`t$($b.Y)`t$($b.Width)`t$($b.Height)`t$(if ($s.Primary) {'primary'} else {''})`t$([WiiuuDisp]::Adapter($s.DeviceName))"
            }
            """;

    private static VirtualDisplay windows(Config config) {
        String pick = config.get("screen.split.display", "").trim();
        List<String> lines = run(10, "powershell", "-NoProfile", "-NonInteractive", "-Command", PS_DISPLAYS);
        int n = 0;
        for (String line : lines) {
            String[] p = line.split("\t", 6);
            if (p.length < 6) continue;
            n++;
            boolean primary = p[4].equals("primary");
            boolean virtual = p[5].toLowerCase(Locale.ROOT).matches(".*(virtual|idd|vdd|parsec|dummy|spacedesk).*");
            if (pick.isEmpty() ? virtual && !primary : pick.equals(Integer.toString(n))) {
                Rectangle r = new Rectangle(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
                System.out.println("[split] GamePad display: " + p[5].trim() + " " + r.width + "x" + r.height + " at " + r.x + "," + r.y);
                return new VirtualDisplay(r, null, null);
            }
        }
        System.out.println("[split] no virtual display found (install a virtual display driver, or set screen.split.display);"
                + " the TV shows both screens");
        return null;
    }

    // ---- helpers ----------------------------------------------------------------------------------

    /** Real screen pixels to Java's (scaled) units. */
    public static Rectangle toJava(Rectangle r) {
        double k = 1;
        try {
            k = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration().getDefaultTransform().getScaleX();
        } catch (RuntimeException ignored) {
            // unscaled
        }
        if (k == 1) return new Rectangle(r);
        return new Rectangle((int) Math.round(r.x / k), (int) Math.round(r.y / k), (int) Math.round(r.width / k), (int) Math.round(r.height / k));
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
