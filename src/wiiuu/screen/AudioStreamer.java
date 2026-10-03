package wiiuu.screen;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import wiiuu.core.Config;

/**
 * Captures what the PC plays and hands it to phones as raw 16-bit stereo PCM, in 20 ms chunks.
 *
 * <p>Like the picture, the phone pulls: it asks for everything newer than the last chunk it has,
 * so sound never queues up in the network. Capture runs only while someone listens.
 * Sources: ScreenCaptureKit on macOS 13+ (the Objective-C library shipped in the jar, see
 * {@link MacAudio}, else the Swift helper built on the Mac), the PulseAudio / PipeWire monitor of
 * the default output on Linux ({@code parec}, or ffmpeg), and ffmpeg + a loopback device on Windows.
 */
public final class AudioStreamer {
    public static final int RATE = 48000, CHANNELS = 2;
    private static final int CHUNK = RATE / 50 * CHANNELS * 2;       // 20 ms
    private static final int KEEP = 25;                              // chunks kept (0.5 s)
    private static final long IDLE_STOP_MS = 4000;
    private static final String OS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

    private final Config config;
    private final Supplier<Path> macHelper;

    // guarded by this
    private final byte[][] ring = new byte[KEEP][];
    private long seq;                     // number of the newest chunk
    private long lastListener;
    private Thread capture;
    private volatile Process process;
    private volatile String problem;      // why there is no sound, for the phone
    private int nativeFailures;           // the Mac library gave no sound this many times in a row

    public AudioStreamer(Config config, Supplier<Path> macHelper) {
        this.config = config;
        this.macHelper = macHelper;
    }

    /** Why sound is unavailable right now, or null. */
    public String problem() {
        return problem;
    }

    /** Sound chunks newer than {@code after} (all concatenated) and the newest chunk's number. */
    public record Chunk(long seq, byte[] pcm) {}

    /**
     * Waits up to {@code waitMs} for sound newer than {@code after}; returns null if none came.
     * {@code after} = -1 starts listening now. A phone that fell far behind gets only the newest
     * part, keeping the delay low.
     */
    public synchronized Chunk next(long after, long waitMs) throws InterruptedException {
        lastListener = System.currentTimeMillis();
        ensureRunning();
        if (after < 0) after = seq;
        long deadline = System.currentTimeMillis() + waitMs;
        while (seq == after && System.currentTimeMillis() < deadline) {
            wait(Math.max(1, deadline - System.currentTimeMillis()));
        }
        lastListener = System.currentTimeMillis();
        if (seq == after) return null;
        // after > seq: the phone listened to an earlier WII-UU run - start over from the newest chunk
        long from = after > seq ? seq - 1 : Math.max(after, seq - 4);  // at most 80 ms at once
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (long s = from + 1; s <= seq; s++) {
            byte[] c = ring[(int) (s % KEEP)];
            if (c != null) out.write(c, 0, c.length);
        }
        return new Chunk(seq, out.toByteArray());
    }

    private synchronized void publish(byte[] chunk) {
        seq++;
        ring[(int) (seq % KEEP)] = chunk;
        notifyAll();
    }

    private synchronized boolean idle() {
        return System.currentTimeMillis() - lastListener > IDLE_STOP_MS;
    }

    private void ensureRunning() {
        if (capture != null && capture.isAlive()) return;
        capture = new Thread(this::run, "audio-capture");
        capture.setDaemon(true);
        capture.start();
    }

    private void run() {
        int failures = 0;
        while (!idle()) {
            List<String> cmd = command();
            if (cmd == null) {
                sleep(1000);
                continue;
            }
            long started = System.currentTimeMillis();
            boolean any = pump(cmd);
            if (cmd.contains(MacAudio.class.getName())) {
                nativeFailures = any ? 0 : nativeFailures + 1;
                if (nativeFailures == 2) System.err.println("[audio] the Mac sound library gave no sound twice: trying the Swift helper");
            }
            if (any) {
                problem = null;
                failures = 0;
            } else if (++failures >= 3 && System.currentTimeMillis() - started < 3000) {
                System.err.println("[audio] no sound from " + cmd.get(0) + ": " + lastLine());
                sleep(5000);
            }
            sleep(300);
        }
        Process p = process;
        if (p != null) p.destroy();
    }

