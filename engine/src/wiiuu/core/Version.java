package wiiuu.core;

import java.math.BigInteger;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Semantic version precedence, with optional v prefix and legacy one/two-part core versions. */
public final class Version implements Comparable<Version> {
    private static final Pattern FORMAT = Pattern.compile("^v?(0|[1-9][0-9]*)(?:\\.(0|[1-9][0-9]*))?(?:\\.(0|[1-9][0-9]*))?(?:-([0-9A-Za-z.-]+))?(?:\\+([0-9A-Za-z.-]+))?$");
    private final List<BigInteger> core;
    private final String pre, metadata;

    private Version(List<BigInteger> core, String pre, String metadata) {
        this.core = core; this.pre = pre; this.metadata = metadata;
    }

    public static Version parse(String value) {
        if (value == null) throw new IllegalArgumentException("Missing version");
        Matcher m = FORMAT.matcher(value);
        if (!m.matches()) throw new IllegalArgumentException("Invalid version: " + value);
        for (int group : new int[]{4, 5}) if (m.group(group) != null)
            for (String part : m.group(group).split("\\.", -1))
                if (part.isEmpty() || group == 4 && part.matches("[0-9]+") && part.length() > 1 && part.startsWith("0"))
                    throw new IllegalArgumentException("Invalid version identifier: " + value);
        return new Version(List.of(new BigInteger(m.group(1)), new BigInteger(m.group(2) == null ? "0" : m.group(2)),
                new BigInteger(m.group(3) == null ? "0" : m.group(3))), m.group(4), m.group(5));
    }

    public String base() { return core.get(0) + "." + core.get(1) + "." + core.get(2); }
    public boolean prerelease() { return pre != null; }
    @Override public String toString() { return base() + (pre == null ? "" : "-" + pre) + (metadata == null ? "" : "+" + metadata); }

    @Override public int compareTo(Version other) {
        for (int i = 0; i < 3; i++) { int c = core.get(i).compareTo(other.core.get(i)); if (c != 0) return c; }
        if (pre == null || other.pre == null) return pre == null ? other.pre == null ? 0 : 1 : -1;
        String[] a = pre.split("\\."), b = other.pre.split("\\.");
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            boolean an = a[i].matches("[0-9]+"), bn = b[i].matches("[0-9]+");
            int c = an && bn ? new BigInteger(a[i]).compareTo(new BigInteger(b[i]))
                    : an != bn ? an ? -1 : 1 : a[i].compareTo(b[i]);
            if (c != 0) return c;
        }
        return Integer.compare(a.length, b.length);
    }
}
