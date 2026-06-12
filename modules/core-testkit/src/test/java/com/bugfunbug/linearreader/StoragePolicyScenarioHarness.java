package com.bugfunbug.linearreader;

import com.bugfunbug.linearreader.linear.LinearCoreTestHooks;
import com.bugfunbug.linearreader.linear.LinearRegionFile;
import com.bugfunbug.linearreader.minecraftapi.ChunkPosCompat;
import com.bugfunbug.linearreader.linear.LinearTestData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.ChunkPos;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

final class StoragePolicyScenarioHarness implements AutoCloseable {

    private static final long TICK_NS = 50_000_000L;

    private final Path root;
    private final Map<String, RegionHandle> regions = new LinkedHashMap<>();

    private long nowNs;
    private int queuedFlushCount;
    private int inFlightFlushCount;
    private int nextRegionX;

    private StoragePolicyScenarioHarness(Path root, boolean dedicatedServer, long startNs) throws IOException {
        this.root = root;
        Files.createDirectories(root);
        LinearTestSupport.resetState();
        nowNs = startNs;
        applyClock();
        StoragePolicyManager.reset(dedicatedServer);
        StoragePolicyManager.onServerTick(0, 0);
    }

    static StoragePolicyScenarioHarness create(Path root, boolean dedicatedServer) throws IOException {
        return new StoragePolicyScenarioHarness(root, dedicatedServer, 1_000_000_000L);
    }

    void runTicks(int count, TickWork work) throws IOException {
        for (int tick = 0; tick < count; tick++) {
            nowNs += TICK_NS;
            applyClock();
            if (work != null) {
                work.accept(new TickStep(this, tick));
            }
            StoragePolicyManager.onServerTick(queuedFlushCount, inFlightFlushCount);
        }
    }

    void quietTicks(int count) throws IOException {
        backlog(0, 0);
        runTicks(count, null);
    }

    void quietMinutes(int minutes) throws IOException {
        quietTicks(ticksForMinutes(minutes));
    }

    void backlog(int queued, int inFlight) {
        queuedFlushCount = Math.max(0, queued);
        inFlightFlushCount = Math.max(0, inFlight);
    }

    void pinnedRegions(int count) {
        StoragePolicyManager.updatePinnedRegionCount(count);
    }

    void pregenActive(boolean active) {
        LinearCoreTestHooks.setPregenActive(active);
    }

    void read(String regionName) {
        RegionHandle region = region(regionName);
        StoragePolicyManager.recordChunkRead(region.path);
    }

    void write(String regionName, int bytesWritten) {
        RegionHandle region = region(regionName);
        StoragePolicyManager.recordChunkWrite(region.path, bytesWritten);
        region.dirty = true;
        region.lastMutationNs = nowNs;
    }

    void flush(String regionName, long flushNs, long uncompressedBytes, long compressedBytes, int compressionLevel) {
        RegionHandle region = region(regionName);
        StoragePolicyManager.recordRegionFlush(region.path, flushNs, uncompressedBytes, compressedBytes, compressionLevel);
        region.dirty = false;
        region.lastSuccessfulFlushNs = nowNs;
    }

    void recompress(String regionName, int compressionLevel, long bytesSaved) {
        StoragePolicyManager.recordRegionRecompressed(region(regionName).path, compressionLevel, bytesSaved);
    }

    void residentReload(String regionName) {
        StoragePolicyManager.recordResidentReload(region(regionName).path);
    }

    void residentEviction(String regionName, long bytesFreed) {
        StoragePolicyManager.recordResidentEviction(region(regionName).path, bytesFreed);
    }

    void realWrite(String regionName, int payloadBytes) throws IOException {
        RegionHandle region = realRegion(regionName);
        ChunkPos pos = region.nextChunkPos();
        try (DataOutputStream out = region.file.write(pos)) {
            NbtIo.write(payloadChunk(regionName, pos, region.writeSerial, payloadBytes), out);
        }
        syncState(region);
    }

    void realFlush(String regionName, boolean allowBackup) throws IOException {
        RegionHandle region = realRegion(regionName);
        region.file.flush(allowBackup);
        syncState(region);
    }

    void realRead(String regionName) throws IOException {
        RegionHandle region = realRegion(regionName);
        ChunkPos pos = region.lastWrittenChunkPos();
        try (DataInputStream in = pos == null ? null : region.file.read(pos)) {
            if (in != null) {
                NbtIo.read(in);
            }
        }
        syncState(region);
    }

