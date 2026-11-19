import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import wiiuu.ui.AnimatedMascot;
import wiiuu.ui.AudioVisualizer;
import wiiuu.ui.MenuPainter;
import wiiuu.ui.MenuPalette;
import wiiuu.ui.UiFonts;

/** Synthetic audio and a branded mascot; needs only the standalone Java 17 UI JAR. */
public final class AnimatedDemo extends JPanel {
    private final AtomicLong millis = new AtomicLong();
    private final UiFonts fonts = new UiFonts(Font.SANS_SERIF);
    private final AnimatedMascot mascot = new AnimatedMascot(
            () -> new AnimatedMascot.Style("MY-", "APP", new Color(0x00A8E8), false, fonts::font), millis::get);
    private final AudioVisualizer visualizer = new AudioVisualizer(48000, () -> millis.get() * 1_000_000);
    private final float[] audio = new float[AudioVisualizer.SAMPLE_COUNT];
    private final boolean full;

    public AnimatedDemo(boolean full) {
        this.full = full;
        setPreferredSize(new Dimension(900, 600));
        mascot.say("Hello! Your app supplies my appearance, messages and animation clock.");
    }

    private void tick(long now) {
        millis.set(now);
        mascot.look(650, 180);
        for (int i = 0; i < audio.length; i++) {
            double t = now / 1000.0 + i / 48000.0;
            audio[i] = (float) (.4 * Math.sin(t * Math.PI * 2 * 100) + .2 * Math.sin(t * Math.PI * 2 * 1800));
        }
        visualizer.update(audio, audio.length);
        repaint();
    }

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            if (full) {
                visualizer.paintFull(g, getWidth(), getHeight(), "Synthetic preview", "Host-provided mono audio at 48 kHz",
                        "MY-", "APP", new Color(0x00A8E8), "Close the window to leave");
            } else {
                g.drawImage(MenuPainter.background(getWidth(), getHeight(), MenuPalette.colours(false)), 0, 0, null);
                g.setFont(fonts.font(Font.BOLD, 30));
                g.setColor(new Color(0x3C4043));
                g.drawString("An animated helper for your app", 50, 80);
                visualizer.paintAmbient(g, getWidth(), getHeight(), 100, new Color(0x00A8E8));
                mascot.paint(g, 180, 400, 50, false, 430);
            }
        } finally {
            g.dispose();
        }
    }

    public static void main(String[] args) throws Exception {
        boolean full = java.util.Arrays.asList(args).contains("--visualizer");
        if (args.length >= 2 && args[0].equals("--snapshot")) {
            AnimatedDemo demo = new AnimatedDemo(full);
            demo.setSize(demo.getPreferredSize());
            for (int i = 0; i < 12; i++) demo.tick(2500);
            BufferedImage image = new BufferedImage(900, 600, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics();
            demo.paint(g);
            g.dispose();
            ImageIO.write(image, "png", Path.of(args[1]).toFile());
            return;
        }
        SwingUtilities.invokeLater(() -> {
            AnimatedDemo demo = new AnimatedDemo(full);
            JFrame frame = new JFrame("Shared animation example");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setContentPane(demo);
            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
            long start = System.currentTimeMillis();
            new Timer(33, e -> demo.tick(System.currentTimeMillis() - start)).start();
        });
    }
}
