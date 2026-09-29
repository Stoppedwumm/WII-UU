package wiiuu.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.function.Predicate;

import wiiuu.input.KeyMap;
import wiiuu.input.PadButton;

/**
 * The temporary keyboard mapping (like {@link DolphinInput}) for PCSX2 (PS2) and DuckStation (PS1).
 * Both keep controller bindings in their settings file as {@code [Pad1] Cross = Keyboard/X}.
 *
 * <p>Only the binding lines WII-UU needs are changed: their original values are saved in
 * {@code <settings>.wiiuu-keys} and exactly those lines are put back when the game ends (or at the
 * next start, if WII-UU was killed). Anything else changed in the emulator meanwhile is kept.
 * Players 1 and 2 are mapped. The keys are the ones WII-UU types for this emulator.
 */
final class PadIniInput implements InputPatch {
    private static final String OS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    private static final String ABSENT = "\u0000absent";

    /** PlayStation buttons by position, like WII-UU's (A = east = Circle, B = south = Cross). */
    private static final Map<PadButton, String> BUTTONS = new LinkedHashMap<>();

    static {
        String[][] m = {{"UP", "Up"}, {"DOWN", "Down"}, {"LEFT", "Left"}, {"RIGHT", "Right"},
                {"A", "Circle"}, {"B", "Cross"}, {"X", "Triangle"}, {"Y", "Square"},
                {"L", "L1"}, {"R", "R1"}, {"ZL", "L2"}, {"ZR", "R2"}, {"L3", "L3"}, {"R3", "R3"},
                {"PLUS", "Start"}, {"MINUS", "Select"},
                {"LS_UP", "LUp"}, {"LS_DOWN", "LDown"}, {"LS_LEFT", "LLeft"}, {"LS_RIGHT", "LRight"},
                {"RS_UP", "RUp"}, {"RS_DOWN", "RDown"}, {"RS_LEFT", "RLeft"}, {"RS_RIGHT", "RRight"}};
        for (String[] e : m) BUTTONS.put(PadButton.valueOf(e[0]), e[1]);
    }

    /** One emulator: how to recognise it and where its settings file lives. */
    record Emulator(String name, String profile, Predicate<List<String>> matches, List<Path> settings) {}

    static Emulator pcsx2() {
        String home = System.getProperty("user.home");
        String xdg = System.getenv("XDG_CONFIG_HOME");
        Path config = xdg != null && !xdg.isBlank() ? Path.of(xdg) : Path.of(home, ".config");
        List<Path> paths = OS.contains("mac")
                ? List.of(Path.of(home, "Library", "Application Support", "PCSX2", "inis", "PCSX2.ini"))
                : OS.contains("win")
                ? List.of(Path.of(home, "Documents", "PCSX2", "inis", "PCSX2.ini"))
                : List.of(config.resolve("PCSX2/inis/PCSX2.ini"),
                        Path.of(home, ".var", "app", "net.pcsx2.PCSX2", "config", "PCSX2", "inis", "PCSX2.ini"));
        return new Emulator("PCSX2", null, cmd -> mentions(cmd, "pcsx2"), paths);
    }

    static Emulator duckstation() {
        String home = System.getProperty("user.home");
        String xdg = System.getenv("XDG_DATA_HOME");
        Path data = xdg != null && !xdg.isBlank() ? Path.of(xdg) : Path.of(home, ".local", "share");
        String local = System.getenv("LOCALAPPDATA");
        List<Path> paths = OS.contains("mac")
                ? List.of(Path.of(home, "Library", "Application Support", "DuckStation", "settings.ini"))
                : OS.contains("win")
                ? List.of(Path.of(home, "Documents", "DuckStation", "settings.ini"),
                        Path.of(local == null ? home : local, "DuckStation", "settings.ini"))
                : List.of(data.resolve("duckstation/settings.ini"),
                        Path.of(home, ".var", "app", "org.duckstation.DuckStation", "data", "duckstation", "settings.ini"));
        return new Emulator("DuckStation", "duckstation", cmd -> mentions(cmd, "duckstation"), paths);
    }

