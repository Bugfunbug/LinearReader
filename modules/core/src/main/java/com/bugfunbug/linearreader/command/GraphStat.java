package com.bugfunbug.linearreader.command;

import com.bugfunbug.linearreader.LinearStats;
import com.bugfunbug.linearreader.StoragePolicyManager;

import java.util.function.Supplier;

/**
 * Every stat that {@link GraphSampler} can record.
 *
 * <h3>Two flavors of stat</h3>
 * <ul>
 *   <li><b>Per-interval</b> — computed as a delta between consecutive samples
 *       so the graph shows what happened <em>during each window</em> rather
 *       than a cumulative average.  The first call after
 *       {@link #resetDeltaState()} captures a baseline and returns
 *       {@link Double#NaN}; the SVG renderer skips NaN points.</li>
 *   <li><b>Instantaneous / running</b> — read directly from the current value.
 *       Running-max stats only increase, but a jump in the graph pinpoints when
 *       the worst event occurred.  StoragePolicyManager stats are already
 *       EWMA-smoothed and need no adjustment.</li>
 * </ul>
 *
 * <h3>Forward-reference note</h3>
 * Java prohibits simple-name references to static fields declared after the
 * enum constants that use them.  All mutable delta state therefore lives in
 * {@link DeltaState}, a static inner class, so the lambdas use qualified names
 * ({@code DeltaState.prevChunkReadNs}) and the restriction does not apply.
 */
public enum GraphStat {

    // ── Chunk-level I/O timing — PER INTERVAL ────────────────────────────────

    CHUNK_READ_AVG_MS("chunk_read_avg_ms", "Chunk Read Avg (ms, interval)", () -> {
        LinearStats s = LinearStats.INSTANCE;
        long ns    = s.chunkReadNs.sum();
        long count = s.chunkReads.sum();
        long pns   = DeltaState.prevChunkReadNs;
        long pc    = DeltaState.prevChunkReadCount;
        DeltaState.prevChunkReadNs    = ns;
        DeltaState.prevChunkReadCount = count;
        if (pns == DeltaState.UNSET) return Double.NaN;
        long dc = count - pc;
        // NaN = no reads this interval; 0.0 would falsely imply 0ms avg.
        return dc > 0 ? LinearStats.avgMs(ns - pns, dc) : Double.NaN;
    }),

    CHUNK_READ_MAX_MS("chunk_read_max_ms", "Chunk Read Max (ms, running)", () ->
            LinearStats.toMs(LinearStats.INSTANCE.maxChunkReadNs.get())),

    CHUNK_WRITE_AVG_MS("chunk_write_avg_ms", "Chunk Write Avg (ms, interval)", () -> {
        LinearStats s = LinearStats.INSTANCE;
        long ns    = s.chunkWriteNs.sum();
        long count = s.chunkWrites.sum();
        long pns   = DeltaState.prevChunkWriteNs;
        long pc    = DeltaState.prevChunkWriteCount;
        DeltaState.prevChunkWriteNs    = ns;
        DeltaState.prevChunkWriteCount = count;
        if (pns == DeltaState.UNSET) return Double.NaN;
        long dc = count - pc;
        return dc > 0 ? LinearStats.avgMs(ns - pns, dc) : Double.NaN;
    }),

    CHUNK_WRITE_MAX_MS("chunk_write_max_ms", "Chunk Write Max (ms, running)", () ->
            LinearStats.toMs(LinearStats.INSTANCE.maxChunkWriteNs.get())),

    // ── Region-level I/O timing — PER INTERVAL ───────────────────────────────

    REGION_LOAD_AVG_MS("region_load_avg_ms", "Region Load Avg (ms, interval)", () -> {
        LinearStats s = LinearStats.INSTANCE;
        long ns    = s.regionLoadNs.sum();
        long count = s.regionLoads.sum();
        long pns   = DeltaState.prevRegionLoadNs;
        long pc    = DeltaState.prevRegionLoadCount;
        DeltaState.prevRegionLoadNs    = ns;
        DeltaState.prevRegionLoadCount = count;
        if (pns == DeltaState.UNSET) return Double.NaN;
        long dc = count - pc;
        return dc > 0 ? LinearStats.avgMs(ns - pns, dc) : Double.NaN;
    }),

