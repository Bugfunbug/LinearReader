package com.bugfunbug.linearreader.targets;

import com.bugfunbug.linearreader.mc1202to1214.Minecraft1202To1214Family;
import com.bugfunbug.linearreader.neoforgefamily.NeoForge1205To121Bootstrap;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;

public final class NeoForge1205To121Target implements TargetBootstrap {

    public NeoForge1205To121Target(IEventBus modEventBus, ModContainer modContainer) {
        new NeoForge1205To121Bootstrap(Minecraft1202To1214Family.INSTANCE, modEventBus, modContainer);
    }
}
