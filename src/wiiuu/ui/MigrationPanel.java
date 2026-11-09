package wiiuu.ui;

import wiiuu.core.Config;
import wiiuu.core.DataTransfer;
import wiiuu.net.Migration;

import javax.swing.*;
import java.awt.*;
import java.io.Closeable;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

final class MigrationPanel {
    private MigrationPanel() {}

    static void open(Component owner, Config config) {
        String[] choices = {"Move to another drive", "Send to another computer", "Receive from another computer"};
        int choice = JOptionPane.showOptionDialog(owner, "Close games and other WII-UU apps before transferring data.",
                "Storage and migration", JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, choices, choices[0]);
        if (choice < 0) return;
        try {
            if (choice == 1) {
                var addresses = new ArrayList<String>();
                for (var network : NetworkInterface.networkInterfaces().toList()) {
                    if (!network.isUp() || network.isLoopback()) continue;
                    network.inetAddresses().filter(Inet4Address.class::isInstance)
                            .map(java.net.InetAddress::getHostAddress).forEach(addresses::add);
                }
                if (addresses.isEmpty()) throw new IOException("Connect both computers to the same network.");
                String address = (String) JOptionPane.showInputDialog(owner,
                        "Choose this computer's address on the shared network.", "Send WII-UU data",
                        JOptionPane.PLAIN_MESSAGE, null, addresses.toArray(), addresses.get(0));
                if (address != null) run(owner, "Send WII-UU data", true,
                        (code, moving, socket) -> Migration.send(config, address, code, moving, socket));
                return;
            }

            String invitation = choice == 2 ? JOptionPane.showInputDialog(owner, "Paste the migration code from the sending computer.") : null;
            if (choice == 2 && (invitation == null || invitation.isBlank())) return;
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("Choose a drive or parent folder for the new WII-UU data folder");
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (chooser.showOpenDialog(owner) != JFileChooser.APPROVE_OPTION) return;
            String name = JOptionPane.showInputDialog(owner, "Name for the new storage folder", "WII-UU Data");
            if (name == null || name.isBlank()) return;
            if (name.matches(".*[/\\\\:].*") || name.equals(".") || name.equals("..")) throw new IOException("Enter a folder name.");
            Path destination = chooser.getSelectedFile().toPath().resolve(name);
            run(owner, choice == 0 ? "Move WII-UU data" : "Receive WII-UU data", choice == 2,
                    (code, moving, socket) -> {
                        if (invitation != null) Migration.receive(invitation, destination, moving, socket);
                        else {
                            moving.run();
                            DataTransfer.relocate(config, destination);
                        }
                    });
        } catch (Exception e) {
            JOptionPane.showMessageDialog(owner, e.getMessage(), "Storage and migration", JOptionPane.ERROR_MESSAGE);
        }
    }

    static void preview(Component owner) {
        var scene = new MigrationCutscene(true);
        var dialog = new JDialog(SwingUtilities.getWindowAncestor(owner), "Migration cutscene preview",
                Dialog.ModalityType.APPLICATION_MODAL);
        var skip = new JButton("Skip preview");
        skip.addActionListener(e -> dialog.dispose());
        dialog.add(scene, BorderLayout.CENTER);
        dialog.add(skip, BorderLayout.SOUTH);
        fit(dialog, owner);
        scene.complete();
        scene.start();
        var timer = new Timer(1000 / 30, e -> {
            if (scene.elapsed() >= MigrationCutscene.SECONDS) dialog.dispose();
            else scene.repaint();
        });
        timer.start();
        try {
            dialog.setVisible(true);
        } finally {
            timer.stop();
        }
    }

    private static void fit(JDialog dialog, Component owner) {
        dialog.pack();
        Rectangle usable = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        GraphicsConfiguration graphics = owner.getGraphicsConfiguration();
        if (graphics != null) {
            usable = graphics.getBounds();
            Insets edges = Toolkit.getDefaultToolkit().getScreenInsets(graphics);
            usable.width -= edges.left + edges.right;
            usable.height -= edges.top + edges.bottom;
        }
        dialog.setSize(Math.min(dialog.getWidth(), usable.width), Math.min(dialog.getHeight(), usable.height));
        dialog.setLocationRelativeTo(owner);
    }

    @FunctionalInterface
    private interface Transfer {
        void run(java.util.function.Consumer<String> code, Runnable moving,
                 java.util.function.Consumer<Closeable> socket) throws Exception;
    }

    private static void run(Component owner, String title, boolean network, Transfer transfer) {
        JTextField code = new JTextField(40);
        code.setEditable(false);
        code.setVisible(false);
        JLabel status = new JLabel("Preparing…");
        MigrationCutscene scene = new MigrationCutscene(network);
        JButton cancel = new JButton("Cancel");
        cancel.setEnabled(network);
        JButton skip = new JButton("Skip cutscene");
        skip.setEnabled(false);
        JPanel controls = new JPanel();
        controls.add(cancel);
        controls.add(skip);
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));
        for (JComponent item : new JComponent[]{status, code, scene, controls}) {
            if (panel.getComponentCount() != 0) panel.add(Box.createVerticalStrut(8));
            item.setAlignmentX(Component.CENTER_ALIGNMENT);
            panel.add(item);
        }
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(owner), title, Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        dialog.add(panel);
        fit(dialog, owner);

        AtomicReference<Closeable> socket = new AtomicReference<>();
        AtomicBoolean cancelled = new AtomicBoolean();
        cancel.addActionListener(e -> {
            cancelled.set(true);
            close(socket.get());
            cancel.setEnabled(false);
        });
        double[] endAt = {Double.POSITIVE_INFINITY};
        Timer timer = new Timer(1000 / 30, e -> {
            if (scene.elapsed() >= endAt[0]) dialog.dispose();
            else scene.repaint();
        });
        skip.addActionListener(e -> dialog.dispose());
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception {
                transfer.run(value -> SwingUtilities.invokeLater(() -> {
                    status.setText("Copy this code to the receiving computer. It expires in five minutes.");
                    code.setText(value);
                    code.setVisible(true);
                    fit(dialog, owner);
                }), () -> SwingUtilities.invokeLater(() -> {
                    status.setText("Transferring WII-UU data…");
                    code.setVisible(false);
                    scene.start();
                    timer.start();
                }), value -> {
                    socket.set(value);
                    if (cancelled.get()) close(value);
                });
                return null;
            }

            @Override protected void done() {
                try {
                    get();
                    cancel.setEnabled(false);
                    skip.setEnabled(true);
                    scene.complete();
                    status.setText("Transfer finished. You can skip the cutscene.");
                    endAt[0] = MigrationCutscene.endOfLoop(scene.elapsed());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    dialog.dispose();
                } catch (ExecutionException e) {
                    dialog.dispose();
                    if (!cancelled.get()) JOptionPane.showMessageDialog(owner, e.getCause().getMessage(),
                            title, JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
        try {
            dialog.setVisible(true);
        } finally {
            timer.stop();
        }
        if (skip.isEnabled()) {
            String message = code.getText().isEmpty()
                    ? "Data is ready. Quit and reopen WII-UU to use the new location. The original files are kept."
                    : "Data sent. Check the receiving computer.";
            JOptionPane.showMessageDialog(owner, message);
        }
    }

    private static void close(Closeable connection) {
        if (connection != null) {
            try { connection.close(); } catch (IOException ignored) { }
        }
    }
}