    REGION_LOAD_MAX_MS("region_load_max_ms", "Region Load Max (ms, running)", () ->
            LinearStats.toMs(LinearStats.INSTANCE.maxRegionLoadNs.get())),

    REGION_FLUSH_AVG_MS("region_flush_avg_ms", "Region Flush Avg (ms, interval)", () -> {
        LinearStats s = LinearStats.INSTANCE;
        long ns    = s.regionFlushNs.sum();
        long count = s.regionFlushes.sum();
        long pns   = DeltaState.prevRegionFlushNs;
        long pc    = DeltaState.prevRegionFlushCount;
        DeltaState.prevRegionFlushNs    = ns;
        DeltaState.prevRegionFlushCount = count;
        if (pns == DeltaState.UNSET) return Double.NaN;
        long dc = count - pc;
        return dc > 0 ? LinearStats.avgMs(ns - pns, dc) : Double.NaN;
    }),

    REGION_FLUSH_MAX_MS("region_flush_max_ms", "Region Flush Max (ms, running)", () ->
            LinearStats.toMs(LinearStats.INSTANCE.maxRegionFlushNs.get())),

    // ── Cache — PER INTERVAL ──────────────────────────────────────────────────

    CACHE_HIT_RATE("cache_hit_rate", "Cache Hit Rate (interval, 0–1)", () -> {
        LinearStats s = LinearStats.INSTANCE;
        long hits   = s.cacheHits.sum();
        long misses = s.cacheMisses.sum();
        long ph = DeltaState.prevCacheHits;
        long pm = DeltaState.prevCacheMisses;
        DeltaState.prevCacheHits   = hits;
        DeltaState.prevCacheMisses = misses;
        if (ph == DeltaState.UNSET) return Double.NaN;
        long dHits  = hits   - ph;
        long dTotal = dHits  + (misses - pm);
        // NaN = no cache accesses this interval; 0.0 would falsely imply 0% hit rate.
        return dTotal > 0 ? (double) dHits / dTotal : Double.NaN;
    }),

    // ── Compression — cumulative (correct view for compression quality) ───────

    COMPRESSION_RATIO("compression_ratio", "Compression Ratio (%)", () -> {
        LinearStats s = LinearStats.INSTANCE;
        return LinearStats.compressionPct(s.bytesUncompressed.sum(), s.bytesCompressed.sum());
    }),

    // ── Throughput — PER INTERVAL ─────────────────────────────────────────────

    CHUNK_READS_PER_SEC("chunk_reads_per_sec", "Chunk Reads/sec (interval)", () -> {
        long nowMs = System.currentTimeMillis();
        long count = LinearStats.INSTANCE.chunkReads.sum();
        long pc    = DeltaState.prevChunkReadCountRate;
        long pt    = DeltaState.prevChunkReadTimeMs;
        DeltaState.prevChunkReadCountRate = count;
        DeltaState.prevChunkReadTimeMs    = nowMs;
        if (pc == DeltaState.UNSET) return Double.NaN;
        double dtSec = (nowMs - pt) / 1000.0;
        return dtSec > 0 ? Math.max(0, count - pc) / dtSec : 0.0;
    }),

    CHUNK_WRITES_PER_SEC("chunk_writes_per_sec", "Chunk Writes/sec (interval)", () -> {
        long nowMs = System.currentTimeMillis();
        long count = LinearStats.INSTANCE.chunkWrites.sum();
        long pc    = DeltaState.prevChunkWriteCountRate;
        long pt    = DeltaState.prevChunkWriteTimeMs;
        DeltaState.prevChunkWriteCountRate = count;
        DeltaState.prevChunkWriteTimeMs    = nowMs;
        if (pc == DeltaState.UNSET) return Double.NaN;
        double dtSec = (nowMs - pt) / 1000.0;
        return dtSec > 0 ? Math.max(0, count - pc) / dtSec : 0.0;
    }),

    // ── Server health — EWMA / instantaneous (StoragePolicyManager) ───────────

    QUIETNESS_SCORE("quietness_score", "Quietness Score (0–1)", () ->
            StoragePolicyManager.debugSnapshot().quietnessScore()),

