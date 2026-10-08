package wiiuu.ui;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.event.KeyEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import javax.swing.SwingUtilities;

import wiiuu.core.Changelog;
import wiiuu.core.Config;
import wiiuu.core.GameSystem;
import wiiuu.core.Systems;
import wiiuu.core.Updater;
import wiiuu.input.KeyMap;
import wiiuu.input.PadButton;

/**
 * WII-UU's settings, drawn on the TV like the rest of the menu (no window of the operating
 * system's): categories down the left, their settings on the right, pages for each console and
 * for the keyboard keys, all steered with a phone, a controller, the keyboard or the mouse.
 * Every change applies (and is saved) straight away.
 *
 * <p>Text is typed on an on-screen keyboard (or a real one, which also pastes with Ctrl+V);
 * folders and programs are picked in a built-in file browser. The list scrolls smoothly to
 * whatever is chosen, and with the mouse wheel.
 */
public final class SettingsScreen {

    /** What the settings can do beyond the config file; Main fills it in. */
    public record Services(Config config, wiiuu.core.OpenBased openBased, Supplier<String> gamepadBase,
                    Runnable showGuide, Runnable restartIntoGuide, Updater updater, Runnable exitForUpgrade,
                    Runnable onClosed) {}

    // ---- rows -----------------------------------------------------------------------------------

    /** One line of a page. */
    private abstract static class Row {
        final String label, help;
        Rectangle2D rect;                                  // where it was drawn (for the mouse)
        float knob = -1;                                   // switches: knob position, animated

        Row(String label, String help) {
            this.label = label;
            this.help = help;
        }

        boolean focusable() {
            return true;
        }

        /** What is shown on the right (null: nothing). */
        String value() {
            return null;
        }

        /** Left / right on the row: true if it used them (else they move between the columns). */
        boolean adjust(int dir) {
            return false;
        }

        void activate() {
        }

        /** How tall, in rows. */
        float height() {
            return 1;
        }
    }

    private final class Toggle extends Row {
        final BooleanSupplier get;
        final Consumer<Boolean> set;

        Toggle(String label, String help, BooleanSupplier get, Consumer<Boolean> set) {
            super(label, help);
            this.get = get;
            this.set = set;
        }

        @Override
        boolean adjust(int dir) {
            if (get.getAsBoolean() != dir > 0) activate();
            else Sfx.bump();
            return true;
        }

        @Override
        void activate() {
            set.accept(!get.getAsBoolean());
            Sfx.select();
        }
    }

    private final class Choice extends Row {
        final Supplier<List<String>> names;
        final IntSupplier index;
        final IntConsumer set;

        Choice(String label, String help, Supplier<List<String>> names, IntSupplier index, IntConsumer set) {
            super(label, help);
            this.names = names;
            this.index = index;
            this.set = set;
        }

        @Override
        String value() {
            List<String> n = names.get();
            int i = index.getAsInt();
            return i >= 0 && i < n.size() ? n.get(i) : "";
        }

        @Override
        boolean adjust(int dir) {
            int n = names.get().size();
            if (n == 0) return true;
            set.accept(Math.floorMod(index.getAsInt() + dir, n));
            Sfx.move();
            return true;
        }

        @Override
        void activate() {
            adjust(1);
        }
    }

    private final class NumberRow extends Row {
        final int min, max;
        final IntSupplier get;
        final IntConsumer set;

        NumberRow(String label, String help, int min, int max, IntSupplier get, IntConsumer set) {
            super(label, help);
            this.min = min;
            this.max = max;
            this.get = get;
            this.set = set;
        }

        @Override
        String value() {
            return Integer.toString(get.getAsInt());
        }

        @Override
        boolean adjust(int dir) {
            int v = Math.max(min, Math.min(max, get.getAsInt() + dir));
            if (v == get.getAsInt()) Sfx.bump();
            else {
                set.accept(v);
                Sfx.move();
            }
            return true;
        }

        @Override
        void activate() {
            edit(label, Integer.toString(get.getAsInt()), false, s -> {
                try {
                    set.accept(Math.max(min, Math.min(max, Integer.parseInt(s.trim()))));
                } catch (NumberFormatException e) {
                    note = "That isn't a number";
                }
            });
        }
    }

    private final class Text extends Row {
        final Supplier<String> get;
        final Consumer<String> set;
        final boolean secret;
        final String empty;

        Text(String label, String help, Supplier<String> get, Consumer<String> set, boolean secret, String empty) {
            super(label, help);
            this.get = get;
            this.set = set;
            this.secret = secret;
            this.empty = empty;
        }

        @Override
        String value() {
            String v = get.get();
            if (v == null || v.isEmpty()) return empty;
            return secret ? "•".repeat(Math.min(12, v.length())) : v;
        }

        @Override
        void activate() {
            Sfx.select();
            edit(label, secret ? "" : get.get(), secret, set);
        }
    }

    private final class Action extends Row {
        final Runnable run;
        final Supplier<String> status;
        final String confirm;                              // non-null: A twice
        long armed;

        Action(String label, String help, Runnable run, Supplier<String> status, String confirm) {
            super(label, help);
            this.run = run;
            this.status = status;
            this.confirm = confirm;
        }

        @Override
        String value() {
            if (armed != 0 && System.currentTimeMillis() - armed < 4000) return confirm;
            return status == null ? null : status.get();
        }

        @Override
        void activate() {
            if (confirm != null && (armed == 0 || System.currentTimeMillis() - armed > 4000)) {
                armed = System.currentTimeMillis();
                Sfx.select();
                return;
            }
            armed = 0;
            Sfx.select();
            run.run();
        }
    }

    /** Opens another page. */
    private final class Link extends Row {
        final Supplier<Page> page;
        final Supplier<String> status;

        Link(String label, String help, Supplier<String> status, Supplier<Page> page) {
            super(label, help);
            this.page = page;
            this.status = status;
        }

        @Override
        String value() {
            return status == null ? null : status.get();
        }

        @Override
        void activate() {
            Sfx.select();
            push(page.get());
        }
    }

    /** A key on the keyboard for a GamePad button: left / right go through the keys, A waits for one. */
    private final class KeyRow extends Row {
        final int player;
        final PadButton button;

        KeyRow(int player, PadButton button) {
            super(buttonLabel(button), "A, then press the key on the keyboard (or ◀ ▶ to go through them). "
                    + "Emulators get this key while a game runs.");
            this.player = player;
            this.button = button;
        }

        @Override
        String value() {
            if (capturing == this) return "Press a key… (Esc: cancel)";
            String k = new KeyMap(config).keyName(player, button);
            return k == null || k.isBlank() ? "(none)" : k;
        }

        @Override
        boolean adjust(int dir) {
            List<String> all = new ArrayList<>(KeyMap.allNames());
            java.util.Collections.sort(all);
            all.add(0, "");
            int at = Math.max(0, all.indexOf(new KeyMap(config).keyName(player, button)));
            setKey(all.get(Math.floorMod(at + dir, all.size())));
            Sfx.move();
            return true;
        }

        void setKey(String name) {
            config.set("keys.p" + player + "." + button.name(), name.equals(KeyMap.defaultKey(player, button)) ? null : name);
            config.save();
        }

        @Override
        void activate() {
            capturing = this;
            Sfx.select();
        }
    }

    private final class Info extends Row {
        Info(String text) {
            super(text, null);
        }

        @Override
        boolean focusable() {
            return false;
        }

        @Override
        float height() {
            return -1;                                     // as tall as its wrapped text
        }
    }

    private final class Header extends Row {
        Header(String text) {
            super(text, null);
        }

        @Override
        boolean focusable() {
            return false;
        }

        @Override
        float height() {
            return 0.8f;
        }
    }

    /** A page: a title and its rows (made again whenever it is shown, so they're up to date). */
    private record Page(String title, Supplier<List<Row>> rows) {}

    // ---- state ------------------------------------------------------------------------------------

    private final MenuView view;
    private final Services sv;
    private final Config config;
    private final long opened = System.currentTimeMillis();
    private long closing;

    private final List<String> categories = List.of("Look & sound", "Games", "GamePad & players", "OpenBased", "System");
    private int category;
    private boolean inSidebar = true;

    /** pages opened from a category (systems, keys, folders, ...); empty: the category itself */
    private final List<Page> stack = new ArrayList<>();
    private final List<Integer> focusStack = new ArrayList<>();
    private Page page;
    private List<Row> rows = List.of();
    private int focus;
    private float scroll, scrollGoal;                       // content offset in pixels
    private float contentHeight, viewHeight;
    private final float[] frame = new float[4];
    private float[] frameGoal;
    private boolean frameSet;
    private long changed = System.currentTimeMillis();
    private int slideDir = 1;
    private String note;                                    // a passing message at the bottom
    private long noteAt;
    private KeyRow capturing;
    private Editor editor;

