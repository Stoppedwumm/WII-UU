package wiiuu.engine;

import java.awt.*;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.BooleanSupplier;
import javax.swing.*;
import wiiuu.core.*;

/** Shared core manager; discovery and downloads never block the UI thread. */
public final class CoreManagerPanel extends JPanel {
    private final CoreManager manager;
    private final BooleanSupplier gameRunning;
    private final JComboBox<GameSystem> console;
    private final JComboBox<CoreManager.Entry> choices = new JComboBox<>();
    private final JLabel current = new JLabel(" "), status = new JLabel(" ");
    private final JTextField location = new JTextField();
    private final JButton refresh = new JButton("Refresh"), download = new JButton("Download / reinstall"), use = new JButton("Use selected core"), automatic = new JButton("Use Automatic");
    private boolean busy;

    public CoreManagerPanel(Config config, BooleanSupplier gameRunning) {
        super(new BorderLayout(10, 10));
        this.manager = new CoreManager(config); this.gameRunning = gameRunning;
        setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        console = new JComboBox<>(Systems.ALL.stream().filter(s -> !CoreManager.catalogue(s.id()).isEmpty()).toArray(GameSystem[]::new));
        console.setRenderer(new DefaultListCellRenderer() { @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
            return super.getListCellRendererComponent(list, value instanceof GameSystem system ? system.name() : value, index, selected, focus);
        }});
        JPanel fields = new JPanel(new GridLayout(0, 1, 6, 6));
        fields.add(new JLabel("Console")); fields.add(console); fields.add(current);
        JCheckBox autoDownload = new JCheckBox("Download missing cores automatically on launch", config.getBool("retroarch.download", true));
        autoDownload.addActionListener(e -> { config.set("retroarch.download", Boolean.toString(autoDownload.isSelected())); config.save(); });
        fields.add(autoDownload);
        fields.add(new JLabel("Cores available from the libretro buildbot")); fields.add(choices);
        location.setEditable(false); fields.add(location);
        fields.add(new JLabel("Selections are shared with WII-UU / standalone Choccy Milk and apply to the next game."));
        fields.add(new JLabel("Core availability varies by platform. Choccy Milk needs software rendering."));
        fields.add(new JLabel("DS online requires melonDS DS. Other cores may change picture settings or save-state compatibility."));
        fields.add(new JLabel("Stop the running game before downloading. Game saves and BIOS files are kept."));
        fields.add(new JLabel("Use selected core applies immediately; closing this window does not undo it."));
        add(fields, BorderLayout.CENTER);
        JPanel footer = new JPanel(new BorderLayout()); JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        for (JButton button : List.of(refresh, download, use, automatic)) buttons.add(button);
        footer.add(buttons, BorderLayout.NORTH); footer.add(status, BorderLayout.SOUTH); add(footer, BorderLayout.SOUTH);
        console.addActionListener(e -> refresh()); choices.addActionListener(e -> showLocation());
        refresh.addActionListener(e -> refresh());
        automatic.addActionListener(e -> { try { manager.select(system(), ""); updateCurrent(); status.setText("Automatic selected for the next game"); } catch (IllegalArgumentException ex) { status.setText(ex.getMessage()); } });
        use.addActionListener(e -> {
            CoreManager.Entry entry = (CoreManager.Entry) choices.getSelectedItem(); if (entry == null) return;
            try {
                manager.select(system(), entry.id()); updateCurrent();
                status.setText(entry.installed() == null ? "Selected. Download it now, or allow automatic downloads on next launch." : "Selected for the next game");
            } catch (IllegalArgumentException ex) { status.setText(ex.getMessage()); }
        });
        download.addActionListener(e -> {
            if (gameRunning.getAsBoolean()) { status.setText("Stop the running game before downloading or reinstalling a core"); return; }
            CoreManager.Entry entry = (CoreManager.Entry) choices.getSelectedItem(); if (entry == null) return;
            String system = system();
            work("Downloading " + entry.id() + "…", () -> manager.download(system, entry.id()), path -> {
                if (path == null) status.setText("Download unavailable or failed. Try another core, or check your connection and logs.");
                else { status.setText("Installed " + entry.id() + ". Choose Use selected core to enable it."); refreshEntries(entry.id()); }
            });
        });
        refresh();
    }
    private String system() { return ((GameSystem) console.getSelectedItem()).id(); }
    private void updateCurrent() { String selected = manager.selected(system()); current.setText("Using: " + (selected.isEmpty() ? "Automatic" : selected)); }
    private void showLocation() {
        CoreManager.Entry entry = (CoreManager.Entry) choices.getSelectedItem();
        location.setText(entry == null || entry.installed() == null ? "Not installed" : entry.installed().toString());
        location.setCaretPosition(0);
    }
    private void refresh() { if (busy || console.getSelectedItem() == null) return; updateCurrent(); refreshEntries(manager.selected(system())); }
    private void refreshEntries(String preferred) {
        String selectedSystem = system();
        work("Looking for installed cores…", () -> manager.entries(selectedSystem), entries -> {
            choices.removeAllItems();
            for (CoreManager.Entry entry : entries) { choices.addItem(entry); if (entry.id().equals(preferred)) choices.setSelectedItem(entry); }
            status.setText("Ready. Downloads install into your Choccy Milk core folder."); showLocation();
        });
    }
    private void enabled(boolean enabled) {
        for (JComponent component : List.of(console, choices, refresh, download, use, automatic)) component.setEnabled(enabled);
    }
    private <T> void work(String message, Callable<T> task, java.util.function.Consumer<T> done) {
        if (busy) return; busy = true; enabled(false); status.setText(message);
        new SwingWorker<T, Void>() {
            @Override protected T doInBackground() throws Exception { return task.call(); }
            @Override protected void done() {
                busy = false; enabled(true);
                try { done.accept(get()); } catch (Exception e) { status.setText("Core operation failed. Check the app log and try again."); }
            }
        }.execute();
    }
    public static void open(Component parent, Config config, BooleanSupplier gameRunning) {
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(parent), "Choccy Milk cores", Dialog.ModalityType.APPLICATION_MODAL);
        dialog.add(new CoreManagerPanel(config, gameRunning)); dialog.setSize(900, 550); dialog.setLocationRelativeTo(parent); dialog.setVisible(true);
    }
}
