package wiiuu.net;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import wiiuu.core.Config;
import wiiuu.core.MiniJson;
import wiiuu.input.PadButton;

/** Shared, bounded controller layouts for the desktop and paired GamePads. */
public final class GamepadLayouts {
    private static final String FILE = "gamepad-layouts.json";
    private static final List<String> TYPES = List.of("button", "slider", "stick", "dpad");

    private GamepadLayouts() {}

    public record Widget(String type, int x, int y, int w, int h, String label, String button,
                         String up, String down, String left, String right, String upMode, String downMode,
                         int axis, boolean racing, String upLabel, String downLabel) {
        public Widget position(int x, int y, int w, int h) {
            w = Math.min(100, Math.max(5, w));
            h = Math.min(100, Math.max(5, h));
            return new Widget(type, Math.max(0, Math.min(100 - w, x)), Math.max(0, Math.min(100 - h, y)), w, h,
                    label, button, up, down, left, right, upMode, downMode, axis, racing, upLabel, downLabel);
        }
    }

    public record Layout(String id, String name, List<Widget> widgets) {
        @Override public String toString() { return name; }
    }

    public static String newId() {
        return "custom-" + UUID.randomUUID();
    }

    public static Widget widget(String type) {
        return switch (type) {
            case "button" -> new Widget(type, 5, 5, 12, 12, "Button", "A", "", "", "", "", "hold", "tap", 0, false, "", "");
            case "slider" -> new Widget(type, 76, 18, 18, 60, "Forward", "A", "R", "L", "", "", "hold", "tap", 0, false, "Drift", "Item");
            case "stick" -> new Widget(type, 5, 10, 19, 32, "Joystick", "", "", "", "", "", "hold", "tap", 0, false, "", "");
            case "dpad" -> new Widget(type, 4, 35, 21, 38, "Steering", "", "UP", "DOWN", "LEFT", "RIGHT", "hold", "tap", 0, true, "", "");
            default -> throw new IllegalArgumentException("Unknown control");
        };
    }

    public static String json(Config config) {
        try { return read(config); }
        catch (IOException e) { return "[]"; }
    }

    private static String read(Config config) throws IOException {
        try { return Files.readString(config.home().resolve(FILE)); }
        catch (NoSuchFileException e) { return "[]"; }
    }

    public static List<Layout> load(Config config) throws IOException {
        return MiniJson.array(MiniJson.parse(read(config))).stream().map(GamepadLayouts::parse).toList();
    }

    public static void save(Config config, Layout layout) throws IOException {
        change(config, layout.id(), layout);
    }

    public static void delete(Config config, String id) throws IOException {
        change(config, id, null);
    }

    private static void change(Config config, String id, Layout layout) throws IOException {
        if (id == null || !id.matches("custom-[a-z0-9-]{1,64}")) throw new IllegalArgumentException("Invalid layout ID");
        synchronized (config) {
            var list = new ArrayList<>(load(config));
            int index = -1;
            for (int i = 0; i < list.size(); i++) if (list.get(i).id().equals(id)) { index = i; break; }
            if (layout == null && index >= 0) list.remove(index);
            if (layout != null) {
                // Validate desktop edits through the same path as the web editor.
                layout = parse(MiniJson.parse(encode(new Json(), layout).toString()));
                if (index >= 0) list.set(index, layout);
                else {
                    if (list.size() >= 24) throw new IllegalArgumentException("Delete a layout before adding another.");
                    list.add(layout);
                }
            }
            var json = new Json().arr();
            for (Layout entry : list) encode(json, entry);
            Path home = Files.createDirectories(config.home());
            Path staged = Files.createTempFile(home, ".gamepad-layouts-", ".tmp");
            try {
                Files.writeString(staged, json.endArr().toString());
                Files.move(staged, home.resolve(FILE), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(staged);
            }
        }
    }

    static Layout parse(Object value) {
        Map<String, Object> item = MiniJson.object(value);
        String id = string(item, "id", 71), name = string(item, "name", 32).strip();
        if (!id.matches("custom-[a-z0-9-]{1,64}") || name.isEmpty() || !(item.get("widgets") instanceof List<?> widgets) || widgets.size() > 32)
            throw new IllegalArgumentException("Choose a layout name and at most 32 controls.");
        var controls = new ArrayList<Widget>();
        for (Object widget : widgets) {
            var m = MiniJson.object(widget);
            String type = string(m, "type", 8);
            if (!TYPES.contains(type)) throw new IllegalArgumentException("Unknown control type");
            int x = number(m, "x", 0, 95), y = number(m, "y", 0, 95);
            int w = number(m, "w", 5, 100), h = number(m, "h", 5, 100);
            if (x + w > 100 || y + h > 100) throw new IllegalArgumentException("A control is outside the canvas.");
            controls.add(new Widget(type, x, y, w, h, string(m, "label", 24), binding(m, "button"),
                    binding(m, "up"), binding(m, "down"), binding(m, "left"), binding(m, "right"),
                    mode(m, "upMode"), mode(m, "downMode"), number(m, "axis", 0, 1), Boolean.TRUE.equals(m.get("racing")),
                    optionalLabel(m, "upLabel"), optionalLabel(m, "downLabel")));
        }
        return new Layout(id, name, List.copyOf(controls));
    }

    private static String string(Map<String, Object> m, String key, int max) {
        String value = MiniJson.string(m.get(key));
        if (value == null || value.length() > max) throw new IllegalArgumentException("Invalid " + key);
        return value;
    }

    private static String optionalLabel(Map<String, Object> m, String key) {
        return m.containsKey(key) ? string(m, key, 24) : "";
    }

    private static int number(Map<String, Object> m, String key, int min, int max) {
        Double value = MiniJson.number(m.get(key));
        if (value == null || value < min || value > max || value != value.intValue())
            throw new IllegalArgumentException("Invalid " + key);
        return value.intValue();
    }

    private static String binding(Map<String, Object> m, String key) {
        String value = string(m, key, 16);
        if (!value.isEmpty() && (PadButton.parse(value) == null || !value.equals(PadButton.parse(value).name())))
            throw new IllegalArgumentException("Invalid button");
        return value;
    }

    private static String mode(Map<String, Object> m, String key) {
        String value = string(m, key, 4);
        if (!value.equals("hold") && !value.equals("tap")) throw new IllegalArgumentException("Invalid gesture mode");
        return value;
    }

    private static Json encode(Json json, Layout layout) {
        json.obj().kv("id", layout.id()).kv("name", layout.name()).key("widgets").arr();
        for (Widget w : layout.widgets()) {
            json.obj().kv("type", w.type()).kv("x", w.x()).kv("y", w.y()).kv("w", w.w()).kv("h", w.h())
                    .kv("label", w.label()).kv("button", w.button()).kv("up", w.up()).kv("down", w.down())
                    .kv("left", w.left()).kv("right", w.right()).kv("upMode", w.upMode()).kv("downMode", w.downMode())
                    .kv("axis", w.axis()).kv("racing", w.racing()).kv("upLabel", w.upLabel()).kv("downLabel", w.downLabel()).endObj();
        }
        return json.endArr().endObj();
    }
}
