package com.bugfunbug.linearreader.targets;

import com.bugfunbug.linearreader.forgefamily.Forge12111To263Bootstrap;
import com.bugfunbug.linearreader.mc263.Minecraft263Family;

public final class Forge263Target implements TargetBootstrap {

    public Forge263Target() {
        new Forge12111To263Bootstrap(Minecraft263Family.INSTANCE);
    }
}
