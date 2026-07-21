package com.bugfunbug.linearreader.command;

import com.bugfunbug.linearreader.LinearTestSupport;
import com.bugfunbug.linearreader.linear.LinearTestData;
import com.bugfunbug.linearreader.linear.LinearRegionFile;
import com.bugfunbug.linearreader.config.LinearConfig;
import com.bugfunbug.linearreader.command.ChunkPruner;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;

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
        LinearConfig.update(4, 256, true, 32, 2048, 30, 60, 4, 60, 4, 16, 500, 1, true, 20, 15, true, 1200, 0);

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
        LinearConfig.update(4, 256, true, 32, 2048, 30, 60, 4, 60, 4, 16, 500, 1, true, 20, 15, true, 1200, 0);

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
        LinearConfig.update(4, 256, true, 32, 2048, 30, 60, 4, 60, 4, 16, 500, 1, true, 20, 15, true, 1200, 0);

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

    @Test
    void prunesFlythroughChunkButProtectsRepeatedlyVisitedChunk() throws IOException {
        // Disable the quiet-region gate so this test isolates the InhabitedTime threshold only.
        LinearConfig.update(4, 256, true, 32, 2048, 30, 60, 4, 60, 4, 16, 500, 1, true, 20, 15, true, 1200, 0);

        Path regionPath = tempDir.resolve("flythrough/region/r.0.0.linear");
        LinearTestData.writeRegion(regionPath, Map.of(
                new ChunkPos(0, 0), LinearTestData.chunkWithInhabitedTime(50, 0, 0),   // one flythrough pass
                new ChunkPos(1, 0), LinearTestData.chunkWithInhabitedTime(5000, 1, 0)  // repeatedly visited (highway)
        ));
        Path worldRoot = tempDir.resolve("flythrough");

        ChunkPruner.PruneAnalysis analysis = ChunkPruner.analyzeWorld(worldRoot, null, System.nanoTime());

        assertEquals(1, analysis.candidateChunks());
        assertTrue(ChunkPruner.validatePlan(analysis));

        ChunkPruner.PruneExecutionResult result = ChunkPruner.applyPlan(analysis);
        assertEquals(1, result.deletedChunks());

        LinearRegionFile region = new LinearRegionFile(regionPath, false);
        try {
            assertFalse(hasChunk(region, new ChunkPos(0, 0)));
            assertTrue(hasChunk(region, new ChunkPos(1, 0)));
        } finally {
            LinearRegionFile.ALL_OPEN.remove(region);
            region.releaseChunkData();
        }
    }

    @Test
    void skipsRegionsModifiedWithinQuietWindow() throws IOException {
        Path regionPath = tempDir.resolve("quiet-window/region/r.0.0.linear");
        // InhabitedTime = 0 would be prunable under the old rule too, so a candidateChunks() == 0
        // result here can only be explained by the quiet-region gate, not the threshold change.
        LinearTestData.writeRegion(regionPath, Map.of(
                new ChunkPos(0, 0), LinearTestData.chunkWithInhabitedTime(0, 0, 0)
        ));
        Path worldRoot = tempDir.resolve("quiet-window");

        // Default config (set by LinearTestSupport.resetState()) has a 12h quiet window,
        // and the region file was just written, so it should be skipped, not scanned.
        ChunkPruner.PruneAnalysis analysis = ChunkPruner.analyzeWorld(worldRoot, null, System.nanoTime());

        assertEquals(0, analysis.candidateChunks());
        assertEquals(1, analysis.skippedBusyRegions());
    }

    private static boolean hasChunk(LinearRegionFile region, ChunkPos pos) throws IOException {
        try (var in = region.read(pos)) {
            return in != null;
        }
    }
}
