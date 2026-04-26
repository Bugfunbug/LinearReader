package com.bugfunbug.linearreader.command;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;

public final class LinearCommand {

    private LinearCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LinearCommandRegistrar.register(dispatcher);
    }
}
