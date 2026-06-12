package com.bugfunbug.linearreader.voxy;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;

public final class VoxyCompatClientCommands {

    private VoxyCompatClientCommands() {}

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                register(dispatcher));
    }

    static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(ClientCommandManager.literal("linearreader")
                .executes(ctx -> forwardToServer(ctx.getSource(), "linearreader"))
                .then(ClientCommandManager.literal("voxy-compat")
                        .executes(ctx -> executeStatus(ctx.getSource()))
                        .then(ClientCommandManager.literal("auto")
                                .executes(ctx -> executeAuto(ctx.getSource()))))
                .then(ClientCommandManager.argument("serverCommand", StringArgumentType.greedyString())
                        .executes(ctx -> forwardToServer(ctx.getSource(),
                                "linearreader " + StringArgumentType.getString(ctx, "serverCommand")))));
    }

    private static int forwardToServer(FabricClientCommandSource source, String command) {
        var connection = source.getClient().getConnection();
        if (connection == null) {
            source.sendError(Component.literal("[LinearReader] No server connection is available."));
            return 0;
        }
        // Send the packet directly to bypass Fabric's client command API interception.
        // Using connection.sendCommand(command) causes infinite recursion because Fabric
        // hooks sendCommand and re-executes /linearreader as a client command (since it
        // is registered client-side for voxy-compat), producing a StackOverflowError.
        connection.getConnection().send(new ServerboundChatCommandPacket(command));
        return 1;
    }

    private static int executeStatus(FabricClientCommandSource source) {
        source.sendFeedback(Component.literal(VoxyCompatAutoImporter.status()));
        return 1;
    }

    private static int executeAuto(FabricClientCommandSource source) {
        VoxyCompatAutoImporter.StartResult result = VoxyCompatAutoImporter.start();
        switch (result) {
            case STARTED -> {
                source.sendFeedback(Component.literal(
                        "[LinearReader] Voxy auto import started. Progress will be posted in chat."));
                return 1;
            }
            case ALREADY_RUNNING -> {
                source.sendFeedback(Component.literal(VoxyCompatAutoImporter.status()));
                return 1;
            }
            case VOXY_NOT_LOADED -> source.sendError(Component.literal(
                    "[LinearReader] Voxy is not loaded; auto import is unavailable."));
            case NO_SINGLEPLAYER_SERVER -> source.sendError(Component.literal(
                    "[LinearReader] Voxy auto import requires singleplayer, matching /voxy import current."));
            case NO_CLIENT_WORLD -> source.sendError(Component.literal(
                    "[LinearReader] No client world is loaded."));
        }
        return 0;
    }
}
