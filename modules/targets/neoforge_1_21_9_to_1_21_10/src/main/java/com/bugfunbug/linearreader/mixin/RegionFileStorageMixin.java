package com.bugfunbug.linearreader.mixin;

import com.bugfunbug.linearreader.LinearRuntime;
import com.bugfunbug.linearreader.LinearStats;
import com.bugfunbug.linearreader.linear.DHPregenMonitor;
import com.bugfunbug.linearreader.linear.IdleRecompressor;
import com.bugfunbug.linearreader.linear.LinearRegionFile;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Mixin(RegionFileStorage.class)
public abstract class RegionFileStorageMixin {

    @Shadow @Final
    private Path folder;

    @Shadow @Final
    private boolean sync;

    @Shadow @Final
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
            } else {
                // Cache is full and every entry is dirty/flushing right now - nothing
                // can be safely evicted. Priority-flush the worst offender instead of
                // silently growing past the configured cap.
                LinearRuntime.maybePanicFlush(linearCache.values());
            }
        }

        Path linearPath = LinearRuntime.resolveLinearRegionPath(folder, pos);
        LinearRuntime.convertLegacyRegionIfNeeded(folder, pos);
        LinearRegionFile region = new LinearRegionFile(linearPath, sync);
        linearCache.putAndMoveToFirst(key, region);
        return region;
    }

    // Following your 1.21.8 working pattern: Full descriptor, default remap
    @Inject(
            method = "read(Lnet/minecraft/world/level/ChunkPos;)Lnet/minecraft/nbt/CompoundTag;",
            at = @At("HEAD"),
            cancellable = true
    )
    private void interceptRead(ChunkPos pos, CallbackInfoReturnable<CompoundTag> cir) throws IOException {
        IdleRecompressor.notifyIO();
        LinearRegionFile region = linearGetOrCreate(pos, true);
        if (region == null) {
            cir.setReturnValue(null);
            return;
        }
        boolean statsEnabled = LinearStats.isEnabled();
        try (DataInputStream dis = region.read(pos)) {
            if (dis == null) {
                cir.setReturnValue(null);
                return;
            }
            long t = statsEnabled ? System.nanoTime() : 0L;
            // Explicitly cast to DataInput to comply with 1.21.9+ compiled method signature rules
            CompoundTag tag = NbtIo.read((DataInput) dis, NbtAccounter.unlimitedHeap());
            if (statsEnabled) {
                LinearStats.recordChunkDeserialize(System.nanoTime() - t);
            }
            cir.setReturnValue(tag);
        } catch (IOException e) {
            LinearRuntime.LOGGER.error("[LinearReader] Failed to read chunk {}: {}", pos, e.getMessage(), e);
            throw e;
        }
    }

    // Following your 1.21.8 working pattern: Simple method string, default remap
    @Inject(method = "write", at = @At("HEAD"), cancellable = true)
    private void interceptWrite(ChunkPos pos, CompoundTag tag, CallbackInfo ci) throws IOException {
        IdleRecompressor.notifyIO();
        if (tag == null) {
            ci.cancel();
            return;
        }
        LinearRegionFile region = linearGetOrCreate(pos, false);
        if (region == null) {
            throw new IOException("[LinearReader] Could not open region for " + pos);
        }
        try (DataOutputStream dos = region.write(pos)) {
            boolean statsEnabled = LinearStats.isEnabled();
            long t = statsEnabled ? System.nanoTime() : 0L;
            NbtIo.write(tag, dos);
            if (statsEnabled) {
                LinearStats.recordChunkWrite(System.nanoTime() - t);
            }
        } catch (IOException e) {
            LinearRuntime.LOGGER.error("[LinearReader] Failed to write chunk {}: {}", pos, e.getMessage(), e);
            throw e;
        }
        ci.cancel();
    }

    // Following your 1.21.8 working pattern: Simple method string, default remap
    @Inject(method = "flush", at = @At("HEAD"), cancellable = true)
    private void interceptFlush(CallbackInfo ci) throws IOException {
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
        ci.cancel();
    }

    // Following your 1.21.8 working pattern: Simple method string, default remap
    @Inject(method = "close", at = @At("HEAD"), cancellable = true)
    private void interceptClose(CallbackInfo ci) throws IOException {
        ensureLinearCacheInitialized();
        final List<LinearRegionFile> toClose;
        synchronized (this) {
            toClose = new ArrayList<>(linearCache.values());
            linearCache.clear();
        }
        try {
            LinearRuntime.closeRegionsBlocking(toClose);
        } catch (IOException e) {
            LinearRuntime.LOGGER.error("[LinearReader] Close error: {}", e.getMessage(), e);
            throw e;
        }
        ci.cancel();
    }
}