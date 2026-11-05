package com.bugfunbug.linearreader.linear;

import com.bugfunbug.linearreader.LinearTestSupport;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * If streaming compression can't work on a system (e.g. the launcher forces an older zstd native library),
 * compressStream() must quietly fall back to one-shot compression, stay correct, and be reversible.
 */
class ZstdStreamingFallbackTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        LinearTestSupport.resetState();
    }

    @AfterEach
    void tearDown() {
        LinearTestSupport.resetState();
    }

    @Test
    void healthyRuntimePassesStartupSelfTestWithStreamingEnabled() {
        ZstdSupport.ensureAvailable();
        assertTrue(ZstdSupport.isStreamingSupported());
    }

    @Test
    void linkFailureFallsBackToOneShotAndOutputStaysCorrect() {
        ZstdSupport.setTestStreamingFailure(new UnsatisfiedLinkError("simulated missing native function"));
        byte[] source = sampleData(300_000);
        byte[] dst = new byte[(int) ZstdSupport.compressBound(source.length)];

        long written = ZstdSupport.compressStream(dst, 0, dst.length, source, 0, source.length, 4);

        assertFalse(ZstdSupport.isStreamingSupported());
        assertRoundTrips(source, dst, (int) written);
    }

    @Test
    void fallbackIsReversible() {
        ZstdSupport.setTestStreamingFailure(new UnsatisfiedLinkError("simulated"));
        byte[] source = sampleData(300_000);
        byte[] dst = new byte[(int) ZstdSupport.compressBound(source.length)];
        ZstdSupport.compressStream(dst, 0, dst.length, source, 0, source.length, 4);
        assertFalse(ZstdSupport.isStreamingSupported());

        // What a game restart effectively does: forget the failure and re-check.
        ZstdSupport.clearTestStreamingFailure();
        assertTrue(ZstdSupport.isStreamingSupported());

        long written = ZstdSupport.compressStream(dst, 0, dst.length, source, 0, source.length, 4);
        assertTrue(ZstdSupport.isStreamingSupported());
        assertRoundTrips(source, dst, (int) written);
    }

    @Test
    void unrelatedStreamingErrorsAreNotSwallowed() {
        ZstdSupport.setTestStreamingFailure(new IllegalArgumentException("simulated bad input"));
        byte[] source = sampleData(100_000);
        byte[] dst = new byte[(int) ZstdSupport.compressBound(source.length)];

        assertThrows(IllegalStateException.class,
                () -> ZstdSupport.compressStream(dst, 0, dst.length, source, 0, source.length, 4));
        assertTrue(ZstdSupport.isStreamingSupported());
    }

    @Test
    void backupsUseFastLevelAndInPlaceRecompressionIsSkippedWhenStreamingUnavailable() throws IOException {
        ZstdSupport.setTestStreamingFailure(new UnsatisfiedLinkError("simulated"));
        Path live = tempDir.resolve("region/r.0.0.linear");
        // This write goes through the fallback, which is also what switches streaming off.
        LinearTestData.writeRegion(live,
                Map.of(new ChunkPos(0, 0), LinearTestData.simpleChunk("fallback", 0, 0)));
        assertFalse(ZstdSupport.isStreamingSupported());

        Path backup = tempDir.resolve("region/backups/r.0.0.linear.bak");
        IdleRecompressor.RecompressResult backupResult = IdleRecompressor.recompressFileTo(
                live, backup, CompressionAlgorithm.Algorithm.ZSTD, CompressionAlgorithm.ZSTD_LEVEL, false);
        assertEquals(IdleRecompressor.RecompressOutcome.UPGRADED, backupResult.outcome());
        CompressionAlgorithm.Encoded written = CompressionAlgorithm.decode(
                LinearRegionFile.readEncodedLinearFile(backup).compressionLevel & 0xFF);
        assertEquals(ZstdSupport.FALLBACK_ONE_SHOT_MAX_LEVEL, written.levelOrQuality());
        assertTrue(LinearRegionFile.verifyOnDisk(backup).ok);

        byte[] before = Files.readAllBytes(live);
        IdleRecompressor.RecompressResult inPlace = IdleRecompressor.recompressFileTo(
                live, live, CompressionAlgorithm.Algorithm.ZSTD, CompressionAlgorithm.ZSTD_LEVEL, false);
        assertEquals(IdleRecompressor.RecompressOutcome.NO_SIZE_GAIN, inPlace.outcome());
        assertArrayEquals(before, Files.readAllBytes(live));
    }

    private static void assertRoundTrips(byte[] source, byte[] compressed, int compressedLen) {
        assertEquals(source.length, ZstdSupport.decompressedSize(compressed, 0, compressedLen));
        byte[] restored = new byte[source.length];
        long restoredLen = ZstdSupport.decompress(restored, 0, restored.length, compressed, 0, compressedLen);
        assertEquals(source.length, restoredLen);
        assertArrayEquals(source, restored);
    }

    private static byte[] sampleData(int size) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) {
            data[i] = (byte) ((i * 31 + (i >> 7)) & 0x3F);
        }
        return data;
    }
}