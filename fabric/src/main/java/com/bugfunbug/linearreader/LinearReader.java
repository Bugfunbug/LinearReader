package com.bugfunbug.linearreader;

import com.bugfunbug.linearreader.command.LinearCommand;
import com.bugfunbug.linearreader.config.FabricConfigIO;
import com.bugfunbug.linearreader.config.FabricLinearConfig;
import com.bugfunbug.linearreader.config.LinearConfig;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

public class LinearReader implements ModInitializer {

    private final LinearRuntime runtime = LinearRuntime.install(FabricMinecraftHooks.INSTANCE);

    @Override
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
                cfg.recompressMinFreeRamPercent
        );
    }
}
