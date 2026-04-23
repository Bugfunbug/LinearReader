package com.bugfunbug.linearreader.config;

/**
 * Fabric-side config model stored at config/linearreader-server.toml.
 * Legacy config/linearreader-server.json is migrated forward automatically.
 * Field names and defaults intentionally match ForgeLinearConfig for easy comparison.
 *
 * Config changes require a server restart to take effect; hot-reload is not
 * implemented because it is never critical on a headless server.
 */
public class FabricLinearConfig {

    /** Zstd level used for normal .linear writes. 4-6 is the usual sweet spot. */
    public int compressionLevel = 4;

    /** Region files kept open in the cache. Higher is faster, lower uses less RAM. */
    public int regionCacheSize = 256;

    /** Keep a .linear.bak in a backups/ folder next to each region file. */
    public boolean backupEnabled = true;

    /** Minimum unique chunk changes since the last completed backup before a refresh is allowed. */
    public int backupMinChangedChunks = 32;

    /** Minimum changed payload volume since the last completed backup before a refresh is allowed. */
    public int backupMinChangedKb = 2048;

    /** Maximum age of a changed backup before it must be refreshed. */
    public int backupMaxAgeMinutes = 30;

    /** Region quiet time required before a backup refresh is allowed. */
    public int backupQuietSeconds = 60;

    /** Dirty regions submitted to the flush executor per server tick during world saves. */
    public int regionsPerSaveTick = 4;

    /** Confirmation window shared by prune-chunks and sync-backups. */
    public int confirmWindowSeconds = 60;

    /** Lower bound for the dynamic pressure-flush dirty-region target. */
    public int pressureFlushMinDirtyRegions = 4;

    /** Upper bound for the dynamic pressure-flush dirty-region target. */
    public int pressureFlushMaxDirtyRegions = 16;

    /** Warn if a region read or write takes longer than this many milliseconds. -1 disables it. */
    public int slowIoThresholdMs = 500;

    /** Warn before writing when free disk space drops below this many GB. -1 disables it. */
    public int diskSpaceWarnGb = 1;

    /** Enable automatic idle recompression after the server has been quiet for a while. */
    public boolean autoRecompressEnabled = true;

    /** Minutes with no chunk I/O before automatic recompression may start. */
    public int idleThresholdMinutes = 20;

    /** Minimum available JVM heap headroom required before recompression continues. */
    public int recompressMinFreeRamPercent = 15;

    public void validate() {
        compressionLevel     = clamp(compressionLevel,     1,  22);
        regionCacheSize      = clamp(regionCacheSize,      8,  1024);
        backupMinChangedChunks = clamp(backupMinChangedChunks, 1, 1024);
        backupMinChangedKb = clamp(backupMinChangedKb, 64, 262144);
        backupMaxAgeMinutes = clamp(backupMaxAgeMinutes, 1, 10080);
        backupQuietSeconds = clamp(backupQuietSeconds, 0, 3600);
        regionsPerSaveTick   = clamp(regionsPerSaveTick,   1,  64);
        confirmWindowSeconds = clamp(confirmWindowSeconds, 10, 3600);
        pressureFlushMinDirtyRegions = clamp(pressureFlushMinDirtyRegions, 1, 64);
        pressureFlushMaxDirtyRegions = clamp(pressureFlushMaxDirtyRegions, 1, 128);
        pressureFlushMaxDirtyRegions = Math.max(pressureFlushMinDirtyRegions, pressureFlushMaxDirtyRegions);
        slowIoThresholdMs    = Math.max(-1, slowIoThresholdMs);
        diskSpaceWarnGb      = Math.max(-1, diskSpaceWarnGb);
        idleThresholdMinutes = clamp(idleThresholdMinutes, 5, 1440);
        recompressMinFreeRamPercent = clamp(recompressMinFreeRamPercent, 5, 50);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
