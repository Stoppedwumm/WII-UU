package wiiuu.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Xbox Cloud Gaming client integration using the Greenlight desktop application.
 * Linux uses the official Flathub app, including aarch64; macOS/Windows can use
 * an explicitly selected or WII-UU-managed desktop executable.
 *
 * Greenlight owns Microsoft sign-in and all streaming. WII-UU never handles
 * account tokens, captures WebRTC packets or loads an untrusted script.
 */
public final class Greenlight {
    public static final String FLATPAK_ID = "io.github.unknownskl.greenlight";
    private Greenlight() {}

    static boolean linux() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
    }

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static boolean mac() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }

    /** Resolves a manually chosen binary or macOS .app bundle. */
    static Path executableAt(Path selected) {
        if (selected == null) return null;
        try {
            Path path = selected.toAbsolutePath().normalize();
            if (Files.isDirectory(path) && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".app")) {
                for (String name : List.of("Greenlight", "greenlight", "Greenlight Desktop")) {
                    Path binary = path.resolve("Contents/MacOS").resolve(name);
                    if (Files.isRegularFile(binary) && Files.isExecutable(binary)) return binary;
                }
                return null;
            }
            return Files.isRegularFile(path) && Files.isExecutable(path) ? path : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Explicit Greenlight path overrides Flatpak and auto-detection. Legacy OpenXCloud paths are ignored. */
    public static Path executable(Config config) {
        String requested = config.get("greenlight.executable", "").trim();
        if (!requested.isEmpty()) {
            try { return executableAt(Path.of(requested)); }
            catch (RuntimeException ignored) { return null; }
        }

        String name = windows() ? "Greenlight.exe" : "greenlight";
        List<Path> candidates = new ArrayList<>();
        String home = System.getProperty("user.home", "");
        if (!home.isBlank()) {
            Path user = Path.of(home);
            candidates.add(user.resolve("Applications/Greenlight.app"));
            if (windows()) {
                candidates.add(user.resolve("AppData/Local/Programs/greenlight/Greenlight.exe"));
                candidates.add(user.resolve("AppData/Local/Programs/Greenlight/Greenlight.exe"));
            }
        }
        if (mac()) {
            candidates.add(Path.of("/Applications/Greenlight.app"));
            candidates.add(Path.of("/opt/homebrew/bin/greenlight"));
        }
        if (!windows()) {
            candidates.add(Path.of("/usr/local/bin/greenlight"));
            candidates.add(Path.of("/usr/bin/greenlight"));
        } else {
            for (String env : List.of("LOCALAPPDATA", "ProgramFiles", "ProgramFiles(x86)")) {
                String path = System.getenv(env);
                if (path != null && !path.isBlank()) {
                    candidates.add(Path.of(path, "Greenlight", "Greenlight.exe"));
                }
            }
        }
        String search = System.getenv("PATH");
        if (search != null) {
            for (String directory : search.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
                if (directory.isBlank()) continue;
                try { candidates.add(Path.of(directory, name)); }
                catch (RuntimeException ignored) { /* malformed PATH entry */ }
            }
        }
        for (Path candidate : candidates) {
            Path resolved = executableAt(candidate);
            if (resolved != null) return resolved;
        }
        return null;
    }

    public static String status(Config config) {
        if (!config.get("greenlight.executable", "").isBlank() && executable(config) == null)
            return "Configured Greenlight path is not executable";
        Path executable = executable(config);
        if (executable != null) return executable.toString();
        if (linux()) {
            String installed = config.get("greenlight.installedVersion", "");
            return installed.isBlank() ? "Flatpak: install or check existing installation"
                    : "Greenlight on Flathub (" + installed + ")";
        }
        return "Greenlight not installed / not detected";
    }

    /** Launch without a shell, OpenXCloud-specific flags or browser embedding. */
    public static Process start(Config config) throws IOException {
        Path executable = executable(config);
        ProcessBuilder builder;
        if (executable != null) {
            builder = new ProcessBuilder(executable.toString());
        } else if (linux() && config.get("greenlight.executable", "").isBlank()) {
            // Fail with an actionable error instead of showing a briefly-lived helper process.
            if (!flatpakInstalled())
                throw new IOException("Greenlight is not installed. Use Download / update Greenlight in Settings > System > Plugin store.");
            builder = new ProcessBuilder("flatpak", "run", FLATPAK_ID);
        } else {
            throw new IOException("Greenlight executable not found. Download it in Settings > System > Plugin store or select an installed binary.");
        }
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        return builder.start();
    }

    private static boolean flatpakInstalled() {
        for (String scope : List.of("--user", "--system")) {
            try {
                Process process = new ProcessBuilder("flatpak", "info", scope, FLATPAK_ID)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD).start();
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    continue;
                }
                if (process.exitValue() == 0) return true;
            } catch (IOException e) {
                return false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }
}
