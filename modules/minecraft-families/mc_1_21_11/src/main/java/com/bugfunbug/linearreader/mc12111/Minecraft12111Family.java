package com.bugfunbug.linearreader.mc12111;

import com.bugfunbug.linearreader.minecraftapi.ChunkNbtAdapter;
import com.bugfunbug.linearreader.minecraftapi.MinecraftFamily;
import com.bugfunbug.linearreader.minecraftapi.RegionStorageHooks;
import com.bugfunbug.linearreader.minecraftapi.WorldPathResolver;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

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

    @Override
    public boolean hasOperatorCommandPermission(CommandSourceStack source) {
        // Direct field access (Permissions.COMMANDS_GAMEMASTER) and method call
        // (source.permissions().hasPermission(...)) are both remapped by Loom to
        // their intermediary names in the production jar.  No reflection strings.
        return source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    @Override
    public boolean isSingleplayerOwner(MinecraftServer server, ServerPlayer player) {
        return server.isSingleplayerOwner(new NameAndId(player.getGameProfile()));
    }
}
