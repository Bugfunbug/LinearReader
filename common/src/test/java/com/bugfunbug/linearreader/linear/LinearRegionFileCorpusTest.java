package com.bugfunbug.linearreader.linear;

import com.bugfunbug.linearreader.LinearTestSupport;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LinearRegionFileCorpusTest {

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
    void readsValidLinearFileFromCorpus() throws IOException {
        Path file = LinearTestSupport.copyCorpusFile(
                "files/valid/r.0.0.linear",
                tempDir.resolve("r.0.0.linear")
        );

        LinearRegionFile.VerifyResult verify = LinearRegionFile.verifyOnDisk(file);
        assertTrue(verify.ok);
        assertTrue(verify.hasCRC);

        LinearRegionFile region = new LinearRegionFile(file, false);
        try {
            CompoundTag entityChunk = readChunk(region, new ChunkPos(1, 0));
            ListTag entities = entityChunk.getList("entities", Tag.TAG_COMPOUND);
            assertEquals(1, entities.size());

            CompoundTag wrappedChunk = readChunk(region, new ChunkPos(2, 0));
            assertTrue(wrappedChunk.contains("Level", Tag.TAG_COMPOUND));
            assertEquals(0L, wrappedChunk.getCompound("Level").getLong("InhabitedTime"));
        } finally {
            LinearRegionFile.ALL_OPEN.remove(region);
            region.releaseChunkData();
        }
    }

    @Test
    void rejectsStandaloneCorruptFixtures() throws IOException {
        Path truncated = LinearTestSupport.copyCorpusFile(
                "files/invalid/r.0.0.truncated.linear",
                tempDir.resolve("r.0.0.truncated.linear")
        );
        Path badCrc = LinearTestSupport.copyCorpusFile(
                "files/invalid/r.0.0.bad-crc.linear",
                tempDir.resolve("r.0.0.bad-crc.linear")
        );
        Path badSignature = LinearTestSupport.copyCorpusFile(
                "files/invalid/r.0.0.bad-signature.linear",
                tempDir.resolve("r.0.0.bad-signature.linear")
        );

        LinearRegionFile.VerifyResult truncatedResult = LinearRegionFile.verifyOnDisk(truncated);
        LinearRegionFile.VerifyResult badCrcResult = LinearRegionFile.verifyOnDisk(badCrc);
        LinearRegionFile.VerifyResult badSignatureResult = LinearRegionFile.verifyOnDisk(badSignature);

        assertFalse(truncatedResult.ok);
        assertTrue(truncatedResult.reason.contains("Bad footer signature"));
        assertFalse(badCrcResult.ok);
        assertTrue(badCrcResult.reason.contains("CRC32 checksum mismatch"));
        assertFalse(badSignatureResult.ok);
        assertTrue(badSignatureResult.reason.contains("Bad header signature"));
    }

    @Test
    void verifiesBackupFixtures() throws IOException {
        Path validBackup = LinearTestSupport.copyCorpusFile(
                "files/backups/r.0.0.linear.bak",
                tempDir.resolve("r.0.0.linear.bak")
        );
        Path corruptBackup = LinearTestSupport.copyCorpusFile(
                "files/backups/r.0.0.bad-crc.linear.bak",
                tempDir.resolve("r.0.0.bad-crc.linear.bak")
        );

        LinearRegionFile.VerifyResult validResult = LinearRegionFile.verifyOnDisk(validBackup);
        LinearRegionFile.VerifyResult corruptResult = LinearRegionFile.verifyOnDisk(corruptBackup);

        assertTrue(validResult.ok);
        assertTrue(validResult.hasCRC);
        assertFalse(corruptResult.ok);
        assertTrue(corruptResult.reason.contains("CRC32 checksum mismatch"));
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
