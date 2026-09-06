package vn.edu.p2p.peer.transfer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.p2p.common.model.SearchResult;
import vn.edu.p2p.peer.PeerRuntime;
import vn.edu.p2p.peer.config.AppConfig;
import vn.edu.p2p.peer.util.HashUtil;
import vn.edu.p2p.tracker.TrackerServer;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClassroomDemoScenarioIT {

    @TempDir
    Path tempDir;

    private TrackerServer tracker;
    private int trackerPort;
    private Thread trackerThread;

    private PeerRuntime runtimeAlice;
    private PeerRuntime runtimeBob;
    private PeerRuntime runtimeCharlie;

    private Path aliceShared;
    private Path bobShared;
    private Path charlieDownloads;

    private static final int C = 1024;

    @BeforeEach
    void setUp() throws Exception {
        aliceShared = tempDir.resolve("alice_shared");
        bobShared = tempDir.resolve("bob_shared");
        charlieDownloads = tempDir.resolve("charlie_downloads");
        Files.createDirectories(aliceShared);
        Files.createDirectories(bobShared);
        Files.createDirectories(charlieDownloads);

        tracker = new TrackerServer(0);
        trackerThread = new Thread(() -> {
            try {
                tracker.start();
            } catch (IOException ignored) {}
        });
        trackerThread.setDaemon(true);
        trackerThread.start();

        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            try {
                trackerPort = tracker.localPort();
                if (trackerPort > 0) break;
            } catch (Exception e) {
                Thread.sleep(20);
            }
        }
        assertTrue(trackerPort > 0);

        int alicePort;
        try (ServerSocket p = new ServerSocket(0)) { alicePort = p.getLocalPort(); }
        int bobPort;
        try (ServerSocket p = new ServerSocket(0)) { bobPort = p.getLocalPort(); }
        int charliePort;
        try (ServerSocket p = new ServerSocket(0)) { charliePort = p.getLocalPort(); }

        AppConfig configAlice = new AppConfig(
                "alice", "Alice", alicePort, "127.0.0.1", trackerPort, tempDir.resolve("alice_down"), aliceShared, C, true,
                15000, 15000, 120000, 135000, 300000, 4
        );
        runtimeAlice = new PeerRuntime(configAlice);

        AppConfig configBob = new AppConfig(
                "bob", "Bob", bobPort, "127.0.0.1", trackerPort, tempDir.resolve("bob_down"), bobShared, C, true,
                15000, 15000, 120000, 135000, 300000, 4
        );
        runtimeBob = new PeerRuntime(configBob);

        AppConfig configCharlie = new AppConfig(
                "charlie", "Charlie", charliePort, "127.0.0.1", trackerPort, charlieDownloads, tempDir.resolve("charlie_shared"), C, true,
                15000, 15000, 120000, 135000, 300000, 4
        );
        runtimeCharlie = new PeerRuntime(configCharlie);
    }

    @AfterEach
    void tearDown() {
        if (runtimeCharlie != null) {
            try { runtimeCharlie.close(); } catch (Exception ignored) {}
        }
        if (runtimeAlice != null) {
            try { runtimeAlice.close(); } catch (Exception ignored) {}
        }
        if (runtimeBob != null) {
            try { runtimeBob.close(); } catch (Exception ignored) {}
        }
        if (tracker != null) {
            try { tracker.close(); } catch (IOException ignored) {}
        }
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void testCompleteClassroomFailoverScenario() throws Exception {
        // Step 1: Create 12-chunk demo presentation file (12 * 1024 bytes = 12,288 bytes)
        int size = 12 * C;
        byte[] presentationBytes = new byte[size];
        new Random(555).nextBytes(presentationBytes);

        // Put in both Alice and Bob shared folders
        Files.write(aliceShared.resolve("demo_presentation.pdf"), presentationBytes);
        Files.write(bobShared.resolve("demo_presentation.pdf"), presentationBytes);
        String expectedHash = HashUtil.sha256(aliceShared.resolve("demo_presentation.pdf"));

        // Step 2: Start Alice and Bob -> index and publish to tracker
        runtimeAlice.start();
        runtimeBob.start();
        runtimeCharlie.start();

        // Step 3: Charlie searches the catalogue for "demo"
        List<SearchResult> results = runtimeCharlie.searchFiles("demo");
        assertEquals(1, results.size());
        SearchResult demoResult = results.get(0);
        assertEquals("demo_presentation.pdf", demoResult.file().fileName());
        assertEquals(12, demoResult.file().totalChunks());
        assertEquals(2, demoResult.providers().size(), "Both Alice and Bob must be registered as providers");

        // Step 4: Charlie starts multi-source download
        CountDownLatch downloadFinished = new CountDownLatch(1);
        AtomicReference<TransferStatus> charlieStatus = new AtomicReference<>();
        AtomicInteger maxSourcesSeen = new AtomicInteger(0);
        AtomicBoolean failoverTriggered = new AtomicBoolean(false);

        runtimeCharlie.transferManager().setListener(u -> {
            if (u.direction() == TransferDirection.RECEIVE) {
                if (u.activeSourceCount() > maxSourcesSeen.get()) {
                    maxSourcesSeen.set(u.activeSourceCount());
                }
                // Trigger mid-stream failure on Alice after some chunks are transferred
                if (u.status() == TransferStatus.TRANSFERRING && u.bytesTransferred() >= 3000 && !failoverTriggered.get()) {
                    failoverTriggered.set(true);
                    try {
                        runtimeAlice.close(); // Abruptly drop Alice!
                    } catch (Exception ignored) {}
                }
                if (u.status() == TransferStatus.COMPLETED || u.status() == TransferStatus.FAILED) {
                    charlieStatus.set(u.status());
                    downloadFinished.countDown();
                }
            }
        });

        runtimeCharlie.downloadFile(demoResult);

        // Step 5: Wait for Charlie to complete download despite Alice dropping
        assertTrue(downloadFinished.await(12, TimeUnit.SECONDS), "Charlie download timed out");
        assertEquals(TransferStatus.COMPLETED, charlieStatus.get(), "Charlie should complete download via Bob");
        assertTrue(maxSourcesSeen.get() >= 2, "Charlie should have observed parallel sources");

        // Step 6: Verify file on disk
        Path published = charlieDownloads.resolve("demo_presentation.pdf");
        assertTrue(Files.exists(published));
        assertEquals(size, Files.size(published));
        assertEquals(expectedHash, HashUtil.sha256(published));
        assertArrayEquals(presentationBytes, Files.readAllBytes(published));

        // Step 7: Verify tracker catalogue has pruned Alice
        Thread.sleep(100);
        List<SearchResult> postFailoverSearch = runtimeCharlie.searchFiles("demo");
        assertEquals(1, postFailoverSearch.size());
        assertEquals(1, postFailoverSearch.get(0).providers().size(), "Only Bob should remain after Alice dropped");
        assertEquals("Bob", postFailoverSearch.get(0).providers().get(0).displayName());
    }
}
