package wiiuu.core;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.*;

/** Copies app data and the standard ROM library into a new storage directory. */
public final class DataTransfer {
    private static final String LIBRARY = "migrated-roms";
    private static final String CUSTOM = "migrated-custom-roms";
    private static final String COMPLETE = ".wiiuu-transfer-complete";

    private DataTransfer() {}

    @FunctionalInterface
    private interface EntrySink {
        void accept(Path source, String name, boolean directory) throws IOException;
    }

    @FunctionalInterface
    private interface StageWriter {
        void write(Path stage) throws IOException;
    }

    public static void relocate(Config config, Path destination) throws IOException {
        Path target = target(destination);
        if (target.startsWith(config.home().toRealPath()) || inside(target, config.romBase()))
            throw new IOException("Choose a destination outside the current data and ROM folders.");
        for (GameSystem system : Systems.ALL) {
            if (inside(target, config.romDir(system)))
                throw new IOException("Choose a destination outside the current ROM folders.");
        }
        install(target, false, stage -> visit(config, false, (source, name, directory) -> {
            Path file = stage.resolve(name);
            if (directory) Files.createDirectories(file);
            else Files.copy(source, file, StandardCopyOption.COPY_ATTRIBUTES);
        }));
    }

    private static boolean inside(Path target, Path source) throws IOException {
        return Files.exists(source) && target.startsWith(source.toRealPath());
    }

    /** Writes a streaming ZIP without making a second local copy of the data. */
    public static void send(Config config, OutputStream out) throws IOException {
        // The caller owns the transport so it can receive an acknowledgement after the ZIP.
        try (var zip = new ZipOutputStream(new FilterOutputStream(out) {
            @Override public void close() throws IOException { flush(); }
        })) {
            zip.setLevel(Deflater.BEST_SPEED);
            visit(config, true, (source, name, directory) -> {
                zip.putNextEntry(new ZipEntry(name + (directory ? "/" : "")));
                if (!directory) Files.copy(source, zip);
                zip.closeEntry();
            });
            zip.putNextEntry(new ZipEntry(COMPLETE));
            zip.closeEntry();
        }
    }

    public static void receive(InputStream in, Path destination) throws IOException {
        install(target(destination), true, stage -> {
            try (var zip = new ZipInputStream(new FilterInputStream(in) {
                @Override public void close() { }
            })) {
                for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                    String name = entry.getName();
                    if (COMPLETE.equals(name) && !entry.isDirectory()) {
                        zip.closeEntry();
                        in.transferTo(OutputStream.nullOutputStream());
                        return;
                    }
                    if (name.indexOf('\\') >= 0 || name.indexOf(':') >= 0)
                        throw new IOException("Invalid migration entry");
                    Path file = stage.resolve(name).normalize();
                    if (!file.startsWith(stage) || file.equals(stage)) throw new IOException("Invalid migration entry");
                    if (networkExcluded(stage.relativize(file).toString().replace(File.separatorChar, '/'))) continue;
                    if (entry.isDirectory()) Files.createDirectories(file);
                    else {
                        Files.createDirectories(file.getParent());
                        Files.copy(zip, file);
                    }
                }
            }
            throw new EOFException("Transfer ended before all data arrived.");
        });
    }

    private static Path target(Path path) throws IOException {
        if (System.getenv("WIIUU_HOME") != null && !System.getenv("WIIUU_HOME").isBlank())
            throw new IOException("Unset WIIUU_HOME before changing the data location.");
        Path absolute = path.toAbsolutePath().normalize();
        if (absolute.getParent() == null) throw new IOException("Choose a new folder inside the drive.");
        Path target = absolute.getParent().toRealPath().resolve(absolute.getFileName());
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Choose a folder name that does not exist yet.");
        return target;
    }

    private static void install(Path target, boolean network, StageWriter writer) throws IOException {
        Path stage = Files.createTempDirectory(target.getParent(), ".wiiuu-transfer-");
        try {
            writer.write(stage);
            Config relocated = new Config(stage);
            if (network) {
                relocated.set("peer.id", null);
                for (GameSystem system : Systems.ALL) relocated.set("system." + system.id() + ".romdir", null);
            }
            relocated.set("roms.base", target.resolve(LIBRARY).toString());
            relocated.saveChecked();
            Files.move(stage, target);
            Config.selectHome(target);
        } finally {
            if (Files.exists(stage)) {
                try (var paths = Files.walk(stage)) {
                    for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
                }
            }
        }
    }

    private static void visit(Config config, boolean network, EntrySink sink) throws IOException {
        Set<Path> customFolders = new HashSet<>();
        for (GameSystem system : Systems.ALL) {
            String custom = config.get("system." + system.id() + ".romdir", "");
            if (!custom.isBlank() && Files.isDirectory(Path.of(custom))) customFolders.add(Path.of(custom).toRealPath());
        }
        tree(config.home(), "", network, customFolders, sink);
        tree(config.romBase(), LIBRARY, false, customFolders, sink);
    }

    private static boolean networkExcluded(String name) {
        String path = name.toLowerCase(java.util.Locale.ROOT);
        return path.equals(CUSTOM) || path.startsWith(CUSTOM + "/")
                || path.equals("peers.properties") || path.equals("peers.properties.tmp")
                || path.equals("choccy/cores") || path.startsWith("choccy/cores/")
                || path.equals("choccy/jre") || path.startsWith("choccy/jre/")
                || path.equals("bin/cloudflared") || path.startsWith("bin/cloudflared/");
    }

    private static void tree(Path root, String prefix, boolean network, Set<Path> customFolders, EntrySink sink) throws IOException {
        if (!Files.exists(root)) return;
        Path real = root.toRealPath();
        Files.walkFileTree(real, new SimpleFileVisitor<>() {
            private String name(Path file) {
                String relative = real.relativize(file).toString().replace(File.separatorChar, '/');
                return relative.isEmpty() ? prefix : prefix.isEmpty() ? relative : prefix + "/" + relative;
            }

            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if ((!prefix.isEmpty() || !dir.equals(real)) && customFolders.contains(dir))
                    return FileVisitResult.SKIP_SUBTREE;
                String name = name(dir);
                if (prefix.isEmpty() && (name.equals("tmp") || name.equals(LIBRARY) || name.equals(CUSTOM)
                        || network && networkExcluded(name)))
                    return FileVisitResult.SKIP_SUBTREE;
                if (!name.isEmpty()) sink.accept(dir, name, true);
                return FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String name = name(file);
                if (attrs.isRegularFile() && !name.equals(COMPLETE)
                        && !(prefix.isEmpty() && (name.matches("gamepad-[^/]+\\.p12") || network && networkExcluded(name))))
                    sink.accept(file, name, false);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
