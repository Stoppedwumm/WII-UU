package wiiuu.android;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The games in the WII-UU folder, per console (roms/&lt;console&gt;, and folders inside it). */
final class Library {
    static final class Game {
        final Consoles.Console console;
        final File file;
        final String name;

        Game(Consoles.Console console, File file) {
            this.console = console;
            this.file = file;
            this.name = file.getName().replaceFirst("\\.[^.]+$", "");
        }

        /** Its cover: covers/&lt;name&gt;.png or .jpg in the console's folder (as WII-UU on a computer keeps them), or null. */
        File cover(Context c) {
            File dir = new File(Store.roms(c, console.id), "covers");
            for (String ext : new String[]{".png", ".jpg", ".jpeg"}) {
                File f = new File(dir, name + ext);
                if (f.isFile()) return f;
            }
            return null;
        }
    }

    private final Map<String, List<Game>> games = new HashMap<>();

    synchronized List<Game> games(String console) {
        List<Game> g = games.get(console);
        return g == null ? Collections.<Game>emptyList() : g;
    }

    /** Looks through the folders (on a background thread: big folders take a moment). */
    void scan(Context c) {
        Map<String, List<Game>> found = new HashMap<>();
        for (Consoles.Console k : Consoles.ALL) {
            List<Game> list = new ArrayList<>();
            walk(Store.roms(c, k.id), k, list, 0);
            // a .m3u (several discs) stands for the discs it lists
            List<Game> m3u = new ArrayList<>();
            for (Game g : list) if (g.file.getName().toLowerCase(Locale.ROOT).endsWith(".m3u")) m3u.add(g);
            if (!m3u.isEmpty()) list.removeIf(g -> !m3u.contains(g) && inPlaylist(m3u, g.file));
            Collections.sort(list, (a, b) -> a.name.compareToIgnoreCase(b.name));
            found.put(k.id, list);
        }
        synchronized (this) {
            games.clear();
            games.putAll(found);
        }
    }

    private static boolean inPlaylist(List<Game> m3u, File f) {
        for (Game g : m3u) {
            try {
                for (String line : java.nio.file.Files.readAllLines(g.file.toPath())) {
                    if (new File(g.file.getParentFile(), line.trim()).equals(f)) return true;
                }
            } catch (Exception ignored) {
                // not readable: list both
            }
        }
        return false;
    }

    private static void walk(File dir, Consoles.Console k, List<Game> out, int depth) {
        File[] files = dir.listFiles();
        if (files == null || depth > 3) return;
        for (File f : files) {
            String n = f.getName();
            if (n.startsWith(".") || n.equals("covers")) continue;
            if (f.isDirectory()) {
                walk(f, k, out, depth + 1);
                continue;
            }
            int dot = n.lastIndexOf('.');
            if (dot < 0) continue;
            if (k.extensions.contains(n.substring(dot + 1).toLowerCase(Locale.ROOT))) out.add(new Game(k, f));
        }
    }

    synchronized int count(String console) {
        return games(console).size();
    }
}
