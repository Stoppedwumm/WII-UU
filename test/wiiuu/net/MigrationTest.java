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
        Config config = new Config(home);
        config.set("roms.base", roms.toString());
        config.set("system.nds.romdir", custom.toString());
        config.saveChecked();
        Path local = root.resolve("local data");
        DataTransfer.relocate(config, local);
        verify(local);
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

        Path invalid = root.resolve("invalid data");
        try {
            DataTransfer.receive(new ByteArrayInputStream(new byte[0]), invalid);
            throw new AssertionError("accepted incomplete transfer");
        } catch (java.io.IOException expected) {
            check(!Files.exists(invalid) && Config.defaultHome().equals(received), "incomplete transfer changed location");
        }
        System.out.println("Storage and TLS migration tests passed");
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
