package com.bugfunbug.linearreader;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Lightweight, always-on statistics collector for LinearReader.
 *
 * Design choices:
 *   - LongAdder  for counters and sums: lower contention than AtomicLong when
 *     multiple chunk-worker threads write simultaneously (each thread has its
 *     own cell; values are summed only at read-time).
 *   - AtomicLong + CAS for min/max: lock-free, no object allocation.
 *   - All fields public so the bench command (a different package) can read
 *     them directly without wrapper allocations.
 */
public final class LinearStats {

    // Singleton — one stats object for the whole mod.
    public static final LinearStats INSTANCE = new LinearStats();

    // ------------------------------------------------------------------
    // Chunk-level I/O  (timed at the NBT read/write layer in the mixin)
    // ------------------------------------------------------------------
    public final LongAdder  chunkReads      = new LongAdder();
    public final LongAdder  chunkReadNs     = new LongAdder();
    public final AtomicLong minChunkReadNs  = new AtomicLong(Long.MAX_VALUE);
    public final AtomicLong maxChunkReadNs  = new AtomicLong(0);

    public final LongAdder  chunkWrites     = new LongAdder();
    public final LongAdder  chunkWriteNs    = new LongAdder();
    public final AtomicLong minChunkWriteNs = new AtomicLong(Long.MAX_VALUE);
    public final AtomicLong maxChunkWriteNs = new AtomicLong(0);

    // ------------------------------------------------------------------
    // Region-level I/O  (timed inside LinearRegionFile disk methods)
    // ------------------------------------------------------------------
    public final LongAdder  regionLoads      = new LongAdder();
    public final LongAdder  regionLoadNs     = new LongAdder();
    public final AtomicLong minRegionLoadNs  = new AtomicLong(Long.MAX_VALUE);
    public final AtomicLong maxRegionLoadNs  = new AtomicLong(0);

    public final LongAdder  regionFlushes    = new LongAdder();
    public final LongAdder  regionFlushNs    = new LongAdder();
    public final AtomicLong minRegionFlushNs = new AtomicLong(Long.MAX_VALUE);
    public final AtomicLong maxRegionFlushNs = new AtomicLong(0);

    // ------------------------------------------------------------------
    // Compression  (recorded once per flush)
    // ------------------------------------------------------------------
    public final LongAdder bytesUncompressed = new LongAdder(); // body before zstd
    public final LongAdder bytesCompressed   = new LongAdder(); // body after  zstd

    // ------------------------------------------------------------------
    // Cache  (hits = region already in cache; misses = had to open file)
    // ------------------------------------------------------------------
    public final LongAdder cacheHits   = new LongAdder();
    public final LongAdder cacheMisses = new LongAdder();
    public final LongAdder wrapperCacheHits   = new LongAdder();
    public final LongAdder wrapperCacheMisses = new LongAdder();
    public final LongAdder residentReloads    = new LongAdder();
    public final LongAdder residentEvictions  = new LongAdder();

    // Wall-clock time of the last reset (or mod init).
    public volatile long resetTimeMs = System.currentTimeMillis();

    private LinearStats() {}

    // ------------------------------------------------------------------
    // Record helpers — called from LinearRegionFile and the mixin.
    // ------------------------------------------------------------------

    public static void recordChunkRead(long elapsedNs) {
        LinearStats s = INSTANCE;
        s.chunkReads.increment();
        s.chunkReadNs.add(elapsedNs);
        updateMin(s.minChunkReadNs, elapsedNs);
        updateMax(s.maxChunkReadNs, elapsedNs);
    }

    public static void recordChunkWrite(long elapsedNs) {
        LinearStats s = INSTANCE;
        s.chunkWrites.increment();
        s.chunkWriteNs.add(elapsedNs);
        updateMin(s.minChunkWriteNs, elapsedNs);
        updateMax(s.maxChunkWriteNs, elapsedNs);
    }

