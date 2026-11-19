package wiiuu.core;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

/** Real HTTP fixtures for archive pagination, verification, metadata and release-note selection. */
public final class ReleaseArchiveTest {
    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        AtomicReference<String> query = new AtomicReference<>();
        AtomicReference<String> response = new AtomicReference<>();
        server.createContext("/api/releases", e -> {
            check(e.getRequestHeaders().getFirst("Authorization") == null, "archive browsing does not send credentials");
            query.set(e.getRequestURI().getQuery());
            byte[] data = response.get().getBytes(StandardCharsets.UTF_8);
            e.sendResponseHeaders(200, data.length); e.getResponseBody().write(data); e.close();
        });
        server.createContext("/notes", e -> {
            byte[] data = "## 2.0.10\n- Future\n## 2.0.9\n- Current cycle\n## 2.0.9-beta.3\n- Beta fix\n## 2.0.8\n- Previous\n".getBytes(StandardCharsets.UTF_8);
            e.sendResponseHeaders(200, data.length); e.getResponseBody().write(data); e.close();
        });
        server.start();
        try {
            ReleaseArchive archive = new ReleaseArchive(base);
            response.set(page(3, build(base, "2.0.9", "stable"), build(base, "2.0.9-beta.3+gabc", "beta")));
            ReleaseArchive.Page first = archive.list("all", 0, 2);
            check(first.builds().size() == 2 && first.hasMore(), "first page");
            check(query.get().contains("offset=0") && !query.get().contains("channel="), "all filter request");
            ReleaseArchive.Build beta = first.builds().get(1);
            check(beta.installed("2.0.9-beta.3+gdef"), "installed marker ignores metadata");
            check(!first.builds().get(0).installed("2.0.9-beta.3"), "final is distinct from beta");
            check(beta.bytes() == 12345 && beta.release().sha256().equals("a".repeat(64)), "download verification metadata");
            check(archive.notes(beta).size() == 2 && archive.notes(first.builds().get(0)).size() == 1, "build-specific release notes");
            response.set(page(3, build(base, "2.0.8", "stable")));
            check(!archive.list("stable", 2, 2).hasMore(), "last page");
            check(query.get().contains("channel=stable") && query.get().contains("offset=2"), "filtered pagination");
            response.set(page(0));
            check(archive.list("beta", 0, 12).builds().isEmpty(), "empty archive");
            response.set(page(1, build(base, "2.0.9", "stable").replace("a".repeat(64), "bad")));
            rejects(archive, "missing checksum");
            response.set(page(1, build(base, "2.0.9", "beta")));
            rejects(archive, "incorrect channel");
            response.set(page(1, build(base, "2.0.9", "stable").replace(base + "/release.zip", "file:///tmp/release.zip")));
            rejects(archive, "non-web download URL");
            response.set(" ".repeat(2 * 1024 * 1024 + 1));
            rejects(archive, "bounded response");
            System.out.println("Release archive HTTP tests passed");
        } finally { server.stop(0); }
    }
    private static String page(int total, String... builds) { return "{\"total\":" + total + ",\"releases\":[" + String.join(",", builds) + "]}"; }
    private static String build(String base, String version, String channel) {
        String key = version.split("\\+")[0];
        return "{\"version\":\"" + version + "\",\"releaseVersion\":\"" + key + "\",\"channel\":\"" + channel
                + "\",\"zip\":\"" + base + "/release.zip\",\"sha256\":\"" + "a".repeat(64)
                + "\",\"commit\":\"abc\",\"publishedAt\":\"2026-11-19T12:00:00Z\",\"assets\":{\"WII-UU-" + key
                + ".zip\":{\"size\":12345},\"CHANGELOG.md\":{\"url\":\"" + base + "/notes\"}}}";
    }
    private static void rejects(ReleaseArchive archive, String message) throws Exception {
        try { archive.list("all", 0, 12); throw new AssertionError(message); }
        catch (IOException expected) { }
    }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
