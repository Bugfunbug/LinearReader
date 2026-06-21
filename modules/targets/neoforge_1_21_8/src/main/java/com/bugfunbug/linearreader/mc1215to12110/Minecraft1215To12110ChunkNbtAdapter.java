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
        // .getCompound(key) now returns an Optional<CompoundTag>
        return tag.getCompound(key).isPresent();
    }

    @Override
    public CompoundTag getCompoundOrEmpty(CompoundTag tag, String key) {
        // .getCompoundOrEmpty(key) directly returns the CompoundTag or an empty one if missing
        return tag.getCompoundOrEmpty(key);
    }

    @Override
    public boolean hasNumeric(CompoundTag tag, String key) {
        // .getLong(key) returns an Optional<Long> which inherently checks presence and type match
        return tag.getLong(key).isPresent();
    }

    @Override
    public long getLongOrDefault(CompoundTag tag, String key, long fallback) {
        // .getLongOr(key, fallback) replaces the ternary operator/contains checks
        return tag.getLongOr(key, fallback);
    }

    @Override
    public ListTag getListOrEmpty(CompoundTag tag, String key, int expectedElementType) {
        // .getListOrEmpty(key) no longer requires an explicit type ID passed to it
        return tag.getListOrEmpty(key);
    }

    @Override
    public Set<String> keySet(CompoundTag tag) {
        // .getAllKeys() was renamed to .keySet() to match standard Java Map conventions
        return tag.keySet();
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