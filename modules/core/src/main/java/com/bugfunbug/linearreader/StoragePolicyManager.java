package com.bugfunbug.linearreader;

import com.bugfunbug.linearreader.config.LinearConfig;
import com.bugfunbug.linearreader.linear.DHPregenMonitor;
import com.bugfunbug.linearreader.linear.IdleRecompressor;
import com.bugfunbug.linearreader.linear.LinearRegionFile;

import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Central adaptive policy layer for storage behavior.
 *
 * The initial implementation intentionally keeps the model small:
 * rolling activity signals, a gradual quietness score, and lightweight
 * per-region heat tracking used to steer background maintenance.
 */
public final class StoragePolicyManager {

    private static final long TARGET_TICK_NS = 50_000_000L;
    private static final long RATE_TIME_CONSTANT_NS = 10_000_000_000L;
    private static final long LATENCY_TIME_CONSTANT_NS = 15_000_000_000L;
    private static final long TICK_STRAIN_TIME_CONSTANT_NS = 8_000_000_000L;
    private static final long BACKLOG_TIME_CONSTANT_NS = 8_000_000_000L;
    private static final long CACHE_CHURN_TIME_CONSTANT_NS = 90_000_000_000L;
    private static final long LOAD_PROFILE_TIME_CONSTANT_NS = 12L * 60L * 1_000_000_000L;
    private static final long REGION_HEAT_DECAY_NS = 15L * 60L * 1_000_000_000L;
    private static final long REGION_FORGET_NS = 6L * 60L * 60L * 1_000_000_000L;
    private static final long BACKGROUND_FLUSH_DELAY_NS = 15_000_000_000L;
    private static final long PRESSURE_FLUSH_MIN_AGE_NS = 3_000_000_000L;
    private static final long FLUSH_COOLDOWN_NS = 15_000_000_000L;
    private static final long TRICKLE_FLUSH_MIN_QUIET_NS = 60_000_000_000L;   // 60s since last write
    private static final long TRICKLE_FLUSH_BASE_INTERVAL_NS = 8_000_000_000L; // interval at low backlog
    private static final long TRICKLE_FLUSH_FLOOR_INTERVAL_NS = 1_000_000_000L; // fastest we'll ever go
    private static final int  TRICKLE_FLUSH_BACKLOG_SCALE = 40;                // dirty-region count to reach the floor
    private static final double TRICKLE_FLUSH_MAX_TICK_STRAIN = 0.35D;

    private static final long PANIC_FLUSH_MIN_INTERVAL_NS = 250_000_000L; // at most ~4/sec, global

    private static final long RESIDENT_TRIM_RECENT_ACCESS_NS = 45_000_000_000L;
    private static final long RESIDENT_TRIM_MIN_INTERVAL_NS = 2_000_000_000L;
    private static final long RESIDENT_BUDGET_BYTES = computeResidentBudgetBytes();
    private static final long RESIDENT_TARGET_BYTES = Math.max(RESIDENT_BUDGET_BYTES * 3L / 4L,
            64L * 1024L * 1024L);
    private static final long MIN_HEAP_HEADROOM_BYTES = computeMinHeapHeadroomBytes();
    private static final long POLICY_LOG_MIN_INTERVAL_NS = 30_000_000_000L;

    private static final double READ_HEAT = 0.35D;
    private static final double FLUSH_HEAT = 0.60D;
    private static final double WRITE_HEAT_BASE = 1.25D;
    private static final double WRITE_HEAT_MAX_BONUS = 1.75D;
    private static final double WRITE_HEAT_BONUS_BYTES = 32_768.0D;
    private static final double BACKGROUND_FLUSH_MIN_QUIETNESS = 0.45D;
    private static final double BACKGROUND_FLUSH_MAX_TICK_STRAIN = 0.50D;
    private static final double PRESSURE_FLUSH_AGE_CAP_SECONDS = 120.0D;
    private static final double DIRTY_DEBT_AGE_SECONDS = 45.0D;
    private static final double BACKUP_DEBT_BYTES_SCALE = 2.0D * 1024.0D * 1024.0D;
    private static final double BACKUP_DEBT_CHUNK_SCALE = 32.0D;
    private static final double COMPRESSION_DEBT_BYTES_SCALE = 2.0D * 1024.0D * 1024.0D;
    private static final int MIN_RESIDENT_HOT_SET = 24;
    private static final double HOT_REGION_THRESHOLD = 2.0D;
    private static final double COLD_REGION_THRESHOLD = 0.50D;

    private static final LongAdder CHUNK_READS = new LongAdder();
    private static final LongAdder CHUNK_WRITES = new LongAdder();
    private static final LongAdder REGION_FLUSHES = new LongAdder();
    private static final LongAdder REGION_FLUSH_NS = new LongAdder();
    private static final LongAdder RESIDENT_RELOADS = new LongAdder();
    private static final LongAdder RESIDENT_EVICTIONS = new LongAdder();
    private static final LongAdder RESIDENT_TRIM_RUNS = new LongAdder();
    private static final LongAdder RESIDENT_TRIMMED_REGIONS = new LongAdder();
    private static final LongAdder RESIDENT_TRIMMED_BYTES = new LongAdder();
    private static final AtomicLong LAST_CHUNK_IO_NS = new AtomicLong(System.nanoTime());
    private static final AtomicLong LAST_RESIDENT_TRIM_NS = new AtomicLong(0L);
    private static final AtomicLong LAST_TRICKLE_FLUSH_NS = new AtomicLong(0L);
    private static final AtomicLong LAST_PANIC_FLUSH_NS = new AtomicLong(0L);
    private static final Map<Path, RegionActivity> REGION_ACTIVITY = new ConcurrentHashMap<>();

    private static volatile PolicySnapshot snapshot = new PolicySnapshot(
            LinearConfig.getCompressionLevel(),
            Math.max(1, LinearConfig.getRegionsPerSaveTick()),
            0.0D,
            false,
            0.0D,
            0.0D,
            0.0D,
            0.0D,
            0,
            0,
            0.0D,
            0.0D,
            0.0D,
            0.0D,
            CompressionMode.BALANCED,
            LoadProfile.BALANCED,
            0.0D,
            0,
            MIN_RESIDENT_HOT_SET,
            RESIDENT_TARGET_BYTES
    );

    private static volatile boolean dedicatedServer;
    private static volatile Long testNowNs;
    private static volatile Long testCurrentTimeMs;

    private static long lastTickNs;
    private static long lastSampleNs;
    private static long lastChunkReadSample;
    private static long lastChunkWriteSample;
    private static long lastRegionFlushSample;
    private static long lastRegionFlushNsSample;
    private static long lastResidentReloadSample;
    private static long lastResidentEvictionSample;
    private static long tickCount;
    private static long lastPolicyLogNs;
    private static volatile int pinnedRegionCount;
    private static volatile long lastTransitionAtMs;
    private static volatile String lastTransitionSummary = "none";
    private static volatile String lastTransitionDetail = "none";

    private static double chunkReadRateEwma;
    private static double chunkWriteRateEwma;
    private static double regionFlushRateEwma;
    private static double flushLatencyMsEwma;
    private static double flushBacklogEwma;
    private static double tickStrainEwma;
    private static double cacheChurnEwma;
    private static double longPressureEwma;
    private static double longQuietnessEwma;

