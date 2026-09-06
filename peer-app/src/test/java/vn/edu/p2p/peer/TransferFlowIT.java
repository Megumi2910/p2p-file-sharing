package vn.edu.p2p.peer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.p2p.common.model.PeerInfo;
import vn.edu.p2p.peer.config.AppConfig;
import vn.edu.p2p.peer.network.PeerServer;
import vn.edu.p2p.peer.transfer.TransferDirection;
import vn.edu.p2p.peer.transfer.TransferManager;
import vn.edu.p2p.peer.transfer.TransferStatus;
import vn.edu.p2p.peer.transfer.TransferUpdate;
import vn.edu.p2p.peer.util.HashUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransferFlowIT {

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

        AppConfig receiverConfig = new AppConfig(
                "receiver-peer", "Receiver", 6001, "127.0.0.1", 5000, receiverDir, 4096, true,
                15000, 15000, 120000, 135000, 300000, 4
        );
        receiverManager = new TransferManager(receiverConfig);
        receiverServer = new PeerServer(0, receiverManager);
        receiverServer.start();
        receiverPort = receiverServer.localPort();
        assertTrue(receiverPort > 0);

        AppConfig senderConfig = new AppConfig(
                "sender-peer", "Sender", 6002, "127.0.0.1", 5000, senderDir, 4096, false,
                15000, 15000, 120000, 135000, 300000, 4
        );
        senderManager = new TransferManager(senderConfig);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (senderManager != null) {
            senderManager.close();
        }
        if (receiverServer != null) {
            receiverServer.close();
        }
        if (receiverManager != null) {
            receiverManager.close();
        }
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void testEmptyFileTransfer() throws Exception {
        Path emptyFile = senderDir.resolve("empty.txt");
        Files.createFile(emptyFile);

        CountDownLatch senderComplete = new CountDownLatch(1);
        CountDownLatch receiverComplete = new CountDownLatch(1);
        AtomicReference<TransferStatus> senderFinal = new AtomicReference<>();
        AtomicReference<TransferStatus> receiverFinal = new AtomicReference<>();

        senderManager.setListener(update -> {
            if (update.direction() == TransferDirection.SEND &&
                    (update.status() == TransferStatus.COMPLETED || update.status() == TransferStatus.FAILED)) {
                senderFinal.set(update.status());
                senderComplete.countDown();
            }
        });

        receiverManager.setListener(update -> {
            if (update.direction() == TransferDirection.RECEIVE &&
                    (update.status() == TransferStatus.COMPLETED || update.status() == TransferStatus.FAILED)) {
                receiverFinal.set(update.status());
                receiverComplete.countDown();
            }
        });

        PeerInfo target = new PeerInfo("receiver-peer", "Receiver", "127.0.0.1", receiverPort);
        senderManager.sendFile(target, emptyFile);

        assertTrue(senderComplete.await(10, TimeUnit.SECONDS), "Sender did not complete");
        assertTrue(receiverComplete.await(10, TimeUnit.SECONDS), "Receiver did not complete");

        assertEquals(TransferStatus.COMPLETED, senderFinal.get());
        assertEquals(TransferStatus.COMPLETED, receiverFinal.get());

        Path receivedFile = receiverDir.resolve("empty.txt");
        assertTrue(Files.exists(receivedFile), "Received empty file does not exist");
        assertEquals(0, Files.size(receivedFile));
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void testMultiChunkDirectTransfer() throws Exception {
        // Create 10,000 bytes file (chunk size is 4096, so 2 full chunks + 1 partial chunk)
        Path sourceFile = senderDir.resolve("multi_chunk.bin");
        byte[] data = new byte[10000];
        new Random(42).nextBytes(data);
        Files.write(sourceFile, data);
        String expectedSha256 = HashUtil.sha256(sourceFile);

        CountDownLatch senderComplete = new CountDownLatch(1);
        CountDownLatch receiverComplete = new CountDownLatch(1);
        AtomicReference<TransferStatus> senderFinal = new AtomicReference<>();
        AtomicReference<TransferStatus> receiverFinal = new AtomicReference<>();

        senderManager.setListener(update -> {
            if (update.direction() == TransferDirection.SEND &&
                    (update.status() == TransferStatus.COMPLETED || update.status() == TransferStatus.FAILED)) {
                senderFinal.set(update.status());
                senderComplete.countDown();
            }
        });

        receiverManager.setListener(update -> {
            if (update.direction() == TransferDirection.RECEIVE &&
                    (update.status() == TransferStatus.COMPLETED || update.status() == TransferStatus.FAILED)) {
                receiverFinal.set(update.status());
                receiverComplete.countDown();
            }
        });

        PeerInfo target = new PeerInfo("receiver-peer", "Receiver", "127.0.0.1", receiverPort);
        senderManager.sendFile(target, sourceFile);

        assertTrue(senderComplete.await(10, TimeUnit.SECONDS), "Sender did not complete");
        assertTrue(receiverComplete.await(10, TimeUnit.SECONDS), "Receiver did not complete");

        assertEquals(TransferStatus.COMPLETED, senderFinal.get());
        assertEquals(TransferStatus.COMPLETED, receiverFinal.get());

        Path receivedFile = receiverDir.resolve("multi_chunk.bin");
        assertTrue(Files.exists(receivedFile), "Received file must exist");
        assertEquals(10000, Files.size(receivedFile));
        assertEquals(expectedSha256, HashUtil.sha256(receivedFile));
        assertArrayEquals(data, Files.readAllBytes(receivedFile));
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void testExplicitRejection() throws Exception {
        Path file = senderDir.resolve("declined.txt");
        Files.writeString(file, "content to be declined");

        // Set prompt to reject
        receiverManager.setIncomingFilePrompt((meta, sender, timeout) -> false);

        // Also change receiver to not auto-accept
        AppConfig noAutoConfig = new AppConfig(
                "receiver-peer", "Receiver", receiverPort, "127.0.0.1", 5000, receiverDir, 4096, false,
                15000, 15000, 120000, 135000, 300000, 4
        );
        // Create new manager with autoAccept = false
        receiverServer.close();
        receiverManager.close();

        receiverManager = new TransferManager(noAutoConfig);
        receiverManager.setIncomingFilePrompt((meta, sender, timeout) -> false);
        receiverServer = new PeerServer(0, receiverManager);
        receiverServer.start();
        receiverPort = receiverServer.localPort();

        CountDownLatch senderLatch = new CountDownLatch(1);
        AtomicReference<TransferStatus> senderFinal = new AtomicReference<>();

        senderManager.setListener(update -> {
            if (update.direction() == TransferDirection.SEND &&
                    (update.status() == TransferStatus.REJECTED || update.status() == TransferStatus.FAILED)) {
                senderFinal.set(update.status());
                senderLatch.countDown();
            }
        });

        PeerInfo target = new PeerInfo("receiver-peer", "Receiver", "127.0.0.1", receiverPort);
        senderManager.sendFile(target, file);

        assertTrue(senderLatch.await(10, TimeUnit.SECONDS), "Sender did not receive rejection response");
        assertEquals(TransferStatus.REJECTED, senderFinal.get());

        Path destFile = receiverDir.resolve("declined.txt");
        assertTrue(Files.notExists(destFile), "Declined file must not exist in receiver directory");
    }
}
