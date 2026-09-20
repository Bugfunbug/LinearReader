package com.bugfunbug.linearreader.linear;

import com.bugfunbug.linearreader.LinearTestSupport;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class LinearRegionFileDeepVerifyTest {

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
    void deepVerifyCatchesCorruptChunkNbtThatShallowVerifyMisses() throws IOException {
        Path file = tempDir.resolve("r.0.0.linear");

        // One valid chunk, written the normal way.
        LinearTestData.writeRegion(file, Map.of(
                new ChunkPos(0, 0), LinearTestData.simpleChunk("valid", 0, 0)
        ));

        // Now reopen the same region and stuff garbage (non-NBT) bytes directly
        // into a second chunk slot, bypassing NbtIo entirely. This is still a
        // perfectly well-formed .linear file (correct CRC, correct chunk-size
        // table) - it's just that slot's *payload* that is nonsense.
        LinearRegionFile region = new LinearRegionFile(file, false);
        try {
            try (DataOutputStream out = region.write(new ChunkPos(1, 0))) {
                out.write(new byte[]{0x00, 0x01, 0x02, 0x03, 0x04});
            }
            region.flush(false);
        } finally {
            LinearRegionFile.ALL_OPEN.remove(region);
            region.releaseChunkData();
        }

        // Shallow verify only checks the container (signatures, CRC, chunk-size
        // table) - it has no way to know a chunk's payload isn't valid NBT, so
        // it must report this file as OK.
        LinearRegionFile.VerifyResult shallow = LinearRegionFile.verifyOnDisk(file);
        assertTrue(shallow.ok, "shallow verify should not detect per-chunk NBT corruption");
        assertTrue(shallow.hasCRC);

        // Deep verify actually parses every chunk's NBT and must catch it.
        LinearRegionFile.VerifyResult deep = LinearRegionFile.verifyOnDisk(file, true);
        assertFalse(deep.ok, "deep verify must detect the corrupt chunk");
        assertEquals(1, deep.corruptChunkCount);
        assertTrue(deep.reason.contains("chunk"));
    }

    @Test
    void deepVerifyReportsZeroCorruptChunksForAFullyValidRegion() throws IOException {
        Path file = tempDir.resolve("r.0.0.linear");

        LinearTestData.writeRegion(file, Map.of(
                new ChunkPos(0, 0), LinearTestData.simpleChunk("a", 0, 0),
                new ChunkPos(1, 0), LinearTestData.entityChunk(1, 0)
        ));

        LinearRegionFile.VerifyResult deep = LinearRegionFile.verifyOnDisk(file, true);
        assertTrue(deep.ok);
        assertEquals(0, deep.corruptChunkCount);
    }

    @Test
    void nonDeepOverloadStillDefaultsToShallowBehavior() throws IOException {
        Path file = tempDir.resolve("r.0.0.linear");
        LinearTestData.writeRegion(file, Map.of(
                new ChunkPos(0, 0), LinearTestData.simpleChunk("a", 0, 0)
        ));

        LinearRegionFile.VerifyResult result = LinearRegionFile.verifyOnDisk(file);
        assertTrue(result.ok);
        // corruptChunkCount == -1 signals "deep check was never requested",
        // distinguishing it from "deep check ran and found zero corrupt chunks".
        assertEquals(-1, result.corruptChunkCount);
    }
}