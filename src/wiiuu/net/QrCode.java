package wiiuu.net;

import java.nio.charset.StandardCharsets;

/**
 * Minimal QR code encoder (byte mode, error correction level M, versions 1-6),
 * enough to show the gamepad URL on the TV so a phone can scan it.
 */
public final class QrCode {
    // index = version; level M
    private static final int[] ECC_PER_BLOCK = {0, 10, 16, 26, 18, 24, 16};
    private static final int[] NUM_BLOCKS = {0, 1, 1, 1, 2, 2, 4};
    private static final int[] ALIGN_POS = {0, 0, 18, 22, 26, 30, 34};

    public final int size;
    private final boolean[][] modules;
    private final boolean[][] function;

    public boolean get(int x, int y) {
        return modules[y][x];
    }

    public static QrCode encode(String text) {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        for (int v = 1; v <= 6; v++) {
            int capacityBits = dataCodewords(v) * 8;
            if (4 + 8 + data.length * 8 <= capacityBits) return new QrCode(v, data);
        }
        throw new IllegalArgumentException("Text too long for QR version 6: " + text.length() + " bytes");
    }

    private static int rawCodewords(int v) {
        int size = v * 4 + 17;
        int result = size * size;
        result -= 3 * 64 + 2 * 15 + 1;           // finders + separators, format info, dark module
        result -= 2 * (size - 16);                // timing patterns
        if (v >= 2) result -= 25;                 // one alignment pattern (v2..v6)
        return result / 8;
    }

    private static int dataCodewords(int v) {
        return rawCodewords(v) - ECC_PER_BLOCK[v] * NUM_BLOCKS[v];
    }

    private QrCode(int version, byte[] data) {
        size = version * 4 + 17;
        modules = new boolean[size][size];
        function = new boolean[size][size];
        drawFunctionPatterns(version);
        byte[] codewords = addEcc(version, buildData(version, data));
        drawCodewords(codewords);

        int best = 0;
        long bestPenalty = Long.MAX_VALUE;
        for (int m = 0; m < 8; m++) {
            applyMask(m);
            drawFormat(m);
            long p = penalty();
            if (p < bestPenalty) {
                bestPenalty = p;
                best = m;
            }
            applyMask(m); // XOR undo
        }
        applyMask(best);
        drawFormat(best);
    }

    // ---- data -------------------------------------------------------------------------

    private static byte[] buildData(int version, byte[] data) {
        int capacity = dataCodewords(version);
        BitBuffer bb = new BitBuffer();
        bb.append(0b0100, 4);           // byte mode
        bb.append(data.length, 8);      // char count (versions 1-9)
        for (byte b : data) bb.append(b & 0xFF, 8);
        bb.append(0, Math.min(4, capacity * 8 - bb.length));
        bb.append(0, (8 - bb.length % 8) % 8);
        for (int pad = 0xEC; bb.length < capacity * 8; pad ^= 0xEC ^ 0x11) bb.append(pad, 8);
        return bb.toBytes();
    }

    private static byte[] addEcc(int version, byte[] data) {
        int numBlocks = NUM_BLOCKS[version];
        int eccLen = ECC_PER_BLOCK[version];
        int raw = rawCodewords(version);
        int numShort = numBlocks - raw % numBlocks;
        int shortLen = raw / numBlocks;

        byte[] divisor = rsDivisor(eccLen);
        byte[][] blocks = new byte[numBlocks][];
        for (int i = 0, k = 0; i < numBlocks; i++) {
            int datLen = shortLen - eccLen + (i < numShort ? 0 : 1);
            byte[] dat = java.util.Arrays.copyOfRange(data, k, k + datLen);
            k += datLen;
            byte[] block = java.util.Arrays.copyOf(dat, shortLen + 1);
            byte[] ecc = rsRemainder(dat, divisor);
            System.arraycopy(ecc, 0, block, block.length - eccLen, eccLen);
            blocks[i] = block;
        }
        byte[] result = new byte[raw];
        int idx = 0;
        for (int i = 0; i < blocks[0].length; i++) {
            for (int j = 0; j < blocks.length; j++) {
                // short blocks carry a dummy byte at the end of their data part
                if (i != shortLen - eccLen || j >= numShort) result[idx++] = blocks[j][i];
            }
        }
        return result;
    }

