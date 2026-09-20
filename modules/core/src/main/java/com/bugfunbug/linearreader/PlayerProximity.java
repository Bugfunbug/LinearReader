package com.bugfunbug.linearreader;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Once-per-second snapshot of which regions are "near" a player, per dimension.
 *
 * Built on the server thread, read lock-free from chunk I/O threads. The snapshot
 * is immutable after publication (volatile swap), so readers never see a set being
 * mutated. Keyed by dimension root (the parent of the region/poi/entities folder),
 * the same key IdleRecompressor's dimension filter already uses.
 */
public final class PlayerProximity {

    /** Chebyshev distance in regions. 1 covers a 32-chunk view distance from any position. */
    static final int NEAR_RADIUS_REGIONS = 1;

    /** Multiplier on resident-trim priority for regions near a player (lower = kept longer). */
    static final double NEAR_TRIM_FACTOR = 0.25D;

    private static volatile Map<Path, LongOpenHashSet> nearByDimension = Map.of();

    private PlayerProximity() {}

    /** Call from the server thread (LinearRuntime.onServerTick, ~1/s). */
    public static void refresh(MinecraftServer server) {
        if (server == null) {
            clear();
            return;
        }
        try {
            Map<Path, LongOpenHashSet> next = new HashMap<>();
            for (ServerLevel level : server.getAllLevels()) {
                List<ServerPlayer> players = level.players();
                if (players.isEmpty()) continue;
                Path regionFolder = LinearRuntime.regionFolderForDimension(level.dimension());
                Path dimensionRoot = regionFolder == null ? null : regionFolder.getParent();
                if (dimensionRoot == null) continue;
                Path key = dimensionRoot.toAbsolutePath().normalize();
                for (ServerPlayer player : players) {
                    addPlayer(next, key,
                            ((int) Math.floor(player.getX())) >> 9,
                            ((int) Math.floor(player.getZ())) >> 9);
                }
            }
            nearByDimension = next;
        } catch (RuntimeException e) {
            // Never let a proximity hiccup break the tick; fall back to plain LRU behavior.
            nearByDimension = Map.of();
            LinearRuntime.LOGGER.debug("[LinearReader] PlayerProximity refresh failed: {}", e.getMessage());
        }
    }

    public static void clear() {
        nearByDimension = Map.of();
    }

    public static boolean isNear(Path dimensionRoot, int regionX, int regionZ) {
        if (dimensionRoot == null) return false;
        LongOpenHashSet near = nearByDimension.get(dimensionRoot);
        return near != null && near.contains(pack(regionX, regionZ));
    }

    public static double trimFactor(Path dimensionRoot, int regionX, int regionZ) {
        return isNear(dimensionRoot, regionX, regionZ) ? NEAR_TRIM_FACTOR : 1.0D;
    }

    static void addPlayer(Map<Path, LongOpenHashSet> map, Path dimensionRoot, int regionX, int regionZ) {
        LongOpenHashSet set = map.computeIfAbsent(dimensionRoot, k -> new LongOpenHashSet());
        for (int dx = -NEAR_RADIUS_REGIONS; dx <= NEAR_RADIUS_REGIONS; dx++) {
            for (int dz = -NEAR_RADIUS_REGIONS; dz <= NEAR_RADIUS_REGIONS; dz++) {
                set.add(pack(regionX + dx, regionZ + dz));
            }
        }
    }

    /** Test hook: adds a player at the given region to the current snapshot. */
    static void setPlayerForTests(Path dimensionRoot, int regionX, int regionZ) {
        Map<Path, LongOpenHashSet> copy = new HashMap<>();
        for (Map.Entry<Path, LongOpenHashSet> e : nearByDimension.entrySet()) {
            copy.put(e.getKey(), new LongOpenHashSet(e.getValue()));
        }
        addPlayer(copy, dimensionRoot, regionX, regionZ);
        nearByDimension = copy;
    }

    private static long pack(int x, int z) {
        return ((long) x & 0xFFFFFFFFL) | ((long) z << 32);
    }
}