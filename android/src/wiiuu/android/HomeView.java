package wiiuu.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * WII-UU's menu on a handheld, drawn the way WII-UU draws it on a TV: the consoles as tiles
 * (their colour on top, the name and how many games under it), a console's games, and Settings.
 * The d-pad or stick moves, the bottom button (A on most handhelds) opens, the right one goes back;
 * on a touch screen a tap opens and a drag scrolls.
 */
final class HomeView extends View {
    /** What the menu asks of its activity. */
    interface Host {
        void play(Library.Game game);

        List<Row> settings();

        void refresh();
    }

    /** A line of Settings: a label, its value now (or null), what A does. */
    static final class Row {
        final String label;
        final java.util.function.Supplier<String> value;
        final Runnable action;
        final String help;

        Row(String label, String help, java.util.function.Supplier<String> value, Runnable action) {
            this.label = label;
            this.help = help;
            this.value = value;
            this.action = action;
        }
    }

    private static final int BLUE = 0xFF00A9E6, INK = 0xFF3C4043, GREY = 0xFF8A9099, WHITE = 0xFFFFFFFF;

    private enum Mode { HOME, GAMES, SETTINGS }

    private final Host host;
    private final Library library;
    private Mode mode = Mode.HOME;
    private Consoles.Console console;
    private int homeSel, gameSel, setSel;
    private float scroll, scrollTarget;
    private List<Row> rows = new ArrayList<>();
    private String message, messageTitle;
    private int progress = -1;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService coverLoader = Executors.newSingleThreadExecutor();
    private final Map<File, Bitmap> covers = new HashMap<>();

    HomeView(Context c, Host host, Library library) {
        super(c);
        this.host = host;
        this.library = library;
        setFocusable(true);
        setFocusableInTouchMode(true);
        tick();
    }

    private void tick() {                                     // the clock in the corner
        invalidate();
        ui.postDelayed(this::tick, 20_000);
    }

    // ---- what the activity tells it ----

    void libraryChanged() {
        coverFiles.clear();
        invalidate();
    }

    /** A message over the menu (null: none); progress 0-100 shows a bar, -1 none. */
    void message(String title, String text, int progress) {
        this.messageTitle = title;
        this.message = text;
        this.progress = progress;
        invalidate();
    }

    boolean backToHome() {
        if (message != null && progress < 0) {
            message(null, null, -1);
            return true;
        }
        if (mode == Mode.HOME) return false;
        mode = Mode.HOME;
        scroll = scrollTarget = 0;
        invalidate();
        return true;
    }

    void openSettings() {
        rows = host.settings();
        mode = Mode.SETTINGS;
        setSel = 0;
        scroll = scrollTarget = 0;
        invalidate();
    }

    // ---- sizes ----

    private float u() {                                      // one hundredth of the shorter side
        return Math.min(getWidth(), getHeight()) / 100f;
    }

    private int cols() {
        if (mode == Mode.SETTINGS) return 1;
        return getWidth() > getHeight() ? 5 : 3;
    }

    private int count() {
        switch (mode) {
            case HOME: return Consoles.ALL.size();
            case GAMES: return library.count(console.id);
            default: return rows.size();
        }
    }

    private int sel() {
        return mode == Mode.HOME ? homeSel : mode == Mode.GAMES ? gameSel : setSel;
    }

    private void setSel(int i) {
        int n = count();
        if (n == 0) return;
        i = Math.max(0, Math.min(n - 1, i));
        if (mode == Mode.HOME) homeSel = i;
        else if (mode == Mode.GAMES) gameSel = i;
        else setSel = i;
        keepInView();
        invalidate();
    }

    private float top() {
        return u() * 16;
    }

    private float bottom() {
        return getHeight() - u() * 9;
    }

    private float cellW() {
        return (getWidth() - u() * 8) / cols();
    }

    private float cellH() {
        return mode == Mode.SETTINGS ? u() * 11 : cellW() * 0.82f;
    }

    private void keepInView() {
        int row = sel() / cols();
        float y = row * cellH(), h = bottom() - top();
        if (y - scrollTarget < 0) scrollTarget = y;
        if (y + cellH() - scrollTarget > h) scrollTarget = y + cellH() - h;
        scrollTarget = Math.max(0, scrollTarget);
    }

