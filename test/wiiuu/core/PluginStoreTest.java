package wiiuu.core;

import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class PluginStoreTest {
    public static void main(String[] args) throws Exception {
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
        System.out.println("Plugin activation, persistence and runtime gating passed");
    }
}
