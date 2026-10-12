package wiiuu.choccy;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BOOLEAN;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * Choccy Milk: WII-UU's own libretro frontend. It runs one game with a libretro core (the same
 * {@code *_libretro.so} files RetroArch uses) in a helper process: WII-UU starts it, sends the
 * players' buttons and sticks on stdin, and draws the frames it sends on stdout in its own
 * window. Sound plays here. If a core crashes, only this process goes.
 *
 * <p>Software-rendered cores only (no OpenGL / Vulkan contexts). Built with the Foreign Function
 * &amp; Memory API, so it needs Java 22 or newer (WII-UU itself doesn't).
 *
 * <pre>
 * java --enable-native-access=ALL-UNNAMED -cp wiiuu.jar wiiuu.choccy.ChoccyMilk CORE ROM SAVES SYSTEM [key=value ...]
 * stdin:  b PORT ID 0|1     a RetroPad button (ids as libretro's RETRO_DEVICE_ID_JOYPAD_*)
 *         s PORT STICK X Y  an analog stick, X / Y in -1..1
 *         q                 quit (saves first)
 * stdout: 'R' name\n        running, with this core
 *         'F' w h aspect(float) then w*h pixels as 0xRRGGBB ints (big-endian)
 *         'X' message\n     stopped: why (empty: all well)
 * </pre>
 */
public final class ChoccyMilk {

    // ---- libretro ----------------------------------------------------------------------------------
    private static final int PIXEL_0RGB1555 = 0, PIXEL_XRGB8888 = 1, PIXEL_RGB565 = 2;
    private static final int DEVICE_JOYPAD = 1, DEVICE_ANALOG = 5, JOYPAD_MASK = 256, MEMORY_SAVE_RAM = 0;
    private static final int EXPERIMENTAL = 0x10000;

    private final Path corePath, romPath, saveDir, systemDir;
    private final Map<String, String> overrides = new HashMap<>();
    private final Map<String, String> defaults = new HashMap<>();
    private final Map<String, MemorySegment> valueStrings = new HashMap<>();
    private final Arena arena = Arena.ofShared();
    private final DataOutputStream out;

    private MethodHandle run, getMemoryData, getMemorySize;
    private int pixelFormat = PIXEL_0RGB1555;
    private double fps = 60, sampleRate = 44100;
    private float aspect = 4f / 3;
    private int baseW = 256, baseH = 240;
    private boolean wantsHardware;
    private MemorySegment systemDirC, saveDirC;

    // input: written by the stdin thread, read by the core's thread
    private final int[] buttons = new int[8];
    /** presses the core hasn't seen yet: held until its next look, so a quick tap between two frames still counts */
    private final int[] latched = new int[8];
    private final int[] seen = new int[8];
    private final short[][] sticks = new short[8][4];

    private SourceDataLine audio;
    private byte[] audioBytes = new byte[16384];
    // frames go to WII-UU on their own thread, so a busy WII-UU never holds up the game: the core's
    // thread fills a free buffer (none free: the frame is dropped), the writer sends the newest
    private final Object frameLock = new Object();
    private int[] pixels = new int[0], sending = new int[0];
    private int pendingW, pendingH;
    private boolean pending;
    private ByteBuffer frameBuffer = ByteBuffer.allocate(0);
    // speed: falling behind (sound running dry) skips drawing frames, so the sound keeps up
    private static final boolean BENCH = System.getenv("CHOCCY_BENCH") != null;
    private boolean hideFrame;
    private int skippedInARow;
    private long framesRun, framesSkipped, framesDropped;
    private int audioBufferBytes;
    private volatile boolean running = true;

    private ChoccyMilk(Path core, Path rom, Path saves, Path system, OutputStream stdout) {
        this.corePath = core;
        this.romPath = rom;
        this.saveDir = saves;
        this.systemDir = system;
        this.out = new DataOutputStream(new java.io.BufferedOutputStream(stdout, 1 << 16));
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("usage: ChoccyMilk CORE ROM SAVES SYSTEM [key=value ...]");
            System.exit(2);
        }
        OutputStream stdout = framesOut();
        System.setOut(System.err);                          // stdout is for frames only; anything printed goes to the log
        ChoccyMilk m = new ChoccyMilk(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]), Path.of(args[3]), stdout);
        for (int i = 4; i < args.length; i++) {
            int eq = args[i].indexOf('=');
            if (eq > 0) m.overrides.put(args[i].substring(0, eq), args[i].substring(eq + 1));
        }
        // the core runs on a thread with a big stack: some (N64) go deep in native code, and running out
        // of stack there kills the whole process instead of just ending the game
        String[] why = {""};
        Runnable game = () -> {
            try {
                m.play();
            } catch (Throwable t) {
                why[0] = t.getMessage() == null ? t.toString() : t.getMessage();
                System.err.println("[choccy] " + why[0]);
            }
        };
        if (!LowStack.run(game)) {
            Thread core = new Thread(null, game, "choccy-core", 256L << 20);
            core.start();
            core.join();
        }
        m.finish(why[0]);
        System.exit(why[0].isEmpty() ? 0 : 1);
    }

    /**
     * Where the frames go: stdout, but on Linux and macOS a copy of it, with the real stdout (file 1)
     * then pointed at stderr: cores printf to stdout, and that mustn't land in the middle of a frame.
     */
    private static OutputStream framesOut() {
        OutputStream plain = new java.io.FileOutputStream(java.io.FileDescriptor.out);
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) return plain;
        try {
            Linker linker = Linker.nativeLinker();
            SymbolLookup libc = linker.defaultLookup();
            MethodHandle dup = linker.downcallHandle(libc.find("dup").orElseThrow(), FunctionDescriptor.of(JAVA_INT, JAVA_INT));
            MethodHandle dup2 = linker.downcallHandle(libc.find("dup2").orElseThrow(), FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT));
            int copy = (int) dup.invokeExact(1);
            if (copy < 0) return plain;
            OutputStream frames = new java.io.FileOutputStream("/dev/fd/" + copy);
            if ((int) dup2.invokeExact(2, 1) < 0) return plain;
            return frames;
        } catch (Throwable t) {
            System.err.println("[choccy] stdout stays shared: " + t);
            return plain;
        }
    }

    // ---- running a game ----------------------------------------------------------------------------

    private void play() throws Throwable {
        Linker linker = Linker.nativeLinker();
        SymbolLookup lib = SymbolLookup.libraryLookup(corePath, arena);
        MethodHandle apiVersion = fn(linker, lib, "retro_api_version", FunctionDescriptor.of(JAVA_INT));
        int api = (int) apiVersion.invokeExact();
        if (api != 1) throw new IllegalStateException("this core speaks libretro API " + api + ", not 1");
        systemDirC = arena.allocateFrom(systemDir.toString());
        saveDirC = arena.allocateFrom(saveDir.toString());
        Files.createDirectories(saveDir);

        // callbacks, then init
        MethodHandles.Lookup me = MethodHandles.lookup();
        interfaces(linker, me);
        set(linker, lib, "retro_set_environment", me.bind(this, "environment",
                MethodType.methodType(boolean.class, int.class, MemorySegment.class)), FunctionDescriptor.of(JAVA_BOOLEAN, JAVA_INT, ADDRESS));
        set(linker, lib, "retro_set_video_refresh", me.bind(this, "video",
                MethodType.methodType(void.class, MemorySegment.class, int.class, int.class, long.class)),
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT, JAVA_LONG));
        set(linker, lib, "retro_set_audio_sample", me.bind(this, "sample",
                MethodType.methodType(void.class, short.class, short.class)), FunctionDescriptor.ofVoid(JAVA_SHORT, JAVA_SHORT));
        set(linker, lib, "retro_set_audio_sample_batch", me.bind(this, "samples",
                MethodType.methodType(long.class, MemorySegment.class, long.class)), FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG));
        set(linker, lib, "retro_set_input_poll", me.bind(this, "poll", MethodType.methodType(void.class)), FunctionDescriptor.ofVoid());
        set(linker, lib, "retro_set_input_state", me.bind(this, "inputState",
                MethodType.methodType(short.class, int.class, int.class, int.class, int.class)),
                FunctionDescriptor.of(JAVA_SHORT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
        fn(linker, lib, "retro_init", FunctionDescriptor.ofVoid()).invokeExact();

        // what the core wants: the file's path, or its bytes
        MemorySegment info = arena.allocate(40, 8);
        fn(linker, lib, "retro_get_system_info", FunctionDescriptor.ofVoid(ADDRESS)).invokeExact(info);
        String name = cString(info.get(ADDRESS, 0)), version = cString(info.get(ADDRESS, 8));
        String extensions = cString(info.get(ADDRESS, 16));
        boolean needPath = info.get(JAVA_BOOLEAN, 24);
        System.err.println("[choccy] " + name + " " + version + " (" + extensions + ")" + (needPath ? ", needs the file's path" : ""));

        Path rom = unzipIfNeeded(romPath, extensions);
        MemorySegment game = arena.allocate(32, 8);
        game.set(ADDRESS, 0, arena.allocateFrom(rom.toString()));
        if (!needPath) {
            byte[] data = Files.readAllBytes(rom);
            MemorySegment bytes = arena.allocate(Math.max(1, data.length), 16);
            MemorySegment.copy(data, 0, bytes, JAVA_BYTE, 0, data.length);
            game.set(ADDRESS, 8, bytes);
            game.set(JAVA_LONG, 16, data.length);
        }
        boolean loaded = (boolean) fn(linker, lib, "retro_load_game", FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS)).invokeExact(game);
        if (!loaded) {
            throw new IllegalStateException(wantsHardware
                    ? name + " needs OpenGL / Vulkan, which Choccy Milk doesn't do: this console plays in RetroArch instead"
                    : name + " couldn't load this game");
        }
        MemorySegment av = arena.allocate(40, 8);
        fn(linker, lib, "retro_get_system_av_info", FunctionDescriptor.ofVoid(ADDRESS)).invokeExact(av);
        readAv(av);
        getMemoryData = fn(linker, lib, "retro_get_memory_data", FunctionDescriptor.of(ADDRESS, JAVA_INT));
        getMemorySize = fn(linker, lib, "retro_get_memory_size", FunctionDescriptor.of(JAVA_LONG, JAVA_INT));
        loadSave();
        openAudio();
        send('R', name);

        Thread input = new Thread(this::readInput, "choccy-input");
        input.setDaemon(true);
        input.start();
        run = fn(linker, lib, "retro_run", FunctionDescriptor.ofVoid());
        Thread writer = new Thread(this::writeFrames, "choccy-frames");
        writer.setDaemon(true);
        writer.start();
        long frameNanos = (long) (1e9 / fps), next = System.nanoTime(), lastSave = System.currentTimeMillis();
        long statAt = System.nanoTime(), statFrames = 0, statSkipped = 0;
        while (running) {
            boolean behind;
            if (BENCH) {
                behind = false;
            } else if (audio != null) {
                // the sound card's queue: under a third left means the game runs slower than real time
                behind = audioBufferBytes - audio.available() < audioBufferBytes / 3;
            } else {
                behind = System.nanoTime() - next > 2 * frameNanos;
            }
            hideFrame = behind && skippedInARow < 3;        // at least every 4th frame is drawn
            skippedInARow = hideFrame ? skippedInARow + 1 : 0;
            if (hideFrame) framesSkipped++;
            run.invokeExact();
            flushSingle();
            checkAchievements();
            framesRun++;
            if (audio == null && !BENCH) {                  // no sound: keep time by the clock (with sound, the sound card does)
                next += frameNanos;
                long wait = next - System.nanoTime();
                if (wait > 0) Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
                else if (wait < -frameNanos * 5) next = System.nanoTime();
            }
            long now = System.nanoTime();
            if (now - statAt > (BENCH ? 5_000_000_000L : 10_000_000_000L)) {
                double secs = (now - statAt) / 1e9, rate = (framesRun - statFrames) / secs;
                System.err.printf(Locale.ROOT, "[choccy] speed: %.1f of %.1f frames a second (%.0f %%), %d not drawn, %d not shown%n",
                        rate, fps, 100 * rate / fps, framesSkipped - statSkipped, framesDropped);
                statAt = now;
                statFrames = framesRun;
                statSkipped = framesSkipped;
                framesDropped = 0;
            }
            if (System.currentTimeMillis() - lastSave > 30_000) {
                writeSave();
                lastSave = System.currentTimeMillis();
            }
        }
        writeSave();
        fn(linker, lib, "retro_unload_game", FunctionDescriptor.ofVoid()).invokeExact();
        fn(linker, lib, "retro_deinit", FunctionDescriptor.ofVoid()).invokeExact();
    }

    // ---- interfaces cores ask for (some call them without checking that they got one) -------------

    private MemorySegment logInterface, perfInterface, rumbleInterface;

    private void interfaces(Linker linker, MethodHandles.Lookup me) throws ReflectiveOperationException {
        // retro_log_callback: void (*log)(level, const char *fmt, ...); the extra arguments are left alone
        logInterface = arena.allocate(ADDRESS);
        logInterface.set(ADDRESS, 0, linker.upcallStub(me.bind(this, "log", MethodType.methodType(void.class, int.class, MemorySegment.class)),
                FunctionDescriptor.ofVoid(JAVA_INT, ADDRESS), arena));
        // retro_perf_callback: get_time_usec, get_cpu_features, get_perf_counter, perf_register, perf_start, perf_stop, perf_log
        perfInterface = arena.allocate(ADDRESS.byteSize() * 7);
        MemorySegment usec = linker.upcallStub(me.findStatic(ChoccyMilk.class, "usec", MethodType.methodType(long.class)), FunctionDescriptor.of(JAVA_LONG), arena);
        MemorySegment none = linker.upcallStub(me.findStatic(ChoccyMilk.class, "noFeatures", MethodType.methodType(long.class)), FunctionDescriptor.of(JAVA_LONG), arena);
        MemorySegment ticks = linker.upcallStub(me.findStatic(System.class, "nanoTime", MethodType.methodType(long.class)), FunctionDescriptor.of(JAVA_LONG), arena);
        MemorySegment counter = linker.upcallStub(me.findStatic(ChoccyMilk.class, "ignore", MethodType.methodType(void.class, MemorySegment.class)), FunctionDescriptor.ofVoid(ADDRESS), arena);
        MemorySegment nothing = linker.upcallStub(me.findStatic(ChoccyMilk.class, "nothing", MethodType.methodType(void.class)), FunctionDescriptor.ofVoid(), arena);
        MemorySegment[] perf = {usec, none, ticks, counter, counter, counter, nothing};
        for (int i = 0; i < perf.length; i++) perfInterface.set(ADDRESS, i * ADDRESS.byteSize(), perf[i]);
        // retro_rumble_interface: bool (*set_rumble_state)(port, effect, strength) (no rumble here)
        rumbleInterface = arena.allocate(ADDRESS);
        rumbleInterface.set(ADDRESS, 0, linker.upcallStub(me.findStatic(ChoccyMilk.class, "rumble", MethodType.methodType(boolean.class, int.class, int.class, short.class)),
                FunctionDescriptor.of(JAVA_BOOLEAN, JAVA_INT, JAVA_INT, JAVA_SHORT), arena));
    }

    private void log(int level, MemorySegment fmt) {
        try {
            if (level < 1 && !DEBUG) return;                 // debug messages: only when debugging
            String text = cString(fmt).strip();
            if (!text.isEmpty()) System.err.println("[core] " + text);
        } catch (Throwable ignored) {
            // never into native code
        }
    }

    private static long usec() {
        return System.nanoTime() / 1000;
    }

    private static long noFeatures() {
        return 0;
    }

    private static void ignore(MemorySegment counter) {}

    private static void nothing() {}

    private static boolean rumble(int port, int effect, short strength) {
        return false;
    }

    private static MethodHandle fn(Linker linker, SymbolLookup lib, String name, FunctionDescriptor d) {
        return linker.downcallHandle(lib.find(name).orElseThrow(() -> new IllegalStateException("not a libretro core (no " + name + ")")), d);
    }

    private void set(Linker linker, SymbolLookup lib, String setter, MethodHandle target, FunctionDescriptor d) throws Throwable {
        MemorySegment stub = linker.upcallStub(target, d, arena);
        fn(linker, lib, setter, FunctionDescriptor.ofVoid(ADDRESS)).invokeExact(stub);
    }

    private void finish(String why) {
        if (audio != null) audio.close();
        try {
            send('X', why);
        } catch (IOException ignored) {
            // WII-UU is gone
        }
    }

    // ---- the core asks things (on its thread; never let anything escape into native code) -----------

    private static final boolean DEBUG = System.getenv("CHOCCY_DEBUG") != null;

    private boolean environment(int cmd, MemorySegment data) {
        try {
            boolean ok = env(cmd, data);
            if (DEBUG) System.err.println("[choccy] env " + (cmd & ~EXPERIMENTAL) + ((cmd & EXPERIMENTAL) != 0 ? "x" : "") + " -> " + ok);
            return ok;
        } catch (Throwable t) {
            System.err.println("[choccy] environment " + cmd + ": " + t);
            return false;
        }
    }

    private boolean env(int cmd, MemorySegment data) {
        switch (cmd) {
            case 27 -> {                                    // GET_LOG_INTERFACE
                data.reinterpret(8).copyFrom(logInterface);
                return true;
            }
            case 28 -> {                                    // GET_PERF_INTERFACE
                data.reinterpret(perfInterface.byteSize()).copyFrom(perfInterface);
                return true;
            }
            case 23 -> {                                    // GET_RUMBLE_INTERFACE
                data.reinterpret(8).copyFrom(rumbleInterface);
                return true;
            }
            case 3 -> {                                     // GET_CAN_DUPE
                data.reinterpret(1).set(JAVA_BOOLEAN, 0, true);
                return true;
            }
            case 8, 11, 18, 35, 6, 60 -> {                  // performance level, input descriptors, no game, controller info, messages
                return true;
            }
            case 36 | EXPERIMENTAL -> {                     // SET_MEMORY_MAPS: where the console's memory is (for achievements)
                MemorySegment map = data.reinterpret(16);
                MemorySegment descs = map.get(ADDRESS, 0);
                int n = map.get(JAVA_INT, 8);
                List<CheevosMemory.Descriptor> list = new ArrayList<>();
                MemorySegment all = descs.reinterpret(64L * n);
                for (int i = 0; i < n; i++) {
                    long at = 64L * i;
                    list.add(new CheevosMemory.Descriptor(all.get(JAVA_LONG, at), all.get(ADDRESS, at + 8),
                            all.get(JAVA_LONG, at + 16), all.get(JAVA_LONG, at + 24), all.get(JAVA_LONG, at + 32),
                            all.get(JAVA_LONG, at + 40), all.get(JAVA_LONG, at + 48)));
                }
                memoryMap = list;
                cheevoMemory = null;                        // made again from this map
                return true;
            }
            case 9 -> {                                     // GET_SYSTEM_DIRECTORY
                data.reinterpret(8).set(ADDRESS, 0, systemDirC);
                return true;
            }
            case 31 -> {                                    // GET_SAVE_DIRECTORY
                data.reinterpret(8).set(ADDRESS, 0, saveDirC);
                return true;
            }
            case 10 -> {                                    // SET_PIXEL_FORMAT
                int f = data.reinterpret(4).get(JAVA_INT, 0);
                if (f < 0 || f > 2) return false;
                pixelFormat = f;
                return true;
            }
            case 14 -> {                                    // SET_HW_RENDER: no OpenGL / Vulkan here
                wantsHardware = true;
                return false;
            }
            case 15 -> {                                    // GET_VARIABLE
                MemorySegment var = data.reinterpret(16);
                String key = cString(var.get(ADDRESS, 0));
                String value = overrides.getOrDefault(key, defaults.get(key));
                if (value == null) return false;
                var.set(ADDRESS, 8, valueStrings.computeIfAbsent(key + "=" + value, k -> arena.allocateFrom(value)));
                return true;
            }
            case 16 -> {                                    // SET_VARIABLES: "Description; first|second|..."
                for (long at = 0; ; at += 16) {
                    MemorySegment var = data.reinterpret(at + 16).asSlice(at, 16);
                    MemorySegment k = var.get(ADDRESS, 0);
                    if (k.address() == 0) break;
                    String desc = cString(var.get(ADDRESS, 8));
                    int semi = desc.indexOf("; ");
                    String opts = semi >= 0 ? desc.substring(semi + 2) : desc;
                    defaults.put(cString(k), opts.split("\\|")[0]);
                }
                return true;
            }
            case 17 -> {                                    // GET_VARIABLE_UPDATE
                data.reinterpret(1).set(JAVA_BOOLEAN, 0, false);
                return true;
            }
            case 32 -> {                                    // SET_SYSTEM_AV_INFO
                readAv(data.reinterpret(40));
                return true;
            }
            case 37 -> {                                    // SET_GEOMETRY
                readGeometry(data.reinterpret(20));
                return true;
            }
            case 39 -> {                                    // GET_LANGUAGE: English
                data.reinterpret(4).set(JAVA_INT, 0, 0);
                return true;
            }
            case 52 -> {                                    // GET_CORE_OPTIONS_VERSION: 0, so cores use SET_VARIABLES
                data.reinterpret(4).set(JAVA_INT, 0, 0);
                return true;
            }
            case 47 | EXPERIMENTAL -> {                     // GET_AUDIO_VIDEO_ENABLE
                if (data.address() != 0) data.reinterpret(4).set(JAVA_INT, 0, hideFrame ? 2 : 3);   // 2: sound only
                return true;
            }
            case 51 | EXPERIMENTAL -> {                     // GET_INPUT_BITMASKS
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private void readAv(MemorySegment av) {
        readGeometry(av);
        double f = av.get(JAVA_DOUBLE, 24), rate = av.get(JAVA_DOUBLE, 32);
        if (f > 1 && f < 500) fps = f;
        if (rate > 1000) sampleRate = rate;                 // the resampler follows it; the sound card stays at 48 kHz
    }

    private void readGeometry(MemorySegment g) {
        int w = g.get(JAVA_INT, 0), h = g.get(JAVA_INT, 4);
        float a = g.get(JAVA_FLOAT, 16);
        if (w > 0 && h > 0) {
            baseW = w;
            baseH = h;
        }
        aspect = a > 0 ? a : (float) baseW / baseH;
    }

    // ---- pictures ----------------------------------------------------------------------------------

    private void video(MemorySegment data, int w, int h, long pitch) {
        try {
            if (data.address() == 0 || w <= 0 || h <= 0) return;           // the same frame again
            synchronized (frameLock) {
                if (pending) {                              // WII-UU hasn't taken the last one yet: skip this
                    framesDropped++;
                    return;
                }
            }
            MemorySegment src = data.reinterpret(pitch * h);
            int n = w * h;
            if (pixels.length < n) pixels = new int[n];
            for (int y = 0; y < h; y++) {
                long row = y * pitch;
                int o = y * w;
                switch (pixelFormat) {
                    case PIXEL_XRGB8888 -> {
                        MemorySegment.copy(src, JAVA_INT, row, pixels, o, w);
                        for (int x = 0; x < w; x++) pixels[o + x] &= 0xFFFFFF;
                    }
                    case PIXEL_RGB565 -> {
                        for (int x = 0; x < w; x++) {
                            int v = src.get(JAVA_SHORT, row + 2L * x) & 0xFFFF;
                            int r = (v >> 11) & 31, gg = (v >> 5) & 63, b = v & 31;
                            pixels[o + x] = (r << 3 | r >> 2) << 16 | (gg << 2 | gg >> 4) << 8 | (b << 3 | b >> 2);
                        }
                    }
                    default -> {
                        for (int x = 0; x < w; x++) {
                            int v = src.get(JAVA_SHORT, row + 2L * x) & 0xFFFF;
                            int r = (v >> 10) & 31, gg = (v >> 5) & 31, b = v & 31;
                            pixels[o + x] = (r << 3 | r >> 2) << 16 | (gg << 3 | gg >> 2) << 8 | (b << 3 | b >> 2);
                        }
                    }
                }
            }
            synchronized (frameLock) {
                int[] t = sending;                          // hand it over; the writer's old buffer is free again
                sending = pixels;
                pixels = t;
                pendingW = w;
                pendingH = h;
                pending = true;
                frameLock.notifyAll();
            }
        } catch (Throwable t) {
            System.err.println("[choccy] video: " + t);
        }
    }

    /** The writer: sends each frame the core's thread hands over (on its own, so the pipe can be slow). */
    private void writeFrames() {
        try {
            while (running) {
                int w, h;
                int[] px;
                synchronized (frameLock) {
                    while (!pending && running) frameLock.wait(200);
                    if (!pending) continue;
                    w = pendingW;
                    h = pendingH;
                    px = sending;
                }
                int n = w * h, bytes = 13 + n * 4;
                if (frameBuffer.capacity() < bytes) frameBuffer = ByteBuffer.allocate(bytes).order(ByteOrder.BIG_ENDIAN);
                frameBuffer.clear();
                frameBuffer.put((byte) 'F').putInt(w).putInt(h).putFloat(aspect);
                frameBuffer.asIntBuffer().put(px, 0, n);
                synchronized (frameLock) {
                    pending = false;                        // copied out: the core may fill it again
                }
                synchronized (out) {
                    out.write(frameBuffer.array(), 0, bytes);
                    out.flush();
                }
            }
        } catch (IOException e) {
            running = false;                                // WII-UU stopped listening
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- sound -------------------------------------------------------------------------------------

    /**
     * The sound card always runs at 48 kHz and Choccy Milk resamples the core's sound to it. Cores
     * use all kinds of rates (32040.5 Hz for the SNES, 32768 for the GBA, odd ones for the N64), and
     * sound systems that don't support a rate exactly may round it instead of converting: then
     * everything plays at the wrong pitch (and speed).
     */
    private static final int OUT_RATE = 48000;

    private void openAudio() {
        try {
            AudioFormat f = new AudioFormat(OUT_RATE, 16, 2, true, false);
            SourceDataLine line = AudioSystem.getSourceDataLine(f);
            // ~100 ms: the sound card keeps the game's pace, with some room for a slow frame
            line.open(f, (int) (OUT_RATE * 0.1) * 4);
            line.start();
            audioBufferBytes = line.getBufferSize();
            audio = line;
        } catch (Exception | LinkageError e) {
            System.err.println("[choccy] no sound: " + e.getMessage());
            audio = null;
        }
    }

    // single samples (some cores send them one by one): collected, then resampled once a frame
    private short[] single = new short[4096];
    private int singleFrames;

    private void sample(short l, short r) {
        try {
            if (audio == null) return;
            if (singleFrames * 2 + 2 > single.length) single = java.util.Arrays.copyOf(single, single.length * 2);
            single[singleFrames * 2] = l;
            single[singleFrames * 2 + 1] = r;
            singleFrames++;
        } catch (Throwable t) {
            System.err.println("[choccy] sample: " + t);
        }
    }

    /** Sends the single samples of this frame on (after each retro_run). */
    private void flushSingle() {
        if (singleFrames == 0) return;
        int n = singleFrames;
        singleFrames = 0;
        resample(single, n);
    }

    private short[] batch = new short[8192];

    private long samples(MemorySegment data, long frames) {
        try {
            if (audio == null || frames <= 0) return frames;
            int n = (int) frames;
            if (batch.length < n * 2) batch = new short[n * 2];
            MemorySegment.copy(data.reinterpret(n * 4L), JAVA_SHORT, 0, batch, 0, n * 2);
            resample(batch, n);
        } catch (Throwable t) {
            System.err.println("[choccy] samples: " + t);
        }
        return frames;
    }

    private final Resampler resampler = new Resampler(OUT_RATE);

    /** Resamples n stereo frames from the core's rate to 48 kHz and plays them. */
    void resample(short[] in, int n) {
        SourceDataLine line = audio;
        if (line == null || n <= 0) return;
        if (Math.abs(sampleRate - OUT_RATE) < 0.5) {        // already 48 kHz
            play(line, in, n);
            return;
        }
        int out = resampler.push(in, n, sampleRate);
        play(line, resampler.out(), out);
    }


    private void play(SourceDataLine line, short[] pcm, int frames) {
        int bytes = frames * 4;
        if (audioBytes.length < bytes) audioBytes = new byte[bytes];
        for (int i = 0, j = 0; i < frames * 2; i++, j += 2) {
            audioBytes[j] = (byte) pcm[i];
            audioBytes[j + 1] = (byte) (pcm[i] >> 8);
        }
        line.write(audioBytes, 0, bytes);
    }

    // ---- input -------------------------------------------------------------------------------------

    private void poll() {
        try {
            for (int p = 0; p < buttons.length; p++) {
                seen[p] = buttons[p] | latched[p];
                latched[p] = 0;
            }
        } catch (Throwable t) {
            System.err.println("[choccy] poll: " + t);
        }
    }

    private short inputState(int port, int device, int index, int id) {
        try {
            if (port < 0 || port >= buttons.length) return 0;
            if (device == DEVICE_JOYPAD) {
                int held = seen[port];
                if (id == JOYPAD_MASK) return (short) held;
                return (short) (id >= 0 && id < 16 && (held & (1 << id)) != 0 ? 1 : 0);
            }
            if (device == DEVICE_ANALOG) {
                if (index == 2) return (short) (id >= 0 && id < 16 && (seen[port] & (1 << id)) != 0 ? 0x7fff : 0);
                if (index < 2 && id < 2) return sticks[port][index * 2 + id];
            }
            return 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    private void readInput() {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            for (String line; (line = in.readLine()) != null; ) {
                String[] p = line.trim().split(" ");
                try {
                    if (p[0].equals("q")) break;
                    if (p[0].equals("c") || p[0].equals("a") || p[0].equals("x")) {    // achievements: for the core's thread
                        cheevoQueue.add(line.trim().split(" ", 3));
                        continue;
                    }
                    int port = Integer.parseInt(p[1]);
                    if (port < 0 || port >= buttons.length) continue;
                    if (p[0].equals("b") && p.length == 4) {
                        int bit = 1 << Integer.parseInt(p[2]);
                        if (p[3].equals("1")) {
                            buttons[port] |= bit;
                            latched[port] |= bit;
                        } else buttons[port] &= ~bit;
                    } else if (p[0].equals("s") && p.length == 5) {
                        int stick = Integer.parseInt(p[2]);
                        if (stick < 0 || stick > 1) continue;
                        sticks[port][stick * 2] = axis(Float.parseFloat(p[3]));
                        sticks[port][stick * 2 + 1] = axis(Float.parseFloat(p[4]));
                    }
                } catch (RuntimeException ignored) {
                    // a garbled line
                }
            }
        } catch (IOException ignored) {
            // WII-UU went away
        }
        running = false;
    }

    // ---- achievements (RetroAchievements) -------------------------------------------------------------

    private final java.util.concurrent.ConcurrentLinkedQueue<String[]> cheevoQueue = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final Map<Integer, Cheevos.Trigger> cheevos = new java.util.LinkedHashMap<>();
    private int cheevoConsole;
    private List<CheevosMemory.Descriptor> memoryMap;
    private CheevosMemory cheevoMemory;
    private boolean memoryReported;

    /**
     * After each frame: takes achievements WII-UU sent ("c CONSOLE", "a ID MEMADDR", "x" for none),
     * and checks them against the game's memory; one that unlocks is reported as 'A' ID.
     */
    private void checkAchievements() {
        for (String[] m; (m = cheevoQueue.poll()) != null; ) {
            try {
                switch (m[0]) {
                    case "c" -> {
                        cheevoConsole = Integer.parseInt(m[1]);
                        cheevoMemory = null;
                    }
                    case "x" -> cheevos.clear();
                    case "a" -> {
                        int id = Integer.parseInt(m[1]);
                        try {
                            cheevos.put(id, Cheevos.parse(m[2]));
                        } catch (RuntimeException e) {
                            System.err.println("[choccy] achievement " + id + " can't be read: " + e.getMessage());
                        }
                    }
                    default -> { }
                }
            } catch (RuntimeException ignored) {
                // a garbled line
            }
        }
        if (cheevos.isEmpty()) return;
        if (cheevoMemory == null) {
            cheevoMemory = new CheevosMemory(cheevoConsole, memoryMap, this::coreMemory);
            if (!memoryReported) {
                memoryReported = true;
                System.err.println("[choccy] achievements: " + cheevos.size() + ", memory "
                        + (memoryMap != null ? "from the core's map" : "from its RAM blocks") + ": " + cheevoMemory);
                if (!cheevoMemory.usable()) System.err.println("[choccy] achievements: this core shows no memory");
            }
        }
        var it = cheevos.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            if (e.getValue().frame(cheevoMemory)) {
                it.remove();
                System.err.println("[choccy] achievement " + e.getKey() + " unlocked");
                try {
                    send('A', Integer.toString(e.getKey()));
                } catch (IOException ex) {
                    running = false;
                }
            }
        }
    }

    /** One of the core's memory blocks (0 save RAM, 2 system RAM, 3 video RAM), or null. */
    private MemorySegment coreMemory(int type) {
        try {
            long size = (long) getMemorySize.invokeExact(type);
            MemorySegment data = (MemorySegment) getMemoryData.invokeExact(type);
            return size <= 0 || data.address() == 0 ? null : data.reinterpret(size);
        } catch (Throwable t) {
            return null;
        }
    }

    private static short axis(float v) {
        return (short) Math.round(Math.max(-1, Math.min(1, v)) * 32767);
    }

    // ---- saves -------------------------------------------------------------------------------------

    private Path saveFile() {
        String base = romPath.getFileName().toString().replaceFirst("\\.[^.]+$", "");
        return saveDir.resolve(base + ".srm");
    }

    private MemorySegment saveRam() throws Throwable {
        long size = (long) getMemorySize.invokeExact(MEMORY_SAVE_RAM);
        MemorySegment ram = (MemorySegment) getMemoryData.invokeExact(MEMORY_SAVE_RAM);
        if (size <= 0 || ram.address() == 0) return null;
        return ram.reinterpret(size);
    }

    private byte[] lastSaved;

    private void loadSave() throws Throwable {
        MemorySegment ram = saveRam();
        Path f = saveFile();
        if (ram == null || !Files.isRegularFile(f)) return;
        byte[] data = Files.readAllBytes(f);
        MemorySegment.copy(data, 0, ram, JAVA_BYTE, 0, (int) Math.min(data.length, ram.byteSize()));
        lastSaved = data;
        System.err.println("[choccy] loaded " + f);
    }

    private void writeSave() {
        try {
            MemorySegment ram = saveRam();
            if (ram == null) return;
            byte[] now = ram.toArray(JAVA_BYTE);
            if (lastSaved != null && java.util.Arrays.equals(now, lastSaved)) return;
            if (lastSaved == null && allZero(now)) return;          // nothing saved yet
            Path f = saveFile(), tmp = f.resolveSibling(f.getFileName() + ".part");
            Files.write(tmp, now);
            Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            lastSaved = now;
            System.err.println("[choccy] saved " + f);
        } catch (Throwable t) {
            System.err.println("[choccy] couldn't save: " + t);
        }
    }

    private static boolean allZero(byte[] b) {
        for (byte x : b) if (x != 0) return false;
        return true;
    }

    // ---- bits --------------------------------------------------------------------------------------

    /** A zipped game for a core that doesn't open zips: the first file, unpacked next to the saves. */
    private Path unzipIfNeeded(Path rom, String extensions) throws IOException {
        String name = rom.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".zip") || ("|" + extensions.toLowerCase(Locale.ROOT) + "|").contains("|zip|")) return rom;
        Path dir = saveDir.resolve(".unzipped");
        Files.createDirectories(dir);
        try (java.util.zip.ZipInputStream z = new java.util.zip.ZipInputStream(Files.newInputStream(rom))) {
            for (java.util.zip.ZipEntry e; (e = z.getNextEntry()) != null; ) {
                if (e.isDirectory()) continue;
                Path to = dir.resolve(Path.of(e.getName()).getFileName().toString());
                Files.copy(z, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return to;
            }
        }
        return rom;
    }

    private static String cString(MemorySegment p) {
        if (p == null || p.address() == 0) return "";
        return p.reinterpret(Integer.MAX_VALUE).getString(0);
    }

    private void send(char kind, String text) throws IOException {
        synchronized (out) {
            out.write(kind);
            out.write((text + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }
}
