package choccymilk;

import java.awt.*;
import java.awt.event.*;
import java.nio.file.*;
import java.util.*;
import javax.swing.*;
import wiiuu.core.*;
import wiiuu.input.ChoccyKeys;

/** Standalone settings: edits are staged until Save, including captured keyboard assignments. */
final class SettingsDialog {
    private SettingsDialog() {}
    static boolean show(JFrame owner, Config config, java.util.function.BooleanSupplier gameRunning) {
        JDialog dialog = new JDialog(owner, "Choccy Milk settings", true);
        JTabbedPane tabs = new JTabbedPane();
        JPanel general = new JPanel(new GridLayout(0, 1, 8, 8));
        general.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        JComboBox<String> theme = new JComboBox<>(new String[]{"auto", "cocoa", "milk"});
        theme.setSelectedItem(config.get("choccy.theme", "auto"));
        JCheckBox intro = new JCheckBox("Play startup animation", config.getBool("choccy.intro", true));
        JTextField roms = new JTextField(config.romBase().toString(), 30);
        JTextField bios = new JTextField(config.get("choccy.systemDir", ""), 30);
        general.add(new JLabel("Theme")); general.add(theme); general.add(intro);
        general.add(new JLabel("Game library folder (shared with WII-UU)")); general.add(folder(roms));
        general.add(new JLabel("BIOS / system folder (blank: automatic)")); general.add(folder(bios));
        general.add(new JLabel("Game folders and BIOS changes apply to the next game."));
        tabs.addTab("General", general);
        tabs.addTab("Cores", new wiiuu.engine.CoreManagerPanel(config, gameRunning));

        Map<String, Integer> keys = new LinkedHashMap<>(ChoccyKeys.load(config));
        JPanel keyboard = new JPanel(new GridLayout(0, 3, 8, 5));
        keyboard.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        Map<String, JButton> keyButtons = new LinkedHashMap<>();
        for (String action : keys.keySet()) {
            keyboard.add(new JLabel(action));
            JButton capture = new JButton(keyName(keys.get(action)));
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
            JButton clear = new JButton("Unbind");
            clear.addActionListener(e -> { keys.put(action, 0); capture.setText("Unbound"); });
            keyboard.add(clear);
        }
        JButton reset = new JButton("Restore default keys");
        reset.addActionListener(e -> { keys.clear(); keys.putAll(ChoccyKeys.defaults()); keyButtons.forEach((a, button) -> button.setText(keyName(keys.get(a)))); });
        JPanel keyboardPage = new JPanel(new BorderLayout(8, 8));
        keyboardPage.add(new JLabel("Click an assignment, then press a key. F1 always opens Settings."), BorderLayout.NORTH);
        keyboardPage.add(new JScrollPane(keyboard), BorderLayout.CENTER); keyboardPage.add(reset, BorderLayout.SOUTH);
        tabs.addTab("Keyboard", keyboardPage);

        JPanel online = new JPanel(new GridLayout(0, 1, 8, 8));
        online.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        JCheckBox wfc = new JCheckBox("Enable DS online (WFC replacement services)", DsOnline.enabled(config));
        JTextField dns = new JTextField(DsOnline.dns(config), 25);
        JTextField name = new JTextField(DsOnline.username(config), 12);
        JCheckBox unique = new JCheckBox("Derive DS MAC from this profile name", config.getBool("choccy.nds.online.uniqueMac", false));
        online.add(wfc); online.add(new JLabel("DNS override (IPv4; blank uses the melonDS DS core's default service)")); online.add(dns);
        online.add(new JLabel("DS profile name (1–10 letters, digits, _ or -)")); online.add(name); online.add(unique);
        online.add(new JLabel("Changing the MAC/profile may affect existing online profiles and Pokémon saves."));
        online.add(new JLabel("Uses melonDS DS indirect networking. Restart the game after changes."));
        online.add(new JLabel("In the game's WFC setup, connect to melonAP, then use its online/friend-code menu."));
        online.add(new JLabel("Game/server compatibility and SSL patches depend on the replacement service."));
        online.add(new JLabel("These settings are shared with WII-UU. Local wireless / Download Play is separate."));
        tabs.addTab("DS online", online);

        JPanel picture = new JPanel(new BorderLayout());
        JComboBox<GameSystem> console = new JComboBox<>(Systems.ALL.stream().filter(Cores::has).toArray(GameSystem[]::new));
        console.setRenderer(new DefaultListCellRenderer() { @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean s, boolean f) {
            return super.getListCellRendererComponent(l, v instanceof GameSystem gs ? gs.name() : v, i, s, f);
        }});
        Map<String, String> filters = new HashMap<>(); Map<String, Boolean> wides = new HashMap<>(), blends = new HashMap<>();
        for (GameSystem gs : Systems.ALL) { filters.put(gs.id(), ChoccyPicture.upscale(config, gs.id())); wides.put(gs.id(), ChoccyPicture.widescreen(config, gs.id())); blends.put(gs.id(), ChoccyPicture.blend(config, gs.id())); }
        JPanel options = new JPanel(new GridLayout(0, 1, 8, 8));
        Runnable rebuild = () -> {
            GameSystem gs = (GameSystem) console.getSelectedItem(); if (gs == null) return;
            options.removeAll();
            var ups = ChoccyPicture.upscalers(gs.id());
            JComboBox<String> up = new JComboBox<>(ups.stream().map(x -> x[1]).toArray(String[]::new));
            for (int i = 0; i < ups.size(); i++) if (ups.get(i)[0].equals(filters.get(gs.id()))) up.setSelectedIndex(i);
            up.addActionListener(e -> filters.put(gs.id(), ups.get(up.getSelectedIndex())[0]));
            JCheckBox wide = new JCheckBox("Widescreen", wides.get(gs.id())); wide.addActionListener(e -> wides.put(gs.id(), wide.isSelected()));
            JCheckBox blend = new JCheckBox("Blend frames", blends.get(gs.id())); blend.addActionListener(e -> blends.put(gs.id(), blend.isSelected()));
            options.add(new JLabel("Upscaling")); options.add(up); options.add(wide); options.add(blend);
            options.add(new JLabel("Core rendering changes take effect on the next game.")); options.revalidate(); options.repaint();
        };
        console.addActionListener(e -> rebuild.run()); rebuild.run();
        picture.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16)); picture.add(console, BorderLayout.NORTH); picture.add(options, BorderLayout.CENTER);
        tabs.addTab("Picture", picture);

        boolean[] saved = {false};
        JButton save = new JButton("Save"); JButton cancel = new JButton("Cancel");
        save.addActionListener(e -> {
            try {
                ChoccyKeys.validate(keys);
                if (!DsOnline.validDns(dns.getText().trim())) throw new IllegalArgumentException("DS DNS must be an IPv4 address, or blank");
                if (!DsOnline.validName(name.getText().trim())) throw new IllegalArgumentException("DS profile name needs 1–10 letters, digits, _ or -");
                Path romPath = Path.of(roms.getText().trim());
                if (roms.getText().isBlank() || Files.exists(romPath) && !Files.isDirectory(romPath)) throw new IllegalArgumentException("Choose a game library folder");
                if (!bios.getText().isBlank() && !Files.isDirectory(Path.of(bios.getText().trim()))) throw new IllegalArgumentException("Choose an existing BIOS folder, or leave it blank");
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
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT)); buttons.add(new JLabel("A running game continues while Settings is open.")); buttons.add(cancel); buttons.add(save);
        dialog.add(tabs, BorderLayout.CENTER); dialog.add(buttons, BorderLayout.SOUTH);
        dialog.setMinimumSize(new Dimension(800, 540)); dialog.setSize(880, 620); dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true); return saved[0];
    }
    private static String keyName(int code) { return code == 0 ? "Unbound" : KeyEvent.getKeyText(code); }
    private static JPanel folder(JTextField field) {
        JPanel row = new JPanel(new BorderLayout(8, 0)); JButton choose = new JButton("Browse…");
        choose.addActionListener(e -> { JFileChooser picker = new JFileChooser(); picker.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (picker.showOpenDialog(row) == JFileChooser.APPROVE_OPTION) field.setText(picker.getSelectedFile().toString()); });
        row.add(field, BorderLayout.CENTER); row.add(choose, BorderLayout.EAST); return row;
    }
}
