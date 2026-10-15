package wiiuu.android;

import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

/**
 * A handheld's or a controller's buttons, by position, as the RetroPad's: Android names the bottom
 * face button BUTTON_A (the Xbox way), the RetroPad calls it B (the Nintendo way), so the button in
 * the same place is pressed whatever is printed on it.
 */
final class Pad {
    private Pad() {}

    /** The RetroPad bit for a key, or 0. */
    static int button(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A: return Choccy.B;         // bottom
            case KeyEvent.KEYCODE_BUTTON_B: return Choccy.A;         // right
            case KeyEvent.KEYCODE_BUTTON_X: return Choccy.Y;         // left
            case KeyEvent.KEYCODE_BUTTON_Y: return Choccy.X;         // top
            case KeyEvent.KEYCODE_BUTTON_L1: return Choccy.L;
            case KeyEvent.KEYCODE_BUTTON_R1: return Choccy.R;
            case KeyEvent.KEYCODE_BUTTON_L2: return Choccy.L2;
            case KeyEvent.KEYCODE_BUTTON_R2: return Choccy.R2;
            case KeyEvent.KEYCODE_BUTTON_THUMBL: return Choccy.L3;
            case KeyEvent.KEYCODE_BUTTON_THUMBR: return Choccy.R3;
            case KeyEvent.KEYCODE_BUTTON_START: return Choccy.START;
            case KeyEvent.KEYCODE_BUTTON_SELECT: return Choccy.SELECT;
            case KeyEvent.KEYCODE_DPAD_UP: return Choccy.UP;
            case KeyEvent.KEYCODE_DPAD_DOWN: return Choccy.DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT: return Choccy.LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return Choccy.RIGHT;
            // a keyboard: arrows, X / Z, S / A, Q / W, Enter / Shift (as Choccy Milk on a computer)
            case KeyEvent.KEYCODE_X: return Choccy.A;
            case KeyEvent.KEYCODE_Z: return Choccy.B;
            case KeyEvent.KEYCODE_S: return Choccy.X;
            case KeyEvent.KEYCODE_A: return Choccy.Y;
            case KeyEvent.KEYCODE_Q: return Choccy.L;
            case KeyEvent.KEYCODE_W: return Choccy.R;
            case KeyEvent.KEYCODE_ENTER: return Choccy.START;
            case KeyEvent.KEYCODE_SHIFT_LEFT:
            case KeyEvent.KEYCODE_SHIFT_RIGHT: return Choccy.SELECT;
            default: return 0;
        }
    }

    /** Whether the event is from a game controller (a handheld's own buttons count). */
    static boolean fromController(int source) {
        return (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                || (source & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD;
    }

    /** -32768..32767 from -1..1, with a small dead zone. */
    static int axis(float v) {
        if (Math.abs(v) < 0.12f) return 0;
        return Math.round(Math.max(-1f, Math.min(1f, v)) * 32767);
    }

    /** The d-pad bits a hat (and analog triggers' L2 / R2) gives. */
    static int fromMotion(MotionEvent e) {
        int b = 0;
        float hx = e.getAxisValue(MotionEvent.AXIS_HAT_X), hy = e.getAxisValue(MotionEvent.AXIS_HAT_Y);
        if (hx < -0.5f) b |= Choccy.LEFT;
        if (hx > 0.5f) b |= Choccy.RIGHT;
        if (hy < -0.5f) b |= Choccy.UP;
        if (hy > 0.5f) b |= Choccy.DOWN;
        if (Math.max(e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_BRAKE)) > 0.5f) b |= Choccy.L2;
        if (Math.max(e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_GAS)) > 0.5f) b |= Choccy.R2;
        return b;
    }

    /** Menu directions from a stick or hat: {dx, dy}. */
    static int[] direction(MotionEvent e) {
        float x = e.getAxisValue(MotionEvent.AXIS_X) + e.getAxisValue(MotionEvent.AXIS_HAT_X);
        float y = e.getAxisValue(MotionEvent.AXIS_Y) + e.getAxisValue(MotionEvent.AXIS_HAT_Y);
        return new int[]{x < -0.5f ? -1 : x > 0.5f ? 1 : 0, y < -0.5f ? -1 : y > 0.5f ? 1 : 0};
    }
}
