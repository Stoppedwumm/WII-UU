package wiiuu.choccy;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Locale;

import static java.lang.foreign.ValueLayout.*;

/** Offscreen OpenGL for the desktop N64 core. All GL calls stay on the core's thread. */
final class OpenGl {
    private static final int CORE_CONTEXT = 3;
    private static final int FRAMEBUFFER = 0x8D40, READ_FRAMEBUFFER = 0x8CA8, RENDERBUFFER = 0x8D41;
    private static final int COLOR = 0x8CE0, DEPTH_STENCIL = 0x821A;
    private static final int PACK_BUFFER = 0x88EB;
    private final Arena arena;
    private final Linker linker = Linker.nativeLinker();
    private final SymbolLookup library;
    private final MethodHandle lookup;
    private final MemorySegment procStub, framebufferStub;
    private final MemorySegment scratch;
    private MethodHandle reset, destroy, bindFramebuffer, bindBuffer, getInteger, pixelStore, readPixels;
    private boolean bottomLeft;
    private int framebuffer, width, height;
    private MemorySegment pixels = MemorySegment.NULL;

    OpenGl(Arena arena) throws Throwable {
        this.arena = arena;
        scratch = arena.allocate(JAVA_INT);
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            library = SymbolLookup.libraryLookup("opengl32.dll", arena);
            lookup = symbol(library, "wglGetProcAddress", FunctionDescriptor.of(ADDRESS, ADDRESS));
            openWindows();
        } else if (os.contains("linux")) {
            library = SymbolLookup.libraryLookup("libEGL.so.1", arena);
            lookup = symbol(library, "eglGetProcAddress", FunctionDescriptor.of(ADDRESS, ADDRESS));
            openLinux();
        } else {
            throw new IllegalStateException("Choccy Milk's N64 OpenGL mode is available on Windows and Linux.");
        }
        var handles = MethodHandles.lookup();
        procStub = linker.upcallStub(handles.bind(this, "proc", MethodType.methodType(MemorySegment.class, MemorySegment.class)),
                FunctionDescriptor.of(ADDRESS, ADDRESS), arena);
        framebufferStub = linker.upcallStub(handles.bind(this, "framebuffer", MethodType.methodType(long.class)),
                FunctionDescriptor.of(JAVA_LONG), arena);
    }

    private MethodHandle symbol(SymbolLookup from, String name, FunctionDescriptor signature) {
        return linker.downcallHandle(from.find(name).orElseThrow(
                () -> new IllegalStateException("Missing OpenGL function " + name)), signature);
    }

    private MemorySegment address(MemorySegment name) throws Throwable {
        MemorySegment address = (MemorySegment) lookup.invokeExact(name);
        if (address.address() > 3 || address.address() < -1) return address;
        return library.find(name.reinterpret(256).getString(0)).orElse(MemorySegment.NULL);
    }

    private MemorySegment proc(MemorySegment name) {
        try { return address(name); } catch (Throwable t) { return MemorySegment.NULL; }
    }

    private MethodHandle function(String name, FunctionDescriptor signature) throws Throwable {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment function = address(temp.allocateFrom(name));
            if (function.address() == 0) throw new IllegalStateException("Missing OpenGL function " + name);
            return linker.downcallHandle(function, signature);
        }
    }

    private void openLinux() throws Throwable {
        var init = symbol(library, "eglInitialize", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        var display = symbol(library, "eglGetDisplay", FunctionDescriptor.of(ADDRESS, ADDRESS));
        MemorySegment d = MemorySegment.NULL;
        var platformDisplay = library.find("eglGetPlatformDisplay");
        if (platformDisplay.isPresent()) {
            var platform = linker.downcallHandle(platformDisplay.get(), FunctionDescriptor.of(ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
            d = (MemorySegment) platform.invokeExact(0x31DD, MemorySegment.NULL, MemorySegment.NULL); // EGL_PLATFORM_SURFACELESS_MESA
        }
        if (d.address() == 0 || (int) init.invokeExact(d, MemorySegment.NULL, MemorySegment.NULL) == 0) {
            d = (MemorySegment) display.invokeExact(MemorySegment.NULL);
            if (d.address() == 0 || (int) init.invokeExact(d, MemorySegment.NULL, MemorySegment.NULL) == 0) {
                throw new IllegalStateException("Could not initialize EGL. Check the graphics driver.");
            }
        }
        var bind = symbol(library, "eglBindAPI", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
        if ((int) bind.invokeExact(0x30A2) == 0) throw new IllegalStateException("EGL does not expose desktop OpenGL.");
        var choose = symbol(library, "eglChooseConfig",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS));
        var surface = symbol(library, "eglCreatePbufferSurface", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        var context = symbol(library, "eglCreateContext", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        var current = symbol(library, "eglMakeCurrent", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        MemorySegment cfg = arena.allocate(ADDRESS);
        MemorySegment configAttrs = ints(0x3033, 1, 0x3040, 8, 0x3024, 8, 0x3023, 8, 0x3022, 8, 0x3038);
        if ((int) choose.invokeExact(d, configAttrs, cfg, 1, scratch) == 0 || scratch.get(JAVA_INT, 0) == 0) {
            throw new IllegalStateException("No EGL OpenGL configuration was found.");
        }
        MemorySegment config = cfg.get(ADDRESS, 0);
        MemorySegment pbuffer = (MemorySegment) surface.invokeExact(d, config, ints(0x3057, 1, 0x3056, 1, 0x3038));
        MemorySegment gl = (MemorySegment) context.invokeExact(d, config, MemorySegment.NULL,
                ints(0x3098, 3, 0x30FB, 3, 0x30FD, 1, 0x3038));
        if (pbuffer.address() == 0 || gl.address() == 0 || (int) current.invokeExact(d, pbuffer, pbuffer, gl) == 0) {
            throw new IllegalStateException("Could not create an OpenGL 3.3 core context.");
        }
    }

    private void openWindows() throws Throwable {
        SymbolLookup user = SymbolLookup.libraryLookup("user32.dll", arena);
        SymbolLookup gdi = SymbolLookup.libraryLookup("gdi32.dll", arena);
        var window = symbol(user, "CreateWindowExW", FunctionDescriptor.of(ADDRESS, JAVA_INT, ADDRESS, ADDRESS,
                JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        var getDC = symbol(user, "GetDC", FunctionDescriptor.of(ADDRESS, ADDRESS));
        MemorySegment type = arena.allocateFrom(JAVA_CHAR, new char[]{'S', 'T', 'A', 'T', 'I', 'C', 0});
        MemorySegment hwnd = (MemorySegment) window.invokeExact(0, type, MemorySegment.NULL, 0x80000000,
                0, 0, 1, 1, MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL);
        if (hwnd.address() == 0) throw new IllegalStateException("Could not create the hidden OpenGL window.");
        MemorySegment dc = (MemorySegment) getDC.invokeExact(hwnd);
        if (dc.address() == 0) throw new IllegalStateException("Could not get the OpenGL window's device context.");
        MemorySegment pfd = arena.allocate(40, 4); // PIXELFORMATDESCRIPTOR
        pfd.set(JAVA_SHORT, 0, (short) 40);
        pfd.set(JAVA_SHORT, 2, (short) 1);
        pfd.set(JAVA_INT, 4, 0x24); // PFD_DRAW_TO_WINDOW | PFD_SUPPORT_OPENGL
        pfd.set(JAVA_BYTE, 9, (byte) 32);
        pfd.set(JAVA_BYTE, 23, (byte) 24);
        pfd.set(JAVA_BYTE, 24, (byte) 8);
        var choose = symbol(gdi, "ChoosePixelFormat", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        var set = symbol(gdi, "SetPixelFormat", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
        int format = (int) choose.invokeExact(dc, pfd);
        if (format == 0 || (int) set.invokeExact(dc, format, pfd) == 0) {
            throw new IllegalStateException("Could not set the OpenGL window's pixel format.");
        }
        var create = symbol(library, "wglCreateContext", FunctionDescriptor.of(ADDRESS, ADDRESS));
        var current = symbol(library, "wglMakeCurrent", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        var delete = symbol(library, "wglDeleteContext", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        MemorySegment legacy = (MemorySegment) create.invokeExact(dc);
        if (legacy.address() == 0 || (int) current.invokeExact(dc, legacy) == 0) {
            throw new IllegalStateException("Could not initialize WGL.");
        }
        var modern = function("wglCreateContextAttribsARB", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        MemorySegment gl = (MemorySegment) modern.invokeExact(dc, MemorySegment.NULL, ints(0x2091, 3, 0x2092, 3, 0x9126, 1, 0));
        if (gl.address() == 0 || (int) current.invokeExact(dc, gl) == 0) {
            throw new IllegalStateException("Could not create an OpenGL 3.3 core context.");
        }
        if ((int) delete.invokeExact(legacy) == 0) throw new IllegalStateException("Could not release the initial WGL context.");
    }

    private MemorySegment ints(int... values) {
        return arena.allocateFrom(JAVA_INT, values);
    }

    private long framebuffer() { return Integer.toUnsignedLong(framebuffer); }

    /** retro_hw_render_callback layout for the desktop 64-bit libretro ABI. */
    boolean register(MemorySegment data) {
        MemorySegment hw = data.reinterpret(64);
        int context = hw.get(JAVA_INT, 0);
        int major = hw.get(JAVA_INT, 36);
        int minor = hw.get(JAVA_INT, 40);
        // Some libretro N64 cores request the legacy OpenGL API rather than
        // OPENGL_CORE. Our desktop backend provides a 3.3 core context; only
        // accept the legacy request when the core has not asked for features
        // beyond that version.
        if (context != 1 && context != CORE_CONTEXT) {
            System.err.println("[choccy] unsupported hardware context: " + context);
            return false;
        }
        if (major > 3 || (major == 3 && minor > 3)) {
            System.err.println("[choccy] unsupported OpenGL version: " + major + "." + minor);
            return false;
        }
        MemorySegment begin = hw.get(ADDRESS, 8);
        if (begin.address() == 0) return false;
        reset = linker.downcallHandle(begin, FunctionDescriptor.ofVoid());
        MemorySegment end = hw.get(ADDRESS, 48);
        destroy = end.address() == 0 ? null : linker.downcallHandle(end, FunctionDescriptor.ofVoid());
        bottomLeft = hw.get(JAVA_BOOLEAN, 34);
        hw.set(ADDRESS, 16, framebufferStub);
        hw.set(ADDRESS, 24, procStub);
        return true;
    }

    /** The GL context and its framebuffer must be ready before the core's reset callback. */
    void start(int w, int h) throws Throwable {
        if (reset == null) throw new IllegalStateException("The core did not accept the OpenGL 3.3 context.");
        if (w > 4096 || h > 4096) throw new IllegalStateException("The N64 core requested an oversized framebuffer.");
        width = w;
        height = h;
        var genFramebuffer = function("glGenFramebuffers", FunctionDescriptor.ofVoid(JAVA_INT, ADDRESS));
        var genRenderbuffer = function("glGenRenderbuffers", FunctionDescriptor.ofVoid(JAVA_INT, ADDRESS));
        bindFramebuffer = function("glBindFramebuffer", FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT));
        bindBuffer = function("glBindBuffer", FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT));
        getInteger = function("glGetIntegerv", FunctionDescriptor.ofVoid(JAVA_INT, ADDRESS));
        pixelStore = function("glPixelStorei", FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT));
        readPixels = function("glReadPixels", FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT,
                JAVA_INT, JAVA_INT, ADDRESS));
        var bindRenderbuffer = function("glBindRenderbuffer", FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT));
        var storage = function("glRenderbufferStorage", FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
        var attach = function("glFramebufferRenderbuffer", FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
        var status = function("glCheckFramebufferStatus", FunctionDescriptor.of(JAVA_INT, JAVA_INT));

        genFramebuffer.invokeExact(1, scratch);
        framebuffer = scratch.get(JAVA_INT, 0);
        bindFramebuffer.invokeExact(FRAMEBUFFER, framebuffer);
        int[] formats = {0x8058, 0x88F0}; // RGBA8, DEPTH24_STENCIL8
        int[] attachments = {COLOR, DEPTH_STENCIL};
        for (int i = 0; i < formats.length; i++) {
            genRenderbuffer.invokeExact(1, scratch);
            int buffer = scratch.get(JAVA_INT, 0);
            bindRenderbuffer.invokeExact(RENDERBUFFER, buffer);
            storage.invokeExact(RENDERBUFFER, formats[i], w, h);
            attach.invokeExact(FRAMEBUFFER, attachments[i], RENDERBUFFER, buffer);
        }
        if ((int) status.invokeExact(FRAMEBUFFER) != 0x8CD5) throw new IllegalStateException("OpenGL framebuffer is incomplete.");
        bindFramebuffer.invokeExact(FRAMEBUFFER, 0);
        pixels = arena.allocate((long) w * h * 4, 4);
        reset.invokeExact();
    }

    private int get(int name) throws Throwable {
        getInteger.invokeExact(name, scratch);
        return scratch.get(JAVA_INT, 0);
    }

    boolean read(int[] output, int w, int h) throws Throwable {
        if (w > width || h > height) return false;
        int previous = get(0x8CAA), buffer = get(0x88ED); // read FBO and pixel pack buffer binding
        int rowLength = get(0x0D02), rows = get(0x0D03), columns = get(0x0D04), alignment = get(0x0D05);
        bindFramebuffer.invokeExact(READ_FRAMEBUFFER, framebuffer);
        bindBuffer.invokeExact(PACK_BUFFER, 0);
        pixelStore.invokeExact(0x0D02, 0);
        pixelStore.invokeExact(0x0D03, 0);
        pixelStore.invokeExact(0x0D04, 0);
        pixelStore.invokeExact(0x0D05, 4);
        try {
            readPixels.invokeExact(0, 0, w, h, 0x80E1, 0x8367, pixels); // BGRA, UNSIGNED_INT_8_8_8_8_REV
        } finally {
            pixelStore.invokeExact(0x0D02, rowLength);
            pixelStore.invokeExact(0x0D03, rows);
            pixelStore.invokeExact(0x0D04, columns);
            pixelStore.invokeExact(0x0D05, alignment);
            bindBuffer.invokeExact(PACK_BUFFER, buffer);
            bindFramebuffer.invokeExact(READ_FRAMEBUFFER, previous);
        }
        for (int y = 0; y < h; y++) {
            long row = (long) (bottomLeft ? h - 1 - y : y) * w * 4;
            MemorySegment.copy(pixels, JAVA_INT, row, output, y * w, w);
        }
        for (int i = 0; i < w * h; i++) output[i] &= 0xFFFFFF;
        return true;
    }

    void stop() throws Throwable {
        if (destroy != null && framebuffer != 0) destroy.invokeExact();
        // The helper process owns the context, native window and GL objects for this game.
    }
}
