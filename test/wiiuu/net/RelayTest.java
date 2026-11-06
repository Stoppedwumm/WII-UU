package wiiuu.net;

import java.net.*;
import java.net.http.*;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicReference;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Exercises the real relay HTTP boundary without a public tunnel or access to the internet. */
public final class RelayTest {
    public static void main(String[] args) throws Exception {
        check(CloudflaredRelay.platform("Windows 11", "aarch64").equals("windows-amd64"), "Windows ARM mapping");
        check(CloudflaredRelay.platform("Mac OS X", "aarch64").equals("darwin-arm64"), "Apple Silicon mapping");
        check(CloudflaredRelay.platform("Linux", "amd64").equals("linux-amd64"), "Linux mapping");
        check(CloudflaredRelay.publicUrl("INFO | https://some-test-words.trycloudflare.com |").equals("https://some-test-words.trycloudflare.com"), "upstream URL");
        check(CloudflaredRelay.publicUrl("https://x.trycloudflare.com.evil.org") == null, "hostname boundary");
        check(CloudflaredRelay.publicUrl("https://evil.org") == null, "only quick tunnel URLs");
        var cmd = CloudflaredRelay.command(Path.of("C:/a path/cloudflared.exe"), Path.of("C:/a path/config.yml"), 12345);
        check(cmd.contains("http2") && cmd.contains("http://127.0.0.1:12345") && cmd.contains("--no-autoupdate"), "controlled loopback command");
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        AtomicInteger requests = new AtomicInteger();
        try (RelayGateway gateway = new RelayGateway(ex -> {
            requests.incrementAndGet(); ex.sendResponseHeaders(204, -1); ex.close();
        })) {
            String base = "http://127.0.0.1:" + gateway.port();
            check(get(client, base + "/", null).statusCode() == 403, "anonymous root blocked");
            check(get(client, base + "/api/status?c=known-pad", null).statusCode() == 403, "remembered pad ID alone insufficient");
            String pairing = gateway.pairingUrl("https://some-test-words.trycloudflare.com", "1234");
            QrCode.encode(pairing);
            var bootstrap = get(client, base + URI.create(pairing).getRawPath() + "?" + URI.create(pairing).getRawQuery(), null);
            check(bootstrap.statusCode() == 303, "capability bootstrap");
            String cookie = bootstrap.headers().firstValue("Set-Cookie").orElseThrow();
            check(cookie.contains("Secure") && cookie.contains("HttpOnly") && cookie.contains("SameSite=Strict"), "session cookie attributes");
            cookie = cookie.split(";", 2)[0];
            check(bootstrap.headers().firstValue("Location").orElseThrow().equals("/?code=1234"), "secret removed from navigation");
            check(get(client, base + "/api/status", cookie).statusCode() == 204, "authenticated status");
            check(get(client, base + "/api/frame", cookie).statusCode() == 204, "authenticated single-frame stream");
            for (String route : List.of("/api/form", "/api/openbased", "/peers/hello", "/split/play", "/api/stream"))
                check(get(client, base + route, cookie).statusCode() == 404, "blocked route " + route);
            var input = HttpRequest.newBuilder(URI.create(base + "/api/input")).header("Cookie", cookie).POST(HttpRequest.BodyPublishers.ofString("b A 1")).build();
            check(client.send(input, HttpResponse.BodyHandlers.discarding()).statusCode() == 204, "authenticated controls");
            var foreign = HttpRequest.newBuilder(URI.create(base + "/api/input")).header("Cookie", cookie).header("Origin", "https://evil.example").POST(HttpRequest.BodyPublishers.ofString("b A 1")).build();
            check(client.send(foreign, HttpResponse.BodyHandlers.discarding()).statusCode() == 403, "cross-origin input blocked");
            check(requests.get() == 3, "only allowed authenticated requests reach the gamepad");
            try (RelayGateway next = new RelayGateway(ex -> { throw new AssertionError("old secret accepted"); })) {
                check(get(client, "http://127.0.0.1:" + next.port() + "/api/status", cookie).statusCode() == 403, "restart invalidates capability");
            }
        }
        // Drive the real wrapper lifecycle with a local fake child, never a public tunnel.
        AtomicReference<Process> child = new AtomicReference<>();
        String java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        String classpath = System.getProperty("java.class.path");
        // The fake process keeps the original working directory, so relative classpaths remain valid.
        try (CloudflaredRelay relay = new CloudflaredRelay(Files.createTempDirectory("relay-lifecycle"), ex -> ex.close(), "1234", () -> {},
                () -> Path.of("fake-helper"), (command, work) -> {
                    Process fake = new ProcessBuilder(java, "-cp", classpath, "wiiuu.net.RelayTest$FakeHelper").redirectErrorStream(true).start();
                    child.set(fake); return fake;
                })) {
            relay.start();
            await(() -> relay.pairingUrl() != null, "relay becomes ready after connection registration");
            String first = relay.pairingUrl();
            Process firstChild = child.get();
            relay.close();
            check(!relay.active() && relay.pairingUrl() == null, "stop clears public URL");
            await(() -> !firstChild.isAlive(), "stop terminates helper");
            relay.start();
            await(() -> relay.pairingUrl() != null, "relay restarts");
            check(!relay.pairingUrl().equals(first), "restart rotates pairing secret");
        }
        await(() -> !child.get().isAlive(), "close terminates helper");
        System.out.println("Relay boundary tests passed");
    }
    public static final class FakeHelper {
        public static void main(String[] args) throws Exception {
            System.out.println("https://test-relay-words.trycloudflare.com");
            System.out.println("Registered tunnel connection connIndex=0");
            System.out.flush();
            while (System.in.read() != -1) {}
        }
    }
    private static void await(java.util.function.BooleanSupplier done, String label) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!done.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        check(done.getAsBoolean(), label);
    }
    private static HttpResponse<String> get(HttpClient client, String url, String cookie) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(url));
        if (cookie != null) request.header("Cookie", cookie);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    private static void check(boolean ok, String label) { if (!ok) throw new AssertionError(label); }
}
