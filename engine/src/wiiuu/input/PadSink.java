package wiiuu.input;

/** Where controller input goes: WII-UU's input router, or the Choccy Milk app. Players count from 1 (phones) or -1 (local pads). */
public interface PadSink {
    void button(int player, PadButton b, boolean down);

    void stick(int player, int stick, float x, float y);

    void releaseAll(int player);
}
