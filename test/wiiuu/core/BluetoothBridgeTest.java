package wiiuu.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

/** Run the production Python relay against a local socketpair, without Bluetooth hardware. */
public final class BluetoothBridgeTest {
    public static void main(String[] args) throws Exception {
        if (!BluetoothLink.supported()) return;
        var script = Files.createTempFile("bluetooth-relay-", ".py");
        try {
            Files.writeString(script, BluetoothLink.bridgeScript(), StandardCharsets.UTF_8);
            String exercise = """
                    import runpy, socket, threading, time, io, os, sys
                    relay = runpy.run_path(sys.argv[1])['relay']
                    a, b = socket.socketpair()
                    a.settimeout(5)
                    b.settimeout(5)
                    reader, writer = os.pipe()
                    src = os.fdopen(reader, 'rb', buffering=0)
                    dest = io.BytesIO()
                    errors = []
                    def run():
                        try:
                            relay(a, src, dest, idle_limit=0.6)
                        except Exception as e:
                            errors.append(e)
                        finally:
                            a.close()
                            src.close()
                    t = threading.Thread(target=run, daemon=True)
                    t.start()
                    try:
                        # Total upload duration exceeds the inactivity limit; the peer sends
                        # no replies while data is flowing.
                        for i in range(10):
                            os.write(writer, bytes([i]) * 16)
                            got = b.recv(16)
                            assert got == bytes([i]) * 16, 'Upload lost data'
                            time.sleep(0.15)
                        # Simulate a final success reply, and a final acknowledgement back.
                        b.sendall(b'OK')
                        until = time.monotonic() + 2
                        while dest.getvalue() != b'OK' and time.monotonic() < until:
                            time.sleep(0.01)
                        assert dest.getvalue() == b'OK', 'Final success not delivered'
                        os.write(writer, b'ACK')
                        os.close(writer)
                        writer = None
                        assert b.recv(3) == b'ACK', 'Final acknowledgement not drained'
                        t.join(3)
                        assert not t.is_alive(), 'Relay failed to shut down'
                        assert not errors, errors
                    finally:
                        if writer is not None: os.close(writer)
                        b.close()
                    print('Bluetooth relay sustained one-way upload and drained acknowledgement')
                    """;
            Process p = new ProcessBuilder("python3", "-c", exercise, script.toString()).inheritIO().start();
            if (!p.waitFor(15, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new AssertionError("Bluetooth relay regression test timed out");
            }
            if (p.exitValue() != 0) throw new AssertionError("Bluetooth relay regression test failed");
        } finally { Files.deleteIfExists(script); }
    }
}
