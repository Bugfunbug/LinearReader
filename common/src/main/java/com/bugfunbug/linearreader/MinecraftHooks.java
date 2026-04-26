package com.bugfunbug.linearreader;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;

import java.nio.file.Path;

/**
 * Small version-sensitive hook surface owned by the loader/version module.
 *
 * Keep this narrow. Add methods only when a concrete cross-version drift needs
 * to be isolated from shared storage/runtime logic.
 */
public interface MinecraftHooks {

    Path resolveWorldRoot(MinecraftServer server);

    Path resolveRegionFolder(Path worldRoot, ResourceKey<Level> dimension);
}
