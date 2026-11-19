package wiiuu.core;

/** Semantic ordering and release-note boundaries, including legacy stored versions. */
public final class VersionTest {
    public static void main(String[] args) {
        String[] ordered = {"2.0.9-alpha", "2.0.9-alpha.1", "2.0.9-beta.2", "2.0.9-beta.10", "2.0.9-beta.10.1", "2.0.9-rc.1", "2.0.9", "2.0.10", "2.1.0"};
        for (int i = 1; i < ordered.length; i++) {
            check(Updater.newer(ordered[i], ordered[i - 1]), "upgrade order");
            check(!Updater.newer(ordered[i - 1], ordered[i]), "no downgrade");
        }
        check(!Updater.newer("2.0.9-beta.2+g123", "2.0.9-beta.2+g456"), "metadata ignored");
        check(Updater.newer("v1.10.0", "1.9"), "legacy versions");
        check(Updater.newer("999999999999999999999.0.0", "3.0.0"), "no integer overflow");
        for (String bad : new String[]{"", "2.0.09", "2.0.9-beta.01", "2.0.9-", "2.0.9-beta..1", "2.0.9+bad..id", "nonsense"}) {
            try { Version.parse(bad); throw new AssertionError("Accepted " + bad); }
            catch (IllegalArgumentException expected) { }
        }
        Changelog notes = Changelog.parse("## 2.0.9 (2026-11-19)\n- Cycle changes\n\n## 2.0.9-beta.2\n- Beta fix\n\n## 2.0.8\n- Old\n");
        check(notes.between("2.0.8", "2.0.9-beta.2").size() == 2, "cycle and beta notes included");
        check(notes.between("2.0.9-beta.1", "2.0.9-beta.2").size() == 1, "cycle notes not repeated");
        System.out.println("Semantic version tests passed");
    }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
