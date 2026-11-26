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
    public static boolean dsEnabled(Config config) { return multiplayerEnabled(config, "nds"); }
    public static boolean allCores(Config config) { return config.getBool("choccy.bluetooth.allCores", false); }
    public static boolean multiplayerEnabled(Config config, String system) {
        return !role(config).equals("off") && (system.equals("nds") || allCores(config));
    }
    public static String availability() {
        return supported() ? "Linux Bluetooth Classic; pair both devices in system Bluetooth settings first."
                : "Direct Bluetooth is currently supported on Linux / Raspberry Pi OS only.";
    }

    // Python's standard-library socket API uses BlueZ RFCOMM without an extra JNI dependency.
    // Require authenticated/encrypted links and accept only the explicitly configured peer MAC.
    private static final String BRIDGE = """
            import socket, sys, threading, os, time
            def secure(s):
                s.setsockopt(274, 4, bytes((3, 0))) # SOL_BLUETOOTH, BT_SECURITY, HIGH

            def relay(s, source, sink, idle_limit=120, allow_idle=False):
                # Socket polling is short; inactivity is measured across BOTH directions.
                # A long upload must not be killed merely because the peer has no reply yet.
                s.settimeout(min(1, idle_limit / 4))
                last_activity = [time.monotonic()]
                drained = threading.Event()
                failed = threading.Event()
                errors = []
                def upload():
                    try:
                        while True:
                            block = source.read(65536)
                            if not block: break
                            pending = memoryview(block)
                            stalled = time.monotonic()
                            while pending:
                                try:
                                    n = s.send(pending)
                                    if n <= 0: raise OSError('Bluetooth write closed')
                                    pending = pending[n:]
                                    stalled = last_activity[0] = time.monotonic()
                                except socket.timeout:
                                    if time.monotonic() - stalled >= idle_limit:
                                        raise TimeoutError('Bluetooth send stalled')
                        drained.set()
                    except Exception as e:
                        errors.append(e)
                        failed.set()
                sender = threading.Thread(target=upload, daemon=True)
                sender.start()
                remote_eof = False
                while not drained.is_set():
                    if failed.is_set(): raise errors[0]
                    if remote_eof:
                        if not sender.is_alive(): break
                        if time.monotonic() - last_activity[0] >= idle_limit:
                            raise TimeoutError('Bluetooth shutdown stalled')
                        drained.wait(0.05)
                        continue
                    try:
                        b = s.recv(65536)
                        if not b:
                            remote_eof = True
                            sink.close() # deliver EOF to Java, even if its outgoing pipe is still open
                            last_activity[0] = time.monotonic()
                            continue
                        last_activity[0] = time.monotonic()
                        sink.write(b)
                        sink.flush()
                    except socket.timeout:
                        if not allow_idle and time.monotonic() - last_activity[0] >= idle_limit:
                            raise TimeoutError('Bluetooth connection inactive')
                if failed.is_set(): raise errors[0]
                if not drained.is_set(): raise OSError('Bluetooth upload not drained')

            def main():
                mode, peer, channel = sys.argv[1], sys.argv[2].upper(), int(sys.argv[3])
                s = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_STREAM, socket.BTPROTO_RFCOMM)
                try:
                    secure(s)
                    s.settimeout(90)
                    if mode == 'host':
                        s.bind(('00:00:00:00:00:00', channel))
                        s.listen(1)
                        conn, addr = s.accept()
                        s.close()
                        s = conn
                        if addr[0].upper() != peer: raise OSError('Unexpected Bluetooth peer')
                        secure(s)
                    else:
                        s.connect((peer, channel))
                    sys.stdout.buffer.write(b'R')
                    sys.stdout.buffer.flush()
                    relay(s, sys.stdin.buffer.raw, sys.stdout.buffer, allow_idle=channel == 24)
                finally:
                    s.close()
            if __name__ == '__main__':
                try: main()
                except Exception as e:
                    print('Bluetooth RFCOMM: ' + str(e), file=sys.stderr, flush=True)
                    sys.exit(1)
            """;

    // Package-private so CI can exercise the exact Python relay on a socket pair.
    static String bridgeScript() { return BRIDGE; }

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
        try {
            if (!process.waitFor(125, java.util.concurrent.TimeUnit.SECONDS))
                throw new IOException("Bluetooth bridge did not drain its outgoing data");
            if (process.exitValue() != 0) throw new IOException("Bluetooth bridge failed while draining");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted while draining Bluetooth");
        }
    }
    @Override public void close() {
        process.destroyForcibly();
        try { Runtime.getRuntime().removeShutdownHook(shutdown); } catch (IllegalStateException ignored) {}
        try { in.close(); } catch (IOException ignored) {}
        try { out.close(); } catch (IOException ignored) {}
    }
}
