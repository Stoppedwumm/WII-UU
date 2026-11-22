package wiiuu.core;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.FileVisitResult;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Installs an isolated Chromium/WebRTC player and the local Xbox protocol helper. */
public final class XboxCloudRuntime {
    private XboxCloudRuntime() {}

    private static final URI CHROME_INDEX = URI.create(
            "https://googlechromelabs.github.io/chrome-for-testing/last-known-good-versions-with-downloads.json");
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private static final long MAX_CHROME_ZIP = 800L * 1024 * 1024;
    private static final long MAX_EXTRACTED = 3L * 1024 * 1024 * 1024;
    private static final String RUNTIME_VERSION = "native-v1";

    public enum Platform {
        LINUX_ARM64("linux-arm64", "chrome-linux-arm64/chrome"),
        LINUX_X64("linux64", "chrome-linux64/chrome"),
        MAC_ARM64("mac-arm64", "chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing"),
        MAC_X64("mac-x64", "chrome-mac-x64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing"),
        WINDOWS_X64("win64", "chrome-win64/chrome.exe");

        final String key, binary;
        Platform(String key, String binary) { this.key = key; this.binary = binary; }
        public String key() { return key; }

        public static Platform detect(String os, String arch) throws IOException {
            String system = os.toLowerCase(Locale.ROOT), cpu = arch.toLowerCase(Locale.ROOT);
            boolean arm64 = cpu.equals("aarch64") || cpu.equals("arm64");
            boolean x64 = cpu.equals("amd64") || cpu.equals("x86_64") || cpu.equals("x64");
            if (system.contains("linux")) {
                if (arm64) return LINUX_ARM64;
                if (x64) return LINUX_X64;
            }
            if (system.contains("mac")) {
                if (arm64) return MAC_ARM64;
                if (x64) return MAC_X64;
            }
            if (system.contains("win") && x64) return WINDOWS_X64;
            throw new IOException("No portable Chrome for Testing package for " + os + " / " + arch);
        }

        static Platform current() throws IOException {
            return detect(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
        }
    }

    public record ChromeRelease(String version, URI url, Platform platform) {}

    static ChromeRelease selectChrome(String json, Platform platform) throws IOException {
        try {
            Map<String, Object> root = MiniJson.object(MiniJson.parse(json));
            Map<String, Object> stable = MiniJson.object(
                    MiniJson.object(root.get("channels")).get("Stable"));
            String version = MiniJson.string(stable.get("version"));
            if (version == null || !version.matches("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+"))
                throw new IOException("Unexpected Chrome version metadata");
            for (Object item : MiniJson.array(MiniJson.object(stable.get("downloads")).get("chrome"))) {
                Map<String, Object> asset = MiniJson.object(item);
                if (!platform.key.equals(asset.get("platform"))) continue;
                URI uri = URI.create(MiniJson.string(asset.get("url")));
                String expectedPath = "/chrome-for-testing-public/" + version
                        + "/" + platform.key + "/chrome-" + platform.key + ".zip";
                if (!uri.getScheme().equals("https") ||
                        !uri.getHost().equals("storage.googleapis.com") ||
                        !uri.getRawPath().equals(expectedPath) ||
                        uri.getRawQuery() != null || uri.getRawFragment() != null)
                    throw new IOException("Chrome download URL does not match Google's release index");
                return new ChromeRelease(version, uri, platform);
            }
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IOException("Invalid Chrome for Testing metadata", e);
        }
        throw new IOException("Google does not publish a stable Chromium build for " + platform.key);
    }

    public static Path home(Config config) { return config.home().resolve("native/xcloud"); }
    public static Path chromium(Config config) throws IOException {
        return home(config).resolve("chrome/current").resolve(Platform.current().binary);
    }
    public static boolean ready(Config config) {
        try {
            Path root = home(config);
            return Files.isRegularFile(root.resolve("player.bundle.js"))
                    && Files.isRegularFile(root.resolve("node_modules/xal-node/package.json"))
                    && Files.isRegularFile(chromium(config))
                    && Files.isRegularFile(root.resolve("installed-version"))
                    && RUNTIME_VERSION.equals(Files.readString(root.resolve("installed-version")).trim());
        } catch (IOException e) {
            return false;
        }
    }
    public static boolean installing() { return BUSY.get(); }

