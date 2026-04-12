package com.bugfunbug.linearreader.command;

import com.bugfunbug.linearreader.LinearReader;
import com.bugfunbug.linearreader.LinearStats;
import com.bugfunbug.linearreader.config.LinearConfig;
import com.bugfunbug.linearreader.linear.IdleRecompressor;
import com.bugfunbug.linearreader.linear.LinearExporter;
import com.bugfunbug.linearreader.linear.LinearRegionFile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.RegisterCommandsEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public class LinearCommand {

    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        dispatcher.register(
                Commands.literal("linearreader")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.literal("cache_info")
                                .executes(LinearCommand::executeInfo))
                        .then(Commands.literal("storage")
                                .executes(LinearCommand::executeStorage))
                        .then(Commands.literal("pos")
                                .executes(LinearCommand::executePos))
                        .then(Commands.literal("verify")
                                .executes(LinearCommand::executeVerify))
                        .then(Commands.literal("prune-chunks")
                                .executes(LinearCommand::executePruneChunks)
                                .then(Commands.literal("confirm")
                                        .executes(LinearCommand::executePruneChunksConfirm)
                                        .then(Commands.argument("token", StringArgumentType.word())
                                                .executes(LinearCommand::executePruneChunksConfirm))))
                        .then(Commands.literal("sync-backups")
                                .executes(LinearCommand::executeSyncBackups)
                                .then(Commands.literal("confirm")
                                        .executes(LinearCommand::executeSyncBackupsConfirm)))
                        .then(Commands.literal("bench")
                                .executes(LinearCommand::executeBench)
                                .then(Commands.literal("reset")
                                        .executes(LinearCommand::executeBenchReset)))
                        .then(Commands.literal("afk-compress")
                                .executes(LinearCommand::executeAfkCompressStatus)
                                .then(Commands.literal("start")
                                        .executes(LinearCommand::executeAfkCompressStart))
                                .then(Commands.literal("stop")
                                        .executes(LinearCommand::executeAfkCompressStop)))
                        .then(Commands.literal("pin")
                                .executes(LinearCommand::executePinHere)
                                .then(Commands.argument("rx", IntegerArgumentType.integer())
                                        .then(Commands.argument("rz", IntegerArgumentType.integer())
                                                .executes(LinearCommand::executePinCoords))))
                        .then(Commands.literal("unpin")
                                .executes(LinearCommand::executeUnpinHere)
                                .then(Commands.argument("rx", IntegerArgumentType.integer())
                                        .then(Commands.argument("rz", IntegerArgumentType.integer())
                                                .executes(LinearCommand::executeUnpinCoords))))
                        .then(Commands.literal("pins")
                                .executes(LinearCommand::executeListPins))
                        .then(Commands.literal("export-mca")
                                .executes(LinearCommand::executeExportStatus)
                                .then(Commands.literal("start")
                                        .executes(LinearCommand::executeExportStart))
                                .then(Commands.literal("stop")
                                        .executes(LinearCommand::executeExportStop)))
        );
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

        String fileName = "r." + regionX + "." + regionZ + ".linear";
        boolean isOpen  = LinearRegionFile.ALL_OPEN.stream()
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
    // /linearreader info
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
    // Note: does a full filesystem walk; use sparingly on large worlds.
    // ---------------------------------------------------------------------------
    private static int executeStorage(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server    = ctx.getSource().getServer();
        Path            worldRoot = server.getWorldPath(LevelResource.ROOT);

        // plain long[] is sufficient — Files.walk returns a sequential stream,
        // so there is no concurrent mutation and AtomicLong overhead is unnecessary.
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
    // /linearreader verify
    //
    // Scans every .linear file in the world folder, checking header/footer
    // signatures and CRC32 (where present). Runs off the server thread so it
    // never hitches gameplay. Progress and results are sent back via chat.
    // ---------------------------------------------------------------------------
    private static int executeVerify(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer    server    = ctx.getSource().getServer();
        Path               worldRoot = server.getWorldPath(LevelResource.ROOT);
        CommandSourceStack source    = ctx.getSource();

        source.sendSuccess(() -> Component.literal(
                "§6[LinearReader] Starting region verification \u2014 results will appear here."), false);

        Thread verifyThread = new Thread(() -> {
            List<Path> allFiles = new ArrayList<>();
            try (Stream<Path> stream = Files.walk(worldRoot)) {
                stream.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".linear"))
                        .forEach(allFiles::add);
            } catch (IOException e) {
                sendFromThread(source, "§c[LinearReader] Verify failed \u2014 could not walk world: " + e.getMessage());
                return;
            }

            if (allFiles.isEmpty()) {
                sendFromThread(source, "§e[LinearReader] No .linear files found to verify.");
                return;
            }

            int total  = allFiles.size();
            int ok     = 0;
            int failed = 0;
            int noCRC  = 0;

            for (Path file : allFiles) {
                LinearRegionFile.VerifyResult result = LinearRegionFile.verifyOnDisk(file);
                if (result.ok) {
                    ok++;
                    if (!result.hasCRC) noCRC++;
                } else {
                    failed++;
                    // sendSuccess goes to RCON chat only; LOGGER goes to server log
                    // so test harnesses can grep for it.
                    String reason = file.getFileName() + " — " + result.reason;
                    LinearReader.LOGGER.warn("[LinearReader] CORRUPT: {}", reason);
                    final String failMsg = "§c[LinearReader] CORRUPT: " + reason;
                    sendFromThread(source, failMsg);
                }
            }

            // Update summary line:
            final int noCRCFinal = noCRC;
            final String summary = "§6[LinearReader] Verify complete: §f" + total
                    + "§6 files scanned - "
                    + "§a" + ok + " OK"
                    + (failed  > 0 ? "§c, " + failed + " CORRUPT" : "")
                    + (noCRCFinal > 0 ? "§e, " + noCRCFinal + " without checksum (Python-written or pre-CRC)" : "")
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
    //
    // Displays a live performance snapshot:
    //   - Chunk reads / writes: count, throughput, avg/min/max NBT parse time
    //   - Region loads / flushes: count, avg/min/max total time (decompress+disk)
    //   - Compression: total bytes in/out, ratio saved
    //   - Cache: hits, misses, hit-rate %
    //   - Uptime since last reset
    //
    // All stats are always-on; this command just reads them.
    // Use "/linearreader bench reset" to start a fresh measurement window.
    // ---------------------------------------------------------------------------
    private static int executeBench(CommandContext<CommandSourceStack> ctx) {
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

        double uptime = LinearStats.uptimeSeconds();
        double hitPct = cTotal == 0 ? 0.0 : cHits * 100.0 / cTotal;
        double wrapperHitPct = wTotal == 0 ? 0.0 : wHits * 100.0 / wTotal;

        // throughput (ops per second over the measurement window)
        double readTps  = uptime > 0 ? cReads  / uptime : 0;
        double writeTps = uptime > 0 ? cWrites / uptime : 0;

        String msg = "§6[LinearReader] Benchmark Report§8 (window: "
                + fmtUptime(uptime) + ")\n"
                + "§7§l  \u2500\u2500 Chunk I/O \u2500\u2500§r\n"
                + "§7  Reads  : §f" + cReads
                + "§7  (" + String.format("%.1f", readTps) + "/s)"
                + "  avg §f" + String.format("%.3f", LinearStats.avgMs(s.chunkReadNs.sum(), cReads)) + "ms"
                + "  min §f" + String.format("%.3f", LinearStats.toMs(s.minChunkReadNs.get())) + "ms"
                + "  max §f" + String.format("%.3f", LinearStats.toMs(s.maxChunkReadNs.get())) + "ms\n"
                + "§7  Writes : §f" + cWrites
                + "§7  (" + String.format("%.1f", writeTps) + "/s)"
                + "  avg §f" + String.format("%.3f", LinearStats.avgMs(s.chunkWriteNs.sum(), cWrites)) + "ms"
                + "  min §f" + String.format("%.3f", LinearStats.toMs(s.minChunkWriteNs.get())) + "ms"
                + "  max §f" + String.format("%.3f", LinearStats.toMs(s.maxChunkWriteNs.get())) + "ms\n"
                + "§7§l  \u2500\u2500 Region I/O \u2500\u2500§r\n"
                + "§7  Loads  : §f" + rLoads
                + "  avg §f" + String.format("%.1f", LinearStats.avgMs(s.regionLoadNs.sum(), rLoads)) + "ms"
                + "  min §f" + String.format("%.1f", LinearStats.toMs(s.minRegionLoadNs.get())) + "ms"
                + "  max §f" + String.format("%.1f", LinearStats.toMs(s.maxRegionLoadNs.get())) + "ms\n"
                + "§7  Flushes: §f" + rFlushes
                + "  avg §f" + String.format("%.1f", LinearStats.avgMs(s.regionFlushNs.sum(), rFlushes)) + "ms"
                + "  min §f" + String.format("%.1f", LinearStats.toMs(s.minRegionFlushNs.get())) + "ms"
                + "  max §f" + String.format("%.1f", LinearStats.toMs(s.maxRegionFlushNs.get())) + "ms\n"
                + "§7§l  \u2500\u2500 Compression \u2500\u2500§r\n"
                + "§7  Uncompressed: §f" + fmtSize(uncomp)
                + "  §7Compressed: §f" + fmtSize(comp)
                + "  §7Saved: §a" + String.format("%.1f%%", LinearStats.compressionPct(uncomp, comp)) + "\n"
                + "§7§l  \u2500\u2500 Region Cache \u2500\u2500§r\n"
                + "§7  Linear: §f" + cHits
                + "§7/§f" + cMisses
                + "  §7Rate: §f" + String.format("%.1f%%", hitPct) + "\n"
                + "§7  Wrapper: §f" + wHits
                + "§7/§f" + wMisses
                + "  §7Rate: §f" + String.format("%.1f%%", wrapperHitPct) + "\n"
                + "§7  Resident reloads: §f" + reloads
                + "  §7Evictions: §f" + evictions + "\n"
                + "§8  Tip: /linearreader bench reset to start a fresh window.";

        ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }

    private static int executeBenchReset(CommandContext<CommandSourceStack> ctx) {
        LinearStats.reset();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "§6[LinearReader] Benchmark stats reset. Window started now."), false);
        return 1;
    }

    private static String fmtUptime(double seconds) {
        if (seconds < 60)   return String.format("%.0fs", seconds);
        if (seconds < 3600) return String.format("%.0fm %.0fs", seconds / 60, seconds % 60);
        return String.format("%.0fh %.0fm", seconds / 3600, (seconds % 3600) / 60);
    }

    // ---------------------------------------------------------------------------
