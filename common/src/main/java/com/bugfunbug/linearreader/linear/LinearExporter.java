package com.bugfunbug.linearreader.linear;

import com.bugfunbug.linearreader.LinearRuntime;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.stream.Stream;

/**
 * Exports all .linear region files back to vanilla Anvil (.mca) format.
 *
 * Output goes to <worldRoot>/../<worldName>_mca_export/ mirroring the exact
 * folder structure of the world. The original .linear files are never touched.
 *
 * Runs on a low-priority background thread so the server stays responsive.
 * Can be stopped at any time via stop() — already-exported files are kept.
 *
 * Uses vanilla RegionFile to write, so the output is byte-perfect Anvil
 * (Zlib compressed, standard sector layout) that any tool or vanilla server
 * can read without modification.
 */
public final class LinearExporter {

    private LinearExporter() {}

    private static final AtomicBoolean RUNNING      = new AtomicBoolean(false);
    private static volatile Thread     WORKER       = null;
    private static final AtomicInteger FILES_DONE   = new AtomicInteger(0);
    private static final AtomicInteger FILES_TOTAL  = new AtomicInteger(0);
    private static final AtomicInteger FILES_FAILED = new AtomicInteger(0);

    public static boolean isRunning()  { return RUNNING.get(); }
    public static int     filesDone()  { return FILES_DONE.get(); }
    public static int     filesTotal() { return FILES_TOTAL.get(); }
    public static int     filesFailed(){ return FILES_FAILED.get(); }

    /**
     * Starts the export. Returns false if already running.
     * worldRoot is the root of the world folder (e.g. .../world/).
     */
    public static boolean start(Path worldRoot) {
        if (!RUNNING.compareAndSet(false, true)) return false;

        FILES_DONE.set(0);
        FILES_TOTAL.set(0);
        FILES_FAILED.set(0);

        Thread t = new Thread(() -> {
            try {
                doExport(worldRoot);
            } finally {
                RUNNING.set(false);
                WORKER = null;
            }
        }, "lr-mca-exporter");
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

    // -------------------------------------------------------------------------
    // Core export logic
    // -------------------------------------------------------------------------

    private static void doExport(Path worldRoot) {
        // Output sits next to the world folder: world/ -> world_mca_export/
        Path exportRoot = worldRoot.resolveSibling(
                worldRoot.getFileName().toString() + "_mca_export");

        LinearRuntime.LOGGER.info(
                "[LinearReader] Starting .linear -> .mca export. Output: {}", exportRoot);

        // Collect every .linear file under worldRoot, preserving subfolder structure.
        List<Path> linearFiles = new ArrayList<>();
        try (Stream<Path> s = Files.walk(worldRoot)) {
            s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".linear"))
                    .sorted()
                    .forEach(linearFiles::add);
        } catch (IOException e) {
            LinearRuntime.LOGGER.error(
                    "[LinearReader] Export failed — cannot walk world folder: {}", e.getMessage());
            return;
        }

        FILES_TOTAL.set(linearFiles.size());

        if (linearFiles.isEmpty()) {
            LinearRuntime.LOGGER.info("[LinearReader] No .linear files found to export.");
            return;
        }

        LinearRuntime.LOGGER.info(
                "[LinearReader] Exporting {} region file(s) to .mca...", linearFiles.size());

        for (Path src : linearFiles) {
            if (Thread.currentThread().isInterrupted()) {
                LinearRuntime.LOGGER.info(
                        "[LinearReader] Export stopped. {}/{} file(s) done.",
                        FILES_DONE.get(), FILES_TOTAL.get());
                return;
            }

            // Mirror the path: worldRoot/DIM-1/region/r.0.0.linear
            //               -> exportRoot/DIM-1/region/r.0.0.mca
            Path rel        = worldRoot.relativize(src);
            Path destFolder = exportRoot.resolve(rel).getParent();
            String srcName  = src.getFileName().toString();
            String destName = srcName.substring(0, srcName.length() - ".linear".length()) + ".mca";
            Path   dest     = destFolder.resolve(destName);

            // Skip if already exported — allows resuming an interrupted run.
            if (Files.exists(dest)) {
                FILES_DONE.incrementAndGet();
                continue;
            }

            try {
                exportOne(src, dest, destFolder);
                FILES_DONE.incrementAndGet();
            } catch (Exception e) {
                FILES_FAILED.incrementAndGet();
                LinearRuntime.LOGGER.warn("[LinearReader] Failed to export {}: {}",
                        srcName, e.getMessage());
            }
        }

        int done   = FILES_DONE.get();
        int failed = FILES_FAILED.get();
        if (failed == 0) {
            LinearRuntime.LOGGER.info(
                    "[LinearReader] Export complete: {} file(s) written to {}",
                    done, exportRoot);
        } else {
            LinearRuntime.LOGGER.warn(
                    "[LinearReader] Export complete: {} ok, {} failed. " +
                            "Failed files can be retried by running the command again.",
                    done, failed);
        }
    }

    private static void exportOne(Path linearPath, Path mcaDest, Path mcaFolder)
            throws IOException {

        String[] parts  = linearPath.getFileName().toString().split("\\.");
        int regionX     = Integer.parseInt(parts[1]);
        int regionZ     = Integer.parseInt(parts[2]);

        Files.createDirectories(mcaFolder);

        // Open the .linear file for reading. Constructor adds to ALL_OPEN;
        // we remove it in the finally block — this is a read-only export instance,
        // not a live cache entry, and we never call flush() on it.
        LinearRegionFile linear = new LinearRegionFile(linearPath, false);
        try {
            // RegionFile(path, externalFileDir, dsync)
            // dsync=false — we're writing an export copy, not a live save.
            try (RegionFile mca = new RegionFile(mcaDest, mcaFolder, false)) {
                for (int i = 0; i < 1024; i++) {
                    int lx = i % 32;
                    int lz = i / 32;
                    ChunkPos pos = new ChunkPos(regionX * 32 + lx, regionZ * 32 + lz);

                    try (DataInputStream dis = linear.read(pos)) {
                        if (dis == null) continue;
                        byte[] nbt = dis.readAllBytes();
                        // getChunkDataOutputStream handles Zlib compression + sector
                        // layout internally — we just write raw NBT bytes.
                        try (DataOutputStream dos = mca.getChunkDataOutputStream(pos)) {
                            dos.write(nbt);
                        }
                    }
                }
            }
        } finally {
            // Always remove from ALL_OPEN. Export instances must never be flushed
            // by the shutdown handler — they hold no dirty data.
            LinearRegionFile.ALL_OPEN.remove(linear);
        }
    }
}