    private enum CompressionMode {
        THROUGHPUT("throughput"),
        BALANCED("balanced"),
        EFFICIENCY("efficiency");

        private final String label;

        CompressionMode(String label) {
            this.label = label;
        }
    }

    private enum LoadProfile {
        CHRONIC_HIGH_LOAD("chronic-high-load"),
        BURSTY("bursty"),
        BALANCED("balanced"),
        CHRONIC_LOW_LOAD("chronic-low-load");

        private final String label;

        LoadProfile(String label) {
            this.label = label;
        }
    }

    private StoragePolicyManager() {}

    public static synchronized void reset(boolean dedicatedServer) {
        StoragePolicyManager.dedicatedServer = dedicatedServer;

        CHUNK_READS.reset();
        CHUNK_WRITES.reset();
        REGION_FLUSHES.reset();
        REGION_FLUSH_NS.reset();
        RESIDENT_RELOADS.reset();
        RESIDENT_EVICTIONS.reset();
        RESIDENT_TRIM_RUNS.reset();
        RESIDENT_TRIMMED_REGIONS.reset();
        RESIDENT_TRIMMED_BYTES.reset();
        LAST_CHUNK_IO_NS.set(nowNs());
        LAST_RESIDENT_TRIM_NS.set(0L);
        LAST_RESIDENT_TRIM_NS.set(0L);
        LAST_TRICKLE_FLUSH_NS.set(0L);
        LAST_PANIC_FLUSH_NS.set(0L);
        REGION_ACTIVITY.clear();

        lastTickNs = 0L;
        lastSampleNs = 0L;
        lastChunkReadSample = 0L;
        lastChunkWriteSample = 0L;
        lastRegionFlushSample = 0L;
        lastRegionFlushNsSample = 0L;
        lastResidentReloadSample = 0L;
        lastResidentEvictionSample = 0L;
        tickCount = 0L;
        lastPolicyLogNs = 0L;
        pinnedRegionCount = 0;
        lastTransitionAtMs = 0L;
        lastTransitionSummary = "none";
        lastTransitionDetail = "none";

        chunkReadRateEwma = 0.0D;
        chunkWriteRateEwma = 0.0D;
        regionFlushRateEwma = 0.0D;
        flushLatencyMsEwma = 0.0D;
        flushBacklogEwma = 0.0D;
        tickStrainEwma = 0.0D;
        cacheChurnEwma = 0.0D;
        longPressureEwma = 0.0D;
        longQuietnessEwma = 0.0D;

        snapshot = new PolicySnapshot(
                LinearConfig.getCompressionLevel(),
                Math.max(1, LinearConfig.getRegionsPerSaveTick()),
                0.0D,
                false,
                0.0D,
                0.0D,
                0.0D,
                0.0D,
                0,
                0,
                0.0D,
                0.0D,
                0.0D,
                0.0D,
                CompressionMode.BALANCED,
                LoadProfile.BALANCED,
                0.0D,
                0,
                MIN_RESIDENT_HOT_SET,
                RESIDENT_TARGET_BYTES
        );
    }

    public static void noteChunkIo() {
        LAST_CHUNK_IO_NS.set(nowNs());
    }

    public static void recordChunkRead(Path regionPath) {
        recordChunkReadAt(regionPath, nowNs());
    }

    static void recordChunkReadAt(Path regionPath, long nowNs) {
        CHUNK_READS.increment();
        LAST_CHUNK_IO_NS.set(nowNs);
        regionActivity(regionPath).noteRead(nowNs);
    }

    public static void recordChunkWrite(Path regionPath, int bytesWritten) {
        recordChunkWriteAt(regionPath, bytesWritten, nowNs());
    }

    static void recordChunkWriteAt(Path regionPath, int bytesWritten, long nowNs) {
        CHUNK_WRITES.increment();
        LAST_CHUNK_IO_NS.set(nowNs);
        regionActivity(regionPath).noteWrite(nowNs, bytesWritten);
    }

    public static void recordRegionFlush(Path regionPath, long flushNs,
                                         long uncompressedBytes, long compressedBytes,
                                         int compressionLevel) {
        recordRegionFlushAt(regionPath, flushNs, uncompressedBytes, compressedBytes, compressionLevel, nowNs());
    }

    static void recordRegionFlushAt(Path regionPath, long flushNs,
                                    long uncompressedBytes, long compressedBytes,
                                    int compressionLevel, long nowNs) {
        REGION_FLUSHES.increment();
        REGION_FLUSH_NS.add(Math.max(0L, flushNs));
        regionActivity(regionPath).noteFlush(nowNs, uncompressedBytes, compressedBytes, compressionLevel);
    }

    public static void recordRegionRecompressed(Path regionPath, int compressionLevel, long bytesSaved) {
        regionActivity(regionPath).noteRecompressed(nowNs(), compressionLevel, bytesSaved);
    }

    public static void recordResidentReload(Path regionPath) {
        RESIDENT_RELOADS.increment();
        LAST_CHUNK_IO_NS.set(nowNs());
        regionActivity(regionPath).noteRead(nowNs());
    }

    public static void recordResidentEviction(Path regionPath, long bytesFreed) {
        if (bytesFreed <= 0L) {
            return;
        }
        RESIDENT_EVICTIONS.increment();
    }

    public static void updatePinnedRegionCount(int pinnedRegionCount) {
        StoragePolicyManager.pinnedRegionCount = Math.max(0, pinnedRegionCount);
    }

    public static void onServerTick(int queuedFlushCount, int inFlightFlushCount) {
        onServerTickAt(nowNs(), queuedFlushCount, inFlightFlushCount);
    }

