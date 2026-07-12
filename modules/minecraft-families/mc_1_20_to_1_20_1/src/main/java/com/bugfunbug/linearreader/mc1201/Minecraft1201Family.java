package com.bugfunbug.linearreader.mc1201;

import com.bugfunbug.linearreader.minecraftapi.ChunkNbtAdapter;
import com.bugfunbug.linearreader.minecraftapi.MinecraftFamily;
import com.bugfunbug.linearreader.minecraftapi.RegionStorageHooks;
import com.bugfunbug.linearreader.minecraftapi.WorldPathResolver;
import net.minecraft.commands.CommandSourceStack;

public final class Minecraft1201Family implements MinecraftFamily {

    public static final Minecraft1201Family INSTANCE = new Minecraft1201Family();

    private Minecraft1201Family() {}

    @Override
    public WorldPathResolver worldPathResolver() {
        return Minecraft1201WorldPathResolver.INSTANCE;
    }

    @Override
    public RegionStorageHooks regionStorageHooks() {
        return Minecraft1201RegionStorageHooks.INSTANCE;
    }

    @Override
    public ChunkNbtAdapter chunkNbtAdapter() {
        return Minecraft1201ChunkNbtAdapter.INSTANCE;
    }

    @Override
    public boolean hasOperatorCommandPermission(CommandSourceStack source) {
        // Direct compiled call — Loom remaps this correctly, unlike the
        // reflection-based default in MinecraftFamily, whose string literal
        // "hasPermission" is never rewritten by Loom in production jars.
        return source.hasPermission(2);
    }
}