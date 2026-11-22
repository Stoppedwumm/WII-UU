package wiiuu.core;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Explicit, per-user Greenlight installer.
 *
 * Linux aarch64 / x86-64: Flathub Flatpak, which verifies its own OSTree data.
 * macOS: checksum-verified universal .dmg; copy .app with ditto, no execution.
 * Windows x64: checksum-verified portable ZIP; no setup.exe execution.
 *
 * No shell, system package manager, sudo or downloaded install scripts.
 */
public final class GreenlightInstaller {
    private GreenlightInstaller() {}

    static final URI RELEASE_API =
            URI.create("https://api.github.com/repos/unknownskl/greenlight/releases/latest");
    static final String FLATHUB_REPO = "https://flathub.org/repo/flathub.flatpakrepo";
    private static final long MAX_DOWNLOAD = 512L * 1024 * 1024;
    private static final long MAX_EXTRACTED = 2L * 1024 * 1024 * 1024;
    private static final AtomicBoolean BUSY = new AtomicBoolean();

    public enum Platform {
        LINUX_FLATPAK, MAC_UNIVERSAL, WINDOWS_X64;

        public static Platform detect(String os, String arch) throws IOException {
            String system = os.toLowerCase(Locale.ROOT);
            String cpu = arch.toLowerCase(Locale.ROOT);
            boolean arm64 = cpu.equals("aarch64") || cpu.equals("arm64");
            boolean x64 = cpu.equals("amd64") || cpu.equals("x86_64") || cpu.equals("x64");
            if (system.contains("linux") && (arm64 || x64)) return LINUX_FLATPAK;
            if (system.contains("mac") && (arm64 || x64)) return MAC_UNIVERSAL;
            if (system.contains("win") && x64) return WINDOWS_X64;
            throw new IOException("Greenlight installer does not support " + os + " / " + arch
                    + ". Supported: Linux aarch64/x86-64, macOS universal and Windows x64.");
        }

        public static Platform current() throws IOException {
            return detect(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
        }

        public String assetName(String tag) {
            String version = tag.substring(1);
            return switch (this) {
                case MAC_UNIVERSAL -> "Greenlight-" + version + "-universal.dmg";
                case WINDOWS_X64 -> "Greenlight-" + version + "-win.zip";
                case LINUX_FLATPAK -> throw new IllegalStateException("Flathub installs Linux builds");
            };
        }
    }

    public record Release(String tag, Platform platform, URI url, String sha256, long size) { }

    static Release parseRelease(String json, Platform platform) throws IOException {
        if (platform == Platform.LINUX_FLATPAK) throw new IOException("Use Flathub for Linux builds");
        try {
            Map<String, Object> release = MiniJson.object(MiniJson.parse(json));
            if (Boolean.TRUE.equals(release.get("draft")) || Boolean.TRUE.equals(release.get("prerelease")))
                throw new IOException("Only stable Greenlight releases are supported");
            String tag = MiniJson.string(release.get("tag_name"));
            if (tag == null || !tag.matches("v[0-9]+\\.[0-9]+\\.[0-9]+"))
                throw new IOException("Unexpected Greenlight release version");
            String filename = platform.assetName(tag);
            for (Object object : MiniJson.array(release.get("assets"))) {
                Map<String, Object> asset = MiniJson.object(object);
                if (!filename.equals(MiniJson.string(asset.get("name")))) continue;
                String digest = MiniJson.string(asset.get("digest"));
                Double size = MiniJson.number(asset.get("size"));
                String url = MiniJson.string(asset.get("browser_download_url"));
                if (digest == null || !digest.matches("sha256:[a-fA-F0-9]{64}"))
                    throw new IOException("Greenlight release has no trusted SHA-256 digest");
                if (size == null || size <= 0 || size > MAX_DOWNLOAD || size != Math.rint(size) || url == null)
                    throw new IOException("Invalid Greenlight release asset metadata");
                URI address = URI.create(url);
                String expected = "/unknownskl/greenlight/releases/download/" + tag + "/" + filename;
                if (!"https".equals(address.getScheme()) || !"github.com".equals(address.getHost())
                        || !expected.equals(address.getRawPath()) || address.getRawQuery() != null
                        || address.getRawFragment() != null)
                    throw new IOException("Untrusted Greenlight release URL");
                return new Release(tag, platform, address,
                        digest.substring(7).toLowerCase(Locale.ROOT), size.longValue());
            }
            throw new IOException("Official Greenlight " + filename + " package is unavailable");
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid Greenlight release metadata", e);
        }
    }

