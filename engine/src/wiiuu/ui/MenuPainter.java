package wiiuu.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.Map;

/** WII-UU menu backgrounds, drop shadows and vector dock icons, independent of app state. */
public final class MenuPainter {
    private MenuPainter() {}

    /** Vector icons shared by the menu dock. */
    public enum Icon { GAMEPAD, SETTINGS, REFRESH, POWER }

    /** Render the palette's gradient, pinstripes and light blooms. Dimensions are at least one pixel. */
    public static BufferedImage background(int w, int h, Map<String, Color> palette) {
        w = Math.max(1, w);
        h = Math.max(1, h);
        BufferedImage bg = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D b = bg.createGraphics();
        try {
            b.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            b.setPaint(new GradientPaint(0, 0, palette.get("background-top"), 0, h, palette.get("background-bottom")));
            b.fillRect(0, 0, w, h);
            // the Wii U menu's faint pinstripes
            b.setColor(palette.get("stripes"));
            for (int y = 0; y < h; y += 6) b.fillRect(0, y, w, 2);
            // soft light blooms
            b.setPaint(new java.awt.RadialGradientPaint(w * 0.18f, h * 0.2f, w * 0.5f,
                    new float[]{0, 1}, new Color[]{palette.get("glow"), new Color(palette.get("glow").getRed(), palette.get("glow").getGreen(), palette.get("glow").getBlue(), 0)}));
            b.fillRect(0, 0, w, h);
            b.setPaint(new java.awt.RadialGradientPaint(w * 0.9f, h * 0.95f, w * 0.45f,
                    new float[]{0, 1}, new Color[]{palette.get("accent-glow"), new Color(palette.get("accent-glow").getRed(), palette.get("accent-glow").getGreen(), palette.get("accent-glow").getBlue(), 0)}));
            b.fillRect(0, 0, w, h);
        } finally {
            b.dispose();
        }
        return bg;
    }

    /** Paint a soft shadow without changing the caller's graphics state. Nonpositive depth/boost draws nothing. */
    public static void shadow(Graphics2D graphics, Shape s, int depth, Color colour, int boost) {
        if (depth <= 0 || boost <= 0) return;
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            Rectangle2D b = s.getBounds2D();
            boolean round = s instanceof RoundRectangle2D;
            boolean oval = s instanceof Ellipse2D;
            double arc = round ? ((RoundRectangle2D) s).getArcWidth() : 0;
            int layers = Math.min(4, depth);
            for (int i = layers; i >= 1; i--) {
                double grow = depth * 0.45 * i / layers, dy = depth * 0.35 * i / layers + 1;
                g.setColor(new Color(colour.getRed(), colour.getGreen(), colour.getBlue(),
                        Math.min(255, (9 + (layers - i) * 3) * boost)));
                double x = b.getX() - grow, y = b.getY() - grow + dy, w = b.getWidth() + 2 * grow, h = b.getHeight() + 2 * grow;
                if (round) g.fill(new RoundRectangle2D.Double(x, y, w, h, arc + 2 * grow, arc + 2 * grow));
                else if (oval) g.fill(new Ellipse2D.Double(x, y, w, h));
                else {
                    g.translate(0, dy);
                    g.fill(s);
                    g.translate(0, -dy);
                }
            }
        } finally {
            g.dispose();
        }
    }

    /** Paint a vector dock icon in the given bounds, preserving graphics state. */
    public static void icon(Graphics2D graphics, Icon d, Rectangle2D r, Color c) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            float cx = (float) r.getCenterX(), cy = (float) r.getCenterY(), s = (float) r.getWidth() * 0.42f;
            g.setColor(c);
            g.setStroke(new BasicStroke(s * 0.14f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            switch (d) {
                case GAMEPAD -> gamepad(g, cx - s * 0.75f, cy - s * 0.45f, s * 1.5f, s * 0.9f, c);
                case SETTINGS -> {
                    for (int k = 0; k < 8; k++) {
                        double a = Math.PI * 2 * k / 8;
                        g.draw(new Line2D.Double(cx + Math.cos(a) * s * 0.45, cy + Math.sin(a) * s * 0.45,
                                cx + Math.cos(a) * s * 0.72, cy + Math.sin(a) * s * 0.72));
                    }
                    g.draw(new Ellipse2D.Float(cx - s * 0.45f, cy - s * 0.45f, s * 0.9f, s * 0.9f));
                    g.fill(new Ellipse2D.Float(cx - s * 0.16f, cy - s * 0.16f, s * 0.32f, s * 0.32f));
                }
                case REFRESH -> {
                    g.draw(new Arc2D.Float(cx - s * 0.6f, cy - s * 0.6f, s * 1.2f, s * 1.2f, 70, 290, Arc2D.OPEN));
                    Path2D head = new Path2D.Float();
                    double ax = cx + Math.cos(Math.toRadians(70)) * s * 0.6, ay = cy - Math.sin(Math.toRadians(70)) * s * 0.6;
                    head.moveTo(ax - s * 0.32, ay - s * 0.22);
                    head.lineTo(ax + s * 0.05, ay);
                    head.lineTo(ax - s * 0.25, ay + s * 0.3);
                    g.draw(head);
                }
                case POWER -> {
                    g.draw(new Arc2D.Float(cx - s * 0.6f, cy - s * 0.55f, s * 1.2f, s * 1.2f, 120, 300, Arc2D.OPEN));
                    g.draw(new Line2D.Float(cx, cy - s * 0.75f, cx, cy - s * 0.05f));
                }
            }
            g.setStroke(new BasicStroke(1));
        } finally {
            g.dispose();
        }
    }

    /** Paint a GamePad symbol at explicit coordinates, preserving graphics state. */
    public static void gamepad(Graphics2D graphics, float x, float y, float w, float h, Color c) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setColor(c);
            RoundRectangle2D body = new RoundRectangle2D.Float(x, y, w, h, h * 0.5f, h * 0.5f);
            g.setStroke(new BasicStroke(Math.max(1.5f, h * 0.12f)));
            g.draw(body);
            g.fill(new RoundRectangle2D.Float(x + w * 0.28f, y + h * 0.22f, w * 0.44f, h * 0.56f, h * 0.12f, h * 0.12f));
            g.fill(new Ellipse2D.Float(x + w * 0.07f, y + h * 0.36f, h * 0.28f, h * 0.28f));
            g.fill(new Ellipse2D.Float(x + w * 0.93f - h * 0.28f, y + h * 0.36f, h * 0.28f, h * 0.28f));
            g.setStroke(new BasicStroke(1));
        } finally {
            g.dispose();
        }
    }
}
