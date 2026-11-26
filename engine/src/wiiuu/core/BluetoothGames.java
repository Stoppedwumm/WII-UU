package wiiuu.core;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.function.LongConsumer;

/** One explicitly selected file per connection, with integrity verification and no overwrite. */
public final class BluetoothGames {
    private static final int MAGIC = 0x57425431;
    private static final long MAX = 64L * 1024 * 1024 * 1024;
    private BluetoothGames() {}
    public static boolean transferable(GameSystem system, String name) {
        return system != null && !system.extensions().isEmpty() && name != null
                && !name.isBlank() && name.length() <= 240 && !name.startsWith(".")
                && !name.contains("/") && !name.contains("\\") && !name.contains(":")
                && name.codePoints().noneMatch(Character::isISOControl)
                && system.matches(name) && !name.toLowerCase(Locale.ROOT).endsWith(".cue")
                && !name.toLowerCase(Locale.ROOT).endsWith(".m3u");
    }
    public static void send(String peer, GameSystem system, Path file, LongConsumer progress) throws IOException {
        if (!Files.isRegularFile(file) || !transferable(system, file.getFileName().toString()))
            throw new IOException("Choose a supported, self-contained game file (not a multi-file disc manifest).");
        try (BluetoothLink link = BluetoothLink.open(false, peer, BluetoothLink.TRANSFER_CHANNEL)) {
            send(link.input(), link.output(), system, file, progress);
        }
    }
    public static Path receive(Config config, String peer, LongConsumer progress) throws IOException {
        try (BluetoothLink link = BluetoothLink.open(true, peer, BluetoothLink.TRANSFER_CHANNEL)) {
            return receive(link.input(), link.output(), config, progress);
        }
    }
    static void send(DataInputStream in, DataOutputStream out, GameSystem system, Path file, LongConsumer progress) throws IOException {
        String name = file.getFileName().toString();
        long size = Files.size(file);
        if (!transferable(system, name) || size <= 0 || size > MAX) throw new IOException("Invalid game file");
        out.writeInt(MAGIC); out.writeUTF(system.id()); out.writeUTF(name); out.writeLong(size); out.flush();
        if (!in.readBoolean()) throw new IOException(in.readUTF());
        MessageDigest sha = digest();
        try (InputStream source = Files.newInputStream(file)) {
            copy(source, out, size, sha, progress);
            if (source.read() != -1) throw new IOException("File grew during transfer");
        }
        out.write(sha.digest()); out.flush();
        if (!in.readBoolean()) throw new IOException(in.readUTF());
    }
    static Path receive(DataInputStream in, DataOutputStream out, Config config, LongConsumer progress) throws IOException {
        Path temporary = null;
        boolean accepted = false;
        try {
            if (in.readInt() != MAGIC) throw new IOException("Unsupported transfer protocol");
            GameSystem system = Systems.byId(in.readUTF()).orElse(null);
            String name = in.readUTF();
            long size = in.readLong();
            if (!transferable(system, name) || size <= 0 || size > MAX) throw new IOException("Invalid game metadata");
            Path directory = Files.createDirectories(config.romDir(system)).toRealPath();
            Path target = directory.resolve(name);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Game already exists");
            if (Files.getFileStore(directory).getUsableSpace() < size) throw new IOException("Not enough free space");
            temporary = Files.createTempFile(directory, ".bluetooth-", ".part");
            out.writeBoolean(true); out.flush(); accepted = true;
            MessageDigest sha = digest();
            try (OutputStream destination = Files.newOutputStream(temporary)) {
                copy(in, destination, size, sha, progress);
            }
            byte[] expected = in.readNBytes(32);
            if (!MessageDigest.isEqual(expected, sha.digest())) throw new IOException("Game checksum mismatch");
            Files.move(temporary, target); // deliberately no REPLACE_EXISTING
            temporary = null;
            out.writeBoolean(true); out.flush();
            return target;
        } catch (IOException | RuntimeException e) {
            try { out.writeBoolean(false); out.writeUTF(accepted ? "Transfer failed" : "Game rejected: " + e.getMessage()); out.flush(); }
            catch (IOException ignored) {}
            if (e instanceof IOException io) throw io;
            throw new IOException("Invalid transfer", e);
        } finally {
            if (temporary != null) Files.deleteIfExists(temporary);
        }
    }
    private static void copy(InputStream in, OutputStream out, long size, MessageDigest sha, LongConsumer progress) throws IOException {
        byte[] buffer = new byte[65536];
        long remaining = size;
        while (remaining > 0) {
            int n = in.read(buffer, 0, (int)Math.min(buffer.length, remaining));
            if (n < 0) throw new EOFException("Game transfer interrupted");
            out.write(buffer, 0, n); sha.update(buffer, 0, n);
            remaining -= n; progress.accept(size - remaining);
        }
    }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
}
