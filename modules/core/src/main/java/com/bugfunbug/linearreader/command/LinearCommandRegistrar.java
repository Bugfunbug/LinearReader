package com.bugfunbug.linearreader.command;

import com.bugfunbug.linearreader.LinearRuntime;
import com.bugfunbug.linearreader.LinearStats;
import com.bugfunbug.linearreader.StoragePolicyManager;
import com.bugfunbug.linearreader.config.LinearConfig;
import com.bugfunbug.linearreader.linear.IdleRecompressor;
import com.bugfunbug.linearreader.linear.LinearExporter;
import com.bugfunbug.linearreader.linear.LinearRegionFile;
import com.bugfunbug.linearreader.linear.CompressionAlgorithm;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.phys.Vec3;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.server.level.ServerLevel;

import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

public final class LinearCommandRegistrar {

    private LinearCommandRegistrar() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher,
                                Predicate<CommandSourceStack> permissionCheck) {

        dispatcher.register(
                Commands.literal("linearreader")
                        .requires(permissionCheck)
                        .then(Commands.literal("cache_info")
                                .executes(LinearCommandRegistrar::executeInfo))
                        .then(Commands.literal("storage")
                                .executes(LinearCommandRegistrar::executeStorage))
                        .then(Commands.literal("health")
                                .executes(LinearCommandRegistrar::executeHealth)
                                .then(Commands.literal("debug")
                                        .executes(LinearCommandRegistrar::executeHealthDebug)))
                        .then(Commands.literal("pos")
                                .executes(LinearCommandRegistrar::executePos))
                        .then(Commands.literal("verify")
                                .executes(LinearCommandRegistrar::executeVerify))
                        .then(Commands.literal("prune-chunks")
                                .executes(LinearCommandRegistrar::executePruneChunks)
                                .then(Commands.literal("confirm")
                                        .executes(LinearCommandRegistrar::executePruneChunksConfirm)))
                        .then(Commands.literal("sync-backups")
                                .executes(LinearCommandRegistrar::executeSyncBackups)
                                .then(Commands.literal("confirm")
                                        .executes(LinearCommandRegistrar::executeSyncBackupsConfirm)))
                        .then(Commands.literal("bench")
                                .executes(LinearCommandRegistrar::executeBench)
                                .then(Commands.literal("debug")
                                        .executes(LinearCommandRegistrar::executeBenchDebug))
                                .then(Commands.literal("reset")
                                        .executes(LinearCommandRegistrar::executeBenchReset)))
                        .then(Commands.literal("afk-compress")
                                .executes(LinearCommandRegistrar::executeAfkCompressStatus)
                                .then(Commands.literal("zstd")
                                        .then(Commands.literal("start")
                                                .executes(ctx -> executeAfkCompressStart(
                                                        ctx, CompressionAlgorithm.Algorithm.ZSTD, null))
                                                .then(Commands.argument("dimension", DimensionArgument.dimension())
                                                        .executes(ctx -> executeAfkCompressStart(
                                                                ctx, CompressionAlgorithm.Algorithm.ZSTD,
                                                                DimensionArgument.getDimension(ctx, "dimension"))))))
                                .then(Commands.literal("brotli")
                                        .then(Commands.literal("start")
                                                .executes(ctx -> executeAfkCompressStart(
                                                        ctx, CompressionAlgorithm.Algorithm.BROTLI, null))
                                                .then(Commands.argument("dimension", DimensionArgument.dimension())
                                                        .executes(ctx -> executeAfkCompressStart(
                                                                ctx, CompressionAlgorithm.Algorithm.BROTLI,
                                                                DimensionArgument.getDimension(ctx, "dimension"))))))
                                .then(Commands.literal("stop")
                                        .executes(LinearCommandRegistrar::executeAfkCompressStop)))
                        .then(Commands.literal("pin")
                                .executes(LinearCommandRegistrar::executePinHere)
                                .then(Commands.argument("rx", IntegerArgumentType.integer())
                                        .then(Commands.argument("rz", IntegerArgumentType.integer())
                                                .executes(LinearCommandRegistrar::executePinCoords))))
                        .then(Commands.literal("unpin")
                                .executes(LinearCommandRegistrar::executeUnpinHere)
                                .then(Commands.argument("rx", IntegerArgumentType.integer())
                                        .then(Commands.argument("rz", IntegerArgumentType.integer())
                                                .executes(LinearCommandRegistrar::executeUnpinCoords))))
                        .then(Commands.literal("pins")
                                .executes(LinearCommandRegistrar::executeListPins))
                        .then(Commands.literal("export-mca")
                                .executes(LinearCommandRegistrar::executeExportStatus)
                                .then(Commands.literal("start")
                                        .executes(LinearCommandRegistrar::executeExportStart))
                                .then(Commands.literal("stop")
                                        .executes(LinearCommandRegistrar::executeExportStop)))
                        .then(Commands.literal("graph")
                                .executes(LinearCommandRegistrar::executeGraphStatus)
                                .then(Commands.literal("stop")
                                    .executes(LinearCommandRegistrar::executeGraphStop))
                                .then(Commands.literal("status")
                                    .executes(LinearCommandRegistrar::executeGraphStatus))
                                .then(Commands.argument("args", StringArgumentType.greedyString())
                                    .suggests(LinearCommandRegistrar::suggestGraphArgs)
                                    .executes(LinearCommandRegistrar::executeGraph)))
        );
    }

    private static String rawArgumentText(CommandContext<CommandSourceStack> ctx, String argumentName) {
        for (var parsedNode : ctx.getNodes()) {
            if (parsedNode.getNode().getName().equals(argumentName)) {
                return parsedNode.getRange().get(ctx.getInput());
            }
        }
        return null;
    }

    // ---------------------------------------------------------------------------
    // /linearreader pos
    // ---------------------------------------------------------------------------
    private static int executePos(CommandContext<CommandSourceStack> ctx) {
        Vec3 pos    = ctx.getSource().getPosition();
        int blockX  = (int) Math.floor(pos.x);
        int blockZ  = (int) Math.floor(pos.z);
        int chunkX  = blockX >> 4;
        int chunkZ  = blockZ >> 4;
        int regionX = chunkX >> 5;
        int regionZ = chunkZ >> 5;
        int localX  = chunkX & 31;
        int localZ  = chunkZ & 31;

        String  fileName = "r." + regionX + "." + regionZ + ".linear";
        boolean isOpen   = LinearRegionFile.ALL_OPEN.stream()
                .anyMatch(r -> r.regionX == regionX && r.regionZ == regionZ);
        String status = isOpen ? "§acached" : "§7not in cache";

        final String msg =
                "§6[LinearReader] Current Position\n"
                        + "§7  Block  : §f" + blockX + ", " + (int) Math.floor(pos.y) + ", " + blockZ + "\n"
                        + "§7  Chunk  : §f" + chunkX + ", " + chunkZ
                        + " §8(local " + localX + ", " + localZ + " within region)\n"
                        + "§7  Region : §f" + fileName + " §8[" + status + "§8]";

        ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }

    // ---------------------------------------------------------------------------
    // /linearreader cache_info
    // ---------------------------------------------------------------------------
    private static int executeInfo(CommandContext<CommandSourceStack> ctx) {
        int  open  = 0;
        int  dirty = 0;
        long bytes = 0;

        for (LinearRegionFile region : LinearRegionFile.ALL_OPEN) {
            open++;
            if (region.isDirty()) dirty++;
            bytes += region.estimateRamBytes();
        }

        final String msg = "§6[LinearReader] Cache Info\n"
                + "§7  Open regions : §f" + open  + "\n"
                + "§7  Dirty regions: §f" + dirty + "\n"
                + "§7  Chunk RAM est: §f" + String.format("%.2f MB", bytes / (1024.0 * 1024.0)) + "\n"
                + "§8  (Estimate covers raw NBT data; Java object overhead is extra)";
        ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
        return open;
    }

    // ---------------------------------------------------------------------------
    // /linearreader storage
    // ---------------------------------------------------------------------------
    private static int executeStorage(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server    = ctx.getSource().getServer();
        Path            worldRoot = LinearRuntime.resolveWorldRoot(server);

        long[] linBytes = {0}, linCount = {0};
        long[] bakBytes = {0}, bakCount = {0};

        try (Stream<Path> stream = Files.walk(worldRoot)) {
            stream.filter(Files::isRegularFile).forEach(p -> {
                String name = p.getFileName().toString();
                try {
                    if (name.endsWith(".linear.bak")) {
                        bakBytes[0] += Files.size(p); bakCount[0]++;
                    } else if (name.endsWith(".linear")) {
                        linBytes[0] += Files.size(p); linCount[0]++;
                    }
                } catch (IOException ignored) {}
            });
        } catch (IOException e) {
            ctx.getSource().sendFailure(Component.literal(
                    "[LinearReader] Could not walk world directory: " + e.getMessage()));
            return 0;
        }

        final String msg = "§6[LinearReader] Storage Info\n"
                + "§7  .linear files : §f" + linCount[0] + " (" + fmtSize(linBytes[0]) + ")\n"
                + "§7  .linear.bak   : §f" + bakCount[0] + " (" + fmtSize(bakBytes[0]) + ")\n"
                + "§7  Total on disk : §f" + fmtSize(linBytes[0] + bakBytes[0]);
        ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
        return (int) Math.min(linCount[0], Integer.MAX_VALUE);
    }

    // ---------------------------------------------------------------------------
    // /linearreader health
    // ---------------------------------------------------------------------------
    private static int executeHealth(CommandContext<CommandSourceStack> ctx) {
        StoragePolicyManager.PolicyDebugSnapshot policy = StoragePolicyManager.debugSnapshot();
        String msg = "§6[LinearReader] Storage Health\n"
                + "§7  Mode: §f" + policy.compressionMode()
                + "§7  Profile: §f" + policy.loadProfile()
                + "§7  Compression: §f" + policy.compressionLevel()
                + "§7  Flush budget: §f" + policy.flushBudgetPerTick() + "/tick\n"
                + "§7  Quietness: §f" + fmtPercent(policy.quietnessScore())
                + "§7  Pressure: §f" + fmtPercent(policy.pressureScore())
                + "§7  Tick strain: §f" + fmtPercent(policy.tickStrain())
                + "§7  Cache churn: §f" + fmtScore(policy.cacheChurnScore())
                + "§7  Idle for: §f" + fmtDuration(policy.idleForMs()) + "\n"
                + "§7  Maintenance: "
                + (policy.maintenanceAllowed() ? "§aallowed" : "§edeferred")
                + "§7  Budget: §f" + policy.maintenanceBudgetFiles() + " file(s)"
                + "§7  Backlog: §f" + policy.backlog() + "\n"
                + "§7  Debt total: §f" + fmtScore(policy.maintenanceDebtScore())
                + "§7  Compression: §f" + fmtScore(policy.compressionDebtScore())
                + "§7  Backup: §f" + fmtScore(policy.backupDebtScore())
                + "§7  Dirty: §f" + fmtScore(policy.dirtyDebtScore()) + "\n"
                + "§7  Writes: §f" + String.format("%.1f/s", policy.chunkWriteRate())
                + "§7  Flush latency: §f" + String.format("%.1f ms", policy.flushLatencyMs()) + "\n"
                + "§7  Region heat: §f" + policy.hotRegions() + " hot"
                + "§7, §f" + policy.coldRegions() + " cold"
                + "§7, §f" + policy.trackedRegions() + " tracked\n"
                + "§7  Pins: §f" + policy.pinnedRegionCount()
                + "§7  Resident hot set: §f" + policy.residentHotSet() + "\n"
                + "§7  Resident cache: budget §f" + fmtSize(policy.residentBudgetBytes())
                + "§7  target §f" + fmtSize(policy.residentTargetBytes())
                + "§7  trims §f" + policy.residentTrimRuns()
                + "§7  evicted §f" + policy.residentTrimmedRegions()
                + "§7  freed §f" + fmtSize(policy.residentTrimmedBytes());
        ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }

    private static int executeHealthDebug(CommandContext<CommandSourceStack> ctx) {
        StoragePolicyManager.PolicyDebugSnapshot policy = StoragePolicyManager.debugSnapshot();
        String msg = "§6[LinearReader] Storage Health §8(debug)\n"
                + "§7  Last policy change: §f" + fmtSince(policy.lastTransitionAtMs())
                + "§7  " + policy.lastTransitionSummary() + "\n"
                + "§8  " + policy.lastTransitionDetail() + "\n"
                + "§7  Recompress status: §f" + (IdleRecompressor.isRunning() ? "running" : "idle")
                + "§7  Manual: §f" + IdleRecompressor.isManual()
                + "§7  Last decision: §f" + fmtSince(IdleRecompressor.lastDecisionAtMs())
                + "§7  " + IdleRecompressor.lastDecisionSummary() + "\n"
                + "§8  " + IdleRecompressor.lastDecisionDetail() + "\n"
                + "§7  Files scanned: §f" + IdleRecompressor.filesScanned()
                + "§7  Upgraded: §f" + IdleRecompressor.filesRecompressed()
                + "§7  Unstable skipped: §f" + IdleRecompressor.filesUnstableSkipped()
                + "§7  Low-RAM pauses: §f" + IdleRecompressor.lowRamPauses() + "\n"
                + "§7  Quietness: §f" + fmtPercent(policy.quietnessScore())
                + "§7  Pressure: §f" + fmtPercent(policy.pressureScore())
                + "§7  Tick strain: §f" + fmtPercent(policy.tickStrain())
                + "§7  Churn: §f" + fmtScore(policy.cacheChurnScore()) + "\n"
                + "§7  Debt: total §f" + fmtScore(policy.maintenanceDebtScore())
                + "§7  comp §f" + fmtScore(policy.compressionDebtScore())
                + "§7  backup §f" + fmtScore(policy.backupDebtScore())
                + "§7  dirty §f" + fmtScore(policy.dirtyDebtScore()) + "\n"
                + "§7  Profile: §f" + policy.loadProfile()
                + "§7  Mode: §f" + policy.compressionMode()
                + "§7  Flush: §f" + policy.flushBudgetPerTick()
                + "§7  Maint budget: §f" + policy.maintenanceBudgetFiles() + "\n"
                + "§7  Resident target: §f" + fmtSize(policy.residentTargetBytes())
                + "§7  Hot set: §f" + policy.residentHotSet()
                + "§7  Pins: §f" + policy.pinnedRegionCount();
        ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }

    // ---------------------------------------------------------------------------
    // /linearreader verify
    // ---------------------------------------------------------------------------
    private static int executeVerify(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer    server    = ctx.getSource().getServer();
        Path               worldRoot = LinearRuntime.resolveWorldRoot(server);
        CommandSourceStack source    = ctx.getSource();

        source.sendSuccess(() -> Component.literal(
                "§6[LinearReader] Starting region verification — results will appear here."), false);

        Thread verifyThread = new Thread(() -> {
            List<Path> allFiles = new ArrayList<>();
            try (Stream<Path> stream = Files.walk(worldRoot)) {
                stream.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".linear"))
                        .forEach(allFiles::add);
            } catch (IOException e) {
                sendFromThread(source, "§c[LinearReader] Verify failed — could not walk world: " + e.getMessage());
                return;
            }

            if (allFiles.isEmpty()) {
                sendFromThread(source, "§e[LinearReader] No .linear files found to verify.");
                return;
            }

            int total = allFiles.size(), ok = 0, failed = 0, noCRC = 0;

            for (Path file : allFiles) {
                LinearRegionFile.VerifyResult result = LinearRegionFile.verifyOnDisk(file);
                if (result.ok) {
                    ok++;
                    if (!result.hasCRC) noCRC++;
                } else {
                    failed++;
                    String reason = file.getFileName() + " — " + result.reason;
                    LinearRuntime.LOGGER.warn("[LinearReader] CORRUPT: {}", reason);
                    sendFromThread(source, "§c[LinearReader] CORRUPT: " + reason);
                }
            }

            final int noCRCFinal = noCRC;
            final String summary = "§6[LinearReader] Verify complete: §f" + total
                    + "§6 files scanned - §a" + ok + " OK"
                    + (failed    > 0 ? "§c, " + failed    + " CORRUPT" : "")
                    + (noCRCFinal > 0 ? "§e, " + noCRCFinal + " without checksum" : "")
                    + "§6.";
            sendFromThread(source, summary);

        }, "linearreader-verify");

        verifyThread.setDaemon(true);
        verifyThread.start();
        return 1;
    }

    private static void sendFromThread(CommandSourceStack source, String msg) {
        source.sendSuccess(() -> Component.literal(msg), false);
    }

    private static int executePruneChunks(CommandContext<CommandSourceStack> ctx) {
        return ChunkPruner.startDryRun(ctx.getSource());
    }

    private static int executePruneChunksConfirm(CommandContext<CommandSourceStack> ctx) {
        return ChunkPruner.confirm(ctx.getSource());
    }

    private static int executeSyncBackups(CommandContext<CommandSourceStack> ctx) {
        return BackupSyncer.startDryRun(ctx.getSource());
    }

    private static int executeSyncBackupsConfirm(CommandContext<CommandSourceStack> ctx) {
        return BackupSyncer.confirm(ctx.getSource());
    }

    // ---------------------------------------------------------------------------
    // /linearreader bench [reset]
    // ---------------------------------------------------------------------------
    private static int executeBench(CommandContext<CommandSourceStack> ctx) {
        return executeBench(ctx, false);
    }

    private static int executeBenchDebug(CommandContext<CommandSourceStack> ctx) {
        return executeBench(ctx, true);
    }

    private static int executeBench(CommandContext<CommandSourceStack> ctx, boolean debug) {
        if (!LinearStats.isEnabled()) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "§e[LinearReader] Benchmark stats collection is currently disabled. "
                            + "Use §f/linearreader bench reset§e to enable it and start a fresh window."), false);
            return 1;
        }

        LinearStats s = LinearStats.INSTANCE;

        long cReads   = s.chunkReads.sum();
        long cWrites  = s.chunkWrites.sum();
        long rLoads   = s.regionLoads.sum();
        long rFlushes = s.regionFlushes.sum();
        long cHits    = s.cacheHits.sum();
        long cMisses  = s.cacheMisses.sum();
        long wHits    = s.wrapperCacheHits.sum();
        long wMisses  = s.wrapperCacheMisses.sum();
        long reloads  = s.residentReloads.sum();
        long evictions = s.residentEvictions.sum();
        long cTotal   = cHits + cMisses;
        long wTotal   = wHits + wMisses;
        long uncomp   = s.bytesUncompressed.sum();
        long comp     = s.bytesCompressed.sum();

        double uptime  = LinearStats.uptimeSeconds();
        double hitPct  = cTotal == 0 ? 0.0 : cHits * 100.0 / cTotal;
        double wrapperHitPct = wTotal == 0 ? 0.0 : wHits * 100.0 / wTotal;
        double readTps = uptime > 0 ? cReads  / uptime : 0;
        double wrtTps  = uptime > 0 ? cWrites / uptime : 0;
        double loadReadAvgMs = LinearStats.avgMs(s.regionLoadReadNs.sum(), rLoads);
        double loadVerifyAvgMs = LinearStats.avgMs(s.regionLoadVerifyNs.sum(), rLoads);
        double loadDecompressAvgMs = LinearStats.avgMs(s.regionLoadDecompressNs.sum(), rLoads);
        double loadParseAvgMs = LinearStats.avgMs(s.regionLoadParseNs.sum(), rLoads);
        double flushSnapshotAvgMs = LinearStats.avgMs(s.regionFlushSnapshotNs.sum(), rFlushes);
        double flushBuildAvgMs = LinearStats.avgMs(s.regionFlushBuildNs.sum(), rFlushes);
        double flushCompressAvgMs = LinearStats.avgMs(s.regionFlushCompressNs.sum(), rFlushes);
        double flushChecksumAvgMs = LinearStats.avgMs(s.regionFlushChecksumNs.sum(), rFlushes);
        double flushWriteAvgMs = LinearStats.avgMs(s.regionFlushWriteNs.sum(), rFlushes);
        double flushSyncAvgMs = LinearStats.avgMs(s.regionFlushSyncNs.sum(), rFlushes);
        double flushRenameAvgMs = LinearStats.avgMs(s.regionFlushRenameNs.sum(), rFlushes);

        String msg = "§6[LinearReader] Benchmark Report"
                + (debug ? " §8(debug)" : "")
                + "§8 (window: " + fmtUptime(uptime) + ")\n"
                + "§7§l  ── Chunk I/O ──§r\n"
                + "§7  Reads  : §f" + cReads
                + "§7  (" + String.format("%.1f", readTps) + "/s)"
                + "  avg §f" + String.format("%.3f", LinearStats.avgMs(s.chunkReadNs.sum(), cReads))   + "ms"
                + "  min §f" + String.format("%.3f", LinearStats.toMs(s.minChunkReadNs.get()))          + "ms"
                + "  max §f" + String.format("%.3f", LinearStats.toMs(s.maxChunkReadNs.get()))          + "ms\n"
                + "§7  Deserialize: §f" + s.chunkDeserializes.sum()
                + "  avg §f" + String.format("%.3f", LinearStats.avgMs(s.chunkDeserializeNs.sum(), s.chunkDeserializes.sum())) + "ms"
                + "  min §f" + String.format("%.3f", LinearStats.toMs(s.minChunkDeserializeNs.get())) + "ms"
                + "  max §f" + String.format("%.3f", LinearStats.toMs(s.maxChunkDeserializeNs.get())) + "ms\n"
                + "§7  Writes : §f" + cWrites
                + "§7  (" + String.format("%.1f", wrtTps) + "/s)"
                + "  avg §f" + String.format("%.3f", LinearStats.avgMs(s.chunkWriteNs.sum(), cWrites)) + "ms"
                + "  min §f" + String.format("%.3f", LinearStats.toMs(s.minChunkWriteNs.get()))         + "ms"
                + "  max §f" + String.format("%.3f", LinearStats.toMs(s.maxChunkWriteNs.get()))         + "ms\n"
                + "§7§l  ── Region I/O ──§r\n"
                + "§7  Loads  : §f" + rLoads
                + "  avg §f" + String.format("%.1f", LinearStats.avgMs(s.regionLoadNs.sum(), rLoads))   + "ms"
                + "  min §f" + String.format("%.1f", LinearStats.toMs(s.minRegionLoadNs.get()))          + "ms"
                + "  max §f" + String.format("%.1f", LinearStats.toMs(s.maxRegionLoadNs.get()))          + "ms\n"
                + (debug
                ? "§8    phases avg: read " + String.format("%.1f", loadReadAvgMs)
                + "  verify " + String.format("%.1f", loadVerifyAvgMs)
                + "  zstd " + String.format("%.1f", loadDecompressAvgMs)
                + "  parse " + String.format("%.1f", loadParseAvgMs) + " ms\n"
                : "")
                + "§7  Flushes: §f" + rFlushes
                + "  avg §f" + String.format("%.1f", LinearStats.avgMs(s.regionFlushNs.sum(), rFlushes))+ "ms"
                + "  min §f" + String.format("%.1f", LinearStats.toMs(s.minRegionFlushNs.get()))         + "ms"
                + "  max §f" + String.format("%.1f", LinearStats.toMs(s.maxRegionFlushNs.get()))         + "ms\n"
                + (debug
                ? "§8    phases avg: snap " + String.format("%.1f", flushSnapshotAvgMs)
                + "  build " + String.format("%.1f", flushBuildAvgMs)
                + "  zstd " + String.format("%.1f", flushCompressAvgMs)
                + "  crc " + String.format("%.1f", flushChecksumAvgMs)
                + "  write " + String.format("%.1f", flushWriteAvgMs)
                + "  sync " + String.format("%.1f", flushSyncAvgMs)
                + "  rename " + String.format("%.1f", flushRenameAvgMs) + " ms\n"
                : "")
                + "§7§l  ── Compression ──§r\n"
                + "§7  Uncompressed: §f" + fmtSize(uncomp)
                + "  §7Compressed: §f" + fmtSize(comp)
                + "  §7Saved: §a" + String.format("%.1f%%", LinearStats.compressionPct(uncomp, comp)) + "\n"
                + "§7§l  ── Region Cache ──§r\n"
                + "§7  Linear: §f" + cHits + "§7/§f" + cMisses
                + "  §7Rate: §f" + String.format("%.1f%%", hitPct) + "\n"
                + "§7  Wrapper: §f" + wHits + "§7/§f" + wMisses
                + "  §7Rate: §f" + String.format("%.1f%%", wrapperHitPct) + "\n"
                + "§7  Resident reloads: §f" + reloads
                + "  §7Evictions: §f" + evictions + "\n"
                + "§8  Tip: /linearreader bench debug for phase timings. "
                + "/linearreader bench reset to start a fresh window.";

        ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }

    private static int executeBenchReset(CommandContext<CommandSourceStack> ctx) {
        LinearStats.enableAndReset();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "§6[LinearReader] Benchmark stats reset. Window started now."), false);
        return 1;
    }

    // ---------------------------------------------------------------------------
    // /linearreader afk-compress
    // ---------------------------------------------------------------------------
    private static int executeAfkCompressStatus(CommandContext<CommandSourceStack> ctx) {
        String status;
        if (IdleRecompressor.isRunning()) {
            String mode = IdleRecompressor.isManual() ? "manual" : "auto";
            status = "§a[LinearReader] Recompression running (" + mode + ", target: "
                    + IdleRecompressor.lastTargetDescription() + "). "
                    + "Scanned: §f" + IdleRecompressor.filesScanned()
                    + "§a  Upgraded: §f" + IdleRecompressor.filesRecompressed()
                    + "§a  Already optimal: §f" + IdleRecompressor.filesAlreadyOptimal()
                    + "§a  Skipped: §f" + IdleRecompressor.filesUnstableSkipped()
                    + "§a  Low-RAM pauses: §f" + IdleRecompressor.lowRamPauses()
                    + "§a  Saved: §f"    + fmtSize(IdleRecompressor.bytesSaved());
        } else {
            String autoStatus;
            if (!IdleRecompressor.isAutoEnabled()) {
                autoStatus = "§eauto disabled";
            } else {
                long idleRemainingMs = IdleRecompressor.idleRemainingMs();
                autoStatus = idleRemainingMs == 0L
                        ? "§aauto ready to start"
                        : "§7auto in §f" + fmtDuration(idleRemainingMs);
            }
            status = "§7[LinearReader] Recompression idle (" + autoStatus + "§7). "
                    + "§7Threshold: §f" + (IdleRecompressor.idleThresholdMs() / 60_000L) + "m"
                    + "§7  Min free RAM: §f" + LinearConfig.getRecompressMinFreeRamPercent() + "%"
                    + "§7. "
                    + "Use '§flinearreader afk-compress start§7' to run manually.";
        }
        ctx.getSource().sendSuccess(() -> Component.literal(status), false);
        return 1;
    }

    private static String fmtDuration(long millis) {
        long totalSeconds = Math.max(0L, millis / 1000L);
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0) return hours + "h " + minutes + "m";
        if (minutes > 0) return minutes + "m " + seconds + "s";
        return seconds + "s";
    }

    private static int executeAfkCompressStart(
            CommandContext<CommandSourceStack> ctx,
            CompressionAlgorithm.Algorithm algorithm,
            ServerLevel dimension) throws CommandSyntaxException {
        Path dimensionFilter = null;
        if (dimension != null) {
            Path regionFolder = LinearRuntime.regionFolderForDimension(dimension.dimension());
            dimensionFilter = regionFolder != null ? regionFolder.getParent() : null;
        }
        if (!IdleRecompressor.startManual(algorithm, dimensionFilter)) {
            ctx.getSource().sendFailure(Component.literal("[LinearReader] Recompression is already running."));
            return 0;
        }
        String algoLabel = algorithm == CompressionAlgorithm.Algorithm.BROTLI ? "Brotli" : "Zstd";
        String dimensionArg = rawArgumentText(ctx, "dimension");
        String scopeLabel = dimensionArg != null ? (" (" + dimensionArg + " only)") : " (all dimensions)";
        ctx.getSource().sendSuccess(() -> Component.literal(
                "§a[LinearReader] Background recompression started (" + algoLabel + ")" + scopeLabel
                        + ". Progress: /linearreader afk-compress"), false);
        return 1;
    }

    private static int executeAfkCompressStop(CommandContext<CommandSourceStack> ctx) {
        if (!IdleRecompressor.isRunning()) {
            ctx.getSource().sendFailure(Component.literal("[LinearReader] Recompression is not running."));
            return 0;
        }
        IdleRecompressor.stopManual();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "§e[LinearReader] Recompression stop requested."), false);
        return 1;
    }

    // ---------------------------------------------------------------------------
    // /linearreader pin / unpin / pins
    // ---------------------------------------------------------------------------
    private static Path resolveCurrentRegion(CommandContext<CommandSourceStack> ctx, int rx, int rz) {
        Path folder = LinearRuntime.regionFolderForDimension(ctx.getSource().getLevel().dimension());
        if (folder == null) return null;
        return folder.resolve("r." + rx + "." + rz + ".linear");
    }

    private static int[] currentRegionCoords(CommandContext<CommandSourceStack> ctx) {
        Vec3 pos = ctx.getSource().getPosition();
        int rx = ((int) Math.floor(pos.x) >> 4) >> 5;
        int rz = ((int) Math.floor(pos.z) >> 4) >> 5;
        return new int[]{rx, rz};
    }

    private static int executePinHere(CommandContext<CommandSourceStack> ctx) {
        int[] rc = currentRegionCoords(ctx); return doPinRegion(ctx, rc[0], rc[1], true);
    }
    private static int executePinCoords(CommandContext<CommandSourceStack> ctx) {
        return doPinRegion(ctx, IntegerArgumentType.getInteger(ctx, "rx"),
                IntegerArgumentType.getInteger(ctx, "rz"), true);
    }
    private static int executeUnpinHere(CommandContext<CommandSourceStack> ctx) {
        int[] rc = currentRegionCoords(ctx); return doPinRegion(ctx, rc[0], rc[1], false);
    }
    private static int executeUnpinCoords(CommandContext<CommandSourceStack> ctx) {
        return doPinRegion(ctx, IntegerArgumentType.getInteger(ctx, "rx"),
                IntegerArgumentType.getInteger(ctx, "rz"), false);
    }

    private static int doPinRegion(CommandContext<CommandSourceStack> ctx, int rx, int rz, boolean pin) {
        Path regionFile = resolveCurrentRegion(ctx, rx, rz);
        if (regionFile == null) {
            ctx.getSource().sendFailure(Component.literal("[LinearReader] Could not resolve region folder."));
            return 0;
        }
        if (pin) {
            LinearRuntime.pinRegion(regionFile);
            String msg = "§a[LinearReader] Pinned r." + rx + "." + rz + ".linear";
            ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
        } else {
            LinearRuntime.unpinRegion(regionFile);
            String msg = "§e[LinearReader] Unpinned r." + rx + "." + rz + ".linear";
            ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
        }
        return 1;
    }

    private static int executeListPins(CommandContext<CommandSourceStack> ctx) {
        Set<Path> pins = LinearRuntime.getPinnedPaths();
        if (pins.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("§7[LinearReader] No regions are pinned."), false);
            return 0;
        }
        StringBuilder sb = new StringBuilder("§6[LinearReader] Pinned regions (" + pins.size() + "):\n");
        pins.stream()
                .map(Path::getFileName).map(Path::toString).sorted()
                .forEach(name -> sb.append("§7  ").append(name).append('\n'));
        String msg = sb.toString().stripTrailing();
        ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
        return pins.size();
    }

    // ---------------------------------------------------------------------------
    // /linearreader export-mca
    // ---------------------------------------------------------------------------
    private static int executeExportStatus(CommandContext<CommandSourceStack> ctx) {
        if (!LinearExporter.isRunning()) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                            "§7[LinearReader] No export running. Use '§flinearreader export-mca start§7' to begin.\n"
                                    + "§8Output: <worldname>_mca_export/ — .linear files are never modified."),
                    false);
            return 0;
        }
        int done = LinearExporter.filesDone(), total = LinearExporter.filesTotal();
        int pct  = total == 0 ? 0 : done * 100 / total;
        final String statusMsg = "§a[LinearReader] Export running: §f" + done + "/" + total
                + " §a(" + pct + "%)"
                + (LinearExporter.filesFailed() > 0 ? "§c  " + LinearExporter.filesFailed() + " failed" : "");
        ctx.getSource().sendSuccess(() -> Component.literal(statusMsg), false);
        return 1;
    }

    private static int executeExportStart(CommandContext<CommandSourceStack> ctx) {
        if (LinearExporter.isRunning()) {
            ctx.getSource().sendFailure(Component.literal(
                    "[LinearReader] Export already running. Check /linearreader export-mca"));
            return 0;
        }
        Path worldRoot = LinearRuntime.getWorldRoot();
        if (worldRoot == null) {
            ctx.getSource().sendFailure(Component.literal("[LinearReader] World root not set."));
            return 0;
        }
        ctx.getSource().getServer().saveAllChunks(false, true, false);
        if (!LinearExporter.start(worldRoot)) {
            ctx.getSource().sendFailure(Component.literal("[LinearReader] Could not start export."));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
                        "§a[LinearReader] Export started. Output: §f<worldname>_mca_export/\n"
                                + "§7Progress: §f/linearreader export-mca"),
                false);
        return 1;
    }

    private static int executeExportStop(CommandContext<CommandSourceStack> ctx) {
        if (!LinearExporter.isRunning()) {
            ctx.getSource().sendFailure(Component.literal("[LinearReader] No export running."));
            return 0;
        }
        LinearExporter.stop();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "§e[LinearReader] Export stop requested. Already-exported files are kept."), false);
        return 1;
    }

    // -------------------------------------------------------------------------
    // /linearreader graph [stop | status | <stat> [stat2 ...] [duration:N|until-stopped] [interval:N] [single-graph]]
    // -------------------------------------------------------------------------

    /**
     * Parses and starts a new graph recording.
     *
     * <p>Positional argument order (space-separated, no key: prefixes):
     * <ol>
     *   <li><b>Duration</b> — a positive integer (seconds) or the literal
     *       {@code until-stopped} (records until {@code graph stop}).</li>
     *   <li><b>Interval</b> — a positive integer (seconds between samples).</li>
     *   <li><b>Mode</b> — {@code single-graph} or
     *       {@code multiple-graphs} (defaults to multiple-graphs).</li>
     *   <li><b>Stats</b> — one or more {@link GraphStat} keys.</li>
     * </ol>
     *
     * <p>Example: {@code /linearreader graph 300 10 single-graph chunk_read_avg_ms quietness_score}
     */
    private static int executeGraph(CommandContext<CommandSourceStack> ctx) {
        String rawArgs = StringArgumentType.getString(ctx, "args").trim();
        CommandSourceStack source = ctx.getSource();

        if (rawArgs.isEmpty()) {
            source.sendFailure(Component.literal(
                    "[LinearReader] Usage: /linearreader graph <duration|until-stopped> <interval> "
                            + "[single-graph|multiple-graphs] <stat> [stat2 ...]\n"
                            + "§7  Example: §f/linearreader graph 300 10 chunk_read_avg_ms quietness_score"));
            return 0;
        }

        String[] tokens = rawArgs.split("\\s+");
        int cursor = 0;

        // ── Token 1: duration ────────────────────────────────────────────────
        if (cursor >= tokens.length) {
            source.sendFailure(Component.literal(
                    "[LinearReader] Missing duration. Provide a number of seconds or 'until-stopped'."));
            return 0;
        }
        int durationSeconds;
        boolean untilStopped = tokens[cursor].equals("until-stopped");
        if (untilStopped) {
            durationSeconds = -1;
        } else {
            try {
                durationSeconds = Integer.parseInt(tokens[cursor]);
                if (durationSeconds <= 0) {
                    source.sendFailure(Component.literal(
                            "[LinearReader] Duration must be a positive number of seconds; got: "
                                    + tokens[cursor]));
                    return 0;
                }
            } catch (NumberFormatException e) {
                source.sendFailure(Component.literal(
                        "[LinearReader] Expected duration (number of seconds or 'until-stopped'), "
                                + "got: §f" + tokens[cursor]));
                return 0;
            }
        }
        cursor++;

        // ── Token 2: interval ────────────────────────────────────────────────
        if (cursor >= tokens.length) {
            source.sendFailure(Component.literal(
                    "[LinearReader] Missing interval. Provide a sample interval in seconds."));
            return 0;
        }
        int intervalSeconds;
        try {
            intervalSeconds = Integer.parseInt(tokens[cursor]);
            if (intervalSeconds <= 0) {
                source.sendFailure(Component.literal(
                        "[LinearReader] Interval must be a positive number of seconds; got: "
                                + tokens[cursor]));
                return 0;
            }
        } catch (NumberFormatException e) {
            source.sendFailure(Component.literal(
                    "[LinearReader] Expected interval (number of seconds), got: §f" + tokens[cursor]));
            return 0;
        }
        cursor++;

        // ── Token 3 (required): render mode ─────────────────────────────────
        if (cursor >= tokens.length) {
            source.sendFailure(Component.literal(
                    "[LinearReader] Missing render mode. Specify §fsingle-graph§c or §fmultiple-graphs§c."));
            return 0;
        }
        boolean singleGraph;
        if (tokens[cursor].equals("single-graph")) {
            singleGraph = true;
        } else if (tokens[cursor].equals("multiple-graphs")) {
            singleGraph = false;
        } else {
            source.sendFailure(Component.literal(
                    "[LinearReader] Expected 'single-graph' or 'multiple-graphs', got: §f"
                            + tokens[cursor]));
            return 0;
        }
        cursor++;

        // ── Remaining tokens: stat keys ──────────────────────────────────────
        if (cursor >= tokens.length) {
            source.sendFailure(Component.literal(
                    "[LinearReader] No stat keys provided. "
                            + "Example stats: §fchunk_read_avg_ms quietness_score pressure_score"));
            return 0;
        }
        Set<GraphStat> stats = new java.util.LinkedHashSet<>();
        List<String> unknownTokens = new ArrayList<>();
        while (cursor < tokens.length) {
            String token = tokens[cursor++];
            GraphStat stat = GraphStat.fromKey(token);
            if (stat != null) {
                stats.add(stat);
            } else {
                unknownTokens.add(token);
            }
        }
        if (!unknownTokens.isEmpty()) {
            source.sendFailure(Component.literal(
                    "[LinearReader] Unknown stat key(s): " + String.join(", ", unknownTokens)));
            return 0;
        }
        if (stats.isEmpty()) {
            source.sendFailure(Component.literal(
                    "[LinearReader] No valid stat keys found in the arguments."));
            return 0;
        }

        // ── Ensure LinearStats is enabled ────────────────────────────────────
        if (!LinearStats.isEnabled()) {
            LinearStats.enableAndReset();
            source.sendSuccess(() -> Component.literal(
                    "§e[LinearReader] bench stats were not active — enabled and reset the "
                            + "measurement window now."), false);
        }

        // ── Start sampler ────────────────────────────────────────────────────
        Path worldRoot = LinearRuntime.getWorldRoot();
        if (worldRoot == null) {
            source.sendFailure(Component.literal(
                    "[LinearReader] World root is not available — is the server fully started?"));
            return 0;
        }

        final int finalDuration = durationSeconds;
        final int finalInterval = intervalSeconds;
        final boolean finalSingle = singleGraph;
        final Set<GraphStat> finalStats = stats;

        boolean started = GraphSampler.start(
                finalStats, finalInterval, finalDuration, finalSingle, worldRoot,
                (outputPaths, error) -> {
                    if (error != null) {
                        source.sendSuccess(() -> Component.literal(
                                "§c[LinearReader] Graph recording failed: " + error), false);
                        return;
                    }
                    if (outputPaths.isEmpty()) {
                        source.sendSuccess(() -> Component.literal(
                                "§e[LinearReader] Graph recording finished but produced no data."), false);
                        return;
                    }
                    StringBuilder msg = new StringBuilder(
                            "§a[LinearReader] Graph recording complete — ")
                            .append(outputPaths.size()).append(" file(s):\n");
                    for (Path p : outputPaths) {
                        msg.append("§7  ").append(p.toAbsolutePath()).append('\n');
                    }
                    final String m = msg.toString().stripTrailing();
                    source.sendSuccess(() -> Component.literal(m), false);
                });

        if (!started) {
            source.sendFailure(Component.literal(
                    "[LinearReader] A graph recording is already running. "
                            + "Use §f/linearreader graph stop§c to stop it first."));
            return 0;
        }

        String statKeys = finalStats.stream().map(GraphStat::getKey)
                .collect(Collectors.joining(", "));
        String durationDesc = finalDuration < 0 ? "until stopped" : finalDuration + "s";
        source.sendSuccess(() -> Component.literal(
                "§6[LinearReader] Graph recording started.\n"
                        + "§7  Stats: §f" + statKeys + "\n"
                        + "§7  Interval: §f" + finalInterval + "s  "
                        + "§7Duration: §f" + durationDesc
                        + (finalSingle ? "  §7Mode: §fsingle SVG" : "  §7Mode: §fmultiple SVGs") + "\n"
                        + "§8  Warming up for " + GraphSampler.WARMUP_SECONDS + "s before first sample."), false);
        return 1;
    }

    private static int executeGraphStop(CommandContext<CommandSourceStack> ctx) {
        if (!GraphSampler.isRunning()) {
            ctx.getSource().sendFailure(Component.literal(
                    "[LinearReader] No graph recording is currently running."));
            return 0;
        }
        GraphSampler.stop();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "§e[LinearReader] Graph stop requested — rendering collected samples now."), false);
        return 1;
    }

    private static int executeGraphStatus(CommandContext<CommandSourceStack> ctx) {
        if (!GraphSampler.isRunning()) {
            List<java.nio.file.Path> last = GraphSampler.getLastOutputPaths();
            String lastRun = last.isEmpty()
                    ? "§8(no completed recordings this session)"
                    : "§7  Last output: §f" + last.get(0).getParent();
            String lastErr = GraphSampler.lastError().isEmpty()
                    ? ""
                    : "\n§c  Last error: " + GraphSampler.lastError();
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "§7[LinearReader] No graph recording is running.\n"
                            + lastRun + lastErr), false);
            return 0;
        }

        // Running — show warmup or recording state.
        if (GraphSampler.isWarmingUp()) {
            double warmupRemaining = GraphSampler.warmupRemainingSeconds();
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "§e[LinearReader] Graph recording: warming up — "
                            + String.format("%.0fs", warmupRemaining)
                            + "s until first sample.\n"
                            + "§7  Stats: §f"
                            + GraphSampler.getActiveStats().stream()
                            .map(GraphStat::getKey)
                            .collect(Collectors.joining(", "))), false);
            return 1;
        }

        Set<GraphStat> active = GraphSampler.getActiveStats();
        int collected = GraphSampler.samplesCollected();
        double elapsed = GraphSampler.elapsedSeconds();
        int duration = GraphSampler.getDurationSeconds();
        int interval = GraphSampler.getIntervalSeconds();

        String remainingStr = duration < 0
                ? "until-stopped"
                : String.format("%.0fs remaining", Math.max(0, duration - elapsed));

        String statKeys = active.stream()
                .map(GraphStat::getKey)
                .collect(Collectors.joining(", "));

        ctx.getSource().sendSuccess(() -> Component.literal(
                "§6[LinearReader] Graph recording in progress.\n"
                        + "§7  Stats: §f" + statKeys + "\n"
                        + "§7  Samples: §f" + collected
                        + "  §7Elapsed: §f" + String.format("%.0fs", elapsed)
                        + "  §7Interval: §f" + interval + "s\n"
                        + "§7  " + remainingStr), false);
        return 1;
    }

    /**
     * Suggestion provider for the greedy {@code args} argument.
     *
     * Positional slot awareness:
     * <ul>
     *   <li>Slot 0 — duration: suggests common durations and {@code until-stopped}.</li>
     *   <li>Slot 1 — interval: suggests common intervals.</li>
     *   <li>Slot 2 — render mode: suggests {@code single-graph} and
     *       {@code multiple-graphs} exclusively (no stat keys).</li>
     *   <li>Slot 3+ — stat keys: suggests remaining {@link GraphStat} keys,
     *       filtering out ones already typed.</li>
     * </ul>
     */
    private static CompletableFuture<Suggestions> suggestGraphArgs(
            CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {

        String remaining = builder.getRemaining();
        String[] parts = remaining.split(" ", -1);
        int committed = parts.length - 1;
        String current = parts[parts.length - 1];

        int currentTokenStart = builder.getStart() + remaining.length() - current.length();
        SuggestionsBuilder sub = builder.createOffset(currentTokenStart);

        // Determine how many positional slots have been filled.
        boolean hasDuration = committed >= 1;
        boolean hasInterval = committed >= 2;
        boolean hasMode     = committed >= 3;
        // Stat keys start at committed token index 3.
        Set<String> usedStatKeys = new java.util.HashSet<>();
        for (int i = 3; i < committed; i++) {
            usedStatKeys.add(parts[i]);
        }

        if (!hasDuration) {
            // Slot 0: duration.
            Stream.of("30", "60", "120", "300", "600", "until-stopped")
                    .filter(s -> s.startsWith(current))
                    .forEach(sub::suggest);
            return sub.buildFuture();
        }

        if (!hasInterval) {
            // Slot 1: interval.
            Stream.of("1", "5", "10", "30", "60")
                    .filter(s -> s.startsWith(current))
                    .forEach(sub::suggest);
            return sub.buildFuture();
        }

        if (!hasMode) {
            // Slot 2: render mode only — no stat keys yet.
            Stream.of("single-graph", "multiple-graphs")
                    .filter(s -> s.startsWith(current))
                    .forEach(sub::suggest);
            return sub.buildFuture();
        }

        // Slot 3+: stat keys only.
        for (GraphStat stat : GraphStat.values()) {
            String key = stat.getKey();
            if (!usedStatKeys.contains(key) && key.startsWith(current)) {
                sub.suggest(key, net.minecraft.network.chat.Component.literal(stat.getLabel()));
            }
        }
        return sub.buildFuture();
    }

    // ---------------------------------------------------------------------------
    // Shared formatting helpers
    // ---------------------------------------------------------------------------
    private static String fmtSize(long bytes) {
        if (bytes >= 1024L * 1024L * 1024L)
            return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
        if (bytes >= 1024L * 1024L)
            return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.2f KB", bytes / 1024.0);
    }

    private static String fmtPercent(double value) {
        return String.format("%.0f%%", Math.max(0.0D, value) * 100.0D);
    }

    private static String fmtScore(double value) {
        return String.format("%.2f", Math.max(0.0D, value));
    }

    private static String fmtSince(long timestampMs) {
        if (timestampMs <= 0L) {
            return "never";
        }
        return fmtDuration(Math.max(0L, System.currentTimeMillis() - timestampMs)) + " ago";
    }

    private static String fmtUptime(double s) {
        if (s < 60)   return String.format("%.0fs", s);
        if (s < 3600) return String.format("%.0fm %.0fs", s / 60, s % 60);
        return String.format("%.0fh %.0fm", s / 3600, (s % 3600) / 60);
    }
}
