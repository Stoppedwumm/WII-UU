package wiiuu.input;

import java.awt.event.KeyEvent;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import wiiuu.core.Config;

/**
 * Maps gamepad buttons to keyboard keys per player. Emulators are then configured to
 * listen to those keys. Stored as keys.p1.A=X etc. using java.awt.event.KeyEvent names.
 */
public final class KeyMap {
    public static final int MAX_PLAYERS = 4;

    private static final Map<String, Integer> NAMES = new TreeMap<>();
    private static final Map<Integer, String> CODES = new java.util.HashMap<>();

    static {
        for (Field f : KeyEvent.class.getFields()) {
            if (f.getName().startsWith("VK_") && Modifier.isStatic(f.getModifiers()) && f.getType() == int.class) {
                try {
                    String n = f.getName().substring(3);
                    int code = f.getInt(null);
                    NAMES.put(n, code);
                    CODES.putIfAbsent(code, n);
                } catch (IllegalAccessException ignored) {
                    // not reachable for public fields
                }
            }
        }
    }

    private static final Map<PadButton, String> P1 = new EnumMap<>(Map.ofEntries(
            Map.entry(PadButton.UP, "UP"), Map.entry(PadButton.DOWN, "DOWN"),
            Map.entry(PadButton.LEFT, "LEFT"), Map.entry(PadButton.RIGHT, "RIGHT"),
            Map.entry(PadButton.A, "X"), Map.entry(PadButton.B, "Z"),
            Map.entry(PadButton.X, "S"), Map.entry(PadButton.Y, "A"),
            Map.entry(PadButton.L, "Q"), Map.entry(PadButton.R, "W"),
            Map.entry(PadButton.ZL, "E"), Map.entry(PadButton.ZR, "R"),
            Map.entry(PadButton.PLUS, "ENTER"), Map.entry(PadButton.MINUS, "SHIFT"),
            Map.entry(PadButton.L3, "C"), Map.entry(PadButton.R3, "V"),
            Map.entry(PadButton.LS_UP, "T"), Map.entry(PadButton.LS_DOWN, "G"),
            Map.entry(PadButton.LS_LEFT, "F"), Map.entry(PadButton.LS_RIGHT, "H"),
            Map.entry(PadButton.RS_UP, "I"), Map.entry(PadButton.RS_DOWN, "K"),
            Map.entry(PadButton.RS_LEFT, "J"), Map.entry(PadButton.RS_RIGHT, "L")));

    // digits/letters rather than the numpad, so Player 2 works regardless of NumLock
    private static final Map<PadButton, String> P2 = new EnumMap<>(Map.ofEntries(
            Map.entry(PadButton.UP, "8"), Map.entry(PadButton.DOWN, "5"),
            Map.entry(PadButton.LEFT, "4"), Map.entry(PadButton.RIGHT, "6"),
            Map.entry(PadButton.A, "3"), Map.entry(PadButton.B, "2"),
            Map.entry(PadButton.X, "9"), Map.entry(PadButton.Y, "1"),
            Map.entry(PadButton.L, "7"), Map.entry(PadButton.R, "0"),
            Map.entry(PadButton.ZL, "O"), Map.entry(PadButton.ZR, "P"),
            Map.entry(PadButton.PLUS, "N"), Map.entry(PadButton.MINUS, "M"),
            Map.entry(PadButton.L3, "B"), Map.entry(PadButton.R3, "Y"),
            Map.entry(PadButton.LS_UP, "8"), Map.entry(PadButton.LS_DOWN, "5"),
            Map.entry(PadButton.LS_LEFT, "4"), Map.entry(PadButton.LS_RIGHT, "6")));

    private final Config config;

    public KeyMap(Config config) {
        this.config = config;
    }

    public static String defaultKey(int player, PadButton b) {
        Map<PadButton, String> m = player == 1 ? P1 : player == 2 ? P2 : Map.of();
        return m.getOrDefault(b, "");
    }

    public String keyName(int player, PadButton b) {
        return config.get("keys.p" + player + "." + b.name(), defaultKey(player, b));
    }

    /** @return AWT key code, or -1 if unmapped */
    public int keyCode(int player, PadButton b) {
        return codeOf(keyName(player, b));
    }

    public static int codeOf(String name) {
        if (name == null || name.isBlank()) return -1;
        Integer c = NAMES.get(name.trim().toUpperCase(Locale.ROOT));
        return c == null ? -1 : c;
    }

    public static String nameOf(int keyCode) {
        return CODES.getOrDefault(keyCode, "");
    }

    public static List<String> allNames() {
        return List.copyOf(NAMES.keySet());
    }
}
