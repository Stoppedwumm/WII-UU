package wiiuu.core;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;

/** Runs on Linux and Windows: both GitHub artifact layouts and malformed archives. */
public final class ActionsBuildTest {
    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("actions-build-test");
        Updater updater = new Updater(new Config(temp), "1.0");
        ByteArrayOutputStream jarBytes = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(jarBytes)) {
            jar.putNextEntry(new JarEntry("wiiuu/Main.class"));
            jar.write(new byte[]{0}); jar.closeEntry();
        }
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("wiiuu.jar", jarBytes.toByteArray());
        files.put("install.sh", new byte[]{1});
        files.put("install.ps1", new byte[]{1});
        Path direct = temp.resolve("artifact.zip");
        Files.write(direct, zip(files));
        check(Files.isRegularFile(updater.prepareBuild(direct).resolve("install.ps1")), "direct Windows artifact");
        Map<String, byte[]> release = new LinkedHashMap<>();
        files.forEach((name, bytes) -> release.put("WII-UU-1.0/" + name, bytes));
        Path nested = temp.resolve("legacy artifact.zip");
        Files.write(nested, zip(Map.of("WII-UU-1.0.zip", zip(release))));
        check(Files.isRegularFile(updater.prepareBuild(nested).resolve("wiiuu.jar")), "nested legacy artifact");
        Path bad = temp.resolve("bad.zip");
        Files.write(bad, zip(Map.of("../escaped.txt", new byte[]{1})));
        rejects(updater, bad, "zip traversal");
        files.remove("install.ps1");
        Files.write(bad, zip(files));
        rejects(updater, bad, "missing Windows installer");
        Files.write(bad, zip(Map.of("README.md", new byte[]{1})));
        rejects(updater, bad, "source archive");
        System.out.println("Actions build import tests passed");
    }
    private static byte[] zip(Map<String, byte[]> files) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (var entry : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue()); zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
    private static void rejects(Updater updater, Path zip, String label) throws Exception {
        try { updater.prepareBuild(zip); } catch (IOException expected) { return; }
        throw new AssertionError(label);
    }
    private static void check(boolean ok, String label) { if (!ok) throw new AssertionError(label); }
}
