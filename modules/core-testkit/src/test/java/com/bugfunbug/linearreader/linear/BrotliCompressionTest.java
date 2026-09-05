package com.bugfunbug.linearreader.linear;

import com.bugfunbug.linearreader.LinearTestSupport;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the real Brotli compression path end-to-end - the one thing no
 * earlier verification (the standalone CompressionAlgorithm tests, or manual
 * in-game testing) could actually prove: that the real reflective Brotli4j
 * bridge (BrotliSupport) round-trips real chunk data correctly through the
 * exact same code every production caller (IdleRecompressor, the manual
 * afk-compress command, backups) goes through - not just that the header-byte
 * bookkeeping around it is correct in isolation.
 */
class BrotliCompressionTest {

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
    void brotliRecompressedRegionReadsBackIdentically() throws IOException {
        Path file = tempDir.resolve("r.0.0.linear");
        CompoundTag chunkA = LinearTestData.simpleChunk("brotli-roundtrip-a", 0, 0);
        CompoundTag chunkB = LinearTestData.entityChunk(1, 0);
        LinearTestData.writeRegion(file, Map.of(
                new ChunkPos(0, 0), chunkA,
                new ChunkPos(1, 0), chunkB
        ));

        // Sanity check: every live write is always Zstd, regardless of what
        // this test is about to do to it.
        LinearRegionFile.EncodedLinearFile beforeEncoded = LinearRegionFile.readEncodedLinearFile(file);
        CompressionAlgorithm.Encoded before = CompressionAlgorithm.decode(beforeEncoded.compressionLevel & 0xFF);
        assertEquals(CompressionAlgorithm.Algorithm.ZSTD, before.algorithm());

        // Recompress to Brotli exactly the way IdleRecompressor does for real -
        // real BrotliSupport.compress() call, real header write, real CRC/size
        // bookkeeping.
        IdleRecompressor.RecompressResult result = IdleRecompressor.recompressFileTo(
                file, file, CompressionAlgorithm.Algorithm.BROTLI, CompressionAlgorithm.BROTLI_QUALITY);
        assertEquals(IdleRecompressor.RecompressOutcome.UPGRADED, result.outcome());

        // Confirm the file is now genuinely Brotli-encoded, not just "didn't crash".
        LinearRegionFile.EncodedLinearFile afterEncoded = LinearRegionFile.readEncodedLinearFile(file);
        CompressionAlgorithm.Encoded after = CompressionAlgorithm.decode(afterEncoded.compressionLevel & 0xFF);
        assertEquals(CompressionAlgorithm.Algorithm.BROTLI, after.algorithm());
        assertEquals(CompressionAlgorithm.BROTLI_QUALITY, after.levelOrQuality());

        // Read every chunk back through the REAL read path (the same one chunk
        // loading, the exporter, Voxy compat, and c2me all go through) and
        // confirm exact NBT equality against what was originally written.
        LinearRegionFile region = new LinearRegionFile(file, false);
        try {
            assertEquals(chunkA, readChunk(region, new ChunkPos(0, 0)));
            assertEquals(chunkB, readChunk(region, new ChunkPos(1, 0)));
        } finally {
            LinearRegionFile.ALL_OPEN.remove(region);
            region.releaseChunkData();
        }

        // The same check /linearreader verify uses must also pass.
        assertTrue(LinearRegionFile.verifyOnDisk(file).ok);
    }

    /**
     * The exact bug caught during manual review, now locked in as a
     * regression test: a Brotli-11 ("king tier") region must never be
     * silently downgraded back to Zstd just because a config value points at
     * Zstd. Checks BOTH entry points IdleRecompressor exposes, since
     * recompressFile (used by the real scanning loop) and recompressFileTo
     * (used directly by backups) each have their own "already optimal" gate.
     */
    @Test
    void brotli11FileResistsZstdTargetedRecompression() throws IOException, InterruptedException {
        Path file = tempDir.resolve("r.0.0.linear");
        LinearTestData.writeRegion(file, Map.of(
                new ChunkPos(0, 0), LinearTestData.simpleChunk("hierarchy-test", 0, 0),
                new ChunkPos(1, 0), LinearTestData.entityChunk(1, 0)
        ));

        IdleRecompressor.RecompressResult upgraded = IdleRecompressor.recompressFileTo(
                file, file, CompressionAlgorithm.Algorithm.BROTLI, CompressionAlgorithm.BROTLI_QUALITY);
        assertEquals(IdleRecompressor.RecompressOutcome.UPGRADED, upgraded.outcome());
        byte[] brotliBytes = Files.readAllBytes(file);

        // Attempt to recompress DOWN to Zstd via recompressFileTo - must be refused.
        IdleRecompressor.RecompressResult viaRecompressFileTo = IdleRecompressor.recompressFileTo(
                file, file, CompressionAlgorithm.Algorithm.ZSTD, CompressionAlgorithm.ZSTD_LEVEL);
        assertEquals(IdleRecompressor.RecompressOutcome.ALREADY_OPTIMAL, viaRecompressFileTo.outcome());

        // Attempt again via recompressFile (the OTHER entry point, used by the
        // real per-file scanning loop) - must ALSO refuse, independently.
        IdleRecompressor.RecompressResult viaRecompressFile = IdleRecompressor.recompressFile(
                file, CompressionAlgorithm.Algorithm.ZSTD, CompressionAlgorithm.ZSTD_LEVEL);
        assertEquals(IdleRecompressor.RecompressOutcome.ALREADY_OPTIMAL, viaRecompressFile.outcome());

        // The file on disk must be byte-for-byte untouched by either attempt.
        byte[] afterBytes = Files.readAllBytes(file);
        assertArrayEquals(brotliBytes, afterBytes,
                "a Brotli-11 file must never be downgraded to Zstd by the recompressor");

        LinearRegionFile.EncodedLinearFile encoded = LinearRegionFile.readEncodedLinearFile(file);
        CompressionAlgorithm.Encoded decoded = CompressionAlgorithm.decode(encoded.compressionLevel & 0xFF);
        assertEquals(CompressionAlgorithm.Algorithm.BROTLI, decoded.algorithm());
        assertEquals(CompressionAlgorithm.BROTLI_QUALITY, decoded.levelOrQuality());
    }

    private static CompoundTag readChunk(LinearRegionFile region, ChunkPos pos) throws IOException {
        try (DataInputStream in = region.read(pos)) {
            assertNotNull(in);
            CompoundTag tag = NbtIo.read(in);
            assertNotNull(tag);
            return tag;
        }
    }
}
