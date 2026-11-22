package wiiuu.core;

import java.nio.file.Files;
import java.nio.file.Path;

/** Dependency-free native companion launcher regression checks. */
public final class XboxCloudTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("wiiuu-xcloud-test");
        Path binary = root.resolve(System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "openxcloud.exe" : "openxcloud");
        Files.writeString(binary, "test binary placeholder");
        binary.toFile().setExecutable(true);

        if (XboxCloud.executableAt(root) != null) throw new AssertionError("Folder must not be executable");
        if (XboxCloud.executableAt(root.resolve("missing")) != null)
            throw new AssertionError("Missing path must be rejected");
        if (Files.isExecutable(binary) && !binary.equals(XboxCloud.executableAt(binary)))
            throw new AssertionError("Selected executable not found");

        Path app = Files.createDirectories(root.resolve("OpenXCloud.app/Contents/MacOS"));
        Path macBinary = app.resolve("openxcloud");
        Files.writeString(macBinary, "test bundle binary");
        macBinary.toFile().setExecutable(true);
        if (Files.isExecutable(macBinary)
                && !macBinary.equals(XboxCloud.executableAt(root.resolve("OpenXCloud.app"))))
            throw new AssertionError("Mac app bundle executable was not resolved");

        Config config = new Config(root.resolve("wiiuu"));
        config.set("xcloud.executable", root.resolve("missing").toString());
        if (XboxCloud.executable(config) != null)
            throw new AssertionError("Invalid explicit executable must not fall back to PATH");
        if (Files.isExecutable(binary)) {
            config.set("xcloud.executable", binary.toString());
            if (!binary.equals(XboxCloud.executable(config))) throw new AssertionError("Explicit binary ignored");
        }
        System.out.println("Native Xbox Cloud launcher tests passed");
    }
}
