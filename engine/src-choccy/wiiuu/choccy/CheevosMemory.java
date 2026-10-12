package wiiuu.choccy;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

/**
 * Where RetroAchievements' addresses are in a core's memory, as rc_libretro (rcheevos, MIT
 * license) works it out: each console has fixed regions (NES $0000-$07FF is its RAM, $6000-$7FFF
 * the cartridge's...); through the memory map a core describes (SET_MEMORY_MAPS) when it gives
 * one, otherwise from its plain RAM, save RAM and video RAM blocks.
 */
final class CheevosMemory implements Cheevos.Memory {

    // region types (rcheevos' RC_MEMORY_TYPE_*)
    static final int SYSTEM = 0, SAVE = 1, VIDEO = 2, READONLY = 3, HARDWARE = 4, VIRTUAL = 5, UNUSED = 6;

    /** One part of a console's achievement address space: [start, end] at real address {@code real}. */
    record Region(long start, long end, long real, int type) {}

    /** A memory descriptor from the core's memory map (struct retro_memory_descriptor). */
    record Descriptor(long flags, MemorySegment ptr, long offset, long start, long select, long disconnect, long len) {}

    /** The regions of the consoles WII-UU plays in Choccy Milk, by RetroAchievements console id. */
    static List<Region> regions(int console) {
        List<Region> r = new ArrayList<>();
        switch (console) {
            case 1 -> {                                     // Mega Drive / Genesis
                r.add(new Region(0x000000, 0x00FFFF, 0xFF0000, SYSTEM));
                r.add(new Region(0x010000, 0x01FFFF, 0x000000, SAVE));
            }
            case 2 -> {                                     // Nintendo 64
                r.add(new Region(0x000000, 0x1FFFFF, 0x80000000L, SYSTEM));
                r.add(new Region(0x200000, 0x3FFFFF, 0x80200000L, SYSTEM));
                r.add(new Region(0x400000, 0x7FFFFF, 0x80400000L, SYSTEM));
            }
            case 3 -> {                                     // Super Nintendo
                r.add(new Region(0x000000, 0x01FFFF, 0x07E0000, SYSTEM));
                r.add(new Region(0x020000, 0x09FFFF, 0x1000000, SAVE));
                r.add(new Region(0x0A0000, 0x0A07FF, 0x1080000, SYSTEM));
            }
            case 4, 6 -> {                                  // Game Boy, Game Boy Color
                r.add(new Region(0x000000, 0x0000FF, 0x000000, HARDWARE));
                r.add(new Region(0x000100, 0x00014F, 0x000100, READONLY));
                r.add(new Region(0x000150, 0x003FFF, 0x000150, READONLY));
                r.add(new Region(0x004000, 0x007FFF, 0x004000, READONLY));
                r.add(new Region(0x008000, 0x0097FF, 0x008000, VIDEO));
                r.add(new Region(0x009800, 0x009BFF, 0x009800, VIDEO));
                r.add(new Region(0x009C00, 0x009FFF, 0x009C00, VIDEO));
                r.add(new Region(0x00A000, 0x00BFFF, 0x00A000, SAVE));
                r.add(new Region(0x00C000, 0x00CFFF, 0x00C000, SYSTEM));
                r.add(new Region(0x00D000, 0x00DFFF, 0x00D000, SYSTEM));
                r.add(new Region(0x00E000, 0x00FDFF, 0x00C000, VIRTUAL));
                r.add(new Region(0x00FE00, 0x00FE9F, 0x00FE00, VIDEO));
                r.add(new Region(0x00FEA0, 0x00FEFF, 0x00FEA0, UNUSED));
                r.add(new Region(0x00FF00, 0x00FF7F, 0x00FF00, HARDWARE));
                r.add(new Region(0x00FF80, 0x00FFFE, 0x00FF80, SYSTEM));
                r.add(new Region(0x00FFFF, 0x00FFFF, 0x00FFFF, HARDWARE));
                r.add(new Region(0x010000, 0x015FFF, 0x010000, console == 6 ? SYSTEM : UNUSED));
                r.add(new Region(0x016000, 0x033FFF, 0x016000, SAVE));
            }
            case 5 -> {                                     // Game Boy Advance
                r.add(new Region(0x000000, 0x007FFF, 0x03000000, SYSTEM));
                r.add(new Region(0x008000, 0x047FFF, 0x02000000, SYSTEM));
                r.add(new Region(0x048000, 0x057FFF, 0x0E000000, SAVE));
            }
            case 7 -> {                                     // NES
                r.add(new Region(0x0000, 0x07FF, 0x0000, SYSTEM));
                r.add(new Region(0x0800, 0x0FFF, 0x0000, VIRTUAL));
                r.add(new Region(0x1000, 0x17FF, 0x0000, VIRTUAL));
                r.add(new Region(0x1800, 0x1FFF, 0x0000, VIRTUAL));
                r.add(new Region(0x2000, 0x2007, 0x2000, HARDWARE));
                r.add(new Region(0x2008, 0x3FFF, 0x2008, VIRTUAL));
                r.add(new Region(0x4000, 0x4017, 0x4000, HARDWARE));
                r.add(new Region(0x4018, 0x401F, 0x4018, HARDWARE));
                r.add(new Region(0x4020, 0x5FFF, 0x4020, READONLY));
                r.add(new Region(0x6000, 0x7FFF, 0x6000, SAVE));
                r.add(new Region(0x8000, 0xFFFF, 0x8000, READONLY));
            }
            case 11 -> {                                    // Master System
                r.add(new Region(0x000000, 0x001FFF, 0x00C000, SYSTEM));
                r.add(new Region(0x002000, 0x009FFF, 0x010000, SAVE));
            }
            case 12 -> {                                    // PlayStation
                r.add(new Region(0x000000, 0x00FFFF, 0x00000000, SYSTEM));
                r.add(new Region(0x010000, 0x1FFFFF, 0x00010000, SYSTEM));
                r.add(new Region(0x200000, 0x2003FF, 0x1F800000, SYSTEM));
            }
            case 18 -> {                                    // Nintendo DS
                r.add(new Region(0x0000000, 0x03FFFFF, 0x02000000, SYSTEM));
                r.add(new Region(0x0400000, 0x0FFFFFF, 0x02400000, UNUSED));
                r.add(new Region(0x1000000, 0x1003FFF, 0x0E000000, SYSTEM));
            }
            default -> { }
        }
        return r;
    }

