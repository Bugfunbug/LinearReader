package com.bugfunbug.linearreader.voxy;

import com.bugfunbug.linearreader.LinearRuntime;
import com.bugfunbug.linearreader.linear.VoxyMcaStager;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

public final class VoxyCompatAutoImporter {

    private static final int VOXY_SAVE_BACKLOG_LOW_WATERMARK = 100;
    private static final long VOXY_SAVE_BACKLOG_STATUS_INTERVAL_MS = 5_000L;
    private static final int AUTO_BATCH_FILES = 16;
    private static final long AUTO_BATCH_LINEAR_BYTES = 512L * 1024L * 1024L;

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static volatile Thread worker;
    private static volatile String status = "[LinearReader] Voxy auto import is idle.";

    private VoxyCompatAutoImporter() {}

    public enum StartResult {
        STARTED,
        ALREADY_RUNNING,
        VOXY_NOT_LOADED,
        NO_SINGLEPLAYER_SERVER,
        NO_CLIENT_WORLD
    }

    public static StartResult start() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!FabricLoader.getInstance().isModLoaded("voxy")) {
            return StartResult.VOXY_NOT_LOADED;
        }
        if (minecraft.level == null) {
            return StartResult.NO_CLIENT_WORLD;
        }
        if (minecraft.getSingleplayerServer() == null) {
            return StartResult.NO_SINGLEPLAYER_SERVER;
        }
        if (!RUNNING.compareAndSet(false, true)) {
            return StartResult.ALREADY_RUNNING;
        }

        Thread thread = new Thread(VoxyCompatAutoImporter::runAutoImport, "lr-voxy-compat-auto");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY + 1);
        worker = thread;
        thread.start();
        return StartResult.STARTED;
    }

    public static String status() {
        return status;
    }

    static void resetForTests() {
        Thread current = worker;
        if (current != null) {
            current.interrupt();
        }
        RUNNING.set(false);
        worker = null;
        status = "[LinearReader] Voxy auto import is idle.";
    }

    private static void runAutoImport() {
        int batches = 0;
        try {
            Minecraft minecraft = Minecraft.getInstance();
            Level level = minecraft.level;
            var server = minecraft.getSingleplayerServer();
            if (level == null || server == null) {
                post("[LinearReader] Voxy auto import stopped: singleplayer world is no longer loaded.");
                return;
            }

            Path worldRoot = server.getWorldPath(LevelResource.ROOT);
            Path regionFolder = DimensionType.getStorageFolder(level.dimension(), worldRoot).resolve("region");

            if (FabricLoader.getInstance().isModLoaded("c2me-opts-accel-opencl")) {
                post("[LinearReader] C2ME OpenCL is loaded. If world loading or Voxy import stalls, disable c2me-opts-accel-opencl and retry.");
            }

            post("[LinearReader] Saving loaded chunks before Voxy auto import starts...");
            saveAllChunks(server);

            while (!Thread.currentThread().isInterrupted()) {
                batches++;
                post("[LinearReader] Preparing Voxy batch " + batches + "...");
                VoxyMcaStager.CleanupResult staleCleanup = VoxyMcaStager.cleanup(worldRoot);
                if (staleCleanup.deleted() > 0 || staleCleanup.failed() > 0) {
                    post("[LinearReader] Removed stale Voxy batch files: deleted " + staleCleanup.deleted()
                            + ", failed " + staleCleanup.failed() + ".");
                }

                long existingMcaFiles = countRegionMcaFiles(regionFolder);
                if (existingMcaFiles > 0) {
                    post("[LinearReader] Voxy auto import stopped: found " + existingMcaFiles
                            + " existing .mca region file(s). Voxy would import those too, so auto mode will not continue.");
                    return;
                }

                VoxyMcaStager.StartResult prepareResult =
                        VoxyMcaStager.start(worldRoot, regionFolder, AUTO_BATCH_FILES, AUTO_BATCH_LINEAR_BYTES);
                if (prepareResult != VoxyMcaStager.StartResult.STARTED) {
                    post("[LinearReader] Voxy auto import stopped: prepare returned " + prepareResult + ".");
                    return;
                }

                waitForPrepare();
                if (!VoxyMcaStager.lastError().isEmpty()) {
                    VoxyMcaStager.abort(worldRoot);
                    post("[LinearReader] Voxy auto import stopped: prepare failed: " + VoxyMcaStager.lastError());
                    return;
                }
                if (VoxyMcaStager.filesFailed() > 0) {
                    VoxyMcaStager.abort(worldRoot);
                    post("[LinearReader] Voxy auto import stopped: "
                            + VoxyMcaStager.filesFailed() + " region file(s) failed to prepare.");
                    return;
                }

                int prepared = VoxyMcaStager.filesWritten();
                int skipped = VoxyMcaStager.filesSkipped();
                if (VoxyMcaStager.filesTotal() == 0) {
                    post("[LinearReader] Voxy auto import complete: no more .linear regions to prepare.");
                    return;
                }

                long mcaFiles = countRegionMcaFiles(regionFolder);
                if (mcaFiles > prepared) {
                    VoxyMcaStager.abort(worldRoot);
                    post("[LinearReader] Voxy auto import stopped: found " + mcaFiles + " .mca region file(s), but only "
                            + prepared + " were staged by LinearReader. Voxy would import too much at once.");
                    return;
                }

                post("[LinearReader] Starting Voxy import for batch " + batches
                        + " (" + prepared + " prepared, " + skipped + " skipped)...");
                VoxyImportHandle importHandle = VoxyReflection.startImport(regionFolder.toFile());
                waitForVoxy(importHandle);

                waitForVoxySavingBacklog(importHandle, batches);

                post("[LinearReader] Voxy finished batch " + batches + "; cleaning temporary .mca files...");
                VoxyMcaStager.CleanupResult cleanup = VoxyMcaStager.cleanup(worldRoot);
                if (cleanup.failed() > 0) {
                    post("[LinearReader] Voxy auto import stopped: cleanup failed for "
                            + cleanup.failed() + " file(s).");
                    return;
                }

                if (VoxyMcaStager.lastBatchComplete()) {
                    post("[LinearReader] Voxy auto import complete after " + batches + " batch(es).");
                    return;
                }
            }
        } catch (Throwable throwable) {
            LinearRuntime.LOGGER.warn("[LinearReader] Voxy auto import failed", throwable);
            post("[LinearReader] Voxy auto import failed: " + throwable.getMessage());
        } finally {
            RUNNING.set(false);
            worker = null;
        }
    }

    private static void saveAllChunks(net.minecraft.server.MinecraftServer server) {
        CompletableFuture<Void> save = new CompletableFuture<>();
        server.execute(() -> {
            try {
                server.saveAllChunks(false, true, false);
                save.complete(null);
            } catch (Throwable throwable) {
                save.completeExceptionally(throwable);
            }
        });
        save.join();
    }

    private static void waitForPrepare() throws InterruptedException {
        while (VoxyMcaStager.isRunning()) {
            Thread.sleep(100L);
        }
    }

    private static void waitForVoxy(VoxyImportHandle handle) throws Exception {
        while (handle.isRunning()) {
            Thread.sleep(250L);
        }
    }

    private static void waitForVoxySavingBacklog(VoxyImportHandle handle, int batch) throws Exception {
        long lastStatus = 0L;
        while (!Thread.currentThread().isInterrupted()) {
            int tasks = handle.savingTaskCount();
            if (tasks <= VOXY_SAVE_BACKLOG_LOW_WATERMARK) {
                return;
            }

            long now = System.currentTimeMillis();
            if (now - lastStatus >= VOXY_SAVE_BACKLOG_STATUS_INTERVAL_MS) {
                post("[LinearReader] Waiting for Voxy save queue after batch " + batch
                        + " (" + tasks + " queued)...");
                lastStatus = now;
            }
            Thread.sleep(500L);
        }
        throw new InterruptedException("Voxy auto import interrupted while waiting for save queue.");
    }

    private static long countRegionMcaFiles(Path regionFolder) throws IOException {
        try (var files = Files.list(regionFolder)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> isRegionMcaName(path.getFileName().toString()))
                    .count();
        }
    }

    private static boolean isRegionMcaName(String name) {
        if (!name.startsWith("r.") || !name.endsWith(".mca")) {
            return false;
        }
        String[] parts = name.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        try {
            Integer.parseInt(parts[1]);
            Integer.parseInt(parts[2]);
            return true;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static void post(String message) {
        status = message;
        LinearRuntime.LOGGER.info(message);
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            if (minecraft.gui != null) {
                minecraft.gui.getChat().addMessage(Component.literal(message));
            }
        });
    }

    private record VoxyImportHandle(Object importer, Method isRunningMethod,
                                    Object savingService, Method getTaskCountMethod) {
        boolean isRunning() throws Exception {
            return (Boolean) isRunningMethod.invoke(importer);
        }

        int savingTaskCount() throws Exception {
            return (Integer) getTaskCountMethod.invoke(savingService);
        }
    }

    private static final class VoxyReflection {
        private static VoxyImportHandle startImport(File regionFolder) throws Exception {
            Minecraft minecraft = Minecraft.getInstance();
            Level level = minecraft.level;
            if (level == null) {
                throw new IllegalStateException("No client world is loaded.");
            }

            Class<?> voxyCommonClass = Class.forName("me.cortex.voxy.commonImpl.VoxyCommon");
            Object instance = voxyCommonClass.getMethod("getInstance").invoke(null);
            if (instance == null) {
                throw new IllegalStateException("Voxy is not enabled.");
            }

            Class<?> worldIdentifierClass = Class.forName("me.cortex.voxy.commonImpl.WorldIdentifier");
            Object engine = worldIdentifierClass.getMethod("ofEngine", Level.class).invoke(null, level);
            if (engine == null) {
                throw new IllegalStateException("Voxy could not resolve a WorldEngine for the current world.");
            }

            Method getServiceManager = instance.getClass().getMethod("getServiceManager");
            Object serviceManager = getServiceManager.invoke(instance);
            Field runCheckerField = findField(instance.getClass(), "savingServiceRateLimiter");
            BooleanSupplier runChecker = (BooleanSupplier) runCheckerField.get(instance);
            Field savingServiceField = findField(instance.getClass(), "savingService");
            Object savingService = savingServiceField.get(instance);
            Method getTaskCount = savingService.getClass().getMethod("getTaskCount");

            Class<?> worldEngineClass = Class.forName("me.cortex.voxy.common.world.WorldEngine");
            Class<?> serviceManagerClass = Class.forName("me.cortex.voxy.common.thread.ServiceManager");
            Class<?> worldImporterClass = Class.forName("me.cortex.voxy.commonImpl.importers.WorldImporter");
            Constructor<?> constructor = worldImporterClass.getConstructor(
                    worldEngineClass,
                    Level.class,
                    serviceManagerClass,
                    BooleanSupplier.class
            );
            Object importer = constructor.newInstance(engine, level, serviceManager, runChecker);
            worldImporterClass.getMethod("importRegionDirectoryAsync", File.class)
                    .invoke(importer, regionFolder);

            Object importManager = instance.getClass().getMethod("getImportManager").invoke(instance);
            Class<?> dataImporterClass = Class.forName("me.cortex.voxy.commonImpl.importers.IDataImporter");
            boolean started = (Boolean) importManager.getClass()
                    .getMethod("tryRunImport", dataImporterClass)
                    .invoke(importManager, importer);
            if (!started) {
                throw new IllegalStateException("Voxy already has an active import for this world.");
            }

            return new VoxyImportHandle(importer, worldImporterClass.getMethod("isRunning"),
                    savingService, getTaskCount);
        }

        private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
            Class<?> current = type;
            while (current != null) {
                try {
                    Field field = current.getDeclaredField(name);
                    field.setAccessible(true);
                    return field;
                } catch (NoSuchFieldException ignored) {
                    current = current.getSuperclass();
                }
            }
            throw new NoSuchFieldException(name);
        }
    }
}
