package wiiuu.core;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Public archive browsing; no upload credential is ever needed or stored in the app. */
public final class ReleaseArchive {
    public static final String DEFAULT_URL = "https://wii-uu-archive.stoppedwumm.net";
    public record Build(String version, String releaseVersion, String channel, String commit, String publishedAt,
                        long bytes, Updater.Release release, URI notesUrl) {
        public boolean installed(String current) {
            return Version.parse(version).compareTo(Version.parse(current)) == 0;
        }
    }
    public record Page(List<Build> builds, int total, int offset) {
        public Page { builds = List.copyOf(builds); }
        public boolean hasMore() { return offset + builds.size() < total; }
    }
    private final URI base;
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10)).build();

    public ReleaseArchive(Config config) { this(config.get("archive.url", DEFAULT_URL)); }
    public ReleaseArchive(String url) {
        base = webUri(url.endsWith("/") ? url : url + "/");
        if (base.getQuery() != null || base.getFragment() != null) throw new IllegalArgumentException("Archive URL must not contain a query or fragment");
    }

    /** channel is all, stable or beta; offsets are scoped to that filter. */
    public Page list(String channel, int offset, int limit) throws IOException, InterruptedException {
        if (!List.of("all", "stable", "beta").contains(channel) || offset < 0 || limit < 1 || limit > 100)
            throw new IllegalArgumentException("Invalid archive filter or pagination");
        URI url = base.resolve("api/releases?limit=" + limit + "&offset=" + offset + (channel.equals("all") ? "" : "&channel=" + channel));
        try {
            Map<String, Object> data = MiniJson.object(MiniJson.parse(get(url, 2 * 1024 * 1024)));
            int total = integer(data.get("total"));
            if (!(data.get("releases") instanceof List<?>)) throw new IllegalArgumentException("Missing release list");
            List<Build> builds = new ArrayList<>();
            for (Object item : MiniJson.array(data.get("releases"))) {
                Map<String, Object> entry = MiniJson.object(item);
                String version = required(entry, "version");
                Version parsed = Version.parse(version);
                String key = required(entry, "releaseVersion");
                if (!version.split("\\+", 2)[0].equals(key)) throw new IllegalArgumentException("Archive version mismatch");
                String kind = required(entry, "channel");
                if (!kind.equals(parsed.prerelease() ? "beta" : "stable") || !channel.equals("all") && !channel.equals(kind))
                    throw new IllegalArgumentException("Archive channel mismatch");
                String sha = required(entry, "sha256");
                if (!sha.matches("[0-9a-fA-F]{64}")) throw new IllegalArgumentException("Missing archive checksum");
                URI zip = webUri(required(entry, "zip"));
                Map<String, Object> assets = MiniJson.object(entry.get("assets"));
                Map<String, Object> zipAsset = MiniJson.object(assets.get("WII-UU-" + key + ".zip"));
                Double size = MiniJson.number(zipAsset.get("size"));
                if (size != null && (!Double.isFinite(size) || size < 0 || size != Math.rint(size) || size > Long.MAX_VALUE))
                    throw new IllegalArgumentException("Invalid archive asset size");
                long bytes = size == null ? 0 : size.longValue();
                String notes = MiniJson.string(MiniJson.object(assets.get("CHANGELOG.md")).get("url"));
                builds.add(new Build(version, key, kind, text(entry, "commit"), text(entry, "publishedAt"), bytes,
                        new Updater.Release(version, zip.toString(), sha), notes == null ? null : webUri(notes)));
            }
            if (builds.size() > limit || (long) offset + builds.size() > total) throw new IllegalArgumentException("Invalid archive page size");
            return new Page(builds, total, offset);
        } catch (IllegalArgumentException e) { throw new IOException("Invalid archive response: " + e.getMessage(), e); }
    }

    /** Only notes belonging to the chosen build/cycle, not every release in its bundled changelog. */
    public List<Changelog.Entry> notes(Build build) throws IOException, InterruptedException {
        if (build.notesUrl() == null) return List.of();
        try {
            return Changelog.parse(get(build.notesUrl(), 2 * 1024 * 1024)).entries().stream()
                    .filter(e -> e.version().equals(build.releaseVersion()) || e.version().equals(Version.parse(build.version()).base()))
                    .toList();
        } catch (IllegalArgumentException e) { throw new IOException("Invalid archive release notes", e); }
    }

    private String get(URI url, int maxBytes) throws IOException, InterruptedException {
        HttpResponse<byte[]> response = http.send(HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(20))
                .header("User-Agent", "WII-UU-Archive-Browser").build(), info -> new LimitedBody(maxBytes));
        if (response.statusCode() != 200) throw new IOException("Archive returned HTTP " + response.statusCode());
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    /** Bound memory while keeping HttpClient's request timeout active until the entire body arrives. */
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> body = HttpResponse.BodySubscribers.ofByteArray();
        private final int limit;
        private long received;
        private Flow.Subscription subscription;
        LimitedBody(int limit) { this.limit = limit; }
        @Override public CompletionStage<byte[]> getBody() { return body.getBody(); }
        @Override public void onSubscribe(Flow.Subscription subscription) { this.subscription = subscription; body.onSubscribe(subscription); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) received += buffer.remaining();
            if (received > limit) { subscription.cancel(); body.onError(new IOException("Archive response is too large")); }
            else body.onNext(buffers);
        }
        @Override public void onError(Throwable error) { body.onError(error); }
        @Override public void onComplete() { body.onComplete(); }
    }
    private static String required(Map<String, Object> data, String name) {
        String value = MiniJson.string(data.get(name));
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + name);
        return value;
    }
    private static String text(Map<String, Object> data, String name) { String s = MiniJson.string(data.get(name)); return s == null ? "" : s; }
    private static int integer(Object value) {
        Double number = MiniJson.number(value);
        if (number == null || number < 0 || number > Integer.MAX_VALUE || number != Math.rint(number)) throw new IllegalArgumentException("Invalid total");
        return number.intValue();
    }
    private static URI webUri(String url) {
        URI uri = URI.create(url);
        if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || uri.getHost() == null || uri.getUserInfo() != null)
            throw new IllegalArgumentException("Invalid archive URL");
        return uri;
    }
}
