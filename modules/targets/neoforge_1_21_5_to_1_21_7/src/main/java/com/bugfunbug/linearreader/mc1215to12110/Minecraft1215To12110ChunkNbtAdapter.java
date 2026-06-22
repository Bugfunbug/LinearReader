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
        return tag.getCompound(key).isPresent();
    }

    @Override
    public CompoundTag getCompoundOrEmpty(CompoundTag tag, String key) {
        return tag.getCompound(key).orElse(new CompoundTag());
    }

    @Override
    public boolean hasNumeric(CompoundTag tag, String key) {
        return tag.getLong(key).isPresent();
    }

    @Override
    public long getLongOrDefault(CompoundTag tag, String key, long fallback) {
        return tag.getLong(key).orElse(fallback);
    }

    @Override
    public ListTag getListOrEmpty(CompoundTag tag, String key, int expectedElementType) {
        return tag.getList(key).orElse(new ListTag());
    }

    @Override
    public Set<String> keySet(CompoundTag tag) {
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