    // the address space: blocks one after another (null data: nothing there)
    private final List<MemorySegment> data = new ArrayList<>();
    private final List<Long> size = new ArrayList<>();

    /**
     * Builds the address space for a console. {@code coreMemory} gives the core's own blocks by
     * libretro type (0 save RAM, 2 system RAM, 3 video RAM; null if it has none).
     */
    CheevosMemory(int console, List<Descriptor> map, IntFunction<MemorySegment> coreMemory) {
        List<Region> regions = regions(console);
        if (regions.isEmpty()) {
            add(coreMemory.apply(2));
            add(coreMemory.apply(0));
        } else if (map != null && !map.isEmpty()) {
            fromMap(regions, map);
        } else {
            fromBlocks(regions, coreMemory);
        }
    }

    /** Whether any real memory was found. */
    boolean usable() {
        for (MemorySegment d : data) if (d != null) return true;
        return false;
    }

    private void add(MemorySegment block) {
        if (block != null) add(block, block.byteSize());
    }

    private void add(MemorySegment block, long bytes) {
        if (bytes <= 0) return;
        int last = data.size() - 1;
        if (block == null && last >= 0 && data.get(last) == null) {
            size.set(last, size.get(last) + bytes);             // a longer gap
            return;
        }
        data.add(block == null ? null : block.reinterpret(bytes));
        size.add(bytes);
    }

