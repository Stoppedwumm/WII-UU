package wiiuu.core;

import java.nio.file.*;
import java.io.IOException;

/** Verify backup fidelity, wipe isolation and recovery round trip. */
public final class DemoDeviceTest {
    public static void main(String[] args) throws Exception {
        Path base = Files.createTempDirectory("wiiuu-demo-test-");
        Path home = Files.createDirectory(base.resolve("home"));
        Path roms = Files.createDirectory(base.resolve("roms"));
        Path usb = Files.createDirectory(base.resolve("usb"));
        Config config = new Config(home);
        config.set("roms.base", roms.toString());
        config.set("ui.sounds", "false");
        config.saveChecked();
        Files.writeString(home.resolve("save.dat"), "private save");
        Files.writeString(roms.resolve("demo.rom"), "rom content");
        Path zip = DemoDevice.backup(config, usb);
        DemoDevice.verify(zip);
        try {
            DemoDevice.backup(config, usb);
            throw new AssertionError("Backup unexpectedly overwritten");
        } catch (IOException expected) {}
        DemoDevice.scheduleReset(config);
        DemoDevice.applyPending(home);
        if (Files.exists(home.resolve("save.dat"))) throw new AssertionError("Factory reset retained private save");
        if (Files.exists(home.resolve("config.properties"))
                && new Config(home).getBool("ui.sounds", false))
            throw new AssertionError("Invalid reset config");
        if (!Files.readString(roms.resolve("demo.rom")).equals("rom content"))
            throw new AssertionError("Factory reset erased external ROM folder");
        DemoDevice.scheduleRestore(new Config(home), zip);
        DemoDevice.applyPending(home);
        if (!Files.readString(home.resolve("save.dat")).equals("private save"))
            throw new AssertionError("Restore lost app data");
        if (!Files.readString(home.resolve("migrated-roms/demo.rom")).equals("rom content"))
            throw new AssertionError("Restore lost ROM library");
        if (!new Config(home).get("ui.sounds", "").equals("false"))
            throw new AssertionError("Restore lost settings");
        if (!Files.exists(zip)) throw new AssertionError("USB backup was erased");
        // Verify a modified backup cannot schedule destructive restore.
        Files.writeString(zip, "corrupt", StandardOpenOption.APPEND);
        try {
            DemoDevice.scheduleRestore(new Config(home), zip);
            throw new AssertionError("Modified backup was accepted");
        } catch (IOException expected) {}
        System.out.println("USB demo backup/reset/restore passed");
    }
}
