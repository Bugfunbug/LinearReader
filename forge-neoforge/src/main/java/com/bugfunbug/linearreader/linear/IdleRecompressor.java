package com.bugfunbug.linearreader.linear;

import com.bugfunbug.linearreader.LinearReader;
import com.github.luben.zstd.Zstd;

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
 * cleaned up by LinearReader.onServerStarting() — they are never promoted
 * because they use a distinct extension, unlike live .linear.wip files.
 *
 * Files currently in LinearRegionFile.ALL_OPEN are always skipped — they may
 * receive dirty writes at any moment. The recompressor re-checks open state
 * immediately before writing to close the window between scan and write.
 */
public final class IdleRecompressor {

    private IdleRecompressor() {}

    private static final long LINEAR_SIGNATURE = 0xc3ff13183cca9d9aL;

    /** Zstd level used during idle/AFK recompression. */
    public static final int  TARGET_LEVEL      = 22;
    /** No chunk IO for this long before auto-recompression starts (20 min). */
    public static final long IDLE_THRESHOLD_MS = 20L * 60L * 1_000L;
    private static final long CHECK_INTERVAL_MS = 60L * 1_000L;  // poll every minute
    /** Pause between files - keeps disk load low during recompression. */
    private static final long FILE_DELAY_MS     = 3_000L;

    // Region folders registered as each RegionFileStorage opens.
    private static final Set<Path> KNOWN_FOLDERS = ConcurrentHashMap.newKeySet();

    // Idle detection.
    private static final AtomicLong    LAST_IO_MS = new AtomicLong(System.currentTimeMillis());
    private static final AtomicBoolean RUNNING    = new AtomicBoolean(false);
    private static final AtomicBoolean IS_MANUAL  = new AtomicBoolean(false);
    private static volatile Thread     WORKER     = null;

    // Stats - reset at the start of each new run.
    private static final AtomicInteger FILES_SCANNED      = new AtomicInteger(0);
    private static final AtomicInteger FILES_RECOMPRESSED = new AtomicInteger(0);
    private static final AtomicLong    BYTES_SAVED        = new AtomicLong(0);

    // -------------------------------------------------------------------------
    // Called from RegionFileStorageMixin
    // -------------------------------------------------------------------------