    private static boolean mentions(List<String> cmd, String name) {
        return cmd.stream().anyMatch(a -> a.toLowerCase(Locale.ROOT).contains(name) && !a.contains("_libretro"));
    }

    private final Config config;
    private final Emulator emu;
    /** Buzz! mode: PCSX2's emulated Buzz! controllers instead of the pads (see {@link Buzz}) */
    private final boolean buzz;
    private Path patched;

    PadIniInput(Config config, Emulator emu) {
        this(config, emu, false);
    }

    PadIniInput(Config config, Emulator emu, boolean buzz) {
        this.config = config;
        this.emu = emu;
        this.buzz = buzz;
    }

    @Override
    public boolean applies(Game game, List<String> cmd) {
        String mode = config.get("input.emulatorMapping", "auto").trim().toLowerCase(Locale.ROOT);
        if (buzz && !Buzz.active(config, game)) return false;
        return (buzz || !mode.equals("off")) && emu.matches().test(cmd);
    }

    /**
     * The lines to set for this game, as "Section/Key" -> value (null removes the line).
     * Pads: players 1 and 2 on ports 1 and 2. Buzz!: up to four buzzers per USB port (players 1-4 on
     * USB port 1, 5-8 on port 2 in 8-player mode), and no keyboard keys left on the pads, so a buzzer
     * key can't also press a pad button.
     */
    private Map<String, String> plan(Ini ini) {
        Map<String, String> plan = new LinkedHashMap<>();
        KeyMap keys = new KeyMap(config);
        if (!buzz) {
            for (int player = 1; player <= 2; player++) {
                for (Map.Entry<PadButton, String> e : BUTTONS.entrySet()) {
                    String key = KeyMap.nameOf(keys.keyCode(player, e.getKey(), emu.profile()));
                    plan.put("Pad" + player + "/" + e.getValue(), key.isEmpty() ? null : "Keyboard/" + qtKey(key));
                }
            }
            return plan;
        }
        int ports = Buzz.players(config) > 4 ? 2 : 1;
        for (int port = 1; port <= ports; port++) {
            String section = "USB" + port;
            plan.put(section + "/Type", Buzz.PCSX2_DEVICE);
            for (int n = 1; n <= 4; n++) {
                int player = (port - 1) * 4 + n;
                for (Map.Entry<PadButton, String> e : Buzz.COLORS.entrySet()) {
                    String key = KeyMap.nameOf(keys.keyCode(player, e.getKey(), null));
                    plan.put(section + "/" + Buzz.PCSX2_DEVICE + "_" + e.getValue() + n, key.isEmpty() ? null : "Keyboard/" + qtKey(key));
                }
            }
        }
        for (String pad : new String[]{"Pad1", "Pad2"}) {
            for (Map.Entry<String, String> e : ini.entries(pad).entrySet()) {
                if (e.getValue().contains("Keyboard/")) plan.put(pad + "/" + e.getKey(), null);
            }
        }
        return plan;
    }

    /** The emulator's settings file, if it has been run at least once (a missing file is left alone). */
    Path settings() {
        String custom = config.get("input." + emu.name().toLowerCase(Locale.ROOT) + ".settings", "").trim();
        if (!custom.isEmpty()) return Files.isRegularFile(Path.of(custom)) ? Path.of(custom) : null;
        for (Path p : emu.settings()) if (Files.isRegularFile(p)) return p;
        return null;
    }

