package wiiuu.net;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import com.sun.net.httpserver.HttpExchange;

import wiiuu.core.Config;
import wiiuu.core.MiniJson;
import wiiuu.input.PadButton;

/**
 * Other WII-UUs on the same network: finding them, linking with one (once, with the pairing code
 * its TV shows), and then
 * <ul>
 * <li><b>sending controllers</b> plugged in here to it: each one joins there as a player of its
 * own, like a phone (so a Raspberry Pi's USB pad plays Choccy Milk on a Mac);</li>
 * <li><b>syncing saves</b> (~/.wiiuu/saves: Choccy Milk's game saves and save states) both ways:
 * the newer copy of a file wins, the one it replaces is kept in saves/.backup. Never while a game
 * runs on either side.</li>
 * </ul>
 *
 * <p>WII-UUs announce themselves on UDP port 26780 (broadcast, every few seconds). Linking gives
 * each side a random token for the other; everything after that (saves, controllers) needs it.
 * The pairing code is only sent once, to link, and a few wrong codes in a row lock linking for a
 * minute.
 */
public final class Peers {
    public static final int DISCOVERY_PORT = 26780;
    private static final String MAGIC = "WIIUU1";
    private static final long SEEN_FOR_MS = 12_000;
    private static final long SYNC_EVERY_MS = 60_000;
    private static final long MAX_SAVE = 128L << 20;

    /** A WII-UU heard on the network. */
    public record Seen(String id, String name, String host, int port, String version, long at) {}

    /** A WII-UU linked with this one. */
    public static final class Link {
        public final String id;
        volatile String name, host;
        volatile int port;
        /** token this side sends (out) and expects from it (in) */
        final String out, in;
        volatile boolean forward, sync;
        volatile String state = "";
        volatile long lastSync, contact;

        Link(String id, String name, String host, int port, String out, String in, boolean forward, boolean sync) {
            this.id = id;
            this.name = name;
            this.host = host;
            this.port = port;
            this.out = out;
            this.in = in;
            this.forward = forward;
            this.sync = sync;
        }

        public String name() {
            return name;
        }

        public String host() {
            return host;
        }

        public boolean forward() {
            return forward;
        }

        public boolean sync() {
            return sync;
        }

        /** What happened last: "synced 3 saves", "can't reach it"... */
        public String state() {
            return state;
        }

        String base() {
            return "http://" + (host.contains(":") ? "[" + host + "]" : host) + ":" + port;
        }
    }

    /** What this WII-UU's GamePad server does for the peers. */
    interface Host {
        boolean codeOk(String code);

        /** A player for a controller another WII-UU sends; its client id. */
        String remotePad(String label);

        /** Whether a game runs here now (saves are left alone then). */
        boolean playing();

        /** Sending controllers was turned on or off. */
        void forwardingChanged();
    }

    private final Config config;
    private final Host host;
    private final int port;
    private final String version;
    private final String myId;
    private final Path saves;
    private final Map<String, Seen> seen = new ConcurrentHashMap<>();
    private final Map<String, Link> links = new ConcurrentHashMap<>();
    private final Map<String, RemotePad> remotePads = new ConcurrentHashMap<>();
    private final Map<Path, String[]> hashes = new ConcurrentHashMap<>();     // file -> {size, mtime, md5}
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final SecureRandom random = new SecureRandom();
    private ScheduledExecutorService timer;
    private DatagramSocket socket;
    private volatile int wrongCodes;
    private volatile long lockedUntil;
    private volatile boolean wasPlaying;

    Peers(Config config, Host host, int port, String version) {
        this.config = config;
        this.host = host;
        this.port = port;
        this.version = version;
        this.saves = config.home().resolve("saves");
        String id = config.get("peer.id", "");
        if (id.isBlank()) {
            id = token().substring(0, 16);
            config.set("peer.id", id);
            config.save();
        }
        this.myId = id;
        load();
    }

