package wiiuu.core;

import java.io.IOException;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CopyOnWriteArrayList;

/** Starts one emulator process at a time and reports when it ends. */
public final class Launcher {

    public interface Listener {
        void started(Game game);

        /** @param quickFailure true when the emulator died within a few seconds with an error code */
        void exited(Game game, int exitCode, boolean quickFailure, Path log);

        /** Something to tell the user outside a launch call (e.g. a delayed start failed). */
        default void message(String text) {
        }
    }

    public static final class LaunchException extends Exception {
        public LaunchException(String message) {
            super(message);
        }
    }

    private final Config config;
    /** temporary controller mappings for emulators (Dolphin, PCSX2, DuckStation), see InputPatch */
    private final List<InputPatch> inputPatches;
    /** whether WII-UU types keys for this game (the mappings only matter then) */
    private volatile java.util.function.Predicate<Game> typesKeys = g -> true;
    private final RetroArch retroArch;
    /** the running game was started in RetroArch (so it reads RetroArch's key layout) */
    private volatile boolean inRetroArch;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private Process process;
    private Game current;
    private String flatpakApp;
    /** macOS: `open -a <App>` starts the app via launchd, so it isn't our child; stop it by name */
    private String macApp;

    public Launcher(Config config) {
        this.config = config;
        // Buzz! first: for Buzz! games it replaces the PCSX2 pad mapping
        this.inputPatches = List.of(new PadIniInput(config, PadIniInput.pcsx2(), true),
                new DolphinInput(config), new PadIniInput(config, PadIniInput.pcsx2()),
                new PadIniInput(config, PadIniInput.duckstation()));
        this.retroArch = new RetroArch(config);
        for (InputPatch p : inputPatches) p.recover();
    }

    /** Tells the launcher when WII-UU types keys for a game (see Main: input.keys, virtual pads). */
    public void setTypesKeys(java.util.function.Predicate<Game> typesKeys) {
        this.typesKeys = typesKeys;
    }

    /** RetroArch mode: where a game's window goes when its screens are split (see DualScreen), or null. */
    private volatile java.util.function.Function<Game, java.awt.Rectangle> split = g -> null;

    public void setSplit(java.util.function.Function<Game, java.awt.Rectangle> split) {
        this.split = split;
    }

    public void addListener(Listener l) {
        listeners.add(l);
    }

    public synchronized boolean isRunning() {
        return process != null && process.isAlive();
    }

    public synchronized Game current() {
        return isRunning() ? current : null;
    }

    /** True while the running game was started through RetroArch (RetroArch mode). */
    public boolean inRetroArch() {
        return inRetroArch && isRunning();
    }

    private volatile java.util.function.BooleanSupplier virtualPads = () -> false;

    /** Plays OpenBased videos (in a video player rather than an emulator), once set. */
    private volatile OpenBased openBased;

    /** Tells RetroArch mode whether the phones are virtual controllers (so it binds their layout). */
    public void setVirtualPads(java.util.function.BooleanSupplier virtualPads) {
        this.virtualPads = virtualPads;
        retroArch.setVirtualPads(virtualPads);
    }

    public void setOpenBased(OpenBased openBased) {
        this.openBased = openBased;
    }

    private Reactions reactions;

    /** Plays reaction videos (in the video player, like OpenBased's). */
    public void setReactions(Reactions reactions) {
        this.reactions = reactions;
    }

