package vn.edu.p2p.peer.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.p2p.peer.util.HashUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestartCoordinatorTest {

    private static final String VALID_HEX_64 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String DUMMY_SHA = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210";

    @Test
    void testIsSupportedJvmArg() {
        assertTrue(RestartCoordinator.isSupportedJvmArg("-Djava.awt.headless=false"));
        assertTrue(RestartCoordinator.isSupportedJvmArg("-Djava.awt.headless=FALSE"));
        assertFalse(RestartCoordinator.isSupportedJvmArg("-Djava.awt.headless=true"));

        assertTrue(RestartCoordinator.isSupportedJvmArg("-Dfile.encoding=UTF-8"));
        assertTrue(RestartCoordinator.isSupportedJvmArg("-dfile.encoding=UTF-8"));
        assertFalse(RestartCoordinator.isSupportedJvmArg("-Dfile.encoding=utf-8")); // must be exact uppercase UTF-8
        assertFalse(RestartCoordinator.isSupportedJvmArg("-Dfile.encoding=ISO-8859-1"));

        assertFalse(RestartCoordinator.isSupportedJvmArg("-Xmx1024m"));
        assertFalse(RestartCoordinator.isSupportedJvmArg(null));
        assertFalse(RestartCoordinator.isSupportedJvmArg(""));
    }

    @Test
    void testGetUnsupportedRestartReasonIsSideEffectFree(@TempDir Path tempDir) {
        Path installRoot = tempDir.resolve("install_root");
        RestartCoordinator coordinator = new RestartCoordinator(installRoot);

        // Capability query must be side-effect-free
        String reason = coordinator.getUnsupportedRestartReason();
        assertNotNull(reason);
        assertFalse(coordinator.isAutomatedRestartSupported());

        // Absolutely no directories or files created by query
        assertFalse(Files.exists(installRoot), "Query must not create installRoot");
        assertFalse(Files.exists(installRoot.resolve(".p2p-update")), "Query must not create .p2p-update");
    }

    @Test
    void testPreflightScratchProbeThrowsWhenUnsupported(@TempDir Path tempDir) {
        Path installRoot = tempDir.resolve("non_canonical");
        RestartCoordinator coordinator = new RestartCoordinator(installRoot);
        assertThrows(IllegalStateException.class, coordinator::preflightScratchProbe);
    }

    @Test
    void testUpdateJournalValidationRejectsMalformedRecords(@TempDir Path tempDir) {
        String installId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        String absConfig = tempDir.resolve("peer.properties").toAbsolutePath().normalize().toString();
        String absCwd = tempDir.toAbsolutePath().normalize().toString();
        String absJava = tempDir.resolve("bin/java").toAbsolutePath().normalize().toString();

        // 1. Invalid schema
        assertThrows(IllegalArgumentException.class, () -> new UpdateJournal(
                2, BuildInfo.DEFAULT_REPOSITORY, installId, UpdateJournal.Operation.UPDATE,
                VALID_HEX_64, UpdateJournal.Phase.PREPARED, 1234L, now, null, null, null, null,
                absConfig, absCwd, absJava, "", "1.0.0", "1.0.1", DUMMY_SHA, DUMMY_SHA, ""
        ));

        // 2. Short / non-hex transactionId
        assertThrows(IllegalArgumentException.class, () -> new UpdateJournal(
                1, BuildInfo.DEFAULT_REPOSITORY, installId, UpdateJournal.Operation.UPDATE,
                "short-nonce", UpdateJournal.Phase.PREPARED, 1234L, now, null, null, null, null,
                absConfig, absCwd, absJava, "", "1.0.0", "1.0.1", DUMMY_SHA, DUMMY_SHA, ""
        ));

        // 3. Parent pid <= 0
        assertThrows(IllegalArgumentException.class, () -> new UpdateJournal(
                1, BuildInfo.DEFAULT_REPOSITORY, installId, UpdateJournal.Operation.UPDATE,
                VALID_HEX_64, UpdateJournal.Phase.PREPARED, 0L, now, null, null, null, null,
                absConfig, absCwd, absJava, "", "1.0.0", "1.0.1", DUMMY_SHA, DUMMY_SHA, ""
        ));

        // 4. Missing parentStartInstant
        assertThrows(NullPointerException.class, () -> new UpdateJournal(
                1, BuildInfo.DEFAULT_REPOSITORY, installId, UpdateJournal.Operation.UPDATE,
                VALID_HEX_64, UpdateJournal.Phase.PREPARED, 1234L, null, null, null, null, null,
                absConfig, absCwd, absJava, "", "1.0.0", "1.0.1", DUMMY_SHA, DUMMY_SHA, ""
        ));

        // 5. Unpaired helper identity (pid present, instant null)
        assertThrows(IllegalArgumentException.class, () -> new UpdateJournal(
                1, BuildInfo.DEFAULT_REPOSITORY, installId, UpdateJournal.Operation.UPDATE,
                VALID_HEX_64, UpdateJournal.Phase.PREPARED, 1234L, now, 5678L, null, null, null,
                absConfig, absCwd, absJava, "", "1.0.0", "1.0.1", DUMMY_SHA, DUMMY_SHA, ""
        ));

        // 6. SWITCHING phase requires helper identity
        assertThrows(IllegalArgumentException.class, () -> new UpdateJournal(
                1, BuildInfo.DEFAULT_REPOSITORY, installId, UpdateJournal.Operation.UPDATE,
                VALID_HEX_64, UpdateJournal.Phase.SWITCHING, 1234L, now, null, null, null, null,
                absConfig, absCwd, absJava, "", "1.0.0", "1.0.1", DUMMY_SHA, DUMMY_SHA, ""
        ));

        // 7. Non-absolute path rejected
        assertThrows(IllegalArgumentException.class, () -> new UpdateJournal(
                1, BuildInfo.DEFAULT_REPOSITORY, installId, UpdateJournal.Operation.UPDATE,
                VALID_HEX_64, UpdateJournal.Phase.PREPARED, 1234L, now, null, null, null, null,
                "relative/path.properties", absCwd, absJava, "", "1.0.0", "1.0.1", DUMMY_SHA, DUMMY_SHA, ""
        ));

        // 8. UPDATE requires targetVersion > currentVersion
        assertThrows(IllegalArgumentException.class, () -> new UpdateJournal(
                1, BuildInfo.DEFAULT_REPOSITORY, installId, UpdateJournal.Operation.UPDATE,
                VALID_HEX_64, UpdateJournal.Phase.PREPARED, 1234L, now, null, null, null, null,
                absConfig, absCwd, absJava, "", "1.0.1", "1.0.0", DUMMY_SHA, DUMMY_SHA, ""
        ));

        // 9. RESTART requires empty candidateSha256 and targetVersion == currentVersion
        assertThrows(IllegalArgumentException.class, () -> new UpdateJournal(
                1, BuildInfo.DEFAULT_REPOSITORY, installId, UpdateJournal.Operation.RESTART,
                VALID_HEX_64, UpdateJournal.Phase.PREPARED, 1234L, now, null, null, null, null,
                absConfig, absCwd, absJava, "", "1.0.0", "1.0.0", DUMMY_SHA, DUMMY_SHA, ""
        ));

        // 10. Valid UPDATE journal succeeds
        assertDoesNotThrow(() -> new UpdateJournal(
                1, BuildInfo.DEFAULT_REPOSITORY, installId, UpdateJournal.Operation.UPDATE,
                VALID_HEX_64, UpdateJournal.Phase.PREPARED, 1234L, now, null, null, null, null,
                absConfig, absCwd, absJava, "-Dfile.encoding=UTF-8", "1.0.0", "1.0.1", DUMMY_SHA, DUMMY_SHA, ""
        ));

        // 11. Valid RESTART journal succeeds
        assertDoesNotThrow(() -> new UpdateJournal(
                1, BuildInfo.DEFAULT_REPOSITORY, installId, UpdateJournal.Operation.RESTART,
                VALID_HEX_64, UpdateJournal.Phase.PREPARED, 1234L, now, null, null, null, null,
                absConfig, absCwd, absJava, "-Djava.awt.headless=false", "1.0.0", "1.0.0", DUMMY_SHA, "", ""
        ));
    }

    @Test
    void testGetOrCreateInstallIdCreatesAndValidatesOwner(@TempDir Path tempDir) throws IOException {
        Path installRoot = tempDir.resolve("canonical_root");
        Files.createDirectories(installRoot);
        Path updateDir = installRoot.resolve(".p2p-update");

        // 1. Initial creation creates owner.properties with UUID
        String installId1 = UpdateJournal.getOrCreateInstallId(updateDir, BuildInfo.DEFAULT_REPOSITORY, installRoot);
        assertNotNull(installId1);
        assertTrue(Files.exists(updateDir.resolve("owner.properties")));

        // 2. Second call validates existing owner and returns matching installId
        String installId2 = UpdateJournal.getOrCreateInstallId(updateDir, BuildInfo.DEFAULT_REPOSITORY, installRoot);
        assertEquals(installId1, installId2);

        // 3. Mismatched repository in owner.properties throws SecurityException
        Path foreignDir = tempDir.resolve("foreign_root");
        Files.createDirectories(foreignDir);
        Path foreignUpdate = foreignDir.resolve(".p2p-update");
        Files.createDirectories(foreignUpdate);
        Files.writeString(foreignUpdate.resolve("owner.properties"), "schema=1\nrepository=attacker/repo\ninstallRoot="
                + foreignDir.toRealPath() + "\ninstallId=" + UUID.randomUUID() + "\n");

        assertThrows(SecurityException.class, () ->
                UpdateJournal.getOrCreateInstallId(foreignUpdate, BuildInfo.DEFAULT_REPOSITORY, foreignDir));
    }

    @Test
    void testRefusesAdoptionOfMarkerlessDirectory(@TempDir Path tempDir) throws IOException {
        Path installRoot = tempDir.resolve("root");
        Files.createDirectories(installRoot);
        Path updateDir = installRoot.resolve(".p2p-update");

        // Create markerless empty directory
        Files.createDirectories(updateDir);

        // Never adopt existing markerless directory
        assertThrows(SecurityException.class, () ->
                UpdateJournal.getOrCreateInstallId(updateDir, BuildInfo.DEFAULT_REPOSITORY, installRoot));
    }

    @Test
    void testUpdateLocksRefusesNonExistentDirectory(@TempDir Path tempDir) {
        Path nonExistent = tempDir.resolve("no_such_dir");
        assertThrows(IOException.class, () -> UpdateLocks.acquireSharedRuntimeLock(nonExistent));
        assertThrows(IOException.class, () -> UpdateLocks.tryAcquireOperationLock(nonExistent));
        assertFalse(UpdateLocks.isOperationActive(nonExistent));
    }

    @Test
    void testBeginRestartAndRestoreReadyLifecycle(@TempDir Path tempDir) throws Exception {
        Path installRoot = tempDir.resolve("app");
        Files.createDirectories(installRoot);

        ReleaseClient client = new ReleaseClient(new BuildInfo(
                "1.0.0", ClientVersion.parse("1.0.0"), BuildInfo.DEFAULT_REPOSITORY, "", null, 1, false
        ));

        try (UpdateService service = new UpdateService(client, installRoot, null)) {
            // Initially NOT_CHECKED -> beginRestart throws
            assertThrows(IllegalStateException.class, service::beginRestart);

            // Inject READY_TO_RESTART state
            ReleaseManifest manifest = new ReleaseManifest(
                    1, BuildInfo.DEFAULT_REPOSITORY, ClientVersion.parse("1.0.1"), "peer-app.jar",
                    1024, DUMMY_SHA, 21, 1,
                    new ReleaseManifest.BundleInfo("p2p-client-1.0.1.zip", 2048, DUMMY_SHA)
            );
            ReleaseClient.ValidatedCandidate candidate = new ReleaseClient.ValidatedCandidate(
                    ClientVersion.parse("1.0.1"), manifest, new byte[10], new byte[64],
                    "https://github.com", 1024, "https://github.com", 2048
            );

            java.lang.reflect.Field snapField = UpdateService.class.getDeclaredField("currentSnapshot");
            snapField.setAccessible(true);
            snapField.set(service, new UpdateService.UpdateSnapshot(
                    UpdateService.UpdateState.READY_TO_RESTART,
                    ClientVersion.parse("1.0.0"),
                    ClientVersion.parse("1.0.1"),
                    1024, 1024,
                    "Ready",
                    "notes",
                    candidate,
                    installRoot.resolve(".p2p-update/candidate.jar")
            ));

            // First call claims READY_TO_RESTART -> transitions to RESTARTING
            UpdateService.UpdateSnapshot prev = service.beginRestart();
            assertNotNull(prev);
            assertEquals(UpdateService.UpdateState.RESTARTING, service.snapshot().state());

            // Second call throws because state is now RESTARTING
            assertThrows(IllegalStateException.class, service::beginRestart);

            // restoreReady returns state to READY_TO_RESTART
            service.restoreReady(prev, "Refused by active transfers");
            assertEquals(UpdateService.UpdateState.READY_TO_RESTART, service.snapshot().state());
            assertEquals("Refused by active transfers", service.snapshot().detail());
        }
    }

    @Test
    void testCheckForUpdatesDisabledDuringReadyAndRestarting(@TempDir Path tempDir) throws Exception {
        Path installRoot = tempDir.resolve("app");
        Files.createDirectories(installRoot);

        ReleaseClient client = new ReleaseClient(new BuildInfo(
                "1.0.0", ClientVersion.parse("1.0.0"), BuildInfo.DEFAULT_REPOSITORY, "", null, 1, false
        ));

        try (UpdateService service = new UpdateService(client, installRoot, null)) {
            java.lang.reflect.Field snapField = UpdateService.class.getDeclaredField("currentSnapshot");
            snapField.setAccessible(true);
            snapField.set(service, new UpdateService.UpdateSnapshot(
                    UpdateService.UpdateState.READY_TO_RESTART,
                    ClientVersion.parse("1.0.0"),
                    ClientVersion.parse("1.0.1"),
                    1024, 1024,
                    "Ready",
                    "notes",
                    null,
                    null
            ));

            service.checkForUpdates();
            // Must remain READY_TO_RESTART, never transition to CHECKING
            assertEquals(UpdateService.UpdateState.READY_TO_RESTART, service.snapshot().state());
        }
    }

    @Test
    void testProcessLineReaderEnforcesDeadlineToAvoidHang() throws Exception {
        String javaBin = System.getProperty("java.home") + "/bin/java";
        Process proc = new ProcessBuilder(javaBin, "-cp", ".", "java.lang.Object").start();
        try {
            RestartCoordinator.ProcessLineReader reader = new RestartCoordinator.ProcessLineReader(proc);
            proc.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
            assertThrows(IOException.class, () ->
                    reader.readLineWithDeadline(java.time.Duration.ofMillis(200)));
        } finally {
            proc.destroyForcibly();
        }
    }

    @Test
    void testRestartFailureCarriesRuntimeStoppedState() {
        RestartCoordinator.RestartFailure rfTrue = new RestartCoordinator.RestartFailure("Close failed", true, null);
        assertTrue(rfTrue.runtimeStopped());

        RestartCoordinator.RestartFailure rfFalse = new RestartCoordinator.RestartFailure("Spawn failed", false, null);
        assertFalse(rfFalse.runtimeStopped());
    }
}
