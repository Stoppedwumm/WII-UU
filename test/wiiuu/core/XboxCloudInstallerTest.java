package wiiuu.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Network-free official release validation, extraction, and platform regression checks. */
public final class XboxCloudInstallerTest {
    private static final String HASH =
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }

    private static String manifest(String url, String digest, String name) {
        return "{\"tag_name\":\"v0.1.2\",\"draft\":false,\"prerelease\":false,"
                + "\"assets\":[{\"name\":\"" + name + "\",\"size\":3,\"digest\":\""
                + digest + "\",\"browser_download_url\":\"" + url + "\"}]}";
    }

    private static void reject(CheckedTask task, String description) throws Exception {
        try { task.run(); }
        catch (IOException expected) { return; }
        throw new AssertionError(description);
    }

    @FunctionalInterface interface CheckedTask { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        check(XboxCloudInstaller.Platform.detect("Mac OS X", "aarch64")
                == XboxCloudInstaller.Platform.MAC_ARM64, "macOS ARM selection");
        check(XboxCloudInstaller.Platform.detect("Windows 11", "amd64")
                == XboxCloudInstaller.Platform.WINDOWS_X64, "Windows x64 selection");
        check(XboxCloudInstaller.Platform.detect("Linux", "x86_64")
                == XboxCloudInstaller.Platform.LINUX_AMD64, "Linux x64 selection");
        reject(() -> XboxCloudInstaller.Platform.detect("Mac OS X", "x86_64"),
                "Unsupported Intel Mac must not download an ARM-only package");
        reject(() -> XboxCloudInstaller.Platform.detect("Linux", "aarch64"),
                "Unsupported Linux ARM must not download amd64 binaries");

        for (XboxCloudInstaller.Platform platform : XboxCloudInstaller.Platform.values()) {
            String url = "https://github.com/shyim/openxcloud/releases/download/v0.1.2/"
                    + platform.filename;
            String json = manifest(url, "sha256:" + HASH, platform.filename);
            var found = XboxCloudInstaller.parseRelease(json, platform);
            check("v0.1.2".equals(found.tag()) && found.platform() == platform
                    && HASH.equals(found.sha256()) && found.size() == 3
                    && found.url().toString().equals(url), "Official asset must be selected");
            reject(() -> XboxCloudInstaller.parseRelease(
                    manifest("https://attacker.example/release.exe", "sha256:" + HASH, platform.filename),
                    platform + " accepted an untrusted URL");
            reject(() -> XboxCloudInstaller.parseRelease(
                    manifest(url, "sha256:invalid", platform.filename),
                    platform + " accepted an asset without a valid digest");
            reject(() -> XboxCloudInstaller.parseRelease(
                    json.replace("\"prerelease\":false", "\"prerelease\":true"), platform),
                    platform + " accepted a prerelease");
            reject(() -> XboxCloudInstaller.parseRelease(
                    manifest(url, "sha256:" + HASH, "unexpected.zip"), platform),
                    platform + " accepted the wrong binary");
        }

        Path root = Files.createTempDirectory("xcloud-installer-test");
        Path bytes = root.resolve("content");
        Files.writeString(bytes, "abc", StandardCharsets.UTF_8);
        check(HASH.equals(XboxCloudInstaller.sha256(bytes)), "SHA-256 checksum must match");

        Path zip = root.resolve("portable.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("OpenXCloud/"));
            out.closeEntry();
            out.putNextEntry(new ZipEntry("OpenXCloud/openxcloud.exe"));
            out.write(new byte[]{1, 2, 3});
            out.closeEntry();
            out.putNextEntry(new ZipEntry("OpenXCloud/avcodec.dll"));
            out.write(new byte[]{4, 5, 6});
            out.closeEntry();
        }
        Path staging = root.resolve("good");
        XboxCloudInstaller.extractZip(zip, staging);
        check(Files.readAllBytes(staging.resolve("OpenXCloud/avcodec.dll")).length == 3,
                "Portable installation must keep native DLLs");
        Path winExe = staging.resolve("OpenXCloud/openxcloud.exe");
        winExe.toFile().setExecutable(true);
        check(winExe.equals(XboxCloudInstaller.findExecutable(staging,
                XboxCloudInstaller.Platform.WINDOWS_X64)), "Find portable executable");

        Path linux = Files.createDirectories(root.resolve("deb/usr/bin"));
        Path nativeExe = linux.resolve("openxcloud");
        Files.writeString(nativeExe, "binary placeholder");
        nativeExe.toFile().setExecutable(true);
        check(nativeExe.equals(XboxCloudInstaller.findExecutable(root.resolve("deb"),
                XboxCloudInstaller.Platform.LINUX_AMD64)), "Find Debian extracted executable");

        Path dangerous = root.resolve("dangerous.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(dangerous))) {
            out.putNextEntry(new ZipEntry("../escape.txt"));
            out.write("bad".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        reject(() -> XboxCloudInstaller.extractZip(dangerous, root.resolve("target")),
                "ZIP extraction must block path traversal");
        check(!Files.exists(root.resolve("escape.txt")), "ZIP escaped its extraction directory");

        System.out.println("OpenXCloud downloader, release metadata, and archive tests passed");
    }
}
