package com.bugfunbug.linearreader.targets;

import com.bugfunbug.linearreader.fabricfamily.Fabric261To262Bootstrap;
import com.bugfunbug.linearreader.mc261to262.Minecraft261To262Family;

public final class Fabric261To262Target implements TargetBootstrap {

    public static final Fabric261To262Target INSTANCE = new Fabric261To262Target();

    private final Fabric261To262Bootstrap loaderBootstrap =
            new Fabric261To262Bootstrap(Minecraft261To262Family.INSTANCE);

    private Fabric261To262Target() {}

    public void onInitialize() {
        loaderBootstrap.onInitialize();
    }
}
