package wiiuu.core;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public final class AchievementViewerTest {
    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/API/", exchange -> {
            String query = exchange.getRequestURI().getRawQuery();
            String path = exchange.getRequestURI().getPath();
            String response;
            int code = 200;
            if (!query.contains("y=first-key") || !query.contains("u=Player")) {
                code = 401;
                response = "{}";
            } else if (path.endsWith("API_GetUserCompletionProgress.php") && query.contains("o=50")) {
                response = "{\"Total\":51,\"Results\":[{\"GameID\":42,\"Title\":\"Game\",\"ConsoleName\":\"NES\",\"NumAwarded\":2,\"NumAwardedHardcore\":1,\"MaxPossible\":3}]}";
            } else if (path.endsWith("API_GetGameInfoAndUserProgress.php") && query.contains("g=42")) {
                response = "{\"Achievements\":{\"3\":{\"Title\":\"Locked\",\"DisplayOrder\":3,\"Points\":5},\"1\":{\"Title\":\"First\",\"Description\":\"Win\",\"DisplayOrder\":1,\"DateEarned\":\"2026-11-25\",\"Points\":10},\"2\":{\"Title\":\"Hard\",\"DisplayOrder\":2,\"DateEarnedHardcore\":\"2026-11-26\",\"Points\":25}}}";
            } else {
                code = 400;
                response = "{}";
            }
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(code, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        try {
            System.setProperty("wiiuu.raApi", "http://127.0.0.1:" + server.getAddress().getPort() + "/API/");
            Config config = new Config(Files.createTempDirectory("achievement-viewer"));
            config.set("ra.user", "Player");
            config.set("ra.apikey", "first-key");
            config.set("ra.enabled", "false");
            var viewer = new Achievements(config).viewer();
            check(viewer.configured() && viewer.current(), "viewer independent of home badges");
            var page = viewer.games(50, 50);
            check(page.total() == 51 && page.games().size() == 1, "pagination");
            var game = page.games().get(0);
            check(game.id() == 42 && game.earned() == 2 && game.hardcore() == 1 && game.total() == 3, "progress");
            config.set("ra.user", "Other");
            config.set("ra.apikey", "other-key");
            check(!viewer.current(), "credential changes invalidate viewer");
            var details = viewer.details(42);
            check(details.size() == 3 && details.get(0).description().equals("Win"), "details sorted");
            check(details.get(0).state().equals("Unlocked"), "softcore");
            check(details.get(1).state().equals("Hardcore"), "hardcore");
            check(details.get(2).state().equals("Locked"), "locked");
            try {
                new Achievements(config).viewer().games(50, 50);
                throw new AssertionError("Invalid credentials accepted");
            } catch (java.io.IOException expected) { }
            System.out.println("AchievementViewerTest passed");
        } finally {
            server.stop(0);
        }
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
