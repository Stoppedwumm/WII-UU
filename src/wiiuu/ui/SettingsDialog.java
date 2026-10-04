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
    private final JCheckBox music = new JCheckBox("Background music");
    private final JCheckBox visualizer = new JCheckBox("Music visualizer behind the menu (V: full screen)");
    private final javax.swing.JComboBox<MenuTracks.Track> musicTrack = new javax.swing.JComboBox<>();
    private final javax.swing.JComboBox<String> theme = new javax.swing.JComboBox<>(
            new String[]{"Auto (follow the system)", "Light", "Dark"});
    private final JCheckBox boot = new JCheckBox("Start-up animation");
    private final JCheckBox eight = new JCheckBox("8-player mode: up to 8 phones at once (instead of 4)");
    private final JCheckBox buzzMode = new JCheckBox("Buzz! mode: phones become Buzz! buzzers in PS2 Buzz! games");
    private final JCheckBox retroarch = new JCheckBox("RetroArch mode: run every system that has a RetroArch core in RetroArch");
    private final JCheckBox split = new JCheckBox("Split DS / 3DS screens in RetroArch mode: top screen on the TV, touch screen on the phone");
    private final JCheckBox hideEmpty = new JCheckBox("Hide systems without games");
    private final JCheckBox minimize = new JCheckBox("Minimize menu while a game runs");

    // OpenBased tab
    private static wiiuu.core.OpenBased openBased;
    private static java.util.function.Supplier<String> gamepadBase = () -> null;

    private static Runnable guide = () -> { };

    private static Runnable restartIntoGuide = () -> { };

    /** Restarts WII-UU, which then starts with the setup guide (after its start-up animation). */
    public static void setRestartIntoGuide(Runnable restart) {
        restartIntoGuide = restart;
    }

    /** Shows the setup guide on the TV (Settings > General > Show the setup guide). */
    public static void setGuide(Runnable showGuide) {
        guide = showGuide;
    }

    /** For signing in to OpenBased from here: the client, and the GamePad server's address (null when off). */
    public static void setOpenBased(wiiuu.core.OpenBased ob, java.util.function.Supplier<String> base) {
        openBased = ob;
        gamepadBase = base;
    }

    private final JTextField obUrl = new JTextField(32);
    private final javax.swing.JPasswordField obToken = new javax.swing.JPasswordField(32);
    private final JTextField obPlayer = new JTextField(32);

    public SettingsDialog(Frame owner, Config config, Runnable onSaved) {
        super(owner, "WII-UU Settings", true);
        this.config = config;
        this.onSaved = onSaved;
        load();

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Systems & Emulators", systemsTab());
        tabs.addTab("GamePad Keys", keysTab());
        tabs.addTab("General", generalTab());
        tabs.addTab("OpenBased", openBasedTab());

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
        obUrl.setText(config.get("openbased.url", ""));
        obPlayer.setText(config.get("openbased.player", ""));
        port.setValue(config.port());
        serverOn.setSelected(config.getBool("server.enabled", true));
        requireCode.setSelected(config.getBool("server.requireCode", true));
        fixedCode.setText(config.get("server.code", ""));
        fullscreen.setSelected(config.getBool("ui.fullscreen", false));
        sounds.setSelected(config.getBool("ui.sounds", true));
        music.setSelected(config.getBool("ui.music", true));
        String track = config.get("ui.musicTrack", MenuTracks.DEFAULT).trim();
        musicTrack.removeAllItems();
        for (MenuTracks.Track t : MenuTracks.all(musicFolder())) musicTrack.addItem(t);
        musicTrack.addItem(new MenuTracks.Track(ExtendedMix.ID, ExtendedMix.NAME));
        musicTrack.addItem(new MenuTracks.Track(MenuTracks.ALL, "All of them, taking turns"));
        visualizer.setSelected(config.getBool("ui.visualizer", true));
        for (int i = 0; i < musicTrack.getItemCount(); i++) if (musicTrack.getItemAt(i).id().equals(track)) musicTrack.setSelectedIndex(i);
        String t = config.get("ui.theme", "auto").trim().toLowerCase();
        theme.setSelectedIndex(t.equals("light") ? 1 : t.equals("dark") ? 2 : 0);
        boot.setSelected(config.getBool("ui.bootAnimation", true));
        eight.setSelected(config.getInt("server.maxPlayers", 4) > 4);
        buzzMode.setSelected(config.getBool("buzz.enabled", true));
        retroarch.setSelected(config.getBool("retroarch.enabled", false));
        split.setSelected(config.getBool("screen.split", true));
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
        String url = obUrl.getText().trim(), token = new String(obToken.getPassword()).trim();
        config.set("openbased.url", url.isEmpty() ? null : url);
        if (url.isEmpty()) config.set("openbased.token", null);
        else if (!token.isEmpty()) {                                       // blank: keep the one set
            config.set("openbased.token", token);
            config.set("openbased.tokenId", null);
            config.set("openbased.user", null);
        }
        config.set("openbased.player", obPlayer.getText().isBlank() ? null : obPlayer.getText().trim());
        config.set("server.port", port.getValue().toString());
        config.set("server.enabled", Boolean.toString(serverOn.isSelected()));
        config.set("server.requireCode", Boolean.toString(requireCode.isSelected()));
        String code = fixedCode.getText().trim();
        config.set("server.code", code.isEmpty() ? null : code);
        config.set("ui.fullscreen", Boolean.toString(fullscreen.isSelected()));
        config.set("ui.sounds", Boolean.toString(sounds.isSelected()));
        config.set("ui.music", Boolean.toString(music.isSelected()));
        config.set("ui.visualizer", visualizer.isSelected() ? null : "false");
        MenuTracks.Track track = (MenuTracks.Track) musicTrack.getSelectedItem();
        config.set("ui.musicTrack", track == null || track.id().equals(MenuTracks.DEFAULT) ? null : track.id());
        config.set("ui.theme", new String[]{"auto", "light", "dark"}[Math.max(0, theme.getSelectedIndex())]);
        config.set("ui.bootAnimation", Boolean.toString(boot.isSelected()));
        config.set("server.maxPlayers", eight.isSelected() ? "8" : null);
        config.set("buzz.enabled", buzzMode.isSelected() ? null : "false");
        config.set("retroarch.enabled", Boolean.toString(retroarch.isSelected()));
        config.set("screen.split", split.isSelected() ? null : "false");
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
        JButton showGuide = new JButton("Show the setup guide\u2026");
        showGuide.setToolTipText("The welcome and setup screens from WII-UU's first start, on the TV");
        showGuide.addActionListener(e -> {
            dispose();
            guide.run();
        });
        JButton restartGuide = new JButton("Restart into the setup guide\u2026");
        restartGuide.setToolTipText("Restarts WII-UU, which then starts with the setup guide, like on its first start");
        restartGuide.addActionListener(e -> {
            int ok = JOptionPane.showConfirmDialog(this, "WII-UU restarts now (a running game is closed) and starts with the setup guide.\n"
                    + "Your settings and games are kept.", "Restart into the setup guide", JOptionPane.OK_CANCEL_OPTION);
            if (ok != JOptionPane.OK_OPTION) return;
            dispose();
            restartIntoGuide.run();
        });
        row = addRow(p, c, row, "Setup guide", showGuide, restartGuide);
        row = addRow(p, c, row, "GamePad server port", port, null);
        row = addRow(p, c, row, "Theme", theme, null);
        musicTrack.setRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override
            public java.awt.Component getListCellRendererComponent(javax.swing.JList<?> list, Object value, int index,
                                                                   boolean selected, boolean focus) {
                return super.getListCellRendererComponent(list, value instanceof MenuTracks.Track t ? t.name() : value,
                        index, selected, focus);
            }
        });
        JButton musicDir = new JButton("Add your own\u2026");
        musicDir.setToolTipText("Opens the music folder: put WAV or AIFF files there, then reopen Settings");
        musicDir.addActionListener(e -> {
            try {
                java.nio.file.Files.createDirectories(musicFolder());
                Desktop.getDesktop().open(musicFolder().toFile());
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Put WAV or AIFF files in " + musicFolder()
                        + ", then reopen Settings.", "Your own music", JOptionPane.INFORMATION_MESSAGE);
            }
        });
        row = addRow(p, c, row, "Menu music", musicTrack, musicDir);
        row = addRow(p, c, row, "Fixed pairing code", fixedCode, new JLabel("(phones stay paired; change it to unpair them)"));
        JButton update = new JButton("Check for updates");
        JLabel updateInfo = new JLabel("Version " + wiiuu.Main.VERSION);
        update.addActionListener(e -> checkForUpdates(update, updateInfo));
        JButton news = new JButton("What's new");
        news.addActionListener(e -> ChangelogDialog.show(this, "What's new in WII-UU", null,
                wiiuu.core.Changelog.bundled().entries(), true));
        JPanel updates = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        updates.add(update);
        updates.add(news);
        row = addRow(p, c, row, "Updates", updates, updateInfo);
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            // split DS/3DS screens need a display the TV doesn't show; Windows needs a driver for one
            JButton vdd = new JButton("Install virtual display\u2026");
            JLabel vddInfo = new JLabel("for split DS/3DS screens (asks for administrator rights once)");
            vdd.addActionListener(e -> {
                vdd.setEnabled(false);
                vddInfo.setText("Installing\u2026 allow the administrator prompt");
                Thread t = new Thread(() -> {
                    String result = wiiuu.screen.VirtualDisplay.installWindows(config);
                    javax.swing.SwingUtilities.invokeLater(() -> {
                        vddInfo.setText("<html>" + result + "</html>");
                        vdd.setEnabled(true);
                    });
                }, "install-virtual-display");
                t.setDaemon(true);
                t.start();
            });
            row = addRow(p, c, row, "Split screens", vdd, vddInfo);
        }
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
        for (JCheckBox box : new JCheckBox[]{serverOn, requireCode, fullscreen, minimize, sounds, music, visualizer, boot, retroarch, split, eight, buzzMode, hideEmpty}) {
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
            private java.util.List<wiiuu.core.Changelog.Entry> notes = java.util.List.of();

            @Override
            protected wiiuu.core.Updater.Release doInBackground() throws Exception {
                wiiuu.core.Updater.Release rel = updater.check();
                if (rel != null) notes = updater.notes(rel);
                return rel;
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
                boolean ok = ChangelogDialog.confirm(SettingsDialog.this, "Update WII-UU",
                        "<b>Update WII-UU " + wiiuu.Main.VERSION + " to " + rel.version() + " now?</b><br>"
                                + "Your settings, ROMs, paired phones and emulators are kept. WII-UU restarts when done.",
                        notes, "Update now", "Later");
                if (ok) upgrade(rel, button, info);
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

    private java.nio.file.Path musicFolder() {
        return config.home().resolve("music");
    }

    /** OpenBased: a media server whose videos WII-UU lists as a channel and plays on the TV. */
    private JComponent openBasedTab() {
        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(6, 8, 6, 8);
        c.anchor = GridBagConstraints.WEST;
        int row = 0;
        boolean hasToken = !config.get("openbased.token", "").isBlank();
        obUrl.setToolTipText("For example http://192.168.1.20:8080");
        obToken.setToolTipText(hasToken ? "A token is set; leave empty to keep it" : "ob_pat_...");
        obPlayer.setToolTipText("Empty: mpv, else VLC, else ffplay. Your own: {url} {title} {start}");
        JButton signIn = new JButton("Sign in with OpenBased\u2026");
        JLabel who = new JLabel(openBased != null && openBased.user() != null ? "Signed in as " + openBased.user()
                : hasToken ? "Connected with a token" : "Not signed in");
        signIn.addActionListener(e -> signInToOpenBased(who));
        JButton signOut = new JButton("Sign out");
        signOut.setEnabled(hasToken && openBased != null);
        signOut.addActionListener(e -> {
            openBased.signOut();
            who.setText("Not signed in");
            signOut.setEnabled(false);
        });
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        buttons.add(signIn);
        buttons.add(signOut);
        buttons.add(who);
        row = addRow(p, c, row, "Server address", obUrl, null);
        row = addRow(p, c, row, "Account", buttons, null);
        row = addRow(p, c, row, "Or a personal access token", obToken, new JLabel(hasToken ? "(set; empty keeps it)" : ""));
        row = addRow(p, c, row, "Video player command", obPlayer, new JLabel("(empty: mpv, VLC or ffplay)"));
        c.gridx = 0;
        c.gridy = row;
        c.gridwidth = 3;
        c.weighty = 1;
        c.anchor = GridBagConstraints.NORTHWEST;
        String base = gamepadBase.get();
        String snippet = openBased == null || base == null ? "(turn on the GamePad server first)"
                : openBased.clientConfig(openBased.redirectUri(base));
        p.add(new JLabel("<html><div style='width:620px'>Your OpenBased server's movies and episodes appear as a "
                + "channel on the home screen, and play full screen on the TV (mpv works best: it resumes where you "
                + "stopped and saves your progress to OpenBased).<br><br><b>Sign in</b> opens OpenBased's sign-in page in "
                + "your browser (OAuth); WII-UU then makes its own access token in your account. You can also sign in from "
                + "the phone: GamePad page, Library, <i>OpenBased</i>.<br><br>Once, OpenBased has to know WII-UU: add this under "
                + "<i>openbased.clients</i> in its application.yml (<i>/etc/openbased/application.yml</i> when installed as a "
                + "service; then <i>sudo systemctl restart openbased</i>). Enter the server address that is OpenBased's "
                + "<i>issuer</i>.<pre>" + snippet.replace("&", "&amp;").replace("<", "&lt;")
                + "</pre>Or paste a token from OpenBased's <b>API tokens</b> page (its web UI): tick media.read, media.stream, "
                + "history.read, history.write and profile, and choose 1 year.<br><br>"
                + "GamePad: A / + pause, ◀ ▶ seek, ▲ ▼ jump a minute, ZL / ZR volume, Y mute, X subtitles. "
                + "Home closes the video.</div></html>"), c);
        return new JScrollPane(p);
    }

    /** Saves the address, then opens OpenBased's sign-in page; WII-UU's GamePad server takes the answer. */
    private void signInToOpenBased(JLabel who) {
        String base = gamepadBase.get();
        if (openBased == null || base == null) {
            JOptionPane.showMessageDialog(this, "Signing in comes back through the GamePad server: turn it on (General) first.");
            return;
        }
        String url = obUrl.getText().trim();
        if (url.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Enter the OpenBased server address first.");
            return;
        }
        config.set("openbased.url", url);
        config.save();
        try {
            Desktop.getDesktop().browse(java.net.URI.create(openBased.loginUrl(openBased.redirectUri(base), null)));
            who.setText("Finish signing in in your browser\u2026");
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Could not open the browser: " + ex.getMessage());
        }
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
