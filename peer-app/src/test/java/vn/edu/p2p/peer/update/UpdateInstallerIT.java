package vn.edu.p2p.peer.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.p2p.peer.util.HashUtil;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateInstallerIT {

    private static final String TX_ID_1 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String TX_ID_2 = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210";
    private static final String DUMMY_SHA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    private static String resolveJavaPath() {
        String ext = System.getProperty("os.name", "").toLowerCase().contains("win") ? ".exe" : "";
        return Path.of(System.getProperty("java.home"), "bin", "java" + ext).toAbsolutePath().normalize().toString();
    }

    public static class LockHolderChild {
        public static void main(String[] args) {
            try {
                Path updateDir = Path.of(args[0]);
                try (UpdateLocks.FileLockHandle lock = UpdateLocks.acquireSharedRuntimeLock(updateDir)) {
                    System.out.println("LOCKED");
                    System.out.flush();
                    Thread.sleep(60_000);
                }
            } catch (Exception ex) {
                ex.printStackTrace();
                System.exit(1);
            }
        }
    }

    @Test
    void testWrongNonceAbortsWithoutReplacement(@TempDir Path tempDir) throws IOException {
        Path installRoot = tempDir.resolve("app").toAbsolutePath().normalize();
        Path updateDir = installRoot.resolve(".p2p-update");
        Files.createDirectories(installRoot);

        String installId = UpdateJournal.getOrCreateInstallId(updateDir, BuildInfo.DEFAULT_REPOSITORY, installRoot);

        Path peerAppJar = installRoot.resolve("peer-app.jar");
        Files.writeString(peerAppJar, "original-peer-app-content");
        String originalSha = HashUtil.sha256(peerAppJar);

        Path configPath = installRoot.resolve("peer.properties");
        Files.writeString(configPath, "peer.id=p1\n");

        ProcessHandle self = ProcessHandle.current();
        Instant selfStart = self.info().startInstant().orElse(Instant.now());

        UpdateJournal journal = new UpdateJournal(
                1, BuildInfo.DEFAULT_REPOSITORY, installId,
                UpdateJournal.Operation.UPDATE, TX_ID_1,
                UpdateJournal.Phase.PREPARED, self.pid(), selfStart,
                null, null, null, null,
                configPath.toString(), installRoot.toString(), resolveJavaPath(), "",
                "1.0.0", "1.0.1", originalSha, DUMMY_SHA, ""
        );
        journal.writeAtomic(updateDir);

        assertThrows(SecurityException.class, () ->
                UpdateInstaller.runHelper(installRoot, TX_ID_2));

        // peer-app.jar must remain untouched
        assertEquals(originalSha, HashUtil.sha256(peerAppJar));
    }

    @Test
    void testExclusiveLockBlockedByAnotherPeerAborts(@TempDir Path tempDir) throws Exception {
        Path installRoot = tempDir.resolve("app").toAbsolutePath().normalize();
        Path updateDir = installRoot.resolve(".p2p-update");
        Files.createDirectories(installRoot);

        String installId = UpdateJournal.getOrCreateInstallId(updateDir, BuildInfo.DEFAULT_REPOSITORY, installRoot);

        Path peerAppJar = installRoot.resolve("peer-app.jar");
        Files.writeString(peerAppJar, "original-peer-app-content");
        String originalSha = HashUtil.sha256(peerAppJar);

        Path configPath = installRoot.resolve("peer.properties");
        Files.writeString(configPath, "peer.id=p1\n");

        ProcessHandle self = ProcessHandle.current();
        Instant selfStart = self.info().startInstant().orElse(Instant.now());

        UpdateJournal journal = new UpdateJournal(
                1, BuildInfo.DEFAULT_REPOSITORY, installId,
                UpdateJournal.Operation.UPDATE, TX_ID_1,
                UpdateJournal.Phase.PREPARED, self.pid(), selfStart,
                null, null, null, null,
                configPath.toString(), installRoot.toString(), resolveJavaPath(), "",
                "1.0.0", "1.0.1", originalSha, DUMMY_SHA, ""
        );
        journal.writeAtomic(updateDir);

        // Spawn a competing child process that holds the shared runtime lock
        Process child = new ProcessBuilder(
                resolveJavaPath(),
                "-cp", System.getProperty("java.class.path"),
                LockHolderChild.class.getName(),
                updateDir.toString()
        ).start();

        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean childLocked = false;
            while (System.nanoTime() < deadline && child.isAlive()) {
                if (reader.ready()) {
                    String line = reader.readLine();
                    if ("LOCKED".equals(line)) {
                        childLocked = true;
                        break;
                    }
                }
                Thread.sleep(50);
            }
            assertTrue(childLocked, "Child process should acquire shared runtime lock");

            // Attempting exclusive lock must fail
            assertThrows(Exception.class, () ->
                    UpdateLocks.acquireExclusiveRuntimeLock(updateDir, Duration.ofMillis(300)));
        } finally {
            child.destroyForcibly();
            child.waitFor(2, TimeUnit.SECONDS);
        }

        // Original JAR remains untouched
        assertEquals(originalSha, HashUtil.sha256(peerAppJar));
    }

    @Test
    void testRecoveryFromInterruptedSwitchingRestoresPreviousJar(@TempDir Path tempDir) throws Exception {
        Path installRoot = tempDir.resolve("app").toAbsolutePath().normalize();
        Path updateDir = installRoot.resolve(".p2p-update");
        Files.createDirectories(installRoot);

        String installId = UpdateJournal.getOrCreateInstallId(updateDir, BuildInfo.DEFAULT_REPOSITORY, installRoot);

        Path previousJar = updateDir.resolve("previous.jar");
        Files.writeString(previousJar, "previous-known-working-binary");
        String previousSha = HashUtil.sha256(previousJar);

        Path peerAppJar = installRoot.resolve("peer-app.jar");
        Files.deleteIfExists(peerAppJar);

        Path configPath = installRoot.resolve("peer.properties");
        Files.writeString(configPath, "peer.id=p1\n");

        UpdateJournal journal = new UpdateJournal(
                1, BuildInfo.DEFAULT_REPOSITORY, installId,
                UpdateJournal.Operation.UPDATE, TX_ID_1,
                UpdateJournal.Phase.SWITCHING, 99999999L, Instant.now(),
                99999998L, Instant.now(), null, null,
                configPath.toString(), installRoot.toString(), resolveJavaPath(), "",
                "1.0.0", "1.0.1", previousSha, DUMMY_SHA, ""
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
        Path installRoot = tempDir.resolve("app").toAbsolutePath().normalize();
        Path updateDir = installRoot.resolve(".p2p-update");
        Files.createDirectories(installRoot);

        String installId = UpdateJournal.getOrCreateInstallId(updateDir, BuildInfo.DEFAULT_REPOSITORY, installRoot);

        Path peerAppJar = installRoot.resolve("peer-app.jar");
        Files.writeString(peerAppJar, "new-version-content");
        String newSha = HashUtil.sha256(peerAppJar);

        Path partFile = updateDir.resolve("candidate.jar.part");
        Files.writeString(partFile, "incomplete-staging-bytes");

        Path configPath = installRoot.resolve("peer.properties");
        Files.writeString(configPath, "peer.id=p1\n");

        UpdateJournal journal = new UpdateJournal(
                1, BuildInfo.DEFAULT_REPOSITORY, installId,
                UpdateJournal.Operation.UPDATE, TX_ID_1,
                UpdateJournal.Phase.COMMITTED, 99999999L, Instant.now(),
                99999998L, Instant.now(), 99999997L, Instant.now(),
                configPath.toString(), installRoot.toString(), resolveJavaPath(), "",
                "1.0.0", "1.0.1", DUMMY_SHA, newSha, ""
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
        Path installRoot = tempDir.resolve("app").toAbsolutePath().normalize();
        Path updateDir = installRoot.resolve(".p2p-update");
        Files.createDirectories(installRoot);

        String installId = UpdateJournal.getOrCreateInstallId(updateDir, BuildInfo.DEFAULT_REPOSITORY, installRoot);

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
                1, BuildInfo.DEFAULT_REPOSITORY, installId,
                UpdateJournal.Operation.UPDATE, TX_ID_1,
                UpdateJournal.Phase.SWITCHING, 99999999L, Instant.now(),
                99999998L, Instant.now(), null, null,
                userConfig.toString(), installRoot.toString(), resolveJavaPath(), "",
                "1.0.0", "1.0.1", prevSha, DUMMY_SHA, ""
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
