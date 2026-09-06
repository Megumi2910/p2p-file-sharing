package vn.edu.p2p.peer.transfer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.p2p.common.model.PeerInfo;
import vn.edu.p2p.common.protocol.Frame;
import vn.edu.p2p.common.protocol.FrameIO;
import vn.edu.p2p.common.protocol.MessageType;
import vn.edu.p2p.peer.config.AppConfig;
import vn.edu.p2p.peer.network.PeerServer;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptResponseTimeoutIT {

    @TempDir
    Path tempDir;

    private Path senderDir;
    private Path receiverDir;
    private TransferManager senderManager;
    private TransferManager receiverManager;
    private PeerServer receiverServer;
    private int receiverPort;

    @BeforeEach
    void setUp() throws IOException {
        senderDir = tempDir.resolve("sender");
        receiverDir = tempDir.resolve("receiver");
        Files.createDirectories(senderDir);
        Files.createDirectories(receiverDir);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (senderManager != null) senderManager.close();
        if (receiverServer != null) {
            try { receiverServer.close(); } catch (IOException ignored) {}
        }
        if (receiverManager != null) receiverManager.close();
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void testPromptExpiryYieldsRejectionBeforeSenderTimeout() throws Exception {
        // Prompt budget 1000 ms, Sender offer response budget 5000 ms
        AppConfig receiverConfig = new AppConfig(
                "receiver", "Receiver", 6001, "127.0.0.1", 5000, receiverDir, 4096, false,
                15000, 15000, 1000, 5000, 300000, 4
        );
        receiverManager = new TransferManager(receiverConfig);

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

        // Prompt that leaves decision unanswered for the full 1000 ms prompt budget, then attempts late click
        receiverManager.setIncomingFilePrompt((meta, sender, timeoutMillis) -> {
            CompletableFuture<Boolean> future = new CompletableFuture<>();
            AtomicBoolean resolved = new AtomicBoolean(false);

            // Timer expires after timeoutMillis (1000ms)
            scheduler.schedule(() -> {
                if (resolved.compareAndSet(false, true)) {
                    future.complete(false);
                }
            }, timeoutMillis, TimeUnit.MILLISECONDS);

            // Simulated late click 500ms after expiry
            scheduler.schedule(() -> {
                if (resolved.compareAndSet(false, true)) {
                    future.complete(true);
                }
            }, timeoutMillis + 500, TimeUnit.MILLISECONDS);

            try {
                return future.get(timeoutMillis + 100, TimeUnit.MILLISECONDS);
            } catch (Exception ex) {
                return false;
            }
        });

        receiverServer = new PeerServer(0, receiverManager);
        receiverServer.start();
        receiverPort = receiverServer.localPort();

        AppConfig senderConfig = new AppConfig(
                "sender", "Sender", 6002, "127.0.0.1", 5000, senderDir, 4096, false,
                15000, 15000, 1000, 5000, 300000, 4
        );
        senderManager = new TransferManager(senderConfig);

        Path file = senderDir.resolve("prompt_timeout.txt");
        Files.writeString(file, "Data for prompt timeout test");

        CountDownLatch senderLatch = new CountDownLatch(1);
        AtomicReference<TransferStatus> senderFinal = new AtomicReference<>();
        long startNanos = System.nanoTime();

        senderManager.setListener(update -> {
            if (update.direction() == TransferDirection.SEND &&
                    (update.status() == TransferStatus.REJECTED || update.status() == TransferStatus.FAILED)) {
                senderFinal.set(update.status());
                senderLatch.countDown();
            }
        });

        PeerInfo target = new PeerInfo("receiver", "Receiver", "127.0.0.1", receiverPort);
        senderManager.sendFile(target, file);

        assertTrue(senderLatch.await(4, TimeUnit.SECONDS), "Sender did not resolve in prompt budget window");
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

        // Sender resolves REJECTED because receiver rejected on prompt timeout
        assertEquals(TransferStatus.REJECTED, senderFinal.get());
        // Must resolve well before sender's 5000ms response timeout
        assertTrue(elapsedMillis < 4000, "Should resolve around prompt timeout (1000ms), elapsed: " + elapsedMillis);

        // Ensure no file or partial was created (late click cannot create storage)
        assertTrue(Files.notExists(receiverDir.resolve("prompt_timeout.txt")));
        try (var s = Files.newDirectoryStream(receiverDir, "*.part")) {
            assertFalse(s.iterator().hasNext(), "No partial file should exist");
        }

        scheduler.shutdownNow();
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testSilentDecisionEndpointTimesOutAsFailed() throws Exception {
        try (ServerSocket fakeReceiver = new ServerSocket(0)) {
            int fakePort = fakeReceiver.getLocalPort();
            AppConfig senderConfig = new AppConfig(
                    "sender", "Sender", 6002, "127.0.0.1", 5000, senderDir, 4096, false,
                    15000, 15000, 500, 2000, 300000, 4
            );
            senderManager = new TransferManager(senderConfig);

            Path file = senderDir.resolve("silent.txt");
            Files.writeString(file, "Data for silent decision test");

            CountDownLatch senderLatch = new CountDownLatch(1);
            AtomicReference<TransferStatus> senderFinal = new AtomicReference<>();
            AtomicReference<String> senderMsg = new AtomicReference<>();

            senderManager.setListener(update -> {
                if (update.direction() == TransferDirection.SEND &&
                        (update.status() == TransferStatus.COMPLETED || update.status() == TransferStatus.FAILED)) {
                    senderFinal.set(update.status());
                    senderMsg.set(update.message());
                    senderLatch.countDown();
                }
            });

            Thread fakeThread = new Thread(() -> {
                try (Socket client = fakeReceiver.accept()) {
                    Frame offer = FrameIO.read(client.getInputStream(), 0);
                    // Stay silent past 2000 ms
                    Thread.sleep(3500);
                } catch (Exception ignored) {}
            });
            fakeThread.setDaemon(true);
            fakeThread.start();

            PeerInfo target = new PeerInfo("fake", "Fake", "127.0.0.1", fakePort);
            senderManager.sendFile(target, file);

            assertTrue(senderLatch.await(5, TimeUnit.SECONDS));
            assertEquals(TransferStatus.FAILED, senderFinal.get());
            assertNotNull(senderMsg.get());
            assertTrue(senderMsg.get().contains("timeout") || senderMsg.get().contains("SocketTimeoutException"));
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testBoundedTransferAdmission() throws Exception {
        // Concurrency = 1
        AppConfig config = new AppConfig(
                "peer", "Peer", 6001, "127.0.0.1", 5000, receiverDir, 4096, true,
                15000, 15000, 120000, 135000, 300000, 1
        );
        TransferManager manager = new TransferManager(config);

        try {
            CountDownLatch firstStarted = new CountDownLatch(1);
            CountDownLatch releaseFirst = new CountDownLatch(1);

            try (ServerSocket fakeServer = new ServerSocket(0)) {
                int fakePort = fakeServer.getLocalPort();
                Path file1 = senderDir.resolve("file1.bin");
                Files.write(file1, new byte[100]);

                Thread serverThread = new Thread(() -> {
                    try (Socket s = fakeServer.accept()) {
                        firstStarted.countDown();
                        releaseFirst.await(5, TimeUnit.SECONDS);
                    } catch (Exception ignored) {}
                });
                serverThread.setDaemon(true);
                serverThread.start();

                PeerInfo target = new PeerInfo("p", "P", "127.0.0.1", fakePort);
                manager.sendFile(target, file1);

                assertTrue(firstStarted.await(5, TimeUnit.SECONDS));

                // Second sendFile must immediately throw RejectedExecutionException due to concurrency cap 1
                Path file2 = senderDir.resolve("file2.bin");
                Files.write(file2, new byte[100]);

                assertThrows(RejectedExecutionException.class, () -> manager.sendFile(target, file2));

                // Release first transfer
                releaseFirst.countDown();
            }
        } finally {
            manager.close();
        }
    }
}
