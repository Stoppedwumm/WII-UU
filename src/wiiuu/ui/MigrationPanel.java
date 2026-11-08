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
        var animation = new TransferAnimation(true);
        var timer = new Timer(1000 / 60, e -> animation.repaint());
        animation.start();
        timer.start();
        try {
            JOptionPane.showMessageDialog(owner, animation, "Migration animation preview", JOptionPane.PLAIN_MESSAGE);
        } finally {
            timer.stop();
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
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));
        for (JComponent item : new JComponent[]{status, code, animation, cancel}) {
            if (panel.getComponentCount() != 0) panel.add(Box.createVerticalStrut(8));
            item.setAlignmentX(Component.CENTER_ALIGNMENT);
            panel.add(item);
        }
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
        Timer timer = new Timer(1000 / 60, e -> animation.repaint());
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception {
                transfer.run(value -> SwingUtilities.invokeLater(() -> {
                    status.setText("Copy this code to the receiving computer. It expires in five minutes.");
                    code.setText(value);
                    code.setVisible(true);
                    dialog.pack();
                }), () -> SwingUtilities.invokeLater(() -> {
                    status.setText("Transferring WII-UU data…");
                    animation.start();
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
        private static final double WALK_START = 0.35, TAKEOFF = 0.95, LAND = 2.1, ARRIVE = 2.75, LOOP = 3.7;
        private final boolean network;
        private final Mascot mascot = new Mascot();
        private long started;

        TransferAnimation(boolean network) {
            this.network = network;
            setPreferredSize(new Dimension(460, 175));
        }

        void start() {
            started = System.nanoTime();
        }

        @Override protected void paintComponent(Graphics graphics) {
            double time = started == 0 ? 0 : ((System.nanoTime() - started) / 1e9) % LOOP;
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                float scale = Math.min(getWidth() / 460f, getHeight() / 175f);
                g.translate((getWidth() - 460 * scale) / 2, (getHeight() - 175 * scale) / 2);
                g.scale(scale, scale);
                g.setColor(MenuView.dark ? new Color(0x192a39) : new Color(0xeaf4f8));
                g.fillRoundRect(0, 0, 460, 175, 24, 24);

                float x, lift = 0, stride = 0;
                if (time < TAKEOFF) {
                    x = lerp(72, 111, phase(time, WALK_START, TAKEOFF));
                    if (time >= WALK_START) stride = (float) Math.sin((time - WALK_START) * 28);
                } else if (time < LAND) {
                    float air = phase(time, TAKEOFF, LAND);
                    x = lerp(111, 349, air);
                    lift = (float) Math.sin(Math.PI * air) * 39;
                    stride = 0.75f;
                } else {
                    x = lerp(349, 388, phase(time, LAND, ARRIVE));
                    if (time < ARRIVE) stride = (float) Math.sin((time - LAND) * 28);
                    else if (time < ARRIVE + 0.35) lift = (float) Math.sin(Math.PI * phase(time, ARRIVE, ARRIVE + 0.35)) * 9;
                }
                endpoint(g, 72, network ? "Computer A" : "Old drive", time < LAND);
                endpoint(g, 388, network ? "Computer B" : "New drive", time >= LAND);
                if (time >= TAKEOFF && time < LAND) {
                    g.setColor(new Color(0x6690a0));
                    for (int i = 1; i <= 4; i++) g.fillOval((int) x - 30 - i * 10, (int) (92 - lift) + i * 2, 5, 3);
                }
                mascot.look(405, 85);
                mascot.paintWalking(g, x, 99 - lift - Math.abs(stride) * 2, 23, stride);
            } finally {
                g.dispose();
            }
        }

        private static float phase(double time, double from, double to) {
            return (float) Math.max(0, Math.min(1, (time - from) / (to - from)));
        }

        private static float lerp(float from, float to, float phase) {
            return from + (to - from) * phase;
        }

        private void endpoint(Graphics2D g, int x, String label, boolean active) {
            g.setColor(new Color(0x263848));
            g.fillRoundRect(x - 43, 99, 86, 48, 9, 9);
            g.setColor(new Color(active ? 0x74d7f4 : 0xb8cbd2));
            g.fillRoundRect(x - 36, 105, 72, 35, 5, 5);
            g.setColor(new Color(0x263848));
            g.fillRect(x - 5, 147, 10, 7);
            g.fillRoundRect(x - 18, 153, 36, 4, 4, 4);
            g.setColor(MenuView.dark ? Color.WHITE : new Color(0x263848));
            g.setFont(MenuView.font(Font.BOLD, 11));
            g.drawString(label, x - g.getFontMetrics().stringWidth(label) / 2, 169);
        }
    }

    private static void close(Closeable connection) {
        if (connection != null) {
            try { connection.close(); } catch (IOException ignored) { }
        }
    }
}
