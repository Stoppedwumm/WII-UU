package wiiuu.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Launches OpenXCloud's native Rust/WebRTC desktop client directly.
 *
 * No browser, Electron, node, shell command or WebRTC frame-copy into Swing is
 * involved. OpenXCloud owns sign-in, streaming, hardware decoding and gamepads.
 * We never read or store its authentication tokens.
 */
public final class XboxCloud {
    private XboxCloud() {}

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static boolean mac() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }

    /** Accept an executable or the enclosing macOS .app bundle. */
    static Path executableAt(Path selected) {
        if (selected == null) return null;
        try {
            Path path = selected.toAbsolutePath().normalize();
            if (Files.isDirectory(path) && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".app")) {
                for (String name : List.of("openxcloud", "OpenXCloud")) {
                    Path binary = path.resolve("Contents/MacOS").resolve(name);
                    if (Files.isRegularFile(binary) && Files.isExecutable(binary)) return binary;
                }
                return null;
            }
            return Files.isRegularFile(path) && Files.isExecutable(path) ? path : null;
        } catch (SecurityException | RuntimeException e) {
            return null;
        }
    }

    /** Explicit user preference takes priority, even when it is currently unavailable. */
    public static Path executable(Config config) {
        String configured = config.get("xcloud.executable", "").trim();
        if (!configured.isEmpty()) {
            try { return executableAt(Path.of(configured)); }
            catch (RuntimeException ignored) { return null; }
        }

        String name = windows() ? "openxcloud.exe" : "openxcloud";
        List<Path> candidates = new ArrayList<>();
        String home = System.getProperty("user.home", "");
        if (!home.isBlank()) {
            Path user = Path.of(home);
            candidates.add(user.resolve(".cargo/bin").resolve(name));
            candidates.add(user.resolve("Applications/OpenXCloud.app"));
            candidates.add(user.resolve("Applications/openxcloud.app"));
            if (windows()) {
                candidates.add(user.resolve("AppData/Local/Programs/OpenXCloud/openxcloud.exe"));
                candidates.add(user.resolve("AppData/Local/OpenXCloud/openxcloud.exe"));
            }
        }
        if (mac()) {
            candidates.add(Path.of("/Applications/OpenXCloud.app"));
            candidates.add(Path.of("/Applications/openxcloud.app"));
            candidates.add(Path.of("/opt/homebrew/bin/openxcloud"));
        }
        if (!windows()) {
            candidates.add(Path.of("/usr/local/bin/openxcloud"));
            candidates.add(Path.of("/usr/bin/openxcloud"));
        } else {
            for (String env : List.of("ProgramFiles", "ProgramFiles(x86)", "LOCALAPPDATA")) {
                String dir = System.getenv(env);
                if (dir != null && !dir.isBlank()) candidates.add(Path.of(dir, "OpenXCloud", name));
            }
        }
        String searchPath = System.getenv("PATH");
        if (searchPath != null) for (String directory : searchPath.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
            if (!directory.isBlank()) {
                try { candidates.add(Path.of(directory, name)); }
                catch (RuntimeException ignored) { /* Invalid PATH entry */ }
            }
        }
        for (Path candidate : candidates) {
            Path binary = executableAt(candidate);
            if (binary != null) return binary;
        }
        return null;
    }

    public static String status(Config config) {
        Path binary = executable(config);
        if (binary != null) return binary.toString();
        return config.get("xcloud.executable", "").isBlank() ? "Not installed / not detected" : "Configured path not executable";
    }

    public static Process start(Config config) throws IOException {
        Path binary = executable(config);
        if (binary == null) throw new IOException("OpenXCloud not found. Install its native app, then select its executable in Settings > System > Plugin store.");
        String present = config.getBool("xcloud.lowLatency", true) ? "immediate" : "fifo";
        // Never use a shell, pass secrets, or pipe frames through Java. The native
        // client uses its own OS credential store and GPU rendering.
        ProcessBuilder builder = new ProcessBuilder(binary.toString(), "--present", present);
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        return builder.start();
    }
}