    public static void install(Config config, Consumer<String> notify) {
        if (!BUSY.compareAndSet(false, true)) {
            notify.accept("The Xbox Cloud Gaming runtime is already installing.");
            return;
        }
        Thread worker = new Thread(() -> {
            try { installNow(config, notify); }
            catch (Exception e) { notify.accept("Xbox Cloud runtime installation failed: " + e.getMessage()); }
            finally { BUSY.set(false); }
        }, "xcloud-runtime-install");
        worker.setDaemon(true);
        worker.start();
    }

    static void installNow(Config config, Consumer<String> notify) throws Exception {
        if (PluginStore.externalRunning(config))
            throw new IOException("Close the Xbox Cloud Gaming player before updating");
        Path root = Files.createDirectories(home(config));
        notify.accept("Preparing the private Xbox Cloud runtime...");
        for (String file : List.of("service.cjs", "player.html", "page-hook.js",
                "mapping-core.js", "player-entry.js")) {
            try (InputStream input = XboxCloudRuntime.class.getResourceAsStream("/xcloud/" + file)) {
                if (input == null) throw new IOException("Missing bundled xCloud resource: " + file);
                Files.copy(input, root.resolve(file), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        notify.accept("Installing the verified package versions (xal-node, WebRTC player, esbuild)...");
        String npm = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? "npm.cmd" : "npm";
        run(List.of(npm, "install", "--prefix", root.toString(),
                "--no-save", "--no-fund", "--no-audit", "--ignore-scripts",
                "--", "xal-node@1.1.6-beta2", "xbox-xcloud-player@1.0.0-beta7",
                "esbuild@0.27.7"), 600, root.resolve("install.log"));
        notify.accept("Bundling the dedicated Chromium WebRTC player...");
        run(List.of("node", root.resolve("node_modules/esbuild/bin/esbuild").toString(),
                root.resolve("player-entry.js").toString(), "--bundle", "--platform=browser",
                "--format=iife", "--target=chrome110",
                "--outfile=" + root.resolve("player.bundle.js")), 120, root.resolve("bundle.log"));
        if (!Files.isRegularFile(root.resolve("player.bundle.js")))
            throw new IOException("Chromium player bundle was not created");
        notify.accept("Checking official Chrome for Testing releases...");
        Platform platform = Platform.current();
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(CHROME_INDEX)
                .timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200 || response.body().length() > 2_000_000)
            throw new IOException("Could not retrieve trusted Chrome version metadata");
        ChromeRelease release = selectChrome(response.body(), platform);
        Path chromeDir = Files.createDirectories(root.resolve("chrome"));
        Path archive = Files.createTempFile(chromeDir, "chrome-", ".zip");
        Path stage = Files.createTempDirectory(chromeDir, ".staging-");
        try {
            download(client, release.url(), archive, notify);
            notify.accept("Extracting Chrome for Testing " + release.version() + "...");
            if (platform == Platform.MAC_ARM64 || platform == Platform.MAC_X64) {
                run(List.of("ditto", "-x", "-k", archive.toString(), stage.toString()),
                        300, root.resolve("extract.log"));
            } else {
                extractZip(archive, stage);
            }
            Path binary = stage.resolve(platform.binary);
            if (!Files.isRegularFile(binary)) throw new IOException("Downloaded Chrome executable is missing");
            if (platform != Platform.WINDOWS_X64) {
                binary.toFile().setExecutable(true);
                if (platform == Platform.LINUX_ARM64 || platform == Platform.LINUX_X64) {
                    for (String helper : List.of("chrome_crashpad_handler", "chrome-sandbox")) {
                        Path p = binary.getParent().resolve(helper);
                        if (Files.isRegularFile(p)) p.toFile().setExecutable(true);
                    }
                }
            }
            Path current = chromeDir.resolve("current"), backup = chromeDir.resolve("previous");
            if (Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) removeTree(backup);
            boolean previous = false, promoted = false;
            try {
                if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                    Files.move(current, backup); previous = true;
                }
                Files.move(stage, current); promoted = true;
            } catch (IOException e) {
                if (promoted) removeTree(current);
                if (previous) Files.move(backup, current);
                throw e;
            }
            if (previous) {
                try { removeTree(backup); } catch (IOException ignored) { }
            }
            Files.writeString(root.resolve("installed-version"), RUNTIME_VERSION);
            notify.accept("Xbox Cloud runtime ready: Chrome " + release.version()
                    + ". Open Xbox Cloud Gaming in the WII-UU dock.");
        } finally {
            Files.deleteIfExists(archive);
            if (Files.exists(stage, LinkOption.NOFOLLOW_LINKS)) removeTree(stage);
        }
    }

    /** Trusted Google release URLs only; no shell downloaders or redirects to arbitrary hosts. */
    static void download(HttpClient client, URI uri, Path archive, Consumer<String> notify)
            throws IOException, InterruptedException {
        CompletableFuture<HttpResponse<Path>> future = client.sendAsync(
                HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(15))
                        .build(), HttpResponse.BodyHandlers.ofFile(archive));
        long start = System.nanoTime(), lastChange = start, lastBytes = 0;
        try {
            while (true) {
                try {
                    HttpResponse<Path> response = future.get(1, TimeUnit.SECONDS);
                    if (response.statusCode() != 200)
                        throw new IOException("Google returned HTTP " + response.statusCode());
                    break;
                } catch (TimeoutException ignored) { }
                catch (ExecutionException e) { throw new IOException("Chrome download interrupted", e.getCause()); }
                long bytes = Files.size(archive), now = System.nanoTime();
                if (bytes > MAX_CHROME_ZIP) throw new IOException("Chrome download exceeds maximum size");
                if (bytes != lastBytes) {
                    lastBytes = bytes; lastChange = now;
                }
                if (now - start > TimeUnit.MINUTES.toNanos(15)
                        || now - lastChange > TimeUnit.SECONDS.toNanos(45))
                    throw new IOException("Chrome download stalled or timed out");
            }
        } finally { if (!future.isDone()) future.cancel(true); }
        if (Files.size(archive) > MAX_CHROME_ZIP) throw new IOException("Chrome ZIP exceeded maximum size");
    }

