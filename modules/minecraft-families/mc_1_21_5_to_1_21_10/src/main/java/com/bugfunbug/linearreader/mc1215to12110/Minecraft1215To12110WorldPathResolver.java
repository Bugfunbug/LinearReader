package com.bugfunbug.linearreader.mc1215to12110;

import com.bugfunbug.linearreader.minecraftapi.WorldPathResolver;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;

/**
 * 1.21.5-1.21.10 remains on the pre-Identifier world-path surface:
 * ResourceLocation plus ResourceKey.location().
 */
public final class Minecraft1215To12110WorldPathResolver implements WorldPathResolver {

    public static final Minecraft1215To12110WorldPathResolver INSTANCE =
            new Minecraft1215To12110WorldPathResolver();

    private Minecraft1215To12110WorldPathResolver() {}

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
