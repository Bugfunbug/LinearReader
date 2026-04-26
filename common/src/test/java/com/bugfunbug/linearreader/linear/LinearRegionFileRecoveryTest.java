package com.bugfunbug.linearreader.linear;

import com.bugfunbug.linearreader.LinearTestSupport;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LinearRegionFileRecoveryTest {

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
    void recoversFromBackupAndQuarantinesCorruptMain() throws IOException {
        Path worldRoot = LinearTestSupport.copyCorpusTree(
                "worlds/recovery-valid-backup",
                tempDir.resolve("recovery-valid-backup")
        );
        Path live = worldRoot.resolve("region/r.0.0.linear");
        Path backup = worldRoot.resolve("region/backups/r.0.0.linear.bak");

        LinearRegionFile region = new LinearRegionFile(live, false);
        try {
            CompoundTag chunk = readChunk(region, new ChunkPos(0, 0));
            assertTrue(region.isDirty());
            assertFalse(Files.exists(live));
            assertTrue(Files.exists(backup));
            assertNotNull(chunk);

            Path quarantinedMain = LinearTestSupport.onlyFileMatching(
                    worldRoot.resolve("region/corrupted"),
                    "r.0.0-",
                    ".corrupt.linear"
            );
            assertTrue(Files.exists(quarantinedMain));

            region.flush(false);
            assertTrue(Files.exists(live));
            assertTrue(LinearRegionFile.verifyOnDisk(live).ok);
        } finally {
            LinearRegionFile.ALL_OPEN.remove(region);
            region.releaseChunkData();
        }
    }

    @Test
    void quarantinesCorruptMainAndCorruptBackup() throws IOException {
        Path worldRoot = LinearTestSupport.copyCorpusTree(
                "worlds/recovery-corrupt-backup",
                tempDir.resolve("recovery-corrupt-backup")
        );
        Path live = worldRoot.resolve("region/r.0.0.linear");
        Path backup = worldRoot.resolve("region/backups/r.0.0.linear.bak");

        LinearRegionFile region = new LinearRegionFile(live, false);
        try {
            DataInputStream in = region.read(new ChunkPos(0, 0));
            assertNull(in);
            assertFalse(Files.exists(live));
            assertFalse(Files.exists(backup));

            try (var stream = Files.list(worldRoot.resolve("region/corrupted"))) {
                var names = stream.map(path -> path.getFileName().toString()).toList();
                assertTrue(names.stream().anyMatch(name -> name.endsWith(".corrupt.linear") && !name.contains("-backup.")));
                assertTrue(names.stream().anyMatch(name -> name.endsWith("-backup.corrupt.linear")));
            }
        } finally {
            LinearRegionFile.ALL_OPEN.remove(region);
            region.releaseChunkData();
        }
    }

    @Test
    void createsBackupInCanonicalBackupsDirectory() throws IOException {
        Path live = tempDir.resolve("region/r.2.2.linear");
        ChunkPos pos = new ChunkPos(64, 64);
        LinearRegionFile region = new LinearRegionFile(live, false);
        try {
            try (DataOutputStream out = region.write(pos)) {
                NbtIo.write(LinearTestData.simpleChunk("backup-create", pos.x, pos.z), out);
            }

            region.flush(true);
            LinearTestData.awaitBackupTasks();

            Path backup = live.getParent().resolve("backups/r.2.2.linear.bak");
            assertTrue(Files.exists(backup));
            assertTrue(LinearRegionFile.verifyOnDisk(backup).ok);
            assertFalse(Files.exists(live.resolveSibling("r.2.2.linear.bak")));
        } finally {
            LinearRegionFile.ALL_OPEN.remove(region);
            region.releaseChunkData();
        }
    }

    private static CompoundTag readChunk(LinearRegionFile region, ChunkPos pos) throws IOException {
        try (DataInputStream in = region.read(pos)) {
            assertNotNull(in);
            CompoundTag tag = NbtIo.read(in);
            assertNotNull(tag);
            return tag;
        }
    }
}
