package com.bugfunbug.linearreader;

import com.bugfunbug.linearreader.mc263.Minecraft263Family;
import com.bugfunbug.linearreader.targets.NeoForge263Target;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

@Mod(LinearRuntime.MOD_ID)
public class LinearReader {

    public static void installForTests() {
        LinearRuntime.install(Minecraft263Family.INSTANCE);
    }

    public LinearReader(IEventBus modEventBus, ModContainer modContainer) {
        new NeoForge263Target(modEventBus, modContainer);
    }
}
