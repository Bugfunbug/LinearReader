package com.bugfunbug.linearreader.targets;

import com.bugfunbug.linearreader.mc12111.Minecraft12111Family;
import com.bugfunbug.linearreader.neoforgefamily.NeoForge12111To2612Bootstrap;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;

public final class NeoForge12111Target implements TargetBootstrap {

    public NeoForge12111Target(IEventBus modEventBus, ModContainer modContainer) {
        new NeoForge12111To2612Bootstrap(Minecraft12111Family.INSTANCE, modEventBus, modContainer);
    }
}
