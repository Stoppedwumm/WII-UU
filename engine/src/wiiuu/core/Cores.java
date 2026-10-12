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

/**
 * Libretro cores: which ones play which console, where they are (RetroArch's folders, its Snap's and
 * Flatpak's, distribution packages, Choccy Milk's own folder) and getting them from the libretro
 * buildbot. Shared by WII-UU (RetroArch mode, Choccy Milk) and the Choccy Milk app; RetroArch need
 * not be installed.
 */
public final class Cores {
    private static final String OS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    private static final boolean MAC = OS.contains("mac"), WINDOWS = OS.contains("win");
    /** A core's file extension here (so, dylib, dll). */
    public static final String EXT = MAC ? "dylib" : WINDOWS ? "dll" : "so";

    /** Preferred cores per system, best first (names without "_libretro"). */
    public static final Map<String, List<String>> CORES = Map.ofEntries(
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

    public Cores(Config config) {
        this.config = config;
    }

    /** Whether there are cores for this console at all. */
    public static boolean has(GameSystem s) {
        return CORES.containsKey(s.id());
    }

    /** Marks a console's core download as tried; false if it was already (don't try again this session). */
    public boolean firstTry(String systemId) {
        return triedDownload.add(systemId);
    }

    /** Thrown when the core is being downloaded; the launch continues by itself afterwards. */
    public static final class Downloading extends Exception {
        public Downloading(String message) {
            super(message);
        }
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

    /** The command prefix that starts RetroArch, or null. */
    public List<String> executable() {
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
        String onPath = onPath("retroarch");
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
        if (onPath("flatpak") != null && run("flatpak", "info", "org.libretro.RetroArch")) {
            return List.of("flatpak", "run", "org.libretro.RetroArch");
        }
        return null;
    }

    /**
     * The core to play this game with in Choccy Milk (installed, or downloaded first: then
     * {@link Downloading} is thrown and afterDownload runs once it's there). Null when there is none.
     */
    public Path coreFor(Game game, Runnable afterDownload) throws Downloading {
        return coreFor(game.system(), afterDownload);
    }

    /** The same, by console. */
    public Path coreFor(GameSystem system, Runnable afterDownload) throws Downloading {
        List<String> exe = executable();
        String id = system.id();
        // WII-UU translated (Rosetta): Choccy Milk runs natively, so RetroArch's cores (for WII-UU's
        // processor) won't do; it keeps its own, by processor
        boolean translated = Choccy.translated();
        Path own = translated ? choccyCoreDir().resolve(Choccy.arch()) : choccyCoreDir();
        Path core;
        if (!translated) {
            core = findCore(id, exe);
            if (core != null) return core;
            // RetroArch's own cores (its Snap / Flatpak folders) even when it isn't the one WII-UU would start
            for (List<String> other : List.of(List.of("/snap/bin/retroarch"), List.of("flatpak", "run", "org.libretro.RetroArch"))) {
                core = findCore(id, other);
                if (core != null) return core;
            }
        }
        // Choccy Milk's own cores (downloaded when there's no RetroArch at all)
        core = findCore(id, List.of(), own);
        if (core != null) return core;
        String from = buildbot(Choccy.arch());
        if (!config.getBool("retroarch.download", true) || from == null || !triedDownload.add(id)) return null;
        Path into = translated || exe == null ? own : userCoreDir(exe);
        Thread t = new Thread(() -> {
            for (String n : CORES.get(id)) {
                if (download(n, into, from) != null) break;
            }
            afterDownload.run();
        }, "core-download");
        t.setDaemon(true);
        t.start();
        throw new Downloading("Getting the core for " + system.name() + " (" + CORES.get(id).get(0)
                + ")\u2026 the game starts by itself in a moment");
    }

    /** RetroArch's system folder (BIOS files), when there is one. */
    public Path systemDir() {
        List<String> exe = executable();
        for (List<String> e : java.util.Arrays.asList(exe, List.of("/snap/bin/retroarch"))) {
            if (e == null) continue;
            Path dir = userCoreDir(e).getParent().resolve("system");
            if (Files.isDirectory(dir)) return dir;
        }
        return config.home().resolve("system");
    }

    /** Core options Choccy Milk gives this console's cores: N64 without OpenGL (and without the recompiler if it crashed). */
    public java.util.Map<String, String> choccyOptions(String systemId) {
        java.util.Map<String, String> o = new java.util.LinkedHashMap<>();
        if (systemId.equals("n64")) {
            o.put("mupen64plus-rdp-plugin", "angrylion");
            o.put("mupen64plus-rsp-plugin", "cxd4");
            o.put("parallel-n64-gfxplugin", "angrylion");
            o.put("parallel-n64-rspplugin", "cxd4");
            if (n64Interpreter()) {
                o.put("mupen64plus-cpucore", "cached_interpreter");
                o.put("parallel-n64-cpucore", "cached_interpreter");
            }
        }
        return o;
    }

    public static boolean flatpak(List<String> exe) {
        return exe != null && exe.size() > 2 && exe.get(0).endsWith("flatpak");
    }

    /** RetroArch as a Snap (/snap/bin/retroarch): its own home, and it may not read hidden folders like ~/.wiiuu. */
    public static boolean snap(List<String> exe) {
        if (exe == null || exe.isEmpty()) return false;
        String p = exe.get(0);
        if (p.startsWith("/snap/")) return true;
        try {
            return Path.of(p).toRealPath().toString().startsWith("/snap/") || Path.of(p).toRealPath().toString().endsWith("/snap");
        } catch (IOException e) {
            return false;
        }
    }

    public static Path snapHome() {
        return Path.of(System.getProperty("user.home"), "snap", "retroarch", "common");
    }


    public Path userCoreDir(List<String> exe) {
        String home = System.getProperty("user.home");
        if (MAC) return Path.of(home, "Library", "Application Support", "RetroArch", "cores");
        if (WINDOWS) {
            if (exe != null && !exe.isEmpty() && Path.of(exe.get(0)).getParent() != null) {
                Path besideExe = Path.of(exe.get(0)).getParent().resolve("cores");     // portable installs
                if (Files.isDirectory(besideExe)) return besideExe;
            }
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
    public Path findCore(String systemId, List<String> exe) {
        return findCore(systemId, exe, null);
    }

    /** Choccy Milk's core folder, for when RetroArch isn't installed: &lt;home&gt;/choccy/cores. */
    public Path choccyCoreDir() {
        return config.home().resolve("choccy").resolve("cores");
    }

    private Path findCore(String systemId, List<String> exe, Path only) {
        String custom = config.get("retroarch.core." + systemId, "").trim();
        List<String> names = CORES.getOrDefault(systemId, List.of());
        if (!custom.isEmpty()) {
            Path p = Path.of(custom);
            if (custom.contains(File.separator) || custom.contains("/")) return Files.exists(p) ? p : null;
            names = List.of(custom.replace("_libretro", ""));
        }
        for (String n : names) {
            for (Path dir : only != null ? List.of(only) : coreDirs(exe)) {
                Path core = dir.resolve(n + "_libretro." + EXT);
                if (Files.isRegularFile(core)) return core;
            }
        }
        return null;
    }

    // ---- downloading cores ----------------------------------------------------------------------

    /** The libretro buildbot folder for this computer, or null where there is none. */
    public static String buildbot() {
        return buildbot(System.getProperty("os.arch", ""));
    }

    /** The buildbot folder for a processor ("arm64", "aarch64", "x64", "amd64"...). */
    public static String buildbot(String arch) {
        arch = arch.toLowerCase(Locale.ROOT);
        boolean arm = arch.contains("aarch64") || arch.contains("arm64");
        boolean x64 = arch.contains("amd64") || arch.contains("x86_64") || arch.equals("x64");
        String base = "https://buildbot.libretro.com/nightly/";
        if (MAC) return base + "apple/osx/" + (arm ? "arm64" : "x86_64") + "/latest/";
        if (WINDOWS) return x64 ? base + "windows/x86_64/latest/" : null;
        if (x64) return base + "linux/x86_64/latest/";
        if (arm) return base + "linux/aarch64/latest/";
        return null;
    }

    /** Downloads one core into RetroArch's core folder; null if it isn't available. */
    public Path download(String name, List<String> exe) {
        return download(name, userCoreDir(exe));
    }

    /** Downloads one core into dir; null if it isn't available. */
    public Path download(String name, Path dir) {
        return download(name, dir, buildbot());
    }

    private Path download(String name, Path dir, String from) {
        String url = from + name + "_libretro." + EXT + ".zip";
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
            System.out.println("[cores] downloading " + name + " core from " + url);
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
            System.out.println("[cores] installed " + target);
            return target;
        } catch (IOException | RuntimeException e) {
            System.err.println("[cores] download failed (" + url + "): " + e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** A program on the PATH, or null. */
    public static String onPath(String name) {
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
