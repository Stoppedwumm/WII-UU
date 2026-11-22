package wiiuu.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Offline Chrome for Testing platform, manifest, URL and extraction checks. */
public final class XboxCloudRuntimeTest {
    private static void check(boolean ok, String reason) {
        if (!ok) throw new AssertionError(reason);
    }
    private static void reject(Checked operation, String reason) throws Exception {
        try { operation.run(); }
        catch (IOException expected) { return; }
        throw new AssertionError(reason);
    }
    private interface Checked { void run() throws Exception; }

    private static String index(String version, String platform, String url) {
        return "{\"channels\":{\"Stable\":{\"version\":\"" + version +
                "\",\"downloads\":{\"chrome\":[{\"platform\":\"" + platform +
                "\",\"url\":\"" + url + "\"}]}}}}";
    }

    public static void main(String[] args) throws Exception {
        check(XboxCloudRuntime.Platform.detect("Linux", "aarch64")
                == XboxCloudRuntime.Platform.LINUX_ARM64, "ARM64 Linux");
        check(XboxCloudRuntime.Platform.detect("Linux", "arm64")
                == XboxCloudRuntime.Platform.LINUX_ARM64, "ARM64 alias");
        check(XboxCloudRuntime.Platform.detect("Linux", "amd64")
                == XboxCloudRuntime.Platform.LINUX_X64, "x86-64 Linux");
        check(XboxCloudRuntime.Platform.detect("Mac OS X", "arm64")
                == XboxCloudRuntime.Platform.MAC_ARM64, "Apple Silicon");
        check(XboxCloudRuntime.Platform.detect("Mac OS X", "x86_64")
                == XboxCloudRuntime.Platform.MAC_X64, "Intel Mac");
        check(XboxCloudRuntime.Platform.detect("Windows 11", "amd64")
                == XboxCloudRuntime.Platform.WINDOWS_X64, "Windows x64");
        reject(() -> XboxCloudRuntime.Platform.detect("Linux", "armv7l"), "Unsupported ARMv7");
        reject(() -> XboxCloudRuntime.Platform.detect("Windows 11", "arm64"), "Unsupported Windows ARM64");

        for (XboxCloudRuntime.Platform platform : XboxCloudRuntime.Platform.values()) {
            String version="158.0.8127.36";
            String url="https://storage.googleapis.com/chrome-for-testing-public/" +
                    version + "/" + platform.key() + "/chrome-" + platform.key() + ".zip";
            var release=XboxCloudRuntime.selectChrome(index(version,platform.key(),url),platform);
            check(release.version().equals(version) && release.url().toString().equals(url),
                    "Correct official asset for " + platform);
            reject(() -> XboxCloudRuntime.selectChrome(index(version,platform.key(),
                    "https://evil.example/chrome.zip"),platform),"Untrusted Chrome host");
            reject(() -> XboxCloudRuntime.selectChrome(index(version,platform.key(),
                    "https://storage.googleapis.com/other-path/chrome.zip"),platform),"Wrong Google bucket");
            reject(() -> XboxCloudRuntime.selectChrome(index("v1",platform.key(),url),platform),
                    "Invalid Chrome release version");
        }

        Path root = Files.createTempDirectory("wiiuu-chrome-test");
        check(!XboxCloudRuntime.ready(new Config(root.resolve("empty"))),
                "A new WII-UU installation has no browser runtime");
        Path safe=root.resolve("safe.zip");
        try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(safe))){
            zip.putNextEntry(new ZipEntry("chrome-linux-arm64/chrome"));
            zip.write("example-binary".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        XboxCloudRuntime.extractZip(safe,root.resolve("install"));
        check(Files.exists(root.resolve("install/chrome-linux-arm64/chrome")),
                "Official nested executable extracted");

        Path unsafe=root.resolve("unsafe.zip");
        try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(unsafe))){
            zip.putNextEntry(new ZipEntry("../../escaped.txt"));
            zip.write("escaped".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        reject(()->XboxCloudRuntime.extractZip(unsafe,root.resolve("invalid")),
                "Archive must reject path traversal");
        check(!Files.exists(root.resolve("escaped.txt")), "Archive escaped install directory");
        System.out.println("Xbox Cloud Runtime platform and safe archive tests passed");
    }
}
