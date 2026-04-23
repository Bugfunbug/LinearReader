package com.bugfunbug.linearreader.command;

import com.bugfunbug.linearreader.LinearReader;
import com.bugfunbug.linearreader.linear.LinearRegionFile;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.LevelResource;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Implements /linearreader prune-chunks with a dry-run + short-lived confirmation window.
 *
 * Safety rules are intentionally strict and conservative:
 * - only chunk region files under .../region are scanned
 * - busy/changed regions are skipped or rejected
 * - confirmation revalidates affected regions before any destructive action
 */
public final class ChunkPruner {

    private static final long CONFIRM_WINDOW_MS = 60_000L;
    private static final int MAX_SAMPLE_CHUNKS = 8;
    private static final int MAX_CHAT_REGIONS = 12;
    private static final Pattern REGION_FILE_PATTERN = Pattern.compile("^r\\.(-?\\d+)\\.(-?\\d+)\\.linear$");
    private static final AtomicReference<PendingPrune> PENDING = new AtomicReference<>();
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private ChunkPruner() {}

    public static int startDryRun(CommandSourceStack source) {
        if (!RUNNING.compareAndSet(false, true)) {
            source.sendFailure(net.minecraft.network.chat.Component.literal(
                    "[LinearReader] Chunk pruning is already running."));
            return 0;
        }

        MinecraftServer server = source.getServer();
        Path worldRoot = server.getWorldPath(LevelResource.ROOT);
        source.sendSuccess(() -> net.minecraft.network.chat.Component.literal(
                "§6[LinearReader] Starting prune-chunks analysis. "
                        + "This is a dry run; nothing will be deleted yet."), false);

        Thread worker = new Thread(() -> {
            try {
                runDryRun(source, server, worldRoot);
            } finally {
                RUNNING.set(false);
            }
        }, "linearreader-prune-analyze");
        worker.setDaemon(true);
        worker.start();
        return 1;
    }

    public static int confirm(CommandSourceStack source) {
        PendingPrune pending = PENDING.get();
        if (pending == null) {
            source.sendFailure(net.minecraft.network.chat.Component.literal(
                    "[LinearReader] No pending prune-chunks analysis. Run /linearreader prune-chunks first."));
            return 0;
        }
        if (System.currentTimeMillis() > pending.expiresAtMs()) {
            PENDING.compareAndSet(pending, null);
            source.sendFailure(net.minecraft.network.chat.Component.literal(
                    "[LinearReader] The prune-chunks confirmation window expired. Run the analysis again."));
            return 0;
        }
        if (!RUNNING.compareAndSet(false, true)) {
            source.sendFailure(net.minecraft.network.chat.Component.literal(
                    "[LinearReader] Chunk pruning is already running."));
            return 0;
        }

        source.sendSuccess(() -> net.minecraft.network.chat.Component.literal(
                "§6[LinearReader] Starting chunk pruning. Matching chunks will now be deleted."), false);

        Thread worker = new Thread(() -> {
            try {
                runConfirm(source, pending);
            } finally {
                RUNNING.set(false);
            }
        }, "linearreader-prune-confirm");
        worker.setDaemon(true);
        worker.start();
        return 1;
    }

