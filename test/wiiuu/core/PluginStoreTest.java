package wiiuu.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class PluginStoreTest {
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--sleep")) {
            Thread.sleep(30_000);
            return;
        }
        Config config = new Config(Files.createTempDirectory("wiiuu-plugin-test-"));
        if (PluginStore.catalog().isEmpty() || !PluginStore.active(config).isEmpty()) throw new AssertionError("Default activation");
        var plugin = PluginStore.catalog().get(0);
        CountDownLatch disabled = new CountDownLatch(1);
        AtomicReference<String> message = new AtomicReference<>();
        PluginStore.launch(config, plugin, result -> { message.set(result); disabled.countDown(); });
        if (!disabled.await(5, TimeUnit.SECONDS) || !message.get().contains("disabled")) throw new AssertionError(message);
        PluginStore.setEnabled(config, plugin, true);
        Config restart = new Config(config.home());
        if (!PluginStore.active(restart).contains(plugin)) throw new AssertionError("Activation did not persist");
        if (PluginStore.ready(restart)) throw new AssertionError("Runtime should require installation");
        CountDownLatch missing = new CountDownLatch(1);
        PluginStore.launch(restart, plugin, result -> { message.set(result); missing.countDown(); });
        if (!missing.await(5, TimeUnit.SECONDS) || !message.get().contains("Settings > System > Plugin store"))
            throw new AssertionError(message);
        if (Files.exists(config.home().resolve("plugin-runtime"))) throw new AssertionError("Launch must not silently install");
        PluginStore.setEnabled(restart, plugin, false);
        if (!PluginStore.active(new Config(config.home())).isEmpty()) throw new AssertionError("Deactivation did not persist");
        var embedded = PluginStore.catalog().stream().filter(PluginStore.Entry::embeddedPlugin).findFirst().orElseThrow();
        if (!embedded.id().equals("choccy") || !embedded.script().isEmpty() || embedded.nativePlugin() || embedded.externalPlugin())
            throw new AssertionError("Embedded plugin catalog contract");
        PluginStore.setEnabled(config, embedded, true);
        if (!PluginStore.active(new Config(config.home())).contains(embedded)) throw new AssertionError("Embedded activation not saved");
        PluginStore.setEnabled(config, embedded, false);
        // A running native process must count as game mode for phone input, but
        // another user's processes and already-exited processes must not.
        var external = PluginStore.catalog().stream().filter(PluginStore.Entry::externalPlugin)
                .findFirst().orElseThrow();
        if (PluginStore.externalRunning(config)) throw new AssertionError("Cloud should start idle");
        String javaName = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", javaName);
        Process cloud = new ProcessBuilder(java.toString(), "-cp", System.getProperty("java.class.path"),
                PluginStoreTest.class.getName(), "--sleep").start();
        Field runningField = PluginStore.class.getDeclaredField("RUNNING");
        runningField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Process> running = (Map<String, Process>) runningField.get(null);
        String cloudKey = config.userHome().toAbsolutePath() + "/" + external.id();
        running.put(cloudKey, cloud);
        try {
            if (!PluginStore.externalRunning(config)) throw new AssertionError("Cloud process not detected");
            Config other = new Config(Files.createTempDirectory("wiiuu-plugin-another-user-"));
            if (PluginStore.externalRunning(other)) throw new AssertionError("Cloud leaked across users");
        } finally {
            cloud.destroyForcibly();
            cloud.waitFor(5, TimeUnit.SECONDS);
            running.remove(cloudKey, cloud);
        }
        if (PluginStore.externalRunning(config)) throw new AssertionError("Cloud still active after exit");
        System.out.println("Plugin activation, persistence, native process and runtime gating passed");
    }
}
