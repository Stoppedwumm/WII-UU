package wiiuu.input;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import wiiuu.core.Joysticks;

/**
 * Real virtual controllers on Linux: each phone GamePad becomes an Xbox 360 pad created through
 * /dev/uinput by a small Python helper ({@code vpad.py}), so emulators see analog sticks and
 * buttons natively - no key mapping and no dependence on which window has focus.
 *
 * <p>Buttons are mapped by position (Nintendo A = east, B = south, X = north, Y = west).
 *
 * <p>The helper also takes over real controllers when asked ({@link #watchControllers}): it grabs
 * each one (so emulators only see the virtual pads) and passes its input on, already as the
 * GamePad's buttons, for WII-UU to play it as a player of its own like a phone.
 */
public final class VirtualPads {
    private static final int BTN_SOUTH = 0x130, BTN_EAST = 0x131, BTN_NORTH = 0x133, BTN_WEST = 0x134;
    private static final int BTN_TL = 0x136, BTN_TR = 0x137, BTN_SELECT = 0x13a, BTN_START = 0x13b;
    private static final int BTN_MODE = 0x13c, BTN_THUMBL = 0x13d, BTN_THUMBR = 0x13e;
    private static final int ABS_X = 0, ABS_Y = 1, ABS_Z = 2, ABS_RX = 3, ABS_RY = 4, ABS_RZ = 5;
    private static final int ABS_HAT0X = 0x10, ABS_HAT0Y = 0x11;

    private Process helper;
    private Writer in;
    private volatile boolean usable;
    private volatile String problem = "not started";
    private final Set<Integer> created = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<Integer, CompletableFuture<Boolean>> pending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Set<PadButton>> held = new ConcurrentHashMap<>();
    private volatile Controllers controllers;

    /** Real controllers the helper took over: when they come and go, and their input. */
    public interface Controllers {
        void connected(String id, String name);

        void disconnected(String id);

        /** One line as a phone sends it: "b BUTTON 0|1" or "s STICK X Y". */
        void input(String id, String line);
    }

    /** Takes over real controllers from now on (also ones plugged in later), telling {@code to} about them. */
    public void watchControllers(Controllers to) {
        if (!usable || controllers != null) return;
        controllers = to;
        send("watch");
    }

    /** Starts the helper and creates pad 1 to check it works. */
    public boolean start(Path home) {
        String fake = System.getProperty("wiiuu.vpad");          // for tests: a stand-in helper
        if (fake != null) return start(new ProcessBuilder("python3", fake));
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux")) {
            problem = "virtual controllers are Linux-only";
            return false;
        }
        if (!Files.isWritable(Path.of("/dev/uinput"))) {
            problem = Files.exists(Path.of("/dev/uinput"))
                    ? "no permission for /dev/uinput (re-run install.sh, or log out and in once)"
                    : "/dev/uinput missing (sudo modprobe uinput)";
            return false;
        }
        Path script = home.resolve("vpad.py");
        try {
            try (InputStream res = VirtualPads.class.getResourceAsStream("/vpad.py")) {
                if (res == null) throw new IOException("vpad.py missing from jar");
                Files.createDirectories(home);
                Files.copy(res, script, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            problem = "vpad.py: " + e.getMessage();
            return false;
        }
        return start(new ProcessBuilder("python3", script.toString()));
    }

    private boolean start(ProcessBuilder command) {
        try {
            helper = command.redirectErrorStream(true).start();
            in = new OutputStreamWriter(helper.getOutputStream(), StandardCharsets.UTF_8);
            Thread reader = new Thread(this::readReplies, "vpad-replies");
            reader.setDaemon(true);
            reader.start();
        } catch (IOException e) {
            problem = "python3 not available: " + e.getMessage();
            return false;
        }
        usable = true;
        if (!ensure(1)) {
            usable = false;
            helper.destroy();
            return false;
        }
        problem = null;
        return true;
    }

    public boolean usable() {
        return usable;
    }

    public String problem() {
        return problem;
    }

    public void stop() {
        usable = false;
        if (helper != null) helper.destroy();
    }

    private void readReplies() {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(helper.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (controllerLine(line)) continue;
                String[] p = line.split(" ", 3);
                if (p.length < 2) continue;
                try {
                    int n = Integer.parseInt(p[1]);
                    CompletableFuture<Boolean> f = pending.remove(n);
                    if (p[0].equals("ok")) created.add(n);
                    else problem = p.length > 2 ? p[2] : line;
                    if (f != null) f.complete(p[0].equals("ok"));
                } catch (NumberFormatException ignored) {
                    // not a reply line (e.g. a Python traceback)
                    problem = line;
                }
            }
        } catch (IOException ignored) {
            // helper exited
        }
        usable = false;
    }