    private static void runDryRun(CommandSourceStack source, MinecraftServer server, Path worldRoot) {
        long scannedAtNs = System.nanoTime();
        PlayerContext playerContext = playerContextFor(source, worldRoot);
        List<Path> regionFiles = findChunkRegionFiles(worldRoot);
        if (regionFiles.isEmpty()) {
            send(source, "§e[LinearReader] No chunk-region .linear files were found.");
            PENDING.set(null);
            return;
        }

        List<RegionPlan> plans = new ArrayList<>();
        int skippedBusyRegions = 0;
        int failedRegions = 0;
        int scannedPresentChunks = 0;
        long estimatedLinearReclaimBytes = 0L;

        for (Path regionPath : regionFiles) {
            RegionAnalysis analysis;
            try {
                analysis = analyzeRegion(worldRoot, regionPath, scannedAtNs, playerContext);
            } catch (IOException e) {
                failedRegions++;
                LinearReader.LOGGER.warn("[LinearReader] prune-chunks scan failed for {}: {}",
                        worldRoot.relativize(regionPath), e.getMessage());
                continue;
            }

            if (analysis.busy) {
                skippedBusyRegions++;
                continue;
            }
            scannedPresentChunks += analysis.presentChunkCount();
            estimatedLinearReclaimBytes += analysis.estimatedReclaimBytes();
            if (analysis.plan() != null) {
                plans.add(analysis.plan());
            }
        }

        int candidateChunks = plans.stream().mapToInt(RegionPlan::candidateCount).sum();
        if (candidateChunks == 0) {
            PENDING.set(null);
            send(source, "§7[LinearReader] prune-chunks found no safe candidates."
                    + (skippedBusyRegions > 0 ? " §8(" + skippedBusyRegions + " busy region(s) skipped)" : "")
                    + (failedRegions > 0 ? " §8(" + failedRegions + " region(s) failed to scan)" : ""));
            return;
        }

        List<String> sampleChunks = buildSampleChunks(plans, playerContext);
        PendingPrune pending = new PendingPrune(
                System.currentTimeMillis() + CONFIRM_WINDOW_MS,
                scannedAtNs,
                worldRoot,
                List.copyOf(plans),
                List.copyOf(sampleChunks),
                skippedBusyRegions,
                failedRegions,
                regionFiles.size(),
                candidateChunks,
                scannedPresentChunks,
                estimatedLinearReclaimBytes
        );
        PENDING.set(pending);

        LinearReader.LOGGER.warn("[LinearReader] prune-chunks dry run found {} candidate chunk(s) across {} region(s).",
                candidateChunks, plans.size());
        for (RegionPlan plan : plans) {
            LinearReader.LOGGER.warn("[LinearReader] prune-chunks candidate region: {} -> {} chunk(s)",
                    plan.regionLabel(), plan.candidateCount());
        }

        StringBuilder msg = new StringBuilder()
                .append("§6[LinearReader] prune-chunks dry run complete.\n")
                .append("§7  Chunks matching rules: §f").append(candidateChunks)
                .append("§7 out of §f").append(scannedPresentChunks)
                .append("§7 stored chunk(s) scanned\n")
                .append("§7  Affected regions: §f").append(plans.size()).append('\n');
        if (estimatedLinearReclaimBytes > 0L) {
            msg.append("§7  Estimated .linear reclaim: §f~").append(formatBytes(estimatedLinearReclaimBytes))
                    .append(" §8(based on current compression ratio)\n");
            if (com.bugfunbug.linearreader.config.LinearConfig.isBackupEnabled()) {
                msg.append("§8  Backups are left untouched. Run §f/linearreader sync-backups§8 if you want .bak files to match.\n");
            }
        }

        if (skippedBusyRegions > 0) {
            msg.append("§e  Busy regions skipped: §f").append(skippedBusyRegions)
                    .append(" §8(rerun while the server is quieter)\n");
        }
        if (failedRegions > 0) {
            msg.append("§e  Regions that failed to scan: §f").append(failedRegions).append('\n');
        }

        appendPlayerContext(msg, playerContext);
        appendChatRegionList(msg, plans, playerContext);
        appendSampleChunks(msg, pending.sampleChunks());
        msg.append("§c  WARNING: This permanently deletes matching chunk data from .linear region files.\n")
                .append("§c  Run this while the server is quiet and make sure you have backups.\n")
                .append("§c  Confirmation expires in 60 seconds.\n")
                .append("§c  Confirm with: §f/linearreader prune-chunks confirm");

        send(source, msg.toString().stripTrailing());
    }

