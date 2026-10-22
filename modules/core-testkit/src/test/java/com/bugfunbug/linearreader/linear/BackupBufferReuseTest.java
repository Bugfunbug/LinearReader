package com.bugfunbug.linearreader.linear;

import com.bugfunbug.linearreader.LinearTestSupport;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Backups made with the per-thread reusable buffers must be byte-identical to backups made with
 * freshly allocated buffers, including when a small region follows a large one on the same thread
 * (the classic buffer-reuse bug is stale bytes from the earlier, larger region leaking through).
 */
class BackupBufferReuseTest {

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
    void reusedBuffersMatchFreshBuffersAcrossAlternatingRegionSizes() throws IOException, InterruptedException {
        Path large = tempDir.resolve("large/r.0.0.linear");
        Path tiny = tempDir.resolve("tiny/r.0.0.linear");

        Map<ChunkPos, CompoundTag> largeChunks = new LinkedHashMap<>();
        for (int i = 0; i < 48; i++) {
            largeChunks.put(new ChunkPos(i % 32, i / 32),
                    chunkWithPayload("large-" + i, i % 32, i / 32, 20_000, 100L + i));
        }
        LinearTestData.writeRegion(large, largeChunks);
        LinearTestData.writeRegion(tiny,
                Map.of(new ChunkPos(0, 0), LinearTestData.simpleChunk("tiny", 0, 0)));

        Path[] sources = {large, tiny, large, tiny};
        for (int round = 0; round < sources.length; round++) {
            Path source = sources[round];
            Path reused = tempDir.resolve("reused-" + round + "/r.0.0.linear.bak");
            Path fresh = tempDir.resolve("fresh-" + round + "/r.0.0.linear.bak");

            IdleRecompressor.RecompressResult withReuse = IdleRecompressor.recompressFileTo(
                    source, reused, CompressionAlgorithm.Algorithm.ZSTD, CompressionAlgorithm.ZSTD_LEVEL, true);
            IdleRecompressor.RecompressResult withoutReuse = IdleRecompressor.recompressFileTo(
                    source, fresh, CompressionAlgorithm.Algorithm.ZSTD, CompressionAlgorithm.ZSTD_LEVEL, false);

            assertEquals(IdleRecompressor.RecompressOutcome.UPGRADED, withReuse.outcome(), "round " + round);
            assertEquals(IdleRecompressor.RecompressOutcome.UPGRADED, withoutReuse.outcome(), "round " + round);
            assertTrue(LinearRegionFile.verifyOnDisk(reused).ok, "round " + round + ": reused-buffer backup must verify");

            assertArrayEquals(Files.readAllBytes(fresh), Files.readAllBytes(reused),
                    "round " + round + ": reused-buffer backup must be byte-identical to a fresh-buffer one");
            assertArrayEquals(decompressedBody(source), decompressedBody(reused),
                    "round " + round + ": backup must decompress to exactly the source region body");
        }
    }

    private static byte[] decompressedBody(Path file) throws IOException {
        LinearRegionFile.EncodedLinearFile encoded = LinearRegionFile.readEncodedLinearFile(file);
        return LinearRegionFile.decompressEncodedLinearFile(file, encoded).bytes;
    }

    private static CompoundTag chunkWithPayload(String kind, int x, int z, int payloadChars, long seed) {
        CompoundTag tag = LinearTestData.simpleChunk(kind, x, z);
        Random random = new Random(seed);
        StringBuilder sb = new StringBuilder(payloadChars);
        for (int i = 0; i < payloadChars; i++) {
            sb.append((char) ('a' + random.nextInt(26)));
        }
        tag.putString("payload", sb.toString());
        return tag;
    }
}