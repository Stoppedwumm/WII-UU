package wiiuu.ui;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import wiiuu.core.Config;
import wiiuu.core.PluginStore;

public final class PluginDockTest {
    public static void main(String[] args) throws Exception {
        Config config = new Config(Files.createTempDirectory("wiiuu-plugin-dock-"));
        Sfx.setEnabled(false);
        for (var entry : PluginStore.catalog()) PluginStore.setEnabled(config, entry, true);
        SwingUtilities.invokeAndWait(() -> {
            try {
                MenuView view = new MenuView(config, true);
                view.setSize(1600, 900);
                var inDock = MenuView.class.getDeclaredField("inDock"); inDock.setAccessible(true); inDock.setBoolean(view, true);
                var selection = MenuView.class.getDeclaredField("dockSel"); selection.setAccessible(true);
                for (int i = 0; i < 5; i++) view.navigate(1, 0);
                if (selection.getInt(view) != 5) throw new AssertionError("Cannot navigate from built-in controls to first app");
                save(view, "plugin-dock.png");
                var embedded = PluginStore.catalog().stream().filter(PluginStore.Entry::embeddedPlugin).findFirst().orElseThrow();
                selection.setInt(view, 5 + PluginStore.active(config).indexOf(embedded));
                view.activate();
                if (view.getComponentCount() != 1 || !(view.getComponent(0) instanceof choccymilk.App))
                    throw new AssertionError("Choccy Milk must be a child of the existing menu");
                view.doLayout();
                var close = MenuView.class.getDeclaredMethod("closeEmbeddedPlugin");
                close.setAccessible(true);
                close.invoke(view);
                if (view.getComponentCount() != 0) throw new AssertionError("Embedded app must be removed on close");
                for (var entry : PluginStore.catalog()) PluginStore.setEnabled(config, entry, false);
                view.reloadPluginDock();
                if (selection.getInt(view) > 4) throw new AssertionError("Selection must stay valid after disabling apps");
                var services = new SettingsScreen.Services(config, null, () -> "", () -> {}, () -> {}, () -> {}, null,
                        () -> {}, () -> {}, null, () -> {}, null, s -> {}, () -> false, () -> false, () -> null, () -> null);
                var viewerServices = new SettingsScreen.Services(config, null, () -> "", () -> {}, () -> {}, () -> {}, null,
                        () -> {}, () -> {}, null, () -> {}, new wiiuu.core.Achievements(config),
                        s -> {}, () -> false, () -> false, () -> null, () -> null);
                var quickRef = new java.util.concurrent.atomic.AtomicReference<MenuView>();
                MenuView quick = new MenuView(config, true, new MenuView.Actions() {
                    @Override public void launch(wiiuu.core.Game game) {}
                    @Override public void openSettings() { throw new AssertionError("Dock opened Settings instead of viewer"); }
                    @Override public void openAchievements() { quickRef.get().openAchievements(viewerServices); }
                    @Override public void quit() {}
                    @Override public void refresh() {}
                    @Override public void closeGame() {}
                });
                quickRef.set(quick);
                inDock.setBoolean(quick, true);
                selection.setInt(quick, 4);
                quick.activate();
                // Drain the queued dock action before checking the overlay.
                java.awt.SecondaryLoop loop = java.awt.Toolkit.getDefaultToolkit().getSystemEventQueue().createSecondaryLoop();
                SwingUtilities.invokeLater(loop::exit);
                loop.enter();
                var overlayField = MenuView.class.getDeclaredField("settings"); overlayField.setAccessible(true);
                SettingsScreen overlay = (SettingsScreen) overlayField.get(quick);
                if (overlay == null) throw new AssertionError("Dock did not open viewer directly");
                var direct = SettingsScreen.class.getDeclaredField("achievementsOnly"); direct.setAccessible(true);
                if (!direct.getBoolean(overlay)) throw new AssertionError("Viewer is not standalone");
                quick.back();
                if (!overlay.isClosing()) throw new AssertionError("B should return directly to library");
                SettingsScreen settings = new SettingsScreen(view, services);
                var category = SettingsScreen.class.getDeclaredMethod("showCategory", int.class);
                category.setAccessible(true); category.invoke(settings, 4);
                settings.navigate(1, 0);
                settings.navigate(0, 1); // System > User profiles comes before Plugin store
                settings.activate(); // System > Plugin store
                settings.navigate(0, 1); // first activation toggle, after runtime installer
                settings.activate();
                if (!PluginStore.enabled(config, PluginStore.catalog().get(0))) throw new AssertionError("Store toggle did not activate app");
                var opening = SettingsScreen.class.getDeclaredField("opened"); opening.setAccessible(true); opening.setLong(settings, 0);
                var changed = SettingsScreen.class.getDeclaredField("changed"); changed.setAccessible(true); changed.setLong(settings, 0);
                BufferedImage store = new BufferedImage(1600, 900, BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = store.createGraphics(); settings.paint(graphics, 1600, 900); graphics.dispose();
                ImageIO.write(store, "png", Path.of("build", "plugin-store.png").toFile());
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        System.out.println("Activated apps render and navigate in the bottom bar; store renders");
    }
    private static void save(MenuView view, String name) throws Exception {
        view.settle();
        BufferedImage image = new BufferedImage(1600, 900, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics(); view.paint(g); g.dispose();
        ImageIO.write(image, "png", Path.of("build", name).toFile());
    }
}
