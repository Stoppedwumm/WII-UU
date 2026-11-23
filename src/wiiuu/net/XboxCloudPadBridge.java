package wiiuu.net;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import wiiuu.input.PadButton;

/**
 * The Xbox stream sees the same inputs that WII-UU routes to its emulators:
 * after controller-specific PadRemap, with the controller manager's player order.
 * No controller identity or credentials need to enter the browser.
 */
public final class XboxCloudPadBridge {
    private static final String[] BUTTONS = {
        "A", "B", "X", "Y", "LeftShoulder", "RightShoulder",
        "LeftTrigger", "RightTrigger", "View", "Menu",
        "LeftThumb", "RightThumb", "DPadUp", "DPadDown",
        "DPadLeft", "DPadRight", "Nexus"
    };
    private static final String[] AXES = {
        "LeftThumbXAxis", "LeftThumbYAxis", "RightThumbXAxis", "RightThumbYAxis"
    };

    private static final Map<PadButton, String> BUTTON_NAMES = Map.ofEntries(
        // WII-UU labels face buttons by physical position (A east, B south).
        // Xbox frames label them by Xbox semantics (A south, B east).
        Map.entry(PadButton.A, "B"), Map.entry(PadButton.B, "A"),
        Map.entry(PadButton.X, "Y"), Map.entry(PadButton.Y, "X"),
        Map.entry(PadButton.L, "LeftShoulder"), Map.entry(PadButton.R, "RightShoulder"),
        Map.entry(PadButton.ZL, "LeftTrigger"), Map.entry(PadButton.ZR, "RightTrigger"),
        Map.entry(PadButton.MINUS, "View"), Map.entry(PadButton.PLUS, "Menu"),
        Map.entry(PadButton.L3, "LeftThumb"), Map.entry(PadButton.R3, "RightThumb"),
        Map.entry(PadButton.UP, "DPadUp"), Map.entry(PadButton.DOWN, "DPadDown"),
        Map.entry(PadButton.LEFT, "DPadLeft"), Map.entry(PadButton.RIGHT, "DPadRight"),
        Map.entry(PadButton.HOME, "Nexus")
    );

    private static final class State {
        final Map<String, Double> buttons = new LinkedHashMap<>();
        final double[] axes = new double[4];

        synchronized void input(String body) {
            for (String line : body.split("\\n")) {
                String[] parts = line.trim().split(" ");
                try {
                    if (parts.length == 3 && "b".equals(parts[0])) {
                        PadButton button = PadButton.parse(parts[1]);
                        String name = BUTTON_NAMES.get(button);
                        if (name != null) buttons.put(name, "1".equals(parts[2]) ? 1.0 : 0.0);
                    } else if (parts.length == 4 && "s".equals(parts[0])) {
                        int stick = Integer.parseInt(parts[1]);
                        if (stick < 0 || stick > 1) continue;
                        double x = Double.parseDouble(parts[2]), y = Double.parseDouble(parts[3]);
                        if (Double.isFinite(x) && Double.isFinite(y)) {
                            axes[stick * 2] = Math.max(-1.0, Math.min(1.0, x));
                            axes[stick * 2 + 1] = Math.max(-1.0, Math.min(1.0, y));
                        }
                    }
                } catch (RuntimeException ignored) {
                    // Ignore a malformed or unsupported controller event.
                }
            }
        }

        synchronized Map<String, Object> snapshot(int slot) {
            Map<String, Object> frame = new LinkedHashMap<>();
            frame.put("GamepadIndex", slot);
            for (String button : BUTTONS) frame.put(button, buttons.getOrDefault(button, 0.0));
            for (int i = 0; i < AXES.length; i++) frame.put(AXES[i], axes[i]);
            // WII-UU uses the same Menu + View shortcut as Xbox's guide button.
            if ((double) frame.get("Menu") > 0 && (double) frame.get("View") > 0) {
                frame.put("Menu", 0.0);
                frame.put("View", 0.0);
                frame.put("Nexus", 1.0);
            }
            return frame;
        }
    }

    private final ConcurrentHashMap<String, State> states = new ConcurrentHashMap<>();

    /** A WII-UU event AFTER the controller manager's custom remapping. */
    public void input(String id, int player, String body) {
        if (id == null || player < 1 || player > 4 || body == null || body.length() > 4096) return;
        states.computeIfAbsent(id, ignored -> new State()).input(body);
    }

    /** Release all buttons when a controller disconnects or changes player. */
    public void release(String id) {
        if (id != null) states.remove(id);
    }

    /** Active phones and physical controllers, ordered by the WII-UU player selector. */
    public List<Map<String, Object>> snapshot(List<GamepadServer.Pad> connected) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (GamepadServer.Pad pad : connected) {
            if (pad.player() < 1 || pad.player() > 4) continue;
            State state = states.get(pad.id());
            result.add((state == null ? new State() : state).snapshot(pad.player() - 1));
        }
        result.sort(Comparator.comparingInt(frame -> ((Number) frame.get("GamepadIndex")).intValue()));
        return result;
    }

    /** JSON contains only fixed field names and bounded numeric values; no device names or IDs. */
    public static String json(List<Map<String, Object>> frames) {
        StringBuilder json = new StringBuilder("[");
        for (Map<String, Object> frame : frames) {
            if (json.length() > 1) json.append(',');
            json.append('{');
            for (Map.Entry<String, Object> field : frame.entrySet()) {
                if (json.charAt(json.length() - 1) != '{') json.append(',');
                json.append('"').append(field.getKey()).append('"').append(':')
                    .append(field.getValue());
            }
            json.append('}');
        }
        return json.append(']').toString();
    }
}
