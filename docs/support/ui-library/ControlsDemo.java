import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import javax.swing.*;
import wiiuu.ui.Ui;
import wiiuu.ui.UiTheme;

/** Complete Swing form using only built-in WII-UU controls and ordinary Swing listeners/layouts. */
public final class ControlsDemo {
    private final Ui ui;
    final JPanel page;
    final JButton save;

    public ControlsDemo(boolean dark) {
        ui = new Ui(dark ? UiTheme.dark() : UiTheme.light());
        page = ui.page(new BorderLayout(0, 20));
        page.setBorder(BorderFactory.createEmptyBorder(30, 36, 30, 36));
        JLabel status = ui.mutedLabel("Ready to save your changes.");
        JToggleButton appearance = ui.toggle("Dark appearance", dark);
        appearance.addActionListener(e -> ui.setTheme(appearance.isSelected() ? UiTheme.dark() : UiTheme.light(), page));
        JPanel header = ui.row(ui.heading("Project settings"));
        header.add(appearance);
        page.add(header, BorderLayout.NORTH);

        JPanel card = ui.card(new GridBagLayout());
        JTextField name = ui.textField("My first project", 28);
        JTextArea notes = ui.textArea("A desktop app built with shared WII-UU controls.", 3, 28);
        JComboBox<String> quality = ui.comboBox(new String[]{"Balanced", "High quality", "Fast preview"});
        JCheckBox autosave = ui.checkBox("Save changes automatically", true);
        JProgressBar progress = ui.progressBar(0, 100);
        progress.setValue(65);
        progress.setString("65% complete");
        JButton disabled = ui.button("Export unavailable");
        disabled.setEnabled(false);
        addRow(card, 0, "Project name", name);
        addRow(card, 1, "Preview quality", quality);
        addRow(card, 2, "Notes", ui.scrollPane(notes));
        addRow(card, 3, "Preferences", autosave);
        addRow(card, 4, "Preparation", progress);
        addRow(card, 5, "Export", disabled);
        page.add(card, BorderLayout.CENTER);
        save = ui.primaryButton("Save changes", e -> {
            progress.setValue(100);
            progress.setString("Saved");
            status.setText("Saved settings for " + name.getText() + ".");
        });
        JButton reset = ui.button("Reset", e -> {
            name.setText("My first project");
            quality.setSelectedIndex(0);
            autosave.setSelected(true);
            status.setText("Restored defaults.");
        });
        JPanel footer = ui.panel(new BorderLayout());
        footer.setOpaque(false);
        footer.add(status, BorderLayout.WEST);
        footer.add(ui.row(reset, save), BorderLayout.EAST);
        page.add(footer, BorderLayout.SOUTH);
    }

    private void addRow(JPanel form, int row, String caption, JComponent field) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0; c.gridy = row; c.anchor = GridBagConstraints.NORTHWEST;
        c.insets = new Insets(10, 8, 10, 22);
        form.add(ui.label(caption, field), c);
        c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(4, 0, 10, 8);
        form.add(field, c);
    }

    private static void layout(Container root) {
        root.doLayout();
        for (Component child : root.getComponents()) if (child instanceof Container container) layout(container);
    }

    public static void main(String[] args) throws Exception {
        boolean dark = java.util.Arrays.asList(args).contains("--dark");
        SwingUtilities.invokeAndWait(() -> {
            ControlsDemo demo = new ControlsDemo(dark);
            if (args.length >= 2 && args[0].equals("--snapshot")) {
                demo.page.setSize(900, 660);
                layout(demo.page);
                BufferedImage image = new BufferedImage(900, 660, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = image.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                demo.page.paint(g);
                g.dispose();
                try { ImageIO.write(image, "png", Path.of(args[1]).toFile()); }
                catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
                return;
            }
            JFrame frame = new JFrame("Project settings");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setContentPane(demo.page);
            frame.getRootPane().setDefaultButton(demo.save);
            frame.setSize(900, 660);
            frame.setMinimumSize(new Dimension(760, 620));
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        });
    }
}