    private static void runConfirm(CommandSourceStack source, PendingPrune pending) {
        if (!PENDING.compareAndSet(pending, null)) {
            send(source, "§c[LinearReader] prune-chunks confirmation state changed. Run the analysis again.");
            return;
        }
        if (System.currentTimeMillis() > pending.expiresAtMs()) {
            send(source, "§c[LinearReader] The prune-chunks confirmation window expired. Run the analysis again.");
            return;
        }
        if (!validatePendingPlan(pending)) {
            send(source, "§e[LinearReader] Pruning was cancelled for safety because one or more affected regions "
                    + "changed after analysis. No chunks were deleted.\n"
                    + "§7  Rerun §f/linearreader prune-chunks§7 when the server is quieter, then confirm again.");
            return;
        }

        int deletedChunks = 0;
        int changedRegions = 0;
        long reclaimedBytes = 0L;

        LinearReader.LOGGER.warn("[LinearReader] prune-chunks confirm started for {} candidate chunk(s) across {} region(s).",
                pending.candidateChunks(), pending.regions().size());

        for (RegionPlan plan : pending.regions()) {
            try {
                int deletedInRegion = pruneRegion(plan, pending.scannedAtNs());
                if (deletedInRegion > 0) {
                    changedRegions++;
                    deletedChunks += deletedInRegion;
                    long newSize = Files.size(plan.path());
                    reclaimedBytes += Math.max(0L, plan.fileSize() - newSize);
                    LinearReader.LOGGER.warn("[LinearReader] prune-chunks deleted {} chunk(s) from {}.",
                            deletedInRegion, plan.regionLabel());
                }
            } catch (IOException e) {
                LinearReader.LOGGER.error("[LinearReader] prune-chunks failed while pruning {}: {}",
                        plan.regionLabel(), e.getMessage(), e);
                send(source, "§c[LinearReader] prune-chunks failed while pruning "
                        + plan.regionLabel() + ": " + e.getMessage());
                return;
            }
        }

        LinearReader.LOGGER.warn("[LinearReader] prune-chunks complete: {} chunk(s) deleted across {} region(s).",
                deletedChunks, changedRegions);
        StringBuilder msg = new StringBuilder("§a[LinearReader] prune-chunks complete. Deleted §f")
                .append(deletedChunks)
                .append("§a chunk(s) across §f")
                .append(changedRegions)
                .append("§a region(s).");
        if (reclaimedBytes > 0L) {
            msg.append("\n§7  Reclaimed from .linear files: §f").append(formatBytes(reclaimedBytes));
            if (com.bugfunbug.linearreader.config.LinearConfig.isBackupEnabled()) {
                msg.append("\n§8  World-folder size may differ if backups were updated.");
            }
        }
        send(source, msg.toString());
    }

    private static RegionAnalysis analyzeRegion(Path worldRoot, Path regionPath, long scannedAtNs, PlayerContext playerContext)
            throws IOException {

        String regionLabel = worldRoot.relativize(regionPath).toString();
        String regionDirLabel = worldRoot.relativize(regionPath.getParent()).toString().replace('\\', '/');
        Matcher matcher = REGION_FILE_PATTERN.matcher(regionPath.getFileName().toString());
        if (!matcher.matches()) {
            return RegionAnalysis.notBusy(null, 0, 0L);
        }
        int regionX = Integer.parseInt(matcher.group(1));
        int regionZ = Integer.parseInt(matcher.group(2));
        Path normalized = regionPath.toAbsolutePath().normalize();

        LinearRegionFile openRegion = findOpenRegion(normalized);
        if (openRegion != null && (openRegion.isDirty() || openRegion.isFlushing())) {
            LinearReader.LOGGER.info("[LinearReader] prune-chunks skipped busy region {}", regionLabel);
            return RegionAnalysis.busyResult();
        }

        long openMutationStart = openRegion != null ? openRegion.lastMutationTimeNs() : Long.MIN_VALUE;
        BitSet candidates = new BitSet(1024);
        List<ChunkPos> sampleCandidates = new ArrayList<>();
        int presentChunkCount = 0;
        boolean ownsRegion = openRegion == null;
        LinearRegionFile region = ownsRegion ? new LinearRegionFile(regionPath, false) : openRegion;

        try {
            for (int localIndex = 0; localIndex < 1024; localIndex++) {
                int localX = localIndex % 32;
                int localZ = localIndex / 32;
                ChunkPos pos = new ChunkPos(regionX * 32 + localX, regionZ * 32 + localZ);
                int storedBytes = region.storedChunkBytes(localIndex);
                if (storedBytes <= 0) continue;
                presentChunkCount++;

                try (DataInputStream dis = region.read(pos)) {
                    if (dis == null) continue;
                    CompoundTag tag = NbtIo.read(dis);
                    if (tag == null) continue;
                    if (isPrunableChunk(tag)) {
                        candidates.set(localIndex);
                        considerSampleChunk(sampleCandidates, pos, playerContext, regionDirLabel);
                    }
                }
            }
        } finally {
            if (ownsRegion) {
                LinearRegionFile.ALL_OPEN.remove(region);
            }
        }

        if (openRegion != null) {
            if (openRegion.isDirty() || openRegion.isFlushing() || openRegion.lastMutationTimeNs() != openMutationStart) {
                LinearReader.LOGGER.info("[LinearReader] prune-chunks skipped region {} because it changed during analysis.",
                        regionLabel);
                return RegionAnalysis.busyResult();
            }
        }

        if (candidates.isEmpty()) {
            return RegionAnalysis.notBusy(null, presentChunkCount, 0L);
        }

        long regionFileSize = Files.size(regionPath);
        long estimatedFileSizeAfterPrune = region.estimateFileSizeAfterRemoving(candidates);
        long estimatedReclaimBytes = Math.max(0L, regionFileSize - estimatedFileSizeAfterPrune);

        RegionPlan plan = new RegionPlan(
                regionPath,
                normalized,
                regionLabel,
                regionDirLabel,
                regionX,
                regionZ,
                (BitSet) candidates.clone(),
                List.copyOf(sampleCandidates),
                candidates.cardinality(),
                regionFileSize,
                lastModifiedMillis(regionPath),
                estimatedReclaimBytes
        );
        return RegionAnalysis.notBusy(plan, presentChunkCount, estimatedReclaimBytes);
    }

