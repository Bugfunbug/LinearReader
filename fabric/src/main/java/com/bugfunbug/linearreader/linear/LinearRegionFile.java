package com.bugfunbug.linearreader.linear;

import com.bugfunbug.linearreader.LinearReader;
import com.bugfunbug.linearreader.LinearStats;
import com.bugfunbug.linearreader.config.LinearConfig;
import net.minecraft.world.level.ChunkPos;

import org.jetbrains.annotations.Nullable;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.zip.CRC32;

public class LinearRegionFile {

    /** Every LinearRegionFile that is currently open. Used for flush-on-save and the info command. */
    public static final Set<LinearRegionFile> ALL_OPEN =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    private static final long LINEAR_SIGNATURE  = 0xc3ff13183cca9d9aL;
    private static final byte LINEAR_VERSION    = 1;
    private static final int  REGION_DIM        = 32;
    private static final int  CHUNK_COUNT       = REGION_DIM * REGION_DIM; // 1024
    private static final int  INNER_HEADER_SIZE = CHUNK_COUNT * 8;         // 8192 bytes

    // Outer file header layout (32 bytes total):
    //   0  –  7 : LINEAR_SIGNATURE  (long)
    //   8        : version           (byte)
    //   9  – 16 : newest timestamp  (long)
    //  17        : compression level (byte)
    //  18 – 19  : chunk count       (short)
    //  20 – 23  : compressed length (int)
    //  24 – 31  : CRC32 of compressed body as long (upper 32 bits = 0); 0 = no checksum

    // One CRC32 per thread — avoids allocating a new CRC32 on every flush/verify.
    private static final ThreadLocal<CRC32> TL_CRC32 = ThreadLocal.withInitial(CRC32::new);

    /**
     * Single-thread executor for backup writes.
     * Backups run at a higher compression level than live files, so write time is
     * significant — pushing them off the flush thread eliminates flush latency spikes.
     */
    private static volatile java.util.concurrent.ExecutorService backupExecutor = createBackupExecutor();

    /** Compression level for .bak files — higher than live since write speed is irrelevant. */
    private static final int BACKUP_COMPRESSION_LEVEL = 22;

    // Reusable byte-array buffers per flush thread — dramatically reduces GC pressure
    // under heavy worldgen where flushes happen constantly.
    private static final ThreadLocal<byte[][]> TL_FLUSH_BUFS =
            ThreadLocal.withInitial(() -> new byte[2][]);

    // Throttle the disk-space syscall to at most once per minute per flush thread.
    private static final ThreadLocal<Long> TL_LAST_DISK_CHECK =
            ThreadLocal.withInitial(() -> 0L);

    /**
     * Unlike vanilla, an open LinearRegionFile keeps decompressed chunk payloads in
     * memory. A vanilla-sized cache across chunks, entities, and POI can easily
     * balloon into hundreds of megabytes or more and trigger full-GC stalls.
     */
    private static final long RESIDENT_BUDGET_BYTES = computeResidentBudgetBytes();
    private static final long RESIDENT_TARGET_BYTES = Math.max(RESIDENT_BUDGET_BYTES * 3L / 4L, 64L * 1024L * 1024L);
    private static final long MIN_HEAP_HEADROOM_BYTES = computeMinHeapHeadroomBytes();
    private static final long RESIDENT_TRIM_RECENT_ACCESS_NS = 45_000_000_000L;
    private static final long RESIDENT_TRIM_MIN_INTERVAL_NS = 2_000_000_000L;
    private static final int  MIN_RESIDENT_HOT_SET = 24;
    private static final AtomicLong LAST_RESIDENT_TRIM_NS = new AtomicLong(0L);
    // Background flushes should target regions that have actually gone quiet, not hot ones.
    private static final long QUIET_FLUSH_DELAY_NS = 15_000_000_000L;
    private static final long PRESSURE_FLUSH_MIN_AGE_NS = 3_000_000_000L;
    private static final long BACKGROUND_FLUSH_COOLDOWN_NS = 15_000_000_000L;

    // Read-write lock: multiple threads can read chunks concurrently; writes are exclusive.
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private final Path    path;
    private final Path    normalizedPath;
    private final boolean dsync;

    public final int regionX;
    public final int regionZ;

    /**
     * Rewritten chunks live as standalone byte arrays here.
     * Chunks loaded from disk can instead stay as slices of {@link #loadedBody}
     * until they are modified, avoiding 1024 allocations and copies per load.
     */
    private final byte[][] chunkData    = new byte[CHUNK_COUNT][];
    private final int[]    chunkSizes   = new int[CHUNK_COUNT];
    private final int[]    chunkOffsets = new int[CHUNK_COUNT];
    private final int[]    timestamps   = new int[CHUNK_COUNT];

    // Running counters updated incrementally on every chunk write,
    // eliminating the O(1024) scans the original code did on every flush.
    private int  chunkCount      = 0;
    private long totalDataBytes  = 0L;
    private long newestTimestamp = 0L;

    // volatile so isDirty() is always fresh across threads without a lock.
    private volatile boolean dirty = false;
    private volatile boolean flushing = false;
    private volatile long materializedBytes = 0L;
    private volatile long lastAccessNs = System.nanoTime();
    private volatile long lastMutationNs = lastAccessNs;
    private volatile long lastSuccessfulFlushNs = 0L;

    private int     saveCount = 0;
    private boolean backedUp  = false;

    // volatile so the fast-path check in loadIfNeeded() is always fresh without a lock.
    private volatile boolean loaded = false;

    /**
     * Decompressed region body loaded from disk. Untouched chunks are served as
     * slices of this array via {@link ByteArrayInputStream#ByteArrayInputStream(byte[], int, int)}.
     */
    @Nullable
    private byte[] loadedBody;