    static synchronized void onServerTickAt(long nowNs, int queuedFlushCount, int inFlightFlushCount) {
        if (lastTickNs == 0L) {
            lastTickNs = nowNs;
            lastSampleNs = nowNs;
            snapshot = buildSnapshot(nowNs, queuedFlushCount, inFlightFlushCount);
            return;
        }

        long tickElapsedNs = Math.max(1L, nowNs - lastTickNs);
        long sampleElapsedNs = Math.max(1L, nowNs - lastSampleNs);
        lastTickNs = nowNs;
        lastSampleNs = nowNs;
        tickCount++;

        long chunkReads = CHUNK_READS.sum();
        long chunkWrites = CHUNK_WRITES.sum();
        long regionFlushes = REGION_FLUSHES.sum();
        long regionFlushNs = REGION_FLUSH_NS.sum();
        long residentReloads = RESIDENT_RELOADS.sum();
        long residentEvictions = RESIDENT_EVICTIONS.sum();

        long deltaChunkReads = Math.max(0L, chunkReads - lastChunkReadSample);
        long deltaChunkWrites = Math.max(0L, chunkWrites - lastChunkWriteSample);
        long deltaRegionFlushes = Math.max(0L, regionFlushes - lastRegionFlushSample);
        long deltaRegionFlushNs = Math.max(0L, regionFlushNs - lastRegionFlushNsSample);
        long deltaResidentReloads = Math.max(0L, residentReloads - lastResidentReloadSample);
        long deltaResidentEvictions = Math.max(0L, residentEvictions - lastResidentEvictionSample);

        lastChunkReadSample = chunkReads;
        lastChunkWriteSample = chunkWrites;
        lastRegionFlushSample = regionFlushes;
        lastRegionFlushNsSample = regionFlushNs;
        lastResidentReloadSample = residentReloads;
        lastResidentEvictionSample = residentEvictions;

        double seconds = sampleElapsedNs / 1_000_000_000.0D;
        double readRate = deltaChunkReads / seconds;
        double writeRate = deltaChunkWrites / seconds;
        double flushRate = deltaRegionFlushes / seconds;
        double latencyMs = deltaRegionFlushes > 0L
                ? (deltaRegionFlushNs / 1_000_000.0D) / deltaRegionFlushes
                : 0.0D;
        double backlog = Math.max(0, queuedFlushCount + inFlightFlushCount);
        double cacheChurn = (deltaResidentReloads + deltaResidentEvictions) / seconds;
        double tickStrain = clamp(
                (tickElapsedNs - TARGET_TICK_NS) / (double) (TARGET_TICK_NS * 2L),
                0.0D,
                1.0D
        );

        chunkReadRateEwma = ewma(chunkReadRateEwma, readRate, sampleElapsedNs, RATE_TIME_CONSTANT_NS);
        chunkWriteRateEwma = ewma(chunkWriteRateEwma, writeRate, sampleElapsedNs, RATE_TIME_CONSTANT_NS);
        regionFlushRateEwma = ewma(regionFlushRateEwma, flushRate, sampleElapsedNs, RATE_TIME_CONSTANT_NS);
        flushLatencyMsEwma = ewma(flushLatencyMsEwma, latencyMs, sampleElapsedNs, LATENCY_TIME_CONSTANT_NS);
        flushBacklogEwma = ewma(flushBacklogEwma, backlog, sampleElapsedNs, BACKLOG_TIME_CONSTANT_NS);
        tickStrainEwma = ewma(tickStrainEwma, tickStrain, sampleElapsedNs, TICK_STRAIN_TIME_CONSTANT_NS);
        cacheChurnEwma = ewma(cacheChurnEwma, cacheChurn, sampleElapsedNs, CACHE_CHURN_TIME_CONSTANT_NS);

        int baseFlushBudget = Math.max(1, DHPregenMonitor.effectiveRegionsPerSaveTick());
        double pressureScore = computePressureScore((int) backlog, baseFlushBudget);
        double quietness = computeQuietnessScore(nowNs, pressureScore);
        longPressureEwma = ewma(longPressureEwma, pressureScore, sampleElapsedNs, LOAD_PROFILE_TIME_CONSTANT_NS);
        longQuietnessEwma = ewma(longQuietnessEwma, quietness, sampleElapsedNs, LOAD_PROFILE_TIME_CONSTANT_NS);

        PolicySnapshot previousSnapshot = snapshot;
        PolicySnapshot nextSnapshot = buildSnapshot(nowNs, queuedFlushCount, inFlightFlushCount);
        snapshot = nextSnapshot;
        maybeLogPolicyTransition(previousSnapshot, nextSnapshot, nowNs);
        if (tickCount % 600L == 0L) {
            pruneRegionActivity(nowNs);
        }
    }

    public static int currentCompressionLevel() {
        return snapshot.compressionLevel();
    }

    public static int flushBudgetPerTick() {
        return snapshot.flushBudgetPerTick();
    }

    public static double quietnessScore() {
        return snapshot.quietnessScore();
    }

    public static boolean maintenanceAllowed() {
        return snapshot.maintenanceAllowed();
    }

    public static double maintenanceDebtScore() {
        return snapshot.maintenanceDebtScore();
    }

    public static int maintenanceBudgetFiles() {
        return snapshot.maintenanceBudgetFiles();
    }

    public static boolean shouldScheduleBackupRefresh(double backupDebtScore) {
        if (backupDebtScore >= 3.0D) {
            return true;
        }
        if (maintenanceBudgetFiles() >= 2) {
            return true;
        }
        return backupDebtScore >= 1.5D && snapshot.quietnessScore() >= 0.55D;
    }

    public static boolean shouldRefreshBackup(boolean backedUp, boolean refreshQueued,
                                              int changedChunks, long changedBytes,
                                              long backupCompletedAtMs, long lastMutationNs,
                                              long nowNs) {
        if (!backedUp || refreshQueued) return false;
        if (changedChunks <= 0 && changedBytes <= 0L) return false;

        long quietNs = (long) LinearConfig.getBackupQuietSeconds() * 1_000_000_000L;
        if (quietNs > 0L && nowNs - lastMutationNs < quietNs) return false;

        double backupDebt = backupDebtScore(changedChunks, changedBytes, true, false);
        boolean thresholdReached = changedChunks >= LinearConfig.getBackupMinChangedChunks()
                || changedBytes >= LinearConfig.getBackupMinChangedBytes();
        if (thresholdReached && shouldScheduleBackupRefresh(backupDebt)) {
            return true;
        }

        long maxAgeMs = (long) LinearConfig.getBackupMaxAgeMinutes() * 60_000L;
        boolean overdue = maxAgeMs > 0L
                && backupCompletedAtMs > 0L
                && currentTimeMs() - backupCompletedAtMs >= maxAgeMs;
        return overdue && snapshot.quietnessScore() >= 0.35D && snapshot.tickStrain() < 0.75D;
    }

    public static boolean shouldStartResidentTrim(long residentBytes, long heapHeadroomBytes, long nowNs) {
        boolean heapPressure = heapHeadroomBytes < MIN_HEAP_HEADROOM_BYTES;
        long lastTrimNs = LAST_RESIDENT_TRIM_NS.get();
        if (!heapPressure && nowNs - lastTrimNs < RESIDENT_TRIM_MIN_INTERVAL_NS) return false;
        long adaptiveStartBytes = Math.min(RESIDENT_BUDGET_BYTES,
                snapshot.residentTargetBytes() + (16L * 1024L * 1024L));
        if (!heapPressure && residentBytes <= adaptiveStartBytes) return false;
        if (!heapPressure && !LAST_RESIDENT_TRIM_NS.compareAndSet(lastTrimNs, nowNs)) return false;
        if (heapPressure) {
            LAST_RESIDENT_TRIM_NS.set(nowNs);
        }
        return true;
    }

    public static boolean shouldTrimResidentRegion(Path regionPath, boolean loaded,
                                                   boolean dirty, boolean flushing,
                                                   boolean pinned, long lastAccessNs, long nowNs) {
        if (!loaded || dirty || flushing || pinned) {
            return false;
        }
        if (nowNs - lastAccessNs < RESIDENT_TRIM_RECENT_ACCESS_NS) {
            return false;
        }
        return residentTrimPriority(regionPath, lastAccessNs, 0L, nowNs) > 0.0D;
    }

