package wiiuu.net;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.CRC32;

import wiiuu.input.PadButton;

/**
 * A DSU ("cemuhook") server: the UDP protocol Cemu, Dolphin, Azahar/Citra, Ryujinx and others
 * use for external controllers. Each phone GamePad appears as a controller slot with analog
 * sticks, all buttons, gyro/accelerometer from the phone and the GamePad-screen touch.
 *
 * <p>Protocol: little endian; 16-byte header ("DSUS", version 1001, length, CRC32, server id),
 * then a 4-byte message type. Clients ask for data per slot and must re-ask every few seconds.
 */
public final class DsuServer {
    private static final int PROTOCOL = 1001;
    private static final int MSG_VERSION = 0x100000, MSG_PORTS = 0x100001, MSG_DATA = 0x100002;
    private static final int SLOTS = 4;
    private static final long CLIENT_TIMEOUT_MS = 5000;

    /** Live state of one phone, written by the HTTP input handler, read by the sender. */
    public static final class Pad {
        final Set<PadButton> buttons = EnumSet.noneOf(PadButton.class);
        float lx, ly, rx, ry;                  // -1..1, +y = down (as sent by the phone)
        float ax, ay, az;                      // g, DS4 axes
        float gx, gy, gz;                      // deg/s: pitch, yaw, roll
        long motionMicros;
        boolean touch;
        int touchId;
        float tx, ty;                          // 0..1
        volatile boolean connected;
    }

    private final Pad[] pads = new Pad[SLOTS];
    /** client address -> slot bitmask -> last request time */
    private final Map<SocketAddress, long[]> clients = new ConcurrentHashMap<>();
    private final int serverId = (int) (Math.random() * Integer.MAX_VALUE);
    private DatagramSocket socket;
    private int packetCounter;
    private volatile boolean running;

    public DsuServer() {
        for (int i = 0; i < SLOTS; i++) pads[i] = new Pad();
    }

    public void start(String bindAddress, int port) throws SocketException {
        socket = new DatagramSocket(new InetSocketAddress(bindAddress, port));
        running = true;
        Thread rx = new Thread(this::receiveLoop, "dsu-receive");
        rx.setDaemon(true);
        rx.start();
        Thread tx = new Thread(this::sendLoop, "dsu-send");
        tx.setDaemon(true);
        tx.start();
        System.out.println("[dsu] controller server on udp " + bindAddress + ":" + port);
    }

    public void stop() {
        running = false;
        if (socket != null) socket.close();
    }

    /** Player 1..4 -> slot 0..3; returns null for spectators. */
    public Pad pad(int player) {
        return player >= 1 && player <= SLOTS ? pads[player - 1] : null;
    }

    public void setConnected(int player, boolean on) {
        Pad p = pad(player);
        if (p == null) return;
        synchronized (p) {
            p.connected = on;
            if (!on) {
                p.buttons.clear();
                p.lx = p.ly = p.rx = p.ry = 0;
                p.gx = p.gy = p.gz = 0;
                p.touch = false;
            }
        }
    }

    public void button(int player, PadButton b, boolean down) {
        Pad p = pad(player);
        if (p == null) return;
        synchronized (p) {
            if (down) p.buttons.add(b);
            else p.buttons.remove(b);
        }
    }

    public void stick(int player, int stick, float x, float y) {
        Pad p = pad(player);
        if (p == null) return;
        synchronized (p) {
            if (stick == 0) { p.lx = x; p.ly = y; } else { p.rx = x; p.ry = y; }
        }
    }

    private final float[] signs = {1, 1, 1, 1, 1, 1};

    /**
     * Per-axis sign flips for accel x y z and gyro pitch yaw roll, e.g. {@code "+-+ ++-"},
     * for emulators that expect a different handedness.
     */
    public void setMotionSigns(String spec) {
        String s = spec == null ? "" : spec.replaceAll("[^+-]", "");
        for (int i = 0; i < 6; i++) signs[i] = i < s.length() && s.charAt(i) == '-' ? -1 : 1;
    }

