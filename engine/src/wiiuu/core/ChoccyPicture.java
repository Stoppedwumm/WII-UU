package wiiuu.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * How Choccy Milk draws a console's games: upscaling and widescreen, per console
 * ({@code choccy.<console>.upscale}, {@code choccy.<console>.widescreen}).
 *
 * <p>Choccy Milk draws without a graphics chip, so upscaling is one of
 * <ul>
 * <li>a picture upscaler, for any console: <b>smooth</b> (softened), or for 2D consoles
 * <b>scale2x</b> / <b>scale3x</b>, which round off the edges of pixel art while keeping it
 * crisp (the AdvMAME2x / 3x rules);</li>
 * <li>the emulator drawing at a higher resolution itself (<b>hd2</b>, <b>hd4</b>), where a core
 * can do that without a graphics chip: PlayStation (Beetle PSX's software renderer: sharper 3D).</li>
 * </ul>
 * Widescreen is real where a core can show more of the game: PlayStation (the widescreen hack
 * of SwanStation and Beetle PSX, for 3D games) and Mega Drive (Genesis Plus GX Wide); for the
 * others the picture is stretched to 16:9 (right for games with a widescreen mode of their own,
 * like some N64 ones).
 *
 * <p>Not bsnes-hd (HD Mode 7 and widescreen for the Super Nintendo): bsnes runs its chips on
 * 32 KB coroutine stacks, too small for the calls back into Choccy Milk (see {@link Cores#choccyCanRun}).
 */
public final class ChoccyPicture {
    private ChoccyPicture() {}

    /** Consoles whose games are pixel art (the pixel-art upscalers suit them). */
    private static final Set<String> PIXEL_ART = Set.of("nes", "snes", "gb", "gba", "sms", "genesis", "nds");
    /** Consoles where the emulator itself draws at a higher resolution. */
    private static final Set<String> HD = Set.of("ps1");
    /** Consoles where widescreen shows more of the game (else: stretched). */
    private static final Set<String> REAL_WIDE = Set.of("ps1", "genesis");

    /** The upscaling choices for a console, as {id, name for people}. */
    public static List<String[]> upscalers(String system) {
        List<String[]> out = new ArrayList<>();
        out.add(new String[]{"off", "Off (sharp pixels)"});
        out.add(new String[]{"smooth", "Smooth"});
        if (PIXEL_ART.contains(system)) {
            out.add(new String[]{"scale2x", "Pixel art 2x (Scale2x)"});
            out.add(new String[]{"scale3x", "Pixel art 3x (Scale3x)"});
        }
        if (HD.contains(system)) {
            out.add(new String[]{"hd2", "Internal resolution 2x"});
            out.add(new String[]{"hd4", "Internal resolution 4x"});
        }
        return out;
    }

    public static String upscale(Config config, String system) {
        String u = config.get("choccy." + system + ".upscale", "off").trim();
        for (String[] c : upscalers(system)) if (c[0].equals(u)) return u;
        return "off";
    }

    public static boolean widescreen(Config config, String system) {
        return config.getBool("choccy." + system + ".widescreen", false);
    }

    /** Whether widescreen shows more of the game on this console (false: the picture is stretched). */
    public static boolean realWidescreen(String system) {
        return REAL_WIDE.contains(system);
    }

    /** Whether the emulator itself draws bigger (hd2 / hd4). */
    public static boolean coreUpscale(Config config, String system) {
        return upscale(config, system).startsWith("hd");
    }

    /** The upscaler the picture gets on its way to the screen: off, smooth, scale2x, scale3x. */
    public static String filter(Config config, String system) {
        String u = upscale(config, system);
        return u.startsWith("hd") ? "smooth" : u;           // drawn bigger already: only smoothed to the screen
    }

    /** Whether the picture is stretched to 16:9 (widescreen on a console without real widescreen). */
    public static boolean stretch(Config config, String system) {
        return widescreen(config, system) && !REAL_WIDE.contains(system);
    }

    /**
     * The core the settings need, best first, or null when the usual ones do: Beetle PSX for
     * PlayStation upscaling, Genesis Plus GX Wide for Mega Drive widescreen.
     */
    public static List<String> cores(Config config, String system) {
        boolean wide = widescreen(config, system), hd = coreUpscale(config, system);
        return switch (system) {
            case "ps1" -> hd ? List.of("mednafen_psx", "mednafen_psx_hw") : null;     // SwanStation does widescreen too
            case "genesis" -> wide ? List.of("genesis_plus_gx_wide") : null;
            default -> null;
        };
    }

    /** The core options for these settings (for every core they may end up with). */
    public static Map<String, String> options(Config config, String system) {
        Map<String, String> o = new LinkedHashMap<>();
        boolean wide = widescreen(config, system);
        String u = upscale(config, system);
        String scale = u.equals("hd4") ? "4" : u.equals("hd2") ? "2" : "1";
        switch (system) {
            case "ps1" -> {
                for (String p : new String[]{"beetle_psx_", "beetle_psx_hw_"}) {
                    o.put(p + "internal_resolution", scale.equals("1") ? "1x(native)" : scale + "x");
                    o.put(p + "widescreen_hack", wide ? "enabled" : "disabled");
                    o.put(p + "widescreen_hack_aspect_ratio", "16:9");
                }
                o.put("beetle_psx_hw_renderer", "software");         // no graphics chip: its software renderer upscales
                o.put("swanstation_GPU_WidescreenHack", wide ? "true" : "false");
                o.put("swanstation_Display_AspectRatio", wide ? "16:9" : "4:3");
            }
            default -> { }
        }
        return o;
    }

    /** One line for people: "Pixel art 3x · widescreen (stretched)". */
    public static String describe(Config config, String system) {
        String u = upscale(config, system), name = "Off";
        for (String[] c : upscalers(system)) if (c[0].equals(u)) name = c[1];
        if (!widescreen(config, system)) return name;
        return name + " · widescreen" + (realWidescreen(system) ? "" : " (stretched)");
    }
}
