package com.bugfunbug.linearreader.targets;

import com.bugfunbug.linearreader.mc263.Minecraft263Family;
import com.bugfunbug.linearreader.neoforgefamily.NeoForge12111To263Bootstrap;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;

public final class NeoForge263Target implements TargetBootstrap {

    public NeoForge263Target(IEventBus modEventBus, ModContainer modContainer) {
        new NeoForge12111To263Bootstrap(Minecraft263Family.INSTANCE, modEventBus, modContainer);
    }
}