    public synchronized void launch(Game game) throws LaunchException {
        if (isRunning()) throw new LaunchException(current.name() + " is already running");
        List<String> cmd = null;
        boolean video = OpenBased.handles(game) || Reactions.handles(game);
        if (Reactions.handles(game)) {
            if (reactions == null) throw new LaunchException("Reaction videos aren't available");
            cmd = reactions.command(game);
        } else if (video) {
            if (openBased == null) throw new LaunchException("OpenBased isn't available");
            cmd = openBased.command(game);
        } else if (retroArch.handles(game.system())) {
            try {
                // after downloading a missing core, start the game by itself
                java.awt.Rectangle where = split.apply(game);
                cmd = retroArch.command(game, () -> {
                    try {
                        launch(game);
                    } catch (LaunchException e) {
                        for (Listener l : listeners) l.message(e.getMessage());
                    }
                }, where);
            } catch (RetroArch.Downloading d) {
                throw new LaunchException(d.getMessage());
            }
        }
        boolean viaRetroArch = cmd != null && !video;
        if (cmd == null) {
            String template = config.command(game.system());
            cmd = expand(template, game);
            if (cmd.isEmpty()) throw new LaunchException("No emulator command set for " + game.system().name());
            cmd = resolve(cmd, game.system());
            cmd = mupenFillsScreen(cmd, screenPixels());
            if (virtualPads.getAsBoolean() && !typesKeys.test(game) && config.getBool("mupen.padBinds", true)) {
                List<String> others = Joysticks.others();
                cmd = mupenPads(cmd, others.isEmpty() ? Math.max(1, Joysticks.own()) : 0);
                if (!others.isEmpty() && isMupen(cmd)) {
                    System.out.println("[n64] controllers besides the phones (" + String.join(", ", others) + "): Mupen64Plus sets them up itself");
                }
            }
        }
        InputPatch patch = null;
        if (!viaRetroArch && !video && typesKeys.test(game)) {
            for (InputPatch ip : inputPatches) {
                if (ip.applies(game, cmd)) {
                    patch = ip;
                    cmd = ip.before(game, cmd);
                    break;
                }
            }
        }
        final InputPatch applied = patch;
        cmd = new ArrayList<>(cmd);
        flatpakApp = flatpakId(cmd);
        macApp = macAppName(cmd);
        // own process group (Linux): closing the game then reaches every process the emulator started
        if (LINUX && onPath("setsid") != null) cmd.add(0, "setsid");

        Path log = config.logDir().resolve(game.system().id() + ".log");
        ProcessBuilder pb = new ProcessBuilder(cmd);
        Path dir = game.path().getParent();
        if (dir != null && Files.isDirectory(dir)) pb.directory(dir.toFile());
        pb.redirectErrorStream(true);
        try {
            Files.createDirectories(log.getParent());
            pb.redirectOutput(log.toFile());
        } catch (IOException e) {
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        }
        final Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            if (applied != null) applied.after();
            if (video) throw new LaunchException("The video player didn't start (\"" + cmd.get(LINUX && cmd.get(0).equals("setsid") ? 1 : 0)
                    + "\"): " + e.getMessage());
            throw new LaunchException(game.system().emulator() + " is not installed (\"" + cmd.get(LINUX && cmd.get(0).equals("setsid") ? 1 : 0)
                    + "\"). " + (LINUX ? "Run: wiiuu-emulators --only " + EMU_DIRS.getOrDefault(game.system().emulator(), "?") + "  or set" : "Set")
                    + " the emulator in Settings (F1).");
        }
        process = p;
        current = game;
        inRetroArch = viaRetroArch;
        if (viaRetroArch) System.out.println("[retroarch] " + game.name() + " with " + Path.of(cmd.get(cmd.indexOf("-L") + 1)).getFileName());
        long startedAt = System.currentTimeMillis();
        for (Listener l : listeners) l.started(game);
        p.onExit().thenAccept(done -> {
            synchronized (Launcher.this) {
                if (process == done) {
                    process = null;
                    current = null;
                }
            }
            if (applied != null) applied.after();        // give the user's own controller settings back
            int code = done.exitValue();
            boolean quick = code != 0 && System.currentTimeMillis() - startedAt < 4000;
            for (Listener l : listeners) l.exited(game, code, quick, log);
        });
    }

    /** Asks the emulator to quit, then force-kills it (and everything it started) if it refuses. */
    public void stop() {
        stop(false);
    }

    /** @param wait block until the emulator is gone (used when WII-UU itself quits) */
    public void stop(boolean wait) {
        Process p;
        String flatpak, mac;
        synchronized (this) {
            p = process;
            flatpak = flatpakApp;
            mac = macApp;
        }
        if (p == null || !p.isAlive()) return;
        long pid = p.pid();
        // Emulators that fork or show an "are you sure?" dialog survive a plain destroy(), so signal
        // the whole process group / tree, and force it after a grace period.
        signalTree(p, pid, flatpak, mac, false);
        Runnable force = () -> {
            try {
                p.waitFor(3, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            signalTree(p, pid, flatpak, mac, true);
        };
        if (wait) {
            force.run();
            try {
                p.waitFor(2, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        } else {
            Thread t = new Thread(force, "emulator-kill");
            t.setDaemon(true);
            t.start();
        }
    }

    private static void signalTree(Process p, long pid, String flatpak, String macApp, boolean force) {
        List<ProcessHandle> tree = p.descendants().toList();
        if (macApp != null) {
            if (!force) {
                // like Cmd+Q, so the emulator can save and shut down cleanly
                quietly("osascript", "-e", "tell application \"" + macApp.replace("\"", "") + "\" to quit");
            }
            // the app's real process, found by the executable inside its .app bundle
            quietly("pkill", force ? "-KILL" : "-TERM", "-f", "/" + macApp + ".app/Contents/MacOS/");
        }
        if (LINUX || MAC) {
            // negative pid = the whole process group created by setsid
            quietly("kill", force ? "-KILL" : "-TERM", "--", "-" + pid);
        } else if (WINDOWS) {
            if (force) quietly("taskkill", "/PID", Long.toString(pid), "/T", "/F");
            else quietly("taskkill", "/PID", Long.toString(pid), "/T");
        }
        if (flatpak != null) quietly("flatpak", "kill", flatpak);   // the flatpak client isn't the emulator
        for (ProcessHandle h : tree) {
            if (force) h.destroyForcibly();
            else h.destroy();
        }
        if (force) p.destroyForcibly();
        else p.destroy();
    }

    private static void quietly(String... cmd) {
        try {
            new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start().waitFor(3, TimeUnit.SECONDS);
        } catch (IOException ignored) {
            // tool not available on this system
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Last meaningful line of the emulator's log, for error messages. */
    public static String lastLogLine(Path log) {
        try {
            List<String> lines = Files.readAllLines(log);
            for (int i = lines.size() - 1; i >= 0; i--) {
                String l = lines.get(i).trim();
                if (!l.isEmpty() && !l.startsWith("ALSA lib")) return l.length() > 160 ? l.substring(0, 160) + "..." : l;
            }
        } catch (IOException | RuntimeException ignored) {
            // no log
        }
        return null;
    }

    // ---- finding emulators ----------------------------------------------------------------

    private static final String OS_NAME = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
    private static final boolean WINDOWS = OS_NAME.contains("win");
    private static final boolean MAC = OS_NAME.contains("mac");
    private static final boolean LINUX = !WINDOWS && !MAC;

    /** emulator name -> folder used by wiiuu-emulators */
    static final Map<String, String> EMU_DIRS = Map.ofEntries(
            Map.entry("Dolphin", "dolphin"), Map.entry("Cemu", "cemu"), Map.entry("Ryujinx", "ryujinx"),
            Map.entry("Azahar", "azahar"), Map.entry("DuckStation", "duckstation"), Map.entry("PCSX2", "pcsx2"),
            Map.entry("RPCS3", "rpcs3"), Map.entry("shadPS4", "shadps4"), Map.entry("PPSSPP", "ppsspp"),
            Map.entry("mGBA", "mgba"), Map.entry("melonDS", "melonds"), Map.entry("Snes9x", "snes9x"),
            Map.entry("Flycast", "flycast"), Map.entry("Mupen64Plus", "mupen64plus"), Map.entry("Mednafen", "mednafen"),
            Map.entry("Mesen", "fceux"));
    /** emulator name -> Flathub id */
    private static final Map<String, String> FLATPAKS = Map.ofEntries(
            Map.entry("Dolphin", "org.DolphinEmu.dolphin-emu"), Map.entry("Cemu", "info.cemu.Cemu"),
            Map.entry("Ryujinx", "io.github.ryubing.Ryujinx"), Map.entry("Azahar", "org.azahar_emu.Azahar"),
            Map.entry("DuckStation", "org.duckstation.DuckStation"), Map.entry("PCSX2", "net.pcsx2.PCSX2"),
            Map.entry("RPCS3", "net.rpcs3.RPCS3"), Map.entry("shadPS4", "net.shadps4.shadPS4"),
            Map.entry("PPSSPP", "org.ppsspp.PPSSPP"), Map.entry("mGBA", "io.mgba.mGBA"),
            Map.entry("melonDS", "net.kuribo64.melonDS"), Map.entry("Snes9x", "com.snes9x.Snes9x"),
            Map.entry("Flycast", "org.flycast.Flycast"));
    /** emulator name -> other program names it is installed under */
    private static final Map<String, List<String>> ALIASES = Map.ofEntries(
            Map.entry("Dolphin", List.of("dolphin-emu", "Dolphin", "dolphin")),
            Map.entry("Cemu", List.of("cemu", "Cemu")), Map.entry("Ryujinx", List.of("Ryujinx", "ryujinx")),
            Map.entry("Azahar", List.of("azahar", "azahar-qt", "citra-qt")),
            Map.entry("DuckStation", List.of("duckstation-qt", "duckstation")), Map.entry("PCSX2", List.of("pcsx2-qt", "pcsx2")),
            Map.entry("RPCS3", List.of("rpcs3")), Map.entry("PPSSPP", List.of("PPSSPPSDL", "ppsspp", "PPSSPPQt")),
            Map.entry("mGBA", List.of("mgba-qt", "mgba")), Map.entry("melonDS", List.of("melonDS", "melonds")),
            Map.entry("Snes9x", List.of("snes9x-gtk", "snes9x")), Map.entry("Mesen", List.of("Mesen", "mesen")));

    /**
     * If the configured program isn't installed, look for the emulator elsewhere: other program
     * names, the wiiuu-emulators folder, Flatpak, and AppImages in ~/Applications.
     */
    List<String> resolve(List<String> cmd, GameSystem system) {
        List<String> out = new ArrayList<>(cmd);
        String prog = out.get(0);
        if (prog.equals("flatpak") || prog.equals("open") || new File(prog).canExecute() || onPath(prog) != null) return out;
        String emu = system.emulator();
        List<String> args = out.subList(1, out.size());
        for (String alias : ALIASES.getOrDefault(emu, List.of())) {
            String found = onPath(alias);
            if (found != null) return join(found, args);
        }
        String dir = EMU_DIRS.get(emu);
        if (dir != null) {
            String data = System.getenv("XDG_DATA_HOME");
            Path base = data != null && !data.isBlank() ? Path.of(data) : Path.of(System.getProperty("user.home"), ".local", "share");
            Path run = base.resolve("wiiuu-emulators").resolve("emulators").resolve(dir).resolve("run");
            if (Files.isExecutable(run)) return join(run.toString(), args);
        }
        String flatpak = FLATPAKS.get(emu);
        if (flatpak != null && onPath("flatpak") != null) {
            try {
                Process p = new ProcessBuilder("flatpak", "info", flatpak).redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
                if (p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0) {
                    List<String> f = new ArrayList<>(List.of("flatpak", "run", flatpak));
                    f.addAll(args);
                    return f;
                }
            } catch (IOException | InterruptedException ignored) {
                // no flatpak
            }
        }
        File[] apps = new File(System.getProperty("user.home"), "Applications").listFiles();
        if (apps != null) {
            for (File f : apps) {
                String n = f.getName().toLowerCase(java.util.Locale.ROOT);
                if (n.endsWith(".appimage") && n.contains(emu.toLowerCase(java.util.Locale.ROOT)) && f.canExecute()) {
                    return join(f.getAbsolutePath(), args);
                }
            }
        }
        return out;   // not found: starting it fails with a helpful message
    }

    private static List<String> join(String prog, List<String> args) {
        List<String> l = new ArrayList<>();
        l.add(prog);
        l.addAll(args);
        return l;
    }

    /**
     * Mupen64Plus draws at its configured size (640x480 unless changed), fullscreen or not, so the
     * game sat small in a corner or in a little window. Unless the command sets a size, give it
     * the screen's: {@code --resolution WxH} right after the program.
     */
    /** Whether this runs Mupen64Plus (its program, not one of its libraries or folders). */
    static boolean isMupen(List<String> cmd) {
        for (String arg : cmd) {
            String name = arg.substring(Math.max(arg.lastIndexOf('/'), arg.lastIndexOf('\\')) + 1).toLowerCase(java.util.Locale.ROOT);
            if (name.equals("mupen64plus") || name.equals("mupen64plus.exe") || name.startsWith("mupen64plus-ui-console")) return true;
            if (arg.endsWith("/mupen64plus/run") || arg.endsWith("\\mupen64plus\\run")) return true;   // wiiuu-emulators' wrapper
        }
        return false;
    }

    /**
     * The phones' layout for Mupen64Plus' SDL input plugin, as --set options before the ROM: WII-UU's
     * virtual pad is an Xbox 360 pad with buttons by position (A east, B south, X north, Y west), so
     * the plugin's own "Microsoft X-Box 360 pad" profile, made for a real one, puts N64 A on the
     * phone's B and nothing on its A. SDL numbers the pad's buttons in key-code order (south 0, east
     * 1, north 2, west 3, L 4, R 5, - 6, + 7, home 8, L3 9, R3 10) and its axes X 0, Y 1, ZL 2, RX 3,
     * RY 4, ZR 5; the triggers rest at the negative end.
     *
     * <p>N64 A / B on the phone's A / B, Z on ZL, L / R on L / R (and ZR), Start on +, the C buttons on
     * the right stick and also C-up on X and C-left on Y, the analog stick on the left stick.
     * {@code pads} = 0: another controller is plugged in, so the plugin goes back to setting up
     * every controller itself (the settings are saved, so a layout from an earlier game would stay).
     */
    static List<String> mupenPads(List<String> cmd, int pads) {
        if (!isMupen(cmd) || cmd.isEmpty()) return cmd;
        List<String> set = new ArrayList<>();
        for (int n = 1; n <= 4; n++) {
            String s = "Input-SDL-Control" + n;
            if (pads == 0) {
                set.addAll(List.of("--set", s + "[mode]=2"));
                continue;
            }
            boolean on = n <= pads;
            String[][] p = {
                    {"version", "2"}, {"mode", "0"}, {"device", on ? Integer.toString(n - 1) : "-1"}, {"name", ""},
                    {"plugged", on ? "True" : "False"}, {"plugin", "2"}, {"mouse", "False"},
                    {"AnalogDeadzone", "4096,4096"}, {"AnalogPeak", "32768,32768"},
                    {"DPad R", "hat(0 Right)"}, {"DPad L", "hat(0 Left)"}, {"DPad D", "hat(0 Down)"}, {"DPad U", "hat(0 Up)"},
                    {"Start", "button(7)"}, {"Z Trig", "axis(2+)"}, {"A Button", "button(1)"}, {"B Button", "button(0)"},
                    {"C Button R", "axis(3+,24000)"}, {"C Button L", "button(3) axis(3-,24000)"},
                    {"C Button D", "axis(4+,24000)"}, {"C Button U", "button(2) axis(4-,24000)"},
                    {"R Trig", "button(5) axis(5+)"}, {"L Trig", "button(4)"},
                    {"Mempak switch", ""}, {"Rumblepak switch", ""},
                    {"X Axis", "axis(0-,0+)"}, {"Y Axis", "axis(1-,1+)"}};
            for (String[] kv : p) set.addAll(List.of("--set", s + "[" + kv[0] + "]=" + kv[1]));
        }
        List<String> out = new ArrayList<>(cmd);
        out.addAll(out.size() - 1, set);                          // the ROM stays last
        return out;
    }

    static List<String> mupenFillsScreen(List<String> cmd, java.awt.Dimension screen) {
        if (screen == null || cmd.contains("--resolution")) return cmd;
        for (int i = 0; i < cmd.size(); i++) {
            String arg = cmd.get(i);
            String name = arg.substring(Math.max(arg.lastIndexOf('/'), arg.lastIndexOf('\\')) + 1).toLowerCase(java.util.Locale.ROOT);
            if (!name.startsWith("mupen64plus") || name.endsWith(".so") || name.contains(".so.") || name.endsWith(".dll")) continue;
            List<String> out = new ArrayList<>(cmd);
            out.addAll(i + 1, List.of("--resolution", screen.width + "x" + screen.height));
            return out;
        }
        return cmd;
    }

    /** The main screen's size in real pixels (whatever the display scaling), or null without one. */
    private static java.awt.Dimension screenPixels() {
        try {
            if (java.awt.GraphicsEnvironment.isHeadless()) return null;
            java.awt.DisplayMode m = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDisplayMode();
            return m.getWidth() >= 320 && m.getHeight() >= 240 ? new java.awt.Dimension(m.getWidth(), m.getHeight()) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** "open -W -a Dolphin --args ..." -> "Dolphin" (null for anything else). */
    static String macAppName(List<String> cmd) {
        if (!MAC || cmd.isEmpty() || !(cmd.get(0).equals("open") || cmd.get(0).endsWith("/open"))) return null;
        for (int i = 1; i + 1 < cmd.size(); i++) {
            if (cmd.get(i).equals("--args")) break;
            if (cmd.get(i).equals("-a")) {
                String app = cmd.get(i + 1);
                int slash = app.lastIndexOf('/');
                if (slash >= 0) app = app.substring(slash + 1);          // "/Applications/Dolphin.app"
                return app.endsWith(".app") ? app.substring(0, app.length() - 4) : app;
            }
        }
        return null;
    }

    private static String flatpakId(List<String> cmd) {
        if (cmd.size() < 3 || !cmd.get(0).endsWith("flatpak") || !cmd.get(1).equals("run")) return null;
        for (int i = 2; i < cmd.size(); i++) if (!cmd.get(i).startsWith("-")) return cmd.get(i);
        return null;
    }

    static String onPath(String name) {
        String path = System.getenv("PATH");
        if (path == null) return null;
        for (String dir : path.split(File.pathSeparator)) {
            for (String ext : WINDOWS ? new String[]{"", ".exe", ".cmd", ".bat"} : new String[]{""}) {
                File f = new File(dir, name + ext);
                if (f.isFile() && f.canExecute()) return f.getAbsolutePath();
            }
        }
        return null;
    }

    /** Splits a command template into arguments (honouring quotes) and fills in placeholders. */
    static List<String> expand(String template, Game game) {
        List<String> out = new ArrayList<>();
        for (String tok : tokenize(template)) {
            out.add(tok.replace("{rom}", game.path().toAbsolutePath().toString())
                    .replace("{dir}", String.valueOf(game.path().toAbsolutePath().getParent()))
                    .replace("{name}", game.name()));
        }
        return out;
    }

    static List<String> tokenize(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inToken = false;
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
                else cur.append(c);
            } else if (c == '"' || c == '\'') {
                quote = c;
                inToken = true;
            } else if (Character.isWhitespace(c)) {
                if (inToken) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    inToken = false;
                }
            } else {
                cur.append(c);
                inToken = true;
            }
        }
        if (inToken) out.add(cur.toString());
        return out;
    }
}
