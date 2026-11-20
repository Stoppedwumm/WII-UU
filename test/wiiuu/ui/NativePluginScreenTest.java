package wiiuu.ui;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import wiiuu.core.*;

public final class NativePluginScreenTest {
    public static void main(String[] args) throws Exception {
        Sfx.setEnabled(false);
        SwingUtilities.invokeAndWait(() -> {
            try {
                AtomicInteger exits = new AtomicInteger();
                NativePluginScreen screen = new NativePluginScreen("Browse Games", () -> {}, exits::incrementAndGet);
                List<NativePluginSession.Entry> entries = new ArrayList<>();
                for (int i = 0; i < 20; i++) entries.add(new NativePluginSession.Entry("e" + (i + 1), "Native panel " + (i + 1), "",
                        List.of(new NativePluginSession.Button("b" + (i + 1), "Download", false))));
                screen.accept(new NativePluginSession.View("v1", "Browse Games", true, "20 games loaded from a JavaScript API request", entries));
                render(screen, 1600, 900, "native-plugin-panels.png");
                screen.page(1); render(screen, 1600, 900, "native-plugin-page-2.png");
                screen.navigate(-1, 0); screen.navigate(0, -1);
                render(screen, 640, 400, "native-plugin-small.png");
                screen.accept(new NativePluginSession.View("v2", "Close test", true, "", entries.subList(0, 1)));
                screen.navigate(-1, 0); screen.activate();
                if (exits.get() != 1) throw new AssertionError("Native close button did not work");
                // B must always provide a way back even with closeButton(false).
                screen.accept(new NativePluginSession.View("v3", "No close button", false, "", entries));
                MenuView menu = new MenuView(new Config(Files.createTempDirectory("native-menu-")), true);
                var field = MenuView.class.getDeclaredField("nativePlugin"); field.setAccessible(true); field.set(menu, screen);
                menu.back(); if (field.get(menu) != null) throw new AssertionError("B did not close native view");
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        imageFixture();
        System.out.println("Native panels render at desktop/small sizes, load images asynchronously, paginate, navigate and close");
    }
    private static void imageFixture() throws Exception {
        BufferedImage cover = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB);
        Graphics2D cg = cover.createGraphics(); cg.setColor(Color.MAGENTA); cg.fillRect(0, 0, 32, 32); cg.dispose();
        var bytes = new java.io.ByteArrayOutputStream(); ImageIO.write(cover, "png", bytes);
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/cover.png", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, bytes.size());
            try (var body = exchange.getResponseBody()) { body.write(bytes.toByteArray()); }
        });
        server.start();
        var loaded = new java.util.concurrent.CompletableFuture<Void>();
        var calls = new AtomicInteger();
        var screen = new java.util.concurrent.atomic.AtomicReference<NativePluginScreen>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                NativePluginScreen nativeScreen = new NativePluginScreen("Images", () -> {
                    if (calls.incrementAndGet() >= 2) loaded.complete(null);
                }, () -> {});
                screen.set(nativeScreen);
                nativeScreen.accept(new NativePluginSession.View("v1", "API image", true, "", List.of(
                        new NativePluginSession.Entry("e1", "Remote cover", "http://127.0.0.1:" + server.getAddress().getPort() + "/cover.png", List.of()))));
                BufferedImage initial = new BufferedImage(1600, 900, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = initial.createGraphics(); nativeScreen.paint(g, 1600, 900); g.dispose();
            });
            loaded.get(10, java.util.concurrent.TimeUnit.SECONDS);
            SwingUtilities.invokeAndWait(() -> {
                BufferedImage image = new BufferedImage(1600, 900, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = image.createGraphics(); screen.get().paint(g, 1600, 900); g.dispose();
                int pixels = 0;
                for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++)
                    if (image.getRGB(x, y) == Color.MAGENTA.getRGB()) pixels++;
                if (pixels < 100) throw new AssertionError("Remote panel image was not drawn");
            });
        } finally {
            SwingUtilities.invokeAndWait(() -> { if (screen.get() != null) screen.get().close(); });
            server.stop(0);
        }
    }
    private static void render(NativePluginScreen screen, int w, int h, String filename) throws Exception {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics(); graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        screen.paint(graphics, w, h); graphics.dispose();
        ImageIO.write(image, "png", Path.of("build", filename).toFile());
    }
}
