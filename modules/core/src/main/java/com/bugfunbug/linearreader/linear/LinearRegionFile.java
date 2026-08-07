package com.bugfunbug.linearreader.linear;

import com.bugfunbug.linearreader.LinearRuntime;
import com.bugfunbug.linearreader.LinearStats;
import com.bugfunbug.linearreader.StoragePolicyManager;
import com.bugfunbug.linearreader.config.LinearConfig;
import com.bugfunbug.linearreader.minecraftapi.ChunkPosCompat;
import net.minecraft.world.level.ChunkPos;

import org.jetbrains.annotations.Nullable;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
    private static final int  MIN_CHUNK_WRITE_CAPACITY = 8192;
    private static final int  MAX_DIRECT_BUFFER_SLACK_BYTES = 8192;
    private static final long ACCESS_TIMESTAMP_UPDATE_INTERVAL_NS = 1_000_000_000L;
    private static final byte[] EMPTY_BYTES = new byte[0];

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

    // Reusable byte-array buffers per flush thread — dramatically reduces GC pressure
    // under heavy worldgen where flushes happen constantly.
    private static final ThreadLocal<byte[][]> TL_FLUSH_BUFS =
            ThreadLocal.withInitial(() -> new byte[2][]);

    // Reusable byte-array buffer per read thread for the *compressed* body read from disk.
    // Safe to pool because it is only ever used as decompression input and is never
    // retained after decompress() returns — true for every current caller (resident
    // region load, verifyOnDisk, and IdleRecompressor recompression).
    //
    // The DECOMPRESSED output is deliberately NOT pooled here: on the resident-load path
    // it becomes `loadedBody` and is retained for the region's entire cache lifetime, so a
    // shared thread-local buffer would let a later unrelated read on the same thread
    // silently overwrite an already-cached region's chunk data.
    private static final ThreadLocal<byte[][]> TL_READ_BUFS =
            ThreadLocal.withInitial(() -> new byte[1][]);

    // Throttle the disk-space syscall to at most once per minute per flush thread.
    private static final ThreadLocal<Long> TL_LAST_DISK_CHECK =
            ThreadLocal.withInitial(() -> 0L);
    private static volatile Long testStateNowNs;
    private static volatile Long testStateNowMs;

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
    private volatile long lastAccessNs = stateNowNs();
    private volatile long lastMutationNs = lastAccessNs;
    private volatile long lastSuccessfulFlushNs = 0L;
    private volatile long backupCompletedAtMs = 0L;

    private boolean backedUp  = false;
    private boolean backupRefreshQueued = false;
    private long mutationVersion = 0L;
    private long changedBytesSinceBackup = 0L;
    private final BitSet changedChunksSinceBackup = new BitSet(CHUNK_COUNT);

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
                    LinearRuntime.LOGGER.error(
                            "[LinearReader] r.{}.{}.linear could not be recovered. " +
                                    "The region will be regenerated by Minecraft.", regionX, regionZ);
                } else {
                    Path bak = bakPath();
                    backedUp = Files.exists(bak);
                    backupCompletedAtMs = backedUp ? backupLastModifiedMs(bak) : 0L;
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
                StoragePolicyManager.recordResidentReload(normalizedPath);
            }
            markAccessed();
            trimResidentDataCache(this);
        }
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    private static int indexOf(ChunkPos pos) {
        return pos.getRegionLocalX() + pos.getRegionLocalZ() * REGION_DIM;
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

    static synchronized void setTestStateClock(Long nowNs, Long nowMs) {
        testStateNowNs = nowNs;
        testStateNowMs = nowMs;
    }

    private void markAccessed() {
        long now = stateNowNs();
        if (now - lastAccessNs >= ACCESS_TIMESTAMP_UPDATE_INTERVAL_NS) {
            lastAccessNs = now;
        }
    }

    private void markDirtyNow() {
        dirty = true;
        lastMutationNs = stateNowNs();
        mutationVersion++;
    }

    private void noteBackupChange(int idx, int oldLen, int newLen) {
        changedChunksSinceBackup.set(idx);
        long changedBytes = Math.max(oldLen, newLen);
        if (changedBytes <= 0L) return;
        if (changedBytesSinceBackup >= Long.MAX_VALUE - changedBytes) {
            changedBytesSinceBackup = Long.MAX_VALUE;
        } else {
            changedBytesSinceBackup += changedBytes;
        }
    }

    private static long backupLastModifiedMs(Path bak) {
        try {
            return Files.getLastModifiedTime(bak).toMillis();
        } catch (IOException ignored) {
            return 0L;
        }
    }

    private void completeBackupTask(long scheduledMutationVersion) {
        long completedAtMs = stateNowMs();
        lock.writeLock().lock();
        try {
            backedUp = true;
            backupCompletedAtMs = completedAtMs;
            backupRefreshQueued = false;
            if (mutationVersion == scheduledMutationVersion) {
                changedChunksSinceBackup.clear();
                changedBytesSinceBackup = 0L;
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void clearBackupRefreshQueued() {
        lock.writeLock().lock();
        try {
            backupRefreshQueued = false;
        } finally {
            lock.writeLock().unlock();
        }
    }

    private int chunkLength(int idx) {
        return chunkSizes[idx];
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
        return StoragePolicyManager.shouldTrimResidentRegion(
                normalizedPath,
                loaded,
                dirty,
                flushing,
                LinearRuntime.isPinnedNormalized(normalizedPath),
                lastAccessNs,
                stateNowNs()
        );
    }

    private long releaseResidentDataIfPossible() {
        lock.writeLock().lock();
        try {
            if (!loaded || dirty || flushing || LinearRuntime.isPinnedNormalized(normalizedPath)) return 0L;
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
            StoragePolicyManager.recordResidentEviction(normalizedPath, freed);
            return freed;
        } finally {
            lock.writeLock().unlock();
        }
    }

    private static void trimResidentDataCache(@Nullable LinearRegionFile keep) {
        long nowNs = stateNowNs();
        Runtime runtime = Runtime.getRuntime();
        long maxHeap = runtime.maxMemory();
        long usedHeap = runtime.totalMemory() - runtime.freeMemory();
        long heapHeadroom = maxHeap > 0L && maxHeap != Long.MAX_VALUE
                ? Math.max(0L, maxHeap - usedHeap)
                : Long.MAX_VALUE;
        boolean heapPressure = heapHeadroom < StoragePolicyManager.minHeapHeadroomBytes();

        long residentBytes = 0L;
        List<LinearRegionFile> candidates = new ArrayList<>();
        for (LinearRegionFile region : ALL_OPEN) {
            residentBytes += region.residentBytesEstimate();
            if (region != keep && region.isResidentTrimCandidate()) {
                candidates.add(region);
            }
        }
        if (!StoragePolicyManager.shouldStartResidentTrim(residentBytes, heapHeadroom, nowNs)) return;

        candidates.sort(Comparator.comparingDouble(
                (LinearRegionFile region) -> StoragePolicyManager.residentTrimPriority(
                        region.normalizedPath,
                        region.lastAccessNs,
                        region.residentBytesEstimate(),
                        nowNs
                )).reversed());
        int trimLimit = Math.max(0, candidates.size() - StoragePolicyManager.residentTrimHotSet());
        int trimmedRegions = 0;
        long trimmedBytes = 0L;
        for (int i = 0; i < trimLimit; i++) {
            LinearRegionFile candidate = candidates.get(i);
            if (!heapPressure && residentBytes <= StoragePolicyManager.residentTargetBytes()) break;
            long freed = candidate.releaseResidentDataIfPossible();
            if (freed <= 0L) continue;
            residentBytes -= freed;
            trimmedRegions++;
            trimmedBytes += freed;
        }
        if (trimmedRegions > 0 || trimmedBytes > 0L) {
            StoragePolicyManager.recordResidentTrim(trimmedRegions, trimmedBytes);
        }
    }

    public long lastMutationTimeNs() {
        return lastMutationNs;
    }

    public long lastSuccessfulFlushTimeNs() {
        return lastSuccessfulFlushNs;
    }

    public MaintenanceDebtSnapshot maintenanceDebtSnapshot(long nowNs) {
        lock.readLock().lock();
        try {
            double dirtyDebt = dirty
                    ? StoragePolicyManager.dirtyDebtScore(Math.max(0L, nowNs - lastMutationNs))
                    : 0.0D;
            double backupDebt = StoragePolicyManager.backupDebtScore(
                    changedChunksSinceBackup.cardinality(),
                    changedBytesSinceBackup,
                    backedUp,
                    backupRefreshQueued
            );
            return new MaintenanceDebtSnapshot(dirtyDebt, backupDebt);
        } finally {
            lock.readLock().unlock();
        }
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
            int oldLen = sizeSnap[idx];
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
            LinearRuntime.LOGGER.error("[LinearReader] Failed to load region in hasChunk for {}: {}",
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
     * Called when c2me's direct delete path invokes RegionFile.clear(ChunkPos).
     */
    public void clearChunk(ChunkPos pos) {
        boolean changed = false;
        try {
            loadIfNeeded();
        } catch (IOException e) {
            LinearRuntime.LOGGER.error("[LinearReader] Failed to load region in clearChunk for {}: {}",
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
                noteBackupChange(idx, oldLen, 0);
                markDirtyNow();
                changed = true;
            }
        } finally {
            lock.writeLock().unlock();
        }
        if (changed) {
            StoragePolicyManager.recordChunkWrite(normalizedPath, 0);
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
                noteBackupChange(idx, oldLen, 0);
                cleared++;
            }

            if (cleared > 0) {
                markDirtyNow();
            }
            if (cleared > 0) {
                StoragePolicyManager.recordChunkWrite(normalizedPath, 0);
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
        boolean statsEnabled = LinearStats.isEnabled();
        DataInputStream dis = null;
        lock.readLock().lock();
        try {
            int idx = indexOf(pos);
            byte[] direct = chunkData[idx];
            int len = chunkSizes[idx];
            if (len == 0) return null;
            long t = statsEnabled ? System.nanoTime() : 0L;
            ByteArrayInputStream in;
            if (direct != null) {
                in = new ByteArrayInputStream(direct, 0, len);
            } else {
                byte[] body = loadedBody;
                if (body == null) return null;
                in = new ByteArrayInputStream(body, chunkOffsets[idx], len);
            }
            dis = new DataInputStream(in);
            if (statsEnabled) {
                LinearStats.recordChunkRead(System.nanoTime() - t);
            }
        } finally {
            lock.readLock().unlock();
        }
        StoragePolicyManager.recordChunkRead(normalizedPath);
        return dis;
    }

    /**
     * Returns a DataOutputStream that commits chunk data into this region when closed.
     * NBT serialisation happens entirely outside any lock; the write lock is taken
     * only for the brief pointer-swap at the end of close().
     */
    public DataOutputStream write(ChunkPos pos) throws IOException {
        final int idx = indexOf(pos);
        int hint = Math.max(chunkLength(idx), MIN_CHUNK_WRITE_CAPACITY);
        return new DataOutputStream(openChunkBytesOutputStream(pos, hint));
    }

    OutputStream openChunkBytesOutputStream(ChunkPos pos, int hint) throws IOException {
        // Load existing chunks before writing so we don't lose data in other slots.
        // This must happen before creating the output stream so that if loading
        // fails we throw immediately rather than silently losing the write.
        loadIfNeeded();
        markAccessed();
        return new ChunkWriteOutputStream(this, indexOf(pos), Math.max(hint, MIN_CHUNK_WRITE_CAPACITY));
    }

    void writeChunkBytes(ChunkPos pos, byte[] data, int length) throws IOException {
        loadIfNeeded();
        markAccessed();
        commitChunkBytes(indexOf(pos), data, length);
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
                lastSuccessfulFlushNs = stateNowNs();
            } finally {
                if (!ioOk) {
                    lock.writeLock().lock();
                    try { dirty = true; } finally { lock.writeLock().unlock(); }
                }
            }

            if (allowBackup && DHPregenMonitor.isBackupEnabled()) {
                final boolean shouldCreateBackup;
                final boolean shouldRefreshBackup;
                final int changedChunkCount;
                final long changedBytes;
                final long scheduledMutationVersion;
                long nowNs = stateNowNs();
                lock.writeLock().lock();
                try {
                    shouldCreateBackup = !backedUp;
                    changedChunkCount = changedChunksSinceBackup.cardinality();
                    changedBytes = changedBytesSinceBackup;
                    boolean normalRefreshDue = !shouldCreateBackup
                            && StoragePolicyManager.shouldRefreshBackup(
                            backedUp,
                            backupRefreshQueued,
                            changedChunkCount,
                            changedBytes,
                            backupCompletedAtMs,
                            lastMutationNs,
                            nowNs
                    );
                    boolean algorithmMismatch = !shouldCreateBackup
                            && !backupRefreshQueued
                            && backupAlgorithmMismatchesConfig(bakPath());
                    shouldRefreshBackup = normalRefreshDue || algorithmMismatch;
                    scheduledMutationVersion = mutationVersion;
                    if (shouldRefreshBackup) {
                        backupRefreshQueued = true;
                    }
                } finally {
                    lock.writeLock().unlock();
                }

                if (shouldCreateBackup) createBackupIfAbsent(scheduledMutationVersion);
                if (shouldRefreshBackup) refreshBackup(scheduledMutationVersion, changedChunkCount, changedBytes);
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
     * {@code linearCache}</b> — i.e. from {@link com.bugfunbug.linearreader.LinearRuntime#submitFlush}
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

    public record MaintenanceDebtSnapshot(double dirtyDebt, double backupDebt) {
    }

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
            LinearRuntime.LOGGER.error(
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
            LinearRuntime.LOGGER.error(
                    "[LinearReader] Quarantined corrupt file -> corrupted/{} " +
                            "(keep this for debugging, it is safe to delete)", corruptName);
        } catch (IOException moveEx) {
            LinearRuntime.LOGGER.error(
                    "[LinearReader] Could not quarantine {}: {}",
                    path.getFileName(), moveEx.getMessage());
        }

        // ── Try backup ────────────────────────────────────────────────────────
        Path bak = bakPath();
        if (Files.exists(bak)) {
            try {
                loadFromDisk(bak);
                LinearRuntime.LOGGER.warn(
                        "[LinearReader] Recovered r.{}.{}.linear from backup (.bak). " +
                                "Chunks modified since the last backup will be regenerated.",
                        regionX, regionZ);
                dirty = true;
                return true;
            } catch (IOException bakEx) {
                LinearRuntime.LOGGER.error(
                        "[LinearReader] Backup is also corrupt: {}", bakEx.getMessage());
                // Quarantine the bad backup too so it doesn't cause confusion.
                String bakCorruptName = "r." + regionX + "." + regionZ
                        + "-" + timestamp + "-backup.corrupt.linear";
                try {
                    Files.move(bak, quarantineDir.resolve(bakCorruptName),
                            StandardCopyOption.REPLACE_EXISTING);
                    LinearRuntime.LOGGER.error(
                            "[LinearReader] Quarantined corrupt backup -> corrupted/{}",
                            bakCorruptName);
                } catch (IOException ignored) {}
            }
        } else {
            LinearRuntime.LOGGER.error(
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
        EncodedLinearFile encoded = readEncodedLinearFile(src);
        long readNs = System.nanoTime() - startNs;

        LoadValidationResult loaded = loadValidatedEncodedLinearFile(src, encoded);
        long verifyNs = loaded.verifyNs;
        long decompressNs = loaded.decompressNs;
        long parseNs = loaded.parseNs;

        long elapsedNs = System.nanoTime() - startNs;
        long elapsedMs = elapsedNs / 1_000_000L;
        LinearStats.recordRegionLoad(elapsedNs, readNs, verifyNs, decompressNs, parseNs);
        int  threshold = LinearConfig.getSlowIoThresholdMs();
        if (threshold >= 0 && elapsedMs > threshold) {
            LinearRuntime.LOGGER.warn(
                    "[LinearReader] Slow region load: {} took {}ms (threshold {}ms). " +
                            "Check disk health or lower regionCacheSize.",
                    src.getFileName(), elapsedMs, threshold);
        } else {
            LinearRuntime.LOGGER.debug("[LinearReader] Loaded {} in {}ms.", src.getFileName(), elapsedMs);
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
            long nowMs = stateNowMs();
            if (nowMs - TL_LAST_DISK_CHECK.get() > 60_000L) {
                TL_LAST_DISK_CHECK.set(nowMs);
                try {
                    long freeBytes = Files.getFileStore(path.getParent()).getUsableSpace();
                    if (freeBytes < (long) warnGb * 1024L * 1024L * 1024L) {
                        LinearRuntime.LOGGER.warn(
                                "[LinearReader] Low disk space: {} GB free (threshold {} GB). " +
                                        "A full disk during a region save can cause corruption!",
                                String.format("%.2f", freeBytes / (1024.0 * 1024.0 * 1024.0)), warnGb);
                    }
                } catch (IOException e) {
                    LinearRuntime.LOGGER.warn("[LinearReader] Could not check disk space: {}", e.getMessage());
                }
            }
        }

        long startNs = System.nanoTime();
        int compressionLevel = LinearRuntime.currentLiveCompressionLevel();

        int totalBytes = (int) totalBytesSnap;
        int bodySize   = INNER_HEADER_SIZE + totalBytes;

        byte[][] bufs = TL_FLUSH_BUFS.get();
        if (bufs[0] == null || bufs[0].length < bodySize)
            bufs[0] = new byte[bodySize];
        byte[] body = bufs[0];

        long buildStartNs = System.nanoTime();
        serializeRegionBody(dataSnap, sizeSnap, offsetSnap, tsSnap, bodySnap, body, bodySize);
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
        StoragePolicyManager.recordRegionFlush(path, elapsedNs, bodySize, compLen, compressionLevel);
        int  threshold = LinearConfig.getSlowIoThresholdMs();
        if (threshold >= 0 && elapsedMs > threshold) {
            LinearRuntime.LOGGER.warn(
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

        serializeRegionBody(dataSnap, sizeSnap, offsetSnap, tsSnap, bodySnap, body, bodySize);

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
        CompressionAlgorithm.Algorithm backupAlgorithm = resolveBackupAlgorithm();
        IdleRecompressor.recompressFileTo(
                linearPath, backupPathFor(linearPath), backupAlgorithm, resolveBackupTargetLevelOrQuality(backupAlgorithm));
    }

    private static CompressionAlgorithm.Algorithm resolveBackupAlgorithm() {
        return LinearConfig.BROTLI.equalsIgnoreCase(LinearConfig.getBackupCompressionAlgorithm())
                ? CompressionAlgorithm.Algorithm.BROTLI
                : CompressionAlgorithm.Algorithm.ZSTD;
    }

    private static int resolveBackupTargetLevelOrQuality(CompressionAlgorithm.Algorithm algorithm) {
        return algorithm == CompressionAlgorithm.Algorithm.BROTLI
                ? CompressionAlgorithm.BROTLI_QUALITY
                : CompressionAlgorithm.ZSTD_LEVEL;
    }

    private Path bakPath() {
        return backupPathFor(path);
    }

    /**
     * True if an existing on-disk backup's compression algorithm no longer
     * matches what's currently configured - e.g. the user just changed
     * backupCompressionAlgorithm. Normal backup-refresh thresholds
     * (StoragePolicyManager.shouldRefreshBackup) only look at how much chunk
     * data has changed since the last backup, so a pure config change with
     * no new edits would otherwise never trigger a refresh at all, no matter
     * how long the server runs.
     */
    private static boolean backupAlgorithmMismatchesConfig(Path bak) {
        if (!Files.exists(bak)) return false;
        try {
            byte[] header = new byte[18];
            try (java.io.InputStream in = Files.newInputStream(bak)) {
                if (in.read(header) < 18) return false;
            }
            CompressionAlgorithm.Encoded current = CompressionAlgorithm.decode(header[17] & 0xFF);
            CompressionAlgorithm.Algorithm configured =
                    LinearConfig.BROTLI.equalsIgnoreCase(LinearConfig.getBackupCompressionAlgorithm())
                            ? CompressionAlgorithm.Algorithm.BROTLI
                            : CompressionAlgorithm.Algorithm.ZSTD;
            return current.algorithm() != configured;
        } catch (IOException | IllegalArgumentException e) {
            return false; // unreadable/corrupt - other paths already handle that; don't force anything here
        }
    }

    private void createBackupIfAbsent(long scheduledMutationVersion) {
        if (backedUp) return;
        backedUp = true; // set eagerly so concurrent flushes don't double-submit
        Path bak = bakPath();
        if (Files.exists(bak)) {
            backupCompletedAtMs = backupLastModifiedMs(bak);
            return;
        }
        final Path src = path;
        try {
            getBackupExecutor().submit(() -> {
                try {
                    CompressionAlgorithm.Algorithm algorithm = resolveBackupAlgorithm();
                    IdleRecompressor.recompressFileTo(src, bak, algorithm, resolveBackupTargetLevelOrQuality(algorithm));
                    completeBackupTask(scheduledMutationVersion);
                    LinearRuntime.LOGGER.debug("[LinearReader] Created backup: {}", bak.getFileName());
                } catch (IOException e) {
                    LinearRuntime.LOGGER.warn("[LinearReader] Could not create backup for {}: {}",
                            src.getFileName(), e.getMessage());
                    lock.writeLock().lock();
                    try {
                        backedUp = false; // allow retry on next flush
                        backupCompletedAtMs = 0L;
                    } finally {
                        lock.writeLock().unlock();
                    }
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            lock.writeLock().lock();
            try {
                backedUp = false;
                backupCompletedAtMs = 0L;
            } finally {
                lock.writeLock().unlock();
            }
            LinearRuntime.LOGGER.warn("[LinearReader] Backup task rejected for {}: {}",
                    src.getFileName(), e.getMessage());
        }
    }

    private void refreshBackup(long scheduledMutationVersion, int changedChunkCount, long changedBytes) {
        Path bak = bakPath();
        final Path src = path;
        try {
            getBackupExecutor().submit(() -> {
                try {
                    CompressionAlgorithm.Algorithm algorithm = resolveBackupAlgorithm();
                    IdleRecompressor.recompressFileTo(src, bak, algorithm, resolveBackupTargetLevelOrQuality(algorithm));
                    completeBackupTask(scheduledMutationVersion);
                    LinearRuntime.LOGGER.debug("[LinearReader] Refreshed backup: {} ({} changed chunk(s), {} changed byte(s))",
                            bak.getFileName(), changedChunkCount, changedBytes);
                } catch (IOException e) {
                    clearBackupRefreshQueued();
                    LinearRuntime.LOGGER.warn("[LinearReader] Could not refresh backup for {}: {}",
                            src.getFileName(), e.getMessage());
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            clearBackupRefreshQueued();
            LinearRuntime.LOGGER.warn("[LinearReader] Backup refresh task rejected for {}: {}",
                    src.getFileName(), e.getMessage());
        }
    }

    static void awaitBackupTasks() throws IOException {
        try {
            getBackupExecutor().submit(() -> null).get(5L, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("[LinearReader] Interrupted while waiting for backup tasks.", e);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new IOException("[LinearReader] Backup task failed while draining executor.", cause);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new IOException("[LinearReader] Timed out while waiting for backup tasks.", e);
        }
    }

    // -------------------------------------------------------------------------
    // Static utility: verify a file on disk without opening it as a region.
    // -------------------------------------------------------------------------

    public static VerifyResult verifyOnDisk(Path file) {
        try {
            ValidationResult validated = validateEncodedLinearFile(file, readEncodedLinearFile(file));
            return VerifyResult.ok(validated.hasCRC);
        } catch (IOException e) {
            String msg = e.getMessage();
            return VerifyResult.fail(msg != null && msg.startsWith("[LinearReader]") ? msg : "I/O error: " + msg);
        }
    }

    static EncodedLinearFile readEncodedLinearFile(Path src) throws IOException {
        long fileSize = Files.size(src);
        if (fileSize < 40L) {
            throw new IOException("[LinearReader] File too short (" + fileSize + " bytes): " + src);
        }
        long compressedBodyLengthLong = fileSize - 40L;
        if (compressedBodyLengthLong <= 0L || compressedBodyLengthLong > Integer.MAX_VALUE) {
            throw new IOException("[LinearReader] Invalid compressed body length in: " + src);
        }
        int compressedBodyLength = (int) compressedBodyLengthLong;

        byte[] header = new byte[32];
        byte[][] readBufs = TL_READ_BUFS.get();
        if (readBufs[0] == null || readBufs[0].length < compressedBodyLength) {
            readBufs[0] = new byte[compressedBodyLength];
        }
        byte[] compressedBody = readBufs[0];
        byte[] footer = new byte[8];

        try (FileChannel channel = FileChannel.open(src, StandardOpenOption.READ)) {
            readFully(channel, ByteBuffer.wrap(header), src, "header");
            readFully(channel, ByteBuffer.wrap(compressedBody, 0, compressedBodyLength), src, "compressed body");
            readFully(channel, ByteBuffer.wrap(footer), src, "footer");
        }

        return new EncodedLinearFile(
                readLongBigEndian(header, 0),
                readLongBigEndian(footer, 0),
                header[8],
                readLongBigEndian(header, 9),
                header[17],
                readShortBigEndian(header, 18),
                readLongBigEndian(header, 24),
                compressedBody,
                compressedBodyLength
        );
    }

    private static void readFully(FileChannel channel, ByteBuffer buffer, Path src, String section) throws IOException {
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) {
                throw new EOFException("[LinearReader] Unexpected EOF while reading " + section + " from: " + src);
            }
        }
    }

    private LoadValidationResult loadValidatedEncodedLinearFile(Path src, EncodedLinearFile encoded) throws IOException {
        long verifyNs = verifyEncodedLinearFile(src, encoded);
        DecompressedBody decompressed = decompressEncodedLinearFile(src, encoded);
        byte[] body = decompressed.bytes;
        if (body.length < INNER_HEADER_SIZE) {
            throw new IOException("[LinearReader] Decompressed body too short in: " + src);
        }

        long parseStartNs = System.nanoTime();
        Arrays.fill(chunkData, null);
        loadedBody = body;
        materializedBytes = 0L;

        int headerPos = 0;
        int offset = INNER_HEADER_SIZE;
        long totalSz = 0L;
        int realCount = 0;
        for (int i = 0; i < CHUNK_COUNT; i++) {
            int chunkSize = readIntBigEndian(body, headerPos);
            int timestamp = readIntBigEndian(body, headerPos + 4);
            headerPos += 8;
            if (chunkSize < 0) {
                throw new IOException("[LinearReader] Negative chunk size in: " + src + " at local index " + i);
            }

            chunkSizes[i] = chunkSize;
            timestamps[i] = timestamp;
            if (chunkSize > 0) {
                chunkOffsets[i] = offset;
                realCount++;
            } else {
                chunkOffsets[i] = 0;
            }
            offset += chunkSize;
            totalSz += chunkSize;
        }

        if (offset != body.length) {
            throw new IOException("[LinearReader] Inner size mismatch in: " + src
                    + " (expected " + offset + ", got " + body.length + ")");
        }
        if (realCount != encoded.chunkCount) {
            throw new IOException("[LinearReader] Chunk count mismatch in: " + src
                    + " (header says " + encoded.chunkCount + ", counted " + realCount + ")");
        }

        newestTimestamp = encoded.newestTimestamp;
        chunkCount = realCount;
        totalDataBytes = totalSz;
        long parseNs = System.nanoTime() - parseStartNs;
        return new LoadValidationResult(verifyNs, decompressed.elapsedNs, parseNs);
    }

    private static ValidationResult validateEncodedLinearFile(Path src, EncodedLinearFile encoded) throws IOException {
        long verifyNs = verifyEncodedLinearFile(src, encoded);
        DecompressedBody decompressedBody = decompressEncodedLinearFile(src, encoded);
        byte[] decompressed = decompressedBody.bytes;
        long decompressNs = decompressedBody.elapsedNs;

        if (decompressed.length < INNER_HEADER_SIZE) {
            throw new IOException("[LinearReader] Decompressed body too short in: " + src);
        }

        long parseStartNs = System.nanoTime();
        int[] parsedChunkSizes = new int[CHUNK_COUNT];
        int[] parsedTimestamps = new int[CHUNK_COUNT];
        long totalSz = 0L;
        int realCount = 0;
        int headerPos = 0;
        for (int i = 0; i < CHUNK_COUNT; i++) {
            int chunkSize = readIntBigEndian(decompressed, headerPos);
            int timestamp = readIntBigEndian(decompressed, headerPos + 4);
            headerPos += 8;
            if (chunkSize < 0) {
                throw new IOException("[LinearReader] Negative chunk size in: " + src + " at local index " + i);
            }
            parsedChunkSizes[i] = chunkSize;
            parsedTimestamps[i] = timestamp;
            totalSz += chunkSize;
            if (chunkSize > 0) {
                realCount++;
            }
        }

        if (INNER_HEADER_SIZE + totalSz != decompressed.length) {
            throw new IOException("[LinearReader] Inner size mismatch in: " + src
                    + " (expected " + (INNER_HEADER_SIZE + totalSz) + ", got " + decompressed.length + ")");
        }

        if (realCount != encoded.chunkCount) {
            throw new IOException("[LinearReader] Chunk count mismatch in: " + src
                    + " (header says " + encoded.chunkCount + ", counted " + realCount + ")");
        }
        long parseNs = System.nanoTime() - parseStartNs;

        return new ValidationResult(
                encoded.newestTimestamp,
                (encoded.storedCRC & 0xFFFFFFFFL) != 0L,
                decompressed,
                parsedChunkSizes,
                parsedTimestamps,
                realCount,
                totalSz,
                verifyNs,
                decompressNs,
                parseNs
        );
    }

    private static long verifyEncodedLinearFile(Path src, EncodedLinearFile encoded) throws IOException {
        long verifyStartNs = System.nanoTime();
        if (encoded.headerSignature != LINEAR_SIGNATURE) {
            throw new IOException("[LinearReader] Bad header signature in: " + src);
        }
        if (encoded.version != LINEAR_VERSION) {
            throw new IOException("[LinearReader] Unsupported version " + encoded.version + " in: " + src);
        }
        if (encoded.footerSignature != LINEAR_SIGNATURE) {
            throw new IOException("[LinearReader] Bad footer signature in: " + src);
        }

        // Only the lower 32 bits of this field are ever a real CRC32 - the upper
        // 32 bits are reused to store a Brotli-compressed file's decompressed
        // size (see decompressEncodedLinearFile below). Every file ever written
        // before Brotli support existed has zero in the upper bits already, so
        // this masking is fully backward compatible.
        long storedCrc32 = encoded.storedCRC & 0xFFFFFFFFL;
        if (storedCrc32 != 0L) {
            CRC32 crc = TL_CRC32.get();
            crc.reset();
            crc.update(encoded.compressedBody, 0, encoded.compressedBodyLength);
            if (crc.getValue() != storedCrc32) {
                throw new IOException("[LinearReader] CRC32 checksum mismatch in: " + src
                        + " (expected " + storedCrc32 + ", got " + crc.getValue() + ")");
            }
        }
        return System.nanoTime() - verifyStartNs;
    }

    static DecompressedBody decompressEncodedLinearFile(Path src, EncodedLinearFile encoded) throws IOException {
        long decompressStartNs = System.nanoTime();

        CompressionAlgorithm.Encoded algo;
        try {
            algo = CompressionAlgorithm.decode(encoded.compressionLevel & 0xFF);
        } catch (IllegalArgumentException e) {
            throw new IOException("[LinearReader] " + e.getMessage() + " in: " + src, e);
        }

        byte[] decompressed;
        if (algo.algorithm() == CompressionAlgorithm.Algorithm.BROTLI) {
            // Brotli has no equivalent to Zstd's embedded frame content-size, so
            // the real decompressed size is stored in the upper 32 bits of the
            // outer header's checksum field by whatever wrote this file
            // (IdleRecompressor.recompressFileTo) - see the CRC masking change
            // above for why that's safe to reuse.
            int decompressedSizeHint = (int) (encoded.storedCRC >>> 32);
            if (decompressedSizeHint <= 0) {
                throw new IOException(
                        "[LinearReader] Missing decompressed size for Brotli-compressed file: " + src);
            }
            try {
                decompressed = BrotliSupport.decompress(
                        encoded.compressedBody, 0, encoded.compressedBodyLength, decompressedSizeHint);
            } catch (RuntimeException e) {
                throw new IOException("[LinearReader] Brotli decompression failed in: " + src, e);
            }
        } else {
            long expectedDecompSize = ZstdSupport.decompressedSize(
                    encoded.compressedBody, 0, encoded.compressedBodyLength);
            if (expectedDecompSize <= 0 || expectedDecompSize > Integer.MAX_VALUE) {
                throw new IOException("[LinearReader] Cannot determine decompressed size in: " + src);
            }
            decompressed = new byte[(int) expectedDecompSize];
            long result = ZstdSupport.decompress(
                    decompressed, 0, decompressed.length,
                    encoded.compressedBody, 0, encoded.compressedBodyLength);
            if (ZstdSupport.isError(result)) {
                throw new IOException(
                        "[LinearReader] Zstd error (" + ZstdSupport.getErrorName(result) + ") in: " + src);
            }
            if (result != expectedDecompSize) {
                throw new IOException("[LinearReader] Decompressed size mismatch in: " + src);
            }
        }
        return new DecompressedBody(decompressed, System.nanoTime() - decompressStartNs);
    }

    static final class EncodedLinearFile {
        final long headerSignature;
        final long footerSignature;
        final byte version;
        final long newestTimestamp;
        final byte compressionLevel;
        final short chunkCount;
        final long storedCRC;
        final byte[] compressedBody;
        final int compressedBodyLength;

        EncodedLinearFile(long headerSignature, long footerSignature, byte version,
                          long newestTimestamp, byte compressionLevel, short chunkCount,
                          long storedCRC, byte[] compressedBody, int compressedBodyLength) {
            this.headerSignature = headerSignature;
            this.footerSignature = footerSignature;
            this.version = version;
            this.newestTimestamp = newestTimestamp;
            this.compressionLevel = compressionLevel;
            this.chunkCount = chunkCount;
            this.storedCRC = storedCRC;
            this.compressedBody = compressedBody;
            this.compressedBodyLength = compressedBodyLength;
        }
    }

    static final class DecompressedBody {
        final byte[] bytes;
        final long elapsedNs;

        private DecompressedBody(byte[] bytes, long elapsedNs) {
            this.bytes = bytes;
            this.elapsedNs = elapsedNs;
        }
    }

    private static final class ValidationResult {
        final long newestTimestamp;
        final boolean hasCRC;
        final byte[] decompressedBody;
        final int[] parsedChunkSizes;
        final int[] parsedTimestamps;
        final int realChunkCount;
        final long totalChunkBytes;
        final long verifyNs;
        final long decompressNs;
        final long parseNs;

        private ValidationResult(long newestTimestamp, boolean hasCRC, byte[] decompressedBody,
                                 int[] parsedChunkSizes, int[] parsedTimestamps,
                                 int realChunkCount, long totalChunkBytes,
                                 long verifyNs, long decompressNs, long parseNs) {
            this.newestTimestamp = newestTimestamp;
            this.hasCRC = hasCRC;
            this.decompressedBody = decompressedBody;
            this.parsedChunkSizes = parsedChunkSizes;
            this.parsedTimestamps = parsedTimestamps;
            this.realChunkCount = realChunkCount;
            this.totalChunkBytes = totalChunkBytes;
            this.verifyNs = verifyNs;
            this.decompressNs = decompressNs;
            this.parseNs = parseNs;
        }
    }

    private static final class LoadValidationResult {
        final long verifyNs;
        final long decompressNs;
        final long parseNs;

        private LoadValidationResult(long verifyNs, long decompressNs, long parseNs) {
            this.verifyNs = verifyNs;
            this.decompressNs = decompressNs;
            this.parseNs = parseNs;
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

    private void serializeRegionBody(byte[][] dataSnap, int[] sizeSnap, int[] offsetSnap,
                                     int[] tsSnap, @Nullable byte[] bodySnap,
                                     byte[] body, int bodySize) throws IOException {
        int headerPos = 0;
        for (int i = 0; i < CHUNK_COUNT; i++) {
            putIntBigEndian(body, headerPos, sizeSnap[i]);
            putIntBigEndian(body, headerPos + 4, tsSnap[i]);
            headerPos += 8;
        }

        int destPos = INNER_HEADER_SIZE;
        int pendingSrcPos = -1;
        int pendingLen = 0;
        for (int i = 0; i < CHUNK_COUNT; i++) {
            int len = sizeSnap[i];
            if (len <= 0) {
                continue;
            }

            byte[] direct = dataSnap[i];
            if (direct != null) {
                if (pendingLen > 0) {
                    System.arraycopy(bodySnap, pendingSrcPos, body, destPos, pendingLen);
                    destPos += pendingLen;
                    pendingLen = 0;
                }
                System.arraycopy(direct, 0, body, destPos, len);
                destPos += len;
                continue;
            }

            if (bodySnap == null) {
                throw new IOException("[LinearReader] Missing loaded body while serializing " + path.getFileName());
            }

            int srcPos = offsetSnap[i];
            if (pendingLen == 0) {
                pendingSrcPos = srcPos;
                pendingLen = len;
            } else if (pendingSrcPos + pendingLen == srcPos) {
                pendingLen += len;
            } else {
                System.arraycopy(bodySnap, pendingSrcPos, body, destPos, pendingLen);
                destPos += pendingLen;
                pendingSrcPos = srcPos;
                pendingLen = len;
            }
        }

        if (pendingLen > 0) {
            System.arraycopy(bodySnap, pendingSrcPos, body, destPos, pendingLen);
            destPos += pendingLen;
        }

        if (destPos != bodySize) {
            throw new IOException("[LinearReader] Serialized body size mismatch for " + path.getFileName()
                    + " (expected " + bodySize + ", got " + destPos + ")");
        }
    }

    private static void putIntBigEndian(byte[] dest, int offset, int value) {
        dest[offset] = (byte) (value >>> 24);
        dest[offset + 1] = (byte) (value >>> 16);
        dest[offset + 2] = (byte) (value >>> 8);
        dest[offset + 3] = (byte) value;
    }

    private static int readIntBigEndian(byte[] src, int offset) {
        return ((src[offset] & 0xFF) << 24)
                | ((src[offset + 1] & 0xFF) << 16)
                | ((src[offset + 2] & 0xFF) << 8)
                | (src[offset + 3] & 0xFF);
    }

    private static short readShortBigEndian(byte[] src, int offset) {
        return (short) (((src[offset] & 0xFF) << 8) | (src[offset + 1] & 0xFF));
    }

    private static long readLongBigEndian(byte[] src, int offset) {
        return ((long) (src[offset] & 0xFF) << 56)
                | ((long) (src[offset + 1] & 0xFF) << 48)
                | ((long) (src[offset + 2] & 0xFF) << 40)
                | ((long) (src[offset + 3] & 0xFF) << 32)
                | ((long) (src[offset + 4] & 0xFF) << 24)
                | ((long) (src[offset + 5] & 0xFF) << 16)
                | ((long) (src[offset + 6] & 0xFF) << 8)
                | ((long) (src[offset + 7] & 0xFF));
    }

    private void commitChunkBytes(int idx, byte[] newData, int newLen) {
        if (newLen < 0 || newLen > newData.length) {
            throw new IllegalArgumentException("chunk length out of bounds: " + newLen + " / " + newData.length);
        }

        byte[] ownedData = compactDirectBufferIfNeeded(newData, newLen);
        int ts = (int) (stateNowMs() / 1000L);

        lock.writeLock().lock();
        try {
            byte[] oldDirect = chunkData[idx];
            int oldDirectLen = oldDirect != null ? oldDirect.length : 0;
            int oldLen = chunkLength(idx);

            if (newLen == 0) {
                chunkData[idx] = null;
                chunkSizes[idx] = 0;
                chunkOffsets[idx] = 0;
                timestamps[idx] = 0;
            } else {
                chunkData[idx] = ownedData;
                chunkSizes[idx] = newLen;
                chunkOffsets[idx] = 0;
                timestamps[idx] = ts;
                if (ts > newestTimestamp) newestTimestamp = ts;
            }

            if (oldLen == 0 && newLen > 0) chunkCount++;
            else if (oldLen > 0 && newLen == 0) chunkCount--;
            totalDataBytes += newLen - oldLen;
            materializedBytes += (ownedData != null ? ownedData.length : 0) - oldDirectLen;

            noteBackupChange(idx, oldLen, newLen);
            markDirtyNow();
        } finally {
            lock.writeLock().unlock();
        }
        StoragePolicyManager.recordChunkWrite(normalizedPath, newLen);
    }

    private static byte[] compactDirectBufferIfNeeded(byte[] buffer, int size) {
        if (size == 0) return EMPTY_BYTES;
        if (buffer.length == size) return buffer;

        long slack = (long) buffer.length - size;
        if (slack > MAX_DIRECT_BUFFER_SLACK_BYTES && buffer.length > size * 2L) {
            return Arrays.copyOf(buffer, size);
        }
        return buffer;
    }

    private static final class ChunkWriteOutputStream extends OutputStream {
        private final LinearRegionFile owner;
        private final int chunkIndex;
        private byte[] buffer;
        private int count;
        private boolean closed;

        private ChunkWriteOutputStream(LinearRegionFile owner, int chunkIndex, int initialCapacity) {
            this.owner = owner;
            this.chunkIndex = chunkIndex;
            this.buffer = new byte[Math.max(initialCapacity, MIN_CHUNK_WRITE_CAPACITY)];
        }

        @Override
        public void write(int b) throws IOException {
            ensureOpen();
            ensureCapacity(count + 1);
            buffer[count++] = (byte) b;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            ensureOpen();
            if (b == null) throw new NullPointerException("b");
            if (off < 0 || len < 0 || off + len > b.length) {
                throw new IndexOutOfBoundsException("off=" + off + ", len=" + len + ", size=" + b.length);
            }
            if (len == 0) return;
            ensureCapacity(count + len);
            System.arraycopy(b, off, buffer, count, len);
            count += len;
        }

        @Override
        public void close() throws IOException {
            if (closed) return;
            closed = true;
            owner.commitChunkBytes(chunkIndex, buffer, count);
            buffer = EMPTY_BYTES;
        }

        private void ensureOpen() throws IOException {
            if (closed) throw new IOException("stream closed");
        }

        private void ensureCapacity(int minCapacity) {
            if (minCapacity <= buffer.length) return;

            int newCapacity = Math.max(buffer.length << 1, minCapacity);
            if (newCapacity < 0) {
                throw new OutOfMemoryError("chunk buffer too large");
            }
            buffer = Arrays.copyOf(buffer, newCapacity);
        }
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

    private static long stateNowNs() {
        Long fixedNow = testStateNowNs;
        return fixedNow != null ? fixedNow : System.nanoTime();
    }

    private static long stateNowMs() {
        Long fixedNow = testStateNowMs;
        return fixedNow != null ? fixedNow : System.currentTimeMillis();
    }
}
