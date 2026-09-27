package wiiuu.core;

import java.awt.Color;
import java.util.Locale;
import java.util.Set;

/**
 * A console the launcher knows how to find games for and hand off to an emulator.
 *
 * @param id          short stable id, used for config keys and ROM folder names
 * @param name        display name
 * @param shortName   text drawn on the system tile
 * @param maker       manufacturer
 * @param year        release year (used for ordering)
 * @param rgb         brand color for the tile
 * @param extensions  lower-case file extensions that count as a game
 * @param markers     lower-case file names that mark a game folder (e.g. eboot.bin)
 * @param emulator    name of the default emulator
 * @param cmdLinux    default command on Linux; {rom} is replaced by the game path
 * @param cmdWindows  default command on Windows
 * @param cmdMac      default command on macOS
 */
public record GameSystem(
        String id,
        String name,
        String shortName,
        String maker,
        int year,
        int rgb,
        Set<String> extensions,
        Set<String> markers,
        String emulator,
        String cmdLinux,
        String cmdWindows,
        String cmdMac) {

    public Color color() {
        return new Color(rgb);
    }

    public String hexColor() {
        return String.format("#%06X", rgb & 0xFFFFFF);
    }

    public String defaultCommand() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return cmdWindows;
        if (os.contains("mac") || os.contains("darwin")) return cmdMac;
        return cmdLinux;
    }

    public boolean matches(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (markers.contains(lower)) return true;
        int dot = lower.lastIndexOf('.');
        return dot > 0 && extensions.contains(lower.substring(dot + 1));
    }
}
