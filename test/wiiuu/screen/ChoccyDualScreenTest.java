package wiiuu.screen;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import wiiuu.core.*;

/** Headless integration check: actual stream JPEGs and the helper's libretro callback. */
public final class ChoccyDualScreenTest {
    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("choccy-dual-test");
        Config config = new Config(home);
        config.set("stream.backend", "java");
        Game game = new Game(Systems.ALL.stream().filter(s -> s.id().equals("nds")).findFirst().orElseThrow(),
                "Test", home.resolve("test.nds"), List.of());
        ScreenStreamer stream = new ScreenStreamer(config, () -> game);
        AtomicBoolean active = new AtomicBoolean(true);
        double[] touch = new double[3];
        stream.setChoccy(active::get, (x, y, down) -> { touch[0] = x; touch[1] = y; touch[2] = down ? 1 : 0; });
        check(stream.available() && stream.secondScreen() != null, "direct screen available headless");
        var region = ScreenStreamer.class.getDeclaredMethod("region", String.class, String[].class);
        region.setAccessible(true);
        for (String mode : List.of("tv", "second")) {
            String[] why = new String[1];
            check(region.invoke(stream, mode, why) == null && why[0].contains("Choccy Milk"),
                    mode + " must never resolve a desktop region before first frame");
        }
        ScreenStreamer.Frame waiting = stream.nextFrame("second", -1, 2000);
        check(waiting != null, "waiting picture before first game frame");
        config.set("screen.split", "false");
        check(stream.secondScreen() != null, "RetroArch virtual-display setting must not disable Choccy screens");
        int[] pixels = new int[256 * 384];
        Arrays.fill(pixels, 0, 256 * 192, 0xff0000);
        Arrays.fill(pixels, 256 * 192, pixels.length, 0x0000ff);
        stream.directFrame(pixels, 256, 384, 2f / 3);
        for (String mode : List.of("tv", "second")) {
            ScreenStreamer.Frame frame = stream.nextFrame(mode, mode.equals("second") ? waiting.seq() : -1, 4000);
            check(frame != null, mode + " frame");
            var image = ImageIO.read(new ByteArrayInputStream(frame.jpeg()));
            check(Math.abs(image.getWidth() / (double) image.getHeight() - 4.0 / 3) < .02, "screen aspect");
            int rgb = image.getRGB(image.getWidth() / 2, image.getHeight() / 2);
            check(mode.equals("tv") ? (rgb >> 16 & 255) > 240 && (rgb & 255) < 10
                    : (rgb & 255) > 240 && (rgb >> 16 & 255) < 10, mode + " crop");
        }
        // Cross the old one-second expiry: keep the cached core image, never start desktop capture.
        Thread.sleep(1200);
        for (String mode : List.of("tv", "second")) {
            String[] why = new String[1];
            check(region.invoke(stream, mode, why) == null && why[0].contains("Choccy Milk"),
                    mode + " stays direct during a long frame gap");
        }
        ScreenStreamer.Frame retained = stream.nextFrame("second", -1, 1000);
        var retainedImage = ImageIO.read(new ByteArrayInputStream(retained.jpeg()));
        check((retainedImage.getRGB(10, 10) & 255) > 240, "retain bottom frame during gap");
        Arrays.fill(pixels, 256 * 192, pixels.length, 0x00ff00);
        stream.directFrame(pixels, 256, 384, 2f / 3);
        ScreenStreamer.Frame resumed = stream.nextFrame("second", retained.seq(), 2000);
        check(resumed != null, "direct frames resume after gap");
        var resumedImage = ImageIO.read(new ByteArrayInputStream(resumed.jpeg()));
        check((resumedImage.getRGB(10, 10) >> 8 & 255) > 240, "resumed bottom picture");
        stream.touch("second", .25, .5, 1);
        check(touch[0] == .25 && touch[1] == .75 && touch[2] == 1, "bottom pointer mapping");
        stream.releaseTouch();
        check(touch[2] == 0, "disconnect releases stylus");
        config.set("choccy.splitScreens", "false");
        check(stream.secondScreen() == null, "split disabled");
        stream.touch("tv", .25, .5, 1);
        check(touch[1] == .5, "combined pointer mapping");
        active.set(false);
        check(!stream.available(), "game ended");

        Class<?> helper = Class.forName("wiiuu.choccy.ChoccyMilk");
        var ctor = helper.getDeclaredConstructor(Path.class, Path.class, Path.class, Path.class, OutputStream.class);
        ctor.setAccessible(true);
        Object milk = ctor.newInstance(home, home, home, home, new ByteArrayOutputStream());
        Class<?> pointer = Class.forName("wiiuu.choccy.ChoccyMilk$Pointer");
        var pc = pointer.getDeclaredConstructors()[0]; pc.setAccessible(true);
        var field = helper.getDeclaredField("pointer"); field.setAccessible(true);
        field.set(milk, pc.newInstance((short) -1234, (short) 16384, true));
        var input = helper.getDeclaredMethod("inputState", int.class, int.class, int.class, int.class);
        input.setAccessible(true);
        check((short) input.invoke(milk, 0, 6, 0, 0) == -1234, "pointer X callback");
        check((short) input.invoke(milk, 0, 6, 0, 1) == 16384, "pointer Y callback");
        check((short) input.invoke(milk, 0, 6, 0, 2) == 1, "pointer pressed callback");
        check((short) input.invoke(milk, 1, 6, 0, 2) == 0, "pointer belongs to player one");
        System.out.println("Choccy dual screen tests passed");
        System.exit(0);
    }
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
