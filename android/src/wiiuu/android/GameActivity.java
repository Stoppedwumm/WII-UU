package wiiuu.android;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.util.HashMap;
import java.util.Map;

/**
 * Choccy Milk playing a game: the picture (as big as fits at the game's shape, or filling the
 * screen), the handheld's buttons as the RetroPad's, a menu on Back / Home / Select + Start (save
 * states, quit), touch controls on a phone. Runs in its own process (":game"): a crashing core
 * only takes the game along, and each game starts with a fresh core.
 */
public final class GameActivity extends Activity implements SurfaceHolder.Callback {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private SurfaceView surface;
    private Hud hud;
    private TouchPad touch;
    private boolean stretch, started, quitting;
    private float aspect = 4f / 3;
    private String name;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        Intent in = getIntent();
        name = in.getStringExtra("name");
        stretch = in.getBooleanExtra("stretch", false);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        surface = new SurfaceView(this);
        surface.getHolder().addCallback(this);
        root.addView(surface, new FrameLayout.LayoutParams(1, 1, Gravity.CENTER));
        String touchMode = in.getStringExtra("touch");
        touch = new TouchPad(this);
        boolean showTouch = "always".equals(touchMode) || "auto".equals(touchMode) && !hasController();
        touch.setVisibility(showTouch ? View.VISIBLE : View.GONE);
        root.addView(touch, new FrameLayout.LayoutParams(-1, -1));
        hud = new Hud(this);
        root.addView(hud, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        fullscreen();
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> fit());

