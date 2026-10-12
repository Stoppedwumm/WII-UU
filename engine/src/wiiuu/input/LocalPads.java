package wiiuu.input;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import wiiuu.core.Joysticks;

/**
 * Real controllers (USB / Bluetooth pads) in WII-UU's own menu, on Linux: Java has no gamepad API,
 * so each controller's input devices are read directly. Buttons come from its event device
 * (/dev/input/eventN), where every button has a standard code by position (BTN_SOUTH, BTN_EAST,
 * ...); the left stick from its joystick device (/dev/input/jsN), which the kernel scales to
 * ±32767 whatever the pad. Both are readable by the logged-in user (udev's "uaccess").
 *
 * <p>They drive the menu through {@link InputRouter} like a phone does, as players -1, -2, ...,
 * which the router ignores while a game runs (the emulator reads the controller itself). The
 * phones' own virtual controllers are skipped: the phones already drive the menu.
 *
 * <p>The bottom button confirms and the right one goes back, as on most controllers;
 * input.padConfirm=east swaps them (Nintendo style). Shoulders and triggers change the page,
 * Start shows the GamePad info, Select refreshes. The top and left buttons are X and Y (as on the
 * GamePad), which only the setup guide's controller test shows.
 */
public final class LocalPads {
    private static final int EV_KEY = 1, EV_ABS = 3;
    private static final int BTN_SOUTH = 0x130, BTN_EAST = 0x131, BTN_NORTH = 0x133, BTN_WEST = 0x134, BTN_TL = 0x136, BTN_TR = 0x137;
    private static final int BTN_TL2 = 0x138, BTN_TR2 = 0x139, BTN_SELECT = 0x13a, BTN_START = 0x13b;
    private static final int BTN_DPAD_UP = 0x220, BTN_DPAD_DOWN = 0x221, BTN_DPAD_LEFT = 0x222, BTN_DPAD_RIGHT = 0x223;
    private static final int ABS_HAT0X = 0x10, ABS_HAT0Y = 0x11;
    /** struct input_event: two longs of time, then u16 type, u16 code, s32 value */
    private static final int EVENT_SIZE = System.getProperty("os.arch", "").contains("64") ? 24 : 16;

    private final InputRouter router;
    private final boolean confirmEast;
    /** device nodes being read now (event5, js0, ...) */
    private final Set<String> open = ConcurrentHashMap.newKeySet();
    /** players handed out per controller (by its event node), -1, -2, ... */
    private final ConcurrentHashMap<String, Integer> players = new ConcurrentHashMap<>();

    /** controller names by player (-1, -2, ...), for the setup guide's controller test */
    private static final ConcurrentHashMap<Integer, String> NAMES = new ConcurrentHashMap<>();

    /** The name of the real controller playing as {@code player} (below 0), or null. */
    public static String name(int player) {
        return NAMES.get(player);
    }

    /** The names of the real controllers connected now. */
    public static java.util.List<String> names() {
        return NAMES.values().stream().sorted().toList();
    }

    public LocalPads(InputRouter router, boolean confirmEast) {
        this.router = router;
        this.confirmEast = confirmEast;
    }

