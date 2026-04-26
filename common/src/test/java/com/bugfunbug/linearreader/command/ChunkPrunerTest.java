package com.bugfunbug.linearreader.command;

import com.bugfunbug.linearreader.LinearTestSupport;
import com.bugfunbug.linearreader.linear.LinearRegionFile;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkPrunerTest {

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
    void detectsPruneCandidatesInDryRun() throws IOException {
        Path worldRoot = LinearTestSupport.copyCorpusTree(
                "worlds/prune-candidates",
                tempDir.resolve("prune-candidates")
        );

        ChunkPruner.PruneAnalysis analysis = ChunkPruner.analyzeWorld(worldRoot, null, System.nanoTime());

        assertEquals(2, analysis.scannedRegionFiles());
        assertEquals(2, analysis.candidateChunks());
        assertEquals(6, analysis.scannedPresentChunks());
        assertEquals(1, analysis.regions().size());
        assertEquals(0, analysis.skippedBusyRegions());
        assertEquals(0, analysis.failedRegions());
        assertFalse(analysis.sampleChunks().isEmpty());
        assertEquals("region/r.0.0.linear", analysis.regions().get(0).regionLabel());
        assertEquals(2, analysis.regions().get(0).candidateCount());
    }

    @Test
    void rejectsConfirmIfFilesChangeAfterAnalysis() throws IOException {
        Path worldRoot = LinearTestSupport.copyCorpusTree(
                "worlds/prune-candidates",
                tempDir.resolve("prune-changed")
        );

        ChunkPruner.PruneAnalysis analysis = ChunkPruner.analyzeWorld(worldRoot, null, System.nanoTime());
        Path target = worldRoot.resolve("region/r.0.0.linear");
        long current = Files.getLastModifiedTime(target).toMillis();
        Files.setLastModifiedTime(target, FileTime.fromMillis(current + 2_000L));

        assertFalse(ChunkPruner.validatePlan(analysis));
    }

    @Test
    void prunesOnlySafeChunksOnConfirm() throws IOException {
        Path worldRoot = LinearTestSupport.copyCorpusTree(
                "worlds/prune-candidates",
                tempDir.resolve("prune-apply")
        );
        Path regionPath = worldRoot.resolve("region/r.0.0.linear");

        ChunkPruner.PruneAnalysis analysis = ChunkPruner.analyzeWorld(worldRoot, null, System.nanoTime());
        assertTrue(ChunkPruner.validatePlan(analysis));

        ChunkPruner.PruneExecutionResult result = ChunkPruner.applyPlan(analysis);
        assertEquals(2, result.deletedChunks());
        assertEquals(1, result.changedRegions());
        assertTrue(result.reclaimedBytes() >= 0L);
        assertTrue(LinearRegionFile.verifyOnDisk(regionPath).ok);

        LinearRegionFile region = new LinearRegionFile(regionPath, false);
        try {
            assertFalse(hasChunk(region, new ChunkPos(0, 0)));
            assertFalse(hasChunk(region, new ChunkPos(2, 0)));
            assertTrue(hasChunk(region, new ChunkPos(1, 0)));
            assertTrue(hasChunk(region, new ChunkPos(3, 0)));
            assertTrue(hasChunk(region, new ChunkPos(4, 0)));
        } finally {
            LinearRegionFile.ALL_OPEN.remove(region);
            region.releaseChunkData();
        }
    }

    private static boolean hasChunk(LinearRegionFile region, ChunkPos pos) throws IOException {
        try (var in = region.read(pos)) {
            return in != null;
        }
    }
}
