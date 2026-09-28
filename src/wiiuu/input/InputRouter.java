package wiiuu.input;

import java.awt.AWTException;
import java.awt.GraphicsEnvironment;
import java.awt.Robot;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import javax.swing.SwingUtilities;

/**
 * Routes gamepad input either to the menu (navigation) or, while an emulator runs,
 * into the OS as keyboard events via {@link Robot} so any emulator can consume it.
 */
public final class InputRouter {

    /** What the phone can do to the launcher menu. Called on the Swing thread. */
    public interface MenuActions {
        void navigate(int dx, int dy);

        void activate();

        void back();

        void page(int delta);

        void toggleGamepadInfo();

        void refresh();
    }

    private static final float STICK_ON = 0.5f;
    private static final float STICK_OFF = 0.35f;

    private final KeyMap keyMap;
    private final BooleanSupplier gameRunning;
    private final Robot robot;
    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "input-router");
        t.setDaemon(true);
        return t;
    });
    private volatile MenuActions menu;
    /** false while a game whose emulator reads the phone through DSU instead of the keyboard runs */
    private volatile BooleanSupplier keysEnabled = () -> true;

    // all state below is only touched on the exec thread
    private final Map<Integer, Set<PadButton>> held = new HashMap<>();
    private final Map<Integer, Integer> keyRefs = new HashMap<>();
    private final Map<Integer, ScheduledFuture<?>> repeats = new HashMap<>();
    private final Map<Integer, PadButton> repeatButton = new HashMap<>();
    private final Map<String, Boolean> stickState = new ConcurrentHashMap<>();
    private boolean lastModeGame;

    public InputRouter(KeyMap keyMap, BooleanSupplier gameRunning) {
        this.keyMap = keyMap;
        this.gameRunning = gameRunning;
        Robot r = null;
        if (!GraphicsEnvironment.isHeadless()) {
            try {
                r = new Robot();
                r.setAutoDelay(0);
            } catch (AWTException | SecurityException e) {
                System.err.println("[input] keyboard injection unavailable: " + e.getMessage());
            }
        }
        this.robot = r;
    }

    /** Which emulator's keyboard layout to use right now (see {@link KeyMap#profileFor}). */
    private volatile java.util.function.Supplier<String> keyProfile = () -> null;

    public void setKeyProfile(java.util.function.Supplier<String> profile) {
        this.keyProfile = profile;
    }

    public void setKeysEnabled(BooleanSupplier keysEnabled) {
        this.keysEnabled = keysEnabled;
    }

    public void setMenu(MenuActions menu) {
        this.menu = menu;
    }

    public boolean canInjectKeys() {
        return robot != null;
    }

    public void button(int player, PadButton b, boolean down) {
        exec.execute(() -> handle(player, b, down));
    }

    /** Analog stick update; stick 0 = left, 1 = right. Values in [-1, 1], +y is down. */
    public void stick(int player, int stick, float x, float y) {
        exec.execute(() -> {
            PadButton up = stick == 0 ? PadButton.LS_UP : PadButton.RS_UP;
            PadButton dn = stick == 0 ? PadButton.LS_DOWN : PadButton.RS_DOWN;
            PadButton lf = stick == 0 ? PadButton.LS_LEFT : PadButton.RS_LEFT;
            PadButton rt = stick == 0 ? PadButton.LS_RIGHT : PadButton.RS_RIGHT;
            axis(player, lf, x < 0 ? -x : 0);
            axis(player, rt, x > 0 ? x : 0);
            axis(player, up, y < 0 ? -y : 0);
            axis(player, dn, y > 0 ? y : 0);
        });
    }

    private void axis(int player, PadButton b, float v) {
        String k = player + ":" + b;
        boolean was = stickState.getOrDefault(k, false);
        boolean now = was ? v > STICK_OFF : v > STICK_ON;
        if (now != was) {
            stickState.put(k, now);
            handle(player, b, now);
        }
    }

    /** Lets go of everything a player holds (phone disconnected, game ended, ...). */
    public void releaseAll(int player) {
        exec.execute(() -> {
            Set<PadButton> s = held.get(player);
            if (s != null) for (PadButton b : java.util.List.copyOf(s)) handle(player, b, false);
            stickState.keySet().removeIf(k -> k.startsWith(player + ":"));
            stopRepeat(player);
        });
    }

    private void handle(int player, PadButton b, boolean down) {
        boolean game = gameRunning.getAsBoolean();
        if (game != lastModeGame) {
            // mode switched: drop whatever was held in the old mode
            releaseInjectedKeys();
            repeats.values().forEach(f -> f.cancel(false));
            repeats.clear();
            repeatButton.clear();
            held.clear();
            lastModeGame = game;
        }
        Set<PadButton> set = held.computeIfAbsent(player, p -> EnumSet.noneOf(PadButton.class));
        if (down == set.contains(b)) return; // duplicate edge
        if (down) set.add(b);
        else set.remove(b);

        if (game) injectKey(player, b, down);
        else menuInput(player, b, down);
    }

    // ---- game mode ----------------------------------------------------------------------

    private void injectKey(int player, PadButton b, boolean down) {
        if (robot == null || (down && !keysEnabled.getAsBoolean())) return;
        int code = keyMap.keyCode(player, b, keyProfile.get());
        if (code < 0) return;
        int refs = keyRefs.getOrDefault(code, 0);
        try {
            if (down) {
                if (refs == 0) robot.keyPress(code);
                keyRefs.put(code, refs + 1);
            } else if (refs > 0) {
                if (refs == 1) {
                    robot.keyRelease(code);
                    keyRefs.remove(code);
                } else {
                    keyRefs.put(code, refs - 1);
                }
            }
        } catch (IllegalArgumentException e) {
            System.err.println("[input] key " + KeyMap.nameOf(code) + " cannot be typed on this system");
        }
    }

    private void releaseInjectedKeys() {
        if (robot != null) {
            for (int code : keyRefs.keySet()) {
                try {
                    robot.keyRelease(code);
                } catch (IllegalArgumentException ignored) {
                    // key never pressed successfully
                }
            }
        }
        keyRefs.clear();
    }

    // ---- menu mode ----------------------------------------------------------------------

    private void menuInput(int player, PadButton b, boolean down) {
        MenuActions m = menu;
        if (m == null) return;
        if (b.isDirection()) {
            if (down) {
                fireDirection(m, b);
                stopRepeat(player);
                repeatButton.put(player, b);
                repeats.put(player, exec.scheduleAtFixedRate(() -> fireDirection(m, b), 380, 110, TimeUnit.MILLISECONDS));
            } else if (repeatButton.get(player) == b) {
                stopRepeat(player);
            }
            return;
        }
        if (!down) return;
        switch (b) {
            case A -> SwingUtilities.invokeLater(m::activate);
            case B -> SwingUtilities.invokeLater(m::back);
            case L, ZL -> SwingUtilities.invokeLater(() -> m.page(-1));
            case R, ZR -> SwingUtilities.invokeLater(() -> m.page(1));
            case PLUS -> SwingUtilities.invokeLater(m::toggleGamepadInfo);
            case MINUS -> SwingUtilities.invokeLater(m::refresh);
            default -> { }
        }
    }

    private void stopRepeat(int player) {
        ScheduledFuture<?> f = repeats.remove(player);
        if (f != null) f.cancel(false);
        repeatButton.remove(player);
    }

    private static void fireDirection(MenuActions m, PadButton b) {
        int dx = 0, dy = 0;
        switch (b) {
            case UP, LS_UP -> dy = -1;
            case DOWN, LS_DOWN -> dy = 1;
            case LEFT, LS_LEFT -> dx = -1;
            case RIGHT, LS_RIGHT -> dx = 1;
            default -> { return; }
        }
        int fx = dx, fy = dy;
        SwingUtilities.invokeLater(() -> m.navigate(fx, fy));
    }
}
