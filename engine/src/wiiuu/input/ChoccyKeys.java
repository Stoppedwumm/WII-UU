package wiiuu.input;

import java.awt.event.KeyEvent;
import java.util.*;
import wiiuu.core.Config;

/** Standalone Choccy Milk's persistent keyboard map. F1 always opens Settings. */
public final class ChoccyKeys {
    private ChoccyKeys() {}
    public static Map<String, Integer> defaults() {
        Map<String, Integer> m = new LinkedHashMap<>();
        String[] actions = {"UP", "DOWN", "LEFT", "RIGHT", "A", "B", "X", "Y", "L", "R", "ZL", "ZR", "PLUS", "MINUS", "L3", "R3", "save", "load", "slotdown", "slotup", "quit", "fullscreen"};
        int[] keys = {KeyEvent.VK_UP, KeyEvent.VK_DOWN, KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT, KeyEvent.VK_X, KeyEvent.VK_Z,
            KeyEvent.VK_S, KeyEvent.VK_A, KeyEvent.VK_Q, KeyEvent.VK_W, KeyEvent.VK_E, KeyEvent.VK_R, KeyEvent.VK_ENTER,
            KeyEvent.VK_SPACE, 0, 0, KeyEvent.VK_F2, KeyEvent.VK_F4, KeyEvent.VK_F6, KeyEvent.VK_F7, KeyEvent.VK_ESCAPE, KeyEvent.VK_F11};
        for (int i = 0; i < actions.length; i++) m.put(actions[i], keys[i]);
        return m;
    }
    public static Map<String, Integer> load(Config config) {
        Map<String, Integer> m = defaults();
        m.replaceAll((a, key) -> config.getInt("choccy.keys." + a, key));
        return m;
    }
    public static void validate(Map<String, Integer> map) {
        Set<Integer> used = new HashSet<>();
        for (String action : defaults().keySet()) {
            Integer key = map.get(action);
            if (key == null || key < 0 || key == KeyEvent.VK_F1) throw new IllegalArgumentException("F1 is reserved for Settings; each action needs a valid key or Unbound");
            if (key != 0 && !used.add(key)) throw new IllegalArgumentException("Key assigned twice: " + KeyEvent.getKeyText(key));
        }
    }
    public static void save(Config c, Map<String, Integer> map) {
        validate(map);
        map.forEach((a, key) -> c.set("choccy.keys." + a, Integer.toString(key)));
        c.save();
    }
    public static String action(Config c, int key) {
        if (key == 0) return null;
        for (var e : load(c).entrySet()) if (e.getValue() == key) return e.getKey();
        if (key == KeyEvent.VK_SHIFT && c.get("choccy.keys.MINUS", null) == null) return "MINUS";
        return null;
    }
}
