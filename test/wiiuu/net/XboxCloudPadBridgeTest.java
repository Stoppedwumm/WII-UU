package wiiuu.net;

import java.util.List;
import java.util.Map;
import wiiuu.core.MiniJson;

/** WII-UU controller-manager inputs must survive Xbox slot and button conversion. */
public final class XboxCloudPadBridgeTest {
    private static void check(boolean condition, String why) {
        if (!condition) throw new AssertionError(why);
    }
    private static double number(Map<String, Object> frame, String key) {
        return ((Number) frame.get(key)).doubleValue();
    }
    public static void main(String[] args) {
        XboxCloudPadBridge bridge = new XboxCloudPadBridge();
        var p1 = new GamepadServer.Pad("phone1", 1, "Phone", false);
        var p2 = new GamepadServer.Pad("pad:controller", 2, "Controller", true);
        var list = List.of(p2,p1);

        bridge.input("pad:controller", 2, "b A 1\nb ZR 1\ns 0 0.5 -0.25");
        bridge.input("phone1", 1, "b B 1\ns 1 -0.75 0.1");
        var frames = bridge.snapshot(list);
        check(frames.size() == 2, "Both phone and physical controller must appear");
        check(number(frames.get(0), "GamepadIndex") == 0, "Phone keeps player 1 / Xbox slot 0");
        check(number(frames.get(1), "GamepadIndex") == 1, "Physical keeps player 2 / Xbox slot 1");
        check(number(frames.get(1), "B") == 1, "WII-UU east A is Xbox east B");
        check(number(frames.get(1), "RightTrigger") == 1, "ZR becomes Xbox right trigger");
        check(number(frames.get(1), "LeftThumbXAxis") == 0.5, "Left stick X forwarded");
        check(number(frames.get(1), "LeftThumbYAxis") == -0.25, "Left stick Y forwarded");
        check(number(frames.get(0), "A") == 1, "WII-UU south B is Xbox south A");
        check(number(frames.get(0), "RightThumbXAxis") == -0.75, "Phone right stick forwarded");

        bridge.input("phone1", 1, "b MINUS 1\nb PLUS 1");
        var guide = bridge.snapshot(list).get(0);
        check(number(guide, "Nexus") == 1 && number(guide, "View") == 0
                && number(guide, "Menu") == 0, "View+Menu becomes Xbox guide");
        bridge.input("phone1", 1, "b PLUS 0\nb MINUS 0");
        bridge.input("pad:controller", 2, "b A 0");
        check(number(bridge.snapshot(list).get(1), "B") == 0, "Button release forwarded");

        bridge.release("pad:controller");
        check(number(bridge.snapshot(list).get(1), "B") == 0, "Unplug clears held state");
        var moved = bridge.snapshot(List.of(new GamepadServer.Pad("phone1", 3, "Phone", false)));
        check(number(moved.get(0), "GamepadIndex") == 2, "Reassigned player uses new Xbox slot");
        check(bridge.snapshot(List.of(new GamepadServer.Pad("viewer", 0, "Viewer", false)))
                .isEmpty(), "Viewer input must never become a player");
        String serialized = XboxCloudPadBridge.json(frames);
        var parsed = MiniJson.array(MiniJson.parse(serialized));
        check(parsed.size() == 2, "Controller frames serialize as JSON");
        check(!serialized.contains("phone1") && !serialized.contains("controller"),
                "No controller names or IDs leak to Chromium");
        System.out.println("WII-UU Xbox controller manager forwarding passed");
    }
}
