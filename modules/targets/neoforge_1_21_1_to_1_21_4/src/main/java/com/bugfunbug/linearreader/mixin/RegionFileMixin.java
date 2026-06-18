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

@Mixin(RegionFile.class)
public class RegionFileMixin {

    @Inject(
            method = "write(Lnet/minecraft/world/level/ChunkPos;Ljava/nio/ByteBuffer;)V",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
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

    @Inject(
            method = "clear(Lnet/minecraft/world/level/ChunkPos;)V",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private void interceptLinearClear(ChunkPos pos, CallbackInfo ci) {
        if (!((Object) this instanceof LinearBackedRegionFile backed)) return;
        backed.clearChunk(pos);
        ci.cancel();
    }
}
