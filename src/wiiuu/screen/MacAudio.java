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
import java.util.Locale;

/**
 * Sound capture on macOS 13+ through ScreenCaptureKit, or a Core Audio tap on macOS 14.2+ when
 * ScreenCaptureKit refuses or doesn't answer, in an Objective-C library called with JNI
 * (native/mac/wiiuu_audio.m). The library is built once, by GitHub Actions on a Mac, for Apple
 * Silicon and Intel, and ships in the jar as /mac/libwiiuu-audio.dylib, so unlike the Swift
 * helper it needs no Xcode Command Line Tools on the Mac.
 *
 * <p>{@link AudioStreamer} loads it into WII-UU itself ({@link #load}), so sound has the same macOS
 * permissions as the picture capture (a second Java process can't even start from WII-UU.app,
 * whose built-in Java has no java command). {@link #main} runs it on its own, for the Mac build's
 * checks.
 */
public final class MacAudio {
    private static final String LIBRARY = "libwiiuu-audio.dylib";
    private static boolean loaded;

    private MacAudio() {}

    /** Starts capturing without waiting for macOS: null, or why it can't even try. */
    static native String start(int rate);

    /** Waits up to timeoutMs for sound: bytes copied, 0 if none came, -1 once capture stopped. */
    static native int read(byte[] buffer, int timeoutMs);

    /** Why capture stopped, or null. */
    static native String error();

    /** Stops capturing (starting again later is fine). */
    static native void stop();

    /** The library's progress notes since the last call (lines), or null. */
    static native String takeNotes();

    static native int version();

    /**
     * Loads the library from the jar (unpacked into {@code binDir}) into this process, once: null
     * when it's ready, else why sound can't use it.
     */
    static synchronized String load(Path binDir) {
        if (loaded) return null;
        if (!macOs13()) return "Sound on the Mac needs macOS 13 (Ventura) or newer.";
        Path lib = unpack(binDir);
        if (lib == null) return "This WII-UU has no Mac sound library.";
        try {
            System.load(lib.toAbsolutePath().toString());
            loaded = true;
            return null;
        } catch (UnsatisfiedLinkError e) {
            return "The Mac sound library did not load: " + e.getMessage();
        }
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
     * On its own, for the Mac build's checks: {@code MacAudio <rate> <library>} writes raw PCM to
     * stdout (and the notes to stderr); {@code MacAudio --check <library>} only loads the library.
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
                takeNotes();                                           // already on stderr
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
            stop();
            System.exit(0);
        }
    }
}
