package wiiuu.core;

import java.io.*;

/** Application-neutral wire format for two independent Choccy engine instances. */
public final class BluetoothDsProtocol {
    public static final int MAX_PACKET = 65536;
    private static final String MAGIC = "WIIUU-CORE-BT-2";
    public static final int NETPACKET = 1, RELIABLE = 2, POLL = 4, CONNECTION_EVENTS = 8;
    public static final int REQUIRED = NETPACKET | RELIABLE;
    public record Capabilities(String core, String protocol, boolean host, int features, int maxPacket) {
        public Capabilities {
            if (core == null || core.isBlank() || core.length() > 256 || protocol == null
                    || protocol.isBlank() || protocol.length() > 256 || maxPacket < 1 || maxPacket > MAX_PACKET)
                throw new IllegalArgumentException("Invalid multiplayer capabilities");
        }
    }
    public record Negotiated(Capabilities remote, int features, int maxPacket) {}
    private BluetoothDsProtocol() {}

    public static Negotiated handshake(DataInputStream in, DataOutputStream out, Capabilities local) throws IOException {
        out.writeUTF(MAGIC);
        out.writeUTF(local.core()); out.writeUTF(local.protocol());
        out.writeBoolean(local.host()); out.writeInt(local.features()); out.writeInt(local.maxPacket());
        out.flush();
        if (!MAGIC.equals(in.readUTF())) throw new IOException("Incompatible Bluetooth multiplayer wire protocol");
        Capabilities remote;
        try { remote = new Capabilities(in.readUTF(), in.readUTF(), in.readBoolean(), in.readInt(), in.readInt()); }
        catch (IllegalArgumentException e) { throw new IOException("Invalid peer capabilities", e); }
        if (local.host() == remote.host()) throw new IOException("Choose Host on one device and Join on the other");
        if (!local.core().equals(remote.core()) || !local.protocol().equals(remote.protocol()))
            throw new IOException("Incompatible multiplayer cores or core protocol versions");
        int common = local.features() & remote.features();
        if ((common & REQUIRED) != REQUIRED) throw new IOException("Peer does not support reliable netpacket multiplayer");
        return new Negotiated(remote, common, Math.min(local.maxPacket(), remote.maxPacket()));
    }

    public static void writePacket(DataOutputStream out, byte[] packet) throws IOException {
        if (packet.length == 0 || packet.length > MAX_PACKET) throw new IOException("Invalid DS packet");
        out.writeInt(packet.length);
        out.write(packet);
        out.flush();
    }

    public static byte[] readPacket(DataInputStream in) throws IOException {
        int size = in.readInt();
        if (size <= 0 || size > MAX_PACKET) throw new IOException("Invalid DS packet");
        byte[] packet = in.readNBytes(size);
        if (packet.length != size) throw new EOFException("Incomplete DS packet");
        return packet;
    }
}
