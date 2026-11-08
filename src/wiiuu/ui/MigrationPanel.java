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
        TransferAnimation animation = new TransferAnimation(network);
        JButton cancel = new JButton("Cancel");
        cancel.setEnabled(network);
        JPanel panel = new JPanel(new GridLayout(0, 1, 0, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));
        panel.add(status);
        panel.add(code);
        panel.add(animation);
        panel.add(cancel);
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(owner), title, Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        dialog.add(panel);
        dialog.pack();
        dialog.setLocationRelativeTo(owner);

        AtomicReference<Closeable> socket = new AtomicReference<>();
        AtomicBoolean cancelled = new AtomicBoolean();
        cancel.addActionListener(e -> {
            cancelled.set(true);
            close(socket.get());
            cancel.setEnabled(false);
        });
        Timer timer = new Timer(60, e -> animation.advance());
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception {
                transfer.run(value -> SwingUtilities.invokeLater(() -> {
                    status.setText("Copy this code to the receiving computer. It expires in five minutes.");
                    code.setText(value);
                    code.setVisible(true);
                    dialog.pack();
                }), () -> SwingUtilities.invokeLater(() -> {
                    status.setText("Transferring WII-UU data…");
                    timer.start();
                }), value -> {
                    socket.set(value);
                    if (cancelled.get()) close(value);
                });
                return null;
            }

            @Override protected void done() {
                timer.stop();
                dialog.dispose();
                try {
                    get();
                    String message = code.isVisible() ? "Data sent. Check the receiving computer."
                            : "Data is ready. Quit and reopen WII-UU to use the new location. The original files are kept.";
                    JOptionPane.showMessageDialog(owner, message);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    if (!cancelled.get()) JOptionPane.showMessageDialog(owner, e.getCause().getMessage(),
                            title, JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
        dialog.setVisible(true);
    }

    private static final class TransferAnimation extends JComponent {
        private final boolean network;
        private int frame;

        TransferAnimation(boolean network) {
            this.network = network;
            setPreferredSize(new Dimension(410, 90));
        }

        void advance() {
            frame = (frame + 1) % 54;
            repaint();
        }

        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int right = getWidth() - 86;
                endpoint(g, 12, network ? "Computer A" : "Old drive");
                endpoint(g, right, network ? "Computer B" : "New drive");
                int start = 89, end = right - 62;
                g.setColor(new Color(0xa1b1bb));
                g.drawLine(start, 31, end + 53, 31);
                int x = start + (end - start) * Math.min(frame, 45) / 45;
                g.setColor(new Color(0x007ea9));
                g.fillRoundRect(x, 19, 56, 24, 14, 14);
                g.setColor(Color.WHITE);
                g.drawString("WII-UU", x + 7, 35);
            } finally {
                g.dispose();
            }
        }

        private void endpoint(Graphics2D g, int x, String label) {
            g.setColor(new Color(0x263848));
            g.fillRoundRect(x + 6, 8, 59, 42, 8, 8);
            g.setColor(new Color(0xc8eaf5));
            g.fillRect(x + 11, 13, 49, 32);
            g.setColor(new Color(0x263848));
            g.fillRect(x + 31, 50, 9, 8);
            g.fillRect(x + 21, 58, 29, 3);
            g.drawString(label, x, 79);
        }
    }

    private static void close(Closeable connection) {
        if (connection != null) {
            try { connection.close(); } catch (IOException ignored) { }
        }
    }
}