    public static double residentTrimPriority(Path regionPath, long lastAccessNs, long residentBytes, long nowNs) {
        double idleSeconds = Math.max(0.0D, (nowNs - lastAccessNs) / 1_000_000_000.0D);
        double idleWeight = clamp(idleSeconds / 45.0D, 0.0D, 8.0D);
        double heatPenalty = 1.0D / (1.0D + regionHeat(normalize(regionPath), nowNs));
        double sizeBonus = residentBytes > 0L
                ? clamp(residentBytes / (32.0D * 1024.0D * 1024.0D), 0.50D, 4.0D)
                : 1.0D;
        return idleWeight * heatPenalty * sizeBonus;
    }

    public static long residentBudgetBytes() {
        return RESIDENT_BUDGET_BYTES;
    }

    public static long residentTargetBytes() {
        return snapshot.residentTargetBytes();
    }

    public static int residentTrimHotSet() {
        return snapshot.residentHotSet();
    }

    public static long minHeapHeadroomBytes() {
        return MIN_HEAP_HEADROOM_BYTES;
    }

    public static void recordResidentTrim(int trimmedRegions, long trimmedBytes) {
        if (trimmedRegions <= 0 && trimmedBytes <= 0L) {
            return;
        }
        RESIDENT_TRIM_RUNS.increment();
        if (trimmedRegions > 0) {
            RESIDENT_TRIMMED_REGIONS.add(trimmedRegions);
        }
        if (trimmedBytes > 0L) {
            RESIDENT_TRIMMED_BYTES.add(trimmedBytes);
        }
    }

    public static boolean shouldQueueBackgroundFlush(LinearRegionFile region, long nowNs) {
        return shouldQueueBackgroundFlush(region.isDirty(), region.isFlushing(),
                region.lastMutationTimeNs(), region.lastSuccessfulFlushTimeNs(), nowNs);
    }

    public static boolean shouldConsiderPressureFlush(LinearRegionFile region, long nowNs) {
        return shouldConsiderPressureFlush(region.isDirty(), region.isFlushing(),
                region.lastMutationTimeNs(), region.lastSuccessfulFlushTimeNs(), nowNs);
    }

    /**
     * Interval between trickle flushes, scaled down as dirty-region backlog grows.
     * At <=1 dirty region: base interval (8s). At >=TRICKLE_FLUSH_BACKLOG_SCALE dirty
     * regions: the floor (1s). Linear in between. This exists because a fixed interval
     * can't keep up with sustained high-velocity chunk generation (e.g. elytra flight
     * generating dirty regions far faster than a flat 1-per-8s rate can drain) - but we
     * still never want to go faster than the floor, since this runs on the same JVM as
     * the render thread on integrated servers.
     */
    private static long trickleFlushIntervalNs(int dirtyRegionCount) {
        if (dirtyRegionCount <= 1) return TRICKLE_FLUSH_BASE_INTERVAL_NS;
        double fraction = clamp(dirtyRegionCount / (double) TRICKLE_FLUSH_BACKLOG_SCALE, 0.0D, 1.0D);
        long span = TRICKLE_FLUSH_BASE_INTERVAL_NS - TRICKLE_FLUSH_FLOOR_INTERVAL_NS;
        return TRICKLE_FLUSH_BASE_INTERVAL_NS - Math.round(span * fraction);
    }

    static boolean shouldTrickleFlushSingleplayer(boolean dirty, boolean flushing,
                                                  long lastMutationNs, long nowNs,
                                                  int dirtyRegionCount) {
        if (dedicatedServer) return false;
        if (!dirty || flushing) return false;
        if (nowNs - lastMutationNs < TRICKLE_FLUSH_MIN_QUIET_NS) return false;
        if (snapshot.tickStrain() > TRICKLE_FLUSH_MAX_TICK_STRAIN) return false;
        return nowNs - LAST_TRICKLE_FLUSH_NS.get() >= trickleFlushIntervalNs(dirtyRegionCount);
    }

    public static boolean shouldTrickleFlushSingleplayer(LinearRegionFile region, long nowNs, int dirtyRegionCount) {
        return shouldTrickleFlushSingleplayer(region.isDirty(), region.isFlushing(),
                region.lastMutationTimeNs(), nowNs, dirtyRegionCount);
    }

    /** Advances the trickle-flush rate limit. Call only after actually queuing a region. */
    public static void noteTrickleFlush(long nowNs) {
        LAST_TRICKLE_FLUSH_NS.set(nowNs);
    }

    /**
     * Rate limiter for the panic-flush backstop (see LinearRuntime.maybePanicFlush).
     * Uses compareAndSet so concurrent callers across different dimension storages
     * (each RegionFileStorage has its own linearCache/lock) can't both win the same
     * rate-limit window.
     */
    public static boolean shouldAttemptPanicFlush(long nowNs) {
        long last = LAST_PANIC_FLUSH_NS.get();
        if (nowNs - last < PANIC_FLUSH_MIN_INTERVAL_NS) return false;
        return LAST_PANIC_FLUSH_NS.compareAndSet(last, nowNs);
    }

    public static double pressureFlushPriority(LinearRegionFile region, long nowNs) {
        return pressureFlushPriority(region.getNormalizedPath(), region.lastMutationTimeNs(), nowNs);
    }

    public static int pressureFlushDirtyRegionLimit(int effectiveCacheSize,
                                                    int queuedFlushCount, int inFlightFlushCount) {
        int minDirty = LinearConfig.getPressureFlushMinDirtyRegions();
        int maxDirty = Math.max(minDirty, LinearConfig.getPressureFlushMaxDirtyRegions());
        if (DHPregenMonitor.isPregenActive()) {
            return Math.max(minDirty, Math.min(maxDirty, 4));
        }
        if (!dedicatedServer) {
            return Integer.MAX_VALUE;
        }

        int flushRate = Math.max(1, flushBudgetPerTick());
        int cacheSize = Math.max(8, effectiveCacheSize);
        int target = Math.max(cacheSize / 16, flushRate * 2);

        int backlog = Math.max(0, queuedFlushCount + inFlightFlushCount);
        if (backlog >= Math.max(2, flushRate) || snapshot.pressureScore() >= 0.85D) {
            target = Math.max(minDirty, target / 2);
        } else if (snapshot.quietnessScore() >= 0.80D && backlog == 0) {
            target = Math.min(maxDirty, target + 1);
        }

        return Math.max(minDirty, Math.min(maxDirty, target));
    }

    public static double recompressPriority(LinearRegionFile region) {
        return recompressPriority(region.getNormalizedPath(), region.isDirty(), region.isFlushing(),
                region.lastMutationTimeNs());
    }

    public static double recompressPriority(Path regionPath) {
        return recompressPriority(normalize(regionPath), false, false, 0L);
    }

