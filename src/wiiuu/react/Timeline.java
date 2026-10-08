package wiiuu.react;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

import javax.swing.JComponent;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;

/**
 * The timeline: a time ruler with the playhead, and the reactions as blocks (coloured by mood,
 * stacked when they overlap). Click to move the playhead, drag a reaction to move it, drag its
 * right edge to make it longer, double-click to edit it; Ctrl + wheel zooms.
 */
final class Timeline extends JComponent {
    static final Map<String, Color> MOOD_COLORS = Map.of(
            "happy", new Color(0x34A853), "sad", new Color(0x5C6BC0), "smug", new Color(0xF9A825),
            "nervous", new Color(0xFB8C00), "focus", new Color(0x00A8E8), "sneaky", new Color(0x8E24AA),
            "shocked", new Color(0xE53935));
    private static final int RULER = 26, LANE = 34, PAD = 8;

    private Project.Clip clip;
    private double playhead;
    private double pxPerSec = 40;
    private Project.Cue selected;
    private final DoubleConsumer seek;
    private final Consumer<Project.Cue> select, edit;
    private final Runnable changed;
    private final List<Object[]> hit = new ArrayList<>();      // cue, its shape

    Timeline(DoubleConsumer seek, Consumer<Project.Cue> select, Consumer<Project.Cue> edit, Runnable changed) {
        this.seek = seek;
        this.select = select;
        this.edit = edit;
        this.changed = changed;
        setFocusable(true);
        setOpaque(true);
        MouseAdapter m = new MouseAdapter() {
            Project.Cue dragging;
            boolean resizing;
            double grabOffset;

            @Override
            public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                if (clip == null) return;
                Project.Cue c = cueAt(e.getX(), e.getY());
                if (c == null) {
                    if (e.getY() >= 0) seekTo(e.getX());
                    dragging = null;
                    return;
                }
                selected = c;
                select.accept(c);
                if (e.getClickCount() == 2) {
                    edit.accept(c);
                    return;
                }
                double t = timeAt(e.getX());
                resizing = Math.abs(xOf(c.at + c.duration) - e.getX()) < 8;
                grabOffset = t - c.at;
                dragging = c;
                repaint();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (clip == null) return;
                if (dragging == null) {
                    seekTo(e.getX());
                    return;
                }
                double t = timeAt(e.getX());
                if (resizing) dragging.duration = Math.max(0.5, Math.round((t - dragging.at) * 10) / 10.0);
                else dragging.at = Math.max(0, Math.min(length() - 0.1, Math.round((t - grabOffset) * 10) / 10.0));
                select.accept(dragging);
                changed.run();
                repaint();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                dragging = null;
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                Project.Cue c = clip == null ? null : cueAt(e.getX(), e.getY());
                boolean edge = c != null && Math.abs(xOf(c.at + c.duration) - e.getX()) < 8;
                setCursor(Cursor.getPredefinedCursor(edge ? Cursor.E_RESIZE_CURSOR : c != null ? Cursor.MOVE_CURSOR : Cursor.DEFAULT_CURSOR));
                setToolTipText(c == null ? null : c.text + "  (" + c.mood + ", " + String.format("%.1f", c.duration) + " s)");
            }

            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                if (e.isControlDown() || e.isMetaDown()) {
                    double keep = timeAt(e.getX());
                    pxPerSec = Math.max(2, Math.min(400, pxPerSec * (e.getWheelRotation() < 0 ? 1.25 : 0.8)));
                    revalidate();
                    // keep the time under the mouse where it is
                    SwingUtilities.invokeLater(() -> {
                        if (getParent() instanceof JViewport vp) {
                            int x = (int) (xOf(keep) - (e.getX() - vp.getViewPosition().x));
                            vp.setViewPosition(new java.awt.Point(Math.max(0, x), 0));
                        }
                    });
                    repaint();
                } else if (getParent() instanceof JViewport vp) {
                    int x = vp.getViewPosition().x + e.getWheelRotation() * 60;
                    vp.setViewPosition(new java.awt.Point(Math.max(0, Math.min(x, getWidth() - vp.getWidth())), 0));
                }
            }
        };
        addMouseListener(m);
        addMouseMotionListener(m);
        addMouseWheelListener(m);
    }

    void setClip(Project.Clip c) {
        clip = c;
        selected = null;
        playhead = 0;
        // fit a short video, readable steps for a long one
        double len = length();
        pxPerSec = len <= 0 ? 40 : Math.max(4, Math.min(80, 1100 / len));
        revalidate();
        repaint();
    }

    void setSelected(Project.Cue c) {
        selected = c;
        repaint();
    }

    void setPlayhead(double t) {
        playhead = t;
        repaint();
        // keep the playhead in sight
        if (getParent() instanceof JViewport vp) {
            int x = (int) xOf(t), left = vp.getViewPosition().x, w = vp.getWidth();
            if (x < left + 20 || x > left + w - 40) vp.setViewPosition(new java.awt.Point(Math.max(0, Math.min(x - w / 3, getWidth() - w)), 0));
        }
    }

    double length() {
        if (clip == null) return 60;
        if (clip.duration > 0) return clip.duration;
        double end = 60;
        for (Project.Cue c : clip.cues) end = Math.max(end, c.at + c.duration + 10);
        return end;
    }

    private void seekTo(int x) {
        double t = Math.max(0, Math.min(length(), timeAt(x)));
        playhead = t;
        seek.accept(t);
        repaint();
    }

    double timeAt(int x) {
        return (x - PAD) / pxPerSec;
    }

    double xOf(double t) {
        return PAD + t * pxPerSec;
    }

    private Project.Cue cueAt(int x, int y) {
        for (int i = hit.size() - 1; i >= 0; i--) {
            Shape s = (Shape) hit.get(i)[1];
            if (s.getBounds2D().contains(x, y) || s.getBounds2D().getMaxX() - x >= -8 && s.getBounds2D().getMaxX() - x < 0
                    && y >= s.getBounds2D().getY() && y <= s.getBounds2D().getMaxY()) return (Project.Cue) hit.get(i)[0];
        }
        return null;
    }

    /** Which lane each cue goes in (the first one free at its start). */
    private int[] lanes(List<Project.Cue> cues) {
        int[] lane = new int[cues.size()];
        List<Double> ends = new ArrayList<>();
        Integer[] order = new Integer[cues.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> Double.compare(cues.get(a).at, cues.get(b).at));
        for (int idx : order) {
            Project.Cue c = cues.get(idx);
            int l = 0;
            while (l < ends.size() && ends.get(l) > c.at + 0.01) l++;
            if (l == ends.size()) ends.add(0.0);
            ends.set(l, c.at + c.duration);
            lane[idx] = l;
        }
        return lane;
    }

    @Override
    public Dimension getPreferredSize() {
        int lanes = 1;
        if (clip != null) for (int l : lanes(clip.cues)) lanes = Math.max(lanes, l + 1);
        return new Dimension((int) xOf(length()) + PAD * 4, RULER + Math.max(3, lanes) * LANE + PAD);
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        int w = getWidth(), h = getHeight();
        g.setColor(new Color(0x1B1F24));
        g.fillRect(0, 0, w, h);
        g.setColor(new Color(0x262B31));
        g.fillRect(0, 0, w, RULER);
        if (clip == null) {
            g.setColor(new Color(0x8A9099));
            g.setFont(getFont().deriveFont(13f));
            g.drawString("Add a video (drag it in), then type what WII-UU says at each moment.", 14, RULER + 30);
            return;
        }
        // the ruler: a tick every step, a label every few
        double len = length();
        double step = pxPerSec >= 60 ? 1 : pxPerSec >= 20 ? 5 : pxPerSec >= 6 ? 15 : 60;
        g.setFont(getFont().deriveFont(11f));
        for (double t = 0; t <= len; t += step) {
            int x = (int) xOf(t);
            boolean label = Math.round(t / step) % (step < 5 ? 5 : 2) == 0;
            g.setColor(new Color(255, 255, 255, label ? 90 : 40));
            g.drawLine(x, label ? 12 : 18, x, RULER);
            if (label) {
                g.setColor(new Color(0xAEB6BF));
                g.drawString(clock(t), x + 3, 12);
            }
            g.setColor(new Color(255, 255, 255, 10));
            g.drawLine(x, RULER, x, h);
        }
        g.setColor(new Color(255, 255, 255, 30));
        g.fillRect((int) xOf(len), 0, 2, h);
        // the reactions
        hit.clear();
        int[] lane = lanes(clip.cues);
        Font f = getFont().deriveFont(Font.BOLD, 12f);
        g.setFont(f);
        FontMetrics fm = g.getFontMetrics();
        for (int i = 0; i < clip.cues.size(); i++) {
            Project.Cue c = clip.cues.get(i);
            float x = (float) xOf(c.at), cw = (float) Math.max(10, c.duration * pxPerSec), y = RULER + 4 + lane[i] * LANE;
            RoundRectangle2D r = new RoundRectangle2D.Float(x, y, cw, LANE - 6, 10, 10);
            Color col = MOOD_COLORS.getOrDefault(c.mood, new Color(0x00A8E8));
            g.setColor(c == selected ? col.brighter() : col);
            g.fill(r);
            if (c == selected) {
                g.setColor(Color.WHITE);
                g.setStroke(new BasicStroke(2));
                g.draw(r);
            }
            // the handle to make it longer
            g.setColor(new Color(255, 255, 255, 110));
            g.fillRect((int) (x + cw - 5), (int) y + 6, 2, LANE - 18);
            Shape clip = g.getClip();
            g.clipRect((int) x + 6, (int) y, (int) cw - 14, LANE - 6);
            g.setColor(Color.WHITE);
            g.drawString(c.text.isEmpty() ? "…" : c.text, x + 7, y + (LANE - 6) / 2f + fm.getAscent() / 2f - 2);
            g.setClip(clip);
            hit.add(new Object[]{c, r});
        }
        // the playhead
        int px = (int) xOf(playhead);
        g.setColor(new Color(0xFF3B30));
        g.fillRect(px - 1, 0, 2, h);
        g.fillPolygon(new int[]{px - 6, px + 6, px}, new int[]{0, 0, 8}, 3);
    }

    static String clock(double t) {
        int s = (int) Math.floor(t);
        return s >= 3600 ? String.format("%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String.format("%d:%02d", s / 60, s % 60);
    }
}
