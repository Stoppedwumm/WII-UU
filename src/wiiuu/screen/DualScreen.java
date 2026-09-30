package wiiuu.screen;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.swing.JComponent;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;

import wiiuu.core.Config;
import wiiuu.core.Game;

/**
 * Split screens, like a DS on a Wii U: the top screen big on the TV, the bottom (touch) screen on
 * the phone. For DS and 3DS games in RetroArch mode.
 *
 * <p>RetroArch draws both screens into one window. That window goes onto a {@link VirtualDisplay}
 * the TV doesn't show, sized to the two screens exactly; WII-UU then covers the TV with a window of
 * its own that shows the top screen live, and the phone streams (and taps) the bottom one from the
 * hidden display. {@code screen.split=false} turns it off.
 */
public final class DualScreen {
    private static final String OS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

    private final Config config;
    private final WindowLocator windows = new WindowLocator();
    private volatile VirtualDisplay display;
    private volatile ScreenProfile profile;
    private volatile Rectangle target;             // where the emulator window belongs, real pixels
    private volatile boolean running;
    private Thread keeper;
    private Presenter presenter;
    private volatile Rectangle tvBounds;           // the TV, Java's units, taken before the displays change

    public DualScreen(Config config) {
        this.config = config;
    }

    /**
     * Before a launch: whether this game's screens are split, and if so makes the hidden display.
     *
     * @return where the emulator's window should go (real pixels), or null for the usual single window
     */
    /** Why the last game that could have split screens didn't, or null. */
    private volatile String problem;

    public String problem() {
        return problem;
    }

    public synchronized Rectangle prepare(Game game, Rectangle tv) {
        end();
        problem = null;
        tvBounds = tv;
        if (!config.getBool("screen.split", true)) return null;
        ScreenProfile p = ScreenProfile.forSystem(config, game.system(), true);
        if (p == null || p.aspect() <= 0 || p.ry() <= 0) return null;
        int h = Math.max(384, config.getInt("screen.split.height", 1152));
        int w = (int) Math.round(h * p.aspect()) & ~1;
        VirtualDisplay d = VirtualDisplay.open(config, w, h);
        if (d == null) {
            problem = VirtualDisplay.problem();
            return null;
        }
        display = d;
        if (d.tvArea() != null) tvBounds = VirtualDisplay.toJava(d.tvArea());
        profile = p;
        target = fit(d.area(), p.aspect());
        return new Rectangle(target);
    }

    /** After the emulator started: keeps its window on the hidden display, shows the top screen on the TV. */
    public synchronized void begin() {
        if (display == null || running) return;
        Rectangle tv = tvBounds;
        if (tv == null) {
            VirtualDisplay.note("don't know where the TV is; the TV shows both screens");
            return;
        }
        running = true;
        VirtualDisplay.note("TV " + tv + " (Java units), RetroArch's window goes to " + target + " (real pixels)");
        Thread snap = new Thread(this::snapshot, "split-snapshot");
        snap.setDaemon(true);
        snap.start();
        keeper = new Thread(this::keepWindow, "split-window");
        keeper.setDaemon(true);
        keeper.start();
        presenter = new Presenter(tv);
    }

    /** Game over: the TV window goes, the hidden display goes. */
    public synchronized void end() {
        running = false;
        if (keeper != null) keeper.interrupt();
        keeper = null;
        if (presenter != null) presenter.close();
        presenter = null;
        if (display != null) display.close();
        display = null;
        profile = null;
        target = null;
    }

    public boolean active() {
        return running;
    }

    /** The hidden display in Java's units (so capture and the mouse may go there), or null. */
    public Rectangle hiddenArea() {
        VirtualDisplay d = display;
        return d == null ? null : VirtualDisplay.toJava(d.area());
    }

    /** The emulator's top screen right now, in Java's units, or null. */
    public Rectangle topRegion() {
        ScreenProfile p = profile;
        Rectangle win = window();
        if (p == null || win == null) return null;
        Rectangle all = new ScreenProfile(p.label(), p.windowRegex(), p.aspect(), 0, 0, 1, 1).locate(win);
        return new Rectangle(all.x, all.y, all.width, (int) Math.round(all.height * p.ry()) & ~1);
    }

