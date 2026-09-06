package vn.edu.p2p.peer.transfer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.p2p.common.model.PeerInfo;
import vn.edu.p2p.common.protocol.Frame;
import vn.edu.p2p.common.protocol.FrameIO;
import vn.edu.p2p.common.protocol.MessageType;
import vn.edu.p2p.peer.PeerRuntime;
import vn.edu.p2p.peer.config.AppConfig;
import vn.edu.p2p.peer.network.PeerServer;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LifecycleShutdownIT {

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void testStartupFailureRollbackAllowsImmediatePortRebind(@TempDir Path tempDir) throws Exception {
        int peerPort;
        try (ServerSocket probe = new ServerSocket(0)) {
            peerPort = probe.getLocalPort();
        }

        // Tracker port is a closed/unavailable port
        int unavailableTrackerPort;
        try (ServerSocket probe = new ServerSocket(0)) {
            unavailableTrackerPort = probe.getLocalPort();
        }

        AppConfig config = new AppConfig(
                "peer-1", "Peer 1", peerPort, "127.0.0.1", unavailableTrackerPort, tempDir, 4096, true,
                15000, 15000, 120000, 135000, 300000, 4
        );
        PeerRuntime runtime = new PeerRuntime(config);

        // start() must fail because tracker is unavailable
        assertThrows(Exception.class, runtime::start);

        // Rollback must have cleanly closed PeerServer listener: port can be rebound immediately
        try (ServerSocket rebind = new ServerSocket(peerPort)) {
            assertTrue(rebind.isBound(), "Peer port should be unbound after startup failure rollback");
        }
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void testCloseDuringActiveTransferTerminatesAsCancelled(@TempDir Path tempDir) throws Exception {
        Path senderDir = tempDir.resolve("sender");
        Path receiverDir = tempDir.resolve("receiver");
        Files.createDirectories(senderDir);
        Files.createDirectories(receiverDir);

        AppConfig receiverConfig = new AppConfig(
                "receiver", "Receiver", 6001, "127.0.0.1", 5000, receiverDir, 1024, true,
                15000, 15000, 120000, 135000, 300000, 4
        );
        TransferManager receiverManager = new TransferManager(receiverConfig);
        PeerServer receiverServer = new PeerServer(0, receiverManager);
        receiverServer.start();
        int receiverPort = receiverServer.localPort();

        AppConfig senderConfig = new AppConfig(
                "sender", "Sender", 6002, "127.0.0.1", 5000, senderDir, 1024, false,
                15000, 15000, 120000, 135000, 300000, 4
        );
        TransferManager senderManager = new TransferManager(senderConfig);

        Path largeFile = senderDir.resolve("cancel_test.bin");
        Files.write(largeFile, new byte[50 * 1024]); // 50 chunks

        CountDownLatch cancelledLatch = new CountDownLatch(1);
        AtomicReference<TransferStatus> senderFinalStatus = new AtomicReference<>();

        senderManager.setListener(update -> {
            if (update.direction() == TransferDirection.SEND) {
                if (update.status() == TransferStatus.TRANSFERRING && update.bytesTransferred() > 5000) {
                    // Trigger shutdown during active transfer
                    try {
                        senderManager.shutdown();
                    } catch (IOException ignored) {}
                }
                if (update.status() == TransferStatus.CANCELLED || update.status() == TransferStatus.FAILED) {
                    senderFinalStatus.set(update.status());
                    cancelledLatch.countDown();
                }
            }
        });

        PeerInfo target = new PeerInfo("receiver", "Receiver", "127.0.0.1", receiverPort);
        senderManager.sendFile(target, largeFile);

        assertTrue(cancelledLatch.await(5, TimeUnit.SECONDS));
        assertEquals(TransferStatus.CANCELLED, senderFinalStatus.get());

        receiverServer.close();
        receiverManager.close();
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testAwaitTerminationWithExpiredDeadlineReturnsImmediately(@TempDir Path tempDir) throws Exception {
        AppConfig config = new AppConfig(
                "peer", "Peer", 6001, "127.0.0.1", 5000, tempDir, 4096, true,
                15000, 15000, 120000, 135000, 300000, 4
        );
        TransferManager manager = new TransferManager(config);
        PeerServer server = new PeerServer(0, manager);
        server.start();

        long expiredDeadline = System.nanoTime() - TimeUnit.SECONDS.toNanos(1);
        long startNanos = System.nanoTime();

        // Both awaitTermination calls must return immediately without waiting
        server.awaitTermination(expiredDeadline);
        manager.awaitTermination(expiredDeadline);

        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        assertTrue(elapsedMillis < 500, "Expired deadline must return immediately, took " + elapsedMillis + " ms");

        server.close();
        manager.close();
    }
}
