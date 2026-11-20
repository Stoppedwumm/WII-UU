package wiiuu.core;

import java.nio.file.*;
import java.util.concurrent.*;
import java.util.function.Predicate;

/** Runs the real packaged JS host in Electron's Node mode, with no browser or display server. */
public final class NativePluginBridgeTest {
    private static NativePluginSession.View await(BlockingQueue<NativePluginSession.View> views, Predicate<NativePluginSession.View> match) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            var view = views.poll(200, TimeUnit.MILLISECONDS);
            if (view != null && match.test(view)) return view;
        }
        throw new AssertionError("Native host did not produce expected view");
    }
    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("wiiuu-native-bridge-");
        Config config = new Config(home);
        Path runtime = home.resolve("plugin-runtime");
        Files.createDirectories(runtime.resolve("node_modules/electron"));
        Files.createSymbolicLink(runtime.resolve("node_modules/electron/dist"), Path.of(args[0]).toAbsolutePath());
        try (var lock = PluginStore.class.getResourceAsStream("/plugins/host/package-lock.json")) { Files.copy(lock, runtime.resolve("installed-lock")); }
        var entry = PluginStore.catalog().stream().filter(PluginStore.Entry::nativePlugin).findFirst().orElseThrow();
        PluginStore.setEnabled(config, entry, true);
        BlockingQueue<NativePluginSession.View> views = new LinkedBlockingQueue<>();
        BlockingQueue<String> errors = new LinkedBlockingQueue<>();
        try (var session = NativePluginSession.open(config, entry, views::add, errors::add, () -> {})) {
            var initial = await(views, view -> view.entries().size() == 2);
            session.activate(initial.entries().get(0).buttons().get(0).id());
            await(views, view -> view.status().contains("callback ran in JavaScript"));
            session.activate(initial.entries().get(1).buttons().get(0).id());
            var details = await(views, view -> view.title().equals("Details"));
            session.activate(details.entries().get(0).buttons().get(0).id());
            await(views, view -> view.title().equals("Native plugin demo"));
            if (!errors.isEmpty()) throw new AssertionError(errors.toString());
        }
        System.out.println("Real Java/JavaScript bridge: native panels, async callbacks and view switching passed");
    }
}
