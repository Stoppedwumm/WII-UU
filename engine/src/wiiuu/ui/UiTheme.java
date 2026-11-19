package wiiuu.ui;

import java.awt.Color;
import java.awt.Font;
import java.util.Map;
import java.util.Objects;

/** An immutable palette and font policy for the built-in Swing controls. */
public final class UiTheme {
    private final Map<String, Color> colours;
    private final UiFonts fonts;
    private final boolean dark;

    /** Create a theme from a complete MenuPalette/ThemeScript palette and a font family. */
    public UiTheme(Map<String, Color> colours, String family, boolean dark) {
        this.colours = Map.copyOf(colours);
        for (String key : ThemeScript.PROPERTIES.keySet())
            Objects.requireNonNull(this.colours.get(key), "Missing theme colour: " + key);
        fonts = new UiFonts(Objects.requireNonNull(family));
        this.dark = dark;
    }

    /** The built-in light appearance. */
    public static UiTheme light() { return builtIn(false); }
    /** The built-in dark appearance. No OS process is invoked. */
    public static UiTheme dark() { return builtIn(true); }
    private static UiTheme builtIn(boolean dark) {
        return new UiTheme(MenuPalette.colours(dark), UiFonts.choose("Nunito", "Segoe UI", "DejaVu Sans"), dark);
    }

    /** Use an evaluated .wtheme, including its font preferences. */
    public static UiTheme from(ThemeScript.Result result) {
        return new UiTheme(result.colours(), UiFonts.choose(result.fonts().toArray(String[]::new)), result.dark());
    }

    public Color colour(String role) { return Objects.requireNonNull(colours.get(role), "Unknown colour: " + role); }
    public Map<String, Color> colours() { return colours; }
    public Font font(int style, float size) { return fonts.font(style, size); }
    public boolean isDark() { return dark; }

    /** Readable foreground for an accent-filled control, including custom theme accents. */
    public Color onAccent() {
        Color c = colour("accent");
        // Compare contrast against black and white in linear light.
        double luminance = .2126 * linear(c.getRed()) + .7152 * linear(c.getGreen()) + .0722 * linear(c.getBlue());
        return (luminance + .05) / .05 > 1.05 / (luminance + .05) ? Color.BLACK : Color.WHITE;
    }

    private static double linear(int channel) {
        double v = channel / 255.0;
        return v <= .04045 ? v / 12.92 : Math.pow((v + .055) / 1.055, 2.4);
    }
}
