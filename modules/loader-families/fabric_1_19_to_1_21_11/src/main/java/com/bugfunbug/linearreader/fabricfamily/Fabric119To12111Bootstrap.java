package com.bugfunbug.linearreader.fabricfamily;

import com.bugfunbug.linearreader.LinearRuntime;
import com.bugfunbug.linearreader.command.LinearCommand;
import com.bugfunbug.linearreader.config.FabricConfigIO;
import com.bugfunbug.linearreader.config.FabricLinearConfig;
import com.bugfunbug.linearreader.config.LinearConfig;
import com.bugfunbug.linearreader.loaderfamilies.LoaderBootstrap;
import com.bugfunbug.linearreader.minecraftapi.MinecraftFamily;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

public final class Fabric119To12111Bootstrap implements LoaderBootstrap {

    private final MinecraftFamily minecraftFamily;
    private final LinearRuntime runtime;

    public Fabric119To12111Bootstrap(MinecraftFamily minecraftFamily) {
        this.minecraftFamily = minecraftFamily;
        this.runtime = installRuntime();
    }

    private LinearRuntime installRuntime() {
        return LoaderBootstrap.super.installRuntime(minecraftFamily);
    }

    public void onInitialize() {
        pushConfig();

        ServerLifecycleEvents.SERVER_STARTING.register(runtime::onServerStarting);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> runtime.onServerStopping());
        ServerTickEvents.END_SERVER_TICK.register(server -> runtime.onServerTick());

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                LinearCommand.register(dispatcher));
    }

    private void pushConfig() {
        FabricLinearConfig cfg = FabricConfigIO.load();
        LinearConfig.update(
                cfg.compressionLevel,
                cfg.regionCacheSize,
                cfg.backupEnabled,
                cfg.backupMinChangedChunks,
                cfg.backupMinChangedKb,
                cfg.backupMaxAgeMinutes,
                cfg.backupQuietSeconds,
                cfg.regionsPerSaveTick,
                cfg.confirmWindowSeconds,
                cfg.pressureFlushMinDirtyRegions,
                cfg.pressureFlushMaxDirtyRegions,
                cfg.slowIoThresholdMs,
                cfg.diskSpaceWarnGb,
                cfg.autoRecompressEnabled,
                cfg.idleThresholdMinutes,
                cfg.recompressMinFreeRamPercent,
                cfg.bulkConvertOnLoad
        );
    }
}
