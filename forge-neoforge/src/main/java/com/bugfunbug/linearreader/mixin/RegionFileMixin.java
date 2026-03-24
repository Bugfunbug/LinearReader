package com.bugfunbug.linearreader.mixin;

import com.bugfunbug.linearreader.linear.LinearBackedRegionFile;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Intercepts the two RegionFile methods c2me calls directly on our
 * {@link LinearBackedRegionFile} instances.
 *
 * <h3>Why two intercepts?</h3>
 * c2me's {@code C2MEStorageThread} has two write paths:
 * <ol>
 *   <li><b>Async path</b> ({@code lambda$writeChunk$13}, line ~383) — calls
 *       {@code IRegionFile.invokeWriteChunk} which maps to the private
 *       {@code write(ChunkPos, ByteBuffer)} method. Handled by
 *       {@link #interceptLinearWrite}.</li>
 *   <li><b>Backlog path</b> ({@code writeChunk}, line ~347, triggered under
 *       memory pressure) — calls {@code RegionFile.clear(ChunkPos)} before
 *       writing, to clear the old sector entry. Handled by
 *       {@link #interceptLinearClear}.</li>
 * </ol>
 * Both intercepts are no-ops for normal {@code RegionFile} instances — they
 * only activate when {@code this} is a {@link LinearBackedRegionFile}.
 */
@Mixin(RegionFile.class)
public class RegionFileMixin {

    /**
     * Intercepts c2me's async write path.
     *
     * <p>c2me calls {@code write(ChunkPos, ByteBuffer)} directly via its
     * {@code IRegionFile.invokeWriteChunk} accessor mixin, bypassing
     * {@code getChunkDataOutputStream}.  We receive the raw MC chunk stream
     * (5-byte header + compressed NBT) and delegate to
     * {@link LinearBackedRegionFile#writeFromBuffer}.
     */
    @Inject(
            method = "write(Lnet/minecraft/world/level/ChunkPos;Ljava/nio/ByteBuffer;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void interceptLinearWrite(ChunkPos pos, ByteBuffer buffer, CallbackInfo ci) {
        if (!((Object) this instanceof LinearBackedRegionFile backed)) return;
        try {
            backed.writeFromBuffer(pos, buffer);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        ci.cancel();
    }

    /**
     * Intercepts c2me's backlog write path.
     *
     * <p>Under memory pressure, c2me flushes its write backlog via
     * {@code C2MEStorageThread.writeBacklog} → {@code writeChunk}, which calls
     * {@code RegionFile.clear(ChunkPos)} to remove the old sector entry before
     * writing the new one.  Our {@link LinearBackedRegionFile} was created with
     * {@code Unsafe.allocateInstance}, so {@code f_63625_} (the sector IntBuffer)
     * is null — any unhandled RegionFile method call that reads it will NPE.
     *
     * <p>We cancel the call and delegate to
     * {@link LinearBackedRegionFile#clearChunk} which properly removes the
     * chunk entry from the in-memory {@code LinearRegionFile}.
     */
    @Inject(
            method = "clear(Lnet/minecraft/world/level/ChunkPos;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void interceptLinearClear(ChunkPos pos, CallbackInfo ci) {
        if (!((Object) this instanceof LinearBackedRegionFile backed)) return;
        backed.clearChunk(pos);
        ci.cancel();
    }
}