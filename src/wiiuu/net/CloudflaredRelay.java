package wiiuu.net;

import com.sun.net.httpserver.HttpHandler;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

/** Owns one temporary cloudflared Quick Tunnel. No account, service installation or inbound port. */
public final class CloudflaredRelay implements AutoCloseable {
    public static final String VERSION = "2026.10.7";
    private static final Pattern URL = Pattern.compile("https://[a-z0-9-]+\\.trycloudflare\\.com(?![a-z0-9.-])");
    private final Path home;
    private final HttpHandler gamepad;
    private final String code;
    private final Runnable changed;
    @FunctionalInterface interface Starter { Process start(List<String> command, Path work) throws IOException; }
    private final Callable<Path> binary;
    private final Starter starter;
    private Process process;
    private RelayGateway gateway;
    private long generation;
    private volatile boolean active;
    private volatile String status = "Relay is off", pairingUrl;

    public CloudflaredRelay(Path home, HttpHandler gamepad, String code, Runnable changed) {
        this(home, gamepad, code, changed, () -> bundledBinary(home),
                (command, work) -> new ProcessBuilder(command).directory(work.toFile()).redirectErrorStream(true).start());
    }
    CloudflaredRelay(Path home, HttpHandler gamepad, String code, Runnable changed, Callable<Path> binary, Starter starter) {
        this.home = home; this.gamepad = gamepad; this.code = code; this.changed = changed;
        this.binary = binary; this.starter = starter;
    }
    public boolean active() { return active; }
    public String status() { return status; }
    public String pairingUrl() { return pairingUrl; }

    public synchronized void start() {
        if (active) return;
        active = true; pairingUrl = null; status = "Starting relay…";
        long id = ++generation;
        changed.run();
        Thread worker = new Thread(() -> run(id), "cloudflared-relay");
        worker.setDaemon(true); worker.start();
    }

