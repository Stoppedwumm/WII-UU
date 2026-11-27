package wiiuu.core;

import java.awt.event.KeyEvent;
import java.nio.file.*;
import java.util.*;
import wiiuu.input.*;
import wiiuu.engine.GameScreen;

public final class ChoccySettingsTest {
    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("choccy-settings-test");
        Config c = new Config(home);
        check(ChoccyKeys.action(c, KeyEvent.VK_X).equals("A"), "default key");
        check(ChoccyKeys.action(c, KeyEvent.VK_SHIFT).equals("MINUS"), "legacy Shift alias");
        Map<String, Integer> map = ChoccyKeys.load(c);
        map.put("A", KeyEvent.VK_K); map.put("save", KeyEvent.VK_F8);
        ChoccyKeys.save(c, map);
        Config reload = new Config(home);
        check(ChoccyKeys.action(reload, KeyEvent.VK_SHIFT).equals("MINUS"), "default alias survives saving");
        check(ChoccyKeys.action(reload, KeyEvent.VK_K).equals("A"), "binding survives restart");
        check(ChoccyKeys.action(reload, KeyEvent.VK_X) == null, "old key unbound");
        check(ChoccyKeys.action(reload, KeyEvent.VK_F8).equals("save"), "state shortcut");
        map.put("B", KeyEvent.VK_K); rejects(() -> ChoccyKeys.save(c, map), "duplicate bindings");
        map.put("B", KeyEvent.VK_F1); rejects(() -> ChoccyKeys.save(c, map), "reserved Settings key");
        map.put("B", 0); ChoccyKeys.save(c, map);
        check(ChoccyKeys.action(c, 0) == null, "unbound");
        check(DsOnline.options(c).get("melonds_network_mode").equals("disabled"), "online opt in");
        for (String dns : List.of("127.0.0.1", "178.62.43.212", "")) check(DsOnline.validDns(dns), "valid DNS");
        for (String dns : List.of("999.1.1.1", "1.2.3", "a.2.3.4", "1.2.3.4.5", "1..2.3", "01.2.3.4")) check(!DsOnline.validDns(dns), "invalid DNS");
        c.set("choccy.nds.online", "true");
        var defaults = DsOnline.options(c);
        check(defaults.get("melonds_network_mode").equals("indirect"), "shared online mode");
        check(!defaults.containsKey("melonds_firmware_wfc_dns"), "preserve core default service");
        check(!defaults.containsKey("melonds_mac_address_mode"), "preserve existing DS identity");
        String name = DsOnline.username(c);
        check(name.equals(DsOnline.username(new Config(home))), "stable profile");
        c.set("choccy.nds.dns", "192.0.2.123"); c.set("choccy.nds.online.uniqueMac", "true");
        var custom = DsOnline.options(c);
        check(custom.get("melonds_firmware_wfc_dns").equals("192.0.2.123"), "custom DNS reaches core");
        check(custom.get("wiiuu.username").equals(name), "profile reaches helper");
        check(custom.get("melonds_mac_address_mode").equals("from-username"), "explicit identity selection");
        c.set("choccy.nds.dns", "bad"); rejects(() -> DsOnline.options(c), "bad DNS rejected before launch");
        check(Cores.melonDsReleaseUrl("Windows 11", "amd64").endsWith("win32-x86_64-Release.zip"), "official Windows networking core");
        check(Cores.melonDsReleaseUrl("Linux", "aarch64").endsWith("linux-arm64-Release.zip"), "official Raspberry Pi 64-bit core");
        check(Cores.melonDsReleaseUrl("Mac OS X", "arm64").endsWith("macos-arm64-Release.zip"), "official Apple Silicon core");
        check(Cores.melonDsReleaseUrl("Windows", "aarch64") == null, "unsupported upstream architecture");
        Cores cores = new Cores(c);
        var n64Software = cores.choccyOptions("n64");
        check("angrylion".equals(n64Software.get("mupen64plus-rdp-plugin")), "N64 software default");
        check("sinc".equals(n64Software.get("wiiuu.audioResampler")), "N64 sinc audio");
        if (ChoccyPicture.n64OpenGlAvailable("n64")) {
            c.set("choccy.n64.widescreen", "true");
            c.set("choccy.n64.upscale", "n64-1080");
            var wide = cores.choccyOptions("n64");
            check("gliden64".equals(wide.get("mupen64plus-rdp-plugin")), "N64 selects GLideN64");
            check("16:9 adjusted".equals(wide.get("mupen64plus-aspect")), "N64 widescreen adjusts the 3D view");
            check("1920x1080".equals(wide.get("mupen64plus-169screensize")), "N64 sets the internal wide resolution");
            check(!ChoccyPicture.stretch(c, "n64"), "N64 never stretches enhanced frames");
            check("sharp".equals(ChoccyPicture.filter(c, "n64")), "N64 HD includes clean sharpening");
            c.set("choccy.n64.widescreen", "false");
            check("4:3".equals(cores.choccyOptions("n64").get("mupen64plus-aspect")), "N64 HD preserves 4:3");
        }
        GameScreen screen = new GameScreen();
        screen.frame(new int[256 * 384], 256, 384, 2f / 3);
        var viewport = screen.pictureBounds(1000, 600);
        check(viewport.width == 400 && viewport.height == 600 && viewport.x == 300, "DS stylus letterboxing");
        System.out.println("Choccy picture, input and DS online settings tests passed");
    }
    private static void rejects(Runnable action, String label) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError(label);
    }
    private static void check(boolean ok, String label) { if (!ok) throw new AssertionError(label); }
}
