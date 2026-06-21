package com.bugfunbug.linearreader;

import com.bugfunbug.linearreader.mc1202to1214.Minecraft1202To1214Family;
import com.bugfunbug.linearreader.targets.NeoForge1202To1204Target;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

@Mod(LinearRuntime.MOD_ID)
public class LinearReader {

    public static void installForTests() {
        LinearRuntime.install(Minecraft1202To1214Family.INSTANCE);
    }

    public LinearReader(IEventBus modEventBus, ModContainer modContainer) {
        new NeoForge1202To1204Target(modEventBus, modContainer);
    }
}
