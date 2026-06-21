package com.bugfunbug.linearreader;

import com.bugfunbug.linearreader.mc1215to12110.Minecraft1215To12110Family;
import com.bugfunbug.linearreader.targets.NeoForge1215To1217Target;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

@Mod(LinearRuntime.MOD_ID)
public class LinearReader {

    public static void installForTests() {
        LinearRuntime.install(Minecraft1215To12110Family.INSTANCE);
    }

    public LinearReader(IEventBus modEventBus, ModContainer modContainer) {
        new NeoForge1215To1217Target(modEventBus, modContainer);
    }
}
