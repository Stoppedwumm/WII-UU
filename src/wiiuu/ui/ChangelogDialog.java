package wiiuu.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.function.IntConsumer;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JScrollBar;
import javax.swing.KeyStroke;
import wiiuu.core.Changelog;
import wiiuu.input.PadButton;

/** Release notes in a window: after an update, from Settings, and before installing one. */
public final class ChangelogDialog {
    private ChangelogDialog() {}

    /**
     * The release-notes window is separate from MenuView. GamePads still send input
     * through the menu's InputRouter, so route that input to the dialog while it is
     * visible. The bindings are pure actions and can be tested without a display.
     */
    static final class ControllerBindings {
        private final Runnable accept, cancel;
        private final IntConsumer scroll;
        ControllerBindings(Runnable accept, Runnable cancel, IntConsumer scroll) {
            this.accept = accept;
            this.cancel = cancel;
            this.scroll = scroll;
        }
        boolean button(PadButton b, boolean down) {
            if (!down) return true; // Consume releases too: no input leaks into the menu.
            switch (b) {
                case A, BUZZ_RED, PLUS -> accept.run();
                case B, BUZZ_YELLOW, HOME -> cancel.run();
                case L, ZL -> scroll.accept(-1);
                case R, ZR -> scroll.accept(1);
                default -> { }
            }
            return true;
        }
        boolean navigate(int dx, int dy) {
            if (dy != 0) scroll.accept(Integer.signum(dy));
            return true; // Horizontal input also stays out of the menu behind the dialog.
        }
    }

    private record Active(JDialog dialog, ControllerBindings bindings) {}
    private static Active active;

    /** Called on the Swing event thread by MenuView's controller router. */
    public static boolean controllerButton(PadButton b, boolean down) {
        Active current = active;
        return current != null && current.dialog().isShowing()
                && current.bindings().button(b, down);
    }

    /** Called for D-pad and stick navigation, including automatic repeats. */
    public static boolean controllerNavigate(int dx, int dy) {
        Active current = active;
        return current != null && current.dialog().isShowing()
                && current.bindings().navigate(dx, dy);
    }

    private static void registerController(JDialog dialog, JButton accept, JButton cancel) {
        JScrollPane scroll = (JScrollPane) ((JPanel) dialog.getContentPane())
                .getComponent(1);
        ControllerBindings controls = new ControllerBindings(
                accept::doClick,
                cancel == null ? dialog::dispose : cancel::doClick,
                direction -> {
                    JScrollBar bar = scroll.getVerticalScrollBar();
                    int step = Math.max(40, scroll.getViewport().getExtentSize().height / 3);
                    bar.setValue(bar.getValue() + direction * step);
                });
        Active attached = new Active(dialog, controls);
        active = attached;
        dialog.addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent e) {
                if (active == attached) active = null;
            }
        });
    }

    /** Shows the notes; Enter, Esc or Close closes. {@code modal}: wait until it's closed. */
    public static void show(Window owner, String title, String intro, List<Changelog.Entry> notes, boolean modal) {
        JDialog d = dialog(owner, title, intro, notes);
        d.setModal(modal);
        JButton close = new JButton("Close");
        close.addActionListener(e -> d.dispose());
        buttons(d, close);
        d.getRootPane().setDefaultButton(close);
        registerController(d, close, null);
        d.setVisible(true);
    }

    /** Shows the notes with {@code yes} / {@code no} buttons; true for yes (Enter), false for no (Esc). */
    public static boolean confirm(Window owner, String title, String intro, List<Changelog.Entry> notes, String yes, String no) {
        JDialog d = dialog(owner, title, intro, notes);
        d.setModal(true);
        boolean[] answer = {false};
        JButton ok = new JButton(yes), cancel = new JButton(no);
        ok.addActionListener(e -> {
            answer[0] = true;
            d.dispose();
        });
        cancel.addActionListener(e -> d.dispose());
        buttons(d, ok, cancel);
        d.getRootPane().setDefaultButton(ok);
        registerController(d, ok, cancel);
        d.setVisible(true);
        return answer[0];
    }

    private static JDialog dialog(Window owner, String title, String intro, List<Changelog.Entry> notes) {
        JDialog d = new JDialog(owner, title);
        d.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        JPanel p = new JPanel(new BorderLayout(0, 8));
        p.setBorder(BorderFactory.createEmptyBorder(12, 14, 8, 14));
        if (intro != null && !intro.isBlank()) p.add(new JLabel("<html>" + intro.replace("\n", "<br>") + "</html>"), BorderLayout.NORTH);
        JEditorPane text = new JEditorPane("text/html", notes.isEmpty()
                ? "<html><body style='font-family:sans-serif'><p>No release notes available.</p></body></html>"
                : Changelog.html(notes));
        text.setEditable(false);
        text.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);    // the system font and colours
        text.setCaretPosition(0);
        JScrollPane scroll = new JScrollPane(text);
        scroll.setPreferredSize(new Dimension(620, notes.size() > 2 ? 420 : 260));
        p.add(scroll, BorderLayout.CENTER);
        d.setContentPane(p);
        d.getRootPane().registerKeyboardAction(e -> d.dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        return d;
    }

    private static void buttons(JDialog d, JButton... buttons) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        for (JButton b : buttons) row.add(b);
        d.getContentPane().add(row, BorderLayout.SOUTH);
        d.pack();
        d.setLocationRelativeTo(d.getOwner());
        d.setAlwaysOnTop(d.getOwner() == null || d.getOwner().isAlwaysOnTop());
        buttons[0].requestFocusInWindow();
    }
}
