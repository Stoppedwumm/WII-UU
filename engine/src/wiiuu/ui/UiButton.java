package wiiuu.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.util.Objects;
import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.plaf.basic.BasicButtonUI;
import javax.swing.plaf.basic.BasicGraphicsUtils;

/** Rounded Swing button with native action, mnemonic, focus and default-button semantics. */
public class UiButton extends JButton {
    static final String THEME = "wiiuu.controls.theme";
    static final String PRIMARY = "wiiuu.controls.primary";

    public UiButton(String text) { this(text, UiTheme.light(), false); }
    public UiButton(String text, UiTheme theme, boolean primary) {
        super(text);
        putClientProperty(PRIMARY, primary);
        setTheme(theme);
    }

    /** Preserve the library painter across Swing look-and-feel updates. */
    @Override public void updateUI() { setUI(new RoundedButtonUI()); }

    public final void setTheme(UiTheme theme) {
        configure(this, Objects.requireNonNull(theme));
    }

    static void configure(AbstractButton button, UiTheme theme) {
        button.putClientProperty(THEME, theme);
        button.setOpaque(false);
        button.setContentAreaFilled(false);
        button.setBorderPainted(false);
        button.setFocusPainted(true);
        button.setRolloverEnabled(true);
        button.setBorder(BorderFactory.createEmptyBorder(11, 20, 11, 20));
        button.setFont(theme.font(java.awt.Font.BOLD, 14));
        button.setForeground(Boolean.TRUE.equals(button.getClientProperty(PRIMARY)) ? theme.onAccent() : theme.colour("text"));
        button.setBackground(theme.colour("button"));
        button.revalidate();
        button.repaint();
    }
}

/** BasicButtonUI retains Swing's keyboard/action installation and icon/text layout. */
final class RoundedButtonUI extends BasicButtonUI {
    @Override public void paint(Graphics graphics, JComponent component) {
        AbstractButton button = (AbstractButton) component;
        UiTheme theme = (UiTheme) button.getClientProperty(UiButton.THEME);
        if (theme == null) { super.paint(graphics, component); return; }
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            boolean accent = Boolean.TRUE.equals(button.getClientProperty(UiButton.PRIMARY)) || button.isSelected();
            Color fill = theme.colour(accent ? "accent" : "button");
            if (!button.isEnabled()) fill = MilkTheme.mix(fill, theme.colour("card"), .6f);
            else if (button.getModel().isPressed() && button.getModel().isArmed())
                fill = MilkTheme.mix(fill, theme.colour("text"), .15f);
            else if (button.getModel().isRollover()) fill = MilkTheme.mix(fill, theme.colour("text"), .07f);
            int w = button.getWidth(), h = button.getHeight();
            g.setColor(fill);
            g.fillRoundRect(2, 2, Math.max(0, w - 4), Math.max(0, h - 4), 18, 18);
            g.setColor(theme.colour("divider"));
            g.drawRoundRect(2, 2, Math.max(0, w - 5), Math.max(0, h - 5), 18, 18);
            if (button.isFocusPainted() && (button.hasFocus() || button instanceof JButton b && b.isDefaultButton())) {
                g.setStroke(new BasicStroke(2));
                g.setColor(theme.colour("accent"));
                g.drawRoundRect(1, 1, Math.max(0, w - 3), Math.max(0, h - 3), 20, 20);
            }
            super.paint(g, component);
        } finally { g.dispose(); }
    }

    @Override protected void paintText(Graphics g, AbstractButton button, Rectangle bounds, String text) {
        UiTheme theme = (UiTheme) button.getClientProperty(UiButton.THEME);
        if (theme == null) { super.paintText(g, button, bounds, text); return; }
        boolean accent = Boolean.TRUE.equals(button.getClientProperty(UiButton.PRIMARY)) || button.isSelected();
        g.setColor(!button.isEnabled() ? theme.colour("text-dim") : accent ? theme.onAccent() : button.getForeground());
        BasicGraphicsUtils.drawStringUnderlineCharAt(g, text, button.getDisplayedMnemonicIndex(),
                bounds.x + getTextShiftOffset(), bounds.y + g.getFontMetrics().getAscent() + getTextShiftOffset());
    }
}
