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
        checkMenuHelpers();
        System.out.println("Standalone UI library checks passed");
    }

    private static void checkMenuHelpers() {
        UiFonts fonts = new UiFonts(java.awt.Font.SANS_SERIF);
        check(fonts.font(0, 16.01f) == fonts.font(0, 16.1f), "font size buckets");
        check(fonts.font(0, 0).getSize2D() == 8, "minimum font size");
        java.awt.Font old = fonts.font(0, 16);
        fonts.family(java.awt.Font.MONOSPACED);
        check(!fonts.font(0, 16).getFamily().equals(old.getFamily()), "family switch invalidates cache");
        check(new UiFonts(java.awt.Font.SANS_SERIF).font(0, 16).getFamily().equals(old.getFamily()), "font caches independent");
        check(UiFonts.choose("missing-font-13489").equals(java.awt.Font.SANS_SERIF), "font fallback");
        for (boolean dark : new boolean[]{false, true}) {
            Map<String, Color> palette = MenuPalette.colours(dark);
            BufferedImage image = MenuPainter.background(640, 400, palette);
            check(image.getWidth() == 640 && image.getRGB(0, 0) != image.getRGB(0, 399), "menu gradient");
            check(MenuPainter.background(0, -1, palette).getWidth() == 1, "minimum background dimensions");
            Graphics2D g = image.createGraphics();
            g.translate(3, 4);
            g.setClip(0, 0, 620, 380);
            g.setColor(Color.MAGENTA);
            g.setStroke(new java.awt.BasicStroke(3));
            var transform = g.getTransform();
            var clip = g.getClip().getBounds();
            var paint = g.getPaint();
            var stroke = g.getStroke();
            int x = 30;
            for (MenuPainter.Icon icon : MenuPainter.Icon.values()) {
                var box = new java.awt.geom.RoundRectangle2D.Float(x, 40, 80, 80, 16, 16);
                MenuPainter.shadow(g, box, 10, palette.get("shadow"), dark ? 3 : 1);
                MenuPainter.icon(g, icon, box.getBounds2D(), palette.get("icon"));
                x += 100;
            }
            MenuPainter.gamepad(g, 30, 160, 100, 70, palette.get("accent"));
            check(g.getTransform().equals(transform) && g.getClip().getBounds().equals(clip)
                    && g.getPaint().equals(paint) && g.getStroke().equals(stroke), "menu painters preserve graphics state");
            g.setFont(fonts.font(0, 16));
            var metrics = g.getFontMetrics();
            float width = metrics.stringWidth("A short label");
            check(UiText.ellipsize("Short", metrics, width).equals("Short"), "short text unchanged");
            check(UiText.ellipsize("Long label", metrics, 0).isEmpty(), "zero text width");
            check(UiText.ellipsize("Long label", metrics, 1).isEmpty(), "ellipsis cannot fit");
            for (String text : new String[]{"A label that is far too long", "A \uD83D\uDE80 journey across the stars"}) {
                String fitted = UiText.ellipsize(text, metrics, width);
                check(metrics.stringWidth(fitted) <= width, "fitted text width");
                for (int i = 0; i < fitted.length(); i++) {
                    if (Character.isHighSurrogate(fitted.charAt(i)))
                        check(++i < fitted.length() && Character.isLowSurrogate(fitted.charAt(i)), "complete surrogate pairs");
                }
                for (int limit : new int[]{1, 2, 3}) {
                    var lines = UiText.wrap(text, metrics, width, limit);
                    check(lines.size() <= limit, "line count limit");
                    for (String line : lines) check(metrics.stringWidth(line) <= width, "wrapped text width");
                }
            }
            check(UiText.wrap("words", metrics, width, 0).isEmpty(), "zero line limit");
            g.dispose();
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
