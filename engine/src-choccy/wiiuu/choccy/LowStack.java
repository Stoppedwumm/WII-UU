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
import java.util.concurrent.CountDownLatch;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Runs the core on a thread whose stack, as far as Java can tell, starts very low in memory.
 *
 * <p>Some cores (Mupen64Plus, for one) run the game on their own stacks (libco coroutines, memory
 * from malloc) and call Choccy Milk from there. Java takes any stack pointer below the thread's stack
 * for an overflow, so whether such a game runs or dies with a StackOverflowError depended on where
 * malloc happened to put that memory. Here the thread's stack is declared to reach down to a small
 * block mapped as low as the system allows, so everything malloc hands out lies above it. The
 * thread itself runs on an ordinary block at the top of that range.
 */
final class LowStack {
    private LowStack() {}

    private static final long REAL_STACK = 64L << 20;
    private static final long FLOOR = 1L << 20;
    private static Runnable job;
    private static CountDownLatch done;

    /** Runs job on such a thread and waits for it; false if this system can't (then nothing ran). */
    static boolean run(Runnable job) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        boolean mac = os.contains("mac");
        if (!mac && !os.contains("linux") || System.getenv("CHOCCY_PLAIN_STACK") != null) return false;
        try {
            Linker linker = Linker.nativeLinker();
            SymbolLookup libc = linker.defaultLookup();
            MethodHandle mmap = linker.downcallHandle(libc.find("mmap").orElseThrow(),
                    FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_LONG));
            MethodHandle malloc = linker.downcallHandle(libc.find("malloc").orElseThrow(), FunctionDescriptor.of(ADDRESS, JAVA_LONG));
            MethodHandle free = linker.downcallHandle(libc.find("free").orElseThrow(), FunctionDescriptor.ofVoid(ADDRESS));
            int prot = 3;                                               // PROT_READ | PROT_WRITE
            int flags = mac ? 0x1002 : 0x22;                            // MAP_PRIVATE | MAP_ANON(YMOUS)
            MethodHandle munmap = linker.downcallHandle(libc.find("munmap").orElseThrow(), FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG));
            // the floor: as low as the system gives. Linux puts a block where asked if that's free (else
            // somewhere high: then try the next place); macOS keeps the first 4 GB unmapped and takes
            // the first free place from there up.
            long floor = -1;
            for (long hint : mac ? new long[]{0x100000000L} : new long[]{0x100000L, 0x1000000L, 0x10000000L, 0x40000000L}) {
                long got = ((MemorySegment) mmap.invokeExact(MemorySegment.ofAddress(hint), FLOOR, prot, flags, -1, 0L)).address();
                if (got == -1 || got == 0) continue;
                if (mac || got < hint + (1L << 30)) {
                    floor = got;
                    break;
                }
                int ignored = (int) munmap.invokeExact(MemorySegment.ofAddress(got), FLOOR);
            }
            long stack = ((MemorySegment) mmap.invokeExact(MemorySegment.NULL, REAL_STACK, prot, flags, -1, 0L)).address();
            if (floor == -1 || stack == -1 || floor == 0 || stack == 0) return false;
            // a core's coroutine stack (malloc) has to land above the floor
            MemorySegment probe = (MemorySegment) malloc.invokeExact(16L << 20);
            boolean below = probe.address() != 0 && probe.address() < floor;
            free.invokeExact(probe);
            long top = stack + REAL_STACK;
            if (below || top <= floor) {
                System.err.println("[choccy] no low stack here (floor " + Long.toHexString(floor) + ")");
                return false;
            }

            MethodHandle attrInit = linker.downcallHandle(libc.find("pthread_attr_init").orElseThrow(), FunctionDescriptor.of(JAVA_INT, ADDRESS));
            MethodHandle setStack = linker.downcallHandle(libc.find("pthread_attr_setstack").orElseThrow(),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG));
            MethodHandle create = linker.downcallHandle(libc.find("pthread_create").orElseThrow(),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
            Arena arena = Arena.global();
            MemorySegment attr = arena.allocate(256, 16);
            MemorySegment thread = arena.allocate(16, 8);
            if ((int) attrInit.invokeExact(attr) != 0) return false;
            long size = (top - floor) & ~0xFFFFL;
            if ((int) setStack.invokeExact(attr, MemorySegment.ofAddress(top - size), size) != 0) return false;
            MemorySegment start = linker.upcallStub(MethodHandles.lookup().findStatic(LowStack.class, "start",
                    MethodType.methodType(MemorySegment.class, MemorySegment.class)), FunctionDescriptor.of(ADDRESS, ADDRESS), arena);
            LowStack.job = job;
            done = new CountDownLatch(1);
            if ((int) create.invokeExact(thread, attr, start, MemorySegment.NULL) != 0) return false;
            done.await();
            return true;
        } catch (Throwable t) {
            System.err.println("[choccy] no low stack: " + t);
            return false;
        }
    }

    private static MemorySegment start(MemorySegment arg) {
        try {
            Thread.currentThread().setName("choccy-core");
            job.run();
        } catch (Throwable t) {
            System.err.println("[choccy] " + t);
        } finally {
            done.countDown();
        }
        return MemorySegment.NULL;
    }
}
