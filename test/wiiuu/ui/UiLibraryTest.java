package wiiuu.ui;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/** Compile and run against only the standalone JAR, never the application classpath. */
public final class UiLibraryTest {
    public static void main(String[] args) throws Exception {
        for (boolean dark : new boolean[]{false, true}) {
            Map<String, Color> palette = MenuPalette.colours(dark);
            check(palette.keySet().equals(ThemeScript.PROPERTIES.keySet()), "complete palette");
            palette.clear();
            check(!MenuPalette.colours(dark).isEmpty(), "independent palette copies");
            ThemeScript.Result result = MenuPalette.evaluate("theme \"Shared\"\nbase auto\n"
                    + "accent = #123456\nwhen dark { card = #101010 }", "fallback", dark);
            check(result.name().equals("Shared") && result.dark() == dark && result.followsSystem(), "theme metadata");
            check(result.colours().get("accent").equals(new Color(0x123456)), "theme override");
            check(result.colours().get("card").equals(dark ? new Color(0x101010) : Color.WHITE), "conditional colour");
        }
        try {
            MenuPalette.evaluate("base light\naccemt = #ffffff", "invalid", false);
            throw new AssertionError("invalid theme accepted");
        } catch (ThemeScript.Error e) {
            check(e.line == 2 && e.column > 0, "theme error location");
        }
        // Existing bundled themes still work with application-supplied console colours.
        try (var themes = Files.list(Path.of("resources/themes"))) {
            for (Path theme : themes.filter(p -> p.toString().endsWith(".wtheme")).toList()) {
                for (boolean dark : new boolean[]{false, true}) {
                    ThemeScript.evaluate(Files.readString(theme), theme.toString(), MenuPalette::colours,
                            id -> Color.BLUE, Set.of("nes", "snes", "n64", "gc", "wii", "wiiu", "ds", "3ds", "gb", "gba"), dark);
                }
            }
        }
        check(MilkTheme.pick(null, true) == MilkTheme.COCOA, "automatic dark theme");
        check(MilkTheme.pick("light", true) == MilkTheme.MILK, "explicit light theme");
        check(MilkTheme.icons().size() == 7 && MilkTheme.icon(32).getWidth() == 32, "window icons");
        for (MilkTheme theme : new MilkTheme[]{MilkTheme.COCOA, MilkTheme.MILK}) {
            MilkIntro intro = new MilkIntro(theme, "Custom App", "Custom tagline", 1000);
            check(!intro.done(1000) && intro.done(4500), "intro lifetime");
            check(intro.cover(1000) == 1 && intro.cover(4500) == 0, "intro endpoints");
            check(intro.cover(4250) > 0 && intro.cover(4250) < 1, "intro fade");
            for (long elapsed : new long[]{0, 250, 700, 1800, 2600, 3250, 3500}) {
                BufferedImage image = new BufferedImage(800, 600, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = image.createGraphics();
                theme.paintPage(g, 800, 600, 70, 1, elapsed);
                theme.shadow(g, 40, 100, 500, 60, 20, 1);
                theme.pick(g, 40, 100, 500, 60, 1, true);
                Rectangle clip = new Rectangle(20, 20, 740, 540);
                g.setClip(clip);
                var transform = g.getTransform();
                var composite = g.getComposite();
                intro.paint(g, 800, 600, 1000 + elapsed);
                check(g.getClip().getBounds().equals(clip) && g.getTransform().equals(transform)
                        && g.getComposite().equals(composite), "intro preserves graphics state");
                MilkTheme.glass(g, 100, 100, 80, 120, .8f, 1, 1, .2f, 0,
                        theme.text, theme.milk, theme.choc, theme.chocLight);
                check(g.getClip().getBounds().equals(clip), "glass preserves clip");
                g.dispose();
                check(image.getRGB(10, 10) != 0, "page rendered");
            }
        }
        System.out.println("Standalone UI library checks passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
