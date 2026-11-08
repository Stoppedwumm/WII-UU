package wiiuu.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/** Properties-backed settings stored in ~/.wiiuu/config.properties (or $WIIUU_HOME). */
public final class Config {
    private final Path home;
    private final Path file;
    private final Properties props = new Properties();

    public Config(Path home) {
        this.home = home;
        this.file = home.resolve("config.properties");
        load();
    }

    public static Path defaultHome() {
        String env = System.getenv("WIIUU_HOME");
        if (env != null && !env.isBlank()) return Paths.get(env);
        Path link = storageLink();
        if (Files.exists(link)) {
            try {
                Path target = Path.of(Files.readString(link).replaceFirst("\\r?\\n$", ""));
                if (!target.isAbsolute() || !Files.isDirectory(target))
                    throw new IOException("Storage drive is unavailable: " + target);
                return target;
            } catch (IOException e) {
                throw new IllegalStateException("Cannot open WII-UU storage listed in " + link, e);
            }
        }
        return Paths.get(System.getProperty("user.home"), ".wiiuu");
    }

    public static Path storageLink() {
        return Path.of(System.getProperty("user.home"), ".wiiuu-location");
    }

    public static void selectHome(Path target) throws IOException {
        String path = target.toAbsolutePath().toString();
        if (path.indexOf('\n') >= 0 || path.indexOf('\r') >= 0) throw new IOException("Invalid storage path");
        Path link = storageLink();
        Path pending = Files.createTempFile(link.getParent(), ".wiiuu-location-", ".tmp");
        try {
            Files.writeString(pending, path + "\n");
            Files.move(pending, link, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(pending);
        }
    }

    public Path tempDir() throws IOException {
        return Files.createDirectories(home.resolve("tmp"));
    }

    public Path home() {
        return home;
    }

    public Path logDir() {
        return home.resolve("logs");
    }

    private void load() {
        if (!Files.exists(file)) return;
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        } catch (IOException e) {
            System.err.println("[config] could not read " + file + ": " + e.getMessage());
        }
    }

    public synchronized void save() {
        try {
            saveChecked();
        } catch (IOException e) {
            System.err.println("[config] could not save " + file + ": " + e.getMessage());
        }
    }

    public synchronized void saveChecked() throws IOException {
        Files.createDirectories(home);
        try (OutputStream out = Files.newOutputStream(file)) {
            props.store(out, "WII-UU settings. {rom} in a command is replaced by the game path.");
        }
    }

    public synchronized String get(String key, String def) {
        String v = props.getProperty(key);
        return v == null ? def : v;
    }

    public synchronized void set(String key, String value) {
        if (value == null) props.remove(key);
        else props.setProperty(key, value);
    }

    public int getInt(String key, int def) {
        try {
            return Integer.parseInt(get(key, Integer.toString(def)).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public boolean getBool(String key, boolean def) {
        return Boolean.parseBoolean(get(key, Boolean.toString(def)).trim());
    }

    // ---- typed accessors ---------------------------------------------------------------

    public Path romBase() {
        return Paths.get(get("roms.base", Paths.get(System.getProperty("user.home"), "WiiUU", "roms").toString()));
    }

    public Path romDir(GameSystem s) {
        String custom = get("system." + s.id() + ".romdir", null);
        return custom != null && !custom.isBlank() ? Paths.get(custom) : romBase().resolve(s.id());
    }

    public String command(GameSystem s) {
        String custom = get("system." + s.id() + ".command", null);
        return custom != null && !custom.isBlank() ? custom : s.defaultCommand();
    }

    public boolean hidden(GameSystem s) {
        return getBool("system." + s.id() + ".hidden", false);
    }

    public int port() {
        return getInt("server.port", 8080);
    }
}
