package com.bugfunbug.linearreader.neoforgefamily;

import com.bugfunbug.linearreader.LinearRuntime;
import com.bugfunbug.linearreader.command.LinearCommand;
import com.bugfunbug.linearreader.config.NeoForgeLinearConfig;
import com.bugfunbug.linearreader.loaderfamilies.LoaderBootstrap;
import com.bugfunbug.linearreader.minecraftapi.MinecraftFamily;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.TickEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

public final class NeoForge1202To1204Bootstrap implements LoaderBootstrap {

    private final MinecraftFamily minecraftFamily;
    private final LinearRuntime runtime;

    public NeoForge1202To1204Bootstrap(MinecraftFamily minecraftFamily, IEventBus modEventBus) {
        this.minecraftFamily = minecraftFamily;
        this.runtime = installRuntime();

        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, NeoForgeLinearConfig.SPEC, "linearreader-server.toml");
        modEventBus.addListener(this::onConfigLoad);
        modEventBus.addListener(this::onConfigReload);

        NeoForge.EVENT_BUS.register(this);
        NeoForge.EVENT_BUS.addListener(LinearCommand::register);
    }

    private LinearRuntime installRuntime() {
        return LoaderBootstrap.super.installRuntime(minecraftFamily);
    }

    private void onConfigLoad(ModConfigEvent.Loading event) {
        if (event.getConfig().getSpec() == NeoForgeLinearConfig.SPEC) {
            NeoForgeLinearConfig.pushToLinearConfig();
        }
    }

    private void onConfigReload(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() == NeoForgeLinearConfig.SPEC) {
            NeoForgeLinearConfig.pushToLinearConfig();
            LinearRuntime.LOGGER.info("[LinearReader] Config reloaded.");
        }
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        runtime.onServerStarting(event.getServer());
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        runtime.onServerStopping();
    }

    @SubscribeEvent
    public void onLevelSave(LevelEvent.Save event) {
        runtime.onLevelSave();
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            runtime.onServerTick();
        }
    }
}