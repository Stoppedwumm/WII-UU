package wiiuu.react;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongConsumer;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import wiiuu.core.MiniJson;

/**
 * A reactions project: videos, and for each the things WII-UU says when (a cue: a time, how long,
 * the words, a mood). Saved as .wiireact (JSON, the videos stay where they are) and exported as a
 * pack: a .zip WII-UU plays (see {@code wiiuu.core.Reactions}) with the videos, a .vtt caption
 * file per video, thumbnails and reactions.json.
 */
final class Project {
    static final List<String> MOODS = List.of("happy", "sad", "smug", "nervous", "focus", "sneaky", "shocked");

    static final class Cue {
        double at, duration = 3;
        String text = "";
        String mood = "happy";

        Cue(double at, double duration, String text, String mood) {
            this.at = at;
            this.duration = duration;
            this.text = text;
            this.mood = mood;
        }
    }

    static final class Clip {
        Path file;
        String title;
        double duration;                                   // seconds; 0 until known
        final List<Cue> cues = new ArrayList<>();

        Clip(Path file) {
            this.file = file;
            String n = file.getFileName().toString();
            int dot = n.lastIndexOf('.');
            this.title = dot > 0 ? n.substring(0, dot) : n;
        }

        @Override
        public String toString() {
            return title + "  (" + cues.size() + (cues.size() == 1 ? " reaction)" : " reactions)");
        }
    }

    String title = "My WII-UU reactions";
    final List<Clip> clips = new ArrayList<>();

    // ---- saving the project --------------------------------------------------------------------

    void save(Path file) throws IOException {
        StringBuilder b = new StringBuilder("{\n  \"format\": \"wiiuu-reactions-project\",\n  \"title\": ").append(q(title))
                .append(",\n  \"videos\": [");
        for (int i = 0; i < clips.size(); i++) {
            Clip c = clips.get(i);
            b.append(i == 0 ? "\n" : ",\n").append("    {\"file\": ").append(q(c.file.toAbsolutePath().toString()))
                    .append(", \"title\": ").append(q(c.title)).append(", \"duration\": ").append(num(c.duration))
                    .append(", \"reactions\": ").append(cues(c.cues)).append("}");
        }
        b.append("\n  ]\n}\n");
        Files.writeString(file, b, StandardCharsets.UTF_8);
    }

    static Project load(Path file) throws IOException {
        Map<String, Object> root;
        try {
            root = MiniJson.object(MiniJson.parse(Files.readString(file, StandardCharsets.UTF_8)));
        } catch (IllegalArgumentException e) {
            throw new IOException("not a reactions project: " + e.getMessage());
        }
        Project p = new Project();
        String t = MiniJson.string(root.get("title"));
        if (t != null) p.title = t;
        for (Object o : MiniJson.array(root.get("videos"))) {
            Map<String, Object> v = MiniJson.object(o);
            String f = MiniJson.string(v.get("file"));
            if (f == null) continue;
            Clip c = new Clip(Paths.get(f));
            String title = MiniJson.string(v.get("title"));
            if (title != null) c.title = title;
            Double d = MiniJson.number(v.get("duration"));
            if (d != null) c.duration = d;
            for (Object ro : MiniJson.array(v.get("reactions"))) {
                Map<String, Object> r = MiniJson.object(ro);
                Double at = MiniJson.number(r.get("at")), dur = MiniJson.number(r.get("duration"));
                String text = MiniJson.string(r.get("text")), mood = MiniJson.string(r.get("mood"));
                if (at != null && text != null) c.cues.add(new Cue(at, dur == null ? 3 : dur, text, mood == null ? "happy" : mood));
            }
            p.clips.add(c);
        }
        return p;
    }

    // ---- the pack -------------------------------------------------------------------------------

    /** Everything the export will write, in bytes (for the progress bar). */
    long packSize() {
        long n = 0;
        for (Clip c : clips) {
            try {
                n += Files.size(c.file);
            } catch (IOException ignored) {
                // reported when exporting
            }
        }
        return Math.max(1, n);
    }