    static void extractZip(Path archive, Path destination) throws IOException {
        Path base = destination.toAbsolutePath().normalize();
        long total = 0; int entries = 0;
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            byte[] buffer = new byte[65536];
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (++entries > 30000 || name.isBlank() || name.startsWith("/")
                        || name.indexOf('\\') >= 0 || name.indexOf(':') >= 0)
                    throw new IOException("Unsafe Chrome archive entry");
                Path file = base.resolve(name).normalize();
                if (!file.startsWith(base) || file.equals(base))
                    throw new IOException("Chrome ZIP path escapes install root");
                if (entry.isDirectory()) {
                    Files.createDirectories(file);
                } else {
                    Files.createDirectories(file.getParent());
                    try (var output = Files.newOutputStream(file)) {
                        for (int n; (n = zip.read(buffer)) != -1;) {
                            total += n;
                            if (total > MAX_EXTRACTED)
                                throw new IOException("Chrome extraction exceeded the size limit");
                            output.write(buffer, 0, n);
                        }
                    }
                }
                zip.closeEntry();
            }
        }
    }

    private static void run(List<String> args, int seconds, Path log)
            throws IOException, InterruptedException {
        Process process = new ProcessBuilder(args)
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            if (!process.waitFor(seconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException(args.get(0) + " timed out. Check " + log);
            }
            if (process.exitValue() != 0)
                throw new IOException(args.get(0) + " failed (exit " + process.exitValue() + "). Check " + log);
        } catch (InterruptedException e) { process.destroyForcibly(); throw e; }
    }

    private static void removeTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                if (error != null) throw error;
                Files.delete(dir); return FileVisitResult.CONTINUE;
            }
        });
    }
}
