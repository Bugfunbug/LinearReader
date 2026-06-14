package com.bugfunbug.linearreader.targets;

import com.bugfunbug.linearreader.forgefamily.Forge119To1215Bootstrap;
import com.bugfunbug.linearreader.mc1202to1214.Minecraft1202To1214Family;

public final class Forge1202To1214Target implements TargetBootstrap {

    public Forge1202To1214Target() {
        new Forge119To1215Bootstrap(Minecraft1202To1214Family.INSTANCE);
    }
}
