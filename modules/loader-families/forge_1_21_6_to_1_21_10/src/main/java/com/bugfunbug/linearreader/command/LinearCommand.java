package com.bugfunbug.linearreader.command;

import com.bugfunbug.linearreader.LinearRuntime;
import net.minecraft.commands.CommandSourceStack;
import net.minecraftforge.event.RegisterCommandsEvent;

/**
 * 1.21.6-26.1.2 differs from the forge_1_19_to_1_21_5 version of this class
 * in one important way: this family's Minecraft range crosses the 1.21.11
 * break where CommandSourceStack.hasPermission(int) is removed in favor of a
 * PermissionSet-based permission surface Calling source.hasPermission(2)
 * directly here the way the older Forge family does would stop working once
 * a target in this family reaches 1.21.11 or 26.1.x.
 *
 * Instead this delegates to LinearRuntime.hasOperatorCommandPermission(...),
 * which is MinecraftFamily-aware and already handles both the legacy
 * hasPermission(int) surface and the newer PermissionSet surface (see
 * MinecraftFamily.hasOperatorCommandPermission and its overrides in
 * Minecraft12111Family / Minecraft261To2612Family). This mirrors how the
 * Fabric 1.19-1.21.11 LinearCommand already solves the same problem.
 */
public final class LinearCommand {

    private LinearCommand() {}

    public static void register(RegisterCommandsEvent event) {
        LinearCommandRegistrar.register(event.getDispatcher(), LinearCommand::hasOperatorPermission);
    }

    private static boolean hasOperatorPermission(CommandSourceStack source) {
        return LinearRuntime.hasOperatorCommandPermission(source);
    }
}