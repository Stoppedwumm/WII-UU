package wiiuu.ui;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import wiiuu.core.Config;
import wiiuu.core.GameSystem;
import wiiuu.core.Systems;

/**
 * The themes WII-UU can use: the ones that come with it (resources/themes) and the user's own
 * .wtheme files in ~/.wiiuu/themes, which win when they share a name. ui.theme holds a theme's
 * id (its file name without .wtheme), or auto / light / dark for the built-in colours.
 */
public final class Themes {
    private Themes() {
    }

    /** A theme file: built in (file is null) or the user's. */
    public record Entry(String id, String name, Path file) {
        public boolean builtIn() {
            return file == null;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    public static final String EXTENSION = ".wtheme";

    public static Path folder(Config config) {
        return config.home().resolve("themes");
    }

    /** Every theme, built-in ones first, by name. */
    public static List<Entry> list(Config config) {
        Map<String, Entry> byId = new LinkedHashMap<>();
        for (String id : builtInIds()) {
            String text = builtIn(id);
            if (text != null) byId.put(id, new Entry(id, nameOr(text, id), null));
        }
        Path dir = folder(config);
        if (Files.isDirectory(dir)) {
            try (Stream<Path> files = Files.list(dir)) {
                for (Path f : files.sorted().toList()) {
                    String file = f.getFileName().toString();
                    if (!file.toLowerCase(Locale.ROOT).endsWith(EXTENSION) || !Files.isRegularFile(f)) continue;
                    String id = idOf(f);
                    String text;
                    try {
                        text = Files.readString(f, StandardCharsets.UTF_8);
                    } catch (IOException e) {
                        continue;
                    }
                    byId.put(id, new Entry(id, nameOr(text, id), f));
                }
            } catch (IOException ignored) {
                // no themes of their own, then
            }
        }
        return new ArrayList<>(byId.values());
    }

    /** The theme with this id, or null. */
    public static Entry find(Config config, String id) {
        if (id == null) return null;
        String want = id.trim().toLowerCase(Locale.ROOT);
        for (Entry e : list(config)) if (e.id.equals(want)) return e;
        return null;
    }

    public static String read(Entry e) throws IOException {
        if (e.file != null) return Files.readString(e.file, StandardCharsets.UTF_8);
        String text = builtIn(e.id);
        if (text == null) throw new IOException("theme " + e.id + " is missing from WII-UU");
        return text;
    }

    /** Works a theme's text out against WII-UU's own colours and consoles. */
    public static ThemeScript.Result evaluate(String source, String fallbackName, boolean systemDark) throws ThemeScript.Error {
        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        for (GameSystem s : Systems.ALL) ids.add(s.id());
        return ThemeScript.evaluate(source, fallbackName, MenuView::builtInColours,
                id -> Systems.byId(id).map(GameSystem::color).orElse(null), ids, systemDark);
    }

    static String idOf(Path file) {
        String n = file.getFileName().toString();
        return n.substring(0, n.length() - EXTENSION.length()).toLowerCase(Locale.ROOT);
    }

    private static String nameOr(String text, String id) {
        String n = ThemeScript.nameOf(text);
        return n != null && !n.isBlank() ? n : id;
    }

    private static List<String> builtInIds() {
        String index = resource("/themes/index.txt");
        if (index == null) return List.of();
        List<String> out = new ArrayList<>();
        for (String line : index.split("\n")) if (!line.isBlank() && !line.startsWith("#")) out.add(line.trim());
        return out;
    }

    private static String builtIn(String id) {
        return resource("/themes/" + id + EXTENSION);
    }

    private static String resource(String path) {
        try (InputStream in = Themes.class.getResourceAsStream(path)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Makes the themes folder ready for writing a theme: a commented starting point
     * (my-theme.wtheme, copied from the tutorial theme) and the full reference, REFERENCE.txt.
     */
    public static Path prepareFolder(Config config) throws IOException {
        Path dir = folder(config);
        Files.createDirectories(dir);
        Path start = dir.resolve("my-theme" + EXTENSION);
        String tutorial = builtIn("tutorial");
        if (!Files.exists(start) && tutorial != null) {
            Files.writeString(start, tutorial.replace("theme \"Tutorial\"", "theme \"My theme\"")
                    .replace("Copy this file, change things, save:", "Change things and save:"), StandardCharsets.UTF_8);
        }
        Files.writeString(dir.resolve("REFERENCE.txt"), reference(), StandardCharsets.UTF_8);
        return dir;
    }

    /** Every setting, function and console id, as plain text. */
    public static String reference() {
        StringBuilder b = new StringBuilder();
        b.append("""
                WII-UU themes - reference
                =========================

                A theme is a text file ending in .wtheme in this folder. Pick it in Settings (F1) >
                Look & sound > Theme. While it is the theme in use, WII-UU redraws as soon as you save the file.
                Check one from a terminal with:  wiiuu --theme-check FILE

                One statement per line. "# " or "//" starts a comment.

                  theme "Name"              what Settings calls it (default: the file name)
                  by "Who"                  who made it
                  base light|dark|auto      start from WII-UU's light or dark colours, or follow the
                                            computer's dark mode (auto); must come before any colour
                  let name = colour         a colour (or number) to use further down by its name
                  SETTING = colour          change one of the colours below
                  console ID = colour       a console's tile colour (IDs below)
                  consoles = colour         every console's tile colour; "it" is the console's own colour,
                                            e.g. consoles = desaturate(it, 40%)
                  font "Name", "Other"      the first of these fonts the computer has
                  when dark { ... }         only in dark mode (with base auto: when the computer is dark)
                  when light { ... }        only in light mode

                Colours: #RGB, #RRGGBB, #RRGGBBAA (the last two digits are the opacity), a let, any
                setting's current colour (text-dim = mix(text, card, 40%)), a console ID (accent = n64),
                none (invisible), or a name: white black transparent red orange yellow green teal cyan
                blue indigo purple pink brown grey.
                Numbers: 40% and 0.4 are the same; rgb() also takes 0-255, hsl() and spin() degrees.

                Settings
                --------
                """);
        for (Map.Entry<String, String> e : ThemeScript.PROPERTIES.entrySet())
            b.append(String.format(Locale.ROOT, "  %-18s %s%n", e.getKey(), e.getValue()));
        b.append(String.format(Locale.ROOT, "  %-18s %s%n", "background", "background-top and -bottom: a colour or gradient(top, bottom)"));
        b.append(String.format(Locale.ROOT, "  %-18s %s%n", "boot", "boot-top and -bottom: a colour or gradient(top, bottom)"));
        b.append("\nFunctions\n---------\n");
        for (String f : ThemeScript.FUNCTIONS.values()) b.append("  ").append(f).append('\n');
        b.append("\nConsole IDs\n-----------\n  ");
        int col = 2;
        for (GameSystem s : Systems.ALL) {
            if (col + s.id().length() > 90) {
                b.append("\n  ");
                col = 2;
            }
            b.append(s.id()).append(' ');
            col += s.id().length() + 1;
        }
        b.append("\n\nWhether a theme is dark (for the setup guide and other details) comes from its base, or,\n"
                + "once it changes colours, from how dark its background is.\n");
        return b.toString();
    }
}
