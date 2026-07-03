package com.bugfunbug.linearreader.mixin;

import com.bugfunbug.linearreader.LinearRuntime;
import com.bugfunbug.linearreader.LinearStats;
import com.bugfunbug.linearreader.linear.DHPregenMonitor;
import com.bugfunbug.linearreader.linear.IdleRecompressor;
import com.bugfunbug.linearreader.linear.LinearBackedRegionFile;
import com.bugfunbug.linearreader.linear.LinearRegionFile;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
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

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Mixin(RegionFileStorage.class)
public abstract class RegionFileStorageMixin {

    @Shadow(remap = false) @Final
    Path folder;

    @Shadow(remap = false) @Final
    private boolean sync;

    @Shadow(remap = false) @Final
    private Long2ObjectLinkedOpenHashMap<RegionFile> regionCache;

    @Unique
    private Long2ObjectLinkedOpenHashMap<LinearRegionFile> linearCache;

    @Unique
    private void ensureLinearCacheInitialized() {
        if (linearCache != null) {
            return;
        }
        synchronized (this) {
            if (linearCache == null) {
                linearCache = new Long2ObjectLinkedOpenHashMap<>();
                LinearRuntime.onRegionStorageOpened(folder);
            }
        }
    }

    @Unique
    private synchronized LinearRegionFile linearGetOrCreate(ChunkPos pos, boolean existingOnly) throws IOException {
        ensureLinearCacheInitialized();
        if (folder == null) return null;

        long key = ChunkPos.asLong(pos.getRegionX(), pos.getRegionZ());

        LinearRegionFile cached = linearCache.getAndMoveToFirst(key);
        if (cached != null) {
            LinearStats.recordCacheHit();
            return cached;
        }
        LinearStats.recordCacheMiss();

        if (linearCache.size() >= DHPregenMonitor.effectiveCacheSize()) {
            long evictKey = Long.MIN_VALUE;
            for (long k : linearCache.keySet()) {
                LinearRegionFile candidate = linearCache.get(k);
                if (candidate != null
                        && !LinearRuntime.isPinnedNormalized(candidate.getNormalizedPath())
                        && candidate.canEvictFromCache()) {
                    evictKey = k;
                }
            }
            if (evictKey != Long.MIN_VALUE) {
                LinearRegionFile evicted = linearCache.remove(evictKey);
                RegionFile staleWrapper = regionCache.remove(evictKey);
                if (staleWrapper != null) {
                    staleWrapper.close();
                }
                LinearRuntime.submitFlush(evicted);
            }
        }

        Path linearPath = LinearRuntime.resolveLinearRegionPath(folder, pos);
        LinearRuntime.convertLegacyRegionIfNeeded(folder, pos);
        LinearRegionFile region = new LinearRegionFile(linearPath, sync);
        linearCache.putAndMoveToFirst(key, region);
        return region;
    }

    /**
     * @author LinearReader
     * @reason Replace Anvil (.mca) chunk reading with Linear (.linear) format.
     */
    @Overwrite(remap = false)
    public CompoundTag read(ChunkPos pos) throws IOException {
        IdleRecompressor.notifyIO();
        LinearRegionFile region = linearGetOrCreate(pos, true);
        if (region == null) return null;
        try (DataInputStream dis = region.read(pos)) {
            if (dis == null) return null;
            boolean statsEnabled = LinearStats.isEnabled();
            long t = statsEnabled ? System.nanoTime() : 0L;
            CompoundTag tag = NbtIo.read(dis);
            if (statsEnabled) {
                LinearStats.recordChunkDeserialize(System.nanoTime() - t);
            }
            return tag;
        } catch (IOException e) {
            LinearRuntime.LOGGER.error("[LinearReader] Failed to read chunk {}: {}",
                    pos, e.getMessage(), e);
            throw e;
        }
    }

