package com.bugfunbug.linearreader.config;

import com.bugfunbug.linearreader.LinearReader;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal Fabric-side config loader with a commented TOML file and one-time
 * migration from the older JSON config.
 */
public final class FabricConfigIO {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private static final String TOML_FILE_NAME = "linearreader-server.toml";
    private static final String LEGACY_JSON_FILE_NAME = "linearreader-server.json";

    private FabricConfigIO() {}

    public static FabricLinearConfig load() {
        Path tomlPath = tomlPath();
        Path legacyJsonPath = legacyJsonPath();
        FabricLinearConfig config = new FabricLinearConfig();

        if (Files.exists(tomlPath)) {
            config = loadToml(tomlPath);
        } else if (Files.exists(legacyJsonPath)) {
            config = loadLegacyJson(legacyJsonPath);
            LinearReader.LOGGER.info(
                    "[LinearReader] Migrated Fabric config from {} to {}.",
                    legacyJsonPath.getFileName(), tomlPath.getFileName());
        }

        config.validate();
        save(config);
        return config;
    }

    public static void save(FabricLinearConfig config) {
        Path path = tomlPath();
        config.validate();

        List<String> lines = new ArrayList<>();
        lines.add("# LinearReader - server-side settings");
        lines.add("");

        addInt(lines, "compressionLevel", config.compressionLevel,
                "Zstd level used for normal .linear writes. Range: 1-22.",
                "2-4 = recommended for normal server use.",
                "22 = slowest, smallest output and is used by the idle recompressor.");
        addInt(lines, "regionCacheSize", config.regionCacheSize,
                "Maximum number of region files kept open in the cache.",
                "Higher = faster repeated access across many regions.",
                "Lower = less RAM use, but more cache misses and disk reads.");
        addBool(lines, "backupEnabled", config.backupEnabled,
                "Keep a .linear.bak beside each region file.",
                "A backup is created on first load and refreshed every",
                "backupUpdateInterval successful saves.");
        addInt(lines, "backupUpdateInterval", config.backupUpdateInterval,
                "Successful saves of a region before its .bak is refreshed.",
                "Only applies when backupEnabled = true.");
        addInt(lines, "regionsPerSaveTick", config.regionsPerSaveTick,
                "Maximum dirty regions submitted to the background flush executor",
                "per server tick during a world save.",
                "Higher drains backlog faster, but increases save-time work.");
        addInt(lines, "pressureFlushMinDirtyRegions", config.pressureFlushMinDirtyRegions,
                "Lower bound for the dynamic pressure-flush dirty-region target.",
                "Smaller values make pressure flushing kick in more aggressively.");
        addInt(lines, "pressureFlushMaxDirtyRegions", config.pressureFlushMaxDirtyRegions,
                "Upper bound for the dynamic pressure-flush dirty-region target.",
                "Larger values allow more backlog before pressure flushing ramps up.");
        addInt(lines, "slowIoThresholdMs", config.slowIoThresholdMs,
                "Warn in the log if a region read or write takes longer than",
                "this many milliseconds. Set to -1 to disable.");
        addInt(lines, "diskSpaceWarnGb", config.diskSpaceWarnGb,
                "Warn before writing if free disk space falls below this value (GB).",
                "Set to -1 to disable.");
        addBool(lines, "autoRecompressEnabled", config.autoRecompressEnabled,
                "Enable automatic idle recompression after the server has had",
                "no chunk I/O for the configured threshold.",
                "Manual /linearreader afk-compress still works when this is false.");
        addInt(lines, "idleThresholdMinutes", config.idleThresholdMinutes,
                "Minutes with no chunk I/O before automatic recompression may start.",
                "Only applies when autoRecompressEnabled = true.");
        addInt(lines, "recompressMinFreeRamPercent", config.recompressMinFreeRamPercent,
                "Minimum available JVM heap headroom required during recompression.",
                "If the worker drops below this percent it pauses for a few minutes",
                "before trying again.");

        try {
            Files.createDirectories(path.getParent());
            Files.write(path, lines);
        } catch (IOException e) {
            LinearReader.LOGGER.warn(
                    "[LinearReader] Failed to write Fabric config {}: {}",
                    path.getFileName(), e.getMessage());
        }
    }

