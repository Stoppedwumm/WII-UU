package wiiuu.ui;

import java.awt.geom.Rectangle2D;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import wiiuu.core.*;
import wiiuu.input.PadButton;

public final class GameAchievementActionTest {
    public static void main(String[] args) throws Exception {
        Config config = new Config(Files.createTempDirectory("achievement-action"));
        var nes = Systems.byId("nes").orElseThrow();
        Game first = new Game(nes, "First", config.home().resolve("first.nes"), List.of());
        Game second = new Game(nes, "Second", config.home().resolve("second.nes"), List.of());
        AtomicReference<Game> opened = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                Sfx.setEnabled(false);
                MenuView menu = new MenuView(config, true, new MenuView.Actions() {
                    public void launch(Game game) { throw new AssertionError("Shortcut launched ROM"); }
                    public void openSettings() { throw new AssertionError("Shortcut opened Settings"); }
                    public void openAchievements(Game game) { opened.set(game); }
                    public void quit() {}
                    public void refresh() {}
                    public void closeGame() {}
                });
                menu.setSize(1280, 720);
                Field screen = MenuView.class.getDeclaredField("screen");
                screen.setAccessible(true);
                Field system = MenuView.class.getDeclaredField("openSystem");
                system.setAccessible(true);
                for (Object value : screen.getType().getEnumConstants())
                    if (value.toString().equals("GAMES")) screen.set(menu, value);
                system.set(menu, nes);
                menu.setSnapshot(new Library.Snapshot(1, Map.of(nes, List.of(first, second))));
                menu.settle();
                if (!menu.pressed(1, PadButton.Y, true) || !first.equals(opened.get()))
                    throw new AssertionError("Y did not open selected game's achievements");
                Method geom = MenuView.class.getDeclaredMethod("geom");
                geom.setAccessible(true);
                Object layout = geom.invoke(menu);
                Method rect = MenuView.class.getDeclaredMethod("achievementActionRect", layout.getClass(), int.class);
                rect.setAccessible(true);
                Rectangle2D bounds = (Rectangle2D) rect.invoke(menu, layout, 1);
                int x = (int) bounds.getCenterX(), y = (int) bounds.getCenterY();
                Method hover = MenuView.class.getDeclaredMethod("hoverAchievementGame", int.class, int.class);
                hover.setAccessible(true);
                hover.invoke(menu, x, y);
                Method click = MenuView.class.getDeclaredMethod("clickAchievementAction", int.class, int.class);
                click.setAccessible(true);
                if (!Boolean.TRUE.equals(click.invoke(menu, x, y)) || !second.equals(opened.get()))
                    throw new AssertionError("Mouse did not open hovered game's achievements");
                var services = new SettingsScreen.Services(config, null, () -> "", () -> {}, () -> {}, () -> {}, null,
                        () -> {}, () -> {}, null, () -> {}, new Achievements(config),
                        value -> {}, () -> false, () -> false, () -> null, () -> null);
                menu.openAchievements(services, second);
                Field overlay = MenuView.class.getDeclaredField("settings");
                overlay.setAccessible(true);
                SettingsScreen viewer = (SettingsScreen) overlay.get(menu);
                if (viewer == null) throw new AssertionError("Viewer missing");
                menu.back();
                if (!viewer.isClosing()) throw new AssertionError("B did not close direct viewer");
            } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        });
        System.out.println("GameAchievementActionTest passed");
    }
}
