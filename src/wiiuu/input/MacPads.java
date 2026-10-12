package wiiuu.input;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;

import wiiuu.core.Config;

/**
 * USB and Bluetooth controllers on a Mac: Java can't read them there, so a small Swift helper does
 * (resources/mac/pads.swift, Apple's GameController framework; built on first use like WII-UU's
 * other Mac helpers). It reports them the way the Linux helper does, so they become players of
 * their own just the same (player order, remapping, sending them to another WII-UU).
 *
 * <p>On a Mac nothing is grabbed: an emulator WII-UU starts (Dolphin, PPSSPP...) reads the
 * controller itself, so WII-UU only uses it in its menu and in Choccy Milk.
 */
public final class MacPads {
    private Process helper;

    /** Builds (first time) and starts the helper, reporting to {@code to}; false where it can't. */
    public boolean start(Config config, VirtualPads.Controllers to) {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")) return false;
        Path exe = wiiuu.screen.ScreenStreamer.buildSwift(config, "wiiuu-pads", "pads");
        if (exe == null) {
            System.err.println("[input] controllers plugged into the Mac work in emulators, but not in WII-UU's menu or "
                    + "Choccy Milk: the controller helper didn't build (Xcode Command Line Tools: xcode-select --install)");
            return false;
        }
        try {
            helper = new ProcessBuilder(exe.toString()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        } catch (IOException e) {
            System.err.println("[input] controller helper: " + e.getMessage());
            return false;
        }
        Thread reader = new Thread(() -> read(to), "mac-pads");
        reader.setDaemon(true);
        reader.start();
        Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "mac-pads-stop"));
        System.out.println("[input] controllers plugged into the Mac are players of their own (menu and Choccy Milk)");
        return true;
    }

    private void read(VirtualPads.Controllers to) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(helper.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split(" ", 3);
                if (p.length < 2) continue;
                switch (p[0]) {
                    case "pad+" -> to.connected(p[1], p.length > 2 ? p[2] : "Controller");
                    case "pad-" -> to.disconnected(p[1]);
                    case "in" -> {
                        if (p.length > 2) to.input(p[1], p[2]);
                    }
                    default -> { }
                }
            }
        } catch (IOException ignored) {
            // the helper ended
        }
    }

    public void stop() {
        if (helper != null) helper.destroy();
    }
}
