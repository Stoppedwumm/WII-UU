package wiiuu.core;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Scans each system's ROM folder and keeps an immutable snapshot of what it found. */
public final class Library {
    /** Folder names that never make a good game title (PS3/PS4/Wii U layouts). */
    private static final Set<String> GENERIC_DIRS = Set.of(
            "code", "content", "meta", "usrdir", "ps3_game", "sce_sys", "app0", "game", "roms");
    private static final int MAX_DEPTH = 6;

    public record Snapshot(int version, Map<GameSystem, List<Game>> games) {
        public List<Game> of(GameSystem s) {
            return games.getOrDefault(s, List.of());
        }

        public int total() {
            return games.values().stream().mapToInt(List::size).sum();
        }

        public Optional<Game> find(String systemId, String gameId) {
            for (var e : games.entrySet()) {
                if (!e.getKey().id().equals(systemId)) continue;
                for (Game g : e.getValue()) if (g.id().equals(gameId)) return Optional.of(g);
            }
            return Optional.empty();
        }
    }

    private final Config config;
    private volatile Snapshot snapshot = new Snapshot(0, Map.of());
    private final List<Consumer<Snapshot>> listeners = new CopyOnWriteArrayList<>();

    public Library(Config config) {
        this.config = config;
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    public void addListener(Consumer<Snapshot> l) {
        listeners.add(l);
    }

    /** Rescans in the background and notifies listeners when done. */
    public void rescanAsync() {
        Thread t = new Thread(this::rescan, "library-scan");
        t.setDaemon(true);
        t.start();
    }

    public synchronized Snapshot rescan() {
        Map<GameSystem, List<Game>> found = new LinkedHashMap<>();
        for (GameSystem s : Systems.ALL) {
            Path dir = config.romDir(s);
            try {
                Files.createDirectories(dir);
            } catch (IOException | SecurityException ignored) {
                // read-only or unreachable folder: just show zero games
            }
            found.put(s, scan(s, dir));
        }
        snapshot = new Snapshot(snapshot.version() + 1, Map.copyOf(found));
        for (var l : listeners) l.accept(snapshot);
        return snapshot;
    }

    static List<Game> scan(GameSystem system, Path dir) {
        List<Game> games = new ArrayList<>();
        if (!Files.isDirectory(dir)) return games;
        try {
            Files.walkFileTree(dir, EnumSet.of(FileVisitOption.FOLLOW_LINKS), MAX_DEPTH, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
                    String n = d.getFileName() == null ? "" : d.getFileName().toString();
                    if (!d.equals(dir) && (n.startsWith(".") || n.equalsIgnoreCase("covers"))) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path f, BasicFileAttributes attrs) {
                    String n = f.getFileName().toString();
                    if (!n.startsWith(".") && system.matches(n)) {
                        games.add(toGame(system, dir, f));
                        // one marker per game folder (e.g. PS3 EBOOT.BIN); skip siblings
                        if (system.markers().contains(n.toLowerCase(Locale.ROOT))) {
                            return FileVisitResult.SKIP_SIBLINGS;
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path f, IOException e) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            System.err.println("[library] scan failed for " + dir + ": " + e.getMessage());
        }
        dedupeDiscImages(games);
        games.sort(Comparator.comparing(g -> g.name().toLowerCase(Locale.ROOT)));
        return games;
    }

    /** When an .m3u playlist exists, hide the individual discs it lists. */
    private static void dedupeDiscImages(List<Game> games) {
        Set<Path> referenced = new java.util.HashSet<>();
        for (Game g : games) {
            if (!g.path().toString().toLowerCase(Locale.ROOT).endsWith(".m3u")) continue;
            try {
                for (String line : Files.readAllLines(g.path())) {
                    line = line.trim();
                    if (!line.isEmpty() && !line.startsWith("#")) {
                        referenced.add(g.path().resolveSibling(line).normalize());
                    }
                }
            } catch (IOException ignored) {
                // unreadable playlist: keep everything
            }
        }
        games.removeIf(g -> referenced.contains(g.path().normalize()));
    }

    static Game toGame(GameSystem system, Path root, Path file) {
        String fileName = file.getFileName().toString();
        String base = stripExtension(fileName);
        Path titleSource = file;
        boolean generic = system.markers().contains(fileName.toLowerCase(Locale.ROOT))
                || fileName.toLowerCase(Locale.ROOT).endsWith(".rpx");
        if (generic) {
            // walk up past folders like USRDIR / PS3_GAME / code to the game's own folder
            Path p = file.getParent();
            while (p != null && !p.equals(root) && GENERIC_DIRS.contains(p.getFileName().toString().toLowerCase(Locale.ROOT))) {
                p = p.getParent();
            }
            if (p != null && !p.equals(root)) {
                titleSource = p;
                base = p.getFileName().toString();
            }
        }
        return new Game(system, cleanTitle(base), file, coverCandidates(root, file, titleSource, stripExtension(fileName)));
    }

    private static List<Path> coverCandidates(Path root, Path file, Path titleSource, String base) {
        List<Path> c = new ArrayList<>();
        String titleBase = titleSource == file ? base : titleSource.getFileName().toString();
        for (String ext : new String[]{"png", "jpg", "jpeg"}) {
            c.add(root.resolve("covers").resolve(titleBase + "." + ext));
            c.add(file.resolveSibling(base + "." + ext));
        }
        if (titleSource != file) {
            // PS3: PS3_GAME/ICON0.PNG, PS4: sce_sys/icon0.png, Wii U: meta/iconTex.png (if converted)
            Path g = titleSource;
            c.add(g.resolve("PS3_GAME").resolve("ICON0.PNG"));
            c.add(g.resolve("ICON0.PNG"));
            c.add(g.resolve("sce_sys").resolve("icon0.png"));
            c.add(g.resolve("meta").resolve("iconTex.png"));
        }
        return c;
    }

    static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** "Super Mario Bros. (USA) [!]" -> "Super Mario Bros." */
    static String cleanTitle(String raw) {
        String s = raw.replace('_', ' ');
        s = s.replaceAll("\\s*\\[[^\\]]*\\]", "");
        s = s.replaceAll("\\s*\\((?!Disc)[^)]*\\)", "");
        s = s.replaceAll("\\s+", " ").trim();
        return s.isEmpty() ? raw : s;
    }
}
