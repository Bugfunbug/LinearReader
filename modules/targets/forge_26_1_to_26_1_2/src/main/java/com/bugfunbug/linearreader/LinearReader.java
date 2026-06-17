package com.bugfunbug.linearreader;

import com.bugfunbug.linearreader.mc261to2612.Minecraft261To2612Family;
import com.bugfunbug.linearreader.targets.Forge261To2612Target;
import net.minecraftforge.fml.common.Mod;

@Mod(LinearRuntime.MOD_ID)
public class LinearReader {

    public static void installForTests() {
        LinearRuntime.install(Minecraft261To2612Family.INSTANCE);
    }

    public LinearReader() {
        new Forge261To2612Target();
    }
}
