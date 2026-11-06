package wiiuu.core;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.*;

/** Verifies the packaged channel is the only source of the beta marker. */
public final class BuildInfoTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("build-channel-test");
        URL classes = BuildInfo.class.getProtectionDomain().getCodeSource().getLocation();
        for (String channel : new String[]{"beta", "release", "unknown", "missing"}) {
            Path metadata = Files.createDirectory(root.resolve(channel));
            if (!channel.equals("missing")) Files.writeString(metadata.resolve("wiiuu-build.properties"), "channel=" + channel + "\n");
            try (URLClassLoader loader = new URLClassLoader(new URL[]{metadata.toUri().toURL(), classes}, ClassLoader.getPlatformClassLoader())) {
                boolean actual = (boolean) loader.loadClass("wiiuu.core.BuildInfo").getMethod("beta").invoke(null);
                if (actual != channel.equals("beta")) throw new AssertionError("Wrong beta marker for " + channel);
            }
        }
        System.out.println("Build channel tests passed");
    }
}
