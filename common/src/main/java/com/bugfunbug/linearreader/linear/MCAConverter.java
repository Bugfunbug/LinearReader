package com.bugfunbug.linearreader.linear;

import com.bugfunbug.linearreader.LinearRuntime;
import com.bugfunbug.linearreader.config.LinearConfig;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Converts legacy .mca (Anvil) region files to .linear format in-place.
 *
 * Called from RegionFileStorageMixin.initLinearCache() — i.e. from inside the
 * RegionFileStorage constructor, before any chunk read or write can occur on
 * that folder.  This is the only safe place to run conversion: ServerStartingEvent
 * fires too late on integrated (singleplayer) servers because Minecraft prepares
 * the spawn area before that event is dispatched.
 *
 * Each RegionFileStorage instance owns exactly one region folder (one dimension),
 * so convertFolder() only needs to scan that one flat directory — no recursion.
 *
 * Correctness:
 *  - Uses vanilla RegionFile to read, so GZip/Zlib/uncompressed/.mcc all work.
 *  - NBT bytes copied verbatim — no parsing, no palette index translation.
 *  - Writes go through LinearRegionFile for the same atomic .wip->rename path.
 *  - Idempotent: .linear already present -> just delete the .mca.
 *  - Thread-safe across dimensions: each call operates on a different folder.
 */
public final class MCAConverter {

    private MCAConverter() {}

    /**
     * Converts all .mca files in regionFolder to .linear, then deletes them.
     * Runs synchronously on the calling thread (the server thread that is
     * constructing RegionFileStorage), so returns only when all conversions
     * in this folder are done.
     */
    public static void convertFolder(Path regionFolder) {
        if (regionFolder == null || !Files.isDirectory(regionFolder)) return;
        if (!Files.isDirectory(regionFolder)) return;

        List<Path> mcaFiles;
        try (Stream<Path> s = Files.list(regionFolder)) {
            mcaFiles = s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".mca"))
                    .sorted()
                    .collect(Collectors.toList());
        } catch (IOException e) {
            LinearRuntime.LOGGER.error(
                    "[LinearReader] Cannot list region folder {}: {}", regionFolder, e.getMessage());
            return;
        }

        int total = mcaFiles.size();
        if (total == 0) return;

        int compressionLevel = LinearConfig.getCompressionLevel();

        LinearRuntime.LOGGER.info(
                "[LinearReader] Converting {} .mca file(s) in {} to .linear (zstd-level={}).",
                total, regionFolder.getFileName(), compressionLevel);

        long          t0      = System.currentTimeMillis();
        AtomicInteger doneOk  = new AtomicInteger(0);
        AtomicInteger doneBad = new AtomicInteger(0);
        boolean       logEach = (total <= 20);

        // Conversion is I/O-bound. 4 threads keeps things fast without
        // hammering spinning disks with too many concurrent seeks.
        int threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()));
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(threads, r -> {
                    Thread t = new Thread(r, "lr-mca-convert");
                    t.setDaemon(true);
                    return t;
                });

        for (Path mca : mcaFiles) {
            pool.submit(() -> {
                try {
                    convertOne(mca, compressionLevel);
                    int n = doneOk.incrementAndGet();
                    if (logEach) {
                        LinearRuntime.LOGGER.info("[LinearReader] Converted: {}", mca.getFileName());
                    } else if (n % 50 == 0 || n == total) {
                        LinearRuntime.LOGGER.info(
                                "[LinearReader] Conversion progress: {}/{}", n, total);
                    }
                } catch (Exception e) {
                    doneBad.incrementAndGet();
                    LinearRuntime.LOGGER.error("[LinearReader] Failed to convert {}: {}",
                            mca.getFileName(), e.getMessage(), e);
                }
            });
        }

        pool.shutdown();
        try {
            if (!pool.awaitTermination(30, java.util.concurrent.TimeUnit.MINUTES)) {
                LinearRuntime.LOGGER.error(
                        "[LinearReader] Conversion timed out after 30 minutes in {}. " +
                                "Remaining .mca files were NOT deleted.", regionFolder.getFileName());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LinearRuntime.LOGGER.error("[LinearReader] MCA conversion interrupted in {}!",
                    regionFolder.getFileName());
        }

        long ms  = System.currentTimeMillis() - t0;
        int  ok  = doneOk.get();
        int  bad = doneBad.get();

        if (bad == 0) {
            LinearRuntime.LOGGER.info(
                    "[LinearReader] Conversion complete: {} region(s) in {}ms.", ok, ms);
        } else {
            LinearRuntime.LOGGER.warn(
                    "[LinearReader] Conversion complete: {} ok, {} FAILED in {}ms. " +
                            "Failed .mca files were NOT deleted - restart to retry.", ok, bad, ms);
        }
    }

    // -------------------------------------------------------------------------
    // Per-file conversion — unchanged from before
    // -------------------------------------------------------------------------

    private static void convertOne(Path mcaPath, int compressionLevel) throws IOException {
        String[] parts  = mcaPath.getFileName().toString().split("\\.");
        int regionX     = Integer.parseInt(parts[1]);
        int regionZ     = Integer.parseInt(parts[2]);
        Path dir        = mcaPath.getParent();
        Path linearPath = dir.resolve("r." + regionX + "." + regionZ + ".linear");

        // Already converted on a previous interrupted run.
        if (Files.exists(linearPath)) {
            Files.delete(mcaPath);
            deleteMccFiles(dir, regionX, regionZ);
            return;
        }

        byte[][] chunkData  = new byte[1024][];
        int      chunkCount = 0;

        try (RegionFile rf = new RegionFile(mcaPath, dir, false)) {
            for (int i = 0; i < 1024; i++) {
                int lx = i % 32;
                int lz = i / 32;
                ChunkPos pos = new ChunkPos(regionX * 32 + lx, regionZ * 32 + lz);
                DataInputStream dis = rf.getChunkDataInputStream(pos);
                if (dis == null) continue;
                try {
                    chunkData[i] = dis.readAllBytes();
                    chunkCount++;
                } finally {
                    dis.close();
                }
            }
        }

        if (chunkCount == 0) {
            Files.delete(mcaPath);
            deleteMccFiles(dir, regionX, regionZ);
            return;
        }

        LinearRegionFile linear = new LinearRegionFile(linearPath, false);
        boolean writeOk = false;
        try {
            for (int i = 0; i < 1024; i++) {
                if (chunkData[i] == null) continue;
                int lx = i % 32;
                int lz = i / 32;
                ChunkPos pos = new ChunkPos(regionX * 32 + lx, regionZ * 32 + lz);
                try (DataOutputStream dos = linear.write(pos)) {
                    dos.write(chunkData[i]);
                }
                chunkData[i] = null; // release ASAP
            }
            linear.flush();
            writeOk = true;
        } finally {
            LinearRegionFile.ALL_OPEN.remove(linear);
            if (!writeOk) {
                try { Files.deleteIfExists(linearPath); } catch (IOException ignored) {}
            }
        }

        Files.delete(mcaPath);
        deleteMccFiles(dir, regionX, regionZ);
    }

    private static void deleteMccFiles(Path dir, int regionX, int regionZ) {
        for (int lz = 0; lz < 32; lz++) {
            for (int lx = 0; lx < 32; lx++) {
                Path mcc = dir.resolve(
                        "c." + (regionX * 32 + lx) + "." + (regionZ * 32 + lz) + ".mcc");
                try { Files.deleteIfExists(mcc); } catch (IOException ignored) {}
            }
        }
    }
}