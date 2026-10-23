package com.bugfunbug.linearreader.mc1215to12110;

import com.bugfunbug.linearreader.minecraftapi.ChunkNbtAdapter;
import com.bugfunbug.linearreader.minecraftapi.MinecraftFamily;
import com.bugfunbug.linearreader.minecraftapi.RegionStorageHooks;
import com.bugfunbug.linearreader.minecraftapi.WorldPathResolver;
import com.bugfunbug.linearreader.LinearRuntime;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public final class Minecraft1215To12110Family implements MinecraftFamily {

    public static final Minecraft1215To12110Family INSTANCE = new Minecraft1215To12110Family();

    private Minecraft1215To12110Family() {}

    @Override
    public WorldPathResolver worldPathResolver() {
        return Minecraft1215To12110WorldPathResolver.INSTANCE;
    }

    @Override
    public RegionStorageHooks regionStorageHooks() {
        return Minecraft1215To12110RegionStorageHooks.INSTANCE;
    }

    @Override
    public ChunkNbtAdapter chunkNbtAdapter() {
        return Minecraft1215To12110ChunkNbtAdapter.INSTANCE;
    }

    @Override
    public boolean hasOperatorCommandPermission(CommandSourceStack source) {
        return source.hasPermission(2);
    }

    @Override
    public boolean isSingleplayerOwner(MinecraftServer server, ServerPlayer player) {
        try {
            if (SINGLEPLAYER_OWNER_METHOD == null) {
                return false;
            }
            Object argument = ARGUMENT_CONSTRUCTOR != null
                    ? ARGUMENT_CONSTRUCTOR.newInstance(player.getGameProfile())
                    : player.getGameProfile();
            return (Boolean) SINGLEPLAYER_OWNER_METHOD.invoke(server, argument);
        } catch (ReflectiveOperationException e) {
            LinearRuntime.LOGGER.warn("[LinearReader] isSingleplayerOwner check failed: {}", e.getMessage());
            return false;
        }
    }

    // Resolved once at class-init. This module compiles against one specific
    // Minecraft version, but ships across 1.21.5-1.21.10, and Mojang changed
    // isSingleplayerOwner's parameter type (GameProfile -> NameAndId) somewhere
    // in that range. We can't search by METHOD NAME via reflection because that
    // name is obfuscated to an intermediary symbol (e.g. method_19466) at
    // runtime and never matches the dev-mapped name "isSingleplayerOwner".
    // Class names, however, are stable and unobfuscated - so we scan every
    // method on MinecraftServer for one that takes exactly one argument of
    // type GameProfile or NameAndId, and use whichever is actually present.
    private static final Method SINGLEPLAYER_OWNER_METHOD;
    private static final java.lang.reflect.Constructor<?> ARGUMENT_CONSTRUCTOR; // null if the method takes GameProfile directly

    static {
        Method foundMethod = null;
        java.lang.reflect.Constructor<?> foundCtor = null;

        for (Method candidate : MinecraftServer.class.getMethods()) {
            if (candidate.getReturnType() != boolean.class || candidate.getParameterCount() != 1) {
                continue;
            }
            Class<?> paramType = candidate.getParameterTypes()[0];

            if (paramType == com.mojang.authlib.GameProfile.class) {
                // Unambiguous: the method takes GameProfile directly.
                foundMethod = candidate;
                foundCtor = null;
                break;
            }

            try {
                // Structural match: any parameter type constructible from exactly one
                // GameProfile is almost certainly the NameAndId-style wrapper
                // isSingleplayerOwner now takes - no other MinecraftServer boolean/1-arg
                // method (Level, boolean, Runnable, Throwable, etc.) has such a
                // constructor. We can't match by class NAME because wrapper types like
                // NameAndId are obfuscated to e.g. "class_11560" at runtime.
                java.lang.reflect.Constructor<?> ctor = paramType.getConstructor(com.mojang.authlib.GameProfile.class);
                foundMethod = candidate;
                foundCtor = ctor;
            } catch (NoSuchMethodException ignored) {
                // Not our method - keep scanning.
            }
        }

        SINGLEPLAYER_OWNER_METHOD = foundMethod;
        ARGUMENT_CONSTRUCTOR = foundCtor;
    }
}