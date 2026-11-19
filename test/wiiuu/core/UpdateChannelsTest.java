package wiiuu.core;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicReference;

/** Local HTTP fixtures exercise actual updater feed selection without external services. */
public final class UpdateChannelsTest {
    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> stable = new AtomicReference<>(manifest("2.0.8", "stable"));
        AtomicReference<String> beta = new AtomicReference<>(manifest("2.0.9-beta.10+gabc", "beta"));
        server.createContext("/stable/version.json", e -> {
            byte[] bytes = stable.get().getBytes(StandardCharsets.UTF_8);
            e.sendResponseHeaders(200, bytes.length); e.getResponseBody().write(bytes); e.close();
        });
        server.createContext("/beta/version.json", e -> {
            if (beta.get() == null) { e.sendResponseHeaders(404, -1); e.close(); return; }
            byte[] bytes = beta.get().getBytes(StandardCharsets.UTF_8);
            e.sendResponseHeaders(200, bytes.length); e.getResponseBody().write(bytes); e.close();
        });
        server.start();
        try {
            Config config = new Config(Files.createTempDirectory("wiiuu-channels-"));
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            config.set("update.stableUrl", base + "/stable/version.json");
            config.set("update.betaUrl", base + "/beta/version.json");
            Updater updater = new Updater(config, "2.0.8");
            check(updater.channel().equals("stable") && updater.check() == null, "stable subscription default");
            config.set("update.channel", "beta");
            check(updater.check().version().startsWith("2.0.9-beta.10"), "beta subscription");
            Updater installedBeta = new Updater(config, "2.0.9-beta.10+gdef");
            check(installedBeta.check() == null, "same version different commit is not an update");
            stable.set(manifest("2.0.9", "stable"));
            check(installedBeta.check().version().equals("2.0.9"), "final supersedes beta");
            stable.set(manifest("2.0.10", "stable"));
            check(updater.check().version().equals("2.0.10"), "newer stable wins over older beta feed");
            config.set("update.channel", "stable");
            stable.set(manifest("3.0.0-beta.1", "beta"));
            check(updater.check() == null, "stable never accepts prereleases even in wrong feed");
            stable.set(manifest("garbage", "stable"));
            try { updater.check(); throw new AssertionError("Malformed version accepted"); }
            catch (java.io.IOException expected) { }
            stable.set(manifest("2.0.8", "stable")); beta.set(null);
            config.set("update.channel", "beta");
            check(updater.check() == null, "optional beta channel before first publication");
            config.set("update.url", base + "/stable/version.json");
            check(updater.feedUrl().equals(base + "/stable/version.json"), "custom override retained");
            Config firstBeta = new Config(Files.createTempDirectory("wiiuu-first-beta-"));
            check(new Updater(firstBeta, "2.0.9-beta.1").channel().equals("beta"), "beta installation default");
            Config promoted = new Config(firstBeta.home());
            check(new Updater(promoted, "2.0.9").channel().equals("beta"), "subscription survives promotion to final");
            System.out.println("Update channel tests passed");
        } finally { server.stop(0); }
    }
    private static String manifest(String version, String channel) {
        return "{\"version\":\"" + version + "\",\"channel\":\"" + channel + "\",\"zip\":\"https://example.test/release.zip\",\"sha256\":\"" + "0".repeat(64) + "\"}";
    }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
