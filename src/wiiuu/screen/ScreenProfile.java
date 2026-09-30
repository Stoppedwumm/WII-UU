package wiiuu.screen;

import java.awt.Rectangle;
import java.util.Locale;

import wiiuu.core.Config;
import wiiuu.core.GameSystem;

/**
 * Where a system's "second screen" lives on the PC, so it can be streamed to the phone:
 * a window (by title), the aspect ratio of what the emulator draws inside it (letterboxed),
 * and the part of that picture that is the second screen.
 *
 * <p>Config keys (all optional): {@code screen.<system>.window} (title regex),
 * {@code screen.<system>.aspect} (e.g. {@code 256:384}), {@code screen.<system>.region}
 * ({@code x,y,w,h} as fractions of the picture), {@code screen.<system>.label}. In RetroArch mode
 * the same keys with {@code retroarch.} in front of the last part apply
 * ({@code screen.nds.retroarch.region}...), since the game then runs inside RetroArch's window.
 */
public record ScreenProfile(String label, String windowRegex, double aspect, double rx, double ry, double rw, double rh) {

    /** Built-in defaults for emulators WII-UU installs; null when a system has no second screen. */
    private static ScreenProfile builtin(String systemId) {
        return switch (systemId) {
            // Cemu: View > "Separate GamePad view" opens a window with this title; 16:9 picture
            case "wiiu" -> new ScreenProfile("GamePad", "GamePad View", 16.0 / 9.0, 0, 0, 1, 1);
            // melonDS default vertical layout: two 256x192 screens stacked, bottom one is the touchscreen
            case "nds" -> new ScreenProfile("Touch screen", "melonDS", 256.0 / 384.0, 0, 0.5, 1, 0.5);
            // Azahar default layout: 400x240 top screen above a centred 320x240 bottom screen
            case "3ds" -> new ScreenProfile("Touch screen", "Azahar|Citra", 400.0 / 480.0, 40.0 / 400, 0.5, 320.0 / 400, 0.5);
            default -> null;
        };
    }

    /**
     * RetroArch mode: the DS and 3DS cores draw both screens into RetroArch's own window, top screen
     * above the touch screen (their default layouts), and RetroArch keeps the core's aspect ratio
     * (WII-UU's retroarch.cfg makes sure of that).
     */
    private static ScreenProfile builtinRetroArch(String systemId) {
        return switch (systemId) {
            // melonDS DS / melonDS / DeSmuME: 256x192 top screen above the 256x192 touch screen
            case "nds" -> new ScreenProfile("Touch screen", "^RetroArch", 256.0 / 384.0, 0, 0.5, 1, 0.5);
            // Citra: 400x240 top screen above a centred 320x240 bottom screen
            case "3ds" -> new ScreenProfile("Touch screen", "^RetroArch", 400.0 / 480.0, 40.0 / 400, 0.5, 320.0 / 400, 0.5);
            default -> null;
        };
    }

    public static ScreenProfile forSystem(Config config, GameSystem s) {
        return forSystem(config, s, false);
    }

    /** @param retroArch whether the game runs in RetroArch (RetroArch mode) */
    public static ScreenProfile forSystem(Config config, GameSystem s, boolean retroArch) {
        String id = s.id(), k = "screen." + id + (retroArch ? ".retroarch." : ".");
        ScreenProfile def = retroArch ? builtinRetroArch(id) : builtin(id);
        String window = config.get(k + "window", def == null ? null : def.windowRegex);
        if (window == null || window.isBlank()) return null;
        String label = config.get(k + "label", def == null ? "Second screen" : def.label);
        double aspect = parseAspect(config.get(k + "aspect", null), def == null ? 0 : def.aspect);
        double[] r = parseRegion(config.get(k + "region", null),
                def == null ? new double[]{0, 0, 1, 1} : new double[]{def.rx, def.ry, def.rw, def.rh});
        return new ScreenProfile(label, window, aspect, r[0], r[1], r[2], r[3]);
    }

    /** Maps the window rectangle to the on-screen rectangle of the second screen. */
    public Rectangle locate(Rectangle window) {
        double x = window.x, y = window.y, w = window.width, h = window.height;
        if (aspect > 0) {                      // the emulator letterboxes its picture inside the window
            if (w / h > aspect) {
                double cw = h * aspect;
                x += (w - cw) / 2;
                w = cw;
            } else {
                double ch = w / aspect;
                y += (h - ch) / 2;
                h = ch;
            }
        }
        return new Rectangle((int) Math.round(x + rx * w), (int) Math.round(y + ry * h),
                (int) Math.round(rw * w), (int) Math.round(rh * h));
    }

    private static double parseAspect(String s, double def) {
        if (s == null || s.isBlank()) return def;
        try {
            String[] p = s.trim().toLowerCase(Locale.ROOT).split("[:/x]");
            return p.length == 2 ? Double.parseDouble(p[0]) / Double.parseDouble(p[1]) : Double.parseDouble(p[0]);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static double[] parseRegion(String s, double[] def) {
        if (s == null || s.isBlank()) return def;
        try {
            String[] p = s.split(",");
            if (p.length != 4) return def;
            double[] r = new double[4];
            for (int i = 0; i < 4; i++) r[i] = Double.parseDouble(p[i].trim());
            return r;
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