    SettingsScreen(MenuView view, Services services) {
        this.view = view;
        this.sv = services;
        this.config = services.config();
        showCategory(0);
        Sfx.select();
    }

    private void showCategory(int c) {
        category = c;
        stack.clear();
        focusStack.clear();
        page = categoryPage(c);
        reload(true);
    }

    /** Makes the rows again (after a change that adds or removes some). */
    private void reload(boolean toTop) {
        rows = page.rows().get();
        if (toTop) {
            focus = firstFocusable();
            scroll = scrollGoal = 0;
            changed = System.currentTimeMillis();
        }
        focus = Math.max(0, Math.min(focus, rows.size() - 1));
        if (!rows.isEmpty() && !rows.get(focus).focusable()) focus = firstFocusable();
    }

    private int firstFocusable() {
        for (int i = 0; i < rows.size(); i++) if (rows.get(i).focusable()) return i;
        return 0;
    }

    private void push(Page p) {
        stack.add(page);
        focusStack.add(focus);
        page = p;
        slideDir = 1;
        reload(true);
        inSidebar = false;
    }

    private void pop() {
        page = stack.remove(stack.size() - 1);
        int f = focusStack.remove(focusStack.size() - 1);
        slideDir = -1;
        reload(true);
        focus = Math.min(f, rows.size() - 1);
    }

    void close() {
        if (closing != 0) return;
        closing = System.currentTimeMillis();
        Sfx.back();
    }

    boolean isClosing() {
        return closing != 0;
    }

    // ---- input ------------------------------------------------------------------------------------

    void navigate(int dx, int dy) {
        if (closing != 0) return;
        if (editor != null) {
            editor.navigate(dx, dy);
            return;
        }
        capturing = null;
        if (inSidebar) {
            if (dy != 0) {
                int c = category + dy;
                if (c < 0 || c >= categories.size()) Sfx.bump();
                else {
                    slideDir = dy;
                    showCategory(c);
                    Sfx.move();
                }
            } else if (dx > 0 && hasFocusable()) {
                inSidebar = false;
                Sfx.move();
            } else Sfx.bump();
            return;
        }
        if (dy != 0) {
            int i = focus;
            do i += dy; while (i >= 0 && i < rows.size() && !rows.get(i).focusable());
            if (i < 0 || i >= rows.size()) {
                Sfx.bump();
                if (dy < 0) scrollGoal = 0;               // show the text above the first setting
                return;
            }
            focus = i;
            Sfx.move();
            return;
        }
        Row r = rows.isEmpty() ? null : rows.get(focus);
        if (r != null && r.adjust(dx)) return;
        if (dx < 0) {
            if (!stack.isEmpty()) {
                pop();
                Sfx.back();
            } else {
                inSidebar = true;
                Sfx.move();
            }
        } else Sfx.bump();
    }

    private boolean hasFocusable() {
        for (Row r : rows) if (r.focusable()) return true;
        return false;
    }

    void activate() {
        if (closing != 0) return;
        if (editor != null) {
            editor.press();
            return;
        }
        if (inSidebar) {
            if (hasFocusable()) {
                inSidebar = false;
                Sfx.select();
            }
            return;
        }
        if (!rows.isEmpty() && rows.get(focus).focusable()) rows.get(focus).activate();
    }

    void back() {
        if (closing != 0) return;
        if (editor != null) {
            editor.backspace();
            return;
        }
        if (capturing != null) {
            capturing = null;
            Sfx.back();
            return;
        }
        if (!stack.isEmpty()) {
            pop();
            Sfx.back();
        } else if (!inSidebar) {
            inSidebar = true;
            Sfx.back();
        } else {
            close();
        }
    }

    /** L / R: the category before / after (in the editor: moves the cursor). */
    void page(int delta) {
        if (closing != 0) return;
        if (editor != null) {
            editor.moveCaret(delta);
            return;
        }
        int c = category + delta;
        if (c < 0 || c >= categories.size()) {
            Sfx.bump();
            return;
        }
        slideDir = delta;
        showCategory(c);
        inSidebar = false;
        if (!hasFocusable()) inSidebar = true;
        Sfx.move();
    }

    /** + on a GamePad: finishes typing. */
    void plus() {
        if (editor != null) editor.done();
    }

    /** Whether the keyboard's keys should come here as they are (typing, or waiting for a key). */
    boolean wantsKeys() {
        return editor != null || capturing != null;
    }

    void keyPressed(KeyEvent e) {
        if (capturing != null) {
            if (e.getKeyCode() != KeyEvent.VK_ESCAPE) {
                String name = KeyMap.nameOf(e.getKeyCode());
                if (!name.isEmpty()) capturing.setKey(name);
                Sfx.select();
            } else Sfx.back();
            capturing = null;
            return;
        }
        if (editor != null) editor.key(e);
    }

    void keyTyped(char c) {
        if (editor != null) editor.typed(c);
    }

    void wheel(int notches) {
        if (editor != null) return;
        scrollGoal = clampScroll(scrollGoal + notches * viewHeight * 0.12f);
        wheelAt = System.currentTimeMillis();
    }

    private long wheelAt;

