package com.bugfunbug.linearreader.command;

import com.bugfunbug.linearreader.LinearRuntime;
import com.bugfunbug.linearreader.linear.LinearRegionFile;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Implements /linearreader save-all [zstdLevel].
 *
 * Forces an immediate, blocking flush of every open dirty region file,
 * bypassing the normal per-tick flush budget entirely - unlike a regular
 * world save, this flushes everything in one pass and only reports success
 * once every region has actually finished writing.
 *
 * zstdLevel is optional. When omitted, each region flushes at whatever
 * LinearRuntime.currentLiveCompressionLevel() (the normal adaptive live-write
 * level) currently resolves to - identical to a normal flush. When given
 * (1-22), every region in this pass is forced to that exact zstd level for
 * this flush only; it does not change any persistent config.
 */
public final class SaveAllRunner {

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private SaveAllRunner() {}

    public static int start(CommandSourceStack source, Integer zstdLevel) {
        if (!RUNNING.compareAndSet(false, true)) {
            source.sendFailure(Component.literal(
                    "[LinearReader] save-all is already running."));
            return 0;
        }

        String levelLabel = zstdLevel != null
                ? ("zstd level " + zstdLevel)
                : "the normal live-write level";
        source.sendSuccess(() -> Component.literal(
                "§6[LinearReader] save-all starting - flushing every open region at " + levelLabel + "..."), false);

        Thread worker = new Thread(() -> {
            try {
                run(source, zstdLevel);
            } finally {
                RUNNING.set(false);
            }
        }, "linearreader-save-all");
        worker.setDaemon(true);
        worker.start();
        return 1;
    }

    private static void run(CommandSourceStack source, Integer zstdLevel) {
        List<LinearRegionFile> dirtyRegions = new ArrayList<>();
        for (LinearRegionFile region : LinearRegionFile.ALL_OPEN) {
            if (region.isDirty()) {
                dirtyRegions.add(region);
            }
        }

        if (dirtyRegions.isEmpty()) {
            send(source, "§7[LinearReader] save-all found no dirty regions - nothing to flush.");
            return;
        }

        for (LinearRegionFile region : dirtyRegions) {
            LinearRuntime.LOGGER.info("[LinearReader] save-all flushing r.{}.{}.linear",
                    region.regionX, region.regionZ);
        }

        try {
            LinearRuntime.flushRegionsBlocking(dirtyRegions, zstdLevel);
        } catch (IOException e) {
            LinearRuntime.LOGGER.error("[LinearReader] save-all failed: {}", e.getMessage(), e);
            send(source, "§c[LinearReader] save-all failed: " + e.getMessage());
            return;
        }

        String levelLabel = zstdLevel != null ? ("zstd level " + zstdLevel) : "the normal live-write level";
        send(source, "§a[LinearReader] save-all complete - " + dirtyRegions.size()
                + " region(s) flushed at " + levelLabel + ".");
    }

    private static void send(CommandSourceStack source, String msg) {
        source.sendSuccess(() -> Component.literal(msg), false);
    }
}