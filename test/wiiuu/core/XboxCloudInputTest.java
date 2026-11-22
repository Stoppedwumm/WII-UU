package wiiuu.core;

import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.SwingUtilities;
import wiiuu.input.InputRouter;
import wiiuu.input.KeyMap;
import wiiuu.input.PadButton;

/** A running native client must never receive menu actions or injected keys. */
public final class XboxCloudInputTest {
    public static void main(String[] args) throws Exception {
        Config config = new Config(Files.createTempDirectory("wiiuu-cloud-input-"));
        AtomicBoolean external = new AtomicBoolean(false);
        AtomicInteger activations = new AtomicInteger();
        CountDownLatch firstMenu = new CountDownLatch(1), returnedMenu = new CountDownLatch(1);

        InputRouter input = new InputRouter(new KeyMap(config), external::get);
        input.setKeysEnabled(() -> !external.get());
        input.setMenu(new InputRouter.MenuActions() {
            @Override public void navigate(int x, int y) { }
            @Override public void activate() {
                if (activations.incrementAndGet() == 1) firstMenu.countDown();
                else returnedMenu.countDown();
            }
            @Override public void back() { throw new AssertionError("Back must not reach menu"); }
            @Override public void page(int delta) { }
            @Override public void toggleGamepadInfo() { }
            @Override public void refresh() { }
        });

        input.button(1, PadButton.A, true);
        if (!firstMenu.await(3, TimeUnit.SECONDS)) throw new AssertionError("Menu input did not work");
        input.button(1, PadButton.A, false);
        external.set(true);
        input.button(1, PadButton.B, true);
        input.button(1, PadButton.B, false);
        input.button(1, PadButton.A, true);
        input.button(1, PadButton.A, false);
        input.stick(1, 0, 1, 0);
        input.stick(1, 0, 0, 0);
        Thread.sleep(150);
        SwingUtilities.invokeAndWait(() -> { });
        if (activations.get() != 1)
            throw new AssertionError("Cloud input operated WII-UU menu: " + activations.get());

        external.set(false);
        input.button(1, PadButton.A, true);
        if (!returnedMenu.await(3, TimeUnit.SECONDS))
            throw new AssertionError("Menu did not resume after cloud client exited");
        System.out.println("Native cloud input isolation passed");
    }
}
