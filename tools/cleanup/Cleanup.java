package wiiuu.cleanup;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Standalone cleaner for staging directories created by Updater.java and emulators.sh. */
public final class Cleanup {
    private static final Pattern UPDATE_STAGE = Pattern.compile("wiiuu-(?:(?:upgrade|actions)-[0-9]+|(?:web|app)\\.[A-Za-z0-9]+)");
    private static final Set<String> EMULATORS = Set.of("fceux", "snes9x", "mgba", "mupen64plus",
            "mednafen", "melonds", "ppsspp", "flycast", "dolphin", "azahar", "cemu", "ryujinx",
            "duckstation", "rpcs3", "shadps4", "pcsx2");
    private static final LinkOption NOFOLLOW = LinkOption.NOFOLLOW_LINKS;

    public static void main(String[] args) {
        int status = run(args, System.out, System.err);
        if (status != 0) System.exit(status);
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        boolean apply = false;
        List<Path> tempRoots = new ArrayList<>();
        Path emulatorRoot = defaultEmulatorRoot();
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--help", "-h" -> {
                        out.println("""
                                WII-UU Cleanup (Java 17+)
                                Usage: java -jar WII-UU-Cleanup.jar [--apply] [--temp-dir DIR] [--emu-root DIR]
                                  Preview is the default. Close WII-UU and its installers before --apply.
                                  A staging folder and everything in it must be at least 24 hours old.
                                  --temp-dir DIR  Override the system temp roots. May be repeated.
                                  --emu-root DIR  Root used by emulators.sh --prefix (above emulators/).
                                """);
                        return 0;
                    }
                    case "--apply" -> apply = true;
                    case "--temp-dir" -> tempRoots.add(pathArgument(args, ++i));
                    case "--emu-root" -> emulatorRoot = pathArgument(args, ++i);
                    default -> throw new IllegalArgumentException("Unknown option " + args[i]);
                }
            }
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage() + ". See --help.");
            return 2;
        }

        if (tempRoots.isEmpty()) {
            Path user = Path.of(System.getProperty("user.home"));
            String data = System.getenv("WIIUU_HOME");
            Path link = user.resolve(".wiiuu-location");
            if ((data == null || data.isBlank()) && Files.isRegularFile(link)) {
                try { data = Files.readString(link).replaceFirst("\\r?\\n$", ""); }
                catch (IOException e) { err.println("Could not read " + link + ": " + e.getMessage()); }
            }
            if (data != null && !data.isBlank()) tempRoots.add(Path.of(data).resolve("tmp"));
            tempRoots.add(user.resolve(".wiiuu/tmp"));
            tempRoots.add(Path.of(System.getProperty("java.io.tmpdir")));
            for (String name : List.of("TMPDIR", "TEMP", "TMP")) {
                String value = System.getenv(name);
                if (value != null && !value.isBlank()) tempRoots.add(Path.of(value));
            }
        }

        var cutoff = FileTime.from(Instant.now().minus(Duration.ofHours(24)));
        var candidates = new LinkedHashSet<Path>();
        boolean failed = false;
        for (Path root : tempRoots) failed |= !find(root, true, candidates, err);
        if (emulatorRoot != null) {
            failed |= !find(emulatorRoot.resolve("emulators"), false, candidates, err);
        }
        long bytes = 0;
        int count = 0;
        for (Path candidate : candidates) {
            try {
                Map<Path, Entry> entries = inspect(candidate, cutoff);
                if (apply) {
                    if (inUse(candidate)) throw new Skip("referenced by a running process");
                    delete(candidate, entries, cutoff);
                }
                long size = entries.values().stream().mapToLong(Entry::bytes).sum();
                out.printf("%s %s (%d bytes)%n", apply ? "Removed" : "Would remove", candidate, size);
                bytes += size;
                count++;
            } catch (Skip e) {
                out.println("Skipped " + candidate + " (" + e.getMessage() + ")");
            } catch (IOException e) {
                err.println("Could not " + (apply ? "clean " : "inspect ") + candidate + ": " + e.getMessage());
                failed = true;
            }
        }
        out.printf("%d staging folder(s), %d bytes %s.%n", count, bytes,
                apply ? "removed" : "eligible for removal");
        if (!apply && count > 0) out.println("Close WII-UU and its installers, then rerun with --apply to delete.");
        return failed ? 1 : 0;
    }

    private static Path pathArgument(String[] args, int index) {
        if (index >= args.length || args[index].isBlank() || args[index].startsWith("--")) {
            throw new IllegalArgumentException("Missing path for " + args[index - 1]);
        }
        return Path.of(args[index]);
    }

    private static Path defaultEmulatorRoot() {
        String override = System.getenv("WIIUU_EMU_ROOT");
        if (override != null && !override.isBlank()) return Path.of(override);
        if (!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("linux")) return null;
        String xdg = System.getenv("XDG_DATA_HOME");
        Path data = xdg == null || xdg.isBlank()
                ? Path.of(System.getProperty("user.home"), ".local", "share") : Path.of(xdg);
        return data.resolve("wiiuu-emulators");
    }

    private static boolean find(Path root, boolean temp, Set<Path> candidates, PrintStream err) {
        try {
            Path realRoot = root.toRealPath();
            try (var children = Files.newDirectoryStream(realRoot)) {
                for (Path child : children) {
                    String name = child.getFileName().toString();
                    boolean match = temp ? UPDATE_STAGE.matcher(name).matches()
                            : name.endsWith(".new") && EMULATORS.contains(name.substring(0, name.length() - 4));
                    if (match && Files.isDirectory(child, NOFOLLOW)) candidates.add(child);
                }
            }
        } catch (NoSuchFileException e) {
            return true; // Temp roots and the emulator install are optional.
        } catch (IOException | DirectoryIteratorException e) {
            err.println("Could not scan " + root + ": " + e.getMessage());
            return false;
        }
        return true;
    }

    private record Entry(FileTime modified, Object key, long bytes, boolean directory, boolean link) {
        static Entry of(BasicFileAttributes attrs) {
            return new Entry(attrs.lastModifiedTime(), attrs.fileKey(),
                    attrs.isRegularFile() ? attrs.size() : 0, attrs.isDirectory(), attrs.isSymbolicLink());
        }
    }

    private static final class Skip extends IOException {
        Skip(String reason) {
            super(reason);
        }
    }

    private static Map<Path, Entry> inspect(Path root, FileTime cutoff) throws IOException {
        Map<Path, Entry> entries = new LinkedHashMap<>();
        var store = Files.getFileStore(root.getParent());
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                // walkFileTree skips symlinks. Also reject directory redirects such as Windows junctions.
                if (!dir.equals(dir.toRealPath()) || !store.equals(Files.getFileStore(dir))) {
                    throw new Skip("redirected or mounted directory " + dir);
                }
                if (attrs.lastModifiedTime().compareTo(cutoff) > 0) throw new Skip("recent activity");
                return FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (file.equals(root) || attrs.isOther()) throw new Skip("unexpected file type " + file);
                if (attrs.lastModifiedTime().compareTo(cutoff) > 0) throw new Skip("recent activity");
                entries.put(file, Entry.of(attrs));
                return FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) throw exc;
                BasicFileAttributes attrs = Files.readAttributes(dir, BasicFileAttributes.class, NOFOLLOW);
                if (!attrs.isDirectory() || attrs.lastModifiedTime().compareTo(cutoff) > 0) {
                    throw new Skip("directory changed during scan");
                }
                entries.put(dir, Entry.of(attrs));
                return FileVisitResult.CONTINUE;
            }
        });
        return entries;
    }

    private static boolean inUse(Path root) {
        String path = root.toString();
        try (var processes = ProcessHandle.allProcesses()) {
            return processes.anyMatch(process -> process.info().commandLine()
                    .map(command -> command.contains(path)).orElse(false));
        }
    }

    private static void delete(Path root, Map<Path, Entry> entries, FileTime cutoff) throws IOException {
        if (!entries.equals(inspect(root, cutoff))) throw new Skip("changed during scan");
        // Entries are in post-order. A directory with an unscanned new child cannot be deleted.
        for (var item : entries.entrySet()) {
            Path path = item.getKey();
            Entry expected = item.getValue();
            Entry current = Entry.of(Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW));
            boolean unchanged = expected.directory()
                    ? current.directory() && Objects.equals(expected.key(), current.key()) && path.equals(path.toRealPath())
                    : expected.equals(current);
            if (!unchanged || !path.getParent().equals(path.getParent().toRealPath())) {
                throw new IOException("Path changed during cleanup: " + path);
            }
            Files.delete(path);
        }
    }
}
