package com.bugfunbug.linearreader.minecraftapi;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Bundles the version-sensitive Minecraft seams that must move together for a
 * given family.
 */
public interface MinecraftFamily {

    WorldPathResolver worldPathResolver();

    RegionStorageHooks regionStorageHooks();

    ChunkNbtAdapter chunkNbtAdapter();

    /**
     * Returns true if {@code source} has operator-level (level 2) command permission.
     *
     * <p>The default implementation uses reflection with a static class reference so
     * that Loom can remap the method name "hasPermission" to its intermediary form.
     * Families where that method no longer exists (mc_1_21_11 and later) must override
     * this method and call the replacement API directly.</p>
     */
    default boolean hasOperatorCommandPermission(CommandSourceStack source) {
        // CommandSourceStack.class is a compile-time constant and "hasPermission" is a
        // string literal at the call site, so Loom's string remapper rewrites it to the
        // correct intermediary method name in the production jar.
        try {
            return (Boolean) CommandSourceStack.class
                    .getMethod("hasPermission", int.class)
                    .invoke(source, 2);
        } catch (NoSuchMethodException e) {
            // MC version does not have hasPermission(int); overriding family must handle.
            return false;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    /**
     * Returns true if {@code player} is this (singleplayer) server's owner.
     *
     * <p>Uses reflection for the exact same reason {@link #hasOperatorCommandPermission}
     * does: {@code MinecraftServer.isSingleplayerOwner(...)}'s parameter type changed
     * starting in Minecraft 26.1 (GameProfile -&gt; NameAndId), so calling it directly
     * here would only compile against one side of that split. Families on the newer
     * API (Minecraft261To262Family) override this method directly instead of relying
     * on this default.</p>
     */
    default boolean isSingleplayerOwner(MinecraftServer server, ServerPlayer player) {
        try {
            return (Boolean) MinecraftServer.class
                    .getMethod("isSingleplayerOwner", com.mojang.authlib.GameProfile.class)
                    .invoke(server, player.getGameProfile());
        } catch (NoSuchMethodException e) {
            // MC version does not have this overload; overriding family must handle.
            return false;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }
}