    /** A line about a real controller; false when it's something else. */
    boolean controllerLine(String line) {
        Controllers c = controllers;
        String[] p = line.split(" ", 3);
        if (p.length < 2) return false;
        switch (p[0]) {
            case "pad+" -> {
                Joysticks.claim(p[1], true);
                if (c != null) c.connected(p[1], p.length > 2 ? p[2] : "Controller");
            }
            case "pad-" -> {
                Joysticks.claim(p[1], false);
                if (c != null) c.disconnected(p[1]);
            }
            case "in" -> {
                if (c != null && p.length > 2) c.input(p[1], p[2]);
            }
            case "padfail" -> System.err.println("[input] can't take over controller " + (p.length > 2 ? p[1] + " " + p[2] : p[1]));
            default -> {
                return false;
            }
        }
        return true;
    }

    /** Creates pad {@code n} the first time it is used. */
    public boolean ensure(int n) {
        if (created.contains(n)) return true;
        if (!usable) return false;
        CompletableFuture<Boolean> f = pending.computeIfAbsent(n, k -> new CompletableFuture<>());
        send("create " + n);
        try {
            return f.get(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            return false;
        }
    }

    private synchronized void send(String lines) {
        try {
            in.write(lines);
            in.write('\n');
            in.flush();
        } catch (IOException e) {
            usable = false;
        }
    }

    // ---- input from the phones ----------------------------------------------------------

    public void button(int player, PadButton b, boolean down) {
        if (!usable || player < 1 || !ensure(player)) return;
        Set<PadButton> set = held.computeIfAbsent(player, k -> EnumSet.noneOf(PadButton.class));
        synchronized (set) {
            if (down) set.add(b);
            else set.remove(b);
        }
        StringBuilder sb = new StringBuilder();
        switch (b) {
            case UP, DOWN, LEFT, RIGHT -> {
                int hx, hy;
                synchronized (set) {
                    hx = (set.contains(PadButton.RIGHT) ? 1 : 0) - (set.contains(PadButton.LEFT) ? 1 : 0);
                    hy = (set.contains(PadButton.DOWN) ? 1 : 0) - (set.contains(PadButton.UP) ? 1 : 0);
                }
                sb.append("abs ").append(player).append(' ').append(ABS_HAT0X).append(' ').append(hx).append('\n');
                sb.append("abs ").append(player).append(' ').append(ABS_HAT0Y).append(' ').append(hy).append('\n');
            }
            case ZL -> sb.append("abs ").append(player).append(' ').append(ABS_Z).append(' ').append(down ? 255 : 0).append('\n');
            case ZR -> sb.append("abs ").append(player).append(' ').append(ABS_RZ).append(' ').append(down ? 255 : 0).append('\n');
            default -> {
                int code = switch (b) {
                    case A -> BTN_EAST;
                    case B -> BTN_SOUTH;
                    case X -> BTN_NORTH;
                    case Y -> BTN_WEST;
                    case L -> BTN_TL;
                    case R -> BTN_TR;
                    case MINUS -> BTN_SELECT;
                    case PLUS -> BTN_START;
                    case HOME -> BTN_MODE;
                    case L3 -> BTN_THUMBL;
                    case R3 -> BTN_THUMBR;
                    default -> -1;          // stick directions arrive as analog axes instead
                };
                if (code < 0) return;
                sb.append("key ").append(player).append(' ').append(code).append(' ').append(down ? 1 : 0).append('\n');
            }
        }
        sb.append("syn ").append(player);
        send(sb.toString());
    }

    /** stick 0 = left, 1 = right; x/y in -1..1 with +y = down (same as evdev). */
    public void stick(int player, int stick, float x, float y) {
        if (!usable || player < 1 || !ensure(player)) return;
        int ax = stick == 0 ? ABS_X : ABS_RX, ay = stick == 0 ? ABS_Y : ABS_RY;
        send("abs " + player + " " + ax + " " + axis(x) + "\nabs " + player + " " + ay + " " + axis(y)
                + "\nsyn " + player);
    }

    /** Centres sticks and releases everything (phone disconnected). */
    public void release(int player) {
        if (!usable || !created.contains(player)) return;
        Set<PadButton> set = held.remove(player);
        if (set != null) {
            for (PadButton b : set.toArray(new PadButton[0])) button(player, b, false);
        }
        stick(player, 0, 0, 0);
        stick(player, 1, 0, 0);
    }

    private static int axis(float v) {
        return Math.round(Math.max(-1f, Math.min(1f, v)) * 32767);
    }
}
