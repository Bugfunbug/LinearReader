package com.bugfunbug.linearreader;

import com.bugfunbug.linearreader.mc263.Minecraft263Family;
import com.bugfunbug.linearreader.targets.Forge263Target;
import net.minecraftforge.fml.common.Mod;

@Mod(LinearRuntime.MOD_ID)
public class LinearReader {

    public static void installForTests() {
        LinearRuntime.install(Minecraft263Family.INSTANCE);
    }

    public LinearReader() {
        new Forge263Target();
    }
}
