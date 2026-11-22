package wiiuu.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.Properties;

/** Properties-backed settings stored in ~/.wiiuu/config.properties (or $WIIUU_HOME). */
public final class Config {
    private final Path home;
    private final Path file;
    private final Properties props = new Properties();
    private final Properties userProps = new Properties();
    private final String userId;
    private final Path userFile;

    public Config(Path home) {
        this.home = home;
        this.file = home.resolve("config.properties");
        this.userId = Users.activeId(home);
        this.userFile = "default".equals(userId) ? null
                : Users.dataHome(home, userId).resolve("config.properties");
        load();
    }

    /** Data owned by the active WII-UU user. The legacy default user's folder is unchanged. */
    public Path userHome() {
        return "default".equals(userId) ? home : Users.dataHome(home, userId);
    }

    public String userId() { return userId; }

    /** Global device keys remain shared; profile preferences and credentials never fall back to another user. */
    private static boolean personal(String key) {
        if (key.startsWith("ui.")) return !key.equals("ui.setupDone")
                && !key.equals("ui.setupNext") && !key.equals("ui.firstBootIntro");
        return key.startsWith("ra.") || key.startsWith("openbased.")
                || key.startsWith("input.") || key.startsWith("keys.")
                || key.startsWith("choccy.") || key.startsWith("retroarch.")
                || key.startsWith("scraper.ss.") || key.startsWith("plugins.")
                || key.startsWith("plugin.") || key.startsWith("favorites.");
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
        read(file, props);
        if (userFile != null) read(userFile, userProps);
    }

    private static void read(Path path, Properties target) {
        if (!Files.exists(path)) return;
        try (InputStream in = Files.newInputStream(path)) {
            target.load(in);
        } catch (IOException e) {
            System.err.println("[config] could not read " + path + ": " + e.getMessage());
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
            props.store(out, "WII-UU shared device settings. {rom} is replaced by the game path.");
        }
        if (userFile != null) {
            Files.createDirectories(userFile.getParent());
            try (OutputStream out = Files.newOutputStream(userFile)) {
                userProps.store(out, "WII-UU private user settings.");
            }
        }
    }

