package com.bugfunbug.linearreader.command;

import net.neoforged.neoforge.event.RegisterCommandsEvent;

public final class LinearCommand {

    private LinearCommand() {}

    public static void register(RegisterCommandsEvent event) {
        LinearCommandRegistrar.register(event.getDispatcher(), source -> source.hasPermission(2));
    }
}
