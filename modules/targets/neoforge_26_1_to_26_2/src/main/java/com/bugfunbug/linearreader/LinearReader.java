package com.bugfunbug.linearreader;

import com.bugfunbug.linearreader.mc261to262.Minecraft261To262Family;
import com.bugfunbug.linearreader.targets.NeoForge261To262Target;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

@Mod(LinearRuntime.MOD_ID)
public class LinearReader {

    public static void installForTests() {
        LinearRuntime.install(Minecraft261To262Family.INSTANCE);
    }

    public LinearReader(IEventBus modEventBus, ModContainer modContainer) {
        new NeoForge261To262Target(modEventBus, modContainer);
    }
}
