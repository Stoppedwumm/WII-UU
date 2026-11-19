package wiiuu.ui;

import java.awt.FontMetrics;
import java.util.ArrayList;
import java.util.List;

/** Width-aware text layout extracted from the WII-UU and Choccy Milk menus. */
public final class UiText {
    private UiText() {}

    /** Fit a line with an ellipsis. Return empty when even the ellipsis cannot fit. */
    public static String ellipsize(String text, FontMetrics metrics, float width) {
        if (!(width > 0)) return "";
        if (metrics.stringWidth(text) <= width) return text;
        String dots = "\u2026";
        if (metrics.stringWidth(dots) > width) return "";
        int end = text.length();
        while (end > 0 && metrics.stringWidth(text.substring(0, end).stripTrailing() + dots) > width) {
            end = text.offsetByCodePoints(end, -1);
        }
        return text.substring(0, end).stripTrailing() + dots;
    }

    /** Wrap words into at most maxLines lines; ellipsize long words and the remaining final line. */
    public static List<String> wrap(String text, FontMetrics metrics, float width, int maxLines) {
        if (maxLines <= 0 || !(width > 0) || text.isBlank()) return List.of();
        String[] words = text.strip().split("\\s+");
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            if (lines.size() == maxLines - 1) {
                for (; i < words.length; i++) {
                    if (!current.isEmpty()) current.append(' ');
                    current.append(words[i]);
                }
                break;
            }
            String next = current.isEmpty() ? words[i] : current + " " + words[i];
            if (metrics.stringWidth(next) <= width || current.isEmpty()) {
                current.setLength(0);
                current.append(next);
            } else {
                lines.add(ellipsize(current.toString(), metrics, width));
                current.setLength(0);
                current.append(words[i]);
            }
        }
        if (!current.isEmpty()) lines.add(ellipsize(current.toString(), metrics, width));
        return List.copyOf(lines);
    }
}
