package wiiuu.ui;

import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.*;
import javax.swing.text.DefaultEditorKit;

/** Exercise real Swing models, theme changes, bindings and rendering on the event thread. */
public final class UiControlsTest {
    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            run();
            String original = UIManager.getLookAndFeel().getClass().getName();
            for (UIManager.LookAndFeelInfo laf : UIManager.getInstalledLookAndFeels()) {
                if (laf.getName().equals("Nimbus")) {
                    try { UIManager.setLookAndFeel(laf.getClassName()); run(); }
                    catch (ReflectiveOperationException | UnsupportedLookAndFeelException e) { throw new AssertionError(e); }
                }
            }
            try { UIManager.setLookAndFeel(original); }
            catch (ReflectiveOperationException | UnsupportedLookAndFeelException e) { throw new AssertionError(e); }
        });
        System.out.println("Built-in Swing control checks passed");
    }

    private static void run() {
        Ui ui = new Ui(UiTheme.light());
        JPanel page = ui.page(new BorderLayout());
        JPanel card = ui.card(new GridLayout(0, 1, 4, 4));
        page.add(card);
        AtomicInteger actions = new AtomicInteger();
        UiButton button = ui.primaryButton("Save", e -> actions.incrementAndGet());
        button.setMnemonic(KeyEvent.VK_S);
        button.doClick(0);
        check(actions.get() == 1, "button dispatches actions");
        button.setEnabled(false);
        button.doClick(0);
        check(actions.get() == 1, "disabled button does not dispatch");
        button.setEnabled(true);
        check(button.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke("SPACE")) != null, "Space press binding");
        check(button.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke("released SPACE")) != null, "Space release binding");
        check(button.getAccessibleContext().getAccessibleRole() == javax.accessibility.AccessibleRole.PUSH_BUTTON, "button accessibility");
        JRootPane root = new JRootPane();
        root.setContentPane(page);
        root.setDefaultButton(button);
        check(root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(KeyStroke.getKeyStroke("ENTER")) != null,
                "default button Enter binding");
        JToggleButton toggle = ui.toggle("Preview", false);
        toggle.doClick(0);
        check(toggle.isSelected(), "toggle model");
        JCheckBox checkBox = ui.checkBox("Enabled", false);
        checkBox.doClick(0);
        check(checkBox.isSelected(), "checkbox model");
        JTextField field = ui.textField("Original", 20);
        JLabel label = ui.label("Project name", field);
        check(label.getLabelFor() == field && "Project name".equals(field.getAccessibleContext().getAccessibleName()), "label association");
        field.setText("User input");
        field.select(0, 4);
        var document = field.getDocument();
        check(field.getActionMap().get(DefaultEditorKit.copyAction) != null
                && field.getActionMap().get(DefaultEditorKit.pasteAction) != null, "native clipboard actions");
        JTextArea area = ui.textArea("Notes", 2, 20);
        JComboBox<String> combo = ui.comboBox(new String[]{"One", "Two"});
        combo.setSelectedIndex(1);
        var comboModel = combo.getModel();
        JProgressBar progress = ui.progressBar(0, 100);
        progress.setValue(67);
        card.add(label); card.add(field); card.add(ui.scrollPane(area)); card.add(combo);
        card.add(checkBox); card.add(toggle); card.add(progress); card.add(button);
        int listeners = button.getActionListeners().length;
        Ui other = new Ui(UiTheme.light());
        JButton independent = other.button("Other window");
        Color independentForeground = independent.getForeground();
        ui.setTheme(UiTheme.dark(), page);
        check(field.getDocument() == document && field.getText().equals("User input") && field.getSelectedText().equals("User"), "retheme preserves editing");
        check(combo.getModel() == comboModel && combo.getSelectedIndex() == 1, "retheme preserves dropdown");
        check(toggle.isSelected() && checkBox.isSelected() && progress.getValue() == 67, "retheme preserves values");
        check(button.getActionListeners().length == listeners && root.getDefaultButton() == button
                && button.getMnemonic() == KeyEvent.VK_S, "retheme preserves actions and defaults");
        check(field.getForeground().equals(ui.theme().colour("text")) && label.getForeground().equals(ui.theme().colour("text")), "descendant theme applied");
        check(independent.getForeground().equals(independentForeground), "independent app themes");
        button.updateUI();
        button.doClick(0);
        check(actions.get() == 2, "look-and-feel update preserves action");
        DefaultListCellRenderer customRenderer = new DefaultListCellRenderer();
        combo.setRenderer(customRenderer);
        ui.setTheme(UiTheme.light(), page);
        check(combo.getRenderer() == customRenderer, "custom renderer retained");
        JButton late = ui.button("Added later");
        card.add(late);
        ui.setTheme(UiTheme.dark(), page);
        check(late.getForeground().equals(ui.theme().colour("text")), "late controls rethemed");
        long normal = renderButton(button, false, false, true);
        long hover = renderButton(button, true, false, true);
        long pressed = renderButton(button, false, true, true);
        long disabled = renderButton(button, false, false, false);
        check(normal != hover && normal != pressed && normal != disabled, "button state painting differs");
        check(!button.isEnabled(), "disabled state retained while rendering");
        ui.setTheme(UiTheme.light(), page);
        check(!button.isEnabled(), "theme changes preserve disabled state");
        for (UiTheme theme : new UiTheme[]{UiTheme.light(), UiTheme.dark(), customTheme()}) {
            ui.setTheme(theme, page);
            page.setSize(500, 650);
            layout(page);
            BufferedImage image = new BufferedImage(500, 650, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = image.createGraphics();
            page.paint(g);
            g.dispose();
            check(image.getRGB(2, 2) != 0, "control tree paints");
        }
        check(ui.column(8, ui.label("A"), ui.button("B")).getComponentCount() == 3, "column spacing helper");
        check(ui.row(ui.label("A"), ui.button("B")).getComponentCount() == 2, "row helper");
    }

    private static UiTheme customTheme() {
        try {
            return UiTheme.from(MenuPalette.evaluate("base dark\naccent = #ffcc00\nfont \"SansSerif\"", "custom", true));
        } catch (ThemeScript.Error e) { throw new AssertionError(e); }
    }

    private static long renderButton(UiButton button, boolean hover, boolean pressed, boolean enabled) {
        button.setEnabled(enabled);
        button.getModel().setRollover(hover);
        button.getModel().setArmed(pressed);
        button.getModel().setPressed(pressed);
        button.setSize(180, 48);
        BufferedImage image = new BufferedImage(180, 48, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        button.paint(g);
        g.dispose();
        long hash = 1;
        for (int y = 0; y < 48; y++) for (int x = 0; x < 180; x++) hash = 31 * hash + image.getRGB(x, y);
        // Disarm before releasing so the state-rendering helper does not synthesize a click.
        button.getModel().setArmed(false);
        button.getModel().setPressed(false);
        return hash;
    }

    private static void layout(Container root) {
        root.doLayout();
        for (Component child : root.getComponents()) if (child instanceof Container container) layout(container);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