    /**
     * Atomically save device-wide settings without touching a user's private settings.
     * A native-app installation must not fail halfway through because another user's
     * profile directory is unwritable or its configuration cannot be serialized.
     */
    synchronized void saveSharedChecked() throws IOException {
        Files.createDirectories(home);
        Path pending = Files.createTempFile(home, ".config-", ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(pending)) {
                props.store(out, "WII-UU shared device settings. {rom} is replaced by the game path.");
            }
            try {
                Files.move(pending, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(pending, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(pending);
        }
    }

    public synchronized String get(String key, String def) {
        Properties source = userFile != null && personal(key) ? userProps : props;
        String v = source.getProperty(key);
        return v == null ? def : v;
    }

    public synchronized void set(String key, String value) {
        Properties target = userFile != null && personal(key) ? userProps : props;
        if (value == null) target.remove(key);
        else target.setProperty(key, value);
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

    /**
     * Local WII-UU user profiles. Names are display-only; directories always use random IDs.
     * Legacy installations are the immutable-ID "default" user without any migration.
     * Removing a user hides it from the menu, but leaves its files for manual recovery.
     */
    public static final class Users {
        public record Profile(String id, String name) {}
        private Users() {}

        private static Path folder(Path home) { return home.resolve("profiles"); }
        private static Path index(Path home) { return folder(home).resolve("index.properties"); }
        private static Path dataHome(Path home, String id) {
            if (!validId(id) || "default".equals(id)) throw new IllegalArgumentException("Invalid user ID");
            return folder(home).resolve(id);
        }
        private static boolean validId(String id) {
            return id != null && ("default".equals(id) || id.matches("[a-f0-9]{32}"));
        }
        private static Properties read(Path home) {
            Properties p = new Properties();
            Config.read(index(home), p);
            if (!p.containsKey("user.default")) p.setProperty("user.default", "Default user");
            return p;
        }
        private static void write(Path home, Properties p) throws IOException {
            Path folder = folder(home);
            Files.createDirectories(folder);
            Path tmp = Files.createTempFile(folder, ".profiles-", ".tmp");
            try {
                try (OutputStream out = Files.newOutputStream(tmp)) {
                    p.store(out, "WII-UU local user list; profile files live in ID-based subfolders.");
                }
                try {
                    Files.move(tmp, index(home), StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, index(home), StandardCopyOption.REPLACE_EXISTING);
                }
            } finally { Files.deleteIfExists(tmp); }
        }
        private static String cleanName(String raw) {
            String name = raw == null ? "" : raw.strip();
            if (name.isEmpty() || name.length() > 32
                    || name.codePoints().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException("User name must be 1-32 visible characters");
            return name;
        }
        private static void unique(Properties p, String name, String exclude) {
            for (String key : p.stringPropertyNames())
                if (key.startsWith("user.") && !key.equals("user." + exclude)
                        && name.equalsIgnoreCase(p.getProperty(key)))
                    throw new IllegalArgumentException("A user with this name already exists");
        }
        public static synchronized List<Profile> list(Path home) {
            Properties p = read(home);
            List<Profile> result = new ArrayList<>();
            for (String key : p.stringPropertyNames()) {
                if (!key.startsWith("user.")) continue;
                String id = key.substring(5);
                if (validId(id)) result.add(new Profile(id, p.getProperty(key)));
            }
            result.sort(Comparator.comparing((Profile u) -> !u.id().equals("default"))
                    .thenComparing(Profile::name, String.CASE_INSENSITIVE_ORDER));
            return List.copyOf(result);
        }
        public static synchronized String activeId(Path home) {
            Properties p = read(home);
            String id = p.getProperty("active", "default");
            return validId(id) && p.containsKey("user." + id) ? id : "default";
        }
        public static synchronized Profile active(Path home) {
            String id = activeId(home);
            return list(home).stream().filter(p -> p.id().equals(id)).findFirst()
                    .orElse(new Profile("default", "Default user"));
        }
        public static synchronized Profile create(Path home, String rawName) throws IOException {
            String name = cleanName(rawName);
            Properties p = read(home);
            unique(p, name, null);
            String id = UUID.randomUUID().toString().replace("-", "");
            Files.createDirectories(dataHome(home, id));
            p.setProperty("user." + id, name);
            write(home, p);
            return new Profile(id, name);
        }
        public static synchronized void rename(Path home, String id, String rawName) throws IOException {
            String name = cleanName(rawName);
            Properties p = read(home);
            if (!validId(id) || !p.containsKey("user." + id))
                throw new IllegalArgumentException("Unknown user");
            unique(p, name, id);
            p.setProperty("user." + id, name);
            write(home, p);
        }
        /**
         * Force the account chooser at the next graphical boot, even for a single profile.
         * The marker remains until a user is explicitly selected, so an aborted boot
         * cannot silently resume the previous user's session.
         */
        public static synchronized void requireChooserOnNextBoot(Path home) throws IOException {
            Properties p = read(home);
            p.setProperty("chooseOnNextBoot", "true");
            write(home, p);
        }

        /** More than one account always shows the chooser; sign-out forces it once. */
        public static synchronized boolean chooserRequired(Path home) {
            Properties p = read(home);
            return "true".equals(p.getProperty("chooseOnNextBoot"))
                    || p.stringPropertyNames().stream()
                        .filter(key -> key.startsWith("user.")
                                && validId(key.substring("user.".length()))).count() > 1;
        }

        public static synchronized void select(Path home, String id) throws IOException {
            Properties p = read(home);
            if (!validId(id) || !p.containsKey("user." + id))
                throw new IllegalArgumentException("Unknown user");
            p.setProperty("active", id);
            p.remove("chooseOnNextBoot");
            write(home, p);
        }
        public static synchronized void remove(Path home, String id) throws IOException {
            Properties p = read(home);
            if (!validId(id) || "default".equals(id) || !p.containsKey("user." + id))
                throw new IllegalArgumentException("Cannot remove that user");
            if (id.equals(activeId(home))) throw new IllegalArgumentException("Switch users before removing the active one");
            p.remove("user." + id);
            write(home, p);
            // Deliberately retain profile data for recovery; no recursive deletion.
        }
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
