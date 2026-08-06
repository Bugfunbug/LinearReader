package com.bugfunbug.linearreader.config;

import com.bugfunbug.linearreader.LinearRuntime;
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
        boolean migratedFromJson = false;

        if (Files.exists(tomlPath)) {
            config = loadToml(tomlPath);
        } else if (Files.exists(legacyJsonPath)) {
            config = loadLegacyJson(legacyJsonPath);
            migratedFromJson = true;
        }

        config.validate();
        if (save(config) && migratedFromJson) {
            deleteLegacyJson(legacyJsonPath);
            LinearRuntime.LOGGER.info(
                    "[LinearReader] Migrated Fabric config from {} to {}.",
                    legacyJsonPath.getFileName(), tomlPath.getFileName());
        }
        return config;
    }

    public static boolean save(FabricLinearConfig config) {
        Path path = tomlPath();
        config.validate();

        List<String> lines = new ArrayList<>();
        lines.add("# LinearReader - server-side settings");
        lines.add("");

        addInt(lines, "compressionLevel", config.compressionLevel,
                "Zstd level used for normal .linear writes. Range: 1-22.",
                "4-6 = recommended for normal server use.",
                "22 = slowest, smallest output and is used by the idle recompressor.");
        addInt(lines, "regionCacheSize", config.regionCacheSize,
                "Maximum number of region files kept open in the cache.",
                "Higher = faster repeated access across many regions.",
                "Lower = less RAM use, but more cache misses and disk reads.");
        addBool(lines, "backupEnabled", config.backupEnabled,
                "Keep a .linear.bak in a backups/ folder next to each region file.");
        addInt(lines, "backupMinChangedChunks", config.backupMinChangedChunks,
                "Minimum unique chunk changes since the last completed backup",
                "before a refresh is allowed.");
        addInt(lines, "backupMinChangedKb", config.backupMinChangedKb,
                "Minimum changed payload volume (KB) since the last completed",
                "backup before a refresh is allowed.");
        addInt(lines, "backupMaxAgeMinutes", config.backupMaxAgeMinutes,
                "Maximum age of a changed backup before it must be refreshed.",
                "Only applies when backupEnabled = true.");
        addInt(lines, "backupQuietSeconds", config.backupQuietSeconds,
                "Region quiet time required before a backup refresh is allowed.",
                "Set to 0 to disable the quiet-time check.");
        addInt(lines, "regionsPerSaveTick", config.regionsPerSaveTick,
                "Maximum dirty regions submitted to the background flush executor",
                "per server tick during a world save.",
                "Higher drains backlog faster, but increases save-time work.");
        addInt(lines, "confirmWindowSeconds", config.confirmWindowSeconds,
                "Confirmation window shared by prune-chunks and sync-backups.",
                "Commands must be confirmed again after this many seconds.");
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
        addBool(lines, "bulkConvertOnLoad", config.bulkConvertOnLoad,
                "If true, converts every legacy .mca file in the world to .linear",
                "immediately on startup, before the world becomes joinable.",
                "If false, conversion still happens automatically for the whole world,",
                "but runs in the background after the server has finished starting,",
                "so players can join immediately.");
        addInt(lines, "pruneMaxInhabitedTimeTicks", config.pruneMaxInhabitedTimeTicks,
                "Maximum cumulative InhabitedTime (in ticks) a chunk can have and still",
                "be eligible for /linearreader prune-chunks. A single flythrough is",
                "roughly 20-100 ticks; a repeatedly-visited chunk (e.g. an elytra",
                "highway) accumulates well past this.",
                "Default = 1200 (about 1 minute of cumulative loaded time)");
        addInt(lines, "pruneMinRegionQuietHours", config.pruneMinRegionQuietHours,
                "Hours a region file must have no writes before its chunks become",
                "eligible for pruning. Protects areas someone is actively playing near.",
                "Set to 0 to disable this check.",
                "Default = 12");
        addString(lines, "idleRecompressAlgorithm", config.idleRecompressAlgorithm,
                "Which algorithm the idle/AFK recompressor uses for cold storage: zstd or brotli.",
                "Brotli produces smaller files but is much slower.",
                "Default = zstd");
        addString(lines, "backupCompressionAlgorithm", config.backupCompressionAlgorithm,
                "Which algorithm backups (.linear.bak files) use: zstd or brotli.",
                "Default = zstd");

        try {
            Files.createDirectories(path.getParent());
            Files.write(path, lines);
            return true;
        } catch (IOException e) {
            LinearRuntime.LOGGER.warn(
                    "[LinearReader] Failed to write Fabric config {}: {}",
                    path.getFileName(), e.getMessage());
            return false;
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
                    case "backupMinChangedChunks" -> config.backupMinChangedChunks = parseInt(key, value, config.backupMinChangedChunks);
                    case "backupMinChangedKb" -> config.backupMinChangedKb = parseInt(key, value, config.backupMinChangedKb);
                    case "backupMaxAgeMinutes" -> config.backupMaxAgeMinutes = parseInt(key, value, config.backupMaxAgeMinutes);
                    case "backupQuietSeconds" -> config.backupQuietSeconds = parseInt(key, value, config.backupQuietSeconds);
                    case "regionsPerSaveTick" -> config.regionsPerSaveTick = parseInt(key, value, config.regionsPerSaveTick);
                    case "confirmWindowSeconds" -> config.confirmWindowSeconds = parseInt(key, value, config.confirmWindowSeconds);
                    case "pressureFlushMinDirtyRegions" -> config.pressureFlushMinDirtyRegions = parseInt(key, value, config.pressureFlushMinDirtyRegions);
                    case "pressureFlushMaxDirtyRegions" -> config.pressureFlushMaxDirtyRegions = parseInt(key, value, config.pressureFlushMaxDirtyRegions);
                    case "slowIoThresholdMs" -> config.slowIoThresholdMs = parseInt(key, value, config.slowIoThresholdMs);
                    case "diskSpaceWarnGb" -> config.diskSpaceWarnGb = parseInt(key, value, config.diskSpaceWarnGb);
                    case "autoRecompressEnabled" -> config.autoRecompressEnabled = parseBoolean(key, value, config.autoRecompressEnabled);
                    case "idleThresholdMinutes" -> config.idleThresholdMinutes = parseInt(key, value, config.idleThresholdMinutes);
                    case "recompressMinFreeRamPercent" -> config.recompressMinFreeRamPercent = parseInt(key, value, config.recompressMinFreeRamPercent);
                    case "bulkConvertOnLoad" -> config.bulkConvertOnLoad = parseBoolean(key, value, config.bulkConvertOnLoad);
                    case "pruneMaxInhabitedTimeTicks" -> config.pruneMaxInhabitedTimeTicks = parseInt(key, value, config.pruneMaxInhabitedTimeTicks);
                    case "pruneMinRegionQuietHours" -> config.pruneMinRegionQuietHours = parseInt(key, value, config.pruneMinRegionQuietHours);
                    case "idleRecompressAlgorithm" -> config.idleRecompressAlgorithm = parseAlgorithm(key, value, config.idleRecompressAlgorithm);
                    case "backupCompressionAlgorithm" -> config.backupCompressionAlgorithm = parseAlgorithm(key, value, config.backupCompressionAlgorithm);
                    default -> {}
                }
            }
        } catch (IOException e) {
            LinearRuntime.LOGGER.warn(
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
            LinearRuntime.LOGGER.warn(
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
            LinearRuntime.LOGGER.warn(
                    "[LinearReader] Invalid integer for Fabric config key {}: {}. Keeping {}.",
                    key, value, fallback);
            return fallback;
        }
    }

    private static boolean parseBoolean(String key, String value, boolean fallback) {
        if ("true".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value)) return false;

        LinearRuntime.LOGGER.warn(
                "[LinearReader] Invalid boolean for Fabric config key {}: {}. Keeping {}.",
                key, value, fallback);
        return fallback;
    }

    private static String parseAlgorithm(String key, String value, String fallback) {
        if ("zstd".equalsIgnoreCase(value) || "brotli".equalsIgnoreCase(value)) {
            return value.toLowerCase(java.util.Locale.ROOT);
        }
        LinearRuntime.LOGGER.warn(
                "[LinearReader] Invalid value for Fabric config key {}: {} (must be zstd or brotli). Keeping {}.",
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

    private static void addString(List<String> lines, String key, String value, String... comments) {
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

    private static void deleteLegacyJson(Path legacyJsonPath) {
        try {
            Files.deleteIfExists(legacyJsonPath);
        } catch (IOException e) {
            LinearRuntime.LOGGER.warn(
                    "[LinearReader] Failed to delete legacy Fabric config {} after migration: {}",
                    legacyJsonPath.getFileName(), e.getMessage());
        }
    }
}
