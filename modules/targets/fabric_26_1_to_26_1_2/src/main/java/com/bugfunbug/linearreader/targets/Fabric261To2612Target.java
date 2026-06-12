package com.bugfunbug.linearreader.targets;

import com.bugfunbug.linearreader.fabricfamily.Fabric261To2612Bootstrap;
import com.bugfunbug.linearreader.mc261to2612.Minecraft261To2612Family;

public final class Fabric261To2612Target implements TargetBootstrap {

    public static final Fabric261To2612Target INSTANCE = new Fabric261To2612Target();

    private final Fabric261To2612Bootstrap loaderBootstrap =
            new Fabric261To2612Bootstrap(Minecraft261To2612Family.INSTANCE);

    private Fabric261To2612Target() {}

    public void onInitialize() {
        loaderBootstrap.onInitialize();
    }
}
