package wiiuu.choccy;

import java.io.*;
import java.lang.foreign.*;
import java.lang.invoke.*;
import java.util.concurrent.ArrayBlockingQueue;
import wiiuu.core.BluetoothLink;
import wiiuu.core.BluetoothDsProtocol;
import static java.lang.foreign.ValueLayout.*;

/** Two-player libretro netpacket session over a paired RFCOMM stream. Native callbacks stay on the core thread. */
final class BluetoothNetplay implements AutoCloseable {
    private final Arena arena;
    private final boolean host;
    private final String peer;
    private final ArrayBlockingQueue<byte[]> incoming = new ArrayBlockingQueue<>(128), outgoing = new ArrayBlockingQueue<>(128);
    private MethodHandle start, receive, stop, poll, connected, disconnected;
    private String protocol = "";
    private BluetoothLink link;
    private volatile boolean failed, closed;
    private boolean active, remoteConnected;
    BluetoothNetplay(Arena arena, String role, String peer) {
        this.arena = arena; this.host = role.equals("host"); this.peer = peer;
    }
    boolean register(MemorySegment address) {
        if (address.address() == 0) return false;
        MemorySegment data = address.reinterpret(7L * ADDRESS.byteSize());
        Linker linker = Linker.nativeLinker();
        start = callback(linker, data, 0, FunctionDescriptor.ofVoid(JAVA_SHORT, ADDRESS, ADDRESS));
        receive = callback(linker, data, 1, FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, JAVA_SHORT));
        stop = callback(linker, data, 2, FunctionDescriptor.ofVoid());
        poll = callback(linker, data, 3, FunctionDescriptor.ofVoid());
        connected = callback(linker, data, 4, FunctionDescriptor.of(JAVA_BOOLEAN, JAVA_SHORT));
        disconnected = callback(linker, data, 5, FunctionDescriptor.ofVoid(JAVA_SHORT));
        MemorySegment version = data.getAtIndex(ADDRESS, 6);
        protocol = version.address() == 0 ? "" : version.reinterpret(1024).getString(0);
        return start != null && receive != null;
    }
    private static MethodHandle callback(Linker linker, MemorySegment data, int index, FunctionDescriptor desc) {
        MemorySegment pointer = data.getAtIndex(ADDRESS, index);
        return pointer.address() == 0 ? null : linker.downcallHandle(pointer, desc);
    }
    void connect(String coreVersion) throws Throwable {
        if (start == null || receive == null) throw new IOException("This melonDS DS build has no libretro netpacket support. Update the core.");
        link = BluetoothLink.open(host, peer, BluetoothLink.DS_CHANNEL);
        try {
            String version = protocol.isEmpty() ? coreVersion : protocol;
            var out = link.output(); var in = link.input();
            BluetoothDsProtocol.handshake(in, out, version);
            Linker linker = Linker.nativeLinker();
            var lookup = MethodHandles.lookup();
            MemorySegment send = linker.upcallStub(lookup.bind(this, "send", MethodType.methodType(void.class,
                    int.class, MemorySegment.class, long.class, short.class)),
                    FunctionDescriptor.ofVoid(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_SHORT), arena);
            MemorySegment pump = linker.upcallStub(lookup.bind(this, "receivePending", MethodType.methodType(void.class)),
                    FunctionDescriptor.ofVoid(), arena);
            worker("ds-bluetooth-receive", () -> {
                while (!closed) {
                    if (!incoming.offer(BluetoothDsProtocol.readPacket(in)))
                        throw new IOException("DS receive buffer overflow");
                }
            });
            worker("ds-bluetooth-send", () -> {
                while (!closed) {
                    byte[] packet = outgoing.take();
                    BluetoothDsProtocol.writePacket(out, packet);
                }
            });
            active = true;
            start.invokeExact((short)(host ? 0 : 1), send, pump);
            if (host && connected != null && !(boolean) connected.invokeExact((short)1))
                throw new IOException("Core rejected multiplayer peer");
            remoteConnected = true;
        } catch (Throwable e) { close(); throw e; }
    }
    private Thread reader, writer;
    private interface Task { void run() throws Exception; }
    private void worker(String name, Task task) {
        Thread thread = new Thread(() -> {
            try { task.run(); } catch (Exception e) { if (!closed) failed = true; }
        }, name);
        thread.setDaemon(true);
        if (reader == null) reader = thread; else writer = thread;
        thread.start();
    }
    private void send(int flags, MemorySegment buf, long size, short client) {
        if (!active || closed || size == 0) return;
        try {
            int id = Short.toUnsignedInt(client);
            if (id != 65535 && id != (host ? 1 : 0)) return;
            if (size < 0 || size > BluetoothDsProtocol.MAX_PACKET || buf.address() == 0
                    || !outgoing.offer(buf.reinterpret(size).toArray(JAVA_BYTE))) failed = true;
        } catch (Throwable e) { failed = true; }
    }
    private void receivePending() {
        if (!active || closed) return;
        try {
            for (int i = 0; i < 64; i++) {
                byte[] packet = incoming.poll();
                if (packet == null) break;
                try (Arena packetArena = Arena.ofConfined()) {
                    MemorySegment bytes = packetArena.allocate(packet.length);
                    bytes.copyFrom(MemorySegment.ofArray(packet));
                    receive.invokeExact(bytes, (long)packet.length, (short)(host ? 1 : 0));
                }
            }
        } catch (Throwable e) { failed = true; }
    }
    boolean tick() throws Throwable {
        if (failed) { close(); return false; }
        receivePending();
        if (active && poll != null) poll.invokeExact();
        return true;
    }
    @Override public void close() {
        if (closed) return;
        closed = true;
        if (reader != null) reader.interrupt();
        if (writer != null) writer.interrupt();
        if (link != null) link.close();
        if (active) {
            try { if (host && remoteConnected && disconnected != null) disconnected.invokeExact((short)1); }
            catch (Throwable ignored) {}
            try { if (stop != null) stop.invokeExact(); } catch (Throwable ignored) {}
            active = false;
        }
    }
}
