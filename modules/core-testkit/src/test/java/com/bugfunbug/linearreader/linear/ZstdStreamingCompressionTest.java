package com.bugfunbug.linearreader.linear;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Isolated correctness test for ZstdSupport.compressStream(...), deliberately kept separate
 * from LinearRegionFile so a failure here points directly at the streaming bridge itself,
 * not at anything downstream (backups, pruning, verify, recompression, etc).
 *
 * A first attempt at wiring compressStream() into the live write path caused widespread
 * AssertionFailedErrors across many unrelated tests that all happened to round-trip a
 * region afterward. This test isolates the mechanism with precise byte-level assertions so
 * a real bug (if any) shows up here directly, instead of several layers removed.
 */
class ZstdStreamingCompressionTest {

    @Test
    void streamedCompressionRoundTripsExactlyForALargeCompressibleBuffer() {
        byte[] original = generateRealisticBuffer(20 * 1024 * 1024, 12345L);

        byte[] compressed = compressStreamToArray(original, 4);

        byte[] roundTripped = decompressToArray(compressed, original.length);
        assertArrayEquals(original, roundTripped,
                "streamed compression must decompress back to byte-for-byte identical input");
    }

    @Test
    void streamedCompressionProducesSameOrSmallerOutputThanOneShotAtTheSameLevel() {
        byte[] original = generateRealisticBuffer(20 * 1024 * 1024, 54321L);

        long oneShotLen = compressOneShot(original, 4);
        byte[] streamed = compressStreamToArray(original, 4);

        // Small allowed slack accounts only for fixed per-block frame overhead inherent to
        // ANY multi-call streaming compressor (a few header bytes at block boundaries) - not
        // a real match-finding/ratio regression. Must not meaningfully regress the ratio.
        double allowedSlackFraction = 0.01; // 1%
        long maxAllowed = oneShotLen + Math.max(64L, (long) (oneShotLen * allowedSlackFraction));

        assertTrue(streamed.length <= maxAllowed,
                "streamed output (" + streamed.length + " bytes) should not be meaningfully "
                        + "larger than one-shot output (" + oneShotLen + " bytes)");
    }

    @Test
    void streamingThenOneShotOnTheSameThreadBothRoundTripCorrectly() {
        // Targets a specific suspicion: the thread-local ZstdCompressCtx is shared between
        // compressStream() and the older one-shot compress(). If the streaming finalize
        // leaves the native context in a bad state, a one-shot compress() called right after
        // on the SAME thread would be the first thing to break - exactly the pattern every
        // failing LinearRegionFile-based test showed (write via streaming, then touch the
        // region again via backup/recompress/reread on the same thread).
        byte[] first = generateRealisticBuffer(6 * 1024 * 1024, 111L);
        byte[] second = generateRealisticBuffer(3 * 1024 * 1024, 222L);

        byte[] streamedCompressed = compressStreamToArray(first, 3);
        byte[] streamedRoundTrip = decompressToArray(streamedCompressed, first.length);
        assertArrayEquals(first, streamedRoundTrip,
                "streamed compression must round-trip correctly");

        byte[] oneShotCompressed = compressOneShotToArray(second, 3);
        byte[] oneShotRoundTrip = decompressToArray(oneShotCompressed, second.length);
        assertArrayEquals(second, oneShotRoundTrip,
                "one-shot compress() immediately after a streamed compress() on the same "
                        + "thread must still round-trip correctly - a failure here means the "
                        + "shared native compression context was left in a bad state");
    }

    @Test
    void streamedCompressionHandlesInputSmallerThanOneSlice() {
        // A region touching only a handful of chunks can be far smaller than the streaming
        // slice size - confirm the loop still behaves correctly (a single CONTINUE call, or
        // going straight to END) rather than only ever being exercised on large inputs.
        byte[] original = generateRealisticBuffer(37, 999L); // smaller than any sane slice size

        byte[] compressed = compressStreamToArray(original, 4);
        byte[] roundTripped = decompressToArray(compressed, original.length);
        assertArrayEquals(original, roundTripped,
                "a tiny input (smaller than one slice) must still round-trip correctly");
    }

    @Test
    void streamedCompressionHandlesEmptyInput() {
        byte[] original = new byte[0];

        byte[] compressed = compressStreamToArray(original, 4);
        byte[] roundTripped = decompressToArray(compressed, 0);
        assertEquals(0, roundTripped.length, "an empty input must round-trip to an empty output");
    }