        Choccy.sharp(in.getBooleanExtra("sharp", true));
        if (!Choccy.start(in.getStringExtra("core"), in.getStringExtra("rom"), in.getStringExtra("saves"),
                in.getStringExtra("system"), in.getStringExtra("options"))) {
            finishAndQuit();
            return;
        }
        started = true;
        hud.notice("Choccy Milk · " + name + " · Back / Home: menu");
        ui.post(poll);
    }

    private boolean hasController() {
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice d = InputDevice.getDevice(id);
            if (d != null && !d.isVirtual() && Pad.fromController(d.getSources())) return true;
        }
        return false;
    }

    private void fullscreen() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    /** The picture as big as fits at its shape (or the whole screen). */
    private void fit() {
        View root = (View) surface.getParent();
        int w = root.getWidth(), h = root.getHeight();
        if (w == 0 || h == 0) return;
        int pw = w, ph = h;
        if (!stretch) {
            if (w / (float) h > aspect) pw = Math.round(h * aspect);
            else ph = Math.round(w / aspect);
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) surface.getLayoutParams();
        if (lp.width != pw || lp.height != ph) {
            lp.width = pw;
            lp.height = ph;
            surface.setLayoutParams(lp);
        }
    }

    /** Messages from the game, its shape, and whether it still plays. */
    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (!started) return;
            for (String n; (n = Choccy.nextNotice()) != null; ) hud.notice(n);
            float a = Choccy.aspect();
            if (a > 0.3f && a < 4 && Math.abs(a - aspect) > 0.01f) {
                aspect = a;
                fit();
            }
            if (!Choccy.running() && !quitting) {
                String why = Choccy.ending();
                if (why != null && !why.isEmpty()) {
                    hud.fatal(why);
                    return;
                }
                finishAndQuit();
                return;
            }
            ui.postDelayed(this, 100);
        }
    };

    @Override
    public void surfaceCreated(SurfaceHolder holder) {}

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
        Choccy.surface(holder.getSurface(), w, h);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        Choccy.surface(null, 0, 0);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (started) Choccy.pause(true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        fullscreen();
        if (started && !hud.menuOpen()) Choccy.pause(false);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) fullscreen();
    }

    /** Saves and stops the game, then ends this process (the next game gets a fresh core). */
    void finishAndQuit() {
        if (quitting) return;
        quitting = true;
        new Thread(() -> {
            Choccy.quit();
            ui.post(() -> {
                finish();
                ui.postDelayed(() -> android.os.Process.killProcess(android.os.Process.myPid()), 400);
            });
        }, "choccy-quit").start();
    }

    @Override
    public void onBackPressed() {
        hud.toggleMenu();
    }

    // ---- controls: a port per controller, in the order they're first used ----

    private final Map<Integer, Integer> ports = new HashMap<>();
    private final int[] buttons = new int[4], motionButtons = new int[4];
    private final int[][] sticks = new int[4][4];

    private int port(int deviceId) {
        Integer p = ports.get(deviceId);
        if (p == null) {
            p = Math.min(3, ports.size());
            ports.put(deviceId, p);
        }
        return p;
    }

    void send(int port) {
        int[] s = sticks[port];
        Choccy.input(port, buttons[port] | motionButtons[port] | (port == 0 ? touch.buttons() : 0), s[0], s[1], s[2], s[3]);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_BUTTON_MODE || code == KeyEvent.KEYCODE_ESCAPE) {
            if (down && e.getRepeatCount() == 0) hud.toggleMenu();
            return true;
        }
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_MUTE) {
            return super.dispatchKeyEvent(e);
        }
        if (hud.menuOpen()) {
            if (down) hud.menuKey(code);
            return true;
        }
        int bit = Pad.button(code);
        if (bit == 0) return super.dispatchKeyEvent(e);
        if (Pad.fromController(e.getSource()) && touch.getVisibility() == View.VISIBLE && "auto".equals(getIntent().getStringExtra("touch"))) {
            touch.setVisibility(View.GONE);                   // a controller is in use: the touch buttons go
        }
        int p = port(e.getDeviceId());
        if (e.getAction() == KeyEvent.ACTION_DOWN) buttons[p] |= bit;
        else if (e.getAction() == KeyEvent.ACTION_UP) buttons[p] &= ~bit;
        // Select + Start together: the menu (handhelds without a Home button)
        if ((buttons[p] & (Choccy.SELECT | Choccy.START)) == (Choccy.SELECT | Choccy.START)) {
            buttons[p] = 0;
            send(p);
            hud.toggleMenu();
            return true;
        }
        send(p);
        return true;
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent e) {
        if (!Pad.fromController(e.getSource()) || e.getAction() != MotionEvent.ACTION_MOVE) return super.dispatchGenericMotionEvent(e);
        if (hud.menuOpen()) {
            int[] d = Pad.direction(e);
            if (d[1] != 0) hud.menuStick(d[1]);
            else hud.menuStick(0);
            return true;
        }
        int p = port(e.getDeviceId());
        int[] s = sticks[p];
        s[0] = Pad.axis(e.getAxisValue(MotionEvent.AXIS_X));
        s[1] = Pad.axis(e.getAxisValue(MotionEvent.AXIS_Y));
        s[2] = Pad.axis(e.getAxisValue(MotionEvent.AXIS_Z));
        s[3] = Pad.axis(e.getAxisValue(MotionEvent.AXIS_RZ));
        motionButtons[p] = Pad.fromMotion(e);
        send(p);
        return true;
    }

    // ---- the menu and messages over the game ----

    private final class Hud extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private String notice, fatal;
        private long noticeUntil;
        private boolean menu;
        private int sel, slot;
        private final String[] items = {"Resume", "Save state", "Load state", "State slot", "Quit game"};

        Hud(android.content.Context c) {
            super(c);
        }

        boolean menuOpen() {
            return menu || fatal != null;
        }

        void notice(String text) {
            notice = text;
            noticeUntil = System.currentTimeMillis() + 2500;
            invalidate();
            ui.postDelayed(this::invalidate, 2600);
        }

        void fatal(String why) {
            fatal = why;
            menu = false;
            invalidate();
        }

        void toggleMenu() {
            if (fatal != null) {
                finishAndQuit();
                return;
            }
            menu = !menu;
            sel = 0;
            Choccy.pause(menu);
            invalidate();
        }

        void menuKey(int code) {
            if (fatal != null) {
                finishAndQuit();
                return;
            }
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_UP: sel = (sel + items.length - 1) % items.length; break;
                case KeyEvent.KEYCODE_DPAD_DOWN: sel = (sel + 1) % items.length; break;
                case KeyEvent.KEYCODE_DPAD_LEFT: if (sel == 3) slot = (slot + 9) % 10; break;
                case KeyEvent.KEYCODE_DPAD_RIGHT: if (sel == 3) slot = (slot + 1) % 10; break;
                case KeyEvent.KEYCODE_BUTTON_A:
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    choose();
                    break;
                case KeyEvent.KEYCODE_BUTTON_B:
                    toggleMenu();
                    break;
                default:
                    break;
            }
            invalidate();
        }

        private int stickDir;

        void menuStick(int dy) {
            if (dy != 0 && dy != stickDir) {
                sel = (sel + items.length + dy) % items.length;
                invalidate();
            }
            stickDir = dy;
        }

        private void choose() {
            switch (sel) {
                case 0: toggleMenu(); break;
                case 1: Choccy.state(true, slot); toggleMenu(); break;
                case 2: Choccy.state(false, slot); toggleMenu(); break;
                case 3: slot = (slot + 1) % 10; break;
                case 4: menu = false; finishAndQuit(); break;
                default: break;
            }
            invalidate();
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (!menuOpen()) return false;
            if (e.getActionMasked() != MotionEvent.ACTION_UP) return true;
            if (fatal != null) {
                finishAndQuit();
                return true;
            }
            float u = Math.min(getWidth(), getHeight()) / 100f, bw = u * 70, x = (getWidth() - bw) / 2;
            float y0 = getHeight() / 2f - items.length * u * 5.5f;
            int i = (int) ((e.getY() - y0) / (u * 11));
            if (e.getX() < x || e.getX() > x + bw || i < 0 || i >= items.length) {
                toggleMenu();
                return true;
            }
            sel = i;
            choose();
            return true;
        }

        @Override
        protected void onDraw(Canvas c) {
            float u = Math.min(getWidth(), getHeight()) / 100f;
            if (fatal != null) {
                paint.setColor(0xCC000000);
                c.drawRect(0, 0, getWidth(), getHeight(), paint);
                paint.setColor(0xFFF3E6DA);
                paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
                paint.setTextSize(u * 4.5f);
                c.drawText("The game didn't start", u * 6, getHeight() / 2f - u * 6, paint);
                paint.setTypeface(Typeface.DEFAULT);
                paint.setTextSize(u * 3.2f);
                float y = getHeight() / 2f;
                for (String line : fatal.split("(?<=\\G.{80})")) {
                    c.drawText(line, u * 6, y, paint);
                    y += u * 4.5f;
                }
                paint.setColor(0xFFD7A27A);
                c.drawText("Any button: back", u * 6, y + u * 4, paint);
                return;
            }
            if (menu) {
                paint.setColor(0xAA000000);
                c.drawRect(0, 0, getWidth(), getHeight(), paint);
                float bw = u * 70, x = (getWidth() - bw) / 2, y0 = getHeight() / 2f - items.length * u * 5.5f;
                paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
                paint.setTextSize(u * 4f);
                paint.setColor(0xFFD7A27A);
                c.drawText("Choccy Milk · " + name, x, y0 - u * 4, paint);
                for (int i = 0; i < items.length; i++) {
                    float y = y0 + i * u * 11;
                    r.set(x, y, x + bw, y + u * 9.5f);
                    paint.setColor(i == sel ? 0xFF00A9E6 : 0xEE2A2420);
                    c.drawRoundRect(r, u * 2, u * 2, paint);
                    paint.setColor(0xFFFFFFFF);
                    paint.setTypeface(i == sel ? Typeface.create(Typeface.DEFAULT, Typeface.BOLD) : Typeface.DEFAULT);
                    String label = items[i] + (i == 3 ? ":  ‹ " + slot + " ›" : i == 1 || i == 2 ? " (slot " + slot + ")" : "");
                    c.drawText(label, x + u * 4, y + u * 6.3f, paint);
                }
            }
            if (notice != null && System.currentTimeMillis() < noticeUntil) {
                paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
                paint.setTextSize(u * 3.2f);
                float tw = paint.measureText(notice) + u * 5, th = u * 7;
                r.set(u * 3, u * 3, u * 3 + tw, u * 3 + th);
                paint.setColor(0xAA000000);
                c.drawRoundRect(r, th / 2, th / 2, paint);
                paint.setColor(0xFFD7A27A);
                c.drawText(notice, u * 5.5f, u * 3 + th / 2 + u * 1.1f, paint);
            }
        }
    }
}
