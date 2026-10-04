package wiiuu.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The joysticks Linux knows (/proc/bus/input/devices), split into WII-UU's own virtual ones (the
 * phones, made by vpad.py through uinput) and the others (real USB / Bluetooth pads). Emulators
 * number buttons the same way for both, but the virtual pad puts them by position like a Wii U
 * GamePad, so WII-UU binds the phones' layout itself only while no other joystick is there.
 */
final class Joysticks {
    private Joysticks() {}

    /** Names of joysticks that aren't WII-UU's (empty when unreadable, e.g. not Linux). */
    static List<String> others() {
        return read(false);
    }

    /** How many of WII-UU's virtual pads exist now. */
    static int own() {
        return read(true).size();
    }

    private static List<String> read(boolean ours) {
        try {
            return parse(Files.readString(Path.of("/proc/bus/input/devices")), ours);
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    static List<String> parse(String all, boolean wantOurs) {
        List<String> out = new ArrayList<>();
        for (String block : all.split("\n\\s*\n")) {
            String name = null, sysfs = "", handlers = "";
            for (String line : block.split("\n")) {
                if (line.startsWith("N: Name=")) name = line.substring(8).replace("\"", "").trim();
                else if (line.startsWith("S: Sysfs=")) sysfs = line.substring(9).trim();
                else if (line.startsWith("H: Handlers=")) handlers = " " + line.substring(12).trim() + " ";
            }
            if (name == null || !handlers.matches(".*\\sjs\\d+\\s.*")) continue;
            boolean ours = sysfs.startsWith("/devices/virtual/") && name.startsWith("Microsoft X-Box 360 pad");
            if (ours == wantOurs) out.add(name);
        }
        return out;
    }
}
