package wiiuu.screen;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * Windows: part of a window's own picture, live (resources/win/wincap.cs). Split DS/3DS screens
 * use it because RetroArch draws with the GPU, and on a virtual display copying the screen only
 * gets black or stale pictures; the window's own picture (what Alt+Tab shows) is always there.
 *
 * <p>The helper is compiled once on the PC with the .NET Framework's C# compiler, which every
 * Windows 10/11 has.
 */
public final class WinCapture implements AutoCloseable {
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    private static volatile Path exe;
    private static volatile boolean buildFailed;

    private final Process process;
    private final DataInputStream in;
    public final int width, height;
    private final byte[] raw;

    private WinCapture(Process process, DataInputStream in, int width, int height) {
        this.process = process;
        this.in = in;
        this.width = width;
        this.height = height;
        this.raw = new byte[width * height * 4];
    }

    /** The helper, built on first use; null when it can't be built (not Windows, no compiler). */
    static synchronized Path helper(Path dir) {
        if (!WINDOWS || buildFailed) return null;
        if (exe != null && Files.isExecutable(exe)) return exe;
        try (InputStream src = WinCapture.class.getResourceAsStream("/win/wincap.cs")) {
            if (src == null) return null;
            byte[] code = src.readAllBytes();
            String hash = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(code)).substring(0, 12);
            Files.createDirectories(dir);
            Path out = dir.resolve("wiiuu-wincap-" + hash + ".exe"), cs = dir.resolve("wiiuu-wincap-" + hash + ".cs");
            if (Files.exists(out)) return exe = out;
            Files.write(cs, code);
            String win = System.getenv().getOrDefault("WINDIR", "C:\\Windows");
            for (String fw : new String[]{"Framework64", "Framework"}) {
                Path csc = Path.of(win, "Microsoft.NET", fw, "v4.0.30319", "csc.exe");
                if (!Files.exists(csc)) continue;
                List<String> res = WinScript.exec(120, List.of(csc.toString(), "/nologo", "/target:exe", "/optimize+", "/out:" + out,
                        "/r:System.Drawing.dll", "/r:System.Windows.Forms.dll", cs.toString()));
                if (Files.exists(out)) {
                    VirtualDisplay.note("window capture helper built: " + out);
                    return exe = out;
                }
                VirtualDisplay.note("could not build the window capture helper: " + String.join(" ", res));
            }
        } catch (IOException | java.security.NoSuchAlgorithmException e) {
            VirtualDisplay.note("could not build the window capture helper: " + e.getMessage());
        }
        buildFailed = true;
        return null;
    }

    /**
     * Starts streaming the part (fractions x, y, w, h of the client area) of the largest window
     * whose "title [program]" matches {@code regex}.
     *
     * @return null when the helper is missing or the window isn't there (yet)
     */
    static WinCapture open(Path dir, String regex, double x, double y, double w, double h, int fps) {
        Path helper = helper(dir);
        if (helper == null) return null;
        try {
            Process p = new ProcessBuilder(helper.toString(), regex, num(x), num(y), num(w), num(h), Integer.toString(fps))
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(p.getInputStream(), 1 << 20));
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            for (int c; (c = in.read()) != -1 && c != '\n'; ) line.write(c);
            String[] wh = line.toString(StandardCharsets.US_ASCII).trim().split(" ");
            if (wh.length != 2) {
                p.destroyForcibly();
                return null;
            }
            return new WinCapture(p, in, Integer.parseInt(wh[0]), Integer.parseInt(wh[1]));
        } catch (IOException | NumberFormatException e) {
            return null;
        }
    }

    /**
     * Shows the part of the window on the TV ({@code tv}: real pixels) in a window of the helper's
     * own: no Java scaling in between. Null when the helper is missing.
     */
    static Process show(Path dir, java.awt.Rectangle tv, String regex, double x, double y, double w, double h, int fps) {
        Path helper = helper(dir);
        if (helper == null) return null;
        try {
            return new ProcessBuilder(helper.toString(), "--show", "" + tv.x, "" + tv.y, "" + tv.width, "" + tv.height,
                    regex, num(x), num(y), num(w), num(h), Integer.toString(fps))
                    .redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        } catch (IOException e) {
            return null;
        }
    }

    private static String num(double v) {
        return String.format(Locale.ROOT, "%.5f", v);
    }

    /** The next frame into {@code into} (TYPE_3BYTE_BGR, width x height), or a new image when null. */
    BufferedImage next(BufferedImage into) throws IOException {
        in.readFully(raw);
        BufferedImage img = into != null && into.getWidth() == width && into.getHeight() == height && into.getType() == BufferedImage.TYPE_3BYTE_BGR
                ? into : new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
        byte[] bgr = ((DataBufferByte) img.getRaster().getDataBuffer()).getData();
        for (int i = 0, j = 0; i < raw.length; i += 4, j += 3) {          // BGRA -> BGR
            bgr[j] = raw[i];
            bgr[j + 1] = raw[i + 1];
            bgr[j + 2] = raw[i + 2];
        }
        return img;
    }

    /** A tap on the part being streamed: state 1 down, 2 move, 0 up; x, y fractions of the part. */
    synchronized void tap(int state, double x, double y) {
        try {
            var out = process.getOutputStream();
            out.write(("t " + state + " " + num(x) + " " + num(y) + "\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
        } catch (IOException ignored) {
            // the helper is gone; the next frame read ends the stream
        }
    }

    @Override
    public void close() {
        process.destroyForcibly();
    }
}
