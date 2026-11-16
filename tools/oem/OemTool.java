package wiiuu.oem;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.*;
import java.util.jar.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

public final class OemTool {
    private static final Console TERMINAL = System.console();
    private static final String BUNDLE = "oem/";
    private static final String MAIN = OemTool.class.getName();
    private static final Set<String> SETTINGS = Set.of("ui.theme", "ui.music", "ui.sounds",
            "ui.visualizer", "ui.fullscreen", "ui.musicTrack", "ui.idleGames", "retroarch.enabled", "server.enabled",
            "server.port", "server.maxPlayers", "audio.enabled", "choccy.enabled", "choccy.split",
            "screen.split", "buzz.enabled", "scraper.auto", "update.check");

    private OemTool() {}

    public static void main(String[] args) {
        try {
            Path self = Path.of(OemTool.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            try (JarFile jar = new JarFile(self.toFile())) {
                boolean deployment = jar.getEntry(BUNDLE + "model.properties") != null;
                if (args.length == 1 && args[0].equals("--help")) {
                    System.out.println(deployment ? "Run this JAR in a terminal to deploy over SSH."
                            : "Run this JAR in a terminal with an extracted WII-UU build available.");
                    return;
                }
                if (args.length != 0 || TERMINAL == null) throw new IOException("Run java -jar from an interactive terminal.");
                if (deployment) deploy(jar);
                else create(jar);
            }
        } catch (Exception e) {
            System.err.println("OEM: " + e.getMessage());
            System.exit(1);
        }
    }

    private static String ask(String prompt) throws IOException {
        String value = TERMINAL.readLine("%s: ", prompt);
        if (value == null) throw new EOFException("Input closed");
        return value.strip();
    }

    private static String password(String prompt, boolean confirm) throws IOException {
        char[] input = TERMINAL.readPassword("%s: ", prompt);
        if (input == null) throw new EOFException("Input closed");
        try {
            String value = new String(input);
            if (value.isBlank() || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0 || value.indexOf(0) >= 0)
                throw new IOException("Enter a nonblank single-line password.");
            if (confirm) {
                char[] again = TERMINAL.readPassword("Repeat password: ");
                try {
                    if (!Arrays.equals(input, again)) throw new IOException("Passwords differ.");
                } finally {
                    if (again != null) Arrays.fill(again, '\0');
                }
            }
            return value;
        } finally {
            Arrays.fill(input, '\0');
        }
    }

    private static String user(String value) throws IOException {
        if (!value.matches("[a-z_][a-z0-9_-]{0,30}") || value.equals("root"))
            throw new IOException("Use a regular Linux account name. The replacement administrator receives root access via sudo.");
        return value;
    }

    private static void create(JarFile self) throws Exception {
        Path settings = Files.createTempDirectory("wiiuu-oem-settings-");
        try {
            Path release = null, content = null;
            String model = "", admin = "", secret = "";
            while (true) {
                System.out.printf("%nWII-UU OEM creator%n1  Console model: %s%n2  WII-UU release ZIP: %s%n"
                        + "E  Edit defaults in WII-UU Settings%n3  Administrator: %s%n4  Delivered files: %s%n"
                        + "5  Create deployment JAR%nQ  Quit%n", model, release, admin, content);
                try {
                    switch (ask("Choose").toLowerCase(Locale.ROOT)) {
                        case "1" -> model = ask("Console model name");
                        case "2" -> {
                            Path candidate = Path.of(ask("WII-UU release ZIP")).toAbsolutePath();
                            releasePrefix(candidate);
                            if (!candidate.equals(release)) Files.deleteIfExists(settings.resolve("config.properties"));
                            release = candidate;
                        }
                        case "e" -> {
                            if (release == null) throw new IOException("Choose a release ZIP first.");
                            edit(release, settings);
                        }
                        case "3" -> {
                            String name = user(ask("Administrator login name"));
                            String next = password("Default administrator password", true);
                            if (next.length() < 12 || next.contains(":")) throw new IOException("Use at least 12 characters and omit colons.");
                            admin = name;
                            secret = next;
                        }
                        case "4" -> {
                            String dir = ask("Content directory (blank for none)");
                            Path candidate = dir.isEmpty() ? null : Path.of(dir).toAbsolutePath();
                            if (candidate != null && !Files.isDirectory(candidate)) throw new IOException("Content directory does not exist.");
                            content = candidate;
                        }
                        case "5" -> {
                            if (model.isBlank() || release == null || admin.isBlank()) throw new IOException("Set model, release and administrator first.");
                            String filename = ask("Output JAR path");
                            if (!filename.endsWith(".jar")) throw new IOException("Use a filename ending in .jar.");
                            Path output = Path.of(filename).toAbsolutePath().normalize();
                            if (content != null && output.startsWith(content.normalize())) throw new IOException("Put the output JAR outside delivered files.");
                            String passphrase = password("Deployment unlock passphrase", true);
                            if (passphrase.length() < 12) throw new IOException("Use an unlock passphrase of at least 12 characters.");
                            createJar(self, output, release, content, settings, model, admin, seal(secret, passphrase));
                            System.out.println("Created " + output);
                            return;
                        }
                        case "q" -> { return; }
                        default -> System.out.println("Choose one of the menu items.");
                    }
                } catch (IOException e) {
                    if (e instanceof EOFException) throw e;
                    System.err.println(e.getMessage());
                }
            }
        } finally {
            remove(settings);
        }
    }

    private static String releasePrefix(Path archive) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            var entries = zip.stream().filter(e -> e.getName().matches("(WII-UU-[A-Za-z0-9._-]+/)?install\\.sh")).toList();
            if (entries.size() != 1) throw new IOException("Expected a WII-UU package ZIP with install.sh.");
            String prefix = entries.get(0).getName().replaceFirst("install\\.sh$", "");
            if (zip.getEntry(prefix + "console/wiiuu-console") == null || zip.getEntry(prefix + "wiiuu.jar") == null)
                throw new IOException("The package must include wiiuu.jar and Console Mode.");
            return prefix;
        }
    }

    private static void edit(Path release, Path settings) throws Exception {
        Path jar = settings.resolve("editor.jar");
        try (ZipFile zip = new ZipFile(release.toFile());
             InputStream source = zip.getInputStream(zip.getEntry(releasePrefix(release) + "wiiuu.jar"))) {
            Files.copy(source, jar, StandardCopyOption.REPLACE_EXISTING);
        }
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        if (new ProcessBuilder(java, "-Duser.home=" + settings, "-jar", jar.toString(), "--home", settings.toString(),
                "--oem-settings").inheritIO().start().waitFor() != 0) {
            throw new IOException("Settings editor failed. Use a package built with OEM Creator and a graphical desktop.");
        }
    }

    private static void createJar(JarFile self, Path output, Path release, Path content, Path settings,
                                  String model, String admin, byte[] encrypted) throws IOException {
        if (Files.exists(output)) throw new IOException("Output already exists.");
        Path temporary = Files.createTempFile(output.getParent(), ".wiiuu-deploy-", ".jar");
        try {
            Manifest manifest = new Manifest();
            manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
            manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, MAIN);
            try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(temporary), manifest)) {
                for (JarEntry entry : self.stream().filter(e -> !e.isDirectory() &&
                        (e.getName().startsWith("wiiuu/oem/") || e.getName().equals(BUNDLE + "provision.sh"))).toList()) {
                    try (InputStream in = self.getInputStream(entry)) { entry(out, entry.getName(), in); }
                }
                Properties metadata = new Properties();
                metadata.setProperty("model", model);
                metadata.setProperty("admin", admin);
                metadata.setProperty("prefix", releasePrefix(release));
                properties(out, BUNDLE + "model.properties", metadata);
                try (InputStream in = Files.newInputStream(release)) { entry(out, BUNDLE + "release.zip", in); }
                entry(out, BUNDLE + "secret", new ByteArrayInputStream(encrypted));
                Properties defaults = new Properties();
                Path options = settings.resolve("config.properties");
                if (Files.exists(options)) {
                    try (InputStream in = Files.newInputStream(options)) { defaults.load(in); }
                    defaults.keySet().removeIf(key -> {
                        String name = key.toString();
                        return !SETTINGS.contains(name) && !name.matches("keys\\.p[0-9]+\\.[A-Z0-9_]+")
                                && !name.matches("system\\.[a-z0-9]+\\.hidden");
                    });
                }
                defaults.setProperty("oem.model", model);
                defaults.setProperty("ui.setupNext", "true");
                properties(out, BUNDLE + "defaults.properties", defaults);
                if (content != null) addContent(out, content);
            }
            Files.move(temporary, output);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void addContent(JarOutputStream out, Path content) throws IOException {
        try (var walk = Files.walk(content)) {
            for (Path file : (Iterable<Path>) walk::iterator) {
                if (Files.isSymbolicLink(file)) throw new IOException("Delivered files cannot include a symlink: " + file);
                if (Files.isDirectory(file)) continue;
                if (!Files.isRegularFile(file)) throw new IOException("Unsupported delivered file: " + file);
                String name = content.relativize(file).toString().replace(File.separatorChar, '/');
                if (!name.matches("(roms|themes|system|choccy|saves|reactions)/.+") || name.contains("\\"))
                    throw new IOException("Put delivered files under roms/, themes/, system/, choccy/, saves/ or reactions/: " + name);
                try (InputStream in = Files.newInputStream(file)) { entry(out, BUNDLE + "files/" + name, in); }
            }
        }
    }

    private static void properties(JarOutputStream out, String name, Properties values) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        values.store(bytes, null);
        entry(out, name, new ByteArrayInputStream(bytes.toByteArray()));
    }

    private static void entry(ZipOutputStream out, String name, InputStream in) throws IOException {
        out.putNextEntry(new ZipEntry(name));
        in.transferTo(out);
        out.closeEntry();
    }

    private static InputStream resource(JarFile jar, String name) throws IOException {
        JarEntry entry = jar.getJarEntry(BUNDLE + name);
        if (entry == null) throw new IOException("Missing packaged resource: " + name);
        return jar.getInputStream(entry);
    }

    private static Cipher cipher(int mode, String passphrase, byte[] salt, byte[] nonce) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(passphrase.toCharArray(), salt, 600_000, 256);
        try {
            byte[] key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            try {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
                return cipher;
            } finally {
                Arrays.fill(key, (byte) 0);
            }
        } finally {
            spec.clearPassword();
        }
    }

    private static byte[] seal(String secret, String passphrase) throws GeneralSecurityException, IOException {
        byte[] salt = new byte[16], nonce = new byte[12];
        SecureRandom random = new SecureRandom();
        random.nextBytes(salt);
        random.nextBytes(nonce);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(salt);
        out.write(nonce);
        out.write(cipher(Cipher.ENCRYPT_MODE, passphrase, salt, nonce).doFinal(secret.getBytes(StandardCharsets.UTF_8)));
        return out.toByteArray();
    }

    private static String unlock(JarFile jar) throws IOException, GeneralSecurityException {
        try (InputStream in = resource(jar, "secret")) {
            byte[] salt = in.readNBytes(16), nonce = in.readNBytes(12);
            try {
                return new String(cipher(Cipher.DECRYPT_MODE, password("Deployment unlock passphrase", false),
                        salt, nonce).doFinal(in.readAllBytes()), StandardCharsets.UTF_8);
            } catch (javax.crypto.AEADBadTagException e) {
                throw new IOException("Incorrect unlock passphrase or damaged deployment JAR.");
            }
        }
    }

    private static List<String> hosts(String pattern, Path table) throws IOException {
        if (pattern.contains("{}") != (table != null) || pattern.indexOf("{}") != pattern.lastIndexOf("{}"))
            throw new IOException("Use a single {} with an ID CSV, or enter one hostname without a CSV.");
        List<String> ids = new ArrayList<>();
        if (table == null) ids.add("");
        else {
            // ID cells cannot contain separators or line breaks, even when CSV-quoted.
            List<String> rows = Files.readAllLines(table, StandardCharsets.UTF_8);
            boolean header = false;
            int index = 0;
            int columns = 1;
            for (String row : rows) {
                row = row.replaceFirst("^\uFEFF", "").strip();
                if (row.isEmpty()) continue;
                List<String> cells = csv(row);
                if (!header && ids.isEmpty() && (cells.size() > 1 || cells.get(0).equalsIgnoreCase("id"))) {
                    index = -1;
                    for (int i = 0; i < cells.size(); i++) {
                        if (cells.get(i).equalsIgnoreCase("id")) {
                            if (index >= 0) throw new IOException("CSV has duplicate id columns.");
                            index = i;
                        }
                    }
                    if (index < 0) throw new IOException("A multi-column CSV needs an id header.");
                    columns = cells.size();
                    header = true;
                    continue;
                }
                if (cells.size() != columns || !cells.get(index).matches("[A-Za-z0-9-]+"))
                    throw new IOException("Invalid CSV row or ID. Use letters, digits and hyphens for IDs.");
                ids.add(cells.get(index));
            }
        }
        Set<String> result = new LinkedHashSet<>();
        for (String id : ids) {
            String host = pattern.replace("{}", id).toLowerCase(Locale.ROOT);
            if (host.length() > 253 || !host.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"
                    + "(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*"))
                throw new IOException("Invalid hostname: " + host);
            if (!result.add(host)) throw new IOException("Duplicate host: " + host);
        }
        if (result.isEmpty()) throw new IOException("CSV contains no IDs.");
        return List.copyOf(result);
    }

    private static List<String> csv(String row) throws IOException {
        List<String> values = new ArrayList<>();
        java.util.regex.Matcher cell = java.util.regex.Pattern.compile("\\s*(?:\"((?:[^\"]|\"\")*)\"|([^,\"]*?))\\s*(,|$)").matcher(row);
        int at = 0;
        do {
            cell.region(at, row.length());
            if (!cell.lookingAt()) throw new IOException("Invalid CSV. Keep each record on one line.");
            values.add(cell.group(1) == null ? cell.group(2).strip() : cell.group(1).replace("\"\"", "\""));
            at = cell.end();
        } while (cell.group(3).equals(","));
        return values;
    }

    private static void deploy(JarFile jar) throws Exception {
        if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"))
            throw new IOException("Run the deployer on Linux, macOS, or WSL with OpenSSH installed.");
        Properties model = new Properties();
        try (InputStream in = resource(jar, "model.properties")) { model.load(in); }
        String admin = user(model.getProperty("admin")), pattern = ask("Hostname or pattern containing {}");
        Path table = pattern.contains("{}") ? Path.of(ask("ID CSV path")) : null;
        List<String> hosts = hosts(pattern, table);
        String old = user(ask("Existing SSH administrator"));
        if (old.equals(admin)) throw new IOException("The replacement administrator must use a different name.");
        System.out.println("Model: " + model.getProperty("model") + "\nTargets: " + String.join(", ", hosts));
        System.out.println("WARNING: All data may be lost. Back up every target. Deployment changes system packages, "
                + "the boot session and administrator access, then deletes the old login. Its home is kept.");
        if (!ask("Type DEPLOY to proceed").equals("DEPLOY")) return;
        String oldPassword = password("Existing SSH and sudo password", false);
        String newPassword = unlock(jar);
        Path local = Files.createTempDirectory("wiiuu-oem-deploy-");
        try {
            Path helper = local.resolve("askpass");
            Files.writeString(helper, "#!/bin/sh\nprintf '%s\\n' \"$WIIUU_SSH_PASSWORD\"\n");
            Files.setPosixFilePermissions(helper, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
            Path payload = local.resolve("payload.zip");
            try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(payload))) {
                for (String name : List.of("release.zip", "defaults.properties", "provision.sh")) {
                    try (InputStream in = resource(jar, name)) { entry(out, name, in); }
                }
                for (JarEntry file : jar.stream().filter(e -> e.getName().startsWith(BUNDLE + "files/")).toList()) {
                    try (InputStream in = jar.getInputStream(file)) { entry(out, file.getName().substring(BUNDLE.length()), in); }
                }
            }
            List<String> failed = new ArrayList<>();
            for (String host : hosts) {
                try {
                    System.out.println("Provisioning " + host);
                    provision(host, old, oldPassword, admin, newPassword, model.getProperty("prefix"), helper, payload);
                    System.out.println(host + ": finished");
                } catch (IOException e) {
                    failed.add(host);
                    System.err.println(host + ": " + e.getMessage());
                }
            }
            if (!failed.isEmpty()) throw new IOException("Deployment incomplete on " + String.join(", ", failed));
        } finally {
            remove(local);
        }
    }

    private static void provision(String host, String old, String oldPassword, String admin, String newPassword,
                                  String prefix, Path helper, Path payload) throws IOException, InterruptedException {
        String stage = ssh(host, old, oldPassword, helper, "mktemp -d /tmp/wiiuu-oem.XXXXXXXX", null, null, true).strip();
        if (!stage.matches("/tmp/wiiuu-oem\\.[A-Za-z0-9]{8}")) throw new IOException("Unexpected remote staging path");
        try {
            ssh(host, old, oldPassword, helper, "cat > " + stage + "/payload.zip", payload, null, false);
            ssh(host, old, oldPassword, helper, "umask 077; cat > " + stage + "/admin-password",
                    null, newPassword + "\n", false);
            String setup = "sudo -k -S -p '' -- bash -c " + quote(
                    "set -eu; command -v apt-get >/dev/null; "
                    + "export DEBIAN_FRONTEND=noninteractive; apt-get update; "
                    + "apt-get install -y unzip; "
                    + "unzip -oq " + stage + "/payload.zip -d " + stage + "; "
                    + "bash " + stage + "/provision.sh " + stage + " " + quote(admin) + " " + quote(prefix));
            ssh(host, old, oldPassword, helper, setup, null, oldPassword + "\n", false);
        } finally {
            try {
                ssh(host, old, oldPassword, helper, "rm -rf -- " + stage, null, null, false);
            } catch (IOException e) {
                System.err.println("Could not clean staging on " + host + ": " + stage);
            }
        }
        // A fresh password-only login and fresh sudo authorization must succeed before removing the old account.
        ssh(host, admin, newPassword, helper, "sudo -k -S -p '' -- true", null, newPassword + "\n", false);
        ssh(host, admin, newPassword, helper, "sudo -k -S -p '' -- userdel -- " + quote(old),
                null, newPassword + "\n", false);
    }

    private static String ssh(String host, String user, String password, Path helper, String command,
                              Path input, String text, boolean capture) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder("ssh", "-T", "-o", "ConnectTimeout=12", "-o",
                "ServerAliveInterval=15", "-o", "ServerAliveCountMax=3", "-o", "StrictHostKeyChecking=yes",
                "-o", "NumberOfPasswordPrompts=1", "-o", "PreferredAuthentications=password,keyboard-interactive",
                "-o", "PubkeyAuthentication=no", "-o", "ControlPath=none",
                "-l", user, "--", host, command);
        builder.environment().put("SSH_ASKPASS", helper.toString());
        builder.environment().put("SSH_ASKPASS_REQUIRE", "force");
        builder.environment().put("WIIUU_SSH_PASSWORD", password);
        builder.redirectError(ProcessBuilder.Redirect.INHERIT);
        if (input != null) builder.redirectInput(input.toFile());
        if (!capture) builder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        Process process = builder.start();
        try {
            if (input == null) {
                try (OutputStream out = process.getOutputStream()) {
                    if (text != null) out.write(text.getBytes(StandardCharsets.UTF_8));
                }
            }
            String output = capture ? new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8) : "";
            int exit = process.waitFor();
            if (exit != 0) throw new IOException("SSH command failed (exit " + exit + ").");
            return output;
        } finally {
            if (process.isAlive()) process.destroy();
        }
    }

    private static String quote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static void remove(Path dir) throws IOException {
        try (var walk = Files.walk(dir)) {
            for (Path file : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
        }
    }
}
