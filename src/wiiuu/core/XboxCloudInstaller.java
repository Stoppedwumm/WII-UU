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
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Download and install an official, checksum-verified OpenXCloud GitHub release.
 * Installed files remain separate from the GPL-licensed WII-UU distribution.
 *
 * Windows uses the portable ZIP, macOS preserves signed app-bundle metadata
 * with ditto, and Linux extracts the .deb without executing maintainer scripts
 * or requiring elevated privileges. No downloaded installer is executed.
 */
public final class XboxCloudInstaller {
    private XboxCloudInstaller() {}

    private static final URI RELEASE_API =
            URI.create("https://api.github.com/repos/shyim/openxcloud/releases/latest");
    private static final long MAX_DOWNLOAD = 512L * 1024 * 1024;
    private static final long MAX_EXTRACTED = 2L * 1024 * 1024 * 1024;
    private static final AtomicBoolean BUSY = new AtomicBoolean();

    public enum Platform {
        MAC_ARM64("OpenXCloud-macos-arm64.zip"),
        WINDOWS_X64("OpenXCloud-windows-x64.zip"),
        LINUX_AMD64("OpenXCloud-linux-amd64.deb");

        public final String filename;
        Platform(String filename) { this.filename = filename; }

        public static Platform detect(String os, String architecture) throws IOException {
            String system = os.toLowerCase(Locale.ROOT);
            String cpu = architecture.toLowerCase(Locale.ROOT);
            boolean arm = cpu.equals("aarch64") || cpu.equals("arm64");
            boolean x64 = cpu.equals("amd64") || cpu.equals("x86_64") || cpu.equals("x64");
            if (system.contains("mac") && arm) return MAC_ARM64;
            if (system.contains("win") && x64) return WINDOWS_X64;
            if (system.contains("linux") && x64) return LINUX_AMD64;
            throw new IOException("No official OpenXCloud package for " + os + " / " + architecture
                    + ". Currently supported: macOS ARM64, Windows x64, Debian/Ubuntu x64.");
        }

        public static Platform current() throws IOException {
            return detect(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
        }
    }

    public record Release(String tag, Platform platform, URI url, String sha256, long size) { }

