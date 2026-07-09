package com.bugfunbug.linearreader.voxy;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class VoxyCompatClientCommands {

    private VoxyCompatClientCommands() {}

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                register(dispatcher));
    }

    private static final List<String> SERVER_SUBCOMMANDS = List.of(
            "cache_info", "storage", "health", "pos", "verify",
            "prune-chunks", "sync-backups", "bench", "afk-compress",
            "pin", "unpin", "pins", "export-mca", "graph"
    );

    static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(ClientCommands.literal("linearreader")
                .executes(ctx -> forwardToServer(ctx.getSource(), "linearreader"))
                .then(ClientCommands.argument("serverCommand", StringArgumentType.greedyString())
                        .suggests(VoxyCompatClientCommands::suggestArgs)
                        .executes(VoxyCompatClientCommands::executeArgs)));
    }

    private static int executeArgs(CommandContext<FabricClientCommandSource> ctx) {
        String full = StringArgumentType.getString(ctx, "serverCommand");
        String[] words = full.split(" ", -1);
        boolean voxyLoaded = FabricLoader.getInstance().isModLoaded("voxy");

        if (voxyLoaded && words.length >= 1 && "voxy-compat".equals(words[0])) {
            if (words.length >= 2 && "auto".equals(words[1])) {
                return executeAuto(ctx.getSource());
            }
            return executeStatus(ctx.getSource());
        }

        return forwardToServer(ctx.getSource(), "linearreader " + full);
    }

    private static CompletableFuture<Suggestions> suggestArgs(
            CommandContext<FabricClientCommandSource> ctx, SuggestionsBuilder builder) {
        String remaining = builder.getRemaining();
        String[] parts = remaining.split(" ", -1);
        int committed = parts.length - 1;
        String current = parts[parts.length - 1];

        int currentTokenStart = builder.getStart() + remaining.length() - current.length();
        SuggestionsBuilder sub = builder.createOffset(currentTokenStart);
        boolean voxyLoaded = FabricLoader.getInstance().isModLoaded("voxy");

        if (committed == 0) {
            for (String name : SERVER_SUBCOMMANDS) {
                if (name.startsWith(current)) sub.suggest(name);
            }
            if (voxyLoaded && "voxy-compat".startsWith(current)) {
                sub.suggest("voxy-compat");
            }
            return sub.buildFuture();
        }

        if (voxyLoaded && committed == 1 && "voxy-compat".equals(parts[0]) && "auto".startsWith(current)) {
            sub.suggest("auto");
        }
        return sub.buildFuture();
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
