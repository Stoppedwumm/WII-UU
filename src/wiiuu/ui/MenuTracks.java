package wiiuu.ui;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

/**
 * The menu's music to choose from: WII-UU's own tune, public-domain melodies in 8-bit and as
 * WII-UU remixes, and the player's own sound files in the music folder (WAV, AIFF or AU, which
 * Java plays by itself).
 */
public final class MenuTracks {
    /** Every track in turn. */
    public static final String ALL = "all";
    public static final String DEFAULT = "wiiuu";
    private static final String FILE = "file:";
    private static final int MAX_SECONDS = 300;           // about 53 MB of memory at most

    public record Track(String id, String name) {}

    private static final List<Track> BUILT_IN = builtIn();

    private static List<Track> builtIn() {
        List<Track> out = new ArrayList<>();
        out.add(new Track(DEFAULT, "WII-UU (original)"));
        for (Tunes.Track t : Tunes.tracks()) out.add(new Track(t.id(), t.name()));
        return List.copyOf(out);
    }

    private MenuTracks() {}

    /** Built-in tracks, then the music folder's files by name. */
    public static List<Track> all(Path folder) {
        List<Track> out = new ArrayList<>(BUILT_IN);
        for (Path f : files(folder)) out.add(new Track(FILE + f.getFileName(), stem(f.getFileName().toString())));
        return out;
    }

    /** A track's name as the menu lists it, or the id itself. */
    static String name(String id, Path folder) {
        if (id.equals(ExtendedMix.ID)) return ExtendedMix.NAME;
        for (Track t : all(folder)) if (t.id().equals(id)) return t.name();
        return id;
    }

    /** The tracks "all" takes turns with. */
    static List<String> playlist(Path folder) {
        List<String> ids = new ArrayList<>();
        for (Track t : all(folder)) ids.add(t.id());
        return ids;
    }

    /** Renders or loads a track as an interleaved stereo loop; null if it can't be played. */
    static short[] load(String id, Path folder) {
        try {
            if (id.equals("wiiuu")) return MenuMusic.render();
            if (id.startsWith(FILE)) {
                String name = id.substring(FILE.length());
                if (name.contains("/") || name.contains("\\") || name.startsWith(".")) return null;
                return decode(folder.resolve(name));
            }
            return Tunes.render(id);
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    private static List<Path> files(Path folder) {
        List<Path> out = new ArrayList<>();
        if (folder == null || !Files.isDirectory(folder)) return out;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(folder)) {
            for (Path f : ds) {
                String n = f.getFileName().toString().toLowerCase(Locale.ROOT);
                if (Files.isRegularFile(f) && !n.startsWith(".")
                        && (n.endsWith(".wav") || n.endsWith(".aif") || n.endsWith(".aiff") || n.endsWith(".au"))) out.add(f);
            }
        } catch (IOException e) {
            // unreadable folder: just the built-in tracks
        }
        out.sort((a, b) -> a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString()));
        return out;
    }

    private static String stem(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** A sound file as 44.1 kHz 16-bit stereo, at most {@link #MAX_SECONDS}, with soft ends for looping. */
    private static short[] decode(Path file) throws Exception {
        AudioFormat target = new AudioFormat(MenuAudio.RATE, 16, 2, true, false);
        try (AudioInputStream src = AudioSystem.getAudioInputStream(file.toFile())) {
            AudioFormat f = src.getFormat();
            // to plain PCM first (compressed WAVs), then to the mixer's rate and channels
            AudioFormat pcm = new AudioFormat(f.getSampleRate(), 16, f.getChannels(), true, false);
            try (AudioInputStream a = AudioSystem.getAudioInputStream(pcm, src);
                 AudioInputStream b = AudioSystem.getAudioInputStream(target, a)) {
                byte[] bytes = b.readNBytes(MAX_SECONDS * MenuAudio.RATE * 4);
                int frames = bytes.length / 4;
                if (frames < MenuAudio.RATE) return null;            // under a second
                short[] out = new short[frames * 2];
                for (int i = 0; i < out.length; i++) out[i] = (short) ((bytes[i * 2] & 0xff) | (bytes[i * 2 + 1] << 8));
                fade(out, frames);
                return out;
            }
        }
    }

    /** Fades in over 0.3 s and out over 1.5 s, so the loop doesn't click where it wraps. */
    private static void fade(short[] pcm, int frames) {
        int in = Math.min(frames / 4, (int) (0.3 * MenuAudio.RATE)), out = Math.min(frames / 4, (int) (1.5 * MenuAudio.RATE));
        for (int i = 0; i < in; i++) scale(pcm, i, i / (double) in);
        for (int i = 0; i < out; i++) scale(pcm, frames - 1 - i, i / (double) out);
    }

    private static void scale(short[] pcm, int frame, double g) {
        pcm[frame * 2] = (short) (pcm[frame * 2] * g);
        pcm[frame * 2 + 1] = (short) (pcm[frame * 2 + 1] * g);
    }
}
