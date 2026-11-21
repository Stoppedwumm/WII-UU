package wiiuu.ui;

import java.awt.Graphics2D;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import wiiuu.core.Config;
import wiiuu.input.PadButton;

/** Pre-login screen must allow full controller navigation without starting a user session. */
public final class UserSelectScreenTest {
    public static void main(String[] args) throws Exception {
        var home = Files.createTempDirectory("wiiuu-startup-users-");
        Config.Users.Profile alice = Config.Users.create(home, "Alice");
        Config.Users.Profile bob = Config.Users.create(home, "Bob");
        List<Config.Users.Profile> profiles = Config.Users.list(home);
        Config.Users.select(home, alice.id());
        Config.Users.requireChooserOnNextBoot(home);
        String original = Config.Users.activeId(home);

        SwingUtilities.invokeAndWait(() -> {
            AtomicReference<String> selected = new AtomicReference<>();
            AtomicInteger exits = new AtomicInteger();
            UserSelectScreen screen = new UserSelectScreen(profiles, selected::set, exits::incrementAndGet);
            screen.setSize(1280, 720);
            BufferedImage buffer = new BufferedImage(1280, 720, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = buffer.createGraphics();
            screen.paint(g);
            g.dispose();

            check(screen.selectedIndex() == 0, "First user is selected by default");
            screen.navigate(1, 0);
            check(screen.selectedIndex() == 1, "D-pad RIGHT selects the next profile");
            screen.navigate(1, 0);
            check(screen.selectedIndex() == 2, "Analog stick RIGHT can navigate profiles");
            screen.navigate(-1, 0);
            check(screen.selectedIndex() == 1, "D-pad LEFT navigates back");
            screen.activate();
            check(profiles.get(1).id().equals(selected.get()), "A activates the highlighted user");
            screen.activate();
            check(profiles.get(1).id().equals(selected.get()), "Selection is only committed once");
            check(exits.get() == 0, "Selection must not exit WII-UU");

            UserSelectScreen quit = new UserSelectScreen(profiles, id -> selected.set("WRONG"), exits::incrementAndGet);
            quit.navigate(0, 1);
            check(quit.quitFocused(), "D-pad DOWN focuses Quit");
            quit.navigate(0, -1);
            check(!quit.quitFocused(), "D-pad UP returns focus to accounts");
            quit.back();
            check(exits.get() == 1, "B cancels without signing in");
            check(!"WRONG".equals(selected.get()), "B did not open an account");

            UserSelectScreen homeButton = new UserSelectScreen(profiles, id -> selected.set("WRONG"), exits::incrementAndGet);
            check(homeButton.pressed(-1, PadButton.HOME, true), "HOME handled on controller");
            check(exits.get() == 2, "HOME cancels login");

            UserSelectScreen mouse = new UserSelectScreen(profiles, selected::set, exits::incrementAndGet);
            mouse.setSize(1280, 720);
            Graphics2D g2 = buffer.createGraphics();
            mouse.paint(g2);
            g2.dispose();
            // At 1280x720, first account tile is centered around x=394, y=430.
            mouse.dispatchEvent(new MouseEvent(mouse, MouseEvent.MOUSE_CLICKED,
                    System.currentTimeMillis(), 0, 394, 430, 1, false));
            check(profiles.get(0).id().equals(selected.get()), "Mouse selects a profile without a dialog");
        });
        check(Config.Users.activeId(home).equals(original),
                "UI does not switch the global active account until Main commits selection");
        check(Config.Users.chooserRequired(home), "Sign-out marker is not cleared by rendering the login screen");
        System.out.println("UserSelectScreenTest passed");
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
