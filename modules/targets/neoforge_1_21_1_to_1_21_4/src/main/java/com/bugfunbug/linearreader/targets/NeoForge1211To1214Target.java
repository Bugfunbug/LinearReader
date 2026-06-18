package com.bugfunbug.linearreader.targets;

import com.bugfunbug.linearreader.mc1202to1214.Minecraft1202To1214Family;
import com.bugfunbug.linearreader.neoforgefamily.NeoForge1211To12110Bootstrap;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;

public final class NeoForge1211To1214Target implements TargetBootstrap {

    public NeoForge1211To1214Target(IEventBus modEventBus, ModContainer modContainer) {
        new NeoForge1211To12110Bootstrap(Minecraft1202To1214Family.INSTANCE, modEventBus, modContainer);
    }
}
