package com.bugfunbug.linearreader;

import com.bugfunbug.linearreader.mc1215to12110.Minecraft1215To12110Family;
import com.bugfunbug.linearreader.targets.Forge1216To12110Target;
import net.minecraftforge.fml.common.Mod;

@Mod(LinearRuntime.MOD_ID)
public class LinearReader {

    public static void installForTests() {
        LinearRuntime.install(Minecraft1215To12110Family.INSTANCE);
    }

    public LinearReader() {
        new Forge1216To12110Target();
    }
}
