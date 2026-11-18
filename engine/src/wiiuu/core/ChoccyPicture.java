package wiiuu.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * How Choccy Milk draws a console's games: upscaling and widescreen, per console
 * ({@code choccy.<console>.upscale}, {@code choccy.<console>.widescreen}).
 *
 * <p>Upscaling is one of
 * <ul>
 * <li>a picture upscaler, for any console: <b>smooth</b> (softened), or for 2D consoles
 * <b>scale2x</b> / <b>scale3x</b>, which round off the edges of pixel art while keeping it
 * crisp (the AdvMAME2x / 3x rules);</li>
 * <li>the emulator drawing at a higher resolution itself (<b>hd2</b>, <b>hd4</b>), where a core
 * can do that without a graphics chip: PlayStation (Beetle PSX's software renderer: sharper 3D).</li>
 * </ul>
 * Widescreen is real where a core can show more of the game: PlayStation (the widescreen hack
 * of SwanStation and Beetle PSX, for 3D games) and Mega Drive (Genesis Plus GX Wide); for the
 * N64 on Windows and Linux uses GLideN64 to render a wider view. Other consoles can stretch
 * to 16:9 for games with their own widescreen mode.
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
            out.add(new String[]{"xbr2", "Rounded 2x (xBR)"});
            out.add(new String[]{"xbr4", "Rounded 4x (xBR)"});
        }
        out.add(new String[]{"crt", "CRT TV (scanlines)"});
        if (n64OpenGlAvailable(system)) {
            out.add(new String[]{"n64-540", "N64 540p wide / 480p 4:3 · clean sharpening"});
            out.add(new String[]{"n64-720", "N64 720p · clean sharpening"});
            out.add(new String[]{"n64-1080", "N64 1080p · clean sharpening"});
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

    /** Whether each frame is blended into the one before (sprites games flicker for see-through effects look right). */
    public static boolean blend(Config config, String system) {
        return config.getBool("choccy." + system + ".blend", false);
    }

    public static boolean widescreen(Config config, String system) {
        return config.getBool("choccy." + system + ".widescreen", false);
    }

    /** Whether widescreen shows more of the game on this console (false: the picture is stretched). */
    public static boolean realWidescreen(String system) {
        return REAL_WIDE.contains(system) || n64OpenGlAvailable(system);
    }

    public static boolean n64OpenGlAvailable(String system) {
        if (!system.equals("n64") || ChoccyPicture.class.getResource("/wiiuu/choccy/OpenGl.class") == null) return false;
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = Choccy.arch();
        return os.contains("win") && arch.equals("x64")
                || os.contains("linux") && (arch.equals("x64") || arch.equals("arm64"));
    }

    /** Whether the selected N64 picture needs GLideN64. */
    public static boolean n64OpenGl(Config config, String system) {
        return n64OpenGlAvailable(system) && (widescreen(config, system) || upscale(config, system).startsWith("n64-"));
    }

    /** Whether the emulator itself draws bigger. */
    public static boolean coreUpscale(Config config, String system) {
        String u = upscale(config, system);
        return u.startsWith("hd") || u.startsWith("n64-");
    }

    /** The upscaler the picture gets on its way to the screen: off, smooth, scale2x, scale3x, xbr2, xbr4, crt. */
    public static String filter(Config config, String system) {
        String u = upscale(config, system);
        if (u.startsWith("n64-")) return "sharp";
        return u.startsWith("hd") ? "smooth" : u;
    }

    /** Whether the picture is stretched to 16:9 (widescreen on a console without real widescreen). */
    public static boolean stretch(Config config, String system) {
        return widescreen(config, system) && !realWidescreen(system);
    }

    /**
     * The core the settings need, best first, or null when the usual ones do: Beetle PSX for
     * PlayStation upscaling, Genesis Plus GX Wide for Mega Drive widescreen, and Mupen64Plus-Next for N64 HD.
     */
    public static List<String> cores(Config config, String system) {
        boolean wide = widescreen(config, system), hd = coreUpscale(config, system);
        return switch (system) {
            case "ps1" -> hd ? List.of("mednafen_psx", "mednafen_psx_hw") : null;     // SwanStation does widescreen too
            case "genesis" -> wide ? List.of("genesis_plus_gx_wide") : null;
            case "n64" -> n64OpenGl(config, system) ? List.of("mupen64plus_next") : null;
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
            case "n64" -> {
                if (n64OpenGl(config, system)) {
                    o.put("wiiuu.n64OpenGl", "true");
                    o.put("mupen64plus-rdp-plugin", "gliden64");
                    o.put("mupen64plus-rsp-plugin", "hle");
                    o.put("mupen64plus-aspect", wide ? "16:9 adjusted" : "4:3");
                    o.put("mupen64plus-169screensize", switch (u) {
                        case "n64-540" -> "960x540";
                        case "n64-1080" -> "1920x1080";
                        default -> "1280x720";
                    });
                    o.put("mupen64plus-43screensize", switch (u) {
                        case "n64-540" -> "640x480";
                        case "n64-1080" -> "1440x1080";
                        default -> "960x720";
                    });
                    o.put("mupen64plus-EnableNativeResFactor", "0");
                    o.put("mupen64plus-EnableFBEmulation", "True");
                }
                o.put("wiiuu.audioResampler", "sinc");
            }
            default -> { }
        }
        return o;
    }

    /** One line for people: "Pixel art 3x · widescreen (stretched)". */
    public static String describe(Config config, String system) {
        String u = upscale(config, system), name = "Off";
        for (String[] c : upscalers(system)) if (c[0].equals(u)) name = c[1];
        if (blend(config, system)) name += " · blended";
        if (!widescreen(config, system)) return name;
        return name + " · widescreen" + (realWidescreen(system) ? "" : " (stretched)");
    }
}
