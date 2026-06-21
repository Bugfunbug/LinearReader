package com.bugfunbug.linearreader.mc1215to12110;

import com.bugfunbug.linearreader.minecraftapi.ChunkNbtAdapter;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.Tag;

import java.util.Set;

public final class Minecraft1215To12110ChunkNbtAdapter implements ChunkNbtAdapter {

    public static final Minecraft1215To12110ChunkNbtAdapter INSTANCE =
            new Minecraft1215To12110ChunkNbtAdapter();

    private Minecraft1215To12110ChunkNbtAdapter() {}

    @Override
    public CompoundTag unwrapChunkTag(CompoundTag rawTag) {
        return hasCompound(rawTag, "Level")
                ? getCompoundOrEmpty(rawTag, "Level")
                : rawTag;
    }

    @Override
    public boolean hasCompound(CompoundTag tag, String key) {
        // Tag ID 10 explicitly represents a CompoundTag in Minecraft NBT
        return tag.contains(key, 10);
    }

    @Override
    public CompoundTag getCompoundOrEmpty(CompoundTag tag, String key) {
        // Vanilla's getCompound already returns an empty CompoundTag if the key doesn't exist
        return tag.getCompound(key);
    }

    @Override
    public boolean hasNumeric(CompoundTag tag, String key) {
        // Tag ID 4 explicitly checks for a Long type (or use 99 to check for any generic number)
        return tag.contains(key, 4);
    }

    @Override
    public long getLongOrDefault(CompoundTag tag, String key, long fallback) {
        return tag.contains(key) ? tag.getLong(key) : fallback;
    }

    @Override
    public ListTag getListOrEmpty(CompoundTag tag, String key, int expectedElementType) {
        // Vanilla utilizes getList(key, type) and returns an empty list if missing
        return tag.getList(key, expectedElementType);
    }

    @Override
    public Set<String> keySet(CompoundTag tag) {
        // Vanilla uses getAllKeys() instead of keySet()
        return tag.getAllKeys();
    }

    @Override
    public boolean hasLongArray(CompoundTag tag, String key) {
        Tag value = tag.get(key);
        return value instanceof LongArrayTag;
    }

    @Override
    public boolean hasNonEmptyLongArray(CompoundTag tag, String key) {
        Tag value = tag.get(key);
        return value instanceof LongArrayTag array && array.size() > 0;
    }
}