package choccymilk;

import java.awt.*;
import java.awt.event.*;
import java.nio.file.*;
import java.util.*;
import javax.swing.*;
import wiiuu.core.*;
import wiiuu.input.ChoccyKeys;
import wiiuu.ui.Ui;
import wiiuu.ui.UiTheme;

/** Standalone settings: edits are staged until Save, including captured keyboard assignments. */
final class SettingsDialog {
    private SettingsDialog() {}
    static boolean show(JFrame owner, Config config, java.util.function.BooleanSupplier gameRunning) {
        Ui ui = new Ui(UiTheme.light());
        JDialog dialog = new JDialog(owner, "Choccy Milk settings", true);
        JTabbedPane tabs = new JTabbedPane();
        JPanel general = ui.panel(new GridLayout(0, 1, 8, 8));
        general.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        JComboBox<String> theme = ui.comboBox(new String[]{"auto", "cocoa", "milk"});
        theme.setSelectedItem(config.get("choccy.theme", "auto"));
        JCheckBox intro = ui.checkBox("Play startup animation", config.getBool("choccy.intro", true));
        JTextField roms = ui.textField(config.romBase().toString(), 30);
        JTextField bios = ui.textField(config.get("choccy.systemDir", ""), 30);
        general.add(ui.label("Theme")); general.add(theme); general.add(intro);
        general.add(ui.label("Game library folder (shared with WII-UU)")); general.add(folder(ui, roms));
        general.add(ui.label("BIOS / system folder (blank: automatic)")); general.add(folder(ui, bios));
        general.add(ui.label("Game folders and BIOS changes apply to the next game."));
        tabs.addTab("General", general);
        tabs.addTab("Cores", new wiiuu.engine.CoreManagerPanel(config, gameRunning));

        Map<String, Integer> keys = new LinkedHashMap<>(ChoccyKeys.load(config));
        JPanel keyboard = ui.panel(new GridLayout(0, 3, 8, 5));
        keyboard.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        Map<String, JButton> keyButtons = new LinkedHashMap<>();
        for (String action : keys.keySet()) {
            keyboard.add(ui.label(action));
            JButton capture = ui.button(keyName(keys.get(action)));
            keyButtons.put(action, capture);
            capture.setFocusTraversalKeysEnabled(false);
            capture.addActionListener(e -> { capture.setText("Press a key…"); capture.putClientProperty("capture", true); capture.requestFocusInWindow(); });
            capture.addKeyListener(new KeyAdapter() {
                @Override public void keyPressed(KeyEvent e) {
                    if (!Boolean.TRUE.equals(capture.getClientProperty("capture"))) return;
                    e.consume();
                    if (e.getKeyCode() == KeyEvent.VK_F1) { capture.setText("F1 is Settings — choose another"); return; }
                    keys.put(action, e.getKeyCode()); capture.setText(keyName(e.getKeyCode())); capture.putClientProperty("capture", false);
                }
            });
            capture.addFocusListener(new FocusAdapter() {
                @Override public void focusLost(FocusEvent e) { capture.putClientProperty("capture", false); capture.setText(keyName(keys.get(action))); }
            });
            keyboard.add(capture);
            JButton clear = ui.button("Unbind");
            clear.addActionListener(e -> { keys.put(action, 0); capture.setText("Unbound"); });
            keyboard.add(clear);
        }
        JButton reset = ui.button("Restore default keys");
        reset.addActionListener(e -> { keys.clear(); keys.putAll(ChoccyKeys.defaults()); keyButtons.forEach((a, button) -> button.setText(keyName(keys.get(a)))); });
        JPanel keyboardPage = ui.panel(new BorderLayout(8, 8));
        keyboardPage.add(ui.label("Click an assignment, then press a key. F1 always opens Settings."), BorderLayout.NORTH);
        keyboardPage.add(ui.scrollPane(keyboard), BorderLayout.CENTER); keyboardPage.add(reset, BorderLayout.SOUTH);
        tabs.addTab("Keyboard", keyboardPage);

        JPanel online = ui.panel(new GridLayout(0, 1, 8, 8));
        online.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        JCheckBox wfc = ui.checkBox("Enable DS online (WFC replacement services)", DsOnline.enabled(config));
        JTextField dns = ui.textField(DsOnline.dns(config), 25);
        JTextField name = ui.textField(DsOnline.username(config), 12);
        JCheckBox unique = ui.checkBox("Derive DS MAC from this profile name", config.getBool("choccy.nds.online.uniqueMac", false));
        online.add(wfc); online.add(ui.label("DNS override (IPv4; blank uses the melonDS DS core's default service)")); online.add(dns);
        online.add(ui.label("DS profile name (1–10 letters, digits, _ or -)")); online.add(name); online.add(unique);
        online.add(ui.label("Changing the MAC/profile may affect existing online profiles and Pokémon saves."));
        online.add(ui.label("Uses melonDS DS indirect networking. Restart the game after changes."));
        online.add(ui.label("In the game's WFC setup, connect to melonAP, then use its online/friend-code menu."));
        online.add(ui.label("Game/server compatibility and SSL patches depend on the replacement service."));
        online.add(ui.label("These settings are shared with WII-UU. Local wireless / Download Play is separate."));
        tabs.addTab("DS online", online);

        JPanel bluetooth = ui.panel(new GridLayout(0, 1, 8, 8));
        bluetooth.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        JTextField peer = ui.textField(BluetoothLink.peer(config), 24);
        JComboBox<String> role = ui.comboBox(new String[]{"off", "host", "join"});
        role.setSelectedItem(BluetoothLink.role(config));
        bluetooth.add(ui.label(BluetoothLink.availability()));
        bluetooth.add(ui.label("Connect to either WII-UU or another Choccy Milk."));
        bluetooth.add(ui.label("Paired device Bluetooth MAC address")); bluetooth.add(peer);
        bluetooth.add(ui.label("DS local multiplayer (experimental): host / join / off")); bluetooth.add(role);
        bluetooth.add(ui.label("Save, start the host first, then join from the other device. Use the in-game local multiplayer menu."));
        JComboBox<GameSystem> transferSystem = ui.comboBox(Systems.ALL.stream().filter(s -> !s.extensions().isEmpty()).toArray(GameSystem[]::new));
        transferSystem.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l, Object value, int i, boolean s, boolean f) {
                return super.getListCellRendererComponent(l, value instanceof GameSystem gs ? gs.name() : value, i, s, f);
            }
        });
        bluetooth.add(transferSystem);
        JButton send = ui.button("Send game…"), receive = ui.button("Receive game");
        JLabel transferStatus = ui.label("Start Receive on the other device first.");
        bluetooth.add(send); bluetooth.add(receive); bluetooth.add(transferStatus);
        java.util.function.Consumer<Path> transfer = path -> {
            if (gameRunning.getAsBoolean() || !BluetoothLink.validAddress(peer.getText().trim())) {
                transferStatus.setText("Close the game and enter a paired Bluetooth MAC address."); return;
            }
            GameSystem system = (GameSystem) transferSystem.getSelectedItem();
            String address = peer.getText().trim();
            send.setEnabled(false); receive.setEnabled(false);
            transferStatus.setText(path == null ? "Waiting for sender…" : "Connecting…");
            new SwingWorker<String, Long>() {
                protected String doInBackground() throws Exception {
                    if (path == null) return "Received " + BluetoothGames.receive(config, address, n -> publish(n)).getFileName();
                    BluetoothGames.send(address, system, path, n -> publish(n));
                    return "Sent " + path.getFileName();
                }
                protected void process(java.util.List<Long> values) {
                    transferStatus.setText(values.get(values.size() - 1) / 1048576 + " MiB transferred");
                }
                protected void done() {
                    try { transferStatus.setText(get()); }
                    catch (Exception e) { transferStatus.setText("Bluetooth transfer failed: " + (e.getCause() == null ? e.getMessage() : e.getCause().getMessage())); }
                    send.setEnabled(true); receive.setEnabled(true);
                }
            }.execute();
        };
        send.addActionListener(e -> {
            GameSystem system = (GameSystem) transferSystem.getSelectedItem();
            JFileChooser picker = new JFileChooser(config.romDir(system).toFile());
            if (picker.showOpenDialog(dialog) == JFileChooser.APPROVE_OPTION) transfer.accept(picker.getSelectedFile().toPath());
        });
        receive.addActionListener(e -> transfer.accept(null));
        if (!BluetoothLink.supported()) { send.setEnabled(false); receive.setEnabled(false); role.setEnabled(false); peer.setEnabled(false); }
        tabs.addTab("Bluetooth", ui.scrollPane(bluetooth));

        JPanel picture = ui.panel(new BorderLayout());
        JComboBox<GameSystem> console = ui.comboBox(Systems.ALL.stream().filter(Cores::has).toArray(GameSystem[]::new));
        console.setRenderer(new DefaultListCellRenderer() { @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean s, boolean f) {
            return super.getListCellRendererComponent(l, v instanceof GameSystem gs ? gs.name() : v, i, s, f);
        }});
        Map<String, String> filters = new HashMap<>(); Map<String, Boolean> wides = new HashMap<>(), blends = new HashMap<>();
        for (GameSystem gs : Systems.ALL) { filters.put(gs.id(), ChoccyPicture.upscale(config, gs.id())); wides.put(gs.id(), ChoccyPicture.widescreen(config, gs.id())); blends.put(gs.id(), ChoccyPicture.blend(config, gs.id())); }
        JPanel options = ui.panel(new GridLayout(0, 1, 8, 8));
        Runnable rebuild = () -> {
            GameSystem gs = (GameSystem) console.getSelectedItem(); if (gs == null) return;
            options.removeAll();
            var ups = ChoccyPicture.upscalers(gs.id());
            JComboBox<String> up = ui.comboBox(ups.stream().map(x -> x[1]).toArray(String[]::new));
            for (int i = 0; i < ups.size(); i++) if (ups.get(i)[0].equals(filters.get(gs.id()))) up.setSelectedIndex(i);
            up.addActionListener(e -> filters.put(gs.id(), ups.get(up.getSelectedIndex())[0]));
            JCheckBox wide = ui.checkBox(ChoccyPicture.n64OpenGlAvailable(gs.id())
                    ? "Widescreen (GLideN64)" : "Widescreen", wides.get(gs.id()));
            wide.addActionListener(e -> wides.put(gs.id(), wide.isSelected()));
            JCheckBox blend = ui.checkBox("Blend frames", blends.get(gs.id())); blend.addActionListener(e -> blends.put(gs.id(), blend.isSelected()));
            options.add(ui.label("Upscaling")); options.add(up); options.add(wide); options.add(blend);
            options.add(ui.label("Core rendering changes take effect on the next game.")); options.revalidate(); options.repaint();
        };
        console.addActionListener(e -> rebuild.run()); rebuild.run();
        picture.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16)); picture.add(console, BorderLayout.NORTH); picture.add(options, BorderLayout.CENTER);
        tabs.addTab("Picture", picture);

        boolean[] saved = {false};
        JButton save = ui.primaryButton("Save"); JButton cancel = ui.button("Cancel");
        save.addActionListener(e -> {
            try {
                ChoccyKeys.validate(keys);
                String btPeer = peer.getText().trim();
                if (!btPeer.isBlank() && !BluetoothLink.validAddress(btPeer)) throw new IllegalArgumentException("Invalid Bluetooth MAC address");
                if (!"off".equals(role.getSelectedItem()) && (!BluetoothLink.supported() || !BluetoothLink.validAddress(btPeer)))
                    throw new IllegalArgumentException("Bluetooth DS multiplayer needs Linux and a paired device MAC");
                if (!DsOnline.validDns(dns.getText().trim())) throw new IllegalArgumentException("DS DNS must be an IPv4 address, or blank");
                if (!DsOnline.validName(name.getText().trim())) throw new IllegalArgumentException("DS profile name needs 1–10 letters, digits, _ or -");
                Path romPath = Path.of(roms.getText().trim());
                if (roms.getText().isBlank() || Files.exists(romPath) && !Files.isDirectory(romPath)) throw new IllegalArgumentException("Choose a game library folder");
                if (!bios.getText().isBlank() && !Files.isDirectory(Path.of(bios.getText().trim()))) throw new IllegalArgumentException("Choose an existing BIOS folder, or leave it blank");
                config.set("choccy.bluetooth.peer", btPeer);
                config.set("choccy.bluetooth.ds", (String)role.getSelectedItem());
                config.set("roms.base", romPath.toString()); config.set("choccy.systemDir", bios.getText().isBlank() ? null : bios.getText().trim());
                config.set("choccy.theme", (String) theme.getSelectedItem()); config.set("choccy.intro", Boolean.toString(intro.isSelected()));
                config.set("choccy.nds.online", Boolean.toString(wfc.isSelected())); config.set("choccy.nds.dns", dns.getText().trim());
                config.set("choccy.nds.username", name.getText().trim()); config.set("choccy.nds.online.uniqueMac", Boolean.toString(unique.isSelected()));
                filters.forEach((id, v) -> config.set("choccy." + id + ".upscale", v));
                wides.forEach((id, v) -> config.set("choccy." + id + ".widescreen", Boolean.toString(v)));
                blends.forEach((id, v) -> config.set("choccy." + id + ".blend", Boolean.toString(v)));
                ChoccyKeys.save(config, keys); saved[0] = true; dialog.dispose();
            } catch (IllegalArgumentException invalid) { JOptionPane.showMessageDialog(dialog, invalid.getMessage(), "Settings", JOptionPane.ERROR_MESSAGE); }
        });
        cancel.addActionListener(e -> dialog.dispose());
        JPanel buttons = ui.panel(new FlowLayout(FlowLayout.RIGHT)); buttons.add(ui.label("A running game continues while Settings is open.")); buttons.add(cancel); buttons.add(save);
        dialog.add(tabs, BorderLayout.CENTER); dialog.add(buttons, BorderLayout.SOUTH);
        dialog.setMinimumSize(new Dimension(800, 540)); dialog.setSize(880, 620); dialog.setLocationRelativeTo(owner);
        dialog.getRootPane().setDefaultButton(save);
        dialog.getRootPane().registerKeyboardAction(e -> dialog.dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        dialog.setVisible(true); return saved[0];
    }
    private static String keyName(int code) { return code == 0 ? "Unbound" : KeyEvent.getKeyText(code); }
    private static JPanel folder(Ui ui, JTextField field) {
        JPanel row = ui.panel(new BorderLayout(8, 0)); JButton choose = ui.button("Browse…");
        choose.addActionListener(e -> { JFileChooser picker = new JFileChooser(); picker.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (picker.showOpenDialog(row) == JFileChooser.APPROVE_OPTION) field.setText(picker.getSelectedFile().toString()); });
        row.add(field, BorderLayout.CENTER); row.add(choose, BorderLayout.EAST); return row;
    }
}
