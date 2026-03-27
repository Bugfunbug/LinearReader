package com.bugfunbug.linearreader;

import com.bugfunbug.linearreader.command.LinearCommand;
import com.bugfunbug.linearreader.config.FabricLinearConfig;
import com.bugfunbug.linearreader.config.LinearConfig;
import com.bugfunbug.linearreader.linear.DHPregenMonitor;
import com.bugfunbug.linearreader.linear.IdleRecompressor;
import com.bugfunbug.linearreader.linear.LinearRegionFile;
import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.serializer.GsonConfigSerializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class LinearReader implements ModInitializer {

	public static final String MOD_ID = "linearreader";
	public static final Logger LOGGER  = LogManager.getLogger(MOD_ID);

	/** Singleton — set in onInitialize(); used by the static submitFlush(). */
	private static volatile LinearReader INSTANCE;

	private static final long LINEAR_SIGNATURE = 0xc3ff13183cca9d9aL;

	// ── Pinning ───────────────────────────────────────────────────────────────

	private static final Set<Path> PINNED_PATHS = ConcurrentHashMap.newKeySet();

	/** World root — set in SERVER_STARTING; used by command / pinning code. */
	public static volatile Path worldRoot = null;

	public static boolean isPinned(Path regionFilePath) {
		return PINNED_PATHS.contains(regionFilePath.toAbsolutePath().normalize());
	}

	/**
	 * Pin a region file so it is never evicted from the cache.
	 * Saves the pins file immediately so that an unclean shutdown (e.g. C2ME
	 * async thread timeout crashing the JVM) does not lose pin data.
	 */
	public static void pinRegion(Path regionFilePath) {
		PINNED_PATHS.add(regionFilePath.toAbsolutePath().normalize());
		savePinsEagerly();
	}

	/**
	 * Unpin a region file.
	 * Saves the pins file immediately for the same reason as pinRegion().
	 */
	public static void unpinRegion(Path regionFilePath) {
		PINNED_PATHS.remove(regionFilePath.toAbsolutePath().normalize());
		savePinsEagerly();
	}

	public static Set<Path> getPinnedPaths() {
		return Collections.unmodifiableSet(PINNED_PATHS);
	}

	/**
	 * Maps a dimension ResourceKey to its region folder.
	 */
	public static Path regionFolderForDimension(ResourceKey<Level> dim) {
		if (worldRoot == null) return null;
		if (dim.equals(Level.OVERWORLD)) return worldRoot.resolve("region");
		if (dim.equals(Level.NETHER))    return worldRoot.resolve("DIM-1").resolve("region");
		if (dim.equals(Level.END))       return worldRoot.resolve("DIM1").resolve("region");
		ResourceLocation id = dim.location();
		return worldRoot.resolve("dimensions")
				.resolve(id.getNamespace())
				.resolve(id.getPath())
				.resolve("region");
	}

	// ── Async flush infrastructure ────────────────────────────────────────────

	private final Deque<LinearRegionFile> flushQueue    = new ArrayDeque<>();
	private final Set<LinearRegionFile>   queuedRegions =
			Collections.newSetFromMap(new IdentityHashMap<>());

	private static final AtomicInteger FLUSH_THREAD_N = new AtomicInteger(0);

	// Change from final to non-final:
	private ExecutorService flushExecutor;
	private Set<LinearRegionFile> inFlightFlushes;

	// Add a method to initialize/reinitialize executor state:
	private void initExecutor() {
		flushQueue.clear();
		queuedRegions.clear();
		inFlightFlushes = Collections.newSetFromMap(new ConcurrentHashMap<>());
		flushExecutor = Executors.newFixedThreadPool(2, r -> {
			Thread t = new Thread(r, "linearreader-flush-" + FLUSH_THREAD_N.incrementAndGet());
			t.setDaemon(true);
			t.setPriority(Thread.NORM_PRIORITY);
			return t;
		});
	}

	/**
	 * Submits a region eviction flush to the background executor.
	 * Safe from any thread; no-op if already queued.
	 */
	public static void submitFlush(LinearRegionFile region) {
		LinearReader instance = INSTANCE;
		if (instance == null
				|| instance.flushExecutor == null
				|| instance.flushExecutor.isShutdown()) {
			try { region.flush(); }
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
			try   { region.flush(); }
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

	// ── ModInitializer entry point ────────────────────────────────────────────

	@Override
	public void onInitialize() {
		INSTANCE = this;

		AutoConfig.register(FabricLinearConfig.class, GsonConfigSerializer::new);
		pushConfig();

		DHPregenMonitor.install();
		IdleRecompressor.startAutoDetector();

		ServerLifecycleEvents.SERVER_STARTING.register(this::onServerStarting);
		ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);
		ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				LinearCommand.register(dispatcher));

		LOGGER.info("[LinearReader] Initialized — using .linear format exclusively.");
	}

	// ── Config ────────────────────────────────────────────────────────────────

	private void pushConfig() {
		FabricLinearConfig cfg =
				AutoConfig.getConfigHolder(FabricLinearConfig.class).getConfig();
		LinearConfig.update(
				cfg.compressionLevel, cfg.regionCacheSize, cfg.backupEnabled,
				cfg.backupUpdateInterval, cfg.regionsPerSaveTick,
				cfg.slowIoThresholdMs, cfg.diskSpaceWarnGb
		);
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
					.map(p -> p.toAbsolutePath().normalize())
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
	 * savePinsEagerly() already keeps the file current on every pin/unpin.
	 */
	private void savePins() {
		savePinsEagerly();
	}

	// ── SERVER_STARTING ───────────────────────────────────────────────────────

	private void onServerStarting(MinecraftServer server) {
		initExecutor();  // recreate executor — handles singleplayer world reload
		worldRoot = server.getWorldPath(LevelResource.ROOT);

		// Remove leftover idle-recompressor temp files
		try (Stream<Path> s = Files.walk(worldRoot)) {
			s.filter(p -> p.getFileName().toString().endsWith(".recompress.wip"))
					.forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
		} catch (IOException e) {
			LOGGER.warn("[LinearReader] Could not clean .recompress.wip files: {}", e.getMessage());
		}

		loadPins();

		// .wip crash-recovery
		int recovered = 0, deleted = 0;
		try (Stream<Path> s = Files.walk(worldRoot)) {
			Iterable<Path> wips = () -> s
					.filter(Files::isRegularFile)
					.filter(p -> p.getFileName().toString().endsWith(".linear.wip"))
					.iterator();
			for (Path wip : wips) {
				String wipName  = wip.getFileName().toString();
				String realName = wipName.substring(0, wipName.length() - 4);
				Path   realPath = wip.resolveSibling(realName);
				if (isValidLinearFile(wip)) {
					try {
						Files.move(wip, realPath, StandardCopyOption.REPLACE_EXISTING);
						LOGGER.warn("[LinearReader] Recovered .wip: {} -> {}", wipName, realName);
						recovered++;
					} catch (IOException e) {
						LOGGER.error("[LinearReader] Could not rename {}: {}",
								wipName, e.getMessage());
					}
				} else {
					try {
						Files.delete(wip);
						LOGGER.warn("[LinearReader] Deleted incomplete .wip: {}", wipName);
						deleted++;
					} catch (IOException e) {
						LOGGER.error("[LinearReader] Could not delete {}: {}",
								wipName, e.getMessage());
					}
				}
			}
		} catch (IOException e) {
			LOGGER.error("[LinearReader] Error scanning for .wip files: {}", e.getMessage(), e);
		}
		if (recovered > 0 || deleted > 0)
			LOGGER.info("[LinearReader] .wip recovery: {} recovered, {} deleted.",
					recovered, deleted);
	}

	// ── SERVER_STOPPING ───────────────────────────────────────────────────────

	private void onServerStopping(MinecraftServer server) {
		IdleRecompressor.shutdown();
		savePins();  // belt-and-suspenders — savePinsEagerly() already keeps file current
		DHPregenMonitor.notifyServerStopping();

		Set<LinearRegionFile> toFlush = new HashSet<>(flushQueue);
		for (LinearRegionFile r : LinearRegionFile.ALL_OPEN) {
			if (r.isDirty()) toFlush.add(r);
		}
		flushQueue.clear();
		queuedRegions.clear();

		for (LinearRegionFile region : toFlush) {
			if (inFlightFlushes.add(region)) {
				flushExecutor.submit(() -> {
					try   { region.flush(); }
					catch (IOException e) {
						LOGGER.error("[LinearReader] Shutdown flush failed for {}: {}",
								region, e.getMessage(), e);
					} finally {
						inFlightFlushes.remove(region);
					}
				});
			}
		}

		flushExecutor.shutdown();
		try {
			if (!flushExecutor.awaitTermination(60, TimeUnit.SECONDS))
				LOGGER.warn("[LinearReader] Flush executor did not finish within 60s on shutdown.");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			LOGGER.warn("[LinearReader] Interrupted waiting for flush executor on shutdown.");
		}
		LOGGER.info("[LinearReader] Shutdown complete — all region flushes finished.");
	}

	// ── END_SERVER_TICK ───────────────────────────────────────────────────────

	private int saveCheckTicker = 0;

	private void onServerTick(MinecraftServer server) {
		// Queue dirty regions once per second
		if (++saveCheckTicker >= 20) {
			saveCheckTicker = 0;
			for (LinearRegionFile region : LinearRegionFile.ALL_OPEN) {
				if (region.isDirty() && queuedRegions.add(region)) {
					flushQueue.add(region);
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
				try   { region.flush(); }
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
			long header = ByteBuffer.wrap(buf).getLong();
			raf.seek(len - 8);
			raf.readFully(buf);
			long footer = ByteBuffer.wrap(buf).getLong();
			return header == LINEAR_SIGNATURE && footer == LINEAR_SIGNATURE;
		} catch (IOException e) {
			return false;
		}
	}
}