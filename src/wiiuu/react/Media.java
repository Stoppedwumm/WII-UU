package wiiuu.react;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javax.imageio.ImageIO;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;

/**
 * Video for the editor, through ffmpeg (Java can't decode video itself): a video's length, a
 * picture at any moment, and playing it with sound (pictures and samples decoded by two ffmpeg
 * processes, the pictures timed by the sound card's clock so they stay in sync).
 */
final class Media {
    private Media() {
    }

    private static final String OS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    private static String ffmpeg, ffprobe;

    static synchronized String ffmpeg() {
        if (ffmpeg == null) ffmpeg = find("ffmpeg");
        return ffmpeg.isEmpty() ? null : ffmpeg;
    }

    static synchronized String ffprobe() {
        if (ffprobe == null) ffprobe = find("ffprobe");
        return ffprobe.isEmpty() ? null : ffprobe;
    }

    private static String find(String name) {
        String exe = OS.contains("win") ? name + ".exe" : name;
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(java.io.File.pathSeparator)) {
                Path p = Paths.get(dir, exe);
                if (Files.isExecutable(p)) return p.toString();
            }
        }
        for (String dir : new String[]{"/opt/homebrew/bin", "/usr/local/bin", "/usr/bin", "C:\\ffmpeg\\bin", "C:\\Program Files\\ffmpeg\\bin"}) {
            Path p = Paths.get(dir, exe);
            if (Files.isExecutable(p)) return p.toString();
        }
        return "";
    }

    /** The video's length in seconds, or 0 if unknown. */
    static double duration(Path file) {
        String probe = ffprobe();
        if (probe == null) return 0;
        try {
            Process p = new ProcessBuilder(probe, "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", file.toString())
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            p.waitFor(10, TimeUnit.SECONDS);
            return Double.parseDouble(out.split("\\s+")[0]);
        } catch (IOException | InterruptedException | NumberFormatException | ArrayIndexOutOfBoundsException e) {
            return 0;
        }
    }

    /** The picture at {@code t} seconds, {@code width} pixels wide (null without ffmpeg). */
    static BufferedImage frame(Path file, double t, int width) {
        byte[] png = run(List.of("-ss", String.format(Locale.ROOT, "%.3f", Math.max(0, t)), "-i", file.toString(), "-frames:v", "1",
                "-vf", "scale=" + width + ":-2", "-f", "image2pipe", "-vcodec", "png", "-"));
        if (png == null || png.length == 0) return null;
        try {
            return ImageIO.read(new ByteArrayInputStream(png));
        } catch (IOException e) {
            return null;
        }
    }

    /** A picture for the pack's thumbnail (320 wide), as PNG. */
    static byte[] thumbnailPng(Path file, double t) {
        byte[] png = run(List.of("-ss", String.format(Locale.ROOT, "%.3f", t), "-i", file.toString(), "-frames:v", "1",
                "-vf", "scale=320:-2", "-f", "image2pipe", "-vcodec", "png", "-"));
        return png == null || png.length == 0 ? null : png;
    }

    private static byte[] run(List<String> args) {
        String ff = ffmpeg();
        if (ff == null) return null;
        List<String> cmd = new ArrayList<>(List.of(ff, "-v", "error", "-nostdin"));
        cmd.addAll(args);
        try {
            Process p = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            byte[] out = p.getInputStream().readAllBytes();
            p.waitFor(20, TimeUnit.SECONDS);
            return out;
        } catch (IOException | InterruptedException e) {
            return null;
        }
    }

    /**
     * Plays a video from a moment: pictures go to {@code frames} (on a background thread), the
     * sound to the sound card. {@link #position()} is where it is.
     */
    static final class Player {
        private static final int FPS = 24;
        private final double start;
        private final Process video, audio;
        private final SourceDataLine line;
        private final long begun = System.nanoTime();
        private volatile boolean stopped;

        Player(Path file, double start, int width, Consumer<BufferedImage> frames) throws IOException {
            this.start = start;
            String ff = ffmpeg();
            if (ff == null) throw new IOException("ffmpeg is needed to play videos");
            String ss = String.format(Locale.ROOT, "%.3f", start);
            video = new ProcessBuilder(ff, "-v", "error", "-nostdin", "-ss", ss, "-i", file.toString(), "-an",
                    "-vf", "fps=" + FPS + ",scale=" + width + ":-2", "-f", "image2pipe", "-c:v", "mjpeg", "-q:v", "4", "-")
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            SourceDataLine l = null;
            Process a = null;
            try {
                AudioFormat fmt = new AudioFormat(44100, 16, 2, true, false);
                l = AudioSystem.getSourceDataLine(fmt);
                l.open(fmt, 44100 * 4 / 5);
                a = new ProcessBuilder(ff, "-v", "error", "-nostdin", "-ss", ss, "-i", file.toString(), "-vn",
                        "-f", "s16le", "-ac", "2", "-ar", "44100", "-").redirectError(ProcessBuilder.Redirect.DISCARD).start();
            } catch (Exception e) {
                l = null;                                  // no sound card: pictures only, by the clock
            }
            line = l;
            audio = a;
            if (line != null) {
                line.start();
                Thread t = new Thread(this::pumpAudio, "react-audio");
                t.setDaemon(true);
                t.start();
            }
            Thread t = new Thread(() -> pumpVideo(frames), "react-video");
            t.setDaemon(true);
            t.start();
        }

        /** Where the video is now, in seconds (by the sound card while there's sound). */
        double position() {
            if (line != null && line.isOpen() && line.getLongFramePosition() > 0) return start + line.getMicrosecondPosition() / 1e6;
            return start + (System.nanoTime() - begun) / 1e9;
        }

        void stop() {
            stopped = true;
            video.destroy();
            if (audio != null) audio.destroy();
            if (line != null) {
                line.stop();
                line.flush();
                line.close();
            }
        }

        private void pumpAudio() {
            byte[] buf = new byte[8192];
            try (InputStream in = audio.getInputStream()) {
                int n;
                while (!stopped && (n = in.read(buf)) > 0) line.write(buf, 0, n - n % 4);
            } catch (IOException ignored) {
                // stopped
            }
        }

        /** Splits ffmpeg's MJPEG stream into pictures and shows each at its moment. */
        private void pumpVideo(Consumer<BufferedImage> frames) {
            try (InputStream in = new java.io.BufferedInputStream(video.getInputStream(), 1 << 16)) {
                ByteArrayOutputStream jpg = new ByteArrayOutputStream(1 << 16);
                int prev = -1, b;
                long n = 0;
                boolean inside = false;
                while (!stopped && (b = in.read()) >= 0) {
                    if (!inside) {
                        if (prev == 0xFF && b == 0xD8) {
                            inside = true;
                            jpg.reset();
                            jpg.write(0xFF);
                            jpg.write(0xD8);
                        }
                    } else {
                        jpg.write(b);
                        if (prev == 0xFF && b == 0xD9) {
                            inside = false;
                            double due = start + n / (double) FPS;
                            n++;
                            double late = position() - due;
                            if (late > 0.25) continue;                       // behind: skip this one
                            while (!stopped && position() < due) Thread.sleep(4);
                            BufferedImage img = ImageIO.read(new ByteArrayInputStream(jpg.toByteArray()));
                            if (img != null && !stopped) frames.accept(img);
                        }
                    }
                    prev = b;
                }
            } catch (IOException | InterruptedException ignored) {
                // stopped
            }
        }
    }
}
