package com.bugfunbug.linearreader.mixin;

import com.bugfunbug.linearreader.LinearReader;
import com.bugfunbug.linearreader.LinearStats;
import com.bugfunbug.linearreader.linear.*;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StreamTagVisitor;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.annotation.Nullable;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;

@Mixin(RegionFileStorage.class)
public abstract class RegionFileStorageMixin {

    @Shadow @Final
    Path folder;

    @Shadow @Final
    private boolean sync;

    // Vanilla's own region cache — shadowed so getRegionFile() can populate it
    // for c2me's IRegionFile.invokeWriteChunk path.
    @Shadow @Final
    private Long2ObjectLinkedOpenHashMap<RegionFile> regionCache;

    @Unique
    private Long2ObjectLinkedOpenHashMap<LinearRegionFile> linearCache;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void initLinearCache(CallbackInfo ci) {
        linearCache = new Long2ObjectLinkedOpenHashMap<>();
            if (folder == null) return;
        // Convert any .mca files in this region folder before the first read/write.
        // This runs synchronously inside the RegionFileStorage constructor, so it
        // is guaranteed to complete before any chunk can be loaded or generated.
        // This is the correct place — ServerStartingEvent fires too late on
        // integrated servers because spawn chunk preparation happens before it.
        MCAConverter.convertFolder(folder);
        IdleRecompressor.registerFolder(folder);
    }

    @Unique
    @Nullable
    private LinearRegionFile linearGetOrCreate(ChunkPos pos, boolean existingOnly) throws IOException {
        long key = ChunkPos.asLong(pos.getRegionX(), pos.getRegionZ());

        LinearRegionFile cached = linearCache.getAndMoveToFirst(key);
        if (cached != null) {
            LinearStats.recordCacheHit();
            return cached;
        }
        LinearStats.recordCacheMiss();

        // Use DHPregenMonitor.effectiveCacheSize() so that during DH pregen the
        // cache is held at 8 entries instead of the default 256.  Each eviction
        // submits a flush to the background executor, keeping memory usage
        // proportional to PREGEN_CACHE_SIZE × (max region NBT size) rather than
        // 256 × that — the difference between ~64 MB and ~2 GB peak residency.
        if (linearCache.size() >= DHPregenMonitor.effectiveCacheSize()) {
            // Evict the LRU region that is not pinned.
            // linearCache is ordered MRU-first, so the last key in iteration is LRU.
            // We scan forward and keep the last non-pinned key we see.
            long evictKey    = Long.MIN_VALUE;
            for (long k : linearCache.keySet()) {
                if (!LinearReader.isPinned(linearCache.get(k).getPath())) {
                    evictKey = k; // keep scanning — want the LRU (last) non-pinned entry
                }
            }
            if (evictKey != Long.MIN_VALUE) {
                LinearRegionFile evicted = linearCache.remove(evictKey);
                LinearReader.submitFlush(evicted);
            }
            // If evictKey is still MIN_VALUE, every cached region is pinned.
            // Allow the cache to grow past its limit rather than evict a pinned region.
        }

        Path linearPath = folder.resolve(
                "r." + pos.getRegionX() + "." + pos.getRegionZ() + ".linear");

        if (existingOnly && !Files.exists(linearPath)) return null;

        Files.createDirectories(folder);
        LinearRegionFile region = new LinearRegionFile(linearPath, sync);
        linearCache.putAndMoveToFirst(key, region);
        return region;
    }

    /**
     * @author LinearReader
     * @reason Replace Anvil (.mca) chunk reading with Linear (.linear) format.
     */
    @Overwrite
    public CompoundTag read(ChunkPos pos) throws IOException {
        IdleRecompressor.notifyIO();
        LinearRegionFile region = linearGetOrCreate(pos, true);
        if (region == null) return null;
        try (DataInputStream dis = region.read(pos)) {
            if (dis == null) return null;
            return NbtIo.read(dis);  // timing removed — handled inside LinearRegionFile.read()
        } catch (IOException e) {
            LinearReader.LOGGER.error("[LinearReader] Failed to read chunk {}: {}",
                    pos, e.getMessage(), e);
            throw e;
        }
    }

    /**
     * @author LinearReader
     * @reason Replace Anvil (.mca) chunk writing with Linear (.linear) format.
     */
    @Overwrite
    protected void write(ChunkPos pos, @Nullable CompoundTag tag) throws IOException {
        IdleRecompressor.notifyIO();
        if (tag == null) return;
        LinearRegionFile region = linearGetOrCreate(pos, false);
        if (region == null)
            throw new IOException("[LinearReader] Could not open region for " + pos);
        try (DataOutputStream dos = region.write(pos)) {
            long t = System.nanoTime();
            NbtIo.write(tag, dos);
            LinearStats.recordChunkWrite(System.nanoTime() - t);
        } catch (IOException e) {
            LinearReader.LOGGER.error("[LinearReader] Failed to write chunk {}: {}",
                    pos, e.getMessage(), e);
            throw e;
        }
    }

    /**
     * @author LinearReader
     * @reason Return a LinearBackedRegionFile so c2me's direct RegionFile access
     *         path (IRegionFile.invokeWriteChunk) is intercepted by RegionFileMixin.
     */
    @Overwrite
    private RegionFile getRegionFile(ChunkPos pos) throws IOException {
        long key = ChunkPos.asLong(pos.getRegionX(), pos.getRegionZ());

        RegionFile cached = regionCache.getAndMoveToFirst(key);
        if (cached != null) {
            if (cached instanceof LinearBackedRegionFile) {
                linearGetOrCreate(pos, false);
            }
            return cached;
        }

        if (regionCache.size() >= 256) {
            regionCache.removeLast().close(); // no-op on LinearBackedRegionFile
        }

        LinearRegionFile linear = linearGetOrCreate(pos, false);
        LinearBackedRegionFile backed = LinearBackedRegionFile.create(linear);
        regionCache.putAndMoveToFirst(key, backed);
        return backed;
    }

    /**
     * @author LinearReader
     * @reason Flush Linear region files instead of Anvil region files.
     */
    @Overwrite
    public void flush() throws IOException {
        IOException first = null;
        for (LinearRegionFile region : linearCache.values()) {
            try { region.flush(); }
            catch (IOException e) {
                LinearReader.LOGGER.error("[LinearReader] Flush error: {}", e.getMessage(), e);
                if (first == null) first = e;
            }
        }
        if (first != null) throw first;
    }

    /**
     * @author LinearReader
     * @reason Close Linear region files instead of Anvil region files.
     */
    @Overwrite
    public void close() throws IOException {
        IOException first = null;
        for (LinearRegionFile region : linearCache.values()) {
            try { region.close(); }
            catch (IOException e) {
                LinearReader.LOGGER.error("[LinearReader] Close error: {}", e.getMessage(), e);
                if (first == null) first = e;
            }
        }
        linearCache.clear();
        regionCache.clear();
        if (first != null) throw first;
    }

    /**
     * @author LinearReader
     * @reason Replace Anvil scanChunk (used by POI system) with Linear format.
     */
    @Overwrite
    public void scanChunk(ChunkPos pos, StreamTagVisitor visitor) throws IOException {
        try {
            LinearRegionFile region = linearGetOrCreate(pos, true);
            if (region == null || !region.hasChunk(pos)) return;
            try (DataInputStream dis = region.read(pos)) {
                if (dis != null) NbtIo.parse(dis, visitor);
            }
        } catch (IOException e) {
            LinearReader.LOGGER.error("[LinearReader] Failed to scan chunk {}: {}",
                    pos, e.getMessage(), e);
            throw e;
        }
    }
}