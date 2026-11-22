package wiiuu.core;

import java.nio.file.Files;
import java.nio.file.Path;

/** Greenlight launcher checks without needing an Xbox account, GPU or network. */
public final class GreenlightTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("wiiuu-greenlight-launcher-");
        if (Greenlight.executableAt(root) != null) throw new AssertionError("A directory is not a binary");
        if (Greenlight.executableAt(root.resolve("missing")) != null)
            throw new AssertionError("A missing binary cannot launch");

        String name = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "Greenlight.exe" : "greenlight";
        Path binary = root.resolve(name);
        Files.writeString(binary, "placeholder");
        binary.toFile().setExecutable(true);
        if (Files.isExecutable(binary) && !binary.equals(Greenlight.executableAt(binary)))
            throw new AssertionError("Greenlight binary not resolved");

        Path app = Files.createDirectories(root.resolve("Greenlight.app/Contents/MacOS"));
        Path executable = app.resolve("Greenlight");
        Files.writeString(executable, "placeholder");
        executable.toFile().setExecutable(true);
        if (Files.isExecutable(executable)
                && !executable.equals(Greenlight.executableAt(root.resolve("Greenlight.app"))))
            throw new AssertionError("macOS Greenlight app bundle not resolved");

        Config config = new Config(root.resolve("wiiuu"));
        config.set("xcloud.executable", binary.toString());
        config.set("greenlight.executable", root.resolve("missing").toString());
        if (Greenlight.executable(config) != null)
            throw new AssertionError("Invalid Greenlight path must not fall back to old OpenXCloud path");
        if (Files.isExecutable(binary)) {
            config.set("greenlight.executable", binary.toString());
            if (!binary.equals(Greenlight.executable(config)))
                throw new AssertionError("Greenlight custom executable ignored");
            if (!Greenlight.status(config).contains(binary.toString()))
                throw new AssertionError("Status must show the selected executable");
        }
        if (!"io.github.unknownskl.greenlight".equals(Greenlight.FLATPAK_ID))
            throw new AssertionError("Wrong canonical Flathub application ID");
        System.out.println("Greenlight launcher tests passed");
    }
}
