package com.bugfunbug.linearreader;

import com.bugfunbug.linearreader.command.LinearCommand;
import com.bugfunbug.linearreader.config.ForgeLinearConfig;
import com.bugfunbug.linearreader.config.LinearConfig;
import com.bugfunbug.linearreader.linear.DHPregenMonitor;
import com.bugfunbug.linearreader.linear.IdleRecompressor;
import com.bugfunbug.linearreader.linear.LinearRegionFile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Mod(LinearReader.MOD_ID)
public class LinearReader {

    public static final String MOD_ID = "linearreader";
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);

    /** Singleton — set in the constructor, used by static submitFlush(). */
    private static volatile LinearReader INSTANCE;

    private static final long LINEAR_SIGNATURE = 0xc3ff13183cca9d9aL;

    /** Absolute paths of pinned region files. Populated from disk on server start. */
    private static final Set<Path> PINNED_PATHS = ConcurrentHashMap.newKeySet();

    /** World root path — set in onServerStarting, used by pin commands. */
    public static volatile Path worldRoot = null;

    private static Path normalizeRegionPath(Path regionFilePath) {
        return regionFilePath.toAbsolutePath().normalize();
    }

    public static boolean isPinned(Path regionFilePath) {
        return isPinnedNormalized(normalizeRegionPath(regionFilePath));
    }

    public static boolean isPinnedNormalized(Path normalizedRegionFilePath) {
        return PINNED_PATHS.contains(normalizedRegionFilePath);
    }

    /**
     * Pin a region file so it is never evicted from the cache.
     * Saves the pins file immediately so that an unclean shutdown (e.g. C2ME
     * async thread timeout crashing the JVM) does not lose pin data.
     */
    public static void pinRegion(Path regionFilePath) {
        PINNED_PATHS.add(normalizeRegionPath(regionFilePath));
        savePinsEagerly();
    }

    /**
     * Unpin a region file.
     * Saves the pins file immediately for the same reason as pinRegion().
     */
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
    public static Path regionFolderForDimension(
            net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dim) {
        if (worldRoot == null) return null;
        if (dim.equals(net.minecraft.world.level.Level.OVERWORLD))
            return worldRoot.resolve("region");
        if (dim.equals(net.minecraft.world.level.Level.NETHER))
            return worldRoot.resolve("DIM-1").resolve("region");
        if (dim.equals(net.minecraft.world.level.Level.END))
            return worldRoot.resolve("DIM1").resolve("region");
        net.minecraft.resources.ResourceLocation id = dim.location();
        return worldRoot.resolve("dimensions")
                .resolve(id.getNamespace())
                .resolve(id.getPath())
                .resolve("region");
    }

    @FunctionalInterface
    private interface RegionIoTask {
        void run(LinearRegionFile region) throws IOException;
    }

    /** Explicit save/close barriers should finish backups too. */
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
                try { task.run(region); }
                catch (IOException e) {
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
                    if (first == null) first = new IOException(
                            "[LinearReader] Interrupted during blocking " + action + '.', e);
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

    /**
     * Queue of dirty regions waiting to be flushed.
     * Both onLevelSave and onServerTick run on the server main thread,
     * so a plain ArrayDeque with no synchronization is correct here.
     */
    private final Deque<LinearRegionFile> flushQueue = new ArrayDeque<>();

    // O(1) membership check — ArrayDeque.contains() is O(n).
    private final Set<LinearRegionFile> queuedRegions =
            Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    private static final java.util.concurrent.atomic.AtomicInteger FLUSH_THREAD_N =
            new java.util.concurrent.atomic.AtomicInteger(0);

    private ExecutorService flushExecutor;
    private Set<LinearRegionFile> inFlightFlushes;
    private boolean dedicatedServer;
    private int tickCounter;
    private long serverStartNs;

    private static final long INTEGRATED_FLUSH_STARTUP_GRACE_NS = 20_000_000_000L;
    private static final long DEDICATED_FLUSH_STARTUP_GRACE_NS = 5_000_000_000L;
    private static final long INTEGRATED_BACKGROUND_FLUSH_EXTRA_IDLE_NS = 60_000_000_000L;

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

    private boolean backgroundFlushesAllowed(long nowNs) {
        long graceNs = dedicatedServer
                ? DEDICATED_FLUSH_STARTUP_GRACE_NS
                : INTEGRATED_FLUSH_STARTUP_GRACE_NS;
        return nowNs - serverStartNs >= graceNs;
    }

    private void initExecutor() {
        flushQueue.clear();
        queuedRegions.clear();
        tickCounter = 0;
        serverStartNs = System.nanoTime();
        inFlightFlushes = Collections.newSetFromMap(new ConcurrentHashMap<>());
        // Integrated servers share CPU with rendering, so keep background flushes gentler there.
        final int threadCount = dedicatedServer ? 2 : 1;
        final int threadPriority = dedicatedServer ? Thread.NORM_PRIORITY - 1 : Thread.MIN_PRIORITY + 1;
        flushExecutor = Executors.newFixedThreadPool(threadCount, r -> {
            Thread t = new Thread(r, "linearreader-flush-" + FLUSH_THREAD_N.incrementAndGet());
            t.setDaemon(true);
            t.setPriority(threadPriority);
            return t;
        });
    }

    /**
     * Submits a region flush to the background executor.
     * Safe to call from any thread including c2me storage threads.
     */
    public static void submitFlush(LinearRegionFile region) {
        LinearReader instance = INSTANCE;
        if (instance == null
                || instance.flushExecutor == null
                || instance.flushExecutor.isShutdown()) {
            try { region.flush(false); }
            catch (IOException e) {
                LOGGER.error("[LinearReader] Fallback flush failed for {}: {}",
                        region, e.getMessage(), e);
            } finally {
                LinearRegionFile.ALL_OPEN.remove(region);
                region.releaseChunkData();
            }
            return;
        }
        if (!instance.inFlightFlushes.add(region)) return;
        instance.flushExecutor.submit(() -> {
            try { region.flush(false); }
            catch (IOException e) {
                LOGGER.error("[LinearReader] Async eviction flush failed for {}: {}",
                        region, e.getMessage(), e);
            } finally {
                instance.inFlightFlushes.remove(region);
                LinearRegionFile.ALL_OPEN.remove(region);
                region.releaseChunkData();
            }
        });
    }

    // ── Constructor ───────────────────────────────────────────────────────────

    public LinearReader() {
        ModLoadingContext.get().registerConfig(
                ModConfig.Type.COMMON, ForgeLinearConfig.SPEC, "linearreader-server.toml");
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::onConfigLoad);
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::onConfigReload);
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::setup);

        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.addListener(LinearCommand::register);
        INSTANCE = this;

        DHPregenMonitor.install();

        LOGGER.info("[LinearReader] Initialized — using .linear format exclusively.");
    }

    private void setup(final FMLCommonSetupEvent event) {}

    // ── Config ────────────────────────────────────────────────────────────────

    private void onConfigLoad(ModConfigEvent.Loading event) {
        if (event.getConfig().getSpec() == ForgeLinearConfig.SPEC)
            ForgeLinearConfig.pushToLinearConfig();
    }

    private void onConfigReload(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() == ForgeLinearConfig.SPEC) {
            ForgeLinearConfig.pushToLinearConfig();
            LOGGER.info("[LinearReader] Config reloaded.");
        }
    }

    // ── Pin helpers ───────────────────────────────────────────────────────────

    private void loadPins() {
        Path pinsFile = worldRoot.resolve("data/linearreader/pinned_regions.txt");
        if (!Files.exists(pinsFile)) return;
        try {
            Path normalizedRoot = worldRoot.toAbsolutePath().normalize();
            int loaded = 0;
            for (String line : Files.readAllLines(pinsFile)) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                PINNED_PATHS.add(normalizedRoot.resolve(line).toAbsolutePath().normalize());
                loaded++;
            }
            if (loaded > 0)
                LOGGER.info("[LinearReader] Loaded {} pinned region(s).", loaded);
        } catch (IOException e) {
            LOGGER.warn("[LinearReader] Could not load pin list: {}", e.getMessage());
        }
    }

    /**
     * Writes pins to disk immediately on the calling thread.
     * Called from pinRegion() / unpinRegion() so that pins survive even if
     * the server crashes during shutdown (e.g. C2ME async timeout).
     */
    private static void savePinsEagerly() {
        if (worldRoot == null) return;
        Path pinsFile = worldRoot.resolve("data/linearreader/pinned_regions.txt");
        try {
            Files.createDirectories(pinsFile.getParent());
            // Normalize both sides to resolve symlinks consistently.
            // On macOS, /Users paths can have symlink representations that
            // make relativize() throw IllegalArgumentException unexpectedly.
            Path normalizedRoot = worldRoot.toAbsolutePath().normalize();
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

    /**
     * Called from onServerStopping as a belt-and-suspenders save.
     * savePinsEagerly() already keeps the file current on every pin/unpin,
     * so this is only needed to catch any edge cases.
     */
    private void savePins() {
        savePinsEagerly();
    }

    // ── Server lifecycle ──────────────────────────────────────────────────────

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        MinecraftServer server = event.getServer();
        dedicatedServer = server.isDedicatedServer();
        initExecutor();  // recreate executor — handles singleplayer world reload
        worldRoot = server.getWorldPath(LevelResource.ROOT);
        IdleRecompressor.startAutoDetector();

        loadPins();

        int recovered = 0, deleted = 0;
        try (Stream<Path> stream = Files.walk(worldRoot)) {
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

        if (recovered > 0 || deleted > 0)
            LOGGER.info("[LinearReader] .wip recovery: {} recovered, {} deleted.",
                    recovered, deleted);
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        IdleRecompressor.shutdown();
        savePins();  // belt-and-suspenders — savePinsEagerly() already keeps file current
        DHPregenMonitor.notifyServerStopping();

        flushQueue.clear();
        queuedRegions.clear();

        if (flushExecutor != null) {
            flushExecutor.shutdown();
        }

        try {
            // Flush whatever is still dirty directly rather than waiting on the low-priority queue.
            flushRegionsBlocking(dirtyRegionsSnapshot());
        } catch (IOException e) {
            LOGGER.error("[LinearReader] Shutdown blocking flush failed: {}", e.getMessage(), e);
        }

        if (flushExecutor != null) {
            try {
                if (!flushExecutor.awaitTermination(10, TimeUnit.SECONDS))
                    LOGGER.warn("[LinearReader] Flush executor did not finish within 10s on shutdown.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOGGER.warn("[LinearReader] Interrupted while waiting for flush executor on shutdown.");
            }
        }
        inFlightFlushes.clear();

        try {
            flushRegionsBlocking(dirtyRegionsSnapshot());
        } catch (IOException e) {
            LOGGER.error("[LinearReader] Final shutdown flush failed: {}", e.getMessage(), e);
        }

        LinearRegionFile.shutdownBackupExecutor();
        LOGGER.info("[LinearReader] Shutdown complete — all region flushes finished.");
    }

    // ── Save scheduler ────────────────────────────────────────────────────────

    @SubscribeEvent
    public void onLevelSave(LevelEvent.Save event) {
        int queued = 0;
        for (LinearRegionFile region : LinearRegionFile.ALL_OPEN) {
            if (region.isDirty() && queuedRegions.add(region)) {
                flushQueue.add(region);
                queued++;
            }
        }
        if (queued > 0) {
            int ratePerTick = DHPregenMonitor.effectiveRegionsPerSaveTick();
            if (queued > ratePerTick * 5)
                LOGGER.info("[LinearReader] World save: {} dirty region(s) queued " +
                        "(draining at {} per tick).", queued, ratePerTick);
            else
                LOGGER.debug("[LinearReader] World save: {} dirty region(s) queued.", queued);
        }
    }

    private static List<LinearRegionFile> dirtyRegionsSnapshot() {
        List<LinearRegionFile> dirty = new ArrayList<>();
        for (LinearRegionFile region : LinearRegionFile.ALL_OPEN) {
            if (region.isDirty()) dirty.add(region);
        }
        return dirty;
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        tickCounter++;
        if (tickCounter % 20 == 0) {
            long nowNs = System.nanoTime();
            if (backgroundFlushesAllowed(nowNs)) {
                List<LinearRegionFile> dirtyCandidates = new ArrayList<>();
                for (LinearRegionFile region : LinearRegionFile.ALL_OPEN) {
                    if (shouldQueueBackgroundFlush(region, nowNs)) {
                        if (queuedRegions.add(region)) {
                            flushQueue.add(region);
                        }
                        continue;
                    }
                    if (region.shouldPressureFlush(nowNs)) {
                        dirtyCandidates.add(region);
                    }
                }
                int dirtyLimit = maxDirtyRegionsBeforePressureFlush();
                if (dirtyCandidates.size() > dirtyLimit) {
                    // Let freshly-started worlds stay read-biased for a bit, then cap dirty build-up.
                    dirtyCandidates.sort(java.util.Comparator.comparingLong(LinearRegionFile::lastMutationTimeNs));
                    int toQueue = dirtyCandidates.size() - dirtyLimit;
                    for (int i = 0; i < toQueue; i++) {
                        LinearRegionFile region = dirtyCandidates.get(i);
                        if (!queuedRegions.add(region)) continue;
                        flushQueue.add(region);
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
            if (!inFlightFlushes.add(region)) continue;
            submitted++;
            flushExecutor.submit(() -> {
                try { region.flush(false); }
                catch (IOException e) {
                    LOGGER.error("[LinearReader] Async flush failed for {}: {}",
                            region, e.getMessage(), e);
                } finally {
                    inFlightFlushes.remove(region);
                }
            });
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static boolean isValidLinearFile(Path file) {
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            long len = raf.length();
            if (len < 40) return false;
            byte[] buf = new byte[8];
            raf.readFully(buf);
            long headerSig = ByteBuffer.wrap(buf).getLong();
            raf.seek(len - 8);
            raf.readFully(buf);
            long footerSig = ByteBuffer.wrap(buf).getLong();
            return headerSig == LINEAR_SIGNATURE && footerSig == LINEAR_SIGNATURE;
        } catch (IOException e) {
            return false;
        }
    }
}
