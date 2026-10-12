package wiiuu.input;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import wiiuu.core.Config;

/**
 * Button remapping for controllers plugged into the PC, button for button, kept per kind of
 * controller (by its name, so two of the same share it): {@code remap.<name>=B=A,A=B,K2C0=HOME}.
 *
 * <p>A controller's buttons ("physical") are named as the GamePad's buttons at the same place
 * (A right, B bottom, X top, Y left, L, R, ZL, ZR, PLUS, MINUS, HOME, L3, R3 and the d-pad), or
 * K + their code for ones the GamePad has nothing like (paddles, extra buttons). Each one plays
 * the GamePad button it is mapped to, or the one of its own name, or nothing ("NONE").
 */
public final class PadRemap {
    /** The GamePad buttons a controller button can be mapped to, in the order Settings lists them. */
    public static final List<PadButton> TARGETS = List.of(PadButton.A, PadButton.B, PadButton.X, PadButton.Y,
            PadButton.L, PadButton.R, PadButton.ZL, PadButton.ZR, PadButton.PLUS, PadButton.MINUS, PadButton.HOME,
            PadButton.L3, PadButton.R3, PadButton.UP, PadButton.DOWN, PadButton.LEFT, PadButton.RIGHT);

    private final Config config;

    public PadRemap(Config config) {
        this.config = config;
    }

    private static String key(String controller) {
        String k = controller.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
        return "remap." + (k.isEmpty() ? "controller" : k);
    }

    /** physical -> target ("NONE": nothing), the changes from the standard layout only. */
    public Map<String, String> changes(String controller) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String pair : config.get(key(controller), "").split(",")) {
            int eq = pair.indexOf('=');
            if (eq > 0) m.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
        }
        return m;
    }

    private void store(String controller, Map<String, String> m) {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, String> e : m.entrySet()) {
            if (e.getKey().equals(e.getValue())) continue;              // the standard: nothing to keep
            if (b.length() > 0) b.append(',');
            b.append(e.getKey()).append('=').append(e.getValue());
        }
        config.set(key(controller), b.length() == 0 ? null : b.toString());
        config.save();
    }

    /** What a controller button plays: a GamePad button, or null for nothing. */
    public PadButton target(String controller, String physical) {
        String t = changes(controller).get(physical);
        if (t == null) t = physical;
        return t.equals("NONE") ? null : PadButton.parse(t);
    }

    /** Whether a controller has any changes. */
    public boolean changed(String controller) {
        return !changes(controller).isEmpty();
    }

    /**
     * Makes {@code physical} play {@code target}. The button that played {@code target} until now
     * gets what {@code physical} played (they swap), so no button is lost; an extra button that
     * played nothing is simply added.
     */
    public void assign(String controller, PadButton target, String physical) {
        Map<String, String> m = changes(controller);
        String was = m.getOrDefault(physical, isTarget(physical) ? physical : "NONE");
        if (!was.equals("NONE")) {
            // a swap; an extra button that did nothing just becomes one more for target
            for (String other : sources(controller, target)) {
                if (!other.equals(physical)) m.put(other, was);
            }
        }
        m.put(physical, target.name());
        store(controller, m);
    }

    /** Back to the standard layout. */
    public void reset(String controller) {
        config.set(key(controller), null);
        config.save();
    }

    /** The controller buttons that play {@code target} now. */
    public List<String> sources(String controller, PadButton target) {
        Map<String, String> m = changes(controller);
        List<String> out = new ArrayList<>();
        if (!m.containsKey(target.name())) out.add(target.name());          // its own, unless moved
        for (Map.Entry<String, String> e : m.entrySet()) {
            if (e.getValue().equals(target.name()) && !e.getKey().equals(target.name())) out.add(e.getKey());
        }
        return out;
    }

    private static boolean isTarget(String physical) {
        PadButton b = PadButton.parse(physical);
        return b != null && TARGETS.contains(b);
    }

    /** A controller button for people: "B (bottom)", "Extra button 2C0". */
    public static String describe(String physical) {
        return switch (physical) {
            case "A" -> "A (right)";
            case "B" -> "B (bottom)";
            case "X" -> "X (top)";
            case "Y" -> "Y (left)";
            case "PLUS" -> "+ / Start";
            case "MINUS" -> "− / Select";
            case "HOME" -> "Home / Guide";
            case "UP" -> "D-pad up";
            case "DOWN" -> "D-pad down";
            case "LEFT" -> "D-pad left";
            case "RIGHT" -> "D-pad right";
            case "L3" -> "L3 (left stick press)";
            case "R3" -> "R3 (right stick press)";
            default -> physical.startsWith("K") ? "Extra button " + physical.substring(1) : physical;
        };
    }
}