    /** Runs one capture process until it ends or nobody listens; returns whether it gave sound. */
    private boolean pump(List<String> cmd) {
        Path log = config.logDir().resolve("audio.log");
        Process p;
        try {
            Files.createDirectories(log.getParent());
            p = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.appendTo(log.toFile())).start();
        } catch (IOException e) {
            problem = "Sound capture could not start: " + e.getMessage();
            return false;
        }
        process = p;
        boolean any = false;
        Thread stopper = new Thread(() -> {        // stop capture once nobody listens
            while (p.isAlive()) {
                if (idle()) {
                    p.destroy();
                    return;
                }
                sleep(500);
            }
        }, "audio-idle");
        stopper.setDaemon(true);
        stopper.start();
        try (InputStream raw = p.getInputStream(); DataInputStream in = new DataInputStream(raw)) {
            while (true) {
                byte[] chunk = new byte[CHUNK];
                in.readFully(chunk);
                publish(chunk);
                any = true;
            }
        } catch (IOException ended) {
            // process stopped
        } finally {
            p.destroy();
            process = null;
        }
        if (!any) problem = "No sound from the PC: " + lastLine();
        return any;
    }

    private String lastLine() {
        try {
            List<String> lines = Files.readAllLines(config.logDir().resolve("audio.log"));
            for (int i = lines.size() - 1; i >= 0; i--) if (!lines.get(i).isBlank()) return lines.get(i).trim();
        } catch (IOException | RuntimeException ignored) {
            // no log
        }
        return "no details";
    }

    /** The capture command for this OS, or null (the reason is in {@link #problem}). */
    private List<String> command() {
        String custom = config.get("audio.command", "").trim();
        if (!custom.isEmpty()) return List.of("sh", "-c", custom);
        if (OS.contains("mac")) {
            // the library from the jar first (no compiler needed); the Swift helper if it keeps failing
            if (nativeFailures < 2) {
                List<String> lib = MacAudio.command(config.home().resolve("bin"), RATE);
                if (lib != null) return lib;
            }
            Path helper = macHelper.get();
            if (helper == null) {
                problem = nativeFailures >= 2
                        ? "No sound from the Mac: " + lastLine() + " (allow WII-UU, or Java / Terminal, under System Settings > Privacy & Security > Screen Recording)"
                        : "Sound on the Mac needs macOS 13 (Ventura) or newer.";
                return null;
            }
            return List.of(helper.toString(), Integer.toString(RATE));
        }
        String ffmpeg = ScreenStreamer.findFfmpeg(config.get("stream.ffmpeg", "ffmpeg"));
        if (OS.contains("win")) {
            if (ffmpeg == null) {
                problem = "Sound needs ffmpeg on Windows.";
                return null;
            }
            // a loopback recording device: "Stereo Mix" (enable it in Sound settings) or e.g. VB-Cable
            String device = config.get("audio.device", "").trim();
            if (device.isEmpty()) device = windowsLoopback(ffmpeg);
            if (device == null) {
                problem = "Windows: turn on \"Stereo Mix\" (Sound settings > Recording) or set audio.device.";
                return null;
            }
            return ffmpegPcm(ffmpeg, "dshow", "audio=" + device);
        }
        // Linux: record the monitor of the default output (PulseAudio or PipeWire)
        String device = config.get("audio.device", "").trim();
        if (device.isEmpty()) device = pulseMonitor();
        if (onPath("parec")) {
            return List.of("parec", "--device=" + device, "--format=s16le", "--rate=" + RATE,
                    "--channels=" + CHANNELS, "--latency-msec=20", "--raw");
        }
        if (ffmpeg != null) return ffmpegPcm(ffmpeg, "pulse", device);
        problem = "Sound needs PulseAudio / PipeWire tools: install pulseaudio-utils (parec).";
        return null;
    }

    private static List<String> ffmpegPcm(String ffmpeg, String format, String input) {
        List<String> cmd = new ArrayList<>(List.of(ffmpeg, "-hide_banner", "-loglevel", "error", "-nostdin",
                "-fflags", "nobuffer", "-f", format));
        if (format.equals("pulse")) cmd.addAll(List.of("-fragment_size", "3840"));
        if (format.equals("dshow")) cmd.addAll(List.of("-audio_buffer_size", "20"));
        cmd.addAll(List.of("-i", input, "-vn", "-ac", Integer.toString(CHANNELS), "-ar", Integer.toString(RATE),
                "-f", "s16le", "-flush_packets", "1", "pipe:1"));
        return cmd;
    }

    private static String pulseMonitor() {
        String sink = runLine("pactl", "get-default-sink");
        if (sink == null || sink.isBlank()) {
            // older pactl: "Default Sink: name" in pactl info
            String info = String.join("\n", run(5, "pactl", "info"));
            for (String l : info.split("\n")) {
                if (l.startsWith("Default Sink:")) sink = l.substring(13).trim();
            }
        }
        return sink == null || sink.isBlank() ? "@DEFAULT_MONITOR@" : sink.trim() + ".monitor";
    }

    private static String windowsLoopback(String ffmpeg) {
        List<String> out = run(10, ffmpeg, "-hide_banner", "-list_devices", "true", "-f", "dshow", "-i", "dummy");
        for (String l : out) {
            String low = l.toLowerCase(Locale.ROOT);
            if (!low.contains("(audio)")) continue;
            int a = l.indexOf('"'), b = l.indexOf('"', a + 1);
            if (a < 0 || b < 0) continue;
            String name = l.substring(a + 1, b);
            String n = name.toLowerCase(Locale.ROOT);
            if (n.contains("stereo mix") || n.contains("stereomix") || n.contains("what u hear")
                    || n.contains("cable output") || n.contains("loopback") || n.contains("wave out")) return name;
        }
        return null;
    }

    private static boolean onPath(String exe) {
        String path = System.getenv("PATH");
        if (path == null) return false;
        for (String d : path.split(java.io.File.pathSeparator)) {
            if (new java.io.File(d, exe).canExecute()) return true;
        }
        return false;
    }

    private static String runLine(String... cmd) {
        List<String> l = run(5, cmd);
        return l.isEmpty() ? null : l.get(0);
    }

    private static List<String> run(int timeoutSec, String... cmd) {
        List<String> lines = new ArrayList<>();
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            try (var r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
                String l;
                while ((l = r.readLine()) != null) lines.add(l);
            }
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) p.destroyForcibly();
        } catch (IOException e) {
            // tool missing
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return lines;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
