package wiiuu.screen;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Sound capture on macOS 13+ through ScreenCaptureKit, in an Objective-C library called with JNI
 * (native/mac/wiiuu_audio.m). The library is built once, by GitHub Actions on a Mac, for Apple
 * Silicon and Intel, and ships in the jar as /mac/libwiiuu-audio.dylib, so unlike the Swift
 * helper it needs no Xcode Command Line Tools on the Mac.
 *
 * <p>It runs in a small Java process of its own ({@link #main}), started by {@link AudioStreamer}
 * like the other capture commands: it writes raw 16-bit stereo PCM to stdout, and a problem in
 * native code can only end that process, never WII-UU.
 */
public final class MacAudio {
    private static final String LIBRARY = "libwiiuu-audio.dylib";

    private MacAudio() {}

    static native String start(int rate);

    static native int read(byte[] buffer, int timeoutMs);

    static native String error();

    static native void stop();

    static native int version();

    /**
     * The command that captures sound with the library: this Java running this class, or null when
     * this WII-UU has no library for the Mac (built without it) or the Mac is older than macOS 13.
     */
    static List<String> command(Path binDir, int rate) {
        if (!macOs13()) return null;
        Path lib = unpack(binDir);
        if (lib == null) return null;
        String java = ProcessHandle.current().info().command()
                .orElse(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        // the class path as absolute paths, so the child finds the classes whatever its directory
        List<String> cp = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path", "").split(File.pathSeparator)) {
            if (!entry.isEmpty()) cp.add(new File(entry).getAbsolutePath());
        }
        return List.of(java, "-Xmx24m", "-XX:+UseSerialGC", "-Djava.awt.headless=true", "-Dapple.awt.UIElement=true",
                "-cp", String.join(File.pathSeparator, cp), MacAudio.class.getName(), Integer.toString(rate), lib.toString());
    }

    /** Whether this WII-UU carries the library (resources/mac, from the Mac build). */
    static boolean shipped() {
        return MacAudio.class.getResource("/mac/" + LIBRARY) != null;
    }

    private static boolean macOs13() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")) return false;
        try {
            return Integer.parseInt(System.getProperty("os.version", "0").split("\\.")[0]) >= 13;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** The library from the jar in {@code binDir}, written again only when it changed. */
    private static Path unpack(Path binDir) {
        try (InputStream in = MacAudio.class.getResourceAsStream("/mac/" + LIBRARY)) {
            if (in == null) return null;
            byte[] bytes = in.readAllBytes();
            Path lib = binDir.resolve(LIBRARY);
            if (Files.exists(lib) && java.util.Arrays.equals(Files.readAllBytes(lib), bytes)) return lib;
            Files.createDirectories(binDir);
            Path tmp = Files.createTempFile(binDir, "libwiiuu-audio", ".tmp");
            Files.write(tmp, bytes);
            Files.move(tmp, lib, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return lib;
        } catch (IOException e) {
            System.err.println("[audio] could not unpack the Mac sound library: " + e.getMessage());
            return null;
        }
    }

    /**
     * The capture process: {@code MacAudio <rate> <library>} writes raw PCM to stdout until WII-UU
     * stops reading; {@code MacAudio --check <library>} only loads the library (for the build).
     */
    public static void main(String[] args) throws IOException {
        if (args.length == 2 && args[0].equals("--check")) {
            System.load(new File(args[1]).getAbsolutePath());
            System.out.println("libwiiuu-audio " + version() + " loaded on " + System.getProperty("os.arch"));
            return;
        }
        int rate = Integer.parseInt(args[0]);
        System.load(new File(args[1]).getAbsolutePath());
        String problem = start(rate);
        if (problem != null) {
            System.err.println(problem);
            System.exit(1);
        }
        OutputStream out = new FileOutputStream(FileDescriptor.out);
        byte[] buffer = new byte[rate / 50 * 4];                       // 20 ms
        try {
            while (true) {
                int n = read(buffer, 1000);
                if (n < 0) {
                    String why = error();
                    System.err.println(why != null ? why : "sound capture stopped");
                    System.exit(1);
                }
                if (n > 0) {
                    out.write(buffer, 0, n);
                    out.flush();
                }
            }
        } catch (IOException readerGone) {
            // WII-UU stopped listening
            stop();
            System.exit(0);
        }
    }
}