    // ---- drawing ----

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), u = u();
        // the Wii U's white, with faint stripes
        paint.setShader(new LinearGradient(0, 0, 0, h, 0xFFF7F8FA, 0xFFE3E7EC, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, paint);
        paint.setShader(null);
        paint.setColor(0x0A000000);
        for (float y = 0; y < h; y += u * 1.2f) c.drawRect(0, y, w, y + u * 0.3f, paint);

        if (Math.abs(scroll - scrollTarget) > 0.5f) {
            scroll += (scrollTarget - scroll) * 0.35f;
            postInvalidateOnAnimation();
        } else {
            scroll = scrollTarget;
        }
        c.save();
        c.clipRect(0, top() - u * 2, w, bottom());
        if (mode == Mode.SETTINGS) drawSettings(c);
        else drawGrid(c);
        c.restore();
        drawTop(c);
        drawHints(c);
        if (message != null) drawMessage(c);
    }

    private void pill(Canvas c, float x, float y, float pw, float ph) {
        paint.setColor(0x22000000);
        r.set(x, y + ph * 0.06f, x + pw, y + ph * 1.06f);
        c.drawRoundRect(r, ph / 2, ph / 2, paint);
        paint.setColor(WHITE);
        r.set(x, y, x + pw, y + ph);
        c.drawRoundRect(r, ph / 2, ph / 2, paint);
    }

    private void drawTop(Canvas c) {
        float u = u(), ph = u * 8, y = u * 3;
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        paint.setTextSize(u * 4.2f);
        String where = mode == Mode.HOME ? "Home" : mode == Mode.GAMES ? console.name : "Settings";
        paint.setTypeface(Typeface.DEFAULT);
        float whereW = paint.measureText(where);
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        float logoW = paint.measureText("WII-UU");
        float pw = logoW + whereW + u * 9;
        pill(c, u * 3, y, pw, ph);
        float ty = y + ph / 2 + u * 1.5f;
        paint.setColor(INK);
        c.drawText("WII-", u * 6, ty, paint);
        paint.setColor(BLUE);
        c.drawText("UU", u * 6 + paint.measureText("WII-"), ty, paint);
        paint.setColor(0xFFC8CCD2);
        c.drawRect(u * 7 + logoW, y + ph * 0.25f, u * 7 + logoW + u * 0.25f, y + ph * 0.75f, paint);
        paint.setTypeface(Typeface.DEFAULT);
        paint.setColor(GREY);
        c.drawText(where, u * 8.5f + logoW, ty, paint);
        // the clock
        String time = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        float cw = paint.measureText(time) + u * 6;
        pill(c, getWidth() - u * 3 - cw, y, cw, ph);
        paint.setColor(INK);
        c.drawText(time, getWidth() - u * 3 - cw + u * 3, ty, paint);
    }

    private void drawHints(Canvas c) {
        float u = u();
        paint.setTypeface(Typeface.DEFAULT);
        paint.setTextSize(u * 3.2f);
        paint.setColor(GREY);
        String hint = mode == Mode.HOME ? "A: Open    Start: Settings    Select: Look for games again"
                : mode == Mode.GAMES ? "A: Play    B: Back" : "A: Choose    B: Back";
        c.drawText(hint, u * 4, getHeight() - u * 3, paint);
        if (mode == Mode.SETTINGS && setSel < rows.size() && rows.get(setSel).help != null) {
            String help = rows.get(setSel).help;
            paint.setTextSize(u * 2.8f);
            float tw = paint.measureText(help);
            c.drawText(help, Math.max(u * 4, getWidth() - u * 4 - tw), getHeight() - u * 3, paint);
        }
    }

    private void drawGrid(Canvas c) {
        float u = u(), cw = cellW(), ch = cellH(), x0 = u * 4, y0 = top() - scroll;
        int n = count(), cols = cols();
        if (mode == Mode.GAMES && n == 0) {
            paint.setTextSize(u * 4);
            paint.setColor(GREY);
            paint.setTypeface(Typeface.DEFAULT);
            String a = "No " + console.shortName + " games yet.", b = "Copy them into " + Store.roms(getContext(), console.id);
            c.drawText(a, x0 + u * 2, top() + u * 8, paint);
            paint.setTextSize(u * 3);
            c.drawText(b, x0 + u * 2, top() + u * 14, paint);
            return;
        }
        for (int i = 0; i < n; i++) {
            float x = x0 + (i % cols) * cw, y = y0 + (i / cols) * ch;
            if (y + ch < top() - u * 2 || y > bottom()) continue;
            if (mode == Mode.HOME) drawConsole(c, Consoles.ALL.get(i), x + u, y + u, cw - u * 2, ch - u * 2, i == homeSel);
            else drawGame(c, library.games(console.id).get(i), x + u, y + u, cw - u * 2, ch - u * 2, i == gameSel);
        }
    }

    private void tileShell(Canvas c, float x, float y, float tw, float th, boolean selected) {
        float u = u(), rad = u * 1.6f;
        if (selected) {
            paint.setColor(0x5500A9E6);
            r.set(x - u * 1.2f, y - u * 1.2f, x + tw + u * 1.2f, y + th + u * 1.2f);
            c.drawRoundRect(r, rad * 1.6f, rad * 1.6f, paint);
        }
        paint.setColor(0x26000000);
        r.set(x, y + u * 0.5f, x + tw, y + th + u * 0.5f);
        c.drawRoundRect(r, rad, rad, paint);
        paint.setColor(WHITE);
        r.set(x, y, x + tw, y + th);
        c.drawRoundRect(r, rad, rad, paint);
        if (selected) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(u * 0.9f);
            paint.setColor(BLUE);
            r.set(x - u * 0.45f, y - u * 0.45f, x + tw + u * 0.45f, y + th + u * 0.45f);
            c.drawRoundRect(r, rad * 1.3f, rad * 1.3f, paint);
            paint.setStyle(Paint.Style.FILL);
        }
    }

    private void colourTop(Canvas c, float x, float y, float tw, float top, int color) {
        float u = u(), rad = u * 1.6f;
        paint.setShader(new LinearGradient(0, y, 0, y + top, lighter(color), color, Shader.TileMode.CLAMP));
        r.set(x, y, x + tw, y + top + rad);
        c.save();
        c.clipRect(x, y, x + tw, y + top);
        c.drawRoundRect(r, rad, rad, paint);
        c.restore();
        paint.setShader(null);
    }

    private static int lighter(int c) {
        int rr = (c >> 16 & 0xff), g = (c >> 8 & 0xff), b = c & 0xff;
        rr += (255 - rr) / 4;
        g += (255 - g) / 4;
        b += (255 - b) / 4;
        return 0xFF000000 | rr << 16 | g << 8 | b;
    }

    private void centred(Canvas c, String s, float cx, float y, float maxW) {
        while (paint.measureText(s) > maxW && s.length() > 2) s = s.substring(0, s.length() - 2) + "…";
        c.drawText(s, cx - paint.measureText(s) / 2, y, paint);
    }

    private void drawConsole(Canvas c, Consoles.Console k, float x, float y, float tw, float th, boolean selected) {
        float u = u(), top = th * 0.62f;
        tileShell(c, x, y, tw, th, selected);
        colourTop(c, x, y, tw, top, k.color);
        paint.setColor(0xCCFFFFFF);
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        paint.setTextSize(u * 2.2f);
        c.drawText(k.maker.toUpperCase(Locale.ROOT), x + u * 1.5f, y + u * 3.2f, paint);
        String year = Integer.toString(k.year);
        c.drawText(year, x + tw - u * 1.5f - paint.measureText(year), y + u * 3.2f, paint);
        paint.setColor(WHITE);
        paint.setTextSize(Math.min(top * 0.45f, tw / Math.max(3, k.shortName.length()) * 1.3f));
        centred(c, k.shortName, x + tw / 2, y + top * 0.72f, tw - u * 2);
        paint.setColor(INK);
        paint.setTextSize(u * 2.9f);
        centred(c, k.name, x + tw / 2, y + top + (th - top) * 0.45f, tw - u * 2);
        int n = library.count(k.id);
        paint.setTypeface(Typeface.DEFAULT);
        paint.setTextSize(u * 2.4f);
        paint.setColor(n > 0 ? BLUE : GREY);
        centred(c, n == 0 ? "No games yet" : n == 1 ? "1 game" : n + " games", x + tw / 2, y + top + (th - top) * 0.8f, tw - u * 2);
    }

    private void drawGame(Canvas c, Library.Game g, float x, float y, float tw, float th, boolean selected) {
        float u = u(), top = th * 0.72f;
        tileShell(c, x, y, tw, th, selected);
        Bitmap cover = cover(g);
        if (cover != null) {
            c.save();
            r.set(x, y, x + tw, y + top);
            c.clipRect(r);
            float s = Math.max(tw / cover.getWidth(), top / cover.getHeight());
            float cwid = cover.getWidth() * s, chei = cover.getHeight() * s;
            r.set(x + (tw - cwid) / 2, y + (top - chei) / 2, x + (tw + cwid) / 2, y + (top + chei) / 2);
            c.drawBitmap(cover, null, r, paint);
            c.restore();
        } else {
            colourTop(c, x, y, tw, top, g.console.color);
            paint.setColor(WHITE);
            paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            paint.setTextSize(top * 0.42f);
            centred(c, initials(g.name), x + tw / 2, y + top * 0.66f, tw - u * 2);
            paint.setColor(0xCCFFFFFF);
            paint.setTextSize(u * 2.2f);
            c.drawText(g.console.shortName, x + u * 1.5f, y + u * 3.2f, paint);
        }
        paint.setColor(INK);
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        paint.setTextSize(u * 2.7f);
        centred(c, g.name, x + tw / 2, y + top + (th - top) * 0.62f, tw - u * 2);
    }

    /** "Super Mario World" -> "SMW" (as WII-UU's tiles without a cover). */
    static String initials(String name) {
        StringBuilder b = new StringBuilder();
        for (String w : name.replaceAll("\\(.*?\\)|\\[.*?\\]", " ").split("[\\s_-]+")) {
            if (!w.isEmpty() && Character.isLetterOrDigit(w.charAt(0))) b.append(Character.toUpperCase(w.charAt(0)));
            if (b.length() == 3) break;
        }
        return b.length() == 0 ? "?" : b.toString();
    }

    private final Map<File, File> coverFiles = new HashMap<>();

    private Bitmap cover(Library.Game g) {
        File f;
        if (coverFiles.containsKey(g.file)) f = coverFiles.get(g.file);
        else coverFiles.put(g.file, f = g.cover(getContext()));     // looked for once (until the next scan)
        if (f == null) return null;
        synchronized (covers) {
            if (covers.containsKey(f)) return covers.get(f);
            covers.put(f, null);                             // loading
        }
        coverLoader.execute(() -> {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 2;
            Bitmap b = BitmapFactory.decodeFile(f.getPath(), o);
            synchronized (covers) {
                covers.put(f, b);
            }
            postInvalidate();
        });
        return null;
    }

    private void drawSettings(Canvas c) {
        float u = u(), x = u * 5, w = getWidth() - u * 10, rh = cellH(), y0 = top() - scroll;
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            float y = y0 + i * rh;
            if (y + rh < top() - u * 2 || y > bottom()) continue;
            paint.setColor(i == setSel ? 0xFFF2F3F5 : 0xFFFAFAFB);
            r.set(x, y + u * 0.6f, x + w, y + rh - u * 0.6f);
            c.drawRoundRect(r, u * 1.6f, u * 1.6f, paint);
            if (i == setSel) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(u * 0.6f);
                paint.setColor(BLUE);
                c.drawRoundRect(r, u * 1.6f, u * 1.6f, paint);
                paint.setStyle(Paint.Style.FILL);
            }
            paint.setTypeface(Typeface.DEFAULT);
            paint.setTextSize(u * 3.6f);
            paint.setColor(INK);
            float ty = y + rh / 2 + u * 1.2f;
            c.drawText(row.label, x + u * 3, ty, paint);
            String v = row.value == null ? null : row.value.get();
            if (v != null) {
                paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
                paint.setTextSize(u * 3.2f);
                paint.setColor(GREY);
                float max = w * 0.55f;
                while (paint.measureText(v) > max && v.length() > 4) v = "…" + v.substring(4);
                c.drawText(v, x + w - u * 3 - paint.measureText(v), ty, paint);
            }
        }
    }

    private void drawMessage(Canvas c) {
        float u = u(), w = getWidth(), h = getHeight();
        paint.setColor(0x88000000);
        c.drawRect(0, 0, w, h, paint);
        float bw = Math.min(w - u * 10, u * 120), bh = u * (progress >= 0 ? 34 : 30), x = (w - bw) / 2, y = (h - bh) / 2;
        paint.setColor(WHITE);
        r.set(x, y, x + bw, y + bh);
        c.drawRoundRect(r, u * 3, u * 3, paint);
        paint.setColor(INK);
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        paint.setTextSize(u * 4.4f);
        if (messageTitle != null) c.drawText(messageTitle, x + u * 5, y + u * 9, paint);
        paint.setTypeface(Typeface.DEFAULT);
        paint.setTextSize(u * 3.3f);
        float ty = y + u * 15;
        for (String line : wrap(message, bw - u * 10)) {
            c.drawText(line, x + u * 5, ty, paint);
            ty += u * 4.6f;
        }
        if (progress >= 0) {
            paint.setColor(0xFFE3E7EC);
            r.set(x + u * 5, y + bh - u * 8, x + bw - u * 5, y + bh - u * 5);
            c.drawRoundRect(r, u * 1.5f, u * 1.5f, paint);
            paint.setColor(BLUE);
            r.set(x + u * 5, y + bh - u * 8, x + u * 5 + (bw - u * 10) * progress / 100f, y + bh - u * 5);
            c.drawRoundRect(r, u * 1.5f, u * 1.5f, paint);
        } else {
            paint.setColor(GREY);
            paint.setTextSize(u * 3f);
            c.drawText("A or B: OK", x + bw - u * 5 - paint.measureText("A or B: OK"), y + bh - u * 4, paint);
        }
    }

    private List<String> wrap(String text, float max) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        for (String para : text.split("\n")) {
            StringBuilder line = new StringBuilder();
            for (String word : para.split(" ")) {
                String tryLine = line.length() == 0 ? word : line + " " + word;
                if (paint.measureText(tryLine) > max && line.length() > 0) {
                    out.add(line.toString());
                    line = new StringBuilder(word);
                } else {
                    line = new StringBuilder(tryLine);
                }
            }
            out.add(line.toString());
        }
        return out.size() > 4 ? out.subList(0, 4) : out;
    }

    // ---- input ----

    private void activate() {
        if (message != null) {
            if (progress < 0) message(null, null, -1);
            return;
        }
        switch (mode) {
            case HOME:
                console = Consoles.ALL.get(homeSel);
                mode = Mode.GAMES;
                gameSel = 0;
                scroll = scrollTarget = 0;
                break;
            case GAMES:
                if (gameSel < library.count(console.id)) host.play(library.games(console.id).get(gameSel));
                break;
            case SETTINGS:
                if (setSel < rows.size() && rows.get(setSel).action != null) rows.get(setSel).action.run();
                break;
        }
        invalidate();
    }

    private void move(int dx, int dy) {
        if (message != null) return;
        int cols = cols(), i = sel();
        if (dx != 0 && cols > 1) {
            int col = i % cols + dx;
            if (col >= 0 && col < cols) setSel(i + dx);
        }
        if (dy != 0) setSel(i + dy * cols);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP: move(0, -1); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: move(0, 1); return true;
            case KeyEvent.KEYCODE_DPAD_LEFT: move(-1, 0); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: move(1, 0); return true;
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                activate();
                return true;
            case KeyEvent.KEYCODE_BUTTON_B:
            case KeyEvent.KEYCODE_ESCAPE:
                return backToHome();
            case KeyEvent.KEYCODE_BUTTON_START:
                if (mode != Mode.SETTINGS && message == null) openSettings();
                return true;
            case KeyEvent.KEYCODE_BUTTON_SELECT:
                if (message == null) host.refresh();
                return true;
            case KeyEvent.KEYCODE_BUTTON_L1:
                move(0, -3);
                return true;
            case KeyEvent.KEYCODE_BUTTON_R1:
                move(0, 3);
                return true;
            default:
                return super.onKeyDown(keyCode, e);
        }
    }

    // sticks: one step, then repeats while held
    private int heldDx, heldDy;
    private final Runnable repeat = new Runnable() {
        @Override
        public void run() {
            if (heldDx == 0 && heldDy == 0) return;
            move(heldDx, heldDy);
            ui.postDelayed(this, 110);
        }
    };

    @Override
    public boolean onGenericMotionEvent(MotionEvent e) {
        if (!Pad.fromController(e.getSource()) || e.getAction() != MotionEvent.ACTION_MOVE) return super.onGenericMotionEvent(e);
        int[] d = Pad.direction(e);
        if (d[0] != heldDx || d[1] != heldDy) {
            heldDx = d[0];
            heldDy = d[1];
            ui.removeCallbacks(repeat);
            if (heldDx != 0 || heldDy != 0) {
                move(heldDx, heldDy);
                ui.postDelayed(repeat, 300);
            }
        }
        return true;
    }

    private float downX, downY, downScroll;
    private boolean dragged;

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                downScroll = scrollTarget;
                dragged = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (Math.abs(e.getY() - downY) > u() * 2) dragged = true;
                if (dragged && message == null) {
                    float max = Math.max(0, (float) Math.ceil(count() / (double) cols()) * cellH() - (bottom() - top()));
                    scrollTarget = scroll = Math.max(0, Math.min(max, downScroll - (e.getY() - downY)));
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (!dragged) tap(e.getX(), e.getY());
                return true;
            default:
                return true;
        }
    }

    private void tap(float x, float y) {
        if (message != null) {
            if (progress < 0) message(null, null, -1);
            return;
        }
        float u = u();
        if (y < top() - u * 2) {                              // the bar at the top: back home
            backToHome();
            return;
        }
        if (y > bottom()) {
            if (mode == Mode.HOME) openSettings();
            return;
        }
        int col = (int) ((x - u * 4) / cellW()), row = (int) ((y - top() + scroll) / cellH());
        if (col < 0 || col >= cols()) return;
        int i = row * cols() + col;
        if (i < 0 || i >= count()) return;
        setSel(i);
        activate();
    }
}