    /**
     * Writes the pack: reactions.json, videos/, captions/ (WebVTT: "WII-UU: ..." at each reaction)
     * and thumbs/ (a frame from each video, when ffmpeg is there). Videos aren't compressed again.
     */
    void export(Path zip, LongConsumer copied) throws IOException {
        Path tmp = zip.resolveSibling(zip.getFileName() + ".part");
        long[] done = {0};
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(tmp))) {
            StringBuilder json = new StringBuilder("{\n  \"format\": \"wiiuu-reactions\",\n  \"version\": 1,\n  \"title\": ")
                    .append(q(title)).append(",\n  \"videos\": [");
            for (int i = 0; i < clips.size(); i++) {
                Clip c = clips.get(i);
                if (!Files.isRegularFile(c.file)) throw new IOException("the video " + c.file + " isn't there any more");
                String base = String.format(Locale.ROOT, "%02d-%s", i + 1, safe(c.title));
                String ext = ext(c.file.getFileName().toString());
                String video = "videos/" + base + ext, vtt = "captions/" + base + ".vtt", thumb = "thumbs/" + base + ".png";
                // the video, as it is
                out.setLevel(Deflater.NO_COMPRESSION);
                out.putNextEntry(new ZipEntry(video));
                try (InputStream in = Files.newInputStream(c.file)) {
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        done[0] += n;
                        copied.accept(done[0]);
                    }
                }
                out.closeEntry();
                out.setLevel(Deflater.DEFAULT_COMPRESSION);
                put(out, vtt, vtt(c).getBytes(StandardCharsets.UTF_8));
                byte[] png = Media.thumbnailPng(c.file, c.duration > 0 ? Math.min(c.duration * 0.15, 30) : 3);
                if (png != null) put(out, thumb, png);
                json.append(i == 0 ? "\n" : ",\n").append("    {\"title\": ").append(q(c.title)).append(", \"file\": ").append(q(video))
                        .append(", \"captions\": ").append(q(vtt)).append(", \"thumbnail\": ").append(png != null ? q(thumb) : "null")
                        .append(", \"duration\": ").append(num(c.duration)).append(",\n     \"reactions\": ").append(cues(c.cues)).append("}");
            }
            json.append("\n  ]\n}\n");
            put(out, "reactions.json", json.toString().getBytes(StandardCharsets.UTF_8));
            put(out, "README.txt", ("A WII-UU reactions pack: put this .zip in ~/.wiiuu/reactions (or use the editor's\n"
                    + "\"Send to WII-UU\"), and the videos show up as the Reactions channel on WII-UU's home screen.\n"
                    + "The captions/ are standard WebVTT subtitles for any player.\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        Files.move(tmp, zip, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private static void put(ZipOutputStream out, String name, byte[] data) throws IOException {
        out.putNextEntry(new ZipEntry(name));
        out.write(data);
        out.closeEntry();
    }

    /** WebVTT captions: the reactions in time order, each "WII-UU: what it says". */
    static String vtt(Clip c) {
        StringBuilder b = new StringBuilder("WEBVTT\n\n");
        List<Cue> sorted = new ArrayList<>(c.cues);
        sorted.sort((a, d) -> Double.compare(a.at, d.at));
        int n = 1;
        for (Cue cue : sorted) {
            if (cue.text.isBlank()) continue;
            b.append(n++).append('\n').append(time(cue.at)).append(" --> ").append(time(cue.at + cue.duration)).append('\n')
                    .append("WII-UU: ").append(cue.text.replace("-->", "->")).append("\n\n");
        }
        return b.toString();
    }

    static String time(double s) {
        long ms = Math.round(Math.max(0, s) * 1000);
        return String.format(Locale.ROOT, "%02d:%02d:%02d.%03d", ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000);
    }

    private static String cues(List<Cue> cues) {
        StringBuilder b = new StringBuilder("[");
        List<Cue> sorted = new ArrayList<>(cues);
        sorted.sort((a, d) -> Double.compare(a.at, d.at));
        for (int i = 0; i < sorted.size(); i++) {
            Cue c = sorted.get(i);
            b.append(i == 0 ? "" : ", ").append("{\"at\": ").append(num(c.at)).append(", \"duration\": ").append(num(c.duration))
                    .append(", \"text\": ").append(q(c.text)).append(", \"mood\": ").append(q(c.mood)).append("}");
        }
        return b.append("]").toString();
    }

    private static String num(double d) {
        return String.format(Locale.ROOT, "%.3f", d);
    }

    static String q(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (ch < 0x20) b.append(String.format("\\u%04x", (int) ch));
                    else b.append(ch);
                }
            }
        }
        return b.append('"').toString();
    }

    private static String safe(String s) {
        String t = s.replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("-+", "-").replaceAll("^-|-$", "");
        return t.isEmpty() ? "video" : t.length() > 40 ? t.substring(0, 40) : t;
    }

    private static String ext(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot).toLowerCase(Locale.ROOT) : ".mp4";
    }
}
