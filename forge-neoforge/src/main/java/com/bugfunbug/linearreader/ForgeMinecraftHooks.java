package com.bugfunbug.linearreader;

import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;

final class ForgeMinecraftHooks implements MinecraftHooks {

    static final ForgeMinecraftHooks INSTANCE = new ForgeMinecraftHooks();

    private ForgeMinecraftHooks() {}

    @Override
    public Path resolveWorldRoot(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT);
    }

    @Override
    public Path resolveRegionFolder(Path worldRoot, ResourceKey<Level> dimension) {
        if (dimension.equals(Level.OVERWORLD)) return worldRoot.resolve("region");
        if (dimension.equals(Level.NETHER)) return worldRoot.resolve("DIM-1").resolve("region");
        if (dimension.equals(Level.END)) return worldRoot.resolve("DIM1").resolve("region");

        ResourceLocation id = dimension.location();
        return worldRoot.resolve("dimensions")
                .resolve(id.getNamespace())
                .resolve(id.getPath())
                .resolve("region");
    }
}