    private static byte[] rsDivisor(int degree) {
        byte[] result = new byte[degree];
        result[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < result.length; j++) {
                result[j] = (byte) gfMul(result[j] & 0xFF, root);
                if (j + 1 < result.length) result[j] ^= result[j + 1];
            }
            root = gfMul(root, 0x02);
        }
        return result;
    }

    private static byte[] rsRemainder(byte[] data, byte[] divisor) {
        byte[] result = new byte[divisor.length];
        for (byte b : data) {
            int factor = (b ^ result[0]) & 0xFF;
            System.arraycopy(result, 1, result, 0, result.length - 1);
            result[result.length - 1] = 0;
            for (int i = 0; i < result.length; i++) result[i] ^= (byte) gfMul(divisor[i] & 0xFF, factor);
        }
        return result;
    }

    private static int gfMul(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z;
    }

    // ---- layout -----------------------------------------------------------------------

    private void set(int x, int y, boolean dark) {
        modules[y][x] = dark;
        function[y][x] = true;
    }

    private void drawFunctionPatterns(int version) {
        for (int i = 0; i < size; i++) {
            set(6, i, i % 2 == 0);
            set(i, 6, i % 2 == 0);
        }
        finder(3, 3);
        finder(size - 4, 3);
        finder(3, size - 4);
        if (version >= 2) {
            int p = ALIGN_POS[version];
            for (int dy = -2; dy <= 2; dy++)
                for (int dx = -2; dx <= 2; dx++)
                    set(p + dx, p + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
        }
        drawFormat(0); // reserve area; real bits drawn after masking
    }

    private void finder(int cx, int cy) {
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int x = cx + dx, y = cy + dy;
                if (x < 0 || y < 0 || x >= size || y >= size) continue;
                int d = Math.max(Math.abs(dx), Math.abs(dy));
                set(x, y, d != 2 && d != 4);
            }
        }
    }

    private void drawFormat(int mask) {
        int data = (0b00 << 3) | mask; // level M = 00
        int rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        int bits = ((data << 10) | rem) ^ 0x5412;

        for (int i = 0; i <= 5; i++) set(8, i, bit(bits, i));
        set(8, 7, bit(bits, 6));
        set(8, 8, bit(bits, 7));
        set(7, 8, bit(bits, 8));
        for (int i = 9; i < 15; i++) set(14 - i, 8, bit(bits, i));

        for (int i = 0; i < 8; i++) set(size - 1 - i, 8, bit(bits, i));
        for (int i = 8; i < 15; i++) set(8, size - 15 + i, bit(bits, i));
        set(8, size - 8, true);
    }

    private static boolean bit(int x, int i) {
        return ((x >>> i) & 1) != 0;
    }

    private void drawCodewords(byte[] data) {
        int i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? size - 1 - vert : vert;
                    if (!function[y][x] && i < data.length * 8) {
                        modules[y][x] = bit(data[i >>> 3], 7 - (i & 7));
                        i++;
                    }
                }
            }
        }
    }

    private void applyMask(int mask) {
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (function[y][x]) continue;
                boolean invert = switch (mask) {
                    case 0 -> (x + y) % 2 == 0;
                    case 1 -> y % 2 == 0;
                    case 2 -> x % 3 == 0;
                    case 3 -> (x + y) % 3 == 0;
                    case 4 -> (x / 3 + y / 2) % 2 == 0;
                    case 5 -> x * y % 2 + x * y % 3 == 0;
                    case 6 -> (x * y % 2 + x * y % 3) % 2 == 0;
                    default -> ((x + y) % 2 + x * y % 3) % 2 == 0;
                };
                if (invert) modules[y][x] = !modules[y][x];
            }
        }
    }

    /** Simplified penalty (runs, 2x2 blocks, balance); any mask decodes, this just picks a clean one. */
    private long penalty() {
        long p = 0;
        for (int a = 0; a < size; a++) {
            int runRow = 1, runCol = 1;
            for (int b = 1; b < size; b++) {
                if (modules[a][b] == modules[a][b - 1]) {
                    if (++runRow == 5) p += 3;
                    else if (runRow > 5) p++;
                } else runRow = 1;
                if (modules[b][a] == modules[b - 1][a]) {
                    if (++runCol == 5) p += 3;
                    else if (runCol > 5) p++;
                } else runCol = 1;
            }
        }
        int dark = 0;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (modules[y][x]) dark++;
                if (x + 1 < size && y + 1 < size) {
                    boolean c = modules[y][x];
                    if (c == modules[y][x + 1] && c == modules[y + 1][x] && c == modules[y + 1][x + 1]) p += 3;
                }
            }
        }
        int total = size * size;
        p += (long) (Math.abs(dark * 20L - total * 10L) + total - 1) / total * 10;
        return p;
    }

    private static final class BitBuffer {
        private final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        private int cur, curBits, length;

        void append(int value, int bits) {
            for (int i = bits - 1; i >= 0; i--) {
                cur = (cur << 1) | ((value >>> i) & 1);
                length++;
                if (++curBits == 8) {
                    out.write(cur);
                    cur = 0;
                    curBits = 0;
                }
            }
        }

        byte[] toBytes() {
            return out.toByteArray();
        }
    }
}
