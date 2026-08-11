package com.bugfunbug.linearreader;

import com.bugfunbug.linearreader.config.LinearConfig;
import com.bugfunbug.linearreader.linear.CompressionAlgorithm;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class StoragePolicyManagerTest {

    @BeforeEach
    void setUp() {
        LinearTestSupport.resetState();
    }

    @AfterEach
    void tearDown() {
        StoragePolicyManager.setTestNowNs(null);
        LinearTestSupport.resetState();
    }

    @Test
    void enablesMaintenanceAfterSustainedQuietPeriod() {
        StoragePolicyManager.reset(true);

        long startNs = 1_000_000_000L;
        Path region = Path.of("/tmp/r.0.0.linear");

        StoragePolicyManager.setTestNowNs(startNs);
        StoragePolicyManager.recordChunkRead(region);
        StoragePolicyManager.onServerTick(0, 0);

        int quietTicks = (12 * 60 * 1_000) / 50;
        for (int tick = 1; tick <= quietTicks; tick++) {
            StoragePolicyManager.setTestNowNs(startNs + tick * 50_000_000L);
            StoragePolicyManager.onServerTick(0, 0);
        }

        StoragePolicyManager.PolicySnapshot snapshot = StoragePolicyManager.snapshotForTests();
        assertTrue(snapshot.quietnessScore() >= 0.75D);
        assertTrue(snapshot.maintenanceAllowed());
    }

    @Test
    void lowersCompressionAndRaisesFlushBudgetUnderSustainedBacklog() {
        StoragePolicyManager.reset(true);

        long startNs = 1_000_000_000L;
        StoragePolicyManager.setTestNowNs(startNs);
        StoragePolicyManager.onServerTick(0, 0);

        for (int tick = 1; tick <= 320; tick++) {
            StoragePolicyManager.setTestNowNs(startNs + tick * 50_000_000L);
            StoragePolicyManager.onServerTick(20, 0);
        }

        StoragePolicyManager.PolicySnapshot snapshot = StoragePolicyManager.snapshotForTests();
        assertTrue(StoragePolicyManager.currentCompressionLevel() < LinearConfig.getCompressionLevel());
        assertTrue(StoragePolicyManager.flushBudgetPerTick() > LinearConfig.getRegionsPerSaveTick());
        assertTrue(snapshot.quietnessScore() < 0.50D);
    }

    @Test
    void prefersColdStableRegionsForRecompression() {
        StoragePolicyManager.reset(true);

        long startNs = 1_000_000_000L;
        long currentNs = startNs + 30L * 60L * 1_000_000_000L;
        Path coldRegion = Path.of("/tmp/r.0.0.linear");
        Path hotRegion = Path.of("/tmp/r.1.0.linear");

        StoragePolicyManager.setTestNowNs(startNs);
        StoragePolicyManager.recordChunkWrite(coldRegion, 4096);

        StoragePolicyManager.setTestNowNs(currentNs - 60_000_000_000L);
        StoragePolicyManager.recordChunkWrite(hotRegion, 4096);

        StoragePolicyManager.setTestNowNs(currentNs);
        double coldPriority = StoragePolicyManager.recompressPriority(coldRegion);
        double hotPriority = StoragePolicyManager.recompressPriority(hotRegion);

        assertTrue(coldPriority > hotPriority);
    }

    @Test
    void centralizesBackgroundAndPressureFlushPolicy() {
        StoragePolicyManager.reset(true);

        long startNs = 1_000_000_000L;
        StoragePolicyManager.setTestNowNs(startNs);
        StoragePolicyManager.onServerTick(0, 0);

        int quietTicks = (6 * 60 * 1_000) / 50;
        for (int tick = 1; tick <= quietTicks; tick++) {
            StoragePolicyManager.setTestNowNs(startNs + tick * 50_000_000L);
            StoragePolicyManager.onServerTick(0, 0);
        }

        long quietNowNs = startNs + quietTicks * 50_000_000L;
        assertTrue(StoragePolicyManager.shouldQueueBackgroundFlush(
                true, false, quietNowNs - 20_000_000_000L, 0L, quietNowNs
        ));
        assertFalse(StoragePolicyManager.shouldConsiderPressureFlush(
                true, false, quietNowNs - 20_000_000_000L, 0L, quietNowNs
        ));

        long pressureStartNs = quietNowNs + 50_000_000L;
        StoragePolicyManager.setTestNowNs(pressureStartNs);
        StoragePolicyManager.onServerTick(0, 0);
        for (int tick = 1; tick <= 320; tick++) {
            StoragePolicyManager.setTestNowNs(pressureStartNs + tick * 50_000_000L);
            StoragePolicyManager.onServerTick(20, 0);
        }

        long pressureNowNs = pressureStartNs + 320L * 50_000_000L;
        assertFalse(StoragePolicyManager.shouldQueueBackgroundFlush(
                true, false, pressureNowNs - 20_000_000_000L, 0L, pressureNowNs
        ));
        assertTrue(StoragePolicyManager.shouldConsiderPressureFlush(
                true, false, pressureNowNs - 4_000_000_000L, 0L, pressureNowNs
        ));
        assertTrue(StoragePolicyManager.pressureFlushDirtyRegionLimit(256, 20, 0)
                < LinearConfig.getPressureFlushMaxDirtyRegions());
    }

    @Test
    void rateLimitsPanicFlushAttempts() {
        long startNs = 1_000_000_000L;

        StoragePolicyManager.reset(true);
        StoragePolicyManager.setTestNowNs(startNs);
        StoragePolicyManager.onServerTick(0, 0);

        // First attempt at any time is always allowed.
        assertTrue(StoragePolicyManager.shouldAttemptPanicFlush(startNs));

        // Immediately after, still within the 250ms window - blocked.
        assertFalse(StoragePolicyManager.shouldAttemptPanicFlush(startNs + 100_000_000L));
        assertFalse(StoragePolicyManager.shouldAttemptPanicFlush(startNs + 249_000_000L));

        // Once the window has elapsed, eligible again.
        assertTrue(StoragePolicyManager.shouldAttemptPanicFlush(startNs + 250_000_000L));

        // And it immediately re-blocks after that success.
        assertFalse(StoragePolicyManager.shouldAttemptPanicFlush(startNs + 300_000_000L));
    }

    @Test
    void tracksAndRepaysMaintenanceDebtForLowCompressionRegions() {
        StoragePolicyManager.reset(true);

        long startNs = 1_000_000_000L;
        Path region = Path.of("/tmp/r.2.0.linear");

        StoragePolicyManager.setTestNowNs(startNs);
        StoragePolicyManager.onServerTick(0, 0);

        StoragePolicyManager.setTestNowNs(startNs + 50_000_000L);
        StoragePolicyManager.recordRegionFlush(region, 120_000_000L, 4L * 1024L * 1024L,
                1L * 1024L * 1024L, 2);
        StoragePolicyManager.onServerTick(0, 0);

        double debtAfterLowCompressionFlush = StoragePolicyManager.maintenanceDebtScore();
        double priorityBeforeRepay = StoragePolicyManager.recompressPriority(region);

        StoragePolicyManager.setTestNowNs(startNs + 100_000_000L);
        StoragePolicyManager.recordRegionRecompressed(region, 22, 512_000L);
        StoragePolicyManager.onServerTick(0, 0);

        double debtAfterRepay = StoragePolicyManager.maintenanceDebtScore();
        double priorityAfterRepay = StoragePolicyManager.recompressPriority(region);

        assertTrue(debtAfterLowCompressionFlush > 0.0D);
        assertTrue(debtAfterRepay < debtAfterLowCompressionFlush);
        assertTrue(priorityBeforeRepay > priorityAfterRepay);
    }

    @Test
    void usesDebtAwareMaintenanceBudgetAndBackupRefreshGate() {
        long startNs = 1_000_000_000L;
        Path region = Path.of("/tmp/r.3.0.linear");

        StoragePolicyManager.setTestNowNs(startNs);
        StoragePolicyManager.reset(true);
        StoragePolicyManager.onServerTick(0, 0);

        StoragePolicyManager.setTestNowNs(startNs + 50_000_000L);
        StoragePolicyManager.recordRegionFlush(region, 100_000_000L, 4L * 1024L * 1024L,
                1L * 1024L * 1024L, 2);
        StoragePolicyManager.onServerTick(0, 0);

        int quietTicks = (8 * 60 * 1_000) / 50;
        for (int tick = 1; tick <= quietTicks; tick++) {
            StoragePolicyManager.setTestNowNs(startNs + 50_000_000L + tick * 50_000_000L);
            StoragePolicyManager.onServerTick(0, 0);
        }

        assertTrue(StoragePolicyManager.maintenanceBudgetFiles() > 0);
        assertTrue(StoragePolicyManager.shouldScheduleBackupRefresh(0.5D));

        long pressureStartNs = startNs + 60_000_000_000L;
        StoragePolicyManager.setTestNowNs(pressureStartNs);
        StoragePolicyManager.onServerTick(0, 0);
        for (int tick = 1; tick <= 320; tick++) {
            StoragePolicyManager.setTestNowNs(pressureStartNs + tick * 50_000_000L);
            StoragePolicyManager.onServerTick(20, 0);
        }

        assertTrue(StoragePolicyManager.maintenanceBudgetFiles() == 0);
        assertFalse(StoragePolicyManager.shouldScheduleBackupRefresh(0.5D));
        assertTrue(StoragePolicyManager.shouldScheduleBackupRefresh(3.0D));
    }

    @Test
    void centralizesBackupRefreshEligibility() {
        long startNs = 1_000_000_000L;
        long quietThresholdNs = (long) LinearConfig.getBackupQuietSeconds() * 1_000_000_000L;

        StoragePolicyManager.setTestNowNs(startNs);
        StoragePolicyManager.reset(true);
        StoragePolicyManager.onServerTick(0, 0);

        int quietTicks = (8 * 60 * 1_000) / 50;
        for (int tick = 1; tick <= quietTicks; tick++) {
            StoragePolicyManager.setTestNowNs(startNs + tick * 50_000_000L);
            StoragePolicyManager.onServerTick(0, 0);
        }

        long quietNowNs = startNs + quietTicks * 50_000_000L;
        assertTrue(StoragePolicyManager.shouldRefreshBackup(
                true,
                false,
                LinearConfig.getBackupMinChangedChunks(),
                0L,
                System.currentTimeMillis(),
                quietNowNs - quietThresholdNs - 1L,
                quietNowNs
        ));

        long pressureStartNs = quietNowNs + 50_000_000L;
        StoragePolicyManager.setTestNowNs(pressureStartNs);
        StoragePolicyManager.onServerTick(0, 0);
        for (int tick = 1; tick <= 320; tick++) {
            StoragePolicyManager.setTestNowNs(pressureStartNs + tick * 50_000_000L);
            StoragePolicyManager.onServerTick(20, 0);
        }

        long pressureNowNs = pressureStartNs + 320L * 50_000_000L;
        assertFalse(StoragePolicyManager.shouldRefreshBackup(
                true,
                false,
                LinearConfig.getBackupMinChangedChunks(),
                0L,
                System.currentTimeMillis(),
                pressureNowNs - quietThresholdNs - 1L,
                pressureNowNs
        ));
    }

    @Test
    void centralizesResidentTrimPolicy() {
        StoragePolicyManager.reset(true);

        long startNs = 1_000_000_000L;
        Path coldRegion = Path.of("/tmp/r.4.0.linear");
        Path hotRegion = Path.of("/tmp/r.5.0.linear");

        assertFalse(StoragePolicyManager.shouldStartResidentTrim(
                StoragePolicyManager.residentBudgetBytes() - 1L,
                Long.MAX_VALUE,
                startNs
        ));
        assertTrue(StoragePolicyManager.shouldStartResidentTrim(
                StoragePolicyManager.residentBudgetBytes() + 1L,
                Long.MAX_VALUE,
                startNs + 3_000_000_000L
        ));
        assertFalse(StoragePolicyManager.shouldStartResidentTrim(
                StoragePolicyManager.residentBudgetBytes() + 1L,
                Long.MAX_VALUE,
                startNs + 4_000_000_000L
        ));
        assertTrue(StoragePolicyManager.shouldStartResidentTrim(
                0L,
                StoragePolicyManager.minHeapHeadroomBytes() - 1L,
                startNs + 4_000_000_001L
        ));

        StoragePolicyManager.setTestNowNs(startNs);
        StoragePolicyManager.recordChunkWrite(hotRegion, 4096);
        long nowNs = startNs + 120_000_000_000L;
        double coldPriority = StoragePolicyManager.residentTrimPriority(
                coldRegion,
                startNs,
                64L * 1024L * 1024L,
                nowNs
        );
        double hotPriority = StoragePolicyManager.residentTrimPriority(
                hotRegion,
                startNs,
                64L * 1024L * 1024L,
                nowNs
        );

        assertTrue(coldPriority > hotPriority);
        assertTrue(StoragePolicyManager.shouldTrimResidentRegion(
                coldRegion,
                true,
                false,
                false,
                false,
                startNs,
                nowNs
        ));
        assertFalse(StoragePolicyManager.shouldTrimResidentRegion(
                coldRegion,
                true,
                false,
                false,
                true,
                startNs,
                nowNs
        ));
    }

    @Test
    void tracksChronicLowLoadProfile() {
        long startNs = 1_000_000_000L;

        StoragePolicyManager.setTestNowNs(startNs);
        StoragePolicyManager.reset(true);
        StoragePolicyManager.onServerTick(0, 0);

        int quietTicks = (30 * 60 * 1_000) / 50;
        for (int tick = 1; tick <= quietTicks; tick++) {
            StoragePolicyManager.setTestNowNs(startNs + tick * 50_000_000L);
            StoragePolicyManager.onServerTick(0, 0);
        }

        StoragePolicyManager.PolicyDebugSnapshot snapshot = StoragePolicyManager.debugSnapshot();
        assertTrue("chronic-low-load".equals(snapshot.loadProfile()));
        assertTrue(snapshot.maintenanceBudgetFiles() > 0);
    }

    @Test
    void tracksChronicHighLoadAndPinAwareResidentPolicy() {
        long startNs = 1_000_000_000L;

        StoragePolicyManager.setTestNowNs(startNs);
        StoragePolicyManager.reset(true);
        StoragePolicyManager.onServerTick(0, 0);

        int busyTicks = (14 * 60 * 1_000) / 50;
        for (int tick = 1; tick <= busyTicks; tick++) {
            StoragePolicyManager.setTestNowNs(startNs + tick * 50_000_000L);
            StoragePolicyManager.onServerTick(20, 0);
        }

        StoragePolicyManager.PolicyDebugSnapshot busySnapshot = StoragePolicyManager.debugSnapshot();
        assertTrue("chronic-high-load".equals(busySnapshot.loadProfile()));
        assertTrue("throughput".equals(busySnapshot.compressionMode()));

        for (int idx = 0; idx < 32; idx++) {
            LinearRuntime.pinRegion(Path.of("/tmp/pinned-" + idx + ".linear"));
        }

        StoragePolicyManager.setTestNowNs(startNs + (busyTicks + 1L) * 50_000_000L);
        StoragePolicyManager.onServerTick(20, 0);

        StoragePolicyManager.PolicyDebugSnapshot pinnedSnapshot = StoragePolicyManager.debugSnapshot();
        assertTrue(pinnedSnapshot.pinnedRegionCount() >= 32);
        assertTrue(pinnedSnapshot.residentHotSet() < busySnapshot.residentHotSet());
        assertTrue(pinnedSnapshot.residentTargetBytes() <= busySnapshot.residentTargetBytes());
    }

    @Test
    void brotli11RegionRecompressionFullyRepaysDebtRegardlessOfConfiguredIdleTarget() {
        StoragePolicyManager.reset(true);

        long startNs = 1_000_000_000L;
        Path region = Path.of("/tmp/r.7.0.linear");

        StoragePolicyManager.setTestNowNs(startNs);
        StoragePolicyManager.onServerTick(0, 0);

        // A low-effort Zstd flush (mirroring a real live write) always leaves
        // some debt, exactly like tracksAndRepaysMaintenanceDebtForLowCompressionRegions
        // above already confirms for the Zstd-only case.
        StoragePolicyManager.setTestNowNs(startNs + 50_000_000L);
        StoragePolicyManager.recordRegionFlush(region, 120_000_000L, 4L * 1024L * 1024L,
                1L * 1024L * 1024L, 2);
        StoragePolicyManager.onServerTick(0, 0);
        double debtAfterLowZstdFlush = StoragePolicyManager.maintenanceDebtScore();
        assertTrue(debtAfterLowZstdFlush > 0.0D);

        // Recompressing that SAME region to Brotli quality 11 - passed as the
        // real ENCODED header byte, matching exactly what IdleRecompressor's
        // fixed call site does in production - must fully repay the debt, the
        // same way recompressing to Zstd 22 already does above.
        int encodedBrotli11 = CompressionAlgorithm.encodeBrotli(CompressionAlgorithm.BROTLI_QUALITY) & 0xFF;
        StoragePolicyManager.setTestNowNs(startNs + 100_000_000L);
        StoragePolicyManager.recordRegionRecompressed(region, encodedBrotli11, 512_000L);
        StoragePolicyManager.onServerTick(0, 0);
        double debtAfterBrotliRecompress = StoragePolicyManager.maintenanceDebtScore();

        assertTrue(debtAfterBrotliRecompress < debtAfterLowZstdFlush);
        assertEquals(0.0D, debtAfterBrotliRecompress, 0.0001D,
                "a region genuinely recompressed to Brotli quality 11 must show zero remaining debt");
    }

    /**
     * Regression trap, not a "correct behavior" test: this documents the exact
     * bug caught during manual review by proving it WOULD still misbehave if
     * IdleRecompressor's fix were ever reverted. Passing the bare Brotli
     * quality number (11) instead of the properly ENCODED header byte (111)
     * makes it look like a mediocre Zstd level to the decoder, producing real
     * nonzero "debt" for a region that is actually already at the best
     * possible compression. If this assertion ever starts failing, it means
     * someone accidentally fixed the encoding bug from the wrong end (inside
     * computeCompressionDebt) rather than at the actual call site - worth
     * investigating either way, not just re-enabling this test.
     */
    @Test
    void passingBareBrotliQualityInsteadOfEncodedByteWouldShowWrongNonzeroDebt() {
        StoragePolicyManager.reset(true);

        long startNs = 1_000_000_000L;
        Path region = Path.of("/tmp/r.8.0.linear");

        StoragePolicyManager.setTestNowNs(startNs);
        StoragePolicyManager.onServerTick(0, 0);

        StoragePolicyManager.setTestNowNs(startNs + 50_000_000L);
        // Deliberately the bare quality number, NOT the encoded byte - this is
        // the exact bug, reproduced on purpose.
        StoragePolicyManager.recordRegionRecompressed(region, CompressionAlgorithm.BROTLI_QUALITY, 512_000L);
        StoragePolicyManager.onServerTick(0, 0);

        double debtWithBareBrotliQuality = StoragePolicyManager.maintenanceDebtScore();
        assertTrue(debtWithBareBrotliQuality > 0.0D,
                "documents why IdleRecompressor MUST pass the ENCODED byte, not the bare quality number - "
                        + "this nonzero value IS the bug, reproduced deliberately");
    }
}
