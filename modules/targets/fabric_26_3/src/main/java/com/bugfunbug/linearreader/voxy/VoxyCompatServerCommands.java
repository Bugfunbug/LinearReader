package com.bugfunbug.linearreader.voxy;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

public final class VoxyCompatServerCommands {

    private VoxyCompatServerCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("linearreader")
                        .then(Commands.literal("voxy-compat")
                                .executes(VoxyCompatServerCommands::executeStatus)
                                .then(Commands.literal("auto")
                                        .executes(VoxyCompatServerCommands::executeAuto))));
    }

    private static int executeStatus(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSuccess(() -> Component.literal(VoxyCompatAutoImporter.status()), false);
        return 1;
    }

    private static int executeAuto(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        VoxyCompatAutoImporter.StartResult result = VoxyCompatAutoImporter.start();
        switch (result) {
            case STARTED -> {
                source.sendSuccess(() -> Component.literal(
                        "[LinearReader] Voxy auto import started. Progress will be posted in chat."), false);
                return 1;
            }
            case ALREADY_RUNNING -> {
                source.sendSuccess(() -> Component.literal(VoxyCompatAutoImporter.status()), false);
                return 1;
            }
            case VOXY_NOT_LOADED -> source.sendFailure(Component.literal(
                    "[LinearReader] Voxy is not loaded; auto import is unavailable."));
            case NO_SINGLEPLAYER_SERVER -> source.sendFailure(Component.literal(
                    "[LinearReader] Voxy auto import requires singleplayer, matching /voxy import current."));
            case NO_CLIENT_WORLD -> source.sendFailure(Component.literal(
                    "[LinearReader] No client world is loaded."));
        }
        return 0;
    }
}