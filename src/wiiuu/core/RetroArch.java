package wiiuu.core;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import wiiuu.input.KeyMap;
import wiiuu.input.PadButton;

/**
 * RetroArch mode: runs every system that has a libretro core through RetroArch instead of the
 * standalone emulator ({@code retroarch.enabled=true}).
 *
 * <p>For each launch WII-UU finds RetroArch and the best installed core, downloading the core
 * from the libretro buildbot if none is installed. It starts RetroArch with an extra config that
 * binds exactly the keys WII-UU types and moves RetroArch's own keyboard shortcuts (reset,
 * fast-forward, full screen...) behind a modifier, so the phone can't trigger them by accident.
 * Systems without a core (Wii U, Switch, PS3, PS4) keep their standalone emulator.
 */
public final class RetroArch {
    private static final String OS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    private static final boolean MAC = OS.contains("mac"), WINDOWS = OS.contains("win");
    private static final String EXT = MAC ? "dylib" : WINDOWS ? "dll" : "so";

    /** Preferred cores per system, best first (names without "_libretro"). */
    static final Map<String, List<String>> CORES = Map.ofEntries(
            Map.entry("nes", List.of("mesen", "nestopia", "fceumm", "quicknes")),
            Map.entry("snes", List.of("snes9x", "bsnes", "snes9x2010")),
            Map.entry("gb", List.of("sameboy", "gambatte", "mgba")),
            Map.entry("gba", List.of("mgba", "vba_next", "gpsp")),
            Map.entry("n64", List.of("mupen64plus_next", "parallel_n64")),
            Map.entry("gc", List.of("dolphin")),
            Map.entry("wii", List.of("dolphin")),
            Map.entry("nds", List.of("melondsds", "melonds", "desmume")),
            Map.entry("3ds", List.of("citra")),
            Map.entry("sms", List.of("genesis_plus_gx", "picodrive", "smsplus")),
            Map.entry("genesis", List.of("genesis_plus_gx", "picodrive")),
            Map.entry("saturn", List.of("mednafen_saturn", "yabasanshiro", "kronos", "yabause")),
            Map.entry("dc", List.of("flycast")),
            Map.entry("ps1", List.of("swanstation", "mednafen_psx_hw", "pcsx_rearmed", "mednafen_psx")),
            Map.entry("ps2", List.of("pcsx2")),
            Map.entry("psp", List.of("ppsspp")));

    private final Config config;
    /** systems whose core download was already tried this session (so a failed one isn't retried forever) */
    private final java.util.Set<String> triedDownload = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Whether the phones are WII-UU's virtual controllers (Linux uinput) right now. */
    private volatile java.util.function.BooleanSupplier virtualPads = () -> false;

    public void setVirtualPads(java.util.function.BooleanSupplier virtualPads) {
        this.virtualPads = virtualPads;
    }

    public RetroArch(Config config) {
        this.config = config;
    }

    public boolean enabled() {
        return config.getBool("retroarch.enabled", false);
    }

    /** Whether this system should run in RetroArch (mode on, a core exists, not opted out). */
    public boolean handles(GameSystem s) {
        return enabled() && CORES.containsKey(s.id()) && config.getBool("system." + s.id() + ".retroarch", true);
    }

    /** Thrown when the core is being downloaded; the launch continues by itself afterwards. */
    public static final class Downloading extends Exception {
        public Downloading(String message) {
            super(message);
        }
    }

    /**
     * The RetroArch command for {@code game}, or null to use the standalone emulator (RetroArch or a
     * core missing and nothing to download).
     *
     * @param afterDownload run once a missing core has been downloaded (then {@link Downloading} is thrown)
     */
    public List<String> command(Game game, Runnable afterDownload) throws Downloading {
        return command(game, afterDownload, null);
    }

