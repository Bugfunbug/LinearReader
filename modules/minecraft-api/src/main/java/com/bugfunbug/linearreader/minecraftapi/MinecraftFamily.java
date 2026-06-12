package com.bugfunbug.linearreader.minecraftapi;

import net.minecraft.commands.CommandSourceStack;

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
}
