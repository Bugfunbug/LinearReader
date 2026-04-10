package com.bugfunbug.linearreader.config;

import com.bugfunbug.linearreader.LinearReader;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Minimal Fabric-side JSON config loader so LinearReader does not need Cloth Config.
 */
public final class FabricConfigIO {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private static final String CONFIG_FILE_NAME = "linearreader-server.json";

    private FabricConfigIO() {}

    public static FabricLinearConfig load() {
        Path path = configPath();
        FabricLinearConfig config = new FabricLinearConfig();

        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path)) {
                FabricLinearConfig loaded = GSON.fromJson(reader, FabricLinearConfig.class);
                if (loaded != null) {
                    config = loaded;
                }
            } catch (IOException | JsonParseException e) {
                LinearReader.LOGGER.warn(
                        "[LinearReader] Failed to read Fabric config {}: {}. Using defaults.",
                        path.getFileName(), e.getMessage());
            }
        }

        config.validate();
        save(config);
        return config;
    }

    public static void save(FabricLinearConfig config) {
        Path path = configPath();
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path)) {
                GSON.toJson(config, writer);
            }
        } catch (IOException e) {
            LinearReader.LOGGER.warn(
                    "[LinearReader] Failed to write Fabric config {}: {}",
                    path.getFileName(), e.getMessage());
        }
    }

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE_NAME);
    }
}