    /** @param split where RetroArch's window goes when the game's screens are split (real pixels), or null */
    public List<String> command(Game game, Runnable afterDownload, java.awt.Rectangle split) throws Downloading {
        List<String> exe = executable();
        if (exe == null) {
            System.err.println("[retroarch] RetroArch not found; using " + game.system().emulator());
            return null;
        }
        String id = game.system().id();
        Path core = findCore(id, exe);
        if (core == null) {
            if (!config.getBool("retroarch.download", true) || buildbot() == null || !triedDownload.add(id)) {
                System.err.println("[retroarch] no core for " + game.system().name() + " installed; using "
                        + game.system().emulator() + " (install one of " + CORES.get(id) + " in RetroArch's Online Updater)");
                return null;
            }
            String name = CORES.get(id).get(0);
            Thread t = new Thread(() -> {
                Path got = null;
                for (String n : CORES.get(id)) {
                    got = download(n, exe);
                    if (got != null) break;
                }
                if (got == null) System.err.println("[retroarch] could not download a core for " + game.system().name());
                afterDownload.run();              // with a core now, or falls back to the standalone emulator
            }, "core-download");
            t.setDaemon(true);
            t.start();
            throw new Downloading("Getting the RetroArch core for " + game.system().name() + " (" + name
                    + ")\u2026 the game starts by itself in a moment");
        }
        List<String> cmd = new ArrayList<>(exe);
        if (split == null && config.getBool("retroarch.fullscreen", true)) cmd.add("-f");
        // --verbose: RetroArch's own log goes to logs/<system>.log (why a game didn't start)
        cmd.addAll(List.of("--verbose", "-L", core.toString()));
        Path extra = writeExtraConfig(split, id.equals("n64") && (n64Software() || n64Interpreter()) ? n64Options(exe) : null,
                userCoreDir(exe).getParent(), forRetroArch(exe, "retroarch.cfg"));
        if (extra != null && config.getBool("retroarch.appendConfig", true)) cmd.addAll(List.of("--appendconfig", extra.toString()));
        cmd.add(game.path().toAbsolutePath().toString());
        return cmd;
    }

    // ---- N64: OpenGL or the software renderer ---------------------------------------------------

    /**
     * Whether N64 games draw with the cores' software renderer (Angrylion) instead of OpenGL
     * (GLideN64): {@code retroarch.n64.renderer} = software, or auto after OpenGL failed on this
     * computer ({@code retroarch.n64.glFailed}, set when the core crashed creating its context).
     */
    public boolean n64Software() {
        String r = config.get("retroarch.n64.renderer", "auto");
        return r.equals("software") || r.equals("auto") && config.getBool("retroarch.n64.glFailed", false);
    }

    /**
     * Whether the N64 cores run the game with their cached interpreter instead of the dynamic
     * recompiler: {@code retroarch.n64.cpu} = interpreter, or auto after the recompiler crashed on
     * this computer ({@code retroarch.n64.dynarecFailed}; it does on some ARM systems, such as a
     * Raspberry Pi 5 with 16 KB memory pages).
     */
    public boolean n64Interpreter() {
        String c = config.get("retroarch.n64.cpu", "auto");
        return c.equals("interpreter") || c.equals("auto") && config.getBool("retroarch.n64.dynarecFailed", false);
    }

    /** True when RetroArch crashed (a signal, not a normal error) right after the N64 core started its recompiler. */
    public static boolean dynarecCrashed(Path log, int exitCode) {
        if (exitCode != 139 && exitCode != 134 && exitCode != 132 && exitCode != 135 && exitCode != -11 && exitCode != 11) return false;
        if (log == null || !Files.isRegularFile(log)) return false;
        try {
            List<String> lines = Files.readAllLines(log, java.nio.charset.StandardCharsets.ISO_8859_1);
            for (int i = Math.max(0, lines.size() - 25); i < lines.size(); i++) {
                if (lines.get(i).toLowerCase(Locale.ROOT).contains("dynarec")) return true;
            }
        } catch (IOException e) {
            return false;
        }
        return false;
    }

