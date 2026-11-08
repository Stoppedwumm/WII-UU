import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import wiiuu.engine.GameScreen;

public final class UiDemo extends JPanel {
    private final GameScreen screen = new GameScreen();

    public UiDemo() {
        setPreferredSize(new Dimension(640, 480));
        int[] pixels = new int[160 * 120];
        for (int y = 0; y < 120; y++) {
            for (int x = 0; x < 160; x++) {
                pixels[y * 160 + x] = ((x / 16 + y / 12) % 2 == 0) ? 0x00A8E8 : 0x23374B;
            }
        }
        screen.look("off", false);
        screen.frame(pixels, 160, 120, 0);
    }

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            screen.paint(g, getWidth(), getHeight(), "WII-UU UI demo",
                    new Font(Font.SANS_SERIF, Font.BOLD, 18));
        } finally {
            g.dispose();
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("--snapshot")) {
            var panel = new UiDemo();
            panel.setSize(panel.getPreferredSize());
            var image = new BufferedImage(panel.getWidth(), panel.getHeight(), BufferedImage.TYPE_INT_RGB);
            var graphics = image.createGraphics();
            try {
                panel.paint(graphics);
            } finally {
                graphics.dispose();
            }
            if (!ImageIO.write(image, "png", Path.of(args[1]).toFile()))
                throw new java.io.IOException("PNG writer unavailable");
            return;
        }
        SwingUtilities.invokeLater(() -> {
            JFrame window = new JFrame("WII-UU UI demo");
            window.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            window.setContentPane(new UiDemo());
            window.pack();
            window.setLocationRelativeTo(null);
            window.setVisible(true);
        });
    }
}
