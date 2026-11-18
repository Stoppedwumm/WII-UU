package wiiuu.choccy;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.*;

/** Keep the Windows N64 core's C stdout (printf) out of the binary frame stream. */
final class WindowsFrameOutput extends OutputStream {
    private final MemorySegment pipe, buffer, count;
    private final MethodHandle writeFile;

    private WindowsFrameOutput(MemorySegment pipe, MethodHandle writeFile, Arena arena) {
        this.pipe = pipe;
        this.writeFile = writeFile;
        buffer = arena.allocate(64 * 1024);
        count = arena.allocate(JAVA_INT);
    }

    static OutputStream open() throws IOException {
        Arena arena = Arena.global(); // the child process owns the duplicate until exit
        try {
            SymbolLookup kernel = SymbolLookup.libraryLookup("kernel32.dll", arena);
            SymbolLookup crt = SymbolLookup.libraryLookup("msvcrt.dll", arena);
            var std = fn(kernel, "GetStdHandle", FunctionDescriptor.of(ADDRESS, JAVA_INT));
            var process = fn(kernel, "GetCurrentProcess", FunctionDescriptor.of(ADDRESS));
            var duplicate = fn(kernel, "DuplicateHandle", FunctionDescriptor.of(JAVA_INT,
                    ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT));
            var redirect = fn(crt, "_dup2", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT));
            MemorySegment me = (MemorySegment) process.invokeExact();
            MemorySegment slot = arena.allocate(ADDRESS);
            MemorySegment stdout = (MemorySegment) std.invokeExact(-11);
            if ((int) duplicate.invokeExact(me, stdout, me, slot, 0, 0, 2) == 0
                    || (int) redirect.invokeExact(2, 1) != 0) {
                throw new IOException("Could not separate Windows core logging from video.");
            }
            var write = fn(kernel, "WriteFile", FunctionDescriptor.of(JAVA_INT,
                    ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
            return new WindowsFrameOutput(slot.get(ADDRESS, 0), write, arena);
        } catch (Throwable e) {
            throw new IOException("Could not open the Windows video pipe", e);
        }
    }

    private static MethodHandle fn(SymbolLookup library, String name, FunctionDescriptor descriptor) {
        return Linker.nativeLinker().downcallHandle(library.find(name).orElseThrow(), descriptor);
    }

    @Override public void write(int b) throws IOException {
        write(new byte[]{(byte) b}, 0, 1);
    }

    @Override public void write(byte[] bytes, int offset, int length) throws IOException {
        while (length > 0) {
            int chunk = Math.min(length, (int) buffer.byteSize());
            MemorySegment.copy(bytes, offset, buffer, JAVA_BYTE, 0, chunk);
            int written;
            try {
                if ((int) writeFile.invokeExact(pipe, buffer, chunk, count, MemorySegment.NULL) == 0) {
                    throw new IOException("Windows video pipe closed");
                }
                written = count.get(JAVA_INT, 0);
            } catch (Throwable e) {
                throw new IOException("Could not write the Windows video pipe", e);
            }
            if (written <= 0) throw new IOException("Windows video pipe closed");
            offset += written;
            length -= written;
        }
    }
}
