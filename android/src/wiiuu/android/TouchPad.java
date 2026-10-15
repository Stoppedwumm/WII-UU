package wiiuu.android;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

/**
 * Buttons on the screen, for phones without a controller: a d-pad on the left, the four face
 * buttons on the right (in the Nintendo places: A right, B bottom, X top, Y left), L / R in the
 * top corners, Select / Start at the bottom. Several fingers at once; sliding between buttons works.
 */
final class TouchPad extends View {
    private final GameActivity game;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private volatile int pressed;

    private static final int[] FACE = {Choccy.A, Choccy.B, Choccy.X, Choccy.Y};
    private static final String[] FACE_LABEL = {"A", "B", "X", "Y"};

    TouchPad(GameActivity game) {
        super(game);
        this.game = game;
    }

    int buttons() {
        return pressed;
    }

    private float u() {
        return Math.min(getWidth(), getHeight()) / 100f;
    }

    /** Centre of the d-pad, the face buttons, and their size. */
    private float padX() { return u() * 22; }
    private float padY() { return getHeight() - u() * 26; }
    private float faceX() { return getWidth() - u() * 22; }
    private float faceY() { return getHeight() - u() * 26; }
    private float radius() { return u() * 17; }

    /** What's under a finger at x, y. */
    private int hit(float x, float y) {
        float u = u(), rad = radius();
        float dx = x - padX(), dy = y - padY();
        if (Math.hypot(dx, dy) < rad * 1.3f && Math.hypot(dx, dy) > u * 2) {
            int b = 0;
            double ang = Math.atan2(dy, dx);
            if (Math.cos(ang) > 0.38) b |= Choccy.RIGHT;
            if (Math.cos(ang) < -0.38) b |= Choccy.LEFT;
            if (Math.sin(ang) > 0.38) b |= Choccy.DOWN;
            if (Math.sin(ang) < -0.38) b |= Choccy.UP;
            return b;
        }
        float fx = faceX(), fy = faceY(), off = rad * 0.62f, br = rad * 0.42f;
        float[][] at = {{fx + off, fy}, {fx, fy + off}, {fx, fy - off}, {fx - off, fy}};
        for (int i = 0; i < 4; i++) if (Math.hypot(x - at[i][0], y - at[i][1]) < br * 1.25f) return FACE[i];
        if (y < u * 18) {
            if (x < u * 26) return Choccy.L;
            if (x > getWidth() - u * 26) return Choccy.R;
        }
        if (y > getHeight() - u * 12) {
            float cx = getWidth() / 2f;
            if (x > cx - u * 22 && x < cx - u * 2) return Choccy.SELECT;
            if (x > cx + u * 2 && x < cx + u * 22) return Choccy.START;
        }
        return 0;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int b = 0;
        int action = e.getActionMasked();
        for (int i = 0; i < e.getPointerCount(); i++) {
            boolean lifted = (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) && i == e.getActionIndex();
            if (action == MotionEvent.ACTION_CANCEL || lifted) continue;
            b |= hit(e.getX(i), e.getY(i));
        }
        if (b != pressed) {
            pressed = b;
            game.send(0);
            invalidate();
        }
        return true;
    }

    private void button(Canvas c, float x, float y, float rad, String label, boolean on) {
        paint.setColor(on ? 0x9900A9E6 : 0x55FFFFFF);
        c.drawCircle(x, y, rad, paint);
        paint.setColor(0xCCFFFFFF);
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        paint.setTextSize(rad * 0.8f);
        c.drawText(label, x - paint.measureText(label) / 2, y + rad * 0.28f, paint);
    }

    @Override
    protected void onDraw(Canvas c) {
        float u = u(), rad = radius(), px = padX(), py = padY(), arm = rad * 0.36f;
        int p = pressed;
        // the d-pad: a cross
        paint.setColor(0x55FFFFFF);
        r.set(px - arm, py - rad, px + arm, py + rad);
        c.drawRoundRect(r, u * 2, u * 2, paint);
        r.set(px - rad, py - arm, px + rad, py + arm);
        c.drawRoundRect(r, u * 2, u * 2, paint);
        paint.setColor(0x9900A9E6);
        if ((p & Choccy.UP) != 0) c.drawCircle(px, py - rad * 0.65f, arm, paint);
        if ((p & Choccy.DOWN) != 0) c.drawCircle(px, py + rad * 0.65f, arm, paint);
        if ((p & Choccy.LEFT) != 0) c.drawCircle(px - rad * 0.65f, py, arm, paint);
        if ((p & Choccy.RIGHT) != 0) c.drawCircle(px + rad * 0.65f, py, arm, paint);
        float fx = faceX(), fy = faceY(), off = rad * 0.62f, br = rad * 0.42f;
        button(c, fx + off, fy, br, "A", (p & Choccy.A) != 0);
        button(c, fx, fy + off, br, "B", (p & Choccy.B) != 0);
        button(c, fx, fy - off, br, "X", (p & Choccy.X) != 0);
        button(c, fx - off, fy, br, "Y", (p & Choccy.Y) != 0);
        // shoulders and Select / Start
        paint.setColor((p & Choccy.L) != 0 ? 0x9900A9E6 : 0x55FFFFFF);
        r.set(u * 3, u * 3, u * 26, u * 14);
        c.drawRoundRect(r, u * 4, u * 4, paint);
        paint.setColor((p & Choccy.R) != 0 ? 0x9900A9E6 : 0x55FFFFFF);
        r.set(getWidth() - u * 26, u * 3, getWidth() - u * 3, u * 14);
        c.drawRoundRect(r, u * 4, u * 4, paint);
        paint.setColor(0xCCFFFFFF);
        paint.setTextSize(u * 5);
        c.drawText("L", u * 13, u * 10.5f, paint);
        c.drawText("R", getWidth() - u * 16, u * 10.5f, paint);
        float cx = getWidth() / 2f, by = getHeight() - u * 9;
        paint.setColor((p & Choccy.SELECT) != 0 ? 0x9900A9E6 : 0x55FFFFFF);
        r.set(cx - u * 20, by - u * 3, cx - u * 4, by + u * 3);
        c.drawRoundRect(r, u * 3, u * 3, paint);
        paint.setColor((p & Choccy.START) != 0 ? 0x9900A9E6 : 0x55FFFFFF);
        r.set(cx + u * 4, by - u * 3, cx + u * 20, by + u * 3);
        c.drawRoundRect(r, u * 3, u * 3, paint);
        paint.setColor(0xCCFFFFFF);
        paint.setTextSize(u * 3);
        c.drawText("SELECT", cx - u * 12 - paint.measureText("SELECT") / 2, by + u * 1.1f, paint);
        c.drawText("START", cx + u * 12 - paint.measureText("START") / 2, by + u * 1.1f, paint);
    }
}
