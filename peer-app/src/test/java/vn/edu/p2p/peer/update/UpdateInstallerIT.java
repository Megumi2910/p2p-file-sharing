package vn.edu.p2p.peer.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.p2p.peer.util.HashUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateInstallerIT {

    @Test
    void testWrongNonceAbortsWithoutReplacement(@TempDir Path tempDir) throws IOException {
        Path installRoot = tempDir.resolve("app");
        Path updateDir = installRoot.resolve(".p2p-update");
        Files.createDirectories(updateDir);

        Path peerAppJar = installRoot.resolve("peer-app.jar");
        Files.writeString(peerAppJar, "original-peer-app-content");
        String originalSha = HashUtil.sha256(peerAppJar);

        UpdateJournal journal = new UpdateJournal(
                1, "Megumi2910/p2p-file-sharing", UUID.randomUUID().toString(),
                UpdateJournal.Operation.UPDATE, "correct-nonce-12345",
                UpdateJournal.Phase.PREPARED, ProcessHandle.current().pid(), Instant.now(),
                null, null, null, null,
                "peer.properties", installRoot.toString(), "java", "",
                "1.0.0", "1.0.1", originalSha, "candSha", ""
        );
        journal.writeAtomic(updateDir);

        assertThrows(SecurityException.class, () ->
                UpdateInstaller.runHelper(installRoot, "wrong-nonce-67890"));

        // peer-app.jar must remain untouched
        assertEquals(originalSha, HashUtil.sha256(peerAppJar));
    }

    @Test
    void testExclusiveLockBlockedByAnotherPeerAborts(@TempDir Path tempDir) throws Exception {
        Path installRoot = tempDir.resolve("app");
        Path updateDir = installRoot.resolve(".p2p-update");
        Files.createDirectories(updateDir);

        Path peerAppJar = installRoot.resolve("peer-app.jar");
        Files.writeString(peerAppJar, "original-peer-app-content");
        String originalSha = HashUtil.sha256(peerAppJar);

        UpdateJournal journal = new UpdateJournal(
                1, "Megumi2910/p2p-file-sharing", UUID.randomUUID().toString(),
                UpdateJournal.Operation.UPDATE, "test-nonce-lock",
                UpdateJournal.Phase.PREPARED, ProcessHandle.current().pid(), Instant.now(),
                null, null, null, null,
                "peer.properties", installRoot.toString(), "java", "",
                "1.0.0", "1.0.1", originalSha, "candSha", ""
        );
        journal.writeAtomic(updateDir);

        // Simulate another running peer holding the shared runtime lock
        try (UpdateLocks.FileLockHandle peerRuntimeLock = UpdateLocks.acquireSharedRuntimeLock(updateDir)) {
            assertThrows(Exception.class, () ->
                    UpdateLocks.acquireExclusiveRuntimeLock(updateDir, Duration.ofMillis(200)));
        }

        // Original JAR remains untouched
        assertEquals(originalSha, HashUtil.sha256(peerAppJar));
    }

    @Test
    void testRecoveryFromInterruptedSwitchingRestoresPreviousJar(@TempDir Path tempDir) throws Exception {
        Path installRoot = tempDir.resolve("app");
        Path updateDir = installRoot.resolve(".p2p-update");
        Files.createDirectories(updateDir);

        Path previousJar = updateDir.resolve("previous.jar");
        Files.writeString(previousJar, "previous-known-working-binary");
        String previousSha = HashUtil.sha256(previousJar);

        Path peerAppJar = installRoot.resolve("peer-app.jar");
        // peer-app.jar was left in corrupted/missing state during crash
        Files.deleteIfExists(peerAppJar);

        UpdateJournal journal = new UpdateJournal(
                1, "Megumi2910/p2p-file-sharing", UUID.randomUUID().toString(),
                UpdateJournal.Operation.UPDATE, "test-tx-recover",
                UpdateJournal.Phase.SWITCHING, 99999999L, Instant.now(),
                null, null, null, null,
                "peer.properties", installRoot.toString(), "java", "",
                "1.0.0", "1.0.1", previousSha, "candSha", ""
        );
        journal.writeAtomic(updateDir);

        UpdateInstaller.runRecovery(installRoot, null, null);

        // Assert peer-app.jar was restored from previous.jar
        assertTrue(Files.exists(peerAppJar));
        assertEquals(previousSha, HashUtil.sha256(peerAppJar));

        UpdateJournal recoveredJournal = UpdateJournal.read(updateDir);
        assertNotNull(recoveredJournal);
        assertEquals(UpdateJournal.Phase.ROLLED_BACK, recoveredJournal.phase());
    }

    @Test
    void testRetainedCommittedInstallCleansStaging(@TempDir Path tempDir) throws Exception {
        Path installRoot = tempDir.resolve("app");
        Path updateDir = installRoot.resolve(".p2p-update");
        Files.createDirectories(updateDir);

        Path peerAppJar = installRoot.resolve("peer-app.jar");
        Files.writeString(peerAppJar, "new-version-content");
        String newSha = HashUtil.sha256(peerAppJar);

        Path partFile = updateDir.resolve("candidate.jar.part");
        Files.writeString(partFile, "incomplete-staging-bytes");

        UpdateJournal journal = new UpdateJournal(
                1, "Megumi2910/p2p-file-sharing", UUID.randomUUID().toString(),
                UpdateJournal.Operation.UPDATE, "test-tx-committed",
                UpdateJournal.Phase.COMMITTED, 99999999L, Instant.now(),
                null, null, null, null,
                "peer.properties", installRoot.toString(), "java", "",
                "1.0.0", "1.0.1", "oldSha", newSha, ""
        );
        journal.writeAtomic(updateDir);

        UpdateInstaller.runRecovery(installRoot, null, null);

        // Assert candidate.jar.part is cleaned up
        assertFalse(Files.exists(partFile));
        // New JAR remains untouched
        assertEquals(newSha, HashUtil.sha256(peerAppJar));
    }

    @Test
    void testPreservationOfUserDataFilesAcrossUpdateAndRecovery(@TempDir Path tempDir) throws Exception {
        Path installRoot = tempDir.resolve("app");
        Path updateDir = installRoot.resolve(".p2p-update");
        Files.createDirectories(updateDir);

        Path userConfig = installRoot.resolve("peer.properties");
        Files.writeString(userConfig, "peer.id=p1\npeer.name=UserOriginalName\n");
        byte[] originalConfigBytes = Files.readAllBytes(userConfig);

        Path downloadDir = installRoot.resolve("downloads");
        Files.createDirectories(downloadDir);
        Path completeFile = downloadDir.resolve("important_document.pdf");
        Files.writeString(completeFile, "user-important-data");
        Path partFile = downloadDir.resolve("video.mp4.part");
        Files.writeString(partFile, "partial-download-bytes");
        Path metaFile = downloadDir.resolve("video.mp4.meta");
        Files.writeString(metaFile, "metadata-bytes");

        Path sharedDir = installRoot.resolve("shared");
        Files.createDirectories(sharedDir);
        Path sharedFile = sharedDir.resolve("shared_file.bin");
        Files.writeString(sharedFile, "shared-data-content");

        // Simulate recovery
        Path previousJar = updateDir.resolve("previous.jar");
        Files.writeString(previousJar, "prev-jar");
        String prevSha = HashUtil.sha256(previousJar);

        UpdateJournal journal = new UpdateJournal(
                1, "Megumi2910/p2p-file-sharing", UUID.randomUUID().toString(),
                UpdateJournal.Operation.UPDATE, "test-tx-data-preservation",
                UpdateJournal.Phase.SWITCHING, 99999999L, Instant.now(),
                null, null, null, null,
                userConfig.toString(), installRoot.toString(), "java", "",
                "1.0.0", "1.0.1", prevSha, "candSha", ""
        );
        journal.writeAtomic(updateDir);

        UpdateInstaller.runRecovery(installRoot, null, null);

        // Assert all user data files are byte-for-byte preserved
        assertEquals(new String(originalConfigBytes), Files.readString(userConfig));
        assertEquals("user-important-data", Files.readString(completeFile));
        assertEquals("partial-download-bytes", Files.readString(partFile));
        assertEquals("metadata-bytes", Files.readString(metaFile));
        assertEquals("shared-data-content", Files.readString(sharedFile));
    }
}
