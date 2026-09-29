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
    /** Players 1-4, or up to 8 in 8-player mode (server.maxPlayers=8). */
    public static final int MAX_PLAYERS = 8;

    /**
     * Buzz! buzzers: red, blue, orange, green, yellow for players 1-8. Distinct for every player and
     * clear of PCSX2's default shortcuts (F-keys, Escape, Space...).
     */
    private static final String[][] BUZZ = {
            {"1", "2", "3", "4", "5"}, {"6", "7", "8", "9", "0"}, {"Q", "W", "E", "R", "T"}, {"Y", "U", "I", "O", "P"},
            {"A", "S", "D", "F", "G"}, {"H", "J", "K", "L", "Z"}, {"X", "C", "V", "B", "N"}, {"M", "HOME", "END", "PAGE_UP", "PAGE_DOWN"}};

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

    /**
     * Each emulator's own default keyboard layout (player 1), so the phone works without setting
     * up keys in the emulator. Buttons are by position: A = east, B = south, X = north, Y = west.
     * Users can override any entry with keys.&lt;profile&gt;.&lt;BUTTON&gt;=KEY.
     */
    private static final Map<String, Map<PadButton, String>> PROFILES = Map.of(
            // Dolphin Wii Remote + Nunchuk as written by DolphinInput (macOS): A=X B=Z 1=C 2=S -=N +=Return Home=M,
            // D-pad arrows, Nunchuk C=Q Z=W, Nunchuk stick TFGH
            "dolphin-wii", profile("A=X B=Z X=C Y=S MINUS=N PLUS=ENTER HOME=M UP=UP DOWN=DOWN LEFT=LEFT RIGHT=RIGHT "
                    + "L=Q R=W ZL=Q ZR=W LS_UP=T LS_DOWN=G LS_LEFT=F LS_RIGHT=H"),
            // Dolphin GameCube pad: A=X B=Z X=C Y=S Z=D L=Q R=W Start=Return, D-pad TFGH, stick arrows, C-stick IJKL
            "dolphin", profile("A=X B=Z X=C Y=S ZR=D L=Q R=W PLUS=ENTER UP=T DOWN=G LEFT=F RIGHT=H "
                    + "LS_UP=UP LS_DOWN=DOWN LS_LEFT=LEFT LS_RIGHT=RIGHT RS_UP=I RS_DOWN=K RS_LEFT=J RS_RIGHT=L"),
            // PPSSPP: Cross=Z Circle=X Square=A Triangle=S L=Q R=W Start=Space Select=V, D-pad arrows, analog IJKL
            "ppsspp", profile("A=X B=Z X=S Y=A L=Q R=W PLUS=SPACE MINUS=V UP=UP DOWN=DOWN LEFT=LEFT RIGHT=RIGHT "
                    + "LS_UP=I LS_DOWN=K LS_LEFT=J LS_RIGHT=L"),
            // mGBA: A=X B=Z L=A R=S Start=Enter Select=Backspace, D-pad arrows
            "mgba", profile("A=X B=Z L=A R=S PLUS=ENTER MINUS=BACK_SPACE UP=UP DOWN=DOWN LEFT=LEFT RIGHT=RIGHT "
                    + "LS_UP=UP LS_DOWN=DOWN LS_LEFT=LEFT LS_RIGHT=RIGHT"),
            // melonDS: A=X B=Z X=S Y=A L=Q R=W Start=Enter Select=Shift, D-pad arrows
            "melonds", profile("A=X B=Z X=S Y=A L=Q R=W PLUS=ENTER MINUS=SHIFT UP=UP DOWN=DOWN LEFT=LEFT RIGHT=RIGHT "
                    + "LS_UP=UP LS_DOWN=DOWN LS_LEFT=LEFT LS_RIGHT=RIGHT"),
            // DuckStation: D-pad WASD, Triangle=I Circle=L Cross=K Square=J, L1=Q R1=E L2=1 R2=3, Start=Return Select=Backspace
            "duckstation", profile("UP=W DOWN=S LEFT=A RIGHT=D X=I A=L B=K Y=J L=Q R=E ZL=1 ZR=3 PLUS=ENTER MINUS=BACK_SPACE "
                    + "LS_UP=W LS_DOWN=S LS_LEFT=A LS_RIGHT=D"),
            // Ryujinx: A=Z B=X X=C Y=V L=E R=U ZL=Q ZR=O +=Plus -=Minus, D-pad arrows, sticks WASD / IJKL
            "ryujinx", profile("A=Z B=X X=C Y=V L=E R=U ZL=Q ZR=O PLUS=EQUALS MINUS=MINUS UP=UP DOWN=DOWN LEFT=LEFT RIGHT=RIGHT "
                    + "LS_UP=W LS_DOWN=S LS_LEFT=A LS_RIGHT=D RS_UP=I RS_DOWN=K RS_LEFT=J RS_RIGHT=L"),
            // Azahar / Citra: A=A B=S X=Z Y=X L=Q R=W ZL=1 ZR=2 Start=M Select=N Home=B, D-pad TFGH, circle pad arrows, C-stick IJKL
            "azahar", profile("A=A B=S X=Z Y=X L=Q R=W ZL=1 ZR=2 PLUS=M MINUS=N HOME=B UP=T DOWN=G LEFT=F RIGHT=H "
                    + "LS_UP=UP LS_DOWN=DOWN LS_LEFT=LEFT LS_RIGHT=RIGHT RS_UP=I RS_DOWN=K RS_LEFT=J RS_RIGHT=L"));

    private static Map<PadButton, String> profile(String spec) {
        Map<PadButton, String> m = new EnumMap<>(PadButton.class);
        for (PadButton b : PadButton.values()) m.put(b, "");        // unlisted buttons send nothing
        for (String kv : spec.split(" ")) {
            String[] p = kv.split("=", 2);
            m.put(PadButton.valueOf(p[0]), p[1]);
        }
        return m;
    }

    /** Which built-in layout fits the emulator in this command (null = the generic defaults). */
    public static String profileFor(String systemId, String command) {
        String c = command == null ? "" : command.toLowerCase(java.util.Locale.ROOT);
        if (c.contains("dolphin")) return "gc".equals(systemId) ? "dolphin" : "wii".equals(systemId) ? "dolphin-wii" : null;
        for (String p : new String[]{"ppsspp", "mgba", "melonds", "duckstation", "ryujinx", "azahar"}) {
            if (c.contains(p)) return p;
        }
        if (c.contains("citra")) return "azahar";
        return null;
    }

    private final Config config;

    public KeyMap(Config config) {
        this.config = config;
    }

    public static String defaultKey(int player, PadButton b) {
        if (b.isBuzz()) {
            return player >= 1 && player <= BUZZ.length ? BUZZ[player - 1][b.ordinal() - PadButton.BUZZ_RED.ordinal()] : "";
        }
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

    /**
     * Key for a button while an emulator with layout {@code profile} runs. Order: keys you set in
     * Settings (keys.pN.X), then keys.&lt;profile&gt;.X, then the emulator's built-in layout, then the defaults.
     */
    public int keyCode(int player, PadButton b, String profile) {
        String explicit = config.get("keys.p" + player + "." + b.name(), null);
        if (b.isBuzz()) return codeOf(keyName(player, b));
        if (explicit != null || profile == null || player != 1 || !PROFILES.containsKey(profile)) {
            return codeOf(keyName(player, b));
        }
        String custom = config.get("keys." + profile + "." + b.name(), null);
        return codeOf(custom != null ? custom : PROFILES.get(profile).get(b));
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
