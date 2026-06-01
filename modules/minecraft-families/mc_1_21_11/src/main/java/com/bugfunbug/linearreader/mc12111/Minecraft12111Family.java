package com.bugfunbug.linearreader.mc12111;

import com.bugfunbug.linearreader.minecraftapi.ChunkNbtAdapter;
import com.bugfunbug.linearreader.minecraftapi.MinecraftFamily;
import com.bugfunbug.linearreader.minecraftapi.RegionStorageHooks;
import com.bugfunbug.linearreader.minecraftapi.WorldPathResolver;

public final class Minecraft12111Family implements MinecraftFamily {

    public static final Minecraft12111Family INSTANCE = new Minecraft12111Family();

    private Minecraft12111Family() {}

    @Override
    public WorldPathResolver worldPathResolver() {
        return Minecraft12111WorldPathResolver.INSTANCE;
    }

    @Override
    public RegionStorageHooks regionStorageHooks() {
        return Minecraft12111RegionStorageHooks.INSTANCE;
    }

    @Override
    public ChunkNbtAdapter chunkNbtAdapter() {
        return Minecraft12111ChunkNbtAdapter.INSTANCE;
    }
}
