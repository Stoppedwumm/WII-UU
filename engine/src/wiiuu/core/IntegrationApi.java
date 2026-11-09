package wiiuu.core;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/** The local automation API shared by the two desktop applications. */
public final class IntegrationApi {
    private static final String PREFIX = "/api/v1/";

    public interface Controls {
        Game current();
        boolean launch(Game game);
        void stop();
    }

    private IntegrationApi() {}

    public static void start(Config config, String app, String version, Library library,
                             Predicate<Game> supported, Controls controls) {
        int port = config.getInt("integration." + app + ".port", 0);
        if (port == 0) return;
        try {
            String token = Files.readString(config.home().resolve("integration.token")).strip();
            if (!token.matches("[0-9a-fA-F]{64}")) throw new IOException("integration.token must contain 64 hex digits");
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 8);
            byte[] authorization = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
            server.createContext(PREFIX, exchange -> {
                try (exchange) {
                    if (!("127.0.0.1:" + port).equals(exchange.getRequestHeaders().getFirst("Host"))) {
                        reply(exchange, 400, "{\"error\":\"invalid_host\"}");
                        return;
                    }
                    String header = exchange.getRequestHeaders().getFirst("Authorization");
                    if (header == null || !MessageDigest.isEqual(authorization, header.getBytes(StandardCharsets.UTF_8))) {
                        exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
                        reply(exchange, 401, "{\"error\":\"unauthorized\"}");
                        return;
                    }
                    handle(exchange, app, version, library, supported, controls);
                }
            });
            server.start();
            System.out.println("[integration] " + app + " API on http://127.0.0.1:" + port + PREFIX);
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("[integration] " + app + " API could not start: " + e.getMessage());
        }
    }

    private static void handle(HttpExchange exchange, String app, String version, Library library,
                               Predicate<Game> supported, Controls controls) throws IOException {
        String path = exchange.getRequestURI().getRawPath().substring(PREFIX.length());
        String[] parts = path.split("/", -1);
        boolean read = path.equals("status") || path.equals("games");
        boolean launch = parts.length == 3 && parts[0].equals("launch");
        if (!read && !launch && !path.equals("stop") && !path.equals("rescan")) {
            reply(exchange, 404, "{\"error\":\"not_found\"}");
            return;
        }
        String method = read ? "GET" : "POST";
        if (!method.equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", method);
            reply(exchange, 405, "{\"error\":\"method_not_allowed\"}");
            return;
        }
        switch (path) {
            case "games" -> {
                List<Game> games = library.snapshot().games().values().stream()
                        .flatMap(List::stream).filter(supported).toList();
                reply(exchange, 200, "{\"games\":[" + games.stream().map(IntegrationApi::gameJson)
                        .collect(Collectors.joining(",")) + "]}");
            }
            case "rescan" -> {
                library.rescanAsync();
                reply(exchange, 202, "{\"accepted\":true}");
            }
            default -> {
                // Read and mutate playback state on the same thread as the UI.
                FutureTask<Response> task = new FutureTask<>(() -> {
                    Game current = controls.current();
                    if (path.equals("status")) {
                        return new Response(200, "{\"app\":" + quote(app) + ",\"version\":" + quote(version)
                                + ",\"game\":" + (current == null ? "null" : gameJson(current)) + "}");
                    }
                    if (path.equals("stop")) {
                        controls.stop();
                        return new Response(202, "{\"accepted\":true}");
                    }
                    if (current != null) return new Response(409, "{\"error\":\"game_running\"}");
                    Game game = library.snapshot().find(parts[1], parts[2]).filter(supported).orElse(null);
                    if (game == null) return new Response(404, "{\"error\":\"game_not_found\"}");
                    return controls.launch(game) ? new Response(202, "{\"accepted\":true}")
                            : new Response(409, "{\"error\":\"launch_rejected\"}");
                });
                SwingUtilities.invokeLater(task);
                Response response;
                try {
                    response = task.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    response = new Response(503, "{\"error\":\"interrupted\"}");
                } catch (java.util.concurrent.ExecutionException e) {
                    System.err.println("[integration] " + e.getCause());
                    response = new Response(500, "{\"error\":\"internal_error\"}");
                }
                reply(exchange, response.status(), response.body());
            }
        }
    }

    private record Response(int status, String body) {}

    private static String gameJson(Game game) {
        return "{\"system\":" + quote(game.system().id()) + ",\"id\":" + quote(game.id())
                + ",\"name\":" + quote(game.name()) + "}";
    }

    private static String quote(String value) {
        StringBuilder result = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') result.append('\\').append(c);
            else if (c < 32) result.append(String.format("\\u%04x", (int) c));
            else result.append(c);
        }
        return result.append('"').toString();
    }

    private static void reply(HttpExchange exchange, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }
}
