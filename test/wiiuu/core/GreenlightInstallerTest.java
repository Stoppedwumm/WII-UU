package wiiuu.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Offline checks for Greenlight's platform selection and verified installer. */
public final class GreenlightInstallerTest {
    private static final String HASH =
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    private static void check(boolean ok, String description) {
        if (!ok) throw new AssertionError(description);
    }

    private static void reject(Checked operation, String description) throws Exception {
        try { operation.run(); }
        catch (IOException expected) { return; }
        throw new AssertionError(description);
    }

    @FunctionalInterface interface Checked { void run() throws Exception; }

    private static String manifest(String tag, String name, String digest, String url) {
        return "{\"tag_name\":\"" + tag + "\",\"draft\":false,\"prerelease\":false,"
                + "\"assets\":[{\"name\":\"" + name + "\",\"digest\":\""
                + digest + "\",\"size\":3,\"browser_download_url\":\"" + url + "\"}]}";
    }

    public static void main(String[] args) throws Exception {
        var platform = GreenlightInstaller.Platform.class;
        check(GreenlightInstaller.Platform.detect("Linux", "aarch64")
                == GreenlightInstaller.Platform.LINUX_FLATPAK, "Linux ARM64 must use Flatpak");
        check(GreenlightInstaller.Platform.detect("Linux", "arm64")
                == GreenlightInstaller.Platform.LINUX_FLATPAK, "Linux ARM64 alias");
        check(GreenlightInstaller.Platform.detect("Linux", "x86_64")
                == GreenlightInstaller.Platform.LINUX_FLATPAK, "Linux x86-64 must use Flatpak");
        check(GreenlightInstaller.Platform.detect("Mac OS X", "arm64")
                == GreenlightInstaller.Platform.MAC_UNIVERSAL, "macOS ARM64 universal DMG");
        check(GreenlightInstaller.Platform.detect("Mac OS X", "x86_64")
                == GreenlightInstaller.Platform.MAC_UNIVERSAL, "macOS Intel universal DMG");
        check(GreenlightInstaller.Platform.detect("Windows 11", "amd64")
                == GreenlightInstaller.Platform.WINDOWS_X64, "Windows portable ZIP");
        reject(() -> GreenlightInstaller.Platform.detect("Windows 11", "arm64"),
                "Windows ARM64 must not download x64 native executable");
        reject(() -> GreenlightInstaller.Platform.detect("Linux", "armv7l"),
                "Linux ARMv7 must not install AArch64 Flatpak");
        reject(() -> GreenlightInstaller.parseRelease("{}", GreenlightInstaller.Platform.LINUX_FLATPAK),
                "Linux should not fetch an x64 GitHub release asset");
        check("https://flathub.org/repo/flathub.flatpakrepo".equals(GreenlightInstaller.FLATHUB_REPO),
                "Installer must configure the canonical Flathub remote");
        for (GreenlightInstaller.Platform p : new GreenlightInstaller.Platform[]{
                GreenlightInstaller.Platform.MAC_UNIVERSAL, GreenlightInstaller.Platform.WINDOWS_X64}) {
            String tag = "v2.4.2";
            String asset = p.assetName(tag);
            String url = "https://github.com/unknownskl/greenlight/releases/download/" + tag + "/" + asset;
            String json = manifest(tag, asset, "sha256:" + HASH, url);
            var release = GreenlightInstaller.parseRelease(json, p);
            check(tag.equals(release.tag()) && p == release.platform()
                    && release.size() == 3 && HASH.equals(release.sha256())
                    && url.equals(release.url().toString()), "Verified native asset " + p);
            reject(() -> GreenlightInstaller.parseRelease(
                    manifest(tag, asset, "sha256:invalid", url), p), "Missing checksum was accepted");
            reject(() -> GreenlightInstaller.parseRelease(
                    manifest(tag, asset, "sha256:" + HASH,
                            "https://attacker.example/" + asset), p), "Untrusted host was accepted");
            reject(() -> GreenlightInstaller.parseRelease(
                    manifest(tag, asset, "sha256:" + HASH,
                            "https://github.com/unknownskl/greenlight/releases/download/" + tag
                                    + "/Greenlight-Setup-2.4.2.exe"), p),
                    "Unreviewed installer was accepted");
            reject(() -> GreenlightInstaller.parseRelease(
                    manifest(tag, "unexpected.zip", "sha256:" + HASH, url), p),
                    "Wrong asset name was accepted");
            reject(() -> GreenlightInstaller.parseRelease(
                    json.replace("\"prerelease\":false", "\"prerelease\":true"), p),
                    "Prerelease was accepted");
        }

        Path root = Files.createTempDirectory("greenlight-installer-test");
        Path bytes = root.resolve("abc");
        Files.writeString(bytes, "abc", StandardCharsets.UTF_8);
        check(HASH.equals(GreenlightInstaller.sha256(bytes)), "SHA-256 verification");

        Path archive = root.resolve("portable.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(archive))) {
            out.putNextEntry(new ZipEntry("win-unpacked/"));
            out.closeEntry();
            out.putNextEntry(new ZipEntry("win-unpacked/Greenlight.exe"));
            out.write(new byte[]{1, 2, 3});
            out.closeEntry();
            out.putNextEntry(new ZipEntry("win-unpacked/resources/app.asar"));
            out.write(new byte[]{4, 5, 6});
            out.closeEntry();
        }
        Path stage = root.resolve("stage");
        GreenlightInstaller.extractZip(archive, stage);
        check(Files.size(stage.resolve("win-unpacked/resources/app.asar")) == 3,
                "Portable Electron resources must be preserved");
        Path executable = stage.resolve("win-unpacked/Greenlight.exe");
        executable.toFile().setExecutable(true);
        if (Files.isExecutable(executable))
            check(executable.equals(GreenlightInstaller.findExecutable(stage,
                    GreenlightInstaller.Platform.WINDOWS_X64)), "Windows binary discovery");

        Path app = Files.createDirectories(root.resolve("Greenlight.app/Contents/MacOS"));
        Path macBinary = app.resolve("Greenlight");
        Files.writeString(macBinary, "placeholder");
        macBinary.toFile().setExecutable(true);
        if (Files.isExecutable(macBinary))
            check(macBinary.equals(GreenlightInstaller.findExecutable(root,
                    GreenlightInstaller.Platform.MAC_UNIVERSAL)), "macOS .app discovery");

        Path dangerous = root.resolve("traversal.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(dangerous))) {
            out.putNextEntry(new ZipEntry("../breakout.txt"));
            out.write("bad".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        reject(() -> GreenlightInstaller.extractZip(dangerous, root.resolve("extraction")),
                "ZIP traversal must fail");
        check(!Files.exists(root.resolve("breakout.txt")), "Extracted file escaped its directory");
        System.out.println("Greenlight installer and ARM64 regression tests passed");
    }
}
