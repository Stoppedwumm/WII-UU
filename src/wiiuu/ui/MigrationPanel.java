package wiiuu.ui;

import wiiuu.core.Config;
import wiiuu.core.DataTransfer;
import wiiuu.net.Migration;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.geom.Rectangle2D;
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

    static void open(MenuView owner, Config config) {
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

    static void preview(MenuView owner) {
        Playback playback = new Playback(true, true);
        playback.start();
        playback.complete();
        owner.startMigration(playback);
    }

    @FunctionalInterface
    private interface Transfer {
        void run(java.util.function.Consumer<String> code, Runnable moving,
                 java.util.function.Consumer<Closeable> socket) throws Exception;
    }

    private static void run(MenuView owner, String title, boolean network, Transfer transfer) {
        Playback playback = new Playback(network, false);
        owner.startMigration(playback);
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception {
                transfer.run(value -> SwingUtilities.invokeLater(() -> playback.invitation = value),
                        () -> SwingUtilities.invokeLater(playback::start), playback::connection);
                return null;
            }

            @Override protected void done() {
                try {
                    get();
                    playback.complete();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    owner.endMigration(playback, title + " interrupted.");
                } catch (ExecutionException e) {
                    String message = playback.cancelled.get() ? "Transfer cancelled." : title + ": " + e.getCause().getMessage();
                    owner.endMigration(playback, message);
                }
            }
        }.execute();
    }

    /** On-screen state, updated on the event thread except for the worker's socket registration. */
    static final class Playback {
        private static final int WIDTH = MigrationCutscene.WIDTH;
        private static final int HEIGHT = MigrationCutscene.HEIGHT + 55;
        private static final Rectangle2D PRIMARY = new Rectangle2D.Float(385, 399, 155, 35);
        private static final Rectangle2D CANCEL = new Rectangle2D.Float(553, 399, 138, 35);
        private final MigrationCutscene scene;
        private final String id = java.util.UUID.randomUUID().toString();
        private final boolean network, preview;
        private final AtomicReference<Closeable> socket = new AtomicReference<>();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private boolean started, complete;
        private double endAt = Double.POSITIVE_INFINITY;
        private String invitation;
        private boolean copied;

        Playback(boolean network, boolean preview) {
            this.network = network;
            this.preview = preview;
            this.scene = new MigrationCutscene(network);
        }

        private void connection(Closeable value) {
            socket.set(value);
            if (cancelled.get()) close(value);
        }

        void start() {
            started = true;
            scene.start();
        }

        void complete() {
            if (!started) start();
            complete = true;
            scene.complete();
            endAt = MigrationCutscene.endOfLoop(scene.elapsed());
        }

        boolean finished() {
            return complete && scene.elapsed() >= endAt;
        }

        String result() {
            if (preview) return null;
            return invitation != null ? "Data sent. Check the receiving computer."
                    : "Data is ready. Quit and reopen WII-UU to use the new location. The original files are kept.";
        }

        wiiuu.net.GamepadServer.Idle gamepadState() {
            int chapter = scene.chapter();
            String line = started ? scene.caption(chapter)
                    : invitation == null ? "Preparing migration…" : "Waiting for the receiving computer.";
            return new wiiuu.net.GamepadServer.Idle(preview ? "Migration preview" : "Migration", 0,
                    line, started ? chapter * 2 + (complete ? 1 : 0) : invitation == null ? -2 : -3,
                    complete ? "happy" : "focus", null, false, id, complete);
        }

        void skip(MenuView owner, String requestedId) {
            if (complete && id.equals(requestedId)) confirm(owner);
        }

        void confirm(MenuView owner) {
            if (complete) owner.endMigration(this, result());
            else if (canCopy()) {
                try {
                    Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(invitation), null);
                    copied = true;
                } catch (IllegalStateException e) {
                    copied = false;
                }
            }
        }

        void back(MenuView owner) {
            if (complete) confirm(owner);
            else if (canCancel()) {
                cancelled.set(true);
                close(socket.get());
            }
        }

        void click(MenuView owner, int x, int y) {
            double scale = scale(owner.getWidth(), owner.getHeight());
            if (scale <= 0) return;
            double px = (x - (owner.getWidth() - WIDTH * scale) / 2) / scale;
            double py = (y - (owner.getHeight() - HEIGHT * scale) / 2) / scale;
            if (PRIMARY.contains(px, py)) confirm(owner);
            else if (CANCEL.contains(px, py) && canCancel()) back(owner);
        }

        private boolean canCopy() {
            return invitation != null && !started && !cancelled.get();
        }

        private boolean canCancel() {
            return network && !preview && !complete && !cancelled.get();
        }

        void paint(Graphics2D g, int w, int h) {
            g.setColor(new Color(0x101c29));
            g.fillRect(0, 0, w, h);
            double scale = scale(w, h);
            g.translate((w - WIDTH * scale) / 2, (h - HEIGHT * scale) / 2);
            g.scale(scale, scale);
            if (started) scene.paint(g);
            else paintWaiting(g);
            g.setFont(MenuView.font(Font.BOLD, 14));
            g.setColor(new Color(0xaed2e7));
            String status = preview ? "Developer preview" : complete ? "Transfer finished"
                    : cancelled.get() ? "Cancelling transfer…" : started ? "Transferring WII-UU data…" : "Migration";
            g.drawString(status, 30, 422);
            if (complete) button(g, PRIMARY, preview ? "Skip preview" : "Skip cutscene");
            else if (canCopy()) button(g, PRIMARY, copied ? "Code copied" : "Copy code");
            if (canCancel()) button(g, CANCEL, "Cancel transfer");
        }

        private void paintWaiting(Graphics2D g) {
            g.setColor(new Color(0x203348));
            g.fillRect(0, 0, WIDTH, MigrationCutscene.HEIGHT);
            g.setColor(Color.WHITE);
            g.setFont(MenuView.font(Font.BOLD, 27));
            g.drawString(invitation == null ? "Preparing migration…" : "Waiting for the receiving computer", 30, 92);
            if (invitation == null) return;
            g.setFont(MenuView.font(Font.PLAIN, 16));
            g.drawString("Copy this code to the receiving computer. It expires in five minutes.", 30, 130);
            g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 16));
            for (int i = 0; i < invitation.length(); i += 60) {
                g.drawString(invitation.substring(i, Math.min(i + 60, invitation.length())), 30, 183 + i / 60 * 25);
            }
        }

        private static void button(Graphics2D g, Rectangle2D bounds, String text) {
            int x = (int) bounds.getX(), y = (int) bounds.getY(), w = (int) bounds.getWidth();
            g.setColor(new Color(0x294b62));
            g.fillRoundRect(x, y, w, (int) bounds.getHeight(), 16, 16);
            g.setColor(Color.WHITE);
            g.drawString(text, x + (w - g.getFontMetrics().stringWidth(text)) / 2, y + 23);
        }

        private static double scale(int w, int h) {
            return Math.min(w / (double) WIDTH, h / (double) HEIGHT);
        }
    }

    private static void close(Closeable connection) {
        if (connection != null) {
            try { connection.close(); } catch (IOException ignored) { }
        }
    }
}
