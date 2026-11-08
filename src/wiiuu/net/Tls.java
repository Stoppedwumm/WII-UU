package wiiuu.net;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/**
 * A self-signed certificate for the GamePad's HTTPS address. Phones only allow motion
 * sensors (gyro) on secure pages, so the GamePad offers an https:// URL too; the phone
 * shows a one-time warning for the self-signed certificate.
 */
final class Tls {
    private static final char[] PASS = "wiiuu-gamepad".toCharArray();

    private Tls() {}

    /** Loads (or creates with the JDK's keytool) a certificate valid for {@code ip}. */
    static SSLContext context(Path home, String ip) throws Exception {
        Path ks = home.resolve("gamepad-" + ip.replace(':', '_') + ".p12");
        if (!Files.exists(ks)) create(ks, ip);
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(ks)) {
            store.load(in, PASS);
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(store, PASS);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        return ctx;
    }

    private static void create(Path ks, String ip) throws IOException, InterruptedException {
        Files.createDirectories(ks.getParent());
        String keytool = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase().contains("win") ? "keytool.exe" : "keytool").toString();
        String san = "dns:localhost,ip:127.0.0.1" + (ip.matches("[0-9.]+") ? ",ip:" + ip : "");
        Process p = new ProcessBuilder(keytool, "-genkeypair", "-alias", "wiiuu", "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "3650", "-dname", "CN=WII-UU GamePad", "-ext", "SAN=" + san,
                "-keystore", ks.toString(), "-storetype", "PKCS12", "-storepass", new String(PASS), "-noprompt")
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) {
            Files.deleteIfExists(ks);
            throw new IOException("keytool failed: " + out.trim());
        }
    }
}
