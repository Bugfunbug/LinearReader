package com.bugfunbug.linearreader;

import com.bugfunbug.linearreader.mc261to2612.Minecraft261To2612Family;
import com.bugfunbug.linearreader.targets.Fabric261To2612Target;
import com.bugfunbug.linearreader.voxy.VoxyCompatClientCommands;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;

public class LinearReader implements ModInitializer, ClientModInitializer {

    public static void installForTests() {
        LinearRuntime.install(Minecraft261To2612Family.INSTANCE);
    }

    @Override
    public void onInitialize() {
        Fabric261To2612Target.INSTANCE.onInitialize();
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
