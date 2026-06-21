package com.bugfunbug.linearreader.targets;

import com.bugfunbug.linearreader.mc1202to1214.Minecraft1202To1214Family;
import com.bugfunbug.linearreader.neoforgefamily.NeoForge1202To1204Bootstrap;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;

public final class NeoForge1202To1204Target implements TargetBootstrap {

    public NeoForge1202To1204Target(IEventBus modEventBus, ModContainer modContainer) {
        new NeoForge1202To1204Bootstrap(Minecraft1202To1214Family.INSTANCE, modEventBus);
    }
}
