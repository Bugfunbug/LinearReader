package com.bugfunbug.linearreader.command;

import com.bugfunbug.linearreader.LinearRuntime;
import net.minecraft.commands.CommandSourceStack;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

public final class LinearCommand {

    private LinearCommand() {}

    public static void register(RegisterCommandsEvent event) {
        LinearCommandRegistrar.register(event.getDispatcher(), LinearCommand::hasCommandPermission);
    }

    private static boolean hasCommandPermission(CommandSourceStack source) {
        return LinearRuntime.hasLinearReaderCommandPermission(source);
    }
}