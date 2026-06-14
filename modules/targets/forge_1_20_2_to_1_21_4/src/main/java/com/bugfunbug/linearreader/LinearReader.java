package com.bugfunbug.linearreader;

import com.bugfunbug.linearreader.mc1202to1214.Minecraft1202To1214Family;
import com.bugfunbug.linearreader.targets.Forge1202To1214Target;
import net.minecraftforge.fml.common.Mod;

@Mod(LinearRuntime.MOD_ID)
public class LinearReader {

    public static void installForTests() {
        LinearRuntime.install(Minecraft1202To1214Family.INSTANCE);
    }

    public LinearReader() {
        new Forge1202To1214Target();
    }
}
