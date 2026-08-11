package com.bugfunbug.linearreader.linear;

import com.bugfunbug.linearreader.LinearTestSupport;
import com.bugfunbug.linearreader.config.LinearConfig;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the fix for a real bug found during manual testing: switching
 * backupCompressionAlgorithm in config had no way to actually take effect on
 * an existing backup, since StoragePolicyManager.shouldRefreshBackup's normal
 * thresholds only look at how much chunk data changed - a pure config change
 * isn't a chunk change, so an old backup would otherwise sit at the wrong
 * algorithm forever. See LinearRegionFile.backupAlgorithmMismatchesConfig().
 */
class BackupAlgorithmSwitchTest {

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
    void switchingBackupAlgorithmForcesRefreshOnTheNextRealFlush() throws IOException {
        Path live = tempDir.resolve("region/r.0.0.linear");
        Files.createDirectories(live.getParent());

        LinearRegionFile region = new LinearRegionFile(live, false);
        try {
            ChunkPos pos = new ChunkPos(0, 0);
            try (DataOutputStream out = region.write(pos)) {
                NbtIo.write(LinearTestData.simpleChunk("backup-switch", 0, 0), out);
            }
            region.flush(true);
            LinearTestData.awaitBackupTasks();

            Path backup = live.getParent().resolve("backups/r.0.0.linear.bak");
            assertTrue(Files.exists(backup));
            LinearRegionFile.EncodedLinearFile beforeSwitch = LinearRegionFile.readEncodedLinearFile(backup);
            assertEquals(CompressionAlgorithm.Algorithm.ZSTD,
                    CompressionAlgorithm.decode(beforeSwitch.compressionLevel & 0xFF).algorithm());

            // Flip ONLY the backup algorithm - same test defaults
            // LinearTestSupport.resetState() itself uses, matching the existing
            // pattern other tests (e.g. ChunkPrunerTest) already use for testing
            // one specific config value in isolation.
            LinearConfig.update(4, 256, true, 32, 2048, 30, 60, 4, 60, 4, 16, 500, 1, true, 20, 15, true, 1200, 12,
                    "zstd", "brotli");

            // A pure config change with zero new writes would never even reach the
            // backup-decision logic - LinearRegionFile.flush() bails out immediately
            // if the region isn't dirty. A real write is needed to make the region
            // dirty again, exactly matching how a backup actually gets refreshed in
            // production (on the region's NEXT real flush, not retroactively on an
            // already-idle file).
            try (DataOutputStream out = region.write(pos)) {
                NbtIo.write(LinearTestData.simpleChunk("backup-switch", 0, 0), out);
            }
            region.flush(true);
            LinearTestData.awaitBackupTasks();

            LinearRegionFile.EncodedLinearFile afterSwitch = LinearRegionFile.readEncodedLinearFile(backup);
            assertEquals(CompressionAlgorithm.Algorithm.BROTLI,
                    CompressionAlgorithm.decode(afterSwitch.compressionLevel & 0xFF).algorithm(),
                    "backup must be refreshed into the newly-configured algorithm on the next real flush");
        } finally {
            LinearRegionFile.ALL_OPEN.remove(region);
            region.releaseChunkData();
        }
    }
}