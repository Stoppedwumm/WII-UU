package wiiuu.ui;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.KeyEvent;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;

/** In-menu Linux Wi-Fi picker. NetworkManager does the actual network operations. */
final class LinuxNetworkPicker {
    record Network(String ssid, int signal, String security, boolean connected) {
        boolean secured() { return !security.isBlank() && !security.equals("--"); }
    }
    private record Result(int exit, String output) {}
    private static final String[] KEYS = {"1234567890", "qwertyuiop", "asdfghjkl-", "zxcvbnm._/"};
    private static final String[] SYMBOLS = {"!@#$%^&*()", "{}[]<>=+~?", "\"':;,?|\\_-", "./@#&%+=*$"};
    private static final String[] COMMANDS = {"Shift", "Symbols", "Space", "Erase", "Cancel", "Connect"};

    private final MenuView view;
    private List<Network> networks = List.of();
    private final Map<Integer, Rectangle2D> hits = new LinkedHashMap<>();
    private final StringBuilder password = new StringBuilder();
    private Network selected;
    private int focus, keyRow = 1, keyCol;
    private boolean typing, shift, symbols, busy, closed;
    private String status = "Scanning for nearby Wi-Fi networks...";

    LinuxNetworkPicker(MenuView view) { this.view = view; refresh(); }
    static boolean supported() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
    }
    boolean typing() { return typing; }
    boolean closed() { return closed; }

    void back() {
        if (typing) {
            typing = false;
            wipePassword();
            status = "Choose a network";
        } else {
            closed = true;
            wipePassword();
        }
        Sfx.back();
    }

    void navigate(int dx, int dy) {
        if (busy) return;
        if (typing) {
            keyRow = Math.max(0, Math.min(4, keyRow + dy));
            keyCol = Math.max(0, Math.min(keyRow == 4 ? COMMANDS.length - 1 : 9, keyCol + dx));
        } else {
            focus = Math.max(0, Math.min(networks.size() + 1, focus + (dy == 0 ? dx : dy)));
        }
        Sfx.move();
    }

    void activate() {
        if (busy) return;
        if (typing) {
            if (keyRow < 4) {
                char ch = (symbols ? SYMBOLS : KEYS)[keyRow].charAt(keyCol);
                append(shift ? Character.toUpperCase(ch) : ch);
                shift = false;
            } else {
                switch (COMMANDS[keyCol]) {
                    case "Shift" -> shift = !shift;
                    case "Symbols" -> symbols = !symbols;
                    case "Space" -> append(' ');
                    case "Erase" -> erase();
                    case "Cancel" -> back();
                    case "Connect" -> submit();
                    default -> { }
                }
            }
        } else if (focus == networks.size()) {
            refresh();
        } else if (focus == networks.size() + 1) {
            back();
        } else if (focus < networks.size()) {
            selected = networks.get(focus);
            if (selected.connected()) {
                status = "Already connected to " + selected.ssid();
            } else if (selected.security().contains("802.1X") || selected.security().contains("WEP")) {
                status = "This network needs advanced credentials. Use a supported WPA-Personal network.";
            } else if (selected.secured()) {
                typing = true;
                keyRow = 1;
                keyCol = 0;
                status = "Enter the password for " + selected.ssid();
            } else {
                connect(selected, null);
            }
        }
        Sfx.select();
    }

    void keyPressed(KeyEvent event) {
        if (!typing || busy) return;
        switch (event.getKeyCode()) {
            case KeyEvent.VK_ESCAPE -> back();
            case KeyEvent.VK_BACK_SPACE -> erase();
            case KeyEvent.VK_ENTER -> submit();
            case KeyEvent.VK_LEFT -> navigate(-1, 0);
            case KeyEvent.VK_RIGHT -> navigate(1, 0);
            case KeyEvent.VK_UP -> navigate(0, -1);
            case KeyEvent.VK_DOWN -> navigate(0, 1);
            case KeyEvent.VK_V -> {
                if (event.isControlDown() || event.isMetaDown()) {
                    try {
                        Object clipboard = Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
                        if (clipboard instanceof String value) for (char c : value.toCharArray()) append(c);
                    } catch (Exception ignored) { }
                }
            }
            default -> { }
        }
    }

    void keyTyped(char c) {
        if (typing && !busy && c >= 32 && c != 127) append(c);
    }

    private void append(char c) {
        if (!Character.isISOControl(c) && password.length() < 128) password.append(c);
    }
    private void erase() {
        if (!password.isEmpty()) password.deleteCharAt(password.length() - 1);
    }
    private void wipePassword() {
        for (int i = 0; i < password.length(); i++) password.setCharAt(i, '\0');
        password.setLength(0);
    }
    private void submit() {
        if (selected == null || password.isEmpty()) {
            status = "Enter a Wi-Fi password";
            return;
        }
        String secret = password.toString();
        wipePassword();
        typing = false;
        connect(selected, secret);
    }

    private void refresh() {
        if (busy || closed) return;
        busy = true;
        status = "Scanning for nearby Wi-Fi networks...";
        Thread task = new Thread(() -> {
            List<Network> found = List.of();
            String info = "Choose a network";
            try {
                Result r = run(List.of("nmcli", "-t", "--escape", "yes", "-f", "IN-USE,SSID,SIGNAL,SECURITY",
                        "device", "wifi", "list", "--rescan", "yes"), null, 20);
                if (r.exit() == 0) {
                    found = parse(r.output());
                    if (found.isEmpty()) info = "No networks found. Check Wi-Fi or connect Ethernet.";
                } else info = "Scan failed: " + shortError(r.output());
            } catch (Exception e) {
                info = "NetworkManager (nmcli) is unavailable or did not respond.";
            }
            List<Network> list = found;
            String message = info;
            SwingUtilities.invokeLater(() -> {
                if (closed) return;
                networks = list;
                focus = 0;
                busy = false;
                status = message;
                view.repaint();
            });
        }, "wiiuu-wifi-scan");
        task.setDaemon(true);
        task.start();
    }

    private void connect(Network network, String secret) {
        busy = true;
        status = "Connecting to " + network.ssid() + "...";
        Thread task = new Thread(() -> {
            String message;
            try {
                Result r;
                if (secret == null) {
                    r = run(List.of("nmcli", "--wait", "35", "device", "wifi", "connect", network.ssid()),
                            null, 42);
                } else {
                    // Create an unsaved-secret WPA profile, then supply the PSK through the
                    // documented passwd-file interface. Neither argv nor disk contains the PSK.
                    String profile = "WII-UU " + java.util.UUID.randomUUID();
                    String keyManagement = network.security().contains("WPA3")
                            && !network.security().contains("WPA2") ? "sae" : "wpa-psk";
                    Result created = run(List.of("nmcli", "connection", "add", "type", "wifi",
                            "ifname", "*", "con-name", profile, "ssid", network.ssid(),
                            "wifi-sec.key-mgmt", keyManagement), null, 15);
                    if (created.exit() != 0) {
                        r = created;
                    } else {
                        try {
                            r = run(List.of("nmcli", "--wait", "35", "connection", "up", "id",
                                    profile, "passwd-file", "/dev/stdin"),
                                    "802-11-wireless-security.psk:" + secret, 42);
                        } finally {
                            // Do not leave profiles without a working password after a failure.
                            // A successful activation stays saved in NetworkManager.
                        }
                        if (r.exit() != 0) {
                            try { run(List.of("nmcli", "connection", "delete", "id", profile), null, 10); }
                            catch (Exception ignored) { }
                        }
                    }
                }
                String safe = secret == null ? r.output() : r.output().replace(secret, "[hidden]");
                message = r.exit() == 0 ? "Connected to " + network.ssid()
                        : "Could not connect: " + shortError(safe);
            } catch (Exception e) {
                message = "Connection failed. Check NetworkManager permissions.";
            }
            String done = message;
            SwingUtilities.invokeLater(() -> {
                if (closed) return;
                busy = false;
                status = done;
                if (done.startsWith("Connected to ")) {
                    networks = networks.stream().map(n -> new Network(n.ssid(), n.signal(),
                            n.security(), n.ssid().equals(network.ssid()))).toList();
                }
                view.repaint();
            });
        }, "wiiuu-wifi-connect");
        task.setDaemon(true);
        task.start();
    }

    // No shell. The passwd-file source is /dev/stdin, so no password enters argv or disk.
    private static Result run(List<String> args, String input, int timeout) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(args).redirectErrorStream(true).start();
        CompletableFuture<String> result = CompletableFuture.supplyAsync(() -> {
            try (var stream = p.getInputStream()) {
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException ignored) { return ""; }
        });
        try (var writer = p.outputWriter(StandardCharsets.UTF_8)) {
            if (input != null) { writer.write(input); writer.write('\n'); writer.flush(); }
        } catch (IOException ignored) { /* Process may have exited without needing input. */ }
        if (!p.waitFor(timeout, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            return new Result(124, "Connection timed out");
        }
        return new Result(p.exitValue(), result.join());
    }

    private static String shortError(String text) {
        String clean = text.replaceAll("[\\r\\n\\t]+", " ").trim();
        if (clean.isEmpty()) return "NetworkManager reported no details";
        return clean.length() > 95 ? clean.substring(0, 95) + "..." : clean;
    }

    /** Parses nmcli's escaped colon-separated fields (including SSIDs containing ':' or '\'). */
    static List<String> split(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder part = new StringBuilder();
        boolean escaped = false;
        for (char c : line.toCharArray()) {
            if (escaped) { part.append(c); escaped = false; }
            else if (c == '\\') escaped = true;
            else if (c == ':') { fields.add(part.toString()); part.setLength(0); }
            else part.append(c);
        }
        if (escaped) part.append('\\');
        fields.add(part.toString());
        return fields;
    }

    static List<Network> parse(String text) {
        Map<String, Network> found = new LinkedHashMap<>();
        for (String line : text.split("\\R")) {
            List<String> parts = split(line);
            if (parts.size() < 4 || parts.get(1).isBlank()) continue;
            int signal;
            try { signal = Integer.parseInt(parts.get(2)); }
            catch (NumberFormatException ignored) { continue; }
            Network n = new Network(parts.get(1), Math.max(0, Math.min(100, signal)),
                    parts.get(3), parts.get(0).trim().equals("*"));
            Network prior = found.get(n.ssid());
            if (prior == null || n.connected() || (!prior.connected() && n.signal() > prior.signal()))
                found.put(n.ssid(), n);
        }
        return found.values().stream().sorted(Comparator.comparing(Network::connected).reversed()
                .thenComparing(Comparator.comparingInt(Network::signal).reversed())
                .thenComparing(Network::ssid, String.CASE_INSENSITIVE_ORDER)).toList();
    }

    void click(double px, double py) {
        if (busy) return;
        for (var entry : hits.entrySet()) {
            if (!entry.getValue().contains(px, py)) continue;
            if (typing) {
                int index = entry.getKey();
                keyRow = index < 40 ? index / 10 : 4;
                keyCol = index < 40 ? index % 10 : index - 40;
            } else focus = entry.getKey();
            activate();
            return;
        }
    }

    void paint(Graphics2D original, int w, int h) {
        Graphics2D g = (Graphics2D) original.create();
        g.setColor(new Color(0, 0, 0, MenuView.dark ? 205 : 145));
        g.fillRect(0, 0, w, h);
        float u = Math.max(.65f, Math.min(w / 1100f, h / 720f));
        float bw = Math.min(w * .9f, 940 * u), bh = Math.min(h * .9f, 640 * u);
        float x = (w - bw) / 2, y = (h - bh) / 2, pad = 32 * u;
        g.setColor(MenuView.CARD);
        g.fill(new RoundRectangle2D.Float(x, y, bw, bh, 25 * u, 25 * u));
        g.setColor(MenuView.ACCENT);
        g.fill(new RoundRectangle2D.Float(x, y, bw, 6 * u, 6 * u, 6 * u));
        g.setColor(MenuView.TEXT);
        g.setFont(MenuView.font(Font.BOLD, 32 * u));
        g.drawString("Choose a Wi-Fi network", x + pad, y + 58 * u);
        g.setFont(MenuView.font(Font.PLAIN, 16 * u));
        g.setColor(MenuView.TEXT_DIM);
        g.drawString("Linux · NetworkManager", x + pad, y + 84 * u);
        g.drawString(trim(g, status, bw - 2 * pad), x + pad, y + 112 * u);
        hits.clear();
        if (typing) drawKeyboard(g, x, y, bw, bh, u);
        else drawNetworks(g, x, y, bw, bh, u);
        g.dispose();
    }

    private void drawNetworks(Graphics2D g, float x, float y, float w, float h, float u) {
        float pad = 32 * u, row = (h - 170 * u) / 8f;
        int start = Math.max(0, Math.min(Math.max(0, networks.size() - 6), focus - 5));
        for (int display = 0; display < 8; display++) {
            int index = display < 6 ? start + display : networks.size() + display - 6;
            if (display < 6 && index >= networks.size()) continue;
            float yy = y + 134 * u + row * display;
            Rectangle2D rect = new Rectangle2D.Float(x + pad, yy, w - 2 * pad, row - 5 * u);
            hits.put(index, rect);
            boolean active = focus == index;
            g.setColor(active ? MenuView.ACCENT : MenuView.dark ? new Color(255,255,255,17) : new Color(0,0,0,11));
            g.fill(new RoundRectangle2D.Float((float) rect.getX(), (float) rect.getY(),
                    (float) rect.getWidth(), (float) rect.getHeight(), 13 * u, 13 * u));
            g.setColor(active ? Color.WHITE : MenuView.TEXT);
            g.setFont(MenuView.font(Font.BOLD, 19 * u));
            String label = index == networks.size() ? "Refresh" : index == networks.size() + 1 ? "Back"
                    : networks.get(index).ssid();
            g.drawString(trim(g, label, w * .57f), x + pad + 15 * u, yy + row * .60f);
            if (index < networks.size()) {
                Network n = networks.get(index);
                String info = n.connected() ? "Connected" : n.signal() + "%  " + (n.secured() ? "Secured" : "Open");
                g.setFont(MenuView.font(Font.PLAIN, 15 * u));
                g.drawString(info, x + w - pad - 15 * u - g.getFontMetrics().stringWidth(info),
                        yy + row * .60f);
            }
        }
        g.setColor(MenuView.TEXT_DIM);
        g.setFont(MenuView.font(Font.PLAIN, 13 * u));
        g.drawString("D-pad / arrows: select     A / Enter: choose     B / Esc: back", x + pad, y + h - 16 * u);
    }

    private void drawKeyboard(Graphics2D g, float x, float y, float w, float h, float u) {
        float pad = 32 * u, top = y + 135 * u;
        g.setColor(MenuView.dark ? new Color(255,255,255,20) : new Color(0,0,0,13));
        g.fill(new RoundRectangle2D.Float(x + pad, top, w - 2 * pad, 54 * u, 12 * u, 12 * u));
        g.setColor(MenuView.TEXT);
        g.setFont(MenuView.font(Font.BOLD, 24 * u));
        String hidden = "\u2022".repeat(Math.min(80, password.length()));
        g.drawString(hidden, x + pad + 14 * u, top + 35 * u);
        float keyTop = top + 72 * u, keyH = (h - 245 * u) / 5f;
        String[] grid = symbols ? SYMBOLS : KEYS;
        for (int r = 0; r < 5; r++) {
            int count = r == 4 ? COMMANDS.length : 10;
            float keyW = (w - 2 * pad) / count;
            for (int c = 0; c < count; c++) {
                float xx = x + pad + c * keyW, yy = keyTop + r * keyH;
                Rectangle2D rect = new Rectangle2D.Float(xx + 3 * u, yy + 3 * u, keyW - 6 * u, keyH - 6 * u);
                hits.put(r == 4 ? 40 + c : 10 * r + c, rect);
                boolean active = keyRow == r && keyCol == c;
                g.setColor(active ? MenuView.ACCENT : MenuView.dark ? new Color(255,255,255,20) : new Color(0,0,0,12));
                g.fill(new RoundRectangle2D.Float((float) rect.getX(), (float) rect.getY(),
                        (float) rect.getWidth(), (float) rect.getHeight(), 10 * u, 10 * u));
                String label = r == 4 ? COMMANDS[c] : String.valueOf(grid[r].charAt(c));
                if (shift && r < 4) label = label.toUpperCase(Locale.ROOT);
                g.setColor(active ? Color.WHITE : MenuView.TEXT);
                g.setFont(MenuView.font(Font.BOLD, (r == 4 ? 13 : 22) * u));
                g.drawString(label, (float) rect.getCenterX() - g.getFontMetrics().stringWidth(label) / 2f,
                        (float) rect.getCenterY() + g.getFontMetrics().getAscent() / 3f);
            }
        }
        g.setColor(MenuView.TEXT_DIM);
        g.setFont(MenuView.font(Font.PLAIN, 13 * u));
        g.drawString("Password is hidden     Enter: connect     Esc: cancel", x + pad, y + h - 16 * u);
    }

    private static String trim(Graphics2D g, String text, float maxWidth) {
        while (!text.isEmpty() && g.getFontMetrics().stringWidth(text) > maxWidth)
            text = text.substring(0, text.length() - 1);
        return text;
    }
}
