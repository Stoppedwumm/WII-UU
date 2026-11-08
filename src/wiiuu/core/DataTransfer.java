package wiiuu.core;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.zip.*;

/** Copies app data and configured ROM folders into a new storage directory. */
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
        install(target, stage -> visit(config, false, (source, name, directory) -> {
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
        install(target(destination), stage -> {
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

    private static void install(Path target, StageWriter writer) throws IOException {
        Path stage = Files.createTempDirectory(target.getParent(), ".wiiuu-transfer-");
        try {
            writer.write(stage);
            Config relocated = new Config(stage);
            relocated.set("roms.base", target.resolve(LIBRARY).toString());
            for (GameSystem system : Systems.ALL) {
                String key = "system." + system.id() + ".romdir";
                Path custom = stage.resolve(CUSTOM).resolve(system.id());
                relocated.set(key, Files.isDirectory(custom) ? target.resolve(CUSTOM).resolve(system.id()).toString() : null);
            }
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
        tree(config.home(), "", network, sink);
        tree(config.romBase(), LIBRARY, false, sink);
        for (GameSystem system : Systems.ALL) {
            String custom = config.get("system." + system.id() + ".romdir", "");
            if (!custom.isBlank()) tree(Path.of(custom), CUSTOM + "/" + system.id(), false, sink);
        }
    }

    private static void tree(Path root, String prefix, boolean network, EntrySink sink) throws IOException {
        if (!Files.exists(root)) return;
        Path real = root.toRealPath();
        Files.walkFileTree(real, new SimpleFileVisitor<>() {
            private String name(Path file) {
                String relative = real.relativize(file).toString().replace(File.separatorChar, '/');
                return relative.isEmpty() ? prefix : prefix.isEmpty() ? relative : prefix + "/" + relative;
            }

            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                String name = name(dir);
                if (prefix.isEmpty() && (name.equals("tmp") || name.equals(LIBRARY) || name.equals(CUSTOM)
                        || network && (name.equals("choccy/jre") || name.equals("bin/cloudflared"))))
                    return FileVisitResult.SKIP_SUBTREE;
                if (!name.isEmpty()) sink.accept(dir, name, true);
                return FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String name = name(file);
                if (attrs.isRegularFile() && !name.equals(COMPLETE)
                        && !(prefix.isEmpty() && name.matches("gamepad-[^/]+\\.p12")))
                    sink.accept(file, name, false);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
