package com.bugfunbug.linearreader.config;

/**
 * Loader-agnostic config values for LinearReader.
 *
 * All mod logic reads from this class. Loader-specific implementations
 * (ForgeLinearConfig, FabricLinearConfig) push values in via update()
 * on load and whenever the config changes.
 *
 * Volatile fields ensure cross-thread visibility without locking —
 * config reads happen on chunk I/O threads constantly.
 */
public final class LinearConfig {

    private LinearConfig() {}

    // -------------------------------------------------------------------------
    // Live values — read by all mod code
    // -------------------------------------------------------------------------

    private static volatile int     compressionLevel     = 2;
    private static volatile int     regionCacheSize      = 256;
    private static volatile boolean backupEnabled        = true;
    private static volatile int     backupUpdateInterval = 10;
    private static volatile int     regionsPerSaveTick   = 4;
    private static volatile int     pressureFlushMinDirtyRegions = 4;
    private static volatile int     pressureFlushMaxDirtyRegions = 16;
    private static volatile int     slowIoThresholdMs    = 500;
    private static volatile int     diskSpaceWarnGb      = 1;

    // -------------------------------------------------------------------------
    // Getters — called everywhere in mod logic
    // -------------------------------------------------------------------------

    public static int     getCompressionLevel()     { return compressionLevel; }
    public static int     getRegionCacheSize()      { return regionCacheSize; }
    public static boolean isBackupEnabled()         { return backupEnabled; }
    public static int     getBackupUpdateInterval() { return backupUpdateInterval; }
    public static int     getRegionsPerSaveTick()   { return regionsPerSaveTick; }
    public static int     getPressureFlushMinDirtyRegions() { return pressureFlushMinDirtyRegions; }
    public static int     getPressureFlushMaxDirtyRegions() { return pressureFlushMaxDirtyRegions; }
    public static int     getSlowIoThresholdMs()    { return slowIoThresholdMs; }
    public static int     getDiskSpaceWarnGb()      { return diskSpaceWarnGb; }

    // -------------------------------------------------------------------------
    // Called by loader-specific config to push current values in
    // -------------------------------------------------------------------------

    public static void update(
            int     compressionLevel,
            int     regionCacheSize,
            boolean backupEnabled,
            int     backupUpdateInterval,
            int     regionsPerSaveTick,
            int     pressureFlushMinDirtyRegions,
            int     pressureFlushMaxDirtyRegions,
            int     slowIoThresholdMs,
            int     diskSpaceWarnGb) {

        LinearConfig.compressionLevel     = compressionLevel;
        LinearConfig.regionCacheSize      = regionCacheSize;
        LinearConfig.backupEnabled        = backupEnabled;
        LinearConfig.backupUpdateInterval = backupUpdateInterval;
        LinearConfig.regionsPerSaveTick   = regionsPerSaveTick;
        LinearConfig.pressureFlushMinDirtyRegions = pressureFlushMinDirtyRegions;
        LinearConfig.pressureFlushMaxDirtyRegions = Math.max(
                pressureFlushMinDirtyRegions, pressureFlushMaxDirtyRegions);
        LinearConfig.slowIoThresholdMs    = slowIoThresholdMs;
        LinearConfig.diskSpaceWarnGb      = diskSpaceWarnGb;
    }
}