    private static boolean validatePendingPlan(PendingPrune pending) {
        for (RegionPlan plan : pending.regions()) {
            try {
                if (!Files.exists(plan.path())) return false;
                if (Files.size(plan.path()) != plan.fileSize()) return false;
                if (lastModifiedMillis(plan.path()) != plan.lastModifiedMs()) return false;
            } catch (IOException e) {
                return false;
            }

            LinearRegionFile openRegion = findOpenRegion(plan.normalizedPath());
            if (openRegion != null) {
                if (openRegion.isDirty() || openRegion.isFlushing()) return false;
                if (openRegion.lastMutationTimeNs() > pending.scannedAtNs()) return false;
            }
        }
        return true;
    }

    private static int pruneRegion(RegionPlan plan, long scannedAtNs) throws IOException {
        LinearRegionFile openRegion = findOpenRegion(plan.normalizedPath());
        boolean ownsRegion = openRegion == null;
        LinearRegionFile region = ownsRegion ? new LinearRegionFile(plan.path(), false) : openRegion;

        try {
            if (!ownsRegion) {
                if (region.isDirty() || region.isFlushing() || region.lastMutationTimeNs() > scannedAtNs) {
                    throw new IOException("region became busy after confirmation: " + plan.regionLabel());
                }
            }

            int deleted = region.clearChunksIfUnchanged((BitSet) plan.localChunkBits().clone(),
                    ownsRegion ? Long.MAX_VALUE : scannedAtNs);
            if (deleted < 0) {
                throw new IOException("region changed before prune lock was acquired: " + plan.regionLabel());
            }
            if (deleted > 0) {
                region.flush(false);
            }
            return deleted;
        } finally {
            if (ownsRegion) {
                LinearRegionFile.ALL_OPEN.remove(region);
            }
        }
    }

