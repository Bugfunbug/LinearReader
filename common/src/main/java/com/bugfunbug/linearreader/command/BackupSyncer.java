package com.bugfunbug.linearreader.command;

import com.bugfunbug.linearreader.LinearRuntime;
import com.bugfunbug.linearreader.config.LinearConfig;
import com.bugfunbug.linearreader.linear.LinearRegionFile;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * Reconciles existing .linear.bak files with the current world state.
 * This intentionally requires a confirm step because it overwrites or deletes restore points.
 */
public final class BackupSyncer {

    private static final int MAX_CHAT_FILES = 12;
    private static final AtomicReference<PendingSync> PENDING = new AtomicReference<>();
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private BackupSyncer() {}

    public static int startDryRun(CommandSourceStack source) {
        if (!RUNNING.compareAndSet(false, true)) {
            source.sendFailure(net.minecraft.network.chat.Component.literal(
                    "[LinearReader] Backup sync is already running."));
            return 0;
        }

        MinecraftServer server = source.getServer();
        Path worldRoot = LinearRuntime.resolveWorldRoot(server);
        source.sendSuccess(() -> net.minecraft.network.chat.Component.literal(
                "§6[LinearReader] Starting backup sync analysis. "
                        + "This is a dry run; no backups will be changed yet."), false);

        Thread worker = new Thread(() -> {
            try {
                runDryRun(source, worldRoot);
            } finally {
                RUNNING.set(false);
            }
        }, "linearreader-backup-sync-analyze");
        worker.setDaemon(true);
        worker.start();
        return 1;
    }

    public static int confirm(CommandSourceStack source) {
        PendingSync pending = PENDING.get();
        if (pending == null) {
            source.sendFailure(net.minecraft.network.chat.Component.literal(
                    "[LinearReader] No pending backup sync. Run /linearreader sync-backups first."));
            return 0;
        }
        if (System.currentTimeMillis() > pending.expiresAtMs()) {
            PENDING.compareAndSet(pending, null);
            source.sendFailure(net.minecraft.network.chat.Component.literal(
                    "[LinearReader] The backup sync confirmation window expired. Run the analysis again."));
            return 0;
        }
        if (!RUNNING.compareAndSet(false, true)) {
            source.sendFailure(net.minecraft.network.chat.Component.literal(
                    "[LinearReader] Backup sync is already running."));
            return 0;
        }

        source.sendSuccess(() -> net.minecraft.network.chat.Component.literal(
                "§6[LinearReader] Starting backup sync. Existing .bak files may now be overwritten."), false);

        Thread worker = new Thread(() -> {
            try {
                runConfirm(source, pending);
            } finally {
                RUNNING.set(false);
            }
        }, "linearreader-backup-sync-confirm");
        worker.setDaemon(true);
        worker.start();
        return 1;
    }

