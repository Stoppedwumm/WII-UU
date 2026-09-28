package wiiuu.screen;

import java.awt.AWTException;
import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Robot;
import java.awt.event.InputEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;

import wiiuu.core.Config;
import wiiuu.core.Game;

/**
 * Captures part of the PC screen and streams it to phones as MJPEG, and turns taps on the
 * phone into mouse clicks at the matching spot (emulators like Cemu and melonDS treat mouse
 * clicks on their GamePad / bottom screen as touch input).
 *
 * <p>Modes: {@code tv} mirrors the whole screen (Off-TV play); {@code second} shows the running
 * game's second screen as described by its {@link ScreenProfile}.
 */
public final class ScreenStreamer {
    public static final String BOUNDARY = "wiiuuframe";

    private final Config config;
    private final Supplier<Game> currentGame;
    private final WindowLocator windows = new WindowLocator();
    private final Robot robot;
    /** last region streamed per mode, used to map touches back to screen coordinates */
    private final Map<String, Rectangle> lastRegion = new ConcurrentHashMap<>();
    private volatile boolean mouseDown;

    public ScreenStreamer(Config config, Supplier<Game> currentGame) {
        this.config = config;
        this.currentGame = currentGame;
        Robot r = null;
        if (!GraphicsEnvironment.isHeadless()) {
            try {
                r = new Robot();
            } catch (AWTException | SecurityException e) {
                System.err.println("[screen] screen capture unavailable: " + e.getMessage());
            }
        }
        this.robot = r;
    }

    public boolean available() {
        return robot != null;
    }

    /** The second-screen profile for the running game, or null. */
    public ScreenProfile secondScreen() {
        Game g = currentGame.get();
        return g == null ? null : ScreenProfile.forSystem(config, g.system());
    }

    /** @return the screen rectangle for a mode right now, or null (with {@code why} filled in) */
    private Rectangle region(String mode, String[] why) {
        if ("second".equals(mode)) {
            ScreenProfile p = secondScreen();
            if (p == null) {
                why[0] = "This game has no second screen";
                return null;
            }
            Rectangle win = windows.find(p.windowRegex());
            if (win == null) {
                why[0] = "Waiting for the \"" + p.windowRegex() + "\" window" + ("wiiu".equals(systemId())
                        ? " (Cemu: View > Separate GamePad view)" : "");
                return null;
            }
            return clip(p.locate(win));
        }
        return clip(GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice()
                .getDefaultConfiguration().getBounds());
    }

    private String systemId() {
        Game g = currentGame.get();
        return g == null ? "" : g.system().id();
    }

    private static Rectangle clip(Rectangle r) {
        Rectangle screen = new Rectangle();
        for (var dev : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            screen = screen.union(dev.getDefaultConfiguration().getBounds());
        }
        Rectangle c = r.intersection(screen);
        return c.width < 8 || c.height < 8 ? null : c;
    }

    /**
     * Writes an endless multipart JPEG stream until the phone disconnects.
     * The caller has already sent headers with content type {@code multipart/x-mixed-replace}.
     */
    public void stream(String mode, OutputStream out) throws IOException {
        int fps = Math.max(1, Math.min(60, config.getInt("stream.fps", 20)));
        int maxW = Math.max(160, config.getInt("stream.maxWidth", 960));
        float quality = Math.max(0.2f, Math.min(0.95f, config.getInt("stream.quality", 60) / 100f));
        long frameNanos = 1_000_000_000L / fps;
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(quality);
        ByteArrayOutputStream buf = new ByteArrayOutputStream(64 * 1024);
        String[] why = new String[1];
        try {
            while (!Thread.currentThread().isInterrupted()) {
                long start = System.nanoTime();
                BufferedImage frame;
                Rectangle r = robot == null ? null : region(mode, why);
                if (r != null) {
                    lastRegion.put(mode, r);
                    frame = scale(robot.createScreenCapture(r), maxW);
                } else {
                    lastRegion.remove(mode);
                    frame = message(robot == null ? "Screen capture is not available on this PC" : why[0]);
                }
                buf.reset();
                try (MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(buf)) {
                    writer.setOutput(ios);
                    writer.write(null, new IIOImage(frame, null, null), param);
                }
                out.write(("--" + BOUNDARY + "\r\nContent-Type: image/jpeg\r\nContent-Length: " + buf.size()
                        + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                buf.writeTo(out);
                out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();
                long sleep = (r == null ? 500_000_000L : frameNanos) - (System.nanoTime() - start);
                if (sleep > 0) Thread.sleep(sleep / 1_000_000, (int) (sleep % 1_000_000));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            writer.dispose();
        }
    }

    /** A tap on the phone: x/y are fractions of the streamed picture; state 1 = down, 2 = move, 0 = up. */
    public synchronized void touch(String mode, double x, double y, int state) {
        Rectangle r = lastRegion.get(mode);
        if (robot == null || r == null) return;
        int px = r.x + (int) Math.round(Math.max(0, Math.min(1, x)) * (r.width - 1));
        int py = r.y + (int) Math.round(Math.max(0, Math.min(1, y)) * (r.height - 1));
        robot.mouseMove(px, py);
        if (state == 1 && !mouseDown) {
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
            mouseDown = true;
        } else if (state == 0 && mouseDown) {
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
            mouseDown = false;
        }
    }

    /** Lets go of the mouse if a phone disconnects mid-touch. */
    public synchronized void releaseTouch() {
        if (robot != null && mouseDown) {
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
            mouseDown = false;
        }
    }

    private static BufferedImage scale(BufferedImage src, int maxW) {
        if (src.getWidth() <= maxW) return src;
        int w = maxW, h = Math.max(1, (int) Math.round(src.getHeight() * (maxW / (double) src.getWidth())));
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    private static BufferedImage message(String text) {
        BufferedImage img = new BufferedImage(640, 360, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(0x1c1f24));
        g.fillRect(0, 0, 640, 360);
        g.setColor(new Color(0x9aa3ad));
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 20));
        var fm = g.getFontMetrics();
        // simple word wrap
        String[] words = text.split(" ");
        StringBuilder line = new StringBuilder();
        java.util.List<String> lines = new java.util.ArrayList<>();
        for (String w : words) {
            if (fm.stringWidth(line + " " + w) > 560 && !line.isEmpty()) {
                lines.add(line.toString());
                line.setLength(0);
            }
            if (!line.isEmpty()) line.append(' ');
            line.append(w);
        }
        lines.add(line.toString());
        int y = 180 - lines.size() * fm.getHeight() / 2 + fm.getAscent();
        for (String l : lines) {
            g.drawString(l, (640 - fm.stringWidth(l)) / 2, y);
            y += fm.getHeight();
        }
        g.dispose();
        return img;
    }
}
