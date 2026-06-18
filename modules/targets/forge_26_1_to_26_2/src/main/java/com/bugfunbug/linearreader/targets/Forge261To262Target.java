package com.bugfunbug.linearreader.targets;

import com.bugfunbug.linearreader.forgefamily.Forge12111To262Bootstrap;
import com.bugfunbug.linearreader.mc261to262.Minecraft261To262Family;

public final class Forge261To262Target implements TargetBootstrap {

    public Forge261To262Target() {
        new Forge12111To262Bootstrap(Minecraft261To262Family.INSTANCE);
    }
}