    @Test
    void streamedCompressionStaysCorrectAcrossManyRepeatedCallsOnTheSameThread() {
        // The real write path reuses ONE thread-local native compression context across
        // many sequential region flushes on the same flush thread - never just once. The
        // earlier isolated tests only ever called compressStream() a single time per test
        // method, which passed, but the real integration still failed identically after
        // being re-wired into writeToDisk(). This reproduces the actual usage pattern:
        // repeated compressStream() calls, back-to-back, on the same thread-local context,
        // with varying input sizes (mirroring how region bodies vary flush to flush).
        Random sizePicker = new Random(7777L);
        for (int iteration = 0; iteration < 50; iteration++) {
            int size = 1 + sizePicker.nextInt(6 * 1024 * 1024);
            byte[] original = generateRealisticBuffer(size, 1000L + iteration);

            byte[] compressed = compressStreamToArray(original, 3);
            byte[] roundTripped = decompressToArray(compressed, original.length);

            assertArrayEquals(original, roundTripped,
                    "iteration " + iteration + " (size " + size + " bytes) failed to round-trip "
                            + "correctly - repeated compressStream() calls on the same thread-local "
                            + "context must not corrupt or leak state between calls");
        }
    }

    @Test
    void smallSliceStreamingAtUltraLevelRoundTripsAndKeepsRatio() {
        byte[] original = generateRealisticBuffer(3 * 1024 * 1024, 4242L);
        int maxCompLen = (int) ZstdSupport.compressBound(original.length);

        long oneShotLen = compressOneShot(original, 19);

        byte[] out = new byte[maxCompLen];
        long compLen = ZstdSupport.compressStream(out, 0, maxCompLen, original, 0, original.length, 19, 128 * 1024);
        assertFalse(ZstdSupport.isError(compLen), "small-slice compressStream() reported an error");
        byte[] trimmed = java.util.Arrays.copyOf(out, (int) compLen);

        assertEquals(original.length, ZstdSupport.decompressedSize(trimmed, 0, trimmed.length),
                "frame header must record the content size");
        assertArrayEquals(original, decompressToArray(trimmed, original.length));

        long maxAllowed = oneShotLen + Math.max(64L, (long) (oneShotLen * 0.01));
        assertTrue(compLen <= maxAllowed,
                "small-slice output (" + compLen + ") must not be meaningfully larger than one-shot (" + oneShotLen + ")");
    }

    // -------------------------------------------------------------------------------
    // Helpers - call ZstdSupport's package-private methods directly, mirroring exactly
    // how LinearRegionFile.writeToDisk() / loadFromDisk() already use them.
    // -------------------------------------------------------------------------------

    private static byte[] compressStreamToArray(byte[] src, int level) {
        int maxCompLen = (int) ZstdSupport.compressBound(src.length);
        byte[] out = new byte[maxCompLen];
        long compLen = ZstdSupport.compressStream(out, 0, maxCompLen, src, 0, src.length, level);
        assertFalse(ZstdSupport.isError(compLen), "compressStream() reported an error");
        byte[] trimmed = new byte[(int) compLen];
        System.arraycopy(out, 0, trimmed, 0, (int) compLen);
        if (src.length > 0) {
            assertEquals(src.length, ZstdSupport.decompressedSize(trimmed, 0, trimmed.length),
                    "frame header must record the content size, because the real read path "
                            + "(decompressEncodedLinearFile) sizes its buffer from it");
        }
        return trimmed;
    }

    private static byte[] compressOneShotToArray(byte[] src, int level) {
        int maxCompLen = (int) ZstdSupport.compressBound(src.length);
        byte[] out = new byte[maxCompLen];
        long compLen = ZstdSupport.compress(out, 0, maxCompLen, src, 0, src.length, level);
        assertFalse(ZstdSupport.isError(compLen), "compress() reported an error");
        byte[] trimmed = new byte[(int) compLen];
        System.arraycopy(out, 0, trimmed, 0, (int) compLen);
        return trimmed;
    }

    private static long compressOneShot(byte[] src, int level) {
        int maxCompLen = (int) ZstdSupport.compressBound(src.length);
        byte[] out = new byte[maxCompLen];
        long compLen = ZstdSupport.compress(out, 0, maxCompLen, src, 0, src.length, level);
        assertFalse(ZstdSupport.isError(compLen), "compress() reported an error");
        return compLen;
    }

    private static byte[] decompressToArray(byte[] compressed, int expectedLength) {
        byte[] out = new byte[expectedLength];
        long result = ZstdSupport.decompress(out, 0, out.length, compressed, 0, compressed.length);
        assertFalse(ZstdSupport.isError(result), "decompress() reported an error");
        assertEquals(expectedLength, result, "decompressed length did not match expected length");
        return out;
    }

    private static byte[] generateRealisticBuffer(int size, long seed) {
        // Mixes repeated runs (compressible, similar to NBT's many repeated palette/tag
        // bytes) with pseudo-random noise, so this is neither trivially all-zeros (which
        // could mask certain bugs) nor fully incompressible random data (unrealistic for NBT).
        byte[] data = new byte[size];
        Random random = new Random(seed);
        int i = 0;
        while (i < size) {
            int runLength = Math.min(size - i, 64 + random.nextInt(192));
            if (random.nextBoolean()) {
                byte value = (byte) random.nextInt(256);
                for (int j = 0; j < runLength; j++) {
                    data[i + j] = value;
                }
            } else {
                for (int j = 0; j < runLength; j++) {
                    data[i + j] = (byte) random.nextInt(256);
                }
            }
            i += runLength;
        }
        return data;
    }
}