    static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                for (int n; (n = in.read(buffer)) != -1;) digest.update(buffer, 0, n);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean installing() { return BUSY.get(); }

    /** Installer never runs on Swing's event thread. The callback reports progress/errors. */
    public static void install(Config config, Consumer<String> report) {
        if (!BUSY.compareAndSet(false, true)) {
            report.accept("Greenlight installation is already running");
            return;
        }
        Thread worker = new Thread(() -> {
            try {
                installNow(config, report);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                report.accept("Greenlight install interrupted");
            } catch (Exception e) {
                report.accept("Greenlight installation failed: " + e.getMessage());
            } finally {
                BUSY.set(false);
            }
        }, "greenlight-installer");
        worker.setDaemon(true);
        worker.start();
    }

    static void installNow(Config config, Consumer<String> report) throws IOException, InterruptedException {
        if (PluginStore.externalRunning(config))
            throw new IOException("Close Xbox Cloud Gaming before updating Greenlight");
        Platform platform = Platform.current();
        if (platform == Platform.LINUX_FLATPAK) {
            installFlatpak(config, report);
        } else {
            installNative(config, platform, report);
        }
    }

    /**
     * Install/update only in the current user's Flatpak scope, not system-wide.
     * Flathub commits are authenticated by Flatpak/OSTree; no release ZIP is used.
     */
    private static void installFlatpak(Config config, Consumer<String> report)
            throws IOException, InterruptedException {
        report.accept("Installing Greenlight from Flathub (Linux ARM64/x86-64)...");
        runCommand(List.of("flatpak", "remote-add", "--user", "--if-not-exists",
                "flathub", FLATHUB_REPO), 60);
        if (commandSucceeds(List.of("flatpak", "info", "--user", Greenlight.FLATPAK_ID), 10)) {
            report.accept("Updating Greenlight from Flathub...");
            runCommand(List.of("flatpak", "update", "--user", "--noninteractive", "-y",
                    Greenlight.FLATPAK_ID), 1200);
        } else {
            report.accept("Downloading Greenlight and its Flatpak runtime...");
            runCommand(List.of("flatpak", "install", "--user", "--noninteractive", "-y",
                    "flathub", Greenlight.FLATPAK_ID), 1200);
        }
        // Existing manual Greenlight executable takes precedence until this explicit action.
        config.set("greenlight.executable", "");
        config.set("greenlight.installedVersion", "Flathub");
        config.saveSharedChecked();
        report.accept("Greenlight installed from Flathub. Enable Xbox Cloud Gaming in the dock.");
    }