    /**
     * @author LinearReader
     * @reason Replace Anvil (.mca) chunk writing with Linear (.linear) format.
     */
    @Overwrite(remap = false)
    protected void write(ChunkPos pos, CompoundTag tag) throws IOException {
        IdleRecompressor.notifyIO();
        if (tag == null) return;
        LinearRegionFile region = linearGetOrCreate(pos, false);
        if (region == null)
            throw new IOException("[LinearReader] Could not open region for " + pos);
        try (DataOutputStream dos = region.write(pos)) {
            boolean statsEnabled = LinearStats.isEnabled();
            long t = statsEnabled ? System.nanoTime() : 0L;
            NbtIo.write(tag, dos);
            if (statsEnabled) {
                LinearStats.recordChunkWrite(System.nanoTime() - t);
            }
        } catch (IOException e) {
            LinearRuntime.LOGGER.error("[LinearReader] Failed to write chunk {}: {}",
                    pos, e.getMessage(), e);
            throw e;
        }
    }

    /**
     * @author LinearReader
     * @reason Return a LinearBackedRegionFile for c2me's direct RegionFile access path.
     */
    @Overwrite(remap = false)
    private synchronized RegionFile getRegionFile(ChunkPos pos) throws IOException {
        ensureLinearCacheInitialized();
        long key = ChunkPos.asLong(pos.getRegionX(), pos.getRegionZ());

        RegionFile cached = regionCache.getAndMoveToFirst(key);
        if (cached != null) {
            LinearStats.recordWrapperCacheHit();
            if (cached instanceof LinearBackedRegionFile) {
                linearCache.getAndMoveToFirst(key);
            }
            return cached;
        }
        LinearStats.recordWrapperCacheMiss();

        if (regionCache.size() >= DHPregenMonitor.effectiveCacheSize()) {
            regionCache.removeLast().close();
        }

        LinearRegionFile linear = linearGetOrCreate(pos, false);
        LinearBackedRegionFile backed = LinearBackedRegionFile.create(linear);
        regionCache.putAndMoveToFirst(key, backed);
        return backed;
    }

    /**
     * @author LinearReader
     * @reason Flush Linear region files.
     */
    @Overwrite(remap = false)
    public void flush() throws IOException {
        ensureLinearCacheInitialized();
        final List<LinearRegionFile> toFlush;
        synchronized (this) {
            toFlush = new ArrayList<>();
            for (LinearRegionFile region : linearCache.values()) {
                if (region.isDirty()) {
                    toFlush.add(region);
                }
            }
        }
        try {
            LinearRuntime.flushRegionsBlocking(toFlush);
        } catch (IOException e) {
            LinearRuntime.LOGGER.error("[LinearReader] Flush error: {}", e.getMessage(), e);
            throw e;
        }
    }

    /**
     * @author LinearReader
     * @reason Close Linear region files.
     */
    @Overwrite(remap = false)
    public void close() throws IOException {
        ensureLinearCacheInitialized();
        final List<LinearRegionFile> toClose;
        synchronized (this) {
            toClose = new ArrayList<>(linearCache.values());
            linearCache.clear();
            regionCache.clear();
        }
        try {
            LinearRuntime.closeRegionsBlocking(toClose);
        } catch (IOException e) {
            LinearRuntime.LOGGER.error("[LinearReader] Close error: {}", e.getMessage(), e);
            throw e;
        }
    }

    /**
     * @author LinearReader
     * @reason Replace Anvil scanChunk (used by POI system) with the 1.20.2+ Linear format scan path.
     */
    @Overwrite(remap = false)
    public void scanChunk(ChunkPos pos, StreamTagVisitor visitor) throws IOException {
        ensureLinearCacheInitialized();
        IdleRecompressor.notifyIO();
        LinearRegionFile region = linearGetOrCreate(pos, true);
        if (region == null) return;
        try (DataInputStream dis = region.read(pos)) {
            if (dis != null) NbtIo.parse(dis, visitor, NbtAccounter.unlimitedHeap());
        } catch (IOException e) {
            LinearRuntime.LOGGER.error("[LinearReader] Failed to scan chunk {}: {}",
                    pos, e.getMessage(), e);
            throw e;
        }
    }
}
