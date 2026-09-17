package com.bugfunbug.linearreader.mc263;

import com.bugfunbug.linearreader.minecraftapi.ChunkNbtAdapter;
import com.bugfunbug.linearreader.minecraftapi.MinecraftFamily;
import com.bugfunbug.linearreader.minecraftapi.RegionStorageHooks;
import com.bugfunbug.linearreader.minecraftapi.WorldPathResolver;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.level.ServerPlayer;

public final class Minecraft263Family implements MinecraftFamily {

    public static final Minecraft263Family INSTANCE = new Minecraft263Family();

    private Minecraft263Family() {}

    @Override
    public WorldPathResolver worldPathResolver() {
        return Minecraft263WorldPathResolver.INSTANCE;
    }

    @Override
    public RegionStorageHooks regionStorageHooks() {
        return Minecraft263RegionStorageHooks.INSTANCE;
    }

    @Override
    public ChunkNbtAdapter chunkNbtAdapter() {
        return Minecraft263ChunkNbtAdapter.INSTANCE;
    }

    @Override
    public boolean hasOperatorCommandPermission(CommandSourceStack source) {
        return source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    @Override
    public boolean isSingleplayerOwner(MinecraftServer server, ServerPlayer player) {
        return server.isSingleplayerOwner(new NameAndId(player.getGameProfile()));
    }
}
