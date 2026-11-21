package wiiuu.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

import wiiuu.core.Config;
import wiiuu.input.InputRouter;

/**
 * Full-window, controller-friendly login gate. No user's library, plugins, credentials or home
 * menu are initialized until a profile is explicitly selected. This screen is NOT a modal dialog.
 */
public final class UserSelectScreen extends JPanel implements InputRouter.MenuActions {
    private static final Color WHITE = new Color(245, 247, 253);
    private static final Color MUTED = new Color(163, 179, 204);
    private static final Color ACCENT = new Color(91, 204, 222);
    private static final int CARDS_PER_PAGE = 4;

    private final List<Config.Users.Profile> users;
    private final Consumer<String> onSelect;
    private final Runnable onQuit;
    private final List<Rectangle> cardBounds = new ArrayList<>();
    private Rectangle quitBounds = new Rectangle();
    private int selected;
    private boolean quitFocused;
    private boolean completed;

    /** Create an offscreen-testable screen; selection is committed only by its callback. */
    public UserSelectScreen(List<Config.Users.Profile> users, Consumer<String> onSelect, Runnable onQuit) {
        if (users.isEmpty()) throw new IllegalArgumentException("At least one profile is required");
        this.users = List.copyOf(users);
        this.onSelect = onSelect;
        this.onQuit = onQuit;
        setFocusable(true);
        setPreferredSize(new Dimension(1280, 720));

        bind("UP", () -> navigate(0, -1));
        bind("DOWN", () -> navigate(0, 1));
        bind("LEFT", () -> navigate(-1, 0));
        bind("RIGHT", () -> navigate(1, 0));
        bind("ENTER", this::activate);
        bind("SPACE", this::activate);
        bind("ESCAPE", this::back);

        addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                if (completed) return;
                if (quitBounds.contains(event.getPoint())) {
                    quitFocused = true;
                    activate();
                    return;
                }
                int start = (selected / CARDS_PER_PAGE) * CARDS_PER_PAGE;
                for (int i = 0; i < cardBounds.size(); i++) {
                    if (cardBounds.get(i).contains(event.getPoint())) {
                        selected = start + i;
                        quitFocused = false;
                        activate();
                        return;
                    }
                }
            }
        });
    }

    /**
     * Run the picker before constructing Main. The caller waits outside the Swing event thread.
     * The ordinary menu and first-boot intro cannot appear behind this screen.
     */
    public static String choose(Path home, InputRouter controller, boolean fullscreen)
            throws InterruptedException, InvocationTargetException {
        if (SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("The startup picker must run outside the Swing event thread");
        }
        CountDownLatch resolved = new CountDownLatch(1);
        AtomicReference<String> selectedId = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            JFrame window = new JFrame("WII-UU · Choose a user");
            Runnable quit = () -> {
                window.dispose();
                resolved.countDown();
            };
            UserSelectScreen picker = new UserSelectScreen(Config.Users.list(home), id -> {
                selectedId.set(id);
                window.dispose();
                resolved.countDown();
            }, quit);
            window.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
            window.addWindowListener(new WindowAdapter() {
                @Override public void windowClosing(WindowEvent event) { picker.back(); }
                @Override public void windowActivated(WindowEvent event) { picker.requestFocusInWindow(); }
            });
            window.setContentPane(picker);
            window.setMinimumSize(new Dimension(960, 540));
            window.setUndecorated(fullscreen);
            if (fullscreen) {
                if (window.getGraphicsConfiguration() != null)
                    window.setBounds(window.getGraphicsConfiguration().getBounds());
                window.setExtendedState(JFrame.MAXIMIZED_BOTH);
            } else {
                window.pack();
                window.setLocationRelativeTo(null);
            }
            controller.setMenu(picker);
            window.setVisible(true);
            picker.requestFocusInWindow();
        });
        resolved.await();
        controller.setMenu(null);
        return selectedId.get();
    }

    private void bind(String key, Runnable action) {
        String name = "select." + key;
        getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(key), name);
        getActionMap().put(name, new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) { action.run(); }
        });
    }

    @Override public void navigate(int dx, int dy) {
        if (completed) return;
        if (dy > 0) quitFocused = true;
        else if (dy < 0) quitFocused = false;
        if (dx != 0) {
            if (quitFocused) quitFocused = false;
            else selected = Math.floorMod(selected + Integer.signum(dx), users.size());
        }
        repaint();
    }

    @Override public void activate() {
        if (completed) return;
        completed = true;
        if (quitFocused) onQuit.run();
        else onSelect.accept(users.get(selected).id());
    }

    @Override public void back() {
        if (completed) return;
        completed = true;
        onQuit.run();
    }

    @Override public void page(int delta) { navigate(delta, 0); }
    @Override public void toggleGamepadInfo() { }
    @Override public void refresh() { }
    @Override public boolean pressed(int player, wiiuu.input.PadButton button, boolean down) {
        if (down && button == wiiuu.input.PadButton.HOME) {
            back();
            return true;
        }
        return false;
    }

    int selectedIndex() { return selected; }
    boolean quitFocused() { return quitFocused; }

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            int w = getWidth(), h = getHeight();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setPaint(new GradientPaint(0, 0, new Color(24, 38, 64), w, h, new Color(9, 17, 30)));
            g.fillRect(0, 0, w, h);

            g.setColor(ACCENT);
            g.fillRoundRect(w / 2 - 94, Math.max(30, h / 7 - 26), 188, 5, 5, 5);
            g.setFont(new Font("SansSerif", Font.BOLD, 44));
            center(g, "WII-UU", w / 2, h / 7 + 35, WHITE);
            g.setFont(new Font("SansSerif", Font.BOLD, 30));
            center(g, "Who's playing?", w / 2, h / 7 + 91, WHITE);
            g.setFont(new Font("SansSerif", Font.PLAIN, 16));
            center(g, "Select a user to start WII-UU", w / 2, h / 7 + 120, MUTED);

            int count = Math.min(CARDS_PER_PAGE, users.size());
            int spacing = 18;
            int cardWidth = Math.max(126, Math.min(228, (w - 112 - spacing * (count - 1)) / count));
            int cardHeight = Math.max(160, Math.min(228, h / 3));
            int startX = (w - count * cardWidth - (count - 1) * spacing) / 2;
            int top = Math.max(h / 2 - 44, h / 7 + 145);
            int start = (selected / CARDS_PER_PAGE) * CARDS_PER_PAGE;
            cardBounds.clear();
            for (int i = 0; i < count && start + i < users.size(); i++) {
                Config.Users.Profile user = users.get(start + i);
                int x = startX + i * (cardWidth + spacing);
                Rectangle tile = new Rectangle(x, top, cardWidth, cardHeight);
                cardBounds.add(tile);
                boolean active = !quitFocused && start + i == selected;
                g.setColor(active ? new Color(42, 82, 112) : new Color(32, 44, 64));
                g.fillRoundRect(x, top, cardWidth, cardHeight, 24, 24);
                g.setStroke(new BasicStroke(active ? 3f : 1.5f));
                g.setColor(active ? ACCENT : new Color(72, 91, 116));
                g.drawRoundRect(x, top, cardWidth, cardHeight, 24, 24);

                int centerX = x + cardWidth / 2, iconY = top + Math.max(51, cardHeight / 3);
                int avatarSize = Math.max(62, Math.min(86, cardHeight / 2 - 6));
                g.setColor(new Color(74 + Math.floorMod(user.id().hashCode(), 35),
                        110 + Math.floorMod(user.id().hashCode() >>> 8, 30), 161));
                g.fillOval(centerX - avatarSize / 2, iconY - avatarSize / 2, avatarSize, avatarSize);
                String initial = user.name().substring(0, 1).toUpperCase(java.util.Locale.ROOT);
                g.setFont(new Font("SansSerif", Font.BOLD, 38));
                center(g, initial, centerX, iconY + 13, WHITE);
                g.setFont(new Font("SansSerif", Font.BOLD, 19));
                center(g, shorten(g, user.name(), cardWidth - 20), centerX, top + cardHeight - 41, WHITE);
                if (active) {
                    g.setFont(new Font("SansSerif", Font.PLAIN, 13));
                    center(g, "Press A to start", centerX, top + cardHeight - 17, ACCENT);
                }
            }

            if (users.size() > CARDS_PER_PAGE) {
                g.setFont(new Font("SansSerif", Font.PLAIN, 14));
                center(g, (selected + 1) + " / " + users.size() + "  ·  Left / Right for more users",
                        w / 2, top + cardHeight + 25, MUTED);
            }
            int quitWidth = 172, quitHeight = 45, quitY = h - 82;
            quitBounds = new Rectangle(w / 2 - quitWidth / 2, quitY, quitWidth, quitHeight);
            g.setColor(quitFocused ? new Color(55, 81, 111) : new Color(27, 39, 57));
            g.fillRoundRect(quitBounds.x, quitY, quitWidth, quitHeight, 18, 18);
            g.setColor(quitFocused ? ACCENT : new Color(89, 108, 132));
            g.setStroke(new BasicStroke(quitFocused ? 3 : 1));
            g.drawRoundRect(quitBounds.x, quitY, quitWidth, quitHeight, 18, 18);
            g.setFont(new Font("SansSerif", Font.BOLD, 16));
            center(g, "Quit", w / 2, quitY + 29, WHITE);
            g.setFont(new Font("SansSerif", Font.PLAIN, 14));
            center(g, "D-pad / Stick  ·  A Select  ·  B Back  ·  Mouse & keyboard",
                    w / 2, h - 17, MUTED);
        } finally {
            g.dispose();
        }
    }

    private static void center(Graphics2D g, String text, int x, int baseline, Color color) {
        g.setColor(color);
        g.drawString(text, x - g.getFontMetrics().stringWidth(text) / 2, baseline);
    }

    private static String shorten(Graphics2D g, String text, int width) {
        if (g.getFontMetrics().stringWidth(text) <= width) return text;
        while (!text.isEmpty() && g.getFontMetrics().stringWidth(text + "…") > width)
            text = text.substring(0, text.length() - 1);
        return text + "…";
    }
}
