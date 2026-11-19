package wiiuu.ui;

import java.awt.*;
import java.awt.event.ActionListener;
import java.awt.image.BufferedImage;
import java.util.Objects;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.border.AbstractBorder;

/**
 * Small Swing app toolkit. Create and use on the Swing event thread. Factory controls retain
 * Swing models/listeners, layout managers, clipboard, keyboard navigation and accessibility.
 * A Ui instance owns a theme, not a global look-and-feel; retheme each host root explicitly.
 */
public final class Ui {
    private static final String THEMER = "wiiuu.controls.applyTheme";
    private UiTheme theme;

    public Ui() { this(UiTheme.light()); }
    public Ui(UiTheme theme) { this.theme = Objects.requireNonNull(theme); }
    public UiTheme theme() { return theme; }

    /** Change future controls and restyle existing toolkit controls under root, preserving their state. */
    public void setTheme(UiTheme theme, Component root) {
        this.theme = Objects.requireNonNull(theme);
        applyTheme(root);
    }

    /** Apply this instance's current theme to toolkit controls in a tree, including late additions. */
    @SuppressWarnings("unchecked")
    public void applyTheme(Component root) {
        Objects.requireNonNull(root);
        if (root instanceof UiButton button) button.setTheme(theme);
        if (root instanceof JComponent c && c.getClientProperty(THEMER) instanceof Consumer<?> action)
            ((Consumer<UiTheme>) action).accept(theme);
        if (root instanceof Container container) for (Component child : container.getComponents()) applyTheme(child);
        root.invalidate();
        root.repaint();
        if (root instanceof JComponent c) c.revalidate();
    }

    private <T extends JComponent> T bind(T control, Consumer<UiTheme> apply) {
        control.putClientProperty(THEMER, apply);
        apply.accept(theme);
        return control;
    }

    public UiButton button(String text) { return new UiButton(text, theme, false); }
    public UiButton button(String text, ActionListener action) { UiButton b = button(text); b.addActionListener(action); return b; }
    public UiButton primaryButton(String text) { return new UiButton(text, theme, true); }
    public UiButton primaryButton(String text, ActionListener action) { UiButton b = primaryButton(text); b.addActionListener(action); return b; }

    /** Selected and unselected states use the same rounded painter as buttons. */
    public JToggleButton toggle(String text, boolean selected) {
        JToggleButton b = new JToggleButton(text, selected) {
            @Override public void updateUI() { setUI(new RoundedButtonUI()); }
        };
        return bind(b, t -> UiButton.configure(b, t));
    }

    public JCheckBox checkBox(String text, boolean selected) {
        JCheckBox box = new JCheckBox(text, selected);
        box.setOpaque(false);
        box.setIcon(new CheckIcon());
        box.setIconTextGap(8);
        return bind(box, t -> {
            box.putClientProperty(UiButton.THEME, t);
            box.setFont(t.font(Font.PLAIN, 14));
            box.setForeground(t.colour("text"));
        });
    }

    public JLabel label(String text) { return label(text, false, false); }
    /** Label a form control and give it an accessible name when it has none. */
    public JLabel label(String text, JComponent control) {
        JLabel label = label(text);
        label.setLabelFor(control);
        if (control.getAccessibleContext().getAccessibleName() == null)
            control.getAccessibleContext().setAccessibleName(text);
        return label;
    }
    public JLabel mutedLabel(String text) { return label(text, false, true); }
    public JLabel heading(String text) { return label(text, true, false); }
    private JLabel label(String text, boolean heading, boolean muted) {
        JLabel label = new JLabel(text);
        return bind(label, t -> {
            label.setFont(t.font(heading ? Font.BOLD : Font.PLAIN, heading ? 26 : 14));
            label.setForeground(t.colour(muted ? "text-dim" : "text"));
        });
    }

