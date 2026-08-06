package com.bugfunbug.linearreader.command;

import com.bugfunbug.linearreader.LinearRuntime;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

public final class LinearCommand {

    private LinearCommand() {}

    public static void register(RegisterCommandsEvent event) {
        LinearCommandRegistrar.register(event.getDispatcher(), LinearRuntime::hasLinearReaderCommandPermission);
    }
}
