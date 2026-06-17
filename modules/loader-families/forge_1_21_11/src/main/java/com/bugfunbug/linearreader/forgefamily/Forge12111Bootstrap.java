package com.bugfunbug.linearreader.forgefamily;

import com.bugfunbug.linearreader.LinearRuntime;
import com.bugfunbug.linearreader.command.LinearCommand;
import com.bugfunbug.linearreader.config.ForgeLinearConfig;
import com.bugfunbug.linearreader.loaderfamilies.LoaderBootstrap;
import com.bugfunbug.linearreader.minecraftapi.MinecraftFamily;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

public final class Forge12111Bootstrap implements LoaderBootstrap {

    private final MinecraftFamily minecraftFamily;
    private final LinearRuntime runtime;

    public Forge12111Bootstrap(MinecraftFamily minecraftFamily) {
        this.minecraftFamily = minecraftFamily;
        this.runtime = installRuntime();

        ModLoadingContext.get().registerConfig(
                ModConfig.Type.COMMON,
                ForgeLinearConfig.SPEC,
                "linearreader-server.toml"
        );

        // 1. Hook Mod Bus Events via the FML Context Mod Bus Group
        var modBusGroup = FMLJavaModLoadingContext.get().getModBusGroup();
        ModConfigEvent.Loading.getBus(modBusGroup).addListener(this::onConfigLoad);
        ModConfigEvent.Reloading.getBus(modBusGroup).addListener(this::onConfigReload);

        // 2. Hook Standard Gameplay Bus Events via their explicit, valid static fields
        ServerStartingEvent.BUS.addListener(this::onServerStarting);
        ServerStoppingEvent.BUS.addListener(this::onServerStopping);
        LevelEvent.Save.BUS.addListener(this::onLevelSave);
        RegisterCommandsEvent.BUS.addListener(this::onCommands);

        // 3. Target ServerTickEvent.Post directly.
        // It now has its own standalone native BUS field, completely removing the need for
        // manual factory creation or legacy phase enum parsing!
        TickEvent.ServerTickEvent.Post.BUS.addListener(this::onServerTick);
    }

    private LinearRuntime installRuntime() {
        return LoaderBootstrap.super.installRuntime(minecraftFamily);
    }

    // ---------------- CONFIG LISTENERS ----------------

    private void onConfigLoad(ModConfigEvent.Loading event) {
        if (event.getConfig().getSpec() == ForgeLinearConfig.SPEC) {
            ForgeLinearConfig.pushToLinearConfig();
        }
    }

    private void onConfigReload(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() == ForgeLinearConfig.SPEC) {
            ForgeLinearConfig.pushToLinearConfig();
            LinearRuntime.LOGGER.info("[LinearReader] Config reloaded.");
        }
    }

    // ---------------- SERVER LISTENERS ----------------

    private void onServerStarting(ServerStartingEvent event) {
        runtime.onServerStarting(event.getServer());
    }

    private void onServerStopping(ServerStoppingEvent event) {
        runtime.onServerStopping();
    }

    private void onLevelSave(LevelEvent.Save event) {
        runtime.onLevelSave();
    }

    // ---------------- TICK HANDLER (CLEAN 1.21.11 STRUCTURE) ----------------

    private void onServerTick(TickEvent.ServerTickEvent.Post event) {
        // The event system only fires this method at the end of a tick now.
        // No phase checks required!
        runtime.onServerTick();
    }

    // ---------------- COMMAND LISTENERS ----------------

    private void onCommands(RegisterCommandsEvent event) {
        LinearCommand.register(event);
    }
}