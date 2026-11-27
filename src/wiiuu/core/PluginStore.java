package wiiuu.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Source-bundled JavaScript apps. Remote websites have no Java or Node bridge. */
public final class PluginStore {
    public record Entry(String id, String name, String description, String script, String type) {
        public boolean nativePlugin() { return type.equals("native"); }
        public boolean embeddedPlugin() { return type.equals("embedded"); }
        /** Reviewed external desktop companion; no WII-UU plugin runtime required. */
        public boolean externalPlugin() { return type.equals("external"); }
    }
    private static final List<Entry> CATALOG = readCatalog();
    private static final Map<String, Process> RUNNING = new ConcurrentHashMap<>();
    private record InProcess(Runnable stop, java.util.concurrent.CompletableFuture<Void> done) {}
    private static final Map<String, InProcess> IN_PROCESS = new ConcurrentHashMap<>();
    private static boolean installing;
    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            for (InProcess session : IN_PROCESS.values()) session.stop().run();
            for (Process process : RUNNING.values()) {
                process.descendants().forEach(ProcessHandle::destroy);
                process.destroy();
            }
        }, "plugin-shutdown"));
    }
    private PluginStore() {}

    private static byte[] resource(String name) throws IOException {
        try (InputStream in = PluginStore.class.getResourceAsStream("/plugins/" + name)) {
            if (in == null) throw new IOException("Missing bundled plugin resource: " + name);
            return in.readAllBytes();
        }
    }

    private static List<Entry> readCatalog() {
        try {
            List<Entry> entries = new ArrayList<>();
            for (Object item : MiniJson.array(MiniJson.parse(new String(resource("catalog.json"), StandardCharsets.UTF_8)))) {
                Map<String, Object> row = MiniJson.object(item);
                String id = java.util.Objects.toString(MiniJson.string(row.get("id")), "");
                String script = java.util.Objects.toString(MiniJson.string(row.get("script")), "");
                String name = java.util.Objects.toString(MiniJson.string(row.get("name")), "");
                String type = java.util.Objects.toString(MiniJson.string(row.get("type")), "web");
                boolean external = "external".equals(type);
                boolean embedded = "embedded".equals(type);
                if (!id.matches("[a-z0-9][a-z0-9-]{0,47}")
                        || !((external || embedded) ? script.isEmpty() : script.matches("[a-z0-9-]+\\.js"))
                        || name.isBlank() || name.length() > 48 || entries.stream().anyMatch(e -> e.id().equals(id)))
                    throw new IOException("Invalid bundled plugin catalog entry");
                // External apps execute native binaries, so only the explicitly reviewed
                // Xbox integration is allowed. Never accept arbitrary catalog commands.
                if (!List.of("web", "native", "external", "embedded").contains(type) || external && !id.equals("xcloud") || embedded && !id.equals("choccy"))
                    throw new IOException("Unknown bundled plugin type");
                if (!external && !embedded) resource("store/" + script);
                entries.add(new Entry(id, name, java.util.Objects.toString(MiniJson.string(row.get("description")), ""), script, type));
            }
            return List.copyOf(entries);
        } catch (Exception e) { throw new IllegalStateException("Cannot read bundled plugin catalog", e); }
    }

    public static List<Entry> catalog() { return CATALOG; }
    public static List<Entry> active(Config config) {
        return CATALOG.stream().filter(e -> enabled(config, e)).toList();
    }
    public static boolean enabled(Config config, Entry entry) { return config.getBool("plugin." + entry.id() + ".enabled", false); }
    /** A native cloud session is active; route phone input to its virtual Xbox pad, not the menu. */
    public static boolean externalRunning(Config config) {
        for (Entry entry : CATALOG) {
            if (!entry.externalPlugin()) continue;
            InProcess session=IN_PROCESS.get(key(config,entry));
            if(session!=null&&!session.done().isDone())return true;
            Process process = RUNNING.get(key(config, entry));
            if (process != null && process.isAlive()) return true;
        }
        return false;
    }
    private static String key(Config config, Entry entry) { return config.userHome().toAbsolutePath() + "/" + entry.id(); }
    /** Only the reviewed xCloud dock app may register its isolated Chromium process. */
    public static synchronized void trackExternal(Config config, Entry entry, Process process) {
        if (!CATALOG.contains(entry) || !entry.externalPlugin() || !"xcloud".equals(entry.id()))
            throw new IllegalArgumentException("Invalid external streaming app");
        Process prior = RUNNING.put(key(config, entry), process);
        if (prior != null && prior.isAlive() && prior != process) prior.destroy();
        process.onExit().thenRun(() -> RUNNING.remove(key(config, entry), process));
    }

    /** Track the in-JVM Xbox media session without treating our own JVM as a child process. */
    public static synchronized void trackInProcess(Config config, Entry entry, Runnable stop,
            java.util.concurrent.CompletableFuture<Void> done) {
        if (!CATALOG.contains(entry) || !entry.externalPlugin() || !"xcloud".equals(entry.id()))
            throw new IllegalArgumentException("Invalid external streaming app");
        String key=key(config,entry);
        InProcess session=new InProcess(stop,done);
        InProcess prior=IN_PROCESS.put(key,session);
        if(prior!=null&&!prior.done().isDone())prior.stop().run();
        done.whenComplete((ignored,error)->IN_PROCESS.remove(key,session));
    }

    public static synchronized void setEnabled(Config config, Entry entry, boolean enabled) {
        if (!CATALOG.contains(entry)) throw new IllegalArgumentException("Unknown plugin");
        config.set("plugin." + entry.id() + ".enabled", Boolean.toString(enabled));
        config.save();
        if (!enabled) {
            InProcess session=IN_PROCESS.get(key(config,entry));
            if(session!=null)session.stop().run();
            Process process = RUNNING.get(key(config, entry));
            if (process != null) {
                process.descendants().forEach(ProcessHandle::destroy);
                process.destroy();
            }
        }
    }
    private static Path runtime(Config config) { return config.home().resolve("plugin-runtime"); }
    private static boolean windows() { return System.getProperty("os.name").toLowerCase().contains("win"); }
    private static Path executable(Config config) {
        String os = System.getProperty("os.name").toLowerCase();
        return runtime(config).resolve("node_modules/electron/dist").resolve(windows() ? "electron.exe"
                : os.contains("mac") ? "Electron.app/Contents/MacOS/Electron" : "electron");
    }
    public static boolean ready(Config config) {
        try { return Files.isRegularFile(executable(config)) && Files.readString(runtime(config).resolve("installed-lock"))
                .equals(new String(resource("host/package-lock.json"), StandardCharsets.UTF_8)); }
        catch (IOException e) { return false; }
    }
    private static synchronized void prepare(Config config) throws IOException {
        Path dir = Files.createDirectories(runtime(config));
        for (String file : List.of("package.json", "package-lock.json", "main.cjs", "api.cjs", "native-api.cjs", "native-host.cjs", "auth-window.cjs"))
            copy("host/" + file, dir.resolve(file));
        copy("catalog.json", dir.resolve("catalog.json"));
        Files.createDirectories(dir.resolve("store"));
        for (Entry entry : CATALOG) if (!entry.externalPlugin() && !entry.embeddedPlugin()) copy("store/" + entry.script(), dir.resolve("store").resolve(entry.script()));
    }
    private static void copy(String name, Path target) throws IOException {
        byte[] content = resource(name);
        if (Files.isRegularFile(target) && java.util.Arrays.equals(Files.readAllBytes(target), content)) return;
        Path pending = Files.createTempFile(target.getParent(), ".plugin-", ".tmp");
        try {
            Files.write(pending, content);
            try { Files.move(pending, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(pending, target, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(pending); }
    }
    private static Path log(Config config, String name) throws IOException {
        return Files.createDirectories(config.logDir()).resolve(name);
    }
    public static synchronized boolean installing() { return installing; }
    private static String npm() {
        // Desktop launches on macOS often omit Homebrew/MacPorts from PATH.
        String search = System.getenv().getOrDefault("PATH", "") + java.io.File.pathSeparator
                + "/opt/homebrew/bin" + java.io.File.pathSeparator + "/usr/local/bin" + java.io.File.pathSeparator + "/opt/local/bin";
        for (String directory : search.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
            if (directory.isBlank()) continue;
            Path candidate = Path.of(directory).resolve("npm");
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) return candidate.toAbsolutePath().toString();
        }
        return "npm";
    }
    /** Requires Node.js/npm on PATH; only the explicit Settings install action downloads a runtime. */
    public static void install(Config config, Consumer<String> done) {
        synchronized (PluginStore.class) {
            if (installing) { done.accept("The plugin runtime is already installing."); return; }
            if (!RUNNING.isEmpty() || !IN_PROCESS.isEmpty()) { done.accept("Close plugin windows before updating the runtime."); return; }
            installing = true;
        }
        Thread thread = new Thread(() -> {
            Process process = null;
            String result;
            try {
                prepare(config);
                List<String> command = windows() ? List.of("cmd.exe", "/d", "/c", "npm.cmd", "ci", "--no-audit", "--no-fund", "--engine-strict")
                        : List.of(npm(), "ci", "--no-audit", "--no-fund", "--engine-strict");
                ProcessBuilder builder = new ProcessBuilder(command).directory(runtime(config).toFile())
                        .redirectErrorStream(true).redirectOutput(log(config, "plugin-runtime.log").toFile());
                if (!windows() && Path.of(command.get(0)).isAbsolute()) {
                    String bin = Path.of(command.get(0)).getParent().toString();
                    builder.environment().put("PATH", bin + java.io.File.pathSeparator + builder.environment().getOrDefault("PATH", ""));
                }
                process = builder.start();
                if (!process.waitFor(20, TimeUnit.MINUTES)) throw new IOException("Runtime installation timed out");
                if (process.exitValue() != 0 || !Files.isRegularFile(executable(config)))
                    throw new IOException("Installation failed; see " + log(config, "plugin-runtime.log"));
                Files.write(runtime(config).resolve("installed-lock"), resource("host/package-lock.json"));
                result = "Plugin runtime installed. Activate an app to add it to the bottom bar.";
            } catch (Exception e) { result = "Could not install plugin runtime: " + e.getMessage() + ". Node.js 22.12+ with npm must be installed."; }
            finally {
                if (process != null && process.isAlive()) { process.descendants().forEach(ProcessHandle::destroy); process.destroy(); }
                synchronized (PluginStore.class) { installing = false; }
            }
            done.accept(result);
        }, "plugin-runtime-install");
        thread.setDaemon(true);
        thread.start();
    }
    /** Starts the reviewed JS host in Node mode; the view is rendered by Java, not Chromium. */
    public static synchronized Process startNative(Config config, Entry entry) throws IOException {
        if (!CATALOG.contains(entry) || !entry.nativePlugin()) throw new IOException("Unknown native plugin");
        if (!enabled(config, entry)) throw new IOException("Plugin is disabled");
        if (installing || !ready(config)) throw new IOException("Install the plugin runtime in Settings > System > Plugin store");
        Process previous = RUNNING.get(key(config, entry));
        if (previous != null && previous.isAlive()) throw new IOException("Native plugin is already open");
        prepare(config);
        ProcessBuilder builder = new ProcessBuilder(executable(config).toAbsolutePath().toString(),
                runtime(config).resolve("native-host.cjs").toAbsolutePath().toString(), entry.id(),
                config.userHome().toAbsolutePath().toString(), config.romBase().toAbsolutePath().toString())
                .redirectError(ProcessBuilder.Redirect.appendTo(log(config, "plugin-" + entry.id() + ".log").toFile()));
        builder.environment().put("ELECTRON_RUN_AS_NODE", "1");
        builder.environment().remove("NODE_OPTIONS");
        Process process = builder.start();
        RUNNING.put(key(config, entry), process);
        process.onExit().thenAccept(p -> RUNNING.remove(key(config, entry), p));
        return process;
    }

    public static void launch(Config config, Entry entry, Consumer<String> report) {
        Thread thread = new Thread(() -> {
            try {
                synchronized (PluginStore.class) {
                    if (!CATALOG.contains(entry)) throw new IOException("Unknown bundled plugin");
                    if (!enabled(config, entry)) throw new IOException("Plugin is disabled");
                    if (entry.externalPlugin()) {
                        Process old = RUNNING.get(key(config, entry));
                        if (old != null && old.isAlive()) { report.accept(entry.name() + " is already running. Switch to its window."); return; }
                        report.accept("Open Xbox Cloud Gaming from the WII-UU dock to use its native login and catalog.");
                        return;
                    }
                    if (entry.embeddedPlugin() || entry.nativePlugin()) throw new IOException("Open this native plugin from the WII-UU bottom bar");
                    if (installing || !ready(config)) throw new IOException("Install the plugin runtime in Settings > System > Plugin store (Node.js/npm required)");
                    Process old = RUNNING.get(key(config, entry));
                    if (old != null && old.isAlive()) { report.accept(entry.name() + " is already open. Switch to its window."); return; }
                    prepare(config);
                    ProcessBuilder builder = new ProcessBuilder(executable(config).toString(), runtime(config).toAbsolutePath().toString(),
                            entry.id(), config.userHome().toAbsolutePath().toString(), config.romBase().toAbsolutePath().toString())
                            .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log(config, "plugin-" + entry.id() + ".log").toFile()));
                    builder.environment().remove("ELECTRON_RUN_AS_NODE");
                    Process process = builder.start();
                    RUNNING.put(key(config, entry), process);
                    process.onExit().thenAccept(p -> {
                        RUNNING.remove(key(config, entry), p);
                        if (p.exitValue() != 0 && enabled(config, entry)) report.accept(entry.name() + " could not start. See " + config.logDir().resolve("plugin-" + entry.id() + ".log"));
                    });
                }
            } catch (Exception e) { report.accept(e.getMessage()); }
        }, "plugin-launch");
        thread.setDaemon(true);
        thread.start();
    }
}
