package wiiuu.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** In-place Xbox authentication helper upgrades must preserve prior user credentials. */
public final class XboxCloudResourceSyncTest {
    private static void ok(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        Config config = new Config(Files.createTempDirectory("wiiuu-xcloud-service-upgrade-"));
        Path root = Files.createDirectories(XboxCloudRuntime.home(config));
        Path script = root.resolve("service.cjs");
        Path browser = Files.createDirectories(root.resolve("chrome/current"));
        Path tokens = Files.createDirectories(config.userHome().resolve("xcloud"))
                .resolve("xcloud-tokens.json");
        byte[] existing = "test-token-persistence".getBytes(StandardCharsets.UTF_8);
        Files.write(tokens, existing);
        Files.writeString(script, "old-service-version");
        Path source = root.resolve("player-entry.js");
        Path bundle = root.resolve("player.bundle.js");
        Files.writeString(source, "old-player-source");
        Files.writeString(bundle, "old-player-bundle");
        Path esbuild = root.resolve("node_modules/esbuild/bin/esbuild");
        Files.createDirectories(esbuild.getParent());
        // Offline stand-in for esbuild: verifies the upgrade invokes the
        // installed bundler without an npm or Chromium reinstall.
        Files.writeString(esbuild, "const fs=require('fs');"
                + "const a=process.argv.slice(2);"
                + "const out=a.find(x=>x.startsWith('--outfile=')).slice(10);"
                + "fs.writeFileSync(out,'built:'+fs.readFileSync(a[0],'utf8'));");
        XboxCloudRuntime.syncBundledService(config);
        byte[] expected;
        try (var input = XboxCloudRuntime.class.getResourceAsStream("/xcloud/service.cjs")) {
            ok(input != null, "Bundled Xbox service must be present in build");
            expected = input.readAllBytes();
        }
        ok(Arrays.equals(expected, Files.readAllBytes(script)), "Old Node backend should be replaced");
        ok(Arrays.equals(existing, Files.readAllBytes(tokens)), "User tokens must remain untouched");
        byte[] expectedPlayer;
        try (var input = XboxCloudRuntime.class.getResourceAsStream("/xcloud/player-entry.js")) {
            ok(input != null, "Bundled Xbox player must be present");
            expectedPlayer = input.readAllBytes();
        }
        ok(Arrays.equals(expectedPlayer, Files.readAllBytes(source)), "Player source must update");
        ok(Files.readString(bundle).equals("built:" + new String(expectedPlayer, StandardCharsets.UTF_8)),
                "Old browser bundle must be rebuilt");
        byte[] updatedBundle = Files.readAllBytes(bundle);
        XboxCloudRuntime.syncBundledService(config);
        ok(Arrays.equals(expected, Files.readAllBytes(script)), "Repeated refresh must be safe");
        ok(Arrays.equals(updatedBundle, Files.readAllBytes(bundle)), "Unchanged bundle must not rebuild");
        ok(Files.isDirectory(browser), "Portable Chromium installation must not be removed");
        System.out.println("Xbox backend auto-upgrade preserves credentials and Chromium without a reinstall");
    }
}
