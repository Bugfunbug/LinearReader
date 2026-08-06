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

    /** Valid values for backupCompressionAlgorithm / idleRecompressAlgorithm. */
    public static final String ZSTD = "zstd";
    public static final String BROTLI = "brotli";

    // -------------------------------------------------------------------------
    // Live values — read by all mod code
    // -------------------------------------------------------------------------

    private static volatile int     compressionLevel     = 4;
    private static volatile int     regionCacheSize      = 256;
    private static volatile boolean backupEnabled        = true;
    private static volatile int     backupMinChangedChunks = 32;
    private static volatile int     backupMinChangedKb = 2048;
    private static volatile int     backupMaxAgeMinutes = 30;
    private static volatile int     backupQuietSeconds = 60;
    private static volatile int     regionsPerSaveTick   = 4;
    private static volatile int     confirmWindowSeconds = 60;
    private static volatile int     pressureFlushMinDirtyRegions = 4;
    private static volatile int     pressureFlushMaxDirtyRegions = 16;
    private static volatile int     slowIoThresholdMs    = 500;
    private static volatile int     diskSpaceWarnGb      = 1;
    private static volatile boolean autoRecompressEnabled = true;
    private static volatile int     idleThresholdMinutes  = 20;
    private static volatile int     recompressMinFreeRamPercent = 15;
    private static volatile boolean bulkConvertOnLoad = true;
    private static volatile int     pruneMaxInhabitedTimeTicks = 1200;
    private static volatile int     pruneMinRegionQuietHours = 12;
    private static volatile String  backupCompressionAlgorithm = ZSTD;
    private static volatile String  idleRecompressAlgorithm    = ZSTD;

    // -------------------------------------------------------------------------
    // Getters — called everywhere in mod logic
    // -------------------------------------------------------------------------

    public static int     getCompressionLevel()     { return compressionLevel; }
    public static int     getRegionCacheSize()      { return regionCacheSize; }
    public static boolean isBackupEnabled()         { return backupEnabled; }
    public static int     getBackupMinChangedChunks() { return backupMinChangedChunks; }
    public static long    getBackupMinChangedBytes()  { return backupMinChangedKb * 1024L; }
    public static int     getBackupMaxAgeMinutes()    { return backupMaxAgeMinutes; }
    public static int     getBackupQuietSeconds()     { return backupQuietSeconds; }
    public static int     getRegionsPerSaveTick()   { return regionsPerSaveTick; }
    public static int     getConfirmWindowSeconds() { return confirmWindowSeconds; }
    public static long    getConfirmWindowMs()      { return confirmWindowSeconds * 1000L; }
    public static int     getPressureFlushMinDirtyRegions() { return pressureFlushMinDirtyRegions; }
    public static int     getPressureFlushMaxDirtyRegions() { return pressureFlushMaxDirtyRegions; }
    public static int     getSlowIoThresholdMs()    { return slowIoThresholdMs; }
    public static int     getDiskSpaceWarnGb()      { return diskSpaceWarnGb; }
    public static boolean isAutoRecompressEnabled() { return autoRecompressEnabled; }
    public static int     getIdleThresholdMinutes() { return idleThresholdMinutes; }
    public static int     getRecompressMinFreeRamPercent() { return recompressMinFreeRamPercent; }
    public static boolean isBulkConvertOnLoad()     { return bulkConvertOnLoad; }
    public static int getPruneMaxInhabitedTimeTicks() { return pruneMaxInhabitedTimeTicks; }
    public static int getPruneMinRegionQuietHours()   { return pruneMinRegionQuietHours; }
    public static long getPruneMinRegionQuietMs()     { return pruneMinRegionQuietHours * 3_600_000L; }
    public static String  getBackupCompressionAlgorithm() { return backupCompressionAlgorithm; }
    public static String  getIdleRecompressAlgorithm()    { return idleRecompressAlgorithm; }


    // -------------------------------------------------------------------------
    // Called by loader-specific config to push current values in
    // -------------------------------------------------------------------------

    public static void update(
            int     compressionLevel,
            int     regionCacheSize,
            boolean backupEnabled,
            int     backupMinChangedChunks,
            int     backupMinChangedKb,
            int     backupMaxAgeMinutes,
            int     backupQuietSeconds,
            int     regionsPerSaveTick,
            int     confirmWindowSeconds,
            int     pressureFlushMinDirtyRegions,
            int     pressureFlushMaxDirtyRegions,
            int     slowIoThresholdMs,
            int     diskSpaceWarnGb,
            boolean autoRecompressEnabled,
            int     idleThresholdMinutes,
            int     recompressMinFreeRamPercent,
            boolean bulkConvertOnLoad,
            int     pruneMaxInhabitedTimeTicks,
            int     pruneMinRegionQuietHours,
            String  backupCompressionAlgorithm,
            String  idleRecompressAlgorithm) {

        LinearConfig.compressionLevel     = compressionLevel;
        LinearConfig.regionCacheSize      = regionCacheSize;
        LinearConfig.backupEnabled        = backupEnabled;
        LinearConfig.backupMinChangedChunks = backupMinChangedChunks;
        LinearConfig.backupMinChangedKb = backupMinChangedKb;
        LinearConfig.backupMaxAgeMinutes = backupMaxAgeMinutes;
        LinearConfig.backupQuietSeconds = backupQuietSeconds;
        LinearConfig.regionsPerSaveTick   = regionsPerSaveTick;
        LinearConfig.confirmWindowSeconds = confirmWindowSeconds;
        LinearConfig.pressureFlushMinDirtyRegions = pressureFlushMinDirtyRegions;
        LinearConfig.pressureFlushMaxDirtyRegions = Math.max(
                pressureFlushMinDirtyRegions, pressureFlushMaxDirtyRegions);
        LinearConfig.slowIoThresholdMs    = slowIoThresholdMs;
        LinearConfig.diskSpaceWarnGb      = diskSpaceWarnGb;
        LinearConfig.autoRecompressEnabled = autoRecompressEnabled;
        LinearConfig.idleThresholdMinutes  = idleThresholdMinutes;
        LinearConfig.recompressMinFreeRamPercent = recompressMinFreeRamPercent;
        LinearConfig.bulkConvertOnLoad = bulkConvertOnLoad;
        LinearConfig.pruneMaxInhabitedTimeTicks = Math.max(0, pruneMaxInhabitedTimeTicks);
        LinearConfig.pruneMinRegionQuietHours = Math.max(0, pruneMinRegionQuietHours);
        LinearConfig.backupCompressionAlgorithm = normalizeAlgorithm(backupCompressionAlgorithm);
        LinearConfig.idleRecompressAlgorithm = normalizeAlgorithm(idleRecompressAlgorithm);
    }

    /**
     * Falls back to Zstd for any unrecognized/missing value instead of
     * throwing - a typo'd or stale config value should degrade to the safe,
     * always-available default, not break startup.
     */
    private static String normalizeAlgorithm(String value) {
        if (BROTLI.equalsIgnoreCase(value)) {
            return BROTLI;
        }
        return ZSTD;
    }
}
