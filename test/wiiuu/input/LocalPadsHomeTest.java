package wiiuu.input;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/** Verify actual Linux evdev HOME press/release translation, not a direct menu callback. */
public final class LocalPadsHomeTest {
    public static void main(String[] args) throws Exception {
        List<String> received = new ArrayList<>();
        PadSink sink = new PadSink() {
            @Override public void button(int player, PadButton button, boolean down) {
                received.add(player + ":" + button + ":" + down);
            }
            @Override public void stick(int player, int stick, float x, float y) { }
            @Override public void releaseAll(int player) { }
        };

        // Linux input_event uses 24 bytes on 64-bit and 16 bytes on 32-bit.
        int eventSize = System.getProperty("os.arch", "").contains("64") ? 24 : 16;
        ByteBuffer events = ByteBuffer.allocate(eventSize * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 2; i++) {
            int offset = i * eventSize;
            events.putShort(offset + eventSize - 8, (short) 1);      // EV_KEY
            events.putShort(offset + eventSize - 6, (short) 0x13c);  // BTN_MODE / HOME
            events.putInt(offset + eventSize - 4, i == 0 ? 1 : 0);
        }
        new LocalPads(sink, false).pumpEvents(new ByteArrayInputStream(events.array()), -1, eventSize);
        if (!received.equals(List.of("-1:HOME:true", "-1:HOME:false")))
            throw new AssertionError("Physical HOME was not correctly translated: " + received);
        System.out.println("Linux physical controller HOME mapping passed");
    }
}
