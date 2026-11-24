package wiiuu.core;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** Explicitly opt-in archive diagnostics. The four-digit PIN is an identifier, never a password. */
public final class Diagnostics {
    public static final String DEFAULT_URL = "https://wii-uu-archive.stoppedwumm.net";
    public static final String DISCLAIMER = "Optional diagnostic sharing sends recent WII-UU error/log lines "
            + "and the app version to the WII-UU archive server approximately every 15 minutes. "
            + "Your four-digit PIN labels your reports but is not a password; other consoles may use "
            + "the same PIN. Logs can contain personal information. WII-UU removes common credentials, "
            + "home-directory names, URLs and IP addresses before upload, but automatic redaction "
            + "cannot guarantee complete privacy. The archive keeps up to 30 reports per device "
            + "for up to 30 days. Only the archive administrator can view them. "
            + "You can stop sharing and request deletion in Settings > System > Diagnostics.";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NEVER).build();
    private static ScheduledExecutorService scheduler;
    private Diagnostics() {}

    private static Path file(Config config) { return config.home().resolve("diagnostics.properties"); }
    private static Properties load(Config config) {
        Properties p = new Properties();
        try {
            if (Files.isRegularFile(file(config))) {
                try (var in = Files.newInputStream(file(config))) { p.load(in); }
            }
        } catch (IOException e) { System.err.println("[diagnostics] settings read failed: " + e.getMessage()); }
        return p;
    }
    private static void store(Config config, Properties p) throws IOException {
        Files.createDirectories(config.home());
        Path tmp = Files.createTempFile(config.home(), ".diagnostics-", ".tmp");
        try {
            try (var out = Files.newOutputStream(tmp)) { p.store(out, "Opt-in WII-UU diagnostics"); }
            try {
                Files.setPosixFilePermissions(tmp, Set.of(PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE));
            } catch (UnsupportedOperationException ignored) { } // Windows
            try {
                Files.move(tmp, file(config), java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, file(config), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(tmp); }
    }
    public static boolean validPin(String pin) { return pin != null && pin.matches("[0-9]{4}"); }
    public static synchronized boolean enabled(Config config) {
        return "true".equals(load(config).getProperty("consent"));
    }
    public static synchronized String status(Config config) {
        Properties p = load(config);
        if ("true".equals(p.getProperty("consent")))
            return p.containsKey("token") ? "Sharing, PIN " + p.getProperty("pin", "????")
                    : "Enrolling, PIN " + p.getProperty("pin", "????");
        return p.containsKey("revokeId") ? "Stopped; server deletion pending" : "Off";
    }
    public static synchronized void enable(Config config, String pin) throws IOException {
        if (!validPin(pin)) throw new IllegalArgumentException("Enter exactly four digits");
        Properties p = load(config);
        if (p.containsKey("revokeId")) throw new IOException("Prior deletion pending; reconnect before enrolling");
        p.setProperty("pin", pin);
        p.setProperty("consent", "true");
        p.remove("token"); p.remove("deviceId"); // Re-enroll only with explicit consent
        store(config,p);
    }
    /** Stops uploads immediately. Server deletion is retried while offline. */
    public static synchronized void disable(Config config) throws IOException {
        Properties p = load(config);
        p.remove("consent"); p.remove("pin");
        if (p.containsKey("token") && p.containsKey("deviceId")) {
            p.setProperty("revokeToken",p.getProperty("token"));
            p.setProperty("revokeId",p.getProperty("deviceId"));
        }
        p.remove("token"); p.remove("deviceId");
        store(config,p);
    }
    /** Start or refresh the scheduler; no network activity occurs without explicit consent. */
    public static synchronized void runSoon(Config config, String version) {
        start(config, version);
        scheduler.execute(() -> {
            try { tick(config,version); }
            catch (Exception e) { System.err.println("[diagnostics] " + e.getMessage()); }
        });
    }

    private static final java.util.concurrent.atomic.AtomicBoolean manualPending =
            new java.util.concurrent.atomic.AtomicBoolean();

    /** Queue a manual upload without blocking the UI or changing consent. */
    public static synchronized boolean uploadNow(Config config, String version) {
        if (!enabled(config) || !manualPending.compareAndSet(false, true)) return false;
        start(config, version);
        scheduler.execute(() -> {
            try {
                boolean needsEnrollment = !load(config).containsKey("token");
                tick(config, version);
                if (needsEnrollment && enabled(config) && load(config).containsKey("token"))
                    tick(config, version);
            } catch (Exception e) {
                System.err.println("[diagnostics] manual upload failed: " + e.getMessage());
            } finally {
                manualPending.set(false);
            }
        });
        return true;
    }

    public static synchronized void start(Config config, String version) {
        if (scheduler != null) return;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "wiiuu-opt-in-diagnostics");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(() -> {
            try { tick(config,version); }
            catch (Exception e) { System.err.println("[diagnostics] " + e.getMessage()); }
        }, 1, 15, TimeUnit.MINUTES);
    }
    static void tick(Config config, String version) throws Exception {
        String base = config.get("diagnostics.url", DEFAULT_URL).replaceFirst("/+$", "");
        URI uri = URI.create(base);
        if (!"https".equals(uri.getScheme()) && !("http".equals(uri.getScheme())
                && ("127.0.0.1".equals(uri.getHost()) || "localhost".equals(uri.getHost()))))
            throw new IOException("Diagnostic upload requires HTTPS");
        Properties p;
        synchronized (Diagnostics.class) { p=load(config); }
        if (p.containsKey("revokeId")) {
            HttpResponse<String> r = request(base + "/api/diagnostics/device", "DELETE",
                    "", p.getProperty("revokeId"), p.getProperty("revokeToken"));
            if (r.statusCode() / 100 == 2 || r.statusCode() == 401) {
                synchronized (Diagnostics.class) {
                    Properties latest = load(config);
                    if (p.getProperty("revokeId").equals(latest.getProperty("revokeId"))) {
                        latest.remove("revokeId"); latest.remove("revokeToken"); store(config,latest);
                    }
                }
            }
            return;
        }
        if (!"true".equals(p.getProperty("consent"))) return;
        if (!p.containsKey("token")) {
            String payload = "{\"pin\":\"" + p.getProperty("pin") + "\",\"consent\":true,\"consentVersion\":1}";
            HttpResponse<String> response=request(base + "/api/diagnostics/enroll","POST",payload,null,null);
            if (response.statusCode()!=201) throw new IOException("Enrollment rejected: HTTP " + response.statusCode());
            Map<String,Object> data=MiniJson.object(MiniJson.parse(response.body()));
            String id=MiniJson.string(data.get("deviceId")), token=MiniJson.string(data.get("token"));
            if (id==null||token==null) throw new IOException("Incomplete enrollment response");
            synchronized (Diagnostics.class) {
                Properties current=load(config);
                if (!"true".equals(current.getProperty("consent"))) {
                    // Consent was withdrawn while the request was in progress.
                    current.setProperty("revokeId", id); current.setProperty("revokeToken", token);
                } else if (p.getProperty("pin").equals(current.getProperty("pin"))) {
                    current.setProperty("deviceId",id); current.setProperty("token",token);
                } else {
                    current.setProperty("revokeId",id); current.setProperty("revokeToken",token);
                }
                store(config,current);
            }
            return; // Send first report only on the next scheduled pass.
        }
        List<String> entries=recentLogs(config);
        if (entries.isEmpty()) return;
        synchronized (Diagnostics.class) {
            Properties latest=load(config);
            if (!"true".equals(latest.getProperty("consent"))
                    || !p.getProperty("token").equals(latest.getProperty("token"))) return;
        }
        StringBuilder payload=new StringBuilder("{\"version\":\"").append(jsonEscape(version)).append("\",\"entries\":[");
        for (int i=0;i<entries.size();i++) {
            if (i>0) payload.append(',');
            payload.append('"').append(jsonEscape(entries.get(i))).append('"');
        }
        payload.append("]}");
        HttpResponse<String> response=request(base + "/api/diagnostics/logs","POST",payload.toString(),
                p.getProperty("deviceId"),p.getProperty("token"));
        if (response.statusCode()!=201 && response.statusCode()!=429)
            throw new IOException("Upload rejected: HTTP "+response.statusCode());
    }
    private static HttpResponse<String> request(String url,String method,String body,String id,String token)
            throws IOException, InterruptedException {
        HttpRequest.Builder request=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
                .header("Content-Type","application/json");
        if(id!=null) request.header("X-Device-Id",id);
        if(token!=null) request.header("Authorization","Bearer "+token);
        request.method(method,body.isEmpty()?HttpRequest.BodyPublishers.noBody()
                :HttpRequest.BodyPublishers.ofString(body,StandardCharsets.UTF_8));
        return HTTP.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    private static List<String> recentLogs(Config config) throws IOException {
        Path dir=config.logDir();
        if (!Files.isDirectory(dir)) return List.of();
        List<Path> files;
        try(Stream<Path> stream=Files.list(dir)){
            files=stream.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".log"))
                    .sorted(Comparator.comparingLong(Diagnostics::mtime).reversed()).limit(3).toList();
        }
        List<String> lines=new ArrayList<>(); int bytes=0;
        for(Path p:files){
            long size=Files.size(p);
            if(size>2_000_000) continue; // Refuse unexpected large logs, rather than reading them.
            List<String> all=Files.readAllLines(p,StandardCharsets.UTF_8);
            int from=Math.max(0,all.size()-45);
            for(int i=from;i<all.size() && lines.size()<120;i++){
                String clean=redact(all.get(i));
                if(clean.length()>800)clean=clean.substring(0,800);
                bytes+=clean.length()*2;
                if(bytes>35_000)return lines;
                lines.add(p.getFileName()+": "+clean);
            }
        }
        return lines;
    }
    private static long mtime(Path p) {
        try {return Files.getLastModifiedTime(p).toMillis();}catch(IOException e){return 0;}
    }
    static String redact(String input) {
        return input.replaceAll("(?i)(token|api[_-]?key|password|secret|cookie|authorization)\\s*[:=]\\s*[^\\s,;]+","$1=[REDACTED]")
                .replaceAll("https?://[^\\s]+","[URL]")
                .replaceAll("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b","[IP]")
                .replaceAll("(?:/home/|/Users/|C:\\\\Users\\\\)[^/\\\\\\s]+","[HOME]");
    }
    static String jsonEscape(String s) {
        StringBuilder b=new StringBuilder();
        for(char c:s.toCharArray()){
            if(c=='"'||c=='\\')b.append('\\').append(c);
            else if(c=='\n')b.append("\\n");
            else if(c=='\r')b.append("\\r");
            else if(c=='\t')b.append("\\t");
            else if(c<32)b.append(' ');
            else b.append(c);
        }
        return b.toString();
    }
}
