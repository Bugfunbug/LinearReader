package com.bugfunbug.linearreader.linear;

import com.bugfunbug.linearreader.LinearRuntime;
import com.bugfunbug.linearreader.config.LinearConfig;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.*;
import java.util.stream.Stream;
import java.util.zip.CRC32;

/**
 * Recompresses .linear files at a higher Zstd level when the server has been
 * idle (no chunk I/O) for a configurable period, or on manual command.
 *
 * Two modes:
 *  AUTO   - daemon thread detects idleness; stops immediately if IO resumes.
 *  MANUAL - /linearreader afk-compress start; runs until all files done or stopped.
 *
 * Each recompression is an atomic .recompress.wip -> rename, identical safety
 * guarantees as normal region writes. Leftover .recompress.wip files are
 * cleaned up by LinearRuntime.onServerStarting() — they are never promoted
 * because they use a distinct extension, unlike live .linear.wip files.
 *
 * Only unstable open files are skipped — regions that are dirty or currently
 * flushing may change on disk at any moment. Clean cached regions remain
 * eligible for recompression. The recompressor re-checks region stability
 * immediately before writing to close the window between scan and write.
 */
public final class IdleRecompressor {

    private IdleRecompressor() {}

    private static final long LINEAR_SIGNATURE = 0xc3ff13183cca9d9aL;
    private static final ThreadLocal<CRC32> TL_CRC32 = ThreadLocal.withInitial(CRC32::new);

    /** Zstd level used during idle/AFK recompression. */
    public static final int  TARGET_LEVEL      = 22;
    private static final long CHECK_INTERVAL_MS = 60L * 1_000L;  // poll every minute
    /** Pause between files - keeps disk load low during recompression. */
    private static final long FILE_DELAY_MS     = 3_000L;
    /** Pause when JVM heap headroom falls below the configured safety threshold. */
    private static final long LOW_RAM_BACKOFF_MS = 3L * 60L * 1_000L;

    // Region folders registered as each RegionFileStorage opens.
    private static final Set<Path> KNOWN_FOLDERS = ConcurrentHashMap.newKeySet();

    // Idle detection.
    private static final AtomicLong    LAST_IO_MS = new AtomicLong(System.currentTimeMillis());
    private static final AtomicBoolean RUNNING    = new AtomicBoolean(false);
    private static final AtomicBoolean IS_MANUAL  = new AtomicBoolean(false);
    private static volatile Thread     DETECTOR   = null;
    private static volatile Thread     WORKER     = null;

    // Stats - reset at the start of each new run.
    private static final AtomicInteger FILES_SCANNED      = new AtomicInteger(0);
    private static final AtomicInteger FILES_RECOMPRESSED = new AtomicInteger(0);
    private static final AtomicInteger FILES_ALREADY_OPTIMAL = new AtomicInteger(0);
    private static final AtomicInteger FILES_UNSTABLE_SKIPPED = new AtomicInteger(0);
    private static final AtomicInteger FILES_NO_SIZE_GAIN = new AtomicInteger(0);
    private static final AtomicInteger FILES_FAILED = new AtomicInteger(0);
    private static final AtomicInteger LOW_RAM_PAUSES = new AtomicInteger(0);
    private static final AtomicLong    BYTES_SAVED        = new AtomicLong(0);

    private enum RecompressOutcome {
        UPGRADED,
        ALREADY_OPTIMAL,
        UNSTABLE_SKIPPED,
        NO_SIZE_GAIN
    }

    private record RecompressResult(RecompressOutcome outcome, long bytesSaved) {}

    // -------------------------------------------------------------------------
    // Called from RegionFileStorageMixin
    // -------------------------------------------------------------------------

    public static void registerFolder(Path folder) {
        if (folder == null) return;
        KNOWN_FOLDERS.add(folder.toAbsolutePath().normalize());
    }

