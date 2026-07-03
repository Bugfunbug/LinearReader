package com.bugfunbug.linearreader.mc12111;

import com.bugfunbug.linearreader.minecraftapi.WorldPathResolver;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;

/**
 * 1.21.11 is the first documented family where ResourceLocation becomes
 * Identifier and ResourceKey.location() becomes ResourceKey.identifier().
 */
public final class Minecraft12111WorldPathResolver implements WorldPathResolver {

    public static final Minecraft12111WorldPathResolver INSTANCE =
            new Minecraft12111WorldPathResolver();

    private Minecraft12111WorldPathResolver() {}

    @Override
    public Path resolveWorldRoot(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
    }

    @Override
    public Path resolveRegionFolder(Path worldRoot, ResourceKey<Level> dimension) {
        if (dimension.equals(Level.OVERWORLD)) return worldRoot.resolve("region");
        if (dimension.equals(Level.NETHER)) return worldRoot.resolve("DIM-1").resolve("region");
        if (dimension.equals(Level.END)) return worldRoot.resolve("DIM1").resolve("region");

        Identifier id = dimension.identifier();
        return worldRoot.resolve("dimensions")
                .resolve(id.getNamespace())
                .resolve(id.getPath())
                .resolve("region");
    }
}