    void unloadResident(String regionName) throws IOException {
        RegionHandle region = realRegion(regionName);
        region.file.releaseChunkData();
    }

    void awaitBackupTasks() throws IOException {
        LinearTestData.awaitBackupTasks();
    }

    boolean shouldQueueBackgroundFlush(String regionName) {
        RegionHandle region = region(regionName);
        if (region.file != null) {
            return StoragePolicyManager.shouldQueueBackgroundFlush(region.file, nowNs);
        }
        return StoragePolicyManager.shouldQueueBackgroundFlush(
                region.dirty,
                false,
                region.lastMutationNs,
                region.lastSuccessfulFlushNs,
                nowNs
        );
    }

    boolean shouldConsiderPressureFlush(String regionName) {
        RegionHandle region = region(regionName);
        if (region.file != null) {
            return StoragePolicyManager.shouldConsiderPressureFlush(region.file, nowNs);
        }
        return StoragePolicyManager.shouldConsiderPressureFlush(
                region.dirty,
                false,
                region.lastMutationNs,
                region.lastSuccessfulFlushNs,
                nowNs
        );
    }

    double recompressPriority(String regionName) {
        RegionHandle region = region(regionName);
        if (region.file != null) {
            return StoragePolicyManager.recompressPriority(region.file);
        }
        return StoragePolicyManager.recompressPriority(region.path);
    }

    double pressureFlushPriority(String regionName) {
        RegionHandle region = region(regionName);
        if (region.file != null) {
            return StoragePolicyManager.pressureFlushPriority(region.file, nowNs);
        }
        return StoragePolicyManager.pressureFlushPriority(
                region.path,
                region.lastMutationNs,
                nowNs
        );
    }

    double residentTrimPriority(String regionName, long lastAccessNs, long residentBytes) {
        return StoragePolicyManager.residentTrimPriority(region(regionName).path, lastAccessNs, residentBytes, nowNs);
    }

    boolean shouldTrimResidentRegion(String regionName, boolean loaded, boolean dirty,
                                     boolean flushing, boolean pinned, long lastAccessNs) {
        return StoragePolicyManager.shouldTrimResidentRegion(
                region(regionName).path,
                loaded,
                dirty,
                flushing,
                pinned,
                lastAccessNs,
                nowNs
        );
    }

    double backupDebt(String regionName) throws IOException {
        return realRegion(regionName).file.maintenanceDebtSnapshot(nowNs).backupDebt();
    }

    double dirtyDebt(String regionName) throws IOException {
        return realRegion(regionName).file.maintenanceDebtSnapshot(nowNs).dirtyDebt();
    }

    State state() {
        StoragePolicyManager.PolicyDebugSnapshot debug = StoragePolicyManager.debugSnapshot();
        return new State(
                nowNs,
                StoragePolicyManager.currentCompressionLevel(),
                StoragePolicyManager.flushBudgetPerTick(),
                StoragePolicyManager.quietnessScore(),
                StoragePolicyManager.maintenanceAllowed(),
                StoragePolicyManager.maintenanceBudgetFiles(),
                StoragePolicyManager.maintenanceDebtScore(),
                debug.pressureScore(),
                debug.backupDebtScore(),
                debug.dirtyDebtScore(),
                debug.cacheChurnScore(),
                debug.loadProfile(),
                debug.compressionMode(),
                debug.pinnedRegionCount(),
                debug.residentHotSet(),
                debug.residentTargetBytes()
        );
    }

    static int ticksForSeconds(int seconds) {
        return Math.max(0, seconds * 20);
    }

    static int ticksForMinutes(int minutes) {
        return ticksForSeconds(minutes * 60);
    }

    @Override
    public void close() {
        for (RegionHandle region : regions.values()) {
            if (region.file == null) {
                continue;
            }
            LinearRegionFile.ALL_OPEN.remove(region.file);
            region.file.releaseChunkData();
        }
        LinearTestSupport.resetState();
    }

    private RegionHandle region(String regionName) {
        return regions.computeIfAbsent(regionName, this::newRegion);
    }

    private RegionHandle realRegion(String regionName) throws IOException {
        RegionHandle region = region(regionName);
        if (region.file == null) {
            Files.createDirectories(region.path.getParent());
            region.file = new LinearRegionFile(region.path, false);
            syncState(region);
        }
        return region;
    }

    private RegionHandle newRegion(String regionName) {
        int regionX = nextRegionX++;
        Path path = root.resolve("region").resolve("r." + regionX + ".0.linear");
        return new RegionHandle(regionName, path, regionX);
    }

