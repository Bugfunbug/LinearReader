package com.bugfunbug.linearreader;

import com.bugfunbug.linearreader.mc263.Minecraft263Family;
import com.bugfunbug.linearreader.targets.Fabric263Target;
import com.bugfunbug.linearreader.voxy.VoxyCompatClientCommands;
import com.bugfunbug.linearreader.voxy.VoxyCompatServerCommands;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;

public class LinearReader implements ModInitializer, ClientModInitializer {

    public static void installForTests() {
        LinearRuntime.install(Minecraft263Family.INSTANCE);
    }

    @Override
    public void onInitialize() {
        Fabric263Target.INSTANCE.onInitialize();

        if (FabricLoader.getInstance().isModLoaded("voxy")) {
            net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register(
                    (dispatcher, registryAccess, environment) ->
                            VoxyCompatServerCommands.register(dispatcher));
        }

        if (FabricLoader.getInstance().isModLoaded("c2me-opts-accel-opencl")) {
            LinearRuntime.LOGGER.warn(
                    "[LinearReader] c2me-opts-accel-opencl is loaded. This C2ME OpenCL module has been reported "
                            + "to stall large LinearReader worlds during spawn preparation; disable it if world "
                            + "loading hangs.");
        }
    }

    @Override
    public void onInitializeClient() {
        VoxyCompatClientCommands.register();
    }
}
