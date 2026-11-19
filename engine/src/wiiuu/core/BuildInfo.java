package wiiuu.core;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Immutable build identity loaded from the packaged JAR, independent of installed folder/branch. */
public final class BuildInfo {
    private static final Properties METADATA = read();
    private BuildInfo() {}

    private static Properties read() {
        Properties out = new Properties();
        try (InputStream in = BuildInfo.class.getResourceAsStream("/wiiuu-build.properties")) {
            if (in != null) out.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException ignored) { }
        return out;
    }
    public static String version() { return METADATA.getProperty("version", "0.0.0-dev"); }
    public static String baseVersion() { return METADATA.getProperty("baseVersion", Version.parse(version()).base()); }
    public static String channel() {
        return switch (METADATA.getProperty("channel", "development")) {
            case "release", "stable" -> "stable";
            case "beta" -> "beta";
            default -> "development";
        };
    }
    public static boolean beta() { return !channel().equals("stable"); }
    public static String number() {
        String attempt = METADATA.getProperty("attempt", "");
        return METADATA.getProperty("build", "local") + (attempt.isEmpty() ? "" : "." + attempt);
    }
    public static String commit() { return METADATA.getProperty("commit", "unknown") + ("true".equals(METADATA.getProperty("dirty")) ? " (modified)" : ""); }
    public static String builtAt() { return METADATA.getProperty("builtAt", "unknown"); }
    public static String branch() { return METADATA.getProperty("branch", "unknown"); }
    public static String badge() { return channel().equals("stable") ? "" : channel().equals("beta") ? "BETA " + baseVersion() + " #" + number() : "DEVELOPMENT"; }
    public static String description() { return version() + " · " + channel() + " · " + commit(); }
}
