package com.bugfunbug.linearreader.linear;

import com.bugfunbug.linearreader.LinearRuntime;
import com.bugfunbug.linearreader.LinearTestSupport;
import com.bugfunbug.linearreader.minecraftapi.ChunkPosCompat;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MCAConverterTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        LinearTestSupport.resetState();
    }

    @Test
    void folderOpenDoesNotBulkConvertLegacyRegions() throws Exception {
        Path regionFolder = tempDir.resolve("region");
        writeMcaRegion(regionFolder, 0, 0, new ChunkPos(0, 0), "first");
        writeMcaRegion(regionFolder, 0, 1, new ChunkPos(0, 32), "second");

        MCAConverter.convertFolder(regionFolder);

        assertTrue(Files.exists(regionFolder.resolve("r.0.0.mca")));
        assertTrue(Files.exists(regionFolder.resolve("r.0.1.mca")));
        assertFalse(Files.exists(regionFolder.resolve("r.0.0.linear")));
        assertFalse(Files.exists(regionFolder.resolve("r.0.1.linear")));
    }

    @Test
    void convertsOnlyRequestedLegacyRegion() throws Exception {
        Path regionFolder = tempDir.resolve("region");
        ChunkPos firstChunk = new ChunkPos(0, 0);
        ChunkPos secondChunk = new ChunkPos(0, 32);
        writeMcaRegion(regionFolder, 0, 0, firstChunk, "first");
        writeMcaRegion(regionFolder, 0, 1, secondChunk, "second");

        MCAConverter.convertRegionIfNeeded(regionFolder, 0, 0);

        Path firstLinear = regionFolder.resolve("r.0.0.linear");
        assertTrue(Files.exists(firstLinear));
        assertFalse(Files.exists(regionFolder.resolve("r.0.0.mca")));
        assertTrue(Files.exists(regionFolder.resolve("r.0.1.mca")));
        assertFalse(Files.exists(regionFolder.resolve("r.0.1.linear")));

        LinearRegionFile linear = new LinearRegionFile(firstLinear, false);
        try {
            try (DataInputStream input = linear.read(firstChunk)) {
                assertNotNull(input);
                assertTrue(NbtIo.read(input).toString().contains("first"));
            }
        } finally {
            LinearRegionFile.ALL_OPEN.remove(linear);
            linear.releaseChunkData();
        }
    }

    @Test
    void leavesMcaSidecarWhenLinearAlreadyExists() throws Exception {
        Path regionFolder = tempDir.resolve("region");
        ChunkPos chunk = new ChunkPos(0, 0);
        LinearTestData.writeRegion(
                regionFolder.resolve("r.0.0.linear"),
                java.util.Map.of(chunk, LinearTestData.simpleChunk("linear", 0, 0))
        );
        writeMcaRegion(regionFolder, 0, 0, chunk, "sidecar");

        MCAConverter.convertRegionIfNeeded(regionFolder, 0, 0);

        assertTrue(Files.exists(regionFolder.resolve("r.0.0.linear")));
        assertTrue(Files.exists(regionFolder.resolve("r.0.0.mca")));
    }

    private static void writeMcaRegion(Path regionFolder, int regionX, int regionZ, ChunkPos chunk, String kind)
            throws Exception {
        Files.createDirectories(regionFolder);
        Path mcaPath = regionFolder.resolve("r." + regionX + "." + regionZ + ".mca");
        CompoundTag tag = LinearTestData.simpleChunk(kind, ChunkPosCompat.x(chunk), ChunkPosCompat.z(chunk));
        try (RegionFile region = LinearRuntime.openVanillaRegionFile(mcaPath, regionFolder, false);
             DataOutputStream output = region.getChunkDataOutputStream(chunk)) {
            NbtIo.write(tag, output);
        }
    }
}