    /** Watches for controllers (also ones plugged in later). Linux only; elsewhere does nothing. */
    public void start() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux")) return;
        Thread t = new Thread(() -> {
            while (true) {
                for (Joysticks.Device d : Joysticks.list()) {
                    if (d.ours() || d.event() == null) continue;
                    int player = players.computeIfAbsent(d.event(), k -> -(players.size() + 1));
                    if (open.add(d.event())) start("event-" + d.event(), () -> readEvents(d, player));
                    if (open.add(d.js())) start("js-" + d.js(), () -> readSticks(d, player));
                }
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "local-pads");
        t.setDaemon(true);
        t.start();
    }

    private void start(String name, Runnable reader) {
        Thread t = new Thread(reader, "pad-" + name);
        t.setDaemon(true);
        t.start();
    }

    /** Buttons and the d-pad, from the event device. */
    private void readEvents(Joysticks.Device d, int player) {
        Path node = Path.of("/dev/input", d.event());
        try (InputStream in = Files.newInputStream(node)) {
            System.out.println("[input] " + d.name() + " controls the menu");
            NAMES.put(player, d.name());
            pumpEvents(in, player, EVENT_SIZE);
        } catch (IOException e) {
            if (Files.exists(node)) System.err.println("[input] can't read " + d.name() + " (" + node + "): " + e.getMessage());
        } finally {
            router.releaseAll(player);
            NAMES.remove(player);
            open.remove(d.event());
        }
    }

    /** Reads input_events ({@code size} bytes each) until the stream ends. */
    void pumpEvents(InputStream in, int player, int size) throws IOException {
        final int EVENT_SIZE = size;
        {
            byte[] buf = new byte[EVENT_SIZE];
            ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
            int hatX = 0, hatY = 0;
            while (in.readNBytes(buf, 0, EVENT_SIZE) == EVENT_SIZE) {
                int type = bb.getShort(EVENT_SIZE - 8) & 0xffff, code = bb.getShort(EVENT_SIZE - 6) & 0xffff;
                int value = bb.getInt(EVENT_SIZE - 4);
                if (type == EV_KEY && value != 2) {                    // 2 = auto-repeat; the router repeats itself
                    PadButton b = button(code);
                    if (b != null) router.button(player, b, value != 0);
                } else if (type == EV_ABS && code == ABS_HAT0X) {
                    hatX = hat(player, hatX, Integer.signum(value), PadButton.LEFT, PadButton.RIGHT);
                } else if (type == EV_ABS && code == ABS_HAT0Y) {
                    hatY = hat(player, hatY, Integer.signum(value), PadButton.UP, PadButton.DOWN);
                }
            }
        }
    }

    /** The left stick, from the joystick device (axes 0 and 1, already scaled to ±32767). */
    private void readSticks(Joysticks.Device d, int player) {
        Path node = Path.of("/dev/input", d.js());
        try (InputStream in = Files.newInputStream(node)) {
            pumpSticks(in, player);
        } catch (IOException e) {
            // unplugged, or no permission (the event device says so)
        } finally {
            open.remove(d.js());
        }
    }

    /** Reads js_events (8 bytes each) until the stream ends. */
    void pumpSticks(InputStream in, int player) throws IOException {
        {
            byte[] buf = new byte[8];
            ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
            float x = 0, y = 0;
            while (in.readNBytes(buf, 0, 8) == 8) {
                int value = bb.getShort(4), type = buf[6] & 0x7f, number = buf[7] & 0xff;   // 0x80: initial state
                if (type != 2 || number > 1) continue;
                if (number == 0) x = value / 32767f;
                else y = value / 32767f;
                router.stick(player, 0, x, y);
            }
        }
    }

    private int hat(int player, int was, int now, PadButton minus, PadButton plus) {
        if (now == was) return was;
        if (was < 0) router.button(player, minus, false);
        if (was > 0) router.button(player, plus, false);
        if (now < 0) router.button(player, minus, true);
        if (now > 0) router.button(player, plus, true);
        return now;
    }

    /** A controller button as the phone's button it stands for in the menu, or null. */
    private PadButton button(int code) {
        return switch (code) {
            case BTN_SOUTH -> confirmEast ? PadButton.B : PadButton.A;
            case BTN_EAST -> confirmEast ? PadButton.A : PadButton.B;
            case BTN_NORTH -> PadButton.X;      // by position, as on the GamePad; nothing in the menu, the guide shows them
            case BTN_WEST -> PadButton.Y;
            case BTN_TL, BTN_TL2 -> PadButton.L;
            case BTN_TR, BTN_TR2 -> PadButton.R;
            case BTN_START -> PadButton.PLUS;
            case BTN_SELECT -> PadButton.MINUS;
            case BTN_DPAD_UP -> PadButton.UP;
            case BTN_DPAD_DOWN -> PadButton.DOWN;
            case BTN_DPAD_LEFT -> PadButton.LEFT;
            case BTN_DPAD_RIGHT -> PadButton.RIGHT;
            default -> null;
        };
    }
}