    public void motion(int player, float ax, float ay, float az, float gx, float gy, float gz) {
        Pad p = pad(player);
        if (p == null) return;
        synchronized (p) {
            p.ax = ax * signs[0]; p.ay = ay * signs[1]; p.az = az * signs[2];
            p.gx = gx * signs[3]; p.gy = gy * signs[4]; p.gz = gz * signs[5];
            p.motionMicros = System.nanoTime() / 1000;
        }
    }

    public void touch(int player, float x, float y, boolean down) {
        Pad p = pad(player);
        if (p == null) return;
        synchronized (p) {
            if (down && !p.touch) p.touchId = (p.touchId + 1) & 0xFF;
            p.touch = down;
            p.tx = x;
            p.ty = y;
        }
    }

    // ---- network -----------------------------------------------------------------------

    private void receiveLoop() {
        byte[] buf = new byte[256];
        while (running) {
            DatagramPacket pkt = new DatagramPacket(buf, buf.length);
            try {
                socket.receive(pkt);
                handle(pkt);
            } catch (IOException e) {
                if (!running) return;
            } catch (RuntimeException e) {
                // malformed request; ignore
            }
        }
    }

    private void handle(DatagramPacket pkt) throws IOException {
        ByteBuffer in = ByteBuffer.wrap(pkt.getData(), 0, pkt.getLength()).order(ByteOrder.LITTLE_ENDIAN);
        if (pkt.getLength() < 20 || in.get(0) != 'D' || in.get(1) != 'S' || in.get(2) != 'U' || in.get(3) != 'C') return;
        int type = in.getInt(16);
        SocketAddress from = pkt.getSocketAddress();
        switch (type) {
            case MSG_VERSION -> {
                ByteBuffer b = message(MSG_VERSION, 2);
                b.putShort((short) PROTOCOL);
                send(b, from);
            }
            case MSG_PORTS -> {
                int count = Math.min(SLOTS, in.getInt(20));
                for (int i = 0; i < count; i++) {
                    int slot = in.get(24 + i) & 0xFF;
                    if (slot >= SLOTS) continue;
                    ByteBuffer b = message(MSG_PORTS, 12);
                    header(b, slot);
                    b.put((byte) 0);
                    send(b, from);
                }
            }
            case MSG_DATA -> {
                int flags = in.get(20) & 0xFF, slot = in.get(21) & 0xFF;
                long[] sub = clients.computeIfAbsent(from, k -> new long[SLOTS]);
                long now = System.currentTimeMillis();
                // flags: 0 = all slots, 1 = by slot, 2 = by MAC (our MACs end in the slot number)
                if (flags == 0) for (int i = 0; i < SLOTS; i++) sub[i] = now;
                else if ((flags & 1) != 0 && slot < SLOTS) sub[slot] = now;
                else if ((flags & 2) != 0) {
                    int macSlot = (in.get(27) & 0xFF) - 1;
                    if (macSlot >= 0 && macSlot < SLOTS) sub[macSlot] = now;
                }
            }
            default -> { }
        }
    }

    private void sendLoop() {
        while (running) {
            long now = System.currentTimeMillis();
            clients.entrySet().removeIf(e -> {
                for (long t : e.getValue()) if (now - t < CLIENT_TIMEOUT_MS) return false;
                return true;
            });
            for (var e : clients.entrySet()) {
                for (int slot = 0; slot < SLOTS; slot++) {
                    if (now - e.getValue()[slot] >= CLIENT_TIMEOUT_MS || !pads[slot].connected) continue;
                    try {
                        send(data(slot), e.getKey());
                    } catch (IOException ignored) {
                        // client gone; it times out
                    }
                }
            }
            try {
                Thread.sleep(8);            // ~125 Hz
            } catch (InterruptedException ex) {
                return;
            }
        }
    }

