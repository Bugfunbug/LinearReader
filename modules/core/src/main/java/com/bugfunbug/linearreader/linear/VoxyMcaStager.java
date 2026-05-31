package com.bugfunbug.linearreader.linear;

import com.bugfunbug.linearreader.LinearRuntime;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Creates temporary vanilla Anvil files for Voxy's file importer.
 *
 * Voxy currently scans region folders for r.<x>.<z>.mca and parses the Anvil
 * sector table directly. It does not know how to import .linear files. This
 * bridge stages .mca sidecars next to the current dimension's .linear files,
 * then removes only those sidecars after the user has run /voxy import current.
 */
public final class VoxyMcaStager {

    private static final String MANIFEST_NAME = "linearreader-voxy-compat-manifest.txt";
    private static final String STATE_NAME = "linearreader-voxy-compat-state.properties";
    private static final int DEFAULT_BATCH_FILES = 4;

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static volatile Thread worker;
    private static volatile Path lastRegionFolder;
    private static volatile Path lastManifest;
    private static volatile String lastError = "";
    private static volatile boolean lastBatchComplete = false;

    private static final AtomicInteger FILES_DONE = new AtomicInteger(0);
    private static final AtomicInteger FILES_TOTAL = new AtomicInteger(0);
    private static final AtomicInteger FILES_WRITTEN = new AtomicInteger(0);
    private static final AtomicInteger FILES_SKIPPED = new AtomicInteger(0);
    private static final AtomicInteger FILES_FAILED = new AtomicInteger(0);

    private VoxyMcaStager() {}

    public enum StartResult {
        STARTED,
        ALREADY_RUNNING,
        CLEANUP_REQUIRED,
        REGION_FOLDER_MISSING
    }

    public record CleanupResult(int deleted, int missing, int failed, boolean manifestDeleted) {}

    public static boolean isRunning() {
        return RUNNING.get();
    }

    public static int filesDone() {
        return FILES_DONE.get();
    }

    public static int filesTotal() {
        return FILES_TOTAL.get();
    }

    public static int filesWritten() {
        return FILES_WRITTEN.get();
    }

    public static int filesSkipped() {
        return FILES_SKIPPED.get();
    }

    public static int filesFailed() {
        return FILES_FAILED.get();
    }

    public static Path lastRegionFolder() {
        return lastRegionFolder;
    }

    public static Path lastManifest() {
        return lastManifest;
    }

    public static String lastError() {
        return lastError;
    }

    public static boolean lastBatchComplete() {
        return lastBatchComplete;
    }

    public static int defaultBatchFiles() {
        return DEFAULT_BATCH_FILES;
    }

    public static Path manifestPath(Path worldRoot) {
        return worldRoot.resolve(MANIFEST_NAME);
    }

    public static Path statePath(Path regionFolder) {
        return regionFolder.resolve(STATE_NAME);
    }

    public static StartResult start(Path worldRoot, Path regionFolder) throws IOException {
        return start(worldRoot, regionFolder, DEFAULT_BATCH_FILES);
    }

