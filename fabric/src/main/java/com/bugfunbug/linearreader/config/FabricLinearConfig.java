package com.bugfunbug.linearreader.config;

import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.annotation.Config;

/**
 * Cloth Config data class.  AutoConfig serialises this to
 * config/linearreader-server.json on first run and loads it on subsequent runs.
 * Identical field names / defaults as ForgeLinearConfig for easy comparison.
 *
 * Config changes require a server restart to take effect; hot-reload is not
 * implemented because it is never critical on a headless server.
 */
@Config(name = "linearreader-server")
public class FabricLinearConfig implements ConfigData {

    /** Zstd compression level 1–22. 2–4 for live use, 22 for idle recompressor. */
    public int compressionLevel = 2;

    /** Max region files held open. More = faster; more RAM. */
    public int regionCacheSize = 256;

    /** Create .linear.bak on first load, refresh every backupUpdateInterval saves. */
    public boolean backupEnabled = true;

    /** Saves between .bak refreshes. Only used when backupEnabled = true. */
    public int backupUpdateInterval = 10;

    /** Max dirty regions submitted to the flush executor per server tick. */
    public int regionsPerSaveTick = 4;

    /** Warn if a region read/write exceeds this many ms. -1 = disabled. */
    public int slowIoThresholdMs = 500;

    /** Warn before writing when free space (GB) is below this value. -1 = disabled. */
    public int diskSpaceWarnGb = 1;

    @Override
    public void validatePostLoad() throws ValidationException {
        compressionLevel     = clamp(compressionLevel,     1,  22);
        regionCacheSize      = clamp(regionCacheSize,      8,  1024);
        backupUpdateInterval = clamp(backupUpdateInterval, 1,  100);
        regionsPerSaveTick   = clamp(regionsPerSaveTick,   1,  64);
        slowIoThresholdMs    = Math.max(-1, slowIoThresholdMs);
        diskSpaceWarnGb      = Math.max(-1, diskSpaceWarnGb);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}