    /** Parse an official release; refuse unverified, unexpected, or off-domain packages. */
    static Release parseRelease(String json, Platform platform) throws IOException {
        try {
            Map<String, Object> release = MiniJson.object(MiniJson.parse(json));
            if (Boolean.TRUE.equals(release.get("draft")) || Boolean.TRUE.equals(release.get("prerelease")))
                throw new IOException("No stable OpenXCloud release available");
            String tag = MiniJson.string(release.get("tag_name"));
            if (tag == null || !tag.matches("v[0-9]+\\.[0-9]+\\.[0-9]+"))
                throw new IOException("Unexpected OpenXCloud release version");
            for (Object object : MiniJson.array(release.get("assets"))) {
                Map<String, Object> asset = MiniJson.object(object);
                if (!platform.filename.equals(MiniJson.string(asset.get("name")))) continue;
                String digest = MiniJson.string(asset.get("digest"));
                String rawUrl = MiniJson.string(asset.get("browser_download_url"));
                Double size = MiniJson.number(asset.get("size"));
                if (digest == null || !digest.matches("sha256:[0-9a-fA-F]{64}"))
                    throw new IOException("OpenXCloud release asset has no SHA-256 checksum");
                if (rawUrl == null || size == null || size <= 0 || size > MAX_DOWNLOAD
                        || size != Math.rint(size))
                    throw new IOException("Invalid OpenXCloud download metadata");
                URI uri = URI.create(rawUrl);
                if (!"https".equals(uri.getScheme()) || !"github.com".equals(uri.getHost())
                        || uri.getRawQuery() != null || uri.getRawFragment() != null
                        || !("/shyim/openxcloud/releases/download/" + tag + "/" + platform.filename)
                                .equals(uri.getRawPath()))
                    throw new IOException("Untrusted OpenXCloud release URL");
                return new Release(tag, platform, uri,
                        digest.substring("sha256:".length()).toLowerCase(Locale.ROOT), size.longValue());
            }
            throw new IOException("Official OpenXCloud " + platform.filename
                    + " package is not available in the latest release");
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid OpenXCloud release metadata", e);
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
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static boolean installing() { return BUSY.get(); }

    /** Starts downloading off the Swing event thread; reports progress via the callback. */
    public static void install(Config config, Consumer<String> report) {
        if (!BUSY.compareAndSet(false, true)) {
            report.accept("OpenXCloud download is already running");
            return;
        }
        Thread worker = new Thread(() -> {
            try {
                installLatest(config, report);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                report.accept("OpenXCloud installation interrupted");
            } catch (Exception e) {
                report.accept("OpenXCloud installation failed: " + e.getMessage());
            } finally {
                BUSY.set(false);
            }
        }, "openxcloud-installer");
        worker.setDaemon(true);
        worker.start();
    }

    /** One verified native release per WII-UU device, shared by its user profiles. */
    static Path installLatest(Config config, Consumer<String> report) throws IOException, InterruptedException {
        if (PluginStore.externalRunning(config))
            throw new IOException("Close Xbox Cloud Gaming before updating OpenXCloud");
        Platform platform = Platform.current();
        report.accept("Checking official OpenXCloud releases...");
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(RELEASE_API)
                .timeout(Duration.ofSeconds(25))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "WII-UU-OpenXCloud-Installer")
                .build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("GitHub release lookup returned HTTP " + response.statusCode());
        }
        if (response.body().length() > 1_000_000)
            throw new IOException("OpenXCloud release metadata is unexpectedly large");
        Release release = parseRelease(response.body(), platform);
        Path root = Files.createDirectories(config.home().resolve("native/openxcloud"));
        Path archive = Files.createTempFile(root, ".download-", platform == Platform.LINUX_AMD64 ? ".deb" : ".zip");
        Path stage = Files.createTempDirectory(root, ".staging-");
        Path current = root.resolve("current"), previous = root.resolve("previous");
        try {
            download(client, release, archive, report);
            report.accept("Installing OpenXCloud " + release.tag() + "...");
            if (platform == Platform.MAC_ARM64) {
                runCommand(List.of("ditto", "-x", "-k", archive.toString(), stage.toString()));
            } else if (platform == Platform.LINUX_AMD64) {
                runCommand(List.of("dpkg-deb", "--extract", archive.toString(), stage.toString()));
            } else {
                extractZip(archive, stage);
            }
            Path binary = findExecutable(stage, platform);
            if (binary == null)
                throw new IOException("OpenXCloud package does not contain the expected native executable");
            Path installed = current.resolve(stage.relativize(binary));
            if (Files.exists(previous, LinkOption.NOFOLLOW_LINKS)) removeTree(previous);
            boolean backedUp = false;
            boolean promoted = false;
            String oldPath = config.get("xcloud.executable", "");
            String oldVersion = config.get("xcloud.installedVersion", "");
            try {
                if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                    Files.move(current, previous);
                    backedUp = true;
                }
                Files.move(stage, current);
                promoted = true;
                config.set("xcloud.executable", installed.toAbsolutePath().toString());
                config.set("xcloud.installedVersion", release.tag());
                config.saveChecked();
            } catch (IOException | RuntimeException e) {
                config.set("xcloud.executable", oldPath);
                config.set("xcloud.installedVersion", oldVersion);
                if (promoted) removeTree(current);
                if (backedUp) Files.move(previous, current);
                throw e;
            }
            if (backedUp) {
                try { removeTree(previous); }
                catch (IOException e) { System.err.println("[xcloud] old build cleanup: " + e.getMessage()); }
            }
            if (platform == Platform.LINUX_AMD64) {
                // Optional system installation to resolve distro-specific dependencies.
                Path savedDeb = root.resolve(release.platform().filename);
                Files.copy(archive, savedDeb, StandardCopyOption.REPLACE_EXISTING);
            }
            report.accept("OpenXCloud " + release.tag()
                    + " installed. Enable Xbox Cloud Gaming and start it from the dock.");
            return installed;
        } finally {
            Files.deleteIfExists(archive);
            if (Files.exists(stage, LinkOption.NOFOLLOW_LINKS)) removeTree(stage);
        }
    }

