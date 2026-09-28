package wiiuu.ui;

import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.table.AbstractTableModel;

import wiiuu.core.Config;
import wiiuu.core.GameSystem;
import wiiuu.core.Systems;
import wiiuu.input.KeyMap;
import wiiuu.input.PadButton;

/** Plain Swing settings: ROM folders + emulator commands, GamePad key mapping, general options. */
public final class SettingsDialog extends JDialog {
    private final Config config;
    private final Runnable onSaved;
    private wiiuu.core.Updater updater;
    private Runnable exitForUpgrade;

    // systems tab
    private final List<GameSystem> systems = Systems.ALL;
    private final boolean[] show = new boolean[systems.size()];
    private final String[] romDirs = new String[systems.size()];
    private final String[] commands = new String[systems.size()];
    private JTable systemTable;

    // controls tab
    private final PadButton[] buttons = PadButton.values();
    private final String[][] keys = new String[KeyMap.MAX_PLAYERS][buttons.length];
    private int player = 1;
    private JTable keyTable;

    // general tab
    private final JTextField romBase = new JTextField(32);
    private final JSpinner port = new JSpinner(new SpinnerNumberModel(8080, 1024, 65535, 1));
    private final JCheckBox serverOn = new JCheckBox("Enable phone GamePad server");
    private final JCheckBox requireCode = new JCheckBox("Require pairing code");
    private final JTextField fixedCode = new JTextField(8);
    private final JCheckBox fullscreen = new JCheckBox("Start in fullscreen");
    private final JCheckBox sounds = new JCheckBox("Menu sounds");
    private final JCheckBox hideEmpty = new JCheckBox("Hide systems without games");
    private final JCheckBox minimize = new JCheckBox("Minimize menu while a game runs");

    public SettingsDialog(Frame owner, Config config, Runnable onSaved) {
        super(owner, "WII-UU Settings", true);
        this.config = config;
        this.onSaved = onSaved;
        load();

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Systems & Emulators", systemsTab());
        tabs.addTab("GamePad Keys", keysTab());
        tabs.addTab("General", generalTab());

        JButton save = new JButton("Save");
        JButton cancel = new JButton("Cancel");
        save.addActionListener(e -> {
            if (systemTable.isEditing()) systemTable.getCellEditor().stopCellEditing();
            if (keyTable.isEditing()) keyTable.getCellEditor().stopCellEditing();
            store();
            dispose();
            onSaved.run();
        });
        cancel.addActionListener(e -> dispose());
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        bottom.add(cancel);
        bottom.add(save);
        getRootPane().setDefaultButton(save);

        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        root.add(tabs, BorderLayout.CENTER);
        root.add(bottom, BorderLayout.SOUTH);
        setContentPane(root);
        setSize(1050, 640);
        setLocationRelativeTo(owner);
    }

    private void load() {
        for (int i = 0; i < systems.size(); i++) {
            GameSystem s = systems.get(i);
            show[i] = !config.hidden(s);
            romDirs[i] = config.romDir(s).toString();
            commands[i] = config.command(s);
        }
        KeyMap km = new KeyMap(config);
        for (int p = 1; p <= KeyMap.MAX_PLAYERS; p++)
            for (int b = 0; b < buttons.length; b++) keys[p - 1][b] = km.keyName(p, buttons[b]);
        romBase.setText(config.romBase().toString());
        port.setValue(config.port());
        serverOn.setSelected(config.getBool("server.enabled", true));
        requireCode.setSelected(config.getBool("server.requireCode", true));
        fixedCode.setText(config.get("server.code", ""));
        fullscreen.setSelected(config.getBool("ui.fullscreen", false));
        sounds.setSelected(config.getBool("ui.sounds", true));
        hideEmpty.setSelected(config.getBool("ui.hideEmpty", false));
        minimize.setSelected(config.getBool("ui.minimizeOnLaunch", true));
    }

