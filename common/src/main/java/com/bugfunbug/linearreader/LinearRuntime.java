package com.bugfunbug.linearreader;

import com.bugfunbug.linearreader.config.LinearConfig;
import com.bugfunbug.linearreader.linear.DHPregenMonitor;
import com.bugfunbug.linearreader.linear.IdleRecompressor;
import com.bugfunbug.linearreader.linear.LinearRegionFile;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Shared runtime/service layer for the 1.20.1 line.
 *
 * Loader entrypoints should only bootstrap config and wire lifecycle events into
 * this class. All storage-facing runtime behavior lives here.
 */
public final class LinearRuntime {

    public static final String MOD_ID = "linearreader";
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);

    private static volatile LinearRuntime INSTANCE;
    private static volatile MinecraftHooks MINECRAFT_HOOKS;

    /** Absolute paths of pinned region files. Populated from disk on server start. */
    private static final Set<Path> PINNED_PATHS = ConcurrentHashMap.newKeySet();

    /** World root path — set on server start, used by commands and pin persistence. */
    private static volatile Path worldRoot;

    private static final AtomicInteger FLUSH_THREAD_N = new AtomicInteger(0);
    private static final long INTEGRATED_FLUSH_STARTUP_GRACE_NS = 20_000_000_000L;
    private static final long DEDICATED_FLUSH_STARTUP_GRACE_NS = 5_000_000_000L;
    private static final long INTEGRATED_BACKGROUND_FLUSH_EXTRA_IDLE_NS = 60_000_000_000L;

    /**
     * Queue of dirty regions waiting to be flushed.
     * Accessed on the server thread via lifecycle hooks.
     */
    private final Deque<LinearRegionFile> flushQueue = new ArrayDeque<>();

    // O(1) membership check — ArrayDeque.contains() is O(n).
    private final Set<LinearRegionFile> queuedRegions =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final AtomicInteger queuedFlushCount = new AtomicInteger(0);
    private final AtomicInteger inFlightFlushCount = new AtomicInteger(0);

    private ExecutorService flushExecutor;
    private Set<LinearRegionFile> inFlightFlushes = Collections.emptySet();
    private boolean dedicatedServer;
    private int tickCounter;
    private long serverStartNs;

    private LinearRuntime() {}

    public static LinearRuntime install(MinecraftHooks hooks) {
        MINECRAFT_HOOKS = Objects.requireNonNull(hooks, "hooks");

        LinearRuntime runtime = INSTANCE;
        if (runtime != null) {
            return runtime;
        }

        synchronized (LinearRuntime.class) {
            runtime = INSTANCE;
            if (runtime == null) {
                runtime = new LinearRuntime();
                INSTANCE = runtime;
                DHPregenMonitor.install();
                LOGGER.info("[LinearReader] Initialized — using .linear format exclusively.");
            }
            return runtime;
        }
    }

    public static Path getWorldRoot() {
        return worldRoot;
    }

    public static Path resolveWorldRoot(MinecraftServer server) {
        return hooks().resolveWorldRoot(server);
    }

    private static Path normalizeRegionPath(Path regionFilePath) {
        return regionFilePath.toAbsolutePath().normalize();
    }

    public static boolean isPinned(Path regionFilePath) {
        return isPinnedNormalized(normalizeRegionPath(regionFilePath));
    }

    public static boolean isPinnedNormalized(Path normalizedRegionFilePath) {
        return PINNED_PATHS.contains(normalizedRegionFilePath);
    }

    public static void pinRegion(Path regionFilePath) {
        PINNED_PATHS.add(normalizeRegionPath(regionFilePath));
        savePinsEagerly();
    }

    public static void unpinRegion(Path regionFilePath) {
        PINNED_PATHS.remove(normalizeRegionPath(regionFilePath));
        savePinsEagerly();
    }

    public static Set<Path> getPinnedPaths() {
        return Collections.unmodifiableSet(PINNED_PATHS);
    }

    /**
     * Maps a dimension ResourceKey to its region folder path under worldRoot.
     * Returns null if worldRoot is not yet set.
     */
    public static Path regionFolderForDimension(ResourceKey<Level> dim) {
        Path root = worldRoot;
        if (root == null) return null;
        return regionFolderForDimension(root, dim);
    }

    public static Path regionFolderForDimension(Path worldRoot, ResourceKey<Level> dim) {
        return hooks().resolveRegionFolder(worldRoot, dim);
    }

    @FunctionalInterface
    private interface RegionIoTask {
        void run(LinearRegionFile region) throws IOException;
    }

    /**
     * Explicit save/close barriers must finish live region flushes.
     * Backup writes are still best-effort async work on a separate executor.
     */
    public static void flushRegionsBlocking(List<LinearRegionFile> regions) throws IOException {
        runRegionIoTasks(regions, "flush", region -> region.flush(true));
    }

    public static void closeRegionsBlocking(List<LinearRegionFile> regions) throws IOException {
        runRegionIoTasks(regions, "close", LinearRegionFile::close);
    }

    private static void runRegionIoTasks(List<LinearRegionFile> regions, String action,
                                         RegionIoTask task) throws IOException {
        if (regions.isEmpty()) return;
        if (regions.size() == 1) {
            task.run(regions.get(0));
            return;
        }

        int threadCount = Math.min(regions.size(),
                Math.max(1, Math.min(Runtime.getRuntime().availableProcessors() / 2, 4)));
        if (threadCount <= 1) {
            IOException first = null;
            for (LinearRegionFile region : regions) {
                try {
                    task.run(region);
                } catch (IOException e) {
                    if (first == null) first = e;
                }
            }
            if (first != null) throw first;
            return;
        }

        ExecutorService executor = Executors.newFixedThreadPool(threadCount, r -> {
            Thread t = new Thread(r, "linearreader-" + action + "-barrier");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY);
            return t;
        });
        List<Future<?>> futures = new ArrayList<>(regions.size());
        try {
            for (LinearRegionFile region : regions) {
                futures.add(executor.submit(() -> {
                    task.run(region);
                    return null;
                }));
            }

            IOException first = null;
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    if (first == null) {
                        first = new IOException("[LinearReader] Interrupted during blocking " + action + '.', e);
                    }
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    IOException io = cause instanceof IOException ioe
                            ? ioe
                            : new IOException("[LinearReader] Blocking " + action + " failed", cause);
                    if (first == null) first = io;
                }
            }
            if (first != null) throw first;
        } finally {
            executor.shutdownNow();
        }
    }

    private boolean backgroundFlushesAllowed(long nowNs) {
        long graceNs = dedicatedServer
                ? DEDICATED_FLUSH_STARTUP_GRACE_NS
                : INTEGRATED_FLUSH_STARTUP_GRACE_NS;
        return nowNs - serverStartNs >= graceNs;
    }

    private int maxDirtyRegionsBeforePressureFlush() {
        int minDirty = LinearConfig.getPressureFlushMinDirtyRegions();
        int maxDirty = Math.max(minDirty, LinearConfig.getPressureFlushMaxDirtyRegions());
        if (DHPregenMonitor.isPregenActive()) {
            return Math.max(minDirty, Math.min(maxDirty, 4));
        }
        if (!dedicatedServer) {
            return Integer.MAX_VALUE;
        }

        int flushRate = Math.max(1, DHPregenMonitor.effectiveRegionsPerSaveTick());
        int cacheSize = Math.max(8, DHPregenMonitor.effectiveCacheSize());
        int target = Math.max(cacheSize / 16, flushRate * 2);

        int backlog = flushQueue.size() + inFlightFlushes.size();
        if (backlog >= Math.max(2, flushRate)) {
            target = Math.max(minDirty, target / 2);
        }

        return Math.max(minDirty, Math.min(maxDirty, target));
    }

    private boolean shouldQueueBackgroundFlush(LinearRegionFile region, long nowNs) {
        if (!region.shouldBackgroundFlush(nowNs)) return false;
        if (dedicatedServer) return true;
        if (!flushQueue.isEmpty() || !inFlightFlushes.isEmpty()) return false;
        return nowNs - region.lastMutationTimeNs() >= INTEGRATED_BACKGROUND_FLUSH_EXTRA_IDLE_NS;
    }

    private void initExecutor() {
        flushQueue.clear();
        queuedRegions.clear();
        queuedFlushCount.set(0);
        inFlightFlushCount.set(0);
        tickCounter = 0;
        serverStartNs = System.nanoTime();
        inFlightFlushes = Collections.newSetFromMap(new ConcurrentHashMap<>());
        final int threadCount = dedicatedServer ? 2 : 1;
        final int threadPriority = dedicatedServer ? Thread.NORM_PRIORITY - 1 : Thread.MIN_PRIORITY + 1;
        flushExecutor = Executors.newFixedThreadPool(threadCount, r -> {
            Thread t = new Thread(r, "linearreader-flush-" + FLUSH_THREAD_N.incrementAndGet());
            t.setDaemon(true);
            t.setPriority(threadPriority);
            return t;
        });
    }

    public static int currentLiveCompressionLevel() {
        int configured = LinearConfig.getCompressionLevel();
        if (configured <= 1) return configured;

        LinearRuntime instance = INSTANCE;
        if (instance == null || instance.flushExecutor == null) return configured;

        if (DHPregenMonitor.isPregenActive()) {
            return Math.max(1, Math.min(configured, 2));
        }

        int pressure = instance.queuedFlushCount.get() + instance.inFlightFlushCount.get();
        int flushRate = Math.max(1, DHPregenMonitor.effectiveRegionsPerSaveTick());
        if (pressure >= Math.max(4, flushRate * 2)) {
            return Math.max(1, configured - 2);
        }
        if (pressure >= Math.max(2, flushRate)) {
            return Math.max(1, configured - 1);
        }
        return configured;
    }

    /**
     * Submits an eviction-triggered region flush.
     * Safe to call from any thread including c2me storage threads.
     */
    public static void submitFlush(LinearRegionFile region) {
        LinearRuntime instance = INSTANCE;
        if (instance == null
                || instance.flushExecutor == null
                || instance.flushExecutor.isShutdown()) {
            try {
                region.flush(true);
            } catch (IOException e) {
                LOGGER.error("[LinearReader] Fallback flush failed for {}: {}",
                        region, e.getMessage(), e);
            } finally {
                LinearRegionFile.ALL_OPEN.remove(region);
                region.releaseChunkData();
            }
            return;
        }
        if (!instance.inFlightFlushes.add(region)) return;
        instance.inFlightFlushCount.incrementAndGet();
        instance.flushExecutor.submit(() -> {
            try {
                region.flush(true);
            } catch (IOException e) {
                LOGGER.error("[LinearReader] Async eviction flush failed for {}: {}",
                        region, e.getMessage(), e);
            } finally {
                instance.inFlightFlushes.remove(region);
                instance.inFlightFlushCount.decrementAndGet();
                LinearRegionFile.ALL_OPEN.remove(region);
                region.releaseChunkData();
            }
        });
    }

    public void onServerStarting(MinecraftServer server) {
        dedicatedServer = server.isDedicatedServer();
        initExecutor();
        worldRoot = resolveWorldRoot(server);
        IdleRecompressor.startAutoDetector();

        migrateLegacyBackups();
        loadPins();
        recoverStartupTempFiles();
    }

    public void onServerStopping() {
        IdleRecompressor.shutdown();
        savePins();
        DHPregenMonitor.notifyServerStopping();

        flushQueue.clear();
        queuedRegions.clear();
        queuedFlushCount.set(0);

        if (flushExecutor != null) {
            flushExecutor.shutdown();
        }

        try {
            flushRegionsBlocking(dirtyRegionsSnapshot());
        } catch (IOException e) {
            LOGGER.error("[LinearReader] Shutdown blocking flush failed: {}", e.getMessage(), e);
        }

        if (flushExecutor != null) {
            try {
                if (!flushExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    LOGGER.warn("[LinearReader] Flush executor did not finish within 10s on shutdown.");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOGGER.warn("[LinearReader] Interrupted while waiting for flush executor on shutdown.");
            }
        }

        inFlightFlushes.clear();
        inFlightFlushCount.set(0);

        try {
            flushRegionsBlocking(dirtyRegionsSnapshot());
        } catch (IOException e) {
            LOGGER.error("[LinearReader] Final shutdown flush failed: {}", e.getMessage(), e);
        }

        LinearRegionFile.shutdownBackupExecutor();
        worldRoot = null;
        PINNED_PATHS.clear();
        flushExecutor = null;
        LOGGER.info("[LinearReader] Shutdown complete — all region flushes finished.");
    }

    public void onLevelSave() {
        if (flushExecutor == null) return;

        int queued = 0;
        for (LinearRegionFile region : LinearRegionFile.ALL_OPEN) {
            if (region.isDirty() && queueRegion(region)) {
                queued++;
            }
        }
        if (queued > 0) {
            int ratePerTick = DHPregenMonitor.effectiveRegionsPerSaveTick();
            if (queued > ratePerTick * 5) {
                LOGGER.info("[LinearReader] World save: {} dirty region(s) queued (draining at {} per tick).",
                        queued, ratePerTick);
            } else {
                LOGGER.debug("[LinearReader] World save: {} dirty region(s) queued.", queued);
            }
        }
    }

    public void onServerTick() {
        if (flushExecutor == null) return;

        tickCounter++;
        if (tickCounter % 20 == 0) {
            long nowNs = System.nanoTime();
            if (backgroundFlushesAllowed(nowNs)) {
                List<LinearRegionFile> dirtyCandidates = new ArrayList<>();
                for (LinearRegionFile region : LinearRegionFile.ALL_OPEN) {
                    if (shouldQueueBackgroundFlush(region, nowNs)) {
                        queueRegion(region);
                        continue;
                    }
                    if (region.shouldPressureFlush(nowNs)) {
                        dirtyCandidates.add(region);
                    }
                }

                int dirtyLimit = maxDirtyRegionsBeforePressureFlush();
                if (dirtyCandidates.size() > dirtyLimit) {
                    dirtyCandidates.sort(java.util.Comparator.comparingLong(LinearRegionFile::lastMutationTimeNs));
                    int toQueue = dirtyCandidates.size() - dirtyLimit;
                    for (int i = 0; i < toQueue; i++) {
                        queueRegion(dirtyCandidates.get(i));
                    }
                }
            }
        }

        if (flushQueue.isEmpty()) return;

        int limit = DHPregenMonitor.effectiveRegionsPerSaveTick();
        int submitted = 0;
        Iterator<LinearRegionFile> it = flushQueue.iterator();
        while (it.hasNext() && submitted < limit) {
            LinearRegionFile region = it.next();
            it.remove();
            queuedRegions.remove(region);
            queuedFlushCount.decrementAndGet();
            if (!inFlightFlushes.add(region)) continue;
            inFlightFlushCount.incrementAndGet();
            submitted++;
            flushExecutor.submit(() -> {
                try {
                    region.flush(true);
                } catch (IOException e) {
                    LOGGER.error("[LinearReader] Async flush failed for {}: {}",
                            region, e.getMessage(), e);
                } finally {
                    inFlightFlushes.remove(region);
                    inFlightFlushCount.decrementAndGet();
                }
            });
        }
    }

    private boolean queueRegion(LinearRegionFile region) {
        if (!queuedRegions.add(region)) return false;
        flushQueue.add(region);
        queuedFlushCount.incrementAndGet();
        return true;
    }

    private void loadPins() {
        PINNED_PATHS.clear();

        Path root = worldRoot;
        if (root == null) return;

        Path pinsFile = root.resolve("data/linearreader/pinned_regions.txt");
        if (!Files.exists(pinsFile)) return;
        try {
            Path normalizedRoot = root.toAbsolutePath().normalize();
            int loaded = 0;
            for (String line : Files.readAllLines(pinsFile)) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                PINNED_PATHS.add(normalizedRoot.resolve(line).toAbsolutePath().normalize());
                loaded++;
            }
            if (loaded > 0) {
                LOGGER.info("[LinearReader] Loaded {} pinned region(s).", loaded);
            }
        } catch (IOException e) {
            LOGGER.warn("[LinearReader] Could not load pin list: {}", e.getMessage());
        }
    }

    private static void savePinsEagerly() {
        Path root = worldRoot;
        if (root == null) return;

        Path pinsFile = root.resolve("data/linearreader/pinned_regions.txt");
        try {
            Files.createDirectories(pinsFile.getParent());
            Path normalizedRoot = root.toAbsolutePath().normalize();
            List<String> lines = PINNED_PATHS.stream()
                    .filter(p -> p.startsWith(normalizedRoot))
                    .map(p -> normalizedRoot.relativize(p).toString().replace('\\', '/'))
                    .sorted()
                    .collect(Collectors.toList());
            Files.write(pinsFile, lines);
            LOGGER.debug("[LinearReader] Saved {} pinned region(s) eagerly.", lines.size());
        } catch (IOException e) {
            LOGGER.warn("[LinearReader] Could not eagerly save pin list: {}", e.getMessage());
        }
    }

    private void savePins() {
        savePinsEagerly();
    }

    private void recoverStartupTempFiles() {
        Path root = worldRoot;
        if (root == null) return;

        int recovered = 0;
        int deleted = 0;
        try (Stream<Path> stream = Files.walk(root)) {
            Iterable<Path> paths = () -> stream.filter(Files::isRegularFile).iterator();
            for (Path path : paths) {
                String fileName = path.getFileName().toString();
                if (fileName.endsWith(".recompress.wip")) {
                    try {
                        Files.delete(path);
                    } catch (IOException e) {
                        LOGGER.warn("[LinearReader] Could not clean recompress temp file {}: {}",
                                fileName, e.getMessage());
                    }
                    continue;
                }
                if (!fileName.endsWith(".linear.wip")) continue;

                String realName = fileName.substring(0, fileName.length() - 4);
                Path realPath = path.resolveSibling(realName);
                if (isValidLinearFile(path)) {
                    try {
                        Files.move(path, realPath, StandardCopyOption.REPLACE_EXISTING);
                        LOGGER.warn("[LinearReader] Recovered .wip file: {} -> {}",
                                fileName, realName);
                        recovered++;
                    } catch (IOException e) {
                        LOGGER.error("[LinearReader] Could not rename {} to {}: {}",
                                fileName, realName, e.getMessage());
                    }
                } else {
                    try {
                        Files.delete(path);
                        LOGGER.warn("[LinearReader] Deleted incomplete .wip file: {}", fileName);
                        deleted++;
                    } catch (IOException e) {
                        LOGGER.error("[LinearReader] Could not delete {}: {}",
                                fileName, e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.error("[LinearReader] Error scanning startup temp files: {}", e.getMessage(), e);
        }

        if (recovered > 0 || deleted > 0) {
            LOGGER.info("[LinearReader] .wip recovery: {} recovered, {} deleted.",
                    recovered, deleted);
        }
    }

    private void migrateLegacyBackups() {
        Path root = worldRoot;
        if (root == null) return;

        int moved = 0;
        int deduped = 0;
        int conflicts = 0;

        try (Stream<Path> stream = Files.walk(root)) {
            Iterable<Path> paths = () -> stream
                    .filter(Files::isRegularFile)
                    .filter(LinearRuntime::isLegacyBackupFile)
                    .iterator();
            for (Path legacyPath : paths) {
                Path canonicalPath = canonicalBackupPathForLegacy(legacyPath);
                try {
                    Path parent = canonicalPath.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }

                    if (!Files.exists(canonicalPath)) {
                        Files.move(legacyPath, canonicalPath);
                        moved++;
                        continue;
                    }

                    if (Files.mismatch(legacyPath, canonicalPath) == -1L) {
                        Files.delete(legacyPath);
                        deduped++;
                        continue;
                    }

                    Path conflictPath = canonicalPath.resolveSibling(
                            canonicalPath.getFileName().toString() + ".legacy-conflict");
                    Files.move(legacyPath, conflictPath, StandardCopyOption.REPLACE_EXISTING);
                    conflicts++;
                    LOGGER.warn("[LinearReader] Legacy backup conflict moved to {}",
                            root.relativize(conflictPath));
                } catch (IOException e) {
                    LOGGER.warn("[LinearReader] Could not migrate legacy backup {}: {}",
                            root.relativize(legacyPath), e.getMessage());
                }
            }
        } catch (IOException e) {
            LOGGER.warn("[LinearReader] Could not scan for legacy backups: {}", e.getMessage());
        }

        if (moved > 0 || deduped > 0 || conflicts > 0) {
            LOGGER.info("[LinearReader] Legacy backup migration: {} moved, {} deduped, {} conflicts.",
                    moved, deduped, conflicts);
        }
    }

    private static boolean isLegacyBackupFile(Path path) {
        String fileName = path.getFileName().toString();
        if (!fileName.endsWith(".linear.bak")) return false;

        Path parent = path.getParent();
        if (parent == null || parent.getFileName() == null) return true;

        String parentName = parent.getFileName().toString();
        return !"backups".equals(parentName) && !"corrupted".equals(parentName);
    }

    private static Path canonicalBackupPathForLegacy(Path legacyPath) {
        String fileName = legacyPath.getFileName().toString();
        String liveName = fileName.substring(0, fileName.length() - 4);
        Path parent = legacyPath.getParent();
        if (parent == null) {
            return Path.of("backups").resolve(fileName);
        }
        return LinearRegionFile.backupPathFor(parent.resolve(liveName));
    }

    private static List<LinearRegionFile> dirtyRegionsSnapshot() {
        List<LinearRegionFile> dirty = new ArrayList<>();
        for (LinearRegionFile region : LinearRegionFile.ALL_OPEN) {
            if (region.isDirty()) dirty.add(region);
        }
        return dirty;
    }

    private static boolean isValidLinearFile(Path file) {
        return LinearRegionFile.verifyOnDisk(file).ok;
    }

    private static MinecraftHooks hooks() {
        MinecraftHooks hooks = MINECRAFT_HOOKS;
        if (hooks == null) {
            throw new IllegalStateException("Minecraft hooks were not installed before LinearRuntime use.");
        }
        return hooks;
    }
}