    private static FabricLinearConfig loadToml(Path path) {
        FabricLinearConfig config = new FabricLinearConfig();
        try {
            for (String rawLine : Files.readAllLines(path)) {
                String line = stripComment(rawLine).trim();
                if (line.isEmpty()) continue;

                int equals = line.indexOf('=');
                if (equals <= 0) continue;

                String key = line.substring(0, equals).trim();
                String value = line.substring(equals + 1).trim();

                switch (key) {
                    case "compressionLevel" -> config.compressionLevel = parseInt(key, value, config.compressionLevel);
                    case "regionCacheSize" -> config.regionCacheSize = parseInt(key, value, config.regionCacheSize);
                    case "backupEnabled" -> config.backupEnabled = parseBoolean(key, value, config.backupEnabled);
                    case "backupUpdateInterval" -> config.backupUpdateInterval = parseInt(key, value, config.backupUpdateInterval);
                    case "regionsPerSaveTick" -> config.regionsPerSaveTick = parseInt(key, value, config.regionsPerSaveTick);
                    case "pressureFlushMinDirtyRegions" -> config.pressureFlushMinDirtyRegions = parseInt(key, value, config.pressureFlushMinDirtyRegions);
                    case "pressureFlushMaxDirtyRegions" -> config.pressureFlushMaxDirtyRegions = parseInt(key, value, config.pressureFlushMaxDirtyRegions);
                    case "slowIoThresholdMs" -> config.slowIoThresholdMs = parseInt(key, value, config.slowIoThresholdMs);
                    case "diskSpaceWarnGb" -> config.diskSpaceWarnGb = parseInt(key, value, config.diskSpaceWarnGb);
                    case "autoRecompressEnabled" -> config.autoRecompressEnabled = parseBoolean(key, value, config.autoRecompressEnabled);
                    case "idleThresholdMinutes" -> config.idleThresholdMinutes = parseInt(key, value, config.idleThresholdMinutes);
                    case "recompressMinFreeRamPercent" -> config.recompressMinFreeRamPercent = parseInt(key, value, config.recompressMinFreeRamPercent);
                    default -> {}
                }
            }
        } catch (IOException e) {
            LinearReader.LOGGER.warn(
                    "[LinearReader] Failed to read Fabric config {}: {}. Using defaults.",
                    path.getFileName(), e.getMessage());
        }
        return config;
    }

    private static FabricLinearConfig loadLegacyJson(Path path) {
        FabricLinearConfig config = new FabricLinearConfig();
        try (Reader reader = Files.newBufferedReader(path)) {
            FabricLinearConfig loaded = GSON.fromJson(reader, FabricLinearConfig.class);
            if (loaded != null) {
                config = loaded;
            }
        } catch (IOException | JsonParseException e) {
            LinearReader.LOGGER.warn(
                    "[LinearReader] Failed to read legacy Fabric config {}: {}. Using defaults.",
                    path.getFileName(), e.getMessage());
        }
        return config;
    }

    private static String stripComment(String line) {
        int commentIdx = line.indexOf('#');
        return commentIdx >= 0 ? line.substring(0, commentIdx) : line;
    }

    private static int parseInt(String key, String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            LinearReader.LOGGER.warn(
                    "[LinearReader] Invalid integer for Fabric config key {}: {}. Keeping {}.",
                    key, value, fallback);
            return fallback;
        }
    }

    private static boolean parseBoolean(String key, String value, boolean fallback) {
        if ("true".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value)) return false;

        LinearReader.LOGGER.warn(
                "[LinearReader] Invalid boolean for Fabric config key {}: {}. Keeping {}.",
                key, value, fallback);
        return fallback;
    }

    private static void addInt(List<String> lines, String key, int value, String... comments) {
        addComments(lines, comments);
        lines.add(key + " = " + value);
        lines.add("");
    }

    private static void addBool(List<String> lines, String key, boolean value, String... comments) {
        addComments(lines, comments);
        lines.add(key + " = " + value);
        lines.add("");
    }

    private static void addComments(List<String> lines, String... comments) {
        for (String comment : comments) {
            lines.add("# " + comment);
        }
    }

    private static Path tomlPath() {
        return FabricLoader.getInstance().getConfigDir().resolve(TOML_FILE_NAME);
    }

    private static Path legacyJsonPath() {
        return FabricLoader.getInstance().getConfigDir().resolve(LEGACY_JSON_FILE_NAME);
    }
}