// /linearreader afk-compress
// ---------------------------------------------------------------------------

    private static int executeAfkCompressStatus(CommandContext<CommandSourceStack> ctx) {
        String status;
        if (IdleRecompressor.isRunning()) {
            String mode = IdleRecompressor.isManual() ? "manual" : "auto";
            status = "§a[LinearReader] Recompression running (" + mode + "). " +
                    "Scanned: §f" + IdleRecompressor.filesScanned() +
                    "§a  Upgraded: §f" + IdleRecompressor.filesRecompressed() +
                    "§a  Already 22: §f" + IdleRecompressor.filesAlreadyOptimal() +
                    "§a  Skipped: §f" + IdleRecompressor.filesUnstableSkipped() +
                    "§a  Low-RAM pauses: §f" + IdleRecompressor.lowRamPauses() +
                    "§a  Saved: §f" + fmtSize(IdleRecompressor.bytesSaved());
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
            status = "§7[LinearReader] Recompression idle (" + autoStatus + "§7). " +
                    "§7Threshold: §f" + (IdleRecompressor.idleThresholdMs() / 60_000L) + "m" +
                    "§7  Min free RAM: §f" + LinearConfig.getRecompressMinFreeRamPercent() + "%" +
                    "§7. " +
                    "Use '§flinearreader afk-compress start§7' to run manually.";
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

    private static int executeAfkCompressStart(CommandContext<CommandSourceStack> ctx) {
        if (!IdleRecompressor.startManual()) {
            ctx.getSource().sendFailure(Component.literal(
                    "[LinearReader] Recompression is already running."));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
                "§a[LinearReader] Background recompression started. " +
                        "Progress: /linearreader afk-compress"), false);
        return 1;
    }

    private static int executeAfkCompressStop(CommandContext<CommandSourceStack> ctx) {
        if (!IdleRecompressor.isRunning()) {
            ctx.getSource().sendFailure(Component.literal(
                    "[LinearReader] Recompression is not running."));
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
        Path folder = LinearReader.regionFolderForDimension(
                ctx.getSource().getLevel().dimension());
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
        int[] rc = currentRegionCoords(ctx);
        return doPinRegion(ctx, rc[0], rc[1], true);
    }

    private static int executePinCoords(CommandContext<CommandSourceStack> ctx) {
        int rx = IntegerArgumentType.getInteger(ctx, "rx");
        int rz = IntegerArgumentType.getInteger(ctx, "rz");
        return doPinRegion(ctx, rx, rz, true);
    }

    private static int executeUnpinHere(CommandContext<CommandSourceStack> ctx) {
        int[] rc = currentRegionCoords(ctx);
        return doPinRegion(ctx, rc[0], rc[1], false);
    }

    private static int executeUnpinCoords(CommandContext<CommandSourceStack> ctx) {
        int rx = IntegerArgumentType.getInteger(ctx, "rx");
        int rz = IntegerArgumentType.getInteger(ctx, "rz");
        return doPinRegion(ctx, rx, rz, false);
    }

    private static int doPinRegion(CommandContext<CommandSourceStack> ctx,
                                   int rx, int rz, boolean pin) {
        Path regionFile = resolveCurrentRegion(ctx, rx, rz);
        if (regionFile == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "[LinearReader] Could not resolve region folder for this dimension."));
            return 0;
        }
        if (pin) {
            LinearReader.pinRegion(regionFile);
            String msg = "§a[LinearReader] Pinned r." + rx + "." + rz + ".linear - " +
                    "this region will never be evicted from cache.";
            ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
        } else {
            LinearReader.unpinRegion(regionFile);
            String msg = "§e[LinearReader] Unpinned r." + rx + "." + rz + ".linear.";
            ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
        }
        return 1;
    }

    private static int executeListPins(CommandContext<CommandSourceStack> ctx) {
        java.util.Set<Path> pins = LinearReader.getPinnedPaths();
        if (pins.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "§7[LinearReader] No regions are pinned."), false);
            return 0;
        }
        StringBuilder sb = new StringBuilder("§6[LinearReader] Pinned regions (" + pins.size() + "):\n");
        pins.stream()
                .map(Path::getFileName)
                .map(Path::toString)
                .sorted()
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
                            "§7[LinearReader] No export running. " +
                                    "Use '§flinearreader export-mca start§7' to begin.\n" +
                                    "§8Output will be written to <worldname>_mca_export/ " +
                                    "next to your world folder. Your .linear files are never modified."),
                    false);
            return 0;
        }
        int done  = LinearExporter.filesDone();
        int total = LinearExporter.filesTotal();
        int pct   = total == 0 ? 0 : (done * 100 / total);
        final String statusMsg = "§a[LinearReader] Export running: §f"
                + done + "/" + total + " §a(" + pct + "%)"
                + (LinearExporter.filesFailed() > 0
                ? "§c  " + LinearExporter.filesFailed() + " failed"
                : "");
        ctx.getSource().sendSuccess(() -> Component.literal(statusMsg), false);
        return 1;
    }

    private static int executeExportStart(CommandContext<CommandSourceStack> ctx) {
        if (LinearExporter.isRunning()) {
            ctx.getSource().sendFailure(Component.literal(
                    "[LinearReader] Export already running. " +
                            "Check progress with /linearreader export-mca"));
            return 0;
        }

        Path worldRoot = LinearReader.worldRoot;
        if (worldRoot == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "[LinearReader] World root not set — is the server fully started?"));
            return 0;
        }

        // Force a save-all before exporting so the .linear files on disk are current.
        ctx.getSource().getServer().saveAllChunks(false, true, false);

        boolean started = LinearExporter.start(worldRoot);
        if (!started) {
            ctx.getSource().sendFailure(Component.literal(
                    "[LinearReader] Could not start export (already running)."));
            return 0;
        }

        ctx.getSource().sendSuccess(() -> Component.literal(
                        "§a[LinearReader] Export started in the background.\n" +
                                "§7Output: §f<worldname>_mca_export/§7 next to your world folder.\n" +
                                "§7Your .linear files are NOT modified. " +
                                "Progress: §f/linearreader export-mca"),
                false);
        return 1;
    }

    private static int executeExportStop(CommandContext<CommandSourceStack> ctx) {
        if (!LinearExporter.isRunning()) {
            ctx.getSource().sendFailure(Component.literal(
                    "[LinearReader] No export is currently running."));
            return 0;
        }
        LinearExporter.stop();
        ctx.getSource().sendSuccess(() -> Component.literal(
                        "§e[LinearReader] Export stop requested. " +
                                "Already-exported files are kept and the export can be resumed."),
                false);
        return 1;
    }

    // ---------------------------------------------------------------------------
    // Shared utilities
    // ---------------------------------------------------------------------------

    private static String fmtSize(long bytes) {
        if (bytes >= 1024L * 1024L * 1024L)
            return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
        if (bytes >= 1024L * 1024L)
            return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.2f KB", bytes / 1024.0);
    }
}
