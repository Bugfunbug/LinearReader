package com.bugfunbug.linearreader.targets;

import com.bugfunbug.linearreader.forgefamily.Forge12111To2612Bootstrap;
import com.bugfunbug.linearreader.mc261to2612.Minecraft261To2612Family;

public final class Forge261To2612Target implements TargetBootstrap {

    public Forge261To2612Target() {
        new Forge12111To2612Bootstrap(Minecraft261To2612Family.INSTANCE);
    }
}
