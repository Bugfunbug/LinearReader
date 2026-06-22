package com.bugfunbug.linearreader.command;

import com.bugfunbug.linearreader.LinearRuntime;
import net.minecraft.commands.CommandSourceStack;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * 1.21.11+ drops CommandSourceStack.hasPermission(int) in favour of a
 * PermissionSet-based surface. Delegating to
 * LinearRuntime.hasOperatorCommandPermission keeps this working across both
 * 1.21.11 (Minecraft12111Family) and 26.1.x (Minecraft261To262Family).
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