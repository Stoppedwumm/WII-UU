package wiiuu.ui;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Asks the desktop whether it is in dark mode (macOS, Windows, GNOME/KDE and other freedesktops). */
final class SystemTheme {
    private static final String OS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

    private SystemTheme() {}

    /** @return true for dark, false for light (also when it can't be told) */
    static boolean isDark() {
        try {
            if (OS.contains("mac")) {
                // prints "Dark" in dark mode; fails (no such key) in light mode
                return run("defaults", "read", "-g", "AppleInterfaceStyle").trim().equalsIgnoreCase("dark");
            }
            if (OS.contains("win")) {
                String out = run("reg", "query", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                        "/v", "AppsUseLightTheme");
                return out.contains("0x0");
            }
            // GNOME 42+ and most freedesktops: the colour-scheme preference; older: a "-dark" GTK theme
            String scheme = run("gsettings", "get", "org.gnome.desktop.interface", "color-scheme");
            if (scheme.contains("prefer-dark")) return true;
            if (scheme.contains("prefer-light") || scheme.contains("default")) {
                return run("gsettings", "get", "org.gnome.desktop.interface", "gtk-theme").toLowerCase(Locale.ROOT).contains("dark");
            }
            String kde = run("kreadconfig5", "--group", "General", "--key", "ColorScheme");
            if (kde.isBlank()) kde = run("kreadconfig6", "--group", "General", "--key", "ColorScheme");
            return kde.toLowerCase(Locale.ROOT).contains("dark");
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String run(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String l;
                while ((l = r.readLine()) != null) out.append(l).append('\n');
            }
            if (!p.waitFor(3, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return "";
            }
            return p.exitValue() == 0 ? out.toString() : "";
        } catch (IOException e) {
            return "";                        // tool not installed
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        }
    }
}
