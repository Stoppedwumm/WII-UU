package wiiuu.core;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Upgrades WII-UU in place: reads {@code version.json} from the website, downloads the release
 * zip, checks its SHA-256 and runs the bundled installer (which keeps settings, ROMs, paired
 * phones and emulators), then starts the new version.
 */
public final class Updater {
    public static final String DEFAULT_URL = "https://wiiuu.stoppedwumm.net/version.json";
    private static final String OS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

    public record Release(String version, String zipUrl, String sha256) {}

    private final Config config;
    private final String current;
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10)).build();

    public Updater(Config config, String currentVersion) {
        this.config = config;
        this.current = currentVersion;
    }

    /** @return the newer release, or null when up to date */
    public Release check() throws IOException, InterruptedException {
        String url = config.get("update.url", DEFAULT_URL);
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
                .header("User-Agent", "WII-UU/" + current).build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new IOException("update check failed: HTTP " + r.statusCode());
        String body = r.body();
        Release rel = new Release(field(body, "version"), field(body, "zip"), field(body, "sha256"));
        if (rel.version() == null || rel.zipUrl() == null) throw new IOException("unexpected version.json");
        return newer(rel.version(), current) ? rel : null;
    }

    /** Numeric, dot-separated comparison: 1.10.0 > 1.9.2. */
    public static boolean newer(String candidate, String than) {
        String[] a = candidate.split("[.-]"), b = than.split("[.-]");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? num(a[i]) : 0, y = i < b.length ? num(b[i]) : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    private static int num(String s) {
        try {
            return Integer.parseInt(s.replaceAll("\\D.*", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String field(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    /** Downloads, verifies and unpacks the release; returns the folder that contains install.sh / install.ps1. */
    public Path download(Release rel) throws IOException, InterruptedException {
        Path dir = Files.createTempDirectory("wiiuu-upgrade-");
        Path zip = dir.resolve("wiiuu.zip");
        HttpResponse<Path> r = http.send(HttpRequest.newBuilder(URI.create(rel.zipUrl())).timeout(Duration.ofMinutes(5))
                .header("User-Agent", "WII-UU/" + current).build(), HttpResponse.BodyHandlers.ofFile(zip));
        if (r.statusCode() != 200) throw new IOException("download failed: HTTP " + r.statusCode());
        if (rel.sha256() != null && !rel.sha256().isBlank()) {
            String got = sha256(zip);
            if (!got.equalsIgnoreCase(rel.sha256().trim())) {
                throw new IOException("download is corrupt (checksum mismatch) - try again");
            }
        }
        unzip(zip, dir);
        try (var s = Files.list(dir)) {
            return s.filter(p -> Files.isDirectory(p) && Files.exists(p.resolve("wiiuu.jar"))).findFirst()
                    .orElseThrow(() -> new IOException("release zip has no wiiuu.jar"));
        }
    }

    /**
     * Runs the installer from {@code release} once this process has exited, then starts WII-UU
     * again. Returns immediately; the caller should exit right after.
     */
    public void installAfterExit(Path release) throws IOException {
        long pid = ProcessHandle.current().pid();
        Path log = config.logDir().resolve("upgrade.log");
        Files.createDirectories(log.getParent());
        String java = Path.of(System.getProperty("java.home"), "bin", OS.contains("win") ? "javaw.exe" : "java").toString();
        String jar = runningJar();
        List<String> cmd;
        if (OS.contains("win")) {
            String ps = "Wait-Process -Id " + pid + " -ErrorAction SilentlyContinue; "
                    + "& '" + release.resolve("install.ps1") + "' -Yes *> '" + log + "'; "
                    + "Start-Process '" + java + "' -ArgumentList '-jar','\"" + (jar != null ? jar
                    : System.getenv("LOCALAPPDATA") + "\\WII-UU\\wiiuu.jar") + "\"'";
            cmd = List.of("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-Command", ps);
        } else {
            String relaunch = jar != null ? "\"" + java + "\" -jar \"" + jar + "\"" : "\"$HOME/.local/bin/wiiuu\"";
            String home = System.getProperty("java.home", "");
            int app = home.indexOf(".app/Contents/");
            if (OS.contains("mac") && app > 0) {
                // native WII-UU.app: start it through macOS so its permissions (screen, keys) apply
                relaunch = "open \"" + home.substring(0, app + 4) + "\"";
            } else if (OS.contains("mac")) {
                relaunch = "open \"$HOME/Applications/WII-UU.app\" || " + relaunch;
            }
            String sh = "while kill -0 " + pid + " 2>/dev/null; do sleep 0.5; done; "
                    + "bash \"" + release.resolve("install.sh") + "\" --yes > \"" + log + "\" 2>&1 < /dev/null; "
                    + "( " + relaunch + " ) >/dev/null 2>&1";
            cmd = List.of("nohup", "bash", "-c", sh);
        }
        new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectInput(ProcessBuilder.Redirect.PIPE).start().getOutputStream().close();
    }

    /** Runs the installer now and waits (command line upgrade, when WII-UU isn't running). */
    public int installNow(Path release) throws IOException, InterruptedException {
        List<String> cmd = OS.contains("win")
                ? List.of("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", release.resolve("install.ps1").toString(), "-Yes")
                : List.of("bash", release.resolve("install.sh").toString(), "--yes");
        return new ProcessBuilder(cmd).inheritIO().start().waitFor();
    }

    private static String runningJar() {
        try {
            Path p = Path.of(Updater.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return p.toString().endsWith(".jar") ? p.toAbsolutePath().toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            return HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    private static void unzip(Path zip, Path into) throws IOException {
        Path root = into.toAbsolutePath().normalize();
        try (ZipInputStream z = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                Path out = root.resolve(e.getName()).normalize();
                if (!out.startsWith(root)) throw new IOException("bad entry in zip: " + e.getName());
                if (e.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(z, out, StandardCopyOption.REPLACE_EXISTING);
                    if (out.getFileName().toString().endsWith(".sh")) out.toFile().setExecutable(true);
                }
            }
        }
    }
}
