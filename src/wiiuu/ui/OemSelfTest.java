package wiiuu.ui;

import wiiuu.core.Config;
import wiiuu.core.Library;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Factory screen shown before the customer setup guide. */
public final class OemSelfTest {
    private record Check(String name, boolean passed, String detail) {}

    private OemSelfTest() {}

    public static void show(Config config) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JFrame frame = new JFrame("WII-UU self-test");
            frame.setUndecorated(true);
            frame.setExtendedState(JFrame.MAXIMIZED_BOTH);
            frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            JPanel panel = new JPanel(new BorderLayout(16, 16));
            panel.setBorder(BorderFactory.createEmptyBorder(28, 32, 28, 32));
            JLabel title = new JLabel(config.get("oem.model", "WII-UU") + "  |  Factory self-test");
            title.setFont(title.getFont().deriveFont(Font.BOLD, 28f));
            panel.add(title, BorderLayout.NORTH);
            JTextArea report = new JTextArea("Running checks…");
            report.setEditable(false);
            report.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 16));
            report.setMargin(new Insets(12, 12, 12, 12));
            panel.add(new JScrollPane(report), BorderLayout.CENTER);
            JButton power = new JButton("Power off");
            power.setEnabled(false);
            power.addActionListener(event -> System.exit(11));
            JLabel hint = new JLabel("Waiting for self-test results");
            JPanel bottom = new JPanel(new BorderLayout(12, 0));
            bottom.add(hint, BorderLayout.CENTER);
            bottom.add(power, BorderLayout.EAST);
            panel.add(bottom, BorderLayout.SOUTH);
            frame.getRootPane().setDefaultButton(power);
            frame.setContentPane(panel);
            frame.setVisible(true);

            new SwingWorker<String, Void>() {
                @Override protected String doInBackground() {
                    return check(config);
                }

                @Override protected void done() {
                    try {
                        report.setText(get());
                    } catch (Exception e) {
                        report.setText("Self-test could not finish. Check the console session log.");
                    }
                    report.setCaretPosition(0);
                    if ("1".equals(System.getenv("WIIUU_POWEROFF_FAILED"))) {
                        hint.setText("Power off failed. Press Power off to try again.");
                    } else {
                        hint.setText("The setup guide opens the next time the console boots.");
                    }
                    power.setEnabled(true);
                    power.requestFocusInWindow();
                }
            }.execute();
        });
    }

    private static String check(Config config) {
        long started = System.nanoTime();
        List<Check> checks = new ArrayList<>();
        checks.add(new Check("Java runtime", Runtime.version().feature() >= 17, Runtime.version().toString()));
        checks.add(new Check("Setup guide", config.getBool("ui.setupNext", false), "first-run request"));
        checks.add(new Check("Virtual controller device", Files.exists(Path.of("/dev/uinput")), "/dev/uinput"));
        checks.add(new Check("Screen streaming", installed("ffmpeg"), "ffmpeg"));
        checks.add(new Check("Network Manager", works("systemctl", "is-active", "--quiet", "NetworkManager"), "service active"));
        try {
            Path probe = Files.createTempFile(config.home(), ".oem-check-", null);
            Files.delete(probe);
            long free = Files.getFileStore(config.home()).getUsableSpace();
            checks.add(new Check("Data storage", true, "%.1f GiB free".formatted(free / (1024.0 * 1024 * 1024))));
        } catch (java.io.IOException e) {
            checks.add(new Check("Data storage", false, "cannot write to the data folder"));
        }
        var library = new Library(config).rescan();
        long passed = checks.stream().filter(Check::passed).count();
        StringBuilder result = new StringBuilder();
        for (Check c : checks) {
            result.append("%-5s  %s  (%s)%n".formatted(c.passed() ? "PASS" : "FAIL", c.name(), c.detail()));
        }
        long systems = library.games().values().stream().filter(games -> !games.isEmpty()).count();
        result.append("%n%d of %d checks passed in %.1f s%n%d games across %d systems%n%d logical CPU cores%n"
                .formatted(passed, checks.size(), (System.nanoTime() - started) / 1_000_000_000.0,
                        library.total(), systems, Runtime.getRuntime().availableProcessors()));
        return result.toString();
    }

    private static boolean installed(String name) {
        String path = System.getenv().getOrDefault("PATH", "");
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (!dir.isEmpty() && Files.isExecutable(Path.of(dir, name))) return true;
        }
        return false;
    }

    private static boolean works(String... command) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            return process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (java.io.IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }
}
