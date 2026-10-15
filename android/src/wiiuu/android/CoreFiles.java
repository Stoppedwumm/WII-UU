package wiiuu.android;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Choccy Milk's cores: downloaded once from libretro's buildbot (the Android builds for this
 * device's processor) into the app's own folder, where Android lets apps load code from.
 */
final class CoreFiles {
    private CoreFiles() {}

    interface Progress {
        void at(String what, int percent);
    }

    /** arm64-v8a, armeabi-v7a, x86_64 or x86: the processor the cores are built for. */
    static String abi() {
        for (String a : Build.SUPPORTED_ABIS) {
            if (a.equals("arm64-v8a") || a.equals("armeabi-v7a") || a.equals("x86_64") || a.equals("x86")) return a;
        }
        return "arm64-v8a";
    }

    static File file(Context c, String core) {
        return new File(Store.cores(c), core + "_libretro_android.so");
    }

    /** The first of the console's cores that is here, or null. */
    static File installed(Context c, Consoles.Console k) {
        for (String core : k.cores) {
            File f = file(c, core);
            if (f.isFile() && f.length() > 0) return f;
        }
        return null;
    }

    /** Downloads the console's first core that the buildbot has (blocking; call it on a background thread). */
    static File download(Context c, Consoles.Console k, Progress p) throws IOException {
        IOException last = null;
        for (String core : k.cores) {
            try {
                return download(c, core, p);
            } catch (IOException e) {
                last = e;
            }
        }
        throw last != null ? last : new IOException("no core for " + k.name);
    }

    static File download(Context c, String core, Progress p) throws IOException {
        String url = "https://buildbot.libretro.com/nightly/android/latest/" + abi() + "/" + core + "_libretro_android.so.zip";
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(15000);
        con.setReadTimeout(30000);
        con.setRequestProperty("User-Agent", "WII-UU for Android");
        if (con.getResponseCode() != 200) throw new IOException(core + ": the download answered " + con.getResponseCode());
        long size = con.getContentLengthLong();
        File out = file(c, core), part = new File(out.getPath() + ".part");
        try (InputStream raw = con.getInputStream()) {
            CountingStream counted = new CountingStream(raw, size, core, p);
            try (ZipInputStream zip = new ZipInputStream(counted)) {
                for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                    if (e.isDirectory() || !e.getName().endsWith(".so")) continue;
                    try (OutputStream o = new FileOutputStream(part)) {
                        byte[] buf = new byte[1 << 16];
                        for (int n; (n = zip.read(buf)) > 0; ) o.write(buf, 0, n);
                    }
                    if (!part.renameTo(out)) throw new IOException("couldn't put " + out.getName() + " in place");
                    return out;
                }
            }
        } finally {
            part.delete();
            con.disconnect();
        }
        throw new IOException(core + ": no core in the download");
    }

    /** Deletes the downloaded cores (they come again when a game needs one). */
    static int deleteAll(Context c) {
        int n = 0;
        File[] all = Store.cores(c).listFiles();
        if (all != null) for (File f : all) if (f.delete()) n++;
        return n;
    }

    private static final class CountingStream extends java.io.FilterInputStream {
        private final long size;
        private final String what;
        private final Progress p;
        private long got;
        private int shown = -1;

        CountingStream(InputStream in, long size, String what, Progress p) {
            super(in);
            this.size = size;
            this.what = what;
            this.p = p;
        }

        private void count(long n) {
            if (n <= 0) return;
            got += n;
            int pct = size > 0 ? (int) Math.min(100, got * 100 / size) : -1;
            if (pct != shown && p != null) {
                shown = pct;
                p.at(what, pct);
            }
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) count(1);
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            count(n);
            return n;
        }
    }
}
