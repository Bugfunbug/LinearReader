package com.bugfunbug.linearreader;

import com.bugfunbug.linearreader.mc12111.Minecraft12111Family;
import com.bugfunbug.linearreader.targets.Forge12111Target;
import net.minecraftforge.fml.common.Mod;

@Mod(LinearRuntime.MOD_ID)
public class LinearReader {

    public static void installForTests() {
        LinearRuntime.install(Minecraft12111Family.INSTANCE);
    }

    public LinearReader() {
        new Forge12111Target();
    }
}
