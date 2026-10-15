package wiiuu.android;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;

import java.io.File;

/**
 * Where things are: the WII-UU folder (games in roms/&lt;console&gt;, saves in saves/&lt;console&gt;,
 * BIOS files in system), laid out as on a computer, so it can be copied over either way.
 *
 * <p>With access to all files it's WiiUU in the device's main storage (easy to copy games into
 * over USB); without, the app's own folder (Android/data/wiiuu.android/files).
 */
final class Store {
    private Store() {}

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("wiiuu", Context.MODE_PRIVATE);
    }

    /** Whether WII-UU may use the shared WiiUU folder. */
    static boolean allFiles(Context c) {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return c.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    static File root(Context c) {
        String set = prefs(c).getString("root", "");
        if (!set.isEmpty() && allFiles(c)) return new File(set);
        if (allFiles(c)) return new File(Environment.getExternalStorageDirectory(), "WiiUU");
        File own = c.getExternalFilesDir(null);
        return own != null ? own : c.getFilesDir();
    }

    static File roms(Context c, String console) {
        return new File(new File(root(c), "roms"), console);
    }

    static File saves(Context c, String console) {
        return new File(new File(root(c), "saves"), console);
    }

    static File system(Context c) {
        return new File(root(c), "system");
    }

    /** Makes the folders (one per console), so it's clear where games go. */
    static void prepare(Context c) {
        for (Consoles.Console k : Consoles.ALL) {
            roms(c, k.id).mkdirs();
            saves(c, k.id).mkdirs();
        }
        system(c).mkdirs();
    }

    /** Downloaded cores (the app's private folder: Android only loads code from there). */
    static File cores(Context c) {
        File d = new File(c.getFilesDir(), "cores");
        d.mkdirs();
        return d;
    }
}