    PRESSURE_SCORE("pressure_score", "Pressure Score (0–1)", () ->
            StoragePolicyManager.debugSnapshot().pressureScore()),

    TICK_STRAIN("tick_strain", "Tick Strain (0–1)", () ->
            StoragePolicyManager.debugSnapshot().tickStrain()),

    // ── Flush pipeline — EWMA / instantaneous ────────────────────────────────

    FLUSH_LATENCY_MS("flush_latency_ms", "Flush Latency EWMA (ms)", () ->
            StoragePolicyManager.debugSnapshot().flushLatencyMs()),

    BACKLOG("backlog", "Flush Backlog (regions)", () ->
            (double) StoragePolicyManager.debugSnapshot().backlog()),

    // ── Maintenance — current debt score ─────────────────────────────────────

    MAINTENANCE_DEBT("maintenance_debt", "Maintenance Debt", () ->
            StoragePolicyManager.debugSnapshot().maintenanceDebtScore());

    // ── Delta state ───────────────────────────────────────────────────────────

    /**
     * Holds all mutable per-interval baseline values.
     *
     * <p>Kept in a static inner class so that the enum constant lambdas can
     * reference these fields using qualified names ({@code DeltaState.foo}),
     * working around the Java forward-reference restriction that prevents enum
     * constants from using simple names to access static fields declared later
     * in the same enum body.
     *
     * <p>Fields are written and read only on the {@link GraphSampler} thread,
     * so {@code volatile} is sufficient; no locking is needed.
     */
    static final class DeltaState {

        private DeltaState() {}

        /** Sentinel value meaning "not yet initialized for this recording". */
        static final long UNSET = Long.MIN_VALUE;

        static volatile long prevChunkReadNs         = UNSET;
        static volatile long prevChunkReadCount      = UNSET;
        static volatile long prevChunkWriteNs        = UNSET;
        static volatile long prevChunkWriteCount     = UNSET;
        static volatile long prevRegionLoadNs        = UNSET;
        static volatile long prevRegionLoadCount     = UNSET;
        static volatile long prevRegionFlushNs       = UNSET;
        static volatile long prevRegionFlushCount    = UNSET;
        static volatile long prevCacheHits           = UNSET;
        static volatile long prevCacheMisses         = UNSET;
        static volatile long prevChunkReadCountRate  = UNSET;
        static volatile long prevChunkReadTimeMs     = 0L;
        static volatile long prevChunkWriteCountRate = UNSET;
        static volatile long prevChunkWriteTimeMs    = 0L;

        static void reset() {
            prevChunkReadNs         = UNSET;
            prevChunkReadCount      = UNSET;
            prevChunkWriteNs        = UNSET;
            prevChunkWriteCount     = UNSET;
            prevRegionLoadNs        = UNSET;
            prevRegionLoadCount     = UNSET;
            prevRegionFlushNs       = UNSET;
            prevRegionFlushCount    = UNSET;
            prevCacheHits           = UNSET;
            prevCacheMisses         = UNSET;
            prevChunkReadCountRate  = UNSET;
            prevChunkReadTimeMs     = 0L;
            prevChunkWriteCountRate = UNSET;
            prevChunkWriteTimeMs    = 0L;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────

    private final String key;
    private final String label;
    private final Supplier<Double> supplier;

    GraphStat(String key, String label, Supplier<Double> supplier) {
        this.key = key;
        this.label = label;
        this.supplier = supplier;
    }

    public String getKey()   { return key; }
    public String getLabel() { return label; }

    /**
     * Reads the current value.  Returns {@link Double#NaN} on the first call
     * of a new recording for per-interval stats (baseline-capture call, called
     * during warmup by {@link GraphSampler}).
     */
    public double sample() {
        try {
            Double v = supplier.get();
            return v == null ? Double.NaN : v;
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    /**
     * Resets all per-interval delta baselines.  Must be called at the start of
     * each new recording.  Delegates to {@link DeltaState#reset()}.
     */
    public static void resetDeltaState() {
        DeltaState.reset();
    }

    public static GraphStat fromKey(String key) {
        for (GraphStat s : values()) {
            if (s.key.equals(key)) return s;
        }
        return null;
    }
}