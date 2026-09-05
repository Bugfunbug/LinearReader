package com.bugfunbug.linearreader;

import com.bugfunbug.linearreader.mc1202to1214.Minecraft1202To1214Family;
import com.bugfunbug.linearreader.targets.Fabric1202To1204Target;
import net.fabricmc.api.ModInitializer;

public class LinearReader implements ModInitializer {

    public static void installForTests() {
        LinearRuntime.install(Minecraft1202To1214Family.INSTANCE);
    }

    @Override
    public void onInitialize() {
        Fabric1202To1204Target.INSTANCE.onInitialize();
    }
}
