package com.bugfunbug.linearreader.targets;

import com.bugfunbug.linearreader.forgefamily.Forge119To1215Bootstrap;
import com.bugfunbug.linearreader.mc1215to12110.Minecraft1215To12110Family;

public final class Forge1215Target implements TargetBootstrap {

    public Forge1215Target() {
        new Forge119To1215Bootstrap(Minecraft1215To12110Family.INSTANCE);
    }
}
