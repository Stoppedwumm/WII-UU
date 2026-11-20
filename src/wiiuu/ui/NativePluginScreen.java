package wiiuu.ui;

import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import javax.imageio.*;
import javax.imageio.stream.ImageInputStream;
import javax.swing.SwingUtilities;
import wiiuu.core.Config;
import wiiuu.core.NativePluginSession;
import wiiuu.core.PluginStore;

/** Native TV/GamePad panels: all layout, input and painting stays in WII-UU's Java UI. */
final class NativePluginScreen implements AutoCloseable {
    private record Action(int entry, NativePluginSession.Button button) {}
    private record Hit(Rectangle2D rectangle, int focus) {}
    private NativePluginSession session;
    private NativePluginSession.View model;
    private final Runnable repaint, exit;
    private final List<Action> actions = new ArrayList<>();
    private final List<Hit> hits = new ArrayList<>();
    private final Map<String, BufferedImage> images = new LinkedHashMap<>();
    private final Set<String> requested = new HashSet<>();
    private final ExecutorService loader = Executors.newFixedThreadPool(2, task -> {
        Thread thread = new Thread(task, "native-plugin-images"); thread.setDaemon(true); return thread;
    });
    private boolean closed;
    private String error;
    private int focus, page, columns = 3, perPage = 6;

