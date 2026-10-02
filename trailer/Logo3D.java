package wiiuu.ui;

import java.awt.Font;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.PathIterator;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;

/**
 * The WII-UU logo as solid 3D letters, drawn by a small software renderer (no 3D library needed):
 * the glyph outlines are extruded into front, back and side faces, then rasterised with a depth
 * buffer and lit by a key light (diffuse and specular), a cyan rim light and a moving sheen.
 * Used by the YouTube music video (MixVideo).
 */
final class Logo3D {
    // triangles: 3 vertices (model space), a flat normal, a material and the model x for the sheen
    private final List<float[]> tris = new ArrayList<>();     // x0 y0 z0 x1 y1 z1 x2 y2 z2 nx ny nz material
    private final float width;

    /** Materials: the white "WII-" and the cyan "UU", each with a darker side. */
    private static final int[][] COLORS = {{238, 242, 248}, {120, 132, 150}, {0, 172, 236}, {0, 92, 140}};

    /**
     * @param cyanFrom index of the first letter drawn cyan ("WII-UU": 4)
     * @param width    model width of the text
     * @param depth    extrusion depth
     */
    Logo3D(Font font, String text, int cyanFrom, float width, float depth) {
        this.width = width;
        FontRenderContext frc = new FontRenderContext(null, true, true);
        GlyphVector gv = font.createGlyphVector(frc, text);
        Rectangle2D b = gv.getVisualBounds();
        double s = width / b.getWidth(), cx = b.getCenterX(), cy = b.getCenterY();
        float zf = -depth / 2, zb = depth / 2;
        for (int g = 0; g < gv.getNumGlyphs(); g++) {
            int front = g >= cyanFrom ? 2 : 0;
            Shape outline = gv.getGlyphOutline(g);
            for (List<float[]> poly : polygons(outline, s, cx, cy)) {
                int n = poly.size();
                if (n < 3) continue;
                // caps: the front faces the camera (-z), the back away from it
                for (int[] t : earClip(poly)) {
                    float[] a = poly.get(t[0]), c = poly.get(t[1]), d = poly.get(t[2]);
                    tris.add(new float[]{a[0], a[1], zf, c[0], c[1], zf, d[0], d[1], zf, 0, 0, -1, front});
                    tris.add(new float[]{a[0], a[1], zb, c[0], c[1], zb, d[0], d[1], zb, 0, 0, 1, front + 1});
                }
                // sides: one quad per outline edge, facing outwards (the polygon runs anticlockwise)
                for (int i = 0; i < n; i++) {
                    float[] p = poly.get(i), q = poly.get((i + 1) % n);
                    float ex = q[0] - p[0], ey = q[1] - p[1], len = (float) Math.hypot(ex, ey);
                    if (len < 1e-6) continue;
                    float nx = ey / len, ny = -ex / len;
                    tris.add(new float[]{p[0], p[1], zf, q[0], q[1], zf, q[0], q[1], zb, nx, ny, 0, front + 1});
                    tris.add(new float[]{p[0], p[1], zf, q[0], q[1], zb, p[0], p[1], zb, nx, ny, 0, front + 1});
                }
            }
        }
    }

    float width() {
        return width;
    }

    /** The glyph's closed outlines in model units (y up, centred), each anticlockwise. */
    private static List<List<float[]>> polygons(Shape outline, double s, double cx, double cy) {
        List<List<float[]>> out = new ArrayList<>();
        List<float[]> cur = null;
        double[] c = new double[6];
        for (PathIterator it = outline.getPathIterator(null, 0.15); !it.isDone(); it.next()) {
            int type = it.currentSegment(c);
            if (type == PathIterator.SEG_MOVETO) {
                cur = new ArrayList<>();
                out.add(cur);
            }
            if (type == PathIterator.SEG_MOVETO || type == PathIterator.SEG_LINETO) {
                float x = (float) ((c[0] - cx) * s), y = (float) (-(c[1] - cy) * s);
                if (cur.isEmpty() || Math.hypot(x - cur.get(cur.size() - 1)[0], y - cur.get(cur.size() - 1)[1]) > 1e-4) {
                    cur.add(new float[]{x, y});
                }
            }
        }
        for (List<float[]> p : out) {
            if (p.size() > 1 && Math.hypot(p.get(0)[0] - p.get(p.size() - 1)[0], p.get(0)[1] - p.get(p.size() - 1)[1]) < 1e-4) {
                p.remove(p.size() - 1);
            }
            if (area(p) < 0) java.util.Collections.reverse(p);
        }
        return out;
    }

