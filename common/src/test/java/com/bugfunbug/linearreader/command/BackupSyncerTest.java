package com.bugfunbug.linearreader.command;

import com.bugfunbug.linearreader.LinearTestSupport;
import com.bugfunbug.linearreader.linear.LinearRegionFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackupSyncerTest {

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
    void reconcilesExistingBackupsWithoutBackfillingMissingOnes() throws IOException {
        Path worldRoot = LinearTestSupport.copyCorpusTree(
                "worlds/sync-backups",
                tempDir.resolve("sync-backups")
        );
        Path refreshRegionBackup = worldRoot.resolve("region/backups/r.0.0.linear.bak");
        Path refreshEntitiesBackup = worldRoot.resolve("entities/backups/r.0.0.linear.bak");
        Path orphanBackup = worldRoot.resolve("region/backups/r.9.9.linear.bak");
        Path missingBackup = worldRoot.resolve("region/backups/r.1.0.linear.bak");

        byte[] oldRegionBackup = Files.readAllBytes(refreshRegionBackup);
        byte[] oldEntitiesBackup = Files.readAllBytes(refreshEntitiesBackup);

        BackupSyncer.SyncAnalysis analysis = BackupSyncer.analyzeWorld(worldRoot);
        assertEquals(2, analysis.refreshCount());
        assertEquals(1, analysis.orphanDeleteCount());
        assertEquals(1, analysis.liveWithoutBackupCount());
        assertEquals(0, analysis.skippedBusyFiles());
        assertEquals(3, analysis.files().size());
        assertTrue(BackupSyncer.validatePlan(analysis));

        BackupSyncer.SyncExecutionResult result = BackupSyncer.applyPlan(analysis);
        assertEquals(2, result.refreshed());
        assertEquals(1, result.deletedOrphans());

        assertFalse(Files.exists(orphanBackup));
        assertFalse(Files.exists(missingBackup));
        assertFalse(Arrays.equals(oldRegionBackup, Files.readAllBytes(refreshRegionBackup)));
        assertFalse(Arrays.equals(oldEntitiesBackup, Files.readAllBytes(refreshEntitiesBackup)));
        assertTrue(LinearRegionFile.verifyOnDisk(refreshRegionBackup).ok);
        assertTrue(LinearRegionFile.verifyOnDisk(refreshEntitiesBackup).ok);
    }
}
