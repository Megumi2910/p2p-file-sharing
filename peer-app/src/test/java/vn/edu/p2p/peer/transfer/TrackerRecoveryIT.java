package vn.edu.p2p.peer.transfer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.p2p.common.model.PeerInfo;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrackerRecoveryIT {

    @TempDir
    Path tempDir;

    private TrackerServer tracker;
    private int trackerPort;
    private Thread trackerThread;

    private PeerRuntime runtimeAlice;
    private PeerRuntime runtimeBob;
    private Path aliceShared;
    private Path aliceDownloads;
    private Path bobShared;
    private Path bobDownloads;

    @BeforeEach
    void setUp() throws Exception {
        aliceShared = tempDir.resolve("alice_shared");
        aliceDownloads = tempDir.resolve("alice_downloads");
        bobShared = tempDir.resolve("bob_shared");
        bobDownloads = tempDir.resolve("bob_downloads");
        Files.createDirectories(aliceShared);
        Files.createDirectories(aliceDownloads);
        Files.createDirectories(bobShared);
        Files.createDirectories(bobDownloads);

        startTracker();

        int alicePort;
        try (ServerSocket probe = new ServerSocket(0)) { alicePort = probe.getLocalPort(); }
        int bobPort;
        try (ServerSocket probe = new ServerSocket(0)) { bobPort = probe.getLocalPort(); }

        AppConfig configAlice = new AppConfig(
                "alice", "Alice", alicePort, "127.0.0.1", trackerPort, aliceDownloads, aliceShared, 4096, true,
                15000, 15000, 120000, 135000, 300000, 4
        );
        runtimeAlice = new PeerRuntime(configAlice);

        AppConfig configBob = new AppConfig(
                "bob", "Bob", bobPort, "127.0.0.1", trackerPort, bobDownloads, bobShared, 4096, true,
                15000, 15000, 120000, 135000, 300000, 4
        );
        runtimeBob = new PeerRuntime(configBob);

        runtimeAlice.start();
        runtimeBob.start();
    }

    private void startTracker() throws Exception {
        if (trackerPort == 0) {
            try (ServerSocket probe = new ServerSocket(0)) {
                trackerPort = probe.getLocalPort();
            }
        }
        tracker = new TrackerServer(trackerPort);
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
                if (tracker.localPort() > 0) break;
            } catch (Exception e) {
                Thread.sleep(20);
            }
        }
    }

    private void stopTracker() throws Exception {
        if (tracker != null) {
            tracker.close();
            tracker = null;
        }
        if (trackerThread != null) {
            trackerThread.join(2000);
            trackerThread = null;
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        if (runtimeAlice != null) {
            try { runtimeAlice.close(); } catch (Exception ignored) {}
        }
        if (runtimeBob != null) {
            try { runtimeBob.close(); } catch (Exception ignored) {}
        }
        stopTracker();
    }

    @Test
    @Timeout(value = 35, unit = TimeUnit.SECONDS)
    void testOutageRetainsCachedPeersAndDirectTransfersSucceed() throws Exception {
        // 1. Initial discovery: Alice discovers Bob
        long deadline = System.currentTimeMillis() + 10_000;
        PeerInfo bobInfo = null;
        while (System.currentTimeMillis() < deadline) {
            var peers = runtimeAlice.listPeers();
            if (!peers.isEmpty()) {
                bobInfo = peers.get(0);
                break;
            }
            Thread.sleep(200);
        }
        assertNotNull(bobInfo, "Alice should discover Bob via tracker");
        assertEquals("bob", bobInfo.peerId());

        // 2. Stop tracker
        stopTracker();

        // 3. Wait for Alice to detect tracker outage
        deadline = System.currentTimeMillis() + 12_000;
        while (System.currentTimeMillis() < deadline) {
            if (runtimeAlice.snapshot().trackerState() == PeerRuntime.TrackerState.OFFLINE) {
                break;
            }
            Thread.sleep(200);
        }
        assertEquals(PeerRuntime.TrackerState.OFFLINE, runtimeAlice.snapshot().trackerState());

        // 4. Cached peers survive outage
        PeerRuntime.RuntimeSnapshot snap = runtimeAlice.snapshot();
        assertEquals(1, snap.peers().size());
        assertEquals("bob", snap.peers().get(0).peerId());
        assertTrue(snap.trackerDetail().contains("unavailable") || snap.trackerDetail().contains("failed"));

        // Subsequent listPeers() call throws IOException (does not pretend stale data is fresh)
        assertThrows(IOException.class, runtimeAlice::listPeers);

        // 5. Direct transfer during tracker outage works
        Path sendFile = aliceShared.resolve("direct_outage_file.bin");
        Files.writeString(sendFile, "direct bytes transferred while tracker is dead");
        String fileHash = HashUtil.sha256(sendFile);
        CountDownLatch completeLatch = new CountDownLatch(1);
        runtimeBob.transferManager().setIncomingFilePrompt((meta, sender, timeout) -> true);
        runtimeBob.transferManager().setListener(update -> {
            if (update.status() == TransferStatus.COMPLETED) {
                completeLatch.countDown();
            }
        });

        runtimeAlice.sendFile(bobInfo, sendFile);
        assertTrue(completeLatch.await(10, TimeUnit.SECONDS), "Direct transfer should complete despite tracker outage");
        Path received = bobDownloads.resolve("direct_outage_file.bin");
        assertTrue(Files.exists(received), "Received file must exist");
        assertEquals(fileHash, HashUtil.sha256(received), "Received bytes must match SHA-256");

    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void testSearchFailsOfflineAndStaleEndpointFailsBounded() throws Exception {
        // Stop tracker
        stopTracker();

        // Outage: searchFiles must fail explicitly
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (runtimeAlice.snapshot().trackerState() == PeerRuntime.TrackerState.OFFLINE) {
                break;
            }
            Thread.sleep(100);
        }
        assertThrows(IOException.class, () -> runtimeAlice.searchFiles("anything"));
        // Stop Bob so its endpoint is dead
        runtimeBob.close();
        runtimeBob = null;

        Path sendFile = aliceShared.resolve("fail_file.txt");
        Files.writeString(sendFile, "fail content");
        PeerInfo deadBob = new PeerInfo("bob", "Bob", "127.0.0.1", 62999);

        AtomicReference<String> failureReason = new AtomicReference<>();
        CountDownLatch failLatch = new CountDownLatch(1);
        runtimeAlice.transferManager().setListener(update -> {
            if (update.status() == TransferStatus.FAILED) {
                failureReason.set(update.message());
                failLatch.countDown();
            }
        });

        runtimeAlice.sendFile(deadBob, sendFile);
        assertTrue(failLatch.await(10, TimeUnit.SECONDS), "Sending to dead peer should terminate with failure within bounded time");
        assertNotNull(failureReason.get());
    }

    @Test
    @Timeout(value = 35, unit = TimeUnit.SECONDS)
    void testAutomaticRecoveryAndRepublishLatestRevision() throws Exception {
        // Stop tracker
        stopTracker();

        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (runtimeAlice.snapshot().trackerState() == PeerRuntime.TrackerState.OFFLINE) {
                break;
            }
            Thread.sleep(100);
        }
        // Alice adds new file during outage and calls rescan
        Path offlineDoc = aliceShared.resolve("offline_created.txt");
        Files.writeString(offlineDoc, "created while offline");
        runtimeAlice.refreshSharedFiles();

        // Wait for newly created file to complete stability observation and transition to PENDING_PUBLISH
        deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (runtimeAlice.snapshot().sharingState() == PeerRuntime.SharingState.PENDING_PUBLISH) {
                break;
            }
            Thread.sleep(100);
        }
        assertEquals(PeerRuntime.SharingState.PENDING_PUBLISH, runtimeAlice.snapshot().sharingState());
        // Restart tracker on the same port
        startTracker();

        // Alice should automatically reconnect and publish latest revision within 25s
        deadline = System.currentTimeMillis() + 25_000;
        while (System.currentTimeMillis() < deadline) {
            PeerRuntime.RuntimeSnapshot snap = runtimeAlice.snapshot();
            if (snap.trackerState() == PeerRuntime.TrackerState.CONNECTED && snap.sharingState() == PeerRuntime.SharingState.SYNCED) {
                break;
            }
            Thread.sleep(300);
        }

        PeerRuntime.RuntimeSnapshot finalSnap = runtimeAlice.snapshot();
        assertEquals(PeerRuntime.TrackerState.CONNECTED, finalSnap.trackerState());
        assertEquals(PeerRuntime.SharingState.SYNCED, finalSnap.sharingState());

        // Search on tracker should now find Alice's file
        List<SearchResult> results = runtimeAlice.searchFiles("offline_created");
        assertEquals(1, results.size());
        assertEquals("offline_created.txt", results.get(0).file().fileName());
    }
}