    public static void registerFolder(Path folder) {
        if (folder == null) return;
        KNOWN_FOLDERS.add(folder.toAbsolutePath());
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
        Thread t = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(CHECK_INTERVAL_MS);
                } catch (InterruptedException e) {
                    break;
                }
                if (RUNNING.get()) continue;
                long idleMs = System.currentTimeMillis() - LAST_IO_MS.get();
                if (idleMs >= IDLE_THRESHOLD_MS) {
                    LinearReader.LOGGER.info(
                            "[LinearReader] Server idle for {} min - starting background recompression.",
                            idleMs / 60_000L);
                    startWorker(false);
                }
            }
        }, "lr-idle-detector");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY + 1);
        t.start();
    }

    /** Returns false if already running. */
    public static boolean startManual() {
        if (RUNNING.get()) return false;
        FILES_SCANNED.set(0);
        FILES_RECOMPRESSED.set(0);
        BYTES_SAVED.set(0);
        startWorker(true);
        return true;
    }

    public static void stopManual() {
        IS_MANUAL.set(false);
        interruptWorker();
    }

    public static void shutdown() {
        interruptWorker();
    }

    public static boolean isRunning()         { return RUNNING.get(); }
    public static boolean isManual()          { return IS_MANUAL.get(); }
    public static int     filesScanned()      { return FILES_SCANNED.get(); }
    public static int     filesRecompressed() { return FILES_RECOMPRESSED.get(); }
    public static long    bytesSaved()        { return BYTES_SAVED.get(); }

    // -------------------------------------------------------------------------
    // Worker
    // -------------------------------------------------------------------------

    private static void startWorker(boolean manual) {
        if (!RUNNING.compareAndSet(false, true)) return;
        IS_MANUAL.set(manual);
        Thread t = new Thread(() -> {
            try {
                doRecompression();
            } finally {
                RUNNING.set(false);
                IS_MANUAL.set(false);
                WORKER = null;
                int recomp = FILES_RECOMPRESSED.get();
                int total  = FILES_SCANNED.get();
                if (recomp > 0) {
                    LinearReader.LOGGER.info(
                            "[LinearReader] Recompression done: {}/{} file(s) upgraded, {} bytes saved.",
                            recomp, total, BYTES_SAVED.get());
                } else if (total > 0) {
                    LinearReader.LOGGER.info(
                            "[LinearReader] Recompression done: all {} file(s) already at level {}.",
                            total, TARGET_LEVEL);
                }
            }
        }, "lr-recompressor");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY + 1);
        WORKER = t;
        t.start();
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
                LinearReader.LOGGER.warn("[LinearReader] Cannot list {}: {}",
                        folder.getFileName(), e.getMessage());
                continue;
            }

            for (Path p : files) {
                if (Thread.currentThread().isInterrupted()) return;
                if (isOpen(p)) continue;

                FILES_SCANNED.incrementAndGet();
                try {
                    long saved = recompressFile(p);
                    if (saved > 0) {
                        BYTES_SAVED.addAndGet(saved);
                        FILES_RECOMPRESSED.incrementAndGet();
                        LinearReader.LOGGER.debug(
                                "[LinearReader] Recompressed {} - saved {} bytes.", p.getFileName(), saved);
                    }
                    Thread.sleep(FILE_DELAY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (IOException e) {
                    LinearReader.LOGGER.warn("[LinearReader] Recompression failed for {}: {}",
                            p.getFileName(), e.getMessage());
                }
            }
        }
    }

    private static boolean isOpen(Path path) {
        Path abs = path.toAbsolutePath();
        for (LinearRegionFile r : LinearRegionFile.ALL_OPEN) {
            if (r.getPath().toAbsolutePath().equals(abs)) return true;
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // File-level recompression — package-private so backup logic can use it
    // -------------------------------------------------------------------------

    /**
     * Recompresses {@code path} in-place at {@link #TARGET_LEVEL}.
     * Returns bytes saved, or 0 if the file was already at/above target level
     * or if recompression would make it larger.
     */
    static long recompressFile(Path path) throws IOException {
        // Read only the outer header (32 bytes) to check compression level.
        // Avoids reading the entire file for the common case of already-maxed files.
        byte[] header = new byte[32];
        try (java.io.InputStream in = Files.newInputStream(path)) {
            if (in.read(header) < 32) return 0;
        }
        ByteBuffer hdr = ByteBuffer.wrap(header);
        if (hdr.getLong(0) != LINEAR_SIGNATURE) return 0;
        if ((header[17] & 0xFF) >= TARGET_LEVEL) return 0; // already at or above target level

        // Full recompression needed — now read the whole file.
        return recompressFileTo(path, path, TARGET_LEVEL);
    }

    /**
     * Reads {@code src}, recompresses at {@code targetLevel}, writes atomically to {@code dst}.
     * {@code src} and {@code dst} may be the same path (in-place).
     * Returns bytes saved (0 if nothing was written — file already optimal or got larger).
     */
    static long recompressFileTo(Path src, Path dst, int targetLevel) throws IOException {
        byte[] raw = Files.readAllBytes(src);
        if (raw.length < 40) return 0;

        ByteBuffer hdr = ByteBuffer.wrap(raw);
        if (hdr.getLong(0)                                       != LINEAR_SIGNATURE) return 0;
        if (ByteBuffer.wrap(raw, raw.length - 8, 8).getLong()   != LINEAR_SIGNATURE) return 0;

        byte  version    = raw[8];
        long  newestTs   = hdr.getLong(9);
        byte  curLevel   = raw[17];
        short chunkCount = hdr.getShort(18);

        // For in-place recompression, skip if already at or above target.
        if (src.equals(dst) && (curLevel & 0xFF) >= targetLevel) return 0;

        int compBodyLen = raw.length - 40; // 32-byte header + 8-byte footer
        if (compBodyLen <= 0) return 0;

        // Decompress.
        byte[] existingComp = new byte[compBodyLen];
        System.arraycopy(raw, 32, existingComp, 0, compBodyLen);

        long expectedDecomp = Zstd.decompressedSize(existingComp);
        if (expectedDecomp <= 0 || expectedDecomp > Integer.MAX_VALUE) return 0;

        byte[] body = new byte[(int) expectedDecomp];
        long result = Zstd.decompress(body, existingComp);
        if (Zstd.isError(result)) return 0;

        // Recompress at target level.
        byte[] newComp = new byte[(int) Zstd.compressBound(body.length)];
        long   newLen  = Zstd.compress(newComp, body, targetLevel);
        if (Zstd.isError(newLen)) return 0;

        // For in-place: don't write if it got larger (can happen with already-optimal data).
        if (src.equals(dst) && newLen >= compBodyLen) return 0;

        CRC32 crc32 = new CRC32();
        crc32.update(newComp, 0, (int) newLen);

        byte[]     out    = new byte[32 + (int) newLen + 8];
        ByteBuffer outBuf = ByteBuffer.wrap(out);
        outBuf.putLong(LINEAR_SIGNATURE);
        outBuf.put(version);
        outBuf.putLong(newestTs);
        outBuf.put((byte) targetLevel);
        outBuf.putShort(chunkCount);
        outBuf.putInt((int) newLen);
        outBuf.putLong(crc32.getValue());
        outBuf.put(newComp, 0, (int) newLen);
        outBuf.putLong(LINEAR_SIGNATURE);

        // Only abort in-place recompression if the region is open.
        // Backup creation (src != dst) is always safe to proceed.
        if (src.toAbsolutePath().equals(dst.toAbsolutePath()) && isOpen(src)) return 0;

        // Atomic rename. Use .recompress.wip so startup recovery ignores/deletes it
        // rather than treating it as a live .linear.wip to promote.
        Path wip = dst.resolveSibling(dst.getFileName() + ".recompress.wip");
        Files.write(wip, out);
        try {
            Files.move(wip, dst,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(wip, dst, StandardCopyOption.REPLACE_EXISTING);
        }

        return compBodyLen - (long) newLen;
    }
}