    private static double area(List<float[]> p) {
        double a = 0;
        for (int i = 0; i < p.size(); i++) {
            float[] u = p.get(i), v = p.get((i + 1) % p.size());
            a += u[0] * v[1] - v[0] * u[1];
        }
        return a / 2;
    }

    /** Ear clipping for a simple anticlockwise polygon (the logo's letters have no holes). */
    private static List<int[]> earClip(List<float[]> p) {
        List<int[]> out = new ArrayList<>();
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < p.size(); i++) idx.add(i);
        int guard = 0;
        while (idx.size() > 3 && guard++ < 10000) {
            boolean clipped = false;
            for (int i = 0; i < idx.size(); i++) {
                int a = idx.get((i + idx.size() - 1) % idx.size()), b = idx.get(i), c = idx.get((i + 1) % idx.size());
                float[] A = p.get(a), B = p.get(b), C = p.get(c);
                double cross = (B[0] - A[0]) * (C[1] - A[1]) - (B[1] - A[1]) * (C[0] - A[0]);
                if (cross <= 1e-12) continue;                          // reflex or flat corner
                boolean inside = false;
                for (int k : idx) {
                    if (k == a || k == b || k == c) continue;
                    if (inTriangle(p.get(k), A, B, C)) {
                        inside = true;
                        break;
                    }
                }
                if (inside) continue;
                out.add(new int[]{a, b, c});
                idx.remove(i);
                clipped = true;
                break;
            }
            if (!clipped) break;                                       // degenerate: give up on the rest
        }
        if (idx.size() == 3) out.add(new int[]{idx.get(0), idx.get(1), idx.get(2)});
        return out;
    }

    private static boolean inTriangle(float[] p, float[] a, float[] b, float[] c) {
        double d1 = (p[0] - b[0]) * (a[1] - b[1]) - (a[0] - b[0]) * (p[1] - b[1]);
        double d2 = (p[0] - c[0]) * (b[1] - c[1]) - (b[0] - c[0]) * (p[1] - c[1]);
        double d3 = (p[0] - a[0]) * (c[1] - a[1]) - (c[0] - a[0]) * (p[1] - a[1]);
        boolean neg = d1 < 0 || d2 < 0 || d3 < 0, pos = d1 > 0 || d2 > 0 || d3 > 0;
        return !(neg && pos);
    }

    /**
     * Draws the logo into {@code rgb} (w x h, 0xRRGGBB) over what is there.
     *
     * @param rx      tilt (radians, about x), {@code ry} turn (about y), {@code rz} roll
     * @param tx      position in camera space; the camera sits at the origin looking along +z
     * @param focal   focal length in pixels
     * @param sheen   model x of the light sweep across the front (off the logo: none)
     * @param opacity 0..1
     */
    void render(int[] rgb, float[] zbuf, int w, int h, double rx, double ry, double rz, double tx, double ty, double tz,
                double focal, double sheen, double opacity) {
        java.util.Arrays.fill(zbuf, 0f);
        double cxr = Math.cos(rx), sxr = Math.sin(rx), cyr = Math.cos(ry), syr = Math.sin(ry), czr = Math.cos(rz), szr = Math.sin(rz);
        double[] light = norm(-0.45, 0.55, -0.7), rimDir = norm(0.6, -0.2, 0.75);
        double[] sx = new double[3], sy = new double[3], iz = new double[3], mx = new double[3], my = new double[3];
        double[] cam = new double[3];
        for (float[] t : tris) {
            double cxs = 0, cys = 0, czs = 0;
            boolean behind = false;
            for (int k = 0; k < 3; k++) {
                rotate(t[k * 3], t[k * 3 + 1], t[k * 3 + 2], cxr, sxr, cyr, syr, czr, szr, cam);
                double X = cam[0] + tx, Y = cam[1] + ty, Z = cam[2] + tz;
                if (Z < 0.05) {
                    behind = true;
                    break;
                }
                sx[k] = w / 2.0 + focal * X / Z;
                sy[k] = h / 2.0 - focal * Y / Z;
                iz[k] = 1 / Z;
                mx[k] = t[k * 3];
                my[k] = t[k * 3 + 1];
                cxs += X / 3;
                cys += Y / 3;
                czs += Z / 3;
            }
            if (behind) continue;
            // flat shading: key light diffuse and specular, a rim light from behind, some ambient
            rotate(t[9], t[10], t[11], cxr, sxr, cyr, syr, czr, szr, cam);
            double[] n = {cam[0], cam[1], cam[2]};
            double[] v = norm(-cxs, -cys, -czs);
            if (n[0] * v[0] + n[1] * v[1] + n[2] * v[2] < 0) continue;      // facing away
            double diff = Math.max(0, dot(n, light));
            double[] hv = norm(light[0] + v[0], light[1] + v[1], light[2] + v[2]);
            double spec = Math.pow(Math.max(0, dot(n, hv)), 40) * 0.9;
            double rim = Math.pow(1 - Math.abs(dot(n, v)), 3) * 0.35 + Math.max(0, dot(n, rimDir)) * 0.15;
            int mat = (int) t[12];
            int[] base = COLORS[mat];
            double shade = 0.42 + 0.68 * diff;
            double r = base[0] * shade + 255 * spec + 40 * rim, g = base[1] * shade + 255 * spec + 190 * rim,
                    b = base[2] * shade + 255 * spec + 255 * rim;
            boolean front = mat % 2 == 0 && t[11] < 0;
            raster(rgb, zbuf, w, h, sx, sy, iz, mx, my, r, g, b, front, sheen, opacity);
        }
    }

    private static void raster(int[] rgb, float[] zbuf, int w, int h, double[] sx, double[] sy, double[] iz, double[] mx,
                               double[] my, double r, double g, double b, boolean front, double sheen, double opacity) {
        int x0 = (int) Math.max(0, Math.floor(Math.min(sx[0], Math.min(sx[1], sx[2]))));
        int x1 = (int) Math.min(w - 1, Math.ceil(Math.max(sx[0], Math.max(sx[1], sx[2]))));
        int y0 = (int) Math.max(0, Math.floor(Math.min(sy[0], Math.min(sy[1], sy[2]))));
        int y1 = (int) Math.min(h - 1, Math.ceil(Math.max(sy[0], Math.max(sy[1], sy[2]))));
        double area = (sx[1] - sx[0]) * (sy[2] - sy[0]) - (sx[2] - sx[0]) * (sy[1] - sy[0]);
        if (Math.abs(area) < 1e-9 || x0 > x1 || y0 > y1) return;
        for (int y = y0; y <= y1; y++) {
            double py = y + 0.5;
            for (int x = x0; x <= x1; x++) {
                double px = x + 0.5;
                double w0 = ((sx[1] - px) * (sy[2] - py) - (sx[2] - px) * (sy[1] - py)) / area;
                double w1 = ((sx[2] - px) * (sy[0] - py) - (sx[0] - px) * (sy[2] - py)) / area;
                double w2 = 1 - w0 - w1;
                if (w0 < 0 || w1 < 0 || w2 < 0) continue;
                double z = w0 * iz[0] + w1 * iz[1] + w2 * iz[2];
                int i = y * w + x;
                if (z <= zbuf[i]) continue;
                zbuf[i] = (float) z;
                double rr = r, gg = g, bb = b;
                if (front) {
                    // brushed-metal fronts: lighter at the top, and the sheen sweeping across
                    double m = (w0 * mx[0] * iz[0] + w1 * mx[1] * iz[1] + w2 * mx[2] * iz[2]) / z;
                    double yy = (w0 * my[0] * iz[0] + w1 * my[1] * iz[1] + w2 * my[2] * iz[2]) / z;
                    double grad = 0.86 + 0.14 * Math.max(-1, Math.min(1, yy / 0.9));
                    double k = Math.exp(-Math.pow((m - sheen) / 0.35, 2)) * 200;
                    rr = rr * grad + k;
                    gg = gg * grad + k;
                    bb = bb * grad + k;
                }
                int old = rgb[i];
                double a = opacity;
                int R = clamp(rr * a + ((old >> 16) & 255) * (1 - a)), G = clamp(gg * a + ((old >> 8) & 255) * (1 - a)),
                        B = clamp(bb * a + (old & 255) * (1 - a));
                rgb[i] = (R << 16) | (G << 8) | B;
            }
        }
    }

    private static int clamp(double v) {
        return v < 0 ? 0 : v > 255 ? 255 : (int) v;
    }

    /** Model rotation: turn about y, then tilt about x, then roll about z. */
    private static void rotate(double x, double y, double z, double cx, double sx, double cy, double sy, double cz, double sz, double[] out) {
        double x1 = x * cy + z * sy, z1 = -x * sy + z * cy;
        double y2 = y * cx - z1 * sx, z2 = y * sx + z1 * cx;
        out[0] = x1 * cz - y2 * sz;
        out[1] = x1 * sz + y2 * cz;
        out[2] = z2;
    }

    private static double[] norm(double x, double y, double z) {
        double l = Math.sqrt(x * x + y * y + z * z);
        return new double[]{x / l, y / l, z / l};
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }
}