    private void fromBlocks(List<Region> regions, IntFunction<MemorySegment> coreMemory) {
        boolean padding = false;
        for (int i = 0; i < regions.size(); i++) {
            Region r = regions.get(i);
            long want = r.end() - r.start() + 1;
            int type = retroType(r.type());
            if (r.type() == UNUSED && want >= 0x10000 && !padding && regions.get(regions.size() - 1).end() > 0x01000000) {
                padding = true;                              // disjoint memory: the rest can't be in one block
            }
            long base = 0;
            for (int j = 0; j <= i; j++) {
                if (retroType(regions.get(j).type()) == type) {
                    base = regions.get(j).start();
                    break;
                }
            }
            long offset = r.start() - base;
            MemorySegment block = padding ? null : coreMemory.apply(type);
            long have = padding ? want : block == null ? 0 : block.byteSize();
            MemorySegment at = null;
            if (offset < have) {
                have -= offset;
                if (block != null) at = block.asSlice(offset);
            } else {
                have = 0;
            }
            if (want > have) {
                if (at != null) add(at, have);
                add(null, want - have);
            } else {
                add(at, want);
            }
        }
    }

    private static int retroType(int regionType) {
        return switch (regionType) {
            case SAVE -> 0;
            case VIDEO -> 3;
            default -> 2;
        };
    }

    private void fromMap(List<Region> regions, List<Descriptor> map) {
        for (Region r : regions) {
            long left = r.end() - r.start() + 1, real = r.real(), disconnectSize = 0;
            while (left > 0) {
                long[] offset = new long[1];
                Descriptor d = find(map, real, offset);
                if (d == null) {
                    if (disconnectSize != 0 && left > disconnectSize) {
                        add(null, disconnectSize);
                        left -= disconnectSize;
                        real += disconnectSize;
                        disconnectSize = 0;
                        continue;
                    }
                    add(null, left);
                    break;
                }
                MemorySegment start = null;
                if (d.ptr() != null && d.ptr().address() != 0) {
                    start = MemorySegment.ofAddress(d.ptr().address() + d.offset() + offset[0]);
                }
                long chunk = d.len() - offset[0];
                if (d.disconnect() != 0 && chunk > d.disconnect()) {
                    disconnectSize = d.disconnect() & -d.disconnect();
                    chunk = disconnectSize - (real & (disconnectSize - 1));
                }
                if (left > chunk) {
                    if (chunk == 0) {
                        add(null, left);
                        left = 0;
                    } else {
                        add(start, chunk);
                        left -= chunk;
                        real += chunk;
                    }
                } else {
                    add(start, left);
                    left = 0;
                }
            }
        }
    }

    /** The descriptor that holds a real address, and the offset into it (rc_libretro_memory_get_descriptor). */
    private static Descriptor find(List<Descriptor> map, long real, long[] offset) {
        for (Descriptor d : map) {
            if (d.select() == 0) {
                if (real >= d.start() && real < d.start() + d.len()) {
                    offset[0] = real - d.start();
                    return d;
                }
            } else if (((d.start() ^ real) & d.select()) == 0) {
                long reduced = (real - d.start()) & 0xffffffffL;
                long mask = d.disconnect() & 0xffffffffL;
                while (mask != 0) {
                    long tmp = (mask - 1) & ~mask;
                    reduced = (reduced & tmp) | ((reduced >>> 1) & ~tmp);
                    mask = (mask & (mask - 1)) >>> 1;
                }
                offset[0] = reduced;
                if (reduced < d.len()) return d;
            }
        }
        offset[0] = 0;
        return null;
    }

    @Override
    public int peek(long address, int bytes) {
        int v = 0;
        for (int i = 0; i < bytes; i++) v |= (read(address + i) & 0xff) << (8 * i);
        return v;
    }

    private int read(long address) {
        for (int i = 0; i < data.size(); i++) {
            long s = size.get(i);
            if (address < s) {
                MemorySegment d = data.get(i);
                return d == null ? 0 : d.get(java.lang.foreign.ValueLayout.JAVA_BYTE, address);
            }
            address -= s;
        }
        return 0;
    }

    @Override
    public String toString() {
        StringBuilder b = new StringBuilder();
        long at = 0;
        for (int i = 0; i < data.size(); i++) {
            b.append(String.format("$%06X+%X%s ", at, size.get(i), data.get(i) == null ? "(none)" : ""));
            at += size.get(i);
        }
        return b.toString().trim();
    }
}
