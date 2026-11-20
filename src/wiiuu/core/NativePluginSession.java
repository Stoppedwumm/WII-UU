package wiiuu.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Bounded JSON-lines bridge between reviewed JavaScript definitions and WII-UU's native UI. */
public final class NativePluginSession implements AutoCloseable {
    public record Button(String id, String label, boolean busy) {}
    public record Entry(String id, String title, String imageURL, List<Button> buttons) {}
    public record View(String id, String title, boolean closeButton, String status, List<Entry> entries) {}
    private volatile Process process;
    private volatile boolean closed;
    private final ExecutorService commands = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "native-plugin-commands"); thread.setDaemon(true); return thread;
    });
    private NativePluginSession() {}

    public static NativePluginSession open(Config config, PluginStore.Entry plugin, Consumer<View> view,
                                           Consumer<String> error, Runnable requestClose) {
        NativePluginSession session = new NativePluginSession();
        Thread worker = new Thread(() -> {
            try {
                if (session.closed) return;
                Process process = PluginStore.startNative(config, plugin);
                synchronized (session) {
                    session.process = process;
                    if (session.closed) { terminate(process); return; }
                }
                try (Reader reader = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
                    String line;
                    boolean hostError = false;
                    while (!session.closed && (line = readLine(reader)) != null) {
                        Map<String, Object> event = MiniJson.object(MiniJson.parse(line));
                        switch (Objects.toString(event.get("type"), "")) {
                            case "view" -> view.accept(parseView(event.get("view")));
                            case "error" -> { hostError = true; error.accept(string(event, "message", 500)); }
                            case "close" -> requestClose.run();
                            default -> throw new IOException("Unknown native plugin event");
                        }
                    }
                    if (!session.closed && !hostError) error.accept("Plugin stopped. Press B to return to the menu.");
                }
            } catch (Exception exception) {
                if (!session.closed) error.accept("Native plugin: " + Objects.toString(exception.getMessage(), "Host failed"));
            } finally { session.close(); }
        }, "native-plugin-" + plugin.id());
        worker.setDaemon(true); worker.start();
        return session;
    }
    private static String readLine(Reader reader) throws IOException {
        StringBuilder line = new StringBuilder();
        int value;
        while ((value = reader.read()) != -1) {
            if (value == '\n') return line.toString();
            if (line.length() >= 2 * 1024 * 1024) throw new IOException("Native view exceeds 2 MiB");
            line.append((char) value);
        }
        if (!line.isEmpty()) throw new IOException("Incomplete native plugin message");
        return null;
    }
    private static String string(Map<String, Object> map, String key, int limit) {
        String value = MiniJson.string(map.get(key));
        if (value == null || value.length() > limit) throw new IllegalArgumentException("Invalid native view " + key);
        return value;
    }
    private static String id(Map<String, Object> map) {
        String value = string(map, "id", 32);
        if (!value.matches("[a-z][0-9]+")) throw new IllegalArgumentException("Invalid native control ID");
        return value;
    }
    public static View parseView(Object input) {
        Map<String, Object> model = MiniJson.object(input);
        String viewId = id(model), title = string(model, "title", 160), status = string(model, "status", 500);
        if (!(model.get("closeButton") instanceof Boolean)) throw new IllegalArgumentException("Invalid closeButton");
        if (!(model.get("entries") instanceof List<?> values) || values.size() > 500) throw new IllegalArgumentException("Too many panel entries");
        List<Entry> entries = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Object value : values) {
            Map<String, Object> entry = MiniJson.object(value);
            String entryId = id(entry), image = string(entry, "imageURL", 2048);
            if (!ids.add(entryId)) throw new IllegalArgumentException("Duplicate entry ID");
            if (!image.isEmpty()) {
                java.net.URI uri = java.net.URI.create(image);
                if (!List.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null)
                    throw new IllegalArgumentException("Invalid panel image URL");
            }
            if (!(entry.get("buttons") instanceof List<?> actions) || actions.size() > 8) throw new IllegalArgumentException("Too many panel buttons");
            List<Button> buttons = new ArrayList<>();
            for (Object action : actions) {
                Map<String, Object> button = MiniJson.object(action);
                if (!(button.get("busy") instanceof Boolean)) throw new IllegalArgumentException("Invalid button state");
                buttons.add(new Button(id(button), string(button, "label", 48), Boolean.TRUE.equals(button.get("busy"))));
            }
            entries.add(new Entry(entryId, string(entry, "title", 160), image, List.copyOf(buttons)));
        }
        return new View(viewId, title, (Boolean) model.get("closeButton"), status, List.copyOf(entries));
    }
    public void activate(String id) {
        if (closed || !id.matches("b[0-9]+")) return;
        try { commands.submit(() -> {
            Process host = process;
            if (closed || host == null || !host.isAlive()) return;
            try {
                host.getOutputStream().write(("{\"type\":\"activate\",\"id\":\"" + id + "\"}\n").getBytes(StandardCharsets.UTF_8));
                host.getOutputStream().flush();
            } catch (IOException ignored) { close(); }
        }); } catch (RejectedExecutionException ignored) { }
    }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        commands.shutdownNow();
        Process host = process;
        if (host != null) terminate(host);
    }
    private static void terminate(Process host) {
        try { host.getOutputStream().close(); } catch (IOException ignored) { }
        host.destroy();
        CompletableFuture.delayedExecutor(2, TimeUnit.SECONDS).execute(() -> {
            if (host.isAlive()) { host.descendants().forEach(ProcessHandle::destroyForcibly); host.destroyForcibly(); }
        });
    }
}
