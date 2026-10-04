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
public final class Joysticks {
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
        for (Device d : devices(all)) if (d.ours() == wantOurs) out.add(d.name());
        return out;
    }

    /** A joystick: its name, its event and joystick device nodes (e.g. event5, js0), and whether it's a phone. */
    public record Device(String name, String event, String js, boolean ours) {}

    /** Every joystick now (none when unreadable, e.g. not Linux). */
    public static List<Device> list() {
        try {
            return devices(Files.readString(Path.of("/proc/bus/input/devices")));
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    static List<Device> devices(String all) {
        List<Device> out = new ArrayList<>();
        for (String block : all.split("\n\\s*\n")) {
            String name = null, sysfs = "", event = null, js = null;
            for (String line : block.split("\n")) {
                if (line.startsWith("N: Name=")) name = line.substring(8).replace("\"", "").trim();
                else if (line.startsWith("S: Sysfs=")) sysfs = line.substring(9).trim();
                else if (line.startsWith("H: Handlers=")) {
                    for (String h : line.substring(12).trim().split("\\s+")) {
                        if (h.matches("event\\d+")) event = h;
                        else if (h.matches("js\\d+")) js = h;
                    }
                }
            }
            if (name == null || js == null) continue;
            boolean ours = sysfs.startsWith("/devices/virtual/") && name.startsWith("Microsoft X-Box 360 pad");
            out.add(new Device(name, event, js, ours));
        }
        return out;
    }
}
