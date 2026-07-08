package com.bugfunbug.linearreader.linear;

import com.bugfunbug.linearreader.LinearRuntime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Bulk-converts every legacy .mca region file under a world root to .linear
 * format, instead of waiting for chunks to be visited lazily.
 *
 * Two modes, selected by LinearConfig.isBulkConvertOnLoad():
 *  - convertAll(worldRoot): synchronous, called directly from
 *    LinearRuntime.onServerStarting with no background thread, so the world
 *    stays in its normal unjoinable startup phase until every .mca file has
 *    been safely converted.
 *  - start(worldRoot): same full-world conversion, but run on a background
 *    daemon thread so server startup is not blocked.
 *
 * The existing lazy per-chunk conversion in MCAConverter.convertRegionIfNeeded
 * stays in place as a safety net either way; it is a no-op for any region
 * already converted here.
 */
public final class BulkMcaConverter {

    private BulkMcaConverter() {}

    /** Small pause between files so a huge world doesn't slam the disk with back-to-back writes. */
    private static final long FILE_DELAY_MS = 15L;

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static volatile Thread WORKER = null;
    private static final AtomicInteger FILES_DONE = new AtomicInteger(0);
    private static final AtomicInteger FILES_TOTAL = new AtomicInteger(0);
    private static final AtomicInteger FILES_FAILED = new AtomicInteger(0);

    /** Minimum time between "still converting..." progress lines, so a huge world doesn't spam INFO logs. */
    private static final long PROGRESS_LOG_MIN_INTERVAL_MS = 10_000L;

    public static boolean isRunning()  { return RUNNING.get(); }
    public static int     filesDone()  { return FILES_DONE.get(); }
    public static int     filesTotal() { return FILES_TOTAL.get(); }
    public static int     filesFailed(){ return FILES_FAILED.get(); }

    /**
     * Converts the whole world synchronously on the calling thread. Intended
     * to be called from onServerStarting so the server does not finish
     * starting until conversion is done.
     */
    public static void convertAll(Path worldRoot) {
        doConvert(worldRoot);
    }

    /**
     * Starts the same full-world conversion on a background daemon thread,
     * so the caller returns immediately and the server can finish starting.
     * Returns false if a bulk conversion is already running.
     */
    public static boolean start(Path worldRoot) {
        if (worldRoot == null) return false;
        if (!RUNNING.compareAndSet(false, true)) return false;

        Thread t = new Thread(() -> {
            try {
                doConvert(worldRoot);
            } finally {
                RUNNING.set(false);
                WORKER = null;
            }
        }, "lr-mca-bulk-converter");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY + 1);
        WORKER = t;
        t.start();
        return true;
    }

    public static void stop() {
        Thread w = WORKER;
        if (w != null) w.interrupt();
    }

    private static void doConvert(Path worldRoot) {
        if (worldRoot == null) return;

        FILES_DONE.set(0);
        FILES_TOTAL.set(0);
        FILES_FAILED.set(0);

        List<Path> mcaFiles = new ArrayList<>();
        try (Stream<Path> s = Files.walk(worldRoot)) {
            s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".mca"))
                    .sorted()
                    .forEach(mcaFiles::add);
        } catch (IOException e) {
            LinearRuntime.LOGGER.error(
                    "[LinearReader] Bulk .mca conversion failed - cannot walk world folder: {}", e.getMessage());
            return;
        }

        FILES_TOTAL.set(mcaFiles.size());
        if (mcaFiles.isEmpty()) {
            LinearRuntime.LOGGER.info("[LinearReader] No legacy .mca files found to convert.");
            return;
        }

        LinearRuntime.LOGGER.info(
                "[LinearReader] Converting {} legacy .mca region file(s) to .linear.",
                mcaFiles.size());

        for (Path mcaPath : mcaFiles) {
            if (Thread.currentThread().isInterrupted()) {
                LinearRuntime.LOGGER.info(
                        "[LinearReader] Bulk .mca conversion stopped early. {}/{} file(s) done.",
                        FILES_DONE.get(), FILES_TOTAL.get());
                return;
            }

            Path regionFolder = mcaPath.getParent();
            String name = mcaPath.getFileName().toString();
            int[] coords = parseRegionCoords(name);
            if (coords == null) {
                FILES_DONE.incrementAndGet();
                continue;
            }

            try {
                MCAConverter.convertRegionIfNeeded(regionFolder, coords[0], coords[1]);
            } catch (Exception e) {
                FILES_FAILED.incrementAndGet();
                LinearRuntime.LOGGER.warn("[LinearReader] Bulk conversion failed for {}: {}",
                        name, e.getMessage());
            } finally {
                FILES_DONE.incrementAndGet();
            }

            maybeLogProgress(mcaFiles.size());

            try {
                Thread.sleep(FILE_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LinearRuntime.LOGGER.warn(
                        "[LinearReader] Bulk .mca conversion interrupted after {} file(s).", FILES_DONE.get());
                return;
            }
        }

        int done = FILES_DONE.get();
        int failed = FILES_FAILED.get();
        if (failed == 0) {
            LinearRuntime.LOGGER.info(
                    "[LinearReader] Bulk .mca conversion complete: {} file(s) converted.", done);
        } else {
            LinearRuntime.LOGGER.warn(
                    "[LinearReader] Bulk .mca conversion complete: {} ok, {} failed. "
                            + "Failed files will still convert automatically the first time they're loaded.",
                    done - failed, failed);
        }
    }

    private static volatile long lastProgressLogMs = 0L;

    private static void maybeLogProgress(int total) {
        long nowMs = System.currentTimeMillis();
        if (nowMs - lastProgressLogMs < PROGRESS_LOG_MIN_INTERVAL_MS) {
            return;
        }
        lastProgressLogMs = nowMs;
        LinearRuntime.LOGGER.info(
                "[LinearReader] Bulk .mca conversion progress: {}/{} file(s) done.",
                FILES_DONE.get(), total);
    }

    private static int[] parseRegionCoords(String fileName) {
        if (!fileName.startsWith("r.") || !fileName.endsWith(".mca")) return null;
        String stem = fileName.substring(0, fileName.length() - ".mca".length());
        String[] parts = stem.split("\\.");
        if (parts.length != 3) return null;
        try {
            return new int[]{Integer.parseInt(parts[1]), Integer.parseInt(parts[2])};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}