package wiiuu.ui;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import javax.imageio.ImageIO;

import wiiuu.core.Game;
import wiiuu.core.OpenBased;

/**
 * OpenBased as a cinema, apart from the games: the lights go down, the music stops, and it's a
 * dark screen of films and series like a streaming service's. The focused title fills the top
 * (its backdrop, story, year, length, genres and how far it was watched); below, rows of posters
 * to browse with the d-pad: Continue watching, Series, Movies, More. A series opens to its
 * seasons and episodes. A plays (from where it was left), B goes back.
 */
final class Cinema {
    private static final Color BG = new Color(0x0A0A0D), TEXT = new Color(0xF4F1EC), DIM = new Color(0xA39E97);
    /** the marquee's warm gold: focus, progress, the wordmark */
    private static final Color GOLD = new Color(0xF2B84B);
    private static final long LIGHTS_MS = 650, CLOSE_MS = 280;

    /** A card in a row: a video, or a series (its episodes). */
    private record Entry(OpenBased.Item item, String series, List<OpenBased.Item> episodes) {
        String title() {
            return item != null ? item.title() : series;
        }

        OpenBased.Item lead() {                             // the video whose details and pictures a series shows
            if (item != null) return item;
            for (OpenBased.Item e : episodes) if (e.continuing()) return e;
            return episodes.get(0);
        }

        Path poster() {
            if (item != null) return item.poster();
            for (OpenBased.Item e : episodes) if (e.poster() != null) return e.poster();
            return null;
        }
    }

    /** A row: its title, its cards, and whether they're wide (continue watching, episodes). */
    private record Row(String title, List<Entry> entries, boolean wide) {}

    private final MenuView view;
    private final OpenBased ob;
    private final Consumer<Game> play;
    private final long opened = System.currentTimeMillis();
    private long closing;
    private List<OpenBased.Item> builtFrom;
    private List<Row> rows = List.of();
    private final Map<String, Integer> colByRow = new HashMap<>();
    private int row, col;
    /** the series page: its name, and the browse position to come back to */
    private String series;
    private int backRow, backCol;
    private float rowAnim, colAnim;
    private final Map<Integer, Float> colAnims = new HashMap<>();
    private final List<Object[]> hits = new ArrayList<>();          // {Rectangle2D, row, col} from the last paint
    private long focusedAt = System.currentTimeMillis();

