package com.bugfunbug.linearreader.linear;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.ChunkPos;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class LinearTestData {

    private static final int DATA_VERSION = 3465;

    private LinearTestData() {}

    public static CompoundTag pruneCandidateChunk(int chunkX, int chunkZ) {
        CompoundTag tag = baseChunk(chunkX, chunkZ);
        tag.putLong("InhabitedTime", 0L);
        return tag;
    }

    public static CompoundTag wrappedPruneCandidateChunk(int chunkX, int chunkZ) {
        CompoundTag root = new CompoundTag();
        root.put("Level", pruneCandidateChunk(chunkX, chunkZ));
        return root;
    }

    public static CompoundTag entityChunk(int chunkX, int chunkZ) {
        CompoundTag tag = pruneCandidateChunk(chunkX, chunkZ);
        tag.putLong("InhabitedTime", 1L);
        ListTag entities = new ListTag();
        CompoundTag entity = new CompoundTag();
        entity.putString("id", "minecraft:pig");
        entities.add(entity);
        tag.put("entities", entities);
        return tag;
    }

    public static CompoundTag blockEntityChunk(int chunkX, int chunkZ) {
        CompoundTag tag = pruneCandidateChunk(chunkX, chunkZ);
        ListTag blockEntities = new ListTag();
        CompoundTag chest = new CompoundTag();
        chest.putString("id", "minecraft:chest");
        blockEntities.add(chest);
        tag.put("block_entities", blockEntities);
        CompoundTag upgradeData = new CompoundTag();
        upgradeData.putInt("Sides", 1);
        tag.put("UpgradeData", upgradeData);
        return tag;
    }

    public static CompoundTag structureChunk(int chunkX, int chunkZ) {
        CompoundTag tag = pruneCandidateChunk(chunkX, chunkZ);
        CompoundTag structures = new CompoundTag();
        CompoundTag references = new CompoundTag();
        references.put("Village", new LongArrayTag(new long[]{1L}));
        structures.put("References", references);
        tag.put("structures", structures);
        return tag;
    }

    public static CompoundTag simpleChunk(String kind, int chunkX, int chunkZ) {
        CompoundTag tag = baseChunk(chunkX, chunkZ);
        tag.putString("kind", kind);
        return tag;
    }

    public static void writeRegion(Path path, Map<ChunkPos, CompoundTag> chunks) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        LinearRegionFile region = new LinearRegionFile(path, false);
        try {
            for (Map.Entry<ChunkPos, CompoundTag> entry : chunks.entrySet()) {
                try (DataOutputStream out = region.write(entry.getKey())) {
                    NbtIo.write(entry.getValue(), out);
                }
            }
            region.flush(false);
        } finally {
            LinearRegionFile.ALL_OPEN.remove(region);
            region.releaseChunkData();
        }
    }

    public static void createBackup(Path liveFile) throws IOException {
        LinearRegionFile.writeBackupCopy(liveFile);
    }

    public static void awaitBackupTasks() throws IOException {
        LinearRegionFile.awaitBackupTasks();
    }

    public static void corruptHeaderSignature(Path path) throws IOException {
        byte[] raw = Files.readAllBytes(path);
        raw[0] ^= 0x01;
        Files.write(path, raw);
    }

    public static void corruptCrc(Path path) throws IOException {
        byte[] raw = Files.readAllBytes(path);
        raw[40] ^= 0x01;
        Files.write(path, raw);
    }

    public static void writeTruncatedCopy(Path source, Path target, int shrinkBytes) throws IOException {
        byte[] raw = Files.readAllBytes(source);
        int newLength = Math.max(1, raw.length - shrinkBytes);
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(target, java.util.Arrays.copyOf(raw, newLength));
    }

    public static void copyAndCorruptHeader(Path source, Path target) throws IOException {
        copy(source, target);
        corruptHeaderSignature(target);
    }

    public static void copyAndCorruptCrc(Path source, Path target) throws IOException {
        copy(source, target);
        corruptCrc(target);
    }

    public static void copy(Path source, Path target) throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private static CompoundTag baseChunk(int chunkX, int chunkZ) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("DataVersion", DATA_VERSION);
        tag.putInt("xPos", chunkX);
        tag.putInt("zPos", chunkZ);
        return tag;
    }
}
