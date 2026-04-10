package com.bugfunbug.linearreader.config;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.event.config.ModConfigEvent;

/**
 * Forge-specific config registration for LinearReader.
 *
 * Owns the ForgeConfigSpec. Pushes current values into LinearConfig
 * on load and on change so all mod logic stays loader-agnostic.
 */
public final class ForgeLinearConfig {

    private ForgeLinearConfig() {}

    public static final ForgeConfigSpec SPEC;

    private static final ForgeConfigSpec.IntValue     COMPRESSION_LEVEL;
    private static final ForgeConfigSpec.IntValue     REGION_CACHE_SIZE;
    private static final ForgeConfigSpec.BooleanValue BACKUP_ENABLED;
    private static final ForgeConfigSpec.IntValue     BACKUP_UPDATE_INTERVAL;
    private static final ForgeConfigSpec.IntValue     REGIONS_PER_SAVE_TICK;
    private static final ForgeConfigSpec.IntValue     PRESSURE_FLUSH_MIN_DIRTY_REGIONS;
    private static final ForgeConfigSpec.IntValue     PRESSURE_FLUSH_MAX_DIRTY_REGIONS;
    private static final ForgeConfigSpec.IntValue     SLOW_IO_THRESHOLD_MS;
    private static final ForgeConfigSpec.IntValue     DISK_SPACE_WARN_GB;
    private static final ForgeConfigSpec.BooleanValue AUTO_RECOMPRESS_ENABLED;
    private static final ForgeConfigSpec.IntValue     IDLE_THRESHOLD_MINUTES;
    private static final ForgeConfigSpec.IntValue     RECOMPRESS_MIN_FREE_RAM_PERCENT;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.comment("LinearReader - server-side settings").push("general");

        COMPRESSION_LEVEL = builder
                .comment(
                        "Zstd level used for normal .linear writes. Range: 1-22.",
                        "2-4 = recommended for normal server use.",
                        "22 = slowest, smallest output and is used by the idle recompressor.",
                        "Default = 2"
                )
                .defineInRange("compressionLevel", 2, 1, 22);

        REGION_CACHE_SIZE = builder
                .comment(
                        "Maximum number of region files kept open in the cache.",
                        "Higher = faster repeated access across many regions.",
                        "Lower = less RAM use, but more cache misses and disk reads.",
                        "Default = 256"
                )
                .defineInRange("regionCacheSize", 256, 8, 1024);

        BACKUP_ENABLED = builder
                .comment(
                        "Keep a .linear.bak beside each region file.",
                        "A backup is created on first load and refreshed every",
                        "backupUpdateInterval successful saves."
                )
                .define("backupEnabled", true);

        BACKUP_UPDATE_INTERVAL = builder
                .comment(
                        "Successful saves of a region before its .bak is refreshed.",
                        "Only applies when backupEnabled = true."
                )
                .defineInRange("backupUpdateInterval", 10, 1, 100);

        REGIONS_PER_SAVE_TICK = builder
                .comment(
                        "Maximum dirty regions submitted to the background flush executor",
                        "per server tick during a world save.",
                        "Higher drains backlog faster, but increases save-time work.",
                        "Default = 4"
                )
                .defineInRange("regionsPerSaveTick", 4, 1, 64);

        PRESSURE_FLUSH_MIN_DIRTY_REGIONS = builder
                .comment(
                        "Lower bound for the dynamic pressure-flush dirty-region target.",
                        "Smaller values make pressure flushing kick in more aggressively.",
                        "Default = 4"
                )
                .defineInRange("pressureFlushMinDirtyRegions", 4, 1, 64);

        PRESSURE_FLUSH_MAX_DIRTY_REGIONS = builder
                .comment(
                        "Upper bound for the dynamic pressure-flush dirty-region target.",
                        "Larger values allow more backlog before pressure flushing ramps up.",
                        "Default = 16"
                )
                .defineInRange("pressureFlushMaxDirtyRegions", 16, 1, 128);

        SLOW_IO_THRESHOLD_MS = builder
                .comment(
                        "Warn in the log if a region read or write takes longer than",
                        "this many milliseconds. Set to -1 to disable.",
                        "Default = 500"
                )
                .defineInRange("slowIoThresholdMs", 500, -1, 30000);

        DISK_SPACE_WARN_GB = builder
                .comment(
                        "Warn before writing if free disk space falls below this value (GB).",
                        "Set to -1 to disable.",
                        "Default = 1"
                )
                .defineInRange("diskSpaceWarnGb", 1, -1, 1000);

        AUTO_RECOMPRESS_ENABLED = builder
                .comment(
                        "Enable automatic idle recompression after the server has had",
                        "no chunk I/O for the configured threshold.",
                        "Manual /linearreader afk-compress still works when this is false.",
                        "Default = true"
                )
                .define("autoRecompressEnabled", true);

        IDLE_THRESHOLD_MINUTES = builder
                .comment(
                        "Minutes with no chunk I/O before automatic recompression may start.",
                        "Only applies when autoRecompressEnabled = true.",
                        "Default = 20"
                )
                .defineInRange("idleThresholdMinutes", 20, 5, 1440);

        RECOMPRESS_MIN_FREE_RAM_PERCENT = builder
                .comment(
                        "Minimum available JVM heap headroom required during recompression.",
                        "If the worker drops below this percent it pauses for a few minutes",
                        "before trying again.",
                        "Default = 15"
                )
                .defineInRange("recompressMinFreeRamPercent", 15, 5, 50);

        builder.pop();
        SPEC = builder.build();
    }

    /**
     * Call this from the ModConfigEvent.Loading and ModConfigEvent.Reloading
     * handlers in LinearReader so values stay current across /reload.
     */
    public static void pushToLinearConfig() {
        int pressureFlushMin = PRESSURE_FLUSH_MIN_DIRTY_REGIONS.get();
        int pressureFlushMax = Math.max(pressureFlushMin, PRESSURE_FLUSH_MAX_DIRTY_REGIONS.get());
        LinearConfig.update(
                COMPRESSION_LEVEL.get(),
                REGION_CACHE_SIZE.get(),
                BACKUP_ENABLED.get(),
                BACKUP_UPDATE_INTERVAL.get(),
                REGIONS_PER_SAVE_TICK.get(),
                pressureFlushMin,
                pressureFlushMax,
                SLOW_IO_THRESHOLD_MS.get(),
                DISK_SPACE_WARN_GB.get(),
                AUTO_RECOMPRESS_ENABLED.get(),
                IDLE_THRESHOLD_MINUTES.get(),
                RECOMPRESS_MIN_FREE_RAM_PERCENT.get()
        );
    }
}
