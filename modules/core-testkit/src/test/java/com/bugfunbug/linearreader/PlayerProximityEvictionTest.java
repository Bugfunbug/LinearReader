package com.bugfunbug.linearreader;

import com.bugfunbug.linearreader.linear.LinearRegionFile;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerProximityEvictionTest {

    private static final long T0 = 1_000_000_000_000L;

    @TempDir
    Path tempDir;

    private Path dimensionRoot;
    private Path storageFolder;
    private Long2ObjectLinkedOpenHashMap<LinearRegionFile> cache;

    @BeforeEach
    void setUp() throws IOException {
        LinearTestSupport.resetState();
        PlayerProximity.clear();
        LinearTestSupport.setFixedClock(T0);
        dimensionRoot = tempDir.resolve("world").toAbsolutePath().normalize();
        storageFolder = dimensionRoot.resolve("region");
        Files.createDirectories(storageFolder);
        cache = new Long2ObjectLinkedOpenHashMap<>();
    }

    @AfterEach
    void tearDown() {
        PlayerProximity.clear();
        LinearTestSupport.resetState();
    }

    /** Inserts oldest first, so the first region added is the LRU tail. */
    private LinearRegionFile add(int rx, int rz) throws IOException {
        LinearRegionFile region = new LinearRegionFile(storageFolder.resolve("r." + rx + "." + rz + ".linear"), false);
        cache.putAndMoveToFirst(key(rx, rz), region);
        return region;
    }

    private static long key(int rx, int rz) {
        return ((long) rx & 0xFFFFFFFFL) | ((long) rz << 32);
    }

    private void makeIdle() {
        LinearTestSupport.setFixedClock(T0 + 120_000_000_000L);
    }

    private long choose() {
        return LinearRuntime.chooseEvictionKey(storageFolder, cache);
    }

    @Test
    void farRegionIsEvictedBeforeOlderNearRegion() throws IOException {
        PlayerProximity.setPlayerForTests(dimensionRoot, 0, 0);
        add(1, 0);    // near, LRU tail
        add(10, 10);  // far, more recently used
        makeIdle();

        assertEquals(key(10, 10), choose());
    }

    @Test
    void fallsBackToPlainLruWithNoPlayers() throws IOException {
        add(0, 0);
        add(10, 10);
        makeIdle();

        assertEquals(key(0, 0), choose());
    }

    @Test
    void evictsLruNearRegionWhenEverythingIsNear() throws IOException {
        PlayerProximity.setPlayerForTests(dimensionRoot, 0, 0);
        add(0, 0);
        add(1, 0);
        makeIdle();

        assertEquals(key(0, 0), choose());
    }

    @Test
    void recentlyAccessedFarRegionIsSkippedForIdleOne() throws IOException {
        PlayerProximity.setPlayerForTests(dimensionRoot, 0, 0);
        LinearRegionFile recentFar = add(10, 10); // LRU tail, but about to be touched
        add(20, 20);
        makeIdle();
        recentFar.hasChunk(new ChunkPos(10 * 32, 10 * 32)); // marks accessed "now"

        assertEquals(key(20, 20), choose());
    }

    @Test
    void whenEverythingIsRecentFallsBackToLru() throws IOException {
        PlayerProximity.setPlayerForTests(dimensionRoot, 0, 0);
        add(10, 10);
        add(20, 20);
        // clock never advances: both regions are "recent"

        assertEquals(key(10, 10), choose());
    }

    @Test
    void pinnedAndDirtyRegionsAreStillExcluded() throws IOException {
        PlayerProximity.setPlayerForTests(dimensionRoot, 0, 0);
        LinearRegionFile pinnedFar = add(10, 10);
        LinearRegionFile dirtyFar = add(20, 20);
        add(1, 0); // near, idle, evictable
        makeIdle();

        LinearRuntime.pinRegion(pinnedFar.getPath());
        try {
            try (DataOutputStream out = dirtyFar.write(new ChunkPos(20 * 32, 20 * 32))) {
                out.write(new byte[]{1});
            }
            assertTrue(dirtyFar.isDirty());

            assertEquals(key(1, 0), choose());
        } finally {
            LinearRuntime.unpinRegion(pinnedFar.getPath());
        }
    }

    @Test
    void playersInOtherDimensionsDoNotProtectRegions() throws IOException {
        PlayerProximity.setPlayerForTests(tempDir.resolve("other-dim").toAbsolutePath().normalize(), 0, 0);
        add(0, 0);
        add(10, 10);
        makeIdle();

        assertEquals(key(0, 0), choose());
    }

    @Test
    void trimFactorFavorsKeepingNearRegions() {
        PlayerProximity.setPlayerForTests(dimensionRoot, 0, 0);

        assertTrue(PlayerProximity.trimFactor(dimensionRoot, 1, 1) < 1.0D);
        assertEquals(1.0D, PlayerProximity.trimFactor(dimensionRoot, 5, 5));
    }
}