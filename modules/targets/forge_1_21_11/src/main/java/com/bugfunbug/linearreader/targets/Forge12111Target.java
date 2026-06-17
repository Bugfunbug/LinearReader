package com.bugfunbug.linearreader.targets;

import com.bugfunbug.linearreader.forgefamily.Forge12111Bootstrap;
import com.bugfunbug.linearreader.mc12111.Minecraft12111Family;

public final class Forge12111Target implements TargetBootstrap {

    public Forge12111Target() {
        new Forge12111Bootstrap(Minecraft12111Family.INSTANCE);
    }
}