    private Rectangle window() {
        ScreenProfile p = profile;
        return p == null ? null : windows.find(p.windowRegex());
    }

    /** The screen that holds the middle of {@code tv}, as Java sees it now. */
    static java.awt.GraphicsConfiguration tvConfig(Rectangle tv) {
        try {
            for (var dev : java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
                var gc = dev.getDefaultConfiguration();
                if (gc.getBounds().contains(tv.getCenterX(), tv.getCenterY())) return gc;
            }
            return java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The largest rectangle of {@code aspect} inside {@code area}, centred. */
    static Rectangle fit(Rectangle area, double aspect) {
        int w = area.width, h = area.height;
        if (w / (double) h > aspect) w = (int) Math.round(h * aspect) & ~1;
        else h = (int) Math.round(w / aspect) & ~1;
        return new Rectangle(area.x + (area.width - w) / 2, area.y + (area.height - h) / 2, w, h);
    }

    // ---- keeping the window in place -------------------------------------------------------------

    /** RetroArch may open its window elsewhere, or make it anew when the core starts: put it back. */
    private int moves;

    private void keepWindow() {
        boolean focused = false;
        moves = 0;
        while (running) {
            Rectangle want = target, now = window();
            if (want != null && now != null) {
                Rectangle real = ScreenStreamer.devicePixels(now);
                if (Math.abs(real.x - want.x) > 2 || Math.abs(real.y - want.y) > 2
                        || Math.abs(real.width - want.width) > 2 || Math.abs(real.height - want.height) > 2) {
                    if (moves++ < 5) VirtualDisplay.note("RetroArch's window is at " + real + " (real pixels): moving it");
                    place(profile.windowRegex(), want);
                    focused = false;
                } else if (!focused) {
                    focus(profile.windowRegex());               // keys typed for the phone go to the focused window
                    focused = true;
                }
            }
            try {
                Thread.sleep(now == null ? 400 : 1500);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /** A picture of the whole desktop a few seconds in (logs/split.jpg): where everything ended up. */
    private void snapshot() {
        try {
            Thread.sleep(7000);
        } catch (InterruptedException e) {
            return;
        }
        if (!running) return;
        String ffmpeg = ScreenStreamer.findFfmpeg(config.get("stream.ffmpeg", "ffmpeg"));
        if (ffmpeg == null) return;
        java.nio.file.Path out = config.logDir().resolve("split.jpg");
        String display = System.getenv("DISPLAY");
        List<String> cmd = OS.contains("win")
                ? List.of(ffmpeg, "-y", "-loglevel", "error", "-f", "gdigrab", "-i", "desktop", "-frames:v", "1", "-vf", "scale='min(2400,iw)':-2", out.toString())
                : List.of(ffmpeg, "-y", "-loglevel", "error", "-f", "x11grab", "-i", display == null ? ":0" : display, "-frames:v", "1", "-vf", "scale='min(2400,iw)':-2", out.toString());
        List<String> res = VirtualDisplay.run(20, cmd.toArray(new String[0]));
        Rectangle top = topRegion();
        VirtualDisplay.note("desktop picture: " + out + (res.isEmpty() ? "" : " (" + String.join(" ", res) + ")")
                + "; RetroArch's window " + window() + ", top screen " + top + " (Java units)");
        if (window() == null) VirtualDisplay.note("no window matched \"" + profile.windowRegex() + "\"; windows now: " + windows.titles());
    }

    private static void place(String regex, Rectangle r) {
        if (OS.contains("win")) {
            VirtualDisplay.run(8, "powershell", "-NoProfile", "-NonInteractive", "-Command", PS_PLACE
                    .replace("%RE%", regex.replace("'", "''")).replace("%X%", "" + r.x).replace("%Y%", "" + r.y)
                    .replace("%W%", "" + r.width).replace("%H%", "" + r.height));
            return;
        }
        for (String id : VirtualDisplay.run(3, "xdotool", "search", "--name", regex)) {
            id = id.trim();
            if (id.isEmpty()) continue;
            VirtualDisplay.run(3, "xdotool", "windowsize", id, "" + r.width, "" + r.height, "windowmove", id, "" + r.x, "" + r.y);
        }
    }

    private static void focus(String regex) {
        if (OS.contains("win")) return;                                  // PS_PLACE already brought it to the front
        List<String> ids = VirtualDisplay.run(3, "xdotool", "search", "--name", regex);
        if (!ids.isEmpty()) {
            String id = ids.get(ids.size() - 1).trim();
            VirtualDisplay.run(3, "xdotool", "windowactivate", id);
            VirtualDisplay.run(3, "xdotool", "windowfocus", id);
        }
    }

    private static final String PS_PLACE = """
            Add-Type @'
            using System; using System.Text; using System.Runtime.InteropServices; using System.Text.RegularExpressions;
            public class WiiuuPlace {
              public delegate bool P(IntPtr h, IntPtr l);
              [DllImport("user32.dll")] public static extern bool EnumWindows(P f, IntPtr l);
              [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
              [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
              [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr h, IntPtr a, int x, int y, int w, int hh, uint f);
              [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
              [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int c);
              [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
              [DllImport("user32.dll")] public static extern void keybd_event(byte k, byte s, uint f, UIntPtr e);
              [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
              static string Exe(IntPtr h) {
                uint pid; GetWindowThreadProcessId(h, out pid);
                try { return System.Diagnostics.Process.GetProcessById((int) pid).ProcessName; } catch { return ""; }
              }
              public static void Go(string re, int x, int y, int w, int hh) {
                SetProcessDPIAware();
                EnumWindows((h, l) => {
                  if (!IsWindowVisible(h)) return true;
                  var sb = new StringBuilder(512); GetWindowText(h, sb, 512);
                  if (!Regex.IsMatch(sb.ToString() + " [" + Exe(h) + "]", re, RegexOptions.IgnoreCase)) return true;
                  ShowWindow(h, 9); SetWindowPos(h, IntPtr.Zero, x, y, w, hh, 0x0040);
                  // Windows only lets the foreground app hand over the focus; a tap of Alt counts as one
                  keybd_event(0x12, 0, 0, UIntPtr.Zero); keybd_event(0x12, 0, 2, UIntPtr.Zero);
                  SetForegroundWindow(h);
                  return true; }, IntPtr.Zero);
              }
            }
            '@
            [WiiuuPlace]::Go('%RE%', %X%, %Y%, %W%, %H%)
            """;

    // ---- the TV: the top screen, live ------------------------------------------------------------

    /** A window over the whole TV showing the top screen, captured from the hidden display. */
    private final class Presenter {
        private final JWindow window;
        private final Pic pic = new Pic();
        private final Thread capture;
        private volatile boolean closed;
        private volatile Process process;

        Presenter(Rectangle tv) {
            JWindow[] w = new JWindow[1];
            Rectangle[] shown = {tv};
            try {
                Runnable make = () -> {
                    // on the TV's own screen, sized as Java sees that screen now: switching a display on can
                    // change the scaling Java applies to a window made elsewhere (the black square on Windows)
                    java.awt.GraphicsConfiguration gc = tvConfig(tv);
                    Rectangle bounds = gc != null ? gc.getBounds() : tv;
                    JWindow jw = gc != null ? new JWindow(gc) : new JWindow();
                    jw.setFocusableWindowState(false);          // the emulator keeps the keyboard
                    jw.setAlwaysOnTop(true);
                    jw.setBackground(Color.BLACK);
                    jw.setContentPane(pic);
                    jw.setCursor(Toolkit.getDefaultToolkit().createCustomCursor(
                            new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), new Point(), "none"));
                    jw.setBounds(bounds);
                    shown[0] = bounds;
                    jw.setVisible(true);
                    w[0] = jw;
                };
                if (SwingUtilities.isEventDispatchThread()) make.run();
                else SwingUtilities.invokeAndWait(make);
            } catch (Exception e) {
                System.err.println("[split] TV window: " + e.getMessage());
            }
            window = w[0];
            VirtualDisplay.note("TV window " + (window == null ? "could not be made" : "at " + shown[0] + " (Java units)"));
            capture = new Thread(this::captureLoop, "split-tv");
            capture.setDaemon(true);
            capture.start();
        }

        private int starts;

        void close() {
            closed = true;
            capture.interrupt();
            Process p = process;
            if (p != null) p.destroyForcibly();
            if (window != null) SwingUtilities.invokeLater(window::dispose);
        }

        private void captureLoop() {
            String ffmpeg = ScreenStreamer.findFfmpeg(config.get("stream.ffmpeg", "ffmpeg"));
            while (!closed) {
                Rectangle top = topRegion();
                if (top == null || top.width < 16 || top.height < 16) {
                    sleep(300);
                    continue;
                }
                if (ffmpeg != null) ffmpegLoop(ffmpeg, top);
                else robotLoop(top);
            }
        }

        /** Raw frames from ffmpeg, until the region moves or the game ends. */
        private void ffmpegLoop(String ffmpeg, Rectangle top) {
            Rectangle d = ScreenStreamer.devicePixels(top);
            if (starts++ < 5) VirtualDisplay.note("TV window captures " + d + " (real pixels) with ffmpeg");
            int fps = Math.max(10, config.getInt("screen.split.fps", 60));
            List<String> cmd = new ArrayList<>(List.of(ffmpeg, "-hide_banner", "-loglevel", "error", "-fflags", "nobuffer"));
            if (OS.contains("win")) {
                cmd.addAll(List.of("-f", "gdigrab", "-draw_mouse", "0", "-framerate", "" + fps, "-offset_x", "" + d.x,
                        "-offset_y", "" + d.y, "-video_size", d.width + "x" + d.height, "-i", "desktop"));
            } else {
                String display = System.getenv("DISPLAY");
                cmd.addAll(List.of("-f", "x11grab", "-draw_mouse", "0", "-framerate", "" + fps, "-video_size", d.width + "x" + d.height,
                        "-i", (display == null ? ":0" : display) + "+" + d.x + "," + d.y));
            }
            cmd.addAll(List.of("-an", "-f", "rawvideo", "-pix_fmt", "bgr24", "-"));
            try {
                java.nio.file.Path err = config.logDir().resolve("split-capture.log");
                Process p = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.appendTo(err.toFile())).start();
                process = p;
                long checked = System.currentTimeMillis();
                try (DataInputStream in = new DataInputStream(p.getInputStream())) {
                    while (!closed) {
                        BufferedImage img = pic.spare(d.width, d.height);
                        in.readFully(((DataBufferByte) img.getRaster().getDataBuffer()).getData());
                        pic.show(img);
                        if (System.currentTimeMillis() - checked > 1000) {
                            checked = System.currentTimeMillis();
                            if (!top.equals(topRegion())) break;      // moved: capture the new place
                        }
                    }
                } finally {
                    p.destroyForcibly();
                }
            } catch (IOException e) {
                if (!closed) sleep(300);
            }
        }

        /** Without ffmpeg: Java's own capture (slower). */
        private void robotLoop(Rectangle top) {
            try {
                Robot robot = new Robot();
                long checked = System.currentTimeMillis();
                while (!closed) {
                    BufferedImage shot = robot.createScreenCapture(top);
                    pic.show(shot);
                    if (System.currentTimeMillis() - checked > 1000) {
                        checked = System.currentTimeMillis();
                        if (!top.equals(topRegion())) return;
                    }
                    sleep(15);
                }
            } catch (Exception e) {
                sleep(500);
            }
        }

        private void sleep(long ms) {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Draws the newest picture, as large as fits, centred on black. */
    private static final class Pic extends JComponent {
        private BufferedImage front, back;

        synchronized BufferedImage spare(int w, int h) {
            if (back == null || back.getWidth() != w || back.getHeight() != h) back = new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR);
            return back;
        }

        void show(BufferedImage img) {
            synchronized (this) {
                if (img == back) {
                    back = front;
                    front = img;
                } else {
                    front = img;
                }
            }
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0;
            g.setColor(Color.BLACK);
            g.fillRect(0, 0, getWidth(), getHeight());
            synchronized (this) {
                if (front == null) return;
                double k = Math.min(getWidth() / (double) front.getWidth(), getHeight() / (double) front.getHeight());
                int w = (int) Math.round(front.getWidth() * k), h = (int) Math.round(front.getHeight() * k);
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(front, (getWidth() - w) / 2, (getHeight() - h) / 2, w, h, null);
            }
        }
    }
}
