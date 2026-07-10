package com.bugfunbug.linearreader.linear;

import com.bugfunbug.linearreader.LinearRuntime;
import com.bugfunbug.linearreader.LinearTestSupport;
import com.bugfunbug.linearreader.minecraftapi.ChunkPosCompat;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoxyMcaStagerTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        LinearTestSupport.resetState();
        VoxyMcaStager.resetForTests();
    }

    @AfterEach
    void tearDown() {
        VoxyMcaStager.resetForTests();
        LinearTestSupport.resetState();
    }

    @Test
    void stagesManifestedMcaFilesAndCleansOnlyThoseFiles() throws Exception {
        Path worldRoot = tempDir.resolve("world");
        Path regionFolder = worldRoot.resolve("region");
        ChunkPos chunk = new ChunkPos(2, 3);
        CompoundTag expected = LinearTestData.simpleChunk(
                "voxy-stage",
                ChunkPosCompat.x(chunk),
                ChunkPosCompat.z(chunk));
        LinearTestData.writeRegion(
                regionFolder.resolve("r.0.0.linear"),
                Map.of(chunk, expected)
        );

        Path stagingDir = VoxyMcaStager.stagingDir(regionFolder);
        Files.createDirectories(stagingDir);

        // Place the pre-existing file directly in the staging directory
        // so that the stager recognizes it as a conflict and correctly skips it.
        Path existingMca = stagingDir.resolve("r.1.0.mca");
        byte[] existingBytes = new byte[]{1, 2, 3, 4};
        LinearTestData.writeRegion(
                regionFolder.resolve("r.1.0.linear"),
                Map.of(new ChunkPos(32, 0), LinearTestData.simpleChunk("skip", 32, 0))
        );
        Files.write(existingMca, existingBytes);

        assertEquals(VoxyMcaStager.StartResult.STARTED, VoxyMcaStager.start(worldRoot, regionFolder));
        waitForStaging();

        Path stagedMca = stagingDir.resolve("r.0.0.mca");
        Path manifest = VoxyMcaStager.manifestPath(worldRoot);

        assertTrue(Files.exists(stagedMca));
        assertTrue(Files.exists(manifest));
        assertArrayEquals(existingBytes, Files.readAllBytes(existingMca));

        assertTrue(Files.readString(manifest).contains("mca:region/linearreader-voxy-staging/r.0.0.mca"));
        assertFalse(Files.readString(manifest).contains("mca:region/linearreader-voxy-staging/r.1.0.mca"));

        try (RegionFile region = LinearRuntime.openVanillaRegionFile(stagedMca, stagingDir, false);
             DataInputStream input = region.getChunkDataInputStream(chunk)) {
            assertNotNull(input);
            assertEquals(expected, NbtIo.read(input));
        }

        // Release the file handle before attempting cleanup to prevent file locking failures
        LinearTestSupport.resetState();

        VoxyMcaStager.CleanupResult cleanup = VoxyMcaStager.cleanup(worldRoot);
        assertEquals(1, cleanup.deleted());
        assertEquals(0, cleanup.failed());
        assertFalse(Files.exists(stagedMca));
        assertFalse(Files.exists(manifest));

        // Check that the new sweep logic correctly wiped the pre-existing file and the folder
        assertFalse(Files.exists(existingMca));
        assertFalse(Files.exists(stagingDir));
    }

    @Test
    void prepareAdvancesThroughBatchesAfterCleanup() throws Exception {
        Path worldRoot = tempDir.resolve("batched-world");
        Path regionFolder = worldRoot.resolve("region");
        LinearTestData.writeRegion(
                regionFolder.resolve("r.0.0.linear"),
                Map.of(new ChunkPos(0, 0), LinearTestData.simpleChunk("first", 0, 0))
        );
        LinearTestData.writeRegion(
                regionFolder.resolve("r.0.1.linear"),
                Map.of(new ChunkPos(0, 32), LinearTestData.simpleChunk("second", 0, 32))
        );

        assertEquals(VoxyMcaStager.StartResult.STARTED, VoxyMcaStager.start(worldRoot, regionFolder, 1));
        waitForStaging();

        Path stagingDir = VoxyMcaStager.stagingDir(regionFolder);
        assertTrue(Files.exists(stagingDir.resolve("r.0.0.mca")));
        assertFalse(Files.exists(stagingDir.resolve("r.0.1.mca")));
        assertTrue(Files.readString(VoxyMcaStager.manifestPath(worldRoot)).contains("mca:region/linearreader-voxy-staging/r.0.0.mca"));

        VoxyMcaStager.CleanupResult firstCleanup = VoxyMcaStager.cleanup(worldRoot);
        assertEquals(1, firstCleanup.deleted());

        assertEquals(VoxyMcaStager.StartResult.STARTED, VoxyMcaStager.start(worldRoot, regionFolder, 1));
        waitForStaging();

        assertFalse(Files.exists(stagingDir.resolve("r.0.0.mca")));
        assertTrue(Files.exists(stagingDir.resolve("r.0.1.mca")));
        assertTrue(Files.readString(VoxyMcaStager.manifestPath(worldRoot)).contains("mca:region/linearreader-voxy-staging/r.0.1.mca"));
        assertTrue(VoxyMcaStager.lastBatchComplete());
    }

    @Test
    void abortRemovesBatchWithoutAdvancingState() throws Exception {
        Path worldRoot = tempDir.resolve("aborted-world");
        Path regionFolder = worldRoot.resolve("region");
        LinearTestData.writeRegion(
                regionFolder.resolve("r.0.0.linear"),
                Map.of(new ChunkPos(0, 0), LinearTestData.simpleChunk("first", 0, 0))
        );
        LinearTestData.writeRegion(
                regionFolder.resolve("r.0.1.linear"),
                Map.of(new ChunkPos(0, 32), LinearTestData.simpleChunk("second", 0, 32))
        );

        assertEquals(VoxyMcaStager.StartResult.STARTED, VoxyMcaStager.start(worldRoot, regionFolder, 1));
        waitForStaging();

        Path stagingDir = VoxyMcaStager.stagingDir(regionFolder);
        assertTrue(Files.exists(stagingDir.resolve("r.0.0.mca")));

        VoxyMcaStager.CleanupResult abort = VoxyMcaStager.abort(worldRoot);
        assertEquals(1, abort.deleted());
        assertFalse(Files.exists(stagingDir.resolve("r.0.0.mca")));

        assertEquals(VoxyMcaStager.StartResult.STARTED, VoxyMcaStager.start(worldRoot, regionFolder, 1));
        waitForStaging();

        assertTrue(Files.exists(stagingDir.resolve("r.0.0.mca")));
        assertFalse(Files.exists(stagingDir.resolve("r.0.1.mca")));
    }

    @Test
    void defaultPrepareKeepsBatchSmallForVoxyMemoryUse() throws Exception {
        Path worldRoot = tempDir.resolve("small-batch-world");
        Path regionFolder = worldRoot.resolve("region");
        for (int z = 0; z < VoxyMcaStager.defaultBatchFiles() + 1; z++) {
            LinearTestData.writeRegion(
                    regionFolder.resolve("r.0." + z + ".linear"),
                    Map.of(new ChunkPos(0, z * 32), LinearTestData.simpleChunk("batch-" + z, 0, z * 32))
            );
        }

        assertEquals(VoxyMcaStager.StartResult.STARTED, VoxyMcaStager.start(worldRoot, regionFolder));
        waitForStaging();

        assertEquals(VoxyMcaStager.defaultBatchFiles(), VoxyMcaStager.filesTotal());

        Path stagingDir = VoxyMcaStager.stagingDir(regionFolder);
        for (int z = 0; z < VoxyMcaStager.defaultBatchFiles(); z++) {
            assertTrue(Files.exists(stagingDir.resolve("r.0." + z + ".mca")));
        }
        assertFalse(Files.exists(stagingDir.resolve("r.0." + VoxyMcaStager.defaultBatchFiles() + ".mca")));
    }

    @Test
    void prepareHonorsLinearByteLimit() throws Exception {
        Path worldRoot = tempDir.resolve("byte-limited-world");
        Path regionFolder = worldRoot.resolve("region");
        Path first = regionFolder.resolve("r.0.0.linear");
        Path second = regionFolder.resolve("r.0.1.linear");
        LinearTestData.writeRegion(
                first,
                Map.of(new ChunkPos(0, 0), LinearTestData.simpleChunk("first", 0, 0))
        );
        LinearTestData.writeRegion(
                second,
                Map.of(new ChunkPos(0, 32), LinearTestData.simpleChunk("second", 0, 32))
        );

        assertEquals(VoxyMcaStager.StartResult.STARTED,
                VoxyMcaStager.start(worldRoot, regionFolder, 10, Files.size(first)));
        waitForStaging();

        assertEquals(1, VoxyMcaStager.filesTotal());

        Path stagingDir = VoxyMcaStager.stagingDir(regionFolder);
        assertTrue(Files.exists(stagingDir.resolve("r.0.0.mca")));
        assertFalse(Files.exists(stagingDir.resolve("r.0.1.mca")));
    }

    private static void waitForStaging() throws InterruptedException, IOException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (VoxyMcaStager.isRunning() && System.nanoTime() < deadline) {
            Thread.sleep(25L);
        }
        if (VoxyMcaStager.isRunning()) {
            throw new AssertionError("Voxy MCA staging did not finish in time");
        }
        if (!VoxyMcaStager.lastError().isEmpty()) {
            throw new IOException(VoxyMcaStager.lastError());
        }
    }
}