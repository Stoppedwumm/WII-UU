package wiiuu.ui;

import java.util.concurrent.atomic.AtomicInteger;
import wiiuu.input.PadButton;

/** Controller focus must remain with the boot changelog, not the hidden menu. */
public final class ChangelogControllerTest {
    public static void main(String[] args) {
        AtomicInteger accept = new AtomicInteger(), cancel = new AtomicInteger();
        AtomicInteger scroll = new AtomicInteger();
        ChangelogDialog.ControllerBindings controls = new ChangelogDialog.ControllerBindings(
                accept::incrementAndGet, cancel::incrementAndGet, scroll::addAndGet);

        check(controls.button(PadButton.A, true), "A consumed");
        check(accept.get() == 1, "A closes startup changelog");
        check(controls.button(PadButton.A, false), "A release consumed");
        check(accept.get() == 1, "A release must not repeat click");

        check(controls.button(PadButton.B, true), "B consumed");
        check(cancel.get() == 1, "B closes startup changelog");
        check(controls.button(PadButton.B, false), "B release consumed");
        check(cancel.get() == 1, "B release must not repeat click");

        controls.button(PadButton.PLUS, true);
        controls.button(PadButton.HOME, true);
        check(accept.get() == 2 && cancel.get() == 2, "Plus and Home shortcuts");
        controls.button(PadButton.BUZZ_RED, true);
        controls.button(PadButton.BUZZ_YELLOW, true);
        check(accept.get() == 3 && cancel.get() == 3, "Buzzer buttons");

        check(controls.navigate(0, 1), "stick down consumed");
        check(scroll.get() == 1, "D-pad or stick down scrolls changelog");
        check(controls.navigate(0, -1), "stick up consumed");
        check(scroll.get() == 0, "D-pad or stick up scrolls back");
        check(controls.navigate(1, 0), "horizontal navigation is consumed");
        check(scroll.get() == 0, "horizontal does not scroll");
        controls.button(PadButton.R, true);
        controls.button(PadButton.L, true);
        check(scroll.get() == 0, "shoulder page scroll directions");

        check(controls.button(PadButton.X, true), "other controller buttons consumed");
        check(controls.button(PadButton.MINUS, true), "refresh button consumed");
        check(accept.get() == 3 && cancel.get() == 3, "other buttons do not close dialog");
        System.out.println("ChangelogControllerTest passed");
    }

    private static void check(boolean ok, String reason) {
        if (!ok) throw new AssertionError(reason);
    }
}
