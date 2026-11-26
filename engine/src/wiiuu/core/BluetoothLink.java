package wiiuu.core;

import java.io.*;
import java.nio.file.*;
import java.util.Locale;

/** Direct Bluetooth Classic RFCOMM on Linux; no IP network, Wi-Fi or relay is involved. */
public final class BluetoothLink implements AutoCloseable {
    public static final int TRANSFER_CHANNEL = 23, DS_CHANNEL = 24;
    private final Process process;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final Thread shutdown;
    private BluetoothLink(Process process) {
        this.process = process;
        shutdown = new Thread(process::destroyForcibly, "bluetooth-close");
        Runtime.getRuntime().addShutdownHook(shutdown);
        in = new DataInputStream(new BufferedInputStream(process.getInputStream()));
        out = new DataOutputStream(new BufferedOutputStream(process.getOutputStream()));
    }

    public static boolean supported() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
    }
    public static boolean validAddress(String address) {
        return address != null && address.matches("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}");
    }
    public static String peer(Config config) { return config.get("choccy.bluetooth.peer", "").trim().toUpperCase(Locale.ROOT); }
    public static String role(Config config) { return config.get("choccy.bluetooth.ds", "off"); }
    public static boolean dsEnabled(Config config) { return !role(config).equals("off"); }
    public static String availability() {
        return supported() ? "Linux Bluetooth Classic; pair both devices in system Bluetooth settings first."
                : "Direct Bluetooth is currently supported on Linux / Raspberry Pi OS only.";
    }

    // Python's standard-library socket API uses BlueZ RFCOMM without an extra JNI dependency.
    // Require authenticated/encrypted links and accept only the explicitly configured peer MAC.
    private static final String BRIDGE = """
            import socket, sys, threading, os
            mode, peer, channel = sys.argv[1], sys.argv[2].upper(), int(sys.argv[3])
            def secure(s):
                s.setsockopt(274, 4, bytes((3, 0))) # SOL_BLUETOOTH, BT_SECURITY, HIGH
            try:
                s = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_STREAM, socket.BTPROTO_RFCOMM)
                secure(s)
                s.settimeout(90)
                if mode == 'host':
                    s.bind(('00:00:00:00:00:00', channel))
                    s.listen(1)
                    conn, addr = s.accept()
                    s.close()
                    s = conn
                    if addr[0].upper() != peer:
                        raise OSError('Unexpected Bluetooth peer')
                    secure(s)
                else:
                    s.connect((peer, channel))
                s.settimeout(120)
                sys.stdout.buffer.write(b'R')
                sys.stdout.buffer.flush()
                def upload():
                    try:
                        while True:
                            b = os.read(0, 65536)
                            if not b: break
                            s.sendall(b)
                    except OSError:
                        pass
                    finally:
                        try: s.shutdown(socket.SHUT_WR)
                        except OSError: pass
                threading.Thread(target=upload, daemon=True).start()
                while True:
                    b = s.recv(65536)
                    if not b: break
                    sys.stdout.buffer.write(b)
                    sys.stdout.buffer.flush()
            except Exception as e:
                print('Bluetooth RFCOMM: ' + str(e), file=sys.stderr, flush=True)
                sys.exit(1)
            """;

    public static BluetoothLink open(boolean host, String peer, int channel) throws IOException {
        if (!supported()) throw new IOException(availability());
        if (!validAddress(peer)) throw new IOException("Enter the paired device's Bluetooth MAC address.");
        if (channel != TRANSFER_CHANNEL && channel != DS_CHANNEL) throw new IOException("Invalid Bluetooth channel");
        Process p = new ProcessBuilder("python3", "-u", "-c", BRIDGE, host ? "host" : "join",
                peer, Integer.toString(channel)).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        BluetoothLink link = new BluetoothLink(p);
        try {
            if (link.in.read() != 'R') throw new IOException("Bluetooth connection failed. Check pairing, adapter and peer address.");
            return link;
        } catch (IOException e) {
            link.close();
            throw e;
        }
    }
    public DataInputStream input() { return in; }
    public DataOutputStream output() { return out; }
    /** Let the bridge flush its final acknowledgement before tearing down a successful transfer. */
    public void finish() throws IOException {
        out.close();
        try { process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
    @Override public void close() {
        process.destroyForcibly();
        try { Runtime.getRuntime().removeShutdownHook(shutdown); } catch (IllegalStateException ignored) {}
        try { in.close(); } catch (IOException ignored) {}
        try { out.close(); } catch (IOException ignored) {}
    }
}
