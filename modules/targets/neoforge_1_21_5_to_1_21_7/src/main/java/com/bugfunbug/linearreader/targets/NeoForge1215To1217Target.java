package com.bugfunbug.linearreader.targets;

import com.bugfunbug.linearreader.mc1215to12110.Minecraft1215To12110Family;
import com.bugfunbug.linearreader.neoforgefamily.NeoForge1211To12110Bootstrap;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;

public final class NeoForge1215To1217Target implements TargetBootstrap {

    public NeoForge1215To1217Target(IEventBus modEventBus, ModContainer modContainer) {
        new NeoForge1211To12110Bootstrap(Minecraft1215To12110Family.INSTANCE, modEventBus, modContainer);
    }
}