    public static void recordRegionLoad(long elapsedNs) {
        LinearStats s = INSTANCE;
        s.regionLoads.increment();
        s.regionLoadNs.add(elapsedNs);
        updateMin(s.minRegionLoadNs, elapsedNs);
        updateMax(s.maxRegionLoadNs, elapsedNs);
    }

    public static void recordRegionFlush(long elapsedNs, long uncompressedBytes, long compressedBytes) {
        LinearStats s = INSTANCE;
        s.regionFlushes.increment();
        s.regionFlushNs.add(elapsedNs);
        updateMin(s.minRegionFlushNs, elapsedNs);
        updateMax(s.maxRegionFlushNs, elapsedNs);
        s.bytesUncompressed.add(uncompressedBytes);
        s.bytesCompressed.add(compressedBytes);
    }

    public static void recordCacheHit()  { INSTANCE.cacheHits.increment(); }
    public static void recordCacheMiss() { INSTANCE.cacheMisses.increment(); }
    public static void recordWrapperCacheHit()  { INSTANCE.wrapperCacheHits.increment(); }
    public static void recordWrapperCacheMiss() { INSTANCE.wrapperCacheMisses.increment(); }
    public static void recordResidentReload()   { INSTANCE.residentReloads.increment(); }
    public static void recordResidentEviction() { INSTANCE.residentEvictions.increment(); }

    // ------------------------------------------------------------------
    // Reset
    // ------------------------------------------------------------------

    public static void reset() {
        LinearStats s = INSTANCE;
        s.chunkReads.reset();     s.chunkReadNs.reset();
        s.minChunkReadNs.set(Long.MAX_VALUE); s.maxChunkReadNs.set(0);

        s.chunkWrites.reset();    s.chunkWriteNs.reset();
        s.minChunkWriteNs.set(Long.MAX_VALUE); s.maxChunkWriteNs.set(0);

        s.regionLoads.reset();    s.regionLoadNs.reset();
        s.minRegionLoadNs.set(Long.MAX_VALUE); s.maxRegionLoadNs.set(0);

        s.regionFlushes.reset();  s.regionFlushNs.reset();
        s.minRegionFlushNs.set(Long.MAX_VALUE); s.maxRegionFlushNs.set(0);

        s.bytesUncompressed.reset();
        s.bytesCompressed.reset();

        s.cacheHits.reset();
        s.cacheMisses.reset();
        s.wrapperCacheHits.reset();
        s.wrapperCacheMisses.reset();
        s.residentReloads.reset();
        s.residentEvictions.reset();

        s.resetTimeMs = System.currentTimeMillis();
    }

    // ------------------------------------------------------------------
    // Lock-free min / max helpers
    // ------------------------------------------------------------------

    private static void updateMin(AtomicLong cell, long value) {
        long cur;
        do { cur = cell.get(); } while (value < cur && !cell.compareAndSet(cur, value));
    }

    private static void updateMax(AtomicLong cell, long value) {
        long cur;
        do { cur = cell.get(); } while (value > cur && !cell.compareAndSet(cur, value));
    }

    // ------------------------------------------------------------------
    // Snapshot helpers used by the bench command
    // ------------------------------------------------------------------

    /** Returns ms/op average, or 0.0 if count == 0. */
    public static double avgMs(long totalNs, long count) {
        return count == 0 ? 0.0 : (totalNs / 1_000_000.0) / count;
    }

    /** Returns the raw nanosecond value as milliseconds, clamping Long.MAX_VALUE → 0. */
    public static double toMs(long ns) {
        return (ns == Long.MAX_VALUE || ns == 0) ? 0.0 : ns / 1_000_000.0;
    }

    /** Compression ratio as a percentage saved, e.g. 65.3 means the file is 34.7% of original. */
    public static double compressionPct(long uncompressed, long compressed) {
        if (uncompressed == 0) return 0.0;
        return (1.0 - (double) compressed / uncompressed) * 100.0;
    }

    /** Elapsed seconds since the last reset. */
    public static double uptimeSeconds() {
        return (System.currentTimeMillis() - INSTANCE.resetTimeMs) / 1000.0;
    }
}