    private static void download(HttpClient client, Release release, Path archive, Consumer<String> report)
            throws IOException, InterruptedException {
        HttpResponse<InputStream> response = client.send(HttpRequest.newBuilder(release.url())
                .timeout(Duration.ofMinutes(12))
                .header("User-Agent", "WII-UU-OpenXCloud-Installer").build(),
                HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200 || !"https".equalsIgnoreCase(response.uri().getScheme()))
            throw new IOException("OpenXCloud download failed: HTTP " + response.statusCode());
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        long total = 0;
        int lastPercent = -5;
        try (InputStream input = response.body(); var output = Files.newOutputStream(archive)) {
            byte[] buffer = new byte[64 * 1024];
            for (int count; (count = input.read(buffer)) != -1;) {
                total += count;
                if (total > MAX_DOWNLOAD || total > release.size())
                    throw new IOException("OpenXCloud download exceeded declared size");
                output.write(buffer, 0, count);
                digest.update(buffer, 0, count);
                int percent = (int) (100 * total / release.size());
                if (percent >= lastPercent + 5) {
                    lastPercent = percent;
                    report.accept("Downloading OpenXCloud " + release.tag() + ": " + percent + "%");
                }
            }
        }
        if (total != release.size()
                || !HexFormat.of().formatHex(digest.digest()).equals(release.sha256()))
            throw new IOException("OpenXCloud download failed SHA-256 / size verification");
    }

    /** Refuse ZIP traversal and oversized archives before making files available. */
    static void extractZip(Path zip, Path directory) throws IOException {
        Path root = directory.toAbsolutePath().normalize();
        Files.createDirectories(root);
        long total = 0;
        int entries = 0;
        try (ZipInputStream input = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            byte[] buffer = new byte[64 * 1024];
            while ((entry = input.getNextEntry()) != null) {
                String name = entry.getName();
                if (++entries > 20_000 || name.startsWith("/") || name.contains("\\")
                        || name.contains(":") || name.indexOf('\0') >= 0)
                    throw new IOException("Unsafe OpenXCloud archive entry");
                Path out = root.resolve(name).normalize();
                if (name.isBlank() || out.equals(root) || !out.startsWith(root))
                    throw new IOException("Unsafe OpenXCloud archive path");
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    try (var output = Files.newOutputStream(out)) {
                        int n;
                        while ((n = input.read(buffer)) != -1) {
                            total += n;
                            if (total > MAX_EXTRACTED)
                                throw new IOException("OpenXCloud archive exceeds extraction size limit");
                            output.write(buffer, 0, n);
                        }
                    }
                }
                input.closeEntry();
            }
        }
    }

    /** Discover the installed executable inside the verified native package. */
    static Path findExecutable(Path stage, Platform platform) throws IOException {
        if (platform == Platform.LINUX_AMD64) {
            Path binary = stage.resolve("usr/bin/openxcloud");
            if (XboxCloud.executableAt(binary) != null) return binary;
        }
        try (Stream<Path> paths = Files.walk(stage, 10)) {
            for (Path path : paths.toList()) {
                if (platform == Platform.MAC_ARM64
                        && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                        && path.getFileName().toString().equalsIgnoreCase("OpenXCloud.app")
                        && XboxCloud.executableAt(path) != null
                        && path.toRealPath().startsWith(stage.toRealPath())) return path;
                if (platform != Platform.MAC_ARM64 && Files.isRegularFile(path)
                        && path.getFileName().toString().equalsIgnoreCase(
                                platform == Platform.WINDOWS_X64 ? "openxcloud.exe" : "openxcloud")
                        && XboxCloud.executableAt(path) != null
                        && path.toRealPath().startsWith(stage.toRealPath())) return path;
            }
        }
        return null;
    }

    private static void runCommand(List<String> command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        if (!process.waitFor(4, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new IOException(command.get(0) + " timed out extracting OpenXCloud");
        }
        if (process.exitValue() != 0)
            throw new IOException(command.get(0) + " failed (exit " + process.exitValue() + ")");
    }

    private static void removeTree(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException error)
                    throws IOException {
                if (error != null) throw error;
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
