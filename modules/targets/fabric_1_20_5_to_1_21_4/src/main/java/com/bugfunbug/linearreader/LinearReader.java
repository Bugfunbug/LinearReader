package com.bugfunbug.linearreader;

import com.bugfunbug.linearreader.mc1205to1214.Minecraft1205To1214Family;
import com.bugfunbug.linearreader.targets.Fabric1205To1214Target;
import net.fabricmc.api.ModInitializer;

public class LinearReader implements ModInitializer {

    public static void installForTests() {
        LinearRuntime.install(Minecraft1205To1214Family.INSTANCE);
    }

    @Override
    public void onInitialize() {
        Fabric1205To1214Target.INSTANCE.onInitialize();
    }
}
