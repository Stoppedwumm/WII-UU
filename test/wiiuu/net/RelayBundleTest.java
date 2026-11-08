package wiiuu.net;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Checks the shipped JAR, actual native extraction, pinned version and CLI flags without a tunnel. */
public final class RelayBundleTest {
    public static void main(String[] args) throws Exception {
        for (String platform : List.of("linux-amd64", "linux-arm64", "darwin-amd64", "darwin-arm64", "windows-amd64", "windows-386")) {
            String root = "/cloudflared/" + platform + "/";
            String filename = platform.startsWith("windows") ? "cloudflared.exe" : "cloudflared";
            try (var binary = CloudflaredRelay.class.getResourceAsStream(root + filename);
                 var sum = CloudflaredRelay.class.getResourceAsStream(root + "sha256")) {
                if (binary == null || sum == null) throw new AssertionError("Missing packaged helper: " + platform);
            }
        }
        Path home = Files.createTempDirectory("relay-bundle-test");
        Path helper = CloudflaredRelay.bundledBinary(home);
        Path cached = CloudflaredRelay.bundledBinary(home);
        if (!helper.equals(cached)) throw new AssertionError("helper cache");
        Process version = new ProcessBuilder(helper.toString(), "--version").redirectErrorStream(true).start();
        String output = new String(version.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        if (!version.waitFor(15, TimeUnit.SECONDS) || version.exitValue() != 0 || !output.contains(CloudflaredRelay.VERSION))
            throw new AssertionError("wrong helper: " + output);
        Path config = home.resolve("config.yml"); Files.writeString(config, "{}\n");
        List<String> command = new ArrayList<>(CloudflaredRelay.command(helper, config, 12345));
        command.add("--help");
        Process help = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        if (!help.waitFor(15, TimeUnit.SECONDS) || help.exitValue() != 0) throw new AssertionError("cloudflared rejected wrapper flags");
        // A modified cached executable must be replaced by the verified bundled bytes.
        Files.writeString(helper, "corrupt");
        CloudflaredRelay.bundledBinary(home);
        if (Files.size(helper) < 1024) throw new AssertionError("corrupt helper cache was reused");
        System.out.println("Packaged relay helper tests passed");
    }
}
