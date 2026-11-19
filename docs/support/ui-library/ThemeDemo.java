import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import wiiuu.ui.MenuPalette;
import wiiuu.ui.MilkIntro;
import wiiuu.ui.MilkTheme;

/** Java 17 example using only WII-UU-UI; no emulator or launcher dependencies. */
public final class ThemeDemo extends JPanel {
    private final MilkTheme theme = MilkTheme.COCOA;
    private final MilkIntro intro = new MilkIntro(theme, "My desktop app", "Powered by WII-UU UI", 0);
    private long elapsed;

    public ThemeDemo() {
        setPreferredSize(new Dimension(900, 600));
    }

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            theme.paintPage(g, getWidth(), getHeight(), 80, 1, elapsed);
            theme.shadow(g, 40, 160, getWidth() - 80, 70, 24, 1);
            theme.pick(g, 40, 160, getWidth() - 80, 70, 1, true);
            g.setColor(theme.pickText);
            g.setFont(MilkTheme.font(Font.BOLD, 24));
            g.drawString("A shared theme, panel and intro", 65, 204);
            intro.paint(g, getWidth(), getHeight(), elapsed);
        } finally {
            g.dispose();
        }
    }

    public static void main(String[] args) throws Exception {
        // Theme files can be evaluated without creating a window.
        var custom = MenuPalette.evaluate("theme \"Ocean\"\nbase dark\naccent = #00a8e8", "custom", true);
        System.out.println("Loaded palette: " + custom.name());
        if (args.length == 2 && args[0].equals("--snapshot")) {
            ThemeDemo demo = new ThemeDemo();
            demo.setSize(demo.getPreferredSize());
            demo.elapsed = 2600;
            BufferedImage image = new BufferedImage(900, 600, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = image.createGraphics();
            demo.paint(g);
            g.dispose();
            ImageIO.write(image, "png", Path.of(args[1]).toFile());
            return;
        }
        SwingUtilities.invokeLater(() -> {
            ThemeDemo demo = new ThemeDemo();
            JFrame frame = new JFrame("Shared UI example");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setIconImages(MilkTheme.icons());
            frame.setContentPane(demo);
            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
            long start = System.currentTimeMillis();
            new Timer(16, e -> {
                demo.elapsed = System.currentTimeMillis() - start;
                demo.repaint();
            }).start();
        });
    }
}
