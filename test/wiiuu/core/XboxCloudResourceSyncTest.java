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
        XboxCloudRuntime.syncBundledService(config);
        byte[] expected;
        try (var input = XboxCloudRuntime.class.getResourceAsStream("/xcloud/service.cjs")) {
            ok(input != null, "Bundled Xbox service must be present in build");
            expected = input.readAllBytes();
        }
        ok(Arrays.equals(expected, Files.readAllBytes(script)), "Old Node backend should be replaced");
        ok(Arrays.equals(existing, Files.readAllBytes(tokens)), "User tokens must remain untouched");
        XboxCloudRuntime.syncBundledService(config);
        ok(Arrays.equals(expected, Files.readAllBytes(script)), "Repeated refresh must be safe");
        ok(Files.isDirectory(browser), "Portable Chromium installation must not be removed");
        System.out.println("Xbox backend auto-upgrade preserves credentials and Chromium without a reinstall");
    }
}