    private static List<Path> findChunkRegionFiles(Path worldRoot) {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(worldRoot)) {
            stream.filter(Files::isRegularFile)
                    .filter(ChunkPruner::isChunkRegionFile)
                    .sorted(Comparator.comparing(Path::toString))
                    .forEach(files::add);
        } catch (IOException e) {
            LinearReader.LOGGER.warn("[LinearReader] prune-chunks could not walk world directory: {}", e.getMessage());
        }
        return files;
    }

    private static boolean isChunkRegionFile(Path path) {
        Path parent = path.getParent();
        if (parent == null || parent.getFileName() == null) return false;
        if (!"region".equals(parent.getFileName().toString())) return false;
        return REGION_FILE_PATTERN.matcher(path.getFileName().toString()).matches();
    }

    private static LinearRegionFile findOpenRegion(Path normalizedPath) {
        for (LinearRegionFile region : LinearRegionFile.ALL_OPEN) {
            if (region.getNormalizedPath().equals(normalizedPath)) {
                return region;
            }
        }
        return null;
    }

    private static boolean isPrunableChunk(CompoundTag rawTag) {
        CompoundTag tag = rawTag.contains("Level", Tag.TAG_COMPOUND)
                ? rawTag.getCompound("Level")
                : rawTag;

        if (!tag.contains("InhabitedTime", Tag.TAG_ANY_NUMERIC)) return false;
        if (tag.getLong("InhabitedTime") != 0L) return false;
        if (hasNonEmptyList(tag, "block_entities") || hasNonEmptyList(tag, "TileEntities")) return false;
        if (hasNonEmptyList(tag, "entities") || hasNonEmptyList(tag, "Entities")) return false;
        if (hasStructureData(tag, "structures", "starts", "References")) return false;
        if (hasStructureData(tag, "Structures", "Starts", "References")) return false;
        if (hasNonEmptyList(tag, "block_ticks") || hasNonEmptyList(tag, "fluid_ticks")
                || hasNonEmptyList(tag, "TileTicks") || hasNonEmptyList(tag, "LiquidTicks")) return false;
        if (hasNonEmptyNestedListList(tag, "PostProcessing")) return false;
        if (hasNonEmptyCompound(tag, "UpgradeData") || hasNonEmptyCompound(tag, "upgradeData")) return false;
        return true;
    }

    private static boolean hasNonEmptyList(CompoundTag tag, String key) {
        if (!tag.contains(key, Tag.TAG_LIST)) return false;
        ListTag list = tag.getList(key, Tag.TAG_END);
        return !list.isEmpty();
    }

    private static boolean hasNonEmptyNestedListList(CompoundTag tag, String key) {
        if (!tag.contains(key, Tag.TAG_LIST)) return false;
        ListTag outer = tag.getList(key, Tag.TAG_LIST);
        for (int i = 0; i < outer.size(); i++) {
            Tag entry = outer.get(i);
            if (entry instanceof ListTag list && !list.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasNonEmptyCompound(CompoundTag tag, String key) {
        return tag.contains(key, Tag.TAG_COMPOUND) && !tag.getCompound(key).isEmpty();
    }

    private static boolean hasStructureData(CompoundTag tag, String structuresKey, String startsKey, String referencesKey) {
        if (!tag.contains(structuresKey, Tag.TAG_COMPOUND)) return false;
        CompoundTag structures = tag.getCompound(structuresKey);

        if (structures.contains(startsKey, Tag.TAG_COMPOUND) && !structures.getCompound(startsKey).isEmpty()) {
            return true;
        }

        if (!structures.contains(referencesKey, Tag.TAG_COMPOUND)) return false;
        CompoundTag references = structures.getCompound(referencesKey);
        Set<String> keys = references.getAllKeys();
        for (String key : keys) {
            if (references.contains(key, Tag.TAG_LONG_ARRAY)) {
                LongArrayTag arr = (LongArrayTag) references.get(key);
                if (arr != null && arr.size() > 0) return true;
            } else {
                // Unknown structure reference payload: be conservative.
                return true;
            }
        }
        return false;
    }

    private static long lastModifiedMillis(Path path) throws IOException {
        FileTime fileTime = Files.getLastModifiedTime(path);
        return fileTime.toMillis();
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

    private static void appendPlayerContext(StringBuilder msg, PlayerContext playerContext) {
        if (playerContext == null) return;
        msg.append("§7  Your position: §fchunk ")
                .append(playerContext.chunkX()).append(", ").append(playerContext.chunkZ())
                .append(" §8(region ").append(playerContext.regionX()).append(", ").append(playerContext.regionZ())
                .append(" in ").append(playerContext.regionDirLabel()).append(")\n");
    }

    private static void appendChatRegionList(StringBuilder msg, List<RegionPlan> plans, PlayerContext playerContext) {
        msg.append("§7  Regions:\n");
        List<RegionPlan> displayPlans = selectDisplayRegions(plans, playerContext);
        int shown = Math.min(displayPlans.size(), MAX_CHAT_REGIONS);
        for (int i = 0; i < shown; i++) {
            RegionPlan plan = displayPlans.get(i);
            msg.append("§7    §f").append(plan.regionLabel())
                    .append(" §7(").append(plan.candidateCount()).append(" chunk(s))");
            String distance = describeRegionDistance(plan, playerContext);
            if (distance != null) {
                msg.append(" §8[").append(distance).append(']');
            }
            msg.append('\n');
        }
        if (displayPlans.size() > shown) {
            msg.append("§7    §8... and ").append(displayPlans.size() - shown).append(" more\n");
        }
    }

    private static void appendSampleChunks(StringBuilder msg, List<String> sampleChunks) {
        if (sampleChunks.isEmpty()) return;
        msg.append("§7  Sample chunks:\n");
        for (String sample : sampleChunks) {
            msg.append("§7    §f").append(sample).append('\n');
        }
    }

    private static PlayerContext playerContextFor(CommandSourceStack source, Path worldRoot) {
        if (source.getEntity() == null) return null;

        ChunkPos chunkPos = new ChunkPos(net.minecraft.core.BlockPos.containing(source.getPosition()));
        Path regionFolder = LinearReader.regionFolderForDimension(source.getLevel().dimension());
        String regionDirLabel = regionFolder != null && regionFolder.startsWith(worldRoot)
                ? worldRoot.relativize(regionFolder).toString().replace('\\', '/')
                : "region";
        return new PlayerContext(
                regionDirLabel,
                chunkPos.x,
                chunkPos.z,
                chunkPos.x >> 5,
                chunkPos.z >> 5
        );
    }

    private static void considerSampleChunk(List<ChunkPos> samples, ChunkPos candidate,
                                            PlayerContext playerContext, String regionDirLabel) {
        if (samples.size() < 3) {
            samples.add(candidate);
            return;
        }
        if (playerContext == null || !regionDirLabel.equals(playerContext.regionDirLabel())) {
            return;
        }

        int worstIndex = -1;
        long worstDistance = Long.MIN_VALUE;
        for (int i = 0; i < samples.size(); i++) {
            long distance = chunkDistanceSq(samples.get(i), playerContext);
            if (distance > worstDistance) {
                worstDistance = distance;
                worstIndex = i;
            }
        }

        long candidateDistance = chunkDistanceSq(candidate, playerContext);
        if (candidateDistance < worstDistance && worstIndex >= 0) {
            samples.set(worstIndex, candidate);
        }
    }

    private static List<RegionPlan> selectDisplayRegions(List<RegionPlan> plans, PlayerContext playerContext) {
        LinkedHashMap<String, RegionPlan> selected = new LinkedHashMap<>();
        int nearbyTarget = Math.min(MAX_CHAT_REGIONS / 2, MAX_CHAT_REGIONS);

        if (playerContext != null) {
            plans.stream()
                    .filter(plan -> plan.regionDirLabel().equals(playerContext.regionDirLabel()))
                    .sorted(Comparator
                            .comparingLong((RegionPlan plan) -> regionDistanceSq(plan, playerContext))
                            .thenComparing(Comparator.comparingInt(RegionPlan::candidateCount).reversed())
                            .thenComparing(RegionPlan::regionLabel))
                    .limit(nearbyTarget)
                    .forEach(plan -> selected.put(plan.regionLabel(), plan));
        }

        plans.stream()
                .sorted(Comparator
                        .comparingInt(RegionPlan::candidateCount).reversed()
                        .thenComparing(Comparator.comparingLong(RegionPlan::estimatedReclaimBytes).reversed())
                        .thenComparingLong(plan -> regionDistanceSq(plan, playerContext))
                        .thenComparing(RegionPlan::regionLabel))
                .forEach(plan -> {
                    if (selected.size() < MAX_CHAT_REGIONS) {
                        selected.putIfAbsent(plan.regionLabel(), plan);
                    }
                });

        return new ArrayList<>(selected.values());
    }

    private static List<String> buildSampleChunks(List<RegionPlan> plans, PlayerContext playerContext) {
        LinkedHashMap<String, String> selected = new LinkedHashMap<>();
        int nearbyTarget = playerContext != null ? MAX_SAMPLE_CHUNKS / 2 : 0;

        if (playerContext != null) {
            plans.stream()
                    .filter(plan -> plan.regionDirLabel().equals(playerContext.regionDirLabel()))
                    .flatMap(plan -> plan.sampleChunks().stream()
                            .map(chunkPos -> new SampleChunk(plan, chunkPos)))
                    .sorted(Comparator
                            .comparingLong((SampleChunk sample) -> chunkDistanceSq(sample.chunkPos(), playerContext))
                            .thenComparing(Comparator.comparingInt((SampleChunk sample) -> sample.plan().candidateCount()).reversed())
                            .thenComparing(sample -> sample.plan().regionLabel()))
                    .limit(nearbyTarget)
                    .forEach(sample -> selected.putIfAbsent(sampleKey(sample),
                            formatSampleChunk(sample, "nearby")));
        }

        plans.stream()
                .sorted(Comparator
                        .comparingInt(RegionPlan::candidateCount).reversed()
                        .thenComparing(Comparator.comparingLong(RegionPlan::estimatedReclaimBytes).reversed())
                        .thenComparingLong(plan -> regionDistanceSq(plan, playerContext))
                        .thenComparing(RegionPlan::regionLabel))
                .forEach(plan -> {
                    for (ChunkPos chunkPos : plan.sampleChunks()) {
                        if (selected.size() >= MAX_SAMPLE_CHUNKS) {
                            return;
                        }
                        SampleChunk sample = new SampleChunk(plan, chunkPos);
                        selected.putIfAbsent(sampleKey(sample),
                                formatSampleChunk(sample, "region has " + plan.candidateCount() + " prune candidates"));
                    }
                });

        return new ArrayList<>(selected.values());
    }

    private static String formatSampleChunk(SampleChunk sample, String reason) {
        return sample.plan().regionLabel() + " @ chunk " + sample.chunkPos().x + ", " + sample.chunkPos().z
                + " §8(" + reason + ")";
    }

    private static String sampleKey(SampleChunk sample) {
        return sample.plan().regionLabel() + "|" + sample.chunkPos().x + "|" + sample.chunkPos().z;
    }

    private static String describeRegionDistance(RegionPlan plan, PlayerContext playerContext) {
        if (playerContext == null || !plan.regionDirLabel().equals(playerContext.regionDirLabel())) {
            return null;
        }
        long distanceSq = regionDistanceSq(plan, playerContext);
        if (distanceSq == 0L) return "you are here";
        long distance = Math.round(Math.sqrt(distanceSq));
        return "~" + distance + " region(s) away";
    }

    private static long regionDistanceSq(RegionPlan plan, PlayerContext playerContext) {
        if (playerContext == null || !plan.regionDirLabel().equals(playerContext.regionDirLabel())) {
            return Long.MAX_VALUE / 4L;
        }
        long dx = (long) plan.regionX() - playerContext.regionX();
        long dz = (long) plan.regionZ() - playerContext.regionZ();
        return dx * dx + dz * dz;
    }

    private static long chunkDistanceSq(ChunkPos chunkPos, PlayerContext playerContext) {
        long dx = (long) chunkPos.x - playerContext.chunkX();
        long dz = (long) chunkPos.z - playerContext.chunkZ();
        return dx * dx + dz * dz;
    }

    private static void send(CommandSourceStack source, String msg) {
        source.sendSuccess(() -> net.minecraft.network.chat.Component.literal(msg), false);
    }

    private record RegionAnalysis(boolean busy, RegionPlan plan, int presentChunkCount, long estimatedReclaimBytes) {
        private static RegionAnalysis busyResult() {
            return new RegionAnalysis(true, null, 0, 0L);
        }

        private static RegionAnalysis notBusy(RegionPlan plan, int presentChunkCount, long estimatedReclaimBytes) {
            return new RegionAnalysis(false, plan, presentChunkCount, estimatedReclaimBytes);
        }
    }

    private record RegionPlan(
            Path path,
            Path normalizedPath,
            String regionLabel,
            String regionDirLabel,
            int regionX,
            int regionZ,
            BitSet localChunkBits,
            List<ChunkPos> sampleChunks,
            int candidateCount,
            long fileSize,
            long lastModifiedMs,
            long estimatedReclaimBytes) {}

    private record PlayerContext(
            String regionDirLabel,
            int chunkX,
            int chunkZ,
            int regionX,
            int regionZ) {}

    private record SampleChunk(RegionPlan plan, ChunkPos chunkPos) {}

    private record PendingPrune(
            long expiresAtMs,
            long scannedAtNs,
            Path worldRoot,
            List<RegionPlan> regions,
            List<String> sampleChunks,
            int skippedBusyRegions,
            int failedRegions,
            int scannedRegionFiles,
            int candidateChunks,
            int scannedPresentChunks,
            long estimatedReclaimBytes) {

        private PendingPrune {
            Objects.requireNonNull(worldRoot, "worldRoot");
            Objects.requireNonNull(regions, "regions");
            Objects.requireNonNull(sampleChunks, "sampleChunks");
        }
    }
}
