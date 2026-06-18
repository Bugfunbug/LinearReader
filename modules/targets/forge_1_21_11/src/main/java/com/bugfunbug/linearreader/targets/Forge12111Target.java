package com.bugfunbug.linearreader.targets;

import com.bugfunbug.linearreader.forgefamily.Forge12111To262Bootstrap;
import com.bugfunbug.linearreader.mc12111.Minecraft12111Family;

public final class Forge12111Target implements TargetBootstrap {

    public Forge12111Target() {
        new Forge12111To262Bootstrap(Minecraft12111Family.INSTANCE);
    }
}
