package wiiuu.input;

/** Every input the phone gamepad can send. Stick directions are digital "virtual buttons". */
public enum PadButton {
    A, B, X, Y,
    L, R, ZL, ZR,
    PLUS, MINUS, HOME,
    UP, DOWN, LEFT, RIGHT,
    L3, R3,
    LS_UP, LS_DOWN, LS_LEFT, LS_RIGHT,
    RS_UP, RS_DOWN, RS_LEFT, RS_RIGHT;

    public static PadButton parse(String s) {
        try {
            return valueOf(s.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public boolean isDirection() {
        return this == UP || this == DOWN || this == LEFT || this == RIGHT
                || this == LS_UP || this == LS_DOWN || this == LS_LEFT || this == LS_RIGHT;
    }
}
