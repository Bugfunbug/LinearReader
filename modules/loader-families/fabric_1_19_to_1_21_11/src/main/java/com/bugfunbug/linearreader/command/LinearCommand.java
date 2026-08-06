package com.bugfunbug.linearreader.command;

import com.bugfunbug.linearreader.LinearRuntime;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;

public final class LinearCommand {

    private LinearCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LinearCommandRegistrar.register(dispatcher, LinearRuntime::hasLinearReaderCommandPermission);
    }
}