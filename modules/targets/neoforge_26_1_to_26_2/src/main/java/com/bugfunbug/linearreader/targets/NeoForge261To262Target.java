package com.bugfunbug.linearreader.targets;

import com.bugfunbug.linearreader.mc261to262.Minecraft261To262Family;
import com.bugfunbug.linearreader.neoforgefamily.NeoForge12111To262Bootstrap;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;

public final class NeoForge261To262Target implements TargetBootstrap {

    public NeoForge261To262Target(IEventBus modEventBus, ModContainer modContainer) {
        new NeoForge12111To262Bootstrap(Minecraft261To262Family.INSTANCE, modEventBus, modContainer);
    }
}
