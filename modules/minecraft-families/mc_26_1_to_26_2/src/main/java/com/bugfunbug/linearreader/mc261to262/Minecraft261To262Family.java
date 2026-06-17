package com.bugfunbug.linearreader.mc261to262;

import com.bugfunbug.linearreader.minecraftapi.ChunkNbtAdapter;
import com.bugfunbug.linearreader.minecraftapi.MinecraftFamily;
import com.bugfunbug.linearreader.minecraftapi.RegionStorageHooks;
import com.bugfunbug.linearreader.minecraftapi.WorldPathResolver;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.permissions.Permissions;

public final class Minecraft261To262Family implements MinecraftFamily {

    public static final Minecraft261To262Family INSTANCE = new Minecraft261To262Family();

    private Minecraft261To262Family() {}

    @Override
    public WorldPathResolver worldPathResolver() {
        return Minecraft261To262WorldPathResolver.INSTANCE;
    }

    @Override
    public RegionStorageHooks regionStorageHooks() {
        return Minecraft261To262RegionStorageHooks.INSTANCE;
    }

    @Override
    public ChunkNbtAdapter chunkNbtAdapter() {
        return Minecraft261To262ChunkNbtAdapter.INSTANCE;
    }

    @Override
    public boolean hasOperatorCommandPermission(CommandSourceStack source) {
        return source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }
}
