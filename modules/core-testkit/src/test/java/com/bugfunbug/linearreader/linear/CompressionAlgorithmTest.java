package com.bugfunbug.linearreader.linear;

import com.bugfunbug.linearreader.linear.CompressionAlgorithm.Algorithm;
import com.bugfunbug.linearreader.linear.CompressionAlgorithm.Encoded;

public final class CompressionAlgorithmTest {

    private static int failures = 0;

    public static void main(String[] args) {
        // --- Zstd round trip, including exact boundaries ---
        check("zstd level 1 round trip", () -> {
            byte b = CompressionAlgorithm.encodeZstd(1);
            Encoded e = CompressionAlgorithm.decode(b & 0xFF);
            assertEquals(Algorithm.ZSTD, e.algorithm());
            assertEquals(1, e.levelOrQuality());
        });
        check("zstd level 22 round trip", () -> {
            byte b = CompressionAlgorithm.encodeZstd(22);
            Encoded e = CompressionAlgorithm.decode(b & 0xFF);
            assertEquals(Algorithm.ZSTD, e.algorithm());
            assertEquals(22, e.levelOrQuality());
        });
        check("zstd level 0 rejected", () -> assertThrows(() -> CompressionAlgorithm.encodeZstd(0)));
        check("zstd level 23 rejected", () -> assertThrows(() -> CompressionAlgorithm.encodeZstd(23)));

        // --- Brotli round trip, including exact boundaries ---
        check("brotli quality 0 round trip", () -> {
            byte b = CompressionAlgorithm.encodeBrotli(0);
            Encoded e = CompressionAlgorithm.decode(b & 0xFF);
            assertEquals(Algorithm.BROTLI, e.algorithm());
            assertEquals(0, e.levelOrQuality());
        });
        check("brotli quality 11 round trip", () -> {
            byte b = CompressionAlgorithm.encodeBrotli(11);
            Encoded e = CompressionAlgorithm.decode(b & 0xFF);
            assertEquals(Algorithm.BROTLI, e.algorithm());
            assertEquals(11, e.levelOrQuality());
        });
        check("brotli quality -1 rejected", () -> assertThrows(() -> CompressionAlgorithm.encodeBrotli(-1)));
        check("brotli quality 12 rejected", () -> assertThrows(() -> CompressionAlgorithm.encodeBrotli(12)));

        // --- Byte ranges must never collide between the two algorithms ---
        check("no byte value decodes to both algorithms across the full 1-22 / 101-111 ranges", () -> {
            for (int level = 1; level <= 22; level++) {
                Encoded e = CompressionAlgorithm.decode(CompressionAlgorithm.encodeZstd(level) & 0xFF);
                assertEquals(Algorithm.ZSTD, e.algorithm());
            }
            for (int quality = 0; quality <= 11; quality++) {
                Encoded e = CompressionAlgorithm.decode(CompressionAlgorithm.encodeBrotli(quality) & 0xFF);
                assertEquals(Algorithm.BROTLI, e.algorithm());
            }
        });

        // --- Corrupt/unrecognized byte values must be rejected, not silently misread ---
        check("byte value 0 rejected on decode", () -> assertThrows(() -> CompressionAlgorithm.decode(0)));
        check("byte value 50 (gap between ranges) rejected on decode",
                () -> assertThrows(() -> CompressionAlgorithm.decode(50)));
        check("byte value 112 (past brotli's max) rejected on decode",
                () -> assertThrows(() -> CompressionAlgorithm.decode(112)));
        check("byte value 255 (garbage/corruption) rejected on decode",
                () -> assertThrows(() -> CompressionAlgorithm.decode(255)));

        // ===================================================================
        // THE HIERARCHY TESTS - these are the ones that catch the real bug:
        // Brotli must NEVER be treated as "not good enough" just because the
        // target algorithm is Zstd.
        // ===================================================================

        check("THE BUG SCENARIO: brotli11 must be considered already-as-good-as a zstd22 target "
                + "(must NOT be recompressed/downgraded just because target algorithm is zstd)", () -> {
            Encoded brotli11 = CompressionAlgorithm.decode(CompressionAlgorithm.encodeBrotli(11) & 0xFF);
            assertTrue(CompressionAlgorithm.isAlreadyAsGoodAs(brotli11, Algorithm.ZSTD, CompressionAlgorithm.ZSTD_LEVEL),
                    "brotli11 must be treated as already-as-good-as any zstd target - it must NEVER be downgraded");
        });

        check("brotli11 is also already-as-good-as a brotli11 target (no pointless re-recompression)", () -> {
            Encoded brotli11 = CompressionAlgorithm.decode(CompressionAlgorithm.encodeBrotli(11) & 0xFF);
            assertTrue(CompressionAlgorithm.isAlreadyAsGoodAs(brotli11, Algorithm.BROTLI, CompressionAlgorithm.BROTLI_QUALITY),
                    "brotli11 should already be as good as a brotli11 target");
        });

        check("zstd22 is NOT already-as-good-as a brotli11 target (should upgrade zstd -> brotli when asked)", () -> {
            Encoded zstd22 = CompressionAlgorithm.decode(CompressionAlgorithm.encodeZstd(22) & 0xFF);
            assertFalse(CompressionAlgorithm.isAlreadyAsGoodAs(zstd22, Algorithm.BROTLI, CompressionAlgorithm.BROTLI_QUALITY),
                    "zstd22, however good for zstd, must still be upgraded to brotli when brotli is requested");
        });

        check("zstd4 (a fresh live write) is NOT already-as-good-as a zstd22 target (today's normal idle-recompress case)", () -> {
            Encoded zstd4 = CompressionAlgorithm.decode(CompressionAlgorithm.encodeZstd(4) & 0xFF);
            assertFalse(CompressionAlgorithm.isAlreadyAsGoodAs(zstd4, Algorithm.ZSTD, CompressionAlgorithm.ZSTD_LEVEL),
                    "zstd4 should not be considered already-as-good-as a zstd22 target");
        });

        check("zstd4 is NOT already-as-good-as a brotli11 target", () -> {
            Encoded zstd4 = CompressionAlgorithm.decode(CompressionAlgorithm.encodeZstd(4) & 0xFF);
            assertFalse(CompressionAlgorithm.isAlreadyAsGoodAs(zstd4, Algorithm.BROTLI, CompressionAlgorithm.BROTLI_QUALITY),
                    "zstd4 should not be considered already-as-good-as a brotli11 target");
        });

        check("even the lowest brotli quality (0) outranks the highest zstd level (22)", () -> {
            Encoded brotli0 = CompressionAlgorithm.decode(CompressionAlgorithm.encodeBrotli(0) & 0xFF);
            assertTrue(CompressionAlgorithm.isAlreadyAsGoodAs(brotli0, Algorithm.ZSTD, CompressionAlgorithm.ZSTD_LEVEL),
                    "any Brotli quality, even 0, must outrank any Zstd level per the stated hierarchy");
        });

        check("within brotli, quality 11 outranks quality 9 (should still upgrade if not yet at max quality)", () -> {
            Encoded brotli9 = CompressionAlgorithm.decode(CompressionAlgorithm.encodeBrotli(9) & 0xFF);
            assertFalse(CompressionAlgorithm.isAlreadyAsGoodAs(brotli9, Algorithm.BROTLI, CompressionAlgorithm.BROTLI_QUALITY),
                    "brotli9 should still be upgraded to brotli11 if that's the target - not yet at max quality");
        });

        System.out.println();
        if (failures == 0) {
            System.out.println("ALL TESTS PASSED");
        } else {
            System.out.println(failures + " TEST(S) FAILED");
            System.exit(1);
        }
    }

    private interface TestBody {
        void run() throws Exception;
    }

    private static void check(String name, TestBody body) {
        try {
            body.run();
            System.out.println("PASS: " + name);
        } catch (Throwable t) {
            failures++;
            System.out.println("FAIL: " + name + "  -> " + t);
        }
    }

    private static void assertEquals(Object expected, Object actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("expected <" + expected + "> but was <" + actual + ">");
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertFalse(boolean condition, String message) {
        if (condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertThrows(Runnable body) {
        try {
            body.run();
        } catch (Exception expected) {
            return;
        }
        throw new AssertionError("expected an exception but none was thrown");
    }
}