    private static void installNative(Config config, Platform platform, Consumer<String> report)
            throws IOException, InterruptedException {
        report.accept("Checking official Greenlight GitHub Releases...");
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        HttpResponse<String> metadata = client.send(HttpRequest.newBuilder(RELEASE_API)
                .timeout(Duration.ofSeconds(25))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "WII-UU-Greenlight-Installer")
                .build(), HttpResponse.BodyHandlers.ofString());
        if (metadata.statusCode() != 200)
            throw new IOException("GitHub release lookup returned HTTP " + metadata.statusCode());
        if (metadata.body().length() > 1_000_000)
            throw new IOException("Unexpectedly large Greenlight release metadata");
        Release release = parseRelease(metadata.body(), platform);
        Path root = Files.createDirectories(config.home().resolve("native/greenlight"));
        Path archive = Files.createTempFile(root, ".download-",
                platform == Platform.MAC_UNIVERSAL ? ".dmg" : ".zip");
        Path stage = Files.createTempDirectory(root, ".staging-");
        Path current = root.resolve("current"), backup = root.resolve("previous");
        try {
            download(client, release, archive, report);
            report.accept("Extracting verified Greenlight " + release.tag() + "...");
            if (platform == Platform.MAC_UNIVERSAL) extractDmg(archive, stage, root);
            else extractZip(archive, stage);
            Path binary = findExecutable(stage, platform);
            if (binary == null) throw new IOException("The native Greenlight executable is missing");
            Path configured = current.resolve(stage.relativize(binary)).toAbsolutePath();
            if (Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) removeTree(backup);
            boolean backedUp = false, promoted = false;
            String oldPath = config.get("greenlight.executable", "");
            String oldVersion = config.get("greenlight.installedVersion", "");
            try {
                if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                    Files.move(current, backup);
                    backedUp = true;
                }
                Files.move(stage, current);
                promoted = true;
                config.set("greenlight.executable", configured.toString());
                config.set("greenlight.installedVersion", release.tag());
                config.saveSharedChecked();
            } catch (IOException | RuntimeException e) {
                config.set("greenlight.executable", oldPath);
                config.set("greenlight.installedVersion", oldVersion);
                try { config.saveSharedChecked(); } catch (IOException ex) { e.addSuppressed(ex); }
                if (promoted) removeTree(current);
                if (backedUp) Files.move(backup, current);
                throw e;
            }
            if (backedUp) {
                try { removeTree(backup); }
                catch (IOException e) { System.err.println("[greenlight] cleanup: " + e.getMessage()); }
            }
            report.accept("Greenlight " + release.tag() + " installed. Start Xbox Cloud Gaming from the dock.");
        } finally {
            Files.deleteIfExists(archive);
            if (Files.exists(stage, LinkOption.NOFOLLOW_LINKS)) removeTree(stage);
        }
    }

    /**
     * Count bytes while sendAsync owns the response body. Enforce a hard maximum
     * duration and 45-second idle timeout even after HTTP response headers arrive.
     */
    private static void download(HttpClient client, Release release, Path archive, Consumer<String> report)
            throws IOException, InterruptedException {
        CompletableFuture<HttpResponse<Path>> transfer = client.sendAsync(
                HttpRequest.newBuilder(release.url()).timeout(Duration.ofMinutes(15))
                    .header("User-Agent", "WII-UU-Greenlight-Installer").build(),
                HttpResponse.BodyHandlers.ofFile(archive));
        long started = System.nanoTime(), lastProgress = started, lastSize = 0;
        int lastPercent = 0;
        try {
            while (true) {
                try {
                    HttpResponse<Path> result = transfer.get(1, TimeUnit.SECONDS);
                    if (result.statusCode() != 200
                            || !"https".equalsIgnoreCase(result.uri().getScheme()))
                        throw new IOException("Greenlight download HTTP " + result.statusCode());
                    break;
                } catch (TimeoutException ignored) {
                    // The response is still transferring.
                } catch (ExecutionException e) {
                    throw new IOException("Greenlight download failed", e.getCause());
                }
                long now = System.nanoTime(), bytes = Files.size(archive);
                if (bytes > release.size() || bytes > MAX_DOWNLOAD)
                    throw new IOException("Greenlight download exceeded the release size");
                if (bytes != lastSize) {
                    lastSize = bytes;
                    lastProgress = now;
                    int percent = (int) (100 * bytes / release.size());
                    if (percent >= lastPercent + 5) {
                        lastPercent = percent;
                        report.accept("Downloading Greenlight " + release.tag() + ": " + percent + "%");
                    }
                }
                if (now - started > TimeUnit.MINUTES.toNanos(15)
                        || now - lastProgress > TimeUnit.SECONDS.toNanos(45))
                    throw new IOException("Greenlight download stalled or timed out");
            }
        } finally {
            if (!transfer.isDone()) transfer.cancel(true);
        }
        if (Files.size(archive) != release.size() || !sha256(archive).equals(release.sha256()))
            throw new IOException("Greenlight download failed SHA-256 / size verification");
    }

    /** Reject unsafe ZIP paths and excessive extraction. */
    static void extractZip(Path zip, Path destination) throws IOException {
        Path root = destination.toAbsolutePath().normalize();
        Files.createDirectories(root);
        long total = 0;
        int entries = 0;
        try (ZipInputStream input = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            byte[] buffer = new byte[64 * 1024];
            while ((entry = input.getNextEntry()) != null) {
                String name = entry.getName();
                if (++entries > 20_000 || name.isBlank() || name.startsWith("/")
                        || name.contains("\\") || name.contains(":") || name.indexOf('\0') >= 0)
                    throw new IOException("Unsafe Greenlight ZIP entry");
                Path out = root.resolve(name).normalize();
                if (out.equals(root) || !out.startsWith(root))
                    throw new IOException("Unsafe Greenlight ZIP path");
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    try (var output = Files.newOutputStream(out)) {
                        for (int n; (n = input.read(buffer)) != -1;) {
                            total += n;
                            if (total > MAX_EXTRACTED)
                                throw new IOException("Greenlight ZIP extraction size exceeded");
                            output.write(buffer, 0, n);
                        }
                    }
                }
                input.closeEntry();
            }
        }
    }

    /** Mount but never execute the verified DMG; copy only the Greenlight.app bundle. */
    private static void extractDmg(Path dmg, Path stage, Path root)
            throws IOException, InterruptedException {
        Path mount = Files.createTempDirectory(root, ".dmg-mount-");
        boolean attached = false;
        try {
            runCommand(List.of("hdiutil", "attach", "-readonly", "-nobrowse", "-noautoopen",
                    "-mountpoint", mount.toString(), dmg.toString()), 120);
            attached = true;
            Path app;
            try (Stream<Path> files = Files.walk(mount, 3)) {
                app = files.filter(p -> p.getFileName().toString().equals("Greenlight.app")
                        && Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS))
                        .filter(p -> {
                            try { return p.toRealPath().startsWith(mount.toRealPath()); }
                            catch (IOException e) { return false; }
                        }).findFirst().orElseThrow(() ->
                                new IOException("Greenlight.app missing from verified DMG"));
            }
            runCommand(List.of("ditto", app.toString(), stage.resolve("Greenlight.app").toString()), 300);
        } finally {
            if (attached) {
                try { runCommand(List.of("hdiutil", "detach", mount.toString()), 90); }
                catch (IOException e) { System.err.println("[greenlight] DMG detach: " + e.getMessage()); }
            }
            if (Files.isDirectory(mount)) {
                try { Files.deleteIfExists(mount); } catch (IOException ignored) { /* mount may remain busy */ }
            }
        }
    }

    static Path findExecutable(Path stage, Platform platform) throws IOException {
        if (platform == Platform.LINUX_FLATPAK) return null;
        try (Stream<Path> files = Files.walk(stage, 12)) {
            for (Path path : files.toList()) {
                if (platform == Platform.MAC_UNIVERSAL
                        && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                        && path.getFileName().toString().equalsIgnoreCase("Greenlight.app")) {
                    Path executable = Greenlight.executableAt(path);
                    if (executable != null && executable.toRealPath().startsWith(stage.toRealPath()))
                        return executable;
                }
                if (platform == Platform.WINDOWS_X64 && Files.isRegularFile(path)
                        && path.getFileName().toString().equalsIgnoreCase("Greenlight.exe")) {
                    Path executable = Greenlight.executableAt(path);
                    if (executable != null && executable.toRealPath().startsWith(stage.toRealPath()))
                        return executable;
                }
            }
        }
        return null;
    }

    static boolean commandSucceeds(List<String> command, int seconds) throws InterruptedException {
        try {
            runCommand(command, seconds);
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    private static void runCommand(List<String> command, int seconds)
            throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            if (!process.waitFor(seconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException(command.get(0) + " timed out");
            }
            if (process.exitValue() != 0)
                throw new IOException(command.get(0) + " failed (exit " + process.exitValue() + ")");
        } catch (InterruptedException e) {
            process.destroyForcibly();
            throw e;
        }
    }

    private static void removeTree(Path dir) throws IOException {
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path path, IOException error)
                    throws IOException {
                if (error != null) throw error;
                Files.delete(path);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
