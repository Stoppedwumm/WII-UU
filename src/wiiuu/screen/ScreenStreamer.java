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
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
 *
 * <p>Each mode is one shared channel: a producer thread publishes the newest JPEG and every
 * phone watching just sends whatever is newest, so a slow phone never slows capture down.
 * Frames come from ffmpeg (x11grab / gdigrab, fast native JPEG encoding) when it is installed,
 * otherwise from java.awt.Robot + ImageIO, which is several times slower.
 */
public final class ScreenStreamer {
    public static final String BOUNDARY = "wiiuuframe";
    private static final String OS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    private static final long IDLE_STOP_MS = 4000;

    private final Config config;
    private final Supplier<Game> currentGame;
    private final WindowLocator windows = new WindowLocator();
    private final Robot robot;
    private final String ffmpeg;
    private final Map<String, Channel> channels = new ConcurrentHashMap<>();
    private volatile boolean mouseDown;
    private volatile boolean captureBlocked;
    /** macOS ScreenCaptureKit helper, once compiled (see resources/mac/capture.swift) */
    private volatile java.nio.file.Path macHelper;
    /** macOS sound helper (resources/mac/audio.swift), once compiled */
    private volatile java.nio.file.Path macAudioHelper;
    private volatile java.util.function.Consumer<String> onBlocked = m -> { };

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
        String backend = config.get("stream.backend", "auto").trim().toLowerCase(Locale.ROOT);
        // Linux / Windows: ffmpeg when installed. macOS: ffmpeg's AVFoundation screen capture no longer
        // works on current macOS, so the ScreenCaptureKit helper is used there (ffmpeg only on request).
        // Whatever fails falls back to Java capture automatically.
        boolean useFfmpeg = backend.equals("ffmpeg") || (backend.equals("auto") && !OS.contains("mac"));
        if (OS.contains("mac") && !backend.equals("java") && !backend.equals("ffmpeg")) {
            Thread t = new Thread(this::buildMacHelper, "build-capture-helper");
            t.setDaemon(true);
            t.start();
        }
        this.ffmpeg = useFfmpeg ? findFfmpeg(config.get("stream.ffmpeg", "ffmpeg")) : null;
        System.out.println("[screen] GamePad streaming via " + (ffmpeg != null ? "ffmpeg (" + ffmpeg + ")"
                : "Java (install ffmpeg for a much higher frame rate)"));
    }

    public boolean available() {
        return robot != null;
    }

    /** The compiled macOS sound helper, or null (not macOS, not built yet, or failed). */
    public java.nio.file.Path macAudioHelper() {
        return macAudioHelper;
    }

    /** True while the OS hands us only black pictures (macOS without Screen Recording permission). */
    public boolean captureBlocked() {
        return captureBlocked;
    }

    public void setOnBlocked(java.util.function.Consumer<String> onBlocked) {
        this.onBlocked = onBlocked;
    }

    static String blockedMessage() {
        return OS.contains("mac")
                ? "The Mac sends a black picture: macOS is blocking screen capture. On the Mac open System Settings > "
                  + "Privacy & Security > Screen Recording, turn on WII-UU (or Java / Terminal if you start it from there), "
                  + "then restart WII-UU."
                : "The PC sends only a black picture. Screen capture may be blocked (Wayland-only session?).";
    }

    /** Samples a grid of pixels: a capture without permission comes back completely black. */
    static boolean isBlack(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        for (int y = 0; y < 12; y++) {
            for (int x = 0; x < 16; x++) {
                int rgb = img.getRGB(x * (w - 1) / 15, y * (h - 1) / 11);
                if (((rgb >> 16) & 0xFF) > 10 || ((rgb >> 8) & 0xFF) > 10 || (rgb & 0xFF) > 10) return false;
            }
        }
        return true;
    }

    public String backend() {
        return ffmpeg != null ? "ffmpeg" : macHelper != null ? "screencapturekit" : "java";
    }

    /** The second-screen profile for the running game, or null. */
    public ScreenProfile secondScreen() {
        Game g = currentGame.get();
        return g == null ? null : ScreenProfile.forSystem(config, g.system());
    }

    // ---- phones watching ----------------------------------------------------------------

    /**
     * Writes an endless multipart JPEG stream until the phone disconnects.
     * The caller has already sent headers with content type {@code multipart/x-mixed-replace}.
     */
    public void stream(String mode, OutputStream out) throws IOException {
        Channel ch = channels.compute(mode, (k, c) -> c != null && c.alive() ? c : new Channel(k));
        ch.join();
        try {
            long seen = 0;          // frame 0 is the empty placeholder: wait for a real picture first
            while (!Thread.currentThread().isInterrupted()) {
                byte[] frame;
                synchronized (ch) {
                    long deadline = System.currentTimeMillis() + 2000;
                    while (ch.seq == seen && System.currentTimeMillis() < deadline) {
                        ch.wait(Math.max(1, deadline - System.currentTimeMillis()));
                    }
                    if (ch.seq == seen) continue;     // keep waiting; nothing new
                    seen = ch.seq;
                    frame = ch.frame;
                }
                out.write(("--" + BOUNDARY + "\r\nContent-Type: image/jpeg\r\nContent-Length: " + frame.length
                        + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                out.write(frame);
                out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            ch.leave();
        }
    }

    /** One pulled frame: its sequence number and JPEG bytes. */
    public record Frame(long seq, byte[] jpeg) {}

    /**
     * Returns the newest frame once it differs from {@code after} (the phone's last one), waiting up
     * to {@code waitMs}; null when nothing new arrived. The phone asks for the next frame only after
     * the previous one has arrived, so frames never queue up in the network: that is what keeps the
     * delay low, instead of letting a continuous stream fill buffers whenever Wi-Fi hiccups.
     */
    public Frame nextFrame(String mode, long after, long waitMs) throws InterruptedException {
        Channel ch = channels.compute(mode, (k, c) -> c != null && c.alive() ? c : new Channel(k));
        ch.join();
        try {
            synchronized (ch) {
                long deadline = System.currentTimeMillis() + waitMs;
                // seq 0 is the empty placeholder; "!=" (not ">") also copes with a restarted channel
                while ((ch.seq == 0 || ch.seq == after) && System.currentTimeMillis() < deadline) {
                    ch.wait(Math.max(1, deadline - System.currentTimeMillis()));
                }
                return ch.seq == 0 || ch.seq == after ? null : new Frame(ch.seq, ch.frame);
            }
        } finally {
            ch.leave();
        }
    }

    /** A tap on the phone: x/y are fractions of the streamed picture; state 1 = down, 2 = move, 0 = up. */
    public synchronized void touch(String mode, double x, double y, int state) {
        Channel ch = channels.get(mode);
        Rectangle r = ch == null ? null : ch.region;
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

    // ---- regions ------------------------------------------------------------------------

    /** @return the screen rectangle for a mode right now, or null (with {@code why} filled in) */
    private Rectangle region(String mode, String[] why) {
        if (robot == null) {
            why[0] = "Screen capture is not available on this PC";
            return null;
        }
        if ("second".equals(mode)) {
            ScreenProfile p = secondScreen();
            if (p == null) {
                why[0] = "This game has no second screen";
                return null;
            }
            Rectangle win = windows.find(p.windowRegex());
            if (win == null) {
                Game g = currentGame.get();
                why[0] = "Waiting for the \"" + p.windowRegex() + "\" window"
                        + (g != null && "wiiu".equals(g.system().id()) ? " (Cemu: View > Separate GamePad view)" : "");
                return null;
            }
            return clip(p.locate(win));
        }
        // TV while a game runs: just the emulator's window (much less to capture than a whole Retina
        // desktop, and no other apps around it); otherwise the whole screen
        Game g = currentGame.get();
        if (g != null && config.getBool("stream.tvFollowsGame", true)) {
            Rectangle w = windows.find(java.util.regex.Pattern.quote(g.name()));
            if (w == null) w = windows.find(java.util.regex.Pattern.quote(g.system().emulator()));
            if (w == null) w = windows.find("^RetroArch");               // RetroArch mode
            if (w != null && w.width >= 200 && w.height >= 150) {
                Rectangle c = clip(w);
                if (c != null) return c;
            }
        }
        return clip(GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice()
                .getDefaultConfiguration().getBounds());
    }

    private static Rectangle clip(Rectangle r) {
        Rectangle screen = new Rectangle();
        for (var dev : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            screen = screen.union(dev.getDefaultConfiguration().getBounds());
        }
        Rectangle c = r.intersection(screen);
        if (c.width < 8 || c.height < 8) return null;
        // even sizes keep video encoders happy
        return new Rectangle(c.x, c.y, c.width & ~1, c.height & ~1);
    }

    // ---- one shared capture per mode --------------------------------------------------------

    private final class Channel {
        final String mode;
        volatile Rectangle region;
        byte[] frame = new byte[0];
        long seq;
        private int viewers;
        private long lastViewer = System.currentTimeMillis();
        private volatile boolean stopped;
        private volatile Process process;
        private final Thread producer, watcher;

        Channel(String mode) {
            this.mode = mode;
            producer = new Thread(this::produce, "stream-" + mode);
            producer.setDaemon(true);
            watcher = new Thread(this::watch, "stream-" + mode + "-region");
            watcher.setDaemon(true);
            String[] why = new String[1];
            region = region(mode, why);
            if (region == null) publish(message(why[0]));
            producer.start();
            watcher.start();
        }

        boolean alive() {
            return !stopped;
        }

        synchronized void join() {
            viewers++;
        }

        synchronized void leave() {
            viewers--;
            lastViewer = System.currentTimeMillis();
        }

        private synchronized boolean idle() {
            return viewers <= 0 && System.currentTimeMillis() - lastViewer > IDLE_STOP_MS;
        }

        synchronized void publish(byte[] jpeg) {
            frame = jpeg;
            seq++;
            notifyAll();
        }

        /** Looks up the window once a second, off the frame path; restarts capture when it moves. */
        private void watch() {
            String[] why = new String[1];
            while (!stopped) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    return;
                }
                if (idle()) {
                    stopped = true;
                    channels.remove(mode, this);
                    Process p = process;
                    if (p != null) p.destroy();
                    return;
                }
                Rectangle now = region(mode, why);
                if (!java.util.Objects.equals(now, region)) {
                    region = now;
                    Process p = process;
                    if (p != null) p.destroy();          // producer restarts ffmpeg with the new area
                    if (now == null) publish(message(why[0]));
                }
            }
        }

        private boolean javaFallback;
        private boolean helperFailed;
        private int helperFailures;
        private boolean sawPicture;
        private int ffmpegFailures;

        private void produce() {
            int fps = Math.max(1, Math.min(60, config.getInt("stream.fps", 30)));
            while (!stopped) {
                Rectangle r = region;
                if (r == null) {
                    sleep(300);
                    continue;
                }
                if (macHelper != null && !helperFailed) {
                    if (runMacHelper(r, fps)) {
                        helperFailures = 0;
                    } else if (++helperFailures >= 2) {
                        helperFailed = true;
                        System.err.println("[screen] macOS capture helper gave no picture (see " + captureLog() + "); using Java capture");
                    } else {
                        sleep(500);
                    }
                } else if (ffmpeg != null && !javaFallback) {
                    if (runFfmpeg(r, fps)) {
                        ffmpegFailures = 0;
                    } else if (++ffmpegFailures >= 2 && robot != null) {
                        // ffmpeg can't capture here (no X11, missing permission, ...): never leave the phone blank
                        javaFallback = true;
                        System.err.println("[screen] ffmpeg produced no picture (" + lastLine(captureLog()) + "); using Java capture for " + mode);
                    } else {
                        sleep(500);
                    }
                } else {
                    captureWithJava(r, fps);
                }
            }
        }

        /** Streams frames from one ffmpeg run until it exits (window moved, idle, error). */
        private boolean runFfmpeg(Rectangle r, int fps) {
            int maxW = Math.max(160, config.getInt("stream.maxWidth", 854));
            int quality = Math.max(1, Math.min(100, config.getInt("stream.quality", 60)));
            int q = Math.round(2 + (100 - quality) * 0.15f);          // 60 -> 8 on ffmpeg's 2 (best)..31 scale
            String crop = "";
            List<String> cmd = new ArrayList<>(List.of(ffmpeg, "-hide_banner", "-loglevel", "error",
                    "-fflags", "nobuffer", "-probesize", "32", "-analyzeduration", "0"));
            if (OS.contains("win")) {
                cmd.addAll(List.of("-f", "gdigrab", "-draw_mouse", "0", "-framerate", Integer.toString(fps),
                        "-offset_x", Integer.toString(r.x), "-offset_y", Integer.toString(r.y),
                        "-video_size", r.width + "x" + r.height, "-i", "desktop"));
            } else if (OS.contains("mac")) {
                // AVFoundation captures the main display in device pixels (2x on Retina): crop there
                double k = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice()
                        .getDefaultConfiguration().getDefaultTransform().getScaleX();
                crop = "crop=" + even(r.width * k) + ":" + even(r.height * k) + ":" + Math.round(r.x * k) + ":" + Math.round(r.y * k) + ",";
                cmd.addAll(List.of("-f", "avfoundation", "-capture_cursor", "0", "-framerate", Integer.toString(fps),
                        "-i", "Capture screen 0:none"));
            } else {
                String display = System.getenv("DISPLAY");
                if (display == null || display.isBlank()) display = ":0";
                cmd.addAll(List.of("-f", "x11grab", "-draw_mouse", "0", "-framerate", Integer.toString(fps),
                        "-video_size", r.width + "x" + r.height, "-i", display + "+" + r.x + "," + r.y));
            }
            cmd.addAll(List.of("-an", "-vf", crop + "scale='trunc(min(" + maxW + ",iw)/2)*2':-2",
                    "-pix_fmt", "yuvj420p", "-q:v", Integer.toString(q), "-f", "mjpeg", "-"));
            Process p;
            try {
                p = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.appendTo(captureLog().toFile())).start();
            } catch (IOException e) {
                System.err.println("[screen] ffmpeg failed to start: " + e.getMessage());
                return false;
            }
            process = p;
            boolean gotFrame = false;
            try (InputStream in = p.getInputStream()) {
                gotFrame = splitJpegs(in);
            } catch (IOException ignored) {
                // ffmpeg stopped
            } finally {
                p.destroy();
                process = null;
            }
            return gotFrame;
        }

        /** ffmpeg's MJPEG output is back-to-back JPEGs: cut at each end-of-image marker. */
        private boolean splitJpegs(InputStream in) throws IOException {
            byte[] buf = new byte[1 << 16];
            ByteArrayOutputStream cur = new ByteArrayOutputStream(1 << 17);
            boolean any = false;
            int prev = -1, n;
            while (!stopped && (n = in.read(buf)) > 0) {
                int start = 0;
                for (int i = 0; i < n; i++) {
                    int b = buf[i] & 0xFF;
                    if (prev == 0xFF && b == 0xD9) {                    // EOI
                        cur.write(buf, start, i + 1 - start);
                        byte[] jpeg = cur.toByteArray();
                        if (jpeg.length > 4 && (jpeg[0] & 0xFF) == 0xFF && (jpeg[1] & 0xFF) == 0xD8) {
                            publish(jpeg);
                            any = true;
                        }
                        cur.reset();
                        start = i + 1;
                        prev = -1;
                        continue;
                    }
                    prev = b;
                }
                cur.write(buf, start, n - start);
            }
            return any;
        }

        /** macOS: frames from the ScreenCaptureKit helper (4-byte length + JPEG each) until it exits. */
        private boolean runMacHelper(Rectangle r, int fps) {
            int maxW = Math.max(160, config.getInt("stream.maxWidth", 854));
            double quality = Math.max(0.2, Math.min(0.95, config.getInt("stream.quality", 60) / 100.0));
            Process p;
            try {
                p = new ProcessBuilder(macHelper.toString(), Integer.toString(r.x), Integer.toString(r.y),
                        Integer.toString(r.width), Integer.toString(r.height), Integer.toString(maxW),
                        Integer.toString(fps), Double.toString(quality))
                        .redirectError(ProcessBuilder.Redirect.appendTo(captureLog().toFile())).start();
            } catch (IOException e) {
                return false;
            }
            process = p;
            boolean any = false;
            try (java.io.DataInputStream in = new java.io.DataInputStream(new java.io.BufferedInputStream(p.getInputStream(), 1 << 16))) {
                while (!stopped) {
                    int len = in.readInt();
                    if (len <= 0 || len > 16 << 20) break;
                    byte[] jpeg = new byte[len];
                    in.readFully(jpeg);
                    publish(jpeg);
                    any = true;
                    sawPicture = true;
                    captureBlocked = false;
                }
            } catch (IOException ignored) {
                // helper stopped (area changed, idle, error)
            } finally {
                p.destroy();
                process = null;
            }
            if (!any) System.err.println("[screen] macOS capture helper: " + lastLine(captureLog()));
            return any;
        }

        /**
         * Java capture: Robot + ImageIO JPEG, until the region changes or the channel stops.
         * Capturing overlaps with encoding on up to three threads, so the frame rate is limited by
         * the slower of the two steps rather than their sum; late frames are dropped, never sent out of order.
         */
        private void captureWithJava(Rectangle r, int fps) {
            int maxW = Math.max(160, config.getInt("stream.maxWidth", 640));
            float quality = Math.max(0.2f, Math.min(0.95f, config.getInt("stream.quality", 60) / 100f));
            long frameNanos = 1_000_000_000L / fps;
            int encoders = Math.max(1, Math.min(3, Runtime.getRuntime().availableProcessors() - 1));
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(encoders, t -> {
                Thread th = new Thread(t, "stream-" + mode + "-jpeg");
                th.setDaemon(true);
                return th;
            });
            java.util.concurrent.Semaphore free = new java.util.concurrent.Semaphore(encoders);
            java.util.concurrent.atomic.AtomicLong newestSent = new java.util.concurrent.atomic.AtomicLong(-1);
            ThreadLocal<ImageWriter> writers = ThreadLocal.withInitial(() -> ImageIO.getImageWritersByFormatName("jpeg").next());
            long captured = 0;
            int blackInARow = 0;
            try {
                while (!stopped && r.equals(region) && !(macHelper != null && !helperFailed)) {
                    long start = System.nanoTime();
                    free.acquire();
                    BufferedImage img;
                    try {
                        img = scale(robot.createScreenCapture(r), maxW);
                    } catch (RuntimeException e) {
                        free.release();
                        throw e;
                    }
                    // A whole desktop that has been pure black from the start means capture is blocked (a game's
                    // second screen can legitimately start black): say what to do instead of streaming black.
                    if (mode.equals("tv") && !sawPicture && isBlack(img)) {
                        if (++blackInARow >= 10) {
                            free.release();
                            if (!captureBlocked) {
                                captureBlocked = true;
                                System.err.println("[screen] capture returns only black - " + blockedMessage());
                                onBlocked.accept(blockedMessage());
                            }
                            publish(message(blockedMessage()));
                            sleep(2000);
                            continue;
                        }
                    } else {
                        sawPicture = true;
                        captureBlocked = false;
                    }
                    long seq = ++captured;
                    pool.execute(() -> {
                        try {
                            ImageWriter writer = writers.get();
                            ImageWriteParam param = writer.getDefaultWriteParam();
                            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                            param.setCompressionQuality(quality);
                            ByteArrayOutputStream buf = new ByteArrayOutputStream(64 * 1024);
                            try (MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(buf)) {
                                writer.setOutput(ios);
                                writer.write(null, new IIOImage(img, null, null), param);
                            }
                            // only publish if no newer frame went out first
                            if (newestSent.getAndAccumulate(seq, Math::max) < seq) publish(buf.toByteArray());
                        } catch (IOException | RuntimeException ignored) {
                            // drop this frame
                        } finally {
                            free.release();
                        }
                    });
                    long left = frameNanos - (System.nanoTime() - start);
                    if (left > 0) sleep(left / 1_000_000);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                sleep(500);
            } finally {
                pool.shutdown();
            }
        }
    }

    // ---- helpers ------------------------------------------------------------------------

    private java.nio.file.Path captureLog() {
        java.nio.file.Path log = config.logDir().resolve("capture.log");
        try {
            java.nio.file.Files.createDirectories(log.getParent());
            if (java.nio.file.Files.exists(log) && java.nio.file.Files.size(log) > 1 << 20) java.nio.file.Files.delete(log);
        } catch (IOException ignored) {
            // logging is best effort
        }
        return log;
    }

    private static String lastLine(java.nio.file.Path log) {
        try {
            List<String> lines = java.nio.file.Files.readAllLines(log);
            for (int i = lines.size() - 1; i >= 0; i--) if (!lines.get(i).isBlank()) return lines.get(i).trim();
        } catch (IOException | RuntimeException ignored) {
            // no log
        }
        return "no details";
    }

    /**
     * macOS: compiles the ScreenCaptureKit helpers with the Xcode Command Line Tools (installed with
     * Homebrew) the first time, and again only when their source changes: first the picture, then
     * the sound (a separate program, so a problem with sound never costs the picture).
     */
    private void buildMacHelper() {
        try {
            Process check = new ProcessBuilder("xcode-select", "-p").redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (!check.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) || check.exitValue() != 0) {
                System.out.println("[screen] for a smooth GamePad screen and sound install the Xcode Command Line Tools: xcode-select --install");
                return;
            }
        } catch (Exception e) {
            System.err.println("[screen] capture helper unavailable: " + e.getMessage());
            return;
        }
        macHelper = buildSwift("wiiuu-capture", "capture");
        if (macHelper != null) System.out.println("[screen] GamePad streaming via ScreenCaptureKit");
        macAudioHelper = buildSwift("wiiuu-audio", "audio");
    }

    /** Compiles resources/mac/{source}.swift to ~/.wiiuu/bin/{exeName} unless already up to date. */
    private java.nio.file.Path buildSwift(String exeName, String source) {
        java.nio.file.Path dir = config.home().resolve("bin");
        java.nio.file.Path exe = dir.resolve(exeName), stamp = dir.resolve(exeName + ".sha256");
        java.nio.file.Path buildLog = config.logDir().resolve(source + "-build.log");
        try (InputStream res = ScreenStreamer.class.getResourceAsStream("/mac/" + source + ".swift")) {
            if (res == null) return null;
            byte[] src = res.readAllBytes();
            String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(src));
            if (java.nio.file.Files.isExecutable(exe) && java.nio.file.Files.exists(stamp)
                    && java.nio.file.Files.readString(stamp).trim().equals(hash)) {
                return exe;
            }
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Files.createDirectories(buildLog.getParent());
            java.nio.file.Path swift = dir.resolve(source + ".swift");
            java.nio.file.Files.write(swift, src);
            System.out.println("[screen] building the macOS " + (source.equals("audio") ? "sound" : "capture")
                    + " helper (first start only, about a minute)...");
            Process p = new ProcessBuilder("xcrun", "swiftc", "-O", "-swift-version", "5", "-o", exe.toString(), swift.toString())
                    .redirectErrorStream(true).redirectOutput(buildLog.toFile()).start();
            if (p.waitFor(300, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0 && java.nio.file.Files.isExecutable(exe)) {
                java.nio.file.Files.writeString(stamp, hash);
                return exe;
            }
            p.destroyForcibly();
            System.err.println("[screen] could not build the " + source + " helper (see " + buildLog + "): " + lastLine(buildLog));
        } catch (Exception e) {
            System.err.println("[screen] " + source + " helper unavailable: " + e.getMessage());
        }
        return null;
    }

    private static long even(double v) {
        return Math.round(v) & ~1L;
    }

    static String findFfmpeg(String configured) {
        if (configured.contains(File.separator) || configured.contains("/")) {
            return new File(configured).canExecute() ? configured : null;
        }
        String exe = OS.contains("win") && !configured.endsWith(".exe") ? configured + ".exe" : configured;
        String path = System.getenv("PATH");
        if (path == null) path = "";
        // apps started from Finder get a minimal PATH without Homebrew / MacPorts
        path += File.pathSeparator + "/opt/homebrew/bin" + File.pathSeparator + "/usr/local/bin" + File.pathSeparator + "/opt/local/bin";
        for (String dir : path.split(File.pathSeparator)) {
            File f = new File(dir, exe);
            if (f.canExecute()) return f.getAbsolutePath();
        }
        return null;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(Math.max(1, ms));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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

    private static byte[] message(String text) {
        BufferedImage img = new BufferedImage(640, 360, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(0x1c1f24));
        g.fillRect(0, 0, 640, 360);
        g.setColor(new Color(0x9aa3ad));
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 20));
        var fm = g.getFontMetrics();
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String w : text.split(" ")) {
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
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(img, "jpeg", out);
        } catch (IOException ignored) {
            // in-memory write cannot fail
        }
        return out.toByteArray();
    }
}