    public static PolicyDebugSnapshot debugSnapshot() {
        long nowNs = nowNs();
        int trackedRegions = 0;
        int hotRegions = 0;
        int coldRegions = 0;
        for (RegionActivity activity : REGION_ACTIVITY.values()) {
            trackedRegions++;
            double heat = activity.heat(nowNs);
            if (heat >= HOT_REGION_THRESHOLD) {
                hotRegions++;
            } else if (heat < COLD_REGION_THRESHOLD) {
                coldRegions++;
            }
        }

        return new PolicyDebugSnapshot(
                snapshot.compressionMode().label,
                snapshot.loadProfile().label,
                snapshot.compressionLevel(),
                snapshot.flushBudgetPerTick(),
                snapshot.quietnessScore(),
                snapshot.pressureScore(),
                snapshot.tickStrain(),
                snapshot.maintenanceAllowed(),
                snapshot.maintenanceBudgetFiles(),
                snapshot.maintenanceDebtScore(),
                snapshot.compressionDebtScore(),
                snapshot.backupDebtScore(),
                snapshot.dirtyDebtScore(),
                snapshot.chunkWriteRate(),
                snapshot.flushLatencyMs(),
                snapshot.cacheChurnScore(),
                snapshot.backlog(),
                Math.max(0L, (nowNs - LAST_CHUNK_IO_NS.get()) / 1_000_000L),
                snapshot.pinnedRegionCount(),
                trackedRegions,
                hotRegions,
                coldRegions,
                RESIDENT_BUDGET_BYTES,
                snapshot.residentTargetBytes(),
                snapshot.residentHotSet(),
                RESIDENT_TRIM_RUNS.sum(),
                RESIDENT_TRIMMED_REGIONS.sum(),
                RESIDENT_TRIMMED_BYTES.sum(),
                lastTransitionAtMs,
                lastTransitionSummary,
                lastTransitionDetail
        );
    }

    static PolicySnapshot snapshotForTests() {
        return snapshot;
    }

    static synchronized void setTestNowNs(Long testNowNs) {
        StoragePolicyManager.testNowNs = testNowNs;
    }

    static synchronized void setTestCurrentTimeMs(Long testCurrentTimeMs) {
        StoragePolicyManager.testCurrentTimeMs = testCurrentTimeMs;
    }

    static boolean shouldQueueBackgroundFlush(boolean dirty, boolean flushing,
                                              long lastMutationNs, long lastSuccessfulFlushNs,
                                              long nowNs) {
        if (!dedicatedServer) return false;
        if (!dirty || flushing) return false;
        if (nowNs - lastMutationNs < BACKGROUND_FLUSH_DELAY_NS) return false;
        if (nowNs - lastSuccessfulFlushNs < FLUSH_COOLDOWN_NS) return false;
        return snapshot.quietnessScore() >= BACKGROUND_FLUSH_MIN_QUIETNESS
                && snapshot.tickStrain() <= BACKGROUND_FLUSH_MAX_TICK_STRAIN;
    }

    static boolean shouldConsiderPressureFlush(boolean dirty, boolean flushing,
                                               long lastMutationNs, long lastSuccessfulFlushNs,
                                               long nowNs) {
        if (!dedicatedServer) return false;
        if (!dirty || flushing) return false;
        if (shouldQueueBackgroundFlush(dirty, flushing, lastMutationNs, lastSuccessfulFlushNs, nowNs)) {
            return false;
        }
        if (nowNs - lastMutationNs < PRESSURE_FLUSH_MIN_AGE_NS) return false;
        if (nowNs - lastSuccessfulFlushNs < FLUSH_COOLDOWN_NS) return false;
        return true;
    }

    private static PolicySnapshot buildSnapshot(long nowNs, int queuedFlushCount, int inFlightFlushCount) {
        int backlog = Math.max(0, queuedFlushCount + inFlightFlushCount);
        int configuredCompression = LinearConfig.getCompressionLevel();
        int baseFlushBudget = Math.max(1, DHPregenMonitor.effectiveRegionsPerSaveTick());
        DebtSummary debt = summarizeMaintenanceDebt(nowNs);
        double pressureScore = computePressureScore(backlog, baseFlushBudget);
        double quietness = computeQuietnessScore(nowNs, pressureScore);
        LoadProfile loadProfile = computeLoadProfile();
        int residentHotSet = computeResidentHotSet(loadProfile, pinnedRegionCount);
        long residentTargetBytes = computeResidentTargetBytes(loadProfile, pinnedRegionCount, pressureScore);

        boolean maintenanceAllowed = quietness >= 0.75D
                && tickStrainEwma < 0.35D
                && backlog <= Math.max(1, baseFlushBudget)
                && !DHPregenMonitor.isPregenActive()
                && loadProfile != LoadProfile.CHRONIC_HIGH_LOAD
                && cacheChurnEwma < 2.5D;
        int maintenanceBudgetFiles = computeMaintenanceBudgetFiles(
                quietness, debt.totalDebt(), backlog, baseFlushBudget, loadProfile, pinnedRegionCount
        );

        int compressionLevel = computeCompressionLevel(configuredCompression, baseFlushBudget, backlog, loadProfile);
        int flushBudgetPerTick = computeFlushBudget(baseFlushBudget, backlog, loadProfile);
        CompressionMode compressionMode = compressionModeFor(
                compressionLevel, configuredCompression, quietness, pressureScore, loadProfile
        );

        return new PolicySnapshot(
                compressionLevel,
                flushBudgetPerTick,
                quietness,
                maintenanceAllowed,
                pressureScore,
                tickStrainEwma,
                chunkWriteRateEwma,
                flushLatencyMsEwma,
                backlog,
                maintenanceBudgetFiles,
                debt.totalDebt(),
                debt.compressionDebt(),
                debt.backupDebt(),
                debt.dirtyDebt(),
                compressionMode,
                loadProfile,
                cacheChurnEwma,
                pinnedRegionCount,
                residentHotSet,
                residentTargetBytes
        );
    }

    private static int computeCompressionLevel(int configuredCompression, int baseFlushBudget,
                                               int backlog, LoadProfile loadProfile) {
        if (configuredCompression <= 1) {
            return configuredCompression;
        }
        if (!dedicatedServer) {
            return Math.max(1, Math.min(configuredCompression, 2));
        }
        if (DHPregenMonitor.isPregenActive()) {
            return Math.max(1, Math.min(configuredCompression, 2));
        }

        double backlogPressure = backlog / (double) Math.max(1, baseFlushBudget);
        double writePressure = clamp(chunkWriteRateEwma / 24.0D, 0.0D, 1.5D);
        double latencyPressure = clamp(flushLatencyMsEwma / 250.0D, 0.0D, 1.5D);
        double combinedPressure = Math.max(
                backlogPressure * 0.65D,
                backlogPressure * 0.45D + latencyPressure * 0.20D + tickStrainEwma * 0.45D + writePressure * 0.25D
        );
        if (loadProfile == LoadProfile.CHRONIC_HIGH_LOAD) {
            combinedPressure += 0.25D;
        } else if (loadProfile == LoadProfile.BURSTY) {
            combinedPressure += 0.10D;
        } else if (loadProfile == LoadProfile.CHRONIC_LOW_LOAD) {
            combinedPressure = Math.max(0.0D, combinedPressure - 0.10D);
        }

        if (combinedPressure >= 2.0D) {
            return Math.max(1, configuredCompression - 2);
        }
        if (combinedPressure >= 1.0D) {
            return Math.max(1, configuredCompression - 1);
        }
        return configuredCompression;
    }