    /**
     * Settings earlier WII-UU versions gave RetroArch for one game that RetroArch saved into its own
     * retroarch.cfg on exit: WII-UU's N64 core options file, and the RetroAchievements sign-in when
     * that's off in WII-UU now. Returns the lines that set them back (RetroArch's file isn't touched).
     */
    private String undoLeftovers(Path raConfigDir, boolean n64Options) {
        if (raConfigDir == null) return "";
        Path main = raConfigDir.resolve("retroarch.cfg");
        if (!Files.isRegularFile(main)) return "";
        StringBuilder out = new StringBuilder();
        try {
            String text = Files.readString(main, java.nio.charset.StandardCharsets.ISO_8859_1);
            String ours = config.home().resolve("retroarch-core-options.cfg").toAbsolutePath().toString().replace("\\", "/");
            String oursSnap = snapHome().resolve("wiiuu").resolve("retroarch-core-options.cfg").toString();
            if (!n64Options && (text.contains(ours) || text.contains(oursSnap))) {
                out.append("global_core_options = \"false\"\n");
                out.append("core_options_path = \"\"\n");
                System.out.println("[retroarch] undoing WII-UU's N64 core options that RetroArch saved into " + main);
            }
            if (Achievements.retroarchConfig(config).isEmpty() && text.matches("(?s).*cheevos_enable\\s*=\\s*\"true\".*")
                    && !config.get("ra.user", "").isBlank()
                    && text.contains("cheevos_username = \"" + config.get("ra.user", "").trim() + "\"")) {
                out.append("cheevos_enable = \"false\"\n");
                System.out.println("[retroarch] RetroAchievements is off in WII-UU: turning off the sign-in RetroArch saved");
            }
        } catch (IOException ignored) {
            // nothing to undo then
        }
        return out.toString();
    }

