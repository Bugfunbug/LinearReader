package com.bugfunbug.linearreader.mc1202to1214;

import com.bugfunbug.linearreader.minecraftapi.WorldPathResolver;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;

/**
 * Verified against the local official mapping snapshots for 1.20.2, 1.20.4,
 * and 1.21.4: this family still exposes the standard world-root and dimension
 * folder layout used by the 1.20.1 baseline.
 */
public final class Minecraft1202To1214WorldPathResolver implements WorldPathResolver {

    public static final Minecraft1202To1214WorldPathResolver INSTANCE =
            new Minecraft1202To1214WorldPathResolver();

    private Minecraft1202To1214WorldPathResolver() {}

    @Override
    public Path resolveWorldRoot(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
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
