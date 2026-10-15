package wiiuu.android;

import android.view.Surface;

/** Choccy Milk's native side (jni/choccy_android.c): one game at a time, in the game screen's process. */
final class Choccy {
    private Choccy() {}

    static {
        System.loadLibrary("choccy");
    }

    /** RetroPad buttons, as bits (libretro's ids). */
    static final int B = 1, Y = 1 << 1, SELECT = 1 << 2, START = 1 << 3, UP = 1 << 4, DOWN = 1 << 5, LEFT = 1 << 6,
            RIGHT = 1 << 7, A = 1 << 8, X = 1 << 9, L = 1 << 10, R = 1 << 11, L2 = 1 << 12, R2 = 1 << 13, L3 = 1 << 14,
            R3 = 1 << 15;

    /** Starts the game on its own thread; false when one is playing already. Whether it really plays: {@link #running}. */
    static native boolean start(String core, String rom, String saves, String system, String options);

    /** The window to draw in (null: none now), and its size. */
    static native void surface(Surface s, int w, int h);

    static native void input(int port, int buttons, int lx, int ly, int rx, int ry);

    static native void pause(boolean paused);

    /** Sharp pixels (whole-pixel enlargement first) or smooth. */
    static native void sharp(boolean sharp);

    /** Saves or loads a save state (slot 0-9), on the game's thread. */
    static native void state(boolean save, int slot);

    /** Saves the game and stops (blocks until it has). */
    static native void quit();

    static native boolean running();

    /** Why it stopped ("" when all went well, or while playing). */
    static native String ending();

    /** The next message for the player, or null. */
    static native String nextNotice();

    static native float aspect();

    static native String coreName();
}
