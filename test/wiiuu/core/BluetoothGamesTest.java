package wiiuu.core;

import java.io.*;
import java.nio.file.*;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

public final class BluetoothGamesTest {
    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("bluetooth-games");
        Config config = new Config(home);
        config.set("roms.base", home.resolve("roms").toString());
        GameSystem nes = Systems.byId("nes").orElseThrow();
        Path source = home.resolve("test.nes");
        byte[] data = new byte[200000]; new java.util.Random(42).nextBytes(data);
        Files.write(source, data);
        check(BluetoothLink.validAddress("AA:BB:CC:DD:EE:FF"), "MAC validation");
        check(!BluetoothLink.validAddress("127.0.0.1"), "no IP fallback");
        check(!BluetoothGames.transferable(nes, "../evil.nes"), "path traversal");
        check(!BluetoothGames.transferable(nes, "test.exe"), "invalid extension");
        exchange(config, nes, source, false);
        check(Arrays.equals(data, Files.readAllBytes(config.romDir(nes).resolve("test.nes"))), "content verified");
        exchange(config, nes, source, true);
        check(Arrays.equals(data, Files.readAllBytes(config.romDir(nes).resolve("test.nes"))), "no overwrite");
        ByteArrayOutputStream bad = new ByteArrayOutputStream();
        DataOutputStream packet = new DataOutputStream(bad);
        packet.writeInt(0x57425431); packet.writeUTF("nes"); packet.writeUTF("bad.nes");
        packet.writeLong(1); packet.write(42); packet.write(new byte[32]);
        try {
            BluetoothGames.receive(new DataInputStream(new ByteArrayInputStream(bad.toByteArray())),
                    new DataOutputStream(new ByteArrayOutputStream()), config, n -> {});
            throw new AssertionError("Corrupt game accepted");
        } catch (IOException expected) {}
        check(!Files.exists(config.romDir(nes).resolve("bad.nes")), "bad checksum rejected");
        try (var files = Files.list(config.romDir(nes))) {
            check(files.noneMatch(p -> p.getFileName().toString().endsWith(".part")), "partial cleaned");
        }
        System.out.println("BluetoothGamesTest passed");
    }
    private static void exchange(Config config, GameSystem system, Path source, boolean reject) throws Exception {
        PipedInputStream senderInput = new PipedInputStream(65536), receiverInput = new PipedInputStream(65536);
        var senderOut = new DataOutputStream(new PipedOutputStream(receiverInput));
        var receiverOut = new DataOutputStream(new PipedOutputStream(senderInput));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Path> result = executor.submit(() -> BluetoothGames.receive(new DataInputStream(receiverInput), receiverOut, config, n -> {}));
            boolean failed = false;
            try { BluetoothGames.send(new DataInputStream(senderInput), senderOut, system, source, n -> {}); }
            catch (IOException expected) { failed = true; }
            check(failed == reject, "sender rejection");
            try { result.get(5, TimeUnit.SECONDS); check(!reject, "receiver must reject duplicate"); }
            catch (ExecutionException expected) { check(reject, "unexpected receiver failure"); }
        } finally { executor.shutdownNow(); senderOut.close(); receiverOut.close(); senderInput.close(); receiverInput.close(); }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