    void click(double x, double y) {
        if (closing != 0) return;
        if (editor != null) {
            editor.click(x, y);
            return;
        }
        if (closeRect != null && closeRect.contains(x, y)) {
            close();
            return;
        }
        if (backRect != null && backRect.contains(x, y) && !stack.isEmpty()) {
            pop();
            Sfx.back();
            return;
        }
        for (int i = 0; i < categoryRects.length; i++) {
            if (categoryRects[i] != null && categoryRects[i].contains(x, y)) {
                if (i != category || !stack.isEmpty()) {
                    slideDir = i > category ? 1 : -1;
                    showCategory(i);
                }
                inSidebar = true;
                Sfx.move();
                return;
            }
        }
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            if (r.rect == null || !r.focusable() || !r.rect.contains(x, y)) continue;
            focus = i;
            inSidebar = false;
            capturing = null;
            // choices: the arrows on the right go back and forth
            if ((r instanceof Choice || r instanceof NumberRow || r instanceof KeyRow) && x > r.rect.getMaxX() - r.rect.getHeight() * 1.2) r.adjust(1);
            else if ((r instanceof Choice || r instanceof NumberRow || r instanceof KeyRow) && arrowLeft(r, x)) r.adjust(-1);
            else r.activate();
            return;
        }
    }

    private boolean arrowLeft(Row r, double x) {
        Float at = leftArrowX.get(r);
        return at != null && Math.abs(x - at) < r.rect.getHeight() * 0.6;
    }

    private final java.util.Map<Row, Float> leftArrowX = new java.util.IdentityHashMap<>();

    private void say(String text) {
        note = text;
        noteAt = System.currentTimeMillis();
    }

    // ---- the pages ----------------------------------------------------------------------------------

    private Page categoryPage(int c) {
        return switch (c) {
            case 0 -> new Page("Look & sound", this::lookRows);
            case 1 -> new Page("Games", this::gameRows);
            case 2 -> new Page("GamePad & players", this::padRows);
            case 3 -> new Page("OpenBased", this::openBasedRows);
            default -> new Page("System", this::systemRows);
        };
    }

    private Toggle toggle(String label, String help, String key, boolean def, Runnable after) {
        return new Toggle(label, help, () -> config.getBool(key, def), on -> {
            config.set(key, Boolean.toString(on));
            config.save();
            if (after != null) after.run();
        });
    }

    private List<Row> lookRows() {
        List<Row> r = new ArrayList<>();
        r.add(new Header("Look"));
        List<String> themeIds = new ArrayList<>(List.of("auto", "light", "dark"));
        List<String> themeNames = new ArrayList<>(List.of("Follow the system", "Light", "Dark"));
        for (Themes.Entry e : Themes.list(config)) {
            themeIds.add(e.id());
            themeNames.add(e.name() + (e.builtIn() ? "" : " (yours)"));
        }
        r.add(new Choice("Theme", "The menu's colours. Themes are text files you can write yourself (below).",
                () -> themeNames,
                () -> Math.max(0, themeIds.indexOf(config.get("ui.theme", "auto").trim().toLowerCase(Locale.ROOT))),
                i -> {
                    config.set("ui.theme", themeIds.get(i));
                    config.save();
                    view.setThemeMode(themeIds.get(i));
                }));
        r.add(new Action("Make your own theme", "Makes ~/.wiiuu/themes with a theme to start from and the theme language's "
                + "reference. Edit it with any text editor: WII-UU redraws as soon as you save.", () -> {
            try {
                Path dir = Themes.prepareFolder(config);
                openFolder(dir);
                reload(false);
            } catch (IOException e) {
                say("Could not make the themes folder: " + e.getMessage());
            }
        }, () -> Themes.folder(config).toString(), null));
        r.add(toggle("Start-up animation", "The console-style animation when WII-UU starts.", "ui.bootAnimation", true, null));
        r.add(toggle("Start in fullscreen", "From the next start (F11 switches now).", "ui.fullscreen", false, null));
        r.add(new Header("Sound"));
        r.add(toggle("Menu music", "Music in the menu; it fades out while a game runs.", "ui.music", true,
                () -> view.setMusicEnabled(config.getBool("ui.music", true))));
        List<MenuTracks.Track> tracks = new ArrayList<>(MenuTracks.all(musicFolder()));
        tracks.add(new MenuTracks.Track(ExtendedMix.ID, ExtendedMix.NAME));
        tracks.add(new MenuTracks.Track(MenuTracks.ALL, "All of them, taking turns"));
        r.add(new Choice("Menu music track", "Which music plays in the menu.",
                () -> tracks.stream().map(MenuTracks.Track::name).toList(),
                () -> {
                    String id = config.get("ui.musicTrack", MenuTracks.DEFAULT).trim();
                    for (int i = 0; i < tracks.size(); i++) if (tracks.get(i).id().equals(id)) return i;
                    return 0;
                },
                i -> {
                    String id = tracks.get(i).id();
                    config.set("ui.musicTrack", id.equals(MenuTracks.DEFAULT) ? null : id);
                    config.save();
                    view.setMusicEnabled(config.getBool("ui.music", true));
                }));
        r.add(new Action("Add your own music", "Opens the music folder: put WAV or AIFF files there (MP3 isn't supported). "
                + "They show up in the list above.", () -> {
            try {
                Files.createDirectories(musicFolder());
                openFolder(musicFolder());
            } catch (IOException e) {
                say("Could not make " + musicFolder());
            }
        }, () -> musicFolder().toString(), null));
        r.add(toggle("Menu sounds", "The clicks and chimes when you move around.", "ui.sounds", true,
                () -> view.setSoundsEnabled(config.getBool("ui.sounds", true))));
        r.add(new Toggle("Music visualizer", "Colours behind the menu that move with the music (V: full screen).",
                () -> config.getBool("ui.visualizer", true), on -> {
            config.set("ui.visualizer", on ? null : "false");
            config.save();
            view.setMusicEnabled(config.getBool("ui.music", true));
        }));
        return r;
    }

    private List<Row> gameRows() {
        List<Row> r = new ArrayList<>();
        r.add(new Text("Games folder", "Each console's games go in a folder of its own in here (nes, snes, n64, ...).",
                () -> config.romBase().toString(), v -> {
            config.set("roms.base", v.isBlank() ? null : v.trim());
            config.save();
        }, false, ""));
        r.add(new Link("Choose the games folder", "Pick the folder in a file browser.", null,
                () -> browser("Games folder", config.romBase(), true, p -> {
                    config.set("roms.base", p.toString());
                    config.save();
                })));
        r.add(new Link("Consoles and emulators", "Which consoles show, where their games are, and the program that plays them.",
                () -> Systems.ALL.size() + " consoles", this::systemsPage));
        r.add(new Header("Playing"));
        r.add(toggle("RetroArch mode", "Runs every console that has a RetroArch core in RetroArch.", "retroarch.enabled", false, null));
        r.add(new Toggle("Split DS / 3DS screens", "In RetroArch mode: the top screen on the TV, the touch screen on the phone.",
                () -> config.getBool("screen.split", true), on -> {
            config.set("screen.split", on ? null : "false");
            config.save();
        }));
        r.add(toggle("Hide consoles without games", "Only consoles you have games for show on the home screen.", "ui.hideEmpty", false, null));
        r.add(toggle("Minimize the menu while a game runs", "So the emulator's window is in front.", "ui.minimizeOnLaunch", true, null));
        return r;
    }

    private Page systemsPage() {
        return new Page("Consoles and emulators", () -> {
            List<Row> r = new ArrayList<>();
            for (GameSystem s : Systems.ALL) {
                r.add(new Link(s.name(), s.maker() + ", " + s.year() + ". Played with " + s.emulator() + ".",
                        () -> config.hidden(s) ? "hidden" : s.emulator(), () -> systemPage(s)));
            }
            return r;
        });
    }

    private Page systemPage(GameSystem s) {
        return new Page(s.name(), () -> {
            List<Row> r = new ArrayList<>();
            r.add(new Toggle("Show on the home screen", null, () -> !config.hidden(s), on -> {
                config.set("system." + s.id() + ".hidden", on ? null : "true");
                config.save();
            }));
            r.add(new Text("Command", "How the game starts: {rom} is the game's file, {dir} its folder, {name} its title. "
                    + "Put paths with spaces in quotes. macOS apps: open -W -a AppName --args {rom}",
                    () -> config.command(s), v -> setCommand(s, v), false, s.defaultCommand()));
            r.add(new Link("Choose the emulator program", "Pick the program in a file browser; the command keeps its options.", null,
                    () -> browser("Emulator for " + s.shortName(), Paths.get(System.getProperty("user.home")), false,
                            p -> setCommand(s, replaceProgram(config.command(s), p.toFile())))));
            r.add(new Action("Reset the command", "Back to " + s.defaultCommand(), () -> setCommand(s, s.defaultCommand()), null, null));
            r.add(new Text("Games folder", "Where this console's games are.", () -> config.romDir(s).toString(), v -> {
                String def = config.romBase().resolve(s.id()).toString();
                config.set("system." + s.id() + ".romdir", v.isBlank() || v.trim().equals(def) ? null : v.trim());
                config.save();
            }, false, ""));
            r.add(new Link("Choose its games folder", null, null, () -> browser(s.shortName() + " games", config.romDir(s), true, p -> {
                String def = config.romBase().resolve(s.id()).toString();
                config.set("system." + s.id() + ".romdir", p.toString().equals(def) ? null : p.toString());
                config.save();
            })));
            r.add(new Action("Open its games folder", "In the computer's file manager, to copy games in.", () -> {
                try {
                    Files.createDirectories(config.romDir(s));
                    openFolder(config.romDir(s));
                } catch (IOException e) {
                    say("Could not make " + config.romDir(s));
                }
            }, null, null));
            return r;
        });
    }

    private void setCommand(GameSystem s, String v) {
        String cmd = v == null ? "" : v.trim();
        config.set("system." + s.id() + ".command", cmd.isEmpty() || cmd.equals(s.defaultCommand()) ? null : cmd);
        config.save();
    }

    private List<Row> padRows() {
        List<Row> r = new ArrayList<>();
        r.add(new Header("Phones"));
        r.add(toggle("Phone GamePad server", "Phones become GamePads by opening WII-UU's page. From the next start.",
                "server.enabled", true, null));
        r.add(toggle("Pairing code", "Phones type the code from the TV before they can play.", "server.requireCode", true, null));
        r.add(new Text("Fixed pairing code", "Keeps the same code; phones stay paired. Change it to unpair them all.",
                () -> config.get("server.code", ""), v -> {
            config.set("server.code", v.isBlank() ? null : v.trim());
            config.save();
        }, false, "a new one each start"));
        r.add(new NumberRow("Server port", "The port of the GamePad page. From the next start.", 1024, 65535,
                config::port, v -> {
            config.set("server.port", Integer.toString(v));
            config.save();
        }));
        r.add(new Toggle("8-player mode", "Up to 8 phones at once instead of 4. From the next start.",
                () -> config.getInt("server.maxPlayers", 4) > 4, on -> {
            config.set("server.maxPlayers", on ? "8" : null);
            config.save();
        }));
        r.add(new Toggle("Buzz! mode", "Phones become Buzz! buzzers in PS2 Buzz! games.",
                () -> config.getBool("buzz.enabled", true), on -> {
            config.set("buzz.enabled", on ? null : "false");
            config.save();
        }));
        r.add(new Header("Keyboard keys"));
        r.add(new Info("While a game runs, GamePad buttons are typed as these keys into the emulator. "
                + "The defaults follow RetroArch (A=X, B=Z, X=S, Y=A, L=Q, R=W, + = Enter)."));
        for (int p = 1; p <= KeyMap.MAX_PLAYERS; p++) {
            int player = p;
            r.add(new Link("Player " + p, null, null, () -> keysPage(player)));
        }
        return r;
    }

    private Page keysPage(int player) {
        return new Page("Player " + player + " keys", () -> {
            List<Row> r = new ArrayList<>();
            r.add(new Action("Reset to the defaults", null, () -> {
                for (PadButton b : PadButton.values()) config.set("keys.p" + player + "." + b.name(), null);
                config.save();
                say("Player " + player + "'s keys are the defaults again");
            }, null, "Press A again to reset"));
            for (PadButton b : PadButton.values()) r.add(new KeyRow(player, b));
            return r;
        });
    }

    private List<Row> openBasedRows() {
        List<Row> r = new ArrayList<>();
        wiiuu.core.OpenBased ob = sv.openBased();
        r.add(new Info("Your OpenBased server's movies and episodes show up as a channel on the home screen and play "
                + "full screen on the TV (mpv works best: it resumes where you stopped and saves your progress)."));
        r.add(new Text("Server address", "For example http://192.168.1.20:8080 (OpenBased's issuer address).",
                () -> config.get("openbased.url", ""), v -> {
            String url = v.trim();
            config.set("openbased.url", url.isEmpty() ? null : url);
            if (url.isEmpty()) config.set("openbased.token", null);
            config.save();
        }, false, "not set"));
        boolean hasToken = !config.get("openbased.token", "").isBlank();
        r.add(new Action("Sign in", "Opens OpenBased's sign-in page in the browser (or sign in from the phone: "
                + "GamePad page, Library, OpenBased). Needs the GamePad server.", this::signIn,
                () -> ob != null && ob.user() != null ? "Signed in as " + ob.user()
                        : !config.get("openbased.token", "").isBlank() ? "Connected with a token" : "Not signed in", null));
        if (hasToken && ob != null) {
            r.add(new Action("Sign out", null, () -> {
                ob.signOut();
                say("Signed out");
                reload(false);
            }, null, "Press A again to sign out"));
        }
        r.add(new Text("Personal access token", "Or paste one from OpenBased's API tokens page: media.read, media.stream, "
                + "history.read, history.write and profile, 1 year. Ctrl+V pastes.",
                () -> config.get("openbased.token", ""), v -> {
            if (v.isBlank()) return;                       // empty keeps the one set
            config.set("openbased.token", v.trim());
            config.set("openbased.tokenId", null);
            config.set("openbased.user", null);
            config.save();
        }, true, "none"));
        r.add(new Text("Video player", "Empty: mpv, else VLC, else ffplay. Your own: {url} {title} {start}.",
                () -> config.get("openbased.player", ""), v -> {
            config.set("openbased.player", v.isBlank() ? null : v.trim());
            config.save();
        }, false, "mpv, VLC or ffplay"));
        String base = sv.gamepadBase().get();
        String snippet = ob == null || base == null ? "(turn on the GamePad server first)" : ob.clientConfig(ob.redirectUri(base));
        r.add(new Header("Once, on the OpenBased server"));
        r.add(new Info("Add this under openbased.clients in its application.yml (/etc/openbased/application.yml when "
                + "installed as a service; then sudo systemctl restart openbased):"));
        for (String line : snippet.split("\n")) r.add(new Info("    " + line));
        r.add(new Info("GamePad while a video plays: A / + pause, ◀ ▶ seek, ▲ ▼ a minute, ZL / ZR volume, Y mute, "
                + "X subtitles, HOME closes it."));
        return r;
    }

    private void signIn() {
        wiiuu.core.OpenBased ob = sv.openBased();
        String base = sv.gamepadBase().get();
        if (ob == null || base == null) {
            say("Signing in comes back through the GamePad server: turn it on first");
            return;
        }
        if (config.get("openbased.url", "").isBlank()) {
            say("Enter the server address first");
            return;
        }
        try {
            String url = ob.loginUrl(ob.redirectUri(base), null);
            java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
            say("Finish signing in in the browser");
        } catch (Exception e) {
            say("No browser here: sign in from the phone (GamePad page, Library, OpenBased)");
        }
    }

    private String updateStatus = "Version " + wiiuu.Main.VERSION;
    private Updater.Release release;
    private List<Changelog.Entry> releaseNotes = List.of();
    private boolean updating;

    private List<Row> systemRows() {
        List<Row> r = new ArrayList<>();
        r.add(new Header("Updates"));
        r.add(new Action("Check for updates", null, this::checkForUpdates, () -> updateStatus, null));
        if (release != null) {
            r.add(new Action("Update to " + release.version(), "Your settings, games, paired phones and emulators are kept. "
                    + "WII-UU restarts when it's done.", this::upgrade, null, "Press A again to update now"));
            for (Changelog.Entry e : releaseNotes) {
                r.add(new Header(e.version()));
                for (String s : e.summary()) r.add(new Info(s));
                for (String s : e.points()) r.add(new Info("•  " + s));
            }
        }
        r.add(new Link("What's new", "Everything that changed, newest first.", null, this::whatsNewPage));
        r.add(new Header("Setup guide"));
        r.add(new Action("Show the setup guide", "The welcome and setup screens from the first start.", () -> {
            close();
            sv.showGuide().run();
        }, null, null));
        r.add(new Action("Restart into the setup guide", "Restarts WII-UU like on its first start: the start-up animation, "
                + "the intro, then the guide. Settings and games are kept.", sv.restartIntoGuide(), null,
                "Press A again to restart"));
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            r.add(new Header("Windows"));
            r.add(new Action("Install the virtual display", "For split DS / 3DS screens. Asks for administrator rights once.", () -> {
                say("Installing… allow the administrator prompt");
                Thread t = new Thread(() -> {
                    String result = wiiuu.screen.VirtualDisplay.installWindows(config);
                    SwingUtilities.invokeLater(() -> say(result));
                }, "install-virtual-display");
                t.setDaemon(true);
                t.start();
            }, null, null));
        }
        if (os.contains("mac")) {
            r.add(new Header("macOS permissions"));
            r.add(new Info("Turn on WII-UU (or Java / Terminal) in both, then restart WII-UU: without them the GamePad screen "
                    + "stays black and buttons don't reach games."));
            r.add(new Action("Screen Recording", null, () -> openMacPane("Privacy_ScreenCapture"), null, null));
            r.add(new Action("Accessibility", null, () -> openMacPane("Privacy_Accessibility"), null, null));
        }
        r.add(new Header("About"));
        r.add(new Info("WII-UU " + wiiuu.Main.VERSION + ". Settings are in " + config.home().resolve("config.properties")));
        return r;
    }

    private Page whatsNewPage() {
        return new Page("What's new", () -> {
            List<Row> r = new ArrayList<>();
            for (Changelog.Entry e : Changelog.bundled().entries()) {
                r.add(new Header(e.version() + (e.date() == null ? "" : "  ·  " + e.date())));
                for (String s : e.summary()) r.add(new Info(s));
                for (String s : e.points()) r.add(new Info("•  " + s.replace("`", "").replace("*", "")));
            }
            return r;
        });
    }

    private void checkForUpdates() {
        if (sv.updater() == null || updating) return;
        updating = true;
        updateStatus = "Checking…";
        Thread t = new Thread(() -> {
            String status;
            Updater.Release rel = null;
            List<Changelog.Entry> notes = List.of();
            try {
                rel = sv.updater().check();
                if (rel != null) notes = sv.updater().notes(rel);
                status = rel == null ? "You have the newest version (" + wiiuu.Main.VERSION + ")" : "Version " + rel.version() + " is out";
            } catch (Exception e) {
                status = "Could not check: " + e.getMessage();
            }
            String s = status;
            Updater.Release found = rel;
            List<Changelog.Entry> n = notes;
            SwingUtilities.invokeLater(() -> {
                updating = false;
                updateStatus = s;
                release = found;
                releaseNotes = n;
                if (category == 4 && stack.isEmpty()) reload(false);
                view.repaint();
            });
        }, "settings-update");
        t.setDaemon(true);
        t.start();
    }

    private void upgrade() {
        if (release == null || updating) return;
        updating = true;
        Updater.Release rel = release;
        updateStatus = "Downloading " + rel.version() + "…";
        Thread t = new Thread(() -> {
            try {
                Path dir = sv.updater().download(rel);
                sv.updater().installAfterExit(dir);
                SwingUtilities.invokeLater(() -> {
                    updateStatus = "Installing: WII-UU restarts in a moment";
                    sv.exitForUpgrade().run();
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    updating = false;
                    updateStatus = "Update failed: " + e.getMessage();
                    view.repaint();
                });
            }
        }, "settings-upgrade");
        t.setDaemon(true);
        t.start();
    }

    private void openMacPane(String anchor) {
        try {
            new ProcessBuilder("open", "x-apple.systempreferences:com.apple.preference.security?" + anchor).start();
        } catch (IOException e) {
            say("Open System Settings > Privacy & Security yourself");
        }
    }

    /** Opens a folder in the computer's file manager; without one (Console Mode) says where it is. */
    private void openFolder(Path dir) {
        try {
            if (!java.awt.Desktop.isDesktopSupported()) throw new UnsupportedOperationException();
            java.awt.Desktop.getDesktop().open(dir.toFile());
            say("Opened " + dir);
        } catch (Exception e) {
            say("It's in " + dir);
        }
    }

    private Path musicFolder() {
        return config.home().resolve("music");
    }

    /** A built-in file browser: folders to go into, and the files (when picking a program). */
    private Page browser(String title, Path start, boolean folders, Consumer<Path> chosen) {
        Path at = start;
        while (at != null && !Files.isDirectory(at)) at = at.getParent();
        if (at == null) at = Paths.get(System.getProperty("user.home"));
        Path dir = at.toAbsolutePath();
        return new Page(title, () -> {
            List<Row> r = new ArrayList<>();
            r.add(new Info(dir.toString()));
            if (folders) {
                r.add(new Action("Use this folder", null, () -> {
                    chosen.accept(dir);
                    say("Chose " + dir);
                    while (!stack.isEmpty() && page.title().equals(title)) pop();
                }, null, null));
            }
            if (dir.getParent() != null) {
                r.add(new Row(".. (the folder above)", null) {
                    @Override
                    void activate() {
                        Sfx.back();
                        replacePage(browser(title, dir.getParent(), folders, chosen));
                    }
                });
            }
            List<Path> entries = new ArrayList<>();
            try (var list = Files.list(dir)) {
                list.filter(p -> !p.getFileName().toString().startsWith("."))
                        .filter(p -> folders ? Files.isDirectory(p) : true)
                        .sorted((a, b) -> {
                            boolean da = Files.isDirectory(a), db = Files.isDirectory(b);
                            if (da != db) return da ? -1 : 1;
                            return a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString());
                        })
                        .limit(400)
                        .forEach(entries::add);
            } catch (IOException e) {
                r.add(new Info("Can't look in here: " + e.getMessage()));
            }
            for (Path p : entries) {
                boolean isDir = Files.isDirectory(p) && !p.getFileName().toString().endsWith(".app");
                String name = p.getFileName().toString() + (isDir ? "/" : "");
                r.add(new Row(name, null) {
                    @Override
                    String value() {
                        return isDir ? "›" : null;
                    }

                    @Override
                    void activate() {
                        Sfx.select();
                        if (isDir) replacePage(browser(title, p, folders, chosen));
                        else {
                            chosen.accept(p);
                            say("Chose " + p.getFileName());
                            while (!stack.isEmpty() && page.title().equals(title)) pop();
                        }
                    }
                });
            }
            if (entries.isEmpty()) r.add(new Info(folders ? "No folders in here." : "Nothing in here."));
            return r;
        });
    }

    /** Swaps the page on top (going into another folder) without stacking it. */
    private void replacePage(Page p) {
        page = p;
        reload(true);
    }

    /** Swaps the first word of a command (the program) for a chosen file, keeping the arguments. */
    static String replaceProgram(String command, File program) {
        String path = program.getAbsolutePath();
        if (path.endsWith(".app")) {
            String name = program.getName().replaceAll("\\.app$", "");
            return "open -W -a \"" + name + "\" --args {rom}";
        }
        String quoted = path.contains(" ") ? "\"" + path + "\"" : path;
        String trimmed = command.trim();
        String rest;
        if (trimmed.startsWith("\"")) {
            int end = trimmed.indexOf('"', 1);
            rest = end > 0 ? trimmed.substring(end + 1) : "";
        } else {
            int sp = trimmed.indexOf(' ');
            rest = sp > 0 ? trimmed.substring(sp) : " {rom}";
        }
        if (trimmed.startsWith("open -W -a")) rest = " {rom}";
        return quoted + rest;
    }

    private static String buttonLabel(PadButton b) {
        return switch (b) {
            case PLUS -> "+ (Start)";
            case MINUS -> "− (Select)";
            case HOME -> "HOME";
            case UP -> "D-pad up";
            case DOWN -> "D-pad down";
            case LEFT -> "D-pad left";
            case RIGHT -> "D-pad right";
            case LS_UP -> "Left stick up";
            case LS_DOWN -> "Left stick down";
            case LS_LEFT -> "Left stick left";
            case LS_RIGHT -> "Left stick right";
            case RS_UP -> "Right stick up";
            case RS_DOWN -> "Right stick down";
            case RS_LEFT -> "Right stick left";
            case RS_RIGHT -> "Right stick right";
            case L3 -> "L3 (press the left stick)";
            case R3 -> "R3 (press the right stick)";
            case BUZZ_RED -> "Buzz! red";
            case BUZZ_BLUE -> "Buzz! blue";
            case BUZZ_ORANGE -> "Buzz! orange";
            case BUZZ_GREEN -> "Buzz! green";
            case BUZZ_YELLOW -> "Buzz! yellow";
            default -> b.name();
        };
    }

    // ---- typing -----------------------------------------------------------------------------------

    private void edit(String title, String text, boolean secret, Consumer<String> done) {
        editor = new Editor(title, text == null ? "" : text, done);
    }

    /**
     * The on-screen keyboard: steered like the menu (A types the key, B deletes, L / R move the
     * cursor, + is done), or typed into with a real keyboard (Enter: done, Esc: cancel, Ctrl+V: paste).
     */
    private final class Editor {
        final String title;
        final StringBuilder text;
        final Consumer<String> done;
        int caret;
        int row = 1, col = 0;
        boolean shift, symbols;
        final long openedAt = System.currentTimeMillis();
        List<List<Rectangle2D>> keyRects = new ArrayList<>();

        static final String[] LETTERS = {"1234567890", "qwertyuiop", "asdfghjkl-", "zxcvbnm._/"};
        static final String[] SYMBOLS = {"!@#$%^&*()", "{}[]<>=+~`", "\"':;,?|\\_-", "./@#&%+=*$"};
        static final String[] SPECIAL = {"Shift", "#+=", "Space", "⌫", "◀", "▶", "Cancel", "Done"};
        static final float[] SPECIAL_W = {1.2f, 1.1f, 2.6f, 1, 0.8f, 0.8f, 1.2f, 1.3f};

        Editor(String title, String text, Consumer<String> done) {
            this.title = title;
            this.text = new StringBuilder(text);
            this.done = done;
            this.caret = text.length();
        }

        String[] grid() {
            return symbols ? SYMBOLS : LETTERS;
        }

        int rowLength(int r) {
            return r < 4 ? 10 : SPECIAL.length;
        }

        void navigate(int dx, int dy) {
            if (dy != 0) {
                int r = row + dy;
                if (r < 0 || r > 4) {
                    Sfx.bump();
                    return;
                }
                // the key nearest above / below
                double cx = keyRects.isEmpty() ? 0 : keyRects.get(row).get(Math.min(col, keyRects.get(row).size() - 1)).getCenterX();
                int best = 0;
                double bestD = Double.MAX_VALUE;
                if (!keyRects.isEmpty()) {
                    List<Rectangle2D> target = keyRects.get(r);
                    for (int i = 0; i < target.size(); i++) {
                        double d = Math.abs(target.get(i).getCenterX() - cx);
                        if (d < bestD) {
                            bestD = d;
                            best = i;
                        }
                    }
                }
                row = r;
                col = best;
                Sfx.move();
            } else {
                int c = col + dx;
                if (c < 0 || c >= rowLength(row)) Sfx.bump();
                else {
                    col = c;
                    Sfx.move();
                }
            }
        }

        void press() {
            if (row < 4) {
                char c = grid()[row].charAt(col);
                insert(String.valueOf(shift ? Character.toUpperCase(c) : c));
                if (shift) shift = false;
                Sfx.move();
                return;
            }
            switch (SPECIAL[col]) {
                case "Shift" -> shift = !shift;
                case "#+=" -> symbols = !symbols;
                case "Space" -> insert(" ");
                case "⌫" -> backspace();
                case "◀" -> moveCaret(-1);
                case "▶" -> moveCaret(1);
                case "Cancel" -> cancel();
                case "Done" -> done();
                default -> { }
            }
            Sfx.move();
        }

        void insert(String s) {
            text.insert(caret, s);
            caret += s.length();
        }

        void backspace() {
            if (caret > 0) {
                text.deleteCharAt(caret - 1);
                caret--;
                Sfx.move();
            } else Sfx.bump();
        }

        void moveCaret(int d) {
            caret = Math.max(0, Math.min(text.length(), caret + d));
        }

        void done() {
            editor = null;
            Sfx.select();
            done.accept(text.toString());
            reload(false);
        }

        void cancel() {
            editor = null;
            Sfx.back();
        }

        void key(KeyEvent e) {
            switch (e.getKeyCode()) {
                case KeyEvent.VK_ENTER -> done();
                case KeyEvent.VK_ESCAPE -> cancel();
                case KeyEvent.VK_BACK_SPACE -> backspace();
                case KeyEvent.VK_DELETE -> {
                    if (caret < text.length()) text.deleteCharAt(caret);
                }
                case KeyEvent.VK_LEFT -> moveCaret(-1);
                case KeyEvent.VK_RIGHT -> moveCaret(1);
                case KeyEvent.VK_HOME -> caret = 0;
                case KeyEvent.VK_END -> caret = text.length();
                case KeyEvent.VK_V -> {
                    if (e.isControlDown() || e.isMetaDown()) paste();
                }
                default -> { }
            }
        }

        void typed(char c) {
            if (c >= 32 && c != 127 && !Character.isISOControl(c)) insert(String.valueOf(c));
        }

        void paste() {
            try {
                Object v = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().getData(java.awt.datatransfer.DataFlavor.stringFlavor);
                if (v instanceof String s) insert(s.replace("\n", " ").replace("\r", "").trim());
            } catch (Exception ignored) {
                // nothing to paste
            }
        }

        Rectangle2D box;                                   // the editor's card (a click outside cancels)

        void click(double x, double y) {
            if (box != null && !box.contains(x, y)) {
                cancel();
                return;
            }
            for (int r = 0; r < keyRects.size(); r++) {
                for (int c = 0; c < keyRects.get(r).size(); c++) {
                    if (keyRects.get(r).get(c).contains(x, y)) {
                        row = r;
                        col = c;
                        press();
                        return;
                    }
                }
            }
        }
    }

    // ---- animation --------------------------------------------------------------------------------

    private static final long OPEN_MS = 260, CLOSE_MS = 220, SLIDE_MS = 260;
    private boolean needsFrame = true;

    /** Advances the animations; false once closed (the menu then drops it). */
    boolean tick() {
        long now = System.currentTimeMillis();
        if (closing != 0 && now - closing > CLOSE_MS) {
            sv.onClosed().run();
            return false;
        }
        boolean moving = false;
        if (Math.abs(scrollGoal - scroll) > 0.5f) {
            scroll += (scrollGoal - scroll) * 0.3f;
            moving = true;
        } else scroll = scrollGoal;
        if (frameGoal != null) {
            for (int i = 0; i < 4; i++) {
                float d = frameGoal[i] - frame[i];
                if (Math.abs(d) > 0.4f) {
                    frame[i] += d * 0.35f;
                    moving = true;
                } else frame[i] = frameGoal[i];
            }
        }
        for (Row r : rows) {
            if (!(r instanceof Toggle t)) continue;
            float goal = t.get.getAsBoolean() ? 1 : 0;
            float was = r.knob;
            r.knob = r.knob < 0 ? goal : Math.abs(goal - r.knob) < 0.01f ? goal : r.knob + (goal - r.knob) * 0.35f;
            moving |= r.knob != was;
        }
        boolean caretBlink = editor != null && (now / 500) != lastBlink;
        lastBlink = now / 500;
        needsFrame = moving || closing != 0 || now - opened < OPEN_MS + 40 || now - changed < SLIDE_MS + 40
                || caretBlink || note != null && now - noteAt > 3900 && now - noteAt < 4250 || stale;
        stale = false;
        return true;
    }

    private long lastBlink;
    private boolean stale = true;

    boolean needsFrame() {
        return needsFrame;
    }

    /** Something changed from outside (input): draw again. */
    void touch() {
        stale = true;
    }

    boolean covers() {
        return closing == 0 && System.currentTimeMillis() - opened > OPEN_MS;
    }

    // ---- painting ---------------------------------------------------------------------------------

    private Rectangle2D closeRect, backRect;
    private final Rectangle2D[] categoryRects = new Rectangle2D[5];
    private BufferedImage backdropImg;
    private String backdropKey;

    void paint(Graphics2D g0, int w, int h) {
        Graphics2D g = (Graphics2D) g0.create();
        long now = System.currentTimeMillis();
        double open = ease((now - opened) / (double) OPEN_MS);
        double fade = closing != 0 ? 1 - ease((now - closing) / (double) CLOSE_MS) : open;
        g.setComposite(AlphaComposite.SrcOver.derive((float) Math.max(0, Math.min(1, fade))));
        g.drawImage(backdrop(g, w, h), 0, 0, null);
        float u = h / 100f;                                // one hundredth of the height
        float pad = w * 0.04f;

        // title bar
        g.setColor(MenuView.TEXT);
        g.setFont(MenuView.font(Font.BOLD, u * 4.6f));
        float titleY = u * 9;
        String title = "Settings";
        g.drawString(title, pad, titleY);
        float tx = pad + g.getFontMetrics().stringWidth(title);
        if (!stack.isEmpty() || page != null) {
            g.setColor(MenuView.TEXT_DIM);
            g.setFont(MenuView.font(Font.PLAIN, u * 3));
            String crumb = "  ›  " + categories.get(category);
            for (Page p : stack.subList(Math.min(1, stack.size()), stack.size())) crumb += "  ›  " + p.title();
            if (!stack.isEmpty()) crumb += "  ›  " + page.title();
            fit(g, crumb, Font.PLAIN, u * 3, w - tx - pad - u * 10);
            g.drawString(crumb, tx, titleY);
        }
        // close button
        float cs = u * 6;
        closeRect = new Rectangle2D.Float(w - pad - cs, titleY - cs * 0.78f, cs, cs);
        g.setColor(MenuView.dark ? new Color(255, 255, 255, 24) : new Color(0, 0, 0, 14));
        g.fill(new Ellipse2D.Double(closeRect.getX(), closeRect.getY(), cs, cs));
        g.setColor(MenuView.TEXT);
        g.setStroke(new BasicStroke(Math.max(2, u * 0.35f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        float m = cs * 0.33f;
        g.draw(new java.awt.geom.Line2D.Double(closeRect.getX() + m, closeRect.getY() + m, closeRect.getMaxX() - m, closeRect.getMaxY() - m));
        g.draw(new java.awt.geom.Line2D.Double(closeRect.getMaxX() - m, closeRect.getY() + m, closeRect.getX() + m, closeRect.getMaxY() - m));

        // sidebar
        float sideW = Math.max(w * 0.22f, u * 30), top = u * 14, bottom = h - u * 11;
        float itemH = u * 7.2f;
        for (int i = 0; i < categories.size(); i++) {
            float y = top + i * (itemH + u * 1.2f);
            Rectangle2D r = new Rectangle2D.Float(pad, y, sideW - u * 2, itemH);
            categoryRects[i] = r;
            boolean on = i == category;
            if (on) {
                g.setColor(inSidebar ? MenuView.ACCENT : mix(MenuView.CARD, MenuView.ACCENT, 0.18f));
                g.fill(new RoundRectangle2D.Double(r.getX(), r.getY(), r.getWidth(), r.getHeight(), itemH, itemH));
            }
            g.setColor(on && inSidebar ? Color.WHITE : on ? MenuView.TEXT : MenuView.TEXT_DIM);
            g.setFont(MenuView.font(on ? Font.BOLD : Font.PLAIN, u * 3.1f));
            fit(g, categories.get(i), on ? Font.BOLD : Font.PLAIN, u * 3.1f, (float) r.getWidth() - itemH * 0.8f);
            g.drawString(categories.get(i), (float) r.getX() + itemH * 0.45f, (float) r.getCenterY() + u * 1.1f);
        }

        // the page: a card with the rows, sliding in when it changes
        float cx = pad + sideW + u * 2, cw = w - cx - pad, cy = top, ch = bottom - top;
        double slide = 1 - ease((now - changed) / (double) SLIDE_MS);
        float shift = (float) (slide * slideDir * u * 6);
        g.setColor(MenuView.CARD);
        RoundRectangle2D card = new RoundRectangle2D.Float(cx, cy, cw, ch, u * 3, u * 3);
        g.fill(card);
        Shape clip = g.getClip();
        g.clip(card);
        Composite base = g.getComposite();
        g.setComposite(AlphaComposite.SrcOver.derive((float) (((AlphaComposite) base).getAlpha() * (1 - slide * 0.6))));
        paintRows(g, cx, cy + shift, cw, ch, u, now);
        g.setComposite(base);
        g.setClip(clip);
        backRect = null;
        if (!stack.isEmpty()) backRect = new Rectangle2D.Float(cx, cy - u * 4.5f, cw * 0.3f, u * 4);

        // what the chosen setting does, or a passing message, and the buttons
        String foot = note != null && now - noteAt < 4000 ? note
                : editor == null && !inSidebar && !rows.isEmpty() && rows.get(focus).help != null ? rows.get(focus).help : null;
        float fy = h - u * 4.2f;
        g.setColor(note != null && now - noteAt < 4000 ? MenuView.ACCENT : MenuView.TEXT_DIM);
        if (foot != null) {
            // up to two lines, at a size that reads on a TV
            g.setFont(MenuView.font(Font.PLAIN, u * 2.5f));
            List<String> lines = wrap(g, foot, w - pad * 2 - u * 52);
            if (lines.size() > 2) {
                lines = new ArrayList<>(lines.subList(0, 2));
                lines.set(1, ellipsizeEnd(g, lines.get(1) + " …", w - pad * 2 - u * 52));
            }
            float ly = fy - (lines.size() - 1) * u * 3.1f;
            for (String line : lines) {
                g.drawString(line, pad, ly);
                ly += u * 3.1f;
            }
        }
        g.setColor(MenuView.TEXT_DIM);
        String hints = editor != null ? "A type   B delete   L/R move   + done"
                : capturing != null ? "Press the key   Esc cancel"
                : "A choose   B back   ◀ ▶ change   L/R category";
        fit(g, hints, Font.PLAIN, u * 2.3f, u * 48);
        g.drawString(hints, w - pad - g.getFontMetrics().stringWidth(hints), fy);

        if (editor != null) paintEditor(g, w, h, u, now);
        g.dispose();
    }

    private void paintRows(Graphics2D g, float x, float y, float w, float h, float u, long now) {
        float rowH = u * 7.4f, inset = u * 3;
        viewHeight = h;
        // lay out (info rows are as tall as their text)
        g.setFont(MenuView.font(Font.PLAIN, u * 2.6f));
        float[] tops = new float[rows.size()], heights = new float[rows.size()];
        List<List<String>> wrapped = new ArrayList<>();
        float yy = u * 2;
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            List<String> lines = null;
            float rh;
            if (r.height() < 0) {
                lines = wrap(g, r.label, w - inset * 2);
                rh = lines.size() * u * 3.6f + u * 1.4f;
            } else rh = rowH * r.height();
            wrapped.add(lines);
            tops[i] = yy;
            heights[i] = rh;
            yy += rh + (r instanceof Header ? 0 : u * 0.6f);
        }
        contentHeight = yy + u * 2;
        // keep the chosen row in view
        if (!rows.isEmpty() && !inSidebar && System.currentTimeMillis() - wheelAt > 1500) {
            float ft = tops[focus], fb = ft + heights[focus];
            if (ft - scrollGoal < u * 4) scrollGoal = clampScroll(ft - u * 4);
            if (fb - scrollGoal > h - u * 4) scrollGoal = clampScroll(fb - h + u * 4);
        }
        scrollGoal = clampScroll(scrollGoal);
        leftArrowX.clear();
        Rectangle2D focused = null;
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            float ry = y + tops[i] - scroll, rh = heights[i];
            if (ry + rh < y - u || ry > y + h + u) {
                r.rect = null;
                continue;
            }
            r.rect = new Rectangle2D.Float(x + inset * 0.6f, ry, w - inset * 1.2f, rh);
            if (r instanceof Header) {
                g.setColor(MenuView.ACCENT);
                g.setFont(MenuView.font(Font.BOLD, u * 2.5f));
                g.drawString(r.label.toUpperCase(Locale.ROOT), x + inset, ry + rh * 0.75f);
                continue;
            }
            if (r instanceof Info) {
                g.setColor(MenuView.TEXT_DIM);
                g.setFont(MenuView.font(Font.PLAIN, u * 2.6f));
                float ly = ry + u * 3.2f;
                for (String line : wrapped.get(i)) {
                    g.drawString(line, x + inset, ly);
                    ly += u * 3.6f;
                }
                continue;
            }
            boolean on = i == focus && !inSidebar;
            if (on) focused = r.rect;
            g.setColor(MenuView.dark ? new Color(255, 255, 255, on ? 18 : 8) : new Color(0, 0, 0, on ? 12 : 5));
            g.fill(new RoundRectangle2D.Double(r.rect.getX(), r.rect.getY(), r.rect.getWidth(), r.rect.getHeight(), u * 2, u * 2));
            float mid = ry + rh / 2 + u * 1.05f;
            String value = r.value();
            // the right side: a switch, ‹ choice ›, a value, or ›
            float right = (float) r.rect.getMaxX() - inset * 0.8f;
            float valueLeft = right;
            if (r instanceof Toggle t) {
                float sh = rh * 0.42f, sw = sh * 1.9f, sx = right - sw, sy = ry + (rh - sh) / 2;
                float k = r.knob < 0 ? (t.get.getAsBoolean() ? 1 : 0) : r.knob;
                Color off = MenuView.dark ? new Color(90, 96, 104) : new Color(196, 202, 208);
                g.setColor(mix(off, MenuView.ACCENT, k));
                g.fill(new RoundRectangle2D.Float(sx, sy, sw, sh, sh, sh));
                g.setColor(Color.WHITE);
                float kd = sh * 0.8f;
                g.fill(new Ellipse2D.Float(sx + sh * 0.1f + k * (sw - kd - sh * 0.2f), sy + sh * 0.1f, kd, kd));
                valueLeft = sx;
            } else if (value != null) {
                boolean arrows = r instanceof Choice || r instanceof NumberRow || r instanceof KeyRow;
                g.setColor(arrows || r instanceof Text ? MenuView.TEXT : MenuView.TEXT_DIM);
                Font vf = MenuView.font(arrows ? Font.BOLD : Font.PLAIN, u * 2.7f);
                g.setFont(vf);
                float maxV = (float) r.rect.getWidth() * 0.55f;
                String shown = ellipsize(g, value, maxV);
                float vw = g.getFontMetrics().stringWidth(shown);
                if (arrows) {
                    g.setColor(on ? MenuView.ACCENT : MenuView.TEXT_DIM);
                    g.setFont(MenuView.font(Font.BOLD, u * 3));
                    g.drawString("›", right - u * 1.6f, mid);
                    float lx = right - u * 3.2f - vw - u * 2.6f;
                    g.drawString("‹", lx, mid);
                    leftArrowX.put(r, lx + u * 0.8f);
                    g.setColor(MenuView.TEXT);
                    g.setFont(vf);
                    g.drawString(shown, right - u * 3.2f - vw, mid);
                    valueLeft = lx;
                } else {
                    g.drawString(shown, right - vw - (r instanceof Link ? u * 3 : 0), mid);
                    valueLeft = right - vw;
                }
            }
            if (r instanceof Link) {
                g.setColor(on ? MenuView.ACCENT : MenuView.TEXT_DIM);
                g.setFont(MenuView.font(Font.BOLD, u * 3.2f));
                g.drawString("›", right - u * 1.4f, mid);
            }
            g.setColor(MenuView.TEXT);
            fit(g, r.label, Font.PLAIN, u * 2.9f, valueLeft - (float) r.rect.getX() - inset * 1.4f);
            g.drawString(r.label, (float) r.rect.getX() + inset * 0.6f, mid);
        }
        // the focus frame glides to the chosen row
        if (focused != null) {
            // kept in the page's own coordinates, so it scrolls along with the rows
            float[] goal = {(float) focused.getX(), (float) focused.getY() + scroll, (float) focused.getWidth(), (float) focused.getHeight()};
            if (!frameSet || now - changed < 30) {
                System.arraycopy(goal, 0, frame, 0, 4);
                frameSet = true;
            }
            frameGoal = goal;
            g.setColor(MenuView.ACCENT);
            g.setStroke(new BasicStroke(Math.max(2.5f, u * 0.45f)));
            g.draw(new RoundRectangle2D.Float(frame[0], frame[1] - scroll, frame[2], frame[3], u * 2, u * 2));
            g.setStroke(new BasicStroke(1));
        } else frameGoal = null;
        // the scroll bar
        if (contentHeight > h) {
            float barH = Math.max(u * 5, h * h / contentHeight), barY = y + (h - barH) * (scroll / (contentHeight - h));
            g.setColor(MenuView.dark ? new Color(255, 255, 255, 40) : new Color(0, 0, 0, 30));
            g.fill(new RoundRectangle2D.Float(x + w - u * 1.4f, barY, u * 0.7f, barH, u * 0.7f, u * 0.7f));
        }
    }

    private float clampScroll(float s) {
        return Math.max(0, Math.min(s, Math.max(0, contentHeight - viewHeight)));
    }

    private void paintEditor(Graphics2D g, int w, int h, float u, long now) {
        Editor e = editor;
        g.setColor(MenuView.dark ? new Color(0, 0, 0, 150) : new Color(0, 0, 0, 90));
        g.fillRect(0, 0, w, h);
        float bw = Math.min(w * 0.82f, u * 160), bx = (w - bw) / 2, by = h * 0.14f, bh = h * 0.74f;
        g.setColor(MenuView.CARD);
        g.fill(new RoundRectangle2D.Float(bx, by, bw, bh, u * 3, u * 3));
        e.box = new Rectangle2D.Float(bx, by, bw, bh);
        g.setColor(MenuView.TEXT);
        g.setFont(MenuView.font(Font.BOLD, u * 3.4f));
        g.drawString(e.title, bx + u * 4, by + u * 7);
        // the text field
        float fx = bx + u * 4, fw = bw - u * 8, fy = by + u * 10, fh = u * 8;
        g.setColor(MenuView.dark ? new Color(255, 255, 255, 16) : new Color(0, 0, 0, 8));
        g.fill(new RoundRectangle2D.Float(fx, fy, fw, fh, u * 2, u * 2));
        g.setColor(MenuView.ACCENT);
        g.setStroke(new BasicStroke(Math.max(2, u * 0.35f)));
        g.draw(new RoundRectangle2D.Float(fx, fy, fw, fh, u * 2, u * 2));
        g.setFont(MenuView.font(Font.PLAIN, u * 3.2f));
        FontMetrics fm = g.getFontMetrics();
        String s = e.text.toString();
        // scroll the text so the cursor is visible
        float inner = fw - u * 4;
        int from = 0;
        while (fm.stringWidth(s.substring(from, e.caret)) > inner && from < e.caret) from++;
        String visible = s.substring(from);
        while (fm.stringWidth(visible) > inner && visible.length() > e.caret - from) visible = visible.substring(0, visible.length() - 1);
        g.setColor(MenuView.TEXT);
        float textY = fy + fh / 2 + u * 1.1f;
        g.drawString(visible, fx + u * 2, textY);
        if ((now / 500) % 2 == 0) {
            float cxp = fx + u * 2 + fm.stringWidth(s.substring(from, e.caret));
            g.fillRect((int) cxp, (int) (fy + fh * 0.22f), Math.max(2, (int) (u * 0.3f)), (int) (fh * 0.56f));
        }
        // the keys
        float ky = fy + fh + u * 4, kh = u * 7.4f, gap = u * 1.1f;
        e.keyRects = new ArrayList<>();
        for (int r = 0; r < 5; r++) {
            List<Rectangle2D> rr = new ArrayList<>();
            if (r < 4) {
                String keys = e.grid()[r];
                float kw = (fw - gap * 9) / 10;
                for (int c = 0; c < 10; c++) rr.add(new Rectangle2D.Float(fx + c * (kw + gap), ky + r * (kh + gap), kw, kh));
                e.keyRects.add(rr);
                for (int c = 0; c < 10; c++) {
                    char ch = keys.charAt(c);
                    String label = String.valueOf(e.shift ? Character.toUpperCase(ch) : ch);
                    key(g, rr.get(c), label, e.row == r && e.col == c, u, false);
                }
            } else {
                float units = 0;
                for (float f : Editor.SPECIAL_W) units += f;
                float unit = (fw - gap * (Editor.SPECIAL.length - 1)) / units, xx = fx;
                for (int c = 0; c < Editor.SPECIAL.length; c++) {
                    float kw = unit * Editor.SPECIAL_W[c];
                    rr.add(new Rectangle2D.Float(xx, ky + r * (kh + gap), kw, kh));
                    xx += kw + gap;
                }
                e.keyRects.add(rr);
                for (int c = 0; c < Editor.SPECIAL.length; c++) {
                    String label = Editor.SPECIAL[c];
                    boolean lit = label.equals("Shift") && e.shift || label.equals("#+=") && e.symbols || label.equals("Done");
                    key(g, rr.get(c), label.equals("#+=") && e.symbols ? "abc" : label, e.row == 4 && e.col == c, u, lit);
                }
            }
        }
    }

    private void key(Graphics2D g, Rectangle2D r, String label, boolean focused, float u, boolean lit) {
        RoundRectangle2D shape = new RoundRectangle2D.Double(r.getX(), r.getY(), r.getWidth(), r.getHeight(), u * 1.6, u * 1.6);
        g.setColor(lit ? MenuView.ACCENT : MenuView.dark ? new Color(255, 255, 255, 22) : new Color(0, 0, 0, 12));
        g.fill(shape);
        if (focused) {
            g.setColor(MenuView.ACCENT);
            g.setStroke(new BasicStroke(Math.max(2.5f, u * 0.5f)));
            g.draw(shape);
            g.setStroke(new BasicStroke(1));
        }
        g.setColor(lit ? Color.WHITE : MenuView.TEXT);
        g.setFont(MenuView.font(Font.BOLD, u * (label.length() > 2 ? 2.4f : 3.2f)));
        FontMetrics fm = g.getFontMetrics();
        g.drawString(label, (float) (r.getCenterX() - fm.stringWidth(label) / 2.0), (float) r.getCenterY() + fm.getAscent() * 0.36f);
    }

    private BufferedImage backdrop(Graphics2D g, int w, int h) {
        String k = w + "x" + h + MenuView.themeVersion + MenuView.dark;
        if (k.equals(backdropKey)) return backdropImg;
        java.awt.GraphicsConfiguration gc = g.getDeviceConfiguration();
        BufferedImage img = gc != null ? gc.createCompatibleImage(Math.max(1, w), Math.max(1, h))
                : new BufferedImage(Math.max(1, w), Math.max(1, h), BufferedImage.TYPE_INT_RGB);
        Graphics2D b = img.createGraphics();
        b.setPaint(new GradientPaint(0, 0, MenuView.bgTop(), 0, h, MenuView.bgBottom()));
        b.fillRect(0, 0, w, h);
        b.dispose();
        backdropImg = img;
        backdropKey = k;
        return img;
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private static double ease(double t) {
        t = Math.max(0, Math.min(1, t));
        return 1 - Math.pow(1 - t, 3);
    }

    private static Color mix(Color a, Color b, float t) {
        t = Math.max(0, Math.min(1, t));
        return new Color((int) (a.getRed() + (b.getRed() - a.getRed()) * t), (int) (a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                (int) (a.getBlue() + (b.getBlue() - a.getBlue()) * t));
    }

    private static void fit(Graphics2D g, String s, int style, float size, float maxW) {
        Font f = MenuView.font(style, size);
        while (size > 9 && g.getFontMetrics(f).stringWidth(s) > maxW) f = MenuView.font(style, size *= 0.94f);
        g.setFont(f);
    }

    private static String ellipsize(Graphics2D g, String s, float maxW) {
        FontMetrics fm = g.getFontMetrics();
        if (fm.stringWidth(s) <= maxW) return s;
        // paths and commands: the end matters most
        String t = s;
        while (t.length() > 1 && fm.stringWidth("…" + t) > maxW) t = t.substring(1);
        return "…" + t;
    }

    private static String ellipsizeEnd(Graphics2D g, String s, float maxW) {
        FontMetrics fm = g.getFontMetrics();
        while (s.length() > 2 && fm.stringWidth(s) > maxW) s = s.substring(0, s.length() - 3) + "…";
        return s;
    }

    private static List<String> wrap(Graphics2D g, String text, float width) {
        List<String> lines = new ArrayList<>();
        FontMetrics fm = g.getFontMetrics();
        StringBuilder line = new StringBuilder();
        String lead = text.startsWith("    ") ? text.substring(0, text.length() - text.stripLeading().length()) : "";
        List<String> words = new ArrayList<>();
        for (String word : text.stripLeading().split(" ")) {
            // a word wider than the line (a long path): in pieces that fit
            while (fm.stringWidth(word) > width && word.length() > 1) {
                int n = word.length() - 1;
                while (n > 1 && fm.stringWidth(word.substring(0, n)) > width) n--;
                words.add(word.substring(0, n));
                word = word.substring(n);
            }
            words.add(word);
        }
        for (String word : words) {
            String test = line.length() == 0 ? lead + word : line + " " + word;
            if (fm.stringWidth(test) > width && line.length() > 0) {
                lines.add(line.toString());
                line = new StringBuilder(lead + word);
            } else {
                line = new StringBuilder(test);
            }
        }
        if (line.length() > 0 || lines.isEmpty()) lines.add(line.toString());
        return lines;
    }
}
