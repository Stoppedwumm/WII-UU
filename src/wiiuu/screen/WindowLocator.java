package wiiuu.screen;

import java.awt.Rectangle;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the on-screen rectangle of a window by title, using the platform's own tools
 * (xdotool / xwininfo on Linux, user32 via PowerShell on Windows, System Events on macOS).
 * Lookups spawn a process, so results are cached briefly.
 */
public final class WindowLocator {
    private static final long CACHE_MS = 1000;
    private static final String OS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

    // one window listing serves every search for a second (listing can take ~1 s on macOS)
    private List<Win> listing = List.of();
    private long listedAt;

    private synchronized List<Win> windowsNow() {
        long now = System.currentTimeMillis();
        if (now - listedAt >= CACHE_MS) {
            try {
                listing = list();
            } catch (RuntimeException e) {
                listing = List.of();
            }
            listedAt = System.currentTimeMillis();
        }
        return listing;
    }

    /** @return the largest visible window whose title matches {@code titleRegex}, or null */
    public Rectangle find(String titleRegex) {
        try {
            return largest(windowsNow(), Pattern.compile(titleRegex, Pattern.CASE_INSENSITIVE));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private record Win(String title, Rectangle rect) {}

    private static Rectangle largest(List<Win> wins, Pattern p) {
        Rectangle best = null;
        for (Win w : wins) {
            if (w.rect.width < 32 || w.rect.height < 32 || !p.matcher(w.title).find()) continue;
            if (best == null || (long) w.rect.width * w.rect.height > (long) best.width * best.height) best = w.rect;
        }
        return best;
    }

    private static List<Win> list() {
        if (OS.contains("win")) return windows();
        if (OS.contains("mac")) return mac();
        // X11 reports real pixels; WII-UU works in Java's scaled units (GDK_SCALE / sun.java2d.uiScale)
        double k = ScreenStreamer.scaleAt(new Rectangle(0, 0, 1, 1));
        List<Win> wins = x11();
        if (k == 1) return wins;
        List<Win> out = new ArrayList<>();
        for (Win w : wins) {
            Rectangle r = w.rect;
            out.add(new Win(w.title, new Rectangle((int) Math.round(r.x / k), (int) Math.round(r.y / k),
                    (int) Math.round(r.width / k), (int) Math.round(r.height / k))));
        }
        return out;
    }

    // ---- Linux / X11 -------------------------------------------------------------------

    private static final Pattern XWININFO = Pattern.compile(
            "^\\s*0x[0-9a-f]+ \"(.*)\":.*?\\s(\\d+)x(\\d+)[+-]-?\\d+[+-]-?\\d+\\s+\\+(-?\\d+)\\+(-?\\d+)\\s*$");

    private static List<Win> x11() {
        // xwininfo ships with x11-utils on most desktops and gives titles plus absolute positions in one call
        List<Win> out = new ArrayList<>();
        for (String line : run(3, "xwininfo", "-root", "-tree")) {
            Matcher m = XWININFO.matcher(line);
            if (m.matches()) {
                out.add(new Win(m.group(1), new Rectangle(Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)),
                        Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)))));
            }
        }
        if (!out.isEmpty()) return out;
        // fallback: xdotool
        for (String id : run(3, "xdotool", "search", "--onlyvisible", "--name", ".")) {
            String name = String.join(" ", run(2, "xdotool", "getwindowname", id.trim()));
            int x = 0, y = 0, w = 0, h = 0;
            for (String kv : run(2, "xdotool", "getwindowgeometry", "--shell", id.trim())) {
                String[] p = kv.split("=", 2);
                if (p.length != 2) continue;
                int v;
                try {
                    v = Integer.parseInt(p[1].trim());
                } catch (NumberFormatException e) {
                    continue;
                }
                switch (p[0]) {
                    case "X" -> x = v;
                    case "Y" -> y = v;
                    case "WIDTH" -> w = v;
                    case "HEIGHT" -> h = v;
                    default -> { }
                }
            }
            out.add(new Win(name, new Rectangle(x, y, w, h)));
        }
        return out;
    }

    // ---- Windows -----------------------------------------------------------------------

    private static final String PS_SCRIPT = """
            Add-Type @'
            using System; using System.Text; using System.Runtime.InteropServices;
            public class WiiuuWin {
              public delegate bool P(IntPtr h, IntPtr l);
              [DllImport("user32.dll")] public static extern bool EnumWindows(P f, IntPtr l);
              [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
              [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
              [DllImport("user32.dll")] public static extern bool GetClientRect(IntPtr h, out RECT r);
              [DllImport("user32.dll")] public static extern bool ClientToScreen(IntPtr h, ref POINT p);
              [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
              public struct RECT { public int L, T, R, B; }
              public struct POINT { public int X, Y; }
              public static void Dump() {
                SetProcessDPIAware();   // real pixels, whatever PowerShell's own DPI setting
                EnumWindows((h, l) => {
                  if (!IsWindowVisible(h)) return true;
                  var sb = new StringBuilder(512); GetWindowText(h, sb, 512);
                  if (sb.Length == 0) return true;
                  RECT r; GetClientRect(h, out r); var p = new POINT();
                  ClientToScreen(h, ref p);
                  Console.WriteLine(p.X + "\\t" + p.Y + "\\t" + (r.R - r.L) + "\\t" + (r.B - r.T) + "\\t" + sb);
                  return true; }, IntPtr.Zero);
              }
            }
            '@
            [WiiuuWin]::Dump()
            """;

    private static List<Win> windows() {
        // the script reports real pixels; WII-UU works in Java's scaled units (display scaling)
        double k = ScreenStreamer.scaleAt(new java.awt.Rectangle(0, 0, 1, 1));
        List<Win> out = new ArrayList<>();
        for (String line : run(8, "powershell", "-NoProfile", "-NonInteractive", "-Command", PS_SCRIPT)) {
            String[] p = line.split("\t", 5);
            if (p.length < 5) continue;
            try {
                out.add(new Win(p[4], new Rectangle((int) Math.round(Integer.parseInt(p[0]) / k), (int) Math.round(Integer.parseInt(p[1]) / k),
                        (int) Math.round(Integer.parseInt(p[2]) / k), (int) Math.round(Integer.parseInt(p[3]) / k))));
            } catch (NumberFormatException ignored) {
                // malformed line
            }
        }
        return out;
    }

    // ---- macOS -------------------------------------------------------------------------

    private static List<Win> mac() {
        String script = """
                set out to ""
                tell application "System Events"
                  repeat with p in (processes whose background only is false)
                    repeat with w in windows of p
                      try
                        set {x, y} to position of w
                        set {ww, hh} to size of w
                        set out to out & x & tab & y & tab & ww & tab & hh & tab & (name of w) & linefeed
                      end try
                    end repeat
                  end repeat
                end tell
                return out""";
        List<Win> out = new ArrayList<>();
        for (String line : run(8, "osascript", "-e", script)) {
            String[] p = line.split("\t", 5);
            if (p.length < 5) continue;
            try {
                out.add(new Win(p[4], new Rectangle(Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()),
                        Integer.parseInt(p[2].trim()), Integer.parseInt(p[3].trim()))));
            } catch (NumberFormatException ignored) {
                // malformed line
            }
        }
        return out;
    }

    private static List<String> run(int timeoutSec, String... cmd) {
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
