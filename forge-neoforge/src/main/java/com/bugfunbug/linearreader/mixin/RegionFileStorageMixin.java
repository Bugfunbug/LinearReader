package com.bugfunbug.linearreader.mixin;

import com.bugfunbug.linearreader.LinearRuntime;
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
import java.util.ArrayList;
import java.util.List;

@Mixin(RegionFileStorage.class)
public abstract class RegionFileStorageMixin {

    @Shadow @Final
    Path folder;

    @Shadow @Final
    private boolean sync;

    @Shadow @Final
    private Long2ObjectLinkedOpenHashMap<RegionFile> regionCache;

    @Unique
    private Long2ObjectLinkedOpenHashMap<LinearRegionFile> linearCache;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void initLinearCache(CallbackInfo ci) {
        linearCache = new Long2ObjectLinkedOpenHashMap<>();
        if (folder == null) return;
        MCAConverter.convertFolder(folder);
        IdleRecompressor.registerFolder(folder);
    }

    /**
     * The ONLY method that needs the coarse RegionFileStorage lock.
     * It protects the linearCache map (Long2ObjectLinkedOpenHashMap is not thread-safe).
     *
     * With lazy loading, this method never touches disk — it just does map operations
     * and creates a LinearRegionFile shell. Disk I/O happens lazily under the
     * per-region ReentrantReadWriteLock, completely outside this lock.
     *
     * Before lazy loading was added, this method called new LinearRegionFile() which
     * called loadFromDisk() (5–300 ms) while holding this lock, serializing ALL
     * chunk I/O for the dimension behind a single disk read. That was the root cause
     * of 11000 ms read max times and chunk holes.
     */
    @Unique
    @Nullable
    private synchronized LinearRegionFile linearGetOrCreate(ChunkPos pos, boolean existingOnly) throws IOException {
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
                // Never evict a region whose latest state is still only in memory.
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

        Path linearPath = folder.resolve(
                "r." + pos.getRegionX() + "." + pos.getRegionZ() + ".linear");

        if (existingOnly && !Files.exists(linearPath)) return null;

        Files.createDirectories(folder);
        // Constructor is now instant — no disk I/O. Loading happens lazily on first access.
        LinearRegionFile region = new LinearRegionFile(linearPath, sync);
        linearCache.putAndMoveToFirst(key, region);
        return region;
    }

    /**
     * @author LinearReader
     * @reason Replace Anvil (.mca) chunk reading with Linear (.linear) format.
     *
     * NOT synchronized — linearGetOrCreate() handles its own synchronization and is
     * now fast (no disk I/O). The actual NbtIo.read() and region.read() (which triggers
     * lazy disk loading if needed) run outside the coarse lock, so multiple threads can
     * do chunk I/O concurrently for different regions. Per-region synchronization is
     * handled by LinearRegionFile's own ReentrantReadWriteLock.
     */
    @Overwrite
    public CompoundTag read(ChunkPos pos) throws IOException {
        IdleRecompressor.notifyIO();
        LinearRegionFile region = linearGetOrCreate(pos, true);
        if (region == null) return null;
        try (DataInputStream dis = region.read(pos)) {
            if (dis == null) return null;
            return NbtIo.read(dis);
        } catch (IOException e) {
            LinearRuntime.LOGGER.error("[LinearReader] Failed to read chunk {}: {}",
                    pos, e.getMessage(), e);
            throw e;
        }
    }

    /**
     * @author LinearReader
     * @reason Replace Anvil (.mca) chunk writing with Linear (.linear) format.
     *         Not synchronized — see read() javadoc.
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
            LinearRuntime.LOGGER.error("[LinearReader] Failed to write chunk {}: {}",
                    pos, e.getMessage(), e);
            throw e;
        }
    }

    /**
     * @author LinearReader
     * @reason Return a LinearBackedRegionFile for c2me's direct RegionFile access path.
     *         Synchronized because it accesses both regionCache and linearCache.
     */
    @Overwrite
    private synchronized RegionFile getRegionFile(ChunkPos pos) throws IOException {
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
     *         Snapshots the region list under lock, then flushes outside the lock so
     *         flush I/O (which can take 100–5000ms) never blocks concurrent reads/writes.
     */
    @Overwrite
    public void flush() throws IOException {
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
     *         Clears caches atomically under lock, then closes outside the lock.
     */
    @Overwrite
    public void close() throws IOException {
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
     * @reason Replace Anvil scanChunk (used by POI system) with Linear format.
     *         Not synchronized — region.read() handles lazy loading and its own locking.
     */
    @Overwrite
    public void scanChunk(ChunkPos pos, StreamTagVisitor visitor) throws IOException {
        IdleRecompressor.notifyIO();
        LinearRegionFile region = linearGetOrCreate(pos, true);
        if (region == null) return;
        try (DataInputStream dis = region.read(pos)) {
            if (dis != null) NbtIo.parse(dis, visitor);
        } catch (IOException e) {
            LinearRuntime.LOGGER.error("[LinearReader] Failed to scan chunk {}: {}",
                    pos, e.getMessage(), e);
            throw e;
        }
    }
}
