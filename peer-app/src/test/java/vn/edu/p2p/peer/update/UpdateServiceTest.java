package vn.edu.p2p.peer.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateServiceTest {

    private static BuildInfo dummyBuildInfo() {
        return new BuildInfo(
                "1.0.0",
                ClientVersion.parse("1.0.0"),
                "Megumi2910/p2p-file-sharing",
                "",
                null,
                1,
                true
        );
    }

    private static ReleaseClient.ValidatedCandidate dummyCandidate() {
        ReleaseManifest manifest = new ReleaseManifest(
                1, "Megumi2910/p2p-file-sharing", ClientVersion.parse("1.0.1"), "peer-app.jar",
                1024, "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                21, 1, new ReleaseManifest.BundleInfo("p2p-client-1.0.1.zip", 2048, "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
        );
        return new ReleaseClient.ValidatedCandidate(
                ClientVersion.parse("1.0.1"), manifest, new byte[10], new byte[64],
                "https://github.com/Megumi2910/p2p-file-sharing/releases/download/v1.0.1/peer-app.jar", 1024,
                "https://github.com/Megumi2910/p2p-file-sharing/releases/download/v1.0.1/p2p-client-1.0.1.zip", 2048
        );
    }

    @Test
    void testUnsupportedInstallRootFailsDownload() throws Exception {
        ReleaseClient client = new ReleaseClient(dummyBuildInfo());
        // Pass null installRoot -> unsupported
        try (UpdateService service = new UpdateService(client, null, null)) {
            assertFalse(service.snapshot().state() == UpdateService.UpdateState.DOWNLOADING);

            // Attempt download
            service.downloadAvailableUpdate();
            assertEquals(UpdateService.UpdateState.FAILED, service.snapshot().state());
            assertTrue(service.snapshot().detail().contains("unsupported"));
        }
    }

    @Test
    void testOperationLockContentionPreventsDownload(@TempDir Path tempDir) throws Exception {
        Path installRoot = tempDir.resolve("app");
        Files.createDirectories(installRoot);
        Path updateDir = installRoot.resolve(".p2p-update");
        UpdateJournal.getOrCreateInstallId(updateDir, BuildInfo.DEFAULT_REPOSITORY, installRoot);

        ReleaseClient.HttpTransport transport = (uri, method, headers, timeout, cancel) ->
                new ReleaseClient.TransportResponse(200, Map.of(), new ByteArrayInputStream(new byte[1024]));

        ReleaseClient client = new ReleaseClient(dummyBuildInfo(), transport, URI.create(ReleaseClient.GITHUB_API_LATEST));

        try (UpdateService service = new UpdateService(client, installRoot, null)) {
            // Hold operation lock from outside
            try (UpdateLocks.FileLockHandle externalOpLock = UpdateLocks.tryAcquireOperationLock(updateDir)) {
                assertNotNull(externalOpLock, "Should acquire operation lock");

                // Inject UPDATE_AVAILABLE state into service snapshot via reflection or listener
                java.lang.reflect.Field snapField = UpdateService.class.getDeclaredField("currentSnapshot");
                snapField.setAccessible(true);
                snapField.set(service, new UpdateService.UpdateSnapshot(
                        UpdateService.UpdateState.UPDATE_AVAILABLE,
                        ClientVersion.parse("1.0.0"),
                        ClientVersion.parse("1.0.1"),
                        0,
                        1024,
                        "Update available",
                        "notes",
                        dummyCandidate(),
                        null
                ));

                CountDownLatch failedLatch = new CountDownLatch(1);
                service.addListener(snapshot -> {
                    if (snapshot.state() == UpdateService.UpdateState.FAILED) {
                        failedLatch.countDown();
                    }
                });

                service.downloadAvailableUpdate();

                assertTrue(failedLatch.await(5, TimeUnit.SECONDS), "State should transition to FAILED due to lock contention");
                assertTrue(service.snapshot().detail().contains("Another update operation is currently in progress"));
                // Candidate file must not be created
                assertFalse(Files.exists(updateDir.resolve("candidate.jar")));
                assertFalse(Files.exists(updateDir.resolve("candidate.jar.part")));
            }
        }
    }

    @Test
    void testCloseCancelsActiveOperations(@TempDir Path tempDir) throws Exception {
        Path installRoot = tempDir.resolve("app");
        Files.createDirectories(installRoot);

        CountDownLatch enteredCheckLatch = new CountDownLatch(1);
        CountDownLatch observedCheckCancelLatch = new CountDownLatch(1);

        ReleaseClient.HttpTransport transport = (uri, method, headers, timeout, cancel) -> {
            enteredCheckLatch.countDown();
            while (cancel != null && !cancel.isCancelled()) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException ie) {
                    break;
                }
            }
            if (cancel != null && cancel.isCancelled()) {
                observedCheckCancelLatch.countDown();
            }
            throw new java.io.IOException("Request cancelled by close");
        };

        ReleaseClient client = new ReleaseClient(dummyBuildInfo(), transport, URI.create(ReleaseClient.GITHUB_API_LATEST));
        UpdateService service = new UpdateService(client, installRoot, null);

        service.checkForUpdates();
        assertTrue(enteredCheckLatch.await(5, TimeUnit.SECONDS), "Transport must enter request");

        // Close service
        service.close();

        assertTrue(observedCheckCancelLatch.await(5, TimeUnit.SECONDS), "Close must trigger cancellation on active check");
    }
}
