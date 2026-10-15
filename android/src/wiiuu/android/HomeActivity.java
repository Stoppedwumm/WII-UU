package wiiuu.android;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** WII-UU's menu on Android: the consoles, their games, Settings; a game opens Choccy Milk ({@link GameActivity}). */
public final class HomeActivity extends Activity implements HomeView.Host {
    static final String VERSION = "1.0";

    private final Library library = new Library();
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private HomeView view;
    private volatile boolean busy;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        view = new HomeView(this, this, library);
        setContentView(view);
        view.requestFocus();
        fullscreen();
        if (!Store.prefs(this).getBoolean("asked", false) && !Store.allFiles(this)) {
            Store.prefs(this).edit().putBoolean("asked", true).apply();
            view.message("Welcome to WII-UU", "Put your games in WiiUU/roms/<console> (nes, snes, gba, ps1...). To use a WiiUU "
                    + "folder you can copy into over USB, allow access to all files in Settings > Games folder. Without it, "
                    + "games go in " + Store.root(this), -1);
        }
    }

    private void fullscreen() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    @Override
    protected void onResume() {
        super.onResume();
        fullscreen();
        refresh();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) fullscreen();
    }

    @Override
    public void refresh() {
        work.execute(() -> {
            Store.prepare(this);
            library.scan(this);
            ui.post(view::libraryChanged);
        });
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e) {
        if (keyCode == KeyEvent.KEYCODE_BACK && view.backToHome()) return true;
        return super.onKeyDown(keyCode, e);
    }

    // ---- playing ----

    @Override
    public void play(Library.Game game) {
        if (busy) return;
        File core = CoreFiles.installed(this, game.console);
        if (core != null) {
            launch(game, core);
            return;
        }
        busy = true;
        view.message("Getting Choccy Milk's " + game.console.shortName + " core", "Once, from libretro's buildbot ("
                + CoreFiles.abi() + ").", 0);
        work.execute(() -> {
            try {
                File got = CoreFiles.download(this, game.console, (what, pct) ->
                        ui.post(() -> view.message("Getting Choccy Milk's " + game.console.shortName + " core", what, Math.max(0, pct))));
                ui.post(() -> {
                    busy = false;
                    view.message(null, null, -1);
                    launch(game, got);
                });
            } catch (IOException ex) {
                ui.post(() -> {
                    busy = false;
                    view.message("The core didn't download", ex.getMessage() + "\nIs the device online?", -1);
                });
            }
        });
    }

    private void launch(Library.Game game, File core) {
        File rom = game.file;
        if (rom.getName().toLowerCase(Locale.ROOT).endsWith(".zip")) {
            try {
                rom = unzip(rom, game.console);
            } catch (IOException e) {
                view.message("Couldn't open " + game.file.getName(), e.getMessage(), -1);
                return;
            }
        }
        SharedPreferences p = Store.prefs(this);
        Intent i = new Intent(this, GameActivity.class);
        i.putExtra("core", core.getPath());
        i.putExtra("rom", rom.getPath());
        i.putExtra("saves", Store.saves(this, game.console.id).getPath());
        i.putExtra("system", Store.system(this).getPath());
        i.putExtra("options", Consoles.options(game.console.id));
        i.putExtra("name", game.name);
        i.putExtra("sharp", p.getBoolean("sharp", true));
        i.putExtra("stretch", p.getBoolean("stretch", false));
        i.putExtra("touch", p.getString("touch", "auto"));
        startActivity(i);
    }

    /** A zipped game: its game file, unpacked into the cache (named as in the zip, so its saves keep their names). */
    private File unzip(File zip, Consoles.Console k) throws IOException {
        File dir = new File(getCacheDir(), "unzipped");
        dir.mkdirs();
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) f.delete();
        try (ZipInputStream z = new ZipInputStream(new java.io.FileInputStream(zip))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                String n = new File(e.getName()).getName();
                int dot = n.lastIndexOf('.');
                if (e.isDirectory() || dot < 0 || !k.extensions.contains(n.substring(dot + 1).toLowerCase(Locale.ROOT))
                        || n.toLowerCase(Locale.ROOT).endsWith(".zip")) continue;
                File out = new File(dir, n);
                try (OutputStream o = new FileOutputStream(out)) {
                    byte[] buf = new byte[1 << 16];
                    for (int r; (r = z.read(buf)) > 0; ) o.write(buf, 0, r);
                }
                return out;
            }
        }
        throw new IOException("no " + k.shortName + " game in it");
    }

    // ---- Settings ----

    @Override
    public List<HomeView.Row> settings() {
        SharedPreferences p = Store.prefs(this);
        List<HomeView.Row> rows = new ArrayList<>();
        rows.add(new HomeView.Row("Games folder", "roms/<console> in it; saves and BIOS files next to it",
                () -> Store.root(this).getPath(), this::askAllFiles));
        rows.add(new HomeView.Row("Access to all files", "For a WiiUU folder in the main storage (copy games in over USB)",
                () -> Store.allFiles(this) ? "on" : "off (press A)", this::askAllFiles));
        rows.add(new HomeView.Row("Sharp pixels", "Whole pixels; off: smoothed", () -> p.getBoolean("sharp", true) ? "on" : "off",
                () -> toggle("sharp", true)));
        rows.add(new HomeView.Row("Fill the screen", "Stretch the picture; off: the game's own shape",
                () -> p.getBoolean("stretch", false) ? "on" : "off", () -> toggle("stretch", false)));
        rows.add(new HomeView.Row("Touch controls", "On the screen while playing: automatic hides them once a controller is used",
                () -> p.getString("touch", "auto"), () -> {
            String now = p.getString("touch", "auto");
            String next = now.equals("auto") ? "always" : now.equals("always") ? "never" : "auto";
            p.edit().putString("touch", next).apply();
            view.invalidate();
        }));
        rows.add(new HomeView.Row("Download every core now", "So games start without waiting (needs a connection)",
                this::coreCount, this::downloadAll));
        rows.add(new HomeView.Row("Delete the downloaded cores", "They come again when a game needs one", null, () -> {
            int n = CoreFiles.deleteAll(this);
            view.message("Cores deleted", n + (n == 1 ? " core" : " cores") + " deleted.", -1);
        }));
        rows.add(new HomeView.Row("BIOS files", "Some consoles need them (PlayStation plays without)",
                () -> Store.system(this).getPath(), null));
        rows.add(new HomeView.Row("About", "WII-UU for Android handhelds, with Choccy Milk",
                () -> "WII-UU " + VERSION + " · Choccy Milk · " + CoreFiles.abi(), null));
        return rows;
    }

    private String coreCount() {
        int n = 0;
        for (Consoles.Console k : Consoles.ALL) if (CoreFiles.installed(this, k) != null) n++;
        return n + " of " + Consoles.ALL.size() + " consoles";
    }

    private void toggle(String key, boolean def) {
        SharedPreferences p = Store.prefs(this);
        p.edit().putBoolean(key, !p.getBoolean(key, def)).apply();
        view.invalidate();
    }

    private void downloadAll() {
        if (busy) return;
        busy = true;
        work.execute(() -> {
            int got = 0, failed = 0;
            for (Consoles.Console k : Consoles.ALL) {
                if (CoreFiles.installed(this, k) != null) continue;
                try {
                    CoreFiles.download(this, k, (what, pct) -> ui.post(() -> view.message("Getting the cores", what, Math.max(0, pct))));
                    got++;
                } catch (IOException e) {
                    failed++;
                }
            }
            int g = got, f = failed;
            ui.post(() -> {
                busy = false;
                view.message("Cores", g + " downloaded" + (f > 0 ? ", " + f + " couldn't be (offline?)" : "") + ".", -1);
            });
        });
    }

    private void askAllFiles() {
        if (Store.allFiles(this)) {
            view.message("Games folder", "Games go in " + new File(Store.root(this), "roms") + "/<console>.", -1);
            return;
        }
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        refresh();
    }
}