    private void syncState(RegionHandle region) {
        if (region.file == null) {
            return;
        }
        region.dirty = region.file.isDirty();
        region.lastMutationNs = region.file.lastMutationTimeNs();
        region.lastSuccessfulFlushNs = region.file.lastSuccessfulFlushTimeNs();
    }

    private void applyClock() {
        long nowMs = nowNs / 1_000_000L;
        StoragePolicyManager.setTestNowNs(nowNs);
        StoragePolicyManager.setTestCurrentTimeMs(nowMs);
        LinearCoreTestHooks.setFixedClock(nowNs, nowMs);
    }

    private static CompoundTag payloadChunk(String kind, ChunkPos pos, int serial, int payloadBytes) {
        CompoundTag tag = LinearTestData.simpleChunk(
                kind + "-" + serial,
                ChunkPosCompat.x(pos),
                ChunkPosCompat.z(pos));
        if (payloadBytes > 0) {
            tag.putString("payload", "x".repeat(payloadBytes));
        }
        return tag;
    }

    static final class TickStep {
        private final StoragePolicyScenarioHarness harness;
        private final int tickIndex;

        private TickStep(StoragePolicyScenarioHarness harness, int tickIndex) {
            this.harness = harness;
            this.tickIndex = tickIndex;
        }

        int tickIndex() {
            return tickIndex;
        }

        TickStep backlog(int queued, int inFlight) {
            harness.backlog(queued, inFlight);
            return this;
        }

        TickStep read(String regionName) {
            harness.read(regionName);
            return this;
        }

        TickStep write(String regionName, int bytesWritten) {
            harness.write(regionName, bytesWritten);
            return this;
        }

        TickStep flush(String regionName, long flushNs, long uncompressedBytes, long compressedBytes, int compressionLevel) {
            harness.flush(regionName, flushNs, uncompressedBytes, compressedBytes, compressionLevel);
            return this;
        }

        TickStep residentReload(String regionName) {
            harness.residentReload(regionName);
            return this;
        }

        TickStep residentEviction(String regionName, long bytesFreed) {
            harness.residentEviction(regionName, bytesFreed);
            return this;
        }

        TickStep realWrite(String regionName, int payloadBytes) throws IOException {
            harness.realWrite(regionName, payloadBytes);
            return this;
        }

        TickStep realFlush(String regionName, boolean allowBackup) throws IOException {
            harness.realFlush(regionName, allowBackup);
            return this;
        }

        TickStep realRead(String regionName) throws IOException {
            harness.realRead(regionName);
            return this;
        }

        TickStep pins(int count) {
            harness.pinnedRegions(count);
            return this;
        }

        TickStep pregen(boolean active) {
            harness.pregenActive(active);
            return this;
        }
    }

    record State(long nowNs,
                 int compressionLevel,
                 int flushBudgetPerTick,
                 double quietnessScore,
                 boolean maintenanceAllowed,
                 int maintenanceBudgetFiles,
                 double maintenanceDebtScore,
                 double pressureScore,
                 double backupDebtScore,
                 double dirtyDebtScore,
                 double cacheChurnScore,
                 String loadProfile,
                 String compressionMode,
                 int pinnedRegionCount,
                 int residentHotSet,
                 long residentTargetBytes) {
    }

    @FunctionalInterface
    interface TickWork {
        void accept(TickStep tick) throws IOException;
    }

    private static final class RegionHandle {
        private final String name;
        private final Path path;
        private final int regionX;
        private LinearRegionFile file;
        private boolean dirty;
        private long lastMutationNs;
        private long lastSuccessfulFlushNs;
        private int writeSerial;
        private int nextChunkIndex;
        private ChunkPos lastWrittenChunkPos;

        private RegionHandle(String name, Path path, int regionX) {
            this.name = Objects.requireNonNull(name, "name");
            this.path = Objects.requireNonNull(path, "path");
            this.regionX = regionX;
        }

        private ChunkPos nextChunkPos() {
            int localIndex = nextChunkIndex++ % 1024;
            int localX = localIndex & 31;
            int localZ = (localIndex >> 5) & 31;
            lastWrittenChunkPos = new ChunkPos(regionX * 32 + localX, localZ);
            writeSerial++;
            return lastWrittenChunkPos;
        }

        private ChunkPos lastWrittenChunkPos() {
            return lastWrittenChunkPos;
        }
    }
}