    /** This WII-UU's name for the others (peer.name, or the computer's name). */
    public String myName() {
        String n = config.get("peer.name", "").trim();
        if (!n.isEmpty()) return n;
        try {
            n = InetAddress.getLocalHost().getHostName();
            int dot = n.indexOf('.');
            if (dot > 0) n = n.substring(0, dot);
            if (!n.isBlank() && !n.equals("localhost")) return n;
        } catch (IOException ignored) {
            // no name
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("mac") ? "Mac" : os.contains("win") ? "Windows PC" : "Linux PC";
    }

    // ---- finding each other ---------------------------------------------------------------------

    void start() {
        timer = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "peers");
            t.setDaemon(true);
            return t;
        });
        try {
            socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            socket.setBroadcast(true);
            socket.bind(new InetSocketAddress(DISCOVERY_PORT));
            Thread listen = new Thread(this::listen, "peers-listen");
            listen.setDaemon(true);
            listen.start();
            timer.scheduleWithFixedDelay(this::announce, 0, 3, TimeUnit.SECONDS);
        } catch (IOException e) {
            System.err.println("[peers] can't use udp " + DISCOVERY_PORT + " (" + e.getMessage() + "): other WII-UUs can still be linked by address");
        }
        timer.scheduleWithFixedDelay(this::keepPadsAlive, 1500, 1500, TimeUnit.MILLISECONDS);
        timer.scheduleWithFixedDelay(this::syncTick, 10, 10, TimeUnit.SECONDS);
        timer.scheduleWithFixedDelay(this::ping, 2, 30, TimeUnit.SECONDS);
    }

    void stop() {
        if (timer != null) timer.shutdownNow();
        if (socket != null) socket.close();
        for (RemotePad p : remotePads.values()) p.close();
    }

    private void announce() {
        byte[] msg = String.join("\t", MAGIC, myId, Integer.toString(port), version, myName().replace('\t', ' '))
                .getBytes(StandardCharsets.UTF_8);
        List<InetAddress> to = new ArrayList<>();
        try {
            to.add(InetAddress.getByName("255.255.255.255"));
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress a : ni.getInterfaceAddresses()) if (a.getBroadcast() != null) to.add(a.getBroadcast());
            }
        } catch (IOException ignored) {
            // the general broadcast only
        }
        for (InetAddress a : to) {
            try {
                socket.send(new DatagramPacket(msg, msg.length, a, DISCOVERY_PORT));
            } catch (IOException ignored) {
                // that network doesn't take broadcasts
            }
        }
    }

    private void listen() {
        byte[] buf = new byte[512];
        while (!socket.isClosed()) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                socket.receive(p);
                heard(new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8), p.getAddress().getHostAddress());
            } catch (IOException e) {
                if (socket.isClosed()) return;
            }
        }
    }

    /** One announcement from {@code from}. */
    void heard(String msg, String from) {
        String[] f = msg.split("\t", 5);
        if (f.length < 5 || !f[0].equals(MAGIC) || f[1].equals(myId)) return;
        try {
            // only for finding WII-UUs to link with: anyone can send these, so a linked one's address
            // is never taken from them (it comes with its own requests, which carry its token)
            seen.put(f[1], new Seen(f[1], f[4].strip(), from, Integer.parseInt(f[2]), f[3], System.currentTimeMillis()));
        } catch (NumberFormatException ignored) {
            // not ours
        }
    }

    /** WII-UUs heard lately that aren't linked yet. */
    public List<Seen> nearby() {
        long now = System.currentTimeMillis();
        List<Seen> out = new ArrayList<>();
        for (Seen s : seen.values()) if (now - s.at() < SEEN_FOR_MS && !links.containsKey(s.id())) out.add(s);
        out.sort(java.util.Comparator.comparing(Seen::name));
        return out;
    }

    public List<Link> links() {
        List<Link> out = new ArrayList<>(links.values());
        out.sort(java.util.Comparator.comparing(Link::name));
        return out;
    }

    /** Whether a linked WII-UU answered or asked something lately (it's on). */
    public boolean online(Link l) {
        return System.currentTimeMillis() - l.contact < 45_000;
    }

    /** Every 30 s: tells each linked WII-UU where this one is (so it follows a new address), and checks it's on. */
    private void ping() {
        for (Link l : links.values()) {
            try {
                HttpResponse<String> r = get(l.base() + "/api/peer/ping", l);
                if (r.statusCode() == 200) l.contact = System.currentTimeMillis();
                else if (r.statusCode() == 401) l.state = "it doesn't know this WII-UU any more: link again";
            } catch (IOException e) {
                // off, or moved: it tells this one its new address when it pings
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    // ---- linking ----------------------------------------------------------------------------------

    /** Links with the WII-UU at host:port, with the pairing code its TV shows. Null when done, else why not. */
    public String link(String address, int defaultPort, String code) {
        String h = address.trim();
        int p = defaultPort;
        int colon = h.lastIndexOf(':');
        if (colon > 0 && h.indexOf(':') == colon) {
            try {
                p = Integer.parseInt(h.substring(colon + 1));
                h = h.substring(0, colon);
            } catch (NumberFormatException e) {
                return "That isn't an address (like 192.168.1.20 or 192.168.1.20:8080)";
            }
        }
        String back = token();
        try {
            HttpResponse<String> r = post("http://" + h + ":" + p + "/api/peer/link", null, form(
                    "code", code.trim(), "id", myId, "name", myName(), "port", Integer.toString(port), "back", back,
                    "version", version));
            Map<String, Object> j = MiniJson.object(MiniJson.parse(r.body()));
            if (r.statusCode() != 200 || j == null) return j != null && j.get("error") != null ? MiniJson.string(j.get("error")) : "It said no (" + r.statusCode() + ")";
            String id = MiniJson.string(j.get("id")), token = MiniJson.string(j.get("token"));
            if (id == null || token == null || id.equals(myId)) return "That's this WII-UU";
            Link l = new Link(id, str(j.get("name"), h), h, p, token, back, false, true);
            l.contact = System.currentTimeMillis();
            links.put(id, l);
            save();
            System.out.println("[peers] linked with " + l.name + " (" + h + ")");
            syncSoon(l);
            return null;
        } catch (IOException e) {
            return "Can't reach a WII-UU at " + h + ":" + p;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Stopped";
        } catch (RuntimeException e) {
            return "That isn't a WII-UU (or an older one)";
        }
    }

    /** Forgets a linked WII-UU (and tells it, if it can be reached). */
    public void unlink(Link l) {
        if (links.remove(l.id) == null) return;
        if (l.forward) host.forwardingChanged();
        save();
        Thread t = new Thread(() -> {
            try {
                post(l.base() + "/api/peer/unlink", l, "");
            } catch (Exception ignored) {
                // it forgets the link when it next can't use it
            }
        }, "peers-unlink");
        t.setDaemon(true);
        t.start();
    }

    /** Send the controllers plugged in here to this one (only one at a time), or keep them here. */
    public void setForward(Link l, boolean on) {
        for (Link o : links.values()) if (o != l && on) o.forward = false;
        l.forward = on;
        save();
        host.forwardingChanged();
    }

    public void setSync(Link l, boolean on) {
        l.sync = on;
        save();
        if (on) syncSoon(l);
    }

    /** The WII-UU controllers are sent to, or null. */
    public Link forwardTarget() {
        for (Link l : links.values()) if (l.forward) return l;
        return null;
    }

    // ---- what the other WII-UUs ask --------------------------------------------------------------

    /** /api/peer/...: true when it was one of these. */
    boolean handle(HttpExchange ex, String path) throws IOException {
        if (!path.startsWith("/api/peer/")) return false;
        String from = ex.getRemoteAddress().getAddress().getHostAddress();
        if (path.equals("/api/peer/link")) {
            acceptLink(ex, from);
            return true;
        }
        Link l = authorized(ex.getRequestHeaders().getFirst("X-Peer"));
        if (l == null) {
            reply(ex, 401, new Json().obj().kv("error", "not linked").endObj());
            return true;
        }
        l.contact = System.currentTimeMillis();
        int theirPort = 0;
        try {
            theirPort = Integer.parseInt(String.valueOf(ex.getRequestHeaders().getFirst("X-Peer-Port")));
        } catch (NumberFormatException ignored) {
            // an old one: the port stays
        }
        if (!from.equals(l.host) || theirPort > 0 && theirPort <= 65535 && theirPort != l.port) {
            l.host = from;                                  // it moved (a new address from the router)
            if (theirPort > 0 && theirPort <= 65535) l.port = theirPort;
            save();
        }
        Map<String, String> q = query(ex);
        switch (path) {
            case "/api/peer/ping" -> reply(ex, 200, new Json().obj().kv("ok", true).endObj());
            case "/api/peer/unlink" -> {
                links.remove(l.id);
                if (l.forward) host.forwardingChanged();
                save();
                System.out.println("[peers] " + l.name + " unlinked");
                reply(ex, 200, new Json().obj().kv("ok", true).endObj());
            }
            case "/api/peer/pad" -> {
                String label = l.name + ": " + form(body(ex, 4096)).getOrDefault("label", "Controller");
                reply(ex, 200, new Json().obj().kv("id", host.remotePad(label)).endObj());
            }
            case "/api/peer/saves" -> {
                Json j = new Json().obj().kv("playing", host.playing()).key("files").arr();
                for (Map.Entry<String, String[]> e : localSaves().entrySet()) {
                    j.obj().kv("f", e.getKey()).kv("s", Long.parseLong(e.getValue()[0])).kv("t", Long.parseLong(e.getValue()[1]))
                            .kv("h", e.getValue()[2]).endObj();
                }
                reply(ex, 200, j.endArr().endObj());
            }
            case "/api/peer/save" -> {
                Path f = savePath(q.get("f"));
                if (f == null) {
                    reply(ex, 400, new Json().obj().kv("error", "bad file").endObj());
                } else if ("POST".equals(ex.getRequestMethod())) {
                    receiveSave(ex, l, f, q);
                } else if (!Files.isRegularFile(f)) {
                    reply(ex, 404, new Json().obj().kv("error", "no such save").endObj());
                } else {
                    byte[] b = Files.readAllBytes(f);
                    ex.getResponseHeaders().set("Content-Type", "application/octet-stream");
                    ex.sendResponseHeaders(200, b.length == 0 ? -1 : b.length);
                    try (OutputStream out = ex.getResponseBody()) {
                        out.write(b);
                    }
                }
            }
            default -> reply(ex, 404, new Json().obj().kv("error", "unknown").endObj());
        }
        return true;
    }

    private void acceptLink(HttpExchange ex, String from) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) {
            reply(ex, 405, new Json().obj().kv("error", "POST only").endObj());
            return;
        }
        if (System.currentTimeMillis() < lockedUntil) {
            reply(ex, 429, new Json().obj().kv("error", "Too many wrong codes: try again in a minute").endObj());
            return;
        }
        Map<String, String> f = form(body(ex, 4096));
        if (!host.codeOk(f.getOrDefault("code", ""))) {
            if (++wrongCodes >= 5) {
                wrongCodes = 0;
                lockedUntil = System.currentTimeMillis() + 60_000;
            }
            reply(ex, 403, new Json().obj().kv("error", "Wrong code: it's the pairing code on that WII-UU's TV (Settings > GamePad & players)").endObj());
            return;
        }
        wrongCodes = 0;
        String id = f.getOrDefault("id", ""), back = f.getOrDefault("back", "");
        int p;
        try {
            p = Integer.parseInt(f.getOrDefault("port", "0"));
        } catch (NumberFormatException e) {
            p = 0;
        }
        if (!id.matches("[A-Za-z0-9_-]{4,64}") || back.length() < 16 || p <= 0 || p > 65535 || id.equals(myId)) {
            reply(ex, 400, new Json().obj().kv("error", "bad request").endObj());
            return;
        }
        String mine = token();
        String name = f.getOrDefault("name", from).replaceAll("[\\p{Cntrl}]", "").strip();
        if (name.length() > 40) name = name.substring(0, 40);
        Link old = links.get(id);
        Link l = new Link(id, name.isEmpty() ? from : name, from, p, back, mine, false, old == null || old.sync);
        links.put(id, l);
        save();
        System.out.println("[peers] " + l.name + " (" + from + ") linked with this WII-UU");
        reply(ex, 200, new Json().obj().kv("id", myId).kv("name", myName()).kv("token", mine).endObj());
    }

    private Link authorized(String token) {
        if (token == null || token.isEmpty()) return null;
        byte[] t = token.getBytes(StandardCharsets.UTF_8);
        for (Link l : links.values()) {
            if (MessageDigest.isEqual(t, l.in.getBytes(StandardCharsets.UTF_8))) return l;
        }
        return null;
    }

    // ---- controllers sent to another WII-UU --------------------------------------------------------

    /** A controller here, playing on a linked WII-UU as a player there. */
    private final class RemotePad {
        final String label;
        final Link link;
        final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "remote-pad");
            t.setDaemon(true);
            return t;
        });
        final Set<PadButton> held = EnumSet.noneOf(PadButton.class);
        volatile String remoteId;
        volatile boolean closed;

        RemotePad(String label, Link link) {
            this.label = label;
            this.link = link;
        }

        /** A line as a phone sends it ("b A 1", "s 0 0.5 0"). */
        void send(String line) {
            String[] p = line.split(" ");
            if (p.length == 3 && p[0].equals("b")) {
                PadButton b = PadButton.parse(p[1]);
                synchronized (held) {
                    if (b != null && p[2].equals("1")) held.add(b);
                    else if (b != null) held.remove(b);
                }
            }
            exec.execute(() -> deliver(line, true));
        }

        private void deliver(String line, boolean retry) {
            try {
                if (remoteId == null) register();
                HttpResponse<String> r = post(link.base() + "/api/input", null, line, "X-Pad", remoteId);
                if (r.statusCode() == 401 && retry) {            // it restarted and forgot the player
                    remoteId = null;
                    deliver(line, false);
                    return;
                }
                link.state = "controllers playing there";
            } catch (IOException e) {
                remoteId = null;
                link.state = "can't reach it";
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private void register() throws IOException, InterruptedException {
            HttpResponse<String> r = post(link.base() + "/api/peer/pad", link, form("label", label));
            Map<String, Object> j = MiniJson.object(MiniJson.parse(r.body()));
            String id = j == null ? null : MiniJson.string(j.get("id"));
            if (r.statusCode() != 200 || id == null) throw new IOException("no player (" + r.statusCode() + ")");
            remoteId = id;
            System.out.println("[peers] " + label + " plays on " + link.name);
        }

        void keepAlive() {
            if (closed || remoteId == null) return;
            exec.execute(() -> {
                try {
                    HttpResponse<String> r = get(link.base() + "/api/status", null, "X-Pad", remoteId);
                    if (r.statusCode() == 401) remoteId = null;
                } catch (IOException e) {
                    remoteId = null;
                    link.state = "can't reach it";
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        /** Lets go of everything there, then stops. */
        void close() {
            closed = true;
            List<PadButton> up;
            synchronized (held) {
                up = new ArrayList<>(held);
                held.clear();
            }
            if (remoteId != null) {
                for (PadButton b : up) exec.execute(() -> deliver("b " + b.name() + " 0", false));
                exec.execute(() -> deliver("s 0 0 0", false));
                exec.execute(() -> deliver("s 1 0 0", false));
            }
            exec.shutdown();
        }
    }

    /** A controller here starts playing on the forward target (or does nothing when there's none). */
    void padConnected(String localId, String name) {
        Link l = forwardTarget();
        if (l == null) return;
        RemotePad pad = new RemotePad(name, l);                    // the other side puts this WII-UU's name before it
        RemotePad old = remotePads.put(localId, pad);
        if (old != null) old.close();
        pad.exec.execute(() -> {
            try {
                pad.register();
            } catch (Exception e) {
                l.state = "can't reach it";
            }
        });
    }

    void padDisconnected(String localId) {
        RemotePad p = remotePads.remove(localId);
        if (p != null) p.close();
    }

    /** Input from a controller here, for the WII-UU it plays on. */
    void padInput(String localId, String line) {
        RemotePad p = remotePads.get(localId);
        if (p != null) p.send(line);
    }

    boolean sending(String localId) {
        return remotePads.containsKey(localId);
    }

    private void keepPadsAlive() {
        for (RemotePad p : remotePads.values()) p.keepAlive();
    }

    // ---- saves ------------------------------------------------------------------------------------

    /** Syncs with this one now (in the background). */
    public void syncSoon(Link l) {
        if (timer == null) return;
        timer.execute(() -> sync(l));
    }

    private void syncTick() {
        boolean playing = host.playing();
        boolean justStopped = wasPlaying && !playing;
        wasPlaying = playing;
        if (playing) return;
        long now = System.currentTimeMillis();
        for (Link l : links.values()) {
            if (l.sync && (justStopped || now - l.lastSync > SYNC_EVERY_MS)) sync(l);
        }
    }

    /** Both ways: files only one side has are copied over, for the rest the newer copy wins. */
    synchronized void sync(Link l) {
        if (!l.sync || host.playing()) return;
        l.lastSync = System.currentTimeMillis();
        try {
            HttpResponse<String> r = get(l.base() + "/api/peer/saves", l);
            if (r.statusCode() == 401) {
                l.state = "it doesn't know this WII-UU any more: link again";
                return;
            }
            Map<String, Object> j = MiniJson.object(MiniJson.parse(r.body()));
            if (r.statusCode() != 200 || j == null) {
                l.state = "sync failed (" + r.statusCode() + ")";
                return;
            }
            if (Boolean.TRUE.equals(j.get("playing"))) {
                l.state = "a game runs there: saves sync when it ends";
                return;
            }
            Map<String, String[]> theirs = new HashMap<>();
            for (Object o : MiniJson.array(j.get("files"))) {
                Map<String, Object> e = MiniJson.object(o);
                theirs.put(MiniJson.string(e.get("f")), new String[]{str(e.get("s"), "0"), str(e.get("t"), "0"), MiniJson.string(e.get("h"))});
            }
            Map<String, String[]> mine = localSaves();
            int got = 0, sent = 0;
            Set<String> all = new java.util.TreeSet<>(mine.keySet());
            all.addAll(theirs.keySet());
            for (String f : all) {
                if (host.playing()) break;
                String[] a = mine.get(f), b = theirs.get(f);
                if (a != null && b != null && a[2].equalsIgnoreCase(b[2])) continue;
                long ta = a == null ? -1 : Long.parseLong(a[1]), tb = b == null ? -1 : Long.parseLong(num(b[1]));
                if (b != null && (a == null || tb > ta + 2000)) {
                    if (download(l, f, tb, b[2])) got++;
                } else if (a != null && (b == null || ta > tb + 2000)) {
                    if (upload(l, f, ta, a[2])) sent++;
                }
            }
            l.state = got + sent == 0 ? "saves in sync" : "synced: " + got + " from it, " + sent + " to it";
            if (got + sent > 0) System.out.println("[peers] saves with " + l.name + ": " + l.state);
        } catch (IOException e) {
            l.state = "can't reach it";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            l.state = "sync failed: " + e.getMessage();
        }
    }

    private boolean download(Link l, String rel, long time, String md5) throws IOException, InterruptedException {
        Path f = savePath(rel);
        if (f == null) return false;
        HttpResponse<byte[]> r = http.send(request(l.base() + "/api/peer/save?f=" + enc(rel), l).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        if (r.statusCode() != 200 || r.body().length > MAX_SAVE || !md5(r.body()).equalsIgnoreCase(md5)) return false;
        write(f, r.body(), time, l.name);
        return true;
    }

    private boolean upload(Link l, String rel, long time, String md5) throws IOException, InterruptedException {
        Path f = savePath(rel);
        if (f == null || !Files.isRegularFile(f) || Files.size(f) > MAX_SAVE) return false;
        byte[] b = Files.readAllBytes(f);
        if (!md5(b).equalsIgnoreCase(md5)) return false;                // it changed meanwhile: next time
        HttpResponse<String> r = http.send(request(l.base() + "/api/peer/save?f=" + enc(rel) + "&t=" + time + "&h=" + md5, l)
                .POST(HttpRequest.BodyPublishers.ofByteArray(b)).build(), HttpResponse.BodyHandlers.ofString());
        return r.statusCode() == 200;
    }

    private void receiveSave(HttpExchange ex, Link l, Path f, Map<String, String> q) throws IOException {
        if (host.playing()) {
            reply(ex, 409, new Json().obj().kv("error", "a game runs here").endObj());
            return;
        }
        byte[] b;
        try (InputStream in = ex.getRequestBody()) {
            b = in.readNBytes((int) MAX_SAVE + 1);
        }
        long t;
        try {
            t = Long.parseLong(q.getOrDefault("t", "0"));
        } catch (NumberFormatException e) {
            t = 0;
        }
        if (b.length > MAX_SAVE || !md5(b).equalsIgnoreCase(q.getOrDefault("h", "")) || t <= 0) {
            reply(ex, 400, new Json().obj().kv("error", "bad save").endObj());
            return;
        }
        write(f, b, t, l.name);
        reply(ex, 200, new Json().obj().kv("ok", true).endObj());
    }

    /** Writes a save that came from another WII-UU; the copy it replaces goes to saves/.backup. */
    private synchronized void write(Path f, byte[] b, long time, String from) throws IOException {
        Files.createDirectories(f.getParent());
        if (Files.isRegularFile(f)) {
            String stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            Path backup = saves.resolve(".backup").resolve(saves.relativize(f).toString() + "." + stamp);
            Files.createDirectories(backup.getParent());
            Files.copy(f, backup, StandardCopyOption.REPLACE_EXISTING);
        }
        Path tmp = f.resolveSibling(f.getFileName() + ".sync.tmp");
        Files.write(tmp, b);
        Files.setLastModifiedTime(tmp, FileTime.fromMillis(time));
        Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        Files.setLastModifiedTime(f, FileTime.fromMillis(time));
        hashes.remove(f);
        System.out.println("[peers] save " + saves.relativize(f) + " from " + from);
    }

    /** The saves here: path (with /) -> {size, modified ms, md5}. */
    Map<String, String[]> localSaves() {
        Map<String, String[]> out = new LinkedHashMap<>();
        if (!Files.isDirectory(saves)) return out;
        try (Stream<Path> all = Files.walk(saves, 4)) {
            for (Path f : (Iterable<Path>) all::iterator) {
                String rel = saves.relativize(f).toString().replace('\\', '/');
                if (!Files.isRegularFile(f, java.nio.file.LinkOption.NOFOLLOW_LINKS) || savePath(rel) == null) continue;
                long size = Files.size(f), t = Files.getLastModifiedTime(f).toMillis();
                if (size > MAX_SAVE) continue;
                String[] h = hashes.get(f);
                if (h == null || Long.parseLong(h[0]) != size || Long.parseLong(h[1]) != t) {
                    h = new String[]{Long.toString(size), Long.toString(t), md5(Files.readAllBytes(f))};
                    hashes.put(f, h);
                }
                out.put(rel, h);
            }
        } catch (IOException ignored) {
            // what was found
        }
        return out;
    }

    /** A save's file from its path relative to saves/, or null when it isn't one (outside, hidden, temporary). */
    Path savePath(String rel) {
        if (rel == null || rel.isBlank() || rel.length() > 300 || rel.contains("\\") || rel.contains("\0")) return null;
        for (String part : rel.split("/")) {
            if (part.isEmpty() || part.startsWith(".") || part.equals("..")) return null;
        }
        if (rel.endsWith(".tmp")) return null;
        Path f = saves.resolve(rel).normalize();
        return f.startsWith(saves) && !f.equals(saves) ? f : null;
    }

    // ---- remembered links --------------------------------------------------------------------------

    private Path file() {
        return config.home().resolve("peers.properties");
    }

    private void load() {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file())) {
            p.load(in);
        } catch (IOException e) {
            return;
        }
        for (String k : p.stringPropertyNames()) {
            if (!k.endsWith(".out")) continue;
            String id = k.substring(0, k.length() - 4);
            try {
                links.put(id, new Link(id, p.getProperty(id + ".name", id), p.getProperty(id + ".host", ""),
                        Integer.parseInt(p.getProperty(id + ".port", "8080")), p.getProperty(id + ".out"), p.getProperty(id + ".in", ""),
                        Boolean.parseBoolean(p.getProperty(id + ".forward")), !"false".equals(p.getProperty(id + ".sync"))));
            } catch (NumberFormatException ignored) {
                // a broken entry
            }
        }
        links.values().removeIf(l -> l.in.length() < 16 || l.out == null || l.host.isEmpty());
    }

    private synchronized void save() {
        Properties p = new Properties();
        for (Link l : links.values()) {
            p.setProperty(l.id + ".name", l.name);
            p.setProperty(l.id + ".host", l.host);
            p.setProperty(l.id + ".port", Integer.toString(l.port));
            p.setProperty(l.id + ".out", l.out);
            p.setProperty(l.id + ".in", l.in);
            p.setProperty(l.id + ".forward", Boolean.toString(l.forward));
            p.setProperty(l.id + ".sync", Boolean.toString(l.sync));
        }
        try {
            Files.createDirectories(config.home());
            Path tmp = file().resolveSibling("peers.properties.tmp");
            try (var out = Files.newOutputStream(tmp)) {
                p.store(out, "WII-UU: linked WII-UUs (the tokens are passwords: keep this file private)");
            }
            try {
                Files.setPosixFilePermissions(tmp, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // Windows: the user's own folder
            }
            Files.move(tmp, file(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.err.println("[peers] can't save links: " + e.getMessage());
        }
    }

    // ---- plumbing ---------------------------------------------------------------------------------

    private HttpRequest.Builder request(String url, Link l, String... headers) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
                .header("User-Agent", "WII-UU/" + version + " (peer)");
        if (l != null) b.header("X-Peer", l.out).header("X-Peer-Port", Integer.toString(port));
        for (int i = 0; i + 1 < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
        return b;
    }

    private HttpResponse<String> post(String url, Link l, String body, String... headers) throws IOException, InterruptedException {
        return http.send(request(url, l, headers).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String url, Link l, String... headers) throws IOException, InterruptedException {
        return http.send(request(url, l, headers).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void reply(HttpExchange ex, int status, Json j) throws IOException {
        byte[] b = j.toString().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(b);
        }
    }

    private static String body(HttpExchange ex, int max) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            return new String(in.readNBytes(max), StandardCharsets.UTF_8);
        }
    }

    private static Map<String, String> query(HttpExchange ex) {
        String q = ex.getRequestURI().getRawQuery();
        return q == null ? Map.of() : form(q);
    }

    static Map<String, String> form(String body) {
        Map<String, String> m = new HashMap<>();
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            m.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8), URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    private static String form(String... kv) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (b.length() > 0) b.append('&');
            b.append(enc(kv[i])).append('=').append(enc(kv[i + 1]));
        }
        return b.toString();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String str(Object v, String fallback) {
        if (v instanceof Number n) return Long.toString(n.longValue());
        String s = MiniJson.string(v);
        return s == null || s.isBlank() ? fallback : s;
    }

    private static String num(String s) {
        int dot = s.indexOf('.');
        return dot > 0 ? s.substring(0, dot) : s;
    }

    private String token() {
        byte[] b = new byte[24];
        random.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    static String md5(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(b));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
