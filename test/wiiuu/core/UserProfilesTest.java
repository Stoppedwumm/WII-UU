package wiiuu.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Ensure old saves survive, personal credentials never fall back, and profiles persist. */
public final class UserProfilesTest {
    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("wiiuu-users-test-");
        Config legacy = new Config(home);
        if (!legacy.userId().equals("default") || !legacy.userHome().equals(home))
            throw new AssertionError("Legacy home should stay the default profile");
        legacy.set("ra.user", "Original");
        legacy.set("ra.apikey", "secret-original");
        legacy.set("openbased.token", "original-token");
        legacy.set("ui.music", "false");
        legacy.set("roms.base", home.resolve("shared-roms").toString());
        legacy.set("server.port", "8081");
        legacy.saveChecked();
        Files.createDirectories(home.resolve("saves/nds"));
        Files.writeString(home.resolve("saves/nds/progress.sav"), "original save");

        Config.Users.Profile alice = Config.Users.create(home, "Alice");
        Config.Users.Profile bob = Config.Users.create(home, "Bob");
        check(Config.Users.list(home).size() == 3, "three profiles including default");
        check(!alice.id().equals(bob.id()), "IDs differ");
        try { Config.Users.create(home, "alice"); throw new AssertionError("duplicate accepted"); }
        catch (IllegalArgumentException expected) {}
        try { Config.Users.create(home, "Bad\nName"); throw new AssertionError("control char accepted"); }
        catch (IllegalArgumentException expected) {}

        Config.Users.select(home, alice.id());
        Config other = new Config(home);
        check(other.userId().equals(alice.id()), "active identity");
        check(other.userHome().equals(home.resolve("profiles").resolve(alice.id())), "safe private directory");
        check(other.get("ra.user", "").isEmpty(), "no cross-user RA identity");
        check(other.get("ra.apikey", "").isEmpty(), "no cross-user RA key");
        check(other.get("openbased.token", "").isEmpty(), "no cross-user OpenBased token");
        check(other.getBool("ui.music", true), "own UI defaults");
        check(other.getInt("server.port", 0) == 8081, "shared port");
        check(other.romBase().equals(home.resolve("shared-roms")), "shared ROM library");
        other.set("ra.user", "Alice");
        other.set("ra.apikey", "alice-only-key");
        other.set("ui.music", "true");
        other.set("server.port", "8082");
        other.saveChecked();
        Files.createDirectories(other.userHome().resolve("saves/nds"));
        Files.writeString(other.userHome().resolve("saves/nds/progress.sav"), "alice save");

        Config.Users.select(home, bob.id());
        Config bobConfig = new Config(home);
        check(bobConfig.get("ra.user", "").isEmpty(), "Alice secrets not visible to Bob");
        check(bobConfig.getInt("server.port", 0) == 8082, "device-level changes shared");

        Config.Users.select(home, "default");
        Config restored = new Config(home);
        check(restored.get("ra.user", "").equals("Original"), "legacy RA account intact");
        check(restored.get("ra.apikey", "").equals("secret-original"), "legacy API key intact");
        check(restored.get("openbased.token", "").equals("original-token"), "legacy OpenBased token intact");
        check(Files.readString(home.resolve("saves/nds/progress.sav")).equals("original save"),
                "legacy save never moved");
        check(Files.readString(home.resolve("profiles").resolve(alice.id())
                        .resolve("saves/nds/progress.sav")).equals("alice save"), "Alice save isolated");

        Config.Users.rename(home, alice.id(), "Alice Changed");
        check(Config.Users.list(home).stream().anyMatch(u -> u.id().equals(alice.id())
                && u.name().equals("Alice Changed")), "rename persists");
        try { Config.Users.select(home, "../outside"); throw new AssertionError("traversal allowed"); }
        catch (IllegalArgumentException expected) {}
        Config.Users.remove(home, bob.id());
        check(Config.Users.list(home).size() == 2, "inactive profile removed from list");
        try { Config.Users.remove(home, "default"); throw new AssertionError("default removed"); }
        catch (IllegalArgumentException expected) {}
        Config.Users.select(home, alice.id());
        try { Config.Users.remove(home, alice.id()); throw new AssertionError("active profile removed"); }
        catch (IllegalArgumentException expected) {}
        check(new Config(home).get("ra.user", "").equals("Alice"), "data available after switching back");
        System.out.println("UserProfilesTest passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