    @Override
    public synchronized List<String> before(Game game, List<String> cmd) {
        Path file = settings();
        if (file == null) {
            System.out.println("[" + emu.name().toLowerCase(Locale.ROOT) + "] no settings file yet; using its default keys");
            return cmd;
        }
        try {
            Ini ini = Ini.read(file);
            Path backup = backupOf(file);
            Properties saved = new Properties();
            // a leftover backup means the file still holds our keys from a crash: keep the older originals
            if (Files.exists(backup)) try (var in = Files.newInputStream(backup)) { saved.load(in); }
            for (Map.Entry<String, String> e : plan(ini).entrySet()) {
                int slash = e.getKey().indexOf('/');
                String section = e.getKey().substring(0, slash), key = e.getKey().substring(slash + 1);
                // a section that didn't exist is removed again afterwards
                if (!saved.containsKey(section + "/")) saved.setProperty(section + "/", ini.has(section) ? "" : ABSENT);
                if (!saved.containsKey(e.getKey())) {
                    String old = ini.get(section, key);
                    saved.setProperty(e.getKey(), old == null ? ABSENT : old);
                }
                // unbinding keeps the line (empty = unbound), so restoring puts the value back in the same place
                if (e.getValue() == null) {
                    if (ini.get(section, key) != null) ini.set(section, key, "");
                } else {
                    ini.set(section, key, e.getValue());
                }
            }
            try (var out = Files.newOutputStream(backup)) { saved.store(out, "WII-UU: your own " + emu.name() + " bindings"); }
            ini.write(file);
            patched = file;
            System.out.println("[" + emu.name().toLowerCase(Locale.ROOT) + "] temporary " + (buzz ? "Buzz! controller" : "GamePad")
                    + " mapping written to " + file);
        } catch (IOException | RuntimeException e) {
            System.err.println("[" + emu.name().toLowerCase(Locale.ROOT) + "] could not write controller mapping: " + e.getMessage());
        }
        return cmd;
    }

    @Override
    public synchronized void after() {
        if (patched != null) restore(patched);
        patched = null;
    }

    @Override
    public void recover() {
        for (Path p : emu.settings()) {
            if (Files.exists(backupOf(p))) {
                restore(p);
                System.out.println("[" + emu.name().toLowerCase(Locale.ROOT) + "] restored your controller settings from an interrupted session");
            }
        }
    }

    private void restore(Path file) {
        Path backup = backupOf(file);
        try {
            Properties saved = new Properties();
            try (var in = Files.newInputStream(backup)) { saved.load(in); }
            Ini ini = Ini.read(file);
            for (String id : saved.stringPropertyNames()) {
                int slash = id.indexOf('/');
                String section = id.substring(0, slash), key = id.substring(slash + 1), value = saved.getProperty(id);
                if (key.isEmpty()) continue;
                if (value.equals(ABSENT)) ini.remove(section, key);
                else ini.set(section, key, value);
            }
            for (String id : saved.stringPropertyNames()) {
                if (id.endsWith("/") && saved.getProperty(id).equals(ABSENT)) ini.removeSectionIfEmpty(id.substring(0, id.length() - 1));
            }
            ini.write(file);
            Files.delete(backup);
        } catch (IOException | RuntimeException e) {
            System.err.println("[" + emu.name().toLowerCase(Locale.ROOT) + "] could not restore controller settings: " + e.getMessage());
        }
    }

    private static Path backupOf(Path file) {
        return file.resolveSibling(file.getFileName() + ".wiiuu-keys");
    }

    /** AWT key name (keys.pN.X) to the Qt key names PCSX2 and DuckStation use. */
    static String qtKey(String awt) {
        String k = awt.trim().toUpperCase(Locale.ROOT);
        if (k.length() == 1) return k;                               // letters and digits
        if (k.matches("F\\d{1,2}")) return k;
        return switch (k) {
            case "ENTER" -> "Return";
            case "SHIFT" -> "Shift";
            case "CONTROL" -> "Control";
            case "ALT" -> "Alt";
            case "SPACE" -> "Space";
            case "BACK_SPACE" -> "Backspace";
            case "TAB" -> "Tab";
            case "ESCAPE" -> "Escape";
            case "UP" -> "Up";
            case "DOWN" -> "Down";
            case "LEFT" -> "Left";
            case "RIGHT" -> "Right";
            case "MINUS" -> "Minus";
            case "EQUALS" -> "Equal";
            case "COMMA" -> "Comma";
            case "PERIOD" -> "Period";
            case "SLASH" -> "Slash";
            case "SEMICOLON" -> "Semicolon";
            case "HOME" -> "Home";
            case "END" -> "End";
            case "PAGE_UP" -> "PageUp";
            case "PAGE_DOWN" -> "PageDown";
            case "DELETE" -> "Delete";
            case "INSERT" -> "Insert";
            default -> k.charAt(0) + k.substring(1).toLowerCase(Locale.ROOT);
        };
    }

