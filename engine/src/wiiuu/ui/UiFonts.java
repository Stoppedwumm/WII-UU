package wiiuu.ui;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Desktop font discovery and an app-owned cache using WII-UU's half-point size buckets. */
public final class UiFonts {
    private static final Set<String> INSTALLED = discover();
    private final Map<Long, Font> cache = new ConcurrentHashMap<>();
    private String family;

    /** Create an independent cache; unknown families use Java's normal font fallback. */
    public UiFonts(String family) {
        this.family = Objects.requireNonNull(family);
    }

    /** Switch family and invalidate this instance's cached fonts. */
    public synchronized void family(String family) {
        Objects.requireNonNull(family);
        if (!this.family.equals(family)) {
            this.family = family;
            cache.clear();
        }
    }

    /** Get a font, rounded to half points and at least eight points, as in the WII-UU menu. */
    public synchronized Font font(int style, float size) {
        if (!Float.isFinite(size)) throw new IllegalArgumentException("Font size must be finite");
        float sz = Math.max(8f, Math.round(size * 2) / 2f);
        return cache.computeIfAbsent(((long) style << 32) | Float.floatToIntBits(sz),
                k -> new Font(family, style, 1).deriveFont(style, sz));
    }

    /** Whether the family was found on this desktop. Safe without a display. */
    public static boolean installed(String family) {
        return INSTALLED.contains(family);
    }

    /** First installed preference, falling back to Java's logical sans-serif font. */
    public static String choose(String... preferences) {
        for (String family : preferences) if (installed(family)) return family;
        return Font.SANS_SERIF;
    }

    private static Set<String> discover() {
        try {
            return Set.copyOf(Arrays.asList(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
        } catch (RuntimeException | LinkageError e) {
            return Set.of();
        }
    }
}
