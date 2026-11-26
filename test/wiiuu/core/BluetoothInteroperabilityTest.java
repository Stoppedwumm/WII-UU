package wiiuu.core;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.ZipFile;

/** Verify the actual packaged apps share the same wire implementations, then exercise both peer roles. */
public final class BluetoothInteroperabilityTest {
    public static void main(String[] args) throws Exception {
        try (ZipFile wii = new ZipFile(args[0]); ZipFile milk = new ZipFile(args[1])) {
            for (String cls : List.of("wiiuu/core/BluetoothLink.class", "wiiuu/core/BluetoothGames.class",
                    "wiiuu/core/BluetoothDsProtocol.class", "wiiuu/choccy/BluetoothNetplay.class")) {
                var w = wii.getEntry(cls);
                var m = milk.getEntry(cls);
                check(w != null && m != null, "Both apps package " + cls);
                try (var a = wii.getInputStream(w); var b = milk.getInputStream(m)) {
                    check(Arrays.equals(a.readAllBytes(), b.readAllBytes()), "Same protocol implementation: " + cls);
                }
            }
        }
        for (String host : List.of("WII-UU", "Choccy Milk"))
            for (String client : List.of("WII-UU", "Choccy Milk")) {
                exercise(host + " <- " + client, "core-v1", "core-v1", true);
            }
        exercise("version mismatch", "core-v1", "core-v2", false);
        System.out.println("BluetoothInteroperabilityTest passed");
    }

    private static void exercise(String label, String hostVersion, String clientVersion, boolean compatible) throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(5000);
            Future<Boolean> host = worker.submit(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(5000);
                    return peer(socket, hostVersion, true, compatible);
                }
            });
            boolean client;
            try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort())) {
                socket.setSoTimeout(5000);
                client = peer(socket, clientVersion, false, compatible);
            }
            check(client == compatible && host.get(6, TimeUnit.SECONDS) == compatible, label);
        } finally { worker.shutdownNow(); }
    }

    private static boolean peer(Socket socket, String version, boolean host, boolean compatible) throws IOException {
        var in = new DataInputStream(socket.getInputStream());
        var out = new DataOutputStream(socket.getOutputStream());
        try { BluetoothDsProtocol.handshake(in, out, version); }
        catch (IOException mismatch) { if (!compatible) return false; throw mismatch; }
        byte[] first = {1, 2, 3}, second = {4, 5, 6, 7};
        BluetoothDsProtocol.writePacket(out, host ? first : second);
        check(Arrays.equals(BluetoothDsProtocol.readPacket(in), host ? second : first), "packet payload");
        return true;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
