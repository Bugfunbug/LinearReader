package com.bugfunbug.linearreader;

import com.bugfunbug.linearreader.linear.LinearRegionFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LinearRuntimeLegacyBackupMigrationTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        LinearTestSupport.resetState();
    }

    @AfterEach
    void tearDown() {
        LinearTestSupport.resetState();
    }

    @Test
    void migratesLegacyBackupsIntoCanonicalLayout() throws IOException {
        Path worldRoot = LinearTestSupport.copyCorpusTree(
                "worlds/legacy-backup-migration",
                tempDir.resolve("legacy-backup-migration")
        );

        LinearRuntime.LegacyBackupMigrationResult result = LinearRuntime.migrateLegacyBackups(worldRoot);

        assertEquals(1, result.moved());
        assertEquals(1, result.deduped());
        assertEquals(1, result.conflicts());

        Path movedLegacy = worldRoot.resolve("region/r.0.0.linear.bak");
        Path movedCanonical = worldRoot.resolve("region/backups/r.0.0.linear.bak");
        Path dedupedLegacy = worldRoot.resolve("poi/r.0.0.linear.bak");
        Path dedupedCanonical = worldRoot.resolve("poi/backups/r.0.0.linear.bak");
        Path conflictLegacy = worldRoot.resolve("entities/r.0.0.linear.bak");
        Path conflictCanonical = worldRoot.resolve("entities/backups/r.0.0.linear.bak");
        Path conflictMovedAside = worldRoot.resolve("entities/backups/r.0.0.linear.bak.legacy-conflict");

        assertFalse(Files.exists(movedLegacy));
        assertTrue(Files.exists(movedCanonical));
        assertTrue(LinearRegionFile.verifyOnDisk(movedCanonical).ok);

        assertFalse(Files.exists(dedupedLegacy));
        assertTrue(Files.exists(dedupedCanonical));
        assertTrue(LinearRegionFile.verifyOnDisk(dedupedCanonical).ok);

        assertFalse(Files.exists(conflictLegacy));
        assertTrue(Files.exists(conflictCanonical));
        assertTrue(Files.exists(conflictMovedAside));
        assertTrue(LinearRegionFile.verifyOnDisk(conflictCanonical).ok);
        assertTrue(LinearRegionFile.verifyOnDisk(conflictMovedAside).ok);
    }
}
