package com.bugfunbug.linearreader.linear;

import com.bugfunbug.linearreader.LinearRuntime;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;

import java.io.*;
import java.nio.file.*;

/**
 * Converts legacy .mca (Anvil) region files to .linear format in-place.
 *
 * Conversion is intentionally lazy: only the specific region Minecraft is about
 * to read or write is converted. Large worlds can contain thousands of .mca
 * files, and bulk migration during world load or shutdown can make integrated
 * servers look hung and can exhaust memory/disk bandwidth.
 *
 * Correctness:
 *  - Uses vanilla RegionFile to read, so GZip/Zlib/uncompressed/.mcc all work.
 *  - NBT bytes copied verbatim — no parsing, no palette index translation.
 *  - Writes go through LinearRegionFile for the same atomic .wip->rename path.
 *  - Idempotent: .linear already present -> leave any .mca sidecar alone.
 *    Voxy compatibility may intentionally create temporary .mca files.
 */
public final class MCAConverter {

    private MCAConverter() {}

    /**
     * Bulk conversion used to run from RegionFileStorage construction. That
     * does not scale for large worlds, especially with async save mods opening
     * storage late during shutdown. Keep the hook as a no-op for old family code.
     */
    public static void convertFolder(Path regionFolder) {
        // Lazy per-region conversion happens in convertRegionIfNeeded().
    }

    public static void convertRegionIfNeeded(Path regionFolder, int regionX, int regionZ) throws IOException {
        if (regionFolder == null || !Files.isDirectory(regionFolder)) return;

        Path linearPath = regionFolder.resolve("r." + regionX + "." + regionZ + ".linear");
        if (Files.exists(linearPath)) {
            return;
        }

        Path mcaPath = regionFolder.resolve("r." + regionX + "." + regionZ + ".mca");
        if (!Files.isRegularFile(mcaPath)) {
            return;
        }

        String folderLabel = regionFolder.getFileName() != null ? regionFolder.getFileName().toString() : "";
        String fileLabel = folderLabel.isEmpty()
                ? mcaPath.getFileName().toString()
                : folderLabel + "/" + mcaPath.getFileName();

        long startNs = System.nanoTime();
        LinearRuntime.LOGGER.info("[LinearReader] Converting legacy region {} to .linear.", fileLabel);
        convertOne(mcaPath);
        long ms = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
        LinearRuntime.LOGGER.info("[LinearReader] Converted legacy region {} in {}ms.", fileLabel, ms);
    }

    private static void convertOne(Path mcaPath) throws IOException {
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

        try (RegionFile rf = LinearRuntime.openVanillaRegionFile(mcaPath, dir, false)) {
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