    public static StartResult start(Path worldRoot, Path regionFolder, int maxFiles) throws IOException {
        Objects.requireNonNull(worldRoot, "worldRoot");
        Objects.requireNonNull(regionFolder, "regionFolder");
        if (maxFiles <= 0) {
            throw new IllegalArgumentException("maxFiles must be positive");
        }

        if (!Files.isDirectory(regionFolder)) {
            return StartResult.REGION_FOLDER_MISSING;
        }

        Path manifest = manifestPath(worldRoot);
        if (RUNNING.get()) {
            return StartResult.ALREADY_RUNNING;
        }
        if (Files.exists(manifest)) {
            return StartResult.CLEANUP_REQUIRED;
        }

        if (!RUNNING.compareAndSet(false, true)) {
            return StartResult.ALREADY_RUNNING;
        }

        FILES_DONE.set(0);
        FILES_TOTAL.set(0);
        FILES_WRITTEN.set(0);
        FILES_SKIPPED.set(0);
        FILES_FAILED.set(0);
        lastError = "";
        lastBatchComplete = false;
        lastRegionFolder = regionFolder;
        lastManifest = manifest;

        Thread thread = new Thread(() -> {
            try {
                doStage(worldRoot, regionFolder, manifest, maxFiles);
            } catch (Exception e) {
                lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                LinearRuntime.LOGGER.warn("[LinearReader] Voxy MCA staging failed: {}", lastError, e);
            } finally {
                RUNNING.set(false);
                worker = null;
            }
        }, "lr-voxy-mca-stage");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY + 1);
        worker = thread;
        thread.start();
        return StartResult.STARTED;
    }

    public static CleanupResult cleanup(Path worldRoot) throws IOException {
        return deleteManifestEntries(worldRoot, true);
    }

    public static CleanupResult abort(Path worldRoot) throws IOException {
        return deleteManifestEntries(worldRoot, false);
    }

    private static CleanupResult deleteManifestEntries(Path worldRoot, boolean advanceState) throws IOException {
        if (RUNNING.get()) {
            throw new IllegalStateException("Voxy MCA staging is still running.");
        }

        Path manifest = manifestPath(worldRoot);
        if (!Files.exists(manifest)) {
            return new CleanupResult(0, 0, 0, false);
        }

        Path normalizedRoot = worldRoot.toAbsolutePath().normalize();
        int deleted = 0;
        int missing = 0;
        int failed = 0;
        String stateRegion = null;
        String stateLastPrepared = null;
        boolean stateComplete = false;

        for (String rawLine : Files.readAllLines(manifest)) {
            String entry = rawLine.strip();
            if (entry.isEmpty() || entry.startsWith("#")) {
                continue;
            }

            int separator = entry.indexOf(':');
            String type = separator >= 0 ? entry.substring(0, separator) : "";
            String relative = separator >= 0 ? entry.substring(separator + 1) : entry;
            if ("stateRegion".equals(type)) {
                stateRegion = relative;
                continue;
            }
            if ("stateLastPrepared".equals(type)) {
                stateLastPrepared = relative;
                continue;
            }
            if ("stateComplete".equals(type)) {
                stateComplete = Boolean.parseBoolean(relative);
                continue;
            }

            Path path = normalizedRoot.resolve(relative).normalize();
            if (!path.startsWith(normalizedRoot)) {
                failed++;
                LinearRuntime.LOGGER.warn("[LinearReader] Refusing to delete staged Voxy path outside world: {}", entry);
                continue;
            }

            try {
                if (Files.deleteIfExists(path)) {
                    deleted++;
                } else {
                    missing++;
                }
            } catch (IOException e) {
                failed++;
                LinearRuntime.LOGGER.warn("[LinearReader] Could not delete staged Voxy file {}: {}",
                        path, e.getMessage());
            }
        }

        boolean manifestDeleted = false;
        if (failed == 0) {
            if (advanceState && stateRegion != null && stateLastPrepared != null) {
                Path regionFolder = normalizedRoot.resolve(stateRegion).normalize();
                if (regionFolder.startsWith(normalizedRoot)) {
                    writeState(regionFolder, new PrepareState(stateLastPrepared, stateComplete));
                } else {
                    throw new IOException("Refusing to write Voxy state outside world: " + stateRegion);
                }
            }
            manifestDeleted = Files.deleteIfExists(manifest);
        }

        return new CleanupResult(deleted, missing, failed, manifestDeleted);
    }

    static void resetForTests() {
        Thread current = worker;
        if (current != null) {
            current.interrupt();
        }
        RUNNING.set(false);
        worker = null;
        lastRegionFolder = null;
        lastManifest = null;
        lastError = "";
        lastBatchComplete = false;
        FILES_DONE.set(0);
        FILES_TOTAL.set(0);
        FILES_WRITTEN.set(0);
        FILES_SKIPPED.set(0);
        FILES_FAILED.set(0);
    }

    private static void doStage(Path worldRoot, Path regionFolder, Path manifest, int maxFiles) throws IOException {
        List<Path> linearFiles = collectLinearRegionFiles(regionFolder);
        PrepareState state = readState(regionFolder);
        BatchSelection selection = selectBatch(linearFiles, state.lastPreparedFile(), maxFiles);
        List<Path> batchFiles = selection.files();
        lastBatchComplete = selection.complete();
        FILES_TOTAL.set(batchFiles.size());

        if (linearFiles.isEmpty()) {
            LinearRuntime.LOGGER.info("[LinearReader] No .linear files found in {} for Voxy staging.", regionFolder);
            writeState(regionFolder, new PrepareState("", true));
            lastBatchComplete = true;
            return;
        }

        if (batchFiles.isEmpty()) {
            LinearRuntime.LOGGER.info("[LinearReader] Voxy compatibility prepare is complete for {}.", regionFolder);
            writeState(regionFolder, new PrepareState(state.lastPreparedFile(), true));
            lastBatchComplete = true;
            return;
        }

        Files.writeString(manifest,
                "# LinearReader Voxy MCA staging manifest\n"
                        + "# Run /linearreader voxy-compat cleanup after /voxy import current finishes.\n"
                        + "# Next prepare advances to the following batch.\n"
                        + "# Only files listed here are eligible for cleanup.\n",
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
        recordBatchState(worldRoot, manifest, regionFolder, selection.lastPreparedFile(), selection.complete());

        int threadCount = 1;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount, r -> {
            Thread thread = new Thread(r, "lr-voxy-mca-stage-worker");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY + 1);
            return thread;
        });

        try {
            for (Path linearFile : batchFiles) {
                executor.execute(() -> stageOne(worldRoot, regionFolder, manifest, linearFile));
            }
        } finally {
            executor.shutdown();
            try {
                while (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
                    if (Thread.currentThread().isInterrupted()) {
                        executor.shutdownNow();
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        LinearRuntime.LOGGER.info(
                "[LinearReader] Voxy MCA staging batch finished: {} written, {} skipped, {} failed.",
                FILES_WRITTEN.get(), FILES_SKIPPED.get(), FILES_FAILED.get());
    }

    private static List<Path> collectLinearRegionFiles(Path regionFolder) throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(regionFolder)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> parseRegionName(path.getFileName().toString(), ".linear") != null)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .forEach(files::add);
        }
        return files;
    }

    private static BatchSelection selectBatch(List<Path> linearFiles, String lastPreparedFile, int maxFiles) {
        List<Path> selected = new ArrayList<>(Math.min(maxFiles, linearFiles.size()));
        boolean afterLast = lastPreparedFile == null || lastPreparedFile.isEmpty();
        String selectedLast = lastPreparedFile == null ? "" : lastPreparedFile;
        int selectedLastIndex = -1;

        for (int i = 0; i < linearFiles.size(); i++) {
            Path file = linearFiles.get(i);
            String fileName = file.getFileName().toString();
            if (!afterLast) {
                afterLast = fileName.compareTo(lastPreparedFile) > 0;
            }
            if (!afterLast) {
                continue;
            }
            selected.add(file);
            selectedLast = fileName;
            selectedLastIndex = i;
            if (selected.size() >= maxFiles) {
                break;
            }
        }

        boolean complete = selected.isEmpty() || selectedLastIndex >= linearFiles.size() - 1;
        return new BatchSelection(selected, selectedLast, complete);
    }

    private static PrepareState readState(Path regionFolder) throws IOException {
        Path statePath = statePath(regionFolder);
        if (!Files.exists(statePath)) {
            return new PrepareState("", false);
        }

        Properties properties = new Properties();
        try (var input = Files.newInputStream(statePath)) {
            properties.load(input);
        }
        if (Boolean.parseBoolean(properties.getProperty("complete", "false"))) {
            return new PrepareState(lastExistingPreparedFile(regionFolder,
                    properties.getProperty("lastPreparedFile", "")), true);
        }
        return new PrepareState(properties.getProperty("lastPreparedFile", ""), false);
    }

    private static String lastExistingPreparedFile(Path regionFolder, String lastPreparedFile) {
        if (lastPreparedFile == null || lastPreparedFile.isEmpty()) {
            return "";
        }
        if (Files.exists(regionFolder.resolve(lastPreparedFile))) {
            return lastPreparedFile;
        }
        return "";
    }

    private static void writeState(Path regionFolder, PrepareState state) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("lastPreparedFile", state.lastPreparedFile() == null ? "" : state.lastPreparedFile());
        properties.setProperty("complete", Boolean.toString(state.complete()));
        try (var output = Files.newOutputStream(statePath(regionFolder),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            properties.store(output, "LinearReader Voxy compatibility prepare state");
        }
    }

    private static synchronized void recordBatchState(Path worldRoot, Path manifest, Path regionFolder,
                                                      String lastPreparedFile, boolean complete)
            throws IOException {
        String entries = "stateRegion:" + toManifestPath(worldRoot, regionFolder) + "\n"
                + "stateLastPrepared:" + (lastPreparedFile == null ? "" : lastPreparedFile) + "\n"
                + "stateComplete:" + complete + "\n";
        Files.writeString(manifest, entries,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND);
    }

    private static void stageOne(Path worldRoot, Path regionFolder, Path manifest, Path linearPath) {
        String sourceName = linearPath.getFileName().toString();
        int[] regionCoords = parseRegionName(sourceName, ".linear");
        if (regionCoords == null) {
            FILES_SKIPPED.incrementAndGet();
            FILES_DONE.incrementAndGet();
            return;
        }

        String mcaName = sourceName.substring(0, sourceName.length() - ".linear".length()) + ".mca";
        Path mcaDest = regionFolder.resolve(mcaName);
        Path tmpDest = regionFolder.resolve("." + mcaName + ".linearreader-voxy.tmp");

        try {
            if (Files.exists(mcaDest)) {
                FILES_SKIPPED.incrementAndGet();
                return;
            }
            if (hasExternalChunkSidecars(regionFolder, regionCoords[0], regionCoords[1])) {
                FILES_SKIPPED.incrementAndGet();
                return;
            }

            Files.deleteIfExists(tmpDest);
            exportOne(linearPath, tmpDest, regionFolder, regionCoords[0], regionCoords[1]);
            moveWithoutReplace(tmpDest, mcaDest);
            recordStagedFiles(worldRoot, manifest, mcaDest, regionFolder, regionCoords[0], regionCoords[1]);
            FILES_WRITTEN.incrementAndGet();
        } catch (Exception e) {
            FILES_FAILED.incrementAndGet();
            LinearRuntime.LOGGER.warn("[LinearReader] Failed to stage {} for Voxy: {}",
                    sourceName, e.getMessage());
            try {
                Files.deleteIfExists(tmpDest);
            } catch (IOException cleanupFailure) {
                LinearRuntime.LOGGER.warn("[LinearReader] Could not remove failed Voxy staging temp file {}: {}",
                        tmpDest, cleanupFailure.getMessage());
            }
        } finally {
            FILES_DONE.incrementAndGet();
        }
    }

    private static void exportOne(Path linearPath, Path mcaDest, Path mcaFolder, int regionX, int regionZ)
            throws IOException {

        Files.createDirectories(mcaFolder);

        LinearRegionFile linear = new LinearRegionFile(linearPath, false);
        try {
            try (RegionFile mca = LinearRuntime.openVanillaRegionFile(mcaDest, mcaFolder, false)) {
                for (int i = 0; i < 1024; i++) {
                    int lx = i % 32;
                    int lz = i / 32;
                    ChunkPos pos = new ChunkPos(regionX * 32 + lx, regionZ * 32 + lz);

                    try (DataInputStream dis = linear.read(pos)) {
                        if (dis == null) continue;
                        byte[] nbt = dis.readAllBytes();
                        try (DataOutputStream dos = mca.getChunkDataOutputStream(pos)) {
                            dos.write(nbt);
                        }
                    }
                }
            }
        } finally {
            LinearRegionFile.ALL_OPEN.remove(linear);
            linear.releaseChunkData();
        }
    }

    private static void moveWithoutReplace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(source, target);
        }
    }

    private static synchronized void recordStagedFiles(Path worldRoot, Path manifest, Path mcaDest,
                                                       Path regionFolder, int regionX, int regionZ)
            throws IOException {
        StringBuilder entries = new StringBuilder();
        entries.append("mca:")
                .append(toManifestPath(worldRoot, mcaDest))
                .append('\n');

        for (Path mccFile : externalChunkSidecars(regionFolder, regionX, regionZ)) {
            if (Files.exists(mccFile)) {
                entries.append("mcc:")
                        .append(toManifestPath(worldRoot, mccFile))
                        .append('\n');
            }
        }

        Files.writeString(manifest, entries.toString(),
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND);
    }

    private static String toManifestPath(Path worldRoot, Path file) {
        return worldRoot.toAbsolutePath().normalize()
                .relativize(file.toAbsolutePath().normalize())
                .toString()
                .replace('\\', '/');
    }

    private static boolean hasExternalChunkSidecars(Path regionFolder, int regionX, int regionZ) {
        for (Path path : externalChunkSidecars(regionFolder, regionX, regionZ)) {
            if (Files.exists(path)) {
                return true;
            }
        }
        return false;
    }

    private static List<Path> externalChunkSidecars(Path regionFolder, int regionX, int regionZ) {
        List<Path> sidecars = new ArrayList<>();
        int baseChunkX = regionX * 32;
        int baseChunkZ = regionZ * 32;
        for (int lx = 0; lx < 32; lx++) {
            for (int lz = 0; lz < 32; lz++) {
                sidecars.add(regionFolder.resolve("c." + (baseChunkX + lx) + "." + (baseChunkZ + lz) + ".mcc"));
            }
        }
        return sidecars;
    }

    private static int[] parseRegionName(String name, String extension) {
        if (!name.startsWith("r.") || !name.endsWith(extension)) {
            return null;
        }
        String stem = name.substring(0, name.length() - extension.length());
        String[] parts = stem.split("\\.");
        if (parts.length != 3) {
            return null;
        }
        try {
            return new int[]{Integer.parseInt(parts[1]), Integer.parseInt(parts[2])};
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private record BatchSelection(List<Path> files, String lastPreparedFile, boolean complete) {}

    private record PrepareState(String lastPreparedFile, boolean complete) {}
}
