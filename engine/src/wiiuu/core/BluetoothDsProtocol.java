package wiiuu.core;

import java.io.*;

/** Application-neutral wire format for two independent Choccy engine instances. */
public final class BluetoothDsProtocol {
    public static final int MAX_PACKET = 65536;
    private static final String MAGIC = "WIIUU-DS-BT-1";
    private BluetoothDsProtocol() {}

    public static void handshake(DataInputStream in, DataOutputStream out, String coreProtocol) throws IOException {
        out.writeUTF(MAGIC);
        out.writeUTF(coreProtocol);
        out.flush();
        if (!MAGIC.equals(in.readUTF()) || !coreProtocol.equals(in.readUTF()))
            throw new IOException("Incompatible DS multiplayer core versions");
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
