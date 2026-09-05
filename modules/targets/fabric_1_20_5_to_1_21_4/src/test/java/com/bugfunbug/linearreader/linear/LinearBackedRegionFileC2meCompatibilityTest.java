package com.bugfunbug.linearreader.linear;

import com.bugfunbug.linearreader.LinearTestSupport;
import net.minecraft.world.level.chunk.storage.RegionFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class LinearBackedRegionFileC2meCompatibilityTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        LinearTestSupport.resetState();
    }

    @AfterEach
    void tearDown() {
        LinearTestSupport.resetState();
    }

    @Test
    void initializesRegionFileVersionForC2meAccessor() throws Exception {
        LinearRegionFile region = new LinearRegionFile(tempDir.resolve("r.0.0.linear"), false);
        try {
            LinearBackedRegionFile backed = LinearBackedRegionFile.create(region);
            Field version = RegionFile.class.getDeclaredField("version");
            version.setAccessible(true);

            assertNotNull(version.get(backed));
        } finally {
            LinearRegionFile.ALL_OPEN.remove(region);
            region.releaseChunkData();
        }
    }
}