    private static void runDryRun(CommandSourceStack source, Path worldRoot) {
        List<BackupPlan> plans = new ArrayList<>();
        int skippedBusyFiles = 0;
        int liveWithoutBackupCount = 0;

        for (Path linearPath : findLinearFiles(worldRoot)) {
            Path normalized = linearPath.toAbsolutePath().normalize();
            LinearRegionFile openRegion = findOpenRegion(normalized);
            if (isBusy(openRegion)) {
                skippedBusyFiles++;
                continue;
            }

            Path backupPath = LinearRegionFile.backupPathFor(linearPath);
            boolean existed = Files.exists(backupPath);
            if (!existed) {
                liveWithoutBackupCount++;
                continue;
            }

            long oldBackupSize;
            try {
                oldBackupSize = Files.size(backupPath);
            } catch (IOException e) {
                LinearRuntime.LOGGER.warn("[LinearReader] backup sync skipped {}: {}",
                        worldRoot.relativize(linearPath), e.getMessage());
                continue;
            }

            plans.add(new BackupPlan(
                    SyncAction.REFRESH,
                    linearPath,
                    normalized,
                    backupPath,
                    worldRoot.relativize(linearPath).toString(),
                    oldBackupSize
            ));
        }

        for (Path backupPath : findBackupFiles(worldRoot)) {
            Path linearPath = livePathForBackup(backupPath);
            if (Files.exists(linearPath)) continue;

            long oldBackupSize;
            try {
                oldBackupSize = Files.size(backupPath);
            } catch (IOException e) {
                LinearRuntime.LOGGER.warn("[LinearReader] backup sync skipped orphan {}: {}",
                        worldRoot.relativize(backupPath), e.getMessage());
                continue;
            }

            plans.add(new BackupPlan(
                    SyncAction.DELETE_ORPHAN,
                    linearPath,
                    linearPath.toAbsolutePath().normalize(),
                    backupPath,
                    worldRoot.relativize(backupPath).toString(),
                    oldBackupSize
            ));
        }

        plans.sort(Comparator.comparing(BackupPlan::relativePath));

        if (plans.isEmpty()) {
            PENDING.set(null);
            send(source, "§7[LinearReader] No existing backups needed reconciliation."
                    + (liveWithoutBackupCount > 0 ? " §8(" + liveWithoutBackupCount + " live file(s) without backups left alone)" : "")
                    + (skippedBusyFiles > 0 ? " §8(" + skippedBusyFiles + " busy file(s) skipped)" : ""));
            return;
        }

        int refreshCount = 0;
        int orphanDeleteCount = 0;
        for (BackupPlan plan : plans) {
            if (plan.action() == SyncAction.REFRESH) refreshCount++;
            else orphanDeleteCount++;
        }

        PendingSync pending = new PendingSync(
                System.currentTimeMillis() + LinearConfig.getConfirmWindowMs(),
                worldRoot,
                List.copyOf(plans),
                skippedBusyFiles,
                liveWithoutBackupCount,
                refreshCount,
                orphanDeleteCount
        );
        PENDING.set(pending);

        StringBuilder msg = new StringBuilder()
                .append("§6[LinearReader] backup sync dry run complete.\n")
                .append("§7  Existing backups to refresh: §f").append(refreshCount).append('\n')
                .append("§7  Orphan backups to delete: §f").append(orphanDeleteCount).append('\n');
        if (liveWithoutBackupCount > 0) {
            msg.append("§7  Live files without backups left alone: §f").append(liveWithoutBackupCount).append('\n');
        }
        if (skippedBusyFiles > 0) {
            msg.append("§e  Busy files skipped: §f").append(skippedBusyFiles)
                    .append(" §8(rerun while the server is quieter)\n");
        }

        appendPlanList(msg, plans);
        msg.append("§c  WARNING: This refreshes existing backups and deletes orphan .linear.bak files.\n")
                .append("§c  Live regions without backups are left alone.\n")
                .append("§c  Confirmation expires in ")
                .append(LinearConfig.getConfirmWindowSeconds())
                .append(" seconds.\n")
                .append("§c  Confirm with: §f/linearreader sync-backups confirm");

        send(source, msg.toString().stripTrailing());
    }

    private static void runConfirm(CommandSourceStack source, PendingSync pending) {
        if (!PENDING.compareAndSet(pending, null)) {
            send(source, "§c[LinearReader] backup sync confirmation state changed. Run the analysis again.");
            return;
        }
        if (System.currentTimeMillis() > pending.expiresAtMs()) {
            send(source, "§c[LinearReader] The backup sync confirmation window expired. Run the analysis again.");
            return;
        }
        if (!validatePendingPlan(pending)) {
            send(source, "§e[LinearReader] backup sync was cancelled for safety because the world state changed "
                    + "after analysis. No backups were modified.\n"
                    + "§7  Rerun §f/linearreader sync-backups§7 when the server is quieter, then confirm again.");
            return;
        }

        int refreshed = 0;
        int deletedOrphans = 0;
        long backupDeltaBytes = 0L;

        for (BackupPlan plan : pending.files()) {
            try {
                if (plan.action() == SyncAction.REFRESH) {
                    long oldSize = Files.exists(plan.backupPath()) ? Files.size(plan.backupPath()) : 0L;
                    LinearRegionFile.writeBackupCopy(plan.path());
                    long newSize = Files.size(plan.backupPath());
                    backupDeltaBytes += oldSize - newSize;
                    refreshed++;
                    LinearRuntime.LOGGER.warn("[LinearReader] backup sync refreshed {}", plan.relativePath());
                    continue;
                }

                if (Files.deleteIfExists(plan.backupPath())) {
                    backupDeltaBytes += plan.oldBackupSize();
                    deletedOrphans++;
                    LinearRuntime.LOGGER.warn("[LinearReader] backup sync deleted orphan {}", plan.relativePath());
                }
            } catch (IOException e) {
                LinearRuntime.LOGGER.error("[LinearReader] backup sync failed for {}: {}",
                        plan.relativePath(), e.getMessage(), e);
                send(source, "§c[LinearReader] backup sync failed for " + plan.relativePath() + ": " + e.getMessage());
                return;
            }
        }

        StringBuilder msg = new StringBuilder("§a[LinearReader] backup sync complete.\n")
                .append("§7  Backups refreshed: §f").append(refreshed).append('\n')
                .append("§7  Orphan backups deleted: §f").append(deletedOrphans);
        if (backupDeltaBytes > 0L) {
            msg.append("\n§7  Backup storage reclaimed: §f").append(formatBytes(backupDeltaBytes));
        } else if (backupDeltaBytes < 0L) {
            msg.append("\n§7  Backup storage grew by: §f").append(formatBytes(-backupDeltaBytes));
        }
        send(source, msg.toString());
    }

