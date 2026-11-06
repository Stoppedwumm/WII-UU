package wiiuu.core;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Build metadata is packaged in the JAR, so installing a build preserves its channel. */
public final class BuildInfo {
    private BuildInfo() {}

    public static boolean beta() {
        Properties metadata = new Properties();
        try (InputStream in = BuildInfo.class.getResourceAsStream("/wiiuu-build.properties")) {
            if (in != null) metadata.load(in);
        } catch (IOException ignored) {
            return false;
        }
        return "beta".equals(metadata.getProperty("channel"));
    }
}
