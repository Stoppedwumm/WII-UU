import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import wiiuu.ui.MenuPalette;
import wiiuu.ui.MenuPainter;
import wiiuu.ui.UiFonts;
import wiiuu.ui.UiText;

/** Reuse the WII-UU menu appearance without depending on the launcher. */
public final class MenuDemo extends JPanel {
    private final UiFonts fonts = new UiFonts(UiFonts.choose("Nunito", "Segoe UI", "DejaVu Sans"));
    private final boolean dark;
    private BufferedImage background;

    public MenuDemo(boolean dark) {
        this.dark = dark;
        setPreferredSize(new Dimension(900, 600));
    }

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        var palette = MenuPalette.colours(dark);
        if (background == null || background.getWidth() != getWidth() || background.getHeight() != getHeight())
            background = MenuPainter.background(getWidth(), getHeight(), palette);
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.drawImage(background, 0, 0, null);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(palette.get("text"));
            g.setFont(fonts.font(Font.BOLD, 36));
            g.drawString("Your desktop app", 50, 80);
            g.setFont(fonts.font(Font.PLAIN, 20));
            int y = 125;
            for (String line : UiText.wrap("Shared backgrounds, shadows, vector icons and typography from the WII-UU menu.",
                    g.getFontMetrics(), getWidth() - 100, 2)) {
                g.drawString(line, 50, y);
                y += 28;
            }
            int x = 50;
            for (var icon : MenuPainter.Icon.values()) {
                var card = new RoundRectangle2D.Float(x, 240, 160, 160, 30, 30);
                MenuPainter.shadow(g, card, 10, palette.get("shadow"), dark ? 3 : 1);
                g.setColor(palette.get("card"));
                g.fill(card);
                MenuPainter.icon(g, icon, card.getBounds2D(), palette.get("accent"));
                x += 205;
            }
        } finally {
            g.dispose();
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length >= 2 && args[0].equals("--snapshot")) {
            MenuDemo demo = new MenuDemo(args.length > 2 && args[2].equals("--dark"));
            demo.setSize(demo.getPreferredSize());
            BufferedImage image = new BufferedImage(900, 600, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics();
            demo.paint(g);
            g.dispose();
            ImageIO.write(image, "png", Path.of(args[1]).toFile());
            return;
        }
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("Shared menu appearance");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setContentPane(new MenuDemo(args.length > 0 && args[0].equals("--dark")));
            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        });
    }
}
