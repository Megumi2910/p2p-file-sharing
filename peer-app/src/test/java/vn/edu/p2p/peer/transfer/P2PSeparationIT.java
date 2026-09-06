package vn.edu.p2p.peer.transfer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.p2p.common.model.PeerInfo;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class P2PSeparationIT {

    @TempDir
    Path tempDir;

    private TrackerServer tracker;
    private int trackerPort;
    private Thread trackerThread;

    private PeerRuntime runtimeAlice;
    private PeerRuntime runtimeBob;
    private Path aliceDir;
    private Path bobDir;

    @BeforeEach
    void setUp() throws Exception {
        aliceDir = tempDir.resolve("alice");
        bobDir = tempDir.resolve("bob");
        Files.createDirectories(aliceDir);
        Files.createDirectories(bobDir);

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
            } catch (IllegalStateException e) {
                Thread.sleep(20);
            }
        }
        assertTrue(trackerPort > 0);

        int alicePort;
        try (ServerSocket probe = new ServerSocket(0)) { alicePort = probe.getLocalPort(); }
        int bobPort;
        try (ServerSocket probe = new ServerSocket(0)) { bobPort = probe.getLocalPort(); }

        AppConfig configAlice = new AppConfig(
                "alice", "Alice", alicePort, "127.0.0.1", trackerPort, aliceDir, 4096, false,
                15000, 15000, 120000, 135000, 300000, 4
        );
        runtimeAlice = new PeerRuntime(configAlice);

        AppConfig configBob = new AppConfig(
                "bob", "Bob", bobPort, "127.0.0.1", trackerPort, bobDir, 4096, true,
                15000, 15000, 120000, 135000, 300000, 4
        );
        runtimeBob = new PeerRuntime(configBob);

        runtimeBob.start();
        runtimeAlice.start();
    }

    @AfterEach
    void tearDown() {
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
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void testDirectTransferCompletesAfterTrackerStops() throws Exception {
        // Alice discovers Bob via tracker
        List<PeerInfo> peers = runtimeAlice.listPeers();
        assertEquals(1, peers.size());
        PeerInfo targetBob = peers.get(0);
        assertEquals("bob", targetBob.peerId());

        // Create 20 KiB file (5 chunks of 4096)
        Path file = aliceDir.resolve("direct_transfer.dat");
        byte[] data = new byte[20 * 1024];
        new Random(99).nextBytes(data);
        Files.write(file, data);
        String expectedSha256 = HashUtil.sha256(file);

        CountDownLatch firstChunkReceived = new CountDownLatch(1);
        CountDownLatch aliceDone = new CountDownLatch(1);
        CountDownLatch bobDone = new CountDownLatch(1);
        AtomicReference<TransferStatus> aliceFinal = new AtomicReference<>();
        AtomicReference<TransferStatus> bobFinal = new AtomicReference<>();

        runtimeBob.transferManager().setListener(update -> {
            if (update.direction() == TransferDirection.RECEIVE) {
                if (update.status() == TransferStatus.TRANSFERRING && update.bytesTransferred() > 4000) {
                    firstChunkReceived.countDown();
                }
                if (update.status() == TransferStatus.COMPLETED || update.status() == TransferStatus.FAILED) {
                    bobFinal.set(update.status());
                    bobDone.countDown();
                }
            }
        });

        runtimeAlice.transferManager().setListener(update -> {
            if (update.direction() == TransferDirection.SEND &&
                    (update.status() == TransferStatus.COMPLETED || update.status() == TransferStatus.FAILED)) {
                aliceFinal.set(update.status());
                aliceDone.countDown();
            }
        });

        // Start direct transfer from Alice to Bob
        runtimeAlice.sendFile(targetBob, file);

        // Wait until at least one chunk is transferred, then STOP TRACKER
        assertTrue(firstChunkReceived.await(5, TimeUnit.SECONDS));
        tracker.close();

        // Direct transfer must still complete successfully
        assertTrue(aliceDone.await(5, TimeUnit.SECONDS), "Alice did not finish transfer after tracker stopped");
        assertTrue(bobDone.await(5, TimeUnit.SECONDS), "Bob did not finish transfer after tracker stopped");

        assertEquals(TransferStatus.COMPLETED, aliceFinal.get());
        assertEquals(TransferStatus.COMPLETED, bobFinal.get());

        Path received = bobDir.resolve("direct_transfer.dat");
        assertTrue(Files.exists(received));
        assertEquals(expectedSha256, HashUtil.sha256(received));

        // Subsequent tracker refresh on Alice must fail cleanly (tracker is down)
        assertThrows(IOException.class, runtimeAlice::listPeers);
    }
}