    public LinearRegionFile(Path path, boolean dsync) throws IOException {
        this.path  = path;
        this.normalizedPath = path.toAbsolutePath().normalize();
        this.dsync = dsync;

        String[] parts = path.getFileName().toString().split("\\.");
        this.regionX = Integer.parseInt(parts[1]);
        this.regionZ = Integer.parseInt(parts[2]);

        // Do NOT load from disk here. The constructor is called from inside the
        // synchronized linearGetOrCreate(), so any disk I/O here would hold the
        // coarse RegionFileStorage lock for the entire load duration — serializing
        // all chunk I/O for the dimension. Loading happens lazily via loadIfNeeded().
        ALL_OPEN.add(this);
    }

    /**
     * Loads this region's chunk data from disk on first access.
     * Uses the region's own write lock (not the coarse RegionFileStorage lock),
     * so only threads accessing THIS region are serialized — other regions load
     * and serve chunks concurrently. The write lock is released before any caller
     * proceeds to do actual chunk reads (which use the read lock), so read
     * concurrency is fully preserved after the initial load completes.
     * The volatile {@link #loaded} flag is the fast-path: once true, this method
     * returns in nanoseconds without acquiring any lock.
     */
    private void loadIfNeeded() throws IOException {
        if (loaded) return; // volatile read — no lock needed for the negative case
        boolean becameLoaded = false;
        boolean reloadedFromDisk = false;
        lock.writeLock().lock();
        try {
            if (loaded) return; // double-check inside lock
            if (Files.exists(path)) {
                boolean ok = tryLoadOrRecover();
                if (!ok) {
                    LinearReader.LOGGER.error(
                            "[LinearReader] r.{}.{}.linear could not be recovered. " +
                                    "The region will be regenerated by Minecraft.", regionX, regionZ);
                } else if (DHPregenMonitor.isBackupEnabled()) {
                    backedUp = Files.exists(bakPath());
                }
                reloadedFromDisk = ok;
            }
            // Mark loaded regardless of whether the file existed — an absent file
            // means a brand-new region; we start empty and dirty=false.
            loaded = true;
            becameLoaded = true;
        } finally {
            lock.writeLock().unlock();
        }
        if (becameLoaded) {
            if (reloadedFromDisk) {
                LinearStats.recordResidentReload();
            }
            markAccessed();
            trimResidentDataCache(this);
        }
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    private static int indexOf(ChunkPos pos) {
        return (pos.x & 31) + (pos.z & 31) * REGION_DIM;
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

    private static java.util.concurrent.ExecutorService createBackupExecutor() {
        return java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "lr-backup");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY + 1);
            return t;
        });
    }

    private static synchronized java.util.concurrent.ExecutorService getBackupExecutor() {
        if (backupExecutor == null || backupExecutor.isShutdown() || backupExecutor.isTerminated()) {
            backupExecutor = createBackupExecutor();
        }
        return backupExecutor;
    }

    private void markAccessed() {
        lastAccessNs = System.nanoTime();
    }

    private void markDirtyNow() {
        dirty = true;
        lastMutationNs = System.nanoTime();
    }

    private int chunkLength(int idx) {
        byte[] direct = chunkData[idx];
        return direct != null ? direct.length : chunkSizes[idx];
    }

    private boolean chunkPresent(int idx) {
        return chunkLength(idx) > 0;
    }

    private long residentBytesEstimate() {
        if (!loaded) return 0L;
        byte[] body = loadedBody;
        return (body != null ? body.length : 0L) + materializedBytes;
    }

    private boolean isResidentTrimCandidate() {
        return loaded
                && !dirty
                && !flushing
                && !LinearReader.isPinnedNormalized(normalizedPath)
                && System.nanoTime() - lastAccessNs >= RESIDENT_TRIM_RECENT_ACCESS_NS;
    }

    private long releaseResidentDataIfPossible() {
        lock.writeLock().lock();
        try {
            if (!loaded || dirty || flushing || LinearReader.isPinnedNormalized(normalizedPath)) return 0L;
            long freed = residentBytesEstimate();
            Arrays.fill(chunkData, null);
            Arrays.fill(chunkSizes, 0);
            Arrays.fill(chunkOffsets, 0);
            Arrays.fill(timestamps, 0);
            loadedBody = null;
            chunkCount = 0;
            totalDataBytes = 0L;
            newestTimestamp = 0L;
            materializedBytes = 0L;
            loaded = false;
            LinearStats.recordResidentEviction();
            return freed;
        } finally {
            lock.writeLock().unlock();
        }
    }

    private static void trimResidentDataCache(@Nullable LinearRegionFile keep) {
        long nowNs = System.nanoTime();
        Runtime runtime = Runtime.getRuntime();
        long maxHeap = runtime.maxMemory();
        long usedHeap = runtime.totalMemory() - runtime.freeMemory();
        long heapHeadroom = maxHeap > 0L && maxHeap != Long.MAX_VALUE
                ? Math.max(0L, maxHeap - usedHeap)
                : Long.MAX_VALUE;
        boolean heapPressure = heapHeadroom < MIN_HEAP_HEADROOM_BYTES;
        long lastTrimNs = LAST_RESIDENT_TRIM_NS.get();
        if (!heapPressure && nowNs - lastTrimNs < RESIDENT_TRIM_MIN_INTERVAL_NS) return;
        if (!heapPressure && !LAST_RESIDENT_TRIM_NS.compareAndSet(lastTrimNs, nowNs)) return;

        long residentBytes = 0L;
        List<LinearRegionFile> candidates = new ArrayList<>();
        for (LinearRegionFile region : ALL_OPEN) {
            residentBytes += region.residentBytesEstimate();
            if (region != keep && region.isResidentTrimCandidate()) {
                candidates.add(region);
            }
        }
        if (!heapPressure && residentBytes <= RESIDENT_BUDGET_BYTES) return;

        candidates.sort(Comparator.comparingLong(region -> region.lastAccessNs));
        int trimLimit = Math.max(0, candidates.size() - MIN_RESIDENT_HOT_SET);
        for (int i = 0; i < trimLimit; i++) {
            LinearRegionFile candidate = candidates.get(i);
            if (nowNs - candidate.lastAccessNs < RESIDENT_TRIM_RECENT_ACCESS_NS) continue;
            if (!heapPressure && residentBytes <= RESIDENT_TARGET_BYTES) break;
            residentBytes -= candidate.releaseResidentDataIfPossible();
        }
    }

    public boolean shouldBackgroundFlush(long nowNs) {
        return dirty
                && !flushing
                && nowNs - lastMutationNs >= QUIET_FLUSH_DELAY_NS
                && nowNs - lastSuccessfulFlushNs >= BACKGROUND_FLUSH_COOLDOWN_NS;
    }

    public boolean shouldPressureFlush(long nowNs) {
        return dirty
                && !flushing
                && nowNs - lastMutationNs >= PRESSURE_FLUSH_MIN_AGE_NS
                && nowNs - lastSuccessfulFlushNs >= BACKGROUND_FLUSH_COOLDOWN_NS;
    }

    public long lastMutationTimeNs() {
        return lastMutationNs;
    }

    public int storedChunkBytes(int localIndex) throws IOException {
        if (localIndex < 0 || localIndex >= CHUNK_COUNT) {
            throw new IllegalArgumentException("local chunk index out of range: " + localIndex);
        }
        loadIfNeeded();
        lock.readLock().lock();
        try {
            return chunkLength(localIndex);
        } finally {
            lock.readLock().unlock();
        }
    }

    public long estimateFileSizeAfterRemoving(BitSet localChunkBits) throws IOException {
        loadIfNeeded();
        markAccessed();

        final byte[][] dataSnap;
        final int[] sizeSnap;
        final int[] offsetSnap;
        final int[] tsSnap;
        final int countSnapStart;
        final long totalBytesSnapStart;
        final byte[] bodySnap;

        lock.readLock().lock();
        try {
            dataSnap = chunkData.clone();
            sizeSnap = chunkSizes.clone();
            offsetSnap = chunkOffsets.clone();
            tsSnap = timestamps.clone();
            countSnapStart = chunkCount;
            totalBytesSnapStart = totalDataBytes;
            bodySnap = loadedBody;
        } finally {
            lock.readLock().unlock();
        }

        int countSnap = countSnapStart;
        long totalBytesSnap = totalBytesSnapStart;
        for (int idx = localChunkBits.nextSetBit(0); idx >= 0; idx = localChunkBits.nextSetBit(idx + 1)) {
            int oldLen = dataSnap[idx] != null ? dataSnap[idx].length : sizeSnap[idx];
            if (oldLen <= 0) continue;
            dataSnap[idx] = null;
            sizeSnap[idx] = 0;
            offsetSnap[idx] = 0;
            tsSnap[idx] = 0;
            countSnap--;
            totalBytesSnap -= oldLen;
        }

        return estimateSerializedFileSize(dataSnap, sizeSnap, offsetSnap, tsSnap, bodySnap, countSnap, totalBytesSnap);
    }

    public boolean canEvictFromCache() {
        return !dirty && !flushing;
    }

    public boolean hasChunk(ChunkPos pos) {
        try {
            loadIfNeeded();
        } catch (IOException e) {
            LinearReader.LOGGER.error("[LinearReader] Failed to load region in hasChunk for {}: {}",
                    pos, e.getMessage());
            return false;
        }
        markAccessed();
        lock.readLock().lock();
        try {
            return chunkPresent(indexOf(pos));
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Removes a chunk entry from this region.
     * Called when c2me's backlog flush invokes RegionFile.clear(ChunkPos).
     */
    public void clearChunk(ChunkPos pos) {
        try {
            loadIfNeeded();
        } catch (IOException e) {
            LinearReader.LOGGER.error("[LinearReader] Failed to load region in clearChunk for {}: {}",
                    pos, e.getMessage());
            return;
        }
        markAccessed();
        lock.writeLock().lock();
        try {
            int idx = indexOf(pos);
            int oldLen = chunkLength(idx);
            byte[] oldDirect = chunkData[idx];
            int oldDirectLen = oldDirect != null ? oldDirect.length : 0;
            if (oldLen > 0) {
                chunkData[idx] = null;
                chunkSizes[idx] = 0;
                chunkOffsets[idx] = 0;
                timestamps[idx] = 0;
                chunkCount--;
                totalDataBytes -= oldLen;
                materializedBytes -= oldDirectLen;
                markDirtyNow();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Removes multiple chunk entries from this region under one write lock.
     *
     * @return number of chunks deleted, or -1 if the region became unstable before the lock was acquired.
     */
    public int clearChunksIfUnchanged(BitSet localChunkBits, long maxMutationTimeNs) throws IOException {
        if (localChunkBits.isEmpty()) return 0;

        loadIfNeeded();
        markAccessed();
        lock.writeLock().lock();
        try {
            if (flushing || lastMutationNs > maxMutationTimeNs) {
                return -1;
            }

            int cleared = 0;
            for (int idx = localChunkBits.nextSetBit(0); idx >= 0; idx = localChunkBits.nextSetBit(idx + 1)) {
                int oldLen = chunkLength(idx);
                byte[] oldDirect = chunkData[idx];
                int oldDirectLen = oldDirect != null ? oldDirect.length : 0;
                if (oldLen <= 0) continue;

                chunkData[idx] = null;
                chunkSizes[idx] = 0;
                chunkOffsets[idx] = 0;
                timestamps[idx] = 0;
                chunkCount--;
                totalDataBytes -= oldLen;
                materializedBytes -= oldDirectLen;
                cleared++;
            }

            if (cleared > 0) {
                markDirtyNow();
            }
            return cleared;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Nullable
    public DataInputStream read(ChunkPos pos) throws IOException {
        loadIfNeeded(); // outside read lock — loadIfNeeded uses write lock internally
        markAccessed();
        lock.readLock().lock();
        try {
            int idx = indexOf(pos);
            byte[] direct = chunkData[idx];
            int len = direct != null ? direct.length : chunkSizes[idx];
            if (len == 0) return null;
            long t = System.nanoTime();
            ByteArrayInputStream in;
            if (direct != null) {
                in = new ByteArrayInputStream(direct);
            } else {
                byte[] body = loadedBody;
                if (body == null) return null;
                in = new ByteArrayInputStream(body, chunkOffsets[idx], len);
            }
            DataInputStream dis = new DataInputStream(in);
            LinearStats.recordChunkRead(System.nanoTime() - t);
            return dis;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Returns a DataOutputStream that commits chunk data into this region when closed.
     * NBT serialisation happens entirely outside any lock; the write lock is taken
     * only for the brief pointer-swap at the end of close().
     */
    public DataOutputStream write(ChunkPos pos) throws IOException {
        // Load existing chunks before writing so we don't lose data in other slots.
        // This must happen before creating the ByteArrayOutputStream so that if
        // loading fails we throw immediately rather than silently losing the write.
        loadIfNeeded();
        markAccessed();

        final int idx = indexOf(pos);
        int hint = Math.max(chunkLength(idx), 8192);

        ByteArrayOutputStream boas = new ByteArrayOutputStream(hint) {
            @Override
            public void close() throws IOException {
                super.close();
                byte[] newData = toByteArray();
                int ts = (int) (System.currentTimeMillis() / 1000L);
                lock.writeLock().lock();
                try {
                    byte[] oldDirect = chunkData[idx];
                    int oldDirectLen = oldDirect != null ? oldDirect.length : 0;
                    int oldLen = chunkLength(idx);

                    chunkData[idx] = newData;
                    chunkSizes[idx] = newData.length;
                    chunkOffsets[idx] = 0;
                    timestamps[idx] = ts;
                    if (ts > newestTimestamp) newestTimestamp = ts;

                    if (oldLen == 0 && newData.length > 0) chunkCount++;
                    else if (oldLen > 0 && newData.length == 0) chunkCount--;
                    totalDataBytes += newData.length - oldLen;
                    materializedBytes += newData.length - oldDirectLen;

                    markDirtyNow();
                } finally {
                    lock.writeLock().unlock();
                }
            }
        };
        return new DataOutputStream(boas);
    }

    /**
     * Flushes this region to disk if dirty.
     *
     * The data snapshot is taken under the write lock, the lock is released, and
     * the heavy compression + I/O work proceeds lock-free.  This lets concurrent
     * chunk reads and writes continue while a flush is in progress.
     */
    public void flush() throws IOException {
        flush(true);
    }

    public void flush(boolean allowBackup) throws IOException {
        if (!dirty) return; // fast volatile read before acquiring any lock

        final long snapshotStartNs = System.nanoTime();
        final byte[][] dataSnap;
        final int[]    sizeSnap;
        final int[]    offsetSnap;
        final int[]    tsSnap;
        final long     newestTsSnap;
        final int      countSnap;
        final long     totalBytesSnap;
        final byte[]   bodySnap;

        lock.writeLock().lock();
        try {
            if (!dirty) return; // double-check after lock
            dataSnap = chunkData.clone();
            sizeSnap = chunkSizes.clone();
            offsetSnap = chunkOffsets.clone();
            tsSnap = timestamps.clone();
            newestTsSnap = newestTimestamp;
            countSnap = chunkCount;
            totalBytesSnap = totalDataBytes;
            bodySnap = loadedBody;
            flushing = true;
            dirty = false;
        } finally {
            lock.writeLock().unlock();
        }

        try {
            boolean ioOk = false;
            try {
                long snapshotNs = System.nanoTime() - snapshotStartNs;
                writeToDisk(dataSnap, sizeSnap, offsetSnap, tsSnap, bodySnap,
                        newestTsSnap, countSnap, totalBytesSnap, snapshotNs);
                ioOk = true;
                lastSuccessfulFlushNs = System.nanoTime();
            } finally {
                if (!ioOk) {
                    lock.writeLock().lock();
                    try { dirty = true; } finally { lock.writeLock().unlock(); }
                }
            }

            // Backups are best-effort and are intentionally skipped on hot background flushes.
            if (allowBackup) {
                final boolean shouldCreateBackup;
                final boolean shouldRefreshBackup;
                lock.writeLock().lock();
                try {
                    saveCount++;
                    shouldCreateBackup = DHPregenMonitor.isBackupEnabled() && !backedUp;
                    shouldRefreshBackup = DHPregenMonitor.isBackupEnabled()
                            && backedUp
                            && saveCount % LinearConfig.getBackupUpdateInterval() == 0;
                } finally {
                    lock.writeLock().unlock();
                }

                if (shouldCreateBackup) createBackupIfAbsent();
                if (shouldRefreshBackup) refreshBackup();
            }
        } finally {
            flushing = false;
        }
        trimResidentDataCache(this);
    }

    public void close() throws IOException {
        ALL_OPEN.remove(this);
        flush();
    }

    /**
     * Releases all chunk byte arrays so the GC can reclaim the NBT payload.
     *
     * <b>Call only after the region has been flushed to disk and evicted from
     * {@code linearCache}</b> — i.e. from {@link com.bugfunbug.linearreader.LinearReader#submitFlush}
     * after the flush task completes.  Calling this on a live cache entry would
     * cause NPEs on the next read of any chunk in the region.
     *
     * During DH pregen the cache is kept at 8 entries, so regions are evicted
     * and flushed frequently.  Without this call each flushed region's ~8 MB of
     * NBT data would sit in old-gen until the next full GC — enough to trigger
     * multi-second GC pauses that the watchdog mistakes for a server hang.
     */
    public void releaseChunkData() {
        lock.writeLock().lock();
        try {
            Arrays.fill(chunkData, null);
            Arrays.fill(chunkSizes, 0);
            Arrays.fill(chunkOffsets, 0);
            Arrays.fill(timestamps, 0);
            loadedBody = null;
            chunkCount     = 0;
            totalDataBytes = 0L;
            newestTimestamp = 0L;
            materializedBytes = 0L;
            loaded = false;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** O(1) — backed by a running counter. */
    public long estimateRamBytes() {
        return totalDataBytes;
    }

    public Path    getPath()           { return path; }
    public Path    getNormalizedPath() { return normalizedPath; }
    public boolean isDirty()           { return dirty; }
    public boolean isFlushing()        { return flushing; }

    @Override
    public String toString() {
        return "LinearRegionFile{r." + regionX + "." + regionZ + ", dirty=" + dirty + "}";
    }

    // -------------------------------------------------------------------------
    // Corruption recovery
    // -------------------------------------------------------------------------

    private boolean tryLoadOrRecover() {
        try {
            loadFromDisk(path);
            return true;
        } catch (IOException primaryEx) {
            LinearReader.LOGGER.error(
                    "[LinearReader] Failed to load {}: {}", path.getFileName(), primaryEx.getMessage());
        }

        // ── Quarantine the corrupt file ───────────────────────────────────────
        // Include a timestamp so multiple corruptions of the same region don't
        // overwrite each other in the quarantine folder.
        String timestamp = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path quarantineDir  = path.getParent().resolve("corrupted");
        String corruptName  = "r." + regionX + "." + regionZ
                + "-" + timestamp + ".corrupt.linear";
        Path quarantinePath = quarantineDir.resolve(corruptName);

        try {
            Files.createDirectories(quarantineDir);
            Files.move(path, quarantinePath, StandardCopyOption.REPLACE_EXISTING);
            LinearReader.LOGGER.error(
                    "[LinearReader] Quarantined corrupt file -> corrupted/{} " +
                            "(keep this for debugging, it is safe to delete)", corruptName);
        } catch (IOException moveEx) {
            LinearReader.LOGGER.error(
                    "[LinearReader] Could not quarantine {}: {}",
                    path.getFileName(), moveEx.getMessage());
        }

        // ── Try backup ────────────────────────────────────────────────────────
        Path bak = bakPath();
        if (Files.exists(bak)) {
            try {
                loadFromDisk(bak);
                LinearReader.LOGGER.warn(
                        "[LinearReader] Recovered r.{}.{}.linear from backup (.bak). " +
                                "Chunks modified since the last backup will be regenerated.",
                        regionX, regionZ);
                dirty = true;
                return true;
            } catch (IOException bakEx) {
                LinearReader.LOGGER.error(
                        "[LinearReader] Backup is also corrupt: {}", bakEx.getMessage());
                // Quarantine the bad backup too so it doesn't cause confusion.
                String bakCorruptName = "r." + regionX + "." + regionZ
                        + "-" + timestamp + "-backup.corrupt.linear";
                try {
                    Files.move(bak, quarantineDir.resolve(bakCorruptName),
                            StandardCopyOption.REPLACE_EXISTING);
                    LinearReader.LOGGER.error(
                            "[LinearReader] Quarantined corrupt backup -> corrupted/{}",
                            bakCorruptName);
                } catch (IOException ignored) {}
            }
        } else {
            LinearReader.LOGGER.error(
                    "[LinearReader] No backup found for r.{}.{}.linear. " +
                            "Enable backupEnabled=true in linearreader-server.toml to protect against this.",
                    regionX, regionZ);
        }

        return false;
    }

    // -------------------------------------------------------------------------
    // Disk I/O
    // -------------------------------------------------------------------------

    private void loadFromDisk(Path src) throws IOException {
        long startNs = System.nanoTime();
        byte[] raw = Files.readAllBytes(src);
        long readNs = System.nanoTime() - startNs;

        if (raw.length < 40)
            throw new IOException("[LinearReader] File too short (" + raw.length + " bytes): " + src);

        long verifyStartNs = System.nanoTime();
        ByteBuffer hdr    = ByteBuffer.wrap(raw, 0, 32);
        long  sig         = hdr.getLong();
        byte  version     = hdr.get();
        long  newestTs    = hdr.getLong();
        hdr.get();                            // compression level (not needed for decompression)
        short storedCount = hdr.getShort();
        hdr.getInt();                         // compressed-body length (we use actual file length)
        long  storedCRC   = hdr.getLong();

        if (sig != LINEAR_SIGNATURE)
            throw new IOException("[LinearReader] Bad header signature in: " + src);
        if (version != LINEAR_VERSION)
            throw new IOException("[LinearReader] Unsupported version " + version + " in: " + src);
        if (ByteBuffer.wrap(raw, raw.length - 8, 8).getLong() != LINEAR_SIGNATURE)
            throw new IOException("[LinearReader] Bad footer signature in: " + src);

        int compressedBodyLength = raw.length - 40;
        if (compressedBodyLength <= 0)
            throw new IOException("[LinearReader] No compressed body in: " + src);

        if (storedCRC != 0L) {
            CRC32 crc = TL_CRC32.get();
            crc.reset();
            crc.update(raw, 32, compressedBodyLength);
            if (crc.getValue() != storedCRC)
                throw new IOException("[LinearReader] CRC32 checksum mismatch in: " + src
                        + " (expected " + storedCRC + ", got " + crc.getValue() + ")");
        }
        long verifyNs = System.nanoTime() - verifyStartNs;

        long decompressStartNs = System.nanoTime();
        long expectedDecompSize = ZstdSupport.decompressedSize(raw, 32, compressedBodyLength);
        if (expectedDecompSize <= 0 || expectedDecompSize > Integer.MAX_VALUE)
            throw new IOException("[LinearReader] Cannot determine decompressed size in: " + src);

        byte[] decompressed = new byte[(int) expectedDecompSize];
        long result = ZstdSupport.decompress(
                decompressed, 0, decompressed.length,
                raw, 32, compressedBodyLength);
        if (ZstdSupport.isError(result))
            throw new IOException("[LinearReader] Zstd error (" + ZstdSupport.getErrorName(result) + ") in: " + src);
        if (result != expectedDecompSize)
            throw new IOException("[LinearReader] Decompressed size mismatch in: " + src);
        long decompressNs = System.nanoTime() - decompressStartNs;

        if (decompressed.length < INNER_HEADER_SIZE)
            throw new IOException("[LinearReader] Decompressed body too short in: " + src);

        long parseStartNs = System.nanoTime();
        ByteBuffer inner  = ByteBuffer.wrap(decompressed, 0, INNER_HEADER_SIZE);
        int        totalSz    = 0;
        int        realCount  = 0;
        for (int i = 0; i < CHUNK_COUNT; i++) {
            chunkSizes[i] = inner.getInt();
            timestamps[i] = inner.getInt();
            totalSz      += chunkSizes[i];
            if (chunkSizes[i] > 0) realCount++;
        }

        if (INNER_HEADER_SIZE + totalSz != decompressed.length)
            throw new IOException("[LinearReader] Inner size mismatch in: " + src
                    + " (expected " + (INNER_HEADER_SIZE + totalSz) + ", got " + decompressed.length + ")");

        if (realCount != storedCount)
            throw new IOException("[LinearReader] Chunk count mismatch in: " + src
                    + " (header says " + storedCount + ", counted " + realCount + ")");

        Arrays.fill(chunkData, null);
        loadedBody = decompressed;
        materializedBytes = 0L;

        int offset = INNER_HEADER_SIZE;
        for (int i = 0; i < CHUNK_COUNT; i++) {
            chunkOffsets[i] = chunkSizes[i] > 0 ? offset : 0;
            offset += chunkSizes[i];
        }

        this.newestTimestamp = newestTs;
        this.chunkCount      = realCount;
        this.totalDataBytes  = totalSz;
        long parseNs = System.nanoTime() - parseStartNs;

        long elapsedNs = System.nanoTime() - startNs;
        long elapsedMs = elapsedNs / 1_000_000L;
        LinearStats.recordRegionLoad(elapsedNs, readNs, verifyNs, decompressNs, parseNs);
        int  threshold = LinearConfig.getSlowIoThresholdMs();
        if (threshold >= 0 && elapsedMs > threshold) {
            LinearReader.LOGGER.warn(
                    "[LinearReader] Slow region load: {} took {}ms (threshold {}ms). " +
                            "Check disk health or lower regionCacheSize.",
                    src.getFileName(), elapsedMs, threshold);
        } else {
            LinearReader.LOGGER.debug("[LinearReader] Loaded {} in {}ms.", src.getFileName(), elapsedMs);
        }
    }

    /**
     * Serializes and atomically writes a data snapshot to disk.
     * Called from flush() after the lock has been released.
     */
    private void writeToDisk(byte[][] dataSnap, int[] sizeSnap, int[] offsetSnap,
                             int[] tsSnap, @Nullable byte[] bodySnap,
                             long newestTsSnap, int countSnap, long totalBytesSnap,
                             long snapshotNs)
            throws IOException {

        Files.createDirectories(path.getParent());

        int warnGb = LinearConfig.getDiskSpaceWarnGb();
        if (warnGb >= 0) {
            long nowMs = System.currentTimeMillis();
            if (nowMs - TL_LAST_DISK_CHECK.get() > 60_000L) {
                TL_LAST_DISK_CHECK.set(nowMs);
                try {
                    long freeBytes = Files.getFileStore(path.getParent()).getUsableSpace();
                    if (freeBytes < (long) warnGb * 1024L * 1024L * 1024L) {
                        LinearReader.LOGGER.warn(
                                "[LinearReader] Low disk space: {} GB free (threshold {} GB). " +
                                        "A full disk during a region save can cause corruption!",
                                String.format("%.2f", freeBytes / (1024.0 * 1024.0 * 1024.0)), warnGb);
                    }
                } catch (IOException e) {
                    LinearReader.LOGGER.warn("[LinearReader] Could not check disk space: {}", e.getMessage());
                }
            }
        }

        long startNs = System.nanoTime();
        int compressionLevel = LinearReader.currentLiveCompressionLevel();

        int totalBytes = (int) totalBytesSnap;
        int bodySize   = INNER_HEADER_SIZE + totalBytes;

        byte[][] bufs = TL_FLUSH_BUFS.get();
        if (bufs[0] == null || bufs[0].length < bodySize)
            bufs[0] = new byte[bodySize];
        byte[] body = bufs[0];

        long buildStartNs = System.nanoTime();
        ByteBuffer bodyBuf = ByteBuffer.wrap(body, 0, bodySize);
        for (int i = 0; i < CHUNK_COUNT; i++) {
            int len = dataSnap[i] != null ? dataSnap[i].length : sizeSnap[i];
            bodyBuf.putInt(len);
            bodyBuf.putInt(tsSnap[i]);
        }
        for (int i = 0; i < CHUNK_COUNT; i++) {
            byte[] direct = dataSnap[i];
            if (direct != null) {
                bodyBuf.put(direct);
                continue;
            }
            int len = sizeSnap[i];
            if (len > 0) {
                if (bodySnap == null) {
                    throw new IOException("[LinearReader] Missing loaded body while flushing " + path.getFileName());
                }
                bodyBuf.put(bodySnap, offsetSnap[i], len);
            }
        }
        long buildNs = System.nanoTime() - buildStartNs;

        int    maxCompLen    = (int) ZstdSupport.compressBound(bodySize);
        int outCapacity = 32 + maxCompLen + 8;
        if (bufs[1] == null || bufs[1].length < outCapacity)
            bufs[1] = new byte[outCapacity];
        byte[] out = bufs[1];

        long compressStartNs = System.nanoTime();
        long   compLen       = ZstdSupport.compress(out, 32, maxCompLen, body, 0, bodySize, compressionLevel);
        if (ZstdSupport.isError(compLen))
            throw new IOException("[LinearReader] Zstd compression error: " + ZstdSupport.getErrorName(compLen));
        long compressNs = System.nanoTime() - compressStartNs;

        long checksumStartNs = System.nanoTime();
        CRC32 crc = TL_CRC32.get();
        crc.reset();
        crc.update(out, 32, (int) compLen);
        long checksum = crc.getValue();
        long checksumNs = System.nanoTime() - checksumStartNs;

        int outLen = 32 + (int) compLen + 8;
        ByteBuffer outBuf = ByteBuffer.wrap(out, 0, outLen);
        outBuf.putLong(LINEAR_SIGNATURE);
        outBuf.put(LINEAR_VERSION);
        outBuf.putLong(newestTsSnap);
        outBuf.put((byte) compressionLevel);
        outBuf.putShort((short) countSnap);
        outBuf.putInt((int) compLen);
        outBuf.putLong(checksum);
        outBuf.position(32 + (int) compLen);
        outBuf.putLong(LINEAR_SIGNATURE);

        Path wip = path.resolveSibling(path.getFileName() + ".wip");
        long writeNs = 0L;
        long syncNs = 0L;
        try (FileOutputStream fos = new FileOutputStream(wip.toFile())) {
            long writeStartNs = System.nanoTime();
            fos.write(out, 0, outLen);
            writeNs = System.nanoTime() - writeStartNs;
            if (dsync) {
                long syncStartNs = System.nanoTime();
                fos.getFD().sync();
                syncNs = System.nanoTime() - syncStartNs;
            }
        }

        long renameStartNs = System.nanoTime();
        try {
            Files.move(wip, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(wip, path, StandardCopyOption.REPLACE_EXISTING);
        }
        long renameNs = System.nanoTime() - renameStartNs;

        long elapsedNs = snapshotNs + (System.nanoTime() - startNs);
        long elapsedMs = elapsedNs / 1_000_000L;
        LinearStats.recordRegionFlush(
                elapsedNs, bodySize, compLen,
                snapshotNs, buildNs, compressNs, checksumNs, writeNs, syncNs, renameNs);
        int  threshold = LinearConfig.getSlowIoThresholdMs();
        if (threshold >= 0 && elapsedMs > threshold) {
            LinearReader.LOGGER.warn(
                    "[LinearReader] Slow region save: r.{}.{}.linear took {}ms (threshold {}ms). " +
                            "Check disk health.", regionX, regionZ, elapsedMs, threshold);
        }
    }

    private int estimateSerializedFileSize(byte[][] dataSnap, int[] sizeSnap, int[] offsetSnap,
                                           int[] tsSnap, @Nullable byte[] bodySnap,
                                           int countSnap, long totalBytesSnap) throws IOException {
        int compressionLevel = LinearConfig.getCompressionLevel();
        int totalBytes = (int) Math.max(0L, totalBytesSnap);
        int bodySize = INNER_HEADER_SIZE + totalBytes;

        byte[][] bufs = TL_FLUSH_BUFS.get();
        if (bufs[0] == null || bufs[0].length < bodySize) {
            bufs[0] = new byte[bodySize];
        }
        byte[] body = bufs[0];

        ByteBuffer bodyBuf = ByteBuffer.wrap(body, 0, bodySize);
        for (int i = 0; i < CHUNK_COUNT; i++) {
            int len = dataSnap[i] != null ? dataSnap[i].length : sizeSnap[i];
            bodyBuf.putInt(len);
            bodyBuf.putInt(tsSnap[i]);
        }
        for (int i = 0; i < CHUNK_COUNT; i++) {
            byte[] direct = dataSnap[i];
            if (direct != null) {
                bodyBuf.put(direct);
                continue;
            }
            int len = sizeSnap[i];
            if (len > 0) {
                if (bodySnap == null) {
                    throw new IOException("[LinearReader] Missing loaded body while estimating " + path.getFileName());
                }
                bodyBuf.put(bodySnap, offsetSnap[i], len);
            }
        }

        int maxCompLen = (int) ZstdSupport.compressBound(bodySize);
        if (bufs[1] == null || bufs[1].length < maxCompLen) {
            bufs[1] = new byte[maxCompLen];
        }
        byte[] compressedBuf = bufs[1];

        long compLen = ZstdSupport.compress(compressedBuf, body, compressionLevel);
        if (ZstdSupport.isError(compLen)) {
            throw new IOException("[LinearReader] Zstd compression error while estimating size: " + ZstdSupport.getErrorName(compLen));
        }
        return 32 + (int) compLen + 8;
    }

    // -------------------------------------------------------------------------
    // Backup helpers
    // -------------------------------------------------------------------------

    public static Path backupDirFor(Path linearPath) {
        Path parent = linearPath.getParent();
        return parent == null ? Path.of("backups") : parent.resolve("backups");
    }

    public static Path legacyBackupPathFor(Path linearPath) {
        return linearPath.resolveSibling(linearPath.getFileName() + ".bak");
    }

    public static Path backupPathFor(Path linearPath) {
        return backupDirFor(linearPath).resolve(linearPath.getFileName() + ".bak");
    }

    public static void writeBackupCopy(Path linearPath) throws IOException {
        IdleRecompressor.recompressFileTo(linearPath, backupPathFor(linearPath), BACKUP_COMPRESSION_LEVEL);
    }

    private Path bakPath() {
        return backupPathFor(path);
    }

    private void createBackupIfAbsent() {
        if (backedUp) return;
        backedUp = true; // set eagerly so concurrent flushes don't double-submit
        Path bak = bakPath();
        if (Files.exists(bak)) return;
        final Path src = path;
        try {
            getBackupExecutor().submit(() -> {
                try {
                    IdleRecompressor.recompressFileTo(src, bak, BACKUP_COMPRESSION_LEVEL);
                    LinearReader.LOGGER.debug("[LinearReader] Created backup: {}", bak.getFileName());
                } catch (IOException e) {
                    LinearReader.LOGGER.warn("[LinearReader] Could not create backup for {}: {}",
                            src.getFileName(), e.getMessage());
                    backedUp = false; // allow retry on next flush
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            backedUp = false;
            LinearReader.LOGGER.warn("[LinearReader] Backup task rejected for {}: {}",
                    src.getFileName(), e.getMessage());
        }
    }

    private void refreshBackup() {
        Path bak = bakPath();
        final Path src = path;
        try {
            getBackupExecutor().submit(() -> {
                try {
                    IdleRecompressor.recompressFileTo(src, bak, BACKUP_COMPRESSION_LEVEL);
                    LinearReader.LOGGER.debug("[LinearReader] Refreshed backup: {} (after {} saves)",
                            bak.getFileName(), saveCount);
                } catch (IOException e) {
                    LinearReader.LOGGER.warn("[LinearReader] Could not refresh backup for {}: {}",
                            src.getFileName(), e.getMessage());
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            LinearReader.LOGGER.warn("[LinearReader] Backup refresh task rejected for {}: {}",
                    src.getFileName(), e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Static utility: verify a file on disk without opening it as a region.
    // -------------------------------------------------------------------------

    public static VerifyResult verifyOnDisk(Path file) {
        try {
            byte[] raw = Files.readAllBytes(file);

            if (raw.length < 40)
                return VerifyResult.fail("file too short (" + raw.length + " bytes)");

            ByteBuffer hdr  = ByteBuffer.wrap(raw, 0, 32);
            long sig        = hdr.getLong();
            byte version    = hdr.get();
            hdr.getLong();  // newest timestamp
            hdr.get();      // compression level
            hdr.getShort(); // chunk count
            hdr.getInt();   // compressed length
            long storedCRC  = hdr.getLong();

            if (sig != LINEAR_SIGNATURE)
                return VerifyResult.fail("bad header signature");
            if (version != LINEAR_VERSION)
                return VerifyResult.fail("unsupported version: " + version);
            if (ByteBuffer.wrap(raw, raw.length - 8, 8).getLong() != LINEAR_SIGNATURE)
                return VerifyResult.fail("bad footer signature");

            if (storedCRC != 0L) {
                int compLen = raw.length - 40;
                if (compLen <= 0)
                    return VerifyResult.fail("no compressed body");
                CRC32 crc = TL_CRC32.get();
                crc.reset();
                crc.update(raw, 32, compLen);
                if (crc.getValue() != storedCRC)
                    return VerifyResult.fail(
                            "CRC32 mismatch (expected " + storedCRC + ", got " + crc.getValue() + ")");
            }

            return VerifyResult.ok(storedCRC != 0L);
        } catch (IOException e) {
            return VerifyResult.fail("I/O error: " + e.getMessage());
        }
    }

    public static class VerifyResult {
        public final boolean ok;
        public final boolean hasCRC;  // false = Python-written or pre-1.0 file (no checksum)
        public final String  reason;

        private VerifyResult(boolean ok, boolean hasCRC, String reason) {
            this.ok     = ok;
            this.hasCRC = hasCRC;
            this.reason = reason;
        }
        static VerifyResult ok(boolean hasCRC)   { return new VerifyResult(true,  hasCRC, null); }
        static VerifyResult fail(String why)     { return new VerifyResult(false, false,  why);  }
    }

    /**
     * Shuts down the backup executor on server stop.
     * Backup writes use a separate .recompress.wip + atomic rename path, so they
     * are safe to abandon on shutdown. Give them a brief grace period, then stop
     * waiting so singleplayer logout is never held hostage by best-effort backups.
     */
    public static void shutdownBackupExecutor() {
        java.util.concurrent.ExecutorService executor;
        synchronized (LinearRegionFile.class) {
            executor = backupExecutor;
            backupExecutor = null;
        }
        if (executor == null) return;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(250, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
