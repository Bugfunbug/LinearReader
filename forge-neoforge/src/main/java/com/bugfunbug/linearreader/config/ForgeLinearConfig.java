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

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.comment("LinearReader - server-side settings").push("general");

        COMPRESSION_LEVEL = builder
                .comment(
                        "Zstd compression level for .linear region files. Range: 1-22.",
                        " 2-4 = recommended (good balance for live compression)",
                        " 22  = slowest, smallest files (used by idle recompressor)",
                        "Default = 2"
                )
                .defineInRange("compressionLevel", 2, 1, 22);

        REGION_CACHE_SIZE = builder
                .comment(
                        "Maximum number of region files to keep open in the cache.",
                        "Larger = faster repeated access to many regions, more RAM.",
                        "Smaller = less RAM, more frequent disk reads.",
                        "Default = 256"
                )
                .defineInRange("regionCacheSize", 256, 8, 1024);

        BACKUP_ENABLED = builder
                .comment(
                        "Create a .linear.bak backup the first time each region is loaded.",
                        "Refreshes every backupUpdateInterval saves.",
                        "Backups are compressed at level 19 on a background thread."
                )
                .define("backupEnabled", true);

        BACKUP_UPDATE_INTERVAL = builder
                .comment(
                        "How many successful saves of a region before its .bak is refreshed.",
                        "Only applies when backupEnabled = true."
                )
                .defineInRange("backupUpdateInterval", 10, 1, 100);

        REGIONS_PER_SAVE_TICK = builder
                .comment(
                        "Maximum dirty regions submitted to the background flush executor",
                        "per server tick during a world save.",
                        "Default = 4"
                )
                .defineInRange("regionsPerSaveTick", 4, 1, 64);

        PRESSURE_FLUSH_MIN_DIRTY_REGIONS = builder
                .comment(
                        "Lower guardrail for the dynamic pressure-flush dirty-region target.",
                        "Smaller = more aggressive background draining under pressure.",
                        "Default = 4"
                )
                .defineInRange("pressureFlushMinDirtyRegions", 4, 1, 64);

        PRESSURE_FLUSH_MAX_DIRTY_REGIONS = builder
                .comment(
                        "Upper guardrail for the dynamic pressure-flush dirty-region target.",
                        "Larger = more backlog allowed before pressure flushing ramps up.",
                        "Default = 16"
                )
                .defineInRange("pressureFlushMaxDirtyRegions", 16, 1, 128);

        SLOW_IO_THRESHOLD_MS = builder
                .comment(
                        "Warn in the log if reading or writing a region takes longer than",
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
                DISK_SPACE_WARN_GB.get()
        );
    }
}