    private ByteBuffer data(int slot) {
        Pad p = pads[slot];
        ByteBuffer b = message(MSG_DATA, 80);
        synchronized (p) {
            header(b, slot);
            b.put((byte) (p.connected ? 1 : 0));
            b.putInt(++packetCounter);
            Set<PadButton> s = p.buttons;
            int b1 = (s.contains(PadButton.MINUS) ? 0x01 : 0) | (s.contains(PadButton.L3) ? 0x02 : 0)
                    | (s.contains(PadButton.R3) ? 0x04 : 0) | (s.contains(PadButton.PLUS) ? 0x08 : 0)
                    | (s.contains(PadButton.UP) ? 0x10 : 0) | (s.contains(PadButton.RIGHT) ? 0x20 : 0)
                    | (s.contains(PadButton.DOWN) ? 0x40 : 0) | (s.contains(PadButton.LEFT) ? 0x80 : 0);
            // face buttons by position: north X, east A, south B, west Y (Nintendo layout)
            int b2 = (s.contains(PadButton.ZL) ? 0x01 : 0) | (s.contains(PadButton.ZR) ? 0x02 : 0)
                    | (s.contains(PadButton.L) ? 0x04 : 0) | (s.contains(PadButton.R) ? 0x08 : 0)
                    | (s.contains(PadButton.X) ? 0x10 : 0) | (s.contains(PadButton.A) ? 0x20 : 0)
                    | (s.contains(PadButton.B) ? 0x40 : 0) | (s.contains(PadButton.Y) ? 0x80 : 0);
            b.put((byte) b1).put((byte) b2);
            b.put((byte) (s.contains(PadButton.HOME) ? 1 : 0));
            b.put((byte) (p.touch ? 1 : 0));
            b.put(axis(p.lx)).put(axis(-p.ly)).put(axis(p.rx)).put(axis(-p.ry));   // DSU: +y is up
            b.put(full(s, PadButton.LEFT)).put(full(s, PadButton.DOWN)).put(full(s, PadButton.RIGHT)).put(full(s, PadButton.UP));
            b.put(full(s, PadButton.Y)).put(full(s, PadButton.B)).put(full(s, PadButton.A)).put(full(s, PadButton.X));
            b.put(full(s, PadButton.R)).put(full(s, PadButton.L)).put(full(s, PadButton.ZR)).put(full(s, PadButton.ZL));
            // touch 1 in DS4 touchpad coordinates (1920 x 942); touch 2 unused
            b.put((byte) (p.touch ? 1 : 0)).put((byte) p.touchId)
                    .putShort((short) Math.round(p.tx * 1919)).putShort((short) Math.round(p.ty * 941));
            b.put((byte) 0).put((byte) 0).putShort((short) 0).putShort((short) 0);
            b.putLong(p.motionMicros);
            b.putFloat(p.ax).putFloat(p.ay).putFloat(p.az);
            b.putFloat(p.gx).putFloat(p.gy).putFloat(p.gz);
        }
        return b;
    }

    private static byte axis(float v) {
        return (byte) Math.round(128 + Math.max(-1, Math.min(1, v)) * 127);
    }

    private static byte full(Set<PadButton> s, PadButton b) {
        return (byte) (s.contains(b) ? 255 : 0);
    }

    /** Shared 11-byte controller header: slot, state, model, connection, MAC, battery. */
    private void header(ByteBuffer b, int slot) {
        boolean on = pads[slot].connected;
        b.put((byte) slot);
        b.put((byte) (on ? 2 : 0));            // 2 = connected
        b.put((byte) (on ? 2 : 0));            // 2 = full gyro
        b.put((byte) (on ? 2 : 0));            // 2 = bluetooth
        b.put(new byte[]{0x57, 0x49, 0x55, 0x55, 0x00, (byte) (slot + 1)});   // "WIUU" + slot
        b.put((byte) (on ? 0x05 : 0x00));       // battery full
    }

    private ByteBuffer message(int type, int payload) {
        ByteBuffer b = ByteBuffer.allocate(20 + payload).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[]{'D', 'S', 'U', 'S'});
        b.putShort((short) PROTOCOL);
        b.putShort((short) (4 + payload));      // length after the 16-byte header
        b.putInt(0);                           // CRC, filled in by send()
        b.putInt(serverId);
        b.putInt(type);
        return b;
    }

    private void send(ByteBuffer b, SocketAddress to) throws IOException {
        byte[] bytes = b.array();
        CRC32 crc = new CRC32();
        crc.update(bytes, 0, bytes.length);
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(8, (int) crc.getValue());
        socket.send(new DatagramPacket(bytes, bytes.length, to));
    }

    public static String loopback() {
        return InetAddress.getLoopbackAddress().getHostAddress();
    }
}
