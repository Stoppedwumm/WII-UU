package wiiuu.ui;

import java.awt.Font;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.PathIterator;
import java.awt.geom.Rectangle2D;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Writes the WII-UU wordmark's outlines as SVG path data ("WII-" and "UU" apart, for their two
 * colours), centred on the origin with y down, for the 3D intro (intro3d.html) to extrude.
 *
 * <pre>usage: LogoPath Inter-900.ttf out.json</pre>
 */
public final class LogoPath {
    public static void main(String[] args) throws Exception {
        Font font = Font.createFont(Font.TRUETYPE_FONT, new File(args[0])).deriveFont(200f);
        FontRenderContext frc = new FontRenderContext(null, true, true);
        GlyphVector a = font.createGlyphVector(frc, "WII-"), u = font.createGlyphVector(frc, "UU");
        float split = (float) a.getLogicalBounds().getWidth();
        Shape left = a.getOutline(), right = u.getOutline(split, 0);
        Rectangle2D b = left.getBounds2D().createUnion(right.getBounds2D());
        double cx = b.getCenterX(), cy = b.getCenterY();
        String json = String.format(Locale.ROOT, "{\"width\": %.2f, \"height\": %.2f, \"left\": \"%s\", \"right\": \"%s\"}%n",
                b.getWidth(), b.getHeight(), svg(left, cx, cy), svg(right, cx, cy));
        Files.writeString(Path.of(args[1]), json);
    }

    private static String svg(Shape s, double cx, double cy) {
        StringBuilder sb = new StringBuilder();
        double[] c = new double[6];
        for (PathIterator it = s.getPathIterator(null); !it.isDone(); it.next()) {
            int type = it.currentSegment(c);
            switch (type) {
                case PathIterator.SEG_MOVETO -> sb.append(String.format(Locale.ROOT, "M%.2f %.2f", c[0] - cx, c[1] - cy));
                case PathIterator.SEG_LINETO -> sb.append(String.format(Locale.ROOT, "L%.2f %.2f", c[0] - cx, c[1] - cy));
                case PathIterator.SEG_QUADTO -> sb.append(String.format(Locale.ROOT, "Q%.2f %.2f %.2f %.2f", c[0] - cx, c[1] - cy, c[2] - cx, c[3] - cy));
                case PathIterator.SEG_CUBICTO -> sb.append(String.format(Locale.ROOT, "C%.2f %.2f %.2f %.2f %.2f %.2f",
                        c[0] - cx, c[1] - cy, c[2] - cx, c[3] - cy, c[4] - cx, c[5] - cy));
                case PathIterator.SEG_CLOSE -> sb.append('Z');
                default -> { }
            }
        }
        return sb.toString();
    }
}