    /** True when this RetroArch log shows a core failing to get its OpenGL context. */
    public static boolean openGlFailed(Path log) {
        if (log == null || !Files.isRegularFile(log)) return false;
        try {
            String text = Files.readString(log, java.nio.charset.StandardCharsets.ISO_8859_1);
            if (text.length() > 400_000) text = text.substring(text.length() - 400_000);
            return text.contains("Failed to create new context") || text.contains("Failed to create GL context")
                    || text.contains("Failed to create shared context");
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * WII-UU's copy of the N64 cores' options: your own options for Mupen64Plus-Next and ParaLLEl N64
     * (from RetroArch's per-core files or its global file), with the software renderer and / or the
     * interpreter chosen. RetroArch's own files stay as they are.
     */
    private Path n64Options(List<String> exe) {
        Path raConfig = userCoreDir(exe).getParent();
        java.util.LinkedHashMap<String, String> opts = new java.util.LinkedHashMap<>();
        for (Path f : List.of(raConfig.resolve("retroarch-core-options.cfg"),
                raConfig.resolve("config").resolve("Mupen64Plus-Next").resolve("Mupen64Plus-Next.opt"),
                raConfig.resolve("config").resolve("ParaLLEl N64").resolve("ParaLLEl N64.opt"))) {
            if (!Files.isRegularFile(f)) continue;
            try {
                for (String line : Files.readAllLines(f)) {
                    int eq = line.indexOf('=');
                    if (eq < 0 || line.trim().startsWith("#")) continue;
                    String k = line.substring(0, eq).trim();
                    if (k.startsWith("mupen64plus-") || k.startsWith("parallel-n64-")) opts.put(k, line.substring(eq + 1).trim());
                }
            } catch (IOException ignored) {
                // just the defaults then
            }
        }
        if (n64Software()) {
            opts.put("mupen64plus-rdp-plugin", "\"angrylion\"");      // Mupen64Plus-Next: software RDP...
            opts.put("mupen64plus-rsp-plugin", "\"cxd4\"");            // ...which needs a low-level RSP
            opts.put("parallel-n64-gfxplugin", "\"angrylion\"");      // ParaLLEl N64: the same
            opts.put("parallel-n64-rspplugin", "\"cxd4\"");
        }
        if (n64Interpreter()) {
            opts.put("mupen64plus-cpucore", "\"cached_interpreter\"");
            opts.put("parallel-n64-cpucore", "\"cached_interpreter\"");
        }
        StringBuilder out = new StringBuilder("# written by WII-UU: N64 core options for this computer"
                + (n64Software() ? " (software renderer: no OpenGL needed)" : "")
                + (n64Interpreter() ? " (interpreter: the recompiler crashes here)" : "") + "\n");
        for (var e : opts.entrySet()) out.append(e.getKey()).append(" = ").append(e.getValue()).append('\n');
        Path file = forRetroArch(exe, "retroarch-core-options.cfg");
        try {
            Files.writeString(file, out.toString());
            return file;
        } catch (IOException e) {
            System.err.println("[retroarch] couldn't write " + file + ": " + e.getMessage());
            return null;
        }
    }

    // ---- finding RetroArch and its cores -------------------------------------------------------

    /** The command prefix that starts RetroArch, or null. */
    List<String> executable() {
        String custom = config.get("retroarch.path", "").trim();
        if (!custom.isEmpty()) return new File(custom).canExecute() ? List.of(custom) : null;
        String home = System.getProperty("user.home");
        if (MAC) {
            for (String app : new String[]{"/Applications/RetroArch.app", home + "/Applications/RetroArch.app"}) {
                File bin = new File(app, "Contents/MacOS/RetroArch");
                if (bin.canExecute()) return List.of(bin.getAbsolutePath());   // the binary itself: WII-UU can stop it
            }
            return null;
        }
        String onPath = Launcher.onPath("retroarch");
        if (onPath != null) return List.of(onPath);
        if (WINDOWS) {
            String appdata = System.getenv("APPDATA");
            for (String p : new String[]{"C:\\RetroArch-Win64\\retroarch.exe", "C:\\RetroArch\\retroarch.exe",
                    appdata == null ? null : appdata + "\\RetroArch\\retroarch.exe",
                    System.getenv("ProgramFiles") + "\\RetroArch\\retroarch.exe"}) {
                if (p != null && new File(p).canExecute()) return List.of(p);
            }
            return null;
        }
        for (String p : new String[]{home + "/.local/share/wiiuu-emulators/emulators/retroarch/run",
                home + "/Applications/RetroArch.AppImage", "/usr/games/retroarch"}) {
            if (new File(p).canExecute()) return List.of(p);
        }
        if (Launcher.onPath("flatpak") != null && run("flatpak", "info", "org.libretro.RetroArch")) {
            return List.of("flatpak", "run", "org.libretro.RetroArch");
        }
        return null;
    }

    private boolean flatpak(List<String> exe) {
        return exe.size() > 2 && exe.get(0).endsWith("flatpak");
    }

    /** Where RetroArch keeps its downloaded cores. */
    /** RetroArch as a Snap (/snap/bin/retroarch): its own home, and it may not read hidden folders like ~/.wiiuu. */
    static boolean snap(List<String> exe) {
        if (exe == null || exe.isEmpty()) return false;
        String p = exe.get(0);
        if (p.startsWith("/snap/")) return true;
        try {
            return Path.of(p).toRealPath().toString().startsWith("/snap/") || Path.of(p).toRealPath().toString().endsWith("/snap");
        } catch (IOException e) {
            return false;
        }
    }

    private static Path snapHome() {
        return Path.of(System.getProperty("user.home"), "snap", "retroarch", "common");
    }

    /** Where WII-UU puts a file RetroArch must read: in the Snap's own folder for a Snap, else ~/.wiiuu. */
    private Path forRetroArch(List<String> exe, String name) {
        if (snap(exe)) {
            Path dir = snapHome().resolve("wiiuu");
            try {
                Files.createDirectories(dir);
                return dir.resolve(name);
            } catch (IOException e) {
                System.err.println("[retroarch] couldn't make " + dir + ": " + e.getMessage());
            }
        }
        return config.home().resolve(name);
    }

    Path userCoreDir(List<String> exe) {
        String home = System.getProperty("user.home");
        if (MAC) return Path.of(home, "Library", "Application Support", "RetroArch", "cores");
        if (WINDOWS) {
            String exePath = exe.get(0);
            Path besideExe = Path.of(exePath).getParent().resolve("cores");       // portable installs
            if (Files.isDirectory(besideExe)) return besideExe;
            String appdata = System.getenv("APPDATA");
            return Path.of(appdata == null ? home : appdata, "RetroArch", "cores");
        }
        if (flatpak(exe)) return Path.of(home, ".var", "app", "org.libretro.RetroArch", "config", "retroarch", "cores");
        if (snap(exe)) return snapHome().resolve(".config").resolve("retroarch").resolve("cores");
        String xdg = System.getenv("XDG_CONFIG_HOME");
        return (xdg != null && !xdg.isBlank() ? Path.of(xdg) : Path.of(home, ".config")).resolve("retroarch").resolve("cores");
    }

    private List<Path> coreDirs(List<String> exe) {
        List<Path> dirs = new ArrayList<>();
        dirs.add(userCoreDir(exe));
        if (MAC) {
            dirs.add(Path.of("/Applications/RetroArch.app/Contents/Resources/cores"));
        } else if (!WINDOWS) {
            // distribution packages (libretro-* / retroarch-core-*)
            for (String d : new String[]{"/usr/lib/x86_64-linux-gnu/libretro", "/usr/lib/aarch64-linux-gnu/libretro",
                    "/usr/lib/arm-linux-gnueabihf/libretro", "/usr/lib/libretro", "/usr/lib64/libretro",
                    "/usr/local/lib/libretro", "/app/lib/libretro"}) {
                dirs.add(Path.of(d));
            }
        }
        return dirs;
    }

    /** The installed core to use for a system: retroarch.core.&lt;id&gt; (name or path), else the best found. */
    Path findCore(String systemId, List<String> exe) {
        String custom = config.get("retroarch.core." + systemId, "").trim();
        List<String> names = CORES.getOrDefault(systemId, List.of());
        if (!custom.isEmpty()) {
            Path p = Path.of(custom);
            if (custom.contains(File.separator) || custom.contains("/")) return Files.exists(p) ? p : null;
            names = List.of(custom.replace("_libretro", ""));
        }
        for (String n : names) {
            for (Path dir : coreDirs(exe)) {
                Path core = dir.resolve(n + "_libretro." + EXT);
                if (Files.isRegularFile(core)) return core;
            }
        }
        return null;
    }

    // ---- downloading cores ----------------------------------------------------------------------

    /** The libretro buildbot folder for this computer, or null where there is none. */
    static String buildbot() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean arm = arch.contains("aarch64") || arch.contains("arm64");
        boolean x64 = arch.contains("amd64") || arch.contains("x86_64");
        String base = "https://buildbot.libretro.com/nightly/";
        if (MAC) return base + "apple/osx/" + (arm ? "arm64" : "x86_64") + "/latest/";
        if (WINDOWS) return x64 ? base + "windows/x86_64/latest/" : null;
        if (x64) return base + "linux/x86_64/latest/";
        if (arm) return base + "linux/aarch64/latest/";
        return null;
    }

    /** Downloads one core into RetroArch's core folder; null if it isn't available. */
    Path download(String name, List<String> exe) {
        String url = buildbot() + name + "_libretro." + EXT + ".zip";
        Path dir = userCoreDir(exe);
        Path target = dir.resolve(name + "_libretro." + EXT);
        try {
            Files.createDirectories(dir);
            HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(15)).build();
            HttpResponse<InputStream> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5))
                    .header("User-Agent", "WII-UU").build(), HttpResponse.BodyHandlers.ofInputStream());
            if (r.statusCode() != 200) {
                r.body().close();
                return null;
            }
            System.out.println("[retroarch] downloading " + name + " core from " + url);
            Path tmp = Files.createTempFile(dir, name, ".part");
            boolean found = false;
            try (ZipInputStream z = new ZipInputStream(r.body())) {
                ZipEntry e;
                while ((e = z.getNextEntry()) != null) {
                    if (e.getName().endsWith("_libretro." + EXT)) {
                        Files.copy(z, tmp, StandardCopyOption.REPLACE_EXISTING);
                        found = true;
                        break;
                    }
                }
            }
            if (!found) {
                Files.deleteIfExists(tmp);
                return null;
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            if (MAC) run("xattr", "-d", "com.apple.quarantine", target.toString());   // let macOS load it
            System.out.println("[retroarch] installed " + target);
            return target;
        } catch (IOException | RuntimeException e) {
            System.err.println("[retroarch] core download failed (" + url + "): " + e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    // ---- input ----------------------------------------------------------------------------------

    /** RetroArch's names for the gamepad buttons, by position like WII-UU's (A = east, B = south). */
    private static final Map<PadButton, String> RA_BUTTONS = Map.ofEntries(
            Map.entry(PadButton.A, "a"), Map.entry(PadButton.B, "b"), Map.entry(PadButton.X, "x"), Map.entry(PadButton.Y, "y"),
            Map.entry(PadButton.L, "l"), Map.entry(PadButton.R, "r"), Map.entry(PadButton.ZL, "l2"), Map.entry(PadButton.ZR, "r2"),
            Map.entry(PadButton.L3, "l3"), Map.entry(PadButton.R3, "r3"),
            Map.entry(PadButton.PLUS, "start"), Map.entry(PadButton.MINUS, "select"),
            Map.entry(PadButton.UP, "up"), Map.entry(PadButton.DOWN, "down"),
            Map.entry(PadButton.LEFT, "left"), Map.entry(PadButton.RIGHT, "right"),
            Map.entry(PadButton.LS_LEFT, "l_x_minus"), Map.entry(PadButton.LS_RIGHT, "l_x_plus"),
            Map.entry(PadButton.LS_UP, "l_y_minus"), Map.entry(PadButton.LS_DOWN, "l_y_plus"),
            Map.entry(PadButton.RS_LEFT, "r_x_minus"), Map.entry(PadButton.RS_RIGHT, "r_x_plus"),
            Map.entry(PadButton.RS_UP, "r_y_minus"), Map.entry(PadButton.RS_DOWN, "r_y_plus"));

    /**
     * Writes ~/.wiiuu/retroarch.cfg, which RetroArch loads on top of its own settings for games
     * started from WII-UU: the keys WII-UU types for each player, and hotkeys only while right Ctrl
     * is held (otherwise stick keys like H, F, L and K would reset the game, toggle full screen,
     * fast-forward...).
     */
    Path writeExtraConfig() {
        return writeExtraConfig(null, null, null, config.home().resolve("retroarch.cfg"));
    }

    Path writeExtraConfig(java.awt.Rectangle split, Path coreOptions, Path raConfigDir, Path file) {
        StringBuilder cfg = new StringBuilder("# written by WII-UU for games it starts in RetroArch\n");
        // full screen as a borderless window: switching the screen mode froze some X11 desktops
        if (split == null) cfg.append("video_windowed_fullscreen = \"true\"\n");
        // RetroArch saves its settings on exit, these included: they'd stay in its own config for every
        // game afterwards. So it doesn't save them, and what earlier versions left there is undone.
        cfg.append("config_save_on_exit = \"false\"\n");
        cfg.append(undoLeftovers(raConfigDir, coreOptions != null));
        if (coreOptions != null) {
            // this game's core options come from WII-UU's copy (the N64 software renderer)
            cfg.append("global_core_options = \"true\"\n");
            cfg.append("core_options_path = \"").append(coreOptions.toAbsolutePath().toString().replace("\\", "/")).append("\"\n");
        }
        if (split != null) {
            // split screens: a borderless window exactly the size of both screens, on WII-UU's hidden display
            cfg.append("video_fullscreen = \"false\"\n");
            cfg.append("video_window_show_decorations = \"false\"\n");
            cfg.append("ui_menubar_enable = \"false\"\n");
            cfg.append("video_window_save_positions = \"true\"\n");
            cfg.append("video_windowed_position_x = \"").append(split.x).append("\"\n");
            cfg.append("video_windowed_position_y = \"").append(split.y).append("\"\n");
            cfg.append("video_windowed_position_width = \"").append(split.width).append("\"\n");
            cfg.append("video_windowed_position_height = \"").append(split.height).append("\"\n");
            cfg.append("video_scale_integer = \"false\"\n");
        }
        cfg.append("pause_nonactive = \"false\"\n");
        cfg.append("quit_press_twice = \"false\"\n");
        cfg.append("input_enable_hotkey = \"").append(config.get("retroarch.hotkeyEnable", "rctrl")).append("\"\n");
        if (virtualPads.getAsBoolean() && config.getBool("retroarch.padBinds", true)) {
            List<String> others = otherJoysticks();
            if (others.isEmpty()) {
                cfg.append(padBinds());
            } else {
                System.out.println("[retroarch] controllers besides the phones (" + String.join(", ", others)
                        + "): leaving the controller layout to RetroArch's own profiles");
            }
        }
        KeyMap keys = new KeyMap(config);
        for (int player = 1; player <= KeyMap.MAX_PLAYERS; player++) {
            for (Map.Entry<PadButton, String> e : RA_BUTTONS.entrySet()) {
                String key = raKey(keys.keyName(player, e.getKey()));
                cfg.append("input_player").append(player).append('_').append(e.getValue())
                        .append(" = \"").append(key).append("\"\n");
            }
        }
        String cheevos = Achievements.retroarchConfig(config);       // RetroAchievements: signed in as you
        cfg.append(cheevos);
        try {
            Files.writeString(file, cfg.toString());
            if (!cheevos.isEmpty()) {
                try {                                                  // it has your password in it: yours only
                    Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
                } catch (UnsupportedOperationException ignored) {
                    // (Windows: the home folder is the user's own anyway)
                }
            }
            return file;
        } catch (IOException e) {
            System.err.println("[retroarch] could not write " + file + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * The layout of WII-UU's virtual controller (vpad.py: an Xbox 360 pad, buttons by position), bound
     * for every port. RetroArch would otherwise need its autoconfig profile for "Microsoft X-Box 360
     * pad", which many packages (Debian, Ubuntu, Raspberry Pi OS) don't ship: it then says "... not
     * configured" for every phone and ignores its buttons. RetroArch's udev driver numbers buttons in
     * key-code order (BTN_SOUTH 0, BTN_EAST 1, BTN_NORTH 2, BTN_WEST 3, TL 4, TR 5, SELECT 6, START 7,
     * MODE 8, THUMBL 9, THUMBR 10) and axes in axis-code order (X 0, Y 1, Z 2, RX 3, RY 4, RZ 5); the
     * d-pad is hat 0, and the triggers rest at the negative end.
     */
    static String padBinds() {
        StringBuilder cfg = new StringBuilder();
        // the "not configured" notices are moot now; real controllers are not bound here (see otherJoysticks)
        cfg.append("notification_show_autoconfig = \"false\"\n");
        String[][] binds = {
                {"a_btn", "1"}, {"b_btn", "0"}, {"x_btn", "2"}, {"y_btn", "3"},
                {"l_btn", "4"}, {"r_btn", "5"}, {"select_btn", "6"}, {"start_btn", "7"},
                {"l3_btn", "9"}, {"r3_btn", "10"},
                {"up_btn", "h0up"}, {"down_btn", "h0down"}, {"left_btn", "h0left"}, {"right_btn", "h0right"},
                {"l2_axis", "+2"}, {"r2_axis", "+5"},
                {"l_x_minus_axis", "-0"}, {"l_x_plus_axis", "+0"}, {"l_y_minus_axis", "-1"}, {"l_y_plus_axis", "+1"},
                {"r_x_minus_axis", "-3"}, {"r_x_plus_axis", "+3"}, {"r_y_minus_axis", "-4"}, {"r_y_plus_axis", "+4"}};
        for (int player = 1; player <= KeyMap.MAX_PLAYERS; player++) {
            for (String[] b : binds) {
                cfg.append("input_player").append(player).append('_').append(b[0]).append(" = \"").append(b[1]).append("\"\n");
            }
        }
        return cfg.toString();
    }

    static List<String> otherJoysticks() {
        return Joysticks.others();
    }

    static List<String> otherJoysticks(String devices) {
        return Joysticks.parse(devices, false);
    }

    /** AWT key name (as in keys.pN.X) to RetroArch's key name; "nul" when unmapped. */
    static String raKey(String awt) {
        if (awt == null || awt.isBlank()) return "nul";
        String k = awt.trim().toUpperCase(Locale.ROOT);
        if (k.length() == 1 && Character.isLetter(k.charAt(0))) return k.toLowerCase(Locale.ROOT);
        if (k.length() == 1 && Character.isDigit(k.charAt(0))) return "num" + k;
        if (k.matches("F\\d{1,2}")) return k.toLowerCase(Locale.ROOT);
        if (k.matches("NUMPAD\\d")) return "keypad" + k.substring(6);
        return switch (k) {
            case "UP", "DOWN", "LEFT", "RIGHT", "SPACE", "TAB", "HOME", "END", "INSERT", "ESCAPE", "PERIOD", "COMMA",
                    "SLASH", "BACKSLASH", "SEMICOLON", "QUOTE", "EQUALS", "MINUS" -> k.toLowerCase(Locale.ROOT);
            case "ENTER" -> "enter";
            case "SHIFT" -> "shift";
            case "CONTROL" -> "ctrl";
            case "ALT" -> "alt";
            case "BACK_SPACE" -> "backspace";
            case "DELETE" -> "del";
            case "PAGE_UP" -> "pageup";
            case "PAGE_DOWN" -> "pagedown";
            case "OPEN_BRACKET" -> "leftbracket";
            case "CLOSE_BRACKET" -> "rightbracket";
            case "BACK_QUOTE" -> "backquote";
            default -> "nul";
        };
    }

    private static boolean run(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            return p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
