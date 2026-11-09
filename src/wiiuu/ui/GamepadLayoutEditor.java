package wiiuu.ui;

import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.event.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import wiiuu.core.Config;
import wiiuu.input.PadButton;
import wiiuu.net.GamepadLayouts;
import wiiuu.net.GamepadLayouts.Layout;
import wiiuu.net.GamepadLayouts.Widget;

/** The desktop editor for the same layouts the phone GamePad can edit and play. */
final class GamepadLayoutEditor extends JPanel {
    private static final String[] TYPES = {"button", "slider", "stick", "dpad"};
    private static final String[] TITLES = {"Button", "Gesture slider", "Joystick", "D-pad"};
    private final Config config;
    private final JComboBox<Layout> profiles = new JComboBox<>();
    private final JTextField name = new JTextField(16);
    private final JLabel message = new JLabel("Drag a control to move it. Drag its lower-right corner to resize it.");
    private final JPanel properties = new JPanel(new GridLayout(0, 2, 6, 5));
    private final Canvas canvas = new Canvas();
    private final List<Widget> widgets = new ArrayList<>();
    private String id;
    private int selected = -1;

    GamepadLayoutEditor(Config config, Runnable close) {
        super(new BorderLayout(12, 10));
        this.config = config;
        setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        JPanel header = new JPanel(new GridLayout(0, 1, 0, 5));
        JPanel choices = new JPanel(new FlowLayout(FlowLayout.LEFT));
        choices.add(new JLabel("Layout"));
        choices.add(profiles);
        choices.add(new JLabel("Name"));
        choices.add(name);
        choices.add(button("Save", this::save));
        choices.add(button("Delete", this::delete));
        choices.add(button("Close", close));
        header.add(choices);
        JPanel palette = new JPanel(new FlowLayout(FlowLayout.LEFT));
        for (int i = 0; i < TYPES.length; i++) palette.add(paletteButton(TYPES[i], TITLES[i]));
        header.add(palette);
        add(header, BorderLayout.NORTH);
        add(canvas, BorderLayout.CENTER);
        var scroll = new JScrollPane(properties);
        scroll.setPreferredSize(new Dimension(260, 260));
        add(scroll, BorderLayout.EAST);
        add(message, BorderLayout.SOUTH);
        profiles.addActionListener(e -> edit((Layout) profiles.getSelectedItem()));
        refresh(null);
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close");
        getActionMap().put("close", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { close.run(); }
        });
    }

    private void refresh(String selectedId) {
        var model = new DefaultComboBoxModel<Layout>();
        model.addElement(new Layout("", "New from Mario Kart DS", List.of()));
        Layout current = model.getElementAt(0);
        List<Layout> saved;
        try { saved = GamepadLayouts.load(config); }
        catch (IOException | IllegalArgumentException | IndexOutOfBoundsException e) {
            message.setText("Could not read layouts: " + e.getMessage());
            saved = List.of();
        }
        for (Layout layout : saved) {
            model.addElement(layout);
            if (layout.id().equals(selectedId)) current = layout;
        }
        model.setSelectedItem(current);
        profiles.setModel(model);
        edit(current);
    }

    private void edit(Layout layout) {
        if (layout == null) return;
        id = layout.id().isEmpty() ? GamepadLayouts.newId() : layout.id();
        name.setText(layout.id().isEmpty() ? "" : layout.name());
        widgets.clear();
        widgets.addAll(layout.id().isEmpty() ? List.of(GamepadLayouts.widget("dpad"), GamepadLayouts.widget("slider"),
                buttonWidget(4, 76, 21, "Map", "Y"), buttonWidget(4, 88, 10, "Select", "MINUS"),
                buttonWidget(15, 88, 10, "Start", "PLUS"), buttonWidget(76, 80, 18, "Brake / Back", "B")) : layout.widgets());
        select(-1);
    }

    private static Widget buttonWidget(int x, int y, int w, String label, String binding) {
        return new Widget("button", x, y, w, 11, label, binding, "", "", "", "", "hold", "tap", 0, false, "", "");
    }

    private void save() {
        try {
            GamepadLayouts.save(config, new Layout(id, name.getText().strip(), List.copyOf(widgets)));
            refresh(id);
            message.setText("Saved. Select this layout from Controls on your GamePad.");
        } catch (IOException | IllegalArgumentException e) {
            message.setText(e.getMessage());
        }
    }

    private void delete() {
        Layout current = (Layout) profiles.getSelectedItem();
        if (current == null || current.id().isEmpty()) return;
        try {
            GamepadLayouts.delete(config, current.id());
            refresh(null);
            message.setText("Layout deleted.");
        } catch (IOException | IllegalArgumentException e) {
            message.setText(e.getMessage());
        }
    }

    private void addWidget(String type, Point point) {
        if (widgets.size() >= 32) { message.setText("A layout can have up to 32 controls."); return; }
        Widget w = GamepadLayouts.widget(type);
        if (point != null) w = w.position(point.x - w.w() / 2, point.y - w.h() / 2, w.w(), w.h());
        widgets.add(w);
        select(widgets.size() - 1);
    }

    private JButton paletteButton(String type, String title) {
        JButton b = button(title, () -> addWidget(type, null));
        b.setTransferHandler(new TransferHandler() {
            @Override public int getSourceActions(JComponent c) { return COPY; }
            @Override protected Transferable createTransferable(JComponent c) { return new StringSelection(type); }
        });
        b.addMouseMotionListener(new MouseMotionAdapter() {
            @Override public void mouseDragged(MouseEvent e) { b.getTransferHandler().exportAsDrag(b, e, TransferHandler.COPY); }
        });
        return b;
    }

    private void select(int index) {
        selected = index;
        properties.removeAll();
        if (index >= 0) {
            Widget w = widgets.get(index);
            JTextField label = new JTextField(w.label(), 12);
            JTextField upLabel = new JTextField(w.upLabel(), 12);
            JTextField downLabel = new JTextField(w.downLabel(), 12);
            var bindings = new ArrayList<String>();
            bindings.add("");
            for (PadButton b : PadButton.values()) bindings.add(b.name());
            var button = choice(bindings, w.button());
            var up = choice(bindings, w.up());
            var down = choice(bindings, w.down());
            var left = choice(bindings, w.left());
            var right = choice(bindings, w.right());
            var upMode = choice(List.of("hold", "tap"), w.upMode());
            var downMode = choice(List.of("hold", "tap"), w.downMode());
            var axis = choice(List.of("Left", "Right"), w.axis() == 0 ? "Left" : "Right");
            var racing = new JCheckBox("Favor left and right", w.racing());
            field("Label", label);
            switch (w.type()) {
                case "button" -> field("Button", button);
                case "slider" -> {
                    field("Held button", button); field("Up", up); field("Up action", upMode);
                    field("Down", down); field("Down action", downMode);
                    field("Up label", upLabel); field("Down label", downLabel);
                }
                case "stick" -> field("Joystick", axis);
                case "dpad" -> {
                    field("Up", up); field("Down", down); field("Left", left); field("Right", right);
                    field("Racing steering", racing);
                }
                default -> { }
            }
            Runnable apply = () -> {
                Widget old = widgets.get(index);
                widgets.set(index, new Widget(old.type(), old.x(), old.y(), old.w(), old.h(),
                        label.getText(), value(button), value(up), value(down), value(left), value(right),
                        value(upMode), value(downMode), axis.getSelectedIndex(), racing.isSelected(), upLabel.getText(), downLabel.getText()));
                canvas.repaint();
            };
            for (var combo : List.of(button, up, down, left, right, upMode, downMode, axis)) combo.addActionListener(e -> apply.run());
            racing.addActionListener(e -> apply.run());
            var labels = new DocumentListener() {
                @Override public void insertUpdate(DocumentEvent e) { apply.run(); }
                @Override public void removeUpdate(DocumentEvent e) { apply.run(); }
                @Override public void changedUpdate(DocumentEvent e) { apply.run(); }
            };
            for (JTextField field : List.of(label, upLabel, downLabel)) field.getDocument().addDocumentListener(labels);
            properties.add(button("Remove control", () -> { widgets.remove(index); select(-1); }));
        }
        properties.revalidate();
        properties.repaint();
        canvas.repaint();
    }

    private void field(String title, JComponent control) {
        JLabel label = new JLabel(title);
        label.setLabelFor(control);
        properties.add(label);
        properties.add(control);
    }

    private static JComboBox<String> choice(List<String> values, String selected) {
        var field = new JComboBox<>(values.toArray(String[]::new));
        field.setSelectedItem(selected);
        return field;
    }

    private static String value(JComboBox<String> field) { return (String) field.getSelectedItem(); }

    private static JButton button(String title, Runnable action) {
        JButton b = new JButton(title);
        b.addActionListener(e -> action.run());
        return b;
    }

    private final class Canvas extends JPanel {
        private Point dragStart;
        private Widget original;
        private boolean resizing;

        Canvas() {
            setBackground(new Color(0x24313e));
            var mouse = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    if (e.getButton() != MouseEvent.BUTTON1) return;
                    for (int i = widgets.size() - 1; i >= 0; i--) {
                        Rectangle r = bounds(widgets.get(i));
                        if (!r.contains(e.getPoint())) continue;
                        select(i);
                        resizing = e.getX() >= r.x + r.width - 14 && e.getY() >= r.y + r.height - 14;
                        original = widgets.get(i);
                        dragStart = point(e.getPoint());
                        return;
                    }
                    select(-1);
                }

                @Override public void mouseDragged(MouseEvent e) {
                    if (original == null || selected < 0) return;
                    Point now = point(e.getPoint());
                    int dx = now.x - dragStart.x, dy = now.y - dragStart.y;
                    Widget w = original;
                    widgets.set(selected, resizing ? w.position(w.x(), w.y(), Math.min(100 - w.x(), w.w() + dx),
                            Math.min(100 - w.y(), w.h() + dy)) : w.position(w.x() + dx, w.y() + dy, w.w(), w.h()));
                    repaint();
                }

                @Override public void mouseReleased(MouseEvent e) { original = null; }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
            setTransferHandler(new TransferHandler() {
                @Override public boolean canImport(TransferSupport support) {
                    return support.isDrop() && support.isDataFlavorSupported(DataFlavor.stringFlavor);
                }
                @Override public boolean importData(TransferSupport support) {
                    if (!canImport(support)) return false;
                    try {
                        String type = (String) support.getTransferable().getTransferData(DataFlavor.stringFlavor);
                        if (!List.of(TYPES).contains(type)) return false;
                        addWidget(type, point(support.getDropLocation().getDropPoint()));
                        return true;
                    } catch (java.awt.datatransfer.UnsupportedFlavorException | IOException e) {
                        return false;
                    }
                }
            });
        }

        private Point point(Point p) {
            return new Point(Math.round(100f * p.x / Math.max(1, getWidth())), Math.round(100f * p.y / Math.max(1, getHeight())));
        }

        private Rectangle bounds(Widget w) {
            return new Rectangle(getWidth() * w.x() / 100, getHeight() * w.y() / 100,
                    getWidth() * w.w() / 100, getHeight() * w.h() / 100);
        }

        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setColor(new Color(0x71889a));
                int x = getWidth() * 31 / 100, y = getHeight() * 6 / 100;
                g.drawRoundRect(x, y, getWidth() * 38 / 100, getHeight() * 81 / 100, 12, 12);
                g.drawString("GamePad screen", x + 10, y + 20);
                for (int i = 0; i < widgets.size(); i++) {
                    Widget w = widgets.get(i);
                    Rectangle r = bounds(w);
                    g.setColor(new Color(i == selected ? 0x117f9c : 0x38526a));
                    g.fillRoundRect(r.x, r.y, r.width, r.height, 10, 10);
                    g.setColor(Color.WHITE);
                    Shape clip = g.getClip();
                    g.clip(r);
                    g.setColor(new Color(0x253848));
                    if (w.type().equals("dpad")) {
                        g.fillRoundRect(r.x + r.width / 10, r.y + r.height * 2 / 5, r.width * 4 / 5, r.height / 5, 5, 5);
                        g.fillRoundRect(r.x + r.width * 2 / 5, r.y + r.height / 10, r.width / 5, r.height * 4 / 5, 5, 5);
                    } else if (w.type().equals("stick")) {
                        g.fillOval(r.x + r.width / 4, r.y + r.height / 4, r.width / 2, r.height / 2);
                    } else if (w.type().equals("slider")) {
                        g.drawLine(r.x, r.y + r.height / 3, r.x + r.width, r.y + r.height / 3);
                        g.drawLine(r.x, r.y + r.height * 2 / 3, r.x + r.width, r.y + r.height * 2 / 3);
                    }
                    g.setColor(Color.WHITE);
                    String title = w.label().isBlank() ? w.type() : w.label();
                    g.drawString(title, r.x + Math.max(4, (r.width - g.getFontMetrics().stringWidth(title)) / 2),
                            r.y + r.height / 2 + 4);
                    g.setClip(clip);
                    g.fillRect(r.x + r.width - 10, r.y + r.height - 10, 8, 8);
                }
            } finally { g.dispose(); }
        }
    }
}
