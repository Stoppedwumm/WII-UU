package wiiuu.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Gives Dolphin a temporary keyboard mapping that matches exactly what the phone GamePad sends,
 * for as long as a game runs, then puts the user's own controller settings back.
 *
 * <p>Used on macOS, where there are no virtual controllers: without it Dolphin only reacts to the
 * keys its own (possibly customised, or "None") controller settings expect. The originals are
 * backed up next to the files ({@code *.wiiuu-backup}) and restored when the game ends, or on the
 * next start if WII-UU was killed mid-game.
 */
public final class DolphinInput implements InputPatch {
    private static final String OS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    private static final String BACKUP = ".wiiuu-backup";
    private static final String[] FILES = {"GCPadNew.ini", "WiimoteNew.ini"};

    // Dolphin's macOS keyboard device, and its names for the keys the "dolphin" key profiles send
    private static final String DEVICE = "Quartz/0/Keyboard & Mouse";

    private static final String GCPAD = """
            [GCPad1]
            Device = %s
            Buttons/A = `X`
            Buttons/B = `Z`
            Buttons/X = `C`
            Buttons/Y = `S`
            Buttons/Z = `D`
            Buttons/Start = `Return`
            Main Stick/Up = `Up Arrow`
            Main Stick/Down = `Down Arrow`
            Main Stick/Left = `Left Arrow`
            Main Stick/Right = `Right Arrow`
            C-Stick/Up = `I`
            C-Stick/Down = `K`
            C-Stick/Left = `J`
            C-Stick/Right = `L`
            Triggers/L = `Q`
            Triggers/R = `W`
            Triggers/L-Analog = `Q`
            Triggers/R-Analog = `W`
            D-Pad/Up = `T`
            D-Pad/Down = `G`
            D-Pad/Left = `F`
            D-Pad/Right = `H`
            """;

    // Wii Remote + Nunchuk; the pointer follows the mouse, which taps on the phone's TV mirror move
    private static final String WIIMOTE = """
            [Wiimote1]
            Device = %s
            Source = 1
            Buttons/A = `X`
            Buttons/B = `Z`
            Buttons/1 = `C`
            Buttons/2 = `S`
            Buttons/- = `N`
            Buttons/+ = `Return`
            Buttons/Home = `M`
            D-Pad/Up = `Up Arrow`
            D-Pad/Down = `Down Arrow`
            D-Pad/Left = `Left Arrow`
            D-Pad/Right = `Right Arrow`
            IR/Up = `Cursor Y-`
            IR/Down = `Cursor Y+`
            IR/Left = `Cursor X-`
            IR/Right = `Cursor X+`
            Extension = Nunchuk
            Nunchuk/Buttons/C = `Q`
            Nunchuk/Buttons/Z = `W`
            Nunchuk/Stick/Up = `T`
            Nunchuk/Stick/Down = `G`
            Nunchuk/Stick/Left = `F`
            Nunchuk/Stick/Right = `H`
            """;

    private final Config config;
    private Path patchedDir;

    public DolphinInput(Config config) {
        this.config = config;
    }

    /** Whether to patch for this launch: Dolphin, GameCube/Wii, on macOS (input.dolphinMapping=off disables it). */
    @Override
    public boolean applies(Game game, List<String> cmd) {
        String id = game.system().id();
        if (!id.equals("gc") && !id.equals("wii")) return false;
        if (cmd.stream().noneMatch(a -> a.toLowerCase(Locale.ROOT).contains("dolphin"))) return false;
        String mode = config.get("input.dolphinMapping", "auto").trim().toLowerCase(Locale.ROOT);
        return OS.contains("mac") && (mode.equals("on") || mode.equals("auto"));   // the mapping uses macOS key names
    }

    /** Backs up Dolphin's controller files, writes the WII-UU mapping, and returns the command to run. */
    @Override
    public synchronized List<String> before(Game game, List<String> cmd) {
        Path dir = configDir();
        try {
            Files.createDirectories(dir);
            for (String f : FILES) {
                Path file = dir.resolve(f), backup = dir.resolve(f + BACKUP);
                // keep the first backup: if WII-UU crashed earlier, the current file is already ours
                if (!Files.exists(backup)) {
                    if (Files.exists(file)) Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
                    else Files.writeString(backup, "");          // empty marker = "file didn't exist"
                }
            }
            Files.writeString(dir.resolve("GCPadNew.ini"), GCPAD.formatted(DEVICE));
            Files.writeString(dir.resolve("WiimoteNew.ini"), WIIMOTE.formatted(DEVICE));
            patchedDir = dir;
            System.out.println("[dolphin] temporary GamePad mapping written to " + dir);
        } catch (IOException e) {
            System.err.println("[dolphin] could not write controller mapping: " + e.getMessage());
            return cmd;
        }
        List<String> out = new ArrayList<>(cmd);
        if (game.system().id().equals("gc")) {
            // port 1 = standard controller for this session only (not saved by Dolphin)
            out.add("-C");
            out.add("Dolphin.Core.SIDevice0=6");
        }
        return out;
    }

    /** Puts the user's own controller settings back. */
    @Override
    public synchronized void after() {
        if (patchedDir != null) restore(patchedDir);
        patchedDir = null;
    }

    /** At start: undo a mapping left behind if WII-UU was killed while a game ran. */
    @Override
    public void recover() {
        Path dir = configDir();
        if (Files.exists(dir.resolve(FILES[0] + BACKUP)) || Files.exists(dir.resolve(FILES[1] + BACKUP))) {
            restore(dir);
            System.out.println("[dolphin] restored your controller settings from an interrupted session");
        }
    }

    private static void restore(Path dir) {
        for (String f : FILES) {
            Path file = dir.resolve(f), backup = dir.resolve(f + BACKUP);
            try {
                if (!Files.exists(backup)) continue;
                if (Files.size(backup) == 0) Files.deleteIfExists(file);
                else Files.copy(backup, file, StandardCopyOption.REPLACE_EXISTING);
                Files.delete(backup);
            } catch (IOException e) {
                System.err.println("[dolphin] could not restore " + f + ": " + e.getMessage());
            }
        }
    }

    /** Dolphin's user config folder (config key dolphin.configDir overrides). */
    Path configDir() {
        String custom = config.get("dolphin.configDir", "").trim();
        if (!custom.isEmpty()) return Path.of(custom);
        String home = System.getProperty("user.home");
        if (OS.contains("mac")) return Path.of(home, "Library", "Application Support", "Dolphin", "Config");
        if (OS.contains("win")) {
            Path docs = Path.of(home, "Documents", "Dolphin Emulator", "Config");
            String appdata = System.getenv("APPDATA");
            if (Files.isDirectory(docs) || appdata == null) return docs;
            return Path.of(appdata, "Dolphin Emulator", "Config");
        }
        for (Path p : new Path[]{Path.of(home, ".config", "dolphin-emu"), Path.of(home, ".dolphin-emu", "Config"),
                Path.of(home, ".var", "app", "org.DolphinEmu.dolphin-emu", "config", "dolphin-emu")}) {
            if (Files.isDirectory(p)) return p;
        }
        return Path.of(home, ".config", "dolphin-emu");
    }
}
