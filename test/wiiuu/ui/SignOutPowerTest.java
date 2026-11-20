package wiiuu.ui;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import wiiuu.core.Config;
import wiiuu.core.Game;

/** The Power button must offer a logout path independent of ordinary Shutdown. */
public final class SignOutPowerTest {
    public static void main(String[] args) throws Exception {
        Config config = new Config(Files.createTempDirectory("wiiuu-logout-power-"));
        config.set("ui.bootAnimation", "false");
        config.set("ui.music", "false");
        config.set("ui.sounds", "false");
        config.saveChecked();
        AtomicReference<String> action = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                // Offscreen preview does not start a Swing timer that could keep CI alive.
                MenuView menu = new MenuView(config, true, new MenuView.Actions() {
                    @Override public void launch(Game game) {}
                    @Override public void openSettings() {}
                    @Override public void quit() { action.set("quit"); }
                    @Override public void refresh() {}
                    @Override public void closeGame() {}
                    @Override public void power(String choice) { action.set(choice); }
                });
                menu.setSize(1280, 720);
                menu.signOut();
                check("signout".equals(action.get()), "Profile sign-out delegates to the power handler");

                Method method = MenuView.class.getDeclaredMethod("powerItems");
                method.setAccessible(true);
                List<?> options = (List<?>) method.invoke(null);
                int index = -1;
                String label = "";
                for (int i = 0; i < options.size(); i++) {
                    Object item = options.get(i);
                    Method key = item.getClass().getDeclaredMethod("action");
                    key.setAccessible(true);
                    if ("signout".equals(key.invoke(item))) {
                        index = i;
                        Method name = item.getClass().getDeclaredMethod("label");
                        name.setAccessible(true);
                        label = String.valueOf(name.invoke(item));
                        break;
                    }
                }
                check(index >= 0, "Power menu contains a sign-out action");
                check(label.contains("Sign Out"), "Sign-out action has a readable label");
                boolean console = "1".equals(System.getenv("WIIUU_CONSOLE"));
                check(label.contains(console ? "Shut Down" : "Quit"),
                        "Power action is labeled correctly for console or desktop");
                action.set(null);
                Method choose = MenuView.class.getDeclaredMethod("choosePower", int.class);
                choose.setAccessible(true);
                choose.invoke(menu, index);
                check("signout".equals(action.get()), "Clicking the Power menu entry signs out");
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
        System.out.println("SignOutPowerTest passed");
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