    private void run(long id) {
        Process child = null;
        try {
            Path executable = binary.call();
            Path work = home.resolve("relay");
            Files.createDirectories(work);
            Path config = work.resolve("quick-tunnel.yml");
            Files.writeString(config, "{}\n"); // ignore an unrelated ~/.cloudflared/config.yml
            synchronized (this) {
                if (id != generation) return;
                gateway = new RelayGateway(gamepad);
                child = starter.start(command(executable, config, gateway.port()), work);
                process = child;
            }
            final Process running = child;
            CompletableFuture.delayedExecutor(60, TimeUnit.SECONDS).execute(() -> {
                synchronized (this) {
                    if (id == generation && pairingUrl == null) fail(id, "Relay timed out. This network may block Cloudflare Tunnel.");
                }
            });
            String candidate = null;
            boolean connected = false;
            try (BufferedReader lines = new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8));
                 BufferedWriter log = Files.newBufferedWriter(home.resolve("relay/cloudflared.log"))) {
                int logged = 0;
                for (String line; (line = lines.readLine()) != null;) {
                    if (logged < 2_000_000) { log.write(line); log.newLine(); log.flush(); logged += line.length(); }
                    String found = publicUrl(line);
                    if (found != null) candidate = found;
                    if (line.contains("Registered tunnel connection")) connected = true;
                    synchronized (this) {
                        if (id != generation) return;
                        if (candidate != null && connected && pairingUrl == null) {
                            pairingUrl = gateway.pairingUrl(candidate, code);
                            status = "Relay ready — scan the pairing QR code";
                            changed.run();
                        }
                    }
                }
            }
            int exit = running.waitFor();
            fail(id, "Relay stopped (exit " + exit + "). Start it again, or check relay/cloudflared.log.");
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            fail(id, "Relay unavailable: " + e.getMessage());
        } finally {
            if (child != null && child.isAlive()) child.destroyForcibly();
        }
    }

    private synchronized void fail(long id, String message) {
        if (id != generation) return;
        stopProcess(); active = false; pairingUrl = null; status = message;
        ++generation; changed.run();
    }
    @Override public synchronized void close() {
        ++generation; stopProcess(); active = false; pairingUrl = null; status = "Relay is off"; changed.run();
    }
    private void stopProcess() {
        if (process != null) {
            Process old = process; old.destroy();
            old.onExit().orTimeout(3, TimeUnit.SECONDS).exceptionally(e -> { old.destroyForcibly(); return old; });
        }
        process = null;
        if (gateway != null) gateway.close();
        gateway = null;
    }

    static List<String> command(Path binary, Path config, int port) {
        return List.of(binary.toString(), "tunnel", "--config", config.toString(), "--no-autoupdate",
                "--protocol", "http2", "--url", "http://127.0.0.1:" + port);
    }
    static String publicUrl(String line) {
        var match = URL.matcher(line);
        return match.find() ? match.group() : null;
    }
    static String platform(String os, String arch) throws IOException {
        os = os.toLowerCase(Locale.ROOT); arch = arch.toLowerCase(Locale.ROOT);
        String system = os.contains("mac") || os.contains("darwin") ? "darwin" : os.contains("win") ? "windows" : os.contains("linux") ? "linux" : "";
        String cpu = switch (arch) { case "amd64", "x86_64" -> "amd64"; case "aarch64", "arm64" -> "arm64";
            case "x86", "i386", "i686" -> "386"; default -> ""; };
        // Upstream has no Windows ARM binary: its x64 executable runs through Windows' built-in emulation.
        if (system.equals("windows") && cpu.equals("arm64")) cpu = "amd64";
        if (system.isEmpty() || cpu.isEmpty() || !system.equals("windows") && cpu.equals("386"))
            throw new IOException("No bundled cloudflared for " + os + "/" + arch);
        return system + "-" + cpu;
    }
    static Path bundledBinary(Path home) throws Exception {
        String target = platform(System.getProperty("os.name"), System.getProperty("os.arch"));
        String name = target.startsWith("windows-") ? "cloudflared.exe" : "cloudflared";
        String resource = "/cloudflared/" + target + "/";
        String expected;
        try (InputStream sum = CloudflaredRelay.class.getResourceAsStream(resource + "sha256")) {
            if (sum == null) throw new IOException("This source build has no bundled relay. Install a packaged WII-UU build.");
            expected = new String(sum.readAllBytes(), StandardCharsets.US_ASCII).trim();
        }
        Path dest = home.resolve("bin/cloudflared/" + VERSION + "/" + target + "/" + name);
        if (Files.isRegularFile(dest) && digest(dest).equals(expected)) { dest.toFile().setExecutable(true, true); return dest; }
        Files.createDirectories(dest.getParent());
        Path temp = Files.createTempFile(dest.getParent(), "cloudflared-", ".tmp");
        try (InputStream in = CloudflaredRelay.class.getResourceAsStream(resource + name)) {
            if (in != null) Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
            else downloadBinary(target, name, temp);
            if (!digest(temp).equals(expected)) throw new IOException("Cloudflared checksum mismatch");
            temp.toFile().setExecutable(true, true);
            Files.move(temp, dest, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
        return dest;
    }
    private static void downloadBinary(String target, String name, Path temp) throws Exception {
        String site = System.getProperty("wiiuu.cloudflared.base",
                "https://wiiuu.stoppedwumm.net/download/cloudflared/");
        var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(site).resolve(VERSION + "/" + target + "/" + name))
                .timeout(java.time.Duration.ofMinutes(3)).build();
        var client = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(15)).build();
        var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofFile(temp,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING));
        if (response.statusCode() != 200) throw new IOException("Relay helper download failed (HTTP " + response.statusCode() + ")");
    }

    private static String digest(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] b = new byte[65536]; int n;
            while ((n = in.read(b)) != -1) digest.update(b, 0, n);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
