package com.bugfunbug.linearreader.targets;

import com.bugfunbug.linearreader.fabricfamily.Fabric261To263Bootstrap;
import com.bugfunbug.linearreader.mc263.Minecraft263Family;

public final class Fabric263Target implements TargetBootstrap {

    public static final Fabric263Target INSTANCE = new Fabric263Target();

    private final Fabric261To263Bootstrap loaderBootstrap =
            new Fabric261To263Bootstrap(Minecraft263Family.INSTANCE);

    private Fabric263Target() {}

    public void onInitialize() {
        loaderBootstrap.onInitialize();
    }
}