    public JTextField textField(String text, int columns) {
        JTextField field = new JTextField(text, columns) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D copy = (Graphics2D) g.create();
                try {
                    copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    copy.setColor(getBackground());
                    copy.fillRoundRect(1, 1, getWidth() - 2, getHeight() - 2, 14, 14);
                } finally { copy.dispose(); }
                super.paintComponent(g);
            }
        };
        field.setOpaque(false);
        return bind(field, t -> {
            textStyle(field, t);
            field.setBorder(new FieldBorder(t));
        });
    }

    public JTextArea textArea(String text, int rows, int columns) {
        JTextArea area = new JTextArea(text, rows, columns);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        return bind(area, t -> {
            textStyle(area, t);
            area.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        });
    }

    private static void textStyle(javax.swing.text.JTextComponent c, UiTheme t) {
        c.setFont(t.font(Font.PLAIN, 14));
        c.setForeground(t.colour("text"));
        c.setBackground(t.colour("card"));
        c.setCaretColor(t.colour("accent"));
        c.setSelectionColor(t.colour("accent"));
        c.setSelectedTextColor(t.onAccent());
        c.setDisabledTextColor(t.colour("text-dim"));
    }

    /** Uses the Swing combo-box model and popup behavior; items may have a host-supplied renderer. */
    public <T> JComboBox<T> comboBox(T[] items) {
        JComboBox<T> combo = new JComboBox<>(items);
        return bind(combo, t -> {
            combo.setFont(t.font(Font.PLAIN, 14));
            combo.setForeground(t.colour("text"));
            combo.setBackground(t.colour("card"));
            if (combo.getRenderer() == null || combo.getRenderer() instanceof javax.swing.plaf.UIResource)
                combo.setRenderer(new ThemeRenderer(t));
            else if (combo.getRenderer() instanceof ThemeRenderer renderer) renderer.theme = t;
        });
    }

    public JProgressBar progressBar(int minimum, int maximum) {
        JProgressBar bar = new JProgressBar(minimum, maximum);
        bar.setStringPainted(true);
        bar.setUI(new javax.swing.plaf.basic.BasicProgressBarUI() {
            @Override protected Color getSelectionForeground() {
                return ((UiTheme) bar.getClientProperty(UiButton.THEME)).onAccent();
            }
            @Override protected Color getSelectionBackground() {
                return ((UiTheme) bar.getClientProperty(UiButton.THEME)).colour("text");
            }
        });
        return bind(bar, t -> {
            bar.putClientProperty(UiButton.THEME, t);
            bar.setBorder(BorderFactory.createLineBorder(t.colour("divider")));
            bar.setFont(t.font(Font.PLAIN, 13));
            bar.setForeground(t.colour("accent"));
            bar.setBackground(t.colour("button"));
        });
    }

    /** Theme-coloured plain panel, useful with any Swing layout manager. */
    public JPanel panel(LayoutManager layout) {
        JPanel panel = new JPanel(layout);
        return bind(panel, t -> panel.setBackground(t.colour("card")));
    }

    /** Rounded content container with 16px padding; accepts any Swing layout. */
    public JPanel card(LayoutManager layout) {
        JPanel card = new JPanel(layout) {
            @Override protected void paintComponent(Graphics graphics) {
                Graphics2D g = (Graphics2D) graphics.create();
                try {
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g.setColor(getBackground());
                    g.fillRoundRect(0, 0, getWidth(), getHeight(), 22, 22);
                } finally { g.dispose(); }
                super.paintComponent(graphics);
            }
        };
        card.setOpaque(false);
        card.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        return bind(card, t -> card.setBackground(t.colour("card")));
    }

    /** Gradient page container. The background cache is invalidated on resize/theme changes. */
    public JPanel page(LayoutManager layout) {
        class Page extends JPanel {
            private UiTheme palette;
            private BufferedImage image;
            Page() { super(layout); }
            void theme(UiTheme t) { palette = t; image = null; }
            @Override protected void paintComponent(Graphics graphics) {
                super.paintComponent(graphics);
                if (getWidth() <= 0 || getHeight() <= 0) return;
                if (image == null || image.getWidth() != getWidth() || image.getHeight() != getHeight())
                    image = MenuPainter.background(getWidth(), getHeight(), palette.colours());
                graphics.drawImage(image, 0, 0, null);
            }
        }
        Page page = new Page();
        return bind(page, page::theme);
    }

    public JScrollPane scrollPane(Component view) {
        JScrollPane scroll = new JScrollPane(view);
        return bind(scroll, t -> {
            scroll.setBorder(BorderFactory.createLineBorder(t.colour("divider")));
            scroll.getViewport().setBackground(t.colour("card"));
        });
    }

    public JPanel row(Component... controls) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEADING, 8, 0));
        row.setOpaque(false);
        for (Component control : controls) row.add(control);
        return row;
    }

    /** Vertical stack with a fixed gap, aligned to the leading edge. */
    public JPanel column(int gap, Component... controls) {
        if (gap < 0) throw new IllegalArgumentException("Gap must be nonnegative");
        JPanel column = new JPanel();
        column.setOpaque(false);
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        for (int i = 0; i < controls.length; i++) {
            if (i > 0) column.add(Box.createVerticalStrut(gap));
            if (controls[i] instanceof JComponent c) c.setAlignmentX(Component.LEFT_ALIGNMENT);
            column.add(controls[i]);
        }
        return column;
    }

    private static final class FieldBorder extends AbstractBorder {
        private final UiTheme theme;
        FieldBorder(UiTheme theme) { this.theme = theme; }
        @Override public Insets getBorderInsets(Component c, Insets insets) {
            insets.set(11, 12, 11, 12); return insets;
        }
        @Override public Insets getBorderInsets(Component c) { return new Insets(11, 12, 11, 12); }
        @Override public void paintBorder(Component c, Graphics graphics, int x, int y, int w, int h) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setStroke(new BasicStroke(c.hasFocus() ? 2 : 1));
                g.setColor(theme.colour(c.hasFocus() ? "accent" : "divider"));
                g.drawRoundRect(x + 1, y + 1, w - 3, h - 3, 14, 14);
            } finally { g.dispose(); }
        }
    }

    private static final class CheckIcon implements Icon {
        public int getIconWidth() { return 20; }
        public int getIconHeight() { return 20; }
        public void paintIcon(Component c, Graphics graphics, int x, int y) {
            AbstractButton b = (AbstractButton) c;
            UiTheme t = (UiTheme) b.getClientProperty(UiButton.THEME);
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(t.colour(!b.isEnabled() ? "button" : b.isSelected() ? "accent" : "card"));
                g.fillRoundRect(x + 1, y + 1, 17, 17, 5, 5);
                g.setColor(t.colour(b.hasFocus() ? "accent" : "divider"));
                g.drawRoundRect(x + 1, y + 1, 17, 17, 5, 5);
                if (b.isSelected()) {
                    g.setColor(b.isEnabled() ? t.onAccent() : t.colour("text-dim"));
                    g.setStroke(new BasicStroke(2, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.drawLine(x + 5, y + 10, x + 8, y + 13);
                    g.drawLine(x + 8, y + 13, x + 14, y + 6);
                }
            } finally { g.dispose(); }
        }
    }

    private static final class ThemeRenderer extends DefaultListCellRenderer {
        private UiTheme theme;
        ThemeRenderer(UiTheme theme) { this.theme = theme; }
        @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
            super.getListCellRendererComponent(list, value, index, selected, focus);
            setBackground(theme.colour(selected ? "accent" : "card"));
            setForeground(selected ? theme.onAccent() : theme.colour("text"));
            setFont(theme.font(Font.PLAIN, 14));
            setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
            return this;
        }
    }
}