    NativePluginScreen(String title, Runnable repaint, Runnable exit) {
        this.repaint = repaint; this.exit = exit;
        model = new NativePluginSession.View("v0", title, true, "Loading plugin...", List.of());
        rebuild();
    }
    void start(Config config, PluginStore.Entry entry) {
        session = NativePluginSession.open(config, entry,
                view -> SwingUtilities.invokeLater(() -> { if (!closed) accept(view); }),
                message -> SwingUtilities.invokeLater(() -> { if (!closed) { error = message; repaint.run(); } }),
                () -> SwingUtilities.invokeLater(() -> { if (!closed) exit.run(); }));
    }
    // Package-visible so the UI fixture can render protocol models without starting an external host.
    void accept(NativePluginSession.View view) {
        String selected = focus >= 0 && focus < actions.size() && actions.get(focus).button() != null ? actions.get(focus).button().id() : null;
        boolean newView = !model.id().equals(view.id()) || model.entries().isEmpty() && !view.entries().isEmpty();
        model = view; error = null; rebuild();
        if (newView) { page = 0; focus = firstOnPage(); }
        else if (selected != null) for (int i = 0; i < actions.size(); i++)
            if (actions.get(i).button() != null && selected.equals(actions.get(i).button().id())) { focus = i; break; }
        focus = Math.max(0, Math.min(focus, actions.size() - 1));
        page = Math.max(0, Math.min(page, pages() - 1));
        repaint.run();
    }
    private void rebuild() {
        hits.clear(); actions.clear();
        if (model.closeButton()) actions.add(new Action(-1, null));
        for (int i = 0; i < model.entries().size(); i++) for (var button : model.entries().get(i).buttons()) actions.add(new Action(i, button));
    }
    private int pages() { return Math.max(1, (model.entries().size() + perPage - 1) / perPage); }
    private int firstOnPage() {
        for (int i = 0; i < actions.size(); i++) if (actions.get(i).entry() >= page * perPage && actions.get(i).entry() < (page + 1) * perPage) return i;
        return 0;
    }
    void navigate(int dx, int dy) {
        if (actions.isEmpty()) return;
        if (dx != 0) focus = Math.max(0, Math.min(actions.size() - 1, focus + Integer.signum(dx)));
        else if (dy != 0) {
            Action selected = actions.get(focus);
            if (selected.entry() < 0 && dy < 0) return;
            int target = selected.entry() < 0 ? 0 : selected.entry() + Integer.signum(dy) * columns;
            if (target < 0 && model.closeButton()) focus = 0;
            else {
                for (int entry = target; entry >= 0 && entry < model.entries().size(); entry += Integer.signum(dy)) {
                    boolean found = false;
                    for (int i = 0; i < actions.size(); i++) if (actions.get(i).entry() == entry) { focus = i; found = true; break; }
                    if (found) break;
                }
            }
        }
        if (actions.get(focus).entry() >= 0) page = actions.get(focus).entry() / perPage;
        repaint.run();
    }
    void activate() {
        if (actions.isEmpty()) return;
        Action selected = actions.get(focus);
        if (selected.button() == null) exit.run();
        else if (!selected.button().busy() && session != null) session.activate(selected.button().id());
    }
    void page(int delta) {
        page = Math.max(0, Math.min(pages() - 1, page + Integer.signum(delta)));
        hits.clear();
        focus = firstOnPage(); repaint.run();
    }
    void click(int x, int y) {
        for (Hit hit : hits) if (hit.rectangle().contains(x, y)) { focus = hit.focus(); activate(); return; }
    }
    void paint(Graphics2D g, int width, int height) {
        g.setColor(MenuView.dark ? new Color(25, 29, 38) : new Color(243, 246, 250)); g.fillRect(0, 0, width, height);
        float unit = Math.max(1, Math.min(width / 1000f, height / 650f));
        float margin = 28 * unit, top = 90 * unit, bottom = 68 * unit, gap = 18 * unit;
        g.setColor(MenuView.TEXT); g.setFont(MenuView.font(Font.BOLD, 28 * unit));
        g.drawString(ellipsize(g, model.title(), width - margin * 2 - 110 * unit), margin, 46 * unit);
        g.setFont(MenuView.font(Font.PLAIN, 13 * unit)); g.setColor(MenuView.TEXT_DIM);
        g.drawString("WII-UU Apps", margin, 69 * unit);
        hits.clear();
        if (model.closeButton()) {
            Rectangle2D close = new Rectangle2D.Float(width - margin - 90 * unit, 22 * unit, 90 * unit, 42 * unit);
            button(g, close, "Close", focus == 0, false, unit); hits.add(new Hit(close, 0));
        }
        columns = width >= 1000 ? 3 : width >= 650 ? 2 : 1;
        int rows = height >= 650 ? 2 : 1;
        int previousPageSize = perPage;
        perPage = columns * rows;
        if (previousPageSize != perPage && !actions.isEmpty() && actions.get(focus).entry() >= 0) page = actions.get(focus).entry() / perPage;
        page = Math.min(page, pages() - 1);
        float cw = (width - margin * 2 - gap * (columns - 1)) / columns;
        float ch = (height - top - bottom - gap * (rows - 1)) / rows;
        for (int local = 0; local < perPage; local++) {
            int index = page * perPage + local; if (index >= model.entries().size()) break;
            var entry = model.entries().get(index);
            float x = margin + local % columns * (cw + gap), y = top + local / columns * (ch + gap);
            RoundRectangle2D card = new RoundRectangle2D.Float(x, y, cw, ch, 20 * unit, 20 * unit);
            g.setColor(MenuView.CARD); g.fill(card);
            float imageH = Math.max(32 * unit, ch * 0.38f);
            BufferedImage image = image(entry.imageURL());
            if (image != null) {
                float scale = Math.min((cw - 24 * unit) / image.getWidth(), imageH / image.getHeight());
                int iw = (int) (image.getWidth() * scale), ih = (int) (image.getHeight() * scale);
                g.drawImage(image, (int) (x + (cw - iw) / 2), (int) (y + 10 * unit + (imageH - ih) / 2), iw, ih, null);
            } else {
                g.setColor(MenuView.ACCENT); g.setFont(MenuView.font(Font.BOLD, 28 * unit));
                String initial = entry.title().isEmpty() ? "?" : entry.title().substring(0, 1);
                centered(g, initial, x + cw / 2, y + 10 * unit + imageH * 0.6f);
            }
            g.setColor(MenuView.TEXT); g.setFont(MenuView.font(Font.BOLD, 17 * unit));
            centered(g, ellipsize(g, entry.title(), cw - 24 * unit), x + cw / 2, y + imageH + 35 * unit);
            int actionRows = (entry.buttons().size() + 1) / 2;
            float bh = Math.min(36 * unit, Math.max(14 * unit, (ch - imageH - 56 * unit) / Math.max(1, actionRows) - 5 * unit));
            for (int b = 0; b < entry.buttons().size(); b++) {
                var action = entry.buttons().get(b);
                int actionColumns = entry.buttons().size() == 1 ? 1 : 2;
                float bw = (cw - 24 * unit - (actionColumns - 1) * 6 * unit) / actionColumns;
                Rectangle2D rect = new Rectangle2D.Float(x + 12 * unit + b % actionColumns * (bw + 6 * unit),
                        y + imageH + 48 * unit + b / actionColumns * (bh + 5 * unit), bw, bh);
                int actionFocus = -1;
                for (int a = 0; a < actions.size(); a++) if (actions.get(a).entry() == index && actions.get(a).button().id().equals(action.id())) { actionFocus = a; break; }
                button(g, rect, action.busy() ? action.label() + "..." : action.label(), focus == actionFocus, action.busy(), unit);
                hits.add(new Hit(rect, actionFocus));
            }
        }
        if (model.entries().isEmpty()) {
            g.setColor(MenuView.TEXT_DIM); g.setFont(MenuView.font(Font.PLAIN, 20 * unit));
            centered(g, error == null ? "Waiting for plugin entries..." : "The plugin could not load.", width / 2f, height / 2f);
        }
        g.setColor(error == null ? MenuView.TEXT_DIM : new Color(200, 65, 65));
        g.setFont(MenuView.font(Font.PLAIN, 14 * unit));
        g.drawString(ellipsize(g, error == null ? model.status() : error, width - margin * 2), margin, height - 38 * unit);
        g.setColor(MenuView.TEXT_DIM); g.setFont(MenuView.font(Font.PLAIN, 12 * unit));
        g.drawString("A: Select   B: Close   Left/Right: buttons   Up/Down: panels   L/R or scroll: page " + (page + 1) + "/" + pages(), margin, height - 15 * unit);
    }
    private static String ellipsize(Graphics2D g, String value, float width) { return UiText.ellipsize(value, g.getFontMetrics(), width); }
    private static void centered(Graphics2D g, String text, float x, float baseline) {
        g.drawString(text, x - g.getFontMetrics().stringWidth(text) / 2f, baseline);
    }
    private static void button(Graphics2D g, Rectangle2D r, String label, boolean selected, boolean busy, float unit) {
        g.setColor(selected ? MenuView.ACCENT : MenuView.dark ? new Color(60, 68, 82) : new Color(225, 232, 240));
        g.fill(new RoundRectangle2D.Double(r.getX(), r.getY(), r.getWidth(), r.getHeight(), 12 * unit, 12 * unit));
        g.setColor(selected ? Color.WHITE : MenuView.TEXT); if (busy) g.setColor(MenuView.TEXT_DIM);
        g.setFont(MenuView.font(Font.BOLD, Math.min(13 * unit, (float) r.getHeight() * 0.5f)));
        centered(g, ellipsize(g, label, (float) r.getWidth() - 10 * unit), (float) r.getCenterX(), (float) r.getCenterY() + g.getFontMetrics().getAscent() * 0.35f);
    }
    private BufferedImage image(String url) {
        if (url.isEmpty()) return null;
        BufferedImage cached = images.get(url); if (cached != null) return cached;
        if (closed || !requested.add(url)) return null;
        loader.submit(() -> {
            BufferedImage loaded = null;
            try { loaded = loadImage(URI.create(url)); } catch (Exception ignored) { }
            BufferedImage result = loaded;
            SwingUtilities.invokeLater(() -> {
                if (closed) return;
                if (result != null) {
                    if (images.size() >= 64) { String oldest = images.keySet().iterator().next(); images.remove(oldest); requested.remove(oldest); }
                    images.put(url, result);
                }
                repaint.run();
            });
        });
        return null;
    }
    private static BufferedImage loadImage(URI uri) throws IOException {
        for (int redirects = 0; redirects < 6; redirects++) {
            if (!List.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null) throw new IOException("Invalid image URL");
            HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setConnectTimeout(5000); connection.setReadTimeout(5000); connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "WII-UU-Native-Plugins");
            try {
                int status = connection.getResponseCode();
                if (List.of(301, 302, 303, 307, 308).contains(status)) {
                    String location = connection.getHeaderField("Location"); if (location == null) return null;
                    uri = uri.resolve(location); continue;
                }
                if (status != 200 || connection.getContentLengthLong() > 2 * 1024 * 1024) return null;
                byte[] bytes;
                try (InputStream input = connection.getInputStream(); ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                    byte[] chunk = new byte[8192];
                    while (buffer.size() <= 2 * 1024 * 1024) {
                        long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                        if (remaining <= 0 || Thread.currentThread().isInterrupted()) throw new IOException("Image request cancelled or timed out");
                        connection.setReadTimeout((int) Math.max(1, Math.min(2000, remaining)));
                        int count = input.read(chunk); if (count < 0) break;
                        buffer.write(chunk, 0, count);
                    }
                    bytes = buffer.toByteArray();
                }
                if (bytes.length > 2 * 1024 * 1024) return null;
                try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
                    var readers = ImageIO.getImageReaders(input); if (!readers.hasNext()) return null;
                    ImageReader reader = readers.next();
                    try {
                        reader.setInput(input); int w = reader.getWidth(0), h = reader.getHeight(0);
                        if (w <= 0 || h <= 0 || w > 8192 || h > 8192) return null;
                        var params = reader.getDefaultReadParam(); int sub = Math.max(1, Math.max(w / 400, h / 240));
                        params.setSourceSubsampling(sub, sub, 0, 0); return reader.read(0, params);
                    } finally { reader.dispose(); }
                }
            } finally { connection.disconnect(); }
        }
        return null;
    }
    @Override public void close() { closed = true; loader.shutdownNow(); if (session != null) session.close(); images.clear(); }
}