    private static boolean validatePendingPlan(PendingSync pending) {
        for (BackupPlan plan : pending.files()) {
            LinearRegionFile openRegion = findOpenRegion(plan.normalizedPath());
            if (isBusy(openRegion)) return false;

            if (plan.action() == SyncAction.REFRESH) {
                if (!Files.exists(plan.path())) return false;
                if (!Files.exists(plan.backupPath())) return false;
                continue;
            }

            if (!Files.exists(plan.backupPath())) return false;
            if (Files.exists(plan.path())) return false;
        }
        return true;
    }

    private static List<Path> findLinearFiles(Path worldRoot) {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(worldRoot)) {
            stream.filter(Files::isRegularFile)
                    .filter(BackupSyncer::isLiveLinearFile)
                    .sorted(Comparator.comparing(Path::toString))
                    .forEach(files::add);
        } catch (IOException e) {
            LinearRuntime.LOGGER.warn("[LinearReader] backup sync could not walk world directory: {}", e.getMessage());
        }
        return files;
    }

    private static List<Path> findBackupFiles(Path worldRoot) {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(worldRoot)) {
            stream.filter(Files::isRegularFile)
                    .filter(BackupSyncer::isBackupFile)
                    .sorted(Comparator.comparing(Path::toString))
                    .forEach(files::add);
        } catch (IOException e) {
            LinearRuntime.LOGGER.warn("[LinearReader] backup sync could not walk backup files: {}", e.getMessage());
        }
        return files;
    }

    private static boolean isLiveLinearFile(Path path) {
        String name = path.getFileName().toString();
        if (!name.endsWith(".linear")) return false;
        Path parent = path.getParent();
        return parent == null || parent.getFileName() == null || !"corrupted".equals(parent.getFileName().toString());
    }

    private static boolean isBackupFile(Path path) {
        String name = path.getFileName().toString();
        if (!name.endsWith(".linear.bak")) return false;
        Path parent = path.getParent();
        return parent == null || parent.getFileName() == null || !"corrupted".equals(parent.getFileName().toString());
    }

    private static Path livePathForBackup(Path backupPath) {
        String fileName = backupPath.getFileName().toString();
        String liveName = fileName.substring(0, fileName.length() - 4);
        Path parent = backupPath.getParent();
        if (parent != null && parent.getFileName() != null
                && "backups".equals(parent.getFileName().toString())) {
            Path liveParent = parent.getParent();
            if (liveParent != null) {
                return liveParent.resolve(liveName);
            }
        }
        return backupPath.resolveSibling(liveName);
    }

    private static LinearRegionFile findOpenRegion(Path normalizedPath) {
        for (LinearRegionFile region : LinearRegionFile.ALL_OPEN) {
            if (region.getNormalizedPath().equals(normalizedPath)) {
                return region;
            }
        }
        return null;
    }

    private static boolean isBusy(LinearRegionFile region) {
        return region != null && (region.isDirty() || region.isFlushing());
    }

    private static void appendPlanList(StringBuilder msg, List<BackupPlan> plans) {
        msg.append("§7  Files:\n");
        int shown = Math.min(plans.size(), MAX_CHAT_FILES);
        for (int i = 0; i < shown; i++) {
            BackupPlan plan = plans.get(i);
            msg.append("§7    §f").append(plan.relativePath())
                    .append(plan.action() == SyncAction.REFRESH ? " §8(refresh)" : " §8(delete orphan)")
                    .append('\n');
        }
        if (plans.size() > shown) {
            msg.append("§7    §8... and ").append(plans.size() - shown).append(" more\n");
        }
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
        source.sendSuccess(() -> net.minecraft.network.chat.Component.literal(msg), false);
    }

    private record BackupPlan(
            SyncAction action,
            Path path,
            Path normalizedPath,
            Path backupPath,
            String relativePath,
            long oldBackupSize) {}

    private enum SyncAction {
        REFRESH,
        DELETE_ORPHAN
    }

    private record PendingSync(
            long expiresAtMs,
            Path worldRoot,
            List<BackupPlan> files,
            int skippedBusyFiles,
            int liveWithoutBackupCount,
            int refreshCount,
            int orphanDeleteCount) {

        private PendingSync {
            Objects.requireNonNull(worldRoot, "worldRoot");
            Objects.requireNonNull(files, "files");
        }
    }
}
