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
                for (int i = 0; i < 4; i++) view.navigate(1, 0);
                if (selection.getInt(view) != 4) throw new AssertionError("Cannot navigate from Settings controls to first app");
                save(view, "plugin-dock.png");
                for (var entry : PluginStore.catalog()) PluginStore.setEnabled(config, entry, false);
                view.reloadPluginDock();
                if (selection.getInt(view) > 3) throw new AssertionError("Selection must stay valid after disabling apps");
                var services = new SettingsScreen.Services(config, null, () -> "", () -> {}, () -> {}, null,
                        () -> {}, () -> {}, null, () -> {}, null, s -> {}, () -> false, () -> false, () -> null, () -> null);
                SettingsScreen settings = new SettingsScreen(view, services);
                var category = SettingsScreen.class.getDeclaredMethod("showCategory", int.class);
                category.setAccessible(true); category.invoke(settings, 4);
                settings.navigate(1, 0);
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
