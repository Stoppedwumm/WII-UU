package wiiuu.net;

import wiiuu.core.Config;
import wiiuu.core.DataTransfer;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class MigrationTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("wiiuu-migration-test");
        System.setProperty("user.home", root.toString());
        Path home = Files.createDirectory(root.resolve("old data"));
        Path roms = Files.createDirectory(root.resolve("old games"));
        Path custom = Files.createDirectory(root.resolve("custom DS"));
        Files.writeString(roms.resolve("game.bin"), "ROM");
        Files.writeString(custom.resolve("mario.nds"), "DS");
        Files.writeString(home.resolve("save.sav"), "save");
        Files.writeString(Files.createDirectory(home.resolve("tmp")).resolve("ignored"), "temporary");
        Path core = Files.createDirectories(home.resolve("choccy/cores")).resolve("example_libretro.so");
        Files.writeString(core, "native core");
        Files.writeString(home.resolve("peers.properties"), "pairing credentials");
        Files.writeString(home.resolve("peers.properties.tmp"), "pending credentials");
        Config config = new Config(home);
        config.set("peer.id", "source-device");
        config.set("choccy.core.nds", "melonds");
        config.set("roms.base", roms.toString());
        config.set("system.nds.romdir", custom.toString());
        config.saveChecked();
        Path local = root.resolve("local data");
        DataTransfer.relocate(config, local);
        verify(local);
        check(new Config(local).get("peer.id", "").equals("source-device"), "local relocation retains peer identity");
        check(Files.exists(local.resolve("peers.properties")), "local relocation retains links");
        check(Files.exists(local.resolve("choccy/cores/example_libretro.so")), "local relocation retains cores");
        check(Files.isRegularFile(home.resolve("save.sav")), "keep source data");

        var invitation = new CompletableFuture<String>();
        var sent = new CompletableFuture<Void>();
        Thread sender = new Thread(() -> {
            try {
                Migration.send(new Config(local), "127.0.0.1", invitation::complete, () -> {}, socket -> {});
                sent.complete(null);
            } catch (Exception e) {
                invitation.completeExceptionally(e);
                sent.completeExceptionally(e);
            }
        });
        sender.setDaemon(true);
        sender.start();
        Path received = root.resolve("received data");
        Migration.receive(invitation.get(30, TimeUnit.SECONDS), received, () -> {}, socket -> {});
        sent.get(30, TimeUnit.SECONDS);
        verify(received);
        verifyPortable(received);
        check(new Config(local).get("peer.id", "").equals("source-device"), "network transfer preserves sender identity");

        var bytes = new java.io.ByteArrayOutputStream();
        try (var zip = new java.util.zip.ZipOutputStream(bytes)) {
            for (String entry : new String[]{"config.properties", "peers.properties", "peers.properties.tmp",
                    "choccy/cores/example_libretro.so", ".wiiuu-transfer-complete"}) {
                zip.putNextEntry(new java.util.zip.ZipEntry(entry));
                if (entry.equals("config.properties"))
                    zip.write("peer.id=source-device\nchoccy.core.nds=melonds\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        Path legacy = root.resolve("legacy sender data");
        DataTransfer.receive(new ByteArrayInputStream(bytes.toByteArray()), legacy);
        verifyPortable(legacy);

        Path invalid = root.resolve("invalid data");
        try {
            DataTransfer.receive(new ByteArrayInputStream(new byte[0]), invalid);
            throw new AssertionError("accepted incomplete transfer");
        } catch (java.io.IOException expected) {
            check(!Files.exists(invalid) && Config.defaultHome().equals(legacy), "incomplete transfer changed location");
        }
        System.out.println("Storage and TLS migration tests passed");
    }

    private static void verifyPortable(Path home) {
        Config config = new Config(home);
        check(config.get("peer.id", "").isEmpty(), "receiver must get its own peer identity");
        check(config.get("choccy.core.nds", "").equals("melonds"), "receiver retains core preference");
        check(!Files.exists(home.resolve("peers.properties")), "receiver must pair its own peers");
        check(!Files.exists(home.resolve("peers.properties.tmp")), "receiver must not copy pending pairing credentials");
        check(!Files.exists(home.resolve("choccy/cores")), "receiver must use native cores for its own architecture");
    }

    private static void verify(Path home) throws Exception {
        Config config = new Config(home);
        check(Config.defaultHome().equals(home), "storage link");
        check(Files.readString(home.resolve("save.sav")).equals("save"), "save copied");
        check(Files.readString(config.romBase().resolve("game.bin")).equals("ROM"), "ROM library copied");
        Path custom = Path.of(config.get("system.nds.romdir", ""));
        check(custom.startsWith(home) && Files.readString(custom.resolve("mario.nds")).equals("DS"), "custom ROM directory copied");
        check(!Files.exists(home.resolve("tmp/ignored")), "staging was skipped");
    }

    private static void check(boolean result, String message) {
        if (!result) throw new AssertionError(message);
    }
}
