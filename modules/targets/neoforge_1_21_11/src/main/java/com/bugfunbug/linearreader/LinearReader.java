package com.bugfunbug.linearreader;

import com.bugfunbug.linearreader.mc12111.Minecraft12111Family;
import com.bugfunbug.linearreader.targets.NeoForge12111Target;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

@Mod(LinearRuntime.MOD_ID)
public class LinearReader {

    public static void installForTests() {
        LinearRuntime.install(Minecraft12111Family.INSTANCE);
    }

    public LinearReader(IEventBus modEventBus, ModContainer modContainer) {
        new NeoForge12111Target(modEventBus, modContainer);
    }
}