    private static int computeFlushBudget(int baseFlushBudget, int backlog, LoadProfile loadProfile) {
        if (DHPregenMonitor.isPregenActive()) {
            return baseFlushBudget;
        }
        if (tickStrainEwma >= 0.75D) {
            return Math.max(1, Math.min(baseFlushBudget, 2));
        }
        if (loadProfile == LoadProfile.CHRONIC_HIGH_LOAD && tickStrainEwma < 0.35D) {
            return Math.max(1, Math.min(baseFlushBudget * 2, baseFlushBudget + 1));
        }
        if (loadProfile == LoadProfile.BURSTY && backlog > 0 && tickStrainEwma < 0.45D) {
            return Math.max(1, Math.min(baseFlushBudget * 2, baseFlushBudget + 1));
        }
        if (backlog > baseFlushBudget * 2 && tickStrainEwma < 0.35D) {
            return Math.max(1, Math.min(baseFlushBudget * 2, baseFlushBudget + 2));
        }
        return Math.max(1, baseFlushBudget);
    }

    private static int computeMaintenanceBudgetFiles(double quietness, double totalDebt,
                                                     int backlog, int baseFlushBudget,
                                                     LoadProfile loadProfile, int pinnedRegionCount) {
        if (DHPregenMonitor.isPregenActive()) {
            return 0;
        }
        if (!dedicatedServer && quietness < 0.75D) {
            return 0;
        }
        if (loadProfile == LoadProfile.CHRONIC_HIGH_LOAD) {
            return quietness >= 0.85D && totalDebt >= 3.0D ? 1 : 0;
        }
        if (backlog > Math.max(1, baseFlushBudget) && quietness < 0.90D) {
            return 0;
        }
        if (cacheChurnEwma >= 3.0D && quietness < 0.90D) {
            return totalDebt >= 3.0D ? 1 : 0;
        }
        if (quietness < 0.40D) {
            return totalDebt >= 3.0D ? 1 : 0;
        }
        if (quietness < 0.55D) {
            return totalDebt >= 2.0D ? 2 : 0;
        }

        int base = quietness >= 0.90D ? 12
                : quietness >= 0.75D ? 8
                : quietness >= 0.65D ? 4 : 2;
        if (loadProfile == LoadProfile.CHRONIC_LOW_LOAD) {
            base += 2;
        } else if (loadProfile == LoadProfile.BURSTY) {
            base = Math.min(base, 4);
        }
        int debtBonus = (int) Math.min(12.0D, Math.floor(totalDebt * 2.0D));
        if (tickStrainEwma >= 0.25D) {
            debtBonus = Math.max(0, debtBonus - 2);
        }
        int pinnedPenalty = Math.min(4, pinnedRegionCount / 16);
        int churnPenalty = cacheChurnEwma >= 2.0D ? 2 : cacheChurnEwma >= 1.0D ? 1 : 0;
        return Math.max(0, Math.min(32, base + debtBonus - pinnedPenalty - churnPenalty));
    }

    private static double computePressureScore(int backlog, int baseFlushBudget) {
        double writePressure = clamp(chunkWriteRateEwma / 24.0D, 0.0D, 1.5D);
        double flushPressure = clamp(regionFlushRateEwma / Math.max(1.0D, baseFlushBudget), 0.0D, 1.5D);
        double backlogPressure = clamp(flushBacklogEwma / Math.max(2.0D, baseFlushBudget * 2.0D), 0.0D, 1.5D);
        double latencyPressure = clamp(flushLatencyMsEwma / 250.0D, 0.0D, 1.5D);
        double ioPressure = Math.max(writePressure, Math.max(flushPressure, backlogPressure));
        return clamp(
                ioPressure * 0.45D + latencyPressure * 0.20D + tickStrainEwma * 0.35D,
                0.0D,
                1.5D
        );
    }

    private static double computeQuietnessScore(long nowNs, double pressureScore) {
        long idleThresholdNs = idleThresholdNs();
        long idleNs = Math.max(0L, nowNs - LAST_CHUNK_IO_NS.get());
        double idleConfidence = clamp(idleNs / (double) Math.max(1L, idleThresholdNs / 2L), 0.0D, 1.0D);
        double quietness = clamp(
                idleConfidence * 0.55D + (1.0D - clamp(pressureScore, 0.0D, 1.0D)) * 0.45D,
                0.0D,
                1.0D
        );
        if (DHPregenMonitor.isPregenActive()) {
            quietness *= 0.20D;
        }
        if (!dedicatedServer) {
            quietness *= 0.90D;
        }
        return quietness;
    }

    private static LoadProfile computeLoadProfile() {
        if (DHPregenMonitor.isPregenActive() || longPressureEwma >= 0.45D) {
            return LoadProfile.CHRONIC_HIGH_LOAD;
        }
        if (longQuietnessEwma >= 0.60D && cacheChurnEwma < 0.50D) {
            return LoadProfile.CHRONIC_LOW_LOAD;
        }
        if (cacheChurnEwma >= 1.25D || (longPressureEwma >= 0.35D && longQuietnessEwma >= 0.45D)) {
            return LoadProfile.BURSTY;
        }
        return LoadProfile.BALANCED;
    }

    private static int computeResidentHotSet(LoadProfile loadProfile, int pinnedRegionCount) {
        int hotSet = MIN_RESIDENT_HOT_SET;
        if (loadProfile == LoadProfile.CHRONIC_HIGH_LOAD || loadProfile == LoadProfile.BURSTY) {
            hotSet += 8;
        } else if (loadProfile == LoadProfile.CHRONIC_LOW_LOAD) {
            hotSet -= 4;
        }
        hotSet -= Math.min(12, pinnedRegionCount / 4);
        return Math.max(8, Math.min(48, hotSet));
    }

    private static long computeResidentTargetBytes(LoadProfile loadProfile, int pinnedRegionCount, double pressureScore) {
        long target = RESIDENT_TARGET_BYTES;
        if (loadProfile == LoadProfile.CHRONIC_HIGH_LOAD || loadProfile == LoadProfile.BURSTY) {
            target = Math.min(RESIDENT_BUDGET_BYTES, target + (RESIDENT_BUDGET_BYTES - target) / 2L);
        } else if (loadProfile == LoadProfile.CHRONIC_LOW_LOAD) {
            target = Math.max(64L * 1024L * 1024L, target - target / 8L);
        }
        target -= Math.min(256L * 1024L * 1024L, (long) (pinnedRegionCount / 8L) * 32L * 1024L * 1024L);
        if (pressureScore >= 1.0D) {
            target = Math.max(64L * 1024L * 1024L, target - target / 8L);
        }
        return Math.max(64L * 1024L * 1024L, Math.min(RESIDENT_BUDGET_BYTES, target));
    }

    private static CompressionMode compressionModeFor(int compressionLevel, int configuredCompression,
                                                      double quietness, double pressureScore,
                                                      LoadProfile loadProfile) {
        if (compressionLevel < configuredCompression || loadProfile == LoadProfile.CHRONIC_HIGH_LOAD) {
            return CompressionMode.THROUGHPUT;
        }
        if (quietness >= 0.75D && pressureScore < 0.55D && loadProfile != LoadProfile.BURSTY) {
            return CompressionMode.EFFICIENCY;
        }
        return CompressionMode.BALANCED;
    }

