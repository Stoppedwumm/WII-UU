package wiiuu.core;

import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import wiiuu.engine.GameScreen;

/** Runs the real native helper on a freely redistributable DS-shaped core, with no game/BIOS assets. */
public final class NativeStartupTest {
    public static void main(String[] args) throws Exception {
        GameScreen screen = new GameScreen();
        screen.starting("Loading game…");
        check(screen.showing() && screen.picture() == null, "game view before first frame");
        screen.frame(new int[]{0x112233}, 1, 1, 1);
        check(screen.showing() && screen.picture() != null, "first picture replaces startup");
        screen.clear(); check(!screen.showing(), "game exit restores menu");
        Path home = Files.createTempDirectory("native-ds-startup");
        Path rom = home.resolve("fixture.nds"); Files.write(rom, new byte[]{0});
        Path log = home.resolve("nds.log");
        Choccy.setHome(home);
        CountDownLatch firstFrame = new CountDownLatch(1);
        AtomicBoolean correct = new AtomicBoolean(); AtomicReference<String> phase = new AtomicReference<>();
        Choccy.Session session = Choccy.play(Path.of(args[0]).toAbsolutePath(), rom, home.resolve("saves"), home,
                Map.of("wiiuu.sound", "pipe"), (rgb, w, h, aspect) -> {
                    correct.set(w == 256 && h == 384 && rgb[0] == 0x112233 && rgb[256 * 192] == 0x445566);
                    firstFrame.countDown();
                }, log);
        session.onStartup(phase::set);
        try {
            if (!firstFrame.await(15, TimeUnit.SECONDS)) throw new AssertionError("No native DS frame; phase=" + phase.get() + "; ending=" + session.ending() + "\n" + Files.readString(log));
            check(correct.get(), "native DS pixels reach parent");
            check(Files.readString(log).contains("native DS fixture banner"), "native stdout stays out of frame protocol");
            session.quit();
            check(session.process().waitFor(10, TimeUnit.SECONDS), "native helper stops");
            check(session.process().exitValue() == 0, "native helper success: " + Files.readString(log));
        } finally { if (session.process().isAlive()) session.process().destroyForcibly(); }
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process bad = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), "wiiuu.core.NativeStartupTest$BrokenHelper").start();
        Choccy.Session broken = Choccy.attach(bad, (rgb, w, h, a) -> {}, log);
        try {
            check(bad.waitFor(10, TimeUnit.SECONDS), "bad frame stream terminates child instead of hanging");
            check(broken.ending() != null && broken.ending().contains("protocol byte"), "protocol error is actionable");
        } finally { bad.destroyForcibly(); }
        System.out.println("Native DS startup tests passed on " + System.getProperty("os.name") + " / Java " + Runtime.version());
    }
    public static final class BrokenHelper {
        public static void main(String[] args) throws Exception { System.out.println("unexpected native output"); System.out.flush(); while (System.in.read() != -1) {} }
    }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
