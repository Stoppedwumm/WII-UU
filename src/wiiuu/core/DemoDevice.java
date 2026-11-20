package wiiuu.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/**
 * USB demo handoff. Backup must be completed and verified before a reset is scheduled.
 * Destructive file operations happen before the main application loads its Config.
 */
public final class DemoDevice {
    private static final String ZIP_NAME = "WII-UU-demo-backup.zip";
    private static final String DONE = ".wiiuu-transfer-complete";
    private DemoDevice() {}

    public static Path archive(Path folder) { return folder.resolve(ZIP_NAME); }
    public static Path checksum(Path zip) { return zip.resolveSibling(zip.getFileName() + ".sha256"); }

    private static Path pending(Path home) {
        Path normalized = home.toAbsolutePath().normalize();
        return normalized.resolveSibling("." + normalized.getFileName() + ".demo-operation");
    }

    private static String digest(Path zip) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(zip)) {
                byte[] block = new byte[65536];
                for (int n; (n = input.read(block)) != -1;) md.update(block, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) { throw new IOException(e); }
    }

    public static void verify(Path zip) throws IOException {
        if (!Files.isRegularFile(zip) || !Files.isRegularFile(checksum(zip)))
            throw new IOException("Backup ZIP or its checksum file is missing.");
        String expected = Files.readString(checksum(zip), StandardCharsets.UTF_8).strip();
        if (!expected.matches("[a-f0-9]{64}") || !MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII), digest(zip).getBytes(StandardCharsets.US_ASCII)))
            throw new IOException("USB backup checksum does not match. Reset cancelled.");
        boolean complete = false;
        int entries = 0;
        try (ZipInputStream input = new ZipInputStream(Files.newInputStream(zip))) {
            for (ZipEntry entry; (entry = input.getNextEntry()) != null;) {
                if (++entries > 200000) throw new IOException("Backup contains too many entries.");
                safeName(entry.getName());
                if (DONE.equals(entry.getName()) && !entry.isDirectory()) complete = true;
                input.transferTo(OutputStream.nullOutputStream());
                input.closeEntry();
            }
        }
        if (!complete || entries < 2) throw new IOException("Backup is incomplete.");
    }

    private static Path safeName(String name) throws IOException {
        if (name.isBlank() || name.startsWith("/") || name.indexOf('\\') >= 0 || name.indexOf(':') >= 0)
            throw new IOException("Unsafe ZIP entry: " + name);
        Path path = Path.of(name).normalize();
        if (path.isAbsolute() || path.startsWith("..") || path.toString().equals("."))
            throw new IOException("Unsafe ZIP entry: " + name);
        return path;
    }

    /** USB folder must be outside both live data and the ROM library. */
    public static Path backup(Config config, Path usbFolder) throws IOException {
        Path destination = usbFolder.toRealPath();
        if (!Files.isDirectory(destination) || !Files.isWritable(destination))
            throw new IOException("USB destination is not a writable directory.");
        Path home = config.home().toAbsolutePath().normalize();
        Path roms = config.romBase().toAbsolutePath().normalize();
        if (destination.startsWith(home) || destination.startsWith(roms)
                || home.startsWith(destination) || roms.startsWith(destination))
            throw new IOException("Choose a separate USB folder, not the WII-UU or ROM directory.");
        Path zip = archive(destination);
        if (Files.exists(zip) || Files.exists(checksum(zip)))
            throw new IOException("A backup already exists in that folder. Choose another folder to avoid overwriting it.");
        Path part = Files.createTempFile(destination, ".wiiuu-demo-", ".partial");
        try {
            try (OutputStream out = Files.newOutputStream(part)) { DataTransfer.sendFull(config, out); }
            // Validate the archive even before publishing the checksum.
            String sha = digest(part);
            Files.move(part, zip);
            Files.writeString(checksum(zip), sha + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW);
            verify(zip);
            return zip;
        } catch (IOException error) {
            Files.deleteIfExists(part);
            Files.deleteIfExists(zip);
            Files.deleteIfExists(checksum(zip));
            throw error;
        }
    }

    /** Factory reset is independent of backup. The UI requires explicit confirmation. */
    public static void scheduleReset(Config config) throws IOException {
        if (Files.exists(pending(config.home()))) throw new IOException("A demo operation is already scheduled.");
        writePending(config.home(), "reset", null);
    }

    public static void scheduleRestore(Config config, Path zip) throws IOException {
        verify(zip);
        if (Files.exists(pending(config.home()))) throw new IOException("A demo operation is already scheduled.");
        Path backup = zip.toRealPath(), home = config.home().toAbsolutePath().normalize();
        if (backup.startsWith(home)) throw new IOException("Backup must be outside the WII-UU data directory.");
        writePending(config.home(), "restore", backup);
    }

    private static void writePending(Path home, String operation, Path zip) throws IOException {
        Path marker = pending(home);
        Files.writeString(marker, operation + "\n" + (zip == null ? "" : zip.toAbsolutePath()) + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW);
    }

    /** Apply only an explicitly scheduled operation and only after checking the USB again. */
    public static void applyPending(Path home) throws IOException {
        Path marker = pending(home);
        if (!Files.exists(marker)) return;
        List<String> lines = Files.readAllLines(marker, StandardCharsets.UTF_8);
        if (lines.size() != 2 || !List.of("reset", "restore").contains(lines.get(0)))
            throw new IOException("Invalid demo operation marker");
        Path zip = null;
        if ("restore".equals(lines.get(0))) {
            if (lines.get(1).isBlank()) throw new IOException("Restore requires a USB backup.");
            zip = Path.of(lines.get(1));
            verify(zip); // Unplugged or modified USB must not overwrite local data.
        }
        Path actualHome = home.toAbsolutePath().normalize();
        Path parent = actualHome.getParent();
        if (parent == null || !Files.isDirectory(parent)) throw new IOException("Invalid data directory");
        Path stage = Files.createTempDirectory(parent, ".wiiuu-demo-stage-");
        Path displaced = null;
        try {
            if (lines.get(0).equals("restore")) {
                try (ZipInputStream input = new ZipInputStream(Files.newInputStream(zip))) {
                    for (ZipEntry entry; (entry = input.getNextEntry()) != null;) {
                        Path relative = safeName(entry.getName());
                        if (relative.toString().equals(DONE)) continue;
                        Path file = stage.resolve(relative).normalize();
                        if (!file.startsWith(stage)) throw new IOException("Invalid restore path");
                        if (entry.isDirectory()) Files.createDirectories(file);
                        else {
                            Files.createDirectories(file.getParent());
                            Files.copy(input, file);
                        }
                        input.closeEntry();
                    }
                }
                Config restored = new Config(stage);
                restored.set("roms.base", actualHome.resolve("migrated-roms").toString());
                // Custom ROM directory overrides might point to the original machine.
                for (GameSystem system : Systems.ALL) restored.set("system." + system.id() + ".romdir", null);
                restored.saveChecked();
            } else {
                Files.createDirectories(stage.resolve("migrated-roms"));
                Config fresh = new Config(stage);
                fresh.set("roms.base", actualHome.resolve("migrated-roms").toString());
                fresh.set("update.channel", "stable");
                fresh.saveChecked();
            }
            if (Files.exists(actualHome, LinkOption.NOFOLLOW_LINKS)) {
                displaced = Files.createTempDirectory(parent, ".wiiuu-demo-old-");
                Files.delete(displaced);
                Files.move(actualHome, displaced);
            }
            try { Files.move(stage, actualHome); }
            catch (IOException error) {
                if (displaced != null) Files.move(displaced, actualHome);
                displaced = null;
                throw error;
            }
            Files.deleteIfExists(marker);
            // Old app data is deleted only after the fresh/restored home exists.
            if (displaced != null) eraseTree(displaced);
        } finally {
            if (Files.exists(stage)) eraseTree(stage);
        }
    }

    private static void eraseTree(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                if (error != null) throw error;
                Files.delete(dir); return FileVisitResult.CONTINUE;
            }
        });
    }
}
