package com.bugfunbug.linearreader.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * NeoForge-backed config bridge for the 1.21.1-1.21.10 loader family.
 */
public final class NeoForgeLinearConfig {

    private NeoForgeLinearConfig() {}

    public static final ModConfigSpec SPEC;

    private static final ModConfigSpec.IntValue     COMPRESSION_LEVEL;
    private static final ModConfigSpec.IntValue     REGION_CACHE_SIZE;
    private static final ModConfigSpec.BooleanValue BACKUP_ENABLED;
    private static final ModConfigSpec.IntValue     BACKUP_MIN_CHANGED_CHUNKS;
    private static final ModConfigSpec.IntValue     BACKUP_MIN_CHANGED_KB;
    private static final ModConfigSpec.IntValue     BACKUP_MAX_AGE_MINUTES;
    private static final ModConfigSpec.IntValue     BACKUP_QUIET_SECONDS;
    private static final ModConfigSpec.IntValue     REGIONS_PER_SAVE_TICK;
    private static final ModConfigSpec.IntValue     CONFIRM_WINDOW_SECONDS;
    private static final ModConfigSpec.IntValue     PRESSURE_FLUSH_MIN_DIRTY_REGIONS;
    private static final ModConfigSpec.IntValue     PRESSURE_FLUSH_MAX_DIRTY_REGIONS;
    private static final ModConfigSpec.IntValue     SLOW_IO_THRESHOLD_MS;
    private static final ModConfigSpec.IntValue     DISK_SPACE_WARN_GB;
    private static final ModConfigSpec.BooleanValue AUTO_RECOMPRESS_ENABLED;
    private static final ModConfigSpec.IntValue     IDLE_THRESHOLD_MINUTES;
    private static final ModConfigSpec.IntValue     RECOMPRESS_MIN_FREE_RAM_PERCENT;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.comment("LinearReader - server-side settings");

        COMPRESSION_LEVEL = builder
                .comment(
                        "Zstd level used for normal .linear writes. Range: 1-22.",
                        "4-6 = recommended for normal server use.",
                        "22 = slowest, smallest output and is used by the idle recompressor.",
                        "Default = 4"
                )
                .defineInRange("compressionLevel", 4, 1, 22);

        REGION_CACHE_SIZE = builder
                .comment(
                        "Maximum number of region files kept open in the cache.",
                        "Higher = faster repeated access across many regions.",
                        "Lower = less RAM use, but more cache misses and disk reads.",
                        "Default = 256"
                )
                .defineInRange("regionCacheSize", 256, 8, 1024);

        BACKUP_ENABLED = builder
                .comment("Keep a .linear.bak in a backups/ folder next to each region file.")
                .define("backupEnabled", true);

        BACKUP_MIN_CHANGED_CHUNKS = builder
                .comment(
                        "Minimum unique chunk changes since the last completed backup",
                        "before a refresh is allowed.",
                        "Default = 32"
                )
                .defineInRange("backupMinChangedChunks", 32, 1, 1024);

        BACKUP_MIN_CHANGED_KB = builder
                .comment(
                        "Minimum changed payload volume (KB) since the last completed",
                        "backup before a refresh is allowed.",
                        "Default = 2048"
                )
                .defineInRange("backupMinChangedKb", 2048, 64, 262144);

        BACKUP_MAX_AGE_MINUTES = builder
                .comment(
                        "Maximum age of a changed backup before it must be refreshed.",
                        "Only applies when backupEnabled = true.",
                        "Default = 30"
                )
                .defineInRange("backupMaxAgeMinutes", 30, 1, 10080);

        BACKUP_QUIET_SECONDS = builder
                .comment(
                        "Region quiet time required before a backup refresh is allowed.",
                        "Set to 0 to disable the quiet-time check.",
                        "Default = 60"
                )
                .defineInRange("backupQuietSeconds", 60, 0, 3600);

        REGIONS_PER_SAVE_TICK = builder
                .comment(
                        "Maximum dirty regions submitted to the background flush executor",
                        "per server tick during a world save.",
                        "Higher drains backlog faster, but increases save-time work.",
                        "Default = 4"
                )
                .defineInRange("regionsPerSaveTick", 4, 1, 64);

        CONFIRM_WINDOW_SECONDS = builder
                .comment(
                        "Confirmation window shared by prune-chunks and sync-backups.",
                        "Commands must be confirmed again after this many seconds.",
                        "Default = 60"
                )
                .defineInRange("confirmWindowSeconds", 60, 10, 3600);

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

        SPEC = builder.build();
    }

    public static void pushToLinearConfig() {
        int pressureFlushMin = PRESSURE_FLUSH_MIN_DIRTY_REGIONS.get();
        int pressureFlushMax = Math.max(pressureFlushMin, PRESSURE_FLUSH_MAX_DIRTY_REGIONS.get());
        LinearConfig.update(
                COMPRESSION_LEVEL.get(),
                REGION_CACHE_SIZE.get(),
                BACKUP_ENABLED.get(),
                BACKUP_MIN_CHANGED_CHUNKS.get(),
                BACKUP_MIN_CHANGED_KB.get(),
                BACKUP_MAX_AGE_MINUTES.get(),
                BACKUP_QUIET_SECONDS.get(),
                REGIONS_PER_SAVE_TICK.get(),
                CONFIRM_WINDOW_SECONDS.get(),
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
