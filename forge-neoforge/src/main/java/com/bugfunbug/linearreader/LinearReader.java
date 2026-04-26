package com.bugfunbug.linearreader;

import com.bugfunbug.linearreader.command.LinearCommand;
import com.bugfunbug.linearreader.config.ForgeLinearConfig;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(LinearRuntime.MOD_ID)
public class LinearReader {

    private final LinearRuntime runtime = LinearRuntime.install(ForgeMinecraftHooks.INSTANCE);

    public LinearReader() {
        ModLoadingContext.get().registerConfig(
                ModConfig.Type.COMMON, ForgeLinearConfig.SPEC, "linearreader-server.toml");
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::onConfigLoad);
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::onConfigReload);

        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.addListener(LinearCommand::register);
    }

    private void onConfigLoad(ModConfigEvent.Loading event) {
        if (event.getConfig().getSpec() == ForgeLinearConfig.SPEC) {
            ForgeLinearConfig.pushToLinearConfig();
            ForgeLinearConfig.rewriteConfigFile();
        }
    }

    private void onConfigReload(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() == ForgeLinearConfig.SPEC) {
            ForgeLinearConfig.pushToLinearConfig();
            ForgeLinearConfig.rewriteConfigFile();
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
