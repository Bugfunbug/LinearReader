package com.bugfunbug.linearreader.targets;

import com.bugfunbug.linearreader.fabricfamily.Fabric119To12111Bootstrap;
import com.bugfunbug.linearreader.mc1205to1214.Minecraft1205To1214Family;

public final class Fabric1205To1214Target implements TargetBootstrap {

    public static final Fabric1205To1214Target INSTANCE = new Fabric1205To1214Target();

    private final Fabric119To12111Bootstrap loaderBootstrap =
            new Fabric119To12111Bootstrap(Minecraft1205To1214Family.INSTANCE);

    private Fabric1205To1214Target() {}

    public void onInitialize() {
        loaderBootstrap.onInitialize();
    }
}
