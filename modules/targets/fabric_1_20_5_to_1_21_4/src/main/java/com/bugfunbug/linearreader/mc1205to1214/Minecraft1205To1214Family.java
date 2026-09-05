package com.bugfunbug.linearreader.mc1205to1214;

import com.bugfunbug.linearreader.mc1202to1214.Minecraft1202To1214ChunkNbtAdapter;
import com.bugfunbug.linearreader.mc1202to1214.Minecraft1202To1214WorldPathResolver;
import com.bugfunbug.linearreader.minecraftapi.ChunkNbtAdapter;
import com.bugfunbug.linearreader.minecraftapi.MinecraftFamily;
import com.bugfunbug.linearreader.minecraftapi.RegionStorageHooks;
import com.bugfunbug.linearreader.minecraftapi.WorldPathResolver;
import net.minecraft.commands.CommandSourceStack;

public final class Minecraft1205To1214Family implements MinecraftFamily {

    public static final Minecraft1205To1214Family INSTANCE = new Minecraft1205To1214Family();

    private Minecraft1205To1214Family() {}

    @Override
    public WorldPathResolver worldPathResolver() {
        return Minecraft1202To1214WorldPathResolver.INSTANCE;
    }

    @Override
    public RegionStorageHooks regionStorageHooks() {
        return Minecraft1205To1214RegionStorageHooks.INSTANCE;
    }

    @Override
    public ChunkNbtAdapter chunkNbtAdapter() {
        return Minecraft1202To1214ChunkNbtAdapter.INSTANCE;
    }

    @Override
    public boolean hasOperatorCommandPermission(CommandSourceStack source) {
        return source.hasPermission(2);
    }
}
