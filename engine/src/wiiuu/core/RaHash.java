package wiiuu.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** RetroAchievements' ROM hashes (how it tells which game a file is), for cartridge consoles. */
public final class RaHash {
    private RaHash() {}

    /** The ROM's RetroAchievements hash (lower-case hex), or null if it can't be read. */
    public static String hash(Game g) {
        try {
            byte[] rom = read(g.path());
            if (rom == null) return null;
            return md5(prepare(g.system().id(), rom));
        } catch (Exception e) {
            return null;
        }
    }

    /** The ROM's bytes (the first file of a zip). Up to 64 MB. */
    private static byte[] read(Path p) throws IOException {
        if (Files.size(p) > 64L << 20) return null;
        if (p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip")) {
            try (ZipInputStream z = new ZipInputStream(Files.newInputStream(p))) {
                for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                    if (!e.isDirectory()) return readAll(z);
                }
            }
            return null;
        }
        return Files.readAllBytes(p);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        return in.readNBytes(64 << 20);
    }

    /** The part of the ROM RetroAchievements hashes, for this console. */
    public static byte[] prepare(String system, byte[] rom) {
        switch (system) {
            case "nes" -> {
                // iNES / FDS header: 16 bytes
                if (rom.length > 16 && (rom[0] == 'N' && rom[1] == 'E' && rom[2] == 'S' && rom[3] == 0x1A
                        || rom[0] == 'F' && rom[1] == 'D' && rom[2] == 'S' && rom[3] == 0x1A)) {
                    return java.util.Arrays.copyOfRange(rom, 16, rom.length);
                }
            }
            case "snes" -> {
                // a 512-byte copier header
                if (rom.length % 1024 == 512) return java.util.Arrays.copyOfRange(rom, 512, rom.length);
            }
            case "n64" -> {
                // always in big-endian (.z64) order
                if (rom.length >= 4) {
                    int m = (rom[0] & 0xff) << 24 | (rom[1] & 0xff) << 16 | (rom[2] & 0xff) << 8 | rom[3] & 0xff;
                    byte[] out = rom.clone();
                    if (m == 0x37804012) {                          // .v64: bytes swapped in pairs
                        for (int i = 0; i + 1 < out.length; i += 2) {
                            byte b = out[i];
                            out[i] = out[i + 1];
                            out[i + 1] = b;
                        }
                        return out;
                    }
                    if (m == 0x40123780) {                          // .n64: little-endian words
                        for (int i = 0; i + 3 < out.length; i += 4) {
                            byte a = out[i], b = out[i + 1];
                            out[i] = out[i + 3];
                            out[i + 1] = out[i + 2];
                            out[i + 2] = b;
                            out[i + 3] = a;
                        }
                        return out;
                    }
                }
            }
            case "nds" -> {
                // the header, the ARM9 and ARM7 code, and the icon / title
                if (rom.length >= 0x200) {
                    java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
                    b.write(rom, 0, 0x160);
                    int arm9 = le(rom, 0x20), arm9n = le(rom, 0x2C), arm7 = le(rom, 0x30), arm7n = le(rom, 0x3C), icon = le(rom, 0x68);
                    if (arm9 >= 0 && arm9n >= 0 && arm9 + (long) arm9n <= rom.length) b.write(rom, arm9, arm9n);
                    if (arm7 >= 0 && arm7n >= 0 && arm7 + (long) arm7n <= rom.length) b.write(rom, arm7, arm7n);
                    if (icon > 0 && icon + 0xA00L <= rom.length) b.write(rom, icon, 0xA00);
                    return b.toByteArray();
                }
            }
            default -> { }
        }
        return rom;
    }

    private static int le(byte[] b, int at) {
        return (b[at] & 0xff) | (b[at + 1] & 0xff) << 8 | (b[at + 2] & 0xff) << 16 | (b[at + 3] & 0xff) << 24;
    }

    public static String md5(byte[] data) throws Exception {
        byte[] d = MessageDigest.getInstance("MD5").digest(data);
        StringBuilder s = new StringBuilder();
        for (byte x : d) s.append(String.format("%02x", x));
        return s.toString();
    }

}
