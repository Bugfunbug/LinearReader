package com.bugfunbug.linearreader.linear;

import com.bugfunbug.linearreader.LinearTestSupport;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class LinearCorpusGenerator {

    private LinearCorpusGenerator() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected exactly one argument: output directory");
        }

        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        generate(root);
    }

    static void generate(Path root) throws IOException {
        LinearTestSupport.resetState();
        LinearTestSupport.deleteTree(root.resolve("files"));
        LinearTestSupport.deleteTree(root.resolve("worlds"));
        Files.createDirectories(root);

        generateStandaloneFiles(root.resolve("files"));
        generateFullSpectrumWorld(root.resolve("worlds/full-spectrum"));
        generateRecoveryWorld(root.resolve("worlds/recovery-valid-backup"), false);
        generateRecoveryWorld(root.resolve("worlds/recovery-corrupt-backup"), true);
        generateLegacyMigrationWorld(root.resolve("worlds/legacy-backup-migration"));
        generateSyncBackupsWorld(root.resolve("worlds/sync-backups"));
        generatePruneWorld(root.resolve("worlds/prune-candidates"));

        LinearTestSupport.resetState();
    }

    private static void generateStandaloneFiles(Path filesRoot) throws IOException {
        Path valid = filesRoot.resolve("valid/r.0.0.linear");
        LinearTestData.writeRegion(valid, fullSpectrumRegionChunks());
        LinearTestData.writeTruncatedCopy(valid, filesRoot.resolve("invalid/r.0.0.truncated.linear"), 7);
        LinearTestData.copyAndCorruptCrc(valid, filesRoot.resolve("invalid/r.0.0.bad-crc.linear"));
        LinearTestData.copyAndCorruptHeader(valid, filesRoot.resolve("invalid/r.0.0.bad-signature.linear"));

        Path tempLive = filesRoot.resolve("r.0.0.linear");
        LinearTestData.writeRegion(tempLive, Map.of(new ChunkPos(0, 0), LinearTestData.simpleChunk("backup-sample", 0, 0)));
        LinearTestData.createBackup(tempLive);
        Files.deleteIfExists(tempLive);
        LinearTestData.copyAndCorruptCrc(
                filesRoot.resolve("backups/r.0.0.linear.bak"),
                filesRoot.resolve("backups/r.0.0.bad-crc.linear.bak")
        );
    }

    private static void generateFullSpectrumWorld(Path worldRoot) throws IOException {
        LinearTestData.writeRegion(worldRoot.resolve("region/r.0.0.linear"), fullSpectrumRegionChunks());
        LinearTestData.writeRegion(
                worldRoot.resolve("poi/r.0.0.linear"),
                Map.of(new ChunkPos(0, 0), LinearTestData.simpleChunk("poi", 0, 0))
        );
        LinearTestData.writeRegion(
                worldRoot.resolve("entities/r.0.0.linear"),
                Map.of(new ChunkPos(0, 0), LinearTestData.simpleChunk("entities", 0, 0))
        );
    }

    private static void generateRecoveryWorld(Path worldRoot, boolean corruptBackupToo) throws IOException {
        Path live = worldRoot.resolve("region/r.0.0.linear");
        LinearTestData.writeRegion(live, fullSpectrumRegionChunks());
        LinearTestData.createBackup(live);
        LinearTestData.corruptCrc(live);
        if (corruptBackupToo) {
            LinearTestData.corruptCrc(worldRoot.resolve("region/backups/r.0.0.linear.bak"));
        }
    }

    private static void generateLegacyMigrationWorld(Path worldRoot) throws IOException {
        Path movedLive = worldRoot.resolve("region/r.0.0.linear");
        LinearTestData.writeRegion(movedLive, Map.of(new ChunkPos(0, 0), LinearTestData.simpleChunk("legacy-moved", 0, 0)));
        LinearTestData.createBackup(movedLive);
        Files.move(
                worldRoot.resolve("region/backups/r.0.0.linear.bak"),
                worldRoot.resolve("region/r.0.0.linear.bak")
        );

        Path dedupedLive = worldRoot.resolve("poi/r.0.0.linear");
        LinearTestData.writeRegion(dedupedLive, Map.of(new ChunkPos(0, 0), LinearTestData.simpleChunk("legacy-deduped", 0, 0)));
        LinearTestData.createBackup(dedupedLive);
        LinearTestData.copy(
                worldRoot.resolve("poi/backups/r.0.0.linear.bak"),
                worldRoot.resolve("poi/r.0.0.linear.bak")
        );

        Path conflictLive = worldRoot.resolve("entities/r.0.0.linear");
        LinearTestData.writeRegion(conflictLive, Map.of(new ChunkPos(0, 0), LinearTestData.simpleChunk("legacy-conflict", 0, 0)));
        LinearTestData.createBackup(conflictLive);
        LinearTestData.copy(
                worldRoot.resolve("entities/backups/r.0.0.linear.bak"),
                worldRoot.resolve("entities/r.0.0.linear.bak")
        );
        byte[] legacyConflictBytes = Files.readAllBytes(worldRoot.resolve("entities/r.0.0.linear.bak"));
        legacyConflictBytes[17] = 21;
        Files.write(worldRoot.resolve("entities/r.0.0.linear.bak"), legacyConflictBytes);
    }

    private static void generateSyncBackupsWorld(Path worldRoot) throws IOException {
        Path refreshRegionLive = worldRoot.resolve("region/r.0.0.linear");
        LinearTestData.writeRegion(refreshRegionLive, Map.of(new ChunkPos(0, 0), LinearTestData.simpleChunk("stale-region", 0, 0)));
        LinearTestData.createBackup(refreshRegionLive);
        LinearTestData.writeRegion(refreshRegionLive, fullSpectrumRegionChunks());

        Path noBackupLive = worldRoot.resolve("region/r.1.0.linear");
        LinearTestData.writeRegion(noBackupLive, Map.of(new ChunkPos(32, 0), LinearTestData.entityChunk(32, 0)));

        Path orphanLive = worldRoot.resolve("region/r.9.9.linear");
        LinearTestData.writeRegion(orphanLive, Map.of(new ChunkPos(288, 288), LinearTestData.simpleChunk("orphan", 288, 288)));
        LinearTestData.createBackup(orphanLive);
        Files.deleteIfExists(orphanLive);

        Path refreshEntitiesLive = worldRoot.resolve("entities/r.0.0.linear");
        LinearTestData.writeRegion(refreshEntitiesLive, Map.of(new ChunkPos(0, 0), LinearTestData.simpleChunk("stale-entities", 0, 0)));
        LinearTestData.createBackup(refreshEntitiesLive);
        LinearTestData.writeRegion(
                refreshEntitiesLive,
                Map.of(new ChunkPos(0, 0), LinearTestData.simpleChunk("fresh-entities", 0, 0))
        );

        Path ignoredCorruptDir = worldRoot.resolve("region/corrupted");
        Files.createDirectories(ignoredCorruptDir);
        LinearTestData.copy(
                worldRoot.resolve("region/backups/r.0.0.linear.bak"),
                ignoredCorruptDir.resolve("r.7.7.linear.bak")
        );
    }

    private static void generatePruneWorld(Path worldRoot) throws IOException {
        LinearTestData.writeRegion(worldRoot.resolve("region/r.0.0.linear"), fullSpectrumRegionChunks());
        LinearTestData.writeRegion(
                worldRoot.resolve("region/r.1.0.linear"),
                Map.of(new ChunkPos(32, 0), LinearTestData.entityChunk(32, 0))
        );
    }

    private static Map<ChunkPos, CompoundTag> fullSpectrumRegionChunks() {
        Map<ChunkPos, CompoundTag> chunks = new LinkedHashMap<>();
        chunks.put(new ChunkPos(0, 0), LinearTestData.pruneCandidateChunk(0, 0));
        chunks.put(new ChunkPos(1, 0), LinearTestData.entityChunk(1, 0));
        chunks.put(new ChunkPos(2, 0), LinearTestData.wrappedPruneCandidateChunk(2, 0));
        chunks.put(new ChunkPos(3, 0), LinearTestData.structureChunk(3, 0));
        chunks.put(new ChunkPos(4, 0), LinearTestData.blockEntityChunk(4, 0));
        return chunks;
    }
}
