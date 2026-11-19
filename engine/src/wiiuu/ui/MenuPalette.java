package wiiuu.ui;

import java.awt.Color;
import java.util.Map;

/** WII-UU's light and dark menu palettes, independent of any application or window. */
public final class MenuPalette {
    private MenuPalette() {}

    /** WII-UU's own colours, light or dark, by theme setting name (see ThemeScript.PROPERTIES). */
    public static Map<String, Color> colours(boolean darkTheme) {
        Map<String, Color> c = new java.util.LinkedHashMap<>();
        if (!darkTheme) {
            c.put("accent", new Color(0x00A8E8));
            c.put("text", new Color(0x3C4043));
            c.put("text-dim", new Color(0x8A9099));
            c.put("card", Color.WHITE);
            c.put("divider", new Color(0xD5D9DE));
            c.put("dot", new Color(0xC3C8CE));
            c.put("icon", new Color(0x6B7178));
            c.put("button", new Color(0xEEF0F2));
            c.put("background-top", new Color(0xF7F8FA));
            c.put("background-bottom", new Color(0xE2E6EA));
            c.put("stripes", new Color(0, 0, 0, 9));
            c.put("glow", new Color(255, 255, 255, 170));
            c.put("accent-glow", new Color(0, 168, 232, 26));
            c.put("shelf", new Color(255, 255, 255, 120));
            c.put("outline", new Color(0, 0, 0, 22));
            c.put("shadow", new Color(40, 50, 60));
            c.put("dim", new Color(235, 238, 241, 215));
            c.put("toast", new Color(40, 44, 50, 225));
            c.put("boot-top", Color.WHITE);
            c.put("boot-bottom", new Color(0xEEF2F5));
            c.put("boot-fade", new Color(0xF6F8FA));
        } else {
            c.put("accent", new Color(0x26B9F2));
            c.put("text", new Color(0xE6E9EC));
            c.put("text-dim", new Color(0x9AA3AD));
            c.put("card", new Color(0x262B31));
            c.put("divider", new Color(0x3C434B));
            c.put("dot", new Color(0x4A525B));
            c.put("icon", new Color(0xAEB6BF));
            c.put("button", new Color(0x343A42));
            c.put("background-top", new Color(0x1C2025));
            c.put("background-bottom", new Color(0x0F1113));
            c.put("stripes", new Color(255, 255, 255, 6));
            c.put("glow", new Color(255, 255, 255, 16));
            c.put("accent-glow", new Color(38, 185, 242, 30));
            c.put("shelf", new Color(255, 255, 255, 16));
            c.put("outline", new Color(255, 255, 255, 18));
            c.put("shadow", new Color(0, 0, 0));
            c.put("dim", new Color(12, 14, 16, 215));
            c.put("toast", new Color(58, 64, 72, 240));
            c.put("boot-top", new Color(0x1B1F24));
            c.put("boot-bottom", new Color(0x0F1113));
            c.put("boot-fade", new Color(0x16191D));
        }
        return c;
    }

    /** Evaluate a theme without console-specific colours, using the standard menu palettes. */
    public static ThemeScript.Result evaluate(String source, String fallbackName, boolean systemDark)
            throws ThemeScript.Error {
        return ThemeScript.evaluate(source, fallbackName, MenuPalette::colours,
                id -> null, java.util.Set.of(), systemDark);
    }
}