    private final Map<Path, BufferedImage> images = new ConcurrentHashMap<>();
    private final Map<Path, Boolean> loading = new ConcurrentHashMap<>();
    private final ExecutorService loader = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "cinema-images");
        t.setDaemon(true);
        return t;
    });

    Cinema(MenuView view, OpenBased ob, Consumer<Game> play) {
        this.view = view;
        this.ob = ob;
        this.play = play;
        rebuild();
    }

    // ---- what's on ------------------------------------------------------------------------------

    private void rebuild() {
        List<OpenBased.Item> items = ob.items();
        builtFrom = items;
        List<Row> out = new ArrayList<>();
        if (series != null) {
            Map<Integer, List<Entry>> seasons = new java.util.TreeMap<>();
            for (OpenBased.Item i : items) {
                if (i.isEpisode() && series.equals(i.series())) {
                    seasons.computeIfAbsent(i.season() == null ? 0 : i.season(), k -> new ArrayList<>()).add(new Entry(i, null, null));
                }
            }
            for (Map.Entry<Integer, List<Entry>> s : seasons.entrySet()) {
                s.getValue().sort(java.util.Comparator.comparing(e -> e.item().episode() == null ? 0 : e.item().episode()));
                out.add(new Row(s.getKey() == 0 ? "Episodes" : "Season " + s.getKey(), s.getValue(), true));
            }
        } else {
            List<Entry> going = new ArrayList<>(), movies = new ArrayList<>(), more = new ArrayList<>();
            Map<String, List<OpenBased.Item>> shows = new LinkedHashMap<>();
            for (OpenBased.Item i : items) {
                if (i.continuing() && i.progress() < 0.97) going.add(new Entry(i, null, null));
                if (i.isEpisode()) shows.computeIfAbsent(i.series(), k -> new ArrayList<>()).add(i);
                else if ("MOVIE".equals(i.type())) movies.add(new Entry(i, null, null));
                else if (!"TRACK".equals(i.type())) more.add(new Entry(i, null, null));
            }
            List<Entry> showRow = new ArrayList<>();
            for (Map.Entry<String, List<OpenBased.Item>> s : shows.entrySet()) {
                List<OpenBased.Item> eps = new ArrayList<>(s.getValue());
                eps.sort(java.util.Comparator.comparing((OpenBased.Item e) -> e.season() == null ? 0 : e.season())
                        .thenComparing(e -> e.episode() == null ? 0 : e.episode()));
                showRow.add(new Entry(null, s.getKey(), eps));
            }
            if (!going.isEmpty()) out.add(new Row("Continue watching", going, true));
            if (!showRow.isEmpty()) out.add(new Row("Series", showRow, false));
            if (!movies.isEmpty()) out.add(new Row("Movies", movies, false));
            if (!more.isEmpty()) out.add(new Row("More", more, false));
        }
        rows = out;
        row = Math.max(0, Math.min(row, rows.size() - 1));
        col = rows.isEmpty() ? 0 : Math.max(0, Math.min(col, rows.get(row).entries().size() - 1));
    }

    private Entry focused() {
        if (rows.isEmpty()) return null;
        List<Entry> e = rows.get(row).entries();
        return e.isEmpty() ? null : e.get(Math.min(col, e.size() - 1));
    }

    // ---- input ----------------------------------------------------------------------------------

    void navigate(int dx, int dy) {
        if (closing != 0 || rows.isEmpty()) return;
        if (dy != 0) {
            int to = Math.max(0, Math.min(rows.size() - 1, row + dy));
            if (to == row) {
                Sfx.bump();
                return;
            }
            colByRow.put(rowKey(row), col);
            row = to;
            col = Math.min(colByRow.getOrDefault(rowKey(row), 0), rows.get(row).entries().size() - 1);
        } else if (dx != 0) {
            int n = rows.get(row).entries().size();
            int to = Math.max(0, Math.min(n - 1, col + dx));
            if (to == col) {
                Sfx.bump();
                return;
            }
            col = to;
        }
        focusedAt = System.currentTimeMillis();
        Sfx.move();
    }

    private String rowKey(int r) {
        return (series == null ? "" : series + "|") + rows.get(r).title();
    }

    void page(int delta) {
        navigate(0, delta);
    }

    void activate() {
        if (closing != 0) return;
        Entry e = focused();
        if (e == null) {
            Sfx.bump();
            return;
        }
        Sfx.select();
        if (e.item() != null) {
            play.accept(e.item().game());
        } else {                                            // a series: its seasons and episodes
            backRow = row;
            backCol = col;
            series = e.series();
            row = 0;
            col = 0;
            rebuild();
            OpenBased.Item lead = e.lead();                 // straight to the episode being watched
            for (int r = 0; r < rows.size(); r++) {
                List<Entry> es = rows.get(r).entries();
                for (int c = 0; c < es.size(); c++) {
                    if (es.get(c).item().id().equals(lead.id())) {
                        row = r;
                        col = c;
                    }
                }
            }
            colAnims.clear();
            focusedAt = System.currentTimeMillis();
        }
    }

    /** B: out of a series, else out of the cinema (true when it's closing). */
    boolean back() {
        if (closing != 0) return true;
        Sfx.back();
        if (series != null) {
            series = null;
            rebuild();
            row = Math.min(backRow, rows.size() - 1);
            col = rows.isEmpty() ? 0 : Math.min(backCol, rows.get(row).entries().size() - 1);
            colAnims.clear();
            focusedAt = System.currentTimeMillis();
            return false;
        }
        closing = System.currentTimeMillis();
        return true;
    }

    void click(int x, int y, int clicks) {
        for (Object[] h : hits) {
            if (((Rectangle2D) h[0]).contains(x, y)) {
                int r = (int) h[1], c = (int) h[2];
                boolean again = r == row && c == col;
                row = r;
                col = c;
                focusedAt = System.currentTimeMillis();
                if (again || clicks >= 2) activate();
                else Sfx.move();
                return;
            }
        }
    }

    void wheel(int notches) {
        navigate(0, notches > 0 ? 1 : -1);
    }

    /** False once it has closed. Keeps the list up to date and the motion going. */
    boolean tick() {
        if (closing != 0 && System.currentTimeMillis() - closing > CLOSE_MS) {
            loader.shutdownNow();
            return false;
        }
        if (ob.items() != builtFrom) rebuild();             // new positions after watching, a refresh...
        return true;
    }

    /** Whether something moves (the lights, a card sliding, the details fading in): keep drawing. */
    boolean animating() {
        long now = System.currentTimeMillis();
        if (now - opened < LIGHTS_MS + 50 || closing != 0 || now - focusedAt < 400) return true;
        if (Math.abs(row - rowAnim) > 0.002f) return true;
        for (Map.Entry<Integer, Float> e : colAnims.entrySet()) {
            int r = e.getKey();
            if (r >= rows.size()) continue;
            float target = r == row ? col : colByRow.getOrDefault(rowKey(r), 0);
            if (Math.abs(target - e.getValue()) > 0.002f) return true;
        }
        return false;
    }

    /** Whether it covers the whole screen (the menu needn't be drawn under it). */
    boolean covers() {
        return closing == 0 && System.currentTimeMillis() - opened > LIGHTS_MS;
    }

    // ---- drawing --------------------------------------------------------------------------------

    void paint(Graphics2D g, int w, int h) {
        long now = System.currentTimeMillis();
        float lights = Math.min(1f, (now - opened) / (float) LIGHTS_MS);
        float fade = closing != 0 ? Math.max(0f, 1 - (now - closing) / (float) CLOSE_MS) : 1f;
        Composite was = g.getComposite();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        // the lights going down: the menu fades to black first, then the screen fades up
        float dark = Math.min(1f, lights * 1.8f) * fade;
        g.setComposite(AlphaComposite.SrcOver.derive(dark));
        g.setColor(BG);
        g.fillRect(0, 0, w, h);
        float show = Math.max(0f, (lights - 0.45f) / 0.55f) * fade;
        if (show <= 0) {
            g.setComposite(was);
            return;
        }
        g.setComposite(AlphaComposite.SrcOver.derive(show));
        float u = Math.max(0.5f, Math.min(w / 1280f, h / 720f));
        hits.clear();
        Entry f = focused();
        paintHero(g, w, h, u, f, now);
        paintRows(g, w, h, u);
        paintTop(g, w, u);
        if (rows.isEmpty()) {
            g.setFont(MenuView.font(Font.PLAIN, 22 * u));
            g.setColor(DIM);
            String msg = OpenBased.lastProblem() != null ? OpenBased.lastProblem() : "Nothing on yet: add videos to OpenBased";
            g.drawString(msg, 64 * u, h * 0.5f);
        }
        // hints
        g.setFont(MenuView.font(Font.PLAIN, 13 * u));
        g.setColor(new Color(255, 255, 255, 110));
        String hint = "A  Play    B  " + (series != null ? "All titles" : "Back to the menu") + "    ◀ ▶ ▲ ▼  Browse";
        g.drawString(hint, w - g.getFontMetrics().stringWidth(hint) - 40 * u, h - 22 * u);
        g.setComposite(was);
    }

    private void paintTop(Graphics2D g, int w, float u) {
        // the wordmark, small and gold, like a marquee
        g.setFont(MenuView.font(Font.BOLD, 22 * u));
        g.setColor(GOLD);
        g.drawString("OpenBased", 48 * u, 50 * u);
        FontMetrics fm = g.getFontMetrics();
        g.setFont(MenuView.font(Font.PLAIN, 15 * u));
        g.setColor(DIM);
        String where = series != null ? series : "Cinema";
        g.drawString("  ·  " + where, 48 * u + fm.stringWidth("OpenBased"), 50 * u);
    }

    private void paintHero(Graphics2D g, int w, int h, float u, Entry f, long now) {
        float heroH = h * 0.62f;
        if (f == null) return;
        OpenBased.Item lead = f.lead();
        OpenBased.Detail d = ob.detail(lead.id(), view::repaint);
        BufferedImage back = d != null && d.backdrop() != null ? image(d.backdrop(), 1600) : null;
        float appear = Math.min(1f, (now - focusedAt) / 260f);
        Composite was = g.getComposite();
        float alpha = ((AlphaComposite) was).getAlpha();
        if (back != null) {
            // the backdrop fills the top right, fading into the dark at its left and bottom
            float bw = w * 0.72f, bh = heroH;
            double s = Math.max(bw / back.getWidth(), bh / back.getHeight());
            int iw = (int) (back.getWidth() * s), ih = (int) (back.getHeight() * s);
            g.setComposite(AlphaComposite.SrcOver.derive(alpha * (0.35f + 0.65f * appear)));
            Shape clip = g.getClip();
            g.clip(new Rectangle2D.Float(w - bw, 0, bw, bh));
            g.drawImage(back, (int) (w - iw), (int) ((bh - ih) / 2), iw, ih, null);
            g.setClip(clip);
            g.setComposite(was);
        } else {
            BufferedImage p = f.poster() != null ? image(f.poster(), 600) : null;
            if (p != null) {
                // no backdrop: the poster, large and soft, far in the background
                float bw = w * 0.6f;
                double s = bw / p.getWidth();
                int iw = (int) bw, ih = (int) (p.getHeight() * s);
                g.setComposite(AlphaComposite.SrcOver.derive(alpha * 0.28f * appear));
                Shape clip = g.getClip();
                g.clip(new Rectangle2D.Float(w - bw, 0, bw, heroH));
                g.drawImage(p, (int) (w - bw), (int) (heroH * 0.5f - ih * 0.35f), iw, ih, null);
                g.setClip(clip);
                g.setComposite(was);
            }
        }
        float edge = w * 0.28f;
        g.setPaint(new GradientPaint(edge, 0, BG, edge + w * 0.3f, 0, new Color(10, 10, 13, 0)));
        g.fillRect((int) edge - 1, 0, (int) (w * 0.3f) + 2, (int) heroH);
        g.setColor(BG);
        g.fillRect(0, 0, (int) edge, (int) heroH);
        g.setPaint(new GradientPaint(0, heroH * 0.55f, new Color(10, 10, 13, 0), 0, heroH, BG));
        g.fillRect(0, (int) (heroH * 0.55f) - 1, w, (int) (heroH * 0.45f) + 2);
        g.setPaint(new GradientPaint(0, 0, new Color(10, 10, 13, 160), 0, 90 * u, new Color(10, 10, 13, 0)));
        g.fillRect(0, 0, w, (int) (90 * u));

        // the title and what it is
        float x = 48 * u, y = heroH * 0.30f, maxW = w * 0.44f;
        g.setComposite(AlphaComposite.SrcOver.derive(alpha * appear));
        String title = f.item() != null && f.item().isEpisode() ? f.item().series() : f.title();
        g.setFont(MenuView.font(Font.BOLD, 46 * u));
        g.setColor(TEXT);
        for (String line : wrap(g, title, maxW, 2)) {
            g.drawString(line, x, y);
            y += 50 * u;
        }
        g.setFont(MenuView.font(Font.BOLD, 15 * u));
        List<String> meta = new ArrayList<>();
        if (f.item() == null) {
            long seasons = f.episodes().stream().map(e -> e.season() == null ? 0 : e.season()).distinct().count();
            meta.add(seasons > 1 ? seasons + " seasons" : f.episodes().size() + (f.episodes().size() == 1 ? " episode" : " episodes"));
        } else if (f.item().isEpisode()) {
            meta.add(se(f.item()) + " · " + epTitle(f.item()));
        }
        if (lead.year() != null) meta.add(Integer.toString(lead.year()));
        double secs = lead.seconds() > 0 ? lead.seconds() : d != null && d.runtime() != null ? d.runtime() * 60.0 : 0;
        if (secs > 0 && f.item() != null) meta.add(length(secs));
        if (d != null && !d.genres().isEmpty()) meta.add(String.join(", ", d.genres().subList(0, Math.min(3, d.genres().size()))));
        g.setColor(GOLD);
        g.drawString(ellipsize(g, String.join("   ·   ", meta), maxW), x, y);
        y += 30 * u;
        if (d != null && d.overview() != null && !d.overview().isBlank()) {
            g.setFont(MenuView.font(Font.PLAIN, 16 * u));
            g.setColor(new Color(0xD9D4CC));
            for (String line : wrap(g, d.overview(), maxW, 3)) {
                g.drawString(line, x, y);
                y += 23 * u;
            }
        }
        // the button: Play, or Resume with how far it got
        y += 14 * u;
        OpenBased.Item it = f.item() != null ? f.item() : lead;
        boolean resume = it.progress() > 0.01 && it.progress() < 0.97;
        String label = f.item() == null ? "▶  Episodes" : resume ? "▶  Resume" : "▶  Play";
        g.setFont(MenuView.font(Font.BOLD, 16 * u));
        FontMetrics fm = g.getFontMetrics();
        float bw = fm.stringWidth(label) + 44 * u, bh = 40 * u;
        g.setColor(TEXT);
        g.fill(new RoundRectangle2D.Float(x, y, bw, bh, 8 * u, 8 * u));
        g.setColor(BG);
        g.drawString(label, x + 22 * u, y + bh / 2 + fm.getAscent() * 0.36f);
        if (resume && f.item() != null) {
            float px = x + bw + 20 * u, pw = 160 * u;
            g.setColor(new Color(255, 255, 255, 50));
            g.fill(new RoundRectangle2D.Float(px, y + bh / 2 - 2 * u, pw, 4 * u, 4 * u, 4 * u));
            g.setColor(GOLD);
            g.fill(new RoundRectangle2D.Float(px, y + bh / 2 - 2 * u, (float) (pw * it.progress()), 4 * u, 4 * u, 4 * u));
            g.setFont(MenuView.font(Font.PLAIN, 13 * u));
            g.setColor(DIM);
            g.drawString(length(Math.max(0, it.seconds() - it.position())) + " left", px + pw + 12 * u, y + bh / 2 + 5 * u);
        }
        g.setComposite(was);
    }

    private void paintRows(Graphics2D g, int w, int h, float u) {
        if (rows.isEmpty()) return;
        rowAnim += (row - rowAnim) * 0.22f;
        if (Math.abs(row - rowAnim) < 0.002f) rowAnim = row;
        float top = h * 0.62f, gap = 34 * u;
        Composite was = g.getComposite();
        float alpha = ((AlphaComposite) was).getAlpha();
        float y = top;
        for (int r = 0; r < rows.size(); r++) {
            Row rw = rows.get(r);
            float cardH = rw.wide() ? h * 0.16f : h * 0.24f, cardW = rw.wide() ? cardH * 16 / 9 : cardH * 2 / 3;
            float rowH = 34 * u + cardH + gap;
            float ry = y - offset(h, u, gap);                   // the focused row sits at the top of the row area
            y += rowH;
            if (ry > h || ry + rowH < top - 10 * u) continue;
            boolean current = r == row;
            float rowAlpha = ry < top - 4 * u ? Math.max(0, 1 - (top - ry) / (rowH * 0.6f)) : 1;
            if (rowAlpha <= 0) continue;
            g.setComposite(AlphaComposite.SrcOver.derive(alpha * rowAlpha * (current ? 1f : 0.75f)));
            g.setFont(MenuView.font(Font.BOLD, 18 * u));
            g.setColor(current ? TEXT : DIM);
            g.drawString(rw.title(), 48 * u, ry + 18 * u);
            float target = r == row ? col : colByRow.getOrDefault(rowKey(r), 0);
            float ca = colAnims.getOrDefault(r, target);
            ca += (target - ca) * 0.22f;
            if (Math.abs(target - ca) < 0.002f) ca = target;
            colAnims.put(r, ca);
            float step = cardW + 16 * u;
            float x0 = 48 * u - Math.max(0, ca - 1) * step;     // the focused card stays near the left
            for (int c = 0; c < rw.entries().size(); c++) {
                float cx = x0 + c * step;
                if (cx > w || cx + cardW < -cardW) continue;
                boolean focus = current && c == col;
                paintCard(g, rw.entries().get(c), cx, ry + 30 * u, cardW, cardH, u, focus, rw.wide());
                hits.add(new Object[]{new Rectangle2D.Float(cx, ry + 30 * u, cardW, cardH), r, c});
            }
        }
        g.setComposite(was);
    }

    /** How far the rows are scrolled: the focused row sits at the top of the row area. */
    private float offset(int h, float u, float gap) {
        int below = (int) Math.floor(rowAnim);
        float frac = rowAnim - below;
        float off = rowHeightUpTo(below, h, u, gap);
        if (frac > 0 && below + 1 < rows.size() + 1) off += frac * rowHeight(below, h, u, gap);
        return off;
    }

    private float rowHeightUpTo(int r, int h, float u, float gap) {
        float sum = 0;
        for (int i = 0; i < r && i < rows.size(); i++) sum += rowHeight(i, h, u, gap);
        return sum;
    }

    private float rowHeight(int r, int h, float u, float gap) {
        if (r >= rows.size()) return 0;
        float cardH = rows.get(r).wide() ? h * 0.16f : h * 0.24f;
        return 34 * u + cardH + gap;
    }

    private void paintCard(Graphics2D g, Entry e, float x, float y, float cw, float ch, float u, boolean focus, boolean wide) {
        Composite was = g.getComposite();
        float grow = focus ? 1.07f : 1f;
        float gx = x - cw * (grow - 1) / 2, gy = y - ch * (grow - 1) / 2, gw = cw * grow, gh = ch * grow;
        RoundRectangle2D card = new RoundRectangle2D.Float(gx, gy, gw, gh, 10 * u, 10 * u);
        if (focus) {                                        // a soft glow under the focused one
            for (int i = 3; i >= 1; i--) {
                g.setColor(new Color(242, 184, 75, 18));
                float s = i * 4 * u;
                g.fill(new RoundRectangle2D.Float(gx - s, gy - s, gw + 2 * s, gh + 2 * s, 14 * u + s, 14 * u + s));
            }
        }
        g.setColor(new Color(0x1C1B21));
        g.fill(card);
        Path p = e.poster();
        BufferedImage img = p != null ? image(p, 480) : null;
        if (wide && e.item() != null) {
            OpenBased.Detail d = ob.detail(e.item().id(), view::repaint);
            if (d != null && d.backdrop() != null) img = image(d.backdrop(), 900);
        }
        Shape clip = g.getClip();
        g.clip(card);
        if (img != null) {
            double s = Math.max(gw / img.getWidth(), gh / img.getHeight());
            int iw = (int) (img.getWidth() * s), ih = (int) (img.getHeight() * s);
            g.drawImage(img, (int) (gx + (gw - iw) / 2), (int) (gy + (gh - ih) / (wide ? 2 : 3)), iw, ih, null);
        }
        boolean caption = wide || img == null;
        if (caption) {
            g.setPaint(new GradientPaint(0, gy + gh * 0.45f, new Color(0, 0, 0, 0), 0, gy + gh, new Color(0, 0, 0, 215)));
            g.fill(new Rectangle2D.Float(gx, gy + gh * 0.45f, gw, gh * 0.55f));
            g.setFont(MenuView.font(Font.BOLD, 14 * u));
            g.setColor(TEXT);
            OpenBased.Item it = e.item();
            String line1 = it != null && it.isEpisode() ? (series != null ? epTitle(it) : it.series()) : e.title();
            String line2 = it != null && it.isEpisode() ? se(it) + (series != null ? "" : " · " + epTitle(it))
                    : it != null && it.year() != null ? Integer.toString(it.year()) : "";
            g.drawString(ellipsize(g, line1, gw - 20 * u), gx + 10 * u, gy + gh - (line2.isEmpty() ? 14 : 30) * u);
            if (!line2.isEmpty()) {
                g.setFont(MenuView.font(Font.PLAIN, 12 * u));
                g.setColor(DIM);
                g.drawString(ellipsize(g, line2, gw - 20 * u), gx + 10 * u, gy + gh - 13 * u);
            }
        }
        OpenBased.Item it = e.item();
        if (it != null && it.progress() > 0.01) {
            g.setColor(new Color(255, 255, 255, 70));
            g.fill(new Rectangle2D.Float(gx, gy + gh - 4 * u, gw, 4 * u));
            g.setColor(GOLD);
            g.fill(new Rectangle2D.Float(gx, gy + gh - 4 * u, (float) (gw * it.progress()), 4 * u));
        }
        g.setClip(clip);
        if (focus) {
            g.setColor(TEXT);
            g.setStroke(new BasicStroke(3f * u));
            g.draw(card);
        }
        g.setComposite(was);
    }

    // ---- helpers --------------------------------------------------------------------------------

    /** A picture, loaded in the background (null until it's there), at most {@code max} pixels wide. */
    private BufferedImage image(Path p, int max) {
        BufferedImage img = images.get(p);
        if (img != null || loading.putIfAbsent(p, true) != null) return img;
        loader.execute(() -> {
            try {
                BufferedImage raw = ImageIO.read(p.toFile());
                if (raw != null) {
                    double s = Math.min(1.0, (double) max / raw.getWidth());
                    int iw = Math.max(1, (int) (raw.getWidth() * s)), ih = Math.max(1, (int) (raw.getHeight() * s));
                    BufferedImage out = new BufferedImage(iw, ih, BufferedImage.TYPE_INT_RGB);
                    Graphics2D g = out.createGraphics();
                    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    g.drawImage(raw, 0, 0, iw, ih, null);
                    g.dispose();
                    images.put(p, out);
                    view.repaint();
                }
            } catch (Exception ignored) {
                // no picture: the card shows its title
            }
        });
        return null;
    }

    /** An episode's own title, or "Episode 2" when it has only a made-up one ("Show S01E02": no details found). */
    private static String epTitle(OpenBased.Item i) {
        String t = i.title() == null ? "" : i.title().trim();
        String generic = "(?i)(" + java.util.regex.Pattern.quote(i.series() == null ? "" : i.series()) + ")?[\\s._-]*S\\d+\\s*E\\d+";
        if (t.isEmpty() || t.equalsIgnoreCase(i.series()) || t.matches(generic)) return "Episode " + (i.episode() == null ? "" : i.episode());
        return t;
    }

    private static String se(OpenBased.Item i) {
        return String.format(Locale.ROOT, "S%d · E%d", i.season() == null ? 0 : i.season(), i.episode() == null ? 0 : i.episode());
    }

    private static String length(double secs) {
        int m = (int) Math.round(secs / 60);
        return m >= 60 ? m / 60 + " h " + (m % 60 > 0 ? m % 60 + " min" : "") : Math.max(1, m) + " min";
    }

    private static List<String> wrap(Graphics2D g, String text, float maxW, int maxLines) {
        FontMetrics fm = g.getFontMetrics();
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.trim().split("\\s+")) {
            String next = line.length() == 0 ? word : line + " " + word;
            if (fm.stringWidth(next) <= maxW || line.length() == 0) {
                line.setLength(0);
                line.append(next);
            } else {
                lines.add(line.toString());
                line.setLength(0);
                line.append(word);
                if (lines.size() == maxLines) break;
            }
        }
        if (lines.size() < maxLines && line.length() > 0) lines.add(line.toString());
        else if (lines.size() == maxLines && line.length() > 0) {
            String last = lines.get(maxLines - 1);
            lines.set(maxLines - 1, ellipsize(g, last + " " + line, maxW));
        }
        return lines;
    }

    private static String ellipsize(Graphics2D g, String s, float maxW) {
        FontMetrics fm = g.getFontMetrics();
        if (fm.stringWidth(s) <= maxW) return s;
        while (s.length() > 1 && fm.stringWidth(s + "…") > maxW) s = s.substring(0, s.length() - 1);
        return s + "…";
    }
}