    private void store() {
        Path oldBase = config.romBase();
        config.set("roms.base", romBase.getText().trim());
        for (int i = 0; i < systems.size(); i++) {
            GameSystem s = systems.get(i);
            config.set("system." + s.id() + ".hidden", show[i] ? null : "true");
            String dir = romDirs[i].trim();
            boolean defaultDir = dir.isEmpty() || dir.equals(oldBase.resolve(s.id()).toString())
                    || dir.equals(config.romBase().resolve(s.id()).toString());
            config.set("system." + s.id() + ".romdir", defaultDir ? null : dir);
            String cmd = commands[i].trim();
            config.set("system." + s.id() + ".command", cmd.isEmpty() || cmd.equals(s.defaultCommand()) ? null : cmd);
        }
        for (int p = 1; p <= KeyMap.MAX_PLAYERS; p++) {
            for (int b = 0; b < buttons.length; b++) {
                String v = keys[p - 1][b] == null ? "" : keys[p - 1][b].trim().toUpperCase();
                config.set("keys.p" + p + "." + buttons[b].name(), v.equals(KeyMap.defaultKey(p, buttons[b])) ? null : v);
            }
        }
        config.set("server.port", port.getValue().toString());
        config.set("server.enabled", Boolean.toString(serverOn.isSelected()));
        config.set("server.requireCode", Boolean.toString(requireCode.isSelected()));
        String code = fixedCode.getText().trim();
        config.set("server.code", code.isEmpty() ? null : code);
        config.set("ui.fullscreen", Boolean.toString(fullscreen.isSelected()));
        config.set("ui.sounds", Boolean.toString(sounds.isSelected()));
        config.set("ui.hideEmpty", Boolean.toString(hideEmpty.isSelected()));
        config.set("ui.minimizeOnLaunch", Boolean.toString(minimize.isSelected()));
        config.save();
    }

    // ---- tabs ---------------------------------------------------------------------------

    private JComponent systemsTab() {
        String[] cols = {"Show", "System", "Emulator", "ROM folder", "Command ({rom} = game file)"};
        systemTable = new JTable(new AbstractTableModel() {
            public int getRowCount() { return systems.size(); }
            public int getColumnCount() { return cols.length; }
            public String getColumnName(int c) { return cols[c]; }
            public Class<?> getColumnClass(int c) { return c == 0 ? Boolean.class : String.class; }
            public boolean isCellEditable(int r, int c) { return c == 0 || c >= 3; }
            public Object getValueAt(int r, int c) {
                GameSystem s = systems.get(r);
                return switch (c) {
                    case 0 -> show[r];
                    case 1 -> s.name();
                    case 2 -> s.emulator();
                    case 3 -> romDirs[r];
                    default -> commands[r];
                };
            }
            public void setValueAt(Object v, int r, int c) {
                if (c == 0) show[r] = (Boolean) v;
                else if (c == 3) romDirs[r] = (String) v;
                else if (c == 4) commands[r] = (String) v;
            }
        });
        systemTable.setRowHeight(26);
        int[] widths = {50, 190, 100, 280, 380};
        for (int i = 0; i < widths.length; i++) systemTable.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);