    private static void maybeLogPolicyTransition(PolicySnapshot previous, PolicySnapshot next, long nowNs) {
        if (previous == null) {
            return;
        }
        boolean compressionChanged = previous.compressionLevel() != next.compressionLevel()
                || previous.compressionMode() != next.compressionMode();
        boolean profileChanged = previous.loadProfile() != next.loadProfile();
        boolean maintenanceChanged = previous.maintenanceAllowed() != next.maintenanceAllowed();
        boolean churnChanged = churnTier(previous.cacheChurnScore()) != churnTier(next.cacheChurnScore());
        boolean pinChanged = previous.pinnedRegionCount() != next.pinnedRegionCount();
        boolean significant = compressionChanged || profileChanged || maintenanceChanged || churnChanged || pinChanged;
        if (!significant) {
            return;
        }

        boolean major = compressionChanged || profileChanged || maintenanceChanged;
        long minIntervalNs = major ? POLICY_LOG_MIN_INTERVAL_NS / 2L : POLICY_LOG_MIN_INTERVAL_NS;
        if (nowNs - lastPolicyLogNs < minIntervalNs) {
            return;
        }
        lastPolicyLogNs = nowNs;

        String message = "[LinearReader] Storage health: mode=" + next.compressionMode().label
                + " profile=" + next.loadProfile().label
                + " quiet=" + Math.round(next.quietnessScore() * 100.0D) + "%"
                + " pressure=" + Math.round(next.pressureScore() * 100.0D) + "%"
                + " flush=" + next.flushBudgetPerTick()
                + " maint=" + (next.maintenanceAllowed() ? "on" : "off")
                + "/" + next.maintenanceBudgetFiles()
                + " debt=" + String.format("%.2f", next.maintenanceDebtScore())
                + " churn=" + String.format("%.2f", next.cacheChurnScore())
                + " pins=" + next.pinnedRegionCount();
        lastTransitionAtMs = currentTimeMs();
        lastTransitionSummary = "mode=" + next.compressionMode().label
                + " profile=" + next.loadProfile().label
                + " maint=" + (next.maintenanceAllowed() ? "on" : "off");
        lastTransitionDetail = "quiet=" + Math.round(next.quietnessScore() * 100.0D) + "%"
                + " pressure=" + Math.round(next.pressureScore() * 100.0D) + "%"
                + " flush=" + next.flushBudgetPerTick()
                + " debt=" + String.format("%.2f", next.maintenanceDebtScore())
                + " churn=" + String.format("%.2f", next.cacheChurnScore())
                + " pins=" + next.pinnedRegionCount();
        if (major) {
            LinearRuntime.LOGGER.info(message);
        } else {
            LinearRuntime.LOGGER.debug(message);
        }
    }

    private static int churnTier(double cacheChurnScore) {
        if (cacheChurnScore >= 2.0D) {
            return 2;
        }
        if (cacheChurnScore >= 1.0D) {
            return 1;
        }
        return 0;
    }

    static double pressureFlushPriority(Path normalizedRegionPath, long lastMutationNs, long nowNs) {
        double regionHeat = regionHeat(normalizedRegionPath, nowNs);
        double ageSeconds = clamp((nowNs - lastMutationNs) / 1_000_000_000.0D,
                0.0D, PRESSURE_FLUSH_AGE_CAP_SECONDS);
        double ageWeight = ageSeconds / 3.0D;
        double heatPenalty = 1.0D / (1.0D + Math.min(4.0D, regionHeat));
        return ageWeight * (1.0D + snapshot.pressureScore()) * (0.65D + heatPenalty);
    }

    private static double recompressPriority(Path normalizedRegionPath, boolean dirty,
                                             boolean flushing, long lastMutationNs) {
        if (dirty || flushing) {
            return Double.NEGATIVE_INFINITY;
        }

        long nowNs = nowNs();
        RegionActivity activity = REGION_ACTIVITY.get(normalizedRegionPath);
        double heat = activity == null ? 0.0D : activity.heat(nowNs);
        double compressionDebt = activity == null ? 0.0D : activity.compressionDebt(nowNs);

        long stabilityReferenceNs = lastMutationNs;
        if (stabilityReferenceNs <= 0L && activity != null) {
            stabilityReferenceNs = activity.lastWriteOrFlushNs(nowNs);
        }
        double stabilityScore = 1.0D;
        if (stabilityReferenceNs > 0L && nowNs > stabilityReferenceNs) {
            double stableMinutes = (nowNs - stabilityReferenceNs) / 60_000_000_000.0D;
            stabilityScore = clamp(0.50D + stableMinutes / 10.0D, 0.50D, 2.50D);
        }

        double coldnessScore = 1.0D / (1.0D + heat);
        double quietnessBonus = 0.75D + snapshot.quietnessScore();
        double debtBonus = 1.0D + Math.min(2.5D, compressionDebt);
        return stabilityScore * coldnessScore * quietnessBonus * debtBonus;
    }

    private static DebtSummary summarizeMaintenanceDebt(long nowNs) {
        double compressionDebt = 0.0D;
        for (RegionActivity activity : REGION_ACTIVITY.values()) {
            compressionDebt += activity.compressionDebt(nowNs);
        }

        double backupDebt = 0.0D;
        double dirtyDebt = 0.0D;
        for (LinearRegionFile region : LinearRegionFile.ALL_OPEN) {
            LinearRegionFile.MaintenanceDebtSnapshot regionDebt = region.maintenanceDebtSnapshot(nowNs);
            backupDebt += regionDebt.backupDebt();
            dirtyDebt += regionDebt.dirtyDebt();
        }

        return new DebtSummary(compressionDebt + backupDebt + dirtyDebt,
                compressionDebt, backupDebt, dirtyDebt);
    }

    private static double regionHeat(Path normalizedRegionPath, long nowNs) {
        RegionActivity activity = REGION_ACTIVITY.get(normalizedRegionPath);
        return activity == null ? 0.0D : activity.heat(nowNs);
    }

    private static RegionActivity regionActivity(Path regionPath) {
        return REGION_ACTIVITY.computeIfAbsent(normalize(regionPath), ignored -> new RegionActivity());
    }

    private static Path normalize(Path regionPath) {
        return regionPath.toAbsolutePath().normalize();
    }

    private static long computeResidentBudgetBytes() {
        long maxHeap = Runtime.getRuntime().maxMemory();
        if (maxHeap <= 0L || maxHeap == Long.MAX_VALUE) {
            return 256L * 1024L * 1024L;
        }
        long budget = maxHeap / 3L;
        long min = 128L * 1024L * 1024L;
        long max = 768L * 1024L * 1024L;
        return Math.max(min, Math.min(max, budget));
    }

    private static long computeMinHeapHeadroomBytes() {
        long maxHeap = Runtime.getRuntime().maxMemory();
        if (maxHeap <= 0L || maxHeap == Long.MAX_VALUE) {
            return 128L * 1024L * 1024L;
        }
        long headroom = maxHeap / 6L;
        long min = 128L * 1024L * 1024L;
        long max = 512L * 1024L * 1024L;
        return Math.max(min, Math.min(max, headroom));
    }

    private static long idleThresholdNs() {
        return LinearConfig.getIdleThresholdMinutes() * 60_000_000_000L;
    }

    private static long nowNs() {
        Long fixedNow = testNowNs;
        return fixedNow != null ? fixedNow : System.nanoTime();
    }

