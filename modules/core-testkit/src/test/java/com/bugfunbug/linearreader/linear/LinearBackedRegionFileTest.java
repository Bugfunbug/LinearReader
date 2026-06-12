package com.bugfunbug.linearreader.linear;

import com.bugfunbug.linearreader.LinearTestSupport;
import com.bugfunbug.linearreader.minecraftapi.ChunkPosCompat;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.zip.DeflaterOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class LinearBackedRegionFileTest {

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
    void writesC2meBufferUsingCurrentBufferWindow() throws IOException {
        Path file = tempDir.resolve("r.0.0.linear");
        LinearRegionFile region = new LinearRegionFile(file, false);
        try {
            LinearBackedRegionFile backed = LinearBackedRegionFile.create(region);
            ChunkPos pos = new ChunkPos(2, 3);
            CompoundTag expected = testChunk(ChunkPosCompat.x(pos), ChunkPosCompat.z(pos));

            byte[] encoded = encodeVanillaChunkBuffer(expected);
            ByteBuffer windowed = ByteBuffer.wrap(new byte[encoded.length + 23]);
            windowed.position(11);
            windowed.put(encoded);
            windowed.flip();
            windowed.position(11);

            backed.writeFromBuffer(pos, windowed);

            try (DataInputStream in = region.read(pos)) {
                assertNotNull(in);
                CompoundTag actual = NbtIo.read(in);
                assertNotNull(actual);
                assertEquals(expected, actual);
            }
        } finally {
            LinearRegionFile.ALL_OPEN.remove(region);
            region.releaseChunkData();
        }
    }

    @Test
    void clearsChunkThroughLinearWrapper() throws IOException {
        Path file = tempDir.resolve("r.0.0.linear");
        LinearRegionFile region = new LinearRegionFile(file, false);
        try {
            LinearBackedRegionFile backed = LinearBackedRegionFile.create(region);
            ChunkPos pos = new ChunkPos(2, 3);

            try (DataOutputStream out = backed.getChunkDataOutputStream(pos)) {
                NbtIo.write(testChunk(ChunkPosCompat.x(pos), ChunkPosCompat.z(pos)), out);
            }

            backed.clearChunk(pos);

            try (DataInputStream in = region.read(pos)) {
                assertNull(in);
            }
        } finally {
            LinearRegionFile.ALL_OPEN.remove(region);
            region.releaseChunkData();
        }
    }

    private static byte[] encodeVanillaChunkBuffer(CompoundTag tag) throws IOException {
        ByteArrayOutputStream compressedOut = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new DeflaterOutputStream(compressedOut))) {
            NbtIo.write(tag, out);
        }

        byte[] compressed = compressedOut.toByteArray();
        ByteBuffer buffer = ByteBuffer.allocate(5 + compressed.length);
        buffer.putInt(compressed.length + 1);
        buffer.put((byte) 2);
        buffer.put(compressed);
        return buffer.array();
    }

    private static CompoundTag testChunk(int chunkX, int chunkZ) {
        CompoundTag root = new CompoundTag();
        root.putInt("DataVersion", 3465);
        root.putInt("xPos", chunkX);
        root.putInt("zPos", chunkZ);
        root.putString("Status", "full");

        ListTag sections = new ListTag();
        CompoundTag section = new CompoundTag();
        section.putInt("Y", 0);
        section.putString("PaletteSentinel", "minecraft:stone");
        sections.add(section);
        root.put("sections", sections);

        CompoundTag structures = new CompoundTag();
        structures.putString("TestMarker", "linearreader");
        root.put("structures", structures);
        return root;
    }
}
