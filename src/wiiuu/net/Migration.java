package wiiuu.net;

import wiiuu.core.Config;
import wiiuu.core.DataTransfer;

import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.HexFormat;
import java.util.function.Consumer;

/** One-time LAN transfer. The invitation carries both an authentication token and a TLS certificate pin. */
public final class Migration {
    private static final HexFormat HEX = HexFormat.of();

    private Migration() {}

    public static void send(Config config, String address, Consumer<String> invitation, Runnable transferring, Consumer<Closeable> connection) throws Exception {
        Tls.Identity tls = Tls.identity(config.tempDir(), address);
        byte[] token = new byte[24];
        new SecureRandom().nextBytes(token);
        try (var listener = (SSLServerSocket) tls.context().getServerSocketFactory()
                .createServerSocket(0, 1, InetAddress.getByName(address))) {
            connection.accept(listener);
            listener.setSoTimeout(300_000);
            invitation.accept(address + ":" + listener.getLocalPort() + "/" + HEX.formatHex(tls.fingerprint())
                    + "/" + HEX.formatHex(token));
            try (var socket = (SSLSocket) listener.accept()) {
                connection.accept(socket);
                socket.setSoTimeout(15_000);
                byte[] supplied = socket.getInputStream().readNBytes(token.length);
                if (!MessageDigest.isEqual(token, supplied)) throw new IOException("Invalid migration code.");
                transferring.run();
                DataTransfer.send(config, socket.getOutputStream());
            }
        }
    }

    public static void receive(String invitation, Path destination, Runnable transferring, Consumer<Closeable> connection) throws Exception {
        String[] fields = invitation.strip().split("[/:]", -1);
        if (fields.length != 4 || !fields[0].matches("[0-9.]+") || !fields[1].matches("[0-9]{1,5}")
                || fields[2].length() != 64 || fields[3].length() != 48)
            throw new IOException("Enter the full migration code shown on the other computer.");
        int port = Integer.parseInt(fields[1]);
        byte[] fingerprint = HEX.parseHex(fields[2]);
        byte[] token = HEX.parseHex(fields[3]);
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(null, new TrustManager[]{new X509TrustManager() {
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }

            @Override public void checkClientTrusted(X509Certificate[] chain, String auth) throws CertificateException {
                throw new CertificateException("Client certificates are not supported");
            }

            @Override public void checkServerTrusted(X509Certificate[] chain, String auth) throws CertificateException {
                try {
                    if (chain.length == 0 || !MessageDigest.isEqual(fingerprint,
                            MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded())))
                        throw new CertificateException("The sender does not match the migration code");
                } catch (java.security.NoSuchAlgorithmException e) {
                    throw new CertificateException(e);
                }
            }
        }}, null);
        try (var socket = (SSLSocket) tls.getSocketFactory().createSocket()) {
            connection.accept(socket);
            socket.connect(new InetSocketAddress(fields[0], port), 10_000);
            socket.setSoTimeout(60_000);
            socket.startHandshake();
            socket.getOutputStream().write(token);
            transferring.run();
            DataTransfer.receive(socket.getInputStream(), destination);
        }
    }
}