    private static long currentTimeMs() {
        Long fixedNow = testCurrentTimeMs;
        return fixedNow != null ? fixedNow : System.currentTimeMillis();
    }

    private static void pruneRegionActivity(long nowNs) {
        for (Iterator<Map.Entry<Path, RegionActivity>> it = REGION_ACTIVITY.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Path, RegionActivity> entry = it.next();
            if (entry.getValue().isColdAndExpired(nowNs)) {
                it.remove();
            }
        }
    }

    private static double ewma(double current, double sample, long elapsedNs, long timeConstantNs) {
        double alpha = 1.0D - Math.exp(-elapsedNs / (double) timeConstantNs);
        return current + (sample - current) * alpha;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    static record PolicySnapshot(int compressionLevel,
                                 int flushBudgetPerTick,
                                 double quietnessScore,
                                 boolean maintenanceAllowed,
                                 double pressureScore,
                                 double tickStrain,
                                 double chunkWriteRate,
                                 double flushLatencyMs,
                                 int backlog,
                                 int maintenanceBudgetFiles,
                                 double maintenanceDebtScore,
                                 double compressionDebtScore,
                                 double backupDebtScore,
                                 double dirtyDebtScore,
                                 CompressionMode compressionMode,
                                 LoadProfile loadProfile,
                                 double cacheChurnScore,
                                 int pinnedRegionCount,
                                 int residentHotSet,
                                 long residentTargetBytes) {
    }

    public record PolicyDebugSnapshot(String compressionMode,
                                      String loadProfile,
                                      int compressionLevel,
                                      int flushBudgetPerTick,
                                      double quietnessScore,
                                      double pressureScore,
                                      double tickStrain,
                                      boolean maintenanceAllowed,
                                      int maintenanceBudgetFiles,
                                      double maintenanceDebtScore,
                                      double compressionDebtScore,
                                      double backupDebtScore,
                                      double dirtyDebtScore,
                                      double chunkWriteRate,
                                      double flushLatencyMs,
                                      double cacheChurnScore,
                                      int backlog,
                                      long idleForMs,
                                      int pinnedRegionCount,
                                      int trackedRegions,
                                      int hotRegions,
                                      int coldRegions,
                                      long residentBudgetBytes,
                                      long residentTargetBytes,
                                      int residentHotSet,
                                      long residentTrimRuns,
                                      long residentTrimmedRegions,
                                      long residentTrimmedBytes,
                                      long lastTransitionAtMs,
                                      String lastTransitionSummary,
                                      String lastTransitionDetail) {
    }

    private record DebtSummary(double totalDebt, double compressionDebt,
                               double backupDebt, double dirtyDebt) {
    }

    private static final class RegionActivity {
        private double heat;
        private double compressionDebt;
        private long lastUpdateNs;
        private long lastSeenNs;
        private long lastWriteNs;
        private long lastFlushNs;

        synchronized void noteRead(long nowNs) {
            decayLocked(nowNs);
            heat += READ_HEAT;
            lastSeenNs = nowNs;
        }

        synchronized void noteWrite(long nowNs, int bytesWritten) {
            decayLocked(nowNs);
            heat += WRITE_HEAT_BASE + Math.min(WRITE_HEAT_MAX_BONUS, Math.max(0, bytesWritten) / WRITE_HEAT_BONUS_BYTES);
            lastSeenNs = nowNs;
            lastWriteNs = nowNs;
        }

        synchronized void noteFlush(long nowNs, long uncompressedBytes, long compressedBytes, int compressionLevel) {
            decayLocked(nowNs);
            heat += FLUSH_HEAT;
            compressionDebt = computeCompressionDebt(uncompressedBytes, compressedBytes, compressionLevel);
            lastSeenNs = nowNs;
            lastFlushNs = nowNs;
        }

        synchronized void noteRecompressed(long nowNs, int compressionLevel, long bytesSaved) {
            decayLocked(nowNs);
            if ((compressionLevel & 0xFF) >= IdleRecompressor.TARGET_LEVEL || bytesSaved <= 0L) {
                compressionDebt = 0.0D;
            } else {
                compressionDebt = Math.min(compressionDebt,
                        computeCompressionDebt(bytesSaved, Math.max(1L, bytesSaved), compressionLevel));
            }
            lastSeenNs = nowNs;
            lastFlushNs = nowNs;
        }

        synchronized double heat(long nowNs) {
            decayLocked(nowNs);
            return heat;
        }

        synchronized double compressionDebt(long nowNs) {
            decayLocked(nowNs);
            return compressionDebt;
        }

        synchronized long lastWriteOrFlushNs(long nowNs) {
            decayLocked(nowNs);
            return Math.max(lastWriteNs, lastFlushNs);
        }

        synchronized boolean isColdAndExpired(long nowNs) {
            decayLocked(nowNs);
            return lastSeenNs > 0L
                    && nowNs - lastSeenNs >= REGION_FORGET_NS
                    && heat < 0.10D;
        }

        private void decayLocked(long nowNs) {
            if (lastUpdateNs == 0L) {
                lastUpdateNs = nowNs;
                return;
            }
            long elapsedNs = nowNs - lastUpdateNs;
            if (elapsedNs <= 0L) {
                return;
            }
            heat *= Math.exp(-elapsedNs / (double) REGION_HEAT_DECAY_NS);
            compressionDebt *= Math.exp(-elapsedNs / (double) REGION_HEAT_DECAY_NS);
            lastUpdateNs = nowNs;
        }
    }

    private static double computeCompressionDebt(long uncompressedBytes, long compressedBytes, int compressionLevel) {
        int targetLevel = IdleRecompressor.TARGET_LEVEL;
        int levelGap = Math.max(0, targetLevel - Math.max(0, compressionLevel & 0xFF));
        if (levelGap == 0) return 0.0D;

        double levelFactor = levelGap / (double) Math.max(1, targetLevel - 1);
        long referenceBytes = Math.max(Math.max(0L, uncompressedBytes), Math.max(0L, compressedBytes));
        double sizeFactor = clamp(referenceBytes / COMPRESSION_DEBT_BYTES_SCALE, 0.25D, 3.0D);
        return levelFactor * sizeFactor;
    }

    public static double dirtyDebtScore(long dirtyAgeNs) {
        double ageSeconds = Math.max(0.0D, dirtyAgeNs / 1_000_000_000.0D);
        return clamp(ageSeconds / DIRTY_DEBT_AGE_SECONDS, 0.0D, 4.0D);
    }

    public static double backupDebtScore(int changedChunks, long changedBytes,
                                         boolean backedUp, boolean refreshQueued) {
        if (refreshQueued) {
            return 0.0D;
        }
        if (!backedUp) {
            return changedChunks > 0 || changedBytes > 0L ? 1.0D : 0.0D;
        }
        double chunkDebt = Math.max(0.0D, changedChunks / BACKUP_DEBT_CHUNK_SCALE);
        double byteDebt = Math.max(0.0D, changedBytes / BACKUP_DEBT_BYTES_SCALE);
        return clamp(Math.max(chunkDebt, byteDebt), 0.0D, 4.0D);
    }
}