    /** A minimal INI editor that keeps every other line of the file exactly as it was. */
    static final class Ini {
        private final List<String> lines;

        private Ini(List<String> lines) {
            this.lines = lines;
        }

        static Ini read(Path file) throws IOException {
            return new Ini(new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8)));
        }

        void write(Path file) throws IOException {
            Path tmp = file.resolveSibling(file.getFileName() + ".wiiuu-tmp");
            Files.write(tmp, lines, StandardCharsets.UTF_8);
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }

        private int[] sectionRange(String section) {
            int start = -1;
            for (int i = 0; i < lines.size(); i++) {
                String l = lines.get(i).trim();
                if (l.startsWith("[") && l.endsWith("]")) {
                    if (start >= 0) return new int[]{start, i};
                    if (l.substring(1, l.length() - 1).trim().equals(section)) start = i + 1;
                }
            }
            return start < 0 ? null : new int[]{start, lines.size()};
        }

        private int find(int[] r, String key) {
            for (int i = r[0]; i < r[1]; i++) {
                String l = lines.get(i);
                int eq = l.indexOf('=');
                if (eq > 0 && !l.trim().startsWith(";") && !l.trim().startsWith("#") && l.substring(0, eq).trim().equals(key)) return i;
            }
            return -1;
        }

        String get(String section, String key) {
            int[] r = sectionRange(section);
            if (r == null) return null;
            int i = find(r, key);
            return i < 0 ? null : lines.get(i).substring(lines.get(i).indexOf('=') + 1).trim();
        }

        void set(String section, String key, String value) {
            int[] r = sectionRange(section);
            if (r == null) {
                if (!lines.isEmpty() && !lines.get(lines.size() - 1).isBlank()) lines.add("");
                lines.add("[" + section + "]");
                lines.add(key + " = " + value);
                return;
            }
            int i = find(r, key);
            if (i >= 0) {
                lines.set(i, key + " = " + value);
            } else {
                int at = r[1];
                while (at > r[0] && lines.get(at - 1).isBlank()) at--;       // before the blank line ending the section
                lines.add(at, key + " = " + value);
            }
        }

        /** All key = value lines of a section, in order. */
        Map<String, String> entries(String section) {
            Map<String, String> out = new LinkedHashMap<>();
            int[] r = sectionRange(section);
            if (r == null) return out;
            for (int i = r[0]; i < r[1]; i++) {
                String l = lines.get(i);
                int eq = l.indexOf('=');
                if (eq > 0 && !l.trim().startsWith(";") && !l.trim().startsWith("#")) out.put(l.substring(0, eq).trim(), l.substring(eq + 1).trim());
            }
            return out;
        }

        boolean has(String section) {
            return sectionRange(section) != null;
        }

        /** Removes a section with no keys left, and the blank line WII-UU put before it. */
        void removeSectionIfEmpty(String section) {
            int[] r = sectionRange(section);
            if (r == null) return;
            for (int i = r[0]; i < r[1]; i++) if (!lines.get(i).isBlank()) return;
            int header = r[0] - 1;
            for (int i = r[1] - 1; i >= header; i--) lines.remove(i);
            if (header > 0 && lines.get(header - 1).isBlank()) lines.remove(header - 1);
        }

        void remove(String section, String key) {
            int[] r = sectionRange(section);
            if (r == null) return;
            int i = find(r, key);
            if (i >= 0) lines.remove(i);
        }
    }
}
