package wiiuu.net;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Separate loopback-only origin: a tunnel never exposes peers, file transfers or setup forms. */
public final class RelayGateway implements AutoCloseable {
    private static final String COOKIE = "wiiuu_relay";
    private static final Set<String> GET = Set.of("/", "/index.html", "/manifest.json", "/music.js", "/icon.png",
            "/api/status", "/api/layouts", "/api/frame", "/api/audio", "/api/library");
    private static final Set<String> POST = Set.of("/api/hello", "/api/input", "/api/launch", "/api/player",
            "/api/state", "/api/layouts", "/api/close", "/api/idle", "/api/migration/skip");
    private final String secret;
    private final HttpServer server;
    private final ExecutorService executor;

    public RelayGateway(HttpHandler gamepad) throws IOException {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        secret = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "relay-http"); t.setDaemon(true); return t; });
        server.setExecutor(executor);
        server.createContext("/", ex -> handle(ex, gamepad));
        server.start();
    }

    public int port() { return server.getAddress().getPort(); }
    public String pairingUrl(String publicUrl, String code) { return publicUrl + "/?relay=" + secret + "&code=" + code; }

    private void handle(HttpExchange ex, HttpHandler gamepad) throws IOException {
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        String path = ex.getRequestURI().getPath(), method = ex.getRequestMethod();
        if (!(method.equals("GET") && GET.contains(path) || method.equals("POST") && POST.contains(path))) {
            reject(ex, 404); return;
        }
        String origin = ex.getRequestHeaders().getFirst("Origin");
        if (origin != null) {
            try {
                if (!URI.create(origin).getRawAuthority().equalsIgnoreCase(ex.getRequestHeaders().getFirst("Host"))) {
                    reject(ex, 403); return;
                }
            } catch (RuntimeException e) { reject(ex, 403); return; }
        }
        String token = parameter(ex.getRequestURI().getRawQuery(), "relay");
        if (path.equals("/") && method.equals("GET") && matches(token)) {
            ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=" + secret + "; Path=/; Secure; HttpOnly; SameSite=Strict");
            // Keep the capability out of subsequent requests and referrers. The short LAN code alone is insufficient.
            String code = parameter(ex.getRequestURI().getRawQuery(), "code");
            ex.getResponseHeaders().set("Location", "/?code=" + java.net.URLEncoder.encode(code, StandardCharsets.UTF_8));
            ex.sendResponseHeaders(303, -1); ex.close(); return;
        }
        String cookies = ex.getRequestHeaders().getFirst("Cookie");
        boolean authenticated = false;
        if (cookies != null) for (String cookie : cookies.split(";")) {
            String[] part = cookie.trim().split("=", 2);
            if (part.length == 2 && part[0].equals(COOKIE) && matches(part[1])) authenticated = true;
        }
        if (!authenticated) { reject(ex, 403); return; }
        gamepad.handle(ex);
    }

    private boolean matches(String token) {
        return token != null && MessageDigest.isEqual(secret.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
    }
    private static String parameter(String query, String key) {
        if (query != null) for (String pair : query.split("&")) {
            String[] p = pair.split("=", 2);
            if (p.length == 2 && p[0].equals(key)) try { return URLDecoder.decode(p[1], StandardCharsets.UTF_8); }
            catch (IllegalArgumentException ignored) { return ""; }
        }
        return "";
    }
    private static void reject(HttpExchange ex, int status) throws IOException {
        byte[] body = "Open the current relay QR code shown on the WII-UU computer.".getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        ex.sendResponseHeaders(status, body.length);
        try (ex) { ex.getResponseBody().write(body); }
    }
    @Override public void close() { server.stop(0); executor.shutdownNow(); }
}
