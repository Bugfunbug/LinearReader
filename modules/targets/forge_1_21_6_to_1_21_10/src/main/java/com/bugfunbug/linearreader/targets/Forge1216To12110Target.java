package com.bugfunbug.linearreader.targets;

import com.bugfunbug.linearreader.forgefamily.Forge1216To2612Bootstrap;
import com.bugfunbug.linearreader.mc1215to12110.Minecraft1215To12110Family;

public final class Forge1216To12110Target implements TargetBootstrap {

    public Forge1216To12110Target() {
        new Forge1216To2612Bootstrap(Minecraft1215To12110Family.INSTANCE);
    }
}
