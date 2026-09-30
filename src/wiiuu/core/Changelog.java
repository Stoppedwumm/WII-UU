package wiiuu.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The release notes (CHANGELOG.md, bundled in the jar and published on the website): one
 * "## version (date)" heading per release, newest first, then a line of summary and "- " points.
 */
public final class Changelog {
    private static final Pattern HEADING = Pattern.compile("^##\\s+v?(\\d+(?:\\.\\d+)*)\\s*(?:\\((.*)\\))?\\s*$");

    /** One release: its summary lines (may be empty) and its points. */
    public record Entry(String version, String date, List<String> summary, List<String> points) {}

    private final List<Entry> entries;

    private Changelog(List<Entry> entries) {
        this.entries = entries;
    }

    public List<Entry> entries() {
        return entries;
    }

    public static Changelog parse(String markdown) {
        List<Entry> out = new ArrayList<>();
        String version = null, date = null;
        List<String> summary = new ArrayList<>(), points = new ArrayList<>();
        boolean afterBlank = true;
        for (String raw : markdown.replace("\r", "").split("\n")) {
            boolean blank = afterBlank;
            afterBlank = raw.isBlank();
            Matcher m = HEADING.matcher(raw);
            if (m.matches()) {
                if (version != null) out.add(new Entry(version, date, List.copyOf(summary), List.copyOf(points)));
                version = m.group(1);
                date = m.group(2) == null ? "" : m.group(2).trim();
                summary.clear();
                points.clear();
                continue;
            }
            if (version == null || raw.isBlank()) continue;
            String line = raw.strip();
            if (line.startsWith("- ") || line.startsWith("* ")) points.add(line.substring(2).strip());
            else if (raw.startsWith(" ") && !points.isEmpty()) points.set(points.size() - 1, points.get(points.size() - 1) + " " + line);
            else if (points.isEmpty() && !summary.isEmpty() && !blank) summary.set(summary.size() - 1, summary.get(summary.size() - 1) + " " + line);
            else if (points.isEmpty()) summary.add(line);
            else points.add(line);
        }
        if (version != null) out.add(new Entry(version, date, List.copyOf(summary), List.copyOf(points)));
        return new Changelog(List.copyOf(out));
    }

    /** The notes shipped inside this copy of WII-UU (empty if missing). */
    public static Changelog bundled() {
        try (InputStream in = Changelog.class.getResourceAsStream("/CHANGELOG.md")) {
            return parse(in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return parse("");
        }
    }

    /** Releases newer than {@code after} and not newer than {@code upTo} (either may be null). */
    public List<Entry> between(String after, String upTo) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries) {
            if (after != null && !Updater.newer(e.version(), after)) continue;
            if (upTo != null && Updater.newer(e.version(), upTo)) continue;
            out.add(e);
        }
        return out;
    }

    // ---- presenting ---------------------------------------------------------------------

    /** Plain text, for the terminal. */
    public static String text(List<Entry> list) {
        StringBuilder sb = new StringBuilder();
        for (Entry e : list) {
            sb.append(e.version()).append(e.date().isEmpty() ? "" : " (" + e.date() + ")").append('\n');
            for (String s : e.summary()) sb.append("  ").append(plain(s)).append('\n');
            for (String p : e.points()) sb.append("  - ").append(plain(p)).append('\n');
            sb.append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /** HTML for Swing's labels and text panes. */
    public static String html(List<Entry> list) {
        StringBuilder sb = new StringBuilder("<html><body style='font-family:sans-serif'>");
        for (Entry e : list) {
            sb.append("<h2 style='margin:10px 0 2px 0'>").append(esc(e.version()));
            if (!e.date().isEmpty()) sb.append(" <span style='font-weight:normal;color:#888888'>").append(esc(e.date())).append("</span>");
            sb.append("</h2>");
            for (String s : e.summary()) sb.append("<p style='margin:2px 0'>").append(inline(s)).append("</p>");
            if (!e.points().isEmpty()) {
                sb.append("<ul style='margin-top:2px;margin-bottom:4px'>");
                for (String p : e.points()) sb.append("<li>").append(inline(p)).append("</li>");
                sb.append("</ul>");
            }
        }
        return sb.append("</body></html>").toString();
    }

    private static String plain(String s) {
        return s.replace("`", "").replace("**", "");
    }

    /** `code` and **bold**, the only markdown the notes use inside a line. */
    private static String inline(String s) {
        return esc(s).replaceAll("`([^`]+)`", "<font face='monospace'>$1</font>").replaceAll("\\*\\*([^*]+)\\*\\*", "<b>$1</b>");
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