    /**
     * Must be called on every chunk read and write.
     * Resets the idle timer; stops the auto-mode worker if it is running
     * (manual mode is unaffected — it runs until explicitly stopped).
     */
    public static void notifyIO() {
        LAST_IO_MS.set(System.currentTimeMillis());
        if (RUNNING.get() && !IS_MANUAL.get()) {
            interruptWorker();
        }
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /** Starts the daemon thread that watches for idleness. Call once at mod init. */
    public static void startAutoDetector() {
        LAST_IO_MS.set(System.currentTimeMillis());
        Thread existing = DETECTOR;
        if (existing != null && existing.isAlive()) return;

        Thread t = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(CHECK_INTERVAL_MS);
                } catch (InterruptedException e) {
                    break;
                }
                if (!LinearConfig.isAutoRecompressEnabled()) continue;
                if (RUNNING.get()) continue;
                long idleMs = System.currentTimeMillis() - LAST_IO_MS.get();
                if (idleMs >= idleThresholdMs()) {
                    LinearRuntime.LOGGER.info(
                            "[LinearReader] Server idle for {} min - starting background recompression.",
                            idleMs / 60_000L);
                    startWorker(false);
                }
            }
            DETECTOR = null;
        }, "lr-idle-detector");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY + 1);
        DETECTOR = t;
        t.start();
    }

    /** Returns false if already running. */
    public static boolean startManual() {
        if (RUNNING.get()) return false;
        startWorker(true);
        return true;
    }

    public static void stopManual() {
        IS_MANUAL.set(false);
        interruptWorker();
    }

    public static void shutdown() {
        Thread detector = DETECTOR;
        if (detector != null) detector.interrupt();
        interruptWorker();
    }

    public static boolean isRunning()         { return RUNNING.get(); }
    public static boolean isManual()          { return IS_MANUAL.get(); }
    public static boolean isAutoEnabled()     { return LinearConfig.isAutoRecompressEnabled(); }
    public static int     filesScanned()      { return FILES_SCANNED.get(); }
    public static int     filesRecompressed() { return FILES_RECOMPRESSED.get(); }
    public static int     filesAlreadyOptimal() { return FILES_ALREADY_OPTIMAL.get(); }
    public static int     filesUnstableSkipped() { return FILES_UNSTABLE_SKIPPED.get(); }
    public static int     lowRamPauses()      { return LOW_RAM_PAUSES.get(); }
    public static long    idleThresholdMs()   { return LinearConfig.getIdleThresholdMinutes() * 60_000L; }
    public static long    idleRemainingMs() {
        if (!LinearConfig.isAutoRecompressEnabled()) return 0L;
        long remaining = idleThresholdMs() - (System.currentTimeMillis() - LAST_IO_MS.get());
        return Math.max(0L, remaining);
    }
    public static long    bytesSaved()        { return BYTES_SAVED.get(); }

    // -------------------------------------------------------------------------
    // Worker
    // -------------------------------------------------------------------------

    private static void startWorker(boolean manual) {
        if (!RUNNING.compareAndSet(false, true)) return;
        resetStats();
        IS_MANUAL.set(manual);
        Thread t = new Thread(() -> {
            try {
                doRecompression();
            } finally {
                RUNNING.set(false);
                IS_MANUAL.set(false);
                WORKER = null;
                logCompletion();
            }
        }, "lr-recompressor");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY + 1);
        WORKER = t;
        t.start();
    }

    private static void resetStats() {
        FILES_SCANNED.set(0);
        FILES_RECOMPRESSED.set(0);
        FILES_ALREADY_OPTIMAL.set(0);
        FILES_UNSTABLE_SKIPPED.set(0);
        FILES_NO_SIZE_GAIN.set(0);
        FILES_FAILED.set(0);
        LOW_RAM_PAUSES.set(0);
        BYTES_SAVED.set(0);
    }

    private static void logCompletion() {
        int total = FILES_SCANNED.get();
        if (total == 0) {
            LinearRuntime.LOGGER.info("[LinearReader] Recompression done: no .linear files found.");
            return;
        }

        StringBuilder msg = new StringBuilder("[LinearReader] Recompression done: ")
                .append(FILES_RECOMPRESSED.get()).append(" upgraded, ")
                .append(FILES_ALREADY_OPTIMAL.get()).append(" already at level ")
                .append(TARGET_LEVEL).append(", ")
                .append(FILES_UNSTABLE_SKIPPED.get()).append(" skipped (dirty/flushing)");

        int noGain = FILES_NO_SIZE_GAIN.get();
        if (noGain > 0) {
            msg.append(", ").append(noGain).append(" no size gain");
        }

        int failed = FILES_FAILED.get();
        if (failed > 0) {
            msg.append(", ").append(failed).append(" failed");
        }

        int lowRamPauses = LOW_RAM_PAUSES.get();
        if (lowRamPauses > 0) {
            msg.append(", ").append(lowRamPauses).append(" low-RAM pauses");
        }

        msg.append(", ").append(BYTES_SAVED.get()).append(" bytes saved.");
        LinearRuntime.LOGGER.info(msg.toString());
    }

    private static void interruptWorker() {
        Thread w = WORKER;
        if (w != null) w.interrupt();
    }

    private static void doRecompression() {
        for (Path folder : KNOWN_FOLDERS) {
            if (Thread.currentThread().isInterrupted()) return;
            if (!Files.isDirectory(folder)) continue;

            Path[] files;
            try (Stream<Path> s = Files.list(folder)) {
                files = s.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".linear"))
                        .toArray(Path[]::new);
            } catch (IOException e) {
                LinearRuntime.LOGGER.warn("[LinearReader] Cannot list {}: {}",
                        folder.getFileName(), e.getMessage());
                continue;
            }

            for (Path p : files) {
                if (Thread.currentThread().isInterrupted()) return;
                FILES_SCANNED.incrementAndGet();
                try {
                    RecompressResult result = recompressFile(p);
                    switch (result.outcome()) {
                        case UPGRADED -> {
                            BYTES_SAVED.addAndGet(result.bytesSaved());
                            FILES_RECOMPRESSED.incrementAndGet();
                        }
                        case ALREADY_OPTIMAL -> FILES_ALREADY_OPTIMAL.incrementAndGet();
                        case UNSTABLE_SKIPPED -> FILES_UNSTABLE_SKIPPED.incrementAndGet();
                        case NO_SIZE_GAIN -> FILES_NO_SIZE_GAIN.incrementAndGet();
                    }
                    if (result.outcome() == RecompressOutcome.UPGRADED) {
                        LinearRuntime.LOGGER.debug(
                                "[LinearReader] Recompressed {} - saved {} bytes.",
                                p.getFileName(), result.bytesSaved());
                    }
                    if (result.outcome() != RecompressOutcome.UNSTABLE_SKIPPED) {
                        Thread.sleep(FILE_DELAY_MS);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (IOException e) {
                    FILES_FAILED.incrementAndGet();
                    LinearRuntime.LOGGER.warn("[LinearReader] Recompression failed for {}: {}",
                            p.getFileName(), e.getMessage());
                }
            }
        }
    }

    private static boolean isRegionUnstable(Path path) {
        Path abs = path.toAbsolutePath().normalize();
        for (LinearRegionFile r : LinearRegionFile.ALL_OPEN) {
            if (r.getNormalizedPath().equals(abs)) {
                return r.isDirty() || r.isFlushing();
            }
        }
        return false;
    }

    private static int availableHeapPercent() {
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        if (maxMemory <= 0L) return 100;

        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        long availableMemory = Math.max(0L, maxMemory - usedMemory);
        return (int) Math.max(0L, Math.min(100L, (availableMemory * 100L) / maxMemory));
    }

    private static void awaitHeapHeadroom(Path path) throws InterruptedException {
        int thresholdPercent = LinearConfig.getRecompressMinFreeRamPercent();
        while (availableHeapPercent() < thresholdPercent) {
            LOW_RAM_PAUSES.incrementAndGet();
            LinearRuntime.LOGGER.info(
                    "[LinearReader] Pausing recompression for {} because JVM heap headroom is {}% (< {}%).",
                    path.getFileName(), availableHeapPercent(), thresholdPercent);
            Thread.sleep(LOW_RAM_BACKOFF_MS);
        }
    }

    // -------------------------------------------------------------------------
    // File-level recompression — package-private so backup logic can use it
    // -------------------------------------------------------------------------

    /**
     * Recompresses {@code path} in-place at {@link #TARGET_LEVEL}.
     * Returns bytes saved, or 0 if the file was already at/above target level
     * or if recompression would make it larger.
     */
    static RecompressResult recompressFile(Path path) throws IOException, InterruptedException {
        if (isRegionUnstable(path)) {
            return new RecompressResult(RecompressOutcome.UNSTABLE_SKIPPED, 0L);
        }

        // Read only the outer header (32 bytes) to check compression level.
        // Avoids reading the entire file for the common case of already-maxed files.
        byte[] header = new byte[32];
        try (java.io.InputStream in = Files.newInputStream(path)) {
            if (in.read(header) < 32) {
                return new RecompressResult(RecompressOutcome.NO_SIZE_GAIN, 0L);
            }
        }
        ByteBuffer hdr = ByteBuffer.wrap(header);
        if (hdr.getLong(0) != LINEAR_SIGNATURE) {
            return new RecompressResult(RecompressOutcome.NO_SIZE_GAIN, 0L);
        }
        if ((header[17] & 0xFF) >= TARGET_LEVEL) {
            return new RecompressResult(RecompressOutcome.ALREADY_OPTIMAL, 0L);
        }

        awaitHeapHeadroom(path);

        // Full recompression needed — now read the whole file.
        return recompressFileTo(path, path, TARGET_LEVEL);
    }

    /**
     * Reads {@code src}, recompresses at {@code targetLevel}, writes atomically to {@code dst}.
     * {@code src} and {@code dst} may be the same path (in-place).
     * Returns the outcome and bytes saved.
     */
    static RecompressResult recompressFileTo(Path src, Path dst, int targetLevel) throws IOException {
        byte[] raw = Files.readAllBytes(src);
        if (raw.length < 40) return new RecompressResult(RecompressOutcome.NO_SIZE_GAIN, 0L);

        ByteBuffer hdr = ByteBuffer.wrap(raw);
        if (hdr.getLong(0) != LINEAR_SIGNATURE) {
            return new RecompressResult(RecompressOutcome.NO_SIZE_GAIN, 0L);
        }
        if (ByteBuffer.wrap(raw, raw.length - 8, 8).getLong() != LINEAR_SIGNATURE) {
            return new RecompressResult(RecompressOutcome.NO_SIZE_GAIN, 0L);
        }

        byte  version    = raw[8];
        long  newestTs   = hdr.getLong(9);
        byte  curLevel   = raw[17];
        short chunkCount = hdr.getShort(18);

        // For in-place recompression, skip if already at or above target.
        if (src.equals(dst) && (curLevel & 0xFF) >= targetLevel) {
            return new RecompressResult(RecompressOutcome.ALREADY_OPTIMAL, 0L);
        }

        int compBodyLen = raw.length - 40; // 32-byte header + 8-byte footer
        if (compBodyLen <= 0) return new RecompressResult(RecompressOutcome.NO_SIZE_GAIN, 0L);

        // Decompress.
        long expectedDecomp = ZstdSupport.decompressedSize(raw, 32, compBodyLen);
        if (expectedDecomp <= 0 || expectedDecomp > Integer.MAX_VALUE) {
            return new RecompressResult(RecompressOutcome.NO_SIZE_GAIN, 0L);
        }

        byte[] body = new byte[(int) expectedDecomp];
        long result = ZstdSupport.decompress(body, 0, body.length, raw, 32, compBodyLen);
        if (ZstdSupport.isError(result)) return new RecompressResult(RecompressOutcome.NO_SIZE_GAIN, 0L);

        // Recompress at target level.
        int maxCompLen = (int) ZstdSupport.compressBound(body.length);
        byte[] out = new byte[32 + maxCompLen + 8];
        long   newLen  = ZstdSupport.compress(out, 32, maxCompLen, body, 0, body.length, targetLevel);
        if (ZstdSupport.isError(newLen)) return new RecompressResult(RecompressOutcome.NO_SIZE_GAIN, 0L);

        // For in-place: don't write if it got larger (can happen with already-optimal data).
        if (src.equals(dst) && newLen >= compBodyLen) {
            return new RecompressResult(RecompressOutcome.NO_SIZE_GAIN, 0L);
        }

        CRC32 crc32 = TL_CRC32.get();
        crc32.reset();
        crc32.update(out, 32, (int) newLen);

        ByteBuffer outBuf = ByteBuffer.wrap(out);
        outBuf.putLong(LINEAR_SIGNATURE);
        outBuf.put(version);
        outBuf.putLong(newestTs);
        outBuf.put((byte) targetLevel);
        outBuf.putShort(chunkCount);
        outBuf.putInt((int) newLen);
        outBuf.putLong(crc32.getValue());
        outBuf.position(32 + (int) newLen);
        outBuf.putLong(LINEAR_SIGNATURE);

        // Only abort in-place recompression if the region is currently unstable.
        // Backup creation (src != dst) is always safe to proceed.
        if (src.toAbsolutePath().normalize().equals(dst.toAbsolutePath().normalize()) && isRegionUnstable(src)) {
            return new RecompressResult(RecompressOutcome.UNSTABLE_SKIPPED, 0L);
        }

        Path dstParent = dst.getParent();
        if (dstParent != null) {
            Files.createDirectories(dstParent);
        }

        // Atomic rename. Use .recompress.wip so startup recovery ignores/deletes it
        // rather than treating it as a live .linear.wip to promote.
        Path wip = dst.resolveSibling(dst.getFileName() + ".recompress.wip");
        try (java.io.OutputStream os = Files.newOutputStream(wip)) {
            os.write(out, 0, 32 + (int) newLen + 8);
        }
        try {
            Files.move(wip, dst,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(wip, dst, StandardCopyOption.REPLACE_EXISTING);
        }

        return new RecompressResult(RecompressOutcome.UPGRADED, compBodyLen - (long) newLen);
    }
}