        JButton browseDir = new JButton("ROM folder…");
        browseDir.addActionListener(e -> withRow(r -> {
            JFileChooser fc = new JFileChooser(romDirs[r]);
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                romDirs[r] = fc.getSelectedFile().getAbsolutePath();
                systemTable.repaint();
            }
        }));
        JButton browseEmu = new JButton("Emulator program…");
        browseEmu.addActionListener(e -> withRow(r -> {
            JFileChooser fc = new JFileChooser();
            fc.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                commands[r] = replaceProgram(commands[r], fc.getSelectedFile());
                systemTable.repaint();
            }
        }));
        JButton reset = new JButton("Reset command");
        reset.addActionListener(e -> withRow(r -> {
            commands[r] = systems.get(r).defaultCommand();
            systemTable.repaint();
        }));
        JButton open = new JButton("Open ROM folder");
        open.addActionListener(e -> withRow(r -> {
            try {
                File dir = new File(romDirs[r]);
                Files.createDirectories(dir.toPath());
                Desktop.getDesktop().open(dir);
            } catch (IOException | UnsupportedOperationException | IllegalArgumentException ex) {
                JOptionPane.showMessageDialog(this, "Could not open " + romDirs[r] + ": " + ex.getMessage());
            }
        }));
        JPanel buttonsRow = new JPanel(new FlowLayout(FlowLayout.LEFT));
        for (JButton b : new JButton[]{browseDir, browseEmu, reset, open}) buttonsRow.add(b);
        JLabel help = new JLabel("<html>Pick a row, then use the buttons, or double-click a cell to edit. "
                + "Placeholders: <b>{rom}</b> game path, <b>{dir}</b> its folder, <b>{name}</b> title. "
                + "Wrap paths with spaces in quotes. macOS apps: <code>open -W -a AppName --args {rom}</code>.</html>");
        help.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));

        JPanel p = new JPanel(new BorderLayout());
        p.add(help, BorderLayout.NORTH);
        p.add(new JScrollPane(systemTable), BorderLayout.CENTER);
        p.add(buttonsRow, BorderLayout.SOUTH);
        return p;
    }

    private JComponent keysTab() {
        String[] cols = {"GamePad button", "Keyboard key"};
        keyTable = new JTable(new AbstractTableModel() {
            public int getRowCount() { return buttons.length; }
            public int getColumnCount() { return 2; }
            public String getColumnName(int c) { return cols[c]; }
            public boolean isCellEditable(int r, int c) { return c == 1; }
            public Object getValueAt(int r, int c) { return c == 0 ? label(buttons[r]) : keys[player - 1][r]; }
            public void setValueAt(Object v, int r, int c) {
                String name = ((String) v).trim().toUpperCase();
                if (name.isEmpty() || KeyMap.codeOf(name) >= 0) keys[player - 1][r] = name;
                else JOptionPane.showMessageDialog(SettingsDialog.this, "Unknown key \"" + v
                        + "\". Use Java key names like X, ENTER, SPACE, UP, NUMPAD8, F5.");
            }
        });
        keyTable.setRowHeight(24);

        JComboBox<String> players = new JComboBox<>(new String[]{"Player 1", "Player 2", "Player 3", "Player 4"});
        players.addActionListener(e -> {
            if (keyTable.isEditing()) keyTable.getCellEditor().stopCellEditing();
            player = players.getSelectedIndex() + 1;
            keyTable.repaint();
        });
        JButton capture = new JButton("Capture key…");
        capture.addActionListener(e -> {
            int r = keyTable.getSelectedRow();
            if (r < 0) {
                JOptionPane.showMessageDialog(this, "Select a button row first.");
                return;
            }
            String k = captureKey(label(buttons[r]));
            if (k != null) {
                keys[player - 1][r] = k;
                keyTable.repaint();
            }
        });
        JButton clear = new JButton("Clear");
        clear.addActionListener(e -> {
            int r = keyTable.getSelectedRow();
            if (r >= 0) {
                keys[player - 1][r] = "";
                keyTable.repaint();
            }
        });
        JButton defaults = new JButton("Reset player to defaults");
        defaults.addActionListener(e -> {
            for (int b = 0; b < buttons.length; b++) keys[player - 1][b] = KeyMap.defaultKey(player, buttons[b]);
            keyTable.repaint();
        });
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        top.add(players);
        top.add(capture);
        top.add(clear);
        top.add(defaults);
        JLabel help = new JLabel("<html>While a game runs, GamePad buttons are typed as these keys into the "
                + "focused emulator window. Set each emulator's keyboard controls to the same keys "
                + "(defaults follow RetroArch: A=X, B=Z, X=S, Y=A, L=Q, R=W, + = Enter).</html>");
        help.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        JPanel north = new JPanel(new BorderLayout());
        north.add(top, BorderLayout.NORTH);
        north.add(help, BorderLayout.SOUTH);
        JPanel p = new JPanel(new BorderLayout());
        p.add(north, BorderLayout.NORTH);
        p.add(new JScrollPane(keyTable), BorderLayout.CENTER);
        return p;
    }

    private JComponent generalTab() {
        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(6, 8, 6, 8);
        c.anchor = GridBagConstraints.WEST;
        int row = 0;
        JButton browse = new JButton("Browse…");
        browse.addActionListener(e -> {
            JFileChooser fc = new JFileChooser(romBase.getText());
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                String old = romBase.getText().trim();
                String now = fc.getSelectedFile().getAbsolutePath();
                romBase.setText(now);
                for (int i = 0; i < systems.size(); i++) {
                    if (romDirs[i].equals(Path.of(old, systems.get(i).id()).toString())) {
                        romDirs[i] = Path.of(now, systems.get(i).id()).toString();
                    }
                }
                systemTable.repaint();
            }
        });
        row = addRow(p, c, row, "ROM base folder", romBase, browse);
        row = addRow(p, c, row, "GamePad server port", port, null);
        row = addRow(p, c, row, "Fixed pairing code", fixedCode, new JLabel("(phones stay paired; change it to unpair them)"));
        JButton update = new JButton("Check for updates");
        JLabel updateInfo = new JLabel("Version " + wiiuu.Main.VERSION);
        update.addActionListener(e -> checkForUpdates(update, updateInfo));
        row = addRow(p, c, row, "Updates", update, updateInfo);
        if (System.getProperty("os.name", "").toLowerCase().contains("mac")) {
            // macOS silently blocks screen capture (black GamePad screen) and synthetic keys without these
            JPanel perms = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            JButton rec = new JButton("Screen Recording\u2026");
            rec.addActionListener(e -> openMacPane("Privacy_ScreenCapture"));
            JButton acc = new JButton("Accessibility\u2026");
            acc.addActionListener(e -> openMacPane("Privacy_Accessibility"));
            perms.add(rec);
            perms.add(acc);
            row = addRow(p, c, row, "macOS permissions", perms,
                    new JLabel("<html>Turn on <b>WII-UU</b> (or Java / Terminal) in both,<br>then restart WII-UU.</html>"));
        }
        for (JCheckBox box : new JCheckBox[]{serverOn, requireCode, fullscreen, minimize, sounds, hideEmpty}) {
            c.gridx = 1;
            c.gridy = row++;
            c.gridwidth = 2;
            p.add(box, c);
            c.gridwidth = 1;
        }
        JLabel note = new JLabel("Port and server changes apply after restarting WII-UU.");
        note.setFont(note.getFont().deriveFont(Font.ITALIC));
        c.gridx = 1;
        c.gridy = row++;
        p.add(note, c);
        c.gridy = row;
        c.weighty = 1;
        p.add(new JLabel(), c);
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.add(p, BorderLayout.NORTH);
        return wrap;
    }

    private void openMacPane(String anchor) {
        try {
            new ProcessBuilder("open", "x-apple.systempreferences:com.apple.preference.security?" + anchor).start();
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Open System Settings > Privacy & Security yourself.");
        }
    }

    /** Lets the dialog upgrade WII-UU; {@code exit} quits the app so the installer can replace it. */
    public SettingsDialog withUpdater(wiiuu.core.Updater updater, Runnable exit) {
        this.updater = updater;
        this.exitForUpgrade = exit;
        return this;
    }

    private void checkForUpdates(JButton button, JLabel info) {
        if (updater == null) return;
        button.setEnabled(false);
        info.setText("Checking...");
        new javax.swing.SwingWorker<wiiuu.core.Updater.Release, Void>() {
            @Override
            protected wiiuu.core.Updater.Release doInBackground() throws Exception {
                return updater.check();
            }

            @Override
            protected void done() {
                button.setEnabled(true);
                wiiuu.core.Updater.Release rel;
                try {
                    rel = get();
                } catch (Exception ex) {
                    info.setText("Could not check: " + (ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage()));
                    return;
                }
                if (rel == null) {
                    info.setText("You have the newest version (" + wiiuu.Main.VERSION + ")");
                    return;
                }
                info.setText("Version " + rel.version() + " is available");
                int ok = JOptionPane.showConfirmDialog(SettingsDialog.this,
                        "Update WII-UU " + wiiuu.Main.VERSION + " to " + rel.version() + " now?\n\n"
                                + "Your settings, ROMs, paired phones and emulators are kept.\nWII-UU restarts when done.",
                        "Update WII-UU", JOptionPane.OK_CANCEL_OPTION);
                if (ok == JOptionPane.OK_OPTION) upgrade(rel, button, info);
            }
        }.execute();
    }

    private void upgrade(wiiuu.core.Updater.Release rel, JButton button, JLabel info) {
        button.setEnabled(false);
        info.setText("Downloading " + rel.version() + "...");
        new javax.swing.SwingWorker<java.nio.file.Path, Void>() {
            @Override
            protected java.nio.file.Path doInBackground() throws Exception {
                java.nio.file.Path dir = updater.download(rel);
                updater.installAfterExit(dir);
                return dir;
            }

            @Override
            protected void done() {
                try {
                    get();
                    info.setText("Installing - WII-UU restarts in a moment");
                    exitForUpgrade.run();
                } catch (Exception ex) {
                    button.setEnabled(true);
                    info.setText("Update failed: " + (ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage()));
                }
            }
        }.execute();
    }

    private static int addRow(JPanel p, GridBagConstraints c, int row, String label, JComponent field, JComponent extra) {
        c.gridx = 0;
        c.gridy = row;
        p.add(new JLabel(label), c);
        c.gridx = 1;
        p.add(field, c);
        if (extra != null) {
            c.gridx = 2;
            p.add(extra, c);
        }
        return row + 1;
    }

    // ---- helpers ------------------------------------------------------------------------

    private void withRow(java.util.function.IntConsumer action) {
        int r = systemTable.getSelectedRow();
        if (r < 0) {
            JOptionPane.showMessageDialog(this, "Select a system row first.");
            return;
        }
        if (systemTable.isEditing()) systemTable.getCellEditor().stopCellEditing();
        action.accept(systemTable.convertRowIndexToModel(r));
    }

    /** Swaps the first word of a command (the program) for a chosen file, keeping the arguments. */
    static String replaceProgram(String command, File program) {
        String path = program.getAbsolutePath();
        if (path.endsWith(".app")) {
            String name = program.getName().replaceAll("\\.app$", "");
            return "open -W -a \"" + name + "\" --args {rom}";
        }
        String quoted = path.contains(" ") ? "\"" + path + "\"" : path;
        String trimmed = command.trim();
        String rest;
        if (trimmed.startsWith("\"")) {
            int end = trimmed.indexOf('"', 1);
            rest = end > 0 ? trimmed.substring(end + 1) : "";
        } else {
            int sp = trimmed.indexOf(' ');
            rest = sp > 0 ? trimmed.substring(sp) : " {rom}";
        }
        if (trimmed.startsWith("open -W -a")) rest = " {rom}";
        return quoted + rest;
    }

    private String captureKey(String buttonLabel) {
        JDialog d = new JDialog(this, "Capture key", true);
        String[] result = new String[1];
        JLabel l = new JLabel("Press the key for " + buttonLabel + "  (Esc cancels)", JLabel.CENTER);
        l.setBorder(BorderFactory.createEmptyBorder(30, 30, 30, 30));
        l.setFocusable(true);
        l.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() != KeyEvent.VK_ESCAPE) result[0] = KeyMap.nameOf(e.getKeyCode());
                d.dispose();
            }
        });
        d.setContentPane(l);
        d.pack();
        d.setLocationRelativeTo(this);
        l.requestFocusInWindow();
        d.setVisible(true);
        return result[0] == null || result[0].isEmpty() ? null : result[0];
    }

    private static String label(PadButton b) {
        return switch (b) {
            case PLUS -> "+ (Start)";
            case MINUS -> "− (Select)";
            case HOME -> "HOME";
            case UP -> "D-Pad Up";
            case DOWN -> "D-Pad Down";
            case LEFT -> "D-Pad Left";
            case RIGHT -> "D-Pad Right";
            case LS_UP -> "Left stick Up";
            case LS_DOWN -> "Left stick Down";
            case LS_LEFT -> "Left stick Left";
            case LS_RIGHT -> "Left stick Right";
            case RS_UP -> "Right stick Up";
            case RS_DOWN -> "Right stick Down";
            case RS_LEFT -> "Right stick Left";
            case RS_RIGHT -> "Right stick Right";
            case L3 -> "L3 (press left stick)";
            case R3 -> "R3 (press right stick)";
            default -> b.name();
        };
    }
}
