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
 * Implements /linearreader clear-cache.
 *
 * Frees the resident (in-RAM) chunk data for every open region file without
 * losing any data: dirty regions are flushed to disk first (blocking), then
 * every region's resident chunk bytes are released the same safe way the
 * automatic resident-trim pass already does — pinned/dirty/flushing regions
 * are simply skipped, never forced.
 *
 * Region objects stay in LinearRegionFile.ALL_OPEN and in Minecraft's own
 * RegionFileStorage caches; the next chunk access to a cleared region just
 * reloads lazily from disk, exactly like any other cold cache entry.
 */
public final class ClearCacheRunner {

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private ClearCacheRunner() {}

    public static int start(CommandSourceStack source) {
        if (!RUNNING.compareAndSet(false, true)) {
            source.sendFailure(Component.literal(
                    "[LinearReader] clear-cache is already running."));
            return 0;
        }

        source.sendSuccess(() -> Component.literal(
                "§6[LinearReader] clear-cache starting - flushing dirty regions, then freeing resident RAM..."), false);

        Thread worker = new Thread(() -> {
            try {
                run(source);
            } finally {
                RUNNING.set(false);
            }
        }, "linearreader-clear-cache");
        worker.setDaemon(true);
        worker.start();
        return 1;
    }

    private static void run(CommandSourceStack source) {
        List<LinearRegionFile> snapshot = new ArrayList<>(LinearRegionFile.ALL_OPEN);

        List<LinearRegionFile> dirtyRegions = new ArrayList<>();
        for (LinearRegionFile region : snapshot) {
            if (region.isDirty()) {
                dirtyRegions.add(region);
            }
        }

        if (!dirtyRegions.isEmpty()) {
            try {
                LinearRuntime.flushRegionsBlocking(dirtyRegions);
            } catch (IOException e) {
                LinearRuntime.LOGGER.error("[LinearReader] clear-cache failed while flushing: {}", e.getMessage(), e);
                send(source, "§c[LinearReader] clear-cache failed while flushing dirty regions: " + e.getMessage());
                return;
            }
        }

        int freedRegions = 0;
        long freedBytes = 0L;
        int skipped = 0;
        for (LinearRegionFile region : snapshot) {
            // Re-checked internally (dirty/flushing/pinned) - a region that got
            // touched again between the flush above and here is simply skipped,
            // never forced. That's the same tolerance the automatic trim pass has.
            long freed = region.releaseResidentDataIfPossible();
            if (freed > 0L) {
                freedRegions++;
                freedBytes += freed;
            } else {
                skipped++;
            }
        }

        send(source, "§a[LinearReader] clear-cache complete - flushed §f" + dirtyRegions.size()
                + "§a dirty region(s), freed resident RAM for §f" + freedRegions
                + "§a region(s) (" + formatBytes(freedBytes) + ")"
                + (skipped > 0 ? " §7(" + skipped + " region(s) skipped - pinned/dirty/flushing)" : ""));
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1000L) return bytes + " B";
        double value = bytes;
        String[] units = {"KB", "MB", "GB", "TB"};
        int unitIndex = -1;
        do {
            value /= 1000.0;
            unitIndex++;
        } while (value >= 1000.0 && unitIndex < units.length - 1);
        return String.format(java.util.Locale.ROOT, "%.2f %s", value, units[unitIndex]);
    }

    private static void send(CommandSourceStack source, String msg) {
        source.sendSuccess(() -> Component.literal(msg), false);
    }
}