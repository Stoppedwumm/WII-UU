package wiiuu.core;

import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.*;

/** Deterministic catalogue/preferences/download tests, using a loopback buildbot fixture. */
public final class CoreManagerTest {
    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("core-manager-test");
        Config c = new Config(home); c.set("retroarch.path", home.resolve("not-installed").toString());
        CoreManager manager = new CoreManager(c);
        check(CoreManager.catalogue("nes").contains("nestopia"), "NES catalogue");
        check(!CoreManager.catalogue("snes").contains("bsnes"), "unsafe core filtered");
        rejects(() -> manager.select("nes", "../../outside"), "invalid selection");
        rejects(() -> manager.select("nes", "snes9x"), "wrong console");
        manager.select("nes", "nestopia");
        check(new CoreManager(new Config(home)).selected("nes").equals("nestopia"), "persistent core preference");
        manager.select("nes", ""); check(manager.selected("nes").isEmpty(), "Automatic reset");
        c.set("choccy.nds.online", "true");
        rejects(() -> manager.select("nds", "desmume"), "WFC core restriction");
        manager.select("nds", "melondsds");
        Cores cores = new Cores(c);
        Path folder = cores.choccyCoreDir(); Files.createDirectories(folder);
        Path installed = folder.resolve("snes9x_libretro." + Cores.EXT);
        Files.writeString(installed, "fixture core, not executed");
        c.set("choccy.snes.widescreen", "true"); manager.select("snes", "snes9x");
        Path chosen = cores.choccyCoreFor(Systems.byId("snes").orElseThrow(), () -> { throw new AssertionError("Unexpected automatic download"); });
        check(chosen.equals(installed), "explicit choice overrides picture-driven automatic selection");
        check(manager.entries("snes").stream().anyMatch(e -> e.id().equals("snes9x") && installed.equals(e.installed())), "installed status");
        c.set("choccy.core.snes", "not_a_real_core");
        try { cores.choccyCoreFor(Systems.byId("snes").orElseThrow(), () -> {}); throw new AssertionError("silent fallback"); }
        catch (Cores.Downloading expected) { check(expected.getMessage().contains("Invalid selected core"), "missing preference error"); }

        byte[] payload = "downloaded native fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String filename = "nestopia_libretro." + Cores.EXT;
        AtomicReference<byte[]> zip = new AtomicReference<>(archive(filename, payload));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> { byte[] body = zip.get(); ex.sendResponseHeaders(200, body.length); try (ex) { ex.getResponseBody().write(body); } });
        server.start();
        try {
            var download = Cores.class.getDeclaredMethod("download", String.class, Path.class, String.class); download.setAccessible(true);
            String source = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
            Path target = (Path) download.invoke(cores, "nestopia", folder, source);
            check(target != null && Arrays.equals(Files.readAllBytes(target), payload), "download and install exact core");
            zip.set(archive("../" + filename, new byte[]{9}));
            check(download.invoke(cores, "nestopia", folder, source) == null, "unexpected archive path rejected");
            check(Arrays.equals(Files.readAllBytes(target), payload), "failed reinstall preserves installed core");
            zip.set(archive("other_libretro." + Cores.EXT, new byte[]{9}));
            check(download.invoke(cores, "nestopia", folder, source) == null, "wrong core rejected");
            try (var files = Files.list(folder)) { check(files.noneMatch(p -> p.toString().endsWith(".part")), "temporary files cleaned"); }
        } finally { server.stop(0); }
        System.out.println("Core manager tests passed");
    }
    private static byte[] archive(String name, byte[] content) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) { zip.putNextEntry(new ZipEntry(name)); zip.write(content); zip.closeEntry(); }
        return bytes.toByteArray();
    }
    private static void rejects(Runnable action, String label) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError(label);
    }
    private static void check(boolean ok, String label) { if (!ok) throw new AssertionError(label); }
}
