package com.bugfunbug.linearreader.minecraftapi;

/**
 * Bundles the version-sensitive Minecraft seams that must move together for a
 * given family.
 */
public interface MinecraftFamily {

    WorldPathResolver worldPathResolver();

    RegionStorageHooks regionStorageHooks();

    ChunkNbtAdapter chunkNbtAdapter();
}
